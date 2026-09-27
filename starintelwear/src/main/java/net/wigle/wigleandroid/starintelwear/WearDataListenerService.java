package net.wigle.wigleandroid.starintelwear;

import android.Manifest;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.ComponentName;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;
import androidx.core.content.ContextCompat;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceUpdateRequester;

import com.google.android.gms.wearable.DataEvent;
import com.google.android.gms.wearable.DataEventBuffer;
import com.google.android.gms.wearable.DataMapItem;
import com.google.android.gms.wearable.WearableListenerService;

import org.json.JSONObject;

/** Receives phone state/alert DataItems and fans them into watch UI + complication. */
public final class WearDataListenerService extends WearableListenerService {
    private static final String PATH_LIVE = "/starintel/live";
    private static final String PATH_ALERT = "/starintel/alert";
    private static final String CHANNEL = "starintel_wear_alerts_v1";

    @Override
    public void onDataChanged(final DataEventBuffer dataEvents) {
        try {
            final WearStateStore store = new WearStateStore(this);
            boolean changed = false;
            for (DataEvent event : dataEvents) {
                if (event.getType() != DataEvent.TYPE_CHANGED) continue;
                final String path = event.getDataItem().getUri().getPath();
                final String json = DataMapItem.fromDataItem(event.getDataItem())
                        .getDataMap()
                        .getString("json");
                if (PATH_LIVE.equals(path)) {
                    store.putLive(json);
                    changed = true;
                } else if (PATH_ALERT.equals(path)) {
                    store.putAlert(json);
                    notifyAlert(json);
                    changed = true;
                }
            }
            if (changed) {
                sendBroadcast(new Intent(WearStateStore.ACTION_UPDATED).setPackage(getPackageName()));
                ComplicationDataSourceUpdateRequester.create(
                        this,
                        new ComponentName(this, NearbyComplicationService.class)
                ).requestUpdateAll();
            }
        } finally {
            dataEvents.release();
        }
    }

    private void notifyAlert(final String raw) {
        final JSONObject alert;
        try {
            alert = new JSONObject(raw == null ? "{}" : raw);
        } catch (Exception ignored) {
            return;
        }
        final JSONObject data = alert.optJSONObject("data");
        final JSONObject ext = alert.optJSONObject("extensions");
        final JSONObject wigle = ext == null ? null : ext.optJSONObject("wigle");

        final String bssid = wigle == null ? "" : wigle.optString("bssid", "");
        final String ssid = wigle == null ? "" : wigle.optString("ssid", "");
        final int rssi = wigle == null ? -100 : wigle.optInt("signal_dbm", -100);
        final int severity = data == null ? 0 : data.optInt("severity", 0);

        ensureChannel();
        final PendingIntent tap = PendingIntent.getActivity(
                this,
                alert.optString("_id", "").hashCode(),
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );
        final String title = "MAC watch hit · " + severity;
        final String body = (ssid.isEmpty() ? "(hidden)" : ssid) + " " + bssid + " " + rssi + " dBm";

        final NotificationCompat.Builder builder = new NotificationCompat.Builder(this, CHANNEL)
                .setSmallIcon(android.R.drawable.ic_menu_mylocation)
                .setContentTitle(title)
                .setContentText(body)
                .setStyle(new NotificationCompat.BigTextStyle().bigText(body))
                .setCategory(NotificationCompat.CATEGORY_ALARM)
                .setPriority(NotificationCompat.PRIORITY_HIGH)
                .setAutoCancel(true)
                .setContentIntent(tap);

        if (Build.VERSION.SDK_INT < 33 ||
                ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                        == PackageManager.PERMISSION_GRANTED) {
            try {
                NotificationManagerCompat.from(this).notify(
                        alert.optString("_id", "").hashCode(),
                        builder.build()
                );
            } catch (SecurityException ignored) {
                // User controls watch notification permission.
            }
        }
    }

    private void ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final NotificationManager manager =
                (NotificationManager) getSystemService(NOTIFICATION_SERVICE);
        if (manager == null) return;
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL,
                "StarIntel watch alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }
}
