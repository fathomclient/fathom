package net.fathom.optimizations.core.hud;

import java.util.Locale;

import net.fathom.optimizations.core.module.Keybind;

/**
 * What a key is called on a keystroke display.
 *
 * <p>Short and upper-case, because it is printed on a key the size of a
 * letter: {@code W}, {@code SPACE}, {@code SHIFT}, {@code LMB}. Derived from
 * the code the player actually bound, so somebody on AZERTY sees {@code Z}
 * where QWERTY shows {@code W} — the display lit the right key before, and
 * printed the wrong letter on it.
 */
public final class KeyLabels {

    private KeyLabels() {}

    public static String shortName(int code) {
        if (Keybind.isMouse(code)) {
            int button = code - Keybind.MOUSE_BASE;
            if (button == 0) {
                return "LMB";
            }
            if (button == 1) {
                return "RMB";
            }
            if (button == 2) {
                return "MMB";
            }
            return "M" + (button + 1);
        }
        switch (code) {
            case Keybind.NONE:
                return "-";
            case 42:
                return "SHIFT";
            case 54:
                return "RSHIFT";
            case 29:
                return "CTRL";
            case 157:
                return "RCTRL";
            case 56:
                return "ALT";
            case 184:
                return "RALT";
            case 57:
                return "SPACE";
            case 15:
                return "TAB";
            case 58:
                return "CAPS";
            case 200:
                return "UP";
            case 208:
                return "DOWN";
            case 203:
                return "LEFT";
            case 205:
                return "RIGHT";
            default:
                return Keybind.nameOf(code).toUpperCase(Locale.ROOT);
        }
    }
}
