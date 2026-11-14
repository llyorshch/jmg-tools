import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Constraint;
import dev.tamboui.layout.Rect;
import dev.tamboui.style.Color;
import dev.tamboui.style.Style;
import dev.tamboui.text.Line;
import dev.tamboui.text.Span;
import dev.tamboui.widgets.block.Block;
import dev.tamboui.widgets.block.BorderType;
import dev.tamboui.widgets.block.Title;
import dev.tamboui.widgets.table.Row;
import dev.tamboui.widgets.table.Table;
import dev.tamboui.widgets.table.TableState;
import dev.tamboui.widget.Widget;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.TextStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/** Shared palette, formatting and widget helpers. */
final class Ui {
    private Ui() {}

    // ----------------------------------------------------------------- palette
    static final Color ACCENT = Color.rgb(0x7a, 0xa2, 0xf7);
    static final Color ACCENT2 = Color.rgb(0xbb, 0x9a, 0xf7);
    static final Color MUTED = Color.rgb(0x73, 0x7a, 0xa2);
    static final Color TEXT = Color.rgb(0xc0, 0xca, 0xf5);
    static final Color BRIGHT = Color.rgb(0xff, 0xff, 0xff);
    static final Color GOOD = Color.rgb(0x9e, 0xce, 0x6a);
    static final Color WARN = Color.rgb(0xe0, 0xaf, 0x68);
    static final Color BAD = Color.rgb(0xf7, 0x76, 0x8e);
    static final Color CYAN = Color.rgb(0x7d, 0xcf, 0xff);
    static final Color BORDER = Color.rgb(0x3b, 0x42, 0x61);

    static final Color NIGHT_BG = Color.rgb(0x1a, 0x1f, 0x3c);
    static final Color NIGHT_FG = Color.rgb(0x56, 0x5f, 0x89);
    static final Color SHOULDER_BG = Color.rgb(0x8a, 0x5a, 0x1c);
    static final Color SHOULDER_FG = Color.rgb(0xff, 0xe0, 0xa8);
    static final Color WORK_BG = Color.rgb(0x2b, 0x7a, 0x4b);
    static final Color WORK_FG = Color.rgb(0xe8, 0xff, 0xee);
    static final Color WEEKEND_BG = Color.rgb(0x2a, 0x2e, 0x42);

    static final Style TITLE = Style.create().fg(ACCENT).bold();
    static final Style DIM = Style.create().fg(MUTED);
    static final Style NORMAL = Style.create().fg(TEXT);
    static final Style HEADER = Style.create().fg(ACCENT2).bold();

    enum Period { NIGHT, SHOULDER, WORK }

    static Period period(LocalTime t, int workStart, int workEnd) {
        double h = t.getHour() + t.getMinute() / 60.0;
        if (h >= workStart && h < workEnd) return Period.WORK;
        if (h >= 7 && h < workStart || h >= workEnd && h < 22) return Period.SHOULDER;
        return Period.NIGHT;
    }

    static Color periodBg(Period p) {
        return switch (p) { case WORK -> WORK_BG; case SHOULDER -> SHOULDER_BG; case NIGHT -> NIGHT_BG; };
    }

    static Color periodFg(Period p) {
        return switch (p) { case WORK -> WORK_FG; case SHOULDER -> SHOULDER_FG; case NIGHT -> NIGHT_FG; };
    }

    static Color periodText(Period p) {
        return switch (p) { case WORK -> GOOD; case SHOULDER -> WARN; case NIGHT -> Color.rgb(0x89, 0x9c, 0xf0); };
    }

    /** Sun/moon glyph for a local hour. */
    static String skyIcon(LocalTime t) {
        int h = t.getHour();
        if (h >= 6 && h < 8) return "◒";
        if (h >= 8 && h < 18) return "☀";
        if (h >= 18 && h < 20) return "◓";
        return "☾";
    }

    static Color skyColor(LocalTime t) {
        int h = t.getHour();
        if (h >= 8 && h < 18) return Color.rgb(0xff, 0xd8, 0x4d);
        if (h >= 6 && h < 8 || h >= 18 && h < 20) return Color.rgb(0xff, 0x9e, 0x64);
        return Color.rgb(0xa9, 0xb1, 0xd6);
    }

    // ----------------------------------------------------------------- formatting
    static String offset(ZoneOffset o) {
        int s = o.getTotalSeconds();
        if (s == 0) return "UTC±0";
        int h = Math.abs(s) / 3600, m = Math.abs(s) % 3600 / 60;
        return "UTC" + (s < 0 ? "−" : "+") + h + (m != 0 ? String.format(":%02d", m) : "");
    }

    static String diff(int seconds) {
        if (seconds == 0) return "±0h";
        int h = Math.abs(seconds) / 3600, m = Math.abs(seconds) % 3600 / 60;
        return (seconds < 0 ? "−" : "+") + h + (m != 0 ? String.format(":%02d", m) : "") + "h";
    }

    static String time(ZonedDateTime t, boolean h12) {
        return t.format(DateTimeFormatter.ofPattern(h12 ? "h:mm a" : "HH:mm", Locale.ENGLISH));
    }

    static String relDay(LocalDate d, LocalDate ref) {
        long delta = d.toEpochDay() - ref.toEpochDay();
        if (delta == 0) return "today";
        if (delta == 1) return "tomorrow";
        if (delta == -1) return "yesterday";
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + d.getDayOfMonth();
    }

    static String dow(LocalDate d) {
        return d.getDayOfWeek().getDisplayName(TextStyle.SHORT, Locale.ENGLISH);
    }

    static String pad(String s, int w) {
        int len = s.codePointCount(0, s.length());
        if (len >= w) return s.substring(0, s.offsetByCodePoints(0, w));
        return s + " ".repeat(w - len);
    }

    static String padLeft(String s, int w) {
        int len = s.codePointCount(0, s.length());
        return len >= w ? s : " ".repeat(w - len) + s;
    }

    static String center(String s, int w) {
        int len = s.codePointCount(0, s.length());
        if (len >= w) return s;
        int left = (w - len) / 2;
        return " ".repeat(left) + s + " ".repeat(w - len - left);
    }

    // ----------------------------------------------------------------- colors
    static Color hsl(double h, double s, double l) {
        h = ((h % 360) + 360) % 360;
        double c = (1 - Math.abs(2 * l - 1)) * s;
        double x = c * (1 - Math.abs((h / 60) % 2 - 1));
        double m = l - c / 2;
        double r, g, b;
        if (h < 60) { r = c; g = x; b = 0; }
        else if (h < 120) { r = x; g = c; b = 0; }
        else if (h < 180) { r = 0; g = c; b = x; }
        else if (h < 240) { r = 0; g = x; b = c; }
        else if (h < 300) { r = x; g = 0; b = c; }
        else { r = c; g = 0; b = x; }
        return Color.rgb((int) Math.round((r + m) * 255), (int) Math.round((g + m) * 255), (int) Math.round((b + m) * 255));
    }

    static Color shade(Color c, double f) {
        var rgb = c.toRgb();
        return Color.rgb(clamp(rgb.r() * f), clamp(rgb.g() * f), clamp(rgb.b() * f));
    }

    static Color mix(Color a, Color b, double t) {
        var x = a.toRgb();
        var y = b.toRgb();
        return Color.rgb(clamp(x.r() + (y.r() - x.r()) * t), clamp(x.g() + (y.g() - x.g()) * t), clamp(x.b() + (y.b() - x.b()) * t));
    }

    private static int clamp(double v) {
        return (int) Math.max(0, Math.min(255, Math.round(v)));
    }

    /** Distinct colour per UTC offset: hue walks the wheel, lightness alternates so neighbours differ. */
    static Color offsetColor(int offsetSeconds) {
        double hours = offsetSeconds / 3600.0;
        double hue = (hours + 12) / 26.0 * 330.0;
        boolean odd = Math.floorMod((int) Math.floor(hours), 2) == 1;
        boolean half = offsetSeconds % 3600 != 0;
        double light = half ? 0.72 : odd ? 0.62 : 0.50;
        double sat = half ? 0.95 : 0.70;
        return hsl(hue, sat, light);
    }

    /** Gradient between two colours across a string, one span per character. */
    static Line gradient(String text, Color from, Color to, boolean bold) {
        List<Span> spans = new ArrayList<>();
        int n = Math.max(1, text.length() - 1);
        for (int i = 0; i < text.length(); i++) {
            Style st = Style.create().fg(mix(from, to, (double) i / n));
            if (bold) st = st.bold();
            spans.add(Span.styled(String.valueOf(text.charAt(i)), st));
        }
        return Line.from(spans);
    }

    // ----------------------------------------------------------------- widgets
    /** A rounded panel; titles are drawn over the border so they keep their own colours. */
    static Widget panel(String title) {
        return panel(title == null ? null : Line.from(Span.styled(" " + title + " ", TITLE)), null);
    }

    static Widget panel(Line title, Line bottom) {
        Block block = Block.builder()
                .borders(dev.tamboui.widgets.block.Borders.ALL)
                .borderType(BorderType.ROUNDED)
                .borderStyle(Style.create().fg(BORDER))
                .build();
        return new Widget() {
            @Override
            public void render(Rect area, Buffer buf) {
                block.render(area, buf);
                if (area.width() > 4 && title != null) buf.setLine(area.x() + 2, area.y(), title, area.width() - 4);
                if (area.width() > 4 && area.height() > 1 && bottom != null) {
                    int w = Math.min(bottom.width(), area.width() - 4);
                    buf.setLine(area.right() - 2 - w, area.bottom() - 1, bottom, w);
                }
            }
        };
    }

    static Rect inner(Rect r) {
        return new Rect(r.x() + 1, r.y() + 1, Math.max(0, r.width() - 2), Math.max(0, r.height() - 2));
    }

    static void table(Buffer buf, Rect area, Row header, List<Row> rows, List<Constraint> widths, Widget panel) {
        Rect in = area;
        if (panel != null) {
            panel.render(area, buf);
            in = new Rect(area.x() + 2, area.y() + 1, Math.max(0, area.width() - 4), Math.max(0, area.height() - 2));
        }
        Table.builder().header(header).rows(rows).widths(widths).columnSpacing(1).build().render(in, buf, new TableState());
    }

    static Line legend(Object... pairs) {
        List<Span> spans = new ArrayList<>();
        for (int i = 0; i + 1 < pairs.length; i += 2) {
            Color c = (Color) pairs[i];
            spans.add(Span.styled("██", Style.create().fg(c)));
            spans.add(Span.styled(" " + pairs[i + 1] + "   ", DIM));
        }
        return Line.from(spans);
    }

    // ----------------------------------------------------------------- big font (3x5 pixels, half-block rendering)
    private static final String[] FONT = {
            // each glyph: 5 rows of 3 pixels
            "###|# #|# #|# #|###", // 0
            " # |## | # | # |###", // 1
            "###|  #|###|#  |###", // 2
            "###|  #|###|  #|###", // 3
            "# #|# #|###|  #|  #", // 4
            "###|#  |###|  #|###", // 5
            "###|#  |###|# #|###", // 6
            "###|  #|  #|  #|  #", // 7
            "###|# #|###|# #|###", // 8
            "###|# #|###|  #|###", // 9
    };

    /** Width in cells of {@link #bigText}. Each pixel is two cells wide. */
    static int bigWidth(String s) {
        int w = 0;
        for (char ch : s.toCharArray()) w += ch == ':' ? 4 : ch == ' ' ? 2 : 8;
        return w;
    }

    /** Draws digits and ':' using half blocks (3 rows high), coloured with a horizontal gradient. */
    static void bigText(Buffer buf, int x0, int y0, String s, Color from, Color to) {
        int total = Math.max(1, bigWidth(s));
        // build a pixel matrix 5 rows high
        int wpx = total / 2;
        boolean[][] px = new boolean[6][wpx];
        int cx = 0;
        for (char ch : s.toCharArray()) {
            if (ch >= '0' && ch <= '9') {
                String[] rows = FONT[ch - '0'].split("\\|");
                for (int r = 0; r < 5; r++)
                    for (int c = 0; c < 3; c++) px[r][cx + c] = rows[r].charAt(c) == '#';
                cx += 4;
            } else if (ch == ':') {
                px[1][cx] = true;
                px[3][cx] = true;
                cx += 2;
            } else {
                cx += 1;
            }
        }
        for (int row = 0; row < 3; row++) {
            for (int c = 0; c < wpx; c++) {
                boolean top = px[row * 2][c], bot = row * 2 + 1 < 6 && px[row * 2 + 1][c];
                String g = top && bot ? "█" : top ? "▀" : bot ? "▄" : " ";
                Color col = mix(from, to, (double) c / Math.max(1, wpx - 1));
                for (int k = 0; k < 2; k++) {
                    int x = x0 + c * 2 + k;
                    if (x < buf.area().right() && y0 + row < buf.area().bottom())
                        buf.setString(x, y0 + row, g, Style.create().fg(col));
                }
            }
        }
    }

    static ZonedDateTime now(ZoneId z) {
        return ZonedDateTime.now(z);
    }
}
