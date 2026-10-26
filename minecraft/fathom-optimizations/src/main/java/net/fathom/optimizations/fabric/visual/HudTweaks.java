package net.fathom.optimizations.fabric.visual;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.input.HubKeyPolicy;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.fabric.input.MouseButtons;
import net.fathom.optimizations.fabric.input.ScreenKinds;

/**
 * Titles, Boss Bar, Hit Colour and Snaplook, as their hooks ask for them.
 * Each reads the module at the moment vanilla does the thing it changes, so
 * switching one off is vanilla again at once.
 */
public final class HudTweaks {

    private HudTweaks() {}

    /** Whether a title ({@code subtitle} false) or subtitle arriving now should be dropped. */
    public static boolean hideTitle(boolean subtitle) {
        FathomModule module = WorldEffects.active("titles");
        return module != null && module.toggle(subtitle ? "hide.subtitle" : "hide.title", false);
    }

    /** The scale to use in place of vanilla's title (4) or subtitle (2) scale. */
    public static float titleScale(float vanilla) {
        FathomModule module = WorldEffects.active("titles");
        if (module == null || (vanilla != 4.0F && vanilla != 2.0F)) {
            return vanilla;
        }
        return vanilla * (float) (module.number("scale", 100) / 100.0);
    }

    /** Whether the boss bar is hidden. */
    public static boolean hideBossBar() {
        return WorldEffects.active("boss-bar") != null;
    }

    /**
     * One channel of the tint a hurt entity is drawn with, in place of
     * vanilla's red at 30%: {@code channel} 0-2 is red, green, blue and 3 is
     * the strength.
     */
    public static float hitColour(int channel, float vanilla) {
        FathomModule module = WorldEffects.active("hit-colour");
        if (module == null) {
            return vanilla;
        }
        if (channel == 3) {
            return (float) (module.number("opacity", 30) / 100.0);
        }
        int rgb = module.colour("colour", 0xFF0000);
        int shift = 16 - channel * 8;
        return ((rgb >> shift) & 0xFF) / 255.0F;
    }

    /** How many more times to draw a potion's glint: 0 unless Shiny Pots is on and it is a potion. */
    public static int extraPotionGlint(net.minecraft.item.ItemStack stack) {
        if (stack == null || !(stack.getItem() instanceof net.minecraft.item.PotionItem)) {
            return 0;
        }
        FathomModule module = WorldEffects.active("shiny-pots");
        return module == null ? 0 : "Very bright".equals(module.choice("shine", "Bright")) ? 2 : 1;
    }

    private static boolean snapping;
    private static int perspectiveBefore;

    /** Snaplook: called once per frame; the view flips while the key is held. */
    public static void snaplook(MinecraftClient client) {
        if (client == null || client.options == null) {
            return;
        }
        FathomModule module = WorldEffects.active("snaplook");
        Keybind key = null;
        if (module != null) {
            for (Keybind each : module.keybinds()) {
                if ("look".equals(each.id())) {
                    key = each;
                }
            }
        }
        boolean playing = client.player != null
                && ScreenKinds.of(client.currentScreen) == HubKeyPolicy.Screen.NONE
                && Display.isCreated() && Display.isActive();
        boolean held = playing && key != null && held(key);
        if (held && !snapping) {
            snapping = true;
            perspectiveBefore = client.options.perspective;
            client.options.perspective = "Third person".equals(module.choice("view", "Behind you")) ? 1 : 2;
        } else if (!held && snapping) {
            snapping = false;
            client.options.perspective = perspectiveBefore;
        }
    }

    private static boolean held(Keybind key) {
        if (!key.isBound()) {
            return false;
        }
        if (key.isMouseButton()) {
            return MouseButtons.isDown(key.mouseButton());
        }
        return Keyboard.isCreated() && key.key() > 0 && key.key() < Keyboard.KEYBOARD_SIZE
                && Keyboard.isKeyDown(key.key());
    }
}
