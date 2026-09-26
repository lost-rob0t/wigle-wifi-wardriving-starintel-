package net.wigle.wigleandroid.starintelwear;

import android.app.Activity;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.os.Bundle;
import android.view.View;

/**
 * Debug-only visual validation surface for the actual NearbyComplicationService data.
 * This never ships in release builds.
 */
public final class ComplicationPreviewActivity extends Activity {
    @Override
    protected void onCreate(final Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(new PreviewView());
    }

    private final class PreviewView extends View {
        private final Paint paint = new Paint(Paint.ANTI_ALIAS_FLAG);
        private final Paint stroke = new Paint(Paint.ANTI_ALIAS_FLAG);

        PreviewView() {
            super(ComplicationPreviewActivity.this);
            stroke.setStyle(Paint.Style.STROKE);
            stroke.setStrokeWidth(dp(2));
        }

        @Override
        protected void onDraw(final Canvas canvas) {
            super.onDraw(canvas);
            final float width = getWidth();
            final float height = getHeight();
            final float cx = width / 2f;
            final float cy = height / 2f;
            final float radius = Math.min(width, height) * 0.47f;

            paint.setColor(0xFF090B10);
            canvas.drawCircle(cx, cy, radius, paint);

            stroke.setColor(0xFF4F5664);
            canvas.drawCircle(cx, cy, radius, stroke);

            text(canvas, "12:42", cx, cy - radius * 0.48f, sp(26), 0xFFE5E7EB, true);
            text(canvas, "STARINTEL", cx, cy - radius * 0.20f, sp(11), 0xFF9CA3AF, true);

            final RectF complication = new RectF(
                    cx - radius * 0.58f,
                    cy - radius * 0.02f,
                    cx + radius * 0.58f,
                    cy + radius * 0.42f
            );
            paint.setColor(0xFF171A21);
            canvas.drawRoundRect(complication, dp(18), dp(18), paint);
            stroke.setColor(0xFF5D6575);
            canvas.drawRoundRect(complication, dp(18), dp(18), stroke);

            final WearStateStore store = new WearStateStore(ComplicationPreviewActivity.this);
            final int nearby = store.nearbyCount() == 0 ? 12 : store.nearbyCount();
            final long hits = store.watchHits() == 0 ? 4 : store.watchHits();

            text(canvas, nearby + "N", cx, complication.centerY() - dp(5), sp(31), 0xFFFFFFFF, true);
            text(canvas, hits + " hit", cx, complication.centerY() + dp(26), sp(13), 0xFFD1D5DB, false);
            text(canvas, "Nearby Wi-Fi", cx, cy + radius * 0.64f, sp(12), 0xFF9CA3AF, false);
            text(canvas, "Complication provider preview", cx, cy + radius * 0.79f, sp(9), 0xFF6B7280, false);
        }

        private void text(
                final Canvas canvas,
                final String value,
                final float x,
                final float y,
                final float size,
                final int color,
                final boolean bold
        ) {
            paint.setStyle(Paint.Style.FILL);
            paint.setColor(color);
            paint.setTextAlign(Paint.Align.CENTER);
            paint.setTextSize(size);
            paint.setTypeface(bold ? android.graphics.Typeface.DEFAULT_BOLD : android.graphics.Typeface.DEFAULT);
            canvas.drawText(value, x, y, paint);
        }

        private float dp(final float value) {
            return value * getResources().getDisplayMetrics().density;
        }

        private float sp(final float value) {
            return value * getResources().getDisplayMetrics().scaledDensity;
        }
    }
}
