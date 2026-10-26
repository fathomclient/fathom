package net.fathom.optimizations.core;

import java.io.File;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.hud.Anchor;
import net.fathom.optimizations.core.hud.EditorPrefs;
import net.fathom.optimizations.core.hud.HubState;
import net.fathom.optimizations.core.hud.HudGeometry;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.hud.TextAnchor;
import net.fathom.optimizations.core.ui.layout.ScrollMath;

/**
 * Scrolling, text anchoring and the preferences behind the editor toolbar and
 * the Hub's remembered page: the arithmetic and the files, with no game.
 */
public final class InteractionTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) throws Exception {
        scrollOffsetsStayInRange();
        theThumbReflectsAndDrivesTheOffset();
        aTrackClickPagesTowardsThePointer();
        aNestedListTakesWhatItCanAndPassesTheRest();
        defaultTextAnchorIsTheOldBehaviour();
        aRightAnchoredLabelKeepsItsRightEdge();
        offsetsInvertForEveryPair();
        placementsCarryTheirTextAnchor();
        toolbarPreferencesRoundTrip();
        hubStateRoundTripsAndToleratesDamage();

        System.out.println("InteractionTests: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    static void scrollOffsetsStayInRange() {
        check(ScrollMath.max(500f, 300f) == 200f, "scrolls by the overflow");
        check(ScrollMath.max(100f, 300f) == 0f, "content that fits does not scroll");
        // Content shrinks (a filter): an old offset clamps to the new end.
        check(ScrollMath.clamp(180f, 0f, ScrollMath.max(250f, 200f)) == 50f, "a shrunk list clamps to its new end");
        // The panel grows past the content.
        check(ScrollMath.clamp(180f, 0f, ScrollMath.max(250f, 400f)) == 0f, "a taller panel returns to the top");
    }

    static void theThumbReflectsAndDrivesTheOffset() {
        float track = 200f;
        float viewport = 300f;
        float content = 900f;
        float max = ScrollMath.max(content, viewport);
        float thumb = ScrollMath.thumbLength(track, viewport, content);
        check(Math.abs(thumb - 200f / 3f) < 0.01f, "thumb length is the visible share of the track: " + thumb);
        check(ScrollMath.thumbLength(track, 10f, 100000f) == ScrollMath.MIN_THUMB,
                "a thumb never gets too small to grab");
        check(ScrollMath.thumbTop(10f, track, thumb, 0f, max) == 10f, "at the top the thumb is at the track's top");
        check(Math.abs(ScrollMath.thumbTop(10f, track, thumb, max, max) - (10f + track - thumb)) < 0.001f,
                "at the end it is at the track's bottom");
        // Grabbing the thumb 5 units from its top and holding there changes nothing.
        float offset = 250f;
        float top = ScrollMath.thumbTop(10f, track, thumb, offset, max);
        float back = ScrollMath.offsetForDrag(top + 5f, 5f, 10f, track, thumb, max);
        check(Math.abs(back - offset) < 0.01f, "drag and display are inverses: " + back);
        check(ScrollMath.offsetForDrag(-500f, 5f, 10f, track, thumb, max) == 0f, "dragging past the top clamps");
        check(ScrollMath.offsetForDrag(5000f, 5f, 10f, track, thumb, max) == max, "dragging past the end clamps");
    }

    static void aTrackClickPagesTowardsThePointer() {
        check(ScrollMath.pageTarget(0f, 300f, true, 600f) == 270f, "a click below pages down by most of a viewport");
        check(ScrollMath.pageTarget(500f, 300f, true, 600f) == 600f, "and stops at the end");
        check(ScrollMath.pageTarget(100f, 300f, false, 600f) == 0f, "above pages up and stops at the top");
    }

    static void aNestedListTakesWhatItCanAndPassesTheRest() {
        // Positive notches scroll towards the top. Inner list: 250 of room above its target.
        check(ScrollMath.consumed(1, 250f, 400f) == 1, "room for one notch takes it");
        check(ScrollMath.consumed(5, 250f, 400f) == 3, "250 units is three notches (the last clamps)");
        check(ScrollMath.leftover(5, 250f, 400f) == 2, "the rest goes to the parent");
        check(ScrollMath.leftover(-2, 250f, 400f) == 0, "scrolling down with room takes everything");
        // At the top: nothing to take, everything passes up.
        check(ScrollMath.consumed(3, 0f, 400f) == 0, "at the top it takes none of an upward turn");
        check(ScrollMath.leftover(3, 0f, 400f) == 3, "so the page gets all three");
        check(ScrollMath.leftover(-3, 400f, 400f) == -3, "same at the bottom, with the sign kept");
        // A list that does not overflow never traps the wheel.
        check(ScrollMath.leftover(-4, 0f, 0f) == -4 && ScrollMath.leftover(4, 0f, 0f) == 4,
                "a list with nothing to scroll passes the wheel on");
    }

    static void defaultTextAnchorIsTheOldBehaviour() {
        // With AUTO, nothing moves from what Anchor.x gave.
        for (Anchor anchor : Anchor.values()) {
            for (int width : new int[] {1, 37, 120}) {
                int old = anchor.x(854, width, 6);
                check(HudGeometry.left(anchor, TextAnchor.AUTO, 854, width, 6) == old,
                        "AUTO is Anchor.x for " + anchor + " w" + width);
            }
        }
        check(HudGeometry.left(Anchor.TOP_RIGHT, TextAnchor.RIGHT, 854, 50, 4) == Anchor.TOP_RIGHT.x(854, 50, 4),
                "right text on a right anchor is the old layout");
        check(TextAnchor.parse(null) == TextAnchor.AUTO && TextAnchor.parse("nonsense") == TextAnchor.AUTO,
                "unknown names read as AUTO, so old and damaged files are the old layout");
        check(TextAnchor.parse("Right") == TextAnchor.RIGHT && TextAnchor.parse("centre") == TextAnchor.CENTER,
                "names read back");
    }

    static void aRightAnchoredLabelKeepsItsRightEdge() {
        int screen = 854;
        // Explicit right text on the LEFT of the screen: the right edge is the fixed point.
        int edge = -1;
        for (int width = 20; width <= 90; width += 7) {
            int left = HudGeometry.left(Anchor.TOP_LEFT, TextAnchor.RIGHT, screen, width, 120);
            int right = left + width;
            if (edge < 0) {
                edge = right;
            }
            check(right == edge, "right edge fixed as the label grows to " + width + ": " + right + " vs " + edge);
        }
        // Centre keeps its middle (to within the rounding of a half pixel).
        int middle = -1;
        for (int width = 20; width <= 90; width += 10) {
            int left = HudGeometry.left(Anchor.TOP_LEFT, TextAnchor.CENTER, screen, width, 200);
            int centre = left + width / 2;
            if (middle < 0) {
                middle = centre;
            }
            check(Math.abs(centre - middle) <= 1, "centre fixed as width " + width);
        }
        // Left text on the right of the screen: the left edge is fixed.
        int fixedLeft = HudGeometry.left(Anchor.TOP_RIGHT, TextAnchor.LEFT, screen, 30, 40);
        check(HudGeometry.left(Anchor.TOP_RIGHT, TextAnchor.LEFT, screen, 80, 40) == fixedLeft,
                "left edge fixed when the text is left-anchored on a right-hand anchor");
        // The clamp keeps what fits on the screen, and leaves what does not.
        check(HudGeometry.clampLeft(-5, 40, 854) == 0 && HudGeometry.clampLeft(840, 40, 854) == 814,
                "a box that fits is kept on screen");
        check(HudGeometry.clampLeft(-5, 900, 854) == -5, "a box wider than the screen is left alone");
    }

    static void offsetsInvertForEveryPair() {
        int screen = 640;
        for (Anchor anchor : Anchor.values()) {
            for (TextAnchor text : TextAnchor.values()) {
                for (int width : new int[] {17, 64, 101}) {
                    for (int left : new int[] {0, 9, 150, 400}) {
                        int offset = HudGeometry.offset(anchor, text, screen, width, left);
                        int back = HudGeometry.left(anchor, text, screen, width, offset);
                        check(back == left, anchor + "/" + text + " w" + width + " left " + left
                                + " came back " + back);
                    }
                }
            }
        }
    }

    static void placementsCarryTheirTextAnchor() {
        Placement placement = Placement.at(Anchor.TOP_LEFT, 4, 4);
        check(placement.textAnchor() == TextAnchor.AUTO, "a new placement follows the anchor");
        Placement right = placement.withTextAnchor(TextAnchor.RIGHT);
        check(right.textAnchor() == TextAnchor.RIGHT && right.textAlignment() == 1f, "set");
        check(right.withScale(1.5f).textAnchor() == TextAnchor.RIGHT
                && right.withOffset(1, 2).textAnchor() == TextAnchor.RIGHT
                && right.withAnchor(Anchor.BOTTOM_RIGHT).textAnchor() == TextAnchor.RIGHT
                && right.withOpacity(0.5f).textAnchor() == TextAnchor.RIGHT, "every other change keeps it");
        check(!placement.equals(right), "and it is part of equality, so the default layout compares as unchanged");
        check(placement.textAlignment() == 0f
                && Placement.at(Anchor.TOP_RIGHT, 0, 0).textAlignment() == 1f, "AUTO alignment is the anchor's");
    }

    static void toolbarPreferencesRoundTrip() throws Exception {
        File dir = Files.createTempDirectory("fathom-editor-prefs").toFile();
        EditorPrefs fresh = EditorPrefs.read(dir);
        check(fresh.toolbar() && !fresh.toolbarCompact(), "the toolbar is shown, standard size, by default");
        fresh.setToolbar(false);
        fresh.setToolbarSizeChoice(1);
        check(fresh.write(dir) == null, "writes");
        EditorPrefs again = EditorPrefs.read(dir);
        check(!again.toolbar() && again.toolbarCompact(), "hidden and compact survive a restart");
        again.setToolbarSizeChoice(99);
        check(again.toolbarCompact(), "an impossible size is ignored");
        deleteAll(dir);
    }

    static void hubStateRoundTripsAndToleratesDamage() throws Exception {
        File dir = Files.createTempDirectory("fathom-hub-state").toFile();
        HubState fresh = HubState.read(dir);
        check(fresh.reopenLast() && fresh.section().isEmpty() && fresh.scroll() == 0f,
                "reopening is on by default and nothing is remembered yet");
        check(!fresh.changed() && fresh.write(dir) == null && !new File(dir, HubState.RELATIVE_PATH).exists(),
                "an untouched state writes nothing");
        fresh.remember("Settings", "Profiles", 123.5f);
        check(fresh.write(dir) == null, "writes");
        HubState again = HubState.read(dir);
        check("Settings".equals(again.section()) && "Profiles".equals(again.page()) && again.scroll() == 123.5f,
                "the page and scroll position come back");
        again.setReopenLast(false);
        again.write(dir);
        check(!HubState.read(dir).reopenLast(), "the preference is kept");
        // Damage: a garbage number falls back instead of failing.
        File file = new File(dir, HubState.RELATIVE_PATH);
        Files.write(file.toPath(), "section=Mods\nscroll=oops\nreopenLast=true\n".getBytes("UTF-8"));
        HubState damaged = HubState.read(dir);
        check("Mods".equals(damaged.section()) && damaged.scroll() == 0f && damaged.reopenLast(),
                "an unreadable scroll is the top of the page");
        damaged.remember("Mods", "", -40f);
        check(damaged.scroll() == 0f, "a negative scroll is never kept");
        deleteAll(dir);
    }

    private static void deleteAll(File file) {
        File[] children = file.listFiles();
        if (children != null) {
            for (File child : children) {
                deleteAll(child);
            }
        }
        file.delete();
    }

    static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(what);
        }
    }
}
