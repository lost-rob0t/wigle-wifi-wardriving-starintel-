package net.wigle.wigleandroid.warstar;

import android.database.Cursor;
import net.wigle.wigleandroid.db.DatabaseHelper;
import org.json.JSONObject;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.BufferedWriter;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.UUID;

/** NDJSON from Star Language generated 0.10.1 core.star document fields. */
public final class StarIntelExport {
    private static final String SCHEMA = "0.10.1";
    private static final String DATASET = "warstar";
    private StarIntelExport() {}

    public static int write(DatabaseHelper db, OutputStream output, boolean latestRun, String installation) throws Exception {
        long min = 0, max = Long.MAX_VALUE;
        if (latestRun) {
            try (Cursor run = db.query(
                    "SELECT MIN(time),MAX(time) FROM route WHERE run_id=(SELECT MAX(run_id) FROM route)",
                    new String[]{})) {
                if (!run.moveToFirst() || run.isNull(0)) return 0;
                min = run.getLong(0); max = run.getLong(1);
            }
        }
        int count = 0;
        BufferedWriter writer = new BufferedWriter(new OutputStreamWriter(output, StandardCharsets.UTF_8));
        try (Cursor rows = db.query(
                "SELECT _id,bssid,level,lat,lon,altitude,accuracy,time,mfgrid,external FROM location " +
                "WHERE lat BETWEEN -90 AND 90 AND lon BETWEEN -180 AND 180 " +
                (latestRun ? "AND external = 0 " : "") + "ORDER BY _id",
                new String[]{})) {
            while (rows.moveToNext()) {
                long time = rows.getLong(7);
                if (time < min || time > max) continue;
                String address = rows.getString(1);
                if (address == null || address.isEmpty()) continue;
                try (Cursor typeRow = db.query("SELECT type FROM network WHERE bssid=?", new String[]{address})) {
                    if (!typeRow.moveToFirst()) continue;
                    String radio = typeRow.getString(0);
                    if (!("W".equals(radio) || "B".equals(radio) || "E".equals(radio))) continue;
                }
                String id = "star:warstar:" + installation + ":" + rows.getLong(0);
                String geoId = id + ":geo";
                JSONObject point = base(geoId, "geo-point", time);
                point.put("sourceKinds", new org.json.JSONArray().put(rows.getInt(9) == 1 ? "import" : "sensor"));
                point.put("geometryType", "point");
                point.put("latitude", decimal(rows.getDouble(3)));
                point.put("longitude", decimal(rows.getDouble(4)));
                if (!rows.isNull(6)) point.put("accuracyMeters", decimal(rows.getDouble(6)));
                writer.write(point.toString()); writer.newLine();

                JSONObject device;
                try (Cursor network = db.query(
                        "SELECT type,ssid,frequency,capabilities FROM network WHERE bssid=?",
                        new String[]{address})) {
                    String type = "unknown", name = "", auth = "";
                    int frequency = 0;
                    if (network.moveToFirst()) {
                        type = network.getString(0);
                        name = network.getString(1);
                        frequency = network.getInt(2);
                        auth = network.getString(3);
                    }
                    boolean wifi = "W".equalsIgnoreCase(type) || "WIFI".equalsIgnoreCase(type);
                    device = base(id, wifi ? "wireless-network" : "network-device", time);
                    device.put("sourceKinds", new org.json.JSONArray().put(rows.getInt(9) == 1 ? "import" : "sensor"));
                    if (wifi) {
                        device.put("bssid", address);
                        if (name != null) device.put("ssid", name);
                        device.put("security", security(auth));
                        if (frequency > 0) device.put("frequencyMhz", frequency);
                        device.put("signalDbm", rows.getInt(2));
                        JSONObject ref = new JSONObject();
                        ref.put("schema", "org.starintel/core@1/geo-point");
                        ref.put("id", geoId);
                        device.put("location", ref);
                        device.put("observations", 1);
                    } else {
                        device.put("deviceId", address);
                        device.put("deviceClass", "unknown");
                        JSONObject extra = new JSONObject();
                        extra.put("radio", type == null ? "unknown" : type);
                        extra.put("geoPointId", geoId);
                        extra.put("signalDbm", rows.getInt(2));
                        device.put("extensions", extra);
                    }
                }
                writer.write(device.toString()); writer.newLine();
                count++;
            }
        }
        writer.flush();
        return count;
    }

    private static JSONObject base(String id, String dtype, long millis) throws Exception {
        JSONObject doc = new JSONObject();
        doc.put("id", id);
        doc.put("dataset", DATASET);
        doc.put("dtype", dtype);
        doc.put("schemaVersion", SCHEMA);
        doc.put("observedAt", millis / 1000);
        doc.put("collector", "star:v1:collector:wireless");
        doc.put("sourceKinds", new org.json.JSONArray().put("sensor"));
        return doc;
    }

    private static String decimal(double value) {
        return String.format(Locale.ROOT, "%.8f", value);
    }

    private static String security(String capabilities) {
        if (capabilities == null) return "unknown";
        String value = capabilities.toUpperCase(Locale.ROOT);
        if (value.contains("WPA3")) return "wpa3-psk";
        if (value.contains("WPA2")) return "wpa2-psk";
        if (value.contains("WPA")) return "wpa-psk";
        if (value.contains("WEP")) return "wep";
        if (value.contains("ESS")) return "open";
        return "unknown";
    }
}
