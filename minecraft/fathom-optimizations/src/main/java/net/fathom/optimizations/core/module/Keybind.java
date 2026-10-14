package net.fathom.optimizations.core.module;

/**
 * A key a feature wants.
 *
 * <h2>Why Fathom keeps its own and does not register vanilla bindings</h2>
 *
 * <p>A vanilla {@code KeyBinding} would appear in the Controls screen, which is
 * better, and it needs an entry in {@code KeyBinding}'s category-order map. A
 * missing entry there makes the Controls screen throw — so getting it wrong
 * crashes a screen somebody opened for an unrelated reason, and it needs a
 * language file, which 1.8.9 loads from resource packs rather than mod jars
 * without Fabric API's resource loader.
 *
 * <p>So Fathom keeps its own small table, rebindable in the hub, and reads raw
 * key events. Nothing is consumed: vanilla's handling runs immediately
 * afterwards, so a Fathom key that collides with a vanilla one fires both, and
 * the hub says so rather than silently winning.
 *
 * <h2>Key codes are LWJGL's</h2>
 *
 * <p>Plain ints, matching {@code org.lwjgl.input.Keyboard}. Stored as ints too:
 * a name table would be a second place for a key to be spelled, and the two
 * would disagree the first time somebody plugged in a keyboard from another
 * country. The hub renders a name for the codes it knows and the number for the
 * ones it does not, which is honest and never wrong.
 */
public final class Keybind {

    /** No key. A binding the user has cleared. */
    public static final int NONE = 0;

    /**
     * Mouse buttons, as key codes.
     *
     * <p>Minecraft's own convention, not one invented here: 1.8.9 stores a
     * mouse binding as {@code button - 100}, so the left button is -100 and the
     * right is -99. Following it means a Fathom binding and a vanilla binding
     * are the same number for the same button, which is what makes it possible
     * to say later that a key is also used by Sneak.
     */
    public static final int MOUSE_BASE = -100;

    /** The code for a mouse button, counted from zero. */
    public static int mouse(int button) {
        return MOUSE_BASE + button;
    }

    /** Whether a code names a mouse button rather than a key. */
    public static boolean isMouse(int code) {
        // -100, -99, -98... are left, right, middle... in 1.8.9. The old
        // <= -100 check recognised only left click and sent every other mouse
        // binding to Keyboard.isKeyDown as a negative array index.
        return code >= MOUSE_BASE && code < NONE;
    }

    private final String id;
    private final String label;
    private final int fallback;
    private int key;
    private boolean primaryButtonsAllowed = true;

    public Keybind(String id, String label, int fallback) {
        if (id == null || id.isEmpty()) {
            throw new IllegalArgumentException("keybind id must not be empty");
        }
        this.id = id;
        this.label = label == null || label.isEmpty() ? id : label;
        this.fallback = fallback;
        this.key = fallback;
    }

    public String id() {
        return id;
    }

    public String label() {
        return label;
    }

    public int key() {
        return key;
    }

    /**
     * Refuse the left and right mouse buttons for this binding.
     *
     * <p>For the Hub key. Bound to right click, every block placed, every
     * bite of food and every bow drawn opened the Hub; bound to left click,
     * every swing did. Neither is a key anybody means to give up.
     */
    public Keybind withoutPrimaryButtons() {
        primaryButtonsAllowed = false;
        return this;
    }

    /** Whether this binding may be set to {@code code}. */
    public boolean accepts(int code) {
        if (!primaryButtonsAllowed && (code == MOUSE_BASE || code == MOUSE_BASE + 1)) {
            return false;
        }
        return true;
    }

    public void set(int next) {
        key = next;
    }

    public void clear() {
        key = NONE;
    }

    public void reset() {
        key = fallback;
    }

    public boolean changed() {
        return key != fallback;
    }

    /** Whether this is bound to a mouse button rather than a key. */
    public boolean isMouseButton() {
        return isMouse(key);
    }

    /** The mouse button this is bound to, or -1 when it is not. */
    public int mouseButton() {
        return isMouse(key) ? key - MOUSE_BASE : -1;
    }

    public boolean isBound() {
        return key != NONE;
    }

    /** Whether this is the key that just happened. */
    public boolean matches(int eventKey) {
        return isBound() && eventKey == key;
    }

    /**
     * A readable name for a key code.
     *
     * <p>Covers the keys anybody actually binds. Anything else renders as its
     * number, which is ugly and true; inventing a name for a code we do not
     * recognise would put a wrong label on a working binding.
     */
    public static String nameOf(int code) {
        if (isMouse(code)) {
            int button = code - MOUSE_BASE;
            if (button == 0) {
                return "Left Click";
            }
            if (button == 1) {
                return "Right Click";
            }
            if (button == 2) {
                return "Middle Click";
            }
            return "Mouse " + (button + 1);
        }
        switch (code) {
            case NONE:
                return "None";
            case 42:
                return "Left Shift";
            case 54:
                return "Right Shift";
            case 29:
                return "Left Ctrl";
            case 157:
                return "Right Ctrl";
            case 56:
                return "Left Alt";
            case 184:
                return "Right Alt";
            case 57:
                return "Space";
            case 15:
                return "Tab";
            case 28:
                return "Enter";
            case 1:
                return "Escape";
            case 14:
                return "Backspace";
            case 211:
                return "Delete";
            case 199:
                return "Home";
            case 207:
                return "End";
            case 210:
                return "Insert";
            case 51:
                return ",";
            case 52:
                return ".";
            case 53:
                return "/";
            case 43:
                return "\\";
            case 39:
                return ";";
            case 40:
                return "'";
            case 26:
                return "[";
            case 27:
                return "]";
            case 12:
                return "-";
            case 13:
                return "=";
            case 41:
                return "`";
            default:
                break;
        }
        // Letters: LWJGL orders them by QWERTY row, not alphabetically, so a
        // table is the only correct answer.
        final String[] rows = {"QWERTYUIOP", "ASDFGHJKL", "ZXCVBNM"};
        final int[] starts = {16, 30, 44};
        for (int row = 0; row < rows.length; row++) {
            int offset = code - starts[row];
            if (offset >= 0 && offset < rows[row].length()) {
                return String.valueOf(rows[row].charAt(offset));
            }
        }
        if (code >= 2 && code <= 10) {
            return String.valueOf(code - 1);
        }
        if (code == 11) {
            return "0";
        }
        if (code >= 59 && code <= 68) {
            return "F" + (code - 58);
        }
        if (code == 87) {
            return "F11";
        }
        if (code == 88) {
            return "F12";
        }
        return "Key " + code;
    }

    public String keyName() {
        return nameOf(key);
    }

    @Override
    public String toString() {
        return id + "=" + keyName();
    }
}
