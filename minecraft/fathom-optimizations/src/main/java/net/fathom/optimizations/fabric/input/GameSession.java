package net.fathom.optimizations.fabric.input;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.hud.FathomHud;
import net.fathom.optimizations.fabric.hud.Zoom;

/**
 * Notices when the player, the world or the connection changes, and clears the
 * per-session state modules keep.
 *
 * <p>Minecraft has no event for "this is a different game now" that covers every
 * case: respawning and changing dimension create a new player object, joining a
 * server or a singleplayer world creates a new world, and disconnecting sets
 * both to null. Comparing the two references once per frame catches all of them
 * with two pointer comparisons, and nothing that holds session state has to hook
 * a dozen vanilla methods to find out.
 *
 * <p>What is cleared: click counts, sprint and sneak latches, the zoom level
 * scrolled to (unless the module is told to remember it) and the crosshair's
 * transient feedback.
 */
public final class GameSession {

    private static Object lastWorld;
    private static Object lastPlayer;

    private GameSession() {}

    /** Called at the start of every frame. */
    public static void frame(MinecraftClient client) {
        if (client == null) {
            return;
        }
        if (client.world == lastWorld && client.player == lastPlayer) {
            if (client.player != null && client.player.getHealth() <= 0f) {
                // The death screen: latches go now, not when the respawned
                // player object arrives a moment later.
                MovementToggles.release();
            }
            return;
        }
        lastWorld = client.world;
        lastPlayer = client.player;
        MovementToggles.release();
        Zoom.sessionChanged();
        FathomHud hud = Runtime.hud();
        if (hud != null) {
            hud.clicks().reset();
        }
        net.fathom.optimizations.fabric.hud.CrosshairWidget.reset();
    }
}
