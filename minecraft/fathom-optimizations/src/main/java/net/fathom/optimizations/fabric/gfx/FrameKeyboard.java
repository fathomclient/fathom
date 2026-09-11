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
            if (down) {
                screen.handleFathomKey(Keyboard.getEventCharacter(), key);
            }
        }
    }
}
