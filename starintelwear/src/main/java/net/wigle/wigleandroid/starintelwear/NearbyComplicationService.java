package net.wigle.wigleandroid.starintelwear;

import android.app.PendingIntent;
import android.content.Intent;
import android.os.RemoteException;

import androidx.annotation.NonNull;
import androidx.annotation.Nullable;
import androidx.wear.watchface.complications.data.ComplicationData;
import androidx.wear.watchface.complications.data.ComplicationText;
import androidx.wear.watchface.complications.data.ComplicationType;
import androidx.wear.watchface.complications.data.LongTextComplicationData;
import androidx.wear.watchface.complications.data.PlainComplicationText;
import androidx.wear.watchface.complications.data.ShortTextComplicationData;
import androidx.wear.watchface.complications.datasource.ComplicationDataSourceService;
import androidx.wear.watchface.complications.datasource.ComplicationRequest;

/** Watch-face complication exposing live nearby-network and watch-hit counts. */
public final class NearbyComplicationService extends ComplicationDataSourceService {
    @Override
    public void onComplicationRequest(
            @NonNull final ComplicationRequest request,
            @NonNull final ComplicationRequestListener listener
    ) {
        try {
            listener.onComplicationData(build(request.getComplicationType(), false));
        } catch (RemoteException ignored) {
            // The watch face binder went away; a later request will refresh it.
        }
    }

    @Nullable
    @Override
    public ComplicationData getPreviewData(@NonNull final ComplicationType type) {
        return build(type, true);
    }

    private ComplicationData build(final ComplicationType type, final boolean preview) {
        final WearStateStore store = new WearStateStore(this);
        final int nearby = preview ? 12 : store.nearbyCount();
        final long hits = preview ? 3L : store.watchHits();

        final ComplicationText description = text(
                nearby + " nearby Wi-Fi networks, " + hits + " watch hits");
        final PendingIntent tapAction = PendingIntent.getActivity(
                this,
                0,
                new Intent(this, MainActivity.class),
                PendingIntent.FLAG_UPDATE_CURRENT | PendingIntent.FLAG_IMMUTABLE
        );

        if (ComplicationType.LONG_TEXT.equals(type)) {
            return new LongTextComplicationData.Builder(
                    text(nearby + " nearby · " + hits + " hits"),
                    description
            )
                    .setTitle(text("StarIntel"))
                    .setTapAction(tapAction)
                    .build();
        }

        final String shortValue = nearby > 9999 ? "9999+" : nearby + "N";
        return new ShortTextComplicationData.Builder(text(shortValue), description)
                .setTitle(text(hits > 99 ? "99+ hit" : hits + " hit"))
                .setTapAction(tapAction)
                .build();
    }

    private static ComplicationText text(final CharSequence value) {
        return new PlainComplicationText.Builder(value).build();
    }
}
