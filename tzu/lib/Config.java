import java.io.IOException;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Shared configuration for every tzu command, stored in
 * {@code $XDG_CONFIG_HOME/tzu/config.properties} (default {@code ~/.config/tzu}).
 *
 * <pre>
 * zones=America/Los_Angeles,New York=America/New_York,Barcelona=Europe/Madrid
 * work.start=9
 * work.end=18
 * clock=24
 * </pre>
 */
final class Config {
    /** A timezone entry with a display label. */
    record Entry(String label, ZoneId zone) {
        String serialize() {
            return label.equals(Geo.labelFor(zone)) ? zone.getId() : label + "=" + zone.getId();
        }
    }

    static final List<String> DEFAULT_ZONES = List.of(
            "America/Los_Angeles", "America/New_York", "America/Sao_Paulo", "UTC",
            "Europe/London", "Europe/Paris", "Asia/Dubai", "Asia/Kolkata",
            "Asia/Singapore", "Asia/Shanghai", "Asia/Tokyo", "Australia/Sydney");

    final List<Entry> zones = new ArrayList<>();
    int workStart = 9;
    int workEnd = 18;
    boolean clock12 = false;

    static Path dir() {
        String xdg = System.getenv("XDG_CONFIG_HOME");
        Path base = xdg != null && !xdg.isBlank() ? Path.of(xdg) : Path.of(System.getProperty("user.home"), ".config");
        return base.resolve("tzu");
    }

    static Path file() {
        String override = System.getenv("TZU_CONFIG");
        return override != null ? Path.of(override) : dir().resolve("config.properties");
    }

    static Config load() {
        Config c = new Config();
        Properties p = new Properties();
        Path f = file();
        if (Files.isRegularFile(f)) {
            try (Reader r = Files.newBufferedReader(f, StandardCharsets.UTF_8)) {
                p.load(r);
            } catch (IOException e) {
                Term.error("tzu: cannot read " + f + ": " + e.getMessage());
            }
        }
        String zones = p.getProperty("zones");
        List<String> items = zones == null || zones.isBlank() ? DEFAULT_ZONES : List.of(zones.split(","));
        for (String item : items) {
            Entry e = parseEntry(item.trim());
            if (e != null) c.zones.add(e);
        }
        c.workStart = intProp(p, "work.start", 9);
        c.workEnd = intProp(p, "work.end", 18);
        c.clock12 = "12".equals(p.getProperty("clock", "24").trim());
        return c;
    }

    void save() {
        Properties p = new Properties();
        List<String> z = new ArrayList<>();
        for (Entry e : zones) z.add(e.serialize());
        p.setProperty("zones", String.join(",", z));
        p.setProperty("work.start", String.valueOf(workStart));
        p.setProperty("work.end", String.valueOf(workEnd));
        p.setProperty("clock", clock12 ? "12" : "24");
        Path f = file();
        try {
            Files.createDirectories(f.getParent());
            try (Writer w = Files.newBufferedWriter(f, StandardCharsets.UTF_8)) {
                p.store(w, "tzu timezone utilities");
            }
        } catch (IOException e) {
            Term.error("tzu: cannot write " + f + ": " + e.getMessage());
        }
    }

    private static int intProp(Properties p, String key, int def) {
        try {
            return Integer.parseInt(p.getProperty(key, String.valueOf(def)).trim());
        } catch (NumberFormatException e) {
            return def;
        }
    }

    /** Parses "Label=Zone/Id", "Zone/Id" or any place name understood by {@link Geo#resolve}. */
    static Entry parseEntry(String item) {
        if (item.isEmpty()) return null;
        int eq = item.indexOf('=');
        if (eq > 0) {
            String label = item.substring(0, eq).trim();
            var place = Geo.resolve(item.substring(eq + 1).trim());
            return place.map(pl -> new Entry(label, pl.zone())).orElse(null);
        }
        return Geo.resolve(item).map(pl -> new Entry(pl.label(), pl.zone())).orElse(null);
    }

    /**
     * Resolves a comma separated list of places given on the command line (or the configured
     * zones when {@code spec} is null), failing loudly on unknown names.
     */
    List<Entry> zonesOrOverride(String spec) {
        if (spec == null || spec.isBlank()) return zones;
        List<Entry> out = new ArrayList<>();
        for (String s : spec.split(",")) {
            if (s.isBlank()) continue;
            Entry e = parseEntry(s.trim());
            if (e == null) throw new IllegalArgumentException("Unknown timezone or place: '" + s.trim() + "' (try tz-find)");
            out.add(e);
        }
        return out;
    }

    /** Configured zones plus the local zone (first), without duplicates (by zone id + label). */
    static List<Entry> withLocal(List<Entry> zones, ZoneId local) {
        Map<String, Entry> m = new LinkedHashMap<>();
        boolean hasLocal = zones.stream().anyMatch(e -> e.zone().normalized().equals(local.normalized())
                || e.zone().getId().equals(local.getId()));
        if (!hasLocal) m.put(local.getId(), new Entry(Geo.labelFor(local), local));
        for (Entry e : zones) m.putIfAbsent(e.zone().getId() + "|" + e.label(), e);
        return new ArrayList<>(m.values());
    }

    static ZoneId localZone() {
        String override = System.getenv("TZU_LOCAL");
        if (override != null && !override.isBlank()) {
            var p = Geo.resolve(override);
            if (p.isPresent()) return p.get().zone();
        }
        return ZoneId.systemDefault();
    }
}
