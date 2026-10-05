package net.fathom.optimizations.fabric.gfx;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.hub.FathomHubScreen;

/** Frame-rate hotkey edges for opening and closing the Hub. */
public final class FrameInput {

    private static int binding = Integer.MIN_VALUE;
    private static boolean wasDown;

    private FrameInput() {}

    public static void poll(MinecraftClient client) {
        if (client == null || Runtime.hud() == null) {
            binding = Integer.MIN_VALUE;
            wasDown = false;
            return;
        }
        Keybind hubKey = Runtime.hud().hubKey();
        int code = hubKey.key();
        boolean down = held(hubKey);

        // A key assigned while it is physically down is a baseline, not a new
        // press. Without this, capture completes and the same press closes the
        // Hub on the following frame.
        if (code != binding) {
            binding = code;
            wasDown = down;
            return;
        }

        boolean pressed = down && !wasDown;
        wasDown = down;
        if (!pressed) {
            return;
        }

        if (client.currentScreen instanceof FathomHubScreen) {
            FathomHubScreen hub = (FathomHubScreen) client.currentScreen;
            if (hub.capturingKeybind()) {
                return;
            }
            if (hubKey.isMouseButton()) {
                hub.consumeMouseButton(hubKey.mouseButton());
            }
            hub.toggleOpen();
        } else if (!(client.currentScreen instanceof FathomScreen)) {
            Runtime.features().seal();
            FathomNavigation.openHub(client, Runtime.hud());
        } else {
            return;
        }

        if (!hubKey.isMouseButton()) {
            FrameKeyboard.suppressUntilReleased(code);
        }
    }

    private static boolean held(Keybind key) {
        if (!key.isBound()) {
            return false;
        }
        if (key.isMouseButton()) {
            int button = key.mouseButton();
            return Mouse.isCreated() && button >= 0 && button < Mouse.getButtonCount()
                    && Mouse.isButtonDown(button);
        }
        return Keyboard.isCreated() && key.key() >= 0 && key.key() < Keyboard.KEYBOARD_SIZE
                && Keyboard.isKeyDown(key.key());
    }
}
