package net.fathom.optimizations.fabric.hud;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Where the HUD's time goes, per widget.
 *
 * <h2>Why per widget and not one number</h2>
 *
 * <p>The HUD is one section in {@code Probe}, which answers "what does the HUD
 * cost" and not "which widget is expensive". When a widget grows a feature —
 * item rendering, potion icons, a second line — the total moves and nothing says
 * which one moved it, and the answer has to come from reading code rather than
 * from measuring.
 *
 * <p>Off unless {@code -PhudProfile} is passed to the development client, and the
 * flag is read once at class load, so a normal run pays a single static branch
 * per widget per frame.
 */
final class WidgetProfile {

    static final boolean ENABLED = Boolean.getBoolean("fathom.hudProfile");

    private static final List<String> ids = new ArrayList<String>();
    private static final List<long[]> totals = new ArrayList<long[]>();
    private static int frames;

    private WidgetProfile() {}

    /** The clock, or zero when profiling is off. */
    static long start() {
        return ENABLED ? System.nanoTime() : 0L;
    }

    /** Add the time since {@code started} to a widget's total. */
    static void stop(String id, long started) {
        if (!ENABLED || started == 0L) {
            return;
        }
        long elapsed = System.nanoTime() - started;
        int index = ids.indexOf(id);
        if (index < 0) {
            ids.add(id);
            totals.add(new long[] {0L, 0L});
            index = ids.size() - 1;
        }
        long[] entry = totals.get(index);
        entry[0] += elapsed;
        entry[1]++;
    }

    static void frame() {
        if (ENABLED) {
            frames++;
        }
    }

    /** Print the table. Called by the capture harness when it has enough frames. */
    static void report() {
        if (!ENABLED || frames == 0) {
            return;
        }
        System.out.println("[Fathom] HUD per-widget cost over " + frames + " frames:");
        for (int index = 0; index < ids.size(); index++) {
            long[] entry = totals.get(index);
            System.out.println(String.format(Locale.ROOT, "    %-14s %8.4f ms/frame (%d draws)",
                    ids.get(index), entry[0] / 1_000_000.0 / frames, entry[1]));
        }
    }
}
