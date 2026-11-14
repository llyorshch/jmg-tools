import dev.tamboui.buffer.Buffer;
import dev.tamboui.layout.Rect;
import dev.tamboui.tui.TuiConfig;
import dev.tamboui.tui.TuiRunner;
import dev.tamboui.tui.event.KeyEvent;

import java.time.Duration;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/** Full-screen TamboUI loop used by the live (--watch) and interactive (-i) modes. */
final class Live {
    private Live() {}

    /**
     * @param tick     redraw interval (null for redraw on input only)
     * @param painter  draws the whole screen
     * @param onKey    handles a key; return true if the screen must be redrawn. q / Esc / Ctrl+C always quit.
     */
    static void run(Duration tick, BiConsumer<Rect, Buffer> painter, Predicate<KeyEvent> onKey) throws Exception {
        var cfg = TuiConfig.builder().alternateScreen(true).hideCursor(true).rawMode(true);
        if (tick != null) cfg.tickRate(tick); else cfg.noTick();
        try (TuiRunner tui = TuiRunner.create(cfg.build())) {
            tui.run((event, runner) -> {
                if (event instanceof KeyEvent k) {
                    if (k.isQuit() || k.isChar('q') || k.code() == dev.tamboui.tui.event.KeyCode.ESCAPE) {
                        runner.quit();
                        return false;
                    }
                    return onKey == null || onKey.test(k);
                }
                return true; // ticks, resizes
            }, frame -> painter.accept(frame.area(), frame.buffer()));
        }
    }
}
