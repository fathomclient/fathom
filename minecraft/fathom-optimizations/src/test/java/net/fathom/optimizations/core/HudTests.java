package net.fathom.optimizations.core;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

import net.fathom.optimizations.core.hud.Anchor;
import net.fathom.optimizations.core.hud.CrosshairShape;
import net.fathom.optimizations.core.hud.EditorPrefs;
import net.fathom.optimizations.core.hud.HudLayout;
import net.fathom.optimizations.core.hud.HudRegistry;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.hud.Sensitivity;
import net.fathom.optimizations.core.hud.Snap;
import net.fathom.optimizations.core.hud.Widget;
import net.fathom.optimizations.core.hud.value.ClickCounter;
import net.fathom.optimizations.core.input.InputGate;
import net.fathom.optimizations.core.input.MovementToggle;
import net.fathom.optimizations.core.input.ZoomMath;
import net.fathom.optimizations.core.opt.frames.FrameCapPolicy;

/**
 * Tests for the HUD framework and the frame-cap policy.
 *
 * <p>Same hand-rolled runner as {@link CoreTests}, for the same reason: this half of the
 * module has no dependencies outside the JDK, and it should stay checkable with nothing
 * but a compiler.
 *
 * <p>Everything here is geometry, parsing or arithmetic. The rendering that uses it is in
 * {@code fabric} and needs a running game, which is exactly why as much as possible was
 * kept out of there.
 */
public final class HudTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        // -- anchors --------------------------------------------------------
        anOffsetAlwaysRunsInwardFromItsAnchor();
        anAnchoredWidgetKeepsItsCornerWhenTheScreenResizes();
        aDroppedWidgetTakesTheAnchorOfTheRegionItLandedIn();

        // -- placements -----------------------------------------------------
        aPlacementClampsScaleAndOpacityRatherThanTrusting();
        aPlacementIsAValueAndMutatorsDoNotMutate();

        // -- the registry ---------------------------------------------------
        everyWidgetIsCompleteAndUniquelyIdentified();
        nothingServerSensitiveIsOnByDefault();
        aWidgetIsDrawnOnlyWhenItsModuleIsOn();
        theDefaultLayoutCoversEveryWidget();
        searchFindsAWidgetByNameSummaryOrCategory();

        // -- layout persistence ---------------------------------------------
        aLayoutSurvivesBeingWrittenAndReadBack();
        aMissingFileYieldsTheDefaultsRatherThanNothing();
        oneCorruptLineDoesNotCostTheWholeLayout();
        aWidgetTheFileDoesNotMentionKeepsItsDefault();

        // -- snapping -------------------------------------------------------
        anEdgeWithinTheThresholdSnapsAndReportsItsGuide();
        theNearerOfTwoGuidesWins();
        somethingFarFromEverythingIsLeftAlone();
        clampingKeepsAWidgetReachable();
        snapStrengthDecidesHowCloseCounts();
        aDroppedWidgetIsMovedOffWhatItLandedOn();
        editorPreferencesSurviveAndAreNotPartOfAProfile();

        // -- the crosshair --------------------------------------------------
        aCrosshairIsSymmetricAboutItsCentre();
        aCrosshairOutlineNeverOverlapsItsOwnFill();
        aGaplessCrossHasNoHoleAtTheAim();
        crosshairInputsAreClampedRatherThanTrusted();

        // -- zoom and movement toggles --------------------------------------
        zoomCompensationSlowsTheCameraByExactlyTheZoom();
        zoomScrollStaysInsideItsLimits();
        aToggleLatchesOnAPressAndLetsGoOnTheNext();
        aReleasedToggleNeedsAFreshPress();

        // -- clicks ---------------------------------------------------------
        clicksAreCountedOverASlidingSecondNotACalendarOne();
        theCounterCannotBeMadeToAllocateByClickingFaster();

        // -- input ----------------------------------------------------------
        aScreenIgnoresWhatWasQueuedBeforeItOpened();
        onePressIsOneAction();
        aBurstOfDuplicatesIsOneActionAndNotSilence();
        typingIsGatedByArrivalAndNothingElse();

        // -- frame caps -----------------------------------------------------
        anInWorldScreenIsNeverMenuCapped();
        theUnfocusedCapIsAnExplicitIndependentPolicy();
        onlyFathomOptsOutOfVanillasNoWorldCap();

        report();
    }

    // -----------------------------------------------------------------------
    // Anchors
    // -----------------------------------------------------------------------

    static void anOffsetAlwaysRunsInwardFromItsAnchor() {
        // 100 wide screen, 20 wide widget, 5 in from the anchored edge.
        check(Anchor.TOP_LEFT.x(100, 20, 5) == 5, "left anchor measures from the left");
        check(Anchor.TOP_RIGHT.x(100, 20, 5) == 75, "right anchor measures from the right");
        check(Anchor.TOP_LEFT.y(100, 10, 5) == 5, "top anchor measures from the top");
        check(Anchor.BOTTOM_LEFT.y(100, 10, 5) == 85, "bottom anchor measures from the bottom");
        // Centre ignores the sign question entirely: there is no edge to run in from.
        check(Anchor.CENTER.x(100, 20, 0) == 40, "centre centres");
        pass("an offset always runs inward from its anchor");
    }

    static void anAnchoredWidgetKeepsItsCornerWhenTheScreenResizes() {
        // The whole reason placements are stored as anchor plus offset. A widget
        // four pixels from the bottom-right stays four pixels from the bottom-right.
        for (int width : new int[] {320, 854, 1920}) {
            for (int height : new int[] {240, 480, 1080}) {
                int x = Anchor.BOTTOM_RIGHT.x(width, 40, 4);
                int y = Anchor.BOTTOM_RIGHT.y(height, 10, 4);
                check(width - (x + 40) == 4, "still four from the right at " + width);
                check(height - (y + 10) == 4, "still four from the bottom at " + height);
            }
        }
        pass("an anchored widget keeps its corner when the screen resizes");
    }

    static void aDroppedWidgetTakesTheAnchorOfTheRegionItLandedIn() {
        check(Anchor.nearest(5, 5, 300, 300) == Anchor.TOP_LEFT, "top left third");
        check(Anchor.nearest(150, 5, 300, 300) == Anchor.TOP_CENTER, "top middle third");
        check(Anchor.nearest(295, 5, 300, 300) == Anchor.TOP_RIGHT, "top right third");
        check(Anchor.nearest(150, 150, 300, 300) == Anchor.CENTER, "the middle");
        check(Anchor.nearest(295, 295, 300, 300) == Anchor.BOTTOM_RIGHT, "bottom right third");
        // A zero-sized screen is not a crash. It happens during a resize.
        check(Anchor.nearest(0, 0, 0, 0) == Anchor.CENTER, "a zero screen is not fatal");
        pass("a dropped widget takes the anchor of the region it landed in");
    }

    // -----------------------------------------------------------------------
    // Placements
    // -----------------------------------------------------------------------

    static void aPlacementClampsScaleAndOpacityRatherThanTrusting() {
        // The file is user-editable, so these arrive from outside. An opacity of
        // zero would be a widget that is on, invisible, and impossible to find.
        Placement tiny = new Placement(Anchor.CENTER, 0, 0, 0.01f, 0f);
        check(tiny.scale() == Placement.MIN_SCALE, "scale has a floor");
        check(tiny.opacity() >= 0.1f, "opacity has a floor");

        Placement huge = new Placement(Anchor.CENTER, 0, 0, 99f, 99f);
        check(huge.scale() == Placement.MAX_SCALE, "scale has a ceiling");
        check(huge.opacity() == 1f, "opacity has a ceiling");

        Placement notANumber = new Placement(Anchor.CENTER, 0, 0, Float.NaN, Float.NaN);
        check(!Float.isNaN(notANumber.scale()), "NaN scale is not stored");
        check(!Float.isNaN(notANumber.opacity()), "NaN opacity is not stored");

        check(new Placement(Anchor.CENTER, 0, 0, 1f, 1f).alpha() == 255, "full alpha");
        pass("a placement clamps scale and opacity rather than trusting them");
    }

    static void aPlacementIsAValueAndMutatorsDoNotMutate() {
        Placement original = Placement.at(Anchor.TOP_LEFT, 1, 2);
        Placement moved = original.withOffset(10, 20);
        check(original.offsetX() == 1 && original.offsetY() == 2, "the original is unchanged");
        check(moved.offsetX() == 10 && moved.offsetY() == 20, "the copy moved");
        check(original.equals(Placement.at(Anchor.TOP_LEFT, 1, 2)), "equal by value");
        check(
                original.hashCode() == Placement.at(Anchor.TOP_LEFT, 1, 2).hashCode(),
                "hash agrees with equals");
        check(!original.equals(moved), "and different values differ");
        pass("a placement is a value and mutators do not mutate");
    }

    // -----------------------------------------------------------------------
    // The registry
    // -----------------------------------------------------------------------

    static void everyWidgetIsCompleteAndUniquelyIdentified() {
        HudRegistry registry = HudRegistry.standard();
        check(registry.size() >= 6, "there is a HUD to speak of");
        List<String> seen = new ArrayList<String>();
        for (Widget widget : registry.all()) {
            check(!seen.contains(widget.id()), "duplicate widget id " + widget.id());
            seen.add(widget.id());
            check(widget.summary().length() > 20, widget.id() + " has no real description");
            check(widget.category() != null, widget.id() + " has no category");
            check(widget.defaultPlacement() != null, widget.id() + " has nowhere to go");
        }
        check(!registry.categories().isEmpty(), "widgets are grouped");
        pass("every widget is complete and uniquely identified");
    }

    static void nothingServerSensitiveIsOnByDefault() {
        // The rule FATHOM_MODS_AND_LICENSING.md asks for, asked of the module
        // registry -- which is where "on" lives now. There is exactly one switch
        // per widget, so there is exactly one place this can be got wrong.
        net.fathom.optimizations.core.module.ModuleRegistry modules =
                net.fathom.optimizations.core.module.ModuleRegistry.standard();
        for (Widget widget : HudRegistry.standard().all()) {
            if (widget.sensitivity() == Sensitivity.SAFE) {
                continue;
            }
            net.fathom.optimizations.core.module.FathomModule owner = modules.owningWidget(widget.id());
            check(owner != null, widget.id() + " has no module to switch it");
            check(!owner.defaultEnabled(), widget.id() + " is on by default and is not safe");
        }
        pass("nothing server-sensitive is on by default");
    }

    static void aWidgetIsDrawnOnlyWhenItsModuleIsOn() {
        // The defect this milestone removed: a layout that could also hide a
        // widget, so a module could read "on" and show nothing. There must be no
        // second switch left anywhere -- not on the placement, and not on the
        // widget.
        for (java.lang.reflect.Method method : Placement.class.getMethods()) {
            check(!"enabled".equals(method.getName()) && !"withEnabled".equals(method.getName()),
                    "Placement still has a second switch: " + method.getName());
        }
        for (java.lang.reflect.Method method : Widget.class.getMethods()) {
            check(!"onByDefault".equals(method.getName()),
                    "Widget still answers whether it is on");
        }

        net.fathom.optimizations.core.module.ModuleRegistry modules =
                net.fathom.optimizations.core.module.ModuleRegistry.standard();
        for (Widget widget : HudRegistry.standard().all()) {
            net.fathom.optimizations.core.module.FathomModule owner = modules.owningWidget(widget.id());
            check(owner != null, widget.id() + " is drawn by nothing that can switch it");
            owner.setEnabled(false);
            check(!modules.widgetAllowed(widget.id()), widget.id() + " ignored its module being off");
            owner.setEnabled(true);
            check(modules.widgetAllowed(widget.id()), widget.id() + " ignored its module being on");
        }
        pass("a widget is drawn only when its module is on");
    }

    static void theDefaultLayoutCoversEveryWidget() {
        HudRegistry registry = HudRegistry.standard();
        HudLayout layout = registry.defaultLayout();
        check(layout.size() == registry.size(), "a default for everything");
        for (Widget widget : registry.all()) {
            check(layout.has(widget.id()), widget.id() + " has no default placement");
        }
        // How many are *drawn* is the module registry's answer, not the
        // layout's. A layout places everything; the switches decide what shows.
        net.fathom.optimizations.core.module.ModuleRegistry modules =
                net.fathom.optimizations.core.module.ModuleRegistry.standard();
        int on = 0;
        for (Widget widget : registry.all()) {
            if (modules.widgetAllowed(widget.id())) {
                on++;
            }
        }
        check(on > 0, "a HUD that draws nothing at all is not a HUD");
        check(on < registry.size(), "and one that draws everything is a mess");
        pass("the default layout covers every widget");
    }

    static void searchFindsAWidgetByNameSummaryOrCategory() {
        HudRegistry registry = HudRegistry.standard();
        check(registry.matching("fps").size() >= 1, "by name");
        check(registry.matching("Performance").size() >= 1, "by category");
        check(registry.matching("").size() == registry.size(), "an empty query matches all");
        check(registry.matching("   ").size() == registry.size(), "and so does whitespace");
        check(registry.matching("zzzzz").isEmpty(), "and nonsense matches none");
        pass("search finds a widget by name, summary or category");
    }

    // -----------------------------------------------------------------------
    // Layout persistence
    // -----------------------------------------------------------------------

    static void aLayoutSurvivesBeingWrittenAndReadBack() {
        File directory = temporaryDirectory("hud-roundtrip");
        HudRegistry registry = HudRegistry.standard();
        HudLayout written = registry.defaultLayout();
        written.put("fps", new Placement(Anchor.BOTTOM_RIGHT, 12, 34, 1.5f, 0.6f));
        written.put("cps", Placement.at(Anchor.CENTER, 0, 0));
        try {
            written.write(directory);
        } catch (Exception problem) {
            throw new AssertionError("writing failed: " + problem);
        }

        HudLayout read = HudLayout.read(directory, registry);
        Placement fps = read.get("fps", null);
        check(fps.anchor() == Anchor.BOTTOM_RIGHT, "anchor survived");
        check(fps.offsetX() == 12 && fps.offsetY() == 34, "offset survived");
        check(Math.abs(fps.scale() - 1.5f) < 0.001f, "scale survived");
        check(Math.abs(fps.opacity() - 0.6f) < 0.001f, "opacity survived");
        check(read.get("cps", null).anchor() == Anchor.CENTER, "a second widget survived too");
        check(read.complaints().isEmpty(), "a file we wrote should not be complained about");
        pass("a layout survives being written and read back");
    }

    static void aMissingFileYieldsTheDefaultsRatherThanNothing() {
        HudRegistry registry = HudRegistry.standard();
        HudLayout layout = HudLayout.read(new File("no-such-directory-anywhere"), registry);
        check(layout.size() == registry.size(), "first run still has a HUD");
        check(layout.complaints().isEmpty(), "and a missing file is not a complaint");
        pass("a missing file yields the defaults rather than nothing");
    }

    static void oneCorruptLineDoesNotCostTheWholeLayout() {
        File directory = temporaryDirectory("hud-corrupt");
        writeFile(
                new File(directory, HudLayout.RELATIVE_PATH),
                "schema = 1\n"
                        + "widget.fps.anchor = SOMEWHERE_ELSE\n"
                        + "widget.fps.x = twelve\n"
                        + "widget.fps.y = 34\n"
                        + "widget.fps.enabled = true\n");

        HudRegistry registry = HudRegistry.standard();
        HudLayout layout = HudLayout.read(directory, registry);
        Placement fps = layout.get("fps", null);
        check(fps.offsetY() == 34, "the readable half of the entry was kept");
        check(
                fps.anchor() == registry.find("fps").defaultPlacement().anchor(),
                "the unreadable anchor fell back to the default");
        check(layout.complaints().size() == 2, "and both problems were reported: " + layout.complaints());
        pass("one corrupt line does not cost the whole layout");
    }

    static void aWidgetTheFileDoesNotMentionKeepsItsDefault() {
        File directory = temporaryDirectory("hud-partial");
        writeFile(
                new File(directory, HudLayout.RELATIVE_PATH),
                "schema = 1\nwidget.fps.x = 40\n");

        HudRegistry registry = HudRegistry.standard();
        HudLayout layout = HudLayout.read(directory, registry);
        check(layout.get("fps", null).offsetX() == 40, "the mentioned widget moved");
        check(
                layout.get("clock", null).equals(registry.find("clock").defaultPlacement()),
                "and an unmentioned one is exactly its default");
        pass("a widget the file does not mention keeps its default");
    }

    // -----------------------------------------------------------------------
    // Snapping
    // -----------------------------------------------------------------------

    static void anEdgeWithinTheThresholdSnapsAndReportsItsGuide() {
        Snap.Box dragged = new Snap.Box(2, 50, 30, 10);
        Snap.Result result = Snap.snap(dragged, new ArrayList<Snap.Box>(), 400, 300);
        check(result.x == 0, "it snapped to the left edge, got " + result.x);
        check(result.snapped(), "and said so");
        boolean hasVertical = false;
        for (Snap.Guide guide : result.guides()) {
            hasVertical |= guide.vertical && guide.position == 0;
        }
        check(hasVertical, "the guide drawn is the edge it snapped to");
        pass("an edge within the threshold snaps and reports its guide");
    }

    static void theNearerOfTwoGuidesWins() {
        // A widget three pixels from one neighbour and one from another must
        // take the one-pixel move. Taking the first candidate checked instead
        // reads as the editor fighting the mouse.
        Snap.Box near = new Snap.Box(101, 200, 20, 10);
        List<Snap.Box> others =
                Arrays.asList(new Snap.Box(98, 10, 20, 10), new Snap.Box(102, 30, 20, 10));
        Snap.Result result = Snap.snap(near, others, 400, 300);
        check(result.x == 102, "it took the nearer guide, got " + result.x);
        pass("the nearer of two guides wins");
    }

    static void somethingFarFromEverythingIsLeftAlone() {
        Snap.Box middle = new Snap.Box(137, 91, 20, 10);
        Snap.Result result = Snap.snap(middle, new ArrayList<Snap.Box>(), 400, 300);
        check(result.x == 137 && result.y == 91, "nothing moved");
        check(!result.snapped(), "and no guide is drawn");
        pass("something far from everything is left alone");
    }

    static void clampingKeepsAWidgetReachable() {
        Snap.Box offScreen = new Snap.Box(-100, 5000, 20, 10);
        Snap.Box clamped = Snap.clamp(offScreen, 400, 300);
        check(clamped.x == 0, "pulled back onto the screen horizontally");
        check(clamped.bottom() == 300, "and vertically");
        pass("clamping keeps a widget reachable");
    }

    static void snapStrengthDecidesHowCloseCounts() {
        Snap.Box dragged = new Snap.Box(6, 50, 30, 10);
        check(Snap.snap(dragged, new ArrayList<Snap.Box>(), 400, 300, 8).x == 0,
                "strong snapping reaches six pixels");
        check(Snap.snap(dragged, new ArrayList<Snap.Box>(), 400, 300, 2).x == 6,
                "light snapping does not");
        Snap.Result off = Snap.snap(dragged, new ArrayList<Snap.Box>(), 400, 300, 0);
        check(off.x == 6 && !off.snapped(), "and off means off, with no guides");
        pass("snap strength decides how close counts");
    }

    static void aDroppedWidgetIsMovedOffWhatItLandedOn() {
        Snap.Box dropped = new Snap.Box(50, 50, 20, 10);
        List<Snap.Box> others = Arrays.asList(new Snap.Box(55, 52, 20, 10));
        Snap.Box moved = Snap.separate(dropped, others, 400, 300, 2);
        check(!Snap.overlaps(moved, others.get(0)), "it no longer overlaps");
        check(moved.width == dropped.width && moved.height == dropped.height, "and was not resized");

        // Clear of everything already: untouched, and the same instance, so the
        // editor can tell that nothing happened.
        Snap.Box clear = new Snap.Box(200, 200, 20, 10);
        check(Snap.separate(clear, others, 400, 300, 2) == clear, "a clear drop is left alone");

        // Nowhere to go: left where it was dropped rather than thrown somewhere.
        Snap.Box crowded = new Snap.Box(0, 0, 40, 30);
        List<Snap.Box> everywhere = Arrays.asList(new Snap.Box(0, 0, 40, 30));
        check(Snap.separate(crowded, everywhere, 40, 30, 2).x == 0,
                "with nowhere clear it stays put");
        pass("a dropped widget is moved off what it landed on");
    }

    static void editorPreferencesSurviveAndAreNotPartOfAProfile() {
        File directory = temporaryDirectory("hud-editor-prefs");
        EditorPrefs written = EditorPrefs.read(directory);
        int before = written.snapPixels();
        written.cycleSnap();
        written.setGrid(true);
        check(written.snapPixels() != before, "cycling changes the strength");
        check(written.write(directory) == null, "they were written");

        EditorPrefs read = EditorPrefs.read(directory);
        check(read.snapChoice() == written.snapChoice(), "the strength survived");
        check(read.grid(), "and so did the grid");
        check(EditorPrefs.read(temporaryDirectory("hud-editor-empty")).snapPixels() > 0,
                "a fresh install snaps by default");
        pass("editor preferences survive and are not part of a profile");
    }

    // -----------------------------------------------------------------------
    // The crosshair
    // -----------------------------------------------------------------------

    /** Every pixel of a built shape, as a set, for the assertions below. */
    private static boolean[] mask(CrosshairShape shape, boolean outline) {
        int size = shape.size();
        boolean[] cells = new boolean[size * size];
        int[] rects = outline ? shape.outline() : shape.fill();
        int count = outline ? shape.outlineCount() : shape.fillCount();
        for (int index = 0; index < count; index += 4) {
            for (int y = rects[index + 1]; y < rects[index + 3]; y++) {
                for (int x = rects[index]; x < rects[index + 2]; x++) {
                    check(x >= 0 && y >= 0 && x < size && y < size, "a rectangle left the box");
                    check(!cells[y * size + x], "a rectangle overlapped another");
                    cells[y * size + x] = true;
                }
            }
        }
        return cells;
    }

    static void aCrosshairIsSymmetricAboutItsCentre() {
        CrosshairShape shape = new CrosshairShape();
        shape.build(CrosshairShape.Style.CROSS, 4, 1, 2, false, 1, 1);
        int size = shape.size();
        boolean[] cells = mask(shape, false);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                check(cells[y * size + x] == cells[y * size + (size - 1 - x)],
                        "left and right differ at " + x + "," + y);
                check(cells[y * size + x] == cells[(size - 1 - y) * size + x],
                        "top and bottom differ at " + x + "," + y);
            }
        }
        check(size == 2 * (2 + 4 + 1) + 1, "the box is the arms, the gaps and the outline: " + size);
        pass("a crosshair is symmetric about its centre");
    }

    static void aCrosshairOutlineNeverOverlapsItsOwnFill() {
        CrosshairShape shape = new CrosshairShape();
        // A circle with a dot: the case where naive outlining -- every
        // rectangle one pixel larger -- overlaps itself dozens of times and
        // shows as dark knots at anything under full opacity.
        shape.build(CrosshairShape.Style.CIRCLE, 3, 2, 3, true, 2, 2);
        boolean[] fill = mask(shape, false);
        boolean[] outline = mask(shape, true);
        for (int cell = 0; cell < fill.length; cell++) {
            check(!(fill[cell] && outline[cell]), "the outline covered the fill at " + cell);
        }
        check(shape.outlineCount() > 0, "there is an outline to check");
        pass("a crosshair outline never overlaps its own fill");
    }

    static void aGaplessCrossHasNoHoleAtTheAim() {
        CrosshairShape shape = new CrosshairShape();
        shape.build(CrosshairShape.Style.CROSS, 4, 1, 0, false, 1, 0);
        boolean[] cells = mask(shape, false);
        int centre = shape.centre();
        check(cells[centre * shape.size() + centre], "the centre pixel is filled");
        pass("a gapless cross has no hole at the aim");
    }

    static void crosshairInputsAreClampedRatherThanTrusted() {
        CrosshairShape shape = new CrosshairShape();
        // A hand-edited profile, or a newer build with wider ranges.
        shape.build(CrosshairShape.Style.CROSS, 100000, 900, 100000, true, 500, 90);
        check(shape.size() <= (CrosshairShape.MAX_LENGTH + CrosshairShape.MAX_GAP
                + CrosshairShape.MAX_OUTLINE) * 2 + CrosshairShape.MAX_THICKNESS,
                "the box stayed sane: " + shape.size());
        check(shape.fillCount() > 0, "and it still drew something");
        pass("crosshair inputs are clamped rather than trusted");
    }

    // -----------------------------------------------------------------------
    // Zoom and movement toggles
    // -----------------------------------------------------------------------

    static void zoomCompensationSlowsTheCameraByExactlyTheZoom() {
        // Vanilla turns by (s * 0.6 + 0.2) cubed, times eight. Compensation has
        // to invert that curve, not divide the option: dividing the option at
        // the default sensitivity slows the camera by about four times what was
        // asked for, which is the sluggish zoom every naive implementation has.
        float[] sensitivities = {0.05f, 0.5f, 1f};
        double[] zooms = {2.0, 4.0, 10.0};
        for (float sensitivity : sensitivities) {
            for (double zoom : zooms) {
                float compensated = ZoomMath.compensatedSensitivity(sensitivity, zoom, 1.0);
                double ratio = ZoomMath.turnRate(sensitivity) / ZoomMath.turnRate(compensated);
                check(Math.abs(ratio - zoom) < 0.001,
                        "at " + sensitivity + " and " + zoom + "x the camera turned " + ratio + " times slower");
            }
        }
        check(ZoomMath.compensatedSensitivity(0.5f, 4.0, 0.0) == 0.5f,
                "zero strength changes nothing");
        check(ZoomMath.compensatedSensitivity(0.5f, 1.0, 1.0) == 0.5f,
                "and no zoom changes nothing");
        pass("zoom compensation slows the camera by exactly the zoom");
    }

    static void zoomScrollStaysInsideItsLimits() {
        double zoomed = ZoomMath.scroll(4.0, 1, 1.25, 1.5, 20.0);
        check(Math.abs(zoomed - 5.0) < 1e-9, "one notch in is one step: " + zoomed);
        check(ZoomMath.scroll(1.6, -1, 1.25, 1.5, 20.0) == 1.5, "it stops at the minimum");
        check(ZoomMath.scroll(19.0, 1, 1.25, 1.5, 20.0) == 20.0, "and at the maximum");
        check(ZoomMath.scroll(4.0, 1, 1.25, 20.0, 1.5) == 5.0,
                "limits given the wrong way round are still limits");
        check(ZoomMath.divisor(8.0, 0.0) == 1.0, "no progress is no zoom");
        check(Math.abs(ZoomMath.divisor(8.0, 1.0) - 8.0) < 1e-9, "full progress is the whole zoom");
        check(Math.abs(ZoomMath.divisor(8.0, 0.5) - Math.sqrt(8.0)) < 1e-9,
                "and half way in is half the ratio, not half the number");
        pass("zoom scrolling stays inside its limits");
    }

    static void aToggleLatchesOnAPressAndLetsGoOnTheNext() {
        MovementToggle toggle = new MovementToggle();
        toggle.tick(true, true, true);
        check(toggle.latched(), "the press latched it");
        check(toggle.held(true, false, true), "and the game is told the key is held");
        toggle.tick(true, false, true);
        check(toggle.latched(), "releasing the key does not let go");
        toggle.tick(true, true, true);
        check(!toggle.latched(), "the next press does");

        // Hold mode is vanilla: the latch cannot exist at all.
        toggle.tick(true, false, true);
        toggle.tick(true, true, true);
        check(toggle.latched(), "latched again");
        toggle.tick(false, false, true);
        check(!toggle.latched(), "switching to Hold drops the latch");
        check(!toggle.held(false, false, true), "and Hold reports exactly the key");
        check(toggle.held(false, true, true), "in both directions");
        pass("a toggle latches on a press and lets go on the next");
    }

    static void aReleasedToggleNeedsAFreshPress() {
        MovementToggle toggle = new MovementToggle();
        toggle.tick(true, true, true);
        check(toggle.latched(), "latched");
        toggle.release();
        check(!toggle.latched(), "death, a respawn or a world change lets go");
        // The key was still held through the respawn: that must not re-latch.
        toggle.tick(true, true, true);
        check(!toggle.latched(), "a key held across the release is not a new press");
        toggle.tick(true, false, true);
        toggle.tick(true, true, true);
        check(toggle.latched(), "releasing and pressing again is");

        // A press that is not allowed to count -- sneak while flying -- is not
        // swallowed into a latch that appears the moment the player lands.
        MovementToggle sneak = new MovementToggle();
        sneak.tick(true, true, false);
        check(!sneak.latched(), "a press while flying does not latch");
        check(sneak.held(true, true, false), "but the key still works as a key");
        pass("a released toggle needs a fresh press");
    }

    // -----------------------------------------------------------------------
    // Clicks
    // -----------------------------------------------------------------------

    static void clicksAreCountedOverASlidingSecondNotACalendarOne() {
        ClickCounter counter = new ClickCounter();
        long second = ClickCounter.WINDOW_NANOS;
        // Eight clicks in the last 100 ms of one notional second.
        for (int i = 0; i < 8; i++) {
            counter.click(second - 100_000_000L + i * 1_000_000L);
        }
        check(counter.perSecond(second) == 8, "all eight are inside the window");
        // A tenth of a second later they are all still within a second.
        check(counter.perSecond(second + 50_000_000L) == 8, "and still are shortly after");
        // A whole second later, none of them are.
        check(counter.perSecond(second + 1_500_000_000L) == 0, "and expire on their own");
        counter.reset();
        check(counter.perSecond(second) == 0, "reset forgets everything");
        pass("clicks are counted over a sliding second, not a calendar one");
    }

    static void theCounterCannotBeMadeToAllocateByClickingFaster() {
        ClickCounter counter = new ClickCounter();
        for (int i = 0; i < ClickCounter.CAPACITY * 10; i++) {
            counter.click(1_000_000L * i);
        }
        int counted = counter.perSecond(1_000_000L * ClickCounter.CAPACITY * 10);
        check(counted <= ClickCounter.CAPACITY, "the ring buffer caps what can be counted");
        check(counted > 0, "and still counts something");
        pass("the counter cannot be made to allocate by clicking faster");
    }

    // -----------------------------------------------------------------------
    // Frame caps
    // -----------------------------------------------------------------------

    static void anInWorldScreenIsNeverMenuCapped() {
        check(FrameCapPolicy.effective(240, 240, true, true, 30, true, false) == 240,
                "opening a focused in-world screen preserves 240");
        check(FrameCapPolicy.effective(75, 75, true, true, 30, true, false) == 75,
                "the policy does not invent a preferred rate");
        pass("an in-world screen is never menu capped");
    }

    static void theUnfocusedCapIsAnExplicitIndependentPolicy() {
        check(FrameCapPolicy.effective(240, 240, false, true, 30, true, false) == 30,
                "unfocused lowers the cap when enabled");
        check(FrameCapPolicy.effective(240, 240, false, false, 30, true, false) == 240,
                "disabled contributes nothing");
        check(FrameCapPolicy.effective(20, 20, false, true, 30, true, false) == 20,
                "the background rule never raises a lower user cap");
        pass("the unfocused cap is explicit and independent");
    }

    static void onlyFathomOptsOutOfVanillasNoWorldCap() {
        int vanillaTitle = FrameCapPolicy.effective(
                FrameCapPolicy.VANILLA_MENU_CAP, 240, true, true, 30, false, false);
        int fathomTitle = FrameCapPolicy.effective(
                FrameCapPolicy.VANILLA_MENU_CAP, 240, true, true, 30, false, true);
        check(vanillaTitle == FrameCapPolicy.VANILLA_MENU_CAP, "vanilla title remains at vanilla's 30");
        check(fathomTitle == 240, "Fathom's interactive title uses the player's limit");
        pass("only a Fathom no-world screen opts out of vanilla's title cap");
    }

    // -----------------------------------------------------------------------
    // Runner
    // -----------------------------------------------------------------------

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
            if (writer != null) {
                try {
                    writer.close();
                } catch (java.io.IOException ignored) {
                    // Nothing useful to do, and the assertion above already ran.
                }
            }
        }
        file.deleteOnExit();
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    static void aScreenIgnoresWhatWasQueuedBeforeItOpened() {
        // The defect this exists for: a press aimed at the game arrives in the
        // tick the hub opens, lands on the close button, and shuts it again.
        InputGate gate = new InputGate(1000L, 220, 90);
        check(!gate.press(1000L), "a press in the opening tick is not the hub's");
        check(!gate.press(1219L), "nor one a millisecond before the panel settles");
        check(gate.press(1220L), "a press once it has settled is acted on");
        pass("a screen ignores what was queued before it opened");
    }

    static void onePressIsOneAction() {
        InputGate gate = new InputGate(0L, 0, 90);
        check(gate.press(500L), "the first press counts");
        check(!gate.press(589L), "the same press arriving again does not");
        check(gate.press(590L), "a deliberate second click does");
        pass("one press is one action");
    }

    static void aBurstOfDuplicatesIsOneActionAndNotSilence() {
        // A refused press must not push the window forward, or a stream of
        // duplicates would keep the screen deaf for as long as it lasted.
        InputGate gate = new InputGate(0L, 0, 90);
        check(gate.press(0L), "the first of the burst counts");
        for (int at = 10; at < 90; at += 10) {
            check(!gate.press(at), "the rest of the burst does not, at " + at);
        }
        check(gate.press(90L), "and the window still ends where it started plus 90");
        pass("a burst of duplicates is one action and not silence");
    }

    static void typingIsGatedByArrivalAndNothingElse() {
        // No repeat window on keys: holding one is how people type.
        InputGate gate = new InputGate(0L, 200, 90);
        check(!gate.key(199L), "a key queued before the screen settled is not typed");
        check(gate.key(200L), "one after it is");
        check(gate.key(200L), "and a second in the same millisecond still is");
        pass("typing is gated by arrival and nothing else");
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

    private HudTests() {}
}
