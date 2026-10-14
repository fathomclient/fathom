package net.fathom.optimizations.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Map;

import net.fathom.optimizations.core.hud.HudLayout;
import net.fathom.optimizations.core.hud.HudRegistry;
import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.profile.FathomProfile;
import net.fathom.optimizations.core.profile.ProfileException;
import net.fathom.optimizations.core.profile.ProfileHost;
import net.fathom.optimizations.core.profile.ProfileStore;
import net.fathom.optimizations.core.profile.ProfileSwitch;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.SwitchBoard;

/**
 * A running client, as far as a profile is concerned, without a game.
 *
 * <p>The same {@link ProfileSwitch} and {@link ProfileStore} the game saves and
 * loads through: a test that writes a setting here and reads it back in a fresh
 * rig has been through exactly the path a restart takes — capture, JSON, disk,
 * parse, apply.
 */
final class ProfileRig implements ProfileHost {

    final ModuleRegistry features = ModuleRegistry.standard();
    final HudRegistry widgets = HudRegistry.standard();
    final OptimizationRegistry optimizations = OptimizationRegistry.standard();
    final SwitchBoard switches = SwitchBoard.defaults(optimizations);
    final Map<String, Boolean> baseline = ProfileSwitch.snapshot(switches, optimizations);
    final Keybind hub = new Keybind("hub", "Open Fathom Hub", 54).withoutPrimaryButtons();
    final ClientPrefs prefs = new ClientPrefs();
    private HudLayout layout = widgets.defaultLayout();

    @Override
    public ModuleRegistry features() {
        return features;
    }

    @Override
    public HudRegistry registry() {
        return widgets;
    }

    @Override
    public HudLayout layout() {
        return layout;
    }

    @Override
    public void replaceLayout(HudLayout next) {
        layout = next;
    }

    @Override
    public Keybind hubKey() {
        return hub;
    }

    @Override
    public ClientPrefs prefs() {
        return prefs;
    }

    void place(String widget, net.fathom.optimizations.core.hud.Placement placement) {
        layout.put(widget, placement);
    }

    /** This client, as a document called {@code id}. */
    FathomProfile capture(String id) {
        return ProfileSwitch.capture(new FathomProfile(id, id), this, switches, optimizations, baseline);
    }

    /** Make this client match {@code profile}. */
    void apply(FathomProfile profile) {
        try {
            ProfileSwitch.apply(profile, this, switches, optimizations, baseline, new ArrayList<String>());
        } catch (ProfileException failure) {
            throw new AssertionError("apply failed: " + failure.getMessage());
        }
    }

    /** Save this client as {@code id} in {@code collection}. */
    void saveAs(File collection, String id) {
        try {
            new ProfileStore(collection).save(capture(id));
        } catch (ProfileException failure) {
            throw new AssertionError("save failed: " + failure.getMessage());
        }
    }

    /** A fresh client that has loaded {@code id} from {@code collection}: a restart. */
    static ProfileRig restarted(File collection, String id) {
        ProfileRig fresh = new ProfileRig();
        try {
            fresh.apply(new ProfileStore(collection).load(id));
        } catch (ProfileException failure) {
            throw new AssertionError("load failed: " + failure.getMessage());
        }
        return fresh;
    }
}
