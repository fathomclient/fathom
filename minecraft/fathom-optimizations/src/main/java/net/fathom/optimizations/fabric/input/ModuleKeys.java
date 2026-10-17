package net.fathom.optimizations.fabric.input;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.input.HubKeyPolicy;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.registry.OptimizationId;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.hud.FathomHud;

/**
 * A module's "Toggle" key: switch the module on or off from in game.
 *
 * <p>Any module may declare a keybind with the id {@code toggle}; it is unbound
 * until somebody binds it. A press switches the module exactly as its switch in
 * the Hub does — optimizations it owns follow — saves the profile, and says
 * what happened in the action bar, because a key that changes something
 * invisible (Fullbright in daylight) with no feedback reads as a broken key.
 *
 * <p>Only while playing: never with a screen open, so a key shared with chat
 * typing does nothing while typing, and never while the window is in the
 * background.
 */
public final class ModuleKeys {

    private static final String TOGGLE = "toggle";

    private static List<FathomModule> owners = new ArrayList<FathomModule>();
    private static List<Keybind> keys = new ArrayList<Keybind>();
    private static boolean[] wasDown = new boolean[0];
    private static int builtFor = -1;

    private ModuleKeys() {}

    /** Called once per frame. */
    public static void poll(MinecraftClient client) {
        ModuleRegistry features = Runtime.features();
        FathomHud hud = Runtime.hud();
        if (client == null || features == null || hud == null) {
            return;
        }
        if (builtFor != features.size()) {
            build(features);
        }
        if (keys.isEmpty()) {
            return;
        }
        boolean playing = client.player != null
                && ScreenKinds.of(client.currentScreen) == HubKeyPolicy.Screen.NONE
                && Display.isCreated() && Display.isActive();
        for (int index = 0; index < keys.size(); index++) {
            Keybind key = keys.get(index);
            boolean down = playing && held(key);
            boolean pressed = down && !wasDown[index];
            wasDown[index] = down;
            if (pressed) {
                toggle(client, hud, owners.get(index));
            }
        }
    }

    private static void build(ModuleRegistry features) {
        List<FathomModule> nextOwners = new ArrayList<FathomModule>();
        List<Keybind> nextKeys = new ArrayList<Keybind>();
        for (FathomModule module : features.all()) {
            for (Keybind key : module.keybinds()) {
                if (TOGGLE.equals(key.id())) {
                    nextOwners.add(module);
                    nextKeys.add(key);
                }
            }
        }
        owners = nextOwners;
        keys = nextKeys;
        // Start every key as held, so one already down when the game starts
        // is not read as a press.
        wasDown = new boolean[nextKeys.size()];
        java.util.Arrays.fill(wasDown, true);
        builtFor = features.size();
    }

    private static void toggle(MinecraftClient client, FathomHud hud, FathomModule module) {
        if (!module.switchable()) {
            return;
        }
        boolean next = !module.enabled();
        module.setEnabled(next);
        if (Runtime.switches() != null) {
            for (String id : module.optimizations()) {
                Runtime.switches().setByUser(OptimizationId.of(id), next);
            }
        }
        hud.saveAll();
        if (client.inGameHud != null) {
            client.inGameHud.setOverlayMessage(module.name() + (next ? " on" : " off"), false);
        }
    }

    private static boolean held(Keybind key) {
        if (!key.isBound()) {
            return false;
        }
        if (key.isMouseButton()) {
            int button = key.mouseButton();
            return Mouse.isCreated() && button >= 0 && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
        }
        return Keyboard.isCreated() && key.key() > 0 && key.key() < Keyboard.KEYBOARD_SIZE
                && Keyboard.isKeyDown(key.key());
    }
}
