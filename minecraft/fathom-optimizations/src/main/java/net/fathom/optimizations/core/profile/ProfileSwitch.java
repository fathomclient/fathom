package net.fathom.optimizations.fabric.profile;

import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import net.fathom.optimizations.core.hud.Anchor;
import net.fathom.optimizations.core.hud.HudLayout;
import net.fathom.optimizations.core.hud.HudRegistry;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.hud.Widget;
import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.core.profile.FathomProfile;
import net.fathom.optimizations.core.profile.ProfileException;
import net.fathom.optimizations.core.registry.Optimization;
import net.fathom.optimizations.core.registry.OptimizationId;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.SwitchBoard;
import net.fathom.optimizations.fabric.hud.FathomHud;

/**
 * A profile document, applied to the running client — and the running client,
 * captured back into one.
 *
 * <h2>Apply is a replacement, not a merge</h2>
 *
 * <p>Anything the target does not mention goes back to its default. A module the
 * target does not name returns to whatever its author set; a setting it does not
 * list is reset; a widget it does not place returns to the registry's position.
 * Merging would make the result of a switch depend on which profile you came
 * from, and "Bedwars" would look different depending on whether you arrived from
 * Survival or from Default.
 *
 * <h2>The order is the specification's</h2>
 *
 * <p>Departing modules are switched off before any setting changes, so a module
 * that is leaving is switched off as itself rather than half-reconfigured first.
 * Arriving modules are switched on after their settings are in place, so they
 * start with the right configuration instead of drawing one frame with the old
 * one.
 *
 * <h2>Where the transaction lives</h2>
 *
 * <p>Here is only the mechanism. {@link ProfileRuntime#switchTo} captures the
 * current state before calling {@link #apply}, and if {@code apply} throws —
 * a document that fails validation, or anything unexpected — applies that
 * capture back. The live profile and the stored pointer only move after a
 * clean apply.
 *
 * <h2>What is skipped rather than failed</h2>
 *
 * <p>A module, widget or optimization this build does not have, and a stored
 * setting or key value this build cannot use (which resets that one value). A profile made by
 * a newer Fathom must still switch on an older one; failing it outright would
 * make every profile brittle against the next release. Skips are reported, not
 * hidden.
 */
public final class ProfileSwitch {

    private ProfileSwitch() {}

    // -----------------------------------------------------------------------
    // Apply
    // -----------------------------------------------------------------------

    /**
     * Make the running client match {@code target}.
     *
     * @param baseline each optimization's state before any profile was applied —
     *     what the launcher and the registry decided. An optimization the target
     *     does not mention returns to this.
     * @param skipped receives one line per id this build does not have
     * @throws ProfileException when a value cannot be applied; the caller rolls back
     */
    public static void apply(FathomProfile target, FathomHud hud, SwitchBoard switches,
            OptimizationRegistry optimizations, Map<String, Boolean> baseline, List<String> skipped)
            throws ProfileException {
        // 1. validate the document before touching anything.
        String problem = target.problem();
        if (problem != null) {
            throw new ProfileException(problem);
        }
        ModuleRegistry features = hud.features();
        for (String id : target.modules().keySet()) {
            if (features.find(id) == null) {
                skipped.add("module " + id);
            }
        }

        // 2-3. the diff is implicit in the comparisons; departing modules go first.
        for (FathomModule module : features.all()) {
            if (module.switchable() && module.enabled() && !wanted(module, target)) {
                module.setEnabled(false);
            }
        }

        // 4. settings, favourites.
        for (FathomModule module : features.all()) {
            FathomProfile.ModuleState state = target.modules().get(module.id());
            module.setFavourite(state != null && state.favourite);
            for (Setting setting : module.settings()) {
                String stored = state == null ? null : state.settings.get(setting.id());
                if (stored == null) {
                    setting.reset();
                } else if (!setting.parse(stored)) {
                    // A value this build cannot use -- an option an older or
                    // newer build spelled differently, a hand-edited number --
                    // costs that one setting, not the whole profile. Failing
                    // the switch here used to leave a client on the wrong
                    // profile at startup over a single renamed dropdown entry.
                    setting.reset();
                    skipped.add("setting " + module.id() + "." + setting.id() + "=\"" + stored + "\"");
                }
            }
            // The whole module has been applied; let it settle anything it
            // derives from several values at once.
            module.settingChanged(null);
        }

        // 5. arriving modules, now that their settings are right.
        for (FathomModule module : features.all()) {
            if (module.switchable() && !module.enabled() && wanted(module, target)) {
                module.setEnabled(true);
            }
        }

        // 6. the HUD layout, replaced whole.
        HudRegistry widgets = hud.registry();
        HudLayout layout = widgets.defaultLayout();
        layout.setName(target.name());
        for (Map.Entry<String, FathomProfile.HudEntry> entry : target.hud().entrySet()) {
            Widget widget = widgets.find(entry.getKey());
            if (widget == null) {
                skipped.add("widget " + entry.getKey());
                continue;
            }
            FathomProfile.HudEntry stored = entry.getValue();
            Anchor anchor = anchor(stored.anchor, widget.defaultPlacement().anchor(), entry.getKey(), skipped);
            layout.put(entry.getKey(), new Placement(anchor, stored.x, stored.y, stored.scale, stored.opacity));
        }
        hud.replaceLayout(layout);

        // 7. keybinds, the hub key and the client's own preferences.
        for (FathomModule module : features.all()) {
            FathomProfile.ModuleState state = target.modules().get(module.id());
            for (Keybind keybind : module.keybinds()) {
                String stored = state == null ? null : state.keys.get(keybind.id());
                if (stored == null) {
                    keybind.reset();
                } else {
                    Integer code = keyCode(stored);
                    if (code == null) {
                        keybind.reset();
                        skipped.add("key " + module.id() + "." + keybind.id() + "=\"" + stored + "\"");
                    } else {
                        keybind.set(code.intValue());
                    }
                }
            }
        }
        Keybind hubKey = hud.hubKey();
        if (target.client().hubKey == null) {
            hubKey.reset();
        } else {
            hubKey.set(target.client().hubKey.intValue());
        }
        ClientPrefs prefs = hud.prefs();
        prefs.setScaleLabel(target.client().uiScale == null ? ClientPrefs.SCALE_CHOICES[0] : target.client().uiScale);
        prefs.setHighQualityUi(target.client().highQualityUi == null || target.client().highQualityUi.booleanValue());
        prefs.setHudFontLabel(target.client().hudFont == null ? ClientPrefs.HUD_FONT_CHOICES[0] : target.client().hudFont);
        prefs.setRshiftBackgroundLabel(target.client().rshiftBackground == null
                ? ClientPrefs.RSHIFT_BACKGROUND_CHOICES[1] : target.client().rshiftBackground);
        prefs.setRshiftBlurLabel(target.client().rshiftBlur == null
                ? ClientPrefs.RSHIFT_BLUR_CHOICES[1] : target.client().rshiftBlur);
        prefs.setRshiftDimLabel(target.client().rshiftDim == null
                ? ClientPrefs.RSHIFT_DIM_CHOICES[1] : target.client().rshiftDim);

        // 8. live-safe performance switches.
        Set<String> owned = ownedByModules(features);
        Set<String> known = new HashSet<String>();
        for (Optimization optimization : optimizations.all()) {
            String id = optimization.id().value();
            known.add(id);
            if (owned.contains(id)) {
                continue;
            }
            Boolean wantedOn = target.performance().switches.get(id);
            if (wantedOn == null) {
                wantedOn = baseline.get(id);
            }
            if (wantedOn != null && switches.isEnabled(optimization.id()) != wantedOn.booleanValue()) {
                switches.setByUser(optimization.id(), wantedOn.booleanValue());
            }
        }
        for (String id : target.performance().switches.keySet()) {
            if (!known.contains(id)) {
                skipped.add("optimization " + id);
            }
        }
        // A module that owns an optimization drives it, exactly as at startup.
        for (FathomModule module : features.all()) {
            for (String id : module.optimizations()) {
                if (known.contains(id)) {
                    switches.setByUser(OptimizationId.of(id), module.enabled());
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // Capture
    // -----------------------------------------------------------------------

    /**
     * The running client, as a profile document with {@code identity}'s id and name.
     *
     * <p>Only what differs from defaults is recorded, which is the document
     * format's own rule: a module at its defaults writes nothing, a widget where
     * the registry put it writes nothing, a switch at its launcher baseline writes
     * nothing.
     */
    public static FathomProfile capture(FathomProfile identity, FathomHud hud, SwitchBoard switches,
            OptimizationRegistry optimizations, Map<String, Boolean> baseline) {
        FathomProfile out = identity.emptied();

        for (FathomModule module : hud.features().all()) {
            FathomProfile.ModuleState state = new FathomProfile.ModuleState();
            if (module.switchable() && module.enabled() != module.defaultEnabled()) {
                state.enabled = Boolean.valueOf(module.enabled());
            }
            state.favourite = module.favourite();
            for (Setting setting : module.settings()) {
                if (setting.changed()) {
                    state.settings.put(setting.id(), setting.serialise());
                }
            }
            for (Keybind keybind : module.keybinds()) {
                if (keybind.changed()) {
                    state.keys.put(keybind.id(), String.valueOf(keybind.key()));
                }
            }
            if (!state.isEmpty()) {
                out.modules().put(module.id(), state);
            }
        }

        HudRegistry widgets = hud.registry();
        for (Map.Entry<String, Placement> entry : hud.layout().all().entrySet()) {
            Widget widget = widgets.find(entry.getKey());
            Placement placement = entry.getValue();
            if (widget != null && placement.equals(widget.defaultPlacement())) {
                continue;
            }
            FathomProfile.HudEntry stored = new FathomProfile.HudEntry();
            stored.anchor = placement.anchor().name().toLowerCase(Locale.ROOT).replace('_', '-');
            stored.x = placement.offsetX();
            stored.y = placement.offsetY();
            stored.scale = placement.scale();
            stored.opacity = placement.opacity();
            out.hud().put(entry.getKey(), stored);
        }

        if (hud.hubKey().changed()) {
            out.client().hubKey = Integer.valueOf(hud.hubKey().key());
        }
        ClientPrefs prefs = hud.prefs();
        if (!prefs.scaleIsAutomatic()) {
            out.client().uiScale = prefs.scaleLabel();
        }
        if (!prefs.highQualityUi()) {
            out.client().highQualityUi = Boolean.FALSE;
        }
        if (!prefs.hudFontIsFathom()) {
            out.client().hudFont = prefs.hudFontLabel();
        }
        if (prefs.rshiftBackgroundChangedFromDefault()) {
            out.client().rshiftBackground = prefs.rshiftBackgroundLabel();
        }
        if (prefs.rshiftBlurChangedFromDefault()) {
            out.client().rshiftBlur = prefs.rshiftBlurLabel();
        }
        if (prefs.rshiftDimChangedFromDefault()) {
            out.client().rshiftDim = prefs.rshiftDimLabel();
        }

        Set<String> owned = ownedByModules(hud.features());
        for (Optimization optimization : optimizations.all()) {
            String id = optimization.id().value();
            // A switch a module drives is the module's state, already recorded
            // above. Recording it here as well would freeze it: switching the
            // module off later would leave its optimization on from the profile.
            if (owned.contains(id)) {
                continue;
            }
            boolean now = switches.isEnabled(optimization.id());
            Boolean base = baseline.get(id);
            if (base == null || base.booleanValue() != now) {
                out.performance().switches.put(id, Boolean.valueOf(now));
            }
        }
        return out;
    }

    /** Every optimization's state right now, for a baseline. */
    public static Map<String, Boolean> snapshot(SwitchBoard switches, OptimizationRegistry optimizations) {
        Map<String, Boolean> out = new java.util.HashMap<String, Boolean>();
        for (Optimization optimization : optimizations.all()) {
            out.put(optimization.id().value(), Boolean.valueOf(switches.isEnabled(optimization.id())));
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean wanted(FathomModule module, FathomProfile target) {
        FathomProfile.ModuleState state = target.modules().get(module.id());
        return state == null || state.enabled == null ? module.defaultEnabled() : state.enabled.booleanValue();
    }

    private static Set<String> ownedByModules(ModuleRegistry features) {
        Set<String> out = new HashSet<String>();
        for (FathomModule module : features.all()) {
            out.addAll(module.optimizations());
        }
        return out;
    }

    private static Anchor anchor(String stored, Anchor fallback, String widget, List<String> skipped) {
        if (stored == null) {
            return fallback;
        }
        try {
            return Anchor.valueOf(stored.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            skipped.add("anchor " + stored + " for " + widget);
            return fallback;
        }
    }

    private static Integer keyCode(String stored) {
        try {
            return Integer.valueOf(Integer.parseInt(stored.trim()));
        } catch (NumberFormatException notANumber) {
            return null;
        }
    }
}
