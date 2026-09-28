package net.fathom.optimizations.fabric.menu;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.module.Behaviour;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.ModuleLog;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Whether Fathom owns the title screen, and the check that makes sure it does.
 *
 * <h2>Why a second check exists</h2>
 *
 * <p>{@code MinecraftClientMenuMixin} swaps vanilla's title screen for ours as
 * it is handed to {@code setScreen}. That only works if Fathom's runtime is
 * already installed at that moment, and in a packaged game it is not always:
 * with OptiFine patched in through OptiFabric, the game can reach its first
 * title screen before Fabric has run Fathom's client initializer. The mixin
 * then sees no runtime, keeps vanilla's screen, and — because the title screen
 * is only set once — the player is left on it for the whole session, while the
 * launcher (correctly) reports Fathom as loaded.
 *
 * <p>So once a frame, after everything is installed, the current screen is
 * looked at again. A plain vanilla {@code TitleScreen} standing where ours
 * belongs is replaced. The same catches anything else that puts the title
 * screen up without going through {@code setScreen}.
 *
 * <p>The per-frame cost is one field read and one class comparison.
 */
public final class MenuTakeover {

    private static boolean said;

    private MenuTakeover() {}

    /**
     * Whether Fathom's menu replaces vanilla's right now.
     *
     * <p>False until the HUD and the feature registry are installed, or when
     * the {@code main-menu} module is switched off. Visual QA runs ask for the
     * menu explicitly and get it regardless of the last profile's toggle.
     */
    public static boolean owned() {
        if (Boolean.getBoolean("fathom.captureMenu")
                || !System.getProperty("fathom.captureUi", "").isEmpty()) {
            return true;
        }
        if (Runtime.hud() == null || Runtime.features() == null) {
            return false;
        }
        FathomModule module = Runtime.features().find("main-menu");
        return module != null
                && module.enabled()
                && module.switchable()
                && Behaviour.implemented(Behaviour.MAIN_MENU);
    }

    /** Whether the runtime the menu needs has been installed yet. */
    public static boolean ready() {
        return Runtime.hud() != null && Runtime.features() != null;
    }

    /** Once a frame: replace a vanilla title screen that got past the mixin. */
    public static void frame(MinecraftClient client) {
        if (client == null || client.currentScreen == null
                || !FathomMenuScreen.shouldReplace(client.currentScreen) || !owned()) {
            return;
        }
        if (!said) {
            said = true;
            ModuleLog.say("main menu: vanilla's title screen was up before Fathom finished loading;"
                    + " replaced it with Fathom's.");
        }
        client.setScreen(new FathomMenuScreen());
    }
}
