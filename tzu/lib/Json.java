import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.temporal.ChronoUnit;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/** Minimal JSON writer for the {@code --json} output (no reflection, so it works in native images). */
final class Json {
    private Json() {}

    /** An ordered object from alternating keys and values; null values are kept. */
    static Map<String, Object> obj(Object... keysAndValues) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < keysAndValues.length; i += 2) m.put((String) keysAndValues[i], keysAndValues[i + 1]);
        return m;
    }

    static String write(Object value) {
        StringBuilder sb = new StringBuilder();
        append(sb, value);
        return sb.toString();
    }

    private static void append(StringBuilder sb, Object v) {
        switch (v) {
            case null -> sb.append("null");
            case Boolean b -> sb.append(b);
            case Double d when d.isNaN() || d.isInfinite() -> sb.append("null");
            case Number n -> sb.append(n);
            case Map<?, ?> m -> {
                sb.append('{');
                boolean first = true;
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    if (!first) sb.append(',');
                    first = false;
                    string(sb, String.valueOf(e.getKey()));
                    sb.append(':');
                    append(sb, e.getValue());
                }
                sb.append('}');
            }
            case List<?> l -> {
                sb.append('[');
                for (int i = 0; i < l.size(); i++) {
                    if (i > 0) sb.append(',');
                    append(sb, l.get(i));
                }
                sb.append(']');
            }
            default -> string(sb, v.toString());
        }
    }

    private static void string(StringBuilder sb, String s) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) sb.append(String.format("\\u%04x", (int) c));
                    else sb.append(c);
                }
            }
        }
        sb.append('"');
    }

    /** ISO-8601 with offset, to the second: 2026-12-03T09:00:00+01:00. */
    static String time(ZonedDateTime t) {
        return t.truncatedTo(ChronoUnit.SECONDS).format(DateTimeFormatter.ISO_OFFSET_DATE_TIME);
    }

    static String hhmm(ZonedDateTime t) {
        return t.format(DateTimeFormatter.ofPattern("HH:mm"));
    }

    static String weekday(ZonedDateTime t) {
        return t.getDayOfWeek().getDisplayName(TextStyle.FULL, Locale.ENGLISH);
    }

    /** Always numeric: +00:00 rather than Z. */
    static String offset(ZoneOffset o) {
        return o.getTotalSeconds() == 0 ? "+00:00" : o.getId();
    }

    /** work, shoulder (early morning or evening) or night. */
    static String period(Ui.Period p) {
        return p.name().toLowerCase(Locale.ROOT);
    }

    /** The clock of a zone at an instant, compared with a reference offset. */
    static Map<String, Object> clock(ZoneId zone, Instant at, ZoneOffset reference) {
        ZonedDateTime t = at.atZone(zone);
        boolean dst = zone.getRules().isDaylightSavings(at);
        return obj("zone", zone.getId(),
                "datetime", time(t),
                "date", t.toLocalDate().toString(),
                "time", hhmm(t),
                "weekday", weekday(t),
                "utc_offset", offset(t.getOffset()),
                "diff_from_local_minutes", (t.getOffset().getTotalSeconds() - reference.getTotalSeconds()) / 60,
                "abbreviation", Geo.displayName(zone, dst),
                "dst_active", dst);
    }
}
