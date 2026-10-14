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
        /** Backspace or Delete: the binding is now unbound. */
        CLEARED,
        BOUND
    }

    /** LWJGL's Backspace and Delete, which clear a binding rather than taking it. */
    public static final int KEY_BACKSPACE = 14;
    public static final int KEY_DELETE = 211;

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
     * Consume a keyboard press. Escape cancels and leaves the old key intact;
     * Backspace and Delete clear the binding, which is the only way to unbind a
     * key without binding another one in its place.
     */
    public Outcome keyboard(int keyCode, int escapeKey) {
        if (target == null) {
            return Outcome.NONE;
        }
        if (keyCode == escapeKey) {
            target = null;
            return Outcome.CANCELLED;
        }
        if (keyCode == KEY_BACKSPACE || keyCode == KEY_DELETE) {
            Keybind binding = target;
            target = null;
            binding.clear();
            return Outcome.CLEARED;
        }
        if (keyCode <= 0) {
            // LWJGL reports characters it cannot name (some IME and layout
            // keys) as KEY_NONE. Binding that would store "no key" while
            // claiming to have bound one; keep listening instead.
            return Outcome.NONE;
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
        if (!target.accepts(Keybind.mouse(button))) {
            // Keep listening: the click was not a choice this binding can take.
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
