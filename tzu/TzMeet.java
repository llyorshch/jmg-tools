///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES lib/Term.java lib/Config.java lib/Geo.java lib/Ui.java lib/Live.java lib/Dates.java lib/Dst.java lib/WorldMap.java lib/Net.java lib/Holidays.java
//FILES data/cities.tsv=data/cities.tsv
//FILES data/countries.tsv=data/countries.tsv
//FILES data/landmask.txt=data/landmask.txt
//FILES META-INF/native-image/tzu/reachability-metadata.json=native/reachability-metadata.json
//DESCRIPTION Meeting planner: a day's timetable across your group of timezones, with the best slots highlighted

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
import picocli.CommandLine.Parameters;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.Callable;

@Command(name = "tz-meet", mixinStandardHelpOptions = true, version = "tz-meet 1.0",
        description = {"Meeting planner: the timetable of a day across a group of timezones.",
                "Each column is one hour of the reference timezone; cells show the local hour in every zone,",
                "coloured by working hours / early-evening / night. The best overlapping slots are highlighted."},
        footer = {"", "Examples:",
                "  tz-meet                       today, configured zones, local reference",
                "  tz-meet fri --at 16:00        next Friday, inspect the 16:00 slot",
                "  tz-meet 2026-12-03 -z 'NYC=New York,Madrid,Tokyo' --work 8-17",
                "  tz-meet -i                    interactive: ←/→ hour, ↑/↓ day, t today, q quit"})
public class TzMeet implements Callable<Integer> {

    @Parameters(index = "0", arity = "0..1", paramLabel = "DATE", description = "Day to plan: yyyy-MM-dd, today, tomorrow, +N, mon..sun (default today)")
    String dateArg;

    @Option(names = {"-z", "--zones"}, paramLabel = "LIST", description = "Comma separated places instead of the configured zones")
    String zones;

    @Option(names = {"-r", "--ref"}, paramLabel = "PLACE", description = "Reference timezone for the columns (default: local)")
    String ref;

    @Option(names = "--work", paramLabel = "H-H", description = "Working hours, e.g. 9-18 (default from config)")
    String work;

    @Option(names = "--at", paramLabel = "TIME", description = "Highlight a slot (reference time, e.g. 15:30 or 3pm)")
    String at;

    @Option(names = {"-d", "--duration"}, paramLabel = "MIN", description = "Meeting length in minutes for the slot details (default 60)", defaultValue = "60")
    int duration;

    @Option(names = {"-i", "--interactive"}, description = "Interactive full-screen planner")
    boolean interactive;

    @Option(names = "--12h", description = "Use a 12-hour clock")
    boolean h12;

    @Option(names = "--no-color", description = "Disable colours (also honours NO_COLOR)")
    boolean noColor;

    Config cfg;
    ZoneId refZone;
    String refLabel;
    List<Config.Entry> entries;
    int ws, we;

    // interactive state
    LocalDate day;
    int selected = -1; // selected column

    public static void main(String... args) {
        System.exit(new CommandLine(new TzMeet()).execute(args));
    }

    @Override
    public Integer call() throws Exception {
        if (noColor) Term.noColor = true;
        cfg = Config.load();
        h12 |= cfg.clock12;
        ZoneId local = Config.localZone();
        try {
            if (ref != null) {
                var p = Geo.resolve(ref).orElseThrow(() -> new IllegalArgumentException("Unknown reference place: " + ref));
                refZone = p.zone();
                refLabel = p.label();
            } else {
                refZone = local;
                refLabel = Geo.labelFor(local);
            }
            entries = Config.withLocal(cfg.zonesOrOverride(zones), refZone);
            int[] wr = work != null ? Dates.parseRange(work) : new int[]{cfg.workStart, cfg.workEnd};
            ws = wr[0];
            we = wr[1];
            day = Dates.parseDate(dateArg, ZonedDateTime.now(refZone).toLocalDate());
            if (at != null) selected = Dates.parseTime(at).getHour();
        } catch (Exception e) {
            Term.error(e.getMessage());
            return 2;
        }
        // order rows: reference first, then by offset on that day
        Instant noon = day.atTime(12, 0).atZone(refZone).toInstant();
        entries.sort(Comparator.comparingInt((Config.Entry e) -> e.zone().getId().equals(refZone.getId()) ? Integer.MIN_VALUE
                : e.zone().getRules().getOffset(noon).getTotalSeconds()));

        if (interactive) {
            if (selected < 0) selected = day.equals(ZonedDateTime.now(refZone).toLocalDate()) ? ZonedDateTime.now(refZone).getHour() : ws;
            Live.run(Duration.ofSeconds(30), this::render, k -> {
                int n = columns(day).size();
                if (k.isKey(KeyCode.LEFT) || k.isChar('h')) selected = (selected - 1 + n) % n;
                else if (k.isKey(KeyCode.RIGHT) || k.isChar('l')) selected = (selected + 1) % n;
                else if (k.isKey(KeyCode.UP) || k.isChar('k') || k.isChar('[')) day = day.minusDays(1);
                else if (k.isKey(KeyCode.DOWN) || k.isChar('j') || k.isChar(']')) day = day.plusDays(1);
                else if (k.isChar('t')) { day = ZonedDateTime.now(refZone).toLocalDate(); selected = ZonedDateTime.now(refZone).getHour(); }
                else if (k.isChar('b')) selected = bestColumns(columns(day)).get(0);
                else if (k.isChar('+')) duration += 15;
                else if (k.isChar('-')) duration = Math.max(15, duration - 15);
                selected = Math.min(selected, columns(day).size() - 1);
                return true;
            });
            return 0;
        }
        int width = Term.width();
        int height = 2 + entries.size() + 4 + 2 + 2 + 3 + entries.size() + 2;
        Term.print(width, height, this::render);
        return 0;
    }

    /** One instant per reference hour of the day (23 or 25 on DST change days). */
    List<ZonedDateTime> columns(LocalDate d) {
        List<ZonedDateTime> cols = new ArrayList<>();
        ZonedDateTime t = d.atStartOfDay(refZone);
        while (t.toLocalDate().equals(d)) {
            cols.add(t);
            t = t.plusHours(1);
        }
        return cols;
    }

    Ui.Period periodAt(Config.Entry e, ZonedDateTime refTime) {
        return Ui.period(refTime.withZoneSameInstant(e.zone()).toLocalTime(), ws, we);
    }

    int score(ZonedDateTime col) {
        int s = 0;
        for (Config.Entry e : entries) {
            s += switch (periodAt(e, col)) { case WORK -> 100; case SHOULDER -> 10; case NIGHT -> 0; };
        }
        return s;
    }

    int working(ZonedDateTime col) {
        int n = 0;
        for (Config.Entry e : entries) if (periodAt(e, col) == Ui.Period.WORK) n++;
        return n;
    }

    /** Columns sorted by score, best first. */
    List<Integer> bestColumns(List<ZonedDateTime> cols) {
        List<Integer> idx = new ArrayList<>();
        for (int i = 0; i < cols.size(); i++) idx.add(i);
        idx.sort(Comparator.comparingInt((Integer i) -> -score(cols.get(i))).thenComparingInt(i -> Math.abs(cols.get(i).getHour() - 13)));
        return idx;
    }

    String cellText(ZonedDateTime local, int w) {
        if (local.getHour() == 0 && local.getMinute() == 0 || local.getHour() == 0 && w >= 4) {
            String dow = local.format(DateTimeFormatter.ofPattern("EEE", Locale.ENGLISH));
            return Ui.pad(dow, w);
        }
        int h = local.getHour();
        if (h12) h = h % 12 == 0 ? 12 : h % 12;
        String frac = switch (local.getMinute()) { case 15 -> "¼"; case 30 -> "½"; case 45 -> "¾"; default -> ""; };
        String s = (h12 ? String.valueOf(h) : String.format("%02d", h)) + frac;
        if (h12 && w >= 4 && frac.isEmpty()) s += local.getHour() < 12 ? "a" : "p";
        return Ui.pad(s, w);
    }

    void render(Rect area, Buffer buf) {
        List<ZonedDateTime> cols = columns(day);
        int n = cols.size();
        int labelW = Math.max(11, Math.min(22, 2 + entries.stream().mapToInt(e -> e.label().length()).max().orElse(8)));
        int offW = 7;
        boolean anyFraction = false;
        for (Config.Entry e : entries) if (e.zone().getRules().getOffset(cols.get(12).toInstant()).getTotalSeconds() % 3600 != 0) anyFraction = true;
        int avail = area.width() - 4 - labelW - offW - 1;
        int cw = avail >= n * 4 ? 4 : avail >= n * 3 || anyFraction ? 3 : 2;
        int gridW = labelW + offW + 1 + n * cw;
        int boxW = Math.min(area.width(), gridW + 4);
        ZonedDateTime nowRef = ZonedDateTime.now(refZone);
        boolean isToday = nowRef.toLocalDate().equals(day);

        // ------------------------------------------------------------------ grid panel
        int gridH = entries.size() + 5;
        Rect box = new Rect(area.x(), area.y(), boxW, Math.min(gridH, area.height()));
        Line title = Line.from(Span.styled(" ◷ tzu ", Ui.TITLE), Span.styled("meeting planner · ", Ui.DIM),
                Span.styled(day.format(DateTimeFormatter.ofPattern("EEEE d MMMM yyyy", Locale.ENGLISH)) + " ", Style.create().fg(Ui.BRIGHT).bold()));
        Line bottom = interactive ? Line.from(Span.styled(" ←/→ hour  ↑/↓ day  t today  b best  +/- length  q quit ", Ui.DIM)) : null;
        Ui.panel(title, bottom).render(box, buf);

        int x0 = box.x() + 2, y = box.y() + 1;
        int gx = x0 + labelW + offW + 1;
        // header row: reference hours + now marker
        buf.setString(x0, y, Ui.pad("hours in " + refLabel, labelW + offW), Ui.DIM);
        List<Integer> best = bestColumns(cols);
        int bestScore = score(cols.get(best.get(0)));
        for (int i = 0; i < n; i++) {
            ZonedDateTime c = cols.get(i);
            boolean now = isToday && c.getHour() == nowRef.getHour();
            Style st = now ? Style.create().fg(Ui.BAD).bold() : i == selected ? Style.create().fg(Ui.BRIGHT).bold() : Style.create().fg(Ui.ACCENT2);
            String txt = now ? Ui.pad("▼now", cw) : Ui.pad(cw >= 3 ? cellText(c, cw).trim() : String.format("%02d", c.getHour()), cw);
            if (now && cw < 4) txt = Ui.pad("▼", cw);
            buf.setString(gx + i * cw, y, txt, st);
        }
        y++;
        // zone rows
        for (Config.Entry e : entries) {
            boolean isRef = e.zone().getId().equals(refZone.getId());
            ZonedDateTime sample = cols.get(Math.min(12, n - 1)).withZoneSameInstant(e.zone());
            int diff = sample.getOffset().getTotalSeconds() - cols.get(Math.min(12, n - 1)).getOffset().getTotalSeconds();
            buf.setString(x0, y, Ui.pad((isRef ? "⌂ " : "  ") + e.label(), labelW), isRef ? Style.create().fg(Ui.BRIGHT).bold() : Ui.NORMAL);
            buf.setString(x0 + labelW, y, Ui.padLeft(isRef ? "ref" : Ui.diff(diff), offW - 1), diff == 0 ? Ui.DIM : Style.create().fg(diff > 0 ? Ui.CYAN : Ui.WARN));
            for (int i = 0; i < n; i++) {
                ZonedDateTime local = cols.get(i).withZoneSameInstant(e.zone());
                var p = Ui.period(local.toLocalTime(), ws, we);
                Color bg = Ui.periodBg(p);
                Color fg = Ui.periodFg(p);
                boolean weekend = local.getDayOfWeek().getValue() >= 6;
                if (weekend && p == Ui.Period.WORK) { bg = Ui.mix(bg, Ui.WEEKEND_BG, 0.55); }
                if (i % 2 == 1) bg = Ui.shade(bg, 0.86);
                Style st = Style.create().bg(bg).fg(fg);
                if (local.getHour() == 0) st = st.fg(Ui.BRIGHT).bold();
                if (i == selected) st = Style.create().bg(Ui.mix(bg, Ui.BRIGHT, 0.55)).fg(Color.rgb(0, 0, 0)).bold();
                buf.setString(gx + i * cw, y, cellText(local, cw), st);
            }
            y++;
        }
        // overlap row
        buf.setString(x0, y, Ui.pad("in working hours", labelW + offW), Ui.DIM);
        for (int i = 0; i < n; i++) {
            int w = working(cols.get(i));
            double t = (double) w / entries.size();
            Color bg = Ui.mix(Color.rgb(0x3a, 0x1c, 0x2a), Color.rgb(0x2e, 0xb8, 0x5c), t);
            String txt = Ui.center(String.valueOf(w), cw);
            buf.setString(gx + i * cw, y, txt, Style.create().bg(bg).fg(Ui.BRIGHT).bold());
        }
        y++;
        buf.setString(x0, y, Ui.pad("best", labelW + offW), Ui.DIM);
        for (int i = 0; i < n; i++) {
            if (score(cols.get(i)) == bestScore) buf.setString(gx + i * cw, y, Ui.center("★", cw), Style.create().fg(Ui.WARN).bold());
        }
        y++;

        // ------------------------------------------------------------------ legend
        y = box.bottom();
        if (y < area.bottom()) {
            buf.setLine(area.x() + 1, y, Ui.legend(Ui.WORK_BG, "work " + ws + "–" + we, Ui.SHOULDER_BG, "early/evening",
                    Ui.NIGHT_BG, "night", Ui.mix(Ui.WORK_BG, Ui.WEEKEND_BG, 0.55), "weekend")
                    .append(Line.from(Span.styled("½ ¼ ¾ = :30 :15 :45   Mon/Tue… = midnight", Ui.DIM))), area.width() - 2);
            y += 2;
        }

        // ------------------------------------------------------------------ best slots summary
        if (y < area.bottom()) {
            List<String> ranges = new ArrayList<>();
            for (int i = 0; i < n; i++) {
                if (score(cols.get(i)) != bestScore) continue;
                int j = i;
                while (j + 1 < n && score(cols.get(j + 1)) == bestScore) j++;
                ranges.add(Ui.time(cols.get(i), h12) + "–" + Ui.time(cols.get(j).plusHours(1), h12));
                i = j;
            }
            int bw = working(cols.get(best.get(0)));
            buf.setLine(area.x() + 1, y, Line.from(
                    Span.styled("★ Best: ", Style.create().fg(Ui.WARN).bold()),
                    Span.styled(String.join(", ", ranges), Style.create().fg(Ui.BRIGHT).bold()),
                    Span.styled(" " + refLabel + " time · ", Ui.DIM),
                    Span.styled(bw + "/" + entries.size(), Style.create().fg(bw == entries.size() ? Ui.GOOD : Ui.WARN).bold()),
                    Span.styled(" zones in working hours", Ui.DIM)), area.width() - 2);
            y += 2;
        }

        // ------------------------------------------------------------------ slot detail
        int slot = selected >= 0 && selected < n ? selected : best.get(0);
        ZonedDateTime start = cols.get(slot);
        if (selected >= 0 && at != null && !interactive) {
            try {
                LocalTime lt = Dates.parseTime(at);
                start = day.atTime(lt).atZone(refZone);
            } catch (Exception ignored) { }
        }
        ZonedDateTime end = start.plusMinutes(duration);
        if (y + 3 < area.bottom()) {
            List<Row> rows = new ArrayList<>();
            for (Config.Entry e : entries) {
                ZonedDateTime ls = start.withZoneSameInstant(e.zone()), le = end.withZoneSameInstant(e.zone());
                var p = Ui.period(ls.toLocalTime(), ws, we);
                var pe = Ui.period(le.minusMinutes(1).toLocalTime(), ws, we);
                String verdict = p == Ui.Period.WORK && pe == Ui.Period.WORK ? "✔ working hours"
                        : p == Ui.Period.NIGHT || pe == Ui.Period.NIGHT ? "✘ night" : "~ outside working hours";
                Color vc = verdict.startsWith("✔") ? Ui.GOOD : verdict.startsWith("✘") ? Ui.BAD : Ui.WARN;
                rows.add(Row.from(
                        Cell.from(Span.styled(Ui.skyIcon(ls.toLocalTime()), Style.create().fg(Ui.skyColor(ls.toLocalTime())))),
                        Cell.from(Span.styled(e.label(), e.zone().getId().equals(refZone.getId()) ? Style.create().fg(Ui.BRIGHT).bold() : Ui.NORMAL)),
                        Cell.from(Span.styled(Ui.time(ls, h12) + " – " + Ui.time(le, h12), Style.create().fg(Ui.periodText(p)).bold())),
                        Cell.from(Span.styled(ls.format(DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH)),
                                ls.toLocalDate().equals(day) ? Ui.DIM : Style.create().fg(Ui.ACCENT2))),
                        Cell.from(Span.styled(Ui.offset(ls.getOffset()), Ui.DIM)),
                        Cell.from(Span.styled(verdict, Style.create().fg(vc)))));
            }
            Row header = Row.from("", "Place", "Local time", "Date", "Offset", "").style(Ui.HEADER);
            String t = (selected >= 0 ? "slot " : "best slot ") + Ui.time(start, h12) + "–" + Ui.time(end, h12) + " " + refLabel + " · " + duration + " min";
            Ui.table(buf, new Rect(area.x(), y, Math.min(area.width(), 100), Math.min(rows.size() + 3, area.bottom() - y)), header, rows,
                    List.of(Constraint.length(1), Constraint.fill(), Constraint.length(h12 ? 19 : 13), Constraint.length(10),
                            Constraint.length(9), Constraint.length(24)), Ui.panel(t));
        }
    }
}
