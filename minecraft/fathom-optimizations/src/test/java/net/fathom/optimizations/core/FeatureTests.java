package net.fathom.optimizations.core;

import java.io.File;
import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.module.Category;
import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.module.SessionName;
import net.fathom.optimizations.core.module.FathomApi;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.module.ModuleStore;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.module.LaunchSession;
import net.fathom.optimizations.core.module.ModuleLog;
import net.fathom.optimizations.core.module.LaunchSession;
import net.fathom.optimizations.core.module.ModuleLog;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.core.hud.HudRegistry;
import net.fathom.optimizations.core.hud.Sensitivity;

/**
 * Tests for the feature framework, the mod-config API and the client preferences.
 *
 * <p>Same hand-rolled runner as {@link CoreTests}: this half of the module has
 * no dependencies outside the JDK and should stay checkable with nothing but a
 * compiler.
 *
 * <p>What is <em>not</em> here is anything that draws. The hub, the controls and
 * the widgets need a running game, and the reason as much as possible was kept
 * out of them is so that everything below can be checked without one.
 */
public final class FeatureTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        // -- settings -------------------------------------------------------
        everySettingKindRoundTripsThroughItsStoredForm();
        aStoredValueThatIsNotUsableFallsBackRatherThanThrowing();
        aSliderStepsAndClampsRatherThanTrustingItsInput();
        aChoiceRefusesAnOptionItDoesNotHave();
        aColourAcceptsTheFormsPeopleWrite();

        // -- features -------------------------------------------------------
        aFeatureThatDoesNothingIsRefused();
        aFeatureIdHasToBeAnId();
        nothingServerSensitiveIsOnByDefaultHowever_itAsks();
        resettingPutsEverythingBackIncludingKeys();
        searchLooksInsideSettingsToo();

        // -- the built-in set ------------------------------------------------
        everyBuiltInFeatureIsCompleteAndUnique();
        everyWidgetAFeatureClaimsActuallyExists();
        everyOptimizationAFeatureDrivesActuallyExists();
        theFirstBatchIsTheOneTheBriefAskedFor();
        onlyTwoThingsAreOnOutOfTheBox();

        // -- the registry ----------------------------------------------------
        aDuplicateRegistrationIsRefusedAndRecorded();
        registrationClosesWhenTheHubOpens();
        aWidgetWhoseFeatureIsOffIsNotDrawn();
        favouritesComeFirstWithoutReshufflingTheRest();

        // -- persistence -----------------------------------------------------
        onlyChangedValuesAreWritten();
        settingsSurviveBeingWrittenAndReadBack();
        oneBadValueDoesNotCostTheFile();
        configurationForSomethingUninstalledIsReported();
        theHubKeyIsRememberedSeparatelyFromEveryFeature();

        // -- the public API --------------------------------------------------
        theApiRefusesEverythingWhenFathomIsNotRunning();
        aThirdPartyFeatureLandsInTheSameListAsOurs();

        // -- the client's own settings ---------------------------------------
        aSessionIsNamedForWhatStartedIt();
        theLaunchDescriptorIsReadOrHonestlyAbsent();
        aQuotedProfileNameSurvivesTheDescriptor();
        aDescriptorFromAnotherSchemaIsIgnoredRatherThanGuessedAt();
        theModuleLogKeepsWhatItSaidAndNoMore();
        theLaunchDescriptorIsReadOrHonestlyAbsent();
        aQuotedProfileNameSurvivesTheDescriptor();
        aDescriptorFromAnotherSchemaIsIgnoredRatherThanGuessedAt();
        theModuleLogKeepsWhatItSaidAndNoMore();
        theLaunchDescriptorIsReadOrHonestlyAbsent();
        aDescriptorFromAnotherSchemaIsIgnoredRatherThanGuessedAt();
        theModuleLogKeepsWhatItSaidAndNoMore();
        theInterfaceScaleIsAChoiceAndNotANumber();

        report();
    }

    // -----------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------

    static void everySettingKindRoundTripsThroughItsStoredForm() {
        Setting.Toggle toggle = new Setting.Toggle("t", "T", "", false);
        toggle.set(true);
        Setting.Toggle toggleBack = new Setting.Toggle("t", "T", "", false);
        check(toggleBack.parse(toggle.serialise()) && toggleBack.get(), "toggle round trips");

        Setting.Slider slider = new Setting.Slider("s", "S", "", 1, 0, 10, 0.5, 1, "x");
        slider.set(3.5);
        Setting.Slider sliderBack = new Setting.Slider("s", "S", "", 1, 0, 10, 0.5, 1, "x");
        check(sliderBack.parse(slider.serialise()), "slider parses");
        check(Math.abs(sliderBack.get() - 3.5) < 1e-9, "slider round trips");

        Setting.Choice choice = new Setting.Choice("c", "C", "", "a", "a", "b", "c");
        choice.set("b");
        Setting.Choice choiceBack = new Setting.Choice("c", "C", "", "a", "a", "b", "c");
        check(choiceBack.parse(choice.serialise()) && "b".equals(choiceBack.get()), "choice round trips");

        Setting.Colour colour = new Setting.Colour("k", "K", "", 0xFFFFFF);
        colour.set(0x123456);
        Setting.Colour colourBack = new Setting.Colour("k", "K", "", 0xFFFFFF);
        check(colourBack.parse(colour.serialise()) && colourBack.rgb() == 0x123456, "colour round trips");
        check(colour.argb(128) == ((128 << 24) | 0x123456), "colour packs with alpha");

        pass("every setting kind round trips through its stored form");
    }

    static void aStoredValueThatIsNotUsableFallsBackRatherThanThrowing() {
        // The file is user-editable, so these arrive from outside. Losing one
        // value is better than losing the file, and a throw here would be
        // inside a start-up path.
        Setting.Toggle toggle = new Setting.Toggle("t", "T", "", true);
        check(!toggle.parse("perhaps"), "nonsense is reported as unusable");
        check(toggle.get(), "and the value is untouched");

        Setting.Slider slider = new Setting.Slider("s", "S", "", 5, 0, 10, 1, 0, "");
        check(!slider.parse("five"), "a word is not a number");
        check(slider.get() == 5, "and the value is untouched");

        Setting.Choice choice = new Setting.Choice("c", "C", "", "a", "a", "b");
        check(!choice.parse("z"), "an option that does not exist is refused");
        check("a".equals(choice.get()), "and the value is untouched");

        Setting.Colour colour = new Setting.Colour("k", "K", "", 0xFFFFFF);
        check(!colour.parse("zzzzzz"), "not hex");
        check(!colour.parse("FFF"), "not six digits");
        check(colour.rgb() == 0xFFFFFF, "and the value is untouched");

        pass("a stored value that is not usable falls back rather than throwing");
    }

    static void aSliderStepsAndClampsRatherThanTrustingItsInput() {
        Setting.Slider slider = new Setting.Slider("s", "S", "", 4, 1.5, 10, 0.5, 1, "x");
        slider.set(99);
        check(slider.get() == 10, "clamped at the top");
        slider.set(-5);
        check(slider.get() == 1.5, "clamped at the bottom");
        slider.set(3.3);
        check(Math.abs(slider.get() - 3.5) < 1e-9, "snapped to the step, got " + slider.get());
        slider.setFraction(1.0);
        check(slider.get() == 10, "the far end of the track is the maximum");
        slider.setFraction(0.0);
        check(slider.get() == 1.5, "and the near end is the minimum");
        check(Math.abs(slider.fraction()) < 1e-9, "which reads back as zero");
        pass("a slider steps and clamps rather than trusting its input");
    }

    static void aChoiceRefusesAnOptionItDoesNotHave() {
        Setting.Choice choice = new Setting.Choice("c", "C", "", "b", "a", "b", "c");
        check("b".equals(choice.get()), "it starts at its default");
        choice.set("nope");
        check("b".equals(choice.get()), "and an unknown option changes nothing");
        choice.advance();
        check("c".equals(choice.get()), "advancing steps forward");
        choice.advance();
        check("a".equals(choice.get()), "and wraps");
        expectThrows("a choice with one option", new Runnable() {
            @Override
            public void run() {
                new Setting.Choice("c", "C", "", "a", "a");
            }
        });
        pass("a choice refuses an option it does not have");
    }

    static void aColourAcceptsTheFormsPeopleWrite() {
        Setting.Colour colour = new Setting.Colour("k", "K", "", 0x000000);
        check(colour.parse("#FF0000") && colour.rgb() == 0xFF0000, "with a hash");
        check(colour.parse("00ff00") && colour.rgb() == 0x00FF00, "without one, lowercase");
        check(colour.parse("  0000FF  ") && colour.rgb() == 0x0000FF, "with whitespace");
        check("#0000FF".equals(colour.display()), "and displays as it was written");
        pass("a colour accepts the forms people write");
    }

    // -----------------------------------------------------------------------
    // Features
    // -----------------------------------------------------------------------

    static void aFeatureThatDoesNothingIsRefused() {
        // A row in a list that does nothing when pressed is the thing the whole
        // client is trying not to have, so it is refused at construction.
        expectThrows("a feature with nothing attached", new Runnable() {
            @Override
            public void run() {
                FathomModule.builder("empty").name("Empty").description("Does absolutely nothing.").build();
            }
        });
        // One of anything is enough.
        FathomModule.builder("ok")
                .name("Ok")
                .description("Has one setting and therefore does something.")
                .setting(new Setting.Toggle("x", "X", "", false))
                .build();
        pass("a feature that does nothing is refused");
    }

    static void aFeatureIdHasToBeAnId() {
        for (final String bad : new String[] {"", "Upper", "with space", "-leading", "trailing-", "a..b", "under_score"}) {
            expectThrows("id " + bad, new Runnable() {
                @Override
                public void run() {
                    FathomModule.builder(bad);
                }
            });
        }
        for (String good : new String[] {"fps", "toggle-sprint", "mymod.thing", "a1"}) {
            FathomModule.builder(good);
        }
        pass("a feature id has to be an id");
    }

    static void nothingServerSensitiveIsOnByDefaultHowever_itAsks() {
        FathomModule asking = FathomModule.builder("asking")
                .name("Asking")
                .description("Asks to be on by default while being server-sensitive.")
                .sensitivity(Sensitivity.SERVER_SENSITIVE)
                .setting(new Setting.Toggle("x", "X", "", false))
                .onByDefault()
                .build();
        check(!asking.defaultEnabled(), "the request is ignored");
        check(!asking.enabled(), "and it starts off");

        // Order must not matter: calling onByDefault before sensitivity would be
        // a way round the rule if the check were in the builder method.
        FathomModule otherOrder = FathomModule.builder("other")
                .name("Other")
                .description("The same thing with the builder calls the other way round.")
                .onByDefault()
                .sensitivity(Sensitivity.SERVER_SENSITIVE)
                .setting(new Setting.Toggle("x", "X", "", false))
                .build();
        check(!otherOrder.defaultEnabled(), "still ignored");

        check(ModuleRegistry.standard().wronglyDefaultedOn().isEmpty(), "and the built-in set obeys it");
        pass("nothing server-sensitive is on by default, however it asks");
    }

    static void resettingPutsEverythingBackIncludingKeys() {
        FathomModule feature = FathomModule.builder("r")
                .name("R")
                .description("Something with a setting and a key to reset.")
                .setting(new Setting.Toggle("t", "T", "", false))
                .keybind(new Keybind("k", "K", 30))
                .build();

        check(!feature.changed(), "it starts unchanged");
        ((Setting.Toggle) feature.setting("t")).set(true);
        feature.keybinds().get(0).set(45);
        feature.setEnabled(true);
        check(feature.changed(), "and notices when it is not");

        feature.resetToDefaults();
        check(!((Setting.Toggle) feature.setting("t")).get(), "the setting went back");
        check(feature.keybinds().get(0).key() == 30, "the key went back");
        check(!feature.enabled(), "and so did the switch");
        check(!feature.changed(), "so nothing is changed any more");
        pass("resetting puts everything back, including keys");
    }

    static void searchLooksInsideSettingsToo() {
        FathomModule feature = FathomModule.builder("s")
                .name("Widget")
                .description("Draws a thing.")
                .setting(new Setting.Slider("o", "Opacity", "", 1, 0, 1, 0.1, 1, ""))
                .build();
        check(feature.matches("widget"), "by name");
        check(feature.matches("draws"), "by description");
        check(feature.matches("opacity"), "by setting label");
        check(feature.matches(""), "an empty query matches");
        check(!feature.matches("zzzz"), "and nonsense does not");
        pass("search looks inside settings too");
    }

    // -----------------------------------------------------------------------
    // The built-in set
    // -----------------------------------------------------------------------

    static void everyBuiltInFeatureIsCompleteAndUnique() {
        ModuleRegistry registry = ModuleRegistry.standard();
        check(registry.size() >= 14, "the first batch is there: " + registry.size());
        List<String> seen = new ArrayList<String>();
        for (FathomModule feature : registry.all()) {
            check(!seen.contains(feature.id()), "duplicate id " + feature.id());
            seen.add(feature.id());
            check(isOneShortSentence(feature.description()),
                    feature.id() + " description is not one short sentence: \""
                            + feature.description() + "\"");
            check(feature.owner().length() > 0, feature.id() + " has no owner");
            check(feature.icon() != null, feature.id() + " has no icon");
        }
        check(registry.complaints().isEmpty(), "and none of them was refused: " + registry.complaints());
        pass("every built-in feature is complete and uniquely identified");
    }

    /**
     * The copy rule for a module card, as an assertion.
     *
     * <p>A card gives its description one line beside a toggle. Anything longer
     * than a short sentence is ellipsised there, which means the words nobody
     * gets to read were written anyway and the reader is left guessing. The
     * previous version of this check asked only for more than twenty
     * characters, which let "Frames per second, smoothed so the number is
     * readable rather than flickering" through and rejected "The real-world
     * time." — exactly backwards.
     */
    /**
     * The longest description that fits a module card without an ellipsis.
     *
     * <p>Measured, not guessed: a 1280-wide window puts four cards across the
     * Mods grid, which leaves a description about 228 units of a 12px face, and
     * forty characters is what reliably fits in it. Wider windows have room to
     * spare; this is the case that has to work.
     */
    static final int MAX_DESCRIPTION = 40;

    static boolean isOneShortSentence(String description) {
        if (description == null) {
            return false;
        }
        String text = description.trim();
        if (text.length() < 12 || text.length() > MAX_DESCRIPTION) {
            return false;
        }
        if (!text.endsWith(".")) {
            return false;
        }
        // One sentence: the only full stop is the one at the end.
        return text.indexOf('.') == text.length() - 1;
    }

    static void everyWidgetAFeatureClaimsActuallyExists() {
        // A feature naming a widget nothing draws would be a switch with no
        // effect, which is the exact failure this client is built to avoid.
        HudRegistry widgets = HudRegistry.standard();
        for (FathomModule feature : ModuleRegistry.standard().all()) {
            for (String id : feature.widgets()) {
                check(widgets.contains(id), feature.id() + " claims widget " + id + ", which does not exist");
            }
        }
        pass("every widget a feature claims actually exists");
    }

    static void everyOptimizationAFeatureDrivesActuallyExists() {
        net.fathom.optimizations.core.registry.OptimizationRegistry optimizations =
                net.fathom.optimizations.core.registry.OptimizationRegistry.standard();
        for (FathomModule feature : ModuleRegistry.standard().all()) {
            for (String id : feature.optimizations()) {
                check(
                        optimizations.find(id) != null,
                        feature.id() + " drives " + id + ", which is not in the registry");
            }
        }
        pass("every optimization a feature drives actually exists");
    }

    /** The list the brief names, checked by name so a rename is caught. */
    static void theFirstBatchIsTheOneTheBriefAskedFor() {
        ModuleRegistry registry = ModuleRegistry.standard();
        String[] wanted = {
            "keystrokes", "cps", "fps", "ping", "coordinates", "armour", "potions",
            "pack-display", "clock", "toggle-sprint", "crosshair", "zoom",
            "unfocused-fps",
        };
        for (String id : wanted) {
            check(registry.contains(id), "the first batch is missing " + id);
        }
        // And they share one framework rather than being fourteen unrelated
        // things: every one of them is a FathomModule in one registry.
        check(registry.size() >= wanted.length, "all of them are in the one registry");
        pass("the first batch is the one the brief asked for");
    }

    static void onlyTwoThingsAreOnOutOfTheBox() {
        // Plus the two frame caps, which draw nothing and cost nothing.
        ModuleRegistry registry = ModuleRegistry.standard();
        List<String> drawing = new ArrayList<String>();
        for (FathomModule feature : registry.all()) {
            if (feature.defaultEnabled() && !feature.widgets().isEmpty()) {
                drawing.add(feature.id());
            }
        }
        check(drawing.size() == 2, "a fresh client draws two widgets, not " + drawing);
        check(drawing.contains("fps") && drawing.contains("coordinates"), "and they are: " + drawing);
        pass("only two things are on out of the box");
    }

    // -----------------------------------------------------------------------
    // The registry
    // -----------------------------------------------------------------------

    static void aDuplicateRegistrationIsRefusedAndRecorded() {
        ModuleRegistry registry = ModuleRegistry.empty();
        check(registry.register(sample("dupe")), "the first one is accepted");
        check(!registry.register(sample("dupe")), "the second is not");
        check(registry.size() == 1, "and it did not replace the first");
        check(registry.complaints().size() == 1, "and the refusal was recorded");
        pass("a duplicate registration is refused and recorded");
    }

    static void registrationClosesWhenTheHubOpens() {
        ModuleRegistry registry = ModuleRegistry.empty();
        registry.seal();
        check(!registry.register(sample("late")), "a late registration is refused");
        check(registry.size() == 0, "and nothing was added");
        check(registry.sealed(), "and the registry says it is closed");
        pass("registration closes when the hub opens");
    }

    static void aWidgetWhoseFeatureIsOffIsNotDrawn() {
        ModuleRegistry registry = ModuleRegistry.empty();
        FathomModule feature = FathomModule.builder("owner")
                .name("Owner")
                .description("Owns a widget, so switching it off hides the widget.")
                .widget("thing")
                .build();
        registry.register(feature);

        feature.setEnabled(true);
        check(registry.widgetAllowed("thing"), "on while its feature is on");
        feature.setEnabled(false);
        check(!registry.widgetAllowed("thing"), "and off while it is off");

        // A widget nothing owns belongs to something outside this registry, and
        // second-guessing it would hide it.
        check(registry.widgetAllowed("orphan"), "an unowned widget is left alone");
        check(registry.owningWidget("thing") == feature, "and ownership is answerable");
        pass("a widget whose feature is off is not drawn");
    }

    static void favouritesComeFirstWithoutReshufflingTheRest() {
        ModuleRegistry registry = ModuleRegistry.empty();
        registry.register(sample("a"));
        registry.register(sample("b"));
        registry.register(sample("c"));
        registry.find("c").setFavourite(true);

        List<FathomModule> order = registry.matching("");
        check("c".equals(order.get(0).id()), "the pinned one is first");
        check("a".equals(order.get(1).id()) && "b".equals(order.get(2).id()), "and the rest keep their order");
        pass("favourites come first without reshuffling the rest");
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    static void onlyChangedValuesAreWritten() {
        File directory = temporaryDirectory("features-clean");
        ModuleRegistry registry = ModuleRegistry.standard();
        ModuleStore store = new ModuleStore();
        try {
            store.save(directory, registry);
        } catch (Exception problem) {
            throw new AssertionError("writing failed: " + problem);
        }
        String written = read(new File(directory, ModuleStore.RELATIVE_PATH));
        // A file listing every value of every feature is a file where a real
        // change is invisible, and it freezes a default that later improves.
        check(!written.contains("feature.fps.enabled"), "an unchanged feature is not written: " + written);
        check(written.contains("schema"), "but the schema is");
        pass("only changed values are written");
    }

    static void settingsSurviveBeingWrittenAndReadBack() {
        File directory = temporaryDirectory("features-round-trip");
        ModuleRegistry written = ModuleRegistry.standard();
        written.find("cps").setEnabled(true);
        written.find("cps").setFavourite(true);
        ((Setting.Colour) written.find("fps").setting(HudStyle.TEXT_COLOUR)).set(0xE0505C);
        ((Setting.Whole) written.find("crosshair").setting("length")).set(7);
        ((Setting.Choice) written.find("clock").setting("format")).set("12 hour");
        written.find("zoom").keybinds().get(0).set(46);

        ModuleStore store = new ModuleStore();
        try {
            store.save(directory, written);
        } catch (Exception problem) {
            throw new AssertionError("writing failed: " + problem);
        }

        ModuleRegistry read = ModuleRegistry.standard();
        new ModuleStore().load(directory, read);
        check(read.find("cps").enabled(), "the switch survived");
        check(read.find("cps").favourite(), "the pin survived");
        check(read.find("fps").colour(HudStyle.TEXT_COLOUR, 0) == 0xE0505C, "the colour survived");
        check(read.find("crosshair").number("length", 0) == 7, "the stepper survived");
        check("12 hour".equals(read.find("clock").choice("format", "")), "the choice survived");
        check(read.find("zoom").keybinds().get(0).key() == 46, "the key survived");
        pass("settings survive being written and read back");
    }

    static void oneBadValueDoesNotCostTheFile() {
        File directory = temporaryDirectory("features-corrupt");
        writeFile(
                new File(directory, ModuleStore.RELATIVE_PATH),
                "schema = 1\n"
                        + "feature.cps.enabled = perhaps\n"
                        + "feature.fps.set.text.colour = not-a-colour\n"
                        + "feature.clock.enabled = true\n");

        ModuleRegistry registry = ModuleRegistry.standard();
        ModuleStore store = new ModuleStore();
        store.load(directory, registry);

        check(registry.find("clock").enabled(), "the readable line was applied");
        check(!registry.find("cps").enabled(), "the unreadable one fell back");
        check(registry.find("fps").colour(HudStyle.TEXT_COLOUR, 0) == 0xF2F2F2,
                "and so did the bad colour");
        check(store.complaints().size() >= 2, "and both were reported: " + store.complaints());
        pass("one bad value does not cost the file");
    }

    static void configurationForSomethingUninstalledIsReported() {
        File directory = temporaryDirectory("features-orphan");
        writeFile(
                new File(directory, ModuleStore.RELATIVE_PATH),
                "schema = 1\nfeature.somebody-elses-mod.enabled = true\n");

        ModuleStore store = new ModuleStore();
        store.load(directory, ModuleRegistry.standard());
        boolean mentioned = false;
        for (String complaint : store.complaints()) {
            mentioned |= complaint.contains("somebody-elses-mod");
        }
        check(mentioned, "it is named: " + store.complaints());
        pass("configuration for something uninstalled is reported");
    }

    static void theHubKeyIsRememberedSeparatelyFromEveryFeature() {
        // It cannot live on a feature: a feature can be switched off, and
        // switching off the only way to open the menu is unreachable by design.
        File directory = temporaryDirectory("features-hubkey");
        Keybind written = new Keybind("hub", "Open Fathom Hub", 54);
        written.set(Keybind.mouse(1));

        ClientPrefs prefs = new ClientPrefs();
        // By label, not by index. What this test is about is that a changed
        // scale survives a restart; which position "115%" happens to occupy is
        // not part of that, and pinning it here meant adding a scale option
        // broke a test about persistence.
        prefs.setScaleLabel("115%");
        prefs.setHighQualityUi(false);

        ModuleStore store = new ModuleStore();
        try {
            store.save(directory, ModuleRegistry.standard(), written, prefs);
        } catch (Exception problem) {
            throw new AssertionError("writing failed: " + problem);
        }

        Keybind read = new Keybind("hub", "Open Fathom Hub", 54);
        ClientPrefs readPrefs = new ClientPrefs();
        new ModuleStore().loadClient(directory, read, readPrefs);
        check(read.key() == Keybind.mouse(1), "the mouse hub key survived");
        // Anything the interface lets somebody change has to survive a restart
        // or it should not be offered.
        check("115%".equals(readPrefs.scaleLabel()), "the interface scale survived, got " + readPrefs.scaleLabel());
        check(!readPrefs.highQualityUi(), "the UI rendering quality survived");
        check("Right Click".equals(read.keyName()), "and renders with a name, got " + read.keyName());

        // Right Shift is the default, and it has to keep rendering as one.
        check("Right Shift".equals(Keybind.nameOf(54)), "Right Shift is named");
        check("None".equals(Keybind.nameOf(0)), "and an unbound key says so");
        pass("the hub key is remembered separately from every feature");
    }

    // -----------------------------------------------------------------------
    // The public API
    // -----------------------------------------------------------------------

    static void theApiRefusesEverythingWhenFathomIsNotRunning() {
        // A mod listing Fathom as optional runs whether or not Fathom is there,
        // and a null host must be a refusal rather than a crash.
        FathomApi.install(null);
        check(!FathomApi.available(), "it says it is not available");
        check(FathomApi.get().register(sample("x")) == FathomApi.Result.NOT_READY, "and refuses");
        check(FathomApi.get().registry() == null, "and hands back nothing");
        pass("the API refuses everything when Fathom is not running");
    }

    static void aThirdPartyFeatureLandsInTheSameListAsOurs() {
        final ModuleRegistry registry = ModuleRegistry.standard();
        FathomApi.install(new FathomApi.Host() {
            @Override
            public FathomApi.Result register(
                    FathomModule feature, Object widget, net.fathom.optimizations.core.hud.Placement placement) {
                return registry.register(feature) ? FathomApi.Result.ACCEPTED : FathomApi.Result.DUPLICATE;
            }

            @Override
            public ModuleRegistry registry() {
                return registry;
            }
        });

        FathomModule theirs = FathomModule.builder("mymod.thing")
                .name("Thing")
                .description("A feature a third-party mod registered through the API.")
                .category(Category.VISUAL)
                .owner("My Mod")
                .setting(new Setting.Toggle("bold", "Bold", "", false))
                .build();

        check(FathomApi.get().register(theirs) == FathomApi.Result.ACCEPTED, "accepted");
        check(registry.contains("mymod.thing"), "and it is in the same registry as ours");
        check(registry.matching("thing").contains(theirs), "and the same search finds it");
        check(registry.inCategory(Category.VISUAL).contains(theirs), "and the same categories");
        check("My Mod".equals(registry.find("mymod.thing").owner()), "and it is credited to its author");

        // A second registration of the same id is refused, so one mod cannot
        // replace another's feature by naming it.
        check(FathomApi.get().register(theirs) == FathomApi.Result.DUPLICATE, "and it cannot be replaced");

        FathomApi.install(null);
        pass("a third-party feature lands in the same list as ours");
    }

    // -----------------------------------------------------------------------
    // The client's own settings
    // -----------------------------------------------------------------------

    static void aSessionIsNamedForWhatStartedIt() {
        // The header used to read "Unknown profile" for every case but one,
        // including every development run. There are three cases and the client
        // can tell them apart.
        check("Competitive 1.8.9".equals(SessionName.of("Competitive 1.8.9", false)),
                "the launcher's profile wins");
        check("Competitive 1.8.9".equals(SessionName.of("  Competitive 1.8.9  ", true)),
                "and is trimmed, even in development");
        check(SessionName.DEVELOPMENT.equals(SessionName.of(null, true)), "a dev run says so");
        check(SessionName.DEVELOPMENT.equals(SessionName.of("   ", true)), "and blank counts as absent");
        check(SessionName.EXTERNAL.equals(SessionName.of(null, false)), "somebody else's instance says so");
        check(!SessionName.of(null, false).toLowerCase(java.util.Locale.ROOT).contains("unknown"),
                "and the word Unknown appears nowhere");

        check(SessionName.fromLauncher("Profile"), "a name means the launcher is there");
        check(!SessionName.fromLauncher(null), "and no name means it is not");
        check(!SessionName.fromLauncher(""), "nor does an empty one");
        pass("a session is named for what started it");
    }

    static void theInterfaceScaleIsAChoiceAndNotANumber() {
        ClientPrefs prefs = new ClientPrefs();
        check(prefs.scaleIsAutomatic(), "automatic by default");
        check(prefs.highQualityUi(), "high quality rendering is on by default");
        // Auto sits a step under the full window-derived size: at 1.0 the Hub
        // read as oversized on a 1080p screen.
        check(Math.abs(prefs.uiScale() - 0.9f) < 0.001f, "which is 0.9 of the automatic scale");
        prefs.setScaleLabel("65%");
        check(Math.abs(prefs.uiScale() - 0.65f) < 0.001f, "and a compact 65% is on offer");
        prefs.setScaleLabel("Auto");
        check(!prefs.scaleChangedFromDefault(), "and a default is not written to the file");

        // Chosen by label throughout. The property under test is that a label
        // and a multiplier agree and survive the file; which index a label sits
        // at is an implementation detail, and asserting it here meant that
        // adding a scale option broke tests that had nothing to do with it.
        prefs.setScaleLabel("125%");
        check("125%".equals(prefs.scaleLabel()), "the label round-trips, got " + prefs.scaleLabel());
        check(Math.abs(prefs.uiScale() - 1.25f) < 0.001f, "and means what it says");
        check(prefs.scaleChangedFromDefault(), "a change is worth writing down");

        ClientPrefs read = new ClientPrefs();
        read.setScaleLabel("115%");
        int fromFile = read.scaleChoice();
        check("115%".equals(read.scaleLabel()), "a label from the file selects the choice");
        read.setScaleLabel("nonsense from a hand-edited file");
        check(read.scaleChoice() == fromFile, "and an unrecognised one changes nothing");
        pass("the interface scale is a choice and not a number");
    }

    // -----------------------------------------------------------------------
    // Runner
    // -----------------------------------------------------------------------

    private static FathomModule sample(String id) {
        return FathomModule.builder(id)
                .name(id)
                .description("A feature that exists for a test to look at.")
                .setting(new Setting.Toggle("x", "X", "", false))
                .build();
    }

    // -----------------------------------------------------------------------
    // The launch descriptor
    // -----------------------------------------------------------------------

    static void theLaunchDescriptorIsReadOrHonestlyAbsent() {
        // The exact shape `crates/launcher-core/src/session.rs` writes. Spelled
        // out rather than generated, because the point of the test is that the
        // two sides agree about a format neither can see the other write.
        String document =
                "{\n  \"schema\": 1,\n  \"profile\": \"bedwars\",\n"
                        + "  \"profileName\": \"Bedwars\",\n  \"minecraft\": \"1.8.9\",\n"
                        + "  \"loader\": \"Legacy Fabric\",\n  \"loaderVersion\": \"0.19.3\",\n"
                        + "  \"preset\": \"competitive\",\n  \"component\": \"0.3.0-hud\",\n"
                        + "  \"launcher\": \"1.2.3\"\n}\n";

        LaunchSession session = LaunchSession.parse(document);
        check(session.present(), "the descriptor was read");
        check("bedwars".equals(session.profileId()), "the id survived");
        check("Bedwars".equals(session.profileName()), "the name survived");
        check("1.8.9".equals(session.minecraft()), "the version survived");
        check("Legacy Fabric".equals(session.loader()), "the loader survived");
        check("0.19.3".equals(session.loaderVersion()), "the loader version survived");
        check("competitive".equals(session.preset()), "the preset survived");
        check("0.3.0-hud".equals(session.component()), "the component version survived");
        check("1.8.9 \u00b7 Legacy Fabric".equals(session.subtitle()),
                "the menu line: " + session.subtitle());

        // No file at all is the ordinary case for a game started any other way,
        // and it has to read as absent rather than as a failure.
        check(!LaunchSession.parse(null).present(), "null is absent");
        check(!LaunchSession.parse("").present(), "empty is absent");
        check(!LaunchSession.parse("{").present(), "a fragment is absent");
        check(LaunchSession.absent().subtitle().isEmpty(),
                "and an absent session has no line to draw");
        pass("the launch descriptor is read, or honestly absent");
    }

    static void aQuotedProfileNameSurvivesTheDescriptor() {
        // The launcher escapes it; this has to unescape it, or a profile called
        // He said "hi" truncates the document at its own name.
        String awkward =
                "{\n  \"schema\": 1,\n  \"profileName\": \"He said \\\"hi\\\"\",\n"
                        + "  \"minecraft\": \"1.8.9\"\n}\n";
        LaunchSession quoted = LaunchSession.parse(awkward);
        check(quoted.present(), "a quoted name is still a descriptor");
        check("He said \"hi\"".equals(quoted.profileName()),
                "and it came back whole: " + quoted.profileName());
        pass("a quoted profile name survives the descriptor");
    }

    static void aDescriptorFromAnotherSchemaIsIgnoredRatherThanGuessedAt() {
        // A launcher that knows something we do not. Reading half of it is how a
        // main menu ends up showing half a profile; absent is the honest answer,
        // and the interface already has a word for it.
        String future = "{\n  \"schema\": 2,\n  \"profileName\": \"Bedwars\"\n}\n";
        check(!LaunchSession.parse(future).present(), "a newer schema is not guessed at");

        String nameless = "{\n  \"schema\": 1,\n  \"minecraft\": \"1.8.9\"\n}\n";
        check(!LaunchSession.parse(nameless).present(),
                "a descriptor with nothing to show is the same as none");
        pass("a descriptor from another schema is ignored rather than guessed at");
    }

    static void theModuleLogKeepsWhatItSaidAndNoMore() {
        ModuleLog.clear();
        check(ModuleLog.lines().isEmpty(), "it starts empty");

        ModuleLog.say("first");
        ModuleLog.say("second");
        check(ModuleLog.size() == 2, "two lines");
        check("first".equals(ModuleLog.lines().get(0)), "oldest first");

        // Bounded, and that is a guarantee rather than a size estimate: a mod
        // using the public API can write here, and an unbounded log somebody
        // else can append to is a memory leak with an owner who did not mean it.
        for (int index = 0; index < ModuleLog.CAPACITY + 50; index++) {
            ModuleLog.say("line " + index);
        }
        check(ModuleLog.size() == ModuleLog.CAPACITY, "it stops at the cap");
        check(!ModuleLog.lines().contains("first"), "and the oldest fell off the front");
        check(ModuleLog.recent(3).size() == 3, "a tail is a tail");

        ModuleLog.clear();
        pass("the module log keeps what it said and no more");
    }

    private static File temporaryDirectory(String label) {
        File base = new File(System.getProperty("java.io.tmpdir"), "fathom-" + label + "-" + System.nanoTime());
        if (!base.mkdirs()) {
            throw new AssertionError("could not create " + base);
        }
        base.deleteOnExit();
        return base;
    }

    private static void writeFile(File file, String contents) {
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new AssertionError("could not create " + parent);
        }
        java.io.Writer writer = null;
        try {
            writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), "UTF-8");
            writer.write(contents);
        } catch (java.io.IOException problem) {
            throw new AssertionError("could not write " + file + ": " + problem);
        } finally {
            close(writer);
        }
        file.deleteOnExit();
    }

    private static String read(File file) {
        try {
            byte[] bytes = new byte[(int) file.length()];
            java.io.DataInputStream in = new java.io.DataInputStream(new java.io.FileInputStream(file));
            try {
                in.readFully(bytes);
            } finally {
                in.close();
            }
            return new String(bytes, "UTF-8");
        } catch (java.io.IOException problem) {
            throw new AssertionError("could not read " + file + ": " + problem);
        }
    }

    private static void close(java.io.Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (java.io.IOException ignored) {
                // The assertion above has already run if anything went wrong.
            }
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void expectThrows(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected " + what + " to be refused");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  ok   " + name);
    }

    private static void report() {
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println(passed + " passed, 0 failed");
        } else {
            for (String failure : failures) {
                System.out.println("FAIL " + failure);
            }
            System.out.println(passed + " passed, " + failures.size() + " failed");
            System.exit(1);
        }
    }

    private FeatureTests() {}
}
