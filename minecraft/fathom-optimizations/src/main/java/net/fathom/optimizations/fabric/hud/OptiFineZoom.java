package net.fathom.optimizations.fabric.hud;

import java.lang.reflect.Field;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;

/**
 * OptiFine's own zoom key, when OptiFine is patched in.
 *
 * <p>OptiFine adds a public {@code ofKeyBindZoom} to the game options and
 * zooms while it is held. It is looked up by name, once: without OptiFine the
 * field does not exist and every answer here is "no".
 */
final class OptiFineZoom {

    private static final Field FIELD = find();
    private static boolean announced;

    private OptiFineZoom() {}

    private static Field find() {
        try {
            Field field = GameOptions.class.getField("ofKeyBindZoom");
            return KeyBinding.class.isAssignableFrom(field.getType()) ? field : null;
        } catch (NoSuchFieldException | SecurityException | LinkageError absent) {
            return null;
        }
    }

    private static KeyBinding binding(MinecraftClient client) {
        if (FIELD == null || client == null || client.options == null) {
            return null;
        }
        try {
            return (KeyBinding) FIELD.get(client.options);
        } catch (IllegalAccessException | RuntimeException unreadable) {
            return null;
        }
    }

    /** Whether OptiFine's zoom key is held right now. */
    static boolean held(MinecraftClient client) {
        KeyBinding binding = binding(client);
        if (binding == null) {
            return false;
        }
        announce(binding);
        return binding.isPressed();
    }

    /** Say once, in the log, when the two zooms share a key. */
    private static void announce(KeyBinding binding) {
        if (announced) {
            return;
        }
        announced = true;
        net.fathom.optimizations.core.module.FathomModule zoom = Widgets.module("zoom");
        if (zoom == null || zoom.keybinds().isEmpty()) {
            return;
        }
        if (zoom.keybinds().get(0).key() == binding.getCode()) {
            net.fathom.optimizations.core.module.ModuleLog.say(
                    "OptiFine's zoom uses the same key as Fathom's Zoom. OptiFine's zoom wins while that key"
                            + " is held; rebind one of them to use Fathom's.");
        }
    }
}
