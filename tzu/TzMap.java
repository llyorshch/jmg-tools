///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES lib/Term.java lib/Config.java lib/Geo.java lib/Ui.java lib/Live.java lib/Dates.java lib/Dst.java lib/WorldMap.java lib/Net.java lib/Holidays.java lib/Json.java
//FILES data/cities.tsv=data/cities.tsv
//FILES data/countries.tsv=data/countries.tsv
//FILES data/landmask.txt=data/landmask.txt
//FILES META-INF/native-image/tzu/reachability-metadata.json=native/reachability-metadata.json
//DESCRIPTION World map of timezones with day/night, plus the daylight saving time rules

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.tui.event.KeyCode;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.Callable;

@Command(name = "tz-map", mixinStandardHelpOptions = true, version = "tz-map 1.0",
        description = {"World map of the timezones (coloured by UTC offset, night side shaded, your zones marked)",
                "followed by the daylight saving time rules of your zones or of every country."},
        footer = {"", "Examples:",
                "  tz-map                         map + DST rules of the configured zones",
                "  tz-map --mode dst              colour the map by DST status (in DST / standard / no DST)",
                "  tz-map --at 2027-07-01T12:00   the world on a summer day",
                "  tz-map --rules                 DST rule sets of every country, grouped",
                "  tz-map -i                      interactive: ←/→ hours, ↑/↓ days, PgUp/PgDn months, m mode, n now"})
public class TzMap implements Callable<Integer> {

    @Option(names = {"-z", "--zones"}, paramLabel = "LIST", description = "Comma separated places to mark instead of the configured zones")
    String zones;

    @Option(names = "--at", paramLabel = "DATETIME", description = "Local date-time to show (yyyy-MM-dd[THH:mm], default now)")
    String at;

    @Option(names = "--mode", description = "Map colouring: offset (default) or dst", defaultValue = "offset")
    String mode;

    @Option(names = "--rules", description = "Show the DST rule sets of every country, grouped, instead of the configured zones")
    boolean allRules;

    @Option(names = "--map-only", description = "Only draw the map")
    boolean mapOnly;

    @Option(names = "--no-map", description = "Only show the DST tables")
    boolean noMap;

    @Option(names = "--no-night", description = "Do not shade the night side")
    boolean noNight;

    @Option(names = {"-W", "--width"}, description = "Map width in columns (default: terminal width)")
    Integer mapWidth;

    @Option(names = {"-i", "--interactive"}, description = "Interactive full-screen map")
    boolean interactive;

    @Option(names = "--no-color", description = "Disable colours (also honours NO_COLOR)")
    boolean noColor;

    @Option(names = "--json", description = "Print the DST data as JSON, without the map (not with --interactive or --map-only)")
    boolean json;

    List<Config.Entry> entries;
    ZoneId local;
    Instant instant;
    boolean live = true; // follows the clock until the user moves in time

    public static void main(String... args) {
        System.exit(new CommandLine(new TzMap()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (noColor) Term.noColor = true;
        if (json && (interactive || mapOnly)) {
            Term.error("--json cannot be combined with --interactive or --map-only");
            return 2;
        }
        Config cfg = Config.load();
        local = Config.localZone();
        try {
            entries = Config.withLocal(cfg.zonesOrOverride(zones), local);
            instant = Instant.now();
            if (at != null) {
                String v = at.trim().replace(' ', 'T');
                instant = (v.length() <= 10 ? LocalDateTime.parse(v + "T12:00") : LocalDateTime.parse(v)).atZone(local).toInstant();
                live = false;
            }
        } catch (Exception e) {
            Term.error(e instanceof IllegalArgumentException ? e.getMessage() : "Invalid --at value, expected yyyy-MM-dd[THH:mm]");
            return 2;
        }
        if (json) {
            Term.json(allRules ? rulesJson() : zonesJson());
            return 0;
        }
        if (interactive) {
            Live.run(Duration.ofSeconds(20), (area, buf) -> {
                if (live) instant = Instant.now();
                renderInteractive(area, buf);
            }, k -> {
                if (k.isKey(KeyCode.LEFT) || k.isChar('h')) shift(-3600);
                else if (k.isKey(KeyCode.RIGHT) || k.isChar('l')) shift(3600);
                else if (k.isKey(KeyCode.UP) || k.isChar('k')) shift(-86400);
                else if (k.isKey(KeyCode.DOWN) || k.isChar('j')) shift(86400);
                else if (k.isKey(KeyCode.PAGE_UP)) instant = instant.atZone(local).minusMonths(1).toInstant();
                else if (k.isKey(KeyCode.PAGE_DOWN)) instant = instant.atZone(local).plusMonths(1).toInstant();
                else if (k.isChar('m')) mode = mode.equals("dst") ? "offset" : "dst";
                else if (k.isChar('n')) { instant = Instant.now(); live = true; }
                if (k.isKey(KeyCode.PAGE_UP) || k.isKey(KeyCode.PAGE_DOWN)) live = false;
                return true;
            });
            return 0;
        }
        int width = mapWidth != null ? mapWidth : Term.width();
        width = Math.max(40, width);
        if (!noMap) {
            int mapH = mapHeight(width - 2);
            Term.print(width, mapH + 2 + 3, (area, buf) -> renderMap(new Rect(area.x(), area.y(), area.width(), mapH + 2), buf, true));
        }
        if (!mapOnly) {
            if (allRules) {
                var groups = ruleGroups();
                int w = Math.min(width, 160);
                List<Row> rows = ruleRows(groups, w);
                int h = rows.stream().mapToInt(Row::height).sum() + 3;
                Term.print(w, h, (area, buf) -> renderRuleGroups(area, buf, rows));
            } else {
                Term.print(Math.min(width, 160), entries.size() + 4, this::renderDstTable);
            }
        }
        return 0;
    }

    String instantText() {
        return instant.truncatedTo(java.time.temporal.ChronoUnit.SECONDS).toString();
    }

    Object zonesJson() {
        List<Config.Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(e -> e.zone().getRules().getOffset(instant).getTotalSeconds()));
        List<Object> rows = new ArrayList<>();
        for (Config.Entry e : sorted) {
            var row = Json.obj("label", e.label(), "zone", e.zone().getId(),
                    "is_local", e.zone().getId().equals(local.getId()),
                    "utc_offset", Json.offset(e.zone().getRules().getOffset(instant)));
            row.putAll(Dst.toJson(Dst.info(e.zone(), instant)));
            rows.add(row);
        }
        return Json.obj("instant", instantText(), "zones", rows);
    }

    Object rulesJson() {
        List<Object> sets = new ArrayList<>();
        List<Object> noDst = new ArrayList<>();
        for (Group g : ruleGroups()) {
            List<Object> countries = new ArrayList<>();
            for (String cc : g.countries()) countries.add(Json.obj("code", cc, "name", Geo.countryName(cc)));
            if (g.signature().isEmpty()) {
                noDst = countries;
                continue;
            }
            String[] rule = startsAndEnds(g.signature());
            sets.add(Json.obj("dst_starts", rule[0], "dst_ends", rule[1],
                    "dst_active", Dst.info(g.sample(), instant).active(), "countries", countries));
        }
        return Json.obj("instant", instantText(), "rule_sets", sets, "countries_without_dst", noDst);
    }

    /** Splits a {@link Dst#signature} into its {start, end} rules; a missing rule is null. */
    static String[] startsAndEnds(String signature) {
        String[] rule = new String[2];
        for (String part : signature.split("  ")) {
            if (part.startsWith("▲")) rule[0] = part.substring(2);
            else if (part.startsWith("▼")) rule[1] = part.substring(2);
        }
        return rule;
    }

    void shift(long seconds) {
        instant = instant.plusSeconds(seconds);
        live = false;
    }

    int mapHeight(int w) {
        // equirectangular: 2 pixels per cell vertically, cells are ~2x taller than wide
        double degPerCol = 360.0 / w;
        return (int) Math.round((WorldMap.TOP_LAT - WorldMap.BOTTOM_LAT) / degPerCol / 2.0);
    }

    void renderInteractive(Rect area, Buffer buf) {
        int w = area.width() - 2;
        int mapH = Math.min(mapHeight(w), area.height() - 6 - Math.min(entries.size(), 8));
        renderMap(new Rect(area.x(), area.y(), area.width(), mapH + 2 + 3), buf, true);
        Rect rest = new Rect(area.x(), area.y() + mapH + 5, area.width(), Math.max(0, area.bottom() - (area.y() + mapH + 5)));
        if (rest.height() > 3) renderDstTable(rest, buf);
    }

    void renderMap(Rect box, Buffer buf, boolean legend) {
        boolean dstMode = "dst".equalsIgnoreCase(mode);
        ZonedDateTime here = instant.atZone(local);
        Line title = Line.from(Span.styled(" ◷ tzu ", Ui.TITLE), Span.styled(dstMode ? "daylight saving map · " : "timezone map · ", Ui.DIM),
                Span.styled(here.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH)) + " " + Geo.labelFor(local), Style.create().fg(Ui.BRIGHT).bold()),
                Span.styled(" · " + instant.atZone(ZoneOffset.UTC).format(DateTimeFormatter.ofPattern("HH:mm 'UTC' ", Locale.ENGLISH)), Ui.DIM));
        Line bottom = interactive ? Line.from(Span.styled(" ←/→ hour  ↑/↓ day  PgUp/PgDn month  m mode  n now  q quit ", Ui.DIM)) : null;
        int mapRows = box.height() - 2 - (legend ? 3 : 0);
        Rect panel = new Rect(box.x(), box.y(), box.width(), mapRows + 2);
        Ui.panel(title, bottom).render(panel, buf);
        Rect mapArea = new Rect(panel.x() + 1, panel.y() + 1, panel.width() - 2, mapRows);

        List<WorldMap.Marker> markers = new ArrayList<>();
        Color[] palette = {Color.rgb(0xff, 0xff, 0xff), Color.rgb(0xff, 0xf0, 0x80)};
        for (Config.Entry e : entries) {
            double[] loc = WorldMap.locate(e.label(), e.zone());
            if (loc == null) continue;
            ZonedDateTime t = instant.atZone(e.zone());
            boolean isLocal = e.zone().getId().equals(local.getId());
            markers.add(new WorldMap.Marker(loc[0], loc[1], e.label() + " " + t.format(DateTimeFormatter.ofPattern("HH:mm")),
                    isLocal ? palette[1] : palette[0]));
        }
        Map<Integer, Integer> seen = WorldMap.render(buf, mapArea, instant, dstMode ? WorldMap.Mode.DST : WorldMap.Mode.OFFSET,
                !noNight, markers, WorldMap.TOP_LAT, WorldMap.BOTTOM_LAT);

        // nominal (solar) offsets along the bottom border, every 30°
        if (bottom == null) {
            for (int lonDeg = -150; lonDeg <= 165; lonDeg += 30) {
                int x = mapArea.x() + (int) Math.floor((lonDeg + 180) / 360.0 * mapArea.width());
                String lbl = WorldMap.offsetShort(lonDeg / 15 * 3600);
                x -= lbl.length() / 2;
                if (x + lbl.length() < panel.right() - 1 && x > panel.x()) buf.setString(x, panel.bottom() - 1, lbl, Ui.DIM);
            }
        }
        if (!legend) return;
        int y = panel.bottom();
        if (dstMode) {
            buf.setLine(box.x() + 1, y, Ui.legend(Color.rgb(0xff, 0x9e, 0x40), "daylight saving time now", Color.rgb(0x3d, 0xa5, 0xd9),
                    "standard time now (observes DST)", Color.rgb(0x56, 0x5f, 0x89), "no DST")
                    .append(Line.from(Span.styled("● your zones  ☀ sun  shaded = night", Ui.DIM))), box.width() - 2);
            return;
        }
        // offset legend: one swatch per offset visible on land, sorted
        List<Span> spans = new ArrayList<>();
        int used = 0, maxW = box.width() - 2;
        int line = 0;
        for (int off : new TreeMap<>(seen).keySet()) {
            String lbl = WorldMap.offsetShort(off);
            int need = 3 + lbl.length() + 1;
            if (used + need > maxW) {
                buf.setLine(box.x() + 1, y + line, Line.from(spans), maxW);
                spans = new ArrayList<>();
                used = 0;
                if (++line >= 2) break;
            }
            spans.add(Span.styled("██", Style.create().fg(Ui.offsetColor(off))));
            spans.add(Span.styled(lbl + "  ", Ui.DIM));
            used += need + 1;
        }
        if (line < 2 && !spans.isEmpty()) buf.setLine(box.x() + 1, y + line, Line.from(spans), maxW);
        if (y + 2 < buf.area().bottom()) {
            buf.setLine(box.x() + 1, y + 2, Line.from(Span.styled("UTC offsets of the land (borders approximated from 7k+ cities) · ● your zones · ☀ subsolar point · shaded = night", Ui.DIM)), maxW);
        }
    }

    void renderDstTable(Rect area, Buffer buf) {
        boolean wide = area.width() >= 150;
        List<Row> rows = new ArrayList<>();
        List<Config.Entry> sorted = new ArrayList<>(entries);
        sorted.sort(Comparator.comparingInt(e -> e.zone().getRules().getOffset(instant).getTotalSeconds()));
        for (Config.Entry e : sorted) {
            Dst.Info d = Dst.info(e.zone(), instant);
            Style state = d.active() ? Style.create().fg(Color.rgb(0xff, 0x9e, 0x40)).bold()
                    : d.observes() ? Style.create().fg(Color.rgb(0x3d, 0xa5, 0xd9)) : Ui.DIM;
            boolean isLocal = e.zone().getId().equals(local.getId());
            List<Cell> cells = new ArrayList<>(List.of(
                    Cell.from(Span.styled("██", Style.create().fg(Ui.offsetColor(e.zone().getRules().getOffset(instant).getTotalSeconds())))),
                    Cell.from(Line.from(Span.styled(e.label(), isLocal ? Style.create().fg(Ui.BRIGHT).bold() : Ui.NORMAL),
                            Span.styled(!wide && d.active() ? " ☼" : "", state)))));
            if (wide) cells.add(Cell.from(Span.styled(e.zone().getId(), Ui.DIM)));
            cells.add(Cell.from(Span.styled(Ui.offset(d.standard()), Ui.NORMAL)));
            cells.add(Cell.from(Span.styled(d.daylight() == null ? "—" : Ui.offset(d.daylight()), Ui.NORMAL)));
            if (wide) cells.add(Cell.from(Span.styled(d.active() ? "☼ summer" : d.observes() ? "standard" : "no DST", state)));
            cells.add(Cell.from(Span.styled(d.starts(), Style.create().fg(Ui.GOOD))));
            cells.add(Cell.from(Span.styled(d.ends(), Style.create().fg(Ui.WARN))));
            cells.add(Cell.from(Span.styled(wide ? Dst.transition(d.next()) : Dst.transitionShort(d.next()), Ui.NORMAL)));
            rows.add(Row.from(cells));
        }
        Row header = wide ? Row.from("", "Place", "Zone", "Standard", "DST", "Now", "DST starts", "DST ends", "Next change").style(Ui.HEADER)
                : Row.from("", "Place", "Standard", "DST", "DST starts", "DST ends", "Next change").style(Ui.HEADER);
        List<Constraint> widths = new ArrayList<>(List.of(Constraint.length(2), Constraint.fill()));
        if (wide) widths.add(Constraint.length(20));
        widths.addAll(List.of(Constraint.length(8), Constraint.length(8)));
        if (wide) widths.add(Constraint.length(8));
        widths.addAll(List.of(Constraint.length(22), Constraint.length(22), Constraint.length(wide ? 31 : 23)));
        Ui.table(buf, new Rect(area.x(), area.y(), area.width(), Math.min(area.height(), rows.size() + 3)), header, rows, widths,
                Ui.panel("daylight saving time rules"));
    }

    record Group(String signature, List<String> countries, ZoneId sample) {}

    List<Group> ruleGroups() {
        Map<String, List<String>> bySig = new LinkedHashMap<>();
        Map<String, ZoneId> sample = new LinkedHashMap<>();
        for (var c : Geo.countries().values()) {
            for (String z : c.zones()) {
                ZoneId zid = ZoneId.of(z);
                String sig = Dst.signature(zid);
                if (sig.isEmpty()) continue;
                String key = sig + "|" + zid.getRules().getStandardOffset(instant).getTotalSeconds();
                var list = bySig.computeIfAbsent(key, k -> new ArrayList<>());
                if (!list.contains(c.iso())) list.add(c.iso());
                sample.putIfAbsent(key, zid);
            }
        }
        // merge groups that share the same rule regardless of offset (e.g. EU across CET/EET/WET)
        Map<String, Group> merged = new LinkedHashMap<>();
        for (var e : bySig.entrySet()) {
            String sig = e.getKey().substring(0, e.getKey().lastIndexOf('|'));
            Group g = merged.get(sig);
            if (g == null) merged.put(sig, new Group(sig, new ArrayList<>(e.getValue()), sample.get(e.getKey())));
            else for (String cc : e.getValue()) if (!g.countries().contains(cc)) g.countries().add(cc);
        }
        List<Group> groups = new ArrayList<>(merged.values());
        groups.sort(Comparator.comparingInt((Group g) -> -g.countries().size()));
        // countries that don't observe DST at all
        List<String> none = new ArrayList<>();
        for (var c : Geo.countries().values()) {
            boolean any = c.zones().stream().anyMatch(z -> Dst.info(ZoneId.of(z), instant).observes());
            if (!any) none.add(c.iso());
        }
        groups.add(new Group("", none, null));
        return groups;
    }

    static List<String> wrap(List<String> words, int width) {
        List<String> lines = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        for (int i = 0; i < words.size(); i++) {
            String wd = words.get(i) + (i < words.size() - 1 ? "," : "");
            if (cur.length() > 0 && cur.length() + 1 + wd.length() > width) {
                lines.add(cur.toString());
                cur = new StringBuilder();
            }
            if (cur.length() > 0) cur.append(' ');
            cur.append(wd);
        }
        if (cur.length() > 0) lines.add(cur.toString());
        return lines;
    }

    List<Row> ruleRows(List<Group> groups, int width) {
        int countriesW = Math.max(20, width - 4 - (3 + 22 + 22 + 9) - 4);
        List<Row> rows = new ArrayList<>();
        for (Group g : groups) {
            List<String> names = new ArrayList<>();
            for (String cc : g.countries()) names.add(Geo.countryName(cc));
            String starts = "—", ends = "—";
            if (!g.signature().isEmpty()) {
                String[] rule = startsAndEnds(g.signature());
                if (rule[0] != null) starts = rule[0];
                if (rule[1] != null) ends = rule[1];
            }
            Dst.Info info = g.sample() == null ? null : Dst.info(g.sample(), instant);
            List<Line> lines = new ArrayList<>();
            List<String> wrapped = wrap(names, countriesW);
            if (g.signature().isEmpty() && wrapped.size() > 3) {
                // "no DST" is most of the world: keep it short
                int shown = 0;
                for (int i = 0; i < 3; i++) shown += wrapped.get(i).split(", ").length;
                wrapped = new ArrayList<>(wrapped.subList(0, 3));
                wrapped.add("… and " + (names.size() - shown) + " more");
            }
            for (String l : wrapped) lines.add(Line.from(Span.styled(l, g.signature().isEmpty() ? Ui.DIM : Ui.NORMAL)));
            rows.add(Row.from(
                    Cell.from(Span.styled(String.valueOf(g.countries().size()), Style.create().fg(Ui.ACCENT).bold())),
                    Cell.from(Span.styled(g.signature().isEmpty() ? "no DST" : starts, Style.create().fg(g.signature().isEmpty() ? Ui.MUTED : Ui.GOOD))),
                    Cell.from(Span.styled(ends, Style.create().fg(Ui.WARN))),
                    Cell.from(Span.styled(info == null ? "" : info.active() ? "☼ summer" : "standard",
                            info != null && info.active() ? Style.create().fg(Color.rgb(0xff, 0x9e, 0x40)) : Ui.DIM)),
                    Cell.from(dev.tamboui.text.Text.from(lines))).height(Math.max(1, lines.size())));
        }
        return rows;
    }

    void renderRuleGroups(Rect area, Buffer buf, List<Row> rows) {
        Row header = Row.from("#", "DST starts", "DST ends", "Now", "Countries").style(Ui.HEADER);
        Ui.table(buf, area, header, rows,
                List.of(Constraint.length(3), Constraint.length(22), Constraint.length(22), Constraint.length(9), Constraint.fill()),
                Ui.panel("daylight saving time rule sets worldwide · " + instant.atZone(local).toLocalDate()));
    }
}
