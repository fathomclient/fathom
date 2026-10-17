package net.fathom.optimizations.core.profile;

import net.fathom.optimizations.core.hud.HudLayout;
import net.fathom.optimizations.core.hud.HudRegistry;
import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.module.ModuleRegistry;

/**
 * The parts of the running client a profile describes.
 *
 * <p>The game's HUD implements this; the tests implement it with plain objects.
 * It exists so that {@link ProfileSwitch} — the code that actually saves and
 * restores every module, setting, key and widget — runs under the same bare-JDK
 * tests as the rest of the core, rather than being the one part of persistence
 * that only a running game could exercise.
 */
public interface ProfileHost {

    /** Every module, first-party and registered. */
    ModuleRegistry features();

    /** Every widget that can be placed. */
    HudRegistry registry();

    /** Where the widgets are now. */
    HudLayout layout();

    /** Replace the arrangement wholesale. */
    void replaceLayout(HudLayout next);

    /** The key that opens the Hub. */
    Keybind hubKey();

    /** Preferences that belong to the client rather than to any module. */
    ClientPrefs prefs();
}
