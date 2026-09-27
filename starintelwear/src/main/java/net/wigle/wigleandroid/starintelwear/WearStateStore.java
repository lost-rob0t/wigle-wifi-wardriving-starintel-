package net.wigle.wigleandroid.starintelwear;

import android.content.Context;
import android.content.SharedPreferences;

import org.json.JSONObject;

/** Local durable copy of phone-published StarIntel live state. */
public final class WearStateStore {
    public static final String ACTION_UPDATED =
            "net.wigle.wigleandroid.starintelwear.STATE_UPDATED";

    private static final String PREFS = "StarIntelWearState";
    private static final String LIVE = "live";
    private static final String ALERT = "alert";

    private final SharedPreferences prefs;

    public WearStateStore(final Context context) {
        prefs = context.getApplicationContext()
                .getSharedPreferences(PREFS, Context.MODE_PRIVATE);
    }

    public void putLive(final String json) {
        if (isJsonObject(json)) {
            prefs.edit().putString(LIVE, json).apply();
        }
    }

    public void putAlert(final String json) {
        if (isJsonObject(json)) {
            prefs.edit().putString(ALERT, json).apply();
        }
    }

    public JSONObject live() {
        return parse(prefs.getString(LIVE, "{}"));
    }

    public JSONObject alert() {
        return parse(prefs.getString(ALERT, "{}"));
    }

    public int nearbyCount() {
        return live().optJSONArray("networks") == null
                ? 0 : live().optJSONArray("networks").length();
    }

    public long watchHits() {
        return live().optLong("watch_hits", 0L);
    }

    private static boolean isJsonObject(final String json) {
        if (json == null || json.isEmpty()) return false;
        try {
            new JSONObject(json);
            return true;
        } catch (Exception ignored) {
            return false;
        }
    }

    private static JSONObject parse(final String json) {
        try {
            return new JSONObject(json == null ? "{}" : json);
        } catch (Exception ignored) {
            return new JSONObject();
        }
    }
}
