package net.fathom.optimizations.fabric.gfx;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import net.fathom.optimizations.core.ui.NavigationHistory;
import net.fathom.optimizations.fabric.hub.FathomHubScreen;
import net.fathom.optimizations.fabric.hud.FathomHud;

/**
 * Explicit return targets for every Fathom screen transition.
 *
 * <p>A stored {@code null} means "the world", not "guess what Minecraft will
 * substitute". A stored main-menu instance means that exact Fathom menu. This
 * is the distinction the old close path lost when it always called
 * {@code setScreen(null)}.
 */
public final class FathomNavigation {

    private static final NavigationHistory<Screen> HISTORY = new NavigationHistory<Screen>();

    private FathomNavigation() {}

    public static FathomHubScreen openHub(MinecraftClient client, FathomHud hud) {
        if (client == null || hud == null) {
            return null;
        }
        FathomHubScreen hub = new FathomHubScreen(hud);
        push(client, hub);
        return hub;
    }

    public static void push(MinecraftClient client, Screen next) {
        if (client == null || next == null) {
            return;
        }
        HISTORY.push(next, client.currentScreen);
        client.setScreen(next);
    }

    /** Return to the exact screen stored when {@code current} was pushed. */
    public static boolean back(MinecraftClient client, Screen current) {
        if (client == null) {
            return false;
        }
        NavigationHistory.Return<Screen> target = HISTORY.back(current);
        if (!target.found()) {
            return false;
        }
        client.setScreen(target.target());
        return true;
    }

    /** Clear stale history after an unrelated screen replacement. */
    public static void clear() {
        HISTORY.clear();
    }
}
