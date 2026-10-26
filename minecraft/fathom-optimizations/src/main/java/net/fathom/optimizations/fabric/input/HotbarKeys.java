package net.fathom.optimizations.fabric.input;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/**
 * Hotbar keys that change the slot when pressed, in the order pressed.
 *
 * <h2>What vanilla does</h2>
 *
 * <p>1.8.9 reads the hotbar keys once per tick, after the tick has drained every
 * mouse and keyboard event, and checks them in slot order. Two consequences,
 * both felt most with side mouse buttons that get tapped fast:
 *
 * <ul>
 *   <li>Press 9 and then 8 inside the same 50 ms tick and the loop reaches 9
 *       last, so the slot ends on 9: the switch to 8 is lost.</li>
 *   <li>A press waits for the next tick, up to 50 ms, before the slot moves.</li>
 * </ul>
 *
 * <h2>What this does</h2>
 *
 * <p>{@link #pressed} runs as the tick reads each press, in event order, selects
 * that slot and consumes the press so vanilla's slot-order loop has nothing left
 * to reorder. {@link #frame} selects the slot on the frame the key goes down,
 * so it does not wait for the tick; the tick then lands on the same slot.
 *
 * <p>Only the selected slot changes, exactly as vanilla's loop would set it. The
 * server hears about it the way it always does: on the next tick, or before an
 * attack or a use, which sync the slot first.
 */
public final class HotbarKeys {

    private static final boolean[] wasDown = new boolean[9];

    private HotbarKeys() {}

    /** Called once per frame, before the tick. */
    public static void frame(MinecraftClient client) {
        if (client == null || client.options == null) {
            return;
        }
        KeyBinding[] keys = client.options.hotbarKeys;
        boolean playing = playing(client) && Display.isCreated() && Display.isActive();
        for (int slot = 0; slot < keys.length && slot < wasDown.length; slot++) {
            boolean down = isDown(keys[slot].getCode());
            if (down && !wasDown[slot] && playing) {
                client.player.inventory.selectedSlot = slot;
            }
            // Tracked with a screen open too, so a key held while closing the
            // inventory does not count as a fresh press afterwards.
            wasDown[slot] = down;
        }
    }

    /** Called as the tick's input loop processes a press of {@code code}. */
    public static void pressed(int code) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null || !playing(client)) {
            return;
        }
        KeyBinding[] keys = client.options.hotbarKeys;
        for (int slot = 0; slot < keys.length; slot++) {
            if (keys[slot].getCode() == code && code != 0) {
                // Consume this press: vanilla's loop would otherwise apply it
                // again later in the tick, in slot order rather than press order.
                keys[slot].wasPressed();
                client.player.inventory.selectedSlot = slot;
                return;
            }
        }
    }

    /** In a world, no screen, and not a spectator (whose hotbar is a menu vanilla runs). */
    private static boolean playing(MinecraftClient client) {
        return client.player != null && client.currentScreen == null && !client.player.isSpectator();
    }

    private static boolean isDown(int code) {
        if (code < 0) {
            int button = code + 100;
            return MouseButtons.isDown(button);
        }
        return code > 0 && Keyboard.isCreated() && code < Keyboard.KEYBOARD_SIZE && Keyboard.isKeyDown(code);
    }
}
