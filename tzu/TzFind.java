///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES lib/Term.java lib/Config.java lib/Geo.java lib/Ui.java lib/Live.java lib/Dates.java lib/Dst.java lib/WorldMap.java lib/Net.java lib/Holidays.java
//FILES data/cities.tsv=data/cities.tsv
//FILES data/countries.tsv=data/countries.tsv
//FILES data/landmask.txt=data/landmask.txt
//FILES META-INF/native-image/tzu/reachability-metadata.json=native/reachability-metadata.json
//DESCRIPTION Find the timezone of a city, country, abbreviation or UTC offset

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.Callable;

@Command(name = "tz-find", mixinStandardHelpOptions = true, version = "tz-find 1.0",
        description = {"Finds the timezone of a city, a country (all its zones), an abbreviation (CET, PST, IST…),",
                "a zone id (Europe/Paris) or a UTC offset (+05:30). Uses an embedded database of 7,400+ cities",
                "and falls back to the Open-Meteo geocoding service for smaller places."},
        footer = {"", "Examples:",
                "  tz-find tokyo",
                "  tz-find 'san jose, costa rica'",
                "  tz-find Australia          every zone of a country",
                "  tz-find IST                abbreviations",
                "  tz-find +5:45              zones currently at an offset",
                "  tz-find Ushuaia --add      search and add the best match to your zones",
                "  tz-find Ponferrada --online"})
public class TzFind implements Callable<Integer> {

    @Parameters(arity = "1..*", paramLabel = "QUERY", description = "City, country, abbreviation, zone id or offset")
    List<String> query;

    @Option(names = {"-n", "--limit"}, description = "Maximum results (default 12)", defaultValue = "12")
    int limit;

    @Option(names = "--online", description = "Also search the Open-Meteo geocoding service (any town or village)")
    boolean online;

    @Option(names = "--offline", description = "Never use the network")
    boolean offline;

    @Option(names = "--add", description = "Add the best match to the configured zones")
    boolean add;

    @Option(names = "--no-map", description = "Do not draw the location map")
    boolean noMap;

    @Option(names = "--plain", description = "Print only the zone id of the best match (for scripts)")
    boolean plain;

    @Option(names = "--no-color", description = "Disable colours (also honours NO_COLOR)")
    boolean noColor;

    ZoneId local;

    public static void main(String... args) {
        System.exit(new CommandLine(new TzFind()).execute(args));
    }

    @Override
    public Integer call() {
        if (noColor) Term.noColor = true;
        local = Config.localZone();
        String q = String.join(" ", query).trim();
        List<Geo.Place> results = new ArrayList<>(Geo.search(q, limit));
        boolean usedOnline = false;
        if (!offline && (online || results.isEmpty())) {
            try {
                List<Geo.Place> remote = geocode(q);
                usedOnline = true;
                for (Geo.Place p : remote) {
                    boolean dup = results.stream().anyMatch(r -> r.label().equalsIgnoreCase(p.label()) && r.cc().equals(p.cc()));
                    if (!dup) results.add(p);
                }
                if (results.size() > limit) results = results.subList(0, limit);
            } catch (Exception e) {
                if (results.isEmpty()) Term.error("Online lookup failed: " + e.getMessage());
            }
        }
        if (results.isEmpty()) {
            Term.error("No timezone found for '" + q + "'." + (offline ? "" : " Try a different spelling or 'tz-find " + q + " --online'."));
            return 1;
        }
        Geo.Place best = results.get(0);
        if (plain) {
            Term.println(best.zone().getId());
            return 0;
        }
        Instant now = Instant.now();
        int width = Math.min(Term.width(), 130);
        int detailH = noMap ? 12 : Math.max(12, mapRows(width) + 2);
        int tableH = results.size() + 3;
        final List<Geo.Place> res = results;
        final boolean onlineUsed = usedOnline;
        Term.print(width, detailH + tableH, (area, buf) -> {
            renderDetail(buf, new Rect(area.x(), area.y(), area.width(), detailH), best, now);
            renderTable(buf, new Rect(area.x(), area.y() + detailH, area.width(), tableH), res, now, q, onlineUsed);
        });
        if (add) {
            Config cfg = Config.load();
            Config.Entry e = new Config.Entry(best.label(), best.zone());
            if (cfg.zones.stream().noneMatch(x -> x.zone().equals(e.zone()) && x.label().equals(e.label()))) {
                cfg.zones.add(e);
                cfg.save();
                Term.println("✔ added " + e.label() + " (" + e.zone().getId() + ") to " + Config.file());
            } else {
                Term.println("Already configured: " + e.label());
            }
        }
        return 0;
    }

    int mapRows(int width) {
        int mapW = mapWidth(width);
        return (int) Math.round((WorldMap.TOP_LAT - WorldMap.BOTTOM_LAT) / (360.0 / mapW) / 2.0);
    }

    int mapWidth(int width) {
        return Math.max(30, Math.min(64, width - 66));
    }

    List<Geo.Place> geocode(String q) throws Exception {
        String name = q, country = null;
        int comma = q.lastIndexOf(',');
        if (comma > 0) {
            name = q.substring(0, comma).trim();
            var c = Geo.findCountry(q.substring(comma + 1).trim());
            country = c != null ? c.iso() : null;
        }
        String url = "https://geocoding-api.open-meteo.com/v1/search?count=10&language=en&format=json&name=" + Net.enc(name);
        if (country != null) url += "&countryCode=" + country;
        Object json = Net.json(Net.get(url));
        List<Geo.Place> out = new ArrayList<>();
        if (json instanceof Map<?, ?> m && m.get("results") != null) {
            for (Map<String, Object> r : Net.list(m.get("results"))) {
                String tz = Net.str(r, "timezone");
                if (tz.isEmpty()) continue;
                try {
                    long pop = r.get("population") instanceof Double d ? d.longValue() : 0;
                    out.add(new Geo.Place(Net.str(r, "name"), ZoneId.of(tz), "online", Net.str(r, "country_code"),
                            (Double) r.get("latitude"), (Double) r.get("longitude"), pop, Net.str(r, "admin1")));
                } catch (Exception ignored) { }
            }
        }
        return out;
    }

    void renderDetail(Buffer buf, Rect area, Geo.Place p, Instant now) {
        ZonedDateTime t = now.atZone(p.zone());
        ZonedDateTime here = now.atZone(local);
        Dst.Info dst = Dst.info(p.zone(), now);
        int mapW = noMap ? 0 : mapWidth(area.width());
        Rect info = new Rect(area.x(), area.y(), area.width() - (noMap ? 0 : mapW + 2), area.height());
        Ui.panel(Line.from(Span.styled(" ◷ tzu ", Ui.TITLE), Span.styled("best match ", Ui.DIM)), null).render(info, buf);
        int x = info.x() + 2, y = info.y() + 1, w = info.width() - 4;
        String where = p.label() + (p.region() != null && !p.region().isBlank() && !p.region().equals(p.label()) ? ", " + p.region() : "");
        buf.setLine(x, y++, Line.from(Span.styled(where, Style.create().fg(Ui.BRIGHT).bold()),
                Span.styled(p.cc().isEmpty() ? "" : "  " + Geo.countryName(p.cc()) + " (" + p.cc() + ")", Style.create().fg(Ui.ACCENT2))), w);
        buf.setLine(x, y++, Line.from(Span.styled(p.zone().getId(), Style.create().fg(Ui.CYAN).bold()),
                Span.styled("  " + Geo.longName(p.zone()), Ui.DIM)), w);
        y++;
        String clock = t.format(DateTimeFormatter.ofPattern("HH:mm"));
        Ui.bigText(buf, x, y, clock, Ui.skyColor(t.toLocalTime()), Ui.ACCENT2);
        int tx = x + Ui.bigWidth(clock) + 2;
        int d = t.getOffset().getTotalSeconds() - here.getOffset().getTotalSeconds();
        buf.setLine(tx, y, Line.from(Span.styled(Ui.skyIcon(t.toLocalTime()) + " ", Style.create().fg(Ui.skyColor(t.toLocalTime()))),
                Span.styled(t.format(DateTimeFormatter.ofPattern("EEE d MMM yyyy", Locale.ENGLISH)), Ui.NORMAL)), Math.max(0, info.right() - tx - 2));
        buf.setLine(tx, y + 1, Line.from(Span.styled(Ui.offset(t.getOffset()), Style.create().fg(Ui.WARN)),
                Span.styled("  " + Ui.diff(d) + " from you", Ui.DIM)), Math.max(0, info.right() - tx - 2));
        buf.setLine(tx, y + 2, Line.from(Span.styled(Geo.displayName(p.zone(), dst.active()), Style.create().fg(Ui.ACCENT)),
                Span.styled(dst.active() ? "  ☼ DST" : dst.observes() ? "  standard time" : "  no DST", dst.active() ? Style.create().fg(Ui.GOOD) : Ui.DIM)),
                Math.max(0, info.right() - tx - 2));
        y += 4;
        if (dst.observes() && y < info.bottom() - 1) {
            buf.setLine(x, y++, Line.from(Span.styled("DST  ", Ui.DIM), Span.styled("▲ starts " + dst.starts(), Style.create().fg(Ui.GOOD))), w);
            if (y < info.bottom() - 1) buf.setLine(x, y++, Line.from(Span.styled("     ", Ui.DIM), Span.styled("▼ ends   " + dst.ends(), Style.create().fg(Ui.WARN))), w);
            if (dst.next() != null && y < info.bottom() - 1)
                buf.setLine(x, y++, Line.from(Span.styled("next ", Ui.DIM), Span.styled(Dst.transition(dst.next()), Ui.NORMAL)), w);
        }
        if (y < info.bottom() - 1 && p.lat() != null) {
            buf.setLine(x, y++, Line.from(Span.styled(String.format(Locale.ROOT, "%.3f, %.3f", p.lat(), p.lon()), Ui.DIM),
                    Span.styled(p.population() > 0 ? String.format(Locale.ROOT, "  · pop. %,d", p.population()) : "", Ui.DIM)), w);
        }
        if (noMap) return;
        Rect mapBox = new Rect(info.right(), area.y(), area.right() - info.right(), area.height());
        Ui.panel("map").render(mapBox, buf);
        Rect mapArea = new Rect(mapBox.x() + 1, mapBox.y() + 1, mapBox.width() - 2, mapBox.height() - 2);
        double[] loc = p.lat() != null ? new double[]{p.lat(), p.lon()} : WorldMap.locate(p.label(), p.zone());
        List<WorldMap.Marker> markers = new ArrayList<>();
        if (loc != null) markers.add(new WorldMap.Marker(loc[0], loc[1], "", Color.rgb(0xff, 0x30, 0x60)));
        WorldMap.render(buf, mapArea, now, WorldMap.Mode.OFFSET, true, markers, WorldMap.TOP_LAT, WorldMap.BOTTOM_LAT);
        if (loc != null) {
            int[] c = WorldMap.toCell(mapArea, loc[0], loc[1], WorldMap.TOP_LAT, WorldMap.BOTTOM_LAT);
            if (c != null) {
                // crosshair
                for (int xx = mapArea.x(); xx < mapArea.right(); xx++) if (xx != c[0] && buf.get(xx, c[1]).symbol().equals("▀"))
                    buf.setString(xx, c[1], "─", buf.get(xx, c[1]).style().fg(Ui.mix(Ui.BRIGHT, Ui.BAD, 0.4)));
                for (int yy = mapArea.y(); yy < mapArea.bottom(); yy++) if (yy != c[1] && buf.get(c[0], yy).symbol().equals("▀"))
                    buf.setString(c[0], yy, "│", buf.get(c[0], yy).style().fg(Ui.mix(Ui.BRIGHT, Ui.BAD, 0.4)));
            }
        }
    }

    void renderTable(Buffer buf, Rect area, List<Geo.Place> res, Instant now, String q, boolean online) {
        List<Row> rows = new ArrayList<>();
        int localOff = now.atZone(local).getOffset().getTotalSeconds();
        for (Geo.Place p : res) {
            ZonedDateTime t = now.atZone(p.zone());
            boolean dst = p.zone().getRules().isDaylightSavings(now);
            int d = t.getOffset().getTotalSeconds() - localOff;
            Color kindColor = switch (p.kind()) {
                case "city" -> Ui.GOOD; case "country" -> Ui.ACCENT2; case "online" -> Ui.CYAN; case "abbreviation" -> Ui.WARN; default -> Ui.ACCENT;
            };
            rows.add(Row.from(
                    Cell.from(Span.styled(Ui.skyIcon(t.toLocalTime()), Style.create().fg(Ui.skyColor(t.toLocalTime())))),
                    Cell.from(Span.styled(p.label(), Style.create().fg(Ui.BRIGHT))),
                    Cell.from(Span.styled(p.region() == null ? "" : p.region(), Ui.DIM)),
                    Cell.from(Span.styled(p.cc().isEmpty() ? "" : Geo.countryName(p.cc()), Ui.NORMAL)),
                    Cell.from(Span.styled(p.zone().getId(), Style.create().fg(Ui.CYAN))),
                    Cell.from(Span.styled(t.format(DateTimeFormatter.ofPattern("HH:mm EEE", Locale.ENGLISH)),
                            Style.create().fg(Ui.periodText(Ui.period(t.toLocalTime(), 9, 18))).bold())),
                    Cell.from(Span.styled(Ui.offset(t.getOffset()), Ui.NORMAL)),
                    Cell.from(Span.styled(Ui.diff(d), d == 0 ? Ui.DIM : Style.create().fg(d > 0 ? Ui.CYAN : Ui.WARN))),
                    Cell.from(Line.from(Span.styled(Geo.displayName(p.zone(), dst), Ui.DIM), Span.styled(dst ? " ☼" : "", Style.create().fg(Ui.GOOD)))),
                    Cell.from(Span.styled(p.kind(), Style.create().fg(kindColor)))));
        }
        Row header = Row.from("", "Name", "Region", "Country", "Zone", "Local", "Offset", "Δ you", "Abbr", "Match").style(Ui.HEADER);
        String title = res.size() + " result" + (res.size() == 1 ? "" : "s") + " for “" + q + "”" + (online ? " (incl. Open-Meteo)" : "");
        Ui.table(buf, area, header, rows,
                List.of(Constraint.length(1), Constraint.fill(2), Constraint.fill(2), Constraint.fill(2), Constraint.length(22),
                        Constraint.length(9), Constraint.length(9), Constraint.length(7), Constraint.length(7), Constraint.length(12)),
                Ui.panel(title));
    }
}
