package net.wigle.wigleandroid.starintelwear;

import android.content.Context;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.RectF;
import android.util.AttributeSet;
import android.view.View;

import org.json.JSONArray;
import org.json.JSONObject;

import java.util.ArrayList;
import java.util.List;

/**
 * Tiny geospatial map optimized for a round watch: it plots recent BSSIDs
 * relative to their observed latitude/longitude bounds and encodes RSSI by radius.
 */
public final class RadarMapView extends View {
    private static final float MIN_RSSI = -100f;
    private static final float MAX_RSSI = -20f;

    private static final class Point {
        final double lat;
        final double lon;
        final int rssi;
        final String label;

        Point(final double lat, final double lon, final int rssi, final String label) {
            this.lat = lat;
            this.lon = lon;
            this.rssi = rssi;
            this.label = label;
        }
    }

    private final Paint linePaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint pointPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final Paint textPaint = new Paint(Paint.ANTI_ALIAS_FLAG);
    private final List<Point> points = new ArrayList<>();

    public RadarMapView(final Context context) {
        this(context, null);
    }

    public RadarMapView(final Context context, final AttributeSet attrs) {
        super(context, attrs);
        final float density = getResources().getDisplayMetrics().density;
        setWillNotDraw(false);
        setBackgroundColor(0x00000000);

        linePaint.setStyle(Paint.Style.STROKE);
        linePaint.setStrokeWidth(Math.max(1f, density));
        linePaint.setAlpha(120);

        pointPaint.setStyle(Paint.Style.FILL);
        pointPaint.setAlpha(255);

        textPaint.setTextSize(10f * getResources().getDisplayMetrics().scaledDensity);
        textPaint.setTextAlign(Paint.Align.CENTER);
    }

    public void setNetworks(final JSONArray networks) {
        points.clear();
        if (networks != null) {
            for (int i = 0; i < networks.length(); i++) {
                final JSONObject network = networks.optJSONObject(i);
                if (network == null || !network.has("lat") || !network.has("lon")) continue;
                final double lat = network.optDouble("lat", Double.NaN);
                final double lon = network.optDouble("lon", Double.NaN);
                if (Double.isNaN(lat) || Double.isNaN(lon)) continue;
                final String ssid = network.optString("ssid", "");
                final String bssid = network.optString("bssid", "");
                points.add(new Point(
                        lat,
                        lon,
                        network.optInt("rssi", -100),
                        ssid.isEmpty() ? shortMac(bssid) : ssid
                ));
            }
        }
        invalidate();
    }

    @Override
    protected void onDraw(final Canvas canvas) {
        super.onDraw(canvas);
        final int width = getWidth();
        final int height = getHeight();
        final float pad = Math.min(width, height) * 0.09f;
        final RectF bounds = new RectF(pad, pad, width - pad, height - pad);

        linePaint.setColor(0x66708BA0);
        textPaint.setColor(0xCC8FA2B5);

        canvas.drawOval(bounds, linePaint);
        canvas.drawLine(width / 2f, bounds.top, width / 2f, bounds.bottom, linePaint);
        canvas.drawLine(bounds.left, height / 2f, bounds.right, height / 2f, linePaint);

        if (points.isEmpty()) {
            canvas.drawText("No GPS fixes", width / 2f, height / 2f, textPaint);
            return;
        }

        double minLat = Double.POSITIVE_INFINITY;
        double maxLat = Double.NEGATIVE_INFINITY;
        double minLon = Double.POSITIVE_INFINITY;
        double maxLon = Double.NEGATIVE_INFINITY;
        for (Point point : points) {
            minLat = Math.min(minLat, point.lat);
            maxLat = Math.max(maxLat, point.lat);
            minLon = Math.min(minLon, point.lon);
            maxLon = Math.max(maxLon, point.lon);
        }
        final double latSpan = Math.max(0.00001d, maxLat - minLat);
        final double lonSpan = Math.max(0.00001d, maxLon - minLon);

        for (Point point : points) {
            final float x = bounds.left +
                    (float) ((point.lon - minLon) / lonSpan) * bounds.width();
            final float y = bounds.bottom -
                    (float) ((point.lat - minLat) / latSpan) * bounds.height();
            final float strength = clamp((point.rssi - MIN_RSSI) / (MAX_RSSI - MIN_RSSI));
            final float radius = dp(3f) + dp(5.5f) * strength;
            if (point.rssi >= -50) {
                pointPaint.setColor(0xFFFF5C70);
            } else if (point.rssi >= -67) {
                pointPaint.setColor(0xFF66DAFF);
            } else {
                pointPaint.setColor(0xFF8FA2B5);
            }
            canvas.drawCircle(x, y, radius, pointPaint);
            pointPaint.setStyle(Paint.Style.STROKE);
            pointPaint.setStrokeWidth(dp(1.5f));
            pointPaint.setColor(0xAAFFFFFF);
            canvas.drawCircle(x, y, radius + dp(2f), pointPaint);
            pointPaint.setStyle(Paint.Style.FILL);
        }

        canvas.drawText(points.size() + " APs", width / 2f, bounds.bottom - dp(6), textPaint);
    }

    private float dp(final float value) {
        return value * getResources().getDisplayMetrics().density;
    }

    private static float clamp(final float value) {
        return Math.max(0f, Math.min(1f, value));
    }

    private static String shortMac(final String value) {
        if (value == null || value.length() < 5) return "AP";
        return value.substring(Math.max(0, value.length() - 5));
    }
}
