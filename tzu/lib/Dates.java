import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.MonthDay;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/** Lenient parsing of the dates and times typed on the command line. */
final class Dates {
    private Dates() {}

    /**
     * Accepts: today, tomorrow, yesterday, +N / -N (days), a weekday name (next occurrence, today included),
     * yyyy-MM-dd, MM-dd / dd/MM.
     */
    static LocalDate parseDate(String s, LocalDate today) {
        if (s == null || s.isBlank()) return today;
        String v = s.trim().toLowerCase(Locale.ROOT);
        switch (v) {
            case "today", "now" -> { return today; }
            case "tomorrow", "tmr" -> { return today.plusDays(1); }
            case "yesterday" -> { return today.minusDays(1); }
            default -> { }
        }
        if (v.matches("[+-]\\d+d?")) return today.plusDays(Long.parseLong(v.replace("d", "").replace("+", "")));
        for (DayOfWeek d : DayOfWeek.values()) {
            String full = d.getDisplayName(TextStyle.FULL, Locale.ENGLISH).toLowerCase(Locale.ROOT);
            if (v.length() >= 3 && full.startsWith(v)) return today.with(TemporalAdjusters.nextOrSame(d));
        }
        if (v.matches("\\d{4}-\\d{1,2}-\\d{1,2}")) {
            return LocalDate.parse(v, DateTimeFormatter.ofPattern("yyyy-M-d"));
        }
        if (v.matches("\\d{1,2}-\\d{1,2}")) {
            MonthDay md = MonthDay.parse(v, DateTimeFormatter.ofPattern("M-d"));
            LocalDate d = md.atYear(today.getYear());
            return d.isBefore(today) ? d.plusYears(1) : d;
        }
        if (v.matches("\\d{1,2}/\\d{1,2}(/\\d{4})?")) {
            String[] p = v.split("/");
            int year = p.length == 3 ? Integer.parseInt(p[2]) : today.getYear();
            return LocalDate.of(year, Integer.parseInt(p[1]), Integer.parseInt(p[0]));
        }
        throw new IllegalArgumentException("Cannot understand date '" + s + "' (use yyyy-MM-dd, today, tomorrow, +3, fri...)");
    }

    /** Accepts 9, 09, 9:30, 0930, 9am, 3pm, 3:30pm. */
    static LocalTime parseTime(String s) {
        String v = s.trim().toLowerCase(Locale.ROOT).replace(" ", "");
        boolean pm = v.endsWith("pm"), am = v.endsWith("am");
        if (pm || am) v = v.substring(0, v.length() - 2);
        int h, m = 0;
        if (v.contains(":")) {
            String[] p = v.split(":");
            h = Integer.parseInt(p[0]);
            m = Integer.parseInt(p[1]);
        } else if (v.length() == 4) {
            h = Integer.parseInt(v.substring(0, 2));
            m = Integer.parseInt(v.substring(2));
        } else {
            h = Integer.parseInt(v);
        }
        if (pm && h < 12) h += 12;
        if (am && h == 12) h = 0;
        return LocalTime.of(h, m);
    }

    /** "9-18", "9:30-17:30" -> {start hour, end hour} (minutes are rounded down). */
    static int[] parseRange(String s) {
        String[] p = s.split("-");
        if (p.length != 2) throw new IllegalArgumentException("Expected a range like 9-18");
        return new int[]{parseTime(p[0]).getHour(), parseTime(p[1]).getHour()};
    }
}
