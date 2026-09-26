package net.wigle.wigleandroid.starintel;

import android.content.Context;

import com.google.android.gms.wearable.PutDataMapRequest;
import com.google.android.gms.wearable.PutDataRequest;
import com.google.android.gms.wearable.Wearable;

import net.wigle.wigleandroid.util.Logging;

import org.json.JSONObject;

/** Phone -> Wear OS durable/urgent data-layer publisher. */
public final class PhoneWearBridge {
    public static final String PATH_LIVE = "/starintel/live";
    public static final String PATH_ALERT = "/starintel/alert";
    public static final String PATH_REQUEST_SNAPSHOT = "/starintel/request-snapshot";

    private final Context context;

    public PhoneWearBridge(final Context context) {
        this.context = context.getApplicationContext();
    }

    public void publishSnapshot(final JSONObject snapshot) {
        publish(PATH_LIVE, snapshot);
    }

    public void publishAlert(final JSONObject alert) {
        publish(PATH_ALERT, alert);
    }

    private void publish(final String path, final JSONObject payload) {
        try {
            final PutDataMapRequest mapRequest = PutDataMapRequest.create(path);
            mapRequest.getDataMap().putString("json", payload.toString());
            mapRequest.getDataMap().putLong("updated_at", System.currentTimeMillis());
            final PutDataRequest request = mapRequest.asPutDataRequest();
            request.setUrgent();
            Wearable.getDataClient(context)
                    .putDataItem(request)
                    .addOnFailureListener(ex ->
                            Logging.warn("Wear data-layer publish failed: " + ex.getClass().getSimpleName()));
        } catch (RuntimeException ex) {
            Logging.warn("Wear data-layer unavailable: " + ex.getClass().getSimpleName());
        }
    }
}
