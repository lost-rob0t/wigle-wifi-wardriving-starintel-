package net.wigle.wigleandroid.starintelwear;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Typeface;
import android.os.Build;
import android.os.Bundle;
import android.view.Gravity;
import android.view.ViewGroup;
import android.widget.Button;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import androidx.appcompat.app.AppCompatActivity;
import androidx.core.app.ActivityCompat;
import androidx.core.content.ContextCompat;

import com.google.android.gms.wearable.Node;
import com.google.android.gms.wearable.Wearable;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;

/** Live StarIntel wrist dashboard: nearby Wi-Fi, geospatial map, watch hits, and stats. */
public final class MainActivity extends AppCompatActivity {
    private static final String PATH_REQUEST_SNAPSHOT = "/starintel/request-snapshot";

    private WearStateStore store;
    private TextView status;
    private TextView stats;
    private TextView alert;
    private LinearLayout nearby;
    private RadarMapView map;

    private final BroadcastReceiver updates = new BroadcastReceiver() {
        @Override
        public void onReceive(final Context context, final Intent intent) {
            render();
        }
    };

    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        store = new WearStateStore(this);
        setContentView(buildUi());
        maybeRequestNotifications();
        requestSnapshot();
        render();
    }

    @Override
    protected void onStart() {
        super.onStart();
        ContextCompat.registerReceiver(
                this,
                updates,
                new IntentFilter(WearStateStore.ACTION_UPDATED),
                ContextCompat.RECEIVER_NOT_EXPORTED
        );
        render();
    }

    @Override
    protected void onStop() {
        try {
            unregisterReceiver(updates);
        } catch (IllegalArgumentException ignored) {
            // already unregistered
        }
        super.onStop();
    }

    private ScrollView buildUi() {
        final ScrollView scroll = new ScrollView(this);
        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        root.setPadding(dp(18), dp(14), dp(18), dp(32));
        scroll.addView(root);

        final TextView title = label("STARINTEL", 18f, true);
        root.addView(title);

        status = label("Waiting for phone…", 13f, false);
        status.setPadding(0, dp(2), 0, dp(8));
        root.addView(status);

        stats = label("", 15f, true);
        stats.setGravity(Gravity.CENTER);
        root.addView(stats);

        map = new RadarMapView(this);
        final LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(190));
        mapParams.topMargin = dp(8);
        mapParams.bottomMargin = dp(8);
        root.addView(map, mapParams);

        alert = label("", 13f, true);
        alert.setGravity(Gravity.CENTER);
        root.addView(alert);

        final TextView nearbyTitle = label("NEARBY", 13f, true);
        nearbyTitle.setPadding(0, dp(12), 0, dp(4));
        root.addView(nearbyTitle);

        nearby = new LinearLayout(this);
        nearby.setOrientation(LinearLayout.VERTICAL);
        root.addView(nearby, new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final Button refresh = new Button(this);
        refresh.setText("Refresh phone");
        refresh.setAllCaps(false);
        refresh.setOnClickListener(v -> requestSnapshot());
        final LinearLayout.LayoutParams buttonParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT);
        buttonParams.topMargin = dp(10);
        root.addView(refresh, buttonParams);

        return scroll;
    }

    private void render() {
        final JSONObject live = store.live();
        final boolean scanning = live.optBoolean("scanning", false);
        final long updated = live.optLong("updated_at", 0L);
        status.setText(updated == 0
                ? "Waiting for phone…"
                : (scanning ? "● scanning" : "○ paused") + " · " + age(updated));

        final int run = live.optInt("run_networks", 0);
        final int fresh = live.optInt("new_networks", 0);
        final long hits = live.optLong("watch_hits", 0L);
        final int rules = live.optInt("watch_rules", 0);
        final int queued = live.optInt("queued_documents", 0);
        stats.setText(
                "RUN " + run + "   NEW " + fresh + "\n" +
                "WATCH " + hits + "/" + rules + "   Q " + queued
        );

        final JSONArray networks = live.optJSONArray("networks");
        map.setNetworks(networks);
        renderNearby(networks);
        renderAlert(live.optJSONObject("last_alert"));
    }

    private void renderNearby(final JSONArray networks) {
        nearby.removeAllViews();
        if (networks == null || networks.length() == 0) {
            nearby.addView(label("No networks synced yet.", 12f, false));
            return;
        }

        final List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < networks.length(); i++) {
            final JSONObject row = networks.optJSONObject(i);
            if (row != null) rows.add(row);
        }
        rows.sort(Comparator.comparingInt(
                (JSONObject row) -> row.optInt("rssi", -100)).reversed());

        final int limit = Math.min(12, rows.size());
        for (int i = 0; i < limit; i++) {
            final JSONObject row = rows.get(i);
            final String ssid = row.optString("ssid", "");
            final String bssid = row.optString("bssid", "");
            final int rssi = row.optInt("rssi", -100);
            final int channel = row.optInt("channel", 0);
            final String title = ssid.isEmpty() ? "(hidden)" : ssid;
            final TextView view = label(
                    title + "\n" + bssid + "  " + rssi + " dBm" +
                            (channel > 0 ? "  ch " + channel : ""),
                    12f,
                    false
            );
            view.setPadding(dp(6), dp(5), dp(6), dp(5));
            nearby.addView(view);
        }
    }

    private void renderAlert(final JSONObject lastAlert) {
        if (lastAlert == null || lastAlert.length() == 0) {
            alert.setText("No watch hits this session");
            return;
        }
        final JSONObject data = lastAlert.optJSONObject("data");
        final JSONObject ext = lastAlert.optJSONObject("extensions");
        final JSONObject wigle = ext == null ? null : ext.optJSONObject("wigle");
        final int severity = data == null ? 0 : data.optInt("severity", 0);
        final String ssid = wigle == null ? "" : wigle.optString("ssid", "");
        final String bssid = wigle == null ? "" : wigle.optString("bssid", "");
        alert.setText("⚠ WATCH " + severity + "\n" +
                (ssid.isEmpty() ? "(hidden)" : ssid) + "\n" + bssid);
    }

    private void requestSnapshot() {
        status.setText("Requesting phone…");
        Wearable.getNodeClient(this)
                .getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes.isEmpty()) {
                        status.setText("Phone not connected");
                        return;
                    }
                    for (Node node : nodes) {
                        Wearable.getMessageClient(this).sendMessage(
                                node.getId(),
                                PATH_REQUEST_SNAPSHOT,
                                new byte[0]
                        );
                    }
                })
                .addOnFailureListener(ex ->
                        status.setText("Data Layer unavailable"));
    }

    private void maybeRequestNotifications() {
        if (Build.VERSION.SDK_INT >= 33 &&
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        != PackageManager.PERMISSION_GRANTED) {
            ActivityCompat.requestPermissions(
                    this,
                    new String[]{Manifest.permission.POST_NOTIFICATIONS},
                    7001
            );
        }
    }

    private TextView label(final String value, final float sp, final boolean bold) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private String age(final long timestamp) {
        final long seconds = Math.max(0L, (System.currentTimeMillis() - timestamp) / 1000L);
        if (seconds < 60L) return seconds + "s";
        final long minutes = seconds / 60L;
        if (minutes < 60L) return minutes + "m";
        return String.format(Locale.US, "%dh", minutes / 60L);
    }

    private int dp(final int value) {
        return Math.round(value * getResources().getDisplayMetrics().density);
    }
}
