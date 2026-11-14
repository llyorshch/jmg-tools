///usr/bin/env jbang "$0" "$@" ; exit $?
//SOURCES lib/Term.java lib/Config.java lib/Geo.java lib/Ui.java lib/Live.java lib/Dates.java lib/Dst.java lib/WorldMap.java lib/Net.java lib/Holidays.java
//FILES data/cities.tsv=data/cities.tsv
//FILES data/countries.tsv=data/countries.tsv
//FILES data/landmask.txt=data/landmask.txt
//FILES META-INF/native-image/tzu/reachability-metadata.json=native/reachability-metadata.json
//DESCRIPTION Public holidays across the countries of your timezones for a week, month, quarter or year

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.calendar.CalendarEventStore;
import dev.tamboui.widgets.calendar.Monthly;
import dev.tamboui.widgets.table.Cell;
import dev.tamboui.widgets.table.Row;
import picocli.CommandLine;
import picocli.CommandLine.Command;
import picocli.CommandLine.Option;
import picocli.CommandLine.Parameters;

import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.temporal.IsoFields;
import java.time.temporal.TemporalAdjusters;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.Callable;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Command(name = "tz-holidays", mixinStandardHelpOptions = true, version = "tz-holidays 1.0",
        description = {"Public holidays of the countries behind your timezones for a week, month, quarter or year,",
                "with the moment each holiday starts and ends in your own timezone.",
                "Data: Nager.Date (https://date.nager.at), cached for 30 days in ~/.cache/tzu."},
        footer = {"", "PERIOD can be: 2026 · 2026-Q4 · Q2 · 2026-12 · dec · 2026-W51 · W51 · a date (its month)", "",
                "Examples:",
                "  tz-holidays                      this month, countries of the configured zones",
                "  tz-holidays -w                   this week",
                "  tz-holidays 2026-Q4 -c US,GB,DE,JP",
                "  tz-holidays 2027 --national      whole year, national holidays only"})
public class TzHolidays implements Callable<Integer> {

    enum Span_ { WEEK, MONTH, QUARTER, YEAR }

    @Parameters(index = "0", arity = "0..1", paramLabel = "PERIOD", description = "Period to show (default: current month)")
    String period;

    @Option(names = {"-w", "--week"}, description = "A week (the current one, or the week of PERIOD)")
    boolean week;

    @Option(names = {"-m", "--month"}, description = "A month")
    boolean month;

    @Option(names = {"-q", "--quarter"}, description = "A quarter")
    boolean quarter;

    @Option(names = {"-y", "--year"}, description = "A whole year")
    boolean year;

    @Option(names = {"-c", "--countries"}, paramLabel = "CC", split = ",", description = "Country codes or names (default: from the zones)")
    List<String> countries;

    @Option(names = {"-z", "--zones"}, paramLabel = "LIST", description = "Comma separated places whose countries to use")
    String zones;

    @Option(names = {"-r", "--ref"}, paramLabel = "PLACE", description = "Timezone used for the start/end times (default: local)")
    String ref;

    @Option(names = "--national", description = "Hide regional holidays")
    boolean nationalOnly;

    @Option(names = "--no-list", description = "Only draw the calendar/grid")
    boolean noList;

    @Option(names = "--offline", description = "Only use cached data")
    boolean offline;

    @Option(names = "--refresh", description = "Ignore the cache and download again")
    boolean refresh;

    @Option(names = "--no-color", description = "Disable colours (also honours NO_COLOR)")
    boolean noColor;

    static final Color NATIONAL = Color.rgb(0xd9, 0x46, 0x5f);
    static final Color REGIONAL = Color.rgb(0x9a, 0x6a, 0x2a);
    static final Color MULTI = Color.rgb(0xb0, 0x4c, 0xf0);

    ZoneId refZone;
    String refLabel;
    LocalDate start, end; // inclusive
    Span_ span;
    List<String> ccs = new ArrayList<>();
    List<String> noData = new ArrayList<>();
    List<String> failed = new ArrayList<>();
    List<Holidays.Holiday> holidays = new ArrayList<>();

    public static void main(String... args) {
        System.exit(new CommandLine(new TzHolidays()).execute(args));
    }

    @Override
    public Integer call() {
        if (noColor) Term.noColor = true;
        Config cfg = Config.load();
        try {
            refZone = Config.localZone();
            if (ref != null) refZone = Geo.resolve(ref).orElseThrow(() -> new IllegalArgumentException("Unknown place: " + ref)).zone();
            refLabel = Geo.labelFor(refZone);
            resolvePeriod(LocalDate.now(refZone));
            resolveCountries(cfg);
        } catch (Exception e) {
            Term.error(e.getMessage());
            return 2;
        }
        if (ccs.isEmpty()) {
            Term.error("No countries: the configured zones have none (UTC?). Use -c US,GB,…");
            return 2;
        }
        List<String> known = Holidays.available(offline);
        for (String cc : ccs) {
            if (known != null && !known.contains(cc)) { noData.add(cc); continue; }
            for (int y = start.getYear(); y <= end.getYear(); y++) {
                try {
                    for (Holidays.Holiday h : Holidays.fetch(cc, y, offline, refresh)) {
                        if (h.date().isBefore(start) || h.date().isAfter(end)) continue;
                        if (nationalOnly && !h.national()) continue;
                        holidays.add(h);
                    }
                } catch (Exception e) {
                    if (!failed.contains(cc)) failed.add(cc);
                }
            }
        }
        holidays.sort(Comparator.comparing(Holidays.Holiday::date).thenComparing(h -> ccs.indexOf(h.cc())));

        int width = Term.width();
        if (span == Span_.WEEK || span == Span_.MONTH) {
            int h = ccs.size() + 6;
            Term.print(width, h, this::renderGrid);
        } else {
            int months = (int) (YearMonth.from(start).until(YearMonth.from(end), java.time.temporal.ChronoUnit.MONTHS) + 1);
            int perRow = Math.max(1, Math.min(months == 3 ? 3 : 6, (width - 2) / 24));
            if (months == 12 && perRow >= 4 && perRow < 6) perRow = 4;
            int rowsOfMonths = (months + perRow - 1) / perRow;
            final int pr = perRow;
            Term.print(width, 2 + rowsOfMonths * 10 + 2, (area, buf) -> renderCalendars(area, buf, months, pr));
        }
        if (!noList) {
            int listW = Math.min(width, 150);
            Term.print(listW, Math.max(4, holidays.size() + 3), this::renderList);
        }
        if (!noData.isEmpty() || !failed.isEmpty()) {
            List<String> msg = new ArrayList<>();
            if (!noData.isEmpty()) msg.add("no holiday data for " + names(noData));
            if (!failed.isEmpty()) msg.add("could not download " + names(failed) + (offline ? " (offline, not cached)" : ""));
            Term.println(Term.noColor ? "  " + String.join(" · ", msg) : "  \u001b[2m" + String.join(" · ", msg) + "\u001b[0m");
        }
        return 0;
    }

    static String ellipsis(String s, int w) {
        return s.length() <= w ? s : s.substring(0, w - 1) + "…";
    }

    String names(List<String> l) {
        List<String> out = new ArrayList<>();
        for (String cc : l) out.add(Geo.countryName(cc) + " (" + cc + ")");
        return String.join(", ", out);
    }

    // ------------------------------------------------------------------ arguments
    void resolvePeriod(LocalDate today) {
        LocalDate anchor = today;
        span = week ? Span_.WEEK : quarter ? Span_.QUARTER : year ? Span_.YEAR : Span_.MONTH;
        if (period != null) {
            String p = period.trim().toLowerCase(Locale.ROOT);
            Matcher m;
            if ((m = Pattern.compile("^(\\d{4})$").matcher(p)).matches()) {
                anchor = LocalDate.of(Integer.parseInt(m.group(1)), 1, 1);
                if (!week && !month && !quarter) span = Span_.YEAR;
            } else if ((m = Pattern.compile("^(?:(\\d{4})-)?q([1-4])$").matcher(p)).matches()) {
                int y = m.group(1) == null ? today.getYear() : Integer.parseInt(m.group(1));
                anchor = LocalDate.of(y, (Integer.parseInt(m.group(2)) - 1) * 3 + 1, 1);
                span = Span_.QUARTER;
            } else if ((m = Pattern.compile("^(?:(\\d{4})-)?w(\\d{1,2})$").matcher(p)).matches()) {
                int y = m.group(1) == null ? today.getYear() : Integer.parseInt(m.group(1));
                anchor = LocalDate.of(y, 6, 1).with(IsoFields.WEEK_OF_WEEK_BASED_YEAR, Integer.parseInt(m.group(2)))
                        .with(DayOfWeek.MONDAY);
                span = Span_.WEEK;
            } else if ((m = Pattern.compile("^(\\d{4})-(\\d{1,2})$").matcher(p)).matches()) {
                anchor = LocalDate.of(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), 1);
                if (!week && !quarter && !year) span = Span_.MONTH;
            } else if (monthIndex(p) > 0) {
                int mi = monthIndex(p);
                int y = today.getYear();
                anchor = LocalDate.of(y, mi, 1);
                if (!week && !quarter && !year) span = Span_.MONTH;
            } else {
                anchor = Dates.parseDate(period, today);
            }
        }
        switch (span) {
            case WEEK -> { start = anchor.with(DayOfWeek.MONDAY); end = start.plusDays(6); }
            case MONTH -> { start = anchor.withDayOfMonth(1); end = anchor.with(TemporalAdjusters.lastDayOfMonth()); }
            case QUARTER -> {
                int q = (anchor.getMonthValue() - 1) / 3;
                start = LocalDate.of(anchor.getYear(), q * 3 + 1, 1);
                end = start.plusMonths(3).minusDays(1);
            }
            case YEAR -> { start = anchor.withDayOfYear(1); end = start.plusYears(1).minusDays(1); }
        }
    }

    static int monthIndex(String p) {
        String[] names = {"jan", "feb", "mar", "apr", "may", "jun", "jul", "aug", "sep", "oct", "nov", "dec"};
        for (int i = 0; i < 12; i++) if (p.length() >= 3 && p.startsWith(names[i])) return i + 1;
        return 0;
    }

    void resolveCountries(Config cfg) {
        Set<String> s = new LinkedHashSet<>();
        if (countries != null && !countries.isEmpty()) {
            for (String c : countries) {
                var country = Geo.findCountry(c.trim());
                if (country == null) throw new IllegalArgumentException("Unknown country: " + c);
                s.add(country.iso());
            }
        } else {
            List<Config.Entry> entries = Config.withLocal(cfg.zonesOrOverride(zones), refZone);
            for (Config.Entry e : entries) {
                String cc = Geo.countryOf(e.zone());
                if (!cc.isEmpty()) s.add(cc);
            }
        }
        ccs.addAll(s);
    }

    // ------------------------------------------------------------------ rendering
    Map<LocalDate, List<Holidays.Holiday>> byDate() {
        Map<LocalDate, List<Holidays.Holiday>> m = new HashMap<>();
        for (Holidays.Holiday h : holidays) m.computeIfAbsent(h.date(), k -> new ArrayList<>()).add(h);
        return m;
    }

    String title() {
        return switch (span) {
            case WEEK -> "week " + start.get(IsoFields.WEEK_OF_WEEK_BASED_YEAR) + " · " + start.format(DateTimeFormatter.ofPattern("d MMM", Locale.ENGLISH))
                    + " – " + end.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH));
            case MONTH -> start.format(DateTimeFormatter.ofPattern("MMMM yyyy", Locale.ENGLISH));
            case QUARTER -> "Q" + ((start.getMonthValue() - 1) / 3 + 1) + " " + start.getYear();
            case YEAR -> String.valueOf(start.getYear());
        };
    }

    Line titleLine() {
        return Line.from(Span.styled(" ◷ tzu ", Ui.TITLE), Span.styled("holidays · ", Ui.DIM),
                Span.styled(title() + " ", Style.create().fg(Ui.BRIGHT).bold()),
                Span.styled("· " + holidays.size() + " holidays in " + ccs.size() + " countries ", Ui.DIM));
    }

    /** Week / month: one row per country, one column per day. */
    void renderGrid(Rect area, Buffer buf) {
        int days = (int) (end.toEpochDay() - start.toEpochDay() + 1);
        int labelW = 30;
        int cw = span == Span_.WEEK ? Math.max(6, Math.min(18, (area.width() - 4 - labelW) / 7)) : area.width() - 4 - labelW >= days * 3 ? 3 : 2;
        int boxW = Math.min(area.width(), Math.max(titleLine().width() + 6, labelW + days * cw + 4));
        Rect box = new Rect(area.x(), area.y(), boxW, ccs.size() + 4);
        Ui.panel(titleLine(), null).render(box, buf);
        int x0 = box.x() + 2, gx = x0 + labelW, y = box.y() + 1;
        LocalDate today = LocalDate.now(refZone);
        for (int i = 0; i < days; i++) {
            LocalDate d = start.plusDays(i);
            boolean we = d.getDayOfWeek().getValue() >= 6;
            Style st = d.equals(today) ? Style.create().fg(Ui.BAD).bold() : Style.create().fg(we ? Ui.MUTED : Ui.ACCENT2);
            String dow = cw >= 6 ? Ui.dow(d) : Ui.dow(d).substring(0, cw >= 3 ? 2 : 1);
            buf.setString(gx + i * cw, y, Ui.pad(dow, cw), st);
            buf.setString(gx + i * cw, y + 1, Ui.pad(cw >= 6 ? String.format("%02d %s", d.getDayOfMonth(), "") : String.format("%2d", d.getDayOfMonth()), cw), st);
        }
        y += 2;
        Map<String, Map<LocalDate, Holidays.Holiday>> perCountry = new LinkedHashMap<>();
        for (Holidays.Holiday h : holidays) {
            var m = perCountry.computeIfAbsent(h.cc(), k -> new HashMap<>());
            Holidays.Holiday prev = m.get(h.date());
            if (prev == null || !prev.national() && h.national()) m.put(h.date(), h);
        }
        for (String cc : ccs) {
            ZoneId z = Geo.mainZone(cc);
            String off = Ui.offset(z.getRules().getOffset(start.atStartOfDay(z).toInstant()));
            Style nameStyle = noData.contains(cc) || failed.contains(cc) ? Ui.DIM : Ui.NORMAL;
            buf.setLine(x0, y, Line.from(Span.styled(cc + " ", Style.create().fg(Ui.ACCENT).bold()),
                    Span.styled(Ui.pad(ellipsis(Geo.countryName(cc), labelW - 13), labelW - 12), nameStyle),
                    Span.styled(Ui.pad(off, 9), Ui.DIM)), labelW);
            for (int i = 0; i < days; i++) {
                LocalDate d = start.plusDays(i);
                boolean we = d.getDayOfWeek().getValue() >= 6;
                Holidays.Holiday h = perCountry.getOrDefault(cc, Map.of()).get(d);
                Color bg = h != null ? (h.national() ? NATIONAL : REGIONAL) : we ? Ui.WEEKEND_BG : Color.rgb(0x1f, 0x23, 0x35);
                if (i % 2 == 1) bg = Ui.shade(bg, 0.88);
                String txt = h != null ? (h.national() ? "★" : "◐") : noData.contains(cc) ? "?" : "·";
                if (cw >= 6 && h != null) txt = " " + ellipsis(h.name(), cw - 1);
                Style st = Style.create().bg(bg).fg(h != null ? Ui.BRIGHT : Ui.MUTED);
                if (d.equals(today)) st = st.underlined();
                buf.setString(gx + i * cw, y, cw >= 6 ? Ui.pad(txt, cw) : Ui.center(txt, cw), st);
            }
            y++;
        }
        buf.setLine(area.x() + 1, box.bottom(), Ui.legend(NATIONAL, "national holiday ★", REGIONAL, "regional ◐", Ui.WEEKEND_BG, "weekend")
                .append(Line.from(Span.styled("? no data", Ui.DIM))), area.width() - 2);
    }

    /** Quarter / year: monthly calendars, days coloured by how many countries are off. */
    void renderCalendars(Rect area, Buffer buf, int months, int perRow) {
        buf.setLine(area.x() + 1, area.y(), titleLine(), area.width() - 2);
        Map<LocalDate, List<Holidays.Holiday>> bd = byDate();
        LocalDate today = LocalDate.now(refZone);
        for (int i = 0; i < months; i++) {
            LocalDate first = start.plusMonths(i);
            CalendarEventStore store = CalendarEventStore.empty();
            for (LocalDate d = first; d.getMonth() == first.getMonth(); d = d.plusDays(1)) {
                List<Holidays.Holiday> hs = bd.getOrDefault(d, List.of());
                long national = hs.stream().filter(Holidays.Holiday::national).map(Holidays.Holiday::cc).distinct().count();
                long any = hs.stream().map(Holidays.Holiday::cc).distinct().count();
                Style st = null;
                if (national >= 2) st = Style.create().bg(MULTI).fg(Ui.BRIGHT).bold();
                else if (national == 1) st = Style.create().bg(NATIONAL).fg(Ui.BRIGHT).bold();
                else if (any >= 1) st = Style.create().bg(REGIONAL).fg(Ui.BRIGHT);
                else if (d.getDayOfWeek().getValue() >= 6) st = Style.create().fg(Ui.MUTED);
                if (d.equals(today)) st = (st == null ? Style.create().fg(Ui.BAD) : st).bold().underlined();
                if (st != null) store = store.add(d, st);
            }
            Monthly cal = Monthly.of(first, store)
                    .showMonthHeader(Style.create().fg(Ui.ACCENT).bold())
                    .showWeekdaysHeader(Style.create().fg(Ui.ACCENT2))
                    .defaultStyle(Ui.NORMAL)
                    .firstDayOfWeek(DayOfWeek.MONDAY);
            int col = i % perRow, row = i / perRow;
            Rect r = new Rect(area.x() + col * 24, area.y() + 1 + row * 10, 24, 10);
            Ui.panel((String) null).render(r, buf);
            cal.render(new Rect(r.x() + 2, r.y() + 1, 20, 8), buf);
        }
        int y = area.y() + 1 + ((months + perRow - 1) / perRow) * 10;
        if (y < area.bottom())
            buf.setLine(area.x() + 1, y, Ui.legend(MULTI, "several countries", NATIONAL, "one country", REGIONAL, "regional only")
                    .append(Line.from(Span.styled("countries: " + String.join(" ", ccs), Ui.DIM))), area.width() - 2);
    }

    void renderList(Rect area, Buffer buf) {
        List<Row> rows = new ArrayList<>();
        DateTimeFormatter df = DateTimeFormatter.ofPattern("EEE d MMM", Locale.ENGLISH);
        DateTimeFormatter tf = DateTimeFormatter.ofPattern("EEE HH:mm", Locale.ENGLISH);
        LocalDate today = LocalDate.now(refZone);
        for (Holidays.Holiday h : holidays) {
            ZoneId z = Geo.mainZone(h.cc());
            ZonedDateTime s = h.date().atStartOfDay(z).withZoneSameInstant(refZone);
            ZonedDateTime e = h.date().plusDays(1).atStartOfDay(z).withZoneSameInstant(refZone);
            long daysAway = h.date().toEpochDay() - today.toEpochDay();
            String when = daysAway == 0 ? "today" : daysAway > 0 ? "in " + daysAway + "d" : -daysAway + "d ago";
            String scope = h.national() ? "national" : h.regions().size() <= 3 ? String.join(" ", h.regions()).replace(h.cc() + "-", "")
                    : h.regions().size() + " regions";
            String local = h.localName().equals(h.name()) ? "" : h.localName();
            rows.add(Row.from(
                    Cell.from(Span.styled(h.date().format(df), Style.create().fg(h.date().getDayOfWeek().getValue() >= 6 ? Ui.MUTED : Ui.BRIGHT).bold())),
                    Cell.from(Span.styled(when, daysAway < 0 ? Ui.DIM : Style.create().fg(Ui.CYAN))),
                    Cell.from(Line.from(Span.styled(h.cc() + " ", Style.create().fg(Ui.ACCENT).bold()), Span.styled(Geo.countryName(h.cc()), Ui.NORMAL))),
                    Cell.from(Line.from(Span.styled(h.national() ? "★ " : "◐ ", Style.create().fg(h.national() ? NATIONAL : REGIONAL)),
                            Span.styled(h.name(), Style.create().fg(Ui.BRIGHT)))),
                    Cell.from(Span.styled(local, Ui.DIM)),
                    Cell.from(Span.styled(scope, h.national() ? Style.create().fg(Ui.GOOD) : Style.create().fg(Ui.WARN))),
                    Cell.from(Span.styled(s.format(tf) + " → " + e.format(tf), Style.create().fg(Ui.ACCENT2)))));
        }
        if (rows.isEmpty()) rows.add(Row.from(Cell.from(Span.styled("no holidays in this period", Ui.DIM))));
        Row header = Row.from("Date", "When", "Country", "Holiday", "Local name", "Scope", "In " + refLabel + " time").style(Ui.HEADER);
        Ui.table(buf, area, header, rows,
                List.of(Constraint.length(10), Constraint.length(8), Constraint.length(22), Constraint.fill(3), Constraint.fill(2),
                        Constraint.length(12), Constraint.length(21)), Ui.panel("holiday list · times in " + refLabel));
    }
}
