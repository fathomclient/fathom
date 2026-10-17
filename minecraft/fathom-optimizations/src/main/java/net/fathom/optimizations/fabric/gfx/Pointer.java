package net.fathom.optimizations.fabric.gfx;

import org.lwjgl.input.Mouse;

import net.minecraft.client.MinecraftClient;

/**
 * The mouse, read once per frame instead of once per tick.
 *
 * <h2>The latency this removes</h2>
 *
 * <p>Minecraft delivers screen input from {@code MinecraftClient.tick} —
 * twenty times a second. Everything a screen knows about the mouse is therefore
 * up to 50 ms old, hover states lag the cursor by a visible amount, and a click
 * is quantised to a tick boundary. That is precisely the "delayed hover,
 * delayed button feedback" this milestone exists to fix, and it cannot be fixed
 * inside the vanilla callbacks because the vanilla callbacks are the problem.
 *
 * <p>LWJGL, meanwhile, updates the mouse's position and button state on every
 * {@code Display.update} — once per rendered frame. So Fathom's screens ignore
 * the callbacks and read the device here, at the top of their own render, and
 * hover, drag and click all run at frame rate.
 *
 * <h2>Edges, not levels</h2>
 *
 * <p>A press is a frame where the button is down and was up. That definition
 * has a property worth having: a button that was already held when a screen
 * opened never produces a press, because there was no edge. The first hub had a
 * bug that was exactly this — a click meant for the game arriving in the menu
 * it had just opened — and it needed a timing guard to work around. This does
 * not need the guard.
 */
public final class Pointer {

    /** Enough for gaming mice while keeping the hot path allocation-free. */
    private static final int BUTTONS = 16;
    /** LWJGL 2 reports conventional wheel detents in multiples of 120. */
    private static final int WHEEL_DELTA = 120;

    /** {@code -Dfathom.qaPointer=x,y} in UI units; absent in ordinary runs. */
    private static final float[] QA_POINTER = qaPointer(System.getProperty("fathom.qaPointer", ""));

    private static float[] qaPointer(String value) {
        String[] parts = value.split(",");
        if (parts.length != 2) {
            return null;
        }
        try {
            return new float[] {Float.parseFloat(parts[0].trim()), Float.parseFloat(parts[1].trim())};
        } catch (NumberFormatException ignored) {
            return null;
        }
    }

    /*
     * Scripted input for the UI tour's measurements (development only): a
     * pointer position in logical units, NaN for the real mouse, and wheel
     * notches to deliver on the next poll.
     */
    private static float scriptedX = Float.NaN;
    private static float scriptedY = Float.NaN;
    private static int scriptedWheel;

    /** Drive the pointer from a script; NaN hands it back to the mouse. */
    public static void script(float logicalX, float logicalY) {
        scriptedX = logicalX;
        scriptedY = logicalY;
    }

    /**
     * Development self-tests only: hold the primary button down (true), let it
     * go (false), or hand it back to the real mouse (null). A scripted press
     * goes through exactly the edge detection a real one does.
     */
    public static void scriptPrimary(Boolean held) {
        scriptedPrimary = held;
    }

    private static Boolean scriptedPrimary;

    /** Wheel notches for the next poll, positive away from the user. */
    public static void scriptWheel(int notches) {
        scriptedWheel += notches;
    }

    private float x;
    private float y;
    private float previousX;
    private float previousY;
    private final boolean[] down = new boolean[BUTTONS];
    private final boolean[] pressed = new boolean[BUTTONS];
    private final boolean[] released = new boolean[BUTTONS];
    private int wheel;
    private boolean started;
    private long pointerEventNs;
    private final boolean[] swallowed = new boolean[BUTTONS];

    /**
     * Read the device.
     *
     * <p>Call once, at the top of a frame, before anything asks a question.
     */
    public void poll(MinecraftClient client, net.fathom.optimizations.core.ui.render.Renderer renderer) {
        if (!Mouse.isCreated()) {
            return;
        }
        previousX = x;
        previousY = y;
        float scale = renderer.scale();
        // LWJGL's origin is the bottom left of the window; ours is the top left.
        x = Mouse.getX() / scale;
        y = (client.height - Mouse.getY() - 1) / scale;
        if (QA_POINTER != null) {
            // Visual-QA only: a still of a hover state needs a pointer that is
            // not wherever the desktop cursor happened to be.
            x = QA_POINTER[0];
            y = QA_POINTER[1];
        }
        if (scriptedX == scriptedX) {
            x = scriptedX;
            y = scriptedY;
        }
        if (started && (x != previousX || y != previousY)) {
            pointerEventNs = System.nanoTime();
        }

        int available = Math.min(BUTTONS, Mouse.getButtonCount());
        for (int button = 0; button < BUTTONS; button++) {
            if (button >= available) {
                pressed[button] = false;
                released[button] = down[button];
                down[button] = false;
                continue;
            }
            boolean now = button == 0 && scriptedPrimary != null
                    ? scriptedPrimary.booleanValue() : Mouse.isButtonDown(button);
            boolean before = down[button];
            // On the very first frame every button counts as "was already down",
            // so nothing that was held when the screen opened fires.
            pressed[button] = started && now && !before;
            released[button] = started && !now && before;
            down[button] = now;
            // A press somebody else took is a press this frame never saw, and
            // neither is the release that ends it -- otherwise a control under
            // the cursor would receive a release with no press and fire.
            if (swallowed[button]) {
                pressed[button] = false;
                released[button] = false;
                if (!now) {
                    swallowed[button] = false;
                }
            }
        }
        wheel = Mouse.getDWheel();
        if (net.fathom.optimizations.fabric.bench.PerfBench.ACTIVE) {
            // The scripted benchmark's scrolling phase, and nothing otherwise.
            wheel += net.fathom.optimizations.fabric.bench.PerfBench.benchWheelNotches() * WHEEL_DELTA;
        }
        if (scriptedWheel != 0) {
            wheel += scriptedWheel * WHEEL_DELTA;
            scriptedWheel = 0;
        }
        started = true;
    }

    /**
     * Take a press away from everything else this frame.
     *
     * <p>For the one case that needs it: a keybind control listening for a
     * mouse button wants the button, not a click. The release that follows is
     * swallowed too, so no control sees half a click.
     */
    public void consume(int button) {
        if (button < 0 || button >= BUTTONS) {
            return;
        }
        pressed[button] = false;
        released[button] = false;
        swallowed[button] = true;
    }

    /** Forget everything, so the next poll establishes a fresh baseline. */
    public void reset() {
        started = false;
        for (int button = 0; button < BUTTONS; button++) {
            down[button] = false;
            pressed[button] = false;
            released[button] = false;
            swallowed[button] = false;
        }
        wheel = 0;
        pointerEventNs = 0L;
    }

    /** Where the cursor is, in logical units. */
    public float x() {
        return x;
    }

    public float y() {
        return y;
    }

    /** How far it moved since the last frame. */
    public float deltaX() {
        return x - previousX;
    }

    public float deltaY() {
        return y - previousY;
    }

    /** Monotonic timestamp of the latest position change observed this frame. */
    public long pointerEventNs() {
        return pointerEventNs;
    }

    /** Whether a button went down this frame. */
    public boolean pressed(int button) {
        return button >= 0 && button < BUTTONS && pressed[button];
    }

    /** Whether it came up this frame. */
    public boolean released(int button) {
        return button >= 0 && button < BUTTONS && released[button];
    }

    /** Whether it is down now. */
    public boolean down(int button) {
        return button >= 0 && button < BUTTONS && down[button];
    }

    /** Any button pressed this frame. */
    public boolean pressedAny() {
        for (int button = 0; button < BUTTONS; button++) {
            if (pressed[button]) {
                return true;
            }
        }
        return false;
    }

    public int buttonCount() {
        return BUTTONS;
    }

    /**
     * Wheel movement since the last frame, in notches.
     *
     * <p>LWJGL's polled accumulator, which is a different thing from the event
     * queue the game drains — so reading it here takes nothing away from
     * anything else.
     */
    public int wheelNotches() {
        if (wheel == 0) {
            return 0;
        }
        int notches = wheel / WHEEL_DELTA;
        // Preserve high-resolution wheels that report a sub-detent delta, but
        // do not collapse a rapid multi-notch scroll into a single step.
        return notches == 0 ? (wheel > 0 ? 1 : -1) : notches;
    }

    /** Whether the cursor is inside a rectangle, in logical units. */
    public boolean over(float left, float top, float width, float height) {
        return x >= left && x < left + width && y >= top && y < top + height;
    }
}
