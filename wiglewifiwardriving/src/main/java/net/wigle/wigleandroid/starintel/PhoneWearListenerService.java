package net.wigle.wigleandroid.starintel;

import com.google.android.gms.wearable.MessageEvent;
import com.google.android.gms.wearable.WearableListenerService;

/** Handles one-shot refresh requests from the Wear OS companion. */
public final class PhoneWearListenerService extends WearableListenerService {
    @Override
    public void onMessageReceived(final MessageEvent event) {
        if (PhoneWearBridge.PATH_REQUEST_SNAPSHOT.equals(event.getPath())) {
            StarIntelRuntime.get(getApplicationContext()).requestSnapshot();
        }
    }
}
