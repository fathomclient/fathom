package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotUtils;

/**
 * Deterministic visual QA for the HUD as it is actually drawn.
 *
 * <h2>Why this is not the HUD editor's capture</h2>
 *
 * <p>The editor draws the widgets from inside a Fathom screen, where a Fathom
 * frame is already open, the world is behind a scrim and the GL state came from
 * a screen. The in-game path shares none of that: it runs at the return of
 * {@code InGameHud.render}, opens its own frame in the middle of the game's own
 * pass, and hands the state back to a renderer that is about to draw the next
 * world frame. That is the path players use and it is the one that can break, so
 * it is the one worth a screenshot.
 *
 * <p>Started with {@code -PcaptureUi=hud}, which loads the development world
 * from the main menu, waits for the player and a settled frame rate, saves a
 * framebuffer capture and stops the client.
 */
public final class HudCapture {

    /** Frames in the world before the shot, so terrain and the frame rate settle. */
    private static final int SETTLE = 140;

    private static int frames;
    private static boolean captured;

    private HudCapture() {}

    /** Whether this run was asked for an in-game HUD capture. */
    public static boolean requested() {
        return "hud".equalsIgnoreCase(System.getProperty("fathom.captureUi", ""));
    }

    /** Called once per drawn HUD frame. */
    public static void frame(MinecraftClient client) {
        if (captured || !requested() || client == null || client.player == null) {
            return;
        }
        if (frames == 0) {
            // Timing starts with the first in-world frame, not with the client,
            // so world load and terrain generation are not averaged into what a
            // HUD costs to draw.
            net.fathom.optimizations.core.instrument.Probe.begin();
        }
        if (++frames < SETTLE) {
            return;
        }
        captured = true;
        net.fathom.optimizations.core.instrument.Probe.end();
        String override = System.getProperty("fathom.hudFont", "").trim();
        String font = override.isEmpty() ? "per-module" : override.toLowerCase(java.util.Locale.ROOT);
        String name = "fathom-hud-" + font + "-" + client.width + "x" + client.height + ".png";
        ScreenshotUtils.saveScreenshot(client.runDirectory, name,
                client.width, client.height, client.getFramebuffer());
        // What the HUD costs with the configured per-module typefaces. A
        // command-line override is only used for controlled comparison runs.
        System.out.println("[Fathom] UI QA hud " + font + ": "
                + net.fathom.optimizations.fabric.Runtime.hud().widgetCount()
                + " widgets, "
                + net.fathom.optimizations.core.instrument.Probe.meanMillis(
                        net.fathom.optimizations.core.instrument.Probe.Section.HUD)
                + " ms mean per frame.");
        client.scheduleStop();
    }
}
