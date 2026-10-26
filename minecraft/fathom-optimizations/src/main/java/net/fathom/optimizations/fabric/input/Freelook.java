package net.fathom.optimizations.fabric.input;

import org.lwjgl.input.Keyboard;
import org.lwjgl.opengl.Display;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.Entity;

import net.fathom.optimizations.core.input.HubKeyPolicy;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Freelook: turn the camera around you without turning yourself.
 *
 * <p>While it is active the mouse moves the camera instead of the player, so
 * where you face, walk and aim stays exactly where it was, and the server
 * sees no change in your rotation at all. The camera is pointed by giving the
 * camera setup the free angles for the moment it reads them and putting the
 * player's own back straight after.
 *
 * <p>Some servers ask clients not to offer this; the module is marked
 * server-sensitive and is off until switched on.
 */
public final class Freelook {

    private static boolean active;
    private static boolean toggled;
    private static boolean wasDown = true;
    private static float yaw;
    private static float pitch;
    private static int perspectiveBefore;

    private static float savedYaw;
    private static float savedPitch;
    private static float savedPrevYaw;
    private static float savedPrevPitch;
    private static Entity swapped;
    private static boolean terrainDirty;

    private Freelook() {}

    public static boolean active() {
        return active;
    }

    /** The free camera's yaw, for the self-test. */
    public static float cameraYaw() {
        return yaw;
    }

    /** Self-test only: as if the key were pressed in Toggle mode. */
    public static void qaToggle() {
        toggled = !toggled;
    }

    private static FathomModule module() {
        if (Runtime.features() == null) {
            return null;
        }
        FathomModule module = Runtime.features().find("freelook");
        return module != null && module.enabled() && module.switchable() ? module : null;
    }

    /** Called once per frame: starts and stops it from the key. */
    public static void frame(MinecraftClient client) {
        FathomModule module = module();
        boolean playing = client != null && client.player != null
                && ScreenKinds.of(client.currentScreen) == HubKeyPolicy.Screen.NONE
                && Display.isCreated() && Display.isActive();
        Keybind key = null;
        if (module != null) {
            for (Keybind each : module.keybinds()) {
                if ("look".equals(each.id())) {
                    key = each;
                }
            }
        }
        boolean down = playing && key != null && held(key);
        boolean pressed = down && !wasDown;
        wasDown = down;
        boolean toggleMode = module != null && "Toggle".equals(module.choice("mode", "Hold"));
        if (toggleMode) {
            if (pressed) {
                toggled = !toggled;
            }
        } else {
            toggled = down;
        }
        // A frame that began with the angles still swapped means a render pass
        // threw between begin and end; the player must never keep camera angles.
        endCamera();
        boolean want = module != null && client != null && client.player != null && toggled;
        if (want && !active) {
            active = true;
            yaw = client.player.yaw;
            pitch = client.player.pitch;
            terrainDirty = true;
            perspectiveBefore = client.options.perspective;
            if (client.options.perspective == 0) {
                client.options.perspective = 1;
            }
        } else if (!want && active) {
            active = false;
            toggled = false;
            terrainDirty = true;
            if (client != null && client.options != null) {
                client.options.perspective = perspectiveBefore;
            }
        }
    }

    /** The mouse moved; true when it turned the free camera instead of the player. */
    public static boolean look(float dx, float dy) {
        if (!active) {
            return false;
        }
        FathomModule module = module();
        boolean invert = module != null && module.toggle("invert", false);
        yaw += dx * 0.15F;
        pitch += (invert ? dy : -dy) * 0.15F;
        pitch = Math.max(-90.0F, Math.min(90.0F, pitch));
        return true;
    }

    /**
     * True once after freelook starts or stops. The third-person camera moves
     * to a different place at those moments with the player's angles
     * unchanged, so the terrain search's own angle test would not notice.
     */
    public static boolean consumeTerrainDirty() {
        boolean dirty = terrainDirty;
        terrainDirty = false;
        return dirty;
    }

    /** The camera is about to be set up: give it the free angles. */
    public static void beginCamera(Entity camera) {
        endCamera();
        if (!active || camera == null || camera != MinecraftClient.getInstance().player) {
            return;
        }
        swapped = camera;
        savedYaw = camera.yaw;
        savedPitch = camera.pitch;
        savedPrevYaw = camera.prevYaw;
        savedPrevPitch = camera.prevPitch;
        camera.yaw = yaw;
        camera.prevYaw = yaw;
        camera.pitch = pitch;
        camera.prevPitch = pitch;
    }

    /** And put the player's own angles back. */
    public static void endCamera() {
        if (swapped == null) {
            return;
        }
        swapped.yaw = savedYaw;
        swapped.pitch = savedPitch;
        swapped.prevYaw = savedPrevYaw;
        swapped.prevPitch = savedPrevPitch;
        swapped = null;
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
