package net.fathom.optimizations.fabric.visual;

import java.util.TimeZone;

import net.fathom.optimizations.core.chat.ChatLines;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.fabric.Runtime;

/**
 * The Chat module, as the chat hooks ask for it.
 *
 * <p>Everything here is a no-op with the module off: the hooks pass vanilla's
 * own values straight through, so a client that never switches Chat on draws
 * vanilla's chat, byte for byte, and the replay cache sees the same frames.
 */
public final class ChatStyle {

    private ChatStyle() {}

    /** The module when it is on, or null. */
    public static FathomModule active() {
        if (Runtime.features() == null) {
            return null;
        }
        FathomModule module = Runtime.features().find("chat");
        return module != null && module.enabled() && module.switchable() ? module : null;
    }

    /** The background band's colour, with its alpha scaled by the module's setting. */
    public static int background(int colour) {
        FathomModule module = active();
        if (module == null) {
            return colour;
        }
        int percent = (int) Math.round(module.number("background", 100));
        int alpha = (colour >>> 24) * Math.max(0, Math.min(100, percent)) / 100;
        return (alpha << 24) | (colour & 0xFFFFFF);
    }

    /** Whether chat text keeps vanilla's shadow. */
    public static boolean shadow() {
        FathomModule module = active();
        return module == null || module.toggle("shadow", true);
    }

    /** A message's timestamp, or null when timestamps are off. */
    public static String stamp(FathomModule module) {
        if (module == null || !module.toggle("timestamps", false)) {
            return null;
        }
        return ChatLines.stamp(System.currentTimeMillis(),
                "12 hour".equals(module.choice("timestamp.format", "24 hour")),
                module.toggle("timestamp.seconds", false), TimeZone.getDefault());
    }

    /** What the chat's look depends on, for the replay cache. */
    public static int signature() {
        FathomModule module = active();
        return module == null ? 0 : 0x40000000 | (Setting.revision() & 0x3FFFFFFF);
    }
}
