package net.wigle.wigleandroid.warstar;

import android.database.Cursor;
import android.location.Location;
import net.wigle.wigleandroid.db.DatabaseHelper;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** A nearby coverage plan: 250 m cells, local observations, nearest-neighbor ordering. */
public final class CoverageRoutes {
    private static final double CELL_METERS = 250.0;
    private CoverageRoutes() {}

    public static String gpx(DatabaseHelper db, Location origin) throws Exception {
        if (origin == null) throw new IllegalArgumentException("A current GPS fix is required");
        double lat = origin.getLatitude(), lon = origin.getLongitude();
        double stepLat = CELL_METERS / 111320.0;
        double stepLon = CELL_METERS / (111320.0 * Math.max(0.2, Math.cos(Math.toRadians(lat))));
        int[][] counts = new int[7][7];
        try (Cursor rows = db.query(
                "SELECT lat,lon FROM location WHERE external = 0 AND lat BETWEEN ? AND ? AND lon BETWEEN ? AND ? LIMIT 50000",
                new String[]{Double.toString(lat - 3.5 * stepLat),
                        Double.toString(lat + 3.5 * stepLat),
                        Double.toString(lon - 3.5 * stepLon),
                        Double.toString(lon + 3.5 * stepLon)})) {
            while (rows.moveToNext()) {
                int y = (int) Math.floor((rows.getDouble(0) - lat) / stepLat + 3.5);
                int x = (int) Math.floor((rows.getDouble(1) - lon) / stepLon + 3.5);
                if (x >= 0 && x < 7 && y >= 0 && y < 7) counts[y][x]++;
            }
        }

        List<int[]> candidates = new ArrayList<>();
        for (int y = 0; y < 7; y++) for (int x = 0; x < 7; x++) {
            if (x == 3 && y == 3) continue;
            candidates.add(new int[]{x, y, counts[y][x]});
        }
        candidates.sort((a, b) -> {
            int coverage = Integer.compare(a[2], b[2]);
            if (coverage != 0) return coverage;
            int da = (a[0] - 3) * (a[0] - 3) + (a[1] - 3) * (a[1] - 3);
            int distanceB = (b[0] - 3) * (b[0] - 3) + (b[1] - 3) * (b[1] - 3);
            return Integer.compare(da, distanceB);
        });
        // Choose eight under-observed cells; order them from the current fix.
        List<int[]> route = new ArrayList<>(candidates.subList(0, 8));
        List<int[]> ordered = new ArrayList<>();
        int px = 3, py = 3;
        while (!route.isEmpty()) {
            int best = 0, distance = Integer.MAX_VALUE;
            for (int i = 0; i < route.size(); i++) {
                int[] cell = route.get(i);
                int d = (cell[0] - px) * (cell[0] - px) + (cell[1] - py) * (cell[1] - py);
                if (d < distance) { best = i; distance = d; }
            }
            int[] next = route.remove(best);
            ordered.add(next);
            px = next[0]; py = next[1];
        }
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
        xml.append("<gpx version=\"1.1\" creator=\"WarStar\" xmlns=\"http://www.topografix.com/GPX/1/1\">");
        xml.append("<metadata><name>WarStar low coverage waypoints</name></metadata><rte>");
        for (int i = 0; i < ordered.size(); i++) {
            int[] cell = ordered.get(i);
            double centerLat = lat + (cell[1] - 3) * stepLat;
            double centerLon = lon + (cell[0] - 3) * stepLon;
            xml.append(String.format(Locale.ROOT,
                    "<rtept lat=\"%.7f\" lon=\"%.7f\"><name>Coverage %d (%d observations)</name></rtept>",
                    centerLat, centerLon, i + 1, cell[2]));
        }
        return xml.append("</rte></gpx>").toString();
    }
}
