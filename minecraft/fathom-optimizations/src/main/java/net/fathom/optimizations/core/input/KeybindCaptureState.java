package net.fathom.optimizations.core.input;

import net.fathom.optimizations.core.module.Keybind;

/**
 * The single owner of a keybind-capture gesture.
 *
 * <p>Capture is deliberately not represented by a page boolean or a numeric
 * setting index. The target is a {@link Keybind}, and the only operations this
 * state can perform are bind, cancel and consume. That makes it impossible for
 * a captured LWJGL key code to be routed into an unrelated slider such as FOV.
 */
public final class KeybindCaptureState {

    public enum Outcome {
        NONE,
        CANCELLED,
        BOUND
    }

    private Keybind target;

    /** Start listening for this exact binding. A second click cancels it. */
    public void toggle(Keybind binding) {
        if (binding == null || binding == target) {
            cancel();
        } else {
            target = binding;
        }
    }

    /** Start listening, replacing any earlier capture. */
    public void begin(Keybind binding) {
        target = binding;
    }

    public boolean active() {
        return target != null;
    }

    public boolean capturing(Keybind binding) {
        return binding != null && binding == target;
    }

    public Keybind target() {
        return target;
    }

    /**
     * Consume a keyboard press. Escape cancels and leaves the old key intact.
     */
    public Outcome keyboard(int keyCode, int escapeKey) {
        if (target == null) {
            return Outcome.NONE;
        }
        if (keyCode == escapeKey) {
            target = null;
            return Outcome.CANCELLED;
        }
        Keybind binding = target;
        target = null;
        binding.set(keyCode);
        return Outcome.BOUND;
    }

    /** Consume a mouse press using Minecraft 1.8.9's mouse-key convention. */
    public Outcome mouse(int button) {
        if (target == null || button < 0) {
            return Outcome.NONE;
        }
        Keybind binding = target;
        target = null;
        binding.set(Keybind.mouse(button));
        return Outcome.BOUND;
    }

    public void cancel() {
        target = null;
    }
}
