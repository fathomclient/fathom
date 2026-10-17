package net.fathom.optimizations.fabric.perf;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

import net.fathom.optimizations.core.opt.perf.PerfCounters;

/**
 * "Show performance statistics": what the layer skipped, per frame, averaged
 * over the last half second.
 *
 * <p>The lines are rebuilt twice a second and kept, so the readout itself
 * formats nothing per frame. Drawn with Minecraft's font on the left edge, at
 * mid-height, clear of the default widget positions.
 */
public final class PerfOverlay {

    private PerfOverlay() {}

    private static final long WINDOW_NANOS = 500_000_000L;
    private static final PerfCounters LAST = new PerfCounters();
    private static final String[] LINES = {"Fathom performance", "", "", ""};
    private static long windowStarted;

    public static void draw(MinecraftClient client, int screenHeight) {
        long now = System.nanoTime();
        if (now - windowStarted >= WINDOW_NANOS) {
            rebuild();
            windowStarted = now;
        }
        TextRenderer font = client.textRenderer;
        int y = screenHeight / 2 - 24;
        for (String line : LINES) {
            font.drawWithShadow(line, 2f, y, 0xFFE0E0E0);
            y += 10;
        }
    }

    private static void rebuild() {
        PerfCounters now = PerfLayer.COUNTERS;
        long frames = Math.max(1L, now.frames - LAST.frames);
        LINES[1] = "entities " + per(now.entitiesConsidered - LAST.entitiesConsidered, frames)
                + "  culled " + per(now.entitiesCulled - LAST.entitiesCulled, frames);
        LINES[2] = "tiles " + per(now.tilesConsidered - LAST.tilesConsidered, frames)
                + "  culled " + per(now.tilesCulled - LAST.tilesCulled, frames)
                + "  nametags " + per(now.nametagsFast - LAST.nametagsFast, frames)
                + " fast / " + per(now.nametagsVanilla - LAST.nametagsVanilla, frames) + " vanilla";
        long replays = now.hudComposites - LAST.hudComposites;
        long records = now.hudRedraws - LAST.hudRedraws;
        LINES[3] = "HUD layer " + (replays + records == 0L ? "off"
                : Math.round(100d * replays / (replays + records)) + "% replayed")
                + "  labels held " + NametagRenderer.size();
        now.copyInto(LAST);
    }

    private static String per(long value, long frames) {
        double rate = value / (double) frames;
        long tenths = Math.round(rate * 10d);
        return (tenths / 10) + "." + (tenths % 10);
    }
}
