package net.fathom.optimizations.fabric.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.Window;

/**
 * Minecraft's scaled GUI dimensions, cached until one of their inputs changes.
 *
 * <p>{@link Window} computes the integer GUI scale in its constructor. Several
 * Fathom render paths used to construct one every frame (the always-on HUD did
 * it even when no Fathom UI was visible). All callers are on the render thread,
 * so one small shared cache removes that steady allocation without making the
 * result stale during resize, fullscreen changes, GUI-scale changes, or Unicode
 * font changes.
 */
public final class MinecraftGuiSize {

    private static final MinecraftGuiSize SHARED = new MinecraftGuiSize();

    private int windowWidth = -1;
    private int windowHeight = -1;
    private int guiScaleOption = -1;
    private boolean unicodeOption;
    private int scaleFactor = 1;
    private int scaledWidth = 1;
    private int scaledHeight = 1;

    private MinecraftGuiSize() {}

    public static MinecraftGuiSize get(MinecraftClient client) {
        return SHARED.update(client);
    }

    private MinecraftGuiSize update(MinecraftClient client) {
        int option = client.options == null ? 0 : client.options.guiScale;
        boolean unicode = client.options != null && client.options.forcesUnicodeFont;
        if (client.width != windowWidth || client.height != windowHeight
                || option != guiScaleOption || unicode != unicodeOption) {
            windowWidth = client.width;
            windowHeight = client.height;
            guiScaleOption = option;
            unicodeOption = unicode;
            Window window = new Window(client);
            scaleFactor = Math.max(1, window.getScaleFactor());
            // Rounded up, as Minecraft's own whole-number size is: its
            // projection spans the exact quotient (1280 / 3 = 426.67), and a
            // size rounded down left the last two pixels of a full-screen
            // backdrop unpainted.
            scaledWidth = Math.max(1, (int) Math.ceil(window.getScaledWidth()));
            scaledHeight = Math.max(1, (int) Math.ceil(window.getScaledHeight()));
        }
        return this;
    }

    public int scaleFactor() {
        return scaleFactor;
    }

    public int width() {
        return scaledWidth;
    }

    public int height() {
        return scaledHeight;
    }
}
