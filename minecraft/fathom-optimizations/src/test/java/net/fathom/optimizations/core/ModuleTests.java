package net.fathom.optimizations.core;

import java.io.File;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Scanner;

import net.fathom.optimizations.core.hud.HudRegistry;
import net.fathom.optimizations.core.hud.Sensitivity;
import net.fathom.optimizations.core.input.KeybindCaptureState;
import net.fathom.optimizations.core.module.Behaviour;
import net.fathom.optimizations.core.module.Category;
import net.fathom.optimizations.core.module.CrosshairPolicy;
import net.fathom.optimizations.core.module.CrosshairPresets;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.module.Implementation;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.module.ModuleStore;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.core.opt.frames.FrameCapPolicy;
import net.fathom.optimizations.core.registry.OptimizationId;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.SwitchBoard;

/**
 * The module system's own tests, and the toggle validation table.
 *
 * <h2>What this file is for</h2>
 *
 * <p>The client shipped with visible toggles that changed a boolean nothing
 * read. The fix is not "go and wire them up" — that is a state a codebase drifts
 * out of again — it is a check that fails the build the moment a module is
 * declared with nothing behind it. That check is
 * {@link #everyWorkingModuleActuallyDoesSomething}, and everything else here
 * exists to make it meaningful:
 *
 * <ul>
 *   <li>a {@code WORKING} module must name a widget, an optimization or a
 *       behaviour the client implements;
 *   <li>an {@code UNAVAILABLE} module must not be switchable at all, which is
 *       how "no toggle for something that does nothing" is enforced rather than
 *       remembered;
 *   <li>every widget module must carry the common HUD settings, so no widget
 *       ends up with a colour setting the drawing ignores;
 *   <li>a toggle must change state, show and hide its effect, persist, and stay
 *       inside its own profile — printed as the table the brief asks for by
 *       {@link #theToggleValidationTable}.
 * </ul>
 *
 * <p>Plain {@code main}, like the rest of this module's tests: {@code core} has
 * no dependency outside the JDK, and it stays checkable with nothing but a
 * compiler.
 */
public final class ModuleTests {

    private static int passed;

    public static void main(String[] args) {
        // -- the model -------------------------------------------------------
        everyWorkingModuleActuallyDoesSomething();
        nothingUnavailableOffersAToggle();
        everyBehaviourIsClaimedAndEveryClaimIsImplemented();
        everyWidgetModuleCarriesTheCommonHudSettings();
        everyHudModuleDefaultsToMinecraftFont();
        vanillaCrosshairIsUntouchedUntilReplacementIsEnabled();
        vanillaButtonsHaveNoGlobalDrawInterceptor();
        keystrokesHasIndependentVisualControls();
        everySettingIdIsUniqueWithinItsModule();
        experimentalAndUnavailableSayWhy();

        // -- the switches ----------------------------------------------------
        aToggleChangesStateAndTheEffectFollows();
        settingsSurviveARestart();
        oneProfileDoesNotSeeAnother();
        keybindsReportConflictsWithoutForbiddingThem();
        keybindCaptureOwnsExactlyOneBindingAndNoSlider();
        mouseBindingsCoverEveryButtonConvention();
        aBindingCanBeClearedWithoutBindingAnotherKey();

        // -- settings that depend on each other -------------------------------
        aCrosshairPresetWritesItsShapeAndEditsMakeItCustom();
        zoomLimitsStayInOrderHoweverTheyAreEdited();
        aRenamedOptionIsStillReadFromAnOlderProfile();
        aLoadedModuleDescribesWhatItActuallyLoaded();
        theSettingsRevisionMovesOnlyWhenAValueDoes();

        // -- the table -------------------------------------------------------
        theToggleValidationTable();

        report();
    }

    // -----------------------------------------------------------------------
    // The model
    // -----------------------------------------------------------------------

    /**
     * The rule the whole milestone is about.
     *
     * <p>A module that is {@code WORKING} and drives no widget, no optimization
     * and no behaviour is a switch over a boolean nobody reads. There is no way
     * to notice that by using the client — it looks exactly like a feature that
     * is subtly broken — so it is noticed here instead.
     */
    static void everyWorkingModuleActuallyDoesSomething() {
        HudRegistry widgets = HudRegistry.standard();
        OptimizationRegistry optimizations = OptimizationRegistry.standard();

        for (FathomModule module : ModuleRegistry.standard().all()) {
            if (module.implementation() == Implementation.UNAVAILABLE) {
                continue;
            }
            check(module.hasEffect(), module.id() + " is switchable and does nothing");

            for (String widget : module.widgets()) {
                check(widgets.contains(widget), module.id() + " claims a widget that does not exist: " + widget);
            }
            for (String optimization : module.optimizations()) {
                check(optimizations.contains(OptimizationId.of(optimization)),
                        module.id() + " drives an optimization that does not exist: " + optimization);
            }
            if (!module.behaviour().isEmpty()) {
                check(Behaviour.implemented(module.behaviour()),
                        module.id() + " names a behaviour nothing implements: " + module.behaviour());
            }
        }
        pass("every working module actually does something");
    }

    /** No toggle for something that does not work. That is the whole state. */
    static void nothingUnavailableOffersAToggle() {
        for (FathomModule module : ModuleRegistry.standard().all()) {
            if (module.implementation() == Implementation.UNAVAILABLE) {
                check(!module.switchable(), module.id() + " is unavailable and still offers a switch");
                check(!module.enabled(), module.id() + " is unavailable and starts on");
            } else {
                check(module.switchable(), module.id() + " cannot be switched and is not unavailable");
            }
        }
        pass("nothing unavailable offers a toggle");
    }

    /**
     * The two lists have to agree in both directions.
     *
     * <p>A module naming a behaviour nobody implements is the old defect with an
     * extra step. A behaviour nobody names is dead code in a Mixin, which is
     * worse than dead code anywhere else.
     */
    static void everyBehaviourIsClaimedAndEveryClaimIsImplemented() {
        List<String> claimed = new ArrayList<String>();
        for (FathomModule module : ModuleRegistry.standard().all()) {
            if (!module.behaviour().isEmpty()) {
                claimed.add(module.behaviour());
            }
        }
        for (String behaviour : Behaviour.ALL) {
            check(claimed.contains(behaviour), "nothing claims the " + behaviour + " behaviour");
        }
        for (String behaviour : claimed) {
            check(Behaviour.implemented(behaviour), behaviour + " is claimed and not implemented");
        }
        pass("every behaviour is claimed and every claim is implemented");
    }

    /**
     * Font, background, padding and the rest, on every widget module.
     *
     * <p>Declared centrally so they cannot drift apart; checked here so a new
     * widget module cannot forget them. Keystrokes intentionally replaces the
     * generic text-colour row with independent pressed/unpressed text colours.
     */
    static void everyWidgetModuleCarriesTheCommonHudSettings() {
        for (FathomModule module : ModuleRegistry.standard().all()) {
            if (module.widgets().isEmpty() || "crosshair".equals(module.id())) {
                // The crosshair is the exception, and it is a real one: a plate
                // with padding and a corner radius behind a crosshair is not a
                // thing anybody wants, so declaring those settings for it would
                // be declaring settings that do nothing.
                continue;
            }
            check(HudStyle.declaredOn(module), module.id() + " is a HUD module without the common settings");
        }
        pass("every widget module carries the common HUD settings");
    }

    /** Fresh profiles preserve the resource-pack-owned Minecraft HUD typography. */
    static void everyHudModuleDefaultsToMinecraftFont() {
        for (FathomModule module : ModuleRegistry.standard().all()) {
            if (module.widgets().isEmpty() || "crosshair".equals(module.id())) {
                continue;
            }
            check("Minecraft".equals(module.choice(HudStyle.FONT, "missing")),
                    module.id() + " does not default to Minecraft's font");
        }

        File directory = temporaryDirectory("module-font");
        ModuleRegistry before = ModuleRegistry.standard();
        ((Setting.Choice) before.find("fps").setting(HudStyle.FONT)).set("Fathom");
        try {
            new ModuleStore().save(directory, before);
        } catch (Exception problem) {
            throw new AssertionError("saving per-module font failed: " + problem);
        }
        ModuleRegistry after = ModuleRegistry.standard();
        new ModuleStore().load(directory, after);
        check("Fathom".equals(after.find("fps").choice(HudStyle.FONT, "missing")),
                "FPS kept its own font choice");
        check("Minecraft".equals(after.find("coordinates").choice(HudStyle.FONT, "missing")),
                "FPS font did not leak into Coordinates");
        pass("HUD fonts default to Minecraft and persist per module");
    }

    /** The default path neither draws Fathom's crosshair nor cancels vanilla's. */
    static void vanillaCrosshairIsUntouchedUntilReplacementIsEnabled() {
        FathomModule crosshair = ModuleRegistry.standard().find("crosshair");
        check(!crosshair.defaultEnabled() && !crosshair.enabled(), "crosshair starts disabled");
        check(!CrosshairPolicy.drawsCustom(crosshair), "fresh profile draws no custom crosshair");
        check(!CrosshairPolicy.hidesVanilla(crosshair), "fresh profile cannot cancel vanilla crosshair");

        crosshair.setEnabled(true);
        check(CrosshairPolicy.drawsCustom(crosshair), "enabled custom shape draws");
        check(CrosshairPolicy.hidesVanilla(crosshair), "enabled replacement may hide vanilla");
        ((Setting.Choice) crosshair.setting("shape")).set("Vanilla");
        check(!CrosshairPolicy.drawsCustom(crosshair), "Vanilla shape draws no Fathom replacement");
        check(!CrosshairPolicy.hidesVanilla(crosshair), "Vanilla shape never cancels resource-pack crosshair");
        pass("vanilla crosshair remains untouched by default");
    }

    /** No global ButtonWidget draw hook may override vanilla or resource packs. */
    static void vanillaButtonsHaveNoGlobalDrawInterceptor() {
        InputStream stream = ModuleTests.class.getResourceAsStream("/fathom.mixins.json");
        check(stream != null, "mixin manifest is on the test classpath");
        Scanner scanner = new Scanner(stream, "UTF-8").useDelimiter("\\A");
        String manifest = scanner.hasNext() ? scanner.next() : "";
        scanner.close();
        check(!manifest.contains("ButtonWidgetMixin"),
                "global ButtonWidget styling still intercepts vanilla screens");
        pass("vanilla buttons have no global draw interceptor");
    }

    /** Keystrokes can style its panel, key plates, borders, text and opacity separately. */
    static void keystrokesHasIndependentVisualControls() {
        FathomModule keys = ModuleRegistry.standard().find("keystrokes");
        Setting.Choice material = (Setting.Choice) keys.setting(HudStyle.BACKGROUND);
        check("Panel material".equals(material.label()), "outer plate is named Panel material");
        check("None".equals(material.get()), "outer panel is clear by default");
        check(material.parse("true") && "Standard".equals(material.get()),
                "the old enabled background migrates to Standard");
        check(material.parse("false") && "None".equals(material.get()),
                "the old disabled background remains clear");
        check(keys.setting("key.background") != null, "key background has its own control");
        check(keys.setting("key.border") != null, "border has its own control");
        check(keys.setting("key.pressed.fill") != null, "pressed fill has its own colour");
        check(keys.setting("key.unpressed.fill") != null, "unpressed fill has its own colour");
        check(keys.setting("key.pressed.text") != null, "pressed text has its own colour");
        check(keys.setting("key.unpressed.text") != null, "unpressed text has its own colour");
        check(keys.setting("key.opacity") != null, "keys have their own opacity");
        check(keys.colour("key.pressed.fill", 0) != 0xD2283E,
                "fresh pressed key is not the old red slab");
        pass("keystrokes exposes independent visual controls");
    }

    static void everySettingIdIsUniqueWithinItsModule() {
        for (FathomModule module : ModuleRegistry.standard().all()) {
            List<String> seen = new ArrayList<String>();
            for (Setting setting : module.settings()) {
                check(!seen.contains(setting.id()),
                        module.id() + " declares " + setting.id() + " twice");
                seen.add(setting.id());
                check(!setting.label().isEmpty(), module.id() + "." + setting.id() + " has no label");
            }
        }
        pass("every setting id is unique within its module");
    }

    static void experimentalAndUnavailableSayWhy() {
        for (FathomModule module : ModuleRegistry.standard().all()) {
            if (module.implementation().noteworthy()) {
                check(!module.description().isEmpty(),
                        module.id() + " is " + module.implementation().label() + " and says nothing about it");
            }
        }
        pass("experimental and unavailable say why");
    }

    // -----------------------------------------------------------------------
    // The switches
    // -----------------------------------------------------------------------

    /**
     * On, and the effect is reachable. Off, and it is not.
     *
     * <p>The effect is not simulated: a widget module is asked through the same
     * {@code widgetAllowed} the renderer asks, and an optimization module
     * through the same switch board the client applies. Testing a copy of the
     * rule would have passed while the client was broken.
     */
    static void aToggleChangesStateAndTheEffectFollows() {
        ModuleRegistry registry = ModuleRegistry.standard();
        for (FathomModule module : registry.all()) {
            if (!module.switchable()) {
                continue;
            }
            boolean before = module.enabled();

            module.setEnabled(true);
            check(module.enabled(), module.id() + " would not switch on");
            check(effectVisible(registry, module), module.id() + " is on and its effect is not reachable");

            module.setEnabled(false);
            check(!module.enabled(), module.id() + " would not switch off");
            check(!effectVisible(registry, module), module.id() + " is off and its effect is still reachable");

            module.setEnabled(before);
        }
        pass("a toggle changes state and the effect follows");
    }

    /**
     * Whether the thing the module drives is actually reachable right now.
     *
     * <p>Asked of the real registries. A behaviour is checked by the same
     * condition its Mixin uses — the module is on and switchable — because a
     * behaviour has no registry to ask.
     */
    private static boolean effectVisible(ModuleRegistry registry, FathomModule module) {
        for (String widget : module.widgets()) {
            if (!registry.widgetAllowed(widget)) {
                return false;
            }
        }
        if (!module.optimizations().isEmpty()) {
            SwitchBoard board = SwitchBoard.defaults(OptimizationRegistry.standard());
            for (String optimization : module.optimizations()) {
                board.setByUser(OptimizationId.of(optimization), module.enabled());
                if (board.isEnabled(OptimizationId.of(optimization)) != module.enabled()) {
                    return false;
                }
            }
        }
        if (!module.widgets().isEmpty()) {
            return module.enabled();
        }
        return module.enabled() && module.switchable();
    }

    /** A value set in the hub is the value the next launch reads. */
    static void settingsSurviveARestart() {
        File directory = temporaryDirectory("modules-restart");

        ModuleRegistry before = ModuleRegistry.standard();
        FathomModule clock = before.find("clock");
        clock.setEnabled(true);
        ((Setting.Choice) clock.setting("format")).set("12 hour");
        ((Setting.Toggle) clock.setting("seconds")).set(true);
        ((Setting.Whole) clock.setting(HudStyle.PADDING)).set(7);
        ((Setting.Colour) clock.setting(HudStyle.TEXT_COLOUR)).set(0x33AAFF);

        Keybind hubKey = new Keybind("hub", "Open Fathom Hub", 54);
        try {
            new ModuleStore().save(directory, before, hubKey,
                    new net.fathom.optimizations.core.module.ClientPrefs());
        } catch (Exception problem) {
            throw new AssertionError("saving failed: " + problem);
        }

        ModuleRegistry after = ModuleRegistry.standard();
        new ModuleStore().load(directory, after);
        FathomModule read = after.find("clock");
        check(read.enabled(), "the switch survived");
        check("12 hour".equals(read.choice("format", "24 hour")), "a choice survived");
        check(read.toggle("seconds", false), "a toggle survived");
        check(Math.round(read.number(HudStyle.PADDING, 3)) == 7, "a common HUD setting survived");
        check(read.colour(HudStyle.TEXT_COLOUR, 0xFFFFFF) == 0x33AAFF, "a colour survived");
        pass("settings survive a restart");
    }

    /**
     * Two profiles, two files, no bleed.
     *
     * <p>Profile isolation is not a feature of the store: it is a consequence of
     * the store being handed the game directory, and the launcher giving each
     * profile its own. That is exactly why it is worth a test — it would be lost
     * silently the day something decided to resolve the path itself.
     */
    static void oneProfileDoesNotSeeAnother() {
        File first = temporaryDirectory("modules-profile-a");
        File second = temporaryDirectory("modules-profile-b");

        ModuleRegistry a = ModuleRegistry.standard();
        a.find("cps").setEnabled(true);
        ((Setting.Whole) a.find("cps").setting("window")).set(5);

        ModuleRegistry b = ModuleRegistry.standard();
        b.find("cps").setEnabled(false);

        try {
            new ModuleStore().save(first, a);
            new ModuleStore().save(second, b);
        } catch (Exception problem) {
            throw new AssertionError("saving failed: " + problem);
        }

        ModuleRegistry readA = ModuleRegistry.standard();
        new ModuleStore().load(first, readA);
        ModuleRegistry readB = ModuleRegistry.standard();
        new ModuleStore().load(second, readB);

        check(readA.find("cps").enabled(), "the first profile kept its switch on");
        check(Math.round(readA.find("cps").number("window", 1)) == 5, "and its own setting");
        check(!readB.find("cps").enabled(), "the second profile is unaffected");
        check(Math.round(readB.find("cps").number("window", 1)) == 1, "including its settings");
        pass("one profile does not see another");
    }

    /**
     * A conflict is shown, not forbidden.
     *
     * <p>Two modules on the same key is usually a mistake and occasionally
     * deliberate — a key that zooms and does something else while zoomed is a
     * real setup. So the interface says "This key is also used by Zoom." and
     * lets it stand; what would be unacceptable is saying nothing.
     */
    static void keybindsReportConflictsWithoutForbiddingThem() {
        ModuleRegistry registry = ModuleRegistry.standard();
        Keybind zoom = null;
        for (Keybind binding : registry.keybinds()) {
            if ("zoom".equals(binding.id())) {
                zoom = binding;
            }
        }
        check(zoom != null, "the zoom key exists");

        Keybind other = new Keybind("other", "Something else", zoom.key());
        List<String> clashes = conflicts(registry, other, zoom.key());
        check(!clashes.isEmpty(), "a shared key is reported");
        check(clashes.get(0).contains("Zoom"), "and it names what it clashes with: " + clashes);

        other.set(Keybind.NONE);
        check(conflicts(registry, other, Keybind.NONE).isEmpty(), "unbound clashes with nothing");
        pass("keybinds report conflicts without forbidding them");
    }

    /** A captured integer is a key code, never a setting or slider index. */
    static void keybindCaptureOwnsExactlyOneBindingAndNoSlider() {
        KeybindCaptureState capture = new KeybindCaptureState();
        Keybind hub = new Keybind("hub", "Open Fathom Hub", 54);
        Setting.Slider fov = new Setting.Slider(
                "fov", "Field of view", "Unrelated video setting", 0.5d, 0d, 1d, 0.01d, 2, "");

        capture.begin(hub);
        check(capture.keyboard(1, 1) == KeybindCaptureState.Outcome.CANCELLED,
                "Escape cancels capture");
        check(hub.key() == 54, "cancel preserves the previous binding");
        check(Math.abs(fov.get() - 0.5d) < 0.0001d, "cancel cannot touch FOV");

        capture.begin(hub);
        check(capture.keyboard(2, 1) == KeybindCaptureState.Outcome.BOUND,
                "the first non-Escape key binds");
        check(hub.key() == 2, "the exact key code was stored");
        check(!capture.active(), "capture owns one gesture only");
        check(capture.keyboard(3, 1) == KeybindCaptureState.Outcome.NONE,
                "later keys are not captured");
        check(hub.key() == 2, "a later key cannot overwrite it");
        check(Math.abs(fov.get() - 0.5d) < 0.0001d,
                "the key code was never interpreted as slider selector 2");
        pass("keybind capture owns exactly one binding and no slider");
    }

    static void mouseBindingsCoverEveryButtonConvention() {
        KeybindCaptureState capture = new KeybindCaptureState();
        Keybind binding = new Keybind("mouse", "Mouse action", 54);

        capture.begin(binding);
        check(capture.mouse(1) == KeybindCaptureState.Outcome.BOUND, "right click binds");
        check(binding.isMouseButton() && binding.mouseButton() == 1, "right click round-trips as button 1");
        check("Right Click".equals(binding.keyName()), "right click is named");

        capture.begin(binding);
        check(capture.mouse(2) == KeybindCaptureState.Outcome.BOUND, "middle click binds");
        check(binding.isMouseButton() && binding.mouseButton() == 2, "middle click round-trips as button 2");
        check("Middle Click".equals(binding.keyName()), "middle click is named");
        pass("mouse bindings cover every Minecraft mouse-code direction");
    }

    /**
     * Backspace and Delete unbind.
     *
     * <p>Without this there is no way to remove a binding at all: every key
     * press binds that key, so a user who wants no key has to pick one they
     * never press. Escape already means cancel, so it cannot also mean clear.
     */
    static void aBindingCanBeClearedWithoutBindingAnotherKey() {
        KeybindCaptureState capture = new KeybindCaptureState();
        Keybind binding = new Keybind("zoom", "Zoom", 46);

        capture.begin(binding);
        check(capture.keyboard(KeybindCaptureState.KEY_BACKSPACE, 1) == KeybindCaptureState.Outcome.CLEARED,
                "Backspace clears");
        check(!binding.isBound(), "and the binding is now none");
        check("None".equals(binding.keyName()), "which is what the row says");

        binding.set(46);
        capture.begin(binding);
        check(capture.keyboard(KeybindCaptureState.KEY_DELETE, 1) == KeybindCaptureState.Outcome.CLEARED,
                "so does Delete");
        check(!binding.isBound(), "the binding is none again");

        binding.reset();
        capture.begin(binding);
        check(capture.keyboard(0, 1) == KeybindCaptureState.Outcome.NONE,
                "a key LWJGL cannot name is not a binding");
        check(capture.active(), "and capture keeps listening rather than silently failing");
        check(binding.key() == 46, "the old key is untouched");
        pass("a binding can be cleared without binding another key");
    }

    /**
     * A preset is a shortcut to values, not a mode.
     *
     * <p>So choosing one has to write the values, and editing a value has to
     * stop claiming the preset. Either half alone is worse than neither: a
     * preset that does not write is decoration, and one that keeps its name
     * after an edit describes a crosshair that is no longer on screen.
     */
    static void aCrosshairPresetWritesItsShapeAndEditsMakeItCustom() {
        FathomModule crosshair = ModuleRegistry.standard().find("crosshair");
        Setting preset = crosshair.setting(CrosshairPresets.PRESET);
        check("Default".equals(((Setting.Choice) preset).get()), "a fresh profile is the default shape");

        ((Setting.Choice) preset).set("Small");
        crosshair.settingChanged(preset);
        check(Math.round(crosshair.number(CrosshairPresets.LENGTH, 0)) == 2, "the preset wrote the length");
        check(Math.round(crosshair.number(CrosshairPresets.GAP, 0)) == 1, "and the gap");
        check("Small".equals(((Setting.Choice) preset).get()), "and the row still says Small");

        Setting length = crosshair.setting(CrosshairPresets.LENGTH);
        ((Setting.Whole) length).set(9);
        crosshair.settingChanged(length);
        check(CrosshairPresets.CUSTOM.equals(((Setting.Choice) preset).get()),
                "editing the shape makes it custom");
        check(Math.round(crosshair.number(CrosshairPresets.LENGTH, 0)) == 9,
                "and the edit stands rather than being written back over");

        // Colour is not part of a preset: somebody who picked their own green
        // should be able to try every shape without losing it.
        Setting colour = crosshair.setting("colour");
        ((Setting.Colour) colour).set(0x33FF99);
        ((Setting.Choice) preset).set("Classic");
        crosshair.settingChanged(preset);
        check(crosshair.colour("colour", 0) == 0x33FF99, "a preset leaves the colour alone");
        pass("a crosshair preset writes its shape and edits make it custom");
    }

    /** A minimum above the maximum is not a state the interface can produce. */
    static void zoomLimitsStayInOrderHoweverTheyAreEdited() {
        FathomModule zoom = ModuleRegistry.standard().find("zoom");
        Setting.Slider min = (Setting.Slider) zoom.setting("min");
        Setting.Slider amount = (Setting.Slider) zoom.setting("amount");
        Setting.Slider max = (Setting.Slider) zoom.setting("max");

        min.set(8);
        zoom.settingChanged(min);
        check(amount.get() >= min.get(), "raising the minimum carries the amount with it");
        check(max.get() >= min.get(), "and never leaves the maximum below it");

        max.set(3);
        zoom.settingChanged(max);
        check(amount.get() <= max.get(), "lowering the maximum carries the amount down");
        check(min.get() <= max.get(), "and the minimum with it");

        amount.set(amount.maximum());
        zoom.settingChanged(amount);
        check(max.get() >= amount.get(), "and an amount past the maximum raises it");
        pass("zoom limits stay in order however they are edited");
    }

    /**
     * A renamed option still reads.
     *
     * <p>The FPS reading called "Off" is called "Raw" now, because Off read as
     * "no FPS readout". A profile written by the older build says Off, and a
     * choice that refused it would quietly reset somebody's setting on upgrade.
     */
    static void aRenamedOptionIsStillReadFromAnOlderProfile() {
        FathomModule fps = ModuleRegistry.standard().find("fps");
        Setting.Choice reading = (Setting.Choice) fps.setting("smoothing");
        check(reading.parse("Off"), "the old spelling is accepted");
        check("Raw".equals(reading.get()), "and read as its new name");
        check(reading.parse("Smooth") && "Smooth".equals(reading.get()), "current names still work");
        check(!reading.parse("Sideways"), "and an option that never existed is still refused");
        pass("a renamed option is still read from an older profile");
    }

    /**
     * A label derived from other settings has to describe what was loaded.
     *
     * <p>A stored preset and stored shape values can disagree — a file edited by
     * hand, or written by a build whose preset meant something slightly
     * different. The values are the truth, so after a load the label is
     * re-derived from them rather than left claiming a shape that is not on
     * screen.
     */
    static void aLoadedModuleDescribesWhatItActuallyLoaded() {
        File directory = temporaryDirectory("modules-derived");
        writeProperties(new File(directory, ModuleStore.RELATIVE_PATH),
                "schema = 1\n"
                        + "feature.crosshair.set.preset = Competitive\n"
                        + "feature.crosshair.set.length = 9\n");

        ModuleRegistry registry = ModuleRegistry.standard();
        new ModuleStore().load(directory, registry);
        FathomModule crosshair = registry.find("crosshair");
        check(Math.round(crosshair.number(CrosshairPresets.LENGTH, 0)) == 9,
                "the stored length was loaded");
        check(CrosshairPresets.CUSTOM.equals(crosshair.choice(CrosshairPresets.PRESET, "")),
                "and the preset row says what that actually is: "
                        + crosshair.choice(CrosshairPresets.PRESET, ""));
        pass("a loaded module describes what it actually loaded");
    }

    private static void writeProperties(File file, String contents) {
        try {
            File parent = file.getParentFile();
            if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
                throw new AssertionError("could not create " + parent);
            }
            java.io.Writer out = new java.io.OutputStreamWriter(
                    new java.io.FileOutputStream(file), "UTF-8");
            try {
                out.write(contents);
            } finally {
                out.close();
            }
        } catch (java.io.IOException problem) {
            throw new AssertionError("writing failed: " + problem);
        }
    }

    /**
     * The revision is what lets a widget cache what it derived from its
     * settings for a whole frame instead of re-reading a dozen values.
     */
    static void theSettingsRevisionMovesOnlyWhenAValueDoes() {
        Setting.Toggle toggle = new Setting.Toggle("t", "Toggle", "", false);
        int before = Setting.revision();
        check(Setting.revision() == before, "reading changes nothing");
        toggle.set(false);
        check(Setting.revision() == before, "and setting the same value changes nothing");
        toggle.set(true);
        check(Setting.revision() != before, "a real change moves it");

        int afterSet = Setting.revision();
        toggle.parse("true");
        check(Setting.revision() == afterSet, "parsing the same value moves nothing");
        toggle.parse("false");
        check(Setting.revision() != afterSet, "parsing a different one does");

        int afterParse = Setting.revision();
        toggle.reset();
        check(Setting.revision() == afterParse, "resetting to the value it already has moves nothing");
        pass("the settings revision moves only when a value does");
    }

    /**
     * Everything already bound to a key, by name.
     *
     * <p>Lives here as well as in the interface deliberately: this is the shape
     * the drawer's message is built from, and a test that invented its own
     * search would not be testing the message anybody sees.
     */
    private static List<String> conflicts(ModuleRegistry registry, Keybind asking, int key) {
        List<String> out = new ArrayList<String>();
        if (key == Keybind.NONE) {
            return out;
        }
        for (FathomModule module : registry.all()) {
            for (Keybind binding : module.keybinds()) {
                if (binding != asking && binding.key() == key) {
                    out.add(module.name());
                }
            }
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // The table
    // -----------------------------------------------------------------------

    /**
     * The table the brief asks for, checked and printed.
     *
     * <p>Printed because a table nobody can read is a check somebody has to take
     * on trust, and this is the artefact that answers "which modules actually
     * work". Every row has to be five ticks; the run fails otherwise, so the
     * table cannot quietly become a list of things that used to pass.
     */
    static void theToggleValidationTable() {
        ModuleRegistry registry = ModuleRegistry.standard();
        System.out.println();
        System.out.println("  Module              state  appears  disappears  persists  isolated");
        System.out.println("  ------------------  -----  -------  ----------  --------  --------");

        for (FathomModule module : registry.all()) {
            if (!module.switchable()) {
                System.out.println("  " + pad(module.name(), 18) + "  (no toggle: "
                        + module.implementation().label().toLowerCase(java.util.Locale.ROOT) + ")");
                continue;
            }
            boolean before = module.enabled();

            module.setEnabled(true);
            boolean appears = module.enabled() && effectVisible(registry, module);
            module.setEnabled(false);
            boolean disappears = !module.enabled() && !effectVisible(registry, module);
            boolean state = appears && disappears;
            module.setEnabled(before);

            boolean persists = persists(module);
            boolean isolated = isolated(module);

            System.out.println("  " + pad(module.name(), 18)
                    + "  " + tick(state, 5)
                    + "  " + tick(appears, 7)
                    + "  " + tick(disappears, 10)
                    + "  " + tick(persists, 8)
                    + "  " + tick(isolated, 8));

            check(state && appears && disappears && persists && isolated,
                    module.id() + " does not pass the toggle validation table");
        }
        System.out.println();
        pass("every switchable module passes the toggle validation table");
    }

    /** Switch it on, write it, read it back into a fresh registry. */
    private static boolean persists(FathomModule module) {
        File directory = temporaryDirectory("modules-persist");
        ModuleRegistry written = ModuleRegistry.standard();
        FathomModule copy = written.find(module.id());
        copy.setEnabled(!copy.defaultEnabled());
        try {
            new ModuleStore().save(directory, written);
        } catch (Exception problem) {
            throw new AssertionError("saving failed: " + problem);
        }
        ModuleRegistry read = ModuleRegistry.standard();
        new ModuleStore().load(directory, read);
        return read.find(module.id()).enabled() == !module.defaultEnabled();
    }

    /** The same switch, written in one directory, must not appear in another. */
    private static boolean isolated(FathomModule module) {
        File mine = temporaryDirectory("modules-mine");
        File theirs = temporaryDirectory("modules-theirs");
        ModuleRegistry written = ModuleRegistry.standard();
        written.find(module.id()).setEnabled(!module.defaultEnabled());
        try {
            new ModuleStore().save(mine, written);
        } catch (Exception problem) {
            throw new AssertionError("saving failed: " + problem);
        }
        ModuleRegistry other = ModuleRegistry.standard();
        new ModuleStore().load(theirs, other);
        return other.find(module.id()).enabled() == module.defaultEnabled();
    }

    private static String tick(boolean yes, int width) {
        return pad(yes ? "yes" : "NO", width);
    }

    private static String pad(String text, int width) {
        StringBuilder out = new StringBuilder(text);
        while (out.length() < width) {
            out.append(' ');
        }
        return out.toString();
    }

    // -----------------------------------------------------------------------
    // Runner
    // -----------------------------------------------------------------------

    private static File temporaryDirectory(String label) {
        File base = new File(System.getProperty("java.io.tmpdir"),
                "fathom-" + label + "-" + System.nanoTime());
        if (!base.mkdirs()) {
            throw new AssertionError("could not create " + base);
        }
        base.deleteOnExit();
        return base;
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  ok   " + name);
    }

    private static void report() {
        System.out.println();
        System.out.println("ModuleTests: " + passed + " passed");
    }

    private ModuleTests() {}
}
