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
            setContentDescription("StarIntel complication preview");
            setBackgroundColor(0xFF000000);
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

            paint.setColor(0xFF060A10);
            canvas.drawCircle(cx, cy, radius, paint);
            stroke.setColor(0xFF31465B);
            canvas.drawCircle(cx, cy, radius, stroke);

            text(canvas, "12:42", cx, cy - radius * 0.56f, sp(24), 0xFFF4F8FC, true);
            text(canvas, "STARINTEL", cx, cy - radius * 0.31f, sp(10), 0xFF66DAFF, true);

            final RectF complication = new RectF(
                    cx - radius * 0.68f,
                    cy - radius * 0.10f,
                    cx + radius * 0.68f,
                    cy + radius * 0.42f
            );
            paint.setColor(0xFF0F1823);
            canvas.drawRoundRect(complication, dp(18), dp(18), paint);
            stroke.setColor(0xFF273A4C);
            canvas.drawRoundRect(complication, dp(18), dp(18), stroke);

            final WearStateStore store = new WearStateStore(ComplicationPreviewActivity.this);
            final int nearby = store.nearbyCount() == 0 ? 5 : store.nearbyCount();
            final long hits = store.watchHits() == 0 ? 4 : store.watchHits();

            final float dividerX = cx;
            stroke.setColor(0xFF273A4C);
            stroke.setStrokeWidth(dp(1));
            canvas.drawLine(
                    dividerX,
                    complication.top + dp(12),
                    dividerX,
                    complication.bottom - dp(12),
                    stroke
            );

            final float leftX = cx - complication.width() * 0.25f;
            final float rightX = cx + complication.width() * 0.25f;
            text(canvas, Integer.toString(nearby), leftX, complication.centerY() + dp(3), sp(26), 0xFFF4F8FC, true);
            text(canvas, "nearby", leftX, complication.centerY() + dp(24), sp(9), 0xFF8FA2B5, false);
            text(canvas, Long.toString(hits), rightX, complication.centerY() + dp(3), sp(26), 0xFFFF5C70, true);
            text(canvas, "watch", rightX, complication.centerY() + dp(24), sp(9), 0xFF8FA2B5, false);

            text(canvas, "Wi-Fi  •  live", cx, cy + radius * 0.62f, sp(11), 0xFF8FA2B5, false);
            text(canvas, "tap → dashboard", cx, cy + radius * 0.77f, sp(8.5f), 0xFF5E7184, false);
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
