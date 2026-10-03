package net.wigle.wigleandroid.warstar;

import android.content.Context;
import android.content.SharedPreferences;
import android.database.Cursor;
import net.wigle.wigleandroid.MainActivity;
import net.wigle.wigleandroid.db.DatabaseHelper;
import net.wigle.wigleandroid.util.PreferenceKeys;
import okhttp3.MediaType;
import okhttp3.OkHttpClient;
import okhttp3.Request;
import okhttp3.RequestBody;
import okhttp3.Response;
import org.json.JSONArray;
import org.json.JSONObject;
import java.io.IOException;
import java.io.InputStream;
import java.io.PushbackInputStream;
import java.io.InputStreamReader;
import java.io.BufferedReader;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;
import java.util.Iterator;
import java.util.Map;
import java.util.HashMap;
import java.util.zip.GZIPInputStream;
import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVParser;
import org.apache.commons.csv.CSVRecord;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

/** Session-only bearer credentials. Never persist a token in prefs or logs. */
public final class WarStarClient {
    private static final String PREF_URL = "warstar.server_url";
    private static final String PREF_CURSOR = "warstar.upload_cursor";
    private static final String PREF_DEVICE = "warstar.installation_id";
    private static final String PREF_TASKS = "warstar.target_documents";
    private static volatile String token;
    private final OkHttpClient http = new OkHttpClient.Builder()
            .connectTimeout(12, TimeUnit.SECONDS).readTimeout(30, TimeUnit.SECONDS).build();
    private final SharedPreferences prefs;

    public WarStarClient(Context context) {
        prefs = context.getSharedPreferences(PreferenceKeys.SHARED_PREFS, Context.MODE_PRIVATE);
    }

    public String url() { return prefs.getString(PREF_URL, ""); }
    public String installationId() {
        String id = prefs.getString(PREF_DEVICE, null);
        if (id != null) return id;
        id = UUID.randomUUID().toString();
        prefs.edit().putString(PREF_DEVICE, id).apply();
        return id;
    }
    public boolean signedIn() { return token != null; }
    public void signOut() { token = null; }
    public void signIn(String url, String bearer) throws IOException {
        String base = checkedBase(url);
        if (bearer.trim().isEmpty()) throw new IOException("Bearer token is required");
        // Authenticate before retaining credentials or server address.
        request(base + "/auth/context", bearer.trim(), null);
        prefs.edit().putString(PREF_URL, base).apply();
        token = bearer.trim();
    }

    public void signInPassword(String url, String username, String password) throws Exception {
        String base = checkedBase(url);
        JSONObject credentials = new JSONObject();
        credentials.put("username", username);
        credentials.put("password", password);
        String response = request(base + "/auth/login", null, credentials.toString());
        String issued = new JSONObject(response).getString("api_key");
        signIn(base, issued);
    }

    private static String checkedBase(String url) throws IOException {
        String base = url.trim().replaceAll("/+$", "");
        if (!base.startsWith("https://") && !base.matches("http://(localhost|127\\.0\\.0\\.1)(:[0-9]+)?")) {
            throw new IOException("Use HTTPS (HTTP is allowed only for localhost)");
        }
        return base;
    }

    private String request(String url, String bearer, String json) throws IOException {
        Request.Builder builder = new Request.Builder().url(url);
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (json != null) builder.post(RequestBody.create(
                json, MediaType.parse("application/json; charset=utf-8")));
        try (Response response = http.newCall(builder.build()).execute()) {
            if (!response.isSuccessful()) throw new IOException("Server returned HTTP " + response.code());
            if (response.body() == null) throw new IOException("Empty server response");
            return response.body().string();
        }
    }

    public JSONArray syncTargets() throws Exception {
        if (token == null) throw new IOException("Sign in first");
        String body = request(url() + "/targets/star:v1:collector:wireless", token, null);
        JSONArray targets = new JSONArray(body);
        prefs.edit().putString(PREF_TASKS, targets.toString()).apply();
        return targets;
    }

    public JSONArray cachedTargets() {
        try { return new JSONArray(prefs.getString(PREF_TASKS, "[]")); }
        catch (Exception exception) { return new JSONArray(); }
    }

    /** Stream a WiGLE CSV or CSV.GZ export in bounded, retry-safe batches. */
    public int importWigleCsv(InputStream source, String importKey) throws Exception {
        if (token == null) throw new IOException("Sign in first");
        String importId = UUID.nameUUIDFromBytes(importKey.getBytes(StandardCharsets.UTF_8)).toString();
        PushbackInputStream input = new PushbackInputStream(source, 2);
        byte[] magic = new byte[2];
        int read = input.read(magic);
        if (read > 0) input.unread(magic, 0, read);
        InputStream decoded = read == 2 && (magic[0] & 0xff) == 0x1f
                && (magic[1] & 0xff) == 0x8b ? new GZIPInputStream(input) : input;
        int total = 0;
        try (CSVParser parser = CSVFormat.DEFAULT.parse(
                new BufferedReader(new InputStreamReader(decoded, StandardCharsets.UTF_8)))) {
            Iterator<CSVRecord> rows = parser.iterator();
            if (!rows.hasNext()) return 0;
            CSVRecord first = rows.next();
            if (!rows.hasNext()) return 0;
            CSVRecord header = first.size() > 0 && first.get(0).startsWith("WigleWifi-")
                    ? rows.next() : first;
            Map<String, Integer> columns = new HashMap<>();
            for (int i = 0; i < header.size(); i++) columns.put(header.get(i).trim(), i);
            if (!columns.containsKey("MAC") || !columns.containsKey("CurrentLatitude")
                    || !columns.containsKey("CurrentLongitude")) {
                throw new IOException("Unsupported WiGLE CSV header");
            }
            SimpleDateFormat date = new SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.ROOT);
            date.setTimeZone(TimeZone.getTimeZone("UTC"));
            JSONArray batch = new JSONArray();
            long ordinal = 0;
            while (rows.hasNext() && !Thread.currentThread().isInterrupted()) {
                CSVRecord row = rows.next();
                ordinal++;
                try {
                    String address = field(row, columns, "MAC");
                    if (address.isEmpty()) continue;
                    JSONObject item = new JSONObject();
                    item.put("id", ordinal);
                    item.put("address", address);
                    item.put("name", field(row, columns, "SSID"));
                    item.put("radio", field(row, columns, "Type"));
                    item.put("security", field(row, columns, "AuthMode"));
                    item.put("level", Integer.parseInt(field(row, columns, "RSSI")));
                    item.put("latitude", Double.parseDouble(field(row, columns, "CurrentLatitude")));
                    item.put("longitude", Double.parseDouble(field(row, columns, "CurrentLongitude")));
                    String seen = field(row, columns, "FirstSeen");
                    Date parsed = date.parse(seen);
                    if (parsed == null) continue;
                    item.put("time", parsed.getTime());
                    item.put("source", "wigle-csv");
                    batch.put(item);
                    if (batch.length() == 100) {
                        sendBatch(importId, batch);
                        total += batch.length();
                        batch = new JSONArray();
                    }
                } catch (IllegalArgumentException | java.text.ParseException ignored) {
                    // A malformed WiGLE row does not invalidate the rest of the export.
                }
            }
            if (batch.length() > 0) {
                sendBatch(importId, batch);
                total += batch.length();
            }
        }
        return total;
    }

    private static String field(CSVRecord row, Map<String, Integer> columns, String name) {
        Integer index = columns.get(name);
        return index != null && index < row.size() ? row.get(index).trim() : "";
    }

    private void sendBatch(String deviceId, JSONArray observations) throws Exception {
        JSONObject batch = new JSONObject();
        batch.put("device_id", deviceId);
        batch.put("observations", observations);
        request(url() + "/warstar/observations", token, batch.toString());
    }

    /** Upload one bounded batch, advancing the cursor only after a successful response. */
    public int uploadNextBatch() throws Exception {
        if (token == null) throw new IOException("Sign in first");
        MainActivity.State state = MainActivity.getStaticState();
        if (state == null || state.dbHelper == null) throw new IOException("Scanner database is unavailable");
        DatabaseHelper db = state.dbHelper;
        long cursorId = prefs.getLong(PREF_CURSOR, 0);
        long lastId = cursorId;
        JSONArray observations = new JSONArray();
        try (Cursor rows = db.locationIterator(cursorId)) {
            while (rows.moveToNext() && observations.length() < 100) {
                long rowId = rows.getLong(0);
                JSONObject item = new JSONObject();
                item.put("id", rowId);
                item.put("address", rows.getString(1));
                item.put("level", rows.getInt(2));
                item.put("latitude", rows.getDouble(3));
                item.put("longitude", rows.getDouble(4));
                item.put("altitude", rows.getDouble(5));
                item.put("accuracy", rows.getDouble(6));
                item.put("time", rows.getLong(7));
                item.put("manufacturer_id", rows.getInt(8));
                try (Cursor network = db.query("SELECT type, ssid, frequency FROM network WHERE bssid = ?",
                        new String[]{rows.getString(1)})) {
                    if (network.moveToFirst()) {
                        item.put("radio", network.getString(0));
                        item.put("name", network.getString(1));
                        item.put("frequency", network.getInt(2));
                    }
                }
                observations.put(item);
                lastId = rowId;
            }
        }
        if (observations.length() == 0) return 0;
        String deviceId = installationId();
        sendBatch(deviceId, observations);
        prefs.edit().putLong(PREF_CURSOR, lastId).apply();
        return observations.length();
    }
}
