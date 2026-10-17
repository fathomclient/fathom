package net.fathom.optimizations.core;

import java.io.File;

import net.fathom.optimizations.core.input.HubKeyPolicy;
import net.fathom.optimizations.core.input.KeybindCaptureState;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.module.ModuleStore;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.core.profile.FathomProfile;
import net.fathom.optimizations.core.profile.ProfileException;
import net.fathom.optimizations.core.profile.ProfileMerge;

/**
 * The public-beta baseline: the shared fixes the module audit asked for, and
 * the modules it found missing.
 *
 * <p>Plain {@code main}, like every suite in this module.
 */
public final class BaselineTests {

    private static int passed;

    public static void main(String[] args) throws Exception {
        // -- input -------------------------------------------------------------
        theHubKeyNeverActsOnSomethingBeingTyped();
        theHubKeyCannotBeAPrimaryMouseButton();

        // -- persistence ---------------------------------------------------------
        theMenuBackgroundIsPartOfAProfile();
        unknownClientFieldsSurviveARoundTrip();
        aSaveKeepsWhatThisBuildDoesNotKnow();
        twoWindowsKeepEachOthersEdits();
        aLoneWindowWritesExactlyItsOwnState();
        theLegacyStoreWritesWholeFiles();
        aLateModuleTakesWhatTheProfileSavedForIt();

        // -- controls ------------------------------------------------------------
        aColourCanBeTypedAndAlwaysPutBack();

        // -- modules -------------------------------------------------------------
        theBaselineModulesAreAllThere();
        theScoreboardStartsLookingLikeVanilla();
        theSidebarIsLaidOutAsVanillaLaysItOut();
        fullbrightNeverDimsAndTimeChangerShowsTheChosenHour();
        theCompassPointsTheWayYouFace();
        keystrokesPrintTheBoundKey();
        aPermanentEffectHasNoTimer();
        chatTimestampsAndRepeats();
        zoomCanBeHeldOrToggled();
        everyHudModuleHasVisibilityRules();
        armourAndPotionIconsHaveASize();

        System.out.println();
        System.out.println("BaselineTests: " + passed + " passed");
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    static void theHubKeyNeverActsOnSomethingBeingTyped() {
        check(HubKeyPolicy.decide(HubKeyPolicy.Screen.NONE, false) == HubKeyPolicy.Action.OPEN, "in game it opens");
        check(HubKeyPolicy.decide(HubKeyPolicy.Screen.MENU, false) == HubKeyPolicy.Action.OPEN,
                "over a plain menu it opens");
        check(HubKeyPolicy.decide(HubKeyPolicy.Screen.TEXT_INPUT, false) == HubKeyPolicy.Action.IGNORE,
                "in chat it types a capital instead");
        check(HubKeyPolicy.decide(HubKeyPolicy.Screen.CONTAINER, false) == HubKeyPolicy.Action.IGNORE,
                "over a chest it does nothing");
        check(HubKeyPolicy.decide(HubKeyPolicy.Screen.HUB, false) == HubKeyPolicy.Action.CLOSE,
                "in the idle Hub it closes");
        check(HubKeyPolicy.decide(HubKeyPolicy.Screen.HUB, true) == HubKeyPolicy.Action.IGNORE,
                "in the Hub's search box it types");
        check(HubKeyPolicy.decide(HubKeyPolicy.Screen.OTHER_FATHOM, false) == HubKeyPolicy.Action.IGNORE,
                "another Fathom screen keeps its keys");
        check(HubKeyPolicy.decide(null, false) == HubKeyPolicy.Action.IGNORE, "an unknown screen is left alone");
        pass("the Hub key never acts on something being typed");
    }

    static void theHubKeyCannotBeAPrimaryMouseButton() {
        Keybind hub = new Keybind("hub", "Open Fathom Hub", 54).withoutPrimaryButtons();
        check(!hub.accepts(Keybind.mouse(0)) && !hub.accepts(Keybind.mouse(1)), "left and right click refused");
        check(hub.accepts(Keybind.mouse(2)) && hub.accepts(Keybind.mouse(3)) && hub.accepts(57),
                "middle, side buttons and keys accepted");

        KeybindCaptureState capture = new KeybindCaptureState();
        capture.begin(hub);
        check(capture.mouse(1) == KeybindCaptureState.Outcome.NONE && capture.active(),
                "a right click keeps listening rather than binding");
        check(capture.mouse(3) == KeybindCaptureState.Outcome.BOUND && hub.key() == Keybind.mouse(3),
                "a side button binds");

        Keybind zoom = new Keybind("zoom", "Zoom", 46);
        capture.begin(zoom);
        check(capture.mouse(1) == KeybindCaptureState.Outcome.BOUND, "other bindings may still take right click");

        // A profile that says otherwise -- edited by hand, or from an older build -- is not obeyed.
        ProfileRig rig = new ProfileRig();
        FathomProfile profile = rig.capture("hub");
        profile.client().hubKey = Integer.valueOf(Keybind.mouse(1));
        ProfileRig other = new ProfileRig();
        other.hub.set(56);
        other.apply(profile);
        check(other.hub.key() == 54, "a stored right-click Hub key falls back to Right Shift");
        pass("the Hub key cannot be a primary mouse button");
    }

    // -----------------------------------------------------------------------
    // Persistence
    // -----------------------------------------------------------------------

    static void theMenuBackgroundIsPartOfAProfile() throws ProfileException {
        ProfileRig rig = new ProfileRig();
        rig.prefs.setMenuBackgroundLabel("Off");
        FathomProfile captured = rig.capture("menu");
        check("Off".equals(captured.client().menuBackground), "captured");
        FathomProfile read = FathomProfile.fromJson(captured.toJson());
        check("Off".equals(read.client().menuBackground), "written and read");
        ProfileRig fresh = new ProfileRig();
        fresh.apply(read);
        check("Off".equals(fresh.prefs.menuBackgroundLabel()), "applied");
        fresh.apply(new ProfileRig().capture("plain"));
        check("High".equals(fresh.prefs.menuBackgroundLabel()), "and a profile without it puts the default back");
        pass("the menu background is part of a profile");
    }

    static void unknownClientFieldsSurviveARoundTrip() throws ProfileException {
        String json = "{\"schema\":1,\"id\":\"future\",\"name\":\"Future\",\"client\":{\"hubKey\":56,"
                + "\"cursorTrail\":\"Sparkles\",\"panel\":{\"width\":3}},\"performance\":{}}";
        FathomProfile profile = FathomProfile.fromJson(json);
        check(profile.client().hubKey.intValue() == 56, "known fields are read");
        check("Sparkles".equals(profile.client().extra.get("cursorTrail")), "an unknown field is kept");
        FathomProfile again = FathomProfile.fromJson(profile.toJson());
        check("Sparkles".equals(again.client().extra.get("cursorTrail")), "and written back");
        Object panel = again.client().extra.get("panel");
        check(panel instanceof java.util.Map
                && ((Number) ((java.util.Map<?, ?>) panel).get("width")).intValue() == 3, "nested values included");
        pass("unknown client fields survive a round trip");
    }

    static void aSaveKeepsWhatThisBuildDoesNotKnow() throws ProfileException {
        FathomProfile stored = FathomProfile.fromJson("{\"schema\":1,\"id\":\"keep\",\"name\":\"Keep\","
                + "\"modules\":{\"someones-mod\":{\"enabled\":true,\"settings\":{\"x\":\"1\"}}},"
                + "\"hud\":{\"someones-widget\":{\"anchor\":\"top-left\",\"x\":3,\"y\":4,\"scale\":1,\"opacity\":1}},"
                + "\"client\":{\"cursorTrail\":\"Sparkles\"},"
                + "\"performance\":{\"switches\":{\"from-the-future\":true}}}");
        ProfileRig rig = new ProfileRig();
        rig.apply(stored);
        rig.features.find("fps").setEnabled(false);
        FathomProfile saved = net.fathom.optimizations.core.profile.ProfileSwitch.capture(
                stored, rig, rig.switches, rig.optimizations, rig.baseline);
        check(saved.modules().containsKey("someones-mod")
                && "1".equals(saved.modules().get("someones-mod").settings.get("x")), "an uninstalled mod's module");
        check(saved.hud().containsKey("someones-widget"), "its widget's place");
        check(Boolean.TRUE.equals(saved.performance().switches.get("from-the-future")), "an unknown switch");
        check("Sparkles".equals(saved.client().extra.get("cursorTrail")), "an unknown preference");
        check(Boolean.FALSE.equals(saved.modules().get("fps").enabled), "alongside this client's own edit");
        pass("a save keeps what this build does not know");
    }

    static void twoWindowsKeepEachOthersEdits() {
        // Both windows start from the same file.
        ProfileRig start = new ProfileRig();
        FathomProfile file = start.capture("shared");

        // Window A changes the FPS format; window B switches Clock on and moves it.
        ProfileRig a = new ProfileRig();
        a.apply(file);
        FathomProfile aBase = a.capture("shared");
        ((Setting.Choice) a.features.find("fps").setting("format")).set("FPS: 120");

        ProfileRig b = new ProfileRig();
        b.apply(file);
        FathomProfile bBase = b.capture("shared");
        b.features.find("clock").setEnabled(true);
        b.place("clock", net.fathom.optimizations.core.hud.Placement.at(
                net.fathom.optimizations.core.hud.Anchor.BOTTOM_RIGHT, 2, 2));

        // A saves first, then B saves over a file A has changed.
        FathomProfile afterA = ProfileMerge.merge(aBase, a.capture("shared"), file);
        FathomProfile afterB = ProfileMerge.merge(bBase, b.capture("shared"), afterA);

        ProfileRig restarted = new ProfileRig();
        restarted.apply(afterB);
        check("FPS: 120".equals(restarted.features.find("fps").choice("format", "")), "A's edit survived B's save");
        check(restarted.features.find("clock").enabled(), "B's switch is there");
        check(restarted.layout().get("clock", null).anchor() == net.fathom.optimizations.core.hud.Anchor.BOTTOM_RIGHT,
                "and B's widget position");

        // The same setting changed in both: the later save wins, nothing else moves.
        ((Setting.Choice) b.features.find("fps").setting("format")).set("120");
        FathomProfile bBase2 = b.capture("shared");
        ((Setting.Choice) b.features.find("fps").setting("format")).set("Custom");
        FathomProfile last = ProfileMerge.merge(bBase2, b.capture("shared"), afterB);
        check("Custom".equals(last.modules().get("fps").settings.get("format")), "the later save wins a clash");

        // A value put back to its default is removed, not left at the other window's.
        ((Setting.Choice) b.features.find("fps").setting("format")).set("120 FPS");
        FathomProfile reset = ProfileMerge.merge(b.capture("shared").duplicate("shared", "shared"),
                b.capture("shared"), last);
        check("Custom".equals(reset.modules().get("fps").settings.get("format")),
                "an unchanged value keeps the file's");
        FathomProfile resetBase = new ProfileRig().capture("shared");
        resetBase.module("fps").settings.put("format", "Custom");
        FathomProfile removed = ProfileMerge.merge(resetBase, b.capture("shared"), last);
        check(!removed.modules().containsKey("fps") || !removed.modules().get("fps").settings.containsKey("format"),
                "a value this window reset is reset in the file");
        pass("two windows keep each other's edits");
    }

    static void aLoneWindowWritesExactlyItsOwnState() {
        ProfileRig rig = new ProfileRig();
        FathomProfile base = rig.capture("alone");
        rig.features.find("keystrokes").setEnabled(true);
        ((Setting.Whole) rig.features.find("keystrokes").setting("key.size")).set(24);
        rig.hub.set(57);
        FathomProfile mine = rig.capture("alone");
        FathomProfile merged = ProfileMerge.merge(base, mine, base);
        check(stripTimes(merged.toJson()).equals(stripTimes(mine.toJson())),
                "with nobody else writing, the merge is the client");
        pass("a lone window writes exactly its own state");
    }

    static void theLegacyStoreWritesWholeFiles() throws Exception {
        File directory = temporaryDirectory("legacy-store");
        ModuleRegistry registry = ModuleRegistry.standard();
        registry.find("cps").setEnabled(true);
        new ModuleStore().save(directory, registry);
        File config = new File(directory, ModuleStore.RELATIVE_PATH);
        File[] siblings = config.getParentFile().listFiles();
        check(siblings != null && siblings.length == 1, "no temporary file left beside it");
        ModuleRegistry read = ModuleRegistry.standard();
        new ModuleStore().load(directory, read);
        check(read.find("cps").enabled(), "and it reads back");

        java.nio.file.Files.write(config.toPath(),
                "client.hub.key=-99\n".getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Keybind hub = new Keybind("hub", "Open Fathom Hub", 54).withoutPrimaryButtons();
        ModuleStore store = new ModuleStore();
        store.loadClient(directory, hub, null);
        check(hub.key() == 54 && !store.complaints().isEmpty(), "a right-click Hub key in an old file is refused");
        pass("the legacy store writes whole files");
    }

    static void aLateModuleTakesWhatTheProfileSavedForIt() throws ProfileException {
        // A mod that registers after the live profile was applied.
        FathomProfile stored = FathomProfile.fromJson("{\"schema\":1,\"id\":\"late\",\"name\":\"Late\","
                + "\"modules\":{\"late-mod\":{\"enabled\":true,\"settings\":{\"size\":\"7\"},\"keys\":{\"go\":\"34\"}}},"
                + "\"hud\":{\"late-widget\":{\"anchor\":\"bottom-right\",\"x\":9,\"y\":8,\"scale\":1,\"opacity\":1}}}");
        ProfileRig rig = new ProfileRig();
        rig.apply(stored);

        net.fathom.optimizations.core.module.FathomModule late =
                net.fathom.optimizations.core.module.FathomModule.builder("late-mod")
                        .name("Late mod")
                        .description("Registered after Fathom.")
                        .widget("late-widget")
                        .keybind(new Keybind("go", "Go", 0))
                        .setting(new Setting.Whole("size", "Size", "", 3, 1, 9, 1, ""))
                        .build();
        check(rig.features.register(late), "registered");
        check(rig.widgets.add(net.fathom.optimizations.core.hud.Widget.of("late-widget", "Late", "A late widget.",
                net.fathom.optimizations.core.hud.HudRegistry.CATEGORY_MODS,
                net.fathom.optimizations.core.hud.Sensitivity.SAFE,
                net.fathom.optimizations.core.hud.Placement.at(net.fathom.optimizations.core.hud.Anchor.TOP_LEFT, 4, 4))),
                "its widget is added to the registry the HUD draws from");
        check(!rig.widgets.add(net.fathom.optimizations.core.hud.Widget.of("fps", "FPS", "Taken.",
                "Mods", net.fathom.optimizations.core.hud.Sensitivity.SAFE,
                net.fathom.optimizations.core.hud.Placement.at(net.fathom.optimizations.core.hud.Anchor.TOP_LEFT, 0, 0))),
                "a taken widget id is refused");

        net.fathom.optimizations.core.profile.ProfileSwitch.adopt(stored, rig, late, new java.util.ArrayList<String>());
        check(late.enabled(), "it is switched on as the profile says");
        check(Math.round(late.number("size", 0)) == 7, "with its saved setting");
        check(late.keybinds().get(0).key() == 34, "and its saved key");
        check(rig.layout().get("late-widget", null).anchor() == net.fathom.optimizations.core.hud.Anchor.BOTTOM_RIGHT,
                "and its widget where it was left");
        pass("a late module takes what the profile saved for it");
    }

    static void aColourCanBeTypedAndAlwaysPutBack() {
        check(Setting.Colour.parseTyped("#1a2B3c").intValue() == 0x1A2B3C, "six digits, with a hash");
        check(Setting.Colour.parseTyped("F80").intValue() == 0xFF8800, "three digits stand for six");
        check(Setting.Colour.parseTyped("12345") == null && Setting.Colour.parseTyped("GGGGGG") == null
                && Setting.Colour.parseTyped(null) == null, "anything else is refused");
        Setting.Colour fill = new Setting.Colour("fill", "Fill", "", 0x1A1A1A);
        fill.set(0xFFFFFF);
        check(fill.fallback() == 0x1A1A1A, "the shipped colour is still known after a change");
        pass("a colour can be typed and always put back");
    }

    static void theBaselineModulesAreAllThere() {
        ModuleRegistry registry = ModuleRegistry.standard();
        String[] expected = {"zoom", "toggle-sprint", "keystrokes", "cps", "fps", "coordinates", "armour", "potions",
            "crosshair", "scoreboard", "fullbright", "time-changer", "ping", "direction", "chat"};
        for (String id : expected) {
            check(registry.find(id) != null, id + " is missing");
            check(registry.find(id).switchable(), id + " has no switch");
            check(!registry.find(id).defaultEnabled() || "fps".equals(id) || "coordinates".equals(id),
                    id + " is on out of the box");
        }
        check(registry.find("fullbright").keybinds().get(0).key() == Keybind.NONE
                && registry.find("time-changer").keybinds().get(0).key() == Keybind.NONE,
                "toggle keys start unbound");
        pass("the baseline modules are all there");
    }

    static void theScoreboardStartsLookingLikeVanilla() {
        net.fathom.optimizations.core.module.FathomModule board = ModuleRegistry.standard().find("scoreboard");
        check(board.colour(net.fathom.optimizations.core.module.HudStyle.TEXT_COLOUR, 0) == 0xFFFFFF, "white text");
        check(!board.toggle(net.fathom.optimizations.core.module.HudStyle.TEXT_SHADOW, true), "no shadow");
        check("None".equals(board.choice(net.fathom.optimizations.core.module.HudStyle.BACKGROUND, "")), "no plate");
        check(Math.round(board.number(net.fathom.optimizations.core.module.HudStyle.PADDING, 9)) == 0, "no padding");
        check(Math.round(board.number("rows", 0)) == 100 && board.toggle("numbers", false)
                && board.toggle("title", false) && !board.toggle("hide", true), "vanilla's bands, numbers and title");
        net.fathom.optimizations.core.hud.Placement start =
                net.fathom.optimizations.core.hud.HudRegistry.standard().find("scoreboard").defaultPlacement();
        check(start.anchor() == net.fathom.optimizations.core.hud.Anchor.MIDDLE_RIGHT && start.offsetX() == 1,
                "on the right edge, a pixel in, as vanilla's is");
        pass("the scoreboard starts looking like vanilla");
    }

    static void theSidebarIsLaidOutAsVanillaLaysItOut() {
        // Vanilla: a band two pixels past the text on the left; widest line
        // plus four; nine pixels a row; a title row and a one-pixel rule.
        check(net.fathom.optimizations.core.hud.Sidebar.width(60, 0) == 64, "width");
        check(net.fathom.optimizations.core.hud.Sidebar.height(5, 9, true, 0) == 55, "height with a title");
        check(net.fathom.optimizations.core.hud.Sidebar.height(5, 9, false, 0) == 45, "height without");
        check(net.fathom.optimizations.core.hud.Sidebar.hidden("#hidden") && !net.fathom.optimizations.core.hud.Sidebar
                .hidden("Kills"), "names starting with # are hidden");
        check(net.fathom.optimizations.core.hud.Sidebar.bandAlpha(0x50, 100, 255) == 0x50, "vanilla's band at 100%");
        check(net.fathom.optimizations.core.hud.Sidebar.bandAlpha(0x50, 0, 255) == 0, "none at 0%");
        pass("the sidebar is laid out as vanilla lays it out");
    }

    static void fullbrightNeverDimsAndTimeChangerShowsTheChosenHour() {
        check(net.fathom.optimizations.core.visual.Lighting.gamma(1000) == 10f, "1000% is a gamma of ten");
        check(net.fathom.optimizations.core.visual.Lighting.gamma(10) == 1f, "and never below vanilla's brightest");
        check(net.fathom.optimizations.core.visual.Lighting.ticks(6, 0) == 0L, "six in the morning is tick zero");
        check(net.fathom.optimizations.core.visual.Lighting.ticks(12, 0) == 6000L, "noon");
        check(net.fathom.optimizations.core.visual.Lighting.ticks(0, 0) == 18000L, "midnight");
        check(net.fathom.optimizations.core.visual.Lighting.ticks("Noon", 3) == 6000L, "a preset ignores the hour");
        check(net.fathom.optimizations.core.visual.Lighting.ticks("Custom", 21) == 15000L, "custom uses it");
        check(net.fathom.optimizations.core.visual.Lighting.ticks("Dusk", 0) == 12500L, "dusk is the sun on the horizon");
        pass("fullbright never dims, and time changer shows the chosen hour");
    }

    static void theCompassPointsTheWayYouFace() {
        check(net.fathom.optimizations.core.hud.Compass.heading(0f) == 180.0, "yaw 0 faces south");
        check(net.fathom.optimizations.core.hud.Compass.heading(90f) == 270.0, "yaw 90 faces west");
        check(net.fathom.optimizations.core.hud.Compass.heading(-90f) == 90.0, "yaw -90 faces east");
        check("N".equals(net.fathom.optimizations.core.hud.Compass.name(0.0))
                && "NE".equals(net.fathom.optimizations.core.hud.Compass.name(44.0))
                && "W".equals(net.fathom.optimizations.core.hud.Compass.name(271.0)), "eight-way names");
        float[] xs = new float[net.fathom.optimizations.core.hud.Compass.MAX_MARKS];
        String[] labels = new String[xs.length];
        int count = net.fathom.optimizations.core.hud.Compass.layout(0.0, 180, 180, true, xs, labels);
        boolean northCentred = false;
        boolean eastRight = false;
        for (int index = 0; index < count; index++) {
            if ("N".equals(labels[index]) && xs[index] == 90f) {
                northCentred = true;
            }
            if ("E".equals(labels[index]) && xs[index] == 180f) {
                eastRight = true;
            }
            check(xs[index] >= 0f && xs[index] <= 180f, "every mark is on the strip");
        }
        check(northCentred && eastRight, "facing north: N in the middle, E at the right-hand end");
        pass("the compass points the way you face");
    }

    static void keystrokesPrintTheBoundKey() {
        check("W".equals(net.fathom.optimizations.core.hud.KeyLabels.shortName(17)), "W");
        check("Z".equals(net.fathom.optimizations.core.hud.KeyLabels.shortName(44)), "AZERTY's Z");
        check("SPACE".equals(net.fathom.optimizations.core.hud.KeyLabels.shortName(57)), "space");
        check("SHIFT".equals(net.fathom.optimizations.core.hud.KeyLabels.shortName(42)), "shift");
        check("LMB".equals(net.fathom.optimizations.core.hud.KeyLabels.shortName(Keybind.mouse(0)))
                && "RMB".equals(net.fathom.optimizations.core.hud.KeyLabels.shortName(Keybind.mouse(1)))
                && "M4".equals(net.fathom.optimizations.core.hud.KeyLabels.shortName(Keybind.mouse(3))), "mouse");
        pass("keystrokes print the bound key");
    }

    static void aPermanentEffectHasNoTimer() {
        check("**:**".equals(net.fathom.optimizations.core.hud.EffectTime.format(32767, false)), "the protocol's maximum");
        check("**:**".equals(net.fathom.optimizations.core.hud.EffectTime.format(100, true)), "an effect flagged as such");
        check("1:05".equals(net.fathom.optimizations.core.hud.EffectTime.format(65 * 20, false)), "an ordinary one");
        check(net.fathom.optimizations.core.hud.EffectTime.sortKey(32767, false)
                > net.fathom.optimizations.core.hud.EffectTime.sortKey(20 * 60 * 30, false),
                "and sorts after every timed one");
        pass("a permanent effect has no timer");
    }

    static void chatTimestampsAndRepeats() {
        java.util.TimeZone utc = java.util.TimeZone.getTimeZone("UTC");
        long at = 14L * 3600_000L + 5L * 60_000L + 9_000L;
        check("[14:05]".equals(net.fathom.optimizations.core.chat.ChatLines.stamp(at, false, false, utc)), "24 hour");
        check("[2:05:09 PM]".equals(net.fathom.optimizations.core.chat.ChatLines.stamp(at, true, true, utc)),
                "12 hour with seconds");
        net.fathom.optimizations.core.chat.ChatLines.Repeats repeats = new net.fathom.optimizations.core.chat.ChatLines.Repeats();
        check(repeats.offer("gg") == 1 && repeats.offer("gg") == 2 && repeats.offer("gg") == 3, "a run counts");
        check(repeats.offer("wp") == 1 && repeats.offer("gg") == 1, "anything in between breaks it");
        check("".equals(net.fathom.optimizations.core.chat.ChatLines.repeats(1))
                && " (x3)".equals(net.fathom.optimizations.core.chat.ChatLines.repeats(3)), "the suffix");
        pass("chat timestamps and repeats");
    }

    static void zoomCanBeHeldOrToggled() {
        net.fathom.optimizations.core.module.FathomModule zoom = ModuleRegistry.standard().find("zoom");
        check("Hold".equals(zoom.choice("mode", "")), "hold by default");
        check(((Setting.Choice) zoom.setting("mode")).parse("Toggle"), "toggle is an option");
        pass("zoom can be held or toggled");
    }

    static void everyHudModuleHasVisibilityRules() {
        for (net.fathom.optimizations.core.module.FathomModule module : ModuleRegistry.standard().all()) {
            if (!net.fathom.optimizations.core.module.HudStyle.declaredOn(module)) {
                continue;
            }
            check(module.setting(net.fathom.optimizations.core.module.HudStyle.HIDE_DEBUG) != null
                    && module.setting(net.fathom.optimizations.core.module.HudStyle.HIDE_SCREENS) != null,
                    module.id() + " has no visibility rules");
            check(!net.fathom.optimizations.core.module.HudStyle.hiddenBy(module, true, true),
                    module.id() + " hides itself by default");
        }
        net.fathom.optimizations.core.module.FathomModule fps = ModuleRegistry.standard().find("fps");
        ((Setting.Toggle) fps.setting(net.fathom.optimizations.core.module.HudStyle.HIDE_DEBUG)).set(true);
        check(net.fathom.optimizations.core.module.HudStyle.hiddenBy(fps, true, false), "hidden with F3");
        check(!net.fathom.optimizations.core.module.HudStyle.hiddenBy(fps, false, true), "not behind menus");
        ((Setting.Toggle) fps.setting(net.fathom.optimizations.core.module.HudStyle.HIDE_SCREENS)).set(true);
        check(net.fathom.optimizations.core.module.HudStyle.hiddenBy(fps, false, true), "and behind menus once asked");
        check(!net.fathom.optimizations.core.module.HudStyle.hiddenBy(null, true, true), "an unowned widget is never hidden");
        pass("every HUD module has visibility rules");
    }

    static void armourAndPotionIconsHaveASize() {
        ModuleRegistry registry = ModuleRegistry.standard();
        check(Math.round(registry.find("armour").number("icon.size", 0)) == 16, "armour icons default to 16");
        check(Math.round(registry.find("potions").number("icon.size", 0)) == 18, "potion icons default to 18");
        check(!((Setting.Whole) registry.find("armour").setting("icon.size")).parse("400")
                || Math.round(registry.find("armour").number("icon.size", 0)) <= 32, "a size is clamped");
        pass("armour and potion icons have a size");
    }

    // -----------------------------------------------------------------------
    // Runner
    // -----------------------------------------------------------------------

    private static String stripTimes(String json) {
        return json.replaceAll("\"(createdAt|updatedAt)\":\\d+", "");
    }

    static File temporaryDirectory(String label) {
        File base = new File(System.getProperty("java.io.tmpdir"), "fathom-" + label + "-" + System.nanoTime());
        if (!base.mkdirs()) {
            throw new AssertionError("could not create " + base);
        }
        base.deleteOnExit();
        return base;
    }

    static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    static void pass(String name) {
        passed++;
        System.out.println("  ok   " + name);
    }
}
