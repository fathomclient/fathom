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
    private static boolean explained;
    /** The module has been seen on this session, so off now is the player's doing. */
    private static boolean seenOn;

    /**
     * Say, once, why a title screen is standing where Fathom's should be.
     *
     * <p>This case used to be silent, which is how a packaged game could sit on
     * vanilla's menu with nothing in the log to say why. Only ever about a
     * title screen: every other screen is supposed to stay.
     */
    private static void explainKept(net.minecraft.client.gui.screen.Screen screen) {
        if (explained || !(screen instanceof net.minecraft.client.gui.screen.TitleScreen) || !ready()) {
            // Not ready yet: it will be replaced, or explained, once it is.
            return;
        }
        String why;
        if (!owned()) {
            why = whyNotOwned();
        } else if (client() != null && client().isDemo()) {
            why = "this is a demo account";
        } else {
            why = "it is another mod's title screen (" + screen.getClass().getName() + ")";
        }
        explained = true;
        ModuleLog.say("main menu: keeping " + screen.getClass().getName() + " because " + why + ".");
    }

    /** Which of {@link #owned}'s conditions failed, exactly. */
    private static String whyNotOwned() {
        if (Runtime.hud() == null || Runtime.features() == null) {
            return "Fathom's runtime is not installed yet";
        }
        FathomModule module = Runtime.features().find("main-menu");
        if (module == null) {
            return "no main-menu module is registered";
        }
        if (!module.switchable()) {
            return "the main-menu module cannot be switched on in this build";
        }
        if (!module.enabled()) {
            return seenOn
                    ? "the Fathom main menu was switched off in the Hub"
                    : "the Fathom main menu is switched off in the profile " + Runtime.hud().sessionName();
        }
        return "the main-menu behaviour is not implemented";
    }

    private static MinecraftClient client() {
        return MinecraftClient.getInstance();
    }

    private MenuTakeover() {}

    /**
     * Whether Fathom's menu replaces vanilla's right now.
     *
     * <p>Only the player turns Fathom's menu off, with the module's switch, and
     * that switch is stored in the live profile. So vanilla's screen is kept
     * when the profile says the menu is off, or when it was switched off during
     * this session -- and at no other time. A packaged game started from the
     * launcher reached its first title screen with the module reading off and
     * nothing in the profile saying so, and sat on vanilla's menu: a state
     * nobody chose is not a reason to give the game's front door away.
     *
     * <p>Visual QA runs ask for the menu explicitly and always get it.
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
        if (module == null || !module.switchable() || !Behaviour.implemented(Behaviour.MAIN_MENU)) {
            return false;
        }
        if (module.enabled()) {
            seenOn = true;
            return true;
        }
        // Off. Honour it if the player did it: in the Hub this session, or in
        // the profile they are playing.
        return !seenOn && !profileSaysOff();
    }

    /** Whether the live profile explicitly stores the main menu as off. */
    private static boolean profileSaysOff() {
        net.fathom.optimizations.fabric.profile.ProfileRuntime profiles = Runtime.hud().profiles();
        net.fathom.optimizations.core.profile.FathomProfile live = profiles == null ? null : profiles.live();
        if (live == null) {
            return false;
        }
        net.fathom.optimizations.core.profile.FathomProfile.ModuleState state = live.modules().get("main-menu");
        return state != null && Boolean.FALSE.equals(state.enabled);
    }

    /** Whether the runtime the menu needs has been installed yet. */
    public static boolean ready() {
        return Runtime.hud() != null && Runtime.features() != null;
    }

    /** Once a frame: replace a vanilla title screen that got past the mixin. */
    public static void frame(MinecraftClient client) {
        // Every frame, not only while a title screen is up, so a switch turned
        // off in the Hub during a game is known to be the player's by the time
        // they leave the world.
        if (!seenOn && ready()) {
            FathomModule module = Runtime.features().find("main-menu");
            seenOn = module != null && module.enabled();
        }
        if (client == null || client.currentScreen == null) {
            return;
        }
        if (!FathomMenuScreen.shouldReplace(client.currentScreen) || !owned()) {
            explainKept(client.currentScreen);
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
