//JAVA 22+
//DEPS info.picocli:picocli:4.7.7
//DEPS info.picocli:picocli-codegen:4.7.7
//DEPS dev.tamboui:tamboui-widgets:0.7.0
//DEPS dev.tamboui:tamboui-tui:0.7.0
//DEPS dev.tamboui:tamboui-panama-backend:0.7.0
//JAVA_OPTIONS --enable-native-access=ALL-UNNAMED
//JAVAC_OPTIONS -proc:full
//NATIVE_OPTIONS --enable-native-access=ALL-UNNAMED -H:+UnlockExperimentalVMOptions -H:+ForeignAPISupport --enable-url-protocols=https
// Shared build directives for every tzu command live here (JBang merges directives from //SOURCES).

import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;

import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.util.concurrent.TimeUnit;
import java.util.function.BiConsumer;

/** Terminal helpers: size detection and one-shot rendering of TamboUI widgets to stdout. */
final class Term {
    private Term() {}

    static boolean noColor = System.getenv("NO_COLOR") != null && !System.getenv("NO_COLOR").isEmpty();

    private static final PrintStream OUT = new PrintStream(System.out, true, StandardCharsets.UTF_8);

    /** Current terminal width in columns (COLUMNS env, then stty, then 100). */
    static int width() {
        String forced = System.getenv("TZU_WIDTH");
        if (forced != null) {
            try { return Integer.parseInt(forced.trim()); } catch (NumberFormatException ignored) { }
        }
        int[] s = sttySize();
        if (s != null && s[1] > 0) return s[1];
        String cols = System.getenv("COLUMNS");
        if (cols != null) {
            try { return Integer.parseInt(cols.trim()); } catch (NumberFormatException ignored) { }
        }
        return 100;
    }

    /** Current terminal height in rows (stty, LINES env, then 40). */
    static int height() {
        int[] s = sttySize();
        if (s != null && s[0] > 0) return s[0];
        String lines = System.getenv("LINES");
        if (lines != null) {
            try { return Integer.parseInt(lines.trim()); } catch (NumberFormatException ignored) { }
        }
        return 40;
    }

    private static int[] cachedSize;

    private static int[] sttySize() {
        if (cachedSize != null) return cachedSize;
        if (System.getProperty("os.name", "").toLowerCase().contains("win")) return null;
        try {
            Process p = new ProcessBuilder("sh", "-c", "stty size < /dev/tty")
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            String out = new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8).trim();
            if (!p.waitFor(2, TimeUnit.SECONDS) || p.exitValue() != 0) return null;
            String[] parts = out.split("\\s+");
            if (parts.length == 2) {
                cachedSize = new int[]{Integer.parseInt(parts[0]), Integer.parseInt(parts[1])};
                return cachedSize;
            }
        } catch (Exception ignored) {
        }
        return null;
    }

    /** Renders into an off-screen buffer of the given size and prints it as ANSI text. */
    static void print(int width, int height, BiConsumer<Rect, Buffer> painter) {
        Buffer buffer = Buffer.empty(Rect.of(width, Math.max(1, height)));
        painter.accept(buffer.area(), buffer);
        String svg = System.getenv("TZU_SVG");
        if (svg != null && !svg.isBlank()) {
            try {
                dev.tamboui.export.ExportRequest.export(buffer).as(dev.tamboui.export.Formats.SVG)
                        .options(o -> o.title(System.getenv().getOrDefault("TZU_SVG_TITLE", "tzu"))).toFile(java.nio.file.Path.of(svg));
            } catch (java.io.IOException e) {
                error("cannot write " + svg + ": " + e.getMessage());
            }
        }
        String ansi = buffer.toAnsiStringTrimmed().replace("\r\n", "\n");
        if (noColor) ansi = stripAnsi(ansi);
        OUT.println(ansi);
    }

    static void println(String s) {
        OUT.println(noColor ? stripAnsi(s) : s);
    }

    static void error(String s) {
        System.err.println(s);
    }

    static String stripAnsi(String s) {
        return s.replaceAll("\u001b\\[[0-9;:?]*[ -/]*[@-~]", "")
                .replaceAll("\u001b\\]8;[^\u001b\u0007]*(\u001b\\\\|\u0007)", "");
    }
}
