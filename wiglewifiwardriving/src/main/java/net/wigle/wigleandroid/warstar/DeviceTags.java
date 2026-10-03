package net.wigle.wigleandroid.warstar;

import android.content.Context;
import android.content.SharedPreferences;
import com.google.gson.Gson;
import net.wigle.wigleandroid.MainActivity;
import net.wigle.wigleandroid.util.PreferenceKeys;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;

/** Local, typed annotation overlay. The scanner database remains untouched. */
public final class DeviceTags {
    private static final String PREF_TAGS = "warstar.device_tags";
    private static final String PREF_OWNED_ALERTS = "warstar.owned_alerts";
    public static final String[] ICONS = {"★", "◆", "●", "⚑", "⌖", "⚡", "⬟", "✚"};
    public static final int[] COLORS = {
        0xFFBD304B, 0xFFE07035, 0xFFD2A328, 0xFF358C61,
        0xFF277FAA, 0xFF625EBD, 0xFFAD4B9C, 0xFF596576
    };

    private DeviceTags() {}

    private static SharedPreferences prefs(Context context) {
        return context.getSharedPreferences(PreferenceKeys.SHARED_PREFS, Context.MODE_PRIVATE);
    }

    public static String key(String type, String address) {
        return type.toUpperCase(Locale.ROOT) + ":" + address.toUpperCase(Locale.ROOT);
    }

    private static JSONObject all(Context context) {
        try {
            return new JSONObject(prefs(context).getString(PREF_TAGS, "{}"));
        } catch (JSONException ignored) {
            return new JSONObject();
        }
    }

    public static JSONArray forDevice(Context context, String type, String address) {
        return all(context).optJSONArray(key(type, address)) == null
                ? new JSONArray() : all(context).optJSONArray(key(type, address));
    }

    public static synchronized void add(Context context, String type, String address,
                                         String label, String icon, int color, boolean alert,
                                         String targetDocumentId) throws JSONException {
        if (address == null || address.trim().isEmpty() || label == null || label.trim().isEmpty()) {
            throw new IllegalArgumentException("Address and label are required");
        }
        JSONObject all = all(context);
        String key = key(type, address);
        JSONArray tags = all.optJSONArray(key);
        if (tags == null) tags = new JSONArray();
        JSONObject tag = new JSONObject();
        tag.put("label", label.trim());
        tag.put("icon", icon);
        tag.put("color", color);
        tag.put("alert", alert);
        tag.put("target_document_id", targetDocumentId == null ? "" : targetDocumentId.trim());
        tags.put(tag);
        all.put(key, tags);
        prefs(context).edit().putString(PREF_TAGS, all.toString()).apply();
        reconcileAlerts(context, all);
    }

    public static synchronized void remove(Context context, String type, String address, int index)
            throws JSONException {
        JSONObject all = all(context);
        String key = key(type, address);
        JSONArray tags = all.optJSONArray(key);
        if (tags == null || index < 0 || index >= tags.length()) return;
        JSONArray remaining = new JSONArray();
        for (int i = 0; i < tags.length(); i++) {
            if (i != index) remaining.put(tags.get(i));
        }
        if (remaining.length() == 0) all.remove(key);
        else all.put(key, remaining);
        prefs(context).edit().putString(PREF_TAGS, all.toString()).apply();
        reconcileAlerts(context, all);
    }

    /** Preserve hand configured alerts while replacing only addresses managed by tags. */
    private static void reconcileAlerts(Context context, JSONObject all) throws JSONException {
        SharedPreferences prefs = prefs(context);
        Gson gson = new Gson();
        String[] existing = gson.fromJson(prefs.getString(PreferenceKeys.PREF_ALERT_ADDRS, "[]"), String[].class);
        String[] owned = gson.fromJson(prefs.getString(PREF_OWNED_ALERTS, "[]"), String[].class);
        Set<String> addresses = new LinkedHashSet<>();
        Set<String> previous = new LinkedHashSet<>();
        if (owned != null) java.util.Collections.addAll(previous, owned);
        if (existing != null) for (String address : existing) {
            if (!previous.contains(address)) addresses.add(address);
        }
        Set<String> next = new LinkedHashSet<>();
        java.util.Iterator<String> keys = all.keys();
        while (keys.hasNext()) {
            String key = keys.next();
            JSONArray tags = all.optJSONArray(key);
            if (tags == null || !(key.startsWith("WIFI:") || key.startsWith("BT:") || key.startsWith("BLE:"))) continue;
            for (int i = 0; i < tags.length(); i++) {
                if (tags.optJSONObject(i) != null && tags.optJSONObject(i).optBoolean("alert")) {
                    next.add(key.substring(key.indexOf(':') + 1));
                    break;
                }
            }
        }
        addresses.addAll(next);
        prefs.edit()
                .putString(PreferenceKeys.PREF_ALERT_ADDRS, gson.toJson(new ArrayList<>(addresses)))
                .putString(PREF_OWNED_ALERTS, gson.toJson(new ArrayList<>(next))).apply();
        MainActivity activity = MainActivity.getMainActivity();
        if (activity != null) activity.updateAddressFilter(PreferenceKeys.PREF_ALERT_ADDRS);
    }
}
