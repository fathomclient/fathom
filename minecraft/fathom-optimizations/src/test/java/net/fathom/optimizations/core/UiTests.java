package net.fathom.optimizations.core;

import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.opt.frames.FrameCapPolicy;
import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.NavigationHistory;
import net.fathom.optimizations.core.ui.UiScale;
import net.fathom.optimizations.core.ui.VideoOptionDomain;
import net.fathom.optimizations.core.ui.anim.Clock;
import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Spring;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.anim.Transition;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.ResponsiveGrid;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.CanvasState;
import net.fathom.optimizations.core.ui.render.FrameScale;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.ImageRef;
import net.fathom.optimizations.core.ui.render.Paint;
import net.fathom.optimizations.core.ui.render.RoundedGeometry;
import net.fathom.optimizations.core.ui.render.TextAlign;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * The interface layer, tested without a game.
 *
 * <p>This is the whole point of the split described in
 * {@code docs/ingame-ui-renderer-decision.md}: the coordinate system, the
 * animation clock, the easing, the text fitting and the frame-cap policy are
 * arithmetic, and arithmetic can be asserted. What is left in {@code fabric} is
 * the part that genuinely needs a window and a GL context.
 *
 * <p>The renderer here is a fake with a deliberately simple metric — every
 * character is six units wide — so a test can say exactly what should fit.
 */
public final class UiTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        // -- scale ------------------------------------------------------------
        theScaleComesFromTheFramebufferAndNotFromMinecraft();
        theScaleIsClampedAtBothEnds();
        aScaleThatBarelyMovedIsNotWorthRebuildingFor();
        logicalAndDeviceAreInverses();

        // -- clock ------------------------------------------------------------
        aStallDoesNotTeleportAnimations();
        aClockThatDidNotMoveReportsNoTime();
        restartingDiscardsTheGapWithoutRewindingTime();

        // -- motion -----------------------------------------------------------
        aMotionIsSampledFromTimeNotCountedInFrames();
        aReversalLeavesFromWhereItIs();
        aMotionNeverQueuesTheTripItWasOn();
        askingForTheTargetItAlreadyHasIsIgnored();
        aTripOverrideDoesNotBecomeTheNextTripsDefault();
        aMotionMapsOpacityGeometryAndColour();
        anImmediateCloseCannotFinishOpeningFirst();
        aSmoothSpringIsFrameRateIndependentAndBounded();
        theCurveStartsFastAndEndsExactly();
        theSharedDurationsAreWithinTheSpecifiedBands();

        // -- text -------------------------------------------------------------
        textThatFitsIsReturnedUntouched();
        textThatDoesNotFitIsCutToFit();
        theFirstSentenceIsTakenWithoutBreakingVersionNumbers();
        aBoxTooNarrowForAnEllipsisGetsNothing();
        wrappingBreaksOnSpacesAndNeverLosesText();

        // -- rectangles -------------------------------------------------------
        aRectangleSplitsWithoutDrifting();
        aRectangleKnowsWhatIsInsideIt();
        insettingNeverProducesANegativeSize();
        roundedGeometryIsBoundedAndConcentric();
        staticEdgesSnapAtEveryMinecraftGuiScale();
        everyOfferedInterfaceScaleIsOneTheRendererWillHonour();
        responsiveGridUsesAvailableWidthWithoutClipping();

        // -- colour -----------------------------------------------------------
        opacityScalingIsRelativeNotAbsolute();
        validFovIsPreservedAndOnlyCorruptionIsRepaired();

        colourChannelsPackAndClamp();

        // -- vector renderer state -------------------------------------------
        nestedClipsIntersect();
        aReplacingClipEscapesItsParentAndPopsBack();
        clipsAreStoredWhereTheyWereDrawn();
        overflowingPushesStillBalance();
        opacityMultipliesDownTheStack();
        offscreenShapesAreCulled();
        theMinecraftFrameScaleMatchesVanillaCoordinates();
        theFathomFrameScaleIsUiScale();
        alignmentFillsInAMissingHalf();
        resourceImagesAreInternedAndPaintsAreReusable();

        // -- navigation -----------------------------------------------------
        nestedScreensReturnToTheExactObjectThatOpenedThem();
        aWorldReturnIsDifferentFromMissingHistory();

        // -- frame caps -------------------------------------------------------
        configuredCapsMapExactly();
        inWorldScreensRetainTheUsersLimit();
        fathomsNoWorldScreensUseTheUsersLimit();
        onlyTheUnfocusedPolicyLowersAValidLimit();

        report();
    }

    // -----------------------------------------------------------------------
    // Vector renderer state
    // -----------------------------------------------------------------------

    static void colourChannelsPackAndClamp() {
        check(Colour.rgb(0x841C24) == 0xFF841C24, "rgb is opaque");
        check(Colour.rgba(132, 28, 36, 128) == 0x80841C24, "channels pack as ARGB");
        check(Colour.rgba(300, -5, 0, 255) == 0xFFFF0000, "out-of-range channels clamp");
        pass("colour channels pack and clamp");
    }

    static void nestedClipsIntersect() {
        CanvasState state = new CanvasState();
        state.intersectClip(0f, 0f, 100f, 100f);
        state.intersectClip(50f, -20f, 150f, 60f);
        check(state.clipLeft() == 50f && state.clipTop() == 0f
                && state.clipRight() == 100f && state.clipBottom() == 60f, "a nested clip is the intersection");
        state.intersectClip(200f, 200f, 300f, 300f);
        check(state.clipRight() >= state.clipLeft() && state.clipBottom() >= state.clipTop(),
                "disjoint clips give an empty rectangle, never a negative one");
        check(state.popClip() && state.popClip(), "each push pops");
        check(state.clipLeft() == 0f && state.clipRight() == 100f, "and the outer clip is back");
        check(state.popClip() && !state.clipped(), "until nothing is clipped");
        check(!state.popClip(), "a pop too many is reported, not a crash");
        pass("nested clips intersect");
    }

    static void aReplacingClipEscapesItsParentAndPopsBack() {
        CanvasState state = new CanvasState();
        state.intersectClip(0f, 0f, 100f, 100f);
        state.pushClip(80f, 80f, 300f, 300f);
        check(state.clipRight() == 300f, "a replacing clip ignores its parent");
        state.pushUnclipped();
        check(!state.clipped(), "an unclipped scope clips nothing");
        state.intersectClip(10f, 10f, 20f, 20f);
        check(state.clipLeft() == 10f && state.clipRight() == 20f, "and a clip inside it starts afresh");
        state.popClip();
        state.popClip();
        state.popClip();
        check(state.clipped() && state.clipRight() == 100f, "popping returns to the original clip");
        pass("a replacing clip escapes its parent and pops back");
    }

    static void clipsAreStoredWhereTheyWereDrawn() {
        CanvasState state = new CanvasState();
        state.pushTransform(10f, 20f, 2f, 0f, 0f);
        state.intersectClip(0f, 0f, 50f, 50f);
        check(state.clipLeft() == 10f && state.clipTop() == 20f
                && state.clipRight() == 110f && state.clipBottom() == 120f, "a clip is transformed when pushed");
        state.popTransform();
        check(state.clipRight() == 110f, "and a later transform does not move it");
        state.pushTransform(0f, 0f, 2f, 50f, 50f);
        check(state.frameX(50f) == 50f && state.frameX(60f) == 70f, "scaling is about the origin given");
        pass("clips are stored where they were drawn");
    }

    static void overflowingPushesStillBalance() {
        CanvasState state = new CanvasState();
        int pushes = CanvasState.MAX_DEPTH + 5;
        for (int i = 0; i < pushes; i++) {
            state.pushAlpha(0.9f);
            state.intersectClip(0f, 0f, 100f - i, 100f);
            state.pushTransform(1f, 0f, 1f, 0f, 0f);
        }
        check(state.unbalanced() == pushes * 3, "every push is counted, stored or not");
        boolean matched = true;
        for (int i = 0; i < pushes; i++) {
            matched &= state.popAlpha() && state.popClip() && state.popTransform();
        }
        check(matched, "every pop matches a push");
        check(state.unbalanced() == 0 && state.alpha() == 1f && !state.clipped() && state.offsetX() == 0f,
                "and the state is back where it started");
        pass("overflowing pushes still balance");
    }

    static void opacityMultipliesDownTheStack() {
        CanvasState state = new CanvasState();
        state.pushAlpha(0.5f);
        state.pushAlpha(0.5f);
        check(Math.abs(state.alpha() - 0.25f) < 0.0001f, "opacities multiply");
        state.pushAlpha(Float.NaN);
        check(state.alpha() == 0f, "NaN is transparent rather than everything breaking");
        state.popAlpha();
        state.popAlpha();
        check(Math.abs(state.alpha() - 0.5f) < 0.0001f, "and pop back");
        pass("opacity multiplies down the stack");
    }

    static void offscreenShapesAreCulled() {
        CanvasState state = new CanvasState();
        check(!state.culled(-50f, -50f, -10f, -10f), "nothing is culled without a clip");
        state.intersectClip(0f, 0f, 100f, 100f);
        check(state.culled(120f, 0f, 150f, 20f), "a shape right of the clip is culled");
        check(!state.culled(90f, 90f, 150f, 150f), "one overlapping it is not");
        state.pushAlpha(0f);
        check(state.culled(10f, 10f, 20f, 20f), "anything at zero opacity is culled");
        pass("offscreen shapes are culled");
    }

    static void theMinecraftFrameScaleMatchesVanillaCoordinates() {
        FrameScale scale = new FrameScale().set(FrameScale.Mode.MINECRAFT, 1920, 1080, 3, 1f);
        check(scale.ratio() == 3f, "the ratio is the GUI scale factor");
        check(scale.logicalWidth() == 640f && scale.logicalHeight() == 360f, "logical units are vanilla's scaled pixels");
        check(scale.mouseY(1079) == 0f, "the top device row is logical zero");
        pass("the Minecraft frame scale matches vanilla coordinates");
    }

    static void theFathomFrameScaleIsUiScale() {
        FrameScale scale = new FrameScale().set(FrameScale.Mode.FATHOM, 2560, 1440, 4, 1f);
        check(Math.abs(scale.ratio() - UiScale.scaleFor(1440, 1f)) < 0.0001f, "Fathom's scale ignores the GUI scale");
        check(Math.abs(scale.logicalHeight() - 720f) < 0.01f, "and keeps the reference height");
        check(new FrameScale().set(FrameScale.Mode.MINECRAFT, 0, 0, 0, 1f).ratio() >= 1f,
                "a degenerate framebuffer never divides by zero");
        pass("the Fathom frame scale is UiScale");
    }

    static void alignmentFillsInAMissingHalf() {
        check(TextAlign.normalise(TextAlign.CENTER) == (TextAlign.CENTER | TextAlign.BASELINE),
                "vertical defaults to baseline");
        check(TextAlign.normalise(TextAlign.MIDDLE) == (TextAlign.LEFT | TextAlign.MIDDLE),
                "horizontal defaults to left");
        check(TextAlign.normalise(TextAlign.LEFT | TextAlign.RIGHT | TextAlign.TOP) == (TextAlign.LEFT | TextAlign.TOP),
                "a contradictory alignment is repaired, not honoured");
        check(TextAlign.offsetX(TextAlign.RIGHT, 40f) == -40f && TextAlign.offsetX(TextAlign.CENTER, 40f) == -20f,
                "offsets follow the alignment");
        pass("alignment fills in a missing half");
    }

    static void resourceImagesAreInternedAndPaintsAreReusable() {
        check(ImageRef.resource("/a/b.png") == ImageRef.resource("/a/b.png"),
                "a resource is one reference, however often it is asked for");
        Paint paint = new Paint();
        check(paint.vertical(0f, 10f, 0xFF000000, 0x00000000) == paint && paint.kind() == Paint.Kind.LINEAR,
                "a paint reshapes itself");
        check(paint.solid(0).invisible(), "an invisible paint says so");
        boolean refused = false;
        try {
            ImageRef.pixels(true).update(java.nio.ByteBuffer.allocate(4), 1, 1);
        } catch (IllegalArgumentException expected) {
            refused = true;
        }
        check(refused, "pixels must be in a direct buffer");
        pass("resource images are interned and paints are reusable");
    }

    // -----------------------------------------------------------------------
    // Scale
    // -----------------------------------------------------------------------

    static void theScaleComesFromTheFramebufferAndNotFromMinecraft() {
        // 720p is the reference: one logical unit, one pixel.
        check(Math.abs(UiScale.scaleFor(720, 1f) - 1f) < 0.001f, "720p is 1x");
        check(Math.abs(UiScale.scaleFor(1440, 1f) - 2f) < 0.001f, "1440p is 2x");
        // And it is not stepped, which is the entire complaint about Minecraft's.
        check(Math.abs(UiScale.scaleFor(1080, 1f) - 1.5f) < 0.001f, "1080p is 1.5x, not 1 or 2");
        pass("the scale comes from the framebuffer and not from Minecraft");
    }

    static void theScaleIsClampedAtBothEnds() {
        check(UiScale.scaleFor(200, 1f) >= UiScale.MIN_SCALE, "a tiny window still gets a usable scale");
        check(UiScale.scaleFor(4320, 1.5f) <= UiScale.MAX_SCALE, "an 8K screen does not get an 9x interface");
        check(UiScale.scaleFor(720, 99f) <= UiScale.MAX_SCALE, "a silly preference is clamped");
        check(UiScale.scaleFor(720, Float.NaN) > 0f, "NaN does not make the interface vanish");
        pass("the scale is clamped at both ends");
    }

    static void aScaleThatBarelyMovedIsNotWorthRebuildingFor() {
        // Rasters are rebuilt when this says the scale changed, and a window
        // being dragged produces a stream of nearly-identical sizes.
        check(!UiScale.differs(1.5f, 1.505f), "half a percent is not a change");
        check(UiScale.differs(1.5f, 1.6f), "ten percent is");
        pass("a scale that barely moved is not worth rebuilding for");
    }

    static void logicalAndDeviceAreInverses() {
        UiScale scale = new UiScale(1920, 1080, 1f);
        check(Math.abs(scale.toLogical(scale.toDevice(37f)) - 37f) < 0.001f, "there and back");
        check(Math.abs(scale.width() * scale.scale() - 1920f) < 0.01f, "the width covers the framebuffer");
        pass("logical and device are inverses");
    }

    // -----------------------------------------------------------------------
    // Clock
    // -----------------------------------------------------------------------

    static void aStallDoesNotTeleportAnimations() {
        Clock clock = new Clock(0L);
        float delta = clock.advance(2_000_000_000L);
        check(delta <= Clock.MAX_DELTA, "two seconds of stall is clamped");
        check(delta > 0f, "and is not zero");
        pass("a stall does not teleport animations");
    }

    static void aClockThatDidNotMoveReportsNoTime() {
        Clock clock = new Clock(1000L);
        check(clock.advance(1000L) == 0f, "no time passed");
        check(clock.advance(500L) == 0f, "a clock that went backwards is not negative time");
        pass("a clock that did not move reports no time");
    }

    static void restartingDiscardsTheGapWithoutRewindingTime() {
        // Restart closes the gap a shut menu left behind. What it must not do is
        // move `seconds` backwards: every live Motion holds a timestamp on that
        // timeline, and rewinding it under them puts their start in the future,
        // where progress reads as zero and the interface freezes mid-animation.
        Clock clock = new Clock(0L);
        clock.advance(50_000_000L);
        float before = clock.seconds();
        clock.restart(10_000_000_000L);
        check(clock.seconds() == before, "restart does not rewind elapsed time");
        float delta = clock.advance(10_016_000_000L);
        check(delta > 0.015f && delta < 0.017f, "and the next frame is a frame, not ten seconds");
        check(clock.seconds() > before && clock.seconds() < before + 0.02f,
                "so the ten closed seconds never reach the animations");
        pass("restarting discards the gap without rewinding time");
    }

    // -----------------------------------------------------------------------
    // Motion
    // -----------------------------------------------------------------------

    static void aMotionIsSampledFromTimeNotCountedInFrames() {
        // The property the whole engine rests on: a motion is a function of the
        // clock, so the same elapsed time gives the same value whether it was
        // reached in six frames or sixty. A counter-based tween cannot say that,
        // and that is why hover used to be faster on a fast machine.
        Motion coarse = new Motion(0f, 0.2f);
        Motion fine = new Motion(0f, 0.2f);
        coarse.to(1f, 0f);
        fine.to(1f, 0f);
        check(coarse.value(0f) == 0f, "nothing has elapsed, nothing has moved");
        check(Math.abs(coarse.value(0.1f) - fine.value(0.1f)) < 0.0001f,
                "half way through is half way through regardless of frame rate");
        check(coarse.value(0.2f) == 1f, "and it arrives exactly");
        check(coarse.settled(0.2f), "and says so");
        pass("a motion is sampled from time, not counted in frames");
    }

    static void aReversalLeavesFromWhereItIs() {
        // Pointer in, pointer straight back out. The reversal starts from the
        // interpolated value, not from the end of a trip that never finished
        // and not from a queue of one.
        Motion motion = new Motion(0f, 0.2f);
        motion.to(1f, 0f);
        float middle = motion.value(0.1f);
        check(middle > 0f && middle < 1f, "part way there");
        motion.to(0f, 0.1f);
        check(Math.abs(motion.value(0.1f) - middle) < 0.001f,
                "no jump at the moment of reversal");
        check(motion.value(0.15f) < middle, "and it is already on its way back");
        check(motion.value(0.3f) == 0f, "arriving where it started");
        pass("a reversal leaves from where it is");
    }

    static void aMotionNeverQueuesTheTripItWasOn() {
        // Three retargets inside one duration. A queue would still be working
        // through the first of them; this finishes the last one on time.
        Motion motion = new Motion(0f, 0.2f);
        motion.to(1f, 0f);
        motion.to(0f, 0.05f);
        motion.to(1f, 0.1f);
        check(motion.settled(0.3f), "the last target is reached one duration after it was set");
        check(motion.value(0.3f) == 1f, "and it is the last target, not an earlier one");
        pass("a motion never queues the trip it was on");
    }

    static void askingForTheTargetItAlreadyHasIsIgnored() {
        // Controls call `to(hovered ? 1 : 0, now)` every frame. If that restarted
        // the animation, nothing would ever finish.
        Motion motion = new Motion(0f, 0.2f);
        motion.to(1f, 0f);
        for (int frame = 0; frame < 20; frame++) {
            motion.to(1f, frame * 0.016f);
        }
        check(motion.settled(0.2f), "a per-frame call to the same target still settles");
        pass("asking for the target it already has is ignored");
    }

    static void aTripOverrideDoesNotBecomeTheNextTripsDefault() {
        Motion motion = new Motion(0f, 0.24f, Ease.Curve.STANDARD);
        motion.to(1f, 0f, 0.10f, Ease.Curve.EXIT);
        check(motion.settled(0.10f), "the overridden trip uses its requested duration");
        motion.to(0f, 0.10f);
        check(!motion.settled(0.20f), "the next trip restores the motion's default duration");
        check(motion.value(0.34f) == 0f, "and reaches the reversed target on that default duration");
        pass("a trip override does not become the next trip's default");
    }

    static void aMotionMapsOpacityGeometryAndColour() {
        Motion motion = new Motion(0f, Timing.STANDARD);
        motion.to(1f, 0f);
        float mapped = motion.between(8f, 16f, Timing.STANDARD / 2f);
        check(mapped > 8f && mapped < 16f, "one scalar maps onto position, scale or radius");
        int colour = motion.colour(0xFF000000, 0xFFFFFFFF, Timing.STANDARD / 2f);
        check((colour & 0x00FFFFFF) != 0 && (colour & 0x00FFFFFF) != 0x00FFFFFF,
                "the same motion interpolates packed colour");
        motion.to(Float.NaN, Timing.STANDARD / 2f);
        check(motion.target() == 1f, "an invalid target cannot poison a live animation");
        pass("a motion maps opacity, geometry and colour");
    }

    static void anImmediateCloseCannotFinishOpeningFirst() {
        Transition transition = new Transition(Timing.SCREEN_ENTER, Timing.SCREEN_EXIT);
        transition.open(0f);
        float visible = transition.visibility(0.04f);
        transition.close(0.04f);
        check(Math.abs(transition.visibility(0.04f) - visible) < 0.0001f,
                "closing begins at the exact visible value");
        check(transition.visibility(0.08f) <= visible,
                "entry cannot keep brightening after close was requested");
        check(transition.finished(0.04f + Timing.SCREEN_EXIT)
                        && transition.visibility(0.04f + Timing.SCREEN_EXIT) == 0f,
                "exit finishes cleanly at invisible");
        pass("an immediate close cannot finish opening first");
    }

    static void aSmoothSpringIsFrameRateIndependentAndBounded() {
        Spring coarse = Spring.smooth(0f);
        Spring fine = Spring.smooth(0f);
        coarse.update(1f, 0.1f);
        coarse.update(1f, 0.1f);
        for (int frame = 0; frame < 20; frame++) {
            fine.update(1f, 0.01f);
        }
        check(Math.abs(coarse.value() - fine.value()) < 0.0002f,
                "the same elapsed time gives the same spring position");
        check(coarse.value() >= 0f && coarse.value() <= 1f,
                "the smooth spring does not overshoot");
        float beforeReverse = coarse.value();
        coarse.update(0f, 0f);
        check(coarse.value() == beforeReverse, "retargeting a spring never snaps");
        for (int frame = 0; frame < 100; frame++) {
            coarse.update(0f, 0.01f);
        }
        check(coarse.value() == 0f && coarse.settled(0f), "a spring settles at its exact target");
        pass("a smooth spring is frame-rate independent and bounded");
    }

    /**
     * The public timing vocabulary stays small and inside the requested bands.
     */
    static void theSharedDurationsAreWithinTheSpecifiedBands() {
        band(Timing.HOVER_IN, 0.090f, 0.140f, "hover in");
        band(Timing.HOVER_OUT, 0.090f, 0.140f, "hover out");
        band(Timing.PRESS, 0.070f, 0.110f, "press");
        band(Timing.TOGGLE, 0.140f, 0.190f, "toggle");
        band(Timing.NAVIGATION, 0.160f, 0.220f, "navigation");
        band(Timing.PANEL, 0.180f, 0.260f, "panel");
        band(Timing.SCREEN_ENTER, 0.180f, 0.260f, "screen open");
        band(Timing.SCREEN_EXIT, 0.090f, 0.180f, "screen close");
        band(Timing.TOOLTIP, 0.100f, 0.150f, "tooltip");
        pass("the shared durations are within the specified bands");
    }

    private static void band(float value, float low, float high, String name) {
        check(value >= low && value <= high,
                name + " is " + value + "s, outside " + low + "-" + high + "s");
    }

    static void theCurveStartsFastAndEndsExactly() {
        check(Ease.out(0f) == 0f, "starts at zero");
        check(Ease.out(1f) == 1f, "ends at one");
        check(Ease.out(0.25f) > 0.5f, "and is more than half done a quarter of the way in");
        check(Ease.exit(0.1f) > 0.1f, "exit responds on its first visible sample");
        check(Ease.out(-5f) == 0f && Ease.out(5f) == 1f, "outside the range is clamped");
        pass("the curve starts fast and ends exactly");
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    static void textThatFitsIsReturnedUntouched() {
        Renderer ui = new FakeRenderer();
        String text = "Coordinates";
        check(Text.fit(ui, text, Type.BODY, 200f) == text, "the same instance, not a copy");
        check(!Text.overflows(ui, text, Type.BODY, 200f), "and it does not overflow");
        pass("text that fits is returned untouched");
    }

    static void textThatDoesNotFitIsCutToFit() {
        Renderer ui = new FakeRenderer();
        // Six units per character: "Coordinates" is 66, and 40 units holds six
        // characters including the ellipsis.
        String cut = Text.fit(ui, "Coordinates", Type.BODY, 40f);
        check(cut.endsWith(Text.ELLIPSIS), "it is marked as cut");
        check(ui.measure(cut, Type.BODY) <= 40f, "and it actually fits, which is the whole point");
        pass("text that does not fit is cut to fit");
    }

    static void theFirstSentenceIsTakenWithoutBreakingVersionNumbers() {
        check("Vanilla issues one draw call per glyph.".equals(Text.firstSentence(
                        "Vanilla issues one draw call per glyph. Batching a text run into a"
                                + " single buffer turns a line of chat into one call.")),
                "a paragraph becomes its opening claim");
        check("Targets Minecraft 1.8.9 only.".equals(
                        Text.firstSentence("Targets Minecraft 1.8.9 only.")),
                "a version number is not a sentence boundary");
        check("Already one sentence.".equals(Text.firstSentence("Already one sentence.")),
                "one sentence is returned whole");
        check("".equals(Text.firstSentence(null)), "and null is not a crash");
        pass("the first sentence is taken without breaking version numbers");
    }

    static void aBoxTooNarrowForAnEllipsisGetsNothing() {
        Renderer ui = new FakeRenderer();
        check(Text.fit(ui, "Coordinates", Type.BODY, 3f).isEmpty(), "nothing rather than an overflow");
        check(Text.fit(ui, "anything", Type.BODY, 0f).isEmpty(), "no room, no text");
        pass("a box too narrow for an ellipsis gets nothing");
    }

    static void wrappingBreaksOnSpacesAndNeverLosesText() {
        Renderer ui = new FakeRenderer();
        String source = "the quick brown fox jumps";
        List<String> lines = Text.wrap(ui, source, Type.BODY, 60f);
        check(!lines.isEmpty(), "something came back");
        StringBuilder joined = new StringBuilder();
        for (String line : lines) {
            check(ui.measure(line, Type.BODY) <= 60f, "every line fits: " + line);
            if (joined.length() > 0) {
                joined.append(' ');
            }
            joined.append(line);
        }
        check(joined.toString().equals(source), "and no word was lost: " + joined);
        pass("wrapping breaks on spaces and never loses text");
    }

    // -----------------------------------------------------------------------
    // Rectangles
    // -----------------------------------------------------------------------

    static void aRectangleSplitsWithoutDrifting() {
        // The layout is built by cutting rectangles up, so the pieces have to
        // add back up to the whole. A half-unit lost per cut is a control that
        // does not line up with the one above it three rows later.
        Rect area = new Rect(10f, 20f, 300f, 200f);
        Rect head = area.top(48f);
        Rect rest = area.belowTop(48f);
        check(head.height + rest.height == area.height, "a horizontal cut loses nothing");
        check(head.bottom() == rest.y, "and leaves no gap");

        Rect left = area.left(120f);
        Rect right = area.rightOf(120f);
        check(left.width + right.width == area.width, "a vertical cut loses nothing");
        check(left.right() == right.x, "and leaves no gap");
        pass("a rectangle splits without drifting");
    }

    static void aRectangleKnowsWhatIsInsideIt() {
        Rect area = new Rect(10f, 10f, 100f, 50f);
        check(area.contains(10f, 10f), "the top-left corner is inside");
        check(!area.contains(110f, 60f), "the bottom-right corner is not");
        check(!area.contains(9.9f, 30f), "just outside is outside");
        check(area.centreX() == 60f && area.centreY() == 35f, "and it knows its own middle");

        Rect clipped = area.intersect(new Rect(60f, 0f, 100f, 100f));
        check(clipped.x == 60f && clipped.right() == 110f, "an intersection is the overlap");
        check(area.intersect(new Rect(500f, 500f, 10f, 10f)).empty(), "and nothing when there is none");
        pass("a rectangle knows what is inside it");
    }

    static void insettingNeverProducesANegativeSize() {
        // A narrow window insets a small rectangle to nothing. Nothing is fine;
        // a negative width would draw a quad with its corners the wrong way
        // round, which is invisible until it is not.
        Rect tiny = new Rect(0f, 0f, 10f, 10f);
        check(tiny.inset(20f).width == 0f, "no negative width");
        check(tiny.inset(20f).height == 0f, "no negative height");
        check(tiny.inset(20f).empty(), "and it says it is empty");
        pass("insetting never produces a negative size");
    }

    static void roundedGeometryIsBoundedAndConcentric() {
        check(RoundedGeometry.radius(20f, 24f, 80f) == 12f,
                "a radius cannot exceed half the shorter side");
        check(RoundedGeometry.radius(Float.NaN, 40f, 20f) == 0f,
                "an invalid radius cannot poison a path");
        check(RoundedGeometry.radius(Float.POSITIVE_INFINITY, 40f, 20f) == 10f,
                "an unbounded pill request still resolves to physical geometry");
        check(RoundedGeometry.pill(80f, 22f) == 11f,
                "a pill is exactly half its height when height is shorter");
        check(RoundedGeometry.insetRadius(12f, 120f, 36f, 2f) == 10f,
                "a two-unit inset subtracts two units from the outer arc");
        check(RoundedGeometry.insetRadius(20f, 18f, 18f, 5f) == 4f,
                "an already-clamped outer arc remains concentric when inset");
        pass("rounded geometry is bounded and concentric");
    }

    static void staticEdgesSnapAtEveryMinecraftGuiScale() {
        for (int scale = 1; scale <= 4; scale++) {
            float snapped = RoundedGeometry.snap(10.37f, scale);
            check(Math.abs(snapped * scale - Math.round(snapped * scale)) < 0.0001f,
                    "a snapped edge lands on a device pixel at GUI scale " + scale);
            check(RoundedGeometry.radius(9f, 100f, 36f) == 9f,
                    "semantic radii stay in logical units at GUI scale " + scale);
        }
        pass("static edges snap at every Minecraft GUI scale");
    }

    // -----------------------------------------------------------------------
    // Colour
    // -----------------------------------------------------------------------

    static void opacityScalingIsRelativeNotAbsolute() {
        // Fading a panel must not flatten the opacity every part of it was
        // designed with.
        int faint = Colour.argb(0.2f, 0xFFFFFF);
        int halved = Colour.scaleAlpha(faint, 0.5f);
        check(Math.abs(Colour.alpha(halved) - 0.1f) < 0.01f, "a fifth, halved, is a tenth");
        check(Colour.invisible(Colour.scaleAlpha(faint, 0f)), "and zero is invisible");
        pass("opacity scaling is relative, not absolute");
    }

    static void validFovIsPreservedAndOnlyCorruptionIsRepaired() {
        check(VideoOptionDomain.fov(30f) == 30f, "the valid lower endpoint is preserved");
        check(VideoOptionDomain.fov(83.25f) == 83.25f, "a valid user choice is preserved exactly");
        check(VideoOptionDomain.fov(110f) == 110f, "the valid upper endpoint is preserved");
        check(VideoOptionDomain.fov(1f) == VideoOptionDomain.NORMAL_FOV,
                "the observed one-degree corruption is repaired to normal");
        check(VideoOptionDomain.fov(Float.NaN) == VideoOptionDomain.NORMAL_FOV,
                "non-finite corruption is repaired too");
        check(VideoOptionDomain.fovFraction(30f) == 0f, "30 degrees maps to the slider start");
        check(VideoOptionDomain.fovFraction(70f) == 0.5f, "70 degrees maps to the slider midpoint");
        check(VideoOptionDomain.fovDegrees(0.5d) == 70f, "the midpoint writes 70 degrees, not 0.5");
        check(VideoOptionDomain.fovDegrees(1d) == 110f, "the endpoint writes 110 degrees");
        pass("valid FOV is preserved and only impossible data is repaired");
    }

    // -----------------------------------------------------------------------
    // Navigation
    // -----------------------------------------------------------------------

    static void nestedScreensReturnToTheExactObjectThatOpenedThem() {
        NavigationHistory<Object> history = new NavigationHistory<Object>();
        Object mainMenu = new Object();
        Object hub = new Object();
        Object editor = new Object();

        history.push(hub, mainMenu);
        history.push(editor, hub);
        NavigationHistory.Return<Object> fromEditor = history.back(editor);
        NavigationHistory.Return<Object> fromHub = history.back(hub);
        check(fromEditor.found() && fromEditor.target() == hub, "editor returns to the same hub object");
        check(fromHub.found() && fromHub.target() == mainMenu, "hub returns to the same Fathom menu object");
        check(history.size() == 0, "both transitions were consumed once");
        pass("nested screens return to the exact object that opened them");
    }

    static void aWorldReturnIsDifferentFromMissingHistory() {
        NavigationHistory<Object> history = new NavigationHistory<Object>();
        Object hub = new Object();
        Object unrelated = new Object();
        history.push(hub, null);

        check(!history.back(unrelated).found(), "an unrelated replacement cannot steal the return target");
        NavigationHistory.Return<Object> world = history.back(hub);
        check(world.found() && world.target() == null, "a successful null target means return to the world");
        check(!history.back(hub).found(), "a second back is missing rather than another world return");
        pass("a world return is different from missing history");
    }

    // -----------------------------------------------------------------------
    // Frame caps
    // -----------------------------------------------------------------------

    static void configuredCapsMapExactly() {
        check(FrameCapPolicy.UNFOCUSED_CAP == 30, "and the unfocused cap is no longer 10");
        check(FrameCapPolicy.capFor("10") == 10, "10");
        check(FrameCapPolicy.capFor("30") == 30, "30");
        check(FrameCapPolicy.capFor("120") == 120, "120");
        check(FrameCapPolicy.capFor("Unlimited") == 0, "unlimited is no cap");
        check(FrameCapPolicy.capFor("nonsense") == 0, "and so is a value nobody recognises");
        pass("configured frame caps map exactly");
    }

    static void inWorldScreensRetainTheUsersLimit() {
        int[] userLimits = {30, 60, 144, 260};
        for (int limit : userLimits) {
            check(FrameCapPolicy.effective(limit, limit, true, true, 30, true, false) == limit,
                    "an in-world screen retains " + limit);
        }
        pass("inventory, pause, chat and containers retain the user's limit");
    }

    static void fathomsNoWorldScreensUseTheUsersLimit() {
        check(FrameCapPolicy.effective(30, 260, true, true, 30, false, true) == 260,
                "Fathom's main menu is not pinned to vanilla's title-screen 30");
        check(FrameCapPolicy.effective(30, 60, true, true, 30, false, true) == 60,
                "a real user limit is respected");
        check(FrameCapPolicy.effective(30, 260, true, true, 30, false, false) == 30,
                "vanilla's own title screen remains vanilla");
        pass("Fathom no-world screens use the user's limit");
    }

    static void onlyTheUnfocusedPolicyLowersAValidLimit() {
        check(FrameCapPolicy.effective(240, 240, false, true, 30, true, false) == 30,
                "enabled and unfocused lowers the limit");
        check(FrameCapPolicy.effective(240, 240, false, false, 30, true, false) == 240,
                "disabled contributes nothing");
        check(FrameCapPolicy.effective(240, 240, true, true, 30, true, false) == 240,
                "focused contributes nothing");
        check(FrameCapPolicy.effective(240, 240, false, true, 0, true, false) == 240,
                "unlimited contributes nothing");
        pass("only the unfocused policy lowers a valid limit");
    }

    static void responsiveGridUsesAvailableWidthWithoutClipping() {
        check(ResponsiveGrid.columns(900f, 190f, 12f, 4) == 4,
                "a wide Hub uses four cards");
        check(ResponsiveGrid.columns(640f, 190f, 12f, 4) == 3,
                "a medium Hub uses three cards");
        check(ResponsiveGrid.columns(430f, 190f, 12f, 4) == 2,
                "a narrow Hub uses two cards");
        check(ResponsiveGrid.columns(170f, 190f, 12f, 4) == 1,
                "a constrained Hub falls back to one card");
        float width = ResponsiveGrid.cellWidth(900f, 12f, 4);
        check(Math.abs(width * 4f + 36f - 900f) < 0.001f,
                "cells and gutters consume exactly the available width");
        check(ResponsiveGrid.contentHeight(7, 3, 128f, 12f) == 408f,
                "scroll height counts complete rows and only internal gutters");
        pass("responsive grid uses available width without clipping");
    }

    // -----------------------------------------------------------------------
    // A renderer that measures and draws nothing
    // -----------------------------------------------------------------------

    /**
     * Six units per character, at every size.
     *
     * <p>Deliberately not proportional: a test about fitting should say what
     * fits without depending on a font file being present, and the code under
     * test only ever asks this object how wide something is.
     */
    static final class FakeRenderer implements Renderer {
        static final float PER_CHARACTER = 6f;

        @Override
        public float measure(String text, Type type) {
            return text == null ? 0f : text.length() * PER_CHARACTER;
        }

        @Override
        public float lineHeight(Type type) {
            return type.size() * 1.4f;
        }

        @Override
        public float ascent(Type type) {
            return type.size();
        }

        @Override
        public float descent(Type type) {
            return type.size() * 0.25f;
        }

        @Override
        public float text(String text, float x, float baselineY, Type type, int argb) {
            return measure(text, type);
        }

        @Override
        public float textCentred(String text, float centreX, float baselineY, Type type, int argb) {
            return measure(text, type);
        }

        @Override
        public float textRight(String text, float rightX, float baselineY, Type type, int argb) {
            return measure(text, type);
        }

        @Override
        public void fill(float left, float top, float right, float bottom, int argb) {}

        @Override
        public void rounded(float left, float top, float right, float bottom, float radius, int argb) {}

        @Override
        public void gradient(
                float left, float top, float right, float bottom, float radius, int topArgb, int bottomArgb) {}

        @Override
        public void outline(
                float left, float top, float right, float bottom, float radius, float thickness, int argb) {}

        @Override
        public void shadow(float left, float top, float right, float bottom, float radius, float spread, int argb) {}

        @Override
        public void icon(Icon icon, float x, float y, float size, int argb) {}

        @Override
        public void clip(float left, float top, float right, float bottom) {}

        @Override
        public void unclip() {}

        @Override
        public void pushAlpha(float alpha) {}

        @Override
        public void popAlpha() {}

        @Override
        public void pushTransform(float translateX, float translateY) {}

        @Override
        public void pushTransform(float translateX, float translateY, float scale, float originX, float originY) {}

        @Override
        public void popTransform() {}

        @Override
        public float width() {
            return 1280f;
        }

        @Override
        public float height() {
            return 720f;
        }

        @Override
        public float scale() {
            return 1f;
        }
    }

    // -----------------------------------------------------------------------

    /**
     * Every scale the Hub offers has to be one {@link UiScale} will actually
     * use.
     *
     * <p>`UiScale.scaleFor` clamps its preference to MIN_PREFERENCE and
     * MAX_PREFERENCE, so an option outside that range would be a control that
     * appears to do something and does nothing -- the user picks 200%, the
     * interface moves to 150%, and nothing says why. The two lists are in
     * different classes for good reasons, so this is what keeps them in step.
     */
    static void everyOfferedInterfaceScaleIsOneTheRendererWillHonour() {
        String[] labels = net.fathom.optimizations.core.module.ClientPrefs.SCALE_CHOICES;
        net.fathom.optimizations.core.module.ClientPrefs prefs =
                new net.fathom.optimizations.core.module.ClientPrefs();

        check(labels.length > 1, "there should be more than just Auto");
        for (int index = 0; index < labels.length; index++) {
            prefs.setScaleChoice(index);
            float wanted = prefs.uiScale();
            check(wanted >= UiScale.MIN_PREFERENCE && wanted <= UiScale.MAX_PREFERENCE,
                    labels[index] + " is outside what the renderer will honour");
            // And the round trip through the file, since the choice is stored
            // by its label rather than its index.
            prefs.setScaleChoice(0);
            prefs.setScaleLabel(labels[index]);
            check(prefs.scaleChoice() == index, labels[index] + " did not survive being saved");
        }
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            failures.add(what);
            System.out.println("  FAIL " + what);
            return;
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

    private UiTests() {}
}
