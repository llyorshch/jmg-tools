import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Equirectangular world map drawn with half blocks (two pixels per cell).
 * Land is coloured by the UTC offset of the nearest known city (an approximation of the real
 * timezone borders), the night side is shaded using the computed solar position.
 */
final class WorldMap {
    enum Mode { OFFSET, DST }

    record Marker(double lat, double lon, String label, Color color) {}

    static final double TOP_LAT = 84, BOTTOM_LAT = -58;

    private static boolean[][] land;

    static synchronized boolean[][] land() {
        if (land != null) return land;
        boolean[][] m = new boolean[180][360];
        try (var r = new BufferedReader(new InputStreamReader(Geo.open("data/landmask.txt"), StandardCharsets.UTF_8))) {
            String line;
            int row = 0;
            while ((line = r.readLine()) != null && row < 180) {
                if (line.startsWith("#") || line.isBlank()) continue;
                BigInteger bits = new BigInteger(line.trim(), 16);
                for (int c = 0; c < 360; c++) m[row][c] = bits.testBit(359 - c);
                row++;
            }
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        land = m;
        return m;
    }

    static boolean isLand(double lat, double lon) {
        int row = (int) Math.floor(90 - lat);
        int col = (int) Math.floor(lon + 180);
        if (row < 0 || row >= 180) return false;
        col = Math.floorMod(col, 360);
        return land()[row][col];
    }

    // ------------------------------------------------------------------ nearest zone lookup (bucketed)
    private static Map<Integer, List<Geo.City>> buckets;

    private static synchronized Map<Integer, List<Geo.City>> buckets() {
        if (buckets == null) {
            Map<Integer, List<Geo.City>> b = new HashMap<>();
            for (Geo.City c : Geo.cities()) b.computeIfAbsent(bucket(c.lat(), c.lon()), k -> new ArrayList<>()).add(c);
            buckets = b;
        }
        return buckets;
    }

    private static int bucket(double lat, double lon) {
        int by = (int) Math.floor((lat + 90) / 5), bx = (int) Math.floor((lon + 180) / 5);
        return by * 100 + Math.floorMod(bx, 72);
    }

    /** Zone of the nearest city to a point (searches growing rings of 5° buckets). */
    static ZoneId nearestZone(double lat, double lon) {
        var b = buckets();
        int by = (int) Math.floor((lat + 90) / 5), bx = (int) Math.floor((lon + 180) / 5);
        Geo.City best = null;
        double bestD = Double.MAX_VALUE;
        for (int ring = 0; ring < 12; ring++) {
            for (int dy = -ring; dy <= ring; dy++) {
                for (int dx = -ring; dx <= ring; dx++) {
                    if (Math.max(Math.abs(dx), Math.abs(dy)) != ring) continue;
                    var list = b.get((by + dy) * 100 + Math.floorMod(bx + dx, 72));
                    if (list == null) continue;
                    for (Geo.City c : list) {
                        double d = dist(lat, lon, c.lat(), c.lon());
                        if (d < bestD) { bestD = d; best = c; }
                    }
                }
            }
            if (best != null && ring >= 1 && bestD < Math.pow(ring * 5.0 * 111, 2) * 0.8) break;
        }
        return best == null ? ZoneOffset.UTC : best.zone();
    }

    private static double dist(double la1, double lo1, double la2, double lo2) {
        double dlon = Math.abs(lo1 - lo2);
        if (dlon > 180) dlon = 360 - dlon;
        double x = dlon * Math.cos(Math.toRadians((la1 + la2) / 2)) * 111;
        double y = (la1 - la2) * 111;
        return x * x + y * y;
    }

    // ------------------------------------------------------------------ sun
    /** Subsolar point {lat, lon} for an instant (NOAA approximation, accurate to ~0.5°). */
    static double[] subsolar(Instant t) {
        double jd = t.toEpochMilli() / 86400000.0 + 2440587.5;
        double n = jd - 2451545.0;
        double L = Math.toRadians((280.460 + 0.9856474 * n) % 360);
        double g = Math.toRadians((357.528 + 0.9856003 * n) % 360);
        double lambda = L + Math.toRadians(1.915) * Math.sin(g) + Math.toRadians(0.020) * Math.sin(2 * g);
        double eps = Math.toRadians(23.439 - 0.0000004 * n);
        double decl = Math.asin(Math.sin(eps) * Math.sin(lambda));
        double ra = Math.atan2(Math.cos(eps) * Math.sin(lambda), Math.cos(lambda));
        double eqTime = (L - ra); // radians
        eqTime = Math.atan2(Math.sin(eqTime), Math.cos(eqTime));
        double utcHours = (t.getEpochSecond() % 86400 + 86400) % 86400 / 3600.0;
        double lon = -15 * (utcHours - 12 + Math.toDegrees(eqTime) / 15);
        lon = ((lon + 540) % 360) - 180;
        return new double[]{Math.toDegrees(decl), lon};
    }

    /** 1 = full daylight, 0 = night, in between = twilight. */
    static double daylight(double lat, double lon, double[] sun) {
        double phi = Math.toRadians(lat), d = Math.toRadians(sun[0]);
        double cosZ = Math.sin(phi) * Math.sin(d) + Math.cos(phi) * Math.cos(d) * Math.cos(Math.toRadians(lon - sun[1]));
        if (cosZ > 0.02) return 1;
        if (cosZ < -0.2) return 0;
        return (cosZ + 0.2) / 0.22;
    }

    // ------------------------------------------------------------------ colours
    static final Color OCEAN_A = Color.rgb(0x10, 0x1c, 0x34);
    static final Color OCEAN_B = Color.rgb(0x13, 0x22, 0x3e);

    static Color dstColor(ZoneId z, Instant at) {
        var rules = z.getRules();
        boolean observes = !rules.getTransitionRules().isEmpty() || rules.nextTransition(at) != null
                && rules.nextTransition(at).getInstant().isBefore(at.plusSeconds(400L * 86400));
        if (!observes) return Color.rgb(0x56, 0x5f, 0x89);
        return rules.isDaylightSavings(at) ? Color.rgb(0xff, 0x9e, 0x40) : Color.rgb(0x3d, 0xa5, 0xd9);
    }

    /**
     * Draws the map into {@code area}.
     *
     * @return the zones visible on land (for legends)
     */
    static Map<Integer, Integer> render(Buffer buf, Rect area, Instant at, Mode mode, boolean night, List<Marker> markers,
                                        double topLat, double bottomLat) {
        int w = area.width(), hPx = area.height() * 2;
        double[] sun = subsolar(at);
        Map<Integer, Integer> offsetsSeen = new HashMap<>(); // offset seconds -> pixel count
        Map<ZoneId, Color> zoneColor = new HashMap<>();
        Color[][] px = new Color[hPx][w];
        for (int y = 0; y < hPx; y++) {
            double lat = topLat - (y + 0.5) * (topLat - bottomLat) / hPx;
            for (int x = 0; x < w; x++) {
                double lon = -180 + (x + 0.5) * 360.0 / w;
                Color c;
                if (isLand(lat, lon)) {
                    ZoneId z = nearestZone(lat, lon);
                    c = zoneColor.computeIfAbsent(z, zz -> mode == Mode.DST ? dstColor(zz, at)
                            : Ui.offsetColor(zz.getRules().getOffset(at).getTotalSeconds()));
                    offsetsSeen.merge(z.getRules().getOffset(at).getTotalSeconds(), 1, Integer::sum);
                } else {
                    int band = (int) Math.floor((lon + 7.5) / 15);
                    c = Math.floorMod(band, 2) == 0 ? OCEAN_A : OCEAN_B;
                }
                if (night) {
                    double d = daylight(lat, lon, sun);
                    c = Ui.shade(c, 0.38 + 0.62 * d);
                }
                px[y][x] = c;
            }
        }
        for (int row = 0; row < area.height(); row++) {
            for (int x = 0; x < w; x++) {
                Color top = px[row * 2][x], bot = row * 2 + 1 < hPx ? px[row * 2 + 1][x] : top;
                buf.setString(area.x() + x, area.y() + row, "▀", Style.create().fg(top).bg(bot));
            }
        }
        // the sun
        if (night) {
            int[] s = toCell(area, sun[0], sun[1], topLat, bottomLat);
            if (s != null) buf.setString(s[0], s[1], "☀", Style.create().fg(Color.rgb(0xff, 0xe0, 0x40)).bg(cellBg(buf, s)).bold());
        }
        // markers with labels, avoiding overlaps
        boolean[][] used = new boolean[area.height()][w];
        for (Marker m : markers) {
            int[] c = toCell(area, m.lat(), m.lon(), topLat, bottomLat);
            if (c == null) continue;
            buf.setString(c[0], c[1], "●", Style.create().fg(m.color()).bg(cellBg(buf, c)).bold());
            used[c[1] - area.y()][c[0] - area.x()] = true;
        }
        for (Marker m : markers) {
            if (m.label() == null || m.label().isEmpty()) continue;
            int[] c = toCell(area, m.lat(), m.lon(), topLat, bottomLat);
            if (c == null) continue;
            String label = m.label();
            int len = label.length();
            int lx = c[0] + 1;
            if (lx + len >= area.right()) lx = c[0] - len; // put it on the left side
            int ly = c[1] - area.y();
            if (lx < area.x()) continue;
            boolean free = true;
            for (int i = lx > c[0] ? 0 : -1; i <= (lx > c[0] ? len : len - 1); i++) {
                int xx = lx - area.x() + i;
                if (xx >= 0 && xx < w && used[ly][xx]) free = false;
            }
            if (!free) continue;
            for (int i = 0; i < len; i++) {
                buf.setString(lx + i, c[1], String.valueOf(label.charAt(i)),
                        Style.create().fg(Ui.BRIGHT).bg(Ui.shade(cellBg(buf, new int[]{lx + i, c[1]}), 0.55)).bold());
                used[ly][lx - area.x() + i] = true;
            }
        }
        return offsetsSeen;
    }

    private static Color cellBg(Buffer buf, int[] c) {
        var cell = buf.get(c[0], c[1]);
        return cell.style().bg().orElse(OCEAN_A);
    }

    static int[] toCell(Rect area, double lat, double lon, double topLat, double bottomLat) {
        if (lat > topLat || lat < bottomLat) return null;
        int x = (int) Math.floor((lon + 180) / 360.0 * area.width());
        int y = (int) Math.floor((topLat - lat) / (topLat - bottomLat) * area.height());
        x = Math.max(0, Math.min(area.width() - 1, x));
        y = Math.max(0, Math.min(area.height() - 1, y));
        return new int[]{area.x() + x, area.y() + y};
    }

    /** Best known location of a zone: a city with the zone's own name, else its biggest city. */
    static double[] locate(String label, ZoneId zone) {
        Geo.City best = null;
        String nl = Geo.norm(label);
        String nz = Geo.norm(Geo.labelFor(zone));
        for (Geo.City c : Geo.cities()) {
            if (!c.zone().getId().equals(zone.getId())) continue;
            String n = Geo.norm(c.name());
            if (n.equals(nl) || c.key().equals(nl)) return new double[]{c.lat(), c.lon()};
            if (n.equals(nz) || c.key().equals(nz)) best = c;
            if (best == null) best = c;
        }
        if (best == null) {
            for (Geo.City c : Geo.cities()) {
                if (Geo.norm(c.name()).equals(nl) || c.key().equals(nl)) return new double[]{c.lat(), c.lon()};
            }
            return null;
        }
        return new double[]{best.lat(), best.lon()};
    }

    static String offsetShort(int seconds) {
        if (seconds == 0) return "±0";
        int h = Math.abs(seconds) / 3600, m = Math.abs(seconds) % 3600 / 60;
        return (seconds < 0 ? "−" : "+") + h + (m != 0 ? ":" + String.format("%02d", m) : "");
    }

    static ZonedDateTime utc(Instant at) {
        return at.atZone(ZoneOffset.UTC);
    }
}
