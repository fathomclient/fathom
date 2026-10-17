package net.fathom.optimizations.core;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

import net.fathom.optimizations.core.hud.Anchor;
import net.fathom.optimizations.core.hud.HudFlow;
import net.fathom.optimizations.core.hud.HudRegistry;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.hud.Widget;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.core.profile.FathomProfile;
import net.fathom.optimizations.core.profile.ProfileException;

/**
 * The HUD editor and module configuration: what keeps widgets apart, and what
 * keeps a player's settings.
 *
 * <p>Plain {@code main}, like every suite in this module.
 */
public final class EditorTests {

    private static int passed;

    public static void main(String[] args) throws Exception {
        // -- keeping widgets apart -------------------------------------------
        aWidgetLandingOnAnotherStacksUnderIt();
        aBottomWidgetStacksUpwards();
        theDraggedWidgetStaysUnderThePointer();
        aWidgetWithNowhereToGoStaysWhereItWasPut();
        theCrosshairTakesNoPart();
        aStackIsBuiltFromItsCornerWhateverTheListOrder();
        theShippedLayoutNeverOverlapsAtCommonGuiSizes();
        aStackShrinksBackWhenAWidgetAboveItDoes();

        // -- keeping settings -------------------------------------------------
        oneImpossibleWidgetValueIsRepairedNotTheProfileRefused();
        aSettingThisBuildDropsSurvivesASave();
        resettingAPageKeepsTheSwitchAndThePosition();
        aWidgetPlacementSurvivesARestart();
        switchingProfilesBackAndForthLosesNothing();
        oneModulesSettingsAreItsOwn();

        // -- keys ---------------------------------------------------------------
        aSharedKeyNamesEverythingElseOnItOnce();

        System.out.println();
        System.out.println("EditorTests: " + passed + " passed");
    }

    // -----------------------------------------------------------------------
    // HudFlow
    // -----------------------------------------------------------------------

    /** Arrays for up to eight widgets, laid out by the test. */
    private static final class Frame {
        final int[] x = new int[16];
        final int[] y = new int[16];
        final int[] w = new int[16];
        final int[] h = new int[16];
        final int[] direction = new int[16];
        final boolean[] fixed = new boolean[16];
        final int[] order = new int[16];
        int count;

        int add(int left, int top, int width, int height, int dir) {
            x[count] = left;
            y[count] = top;
            w[count] = width;
            h[count] = height;
            direction[count] = dir;
            return count++;
        }

        void flow(int screenHeight) {
            HudFlow.resolve(count, x, y, w, h, direction, fixed, order, screenHeight);
        }

        boolean overlap(int a, int b) {
            return x[a] < x[b] + w[b] && x[b] < x[a] + w[a] && y[a] < y[b] + h[b] && y[b] < y[a] + h[a];
        }
    }

    static void aWidgetLandingOnAnotherStacksUnderIt() {
        Frame frame = new Frame();
        int fps = frame.add(4, 4, 40, 14, 1);
        int coordinates = frame.add(4, 16, 90, 30, 1);
        frame.flow(240);
        check(frame.y[fps] == 4, "the first stays at its corner");
        check(frame.y[coordinates] == 4 + 14 + HudFlow.GAP, "the second sits just under it, not on it");
        pass("a widget landing on another stacks under it");
    }

    static void aBottomWidgetStacksUpwards() {
        Frame frame = new Frame();
        int pack = frame.add(300, 240 - 4 - 12, 60, 12, HudFlow.direction(Anchor.BOTTOM_RIGHT));
        int armour = frame.add(300, 240 - 8 - 40, 60, 40, HudFlow.direction(Anchor.BOTTOM_RIGHT));
        frame.flow(240);
        check(frame.y[pack] == 224, "the one at the very bottom stays");
        check(frame.y[armour] + 40 + HudFlow.GAP == frame.y[pack], "the other stands on top of it");
        pass("a bottom-anchored widget stacks upwards");
    }

    static void theDraggedWidgetStaysUnderThePointer() {
        Frame frame = new Frame();
        int still = frame.add(4, 4, 40, 14, 1);
        int dragged = frame.add(10, 8, 40, 14, 1);
        frame.fixed[dragged] = true;
        frame.flow(240);
        check(frame.y[dragged] == 8, "the dragged widget is exactly where the pointer put it");
        check(!frame.overlap(still, dragged), "the other one moved off it");
        pass("the dragged widget stays under the pointer");
    }

    static void aWidgetWithNowhereToGoStaysWhereItWasPut() {
        Frame frame = new Frame();
        int tall = frame.add(0, 0, 100, 230, 1);
        int other = frame.add(0, 10, 100, 20, 1);
        frame.flow(240);
        check(frame.y[tall] == 0, "the first is untouched");
        check(frame.y[other] == 10, "the second has no room below and is left, overlapping, where it was put");
        pass("a widget with nowhere to go stays where it was put");
    }

    static void theCrosshairTakesNoPart() {
        Frame frame = new Frame();
        int crosshair = frame.add(100, 100, 20, 20, 0);
        int widget = frame.add(95, 95, 40, 14, 1);
        frame.flow(240);
        check(frame.y[crosshair] == 100 && frame.y[widget] == 95, "neither moves for the other");
        pass("the crosshair takes no part");
    }

    static void aStackIsBuiltFromItsCornerWhateverTheListOrder() {
        Frame frame = new Frame();
        int lower = frame.add(4, 16, 90, 30, 1);
        int upper = frame.add(4, 4, 40, 14, 1);
        frame.flow(240);
        check(frame.y[upper] == 4, "the one nearer the corner keeps its place though it is listed second");
        check(frame.y[lower] == 4 + 14 + HudFlow.GAP, "and the other goes under it");
        pass("a stack is built from its corner whatever the list order");
    }

    static void aStackShrinksBackWhenAWidgetAboveItDoes() {
        Frame frame = new Frame();
        frame.add(300, 4, 70, 60, 1);
        int below = frame.add(300, 30, 40, 12, 1);
        frame.flow(240);
        int pushed = frame.y[below];
        Frame shorter = new Frame();
        shorter.add(300, 4, 70, 10, 1);
        int again = shorter.add(300, 30, 40, 12, 1);
        shorter.flow(240);
        check(pushed == 66, "a long potion list pushes the widget under it down");
        check(shorter.y[again] == 30, "and when it shortens the widget is back at its own place");
        pass("a stack shrinks back when a widget above it does");
    }

    /**
     * Every shipped widget at its default place, at a plausible size, on the
     * GUI sizes players actually have: 1280x720 and 1920x1080 at automatic
     * GUI scale are 426x240 and 480x270. Nothing may overlap after the flow.
     */
    static void theShippedLayoutNeverOverlapsAtCommonGuiSizes() {
        Map<String, int[]> sizes = new HashMap<String, int[]>();
        sizes.put("fps", new int[] {46, 14});
        sizes.put("coordinates", new int[] {110, 32});
        sizes.put("clock", new int[] {34, 14});
        sizes.put("cps", new int[] {44, 14});
        sizes.put("keystrokes", new int[] {64, 70});
        sizes.put("toggle-sprint", new int[] {66, 14});
        sizes.put("ping", new int[] {38, 14});
        sizes.put("armour", new int[] {70, 60});
        sizes.put("potions", new int[] {90, 44});
        sizes.put("pack-display", new int[] {60, 14});
        sizes.put("direction", new int[] {150, 30});
        sizes.put("scoreboard", new int[] {90, 70});
        sizes.put("crosshair", new int[] {9, 9});
        int[][] screens = {{426, 240}, {480, 270}, {640, 360}, {960, 540}};
        for (int[] screen : screens) {
            Frame frame = new Frame();
            String[] ids = new String[16];
            for (Widget widget : HudRegistry.standard().all()) {
                int[] size = sizes.get(widget.id());
                check(size != null, "a size for " + widget.id());
                Placement placement = widget.defaultPlacement();
                int x = clamp(placement.anchor().x(screen[0], size[0], placement.offsetX()), screen[0] - size[0]);
                int y = clamp(placement.anchor().y(screen[1], size[1], placement.offsetY()), screen[1] - size[1]);
                ids[frame.add(x, y, size[0], size[1],
                        "crosshair".equals(widget.id()) ? 0 : HudFlow.direction(placement.anchor()))] = widget.id();
            }
            frame.flow(screen[1]);
            StringBuilder clashes = new StringBuilder();
            for (int a = 0; a < frame.count; a++) {
                for (int b = a + 1; b < frame.count; b++) {
                    if (frame.direction[a] == 0 || frame.direction[b] == 0) {
                        continue;
                    }
                    if (frame.overlap(a, b)) {
                        clashes.append(' ').append(ids[a]).append('/').append(ids[b]);
                    }
                }
            }
            check(clashes.length() == 0, "overlaps at " + screen[0] + "x" + screen[1] + ":" + clashes);
        }
        pass("the shipped layout never overlaps at common GUI sizes");
    }

    private static int clamp(int value, int maximum) {
        return Math.max(0, Math.min(value, maximum));
    }

    // -----------------------------------------------------------------------
    // Settings
    // -----------------------------------------------------------------------

    static void oneImpossibleWidgetValueIsRepairedNotTheProfileRefused() throws ProfileException {
        FathomProfile loaded = FathomProfile.fromJson("{\"schema\":1,\"id\":\"mine\",\"name\":\"Mine\","
                + "\"modules\":{\"fps\":{\"enabled\":false,\"settings\":{\"format\":\"FPS: 120\"}}},"
                + "\"hud\":{"
                + "\"clock\":{\"anchor\":\"top-right\",\"x\":7,\"y\":9,\"scale\":0,\"opacity\":1.4},"
                + "\"ping\":{\"anchor\":\"sideways\",\"x\":1e15,\"y\":-3,\"scale\":1e999,\"opacity\":-1},"
                + "\"fps\":{\"anchor\":\"bottom-left\",\"x\":3,\"y\":5,\"scale\":1.5,\"opacity\":0.5}}}");
        check(loaded.hud().get("clock").scale == 1f, "a zero scale becomes the normal size");
        check(loaded.hud().get("clock").opacity == 1f, "an opacity over one is one");
        check(loaded.hud().get("clock").x == 7, "and the clock's position is kept");
        check(loaded.hud().get("ping").scale == 1f, "an infinite scale becomes the normal size");
        check(loaded.hud().get("ping").opacity == 0f, "a negative opacity is the faintest");
        check(Math.abs(loaded.hud().get("ping").x) <= 100_000, "an absurd offset is brought back near the screen");
        check(loaded.hud().get("fps").scale == 1.5f && loaded.hud().get("fps").opacity == 0.5f,
                "a sound widget is untouched");
        check("FPS: 120".equals(loaded.modules().get("fps").settings.get("format")), "and so is everything else");

        ProfileRig rig = new ProfileRig();
        rig.apply(loaded);
        check(!rig.features.find("fps").enabled(), "it applies: the module stays off");
        check(rig.layout().get("ping", null).anchor() == HudRegistry.standard().find("ping").defaultPlacement().anchor(),
                "an anchor that does not exist falls back to the widget's own");
        Placement clock = rig.layout().get("clock", null);
        check(clock.scale() == 1f && clock.opacity() == 1f && clock.offsetX() == 7,
                "the repaired clock is where it was, at a sane size");
        pass("one impossible widget value is repaired, not the profile refused");
    }

    static void aSettingThisBuildDropsSurvivesASave() throws ProfileException {
        FathomProfile stored = FathomProfile.fromJson("{\"schema\":1,\"id\":\"old\",\"name\":\"Old\","
                + "\"modules\":{\"main-menu\":{\"settings\":{\"panorama\":\"false\"}}}}");
        // The live save's own path (ProfileRuntime.toWrite): what this client
        // had at load, what it has now, and what is on disk, merged.
        ProfileRig rig = new ProfileRig();
        rig.apply(stored);
        FathomProfile atLoad = net.fathom.optimizations.core.profile.ProfileSwitch.capture(
                stored, rig, rig.switches, rig.optimizations, rig.baseline);
        rig.features.find("clock").setEnabled(true);
        FathomProfile saved = net.fathom.optimizations.core.profile.ProfileMerge.merge(atLoad,
                net.fathom.optimizations.core.profile.ProfileSwitch.capture(
                        stored, rig, rig.switches, rig.optimizations, rig.baseline),
                stored);
        FathomProfile.ModuleState menu = saved.modules().get("main-menu");
        check(menu != null && "false".equals(menu.settings.get("panorama")),
                "the retired main-menu switch is still in the file after an unrelated edit");
        check(Boolean.TRUE.equals(saved.modules().get("clock").enabled), "beside the edit");
        pass("a setting this build drops survives a save");
    }

    static void resettingAPageKeepsTheSwitchAndThePosition() {
        ProfileRig rig = new ProfileRig();
        FathomModule clock = rig.features.find("clock");
        clock.setEnabled(true);
        Setting.Colour colour = (Setting.Colour) clock.setting("text.colour");
        check(colour != null, "the clock has a text colour");
        colour.set(0x00FF00);
        rig.place("clock", Placement.at(Anchor.BOTTOM_LEFT, 20, 30));
        check(clock.settingsChanged(), "a changed colour is a change to reset");
        clock.resetSettings();
        check(colour.rgb() == colour.fallback(), "the colour is back");
        check(!clock.settingsChanged(), "nothing is left to reset");
        check(clock.enabled(), "the module is still on");
        check(rig.layout().get("clock", null).anchor() == Anchor.BOTTOM_LEFT, "and still where it was put");
        pass("resetting a page keeps the switch and the position");
    }

    static void aWidgetPlacementSurvivesARestart() {
        File collection = scratch("editor-restart");
        ProfileRig rig = new ProfileRig();
        Placement moved = new Placement(Anchor.MIDDLE_RIGHT, 12, -30, 1.35f, 0.65f);
        rig.place("potions", moved);
        rig.features.find("potions").setEnabled(true);
        rig.saveAs(collection, "placed");
        ProfileRig fresh = ProfileRig.restarted(collection, "placed");
        Placement back = fresh.layout().get("potions", null);
        check(back != null && back.equals(moved), "the potion list is where it was put, as large and as faint: "
                + back);
        check(fresh.features.find("potions").enabled(), "and switched on");
        check(fresh.layout().get("fps", null).equals(HudRegistry.standard().find("fps").defaultPlacement()),
                "a widget never moved is at its default");
        pass("a widget placement survives a restart");
    }

    static void switchingProfilesBackAndForthLosesNothing() {
        ProfileRig rig = new ProfileRig();
        rig.features.find("cps").setEnabled(true);
        ((Setting.Choice) rig.features.find("fps").setting("format")).set("FPS: 120");
        rig.place("cps", Placement.at(Anchor.TOP_RIGHT, 9, 50));
        FathomProfile a = rig.capture("a");

        ProfileRig other = new ProfileRig();
        other.features.find("clock").setEnabled(true);
        FathomProfile b = other.capture("b");

        rig.apply(b);
        check(!rig.features.find("cps").enabled(), "B does not have CPS on");
        check(!"FPS: 120".equals(rig.features.find("fps").choice("format", "")), "nor A's FPS format");
        check(rig.layout().get("cps", null).equals(HudRegistry.standard().find("cps").defaultPlacement()),
                "nor A's CPS position");
        check(rig.features.find("clock").enabled(), "but B's clock");

        rig.apply(a);
        check(rig.features.find("cps").enabled(), "back on A, CPS is on again");
        check("FPS: 120".equals(rig.features.find("fps").choice("format", "")), "the format is A's");
        check(rig.layout().get("cps", null).equals(Placement.at(Anchor.TOP_RIGHT, 9, 50)), "CPS is where A put it");
        check(!rig.features.find("clock").enabled(), "and B's clock is not carried over");
        pass("switching profiles back and forth loses nothing");
    }

    static void oneModulesSettingsAreItsOwn() {
        ProfileRig rig = new ProfileRig();
        Setting.Colour fps = (Setting.Colour) rig.features.find("fps").setting("text.colour");
        Setting.Colour coordinates = (Setting.Colour) rig.features.find("coordinates").setting("text.colour");
        fps.set(0xFF0000);
        check(coordinates.rgb() == coordinates.fallback(), "changing FPS's colour leaves Coordinates' alone");
        check(fps != coordinates, "each module has its own setting object");
        pass("one module's settings are its own");
    }

    /**
     * The warning under a keybind row, through the same function the Mods page
     * calls: other modules, the Hub key and Minecraft's own bindings, each once.
     */
    static void aSharedKeyNamesEverythingElseOnItOnce() {
        net.fathom.optimizations.core.module.ModuleRegistry registry =
                net.fathom.optimizations.core.module.ModuleRegistry.standard();
        net.fathom.optimizations.core.module.Keybind zoom = registry.find("zoom").keybinds().get(0);
        net.fathom.optimizations.core.module.Keybind fullbright = registry.find("fullbright").keybinds().get(0);
        net.fathom.optimizations.core.module.Keybind timeChanger = registry.find("time-changer").keybinds().get(0);
        net.fathom.optimizations.core.module.Keybind hub =
                new net.fathom.optimizations.core.module.Keybind("hub", "Open Fathom Hub", 54);
        String[] vanillaNames = {"Drop Item", "Sprint", "Toggle Perspective"};
        int[] vanillaCodes = {16, 29, 46};

        java.util.List<String> alone = net.fathom.optimizations.core.input.KeyConflicts.of(
                fullbright, registry.all(), hub, vanillaNames, vanillaCodes);
        check(alone.isEmpty(), "an unbound key clashes with nothing: " + alone);

        fullbright.set(zoom.key());
        timeChanger.set(zoom.key());
        java.util.List<String> shared = net.fathom.optimizations.core.input.KeyConflicts.of(
                fullbright, registry.all(), hub, vanillaNames, vanillaCodes);
        check(shared.equals(java.util.Arrays.asList("Zoom", "Time Changer", "Toggle Perspective")),
                "C names Zoom, Time Changer and vanilla's binding on it, not itself: " + shared);
        check("This key is also used by Zoom, Time Changer and Toggle Perspective.".equals(
                net.fathom.optimizations.core.input.KeyConflicts.sentence(shared)), "as one sentence");

        zoom.set(54);
        java.util.List<String> hubClash = net.fathom.optimizations.core.input.KeyConflicts.of(
                zoom, registry.all(), hub, vanillaNames, vanillaCodes);
        check(hubClash.equals(java.util.Collections.singletonList(
                net.fathom.optimizations.core.input.KeyConflicts.HUB)), "Right Shift is the Hub's: " + hubClash);
        check(net.fathom.optimizations.core.input.KeyConflicts.of(
                hub, registry.all(), hub, vanillaNames, vanillaCodes).contains("Zoom"),
                "and from the Hub key's side, Zoom");

        zoom.set(net.fathom.optimizations.core.module.Keybind.mouse(3));
        fullbright.set(net.fathom.optimizations.core.module.Keybind.mouse(3));
        check(net.fathom.optimizations.core.input.KeyConflicts.of(
                zoom, registry.all(), hub, null, null).equals(java.util.Collections.singletonList("Fullbright")),
                "a mouse button clashes like a key");
        check(net.fathom.optimizations.core.input.KeyConflicts.sentence(
                java.util.Collections.<String>emptyList()).isEmpty(), "and no clash is no sentence");
        pass("a shared key names everything else on it, once");
    }

    // -----------------------------------------------------------------------

    private static File scratch(String name) {
        File directory = new File(System.getProperty("java.io.tmpdir"), "fathom-" + name + "-" + System.nanoTime());
        if (!directory.mkdirs()) {
            throw new AssertionError("could not make " + directory);
        }
        return directory;
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
