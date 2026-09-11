package net.fathom.optimizations.fabric.hud;

import org.lwjgl.input.Keyboard;
import org.lwjgl.input.Mouse;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;

/**
 * Hold a key to narrow the field of view.
 *
 * <h2>Why the whole thing is here and not in the Mixin</h2>
 *
 * <p>The Mixin is three lines: take vanilla's field of view and divide it. This
 * class is the part with state — how far in the animation is, and what the mouse
 * sensitivity was before the zoom touched it — and state in a Mixin is state on
 * a class Minecraft owns, which is where accidental leaks between worlds come
 * from.
 *
 * <h2>Why the animation is driven from the render call</h2>
 *
 * <p>{@link #factor} is asked once per rendered frame, and it advances by the
 * real time since it was last asked. That makes it independent of both the tick
 * rate and the number of times the renderer happens to ask: two calls in one
 * frame each advance by half the elapsed time, so the total is the same. A
 * counter incremented per call would zoom at twice the speed on any frame that
 * asked twice.
 *
 * <h2>Sensitivity and the smooth camera</h2>
 *
 * <p>Both are vanilla options, and both are put back exactly as they were the
 * moment the key is released — including when the module is switched off mid
 * zoom, which is the case that leaves a client permanently at a tenth of its
 * mouse speed if nobody thought about it. The saved value is the one from before
 * the zoom, so a change made while zoomed is overwritten rather than compounded;
 * that is the lesser of the two wrongs, and the greater one is a sensitivity
 * that ratchets down every time you zoom.
 */
public final class Zoom {

    /** How long the ease takes, in seconds. Matches the interface's own timing. */
    private static final double DURATION = 0.18;

    private static long lastNanos;
    /** 0 is no zoom, 1 is fully zoomed. */
    private static double progress;

    private static boolean touchedOptions;
    private static float savedSensitivity;
    private static boolean savedSmoothCamera;

    private Zoom() {}

    /**
     * The divisor to apply to the field of view this frame.
     *
     * <p>1 means no zoom, and is the answer whenever the module is off, the key
     * is unbound, or the key is not held.
     */
    public static float factor() {
        MinecraftClient client = MinecraftClient.getInstance();
        FathomModule module = net.fathom.optimizations.fabric.Runtime.features() == null
                ? null
                : net.fathom.optimizations.fabric.Runtime.features().find("zoom");

        boolean wanted = module != null
                && module.enabled()
                && module.switchable()
                && client != null
                && client.player != null
                && client.currentScreen == null
                && held(module);

        advance(wanted, module);
        applyOptions(client, module, wanted);

        if (progress <= 0.0001) {
            return 1f;
        }
        double amount = module == null ? 4 : module.number("amount", 4);
        // Interpolate the divisor rather than the angle: a linear walk from 1 to
        // 4 spends most of its time near the wide end, which reads as a zoom
        // that hesitates and then rushes.
        return (float) (1.0 + (amount - 1.0) * progress);
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
            return Keyboard.isKeyDown(binding.key());
        }
        return false;
    }

    private static void advance(boolean wanted, FathomModule module) {
        long now = System.nanoTime();
        double elapsed = lastNanos == 0L ? 0.0 : (now - lastNanos) / 1_000_000_000.0;
        lastNanos = now;
        // A stall must not teleport the animation; the interface clamps its
        // delta for the same reason.
        if (elapsed > 0.10) {
            elapsed = 0.10;
        }

        boolean animate = module == null || module.toggle("animate", true);
        if (!animate) {
            progress = wanted ? 1.0 : 0.0;
            return;
        }
        double step = elapsed / DURATION;
        progress = wanted ? Math.min(1.0, progress + step) : Math.max(0.0, progress - step);
    }

    private static void applyOptions(MinecraftClient client, FathomModule module, boolean wanted) {
        if (client == null || client.options == null) {
            return;
        }
        GameOptions options = client.options;

        if (wanted && !touchedOptions) {
            savedSensitivity = options.sensitivity;
            savedSmoothCamera = options.smoothCameraEnabled;
            touchedOptions = true;
        }
        if (!touchedOptions) {
            return;
        }
        if (!wanted) {
            options.sensitivity = savedSensitivity;
            options.smoothCameraEnabled = savedSmoothCamera;
            touchedOptions = false;
            return;
        }
        if (module != null && module.toggle("sensitivity", true)) {
            double amount = module.number("amount", 4);
            // In proportion to how far in the zoom actually is, so the mouse
            // slows with the picture instead of snapping at the key press.
            double scale = 1.0 / (1.0 + (amount - 1.0) * progress);
            options.sensitivity = (float) (savedSensitivity * scale);
        } else {
            options.sensitivity = savedSensitivity;
        }
        options.smoothCameraEnabled =
                savedSmoothCamera || (module != null && module.toggle("smooth", false));
    }
}
