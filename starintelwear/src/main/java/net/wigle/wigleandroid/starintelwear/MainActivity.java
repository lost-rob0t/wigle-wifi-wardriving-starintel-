package net.wigle.wigleandroid.starintelwear;

import android.Manifest;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.graphics.Color;
import android.graphics.Typeface;
import android.graphics.drawable.GradientDrawable;
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

/** Live StarIntel wrist dashboard: nearby Wi-Fi, geospatial radar, watch hits, and stats. */
public final class MainActivity extends AppCompatActivity {
    private static final String PATH_REQUEST_SNAPSHOT = "/starintel/request-snapshot";
    private static final String EXTRA_CI_VISUAL = "ci_visual";

    private static final int COLOR_BG = Color.rgb(6, 10, 16);
    private static final int COLOR_CARD = Color.rgb(15, 24, 35);
    private static final int COLOR_CARD_ALT = Color.rgb(20, 31, 44);
    private static final int COLOR_STROKE = Color.rgb(39, 58, 76);
    private static final int COLOR_TEXT = Color.rgb(244, 248, 252);
    private static final int COLOR_MUTED = Color.rgb(143, 162, 181);
    private static final int COLOR_CYAN = Color.rgb(102, 218, 255);
    private static final int COLOR_ALERT = Color.rgb(255, 92, 112);
    private static final int COLOR_ALERT_BG = Color.rgb(45, 18, 27);

    private WearStateStore store;
    private TextView status;
    private TextView runValue;
    private TextView newValue;
    private TextView watchValue;
    private TextView queueValue;
    private TextView mapCaption;
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
        if (BuildConfig.DEBUG && getIntent().getBooleanExtra(EXTRA_CI_VISUAL, false)) {
            seedCiVisualState();
        } else {
            maybeRequestNotifications();
            requestSnapshot();
        }
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
        scroll.setFillViewport(true);
        scroll.setClipToPadding(false);
        scroll.setBackgroundColor(COLOR_BG);

        final LinearLayout root = new LinearLayout(this);
        root.setOrientation(LinearLayout.VERTICAL);
        root.setGravity(Gravity.CENTER_HORIZONTAL);
        // Keep content inside the useful center of a round watch.
        root.setPadding(dp(40), dp(34), dp(40), dp(44));
        root.setBackgroundColor(COLOR_BG);
        scroll.addView(root, new ScrollView.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT));

        final TextView title = label("STARINTEL", 13f, true, COLOR_CYAN);
        title.setLetterSpacing(0.16f);
        title.setGravity(Gravity.CENTER);
        root.addView(title, matchWrap());

        status = label("WAITING FOR PHONE", 11f, true, COLOR_MUTED);
        status.setLetterSpacing(0.05f);
        status.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams statusParams = matchWrap();
        statusParams.topMargin = dp(3);
        statusParams.bottomMargin = dp(10);
        root.addView(status, statusParams);

        final LinearLayout statRow = new LinearLayout(this);
        statRow.setOrientation(LinearLayout.HORIZONTAL);
        statRow.setGravity(Gravity.CENTER);
        root.addView(statRow, matchWrap());

        final LinearLayout runCard = statCard("RUN");
        runValue = (TextView) runCard.getTag();
        addWeightedCard(statRow, runCard, 0, dp(3));

        final LinearLayout newCard = statCard("NEW");
        newValue = (TextView) newCard.getTag();
        addWeightedCard(statRow, newCard, dp(3), dp(3));

        final LinearLayout watchCard = statCard("WATCH");
        watchValue = (TextView) watchCard.getTag();
        addWeightedCard(statRow, watchCard, dp(3), 0);

        queueValue = label("QUEUE 0", 10f, true, COLOR_MUTED);
        queueValue.setLetterSpacing(0.08f);
        queueValue.setGravity(Gravity.CENTER);
        final LinearLayout.LayoutParams queueParams = matchWrap();
        queueParams.topMargin = dp(7);
        queueParams.bottomMargin = dp(8);
        root.addView(queueValue, queueParams);

        mapCaption = label("RADAR", 10f, true, COLOR_MUTED);
        mapCaption.setLetterSpacing(0.10f);
        final LinearLayout.LayoutParams captionParams = matchWrap();
        captionParams.bottomMargin = dp(5);
        root.addView(mapCaption, captionParams);

        map = new RadarMapView(this);
        map.setBackground(rounded(COLOR_CARD, COLOR_STROKE, 22));
        final LinearLayout.LayoutParams mapParams = new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, dp(166));
        mapParams.bottomMargin = dp(9);
        root.addView(map, mapParams);

        alert = label("", 12f, true, COLOR_TEXT);
        alert.setGravity(Gravity.CENTER);
        alert.setPadding(dp(12), dp(9), dp(12), dp(9));
        alert.setBackground(rounded(COLOR_ALERT_BG, Color.rgb(105, 43, 57), 16));
        root.addView(alert, matchWrap());

        final TextView nearbyTitle = label("NEARBY  •  STRONGEST FIRST", 10f, true, COLOR_CYAN);
        nearbyTitle.setLetterSpacing(0.08f);
        final LinearLayout.LayoutParams nearbyTitleParams = matchWrap();
        nearbyTitleParams.topMargin = dp(14);
        nearbyTitleParams.bottomMargin = dp(6);
        root.addView(nearbyTitle, nearbyTitleParams);

        nearby = new LinearLayout(this);
        nearby.setOrientation(LinearLayout.VERTICAL);
        root.addView(nearby, matchWrap());

        final Button refresh = new Button(this);
        refresh.setText("Refresh phone");
        refresh.setAllCaps(false);
        refresh.setTextSize(12f);
        refresh.setTextColor(COLOR_BG);
        refresh.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        refresh.setMinHeight(dp(44));
        refresh.setBackground(rounded(COLOR_CYAN, 0, 20));
        refresh.setOnClickListener(v -> requestSnapshot());
        final LinearLayout.LayoutParams buttonParams = matchWrap();
        buttonParams.topMargin = dp(10);
        root.addView(refresh, buttonParams);

        return scroll;
    }

    private LinearLayout statCard(final String caption) {
        final LinearLayout card = new LinearLayout(this);
        card.setOrientation(LinearLayout.VERTICAL);
        card.setGravity(Gravity.CENTER);
        card.setPadding(dp(4), dp(8), dp(4), dp(7));
        card.setBackground(rounded(COLOR_CARD_ALT, COLOR_STROKE, 14));

        final TextView value = label("0", 19f, true, COLOR_TEXT);
        value.setGravity(Gravity.CENTER);
        card.addView(value, matchWrap());

        final TextView label = label(caption, 8.5f, true, COLOR_MUTED);
        label.setLetterSpacing(0.08f);
        label.setGravity(Gravity.CENTER);
        card.addView(label, matchWrap());

        card.setTag(value);
        return card;
    }

    private void addWeightedCard(
            final LinearLayout row,
            final LinearLayout card,
            final int leftMargin,
            final int rightMargin
    ) {
        final LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f);
        params.leftMargin = leftMargin;
        params.rightMargin = rightMargin;
        row.addView(card, params);
    }

    private void render() {
        final JSONObject live = store.live();
        final boolean scanning = live.optBoolean("scanning", false);
        final long updated = live.optLong("updated_at", 0L);
        status.setText(updated == 0
                ? "WAITING FOR PHONE"
                : (scanning ? "● SCANNING" : "○ PAUSED") + "  •  " + age(updated));
        status.setTextColor(scanning ? COLOR_CYAN : COLOR_MUTED);

        final int run = live.optInt("run_networks", 0);
        final int fresh = live.optInt("new_networks", 0);
        final long hits = live.optLong("watch_hits", 0L);
        final int rules = live.optInt("watch_rules", 0);
        final int queued = live.optInt("queued_documents", 0);

        runValue.setText(Integer.toString(run));
        newValue.setText(Integer.toString(fresh));
        watchValue.setText(hits + "/" + rules);
        watchValue.setTextColor(hits > 0 ? COLOR_ALERT : COLOR_TEXT);
        queueValue.setText("QUEUE  " + queued + "  •  STARINTEL 0.10.1");

        final JSONArray networks = live.optJSONArray("networks");
        final int mapped = networks == null ? 0 : networks.length();
        mapCaption.setText("RADAR  •  " + mapped + " MAPPED");
        map.setNetworks(networks);
        renderNearby(networks);
        renderAlert(live.optJSONObject("last_alert"));
    }

    private void renderNearby(final JSONArray networks) {
        nearby.removeAllViews();
        if (networks == null || networks.length() == 0) {
            final TextView empty = label("No networks synced yet.", 11f, false, COLOR_MUTED);
            empty.setGravity(Gravity.CENTER);
            nearby.addView(empty, matchWrap());
            return;
        }

        final List<JSONObject> rows = new ArrayList<>();
        for (int i = 0; i < networks.length(); i++) {
            final JSONObject row = networks.optJSONObject(i);
            if (row != null) rows.add(row);
        }
        rows.sort(Comparator.comparingInt(
                (JSONObject row) -> row.optInt("rssi", -100)).reversed());

        final int limit = Math.min(10, rows.size());
        for (int i = 0; i < limit; i++) {
            final JSONObject row = rows.get(i);
            final String ssid = row.optString("ssid", "");
            final String bssid = row.optString("bssid", "");
            final int rssi = row.optInt("rssi", -100);
            final int channel = row.optInt("channel", 0);
            final String title = ssid.isEmpty() ? "(hidden)" : ssid;

            final LinearLayout card = new LinearLayout(this);
            card.setOrientation(LinearLayout.HORIZONTAL);
            card.setGravity(Gravity.CENTER_VERTICAL);
            card.setPadding(dp(11), dp(8), dp(11), dp(8));
            card.setBackground(rounded(COLOR_CARD, COLOR_STROKE, 13));

            final LinearLayout identity = new LinearLayout(this);
            identity.setOrientation(LinearLayout.VERTICAL);
            final TextView ssidView = label(title, 12.5f, true, COLOR_TEXT);
            final TextView macView = label(shortMac(bssid), 9.5f, false, COLOR_MUTED);
            macView.setTypeface(Typeface.MONOSPACE);
            identity.addView(ssidView, matchWrap());
            identity.addView(macView, matchWrap());
            card.addView(identity, new LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f));

            final TextView signal = label(
                    rssi + " dBm" + (channel > 0 ? "\nch " + channel : ""),
                    10f,
                    true,
                    signalColor(rssi)
            );
            signal.setGravity(Gravity.END);
            card.addView(signal, new LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.WRAP_CONTENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT));

            final LinearLayout.LayoutParams cardParams = matchWrap();
            cardParams.bottomMargin = dp(6);
            nearby.addView(card, cardParams);
        }
    }

    private void renderAlert(final JSONObject lastAlert) {
        if (lastAlert == null || lastAlert.length() == 0) {
            alert.setText("WATCHLIST ARMED\nNo hits this session");
            alert.setTextColor(COLOR_MUTED);
            return;
        }
        final JSONObject data = lastAlert.optJSONObject("data");
        final JSONObject ext = lastAlert.optJSONObject("extensions");
        final JSONObject wigle = ext == null ? null : ext.optJSONObject("wigle");
        final int severity = data == null ? 0 : data.optInt("severity", 0);
        final String ssid = wigle == null ? "" : wigle.optString("ssid", "");
        final String bssid = wigle == null ? "" : wigle.optString("bssid", "");
        final int rssi = wigle == null ? 0 : wigle.optInt("signal_dbm", 0);

        alert.setTextColor(COLOR_TEXT);
        alert.setText(
                "⚠  WATCH HIT  •  " + severity + "\n" +
                (ssid.isEmpty() ? "(hidden)" : ssid) + "  " +
                shortMac(bssid) + (rssi != 0 ? "  •  " + rssi + " dBm" : "")
        );
    }

    private void seedCiVisualState() {
        try {
            final long now = System.currentTimeMillis();
            final JSONArray networks = new JSONArray()
                    .put(network("DE:AD:BE:EF:10:01", "WATCHED-AP", -38, 2412, 1, 39.9619, -82.9987, now))
                    .put(network("02:42:AC:11:00:05", "field-kit", -51, 5180, 36, 39.9624, -82.9978, now))
                    .put(network("7C:DF:A1:44:22:10", "ops-uplink", -64, 5955, 1, 39.9608, -82.9993, now))
                    .put(network("A0:B1:C2:D3:E4:F5", "coffee-guest", -72, 2462, 11, 39.9631, -82.9968, now))
                    .put(network("12:34:56:78:9A:BC", "", -81, 5220, 44, 39.9599, -82.9971, now));

            final JSONObject alertData = new JSONObject()
                    .put("alert_type", "wireless-mac-in-range")
                    .put("severity", 92)
                    .put("status", "triggered")
                    .put("triggered_at", java.time.Instant.ofEpochMilli(now).toString());
            final JSONObject wigle = new JSONObject()
                    .put("bssid", "DE:AD:BE:EF:10:01")
                    .put("ssid", "WATCHED-AP")
                    .put("signal_dbm", -38);
            final JSONObject alert = new JSONObject()
                    .put("_id", "starintel:alert:wigle-mac-watch:visual")
                    .put("dtype", "alert")
                    .put("data", alertData)
                    .put("extensions", new JSONObject().put("wigle", wigle));

            final JSONObject live = new JSONObject()
                    .put("updated_at", now)
                    .put("scanning", true)
                    .put("run_networks", 127)
                    .put("new_networks", 18)
                    .put("database_networks", 12844)
                    .put("watch_hits", 4)
                    .put("watch_rules", 3)
                    .put("queued_documents", 7)
                    .put("networks", networks)
                    .put("last_alert", alert);

            store.putLive(live.toString());
            store.putAlert(alert.toString());
        } catch (Exception ignored) {
            // Visual fixture failure should not affect the real Wear app path.
        }
    }

    private static JSONObject network(
            final String bssid,
            final String ssid,
            final int rssi,
            final int frequency,
            final int channel,
            final double lat,
            final double lon,
            final long seenAt
    ) throws org.json.JSONException {
        return new JSONObject()
                .put("bssid", bssid)
                .put("ssid", ssid)
                .put("rssi", rssi)
                .put("frequency_mhz", frequency)
                .put("channel", channel)
                .put("lat", lat)
                .put("lon", lon)
                .put("accuracy_m", 4.2d)
                .put("seen_at", seenAt);
    }

    private void requestSnapshot() {
        status.setText("REQUESTING PHONE…");
        status.setTextColor(COLOR_MUTED);
        Wearable.getNodeClient(this)
                .getConnectedNodes()
                .addOnSuccessListener(nodes -> {
                    if (nodes.isEmpty()) {
                        status.setText("PHONE NOT CONNECTED");
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
                        status.setText("DATA LAYER UNAVAILABLE"));
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

    private TextView label(
            final String value,
            final float sp,
            final boolean bold,
            final int color
    ) {
        final TextView view = new TextView(this);
        view.setText(value);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setIncludeFontPadding(false);
        if (bold) view.setTypeface(Typeface.DEFAULT, Typeface.BOLD);
        return view;
    }

    private GradientDrawable rounded(final int fill, final int stroke, final int radiusDp) {
        final GradientDrawable drawable = new GradientDrawable();
        drawable.setColor(fill);
        drawable.setCornerRadius(dp(radiusDp));
        if (stroke != 0) drawable.setStroke(dp(1), stroke);
        return drawable;
    }

    private LinearLayout.LayoutParams matchWrap() {
        return new LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
        );
    }

    private int signalColor(final int rssi) {
        if (rssi >= -50) return COLOR_ALERT;
        if (rssi >= -67) return COLOR_CYAN;
        return COLOR_MUTED;
    }

    private static String shortMac(final String value) {
        if (value == null || value.length() < 8) return value == null ? "" : value;
        return value.substring(Math.max(0, value.length() - 8));
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
