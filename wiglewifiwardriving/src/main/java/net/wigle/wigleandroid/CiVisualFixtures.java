package net.wigle.wigleandroid;

import android.content.Intent;
import android.content.SharedPreferences;

import com.google.gson.Gson;

import net.wigle.wigleandroid.model.LatLng;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.model.NetworkType;
import net.wigle.wigleandroid.starintel.StarIntelCredentialStore;
import net.wigle.wigleandroid.util.PreferenceKeys;

import java.util.Arrays;

/**
 * Debug-only visual fixtures used by CI screenshot capture.
 * Production builds ignore every entry point in this class.
 */
public final class CiVisualFixtures {
    public static final String EXTRA_VISUAL = "ci_visual";
    public static final String EXTRA_SCREEN = "ci_visual_screen";

    private CiVisualFixtures() {}

    public static boolean enabled(final Intent intent) {
        return BuildConfig.DEBUG && intent != null
                && intent.getBooleanExtra(EXTRA_VISUAL, false);
    }

    public static void configurePreferences(
            final MainActivity activity,
            final SharedPreferences prefs
    ) {
        if (!enabled(activity.getIntent())) return;

        prefs.edit()
                .putBoolean(PreferenceKeys.PREF_MUTED, true)
                .putBoolean(PreferenceKeys.PREF_SCAN_RUNNING, false)
                .putBoolean(PreferenceKeys.PREF_STARINTEL_ENABLED, true)
                .putBoolean(PreferenceKeys.PREF_STARINTEL_LIVE_INGEST, true)
                .putBoolean(PreferenceKeys.PREF_STARINTEL_WEAR_SYNC, true)
                .putBoolean(PreferenceKeys.PREF_USE_FOSS_MAPS, true)
                .putFloat(PreferenceKeys.PREF_PREV_LAT, 39.9612f)
                .putFloat(PreferenceKeys.PREF_PREV_LON, -82.9988f)
                .putFloat(PreferenceKeys.PREF_PREV_ZOOM, 16.0f)
                .putString(PreferenceKeys.PREF_STARINTEL_BASE_URL, "https://starintel.example")
                .putString(PreferenceKeys.PREF_STARINTEL_DATASET, "field-ops-demo")
                .putString(
                        PreferenceKeys.PREF_ALERT_ADDRS,
                        new Gson().toJson(Arrays.asList(
                                "DE:AD:BE:EF:10:01",
                                "02:42:AC",
                                "7C:DF:A1:44:22:10")))
                .apply();

        new StarIntelCredentialStore(activity)
                .saveToken("star_sk_v1_visual_ci_only");
    }

    public static int requestedNavId(final Intent intent) {
        final String screen = intent == null ? "" :
                intent.getStringExtra(EXTRA_SCREEN);
        if ("starintel".equals(screen)) return R.id.nav_starintel;
        if ("map".equals(screen)) return R.id.nav_map;
        if ("dash".equals(screen)) return R.id.nav_dash;
        return R.id.nav_list;
    }

    public static void seedUi(final MainActivity activity, final MainActivity.State state) {
        if (!enabled(activity.getIntent()) || state == null) return;

        final Network[] demo = new Network[] {
                wifi("DE:AD:BE:EF:10:01", "WATCHED-AP", 2412, "[WPA2-PSK-CCMP][ESS]", -38, 39.9619, -82.9987),
                wifi("02:42:AC:11:00:05", "field-kit", 5180, "[WPA3-SAE-CCMP][ESS]", -51, 39.9624, -82.9978),
                wifi("7C:DF:A1:44:22:10", "ops-uplink", 5955, "[WPA3-SAE-CCMP][ESS]", -64, 39.9608, -82.9993),
                wifi("A0:B1:C2:D3:E4:F5", "coffee-guest", 2462, "[ESS]", -72, 39.9631, -82.9968),
                wifi("12:34:56:78:9A:BC", "", 5220, "[WPA2-PSK-CCMP][ESS]", -81, 39.9599, -82.9971)
        };

        MainActivity.getNetworkCache().clear();
        for (Network network : demo) {
            MainActivity.getNetworkCache().put(network.getBssid(), network);
        }

        ListFragment.lameStatic.currWifi = demo.length;
        ListFragment.lameStatic.currNets = demo.length;
        ListFragment.lameStatic.runNets = 127;
        ListFragment.lameStatic.newNets = 18;
        ListFragment.lameStatic.newWifi = 18;
        ListFragment.lameStatic.dbNets = 12844;
        ListFragment.lameStatic.dbLocs = 47391;
        ListFragment.lameStatic.preQueueSize = 3;
        ListFragment.lameStatic.currWifiScanDurMs = 842;

        if (state.listAdapter != null) {
            state.listAdapter.clear();
            for (Network network : demo) {
                state.listAdapter.addWiFi(network);
            }
            state.listAdapter.notifyDataSetChanged();
        }

        activity.getSupportFragmentManager().executePendingTransactions();
        for (Network network : demo) {
            MainActivity.addNetworkToMap(network);
        }
    }

    private static Network wifi(
            final String bssid,
            final String ssid,
            final int frequency,
            final String capabilities,
            final int level,
            final double lat,
            final double lon
    ) {
        final Network network = new Network(
                bssid, ssid, frequency, capabilities, level, NetworkType.WIFI);
        network.setLatLng(new LatLng(lat, lon));
        return network;
    }
}
