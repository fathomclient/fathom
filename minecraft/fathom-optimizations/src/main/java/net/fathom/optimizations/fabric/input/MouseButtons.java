package net.fathom.optimizations.fabric.input;

import org.lwjgl.input.Mouse;

/**
 * Mouse buttons as they were really pressed, with LWJGL 2's side-button bug corrected.
 *
 * <h2>The bug</h2>
 *
 * <p>On Windows, LWJGL 2.9 decides which side button went down from
 * {@code (wParam & 0xFF) == MK_XBUTTON1}. The low byte of that {@code wParam}
 * is every modifier held at the time: Shift, Ctrl, and the other mouse buttons.
 * So Mouse 4 pressed while sneaking, or while holding Ctrl or a click, is
 * reported as Mouse 5 — and the release, which LWJGL decodes correctly from
 * the high word, comes back as Mouse 4. A Mouse 4 hotkey never fires with
 * Shift held, and LWJGL's own state for Mouse 5 sticks down until Mouse 5 is
 * next released.
 *
 * <h2>The correction</h2>
 *
 * <p>LWJGL cannot be patched (Forge's LaunchWrapper never transforms
 * {@code org.lwjgl}), so the two side buttons are read from Windows directly:
 * {@code GetAsyncKeyState(VK_XBUTTON1/2)}, through the JNA that Minecraft
 * 1.8.9 already ships. {@link #isDown} answers from that for buttons 3 and 4;
 * {@link #eventButton} re-labels a press event the tick reads, using the
 * presses this class saw since the last one. Anywhere else, or if JNA cannot
 * load, both fall straight through to LWJGL.
 */
public final class MouseButtons {

    private static final int VK_XBUTTON1 = 0x05;
    private static final int VK_XBUTTON2 = 0x06;
    /** How long a press seen here waits for its event before it is forgotten. */
    private static final long PENDING_NANOS = 500_000_000L;

    /**
     * JNA's direct mapping: a registered native method, so a call boxes and
     * allocates nothing. It runs several times a frame.
     */
    static final class User32 {
        static native short GetAsyncKeyState(int key);
    }

    private static final boolean USER32 = load();

    private static boolean x1Was;
    private static boolean x2Was;
    private static long x1PressedAt;
    private static long x2PressedAt;

    private static long eventNanos = -1L;
    private static int eventRaw = -1;
    private static int eventFixed = -1;

    private MouseButtons() {}

    private static boolean load() {
        if (!System.getProperty("os.name", "").toLowerCase(java.util.Locale.ROOT).startsWith("windows")) {
            return false;
        }
        try {
            com.sun.jna.Native.register(User32.class, com.sun.jna.NativeLibrary.getInstance("user32"));
            return true;
        } catch (Throwable unavailable) {
            return false;
        }
    }

    /** Whether {@code button} (counted from zero) is held. */
    public static boolean isDown(int button) {
        if (!Mouse.isCreated() || button < 0 || button >= Mouse.getButtonCount()) {
            return false;
        }
        if (USER32 && (button == 3 || button == 4)) {
            poll();
            return button == 3 ? x1Was : x2Was;
        }
        return Mouse.isButtonDown(button);
    }

    /**
     * Records side-button presses as they happen. Called every frame, and by
     * the other methods, so a quick tap between two reads is still seen.
     */
    public static void poll() {
        if (!USER32) {
            return;
        }
        long now = System.nanoTime();
        short x1 = User32.GetAsyncKeyState(VK_XBUTTON1);
        short x2 = User32.GetAsyncKeyState(VK_XBUTTON2);
        boolean x1Down = (x1 & 0x8000) != 0;
        boolean x2Down = (x2 & 0x8000) != 0;
        // The low bit is "pressed since the last call": it catches a tap that
        // went down and up between two polls.
        if ((x1Down && !x1Was) || (x1 & 1) != 0) {
            x1PressedAt = now;
        }
        if ((x2Down && !x2Was) || (x2 & 1) != 0) {
            x2PressedAt = now;
        }
        x1Was = x1Down;
        x2Was = x2Down;
    }

    /**
     * {@code Mouse.getEventButton()}, with a mislabelled side-button press put right.
     * The same event always gets the same answer, however often it is asked.
     */
    public static int eventButton() {
        int raw = Mouse.getEventButton();
        if (!USER32 || (raw != 3 && raw != 4) || !Mouse.getEventButtonState()) {
            return raw;
        }
        long nanos = Mouse.getEventNanoseconds();
        if (nanos == eventNanos && raw == eventRaw) {
            return eventFixed;
        }
        poll();
        long now = System.nanoTime();
        boolean x1Pending = x1PressedAt != 0L && now - x1PressedAt < PENDING_NANOS;
        boolean x2Pending = x2PressedAt != 0L && now - x2PressedAt < PENDING_NANOS;
        int fixed = raw;
        if (raw == 3) {
            // LWJGL only says 3 when Mouse 4 was the only flag: always right.
            x1PressedAt = 0L;
        } else if (x1Pending && (!x2Pending || x1PressedAt <= x2PressedAt)) {
            fixed = 3;
            x1PressedAt = 0L;
        } else if (x2Pending) {
            x2PressedAt = 0L;
        } else if (x1Was && !x2Was) {
            fixed = 3;
        }
        eventNanos = nanos;
        eventRaw = raw;
        eventFixed = fixed;
        return fixed;
    }
}
