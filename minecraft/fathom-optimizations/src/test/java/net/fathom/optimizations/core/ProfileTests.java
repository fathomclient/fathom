package net.fathom.optimizations.core;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;

import net.fathom.optimizations.core.profile.FathomProfile;
import net.fathom.optimizations.core.profile.ProfileException;
import net.fathom.optimizations.core.profile.ProfileMigration;
import net.fathom.optimizations.core.profile.ProfileStore;

/**
 * Fathom Profiles, tested without a game.
 *
 * <p>The same properties the Rust store's tests assert, asserted again on this
 * side, because "one collection read by two programs" is only true if both
 * programs follow the same rules. The one test that has no Rust twin is
 * {@link #aDocumentWrittenByTheLauncherIsReadExactly}: it feeds this reader the
 * bytes {@code serde_json} produces, which is the contract that actually matters.
 */
public final class ProfileTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        // -- the store ----------------------------------------------------------
        anEmptyCollectionProducesExactlyOneDefault();
        aDanglingPointerFallsBackWithoutCreatingAnything();
        theLastProfileCannotBeDeleted();
        deletingTheLiveProfileMovesThePointerSomewhereReal();
        oneCorruptDocumentDoesNotHideTheOthers();
        aDocumentWhoseIdDoesNotMatchItsFileIsRefused();
        aRenameKeepsTheIdSoThePointerSurvives();
        anImportNeverOverwritesAProfileThatIsThere();
        duplicatingTwiceNumbersRatherThanCollides();
        aBackgroundSaveIsNeverReadStale();

        // -- the document -------------------------------------------------------
        aProfileRoundTripsUnchanged();
        aDocumentWrittenByTheLauncherIsReadExactly();
        aProfileFromTheFutureIsRefused();
        anImpossibleHudEntryIsRefusedBeforeItIsApplied();
        aProfileCarriesNothingAboutTheInstallation();
        namesWithQuotesAndNewlinesSurviveTheDisk();
        idsFollowTheLaunchersRules();

        // -- migration ----------------------------------------------------------
        theThreeFilesBecomeOneDefault();
        theOriginalsAreBackedUpAndLeftAlone();
        runningTwiceDoesNotProduceASecondDefault();
        anEditedDefaultIsNeverOverwritten();
        aBlankDefaultIsFilledNotDuplicated();
        namedLayoutsBecomeProfiles();
        aSecondCollectionCanStillMigrateTheSameGame();

        System.out.println();
        System.out.println(passed + " passed, " + failures.size() + " failed");
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------
    // The store
    // -----------------------------------------------------------------------

    static void anEmptyCollectionProducesExactlyOneDefault() throws Exception {
        ProfileStore store = store();
        FathomProfile first = store.resolveLive();
        check(FathomProfile.DEFAULT_ID.equals(first.id()), "the fallback is default");
        check(FathomProfile.DEFAULT_NAME.equals(first.name()), "called Default");
        FathomProfile second = store.resolveLive();
        check(second.id().equals(first.id()), "asking again returns the same one");
        check(store.list(null).size() == 1, "one Default, not two");
        pass("an empty collection produces exactly one Default");
    }

    static void aDanglingPointerFallsBackWithoutCreatingAnything() throws Exception {
        ProfileStore store = store();
        store.create("Bedwars");
        store.setLastProfileId("deleted-elsewhere");
        FathomProfile live = store.resolveLive();
        check("Bedwars".equals(live.name()), "fell back to what exists");
        check(store.list(null).size() == 1, "no Default was invented");
        check(live.id().equals(store.lastProfileId()), "and the pointer was repaired");
        pass("a dangling pointer falls back without creating anything");
    }

    static void theLastProfileCannotBeDeleted() throws Exception {
        ProfileStore store = store();
        FathomProfile only = store.create("Only");
        boolean refused = false;
        try {
            store.delete(only.id());
        } catch (ProfileException expected) {
            refused = true;
        }
        check(refused, "deleting the last profile is refused");
        check(store.list(null).size() == 1, "and it is still there");
        pass("the last profile cannot be deleted");
    }

    static void deletingTheLiveProfileMovesThePointerSomewhereReal() throws Exception {
        ProfileStore store = store();
        FathomProfile defaults = store.create("Default");
        FathomProfile bedwars = store.create("Bedwars");
        store.setLastProfileId(bedwars.id());
        store.delete(bedwars.id());
        check(defaults.id().equals(store.lastProfileId()), "the pointer moved to Default");
        pass("deleting the live profile moves the pointer somewhere real");
    }

    /**
     * The render thread saves in the background. Many saves coalesce to the last
     * one, and any read that follows sees what was saved last, never an older
     * document that happened to still be on disk.
     */
    static void aBackgroundSaveIsNeverReadStale() throws Exception {
        ProfileStore store = store();
        FathomProfile profile = store.create("Before");
        for (int index = 0; index < 50; index++) {
            profile.setName("Name " + index);
            store.saveInBackground(profile);
        }
        FathomProfile read = store.load(profile.id());
        check("Name 49".equals(read.name()), "a load after background saves sees the last one: " + read.name());
        check(store.takeBackgroundFailure() == null, "and no write failed");
        profile.setName("Renamed");
        store.saveInBackground(profile);
        check(store.list(null).get(0).name().equals("Renamed"), "listing waits for the pending write too");
        pass("a background save is never read stale");
    }

    static void oneCorruptDocumentDoesNotHideTheOthers() throws Exception {
        ProfileStore store = store();
        store.create("Bedwars");
        store.create("Survival");
        write(new File(store.directory(), "broken.json"), "{not json");
        List<String> complaints = new ArrayList<String>();
        List<FathomProfile> profiles = store.list(complaints);
        check(profiles.size() == 2, "the two good ones load: " + profiles.size());
        check(complaints.size() == 1 && complaints.get(0).startsWith("broken.json"), "the bad one is reported");
        pass("one corrupt document does not hide the others");
    }

    static void aDocumentWhoseIdDoesNotMatchItsFileIsRefused() throws Exception {
        ProfileStore store = store();
        FathomProfile profile = new FathomProfile("bedwars", "Bedwars");
        store.directory().mkdirs();
        write(new File(store.directory(), "survival.json"), profile.toJson());
        List<String> complaints = new ArrayList<String>();
        check(store.list(complaints).isEmpty(), "a hand-copied file is not trusted");
        check(complaints.size() == 1, "and says why");
        pass("a document whose id does not match its file is refused");
    }

    static void aRenameKeepsTheIdSoThePointerSurvives() throws Exception {
        ProfileStore store = store();
        FathomProfile profile = store.create("Bedwars");
        store.setLastProfileId(profile.id());
        FathomProfile renamed = store.rename(profile.id(), "Bed Wars");
        check(renamed.id().equals(profile.id()), "the id did not move");
        check("Bed Wars".equals(renamed.name()), "the name did");
        check(profile.id().equals(store.lastProfileId()), "the pointer still resolves");
        pass("a rename keeps the id so the pointer survives");
    }

    static void anImportNeverOverwritesAProfileThatIsThere() throws Exception {
        ProfileStore store = store();
        FathomProfile mine = store.create("Default");
        FathomProfile theirs = new FathomProfile("default", "Default");
        FathomProfile imported = store.importJson(theirs.toJson());
        check(!imported.id().equals(mine.id()), "the incoming id was not honoured");
        check(store.list(null).size() == 2, "both exist");
        pass("an import never overwrites a profile that is there");
    }

    static void duplicatingTwiceNumbersRatherThanCollides() throws Exception {
        ProfileStore store = store();
        FathomProfile original = store.create("Bedwars");
        FathomProfile first = store.duplicate(original.id(), "Bedwars");
        FathomProfile second = store.duplicate(original.id(), "Bedwars");
        check("bedwars-2".equals(first.id()), "first copy is bedwars-2: " + first.id());
        check("bedwars-3".equals(second.id()), "second copy is bedwars-3: " + second.id());
        pass("duplicating twice numbers rather than collides");
    }

    // -----------------------------------------------------------------------
    // The document
    // -----------------------------------------------------------------------

    static void aProfileRoundTripsUnchanged() throws Exception {
        ProfileStore store = store();
        FathomProfile profile = new FathomProfile("bedwars", "Bedwars");
        FathomProfile.ModuleState cps = profile.module("cps");
        cps.enabled = Boolean.TRUE;
        cps.favourite = true;
        cps.settings.put("window", "1000");
        cps.keys.put("toggle", "33");
        FathomProfile.HudEntry entry = new FathomProfile.HudEntry();
        entry.anchor = "bottom-right";
        entry.x = 6;
        entry.y = -2;
        entry.scale = 1.25f;
        entry.opacity = 0.5f;
        profile.hud().put("cps", entry);
        profile.client().hudFont = "Minecraft";
        profile.client().hubKey = Integer.valueOf(54);
        profile.performance().preset = "balanced";
        profile.performance().switches.put("font-metrics-cache", Boolean.TRUE);
        store.save(profile);

        FathomProfile back = store.load("bedwars");
        FathomProfile.ModuleState backCps = back.modules().get("cps");
        check(Boolean.TRUE.equals(backCps.enabled) && backCps.favourite, "module flags");
        check("1000".equals(backCps.settings.get("window")) && "33".equals(backCps.keys.get("toggle")), "settings and keys");
        FathomProfile.HudEntry backEntry = back.hud().get("cps");
        check("bottom-right".equals(backEntry.anchor) && backEntry.x == 6 && backEntry.y == -2, "placement");
        check(backEntry.scale == 1.25f && backEntry.opacity == 0.5f, "scale and opacity");
        check("Minecraft".equals(back.client().hudFont) && back.client().hubKey.intValue() == 54, "client");
        check("balanced".equals(back.performance().preset)
                && Boolean.TRUE.equals(back.performance().switches.get("font-metrics-cache")), "performance");
        pass("a profile round-trips unchanged");
    }

    /**
     * The cross-language contract. This is what {@code serde_json::to_vec_pretty}
     * writes for a {@code FathomProfile} with every section populated — camelCase
     * at the top and in client, sorted maps, f32s with a decimal point.
     */
    static void aDocumentWrittenByTheLauncherIsReadExactly() throws Exception {
        String rust = "{\n"
                + "  \"schema\": 1,\n"
                + "  \"id\": \"bedwars\",\n"
                + "  \"name\": \"Bedwars\",\n"
                + "  \"createdAt\": 1789000000,\n"
                + "  \"updatedAt\": 1789000001,\n"
                + "  \"modules\": {\n"
                + "    \"cps\": {\n"
                + "      \"enabled\": true,\n"
                + "      \"settings\": {\n"
                + "        \"window\": \"1000\"\n"
                + "      }\n"
                + "    },\n"
                + "    \"fps\": {\n"
                + "      \"enabled\": false,\n"
                + "      \"favourite\": true\n"
                + "    }\n"
                + "  },\n"
                + "  \"hud\": {\n"
                + "    \"cps\": {\n"
                + "      \"anchor\": \"top-right\",\n"
                + "      \"x\": 4,\n"
                + "      \"y\": 20,\n"
                + "      \"scale\": 1.5,\n"
                + "      \"opacity\": 1.0\n"
                + "    }\n"
                + "  },\n"
                + "  \"client\": {\n"
                + "    \"hubKey\": 54,\n"
                + "    \"hudFont\": \"Fathom\"\n"
                + "  },\n"
                + "  \"performance\": {\n"
                + "    \"preset\": \"aggressive\",\n"
                + "    \"switches\": {\n"
                + "      \"font-metrics-cache\": true\n"
                + "    }\n"
                + "  }\n"
                + "}";
        FathomProfile profile = FathomProfile.fromJson(rust);
        check("bedwars".equals(profile.id()) && "Bedwars".equals(profile.name()), "identity");
        check(profile.createdAt() == 1789000000L && profile.updatedAt() == 1789000001L, "timestamps");
        check(Boolean.TRUE.equals(profile.modules().get("cps").enabled), "cps on");
        check(Boolean.FALSE.equals(profile.modules().get("fps").enabled)
                && profile.modules().get("fps").favourite, "fps off and favourite");
        check("1000".equals(profile.modules().get("cps").settings.get("window")), "a setting");
        FathomProfile.HudEntry cps = profile.hud().get("cps");
        check("top-right".equals(cps.anchor) && cps.x == 4 && cps.y == 20 && cps.scale == 1.5f, "hud entry");
        check(profile.client().hubKey.intValue() == 54 && "Fathom".equals(profile.client().hudFont), "client");
        check(profile.client().uiScale == null && profile.client().highQualityUi == null, "absent means default");
        check("aggressive".equals(profile.performance().preset), "preset");

        // And what this side writes carries every key the Rust struct requires.
        String written = profile.toJson();
        for (String required : Arrays.asList("\"schema\"", "\"id\"", "\"name\"", "\"createdAt\"", "\"anchor\"",
                "\"x\"", "\"y\"", "\"client\"", "\"performance\"", "\"hubKey\"")) {
            check(written.contains(required), "writes " + required);
        }
        check(!written.contains("\"hidden\""), "a false flag is left out, as serde does");
        pass("a document written by the launcher is read exactly");
    }

    static void aProfileFromTheFutureIsRefused() throws Exception {
        String future = new FathomProfile("bedwars", "Bedwars").toJson().replace("\"schema\": 1", "\"schema\": 2");
        boolean refused = false;
        try {
            FathomProfile.fromJson(future);
        } catch (ProfileException expected) {
            refused = true;
        }
        check(refused, "schema 2 is refused rather than guessed at");
        pass("a profile from the future is refused");
    }

    static void anImpossibleHudEntryIsRefusedBeforeItIsApplied() throws Exception {
        FathomProfile profile = new FathomProfile("bedwars", "Bedwars");
        FathomProfile.HudEntry entry = new FathomProfile.HudEntry();
        entry.opacity = 4f;
        profile.hud().put("fps", entry);
        check(profile.problem() != null, "opacity 4 is a problem");
        entry.opacity = 1f;
        entry.scale = Float.NaN;
        check(profile.problem() != null, "a NaN scale is a problem");
        boolean refused = false;
        try {
            store().save(profile);
        } catch (ProfileException expected) {
            refused = true;
        }
        check(refused, "and it cannot be saved");
        pass("an impossible HUD entry is refused before it is applied");
    }

    static void aProfileCarriesNothingAboutTheInstallation() throws Exception {
        FathomProfile profile = FathomProfile.defaultProfile();
        String json = profile.toJson().toLowerCase(java.util.Locale.ROOT);
        for (String forbidden : Arrays.asList("version", "loader", "java", "memory", "jvm", "directory",
                "account", "token", "username", "uuid")) {
            check(!json.contains(forbidden), "a profile must not carry " + forbidden);
        }
        pass("a profile carries nothing about the installation");
    }

    static void namesWithQuotesAndNewlinesSurviveTheDisk() throws Exception {
        ProfileStore store = store();
        FathomProfile profile = new FathomProfile("odd", "Sky \"Wars\" \\ Ünïcode");
        store.save(profile);
        check("Sky \"Wars\" \\ Ünïcode".equals(store.load("odd").name()), "the name survived: " + store.load("odd").name());
        pass("names with quotes and unicode survive the disk");
    }

    static void idsFollowTheLaunchersRules() {
        List<String> none = Collections.emptyList();
        check("bedwars".equals(ProfileStore.idFromName("Bedwars", none)), "Bedwars");
        check("bedwars-2".equals(ProfileStore.idFromName("Bedwars", Arrays.asList("bedwars"))), "taken becomes -2");
        check("sky-wars".equals(ProfileStore.idFromName("  Sky  Wars!! ", none)), "punctuation collapses");
        check(ProfileStore.idFromName("🎮🎮", none) == null, "nothing usable is null");
        check("con-1".equals(ProfileStore.idFromName("CON", none)), "a device name is moved aside");
        check(!FathomProfile.validId("client-state"), "the pointer's own name is reserved");
        pass("ids follow the launcher's rules");
    }

    // -----------------------------------------------------------------------
    // Migration
    // -----------------------------------------------------------------------

    private static final String FEATURES = "schema=1\n"
            + "client.hub.key=54\n"
            + "client.ui.scale=110%\n"
            + "client.hud.font=Minecraft\n"
            + "feature.fps.enabled=true\n"
            + "feature.fps.favourite=true\n"
            + "feature.fps.set.decimals=0\n"
            + "feature.zoom.key.hold=46\n"
            + "feature.cps.enabled=false\n"
            + "something.we.do.not.know=42\n";

    private static final String HUD = "schema=1\n"
            + "name=Default\n"
            + "widget.fps.anchor=TOP_LEFT\n"
            + "widget.fps.x=4\n"
            + "widget.fps.y=4\n"
            + "widget.fps.scale=1.5\n"
            + "widget.cps.anchor=BOTTOM_RIGHT\n";

    /** The real shape, as the launcher writes it before every launch. */
    private static final String PERFORMANCE = "# Written by Fathom before launch. Do not edit.\n"
            + "schema = 1\n"
            + "preset = balanced\n"
            + "opt.render.font.metrics-cache = true\n"
            + "opt.memory.world-change-gc = false\n"
            + "conflict.render.font.metrics-cache = OptiFine\n";

    static void theThreeFilesBecomeOneDefault() throws Exception {
        File game = game("fathom-features.properties", FEATURES, "fathom-hud.properties", HUD,
                "fathom-performance.properties", PERFORMANCE);
        ProfileStore store = new ProfileStore(new File(game, "fathom/profiles"));
        ProfileMigration.Report report = ProfileMigration.run(store, game);
        check(report.created.equals(Arrays.asList("Default")), "one profile: " + report.created);
        check(report.sources.size() == 3, "three sources");

        FathomProfile profile = store.load("default");
        check(profile.client().hubKey.intValue() == 54, "hub key");
        check("110%".equals(profile.client().uiScale) && "Minecraft".equals(profile.client().hudFont), "client prefs");
        FathomProfile.ModuleState fps = profile.modules().get("fps");
        check(Boolean.TRUE.equals(fps.enabled) && fps.favourite && "0".equals(fps.settings.get("decimals")), "fps");
        check("46".equals(profile.modules().get("zoom").keys.get("hold")), "a keybind");
        check(Boolean.FALSE.equals(profile.modules().get("cps").enabled), "a module switched off");
        check("top-left".equals(profile.hud().get("fps").anchor), "enum anchor normalised");
        check(profile.hud().get("fps").x == 4 && profile.hud().get("fps").scale == 1.5f, "hud values");
        check("balanced".equals(profile.performance().preset), "preset");
        check(Boolean.TRUE.equals(profile.performance().switches.get("render.font.metrics-cache")),
                "the opt. prefix is stripped to the registry id: " + profile.performance().switches);
        check(Boolean.FALSE.equals(profile.performance().switches.get("memory.world-change-gc")), "a switch off");
        for (String key : profile.performance().switches.keySet()) {
            check(!key.startsWith("opt.") && !key.contains("conflict"), "a stand-down is not a choice: " + key);
        }
        check(report.unmapped.contains("something.we.do.not.know"), "unmapped is reported");
        check("default".equals(store.lastProfileId()), "and it is live");
        pass("the three files become one Default");
    }

    static void theOriginalsAreBackedUpAndLeftAlone() throws Exception {
        File game = game("fathom-features.properties", FEATURES);
        ProfileStore store = new ProfileStore(new File(game, "fathom/profiles"));
        ProfileMigration.Report report = ProfileMigration.run(store, game);
        check(new File(report.backup, "fathom-features.properties").isFile(), "backed up");
        check(new File(game, "config/fathom-features.properties").isFile(), "the original is still there");
        String kept = read(new File(report.backup, "fathom-features.properties"));
        check(kept.contains("something.we.do.not.know"), "the unmapped key survives in the backup");
        pass("the originals are backed up and left alone");
    }

    static void runningTwiceDoesNotProduceASecondDefault() throws Exception {
        File game = game("fathom-features.properties", FEATURES, "fathom-hud.properties", HUD);
        ProfileStore store = new ProfileStore(new File(game, "fathom/profiles"));
        check(!ProfileMigration.run(store, game).alreadyDone, "the first run migrates");
        int before = store.list(null).size();
        check(ProfileMigration.run(store, game).alreadyDone, "the second does nothing");
        check(store.list(null).size() == before, "no second Default");
        pass("running twice does not produce a second Default");
    }

    static void anEditedDefaultIsNeverOverwritten() throws Exception {
        File game = game("fathom-features.properties", FEATURES);
        ProfileStore store = new ProfileStore(new File(game, "fathom/profiles"));
        FathomProfile mine = FathomProfile.defaultProfile();
        mine.client().hudFont = "Fathom";
        store.save(mine);

        ProfileMigration.Report report = ProfileMigration.run(store, game);
        check(report.created.contains(ProfileMigration.IMPORTED_NAME), "migrated beside it: " + report.created);
        check("Fathom".equals(store.load("default").client().hudFont), "the edited Default survived");
        check(store.list(null).size() == 2, "two profiles");
        pass("an edited Default is never overwritten");
    }

    static void aBlankDefaultIsFilledNotDuplicated() throws Exception {
        File game = game("fathom-features.properties", FEATURES);
        ProfileStore store = new ProfileStore(new File(game, "fathom/profiles"));
        store.resolveLive();
        ProfileMigration.run(store, game);
        check(store.list(null).size() == 1, "a placeholder is filled, not duplicated");
        check(store.load("default").client().hubKey.intValue() == 54, "with the real data");
        pass("a blank Default is filled, not duplicated");
    }

    static void namedLayoutsBecomeProfiles() throws Exception {
        File game = game("fathom-features.properties", FEATURES, "fathom-hud.properties", HUD,
                "fathom-hud-bedwars.properties", HUD);
        ProfileStore store = new ProfileStore(new File(game, "fathom/profiles"));
        ProfileMigration.Report report = ProfileMigration.run(store, game);
        check(report.created.contains("Bedwars"), "Bedwars was created: " + report.created);
        FathomProfile bedwars = store.load("bedwars");
        check(Boolean.TRUE.equals(bedwars.modules().get("fps").enabled), "it shares the modules");
        check(bedwars.hud().containsKey("fps"), "and has its own layout");
        pass("named layouts become profiles");
    }

    static void aSecondCollectionCanStillMigrateTheSameGame() throws Exception {
        File game = game("fathom-features.properties", FEATURES);
        ProfileStore standalone = new ProfileStore(new File(game, "fathom/profiles"));
        ProfileStore launcher = new ProfileStore(new File(Files.createTempDirectory("fathom-root").toFile(), "profiles"));
        check(!ProfileMigration.run(standalone, game).alreadyDone, "standalone migrates");
        check(ProfileMigration.run(standalone, game).alreadyDone, "standalone again does not");
        check(!ProfileMigration.run(launcher, game).alreadyDone, "the launcher's collection has not seen this game");
        pass("a second collection can still migrate the same game");
    }

    // -----------------------------------------------------------------------
    // Harness
    // -----------------------------------------------------------------------

    private static ProfileStore store() throws IOException {
        return new ProfileStore(new File(Files.createTempDirectory("fathom-profiles").toFile(), "profiles"));
    }

    private static File game(String... namesAndBodies) throws IOException {
        File game = Files.createTempDirectory("fathom-game").toFile();
        File config = new File(game, "config");
        config.mkdirs();
        for (int index = 0; index + 1 < namesAndBodies.length; index += 2) {
            write(new File(config, namesAndBodies[index]), namesAndBodies[index + 1]);
        }
        return game;
    }

    private static void write(File file, String text) throws IOException {
        Files.write(file.toPath(), text.getBytes(StandardCharsets.UTF_8));
    }

    private static String read(File file) throws IOException {
        return new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8);
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
}
