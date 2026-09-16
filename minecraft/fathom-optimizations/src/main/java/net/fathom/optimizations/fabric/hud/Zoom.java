package net.fathom.optimizations.fabric.hud;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.input.ZoomMath;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;

/**
 * Hold a key to narrow the field of view.
 *
 * <h2>Nothing is written to the game's options</h2>
 *
 * <p>An earlier version zoomed by writing {@code GameOptions.sensitivity} and
 * {@code smoothCameraEnabled} and putting them back on release. That is state in
 * a file Minecraft saves: anything that saved the options mid-zoom — or a crash —
 * kept the zoomed sensitivity for good, and a change the player made while zoomed
 * was silently overwritten. Now each of the three is changed where vanilla reads
 * it, for that read only:
 *
 * <ul>
 *   <li>the field of view in {@code getFov}, for the world and not the hand;
 *   <li>the sensitivity where {@code GameRenderer} turns mouse travel into
 *       camera rotation;
 *   <li>the smooth camera where the same code decides whether to smooth.
 * </ul>
 *
 * <p>Releasing the key therefore restores exactly the original state, because the
 * original state was never touched. The same is true when a screen opens, the
 * window loses focus, the world changes, the player dies or the profile switches:
 * each makes {@link #wanted} false and the zoom eases out.
 *
 * <h2>Why the animation is driven by elapsed time</h2>
 *
 * <p>{@link #factor} is asked more than once per frame (the world and the sky
 * both project), and it advances by the real time since it was last asked. Two
 * calls in one frame each advance by part of the elapsed time, so the animation
 * runs at the same speed at 30 fps and 300.
 */
public final class Zoom {

    private static long lastNanos;
    /** 0 is no zoom, 1 is fully zoomed. Linear; eased on read. */
    private static float progress;
    private static boolean wasWanted;
    /** The divisor this zoom is heading for; NaN until the first press. */
    private static double level = Double.NaN;
    /** The divisor applied this frame, for sensitivity. */
    private static double current = 1.0;

    private Zoom() {}

    private static FathomModule module() {
        return net.fathom.optimizations.fabric.Runtime.features() == null
                ? null
                : net.fathom.optimizations.fabric.Runtime.features().find("zoom");
    }

    /**
     * The divisor to apply to the world's field of view this frame.
     *
     * <p>1 means no zoom, and is the answer whenever the module is off, the key
     * is unbound, or the key is not held.
     */
    public static float factor() {
        FathomModule module = module();
        boolean wanted = wanted(module);

        if (wanted && !wasWanted && (Double.isNaN(level) || !"Remember".equals(module.choice("release", "Reset")))) {
            level = module.number("amount", 4);
        }
        wasWanted = wanted;
        advance(wanted, module);

        if (progress <= 0f || Double.isNaN(level)) {
            current = 1.0;
            return 1f;
        }
        current = ZoomMath.divisor(level, eased(progress));
        return (float) current;
    }

    private static boolean wanted(FathomModule module) {
        MinecraftClient client = MinecraftClient.getInstance();
        return module != null
                && module.enabled()
                && module.switchable()
                && client != null
                && client.player != null
                && client.currentScreen == null
                && Display.isCreated()
                && Display.isActive()
                && held(module);
    }

    /**
     * Whether the zoom key is down.
     *
     * <p>A binding can be a mouse button, so this asks the right device rather
     * than handing a negative code to {@code Keyboard.isKeyDown} -- which is an
     * array index, and would throw inside the render loop.
     */
    private static boolean held(FathomModule module) {
        for (Keybind binding : module.keybinds()) {
            if (!"zoom".equals(binding.id()) || !binding.isBound()) {
                continue;
            }
            if (binding.isMouseButton()) {
                int button = binding.mouseButton();
                return Mouse.isCreated() && button < Mouse.getButtonCount() && Mouse.isButtonDown(button);
            }
            return Keyboard.isCreated() && binding.key() > 0 && binding.key() < Keyboard.KEYBOARD_SIZE
                    && Keyboard.isKeyDown(binding.key());
        }
        return false;
    }

    private static void advance(boolean wanted, FathomModule module) {
        long now = System.nanoTime();
        float elapsed = lastNanos == 0L ? 0f : Math.min(0.25f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        float target = wanted ? 1f : 0f;
        boolean animate = module == null || module.toggle("animate", true);
        if (!animate) {
            progress = target;
            return;
        }
        float duration = (float) Math.max(0.01, (module == null ? 160 : module.number("speed", 160)) / 1000.0);
        float step = elapsed / duration;
        progress = target > progress ? Math.min(target, progress + step) : Math.max(target, progress - step);
    }

    /** Smoothstep: starts and ends gently, symmetric in and out. */
    private static float eased(float t) {
        return t * t * (3f - 2f * t);
    }

    /** Whether any zoom is on screen, including the ease out. */
    public static boolean active() {
        return progress > 0f && current > 1.0001;
    }

    /**
     * The sensitivity vanilla should turn the camera with this frame.
     *
     * <p>Scaled along vanilla's own curve by {@link ZoomMath#compensatedSensitivity},
     * in step with the picture as it eases, so the mouse slows with the zoom
     * rather than snapping at the key press.
     */
    public static float sensitivity(float original) {
        if (!active()) {
            return original;
        }
        FathomModule module = module();
        if (module == null || !module.toggle("sensitivity", true)) {
            return original;
        }
        return ZoomMath.compensatedSensitivity(original, current,
                module.number("sensitivity.strength", 100) / 100.0);
    }

    /** Whether vanilla's smooth camera should run this frame. The player's own choice always stands. */
    public static boolean smoothCamera(boolean original) {
        if (original || !active()) {
            return original;
        }
        FathomModule module = module();
        return module != null && module.toggle("smooth", false);
    }

    /** Whether to skip drawing the hand this frame. */
    public static boolean hideHand() {
        if (!active()) {
            return false;
        }
        FathomModule module = module();
        return module != null && module.toggle("hide-hand", false);
    }

    /**
     * A mouse wheel movement, offered before it scrolls the hotbar.
     *
     * @return whether the zoom took it, in which case the hotbar must not move
     */
    public static boolean scroll(int wheel) {
        if (wheel == 0 || !wasWanted || Double.isNaN(level)) {
            return false;
        }
        FathomModule module = module();
        if (module == null || !module.toggle("scroll", true) || !wanted(module)) {
            return false;
        }
        level = ZoomMath.scroll(level, wheel > 0 ? 1 : -1, module.number("scroll.step", 1.25),
                module.number("min", 1.5), module.number("max", 20));
        return true;
    }

    /** A new world, player or connection: nothing zoomed carries over. */
    public static void sessionChanged() {
        progress = 0f;
        current = 1.0;
        wasWanted = false;
        FathomModule module = module();
        if (module == null || !"Remember".equals(module.choice("release", "Reset"))) {
            level = Double.NaN;
        }
    }
}
