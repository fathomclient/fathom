package net.fathom.optimizations.fabric.gfx;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.MinecraftClient;

/** Drains Fathom-screen keyboard events once per rendered frame. */
public final class FrameKeyboard {

    private static int suppressed = Integer.MIN_VALUE;

    private FrameKeyboard() {}

    /** Do not deliver the key-down that opened the screen to that screen. */
    public static void suppressUntilReleased(int keyCode) {
        suppressed = keyCode;
    }

    /** Drain before Minecraft's 20 Hz tick has a chance to consume the queue. */
    public static void pollCurrent(MinecraftClient client) {
        if (client != null && client.currentScreen instanceof FathomScreen) {
            poll((FathomScreen) client.currentScreen);
        }
    }

    /**
     * Own the LWJGL queue while a Fathom screen is current. Minecraft's next
     * 20 Hz tick then sees an empty queue instead of delivering the same event
     * a second time.
     */
    public static void poll(FathomScreen screen) {
        if (screen == null || !Keyboard.isCreated()) {
            return;
        }
        while (Keyboard.next()) {
            int key = Keyboard.getEventKey();
            boolean down = Keyboard.getEventKeyState();
            if (key == suppressed) {
                if (!down) {
                    suppressed = Integer.MIN_VALUE;
                }
                continue;
            }
            if (down && passThroughToVanilla(key)) {
                continue;
            }
            if (down) {
                screen.handleFathomKey(Keyboard.getEventCharacter(), key);
            }
        }
    }

    /**
     * Keys this drain must not eat, handed to the game instead.
     *
     * <h2>Why this is needed at all</h2>
     *
     * <p>{@link #poll} owns the LWJGL queue while a Fathom screen is current,
     * so that Minecraft's next tick does not deliver the same event a second
     * time. The cost is that it owns <em>every</em> event, not just the ones a
     * Fathom screen wants — and Fathom replaces the main menu, so from the
     * moment the game opens, F11 was being consumed here and vanilla's
     * fullscreen toggle never ran. It looked like F11 did nothing.
     *
     * <h2>Why this calls vanilla rather than toggling a window itself</h2>
     *
     * <p>Because 1.8.9 already has fullscreen, and a second implementation
     * beside it would be two things that disagree about the window's state.
     * {@code MinecraftClient.toggleFullscreen} is the same method vanilla's own
     * key handler calls; this restores the call that the drain removed rather
     * than adding a competing one.
     *
     * <p>An event cannot be put back once {@code Keyboard.next()} has taken it,
     * which is why this dispatches rather than simply declining to consume.
     *
     * @return whether the key was handled here and must not reach the screen.
     */
    private static boolean passThroughToVanilla(int key) {
        if (key != Keyboard.KEY_F11) {
            return false;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return false;
        }
        client.toggleFullscreen();
        return true;
    }
}
