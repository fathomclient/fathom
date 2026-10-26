package net.fathom.optimizations.fabric.visual;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Nick Hider: your username, wherever the game draws text, shown as a name you
 * choose. For streams and screenshots.
 *
 * <p>Applied where the font draws and measures a string, so chat, the tab
 * list, nametags, the scoreboard and screens all follow, and widths are
 * measured on what is actually drawn. Nothing sent to the server changes:
 * commands and chat you type still carry your real name where they would.
 *
 * <p>The module's state is read once per frame into plain fields, because
 * {@link #apply} runs for every string the game draws. When it changes, the
 * caches that remember drawn text are released, so nothing keeps showing the
 * old name.
 */
public final class NickHider {

    private static boolean active;
    private static String real = "";
    private static String shown = "";

    private NickHider() {}

    /** Called once per frame. */
    public static void frame(MinecraftClient client) {
        FathomModule module = WorldEffects.active("nick-hider");
        boolean nextActive = false;
        String nextReal = "";
        String nextShown = "";
        if (module != null && client != null && client.getSession() != null) {
            nextReal = client.getSession().getUsername();
            nextShown = module.text("name", "You");
            nextActive = nextReal != null && nextReal.length() > 0 && nextShown != null
                    && !nextShown.equals(nextReal);
        }
        if (nextActive != active || !nextReal.equals(real) || !nextShown.equals(shown)) {
            active = nextActive;
            real = nextReal == null ? "" : nextReal;
            shown = nextShown == null ? "" : nextShown;
            textChanged();
        }
    }

    /** {@code text} with your name replaced, or {@code text} itself when there is nothing to replace. */
    public static String apply(String text) {
        if (!active || text == null || text.indexOf(real) < 0) {
            return text;
        }
        return text.replace(real, shown);
    }

    /** Releases everything that remembers drawn text, as a font reload does. */
    private static void textChanged() {
        net.fathom.optimizations.fabric.perf.SignTexts.fontChanged();
        net.fathom.optimizations.fabric.perf.TextLists.release();
        net.fathom.optimizations.fabric.perf.VanillaHudReplay.releaseAll();
        net.fathom.optimizations.fabric.perf.NametagRenderer.release();
        net.fathom.optimizations.fabric.perf.HudSteadyCache.release();
        net.fathom.optimizations.fabric.perf.HudLayerCache.invalidate();
    }
}
