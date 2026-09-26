package net.wigle.wigleandroid.starintel;

import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.PendingIntent;
import android.content.Context;
import android.content.Intent;
import android.os.Build;

import androidx.core.app.NotificationCompat;
import androidx.core.app.NotificationManagerCompat;

import net.wigle.wigleandroid.MainActivity;
import net.wigle.wigleandroid.R;
import net.wigle.wigleandroid.model.Network;
import net.wigle.wigleandroid.util.Logging;

import org.json.JSONObject;

/** High-priority local notification for canonical MAC-watch alert documents. */
public final class WatchAlertNotifier {
    private static final String CHANNEL_ID = "starintel_mac_watch_v1";

    private WatchAlertNotifier() {}

    public static void notify(
            final Context context,
            final JSONObject alert,
            final Network network,
            final String rule
    ) {
        ensureChannel(context);

        final Intent intent = new Intent(context, MainActivity.class)
                .addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP | Intent.FLAG_ACTIVITY_SINGLE_TOP);
        final PendingIntent pendingIntent = PendingIntent.getActivity(
                context,
                alert.optString("_id", "").hashCode(),
                intent,
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        final String ssid = network.getSsid() == null || network.getSsid().isEmpty()
                ? "(hidden SSID)" : network.getSsid();
        final String title = "Watched MAC in range";
        final String text = ssid + "  " + network.getBssid() + "  " +
                network.getLevel() + " dBm";

        final NotificationCompat.Builder builder =
                new NotificationCompat.Builder(context, CHANNEL_ID)
                        .setSmallIcon(R.drawable.wiglewifi_small_white)
                        .setContentTitle(title)
                        .setContentText(text)
                        .setStyle(new NotificationCompat.BigTextStyle()
                                .bigText(text + "\nWatch: " + rule))
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(NotificationCompat.CATEGORY_ALARM)
                        .setAutoCancel(true)
                        .setContentIntent(pendingIntent);

        try {
            NotificationManagerCompat.from(context).notify(
                    alert.optString("_id", "").hashCode(),
                    builder.build()
            );
        } catch (SecurityException ex) {
            Logging.warn("Notification permission denied for MAC watch alert");
        }
    }

    private static void ensureChannel(final Context context) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return;
        final NotificationManager manager =
                (NotificationManager) context.getSystemService(Context.NOTIFICATION_SERVICE);
        if (manager == null) return;
        final NotificationChannel channel = new NotificationChannel(
                CHANNEL_ID,
                "StarIntel MAC watch alerts",
                NotificationManager.IMPORTANCE_HIGH
        );
        channel.setDescription("Alerts when a watched MAC/OUI is observed in range");
        channel.enableVibration(true);
        manager.createNotificationChannel(channel);
    }
}
