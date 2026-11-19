import java.time.DayOfWeek;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.time.zone.ZoneOffsetTransition;
import java.time.zone.ZoneOffsetTransitionRule;
import java.time.zone.ZoneRules;
import java.util.List;
import java.util.Locale;

/** Human readable daylight saving time rules. */
final class Dst {
    private Dst() {}

    record Info(ZoneOffset standard, ZoneOffset daylight, boolean observes, boolean active,
                String starts, String ends, ZoneOffsetTransition next) {}

    static Info info(ZoneId zone, Instant at) {
        ZoneRules r = zone.getRules();
        ZoneOffset std = r.getStandardOffset(at);
        List<ZoneOffsetTransitionRule> rules = r.getTransitionRules();
        ZoneOffsetTransition next = r.nextTransition(at);
        // a transition within ~13 months means the zone still changes clocks (some zones have no rules but explicit transitions)
        boolean upcoming = next != null && next.getInstant().isBefore(at.plusSeconds(400L * 86400));
        ZoneOffsetTransition prev = r.previousTransition(at);
        boolean recent = prev != null && prev.getInstant().isAfter(at.minusSeconds(400L * 86400));
        boolean observes = !rules.isEmpty() || upcoming && recent;
        String starts = "—", ends = "—";
        ZoneOffset dst = null;
        for (ZoneOffsetTransitionRule tr : rules) {
            if (tr.getOffsetAfter().getTotalSeconds() > tr.getStandardOffset().getTotalSeconds()) {
                starts = describe(tr);
                dst = tr.getOffsetAfter();
            } else {
                ends = describe(tr);
            }
        }
        if (rules.isEmpty() && observes && next != null) {
            // describe by the actual dates
            DateTimeFormatter f = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH);
            if (next.isGap()) { starts = next.getDateTimeBefore().format(f); dst = next.getOffsetAfter(); }
            else ends = next.getDateTimeBefore().format(f);
            ZoneOffsetTransition after = r.nextTransition(next.getInstant());
            if (after != null && after.getInstant().isBefore(at.plusSeconds(800L * 86400))) {
                if (after.isGap()) { starts = after.getDateTimeBefore().format(f); dst = after.getOffsetAfter(); }
                else ends = after.getDateTimeBefore().format(f);
            }
            if (dst == null && r.isDaylightSavings(at)) dst = r.getOffset(at);
        }
        return new Info(std, dst, observes, r.isDaylightSavings(at), starts, ends, upcoming ? next : null);
    }

    /** The rules of a zone for the --json output; absent values are null. */
    static java.util.Map<String, Object> toJson(Info d) {
        ZoneOffsetTransition t = d.next();
        return Json.obj("observes_dst", d.observes(),
                "dst_active", d.active(),
                "standard_offset", Json.offset(d.standard()),
                "dst_offset", d.daylight() == null ? null : Json.offset(d.daylight()),
                "dst_starts", "—".equals(d.starts()) ? null : d.starts(),
                "dst_ends", "—".equals(d.ends()) ? null : d.ends(),
                "next_change", t == null ? null : Json.obj(
                        "at", t.getInstant().toString(),
                        "local_before", t.getDateTimeBefore().toString(),
                        "local_after", t.getDateTimeAfter().toString(),
                        "change_minutes", (t.getOffsetAfter().getTotalSeconds() - t.getOffsetBefore().getTotalSeconds()) / 60));
    }

    static String describe(ZoneOffsetTransitionRule tr) {
        String month = tr.getMonth().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
        DayOfWeek dow = tr.getDayOfWeek();
        int dom = tr.getDayOfMonthIndicator();
        String day;
        if (dow == null) {
            day = month + " " + dom;
        } else {
            String d = dow.getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
            if (dom < 0) {
                day = (dom == -1 ? "last " : ordinal(-dom) + "-last ") + d + " " + month;
            } else if (dom + 6 == tr.getMonth().maxLength()) {
                day = "last " + d + " " + month;
            } else if ((dom - 1) % 7 == 0) {
                day = ordinal((dom - 1) / 7 + 1) + " " + d + " " + month;
            } else {
                day = d + " ≥ " + month + " " + dom;
            }
        }
        String time = tr.isMidnightEndOfDay() ? "24:00" : tr.getLocalTime().toString();
        String def = switch (tr.getTimeDefinition()) { case UTC -> " UTC"; case STANDARD -> " std"; case WALL -> ""; };
        return day + " " + time + def;
    }

    static String ordinal(int n) {
        return switch (n) { case 1 -> "1st"; case 2 -> "2nd"; case 3 -> "3rd"; default -> n + "th"; };
    }

    static String transition(ZoneOffsetTransition t) {
        if (t == null) return "—";
        int delta = t.getOffsetAfter().getTotalSeconds() - t.getOffsetBefore().getTotalSeconds();
        return t.getDateTimeBefore().format(DateTimeFormatter.ofPattern("EEE d MMM yyyy HH:mm", Locale.ENGLISH))
                + "→" + t.getDateTimeAfter().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH))
                + " " + (delta > 0 ? "+" : "−") + Ui.diff(Math.abs(delta)).substring(1);
    }

    /** Compact form: "14 Mar 2027 02:00→03:00". */
    static String transitionShort(ZoneOffsetTransition t) {
        if (t == null) return "—";
        return t.getDateTimeBefore().format(DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH))
                + "→" + t.getDateTimeAfter().format(DateTimeFormatter.ofPattern("HH:mm", Locale.ENGLISH));
    }

    /** A key that groups zones following the same rule set (e.g. all of the EU). */
    static String signature(ZoneId zone) {
        List<ZoneOffsetTransitionRule> rules = zone.getRules().getTransitionRules();
        if (rules.isEmpty()) return "";
        StringBuilder sb = new StringBuilder();
        for (ZoneOffsetTransitionRule tr : rules) {
            boolean start = tr.getOffsetAfter().getTotalSeconds() > tr.getStandardOffset().getTotalSeconds();
            sb.append(start ? "▲ " : "▼ ").append(describe(tr)).append("  ");
        }
        return sb.toString().trim();
    }
}
