///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES lib/Term.java lib/Config.java lib/Geo.java lib/Ui.java lib/Live.java lib/Dates.java lib/Dst.java lib/WorldMap.java lib/Net.java lib/Holidays.java
//FILES data/cities.tsv=data/cities.tsv
//FILES data/countries.tsv=data/countries.tsv
//FILES data/landmask.txt=data/landmask.txt
//FILES META-INF/native-image/tzu/reachability-metadata.json=native/reachability-metadata.json
//DESCRIPTION World clock: current time in your timezone and a configurable group of timezones

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "tz-now", mixinStandardHelpOptions = true, version = "tz-now 1.0",
        description = {"Shows the current time in your timezone and in a configurable group of timezones.",
                "Zones are stored in ~/.config/tzu/config.properties and shared by every tzu command."},
        footer = {"", "Examples:",
                "  tz-now                         world clock for the configured zones",
                "  tz-now -w                      live, full screen clock (q to quit)",
                "  tz-now --add Bilbao --add JST  add places (cities, countries, abbreviations, zone ids)",
                "  tz-now -z Tokyo,Lima           one-off group of zones",
                "  tz-now --at '2026-12-24 18:00' what time is it elsewhere at that local time"})
public class TzNow implements Callable<Integer> {

    @Option(names = {"-z", "--zones"}, paramLabel = "LIST", description = "Comma separated places to show instead of the configured ones")
    String zones;

    @Option(names = "--add", paramLabel = "PLACE", description = "Add a place to the configured zones (repeatable). Use Label=Place for a custom label")
    List<String> add = new ArrayList<>();

    @Option(names = "--remove", paramLabel = "PLACE", description = "Remove a place from the configured zones (repeatable)")
    List<String> remove = new ArrayList<>();

    @Option(names = "--reset", description = "Restore the default zones")
    boolean reset;

    @Option(names = "--config", description = "Print the configuration file location and contents")
    boolean showConfig;

    @Option(names = {"-w", "--watch"}, description = "Live full-screen clock that updates every second")
    boolean watch;

    @Option(names = "--at", paramLabel = "DATETIME", description = "Show the times for this local date-time instead of now (yyyy-MM-dd HH:mm)")
    String at;

    @Option(names = "--12h", description = "Use a 12-hour clock")
    boolean h12;

    @Option(names = "--no-color", description = "Disable colours (also honours NO_COLOR)")
    boolean noColor;

    @Option(names = "--sort", description = "Row order: offset (default), config, name", defaultValue = "offset")
    String sort;

    Config cfg;
    ZoneId local;

    public static void main(String... args) {
        System.exit(new CommandLine(new TzNow()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (noColor) Term.noColor = true;
        cfg = Config.load();
        local = Config.localZone();
        h12 |= cfg.clock12;

        if (reset || !add.isEmpty() || !remove.isEmpty()) {
            if (!editConfig()) return 2;
        }
        if (showConfig) {
            Term.println("Config file: " + Config.file());
            for (Config.Entry e : cfg.zones) Term.println("  " + e.label() + "  →  " + e.zone().getId());
            Term.println("Working hours: " + cfg.workStart + ":00–" + cfg.workEnd + ":00");
            return 0;
        }
        List<Config.Entry> entries;
        try {
            entries = Config.withLocal(cfg.zonesOrOverride(zones), local);
        } catch (IllegalArgumentException e) {
            Term.error(e.getMessage());
            return 2;
        }
        Instant instant = Instant.now();
        if (at != null) {
            try {
                instant = LocalDateTime.parse(at.trim().replace(' ', 'T')).atZone(local).toInstant();
            } catch (Exception e) {
                Term.error("Invalid --at value, expected yyyy-MM-dd HH:mm");
                return 2;
            }
        }
        if (watch) {
            Live.run(Duration.ofMillis(500), (area, buf) -> render(area, buf, entries, Instant.now(), true), k -> true);
            return 0;
        }
        final Instant when = instant;
        int width = Math.min(Term.width(), 120);
        Term.print(width, height(entries), (area, buf) -> render(area, buf, entries, when, false));
        return 0;
    }

    boolean editConfig() {
        if (reset) {
            cfg.zones.clear();
            for (String z : Config.DEFAULT_ZONES) cfg.zones.add(Config.parseEntry(z));
        }
        for (String r : remove) {
            var place = Geo.resolve(r);
            boolean removed = cfg.zones.removeIf(e -> e.label().equalsIgnoreCase(r.trim())
                    || e.zone().getId().equalsIgnoreCase(r.trim())
                    || place.isPresent() && e.zone().equals(place.get().zone()));
            if (!removed) Term.error("Not in the configured zones: " + r);
        }
        for (String a : add) {
            Config.Entry e = Config.parseEntry(a.trim());
            if (e == null) {
                Term.error("Unknown place: '" + a + "' (try: tz-find " + a + ")");
                return false;
            }
            if (cfg.zones.stream().noneMatch(x -> x.label().equals(e.label()) && x.zone().equals(e.zone()))) cfg.zones.add(e);
            Term.println("✔ added " + e.label() + " (" + e.zone().getId() + ")");
        }
        cfg.save();
        Term.println("Saved " + cfg.zones.size() + " zones to " + Config.file());
        return true;
    }

    int height(List<Config.Entry> entries) {
        return 5 + entries.size() + 3 + 1;
    }

    List<Config.Entry> sorted(List<Config.Entry> entries, Instant now) {
        List<Config.Entry> l = new ArrayList<>(entries);
        switch (sort) {
            case "name" -> l.sort(Comparator.comparing(Config.Entry::label));
            case "config" -> { }
            default -> l.sort(Comparator.comparingInt((Config.Entry e) -> e.zone().getRules().getOffset(now).getTotalSeconds())
                    .thenComparing(Config.Entry::label));
        }
        return l;
    }

    void render(Rect area, Buffer buf, List<Config.Entry> entries, Instant now, boolean live) {
        ZonedDateTime here = now.atZone(local);
        boolean dst = local.getRules().isDaylightSavings(now);

        // ---------------------------------------------------------------- header with a big clock
        Rect head = new Rect(area.x(), area.y(), area.width(), 5);
        Ui.panel(Line.from(Span.styled(" ◷ tzu ", Ui.TITLE), Span.styled("world clock ", Ui.DIM)),
                live ? Line.from(Span.styled(" q quit ", Ui.DIM)) : null).render(head, buf);
        String clock = here.format(DateTimeFormatter.ofPattern(live ? (h12 ? "hh:mm:ss" : "HH:mm:ss") : (h12 ? "hh:mm" : "HH:mm")));
        int bx = head.x() + 3;
        Ui.bigText(buf, bx, head.y() + 1, clock, Ui.CYAN, Ui.ACCENT2);
        int tx = bx + Ui.bigWidth(clock) + 3;
        int maxW = head.right() - tx - 2;
        if (maxW > 10) {
            String ampm = h12 ? here.format(DateTimeFormatter.ofPattern(" a", Locale.ENGLISH)) : "";
            buf.setLine(tx, head.y() + 1, Line.from(
                    Span.styled(Geo.labelFor(local) + ampm, Style.create().fg(Ui.BRIGHT).bold()),
                    Span.styled("  " + local.getId(), Ui.DIM)), maxW);
            buf.setLine(tx, head.y() + 2, Line.from(Span.styled(
                    here.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy · 'week' w", Locale.ENGLISH)), Ui.NORMAL)), maxW);
            buf.setLine(tx, head.y() + 3, Line.from(
                    Span.styled(Ui.offset(here.getOffset()), Style.create().fg(Ui.WARN)),
                    Span.styled(" · " + Geo.displayName(local, dst) + " · ", Ui.DIM),
                    dst ? Span.styled("DST in effect", Style.create().fg(Ui.GOOD)) : Span.styled("standard time", Ui.DIM),
                    Span.styled(" · " + Ui.skyIcon(here.toLocalTime()), Style.create().fg(Ui.skyColor(here.toLocalTime())))), maxW);
        }

        // ---------------------------------------------------------------- zone table
        Rect body = new Rect(area.x(), area.y() + 5, area.width(), Math.max(0, area.height() - 6));
        List<Row> rows = new ArrayList<>();
        int localOffset = here.getOffset().getTotalSeconds();
        for (Config.Entry e : sorted(entries, now)) {
            ZonedDateTime t = now.atZone(e.zone());
            boolean isLocal = e.zone().getId().equals(local.getId());
            boolean zdst = e.zone().getRules().isDaylightSavings(now);
            var period = Ui.period(t.toLocalTime(), cfg.workStart, cfg.workEnd);
            Style name = isLocal ? Style.create().fg(Ui.BRIGHT).bold() : Ui.NORMAL;
            String timeText = live ? t.format(DateTimeFormatter.ofPattern(h12 ? "h:mm:ss a" : "HH:mm:ss", Locale.ENGLISH)) : Ui.time(t, h12);
            int d = t.getOffset().getTotalSeconds() - localOffset;
            rows.add(Row.from(
                    Cell.from(Span.styled(Ui.skyIcon(t.toLocalTime()), Style.create().fg(Ui.skyColor(t.toLocalTime())))),
                    Cell.from(Line.from(Span.styled(e.label(), name), Span.styled(isLocal ? " ⌂" : "", Style.create().fg(Ui.WARN)))),
                    Cell.from(Span.styled(timeText, Style.create().fg(Ui.periodText(period)).bold())),
                    Cell.from(Span.styled(Ui.relDay(t.toLocalDate(), here.toLocalDate()),
                            t.toLocalDate().equals(here.toLocalDate()) ? Ui.DIM : Style.create().fg(Ui.ACCENT2))),
                    Cell.from(Span.styled(Ui.offset(t.getOffset()), Ui.NORMAL)),
                    Cell.from(Span.styled(Ui.diff(d), d == 0 ? Ui.DIM : Style.create().fg(d > 0 ? Ui.CYAN : Ui.WARN))),
                    Cell.from(Line.from(Span.styled(Geo.displayName(e.zone(), zdst), Ui.DIM),
                            Span.styled(zdst ? " ☼" : "", Style.create().fg(Ui.GOOD)))),
                    Cell.from(dayBar(t))));
        }
        Row header = Row.from(Cell.from(""), Cell.from("Place"), Cell.from("Time"), Cell.from("Day"),
                Cell.from("Offset"), Cell.from("Δ you"), Cell.from("Zone"),
                Cell.from("0     6     12    18    ")).style(Ui.HEADER);
        int timeW = live ? (h12 ? 11 : 8) : (h12 ? 8 : 5);
        Ui.table(buf, new Rect(body.x(), body.y(), body.width(), entries.size() + 3), header, rows,
                List.of(Constraint.length(1), Constraint.fill(), Constraint.length(timeW), Constraint.length(9),
                        Constraint.length(9), Constraint.length(7), Constraint.length(7), Constraint.length(24)),
                Ui.panel(entries.size() + " zones"));

        // ---------------------------------------------------------------- legend
        int ly = body.y() + entries.size() + 3;
        if (ly < area.bottom()) {
            buf.setLine(area.x() + 1, ly, Ui.legend(Ui.WORK_BG, "working hours " + cfg.workStart + "–" + cfg.workEnd,
                    Ui.SHOULDER_BG, "early/evening", Ui.NIGHT_BG, "night").append(Line.from(
                    Span.styled("☼ DST  ⌂ local", Ui.DIM))), area.width() - 2);
        }
    }

    /** 24 cells, one per local hour, coloured by period; the current hour is marked. */
    Line dayBar(ZonedDateTime t) {
        List<Span> spans = new ArrayList<>();
        int now = t.getHour();
        for (int h = 0; h < 24; h++) {
            var p = Ui.period(t.toLocalTime().withHour(h).withMinute(0), cfg.workStart, cfg.workEnd);
            String g = h == now ? (t.getMinute() < 30 ? "▌" : "▐") : " ";
            Style st = Style.create().bg(Ui.periodBg(p));
            if (h == now) st = st.fg(Ui.BRIGHT).bold();
            spans.add(Span.styled(g, st));
        }
        return Line.from(spans);
    }
}
