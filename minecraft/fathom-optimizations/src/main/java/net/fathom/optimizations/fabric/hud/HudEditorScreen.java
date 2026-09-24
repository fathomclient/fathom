package net.fathom.optimizations.fabric.hud;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotUtils;

import net.fathom.optimizations.core.hud.Anchor;
import net.fathom.optimizations.core.hud.EditorPrefs;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.hud.Snap;
import net.fathom.optimizations.core.hud.Widget;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.anim.Transition;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.GlRenderer;
import net.fathom.optimizations.fabric.gfx.MinecraftGuiSize;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.FathomComponents;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.ui.VisualEffects;

/**
 * The HUD editor: arrange your screen while looking at your screen.
 *
 * <h2>Why this is an overlay on the game and not a page in the hub</h2>
 *
 * <p>Because "is this in the way" is a question about the scene behind it. The
 * chat is in the bottom left, the hotbar is in the bottom centre, and a widget
 * that looks fine on a panel sits on top of one of them in a real game. So the
 * editor draws the actual HUD over the actual world, and the world keeps
 * running — half of what is on screen only means anything while it does.
 *
 * <h2>Two coordinate systems, on purpose</h2>
 *
 * <p>The widgets are drawn by the game's own HUD pass in Minecraft's scaled
 * space, because that is where the hotbar and the chat they have to line up
 * with are. The editor's own chrome — toolbar, inspector, outlines — is drawn by
 * Fathom's renderer in logical units, because that is where the type is legible.
 * {@link #mcToLogical} converts between them, in one place, so nothing else in
 * this file has to think about it.
 *
 * <h2>What the drag actually changes</h2>
 *
 * <p>Not the position: the <em>anchor and offset</em>. On release the widget
 * takes the anchor of the screen third it was dropped in, and the offset is
 * recomputed from that anchor's edges. A widget dragged to the bottom-right
 * becomes bottom-right-anchored, so it is still in the bottom-right at another
 * resolution or GUI scale. Storing the raw pixel position instead is why so many
 * HUDs scatter when the window is resized.
 *
 * <h2>The four aids, and what each is for</h2>
 *
 * <ul>
 *   <li><b>Snapping</b> pulls edges and centres together, and draws the line
 *       that caused it. The geometry is in {@code core}, where the case that
 *       matters — the nearer of two candidates wins — has a test a screenshot
 *       could not give.
 *   <li><b>The grid</b> is for arranging several widgets into a column. Off by
 *       default: a grid over a game is visual noise for the nine times out of
 *       ten somebody is moving one thing.
 *   <li><b>Safe bounds</b> mark where vanilla draws. Not a fence — you may put a
 *       widget over the hotbar if you want to — but the reason a widget
 *       "disappears in game" is almost always that it is under one of these.
 *   <li><b>The inspector</b> is for the things a drag cannot express: scale,
 *       opacity, which anchor, and getting one back that has been hidden.
 * </ul>
 */
public final class HudEditorScreen extends Screen implements FathomScreen {

    /** Grid spacing, in Minecraft's scaled pixels — the space widgets live in. */
    private static final int GRID = 10;

    /** The corner handle's side, in scaled pixels. */
    private static final int HANDLE = 6;

    private static final float TOOLBAR_HEIGHT = 52f;

    /**
     * The toolbar's buttons, as widths and the gap that follows each.
     *
     * <p>One array, read by both the pass that draws the bar and the pass that
     * decides whether a click landed on it. They were two copies of the same
     * arithmetic and drifted apart the first time a button changed width, which
     * is exactly the divergence {@link Rect} exists to prevent.
     */
    private static final float[] TOOL_WIDTHS = {96f, 92f, 110f, 116f, 100f, 104f};
    private static final float[] TOOL_GAPS = {Skin.S3, Skin.S2, Skin.S2, Skin.S2, Skin.S2, Skin.S4};
    private static final float PICKER_WIDTH = 200f;
    // Wide enough for "Reset position" beside "Reset widget", tall enough for
    // the row they sit in. Both were measured against the panel in the game
    // after the buttons were added and drew outside it.
    private static final float INSPECTOR_WIDTH = 272f;
    private static final Anchor[] ANCHORS = Anchor.values();
    private static final String[] ANCHOR_LABELS = anchorLabels();

    private static String[] anchorLabels() {
        String[] labels = new String[ANCHORS.length];
        for (int index = 0; index < ANCHORS.length; index++) {
            labels[index] = ANCHORS[index].label();
        }
        return labels;
    }
    private static final float INSPECTOR_HEIGHT = 268f;

    private final FathomHud hud;
    private final Controls controls = new Controls();
    private final Pointer pointer = new Pointer();
    private final Transition transition = new Transition(Timing.SCREEN_ENTER, Timing.SCREEN_EXIT);

    private String dragging;
    private String resizing;
    private String selected;
    private int grabOffsetX;
    private int grabOffsetY;
    private float resizeStartScale;
    private int resizeStartX;
    private List<Snap.Guide> guides = new ArrayList<Snap.Guide>();

    private final EditorPrefs prefs;
    /** Where the dragged widget is, drawn beside it while it moves. */
    private String readout = "";
    private String problem;
    private String note = "";
    private int captureFrames;
    private boolean captured;
    private boolean transitionStarted;
    private boolean closing;

    /** Logical units per Minecraft scaled pixel, recomputed every frame. */
    private float mcToLogical = 1f;
    private int screenWidth;
    private int screenHeight;

    /**
     * Every widget's rectangle for this frame, measured once.
     *
     * <p>Hit testing, snapping, outlines and the yield test all want the same
     * list, and measuring a widget means measuring its text. This was being
     * recomputed four or five times a frame for one answer.
     */
    private List<FathomHud.Positioned> frameBoxes;

    /**
     * Whether the toolbar has stepped aside for the widget being worked on.
     *
     * <p>The bar sits over the bottom of the screen, which is also where the
     * hotbar is and therefore where people put widgets. While the widget under
     * the pointer -- or the one being dragged -- reaches into the strip, the bar
     * fades and stops taking clicks, so the widget underneath can be grabbed,
     * seen and dropped. Without this a widget dropped under a toolbar button
     * could never be picked up again.
     */
    private boolean barYields;

    /** The same, for the inspector panel in the top right. */
    private boolean inspectorYields;

    public HudEditorScreen(FathomHud hud) {
        this.hud = hud;
        this.prefs = EditorPrefs.read(hud.gameDirectory());
    }

    /**
     * Whether the modifier that turns every aid off is held.
     *
     * <p>Alt, which is what every design tool uses for "I mean exactly here".
     * While it is down nothing snaps, nothing is pushed off its neighbours and
     * the grid does not round — so a widget can be put one pixel from another on
     * purpose, which is impossible while the editor is helping.
     */
    private static boolean freehand() {
        return Keyboard.isKeyDown(Keyboard.KEY_LMENU) || Keyboard.isKeyDown(Keyboard.KEY_RMENU);
    }

    @Override
    public void init() {
        buttons.clear();
        Gfx.restartClock();
        pointer.reset();
        if (!transitionStarted) {
            transition.open(Gfx.clock().seconds());
            transitionStarted = true;
        }
        controls.setInputEnabled(!closing);
        if ("hud-editor".equalsIgnoreCase(System.getProperty("fathom.captureUi", ""))
                && selected == null && !hud.registry().all().isEmpty()) {
            selected = hud.registry().all().get(0).name();
        }
    }

    /**
     * The world keeps running behind the editor.
     *
     * <p>Deliberate: half the widgets show something that only moves while the
     * game does. A frozen scene would make the FPS readout meaningless and the
     * sprint indicator dead, which is the opposite of arranging a HUD against
     * what it will actually sit over.
     */
    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    // -----------------------------------------------------------------------
    // The frame
    // -----------------------------------------------------------------------

    @Override
    public void render(int ignoredX, int ignoredY, float ignoredDelta) {
        MinecraftGuiSize viewport = MinecraftGuiSize.get(client);
        screenWidth = viewport.width();
        screenHeight = viewport.height();

        // The widgets themselves, in the game's own space and font. Drawn before
        // Fathom takes the GL state, because it is the game's pass.
        hud.render(client, true);

        float delta = Gfx.begin(client, Runtime.uiScale());
        boolean restorePrevious = false;
        try {
            GlRenderer ui = Gfx.renderer(client);
            pointer.poll(client, ui);
            controls.begin(pointer, delta);
            mcToLogical = (client.width / (float) Math.max(1, screenWidth)) / ui.scale();

            frameBoxes = null;
            int mouseX = toScaledX(pointer.x());
            int mouseY = toScaledY(pointer.y());
            // Decided before anything reads it: the bar's own controls consult it
            // to know whether to respond, and so does the widget hit test.
            barYields = chromeYields(
                    Rect.of(0f, ui.height() - TOOLBAR_HEIGHT, ui.width(), ui.height()), mouseX, mouseY);
            inspectorYields = selected != null && chromeYields(inspectorArea(ui), mouseX, mouseY);

            input(ui);

            if (dragging != null) {
                drag(mouseX, mouseY);
            } else if (resizing != null) {
                resize(mouseX);
            }

            float now = Gfx.clock().seconds();
            float shown = transition.visibility(now);
            float enter = transition.enter(now);
            float exit = transition.exit(now);
            ui.pushAlpha(shown);
            ui.pushTransform(0f, (1f - enter) * 8f + exit * 3f,
                    0.990f + enter * 0.010f - exit * 0.004f,
                    ui.width() / 2f, ui.height() / 2f);

            // A very light wash, so the editor is distinguishable from the game
            // without hiding what the HUD has to sit over.
            ui.fill(0f, 0f, ui.width(), ui.height(), Skin.argb(0.22f, 0x000000));

            if (prefs.grid()) {
                grid(ui);
            }
            if (prefs.bounds()) {
                safeBounds(ui);
            }
            drawGuides(ui);

            toolbar(ui);
            if (selected != null) {
                inspector(ui);
            }
            // After the chrome, not before it. A widget under the toolbar or the
            // inspector is exactly the one somebody is trying to move, and an
            // outline drawn underneath the panel covering it is no help at all.
            outlines(ui, mouseX, mouseY);
            controls.drawOverlays(ui);
            controls.end();
            ui.popTransform();
            ui.popAlpha();
            restorePrevious = transition.finished(now);
        } finally {
            Gfx.end();
        }
        if (restorePrevious && !FathomNavigation.back(client, this)) {
            closing = false;
            controls.setInputEnabled(true);
            problem = "navigation history was lost";
            transition.open(Gfx.clock().seconds());
        }
        captureSettledFrame();
    }

    private void captureSettledFrame() {
        captureFrames++;
        if (captured || !"hud-editor".equalsIgnoreCase(System.getProperty("fathom.captureUi", ""))
                || captureFrames < 90) {
            return;
        }
        captured = true;
        String quality = Runtime.highQualityUi() ? "hq" : "fast";
        String name = "fathom-hud-editor-" + quality + "-"
                + client.width + "x" + client.height + ".png";
        ScreenshotUtils.saveScreenshot(client.runDirectory, name,
                client.width, client.height, client.getFramebuffer());
        System.out.println("[Fathom] UI QA hud-editor " + quality + ": mean "
                + Gfx.frames().uiMeanMillis() + " ms.");
        client.scheduleStop();
    }

    /**
     * This frame's widget rectangles, measured on first ask and kept.
     *
     * <p>Invalidated by {@link #place}, so the outline drawn after a drag step
     * is where the widget now is rather than where it was at the top of the
     * frame.
     */
    private List<FathomHud.Positioned> boxes() {
        if (frameBoxes == null) {
            frameBoxes = hud.boxes(client, screenWidth, screenHeight);
        }
        return frameBoxes;
    }

    /** The topmost widget under a point in Minecraft's scaled space, or null. */
    private FathomHud.Positioned widgetAt(int scaledX, int scaledY) {
        List<FathomHud.Positioned> positioned = boxes();
        for (int index = positioned.size() - 1; index >= 0; index--) {
            if (positioned.get(index).box.contains(scaledX, scaledY)) {
                return positioned.get(index);
            }
        }
        return null;
    }

    /**
     * The widget a yield would be for: the one being worked on, or the one under
     * the pointer.
     */
    private FathomHud.Positioned inPlay(int scaledX, int scaledY) {
        if (dragging != null) {
            return find(dragging);
        }
        if (resizing != null) {
            return find(resizing);
        }
        return widgetAt(scaledX, scaledY);
    }

    /**
     * Whether a piece of chrome overlaps the widget in play.
     *
     * <p>Only then, so the bar does not blink every time the pointer crosses a
     * widget somewhere else on the screen.
     */
    private boolean chromeYields(Rect chrome, int scaledX, int scaledY) {
        FathomHud.Positioned active = inPlay(scaledX, scaledY);
        if (active == null) {
            return false;
        }
        Rect widget = Rect.of(
                toLogical(active.box.x),
                toLogical(active.box.y),
                toLogical(active.box.right()),
                toLogical(active.box.bottom()));
        return !chrome.intersect(widget).empty();
    }

    private float toLogical(float scaled) {
        return scaled * mcToLogical;
    }

    private int toScaledX(float logical) {
        return Math.round(logical / Math.max(0.0001f, mcToLogical));
    }

    private int toScaledY(float logical) {
        return Math.round(logical / Math.max(0.0001f, mcToLogical));
    }

    /**
     * The grid.
     *
     * <p>Dots at intersections rather than lines. A full grid of lines over a
     * game is a cage; dots give the same alignment cue and leave the scene
     * visible, which is the entire reason the editor is over the game.
     */
    private void grid(GlRenderer ui) {
        int colour = Skin.argb(0.18f, 0xFFFFFF);
        float dot = Math.max(1f, mcToLogical);
        for (int x = GRID; x < screenWidth; x += GRID) {
            for (int y = GRID; y < screenHeight; y += GRID) {
                float lx = toLogical(x);
                float ly = toLogical(y);
                ui.fill(lx, ly, lx + dot, ly + dot, colour);
            }
        }
    }

    /**
     * Where vanilla draws.
     *
     * <p>Three regions, each marked and named: the hotbar and its bars along the
     * bottom centre, the chat along the bottom left, and the debug screen across
     * the top when F3 is held. A widget under any of them is a widget its owner
     * will describe as "not working".
     */
    private void safeBounds(GlRenderer ui) {
        int colour = Skin.argb(0.34f, 0xE0505C);

        // The hotbar: 182 wide plus the health and hunger rows above it. These
        // are vanilla's own numbers, not estimates.
        int hotbarWidth = 182;
        int hotbarLeft = screenWidth / 2 - hotbarWidth / 2;
        dashed(ui, hotbarLeft, screenHeight - 39, hotbarLeft + hotbarWidth, screenHeight, colour);
        label(ui, "Hotbar", toLogical(hotbarLeft) + 2f, toLogical(screenHeight - 39) - 18f);

        dashed(ui, 0, screenHeight - 48 - 80, 320, screenHeight - 48, Skin.argb(0.20f, 0xE0505C));
        label(ui, "Chat", 4f, toLogical(screenHeight - 48 - 80) - 18f);

        dashed(ui, 0, 0, screenWidth, 80, Skin.argb(0.14f, 0xE0505C));
        label(ui, "F3", 4f, toLogical(80) + 4f);
    }

    private void label(GlRenderer ui, String text, float x, float y) {
        ui.text(text, x, y + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.argb(0.65f, 0xE0505C));
    }

    /** A dashed rectangle, so it reads as a boundary rather than a box. */
    private void dashed(GlRenderer ui, int left, int top, int right, int bottom, int colour) {
        float thickness = Math.max(1f, mcToLogical);
        float dash = 8f;
        float gap = 6f;
        float l = toLogical(left);
        float t = toLogical(top);
        float r = toLogical(right);
        float b = toLogical(bottom);
        for (float x = l; x < r; x += dash + gap) {
            ui.fill(x, t, Math.min(x + dash, r), t + thickness, colour);
            ui.fill(x, b - thickness, Math.min(x + dash, r), b, colour);
        }
        for (float y = t; y < b; y += dash + gap) {
            ui.fill(l, y, l + thickness, Math.min(y + dash, b), colour);
            ui.fill(r - thickness, y, r, Math.min(y + dash, b), colour);
        }
    }

    private void drawGuides(GlRenderer ui) {
        int colour = Skin.argb(0.65f, 0xE0505C);
        float thickness = Math.max(1f, mcToLogical);
        for (Snap.Guide guide : guides) {
            if (guide.vertical) {
                float x = toLogical(guide.position);
                ui.fill(x, 0f, x + thickness, ui.height(), colour);
            } else {
                float y = toLogical(guide.position);
                ui.fill(0f, y, ui.width(), y + thickness, colour);
            }
        }
    }

    private void outlines(GlRenderer ui, int mouseX, int mouseY) {
        for (FathomHud.Positioned positioned : boxes()) {
            boolean hovered = positioned.box.contains(mouseX, mouseY);
            boolean active = positioned.widget.id().equals(dragging)
                    || positioned.widget.id().equals(resizing)
                    || positioned.widget.id().equals(selected);
            if (!hovered && !active) {
                continue;
            }
            float l = toLogical(positioned.box.x);
            float t = toLogical(positioned.box.y);
            float r = toLogical(positioned.box.right());
            float b = toLogical(positioned.box.bottom());
            ui.outline(l - 2f, t - 2f, r + 2f, b + 2f, Skin.R_SMALL, Skin.HAIRLINE,
                    active ? Skin.ACCENT_STRONG : Skin.argb(0.42f, 0xFFFFFF));

            if (active) {
                // The resize handle, bottom-right, which is where every resize
                // handle has been since windows had corners.
                float handle = toLogical(HANDLE);
                ui.rounded(r - handle, b - handle, r + handle, b + handle, handle, Skin.ACCENT_STRONG);
            }
            if (active && !readout.isEmpty()
                    && positioned.widget.id().equals(dragging == null ? resizing : dragging)) {
                // Beside the widget, and below it when it is near the top, so
                // the numbers stay on screen while it is dragged into a corner.
                float labelWidth = ui.measure(readout, Type.CAPTION) + Skin.S3;
                float labelTop = t - 24f < 0f ? b + 6f : t - 24f;
                ui.rounded(l - 2f, labelTop, l - 2f + labelWidth, labelTop + 18f,
                        Skin.R_SMALL, Skin.OVERLAY);
                ui.text(readout, l + 4f, labelTop + 13f, Type.CAPTION, Skin.TEXT);
            }
            if (hovered && dragging == null && resizing == null) {
                float labelWidth = ui.measure(positioned.widget.name(), Type.CAPTION) + Skin.S3;
                ui.rounded(l - 2f, t - 24f, l - 2f + labelWidth, t - 6f, Skin.R_SMALL, Skin.OVERLAY);
                ui.text(positioned.widget.name(), l + 4f, t - 11f, Type.CAPTION, Skin.TEXT);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Chrome
    // -----------------------------------------------------------------------

    private void toolbar(GlRenderer ui) {
        float top = ui.height() - TOOLBAR_HEIGHT;
        Rect bar = Rect.of(0f, top, ui.width(), ui.height());
        Rect row = toolbarRow(ui);
        Rect[] keys = toolbarButtons(ui);

        // Stepped aside: faint, and deaf. The fade is what makes it legible as
        // "the bar has moved out of your way" rather than "the bar is broken",
        // and the shield stops a press that is grabbing the widget underneath
        // from also pressing the button it happens to be over.
        if (barYields) {
            controls.shield(bar);
        }
        ui.pushAlpha(barYields ? 0.18f : 1f);

        ui.fill(bar.x, bar.y, bar.right(), bar.bottom(), Skin.fade(Skin.CHROME, 0.94f));
        ui.fill(bar.x, bar.y, bar.right(), bar.y + Skin.HAIRLINE, Skin.CHROME_LINE_STRONG);

        if (controls.primary(ui, "edit.done", keys[0], "Done", true)) {
            close();
        }
        if (controls.secondary(ui, "edit.grid", keys[1],
                prefs.grid() ? "Grid on" : "Grid", true)) {
            prefs.setGrid(!prefs.grid());
        }
        if (controls.secondary(ui, "edit.bounds", keys[2],
                prefs.bounds() ? "Bounds on" : "Bounds", true)) {
            prefs.setBounds(!prefs.bounds());
        }
        // Snapping strength, as one cycling button rather than four: it has an
        // order, exactly one value is in force, and a dropdown for four words
        // would be the largest control on the bar.
        if (controls.secondary(ui, "edit.snap", keys[3],
                "Snap: " + prefs.snapLabel(), true)) {
            prefs.cycleSnap();
            note = prefs.snapPixels() == 0
                    ? "Snapping off. A widget lands exactly where it is dropped."
                    : "Snapping " + prefs.snapLabel().toLowerCase(java.util.Locale.ROOT)
                            + ". Hold Alt to place freely.";
        }
        if (controls.secondary(ui, "edit.dup", keys[4], "Duplicate",
                hud.profiles() != null)) {
            // A copy of the whole loadout, made live, so the dragging that follows
            // changes the copy. The HUD belongs to a profile now; it has no names
            // of its own.
            try {
                net.fathom.optimizations.core.profile.FathomProfile copy =
                        hud.profiles().duplicate(hud.profiles().liveId());
                hud.profiles().switchTo(copy.id());
                selected = null;
                note = "Now editing " + copy.name() + ".";
            } catch (net.fathom.optimizations.core.profile.ProfileException failure) {
                problem = failure.getMessage();
            }
        }
        if (controls.danger(ui, "edit.reset", keys[5], "Reset all", true)) {
            hud.resetLayout();
            selected = null;
            note = "Every widget is back where it started.";
            problem = hud.save();
        }
        float cursor = keys[5].right() + Skin.S4;

        // The profile picker, at the right where a document's name goes. The
        // layout being edited is the live profile's, so choosing another profile
        // switches the whole loadout, live, and edits that one's HUD.
        Rect picker = toolbarPicker(ui);
        net.fathom.optimizations.fabric.profile.ProfileRuntime profiles = hud.profiles();
        if (profiles != null) {
            List<net.fathom.optimizations.core.profile.FathomProfile> all = profiles.cachedProfiles();
            String[] options = new String[all.size()];
            int index = 0;
            for (int at = 0; at < all.size(); at++) {
                options[at] = all.get(at).name();
                if (all.get(at).id().equals(profiles.liveId())) {
                    index = at;
                }
            }
            int chosen = options.length == 0 ? index : controls.dropdown(ui, "edit.profile", picker, options, index);
            if (chosen != index && chosen >= 0 && chosen < options.length) {
                try {
                    profiles.switchTo(all.get(chosen).id());
                    selected = null;
                    note = "Editing " + options[chosen] + ".";
                } catch (net.fathom.optimizations.core.profile.ProfileException failure) {
                    problem = failure.getMessage();
                }
            }
        }

        String hint = selected == null
                ? "Drag to move, corner to resize, scroll to fade, Shift-scroll to scale. "
                        + "Tab steps through the widgets; hold Alt to place freely."
                : selected + " selected — arrows nudge, Shift-arrows by ten, Tab for the next, "
                        + "Delete switches it off";
        float room = picker.x - cursor - Skin.S4;
        if (room > 80f) {
            ui.text(Text.fit(ui, note.isEmpty() ? hint : note, Type.BODY, room), cursor,
                    Controls.baseline(ui, row, Type.BODY), Type.BODY,
                    note.isEmpty() ? Skin.TEXT_DISABLED : Skin.GOOD);
        }

        if (problem != null) {
            ui.text("Could not save: " + problem, Skin.S5, top - Skin.S3, Type.BODY, Skin.BAD);
        }
        ui.popAlpha();
        controls.clearShield();
    }

    /** The six buttons along the left of the bar, laid out once. */
    private Rect[] toolbarButtons(GlRenderer ui) {
        Rect row = toolbarRow(ui);
        Rect[] out = new Rect[TOOL_WIDTHS.length];
        float cursor = row.x;
        for (int index = 0; index < TOOL_WIDTHS.length; index++) {
            out[index] = new Rect(cursor, row.y, TOOL_WIDTHS[index], row.height);
            cursor += TOOL_WIDTHS[index] + TOOL_GAPS[index];
        }
        return out;
    }

    /** The profile picker, at the right of the bar. */
    private Rect toolbarPicker(GlRenderer ui) {
        return toolbarRow(ui).vcentreRight(0f, PICKER_WIDTH, toolbarRow(ui).height);
    }

    /**
     * The inspector for the selected widget.
     *
     * <p>Everything a drag cannot say: which anchor, how big, how faint, and the
     * way back for a widget that has been hidden. It sits in the top right, away
     * from the hotbar and the chat, which are the two places a widget is most
     * likely to be being moved to.
     */
    private void inspector(GlRenderer ui) {
        Widget widget = widgetNamed(selected);
        if (widget == null) {
            return;
        }
        Rect panel = inspectorArea(ui);
        if (inspectorYields) {
            controls.shield(panel);
        }
        ui.pushAlpha(inspectorYields ? 0.18f : 1f);
        try {
            inspectorBody(ui, widget, panel);
        } finally {
            ui.popAlpha();
            controls.clearShield();
        }
    }

    private void inspectorBody(GlRenderer ui, Widget widget, Rect panel) {
        Placement placement = hud.placement(widget.id());
        VisualEffects.overlayShadow(ui, panel, Skin.R_MEDIUM);
        FathomComponents.panel(ui, panel);

        Rect inner = panel.inset(Skin.S4, Skin.S4);
        ui.text(Text.fit(ui, widget.name(), Type.ROW_TITLE, inner.width), inner.x,
                inner.y + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE, Skin.TEXT);

        float y = inner.y + 30f;

        // Anchor, as a dropdown rather than a row that cycles: nine values is
        // too many to step through one press at a time.
        ui.text("Anchor", inner.x, y + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_FAINT);
        Rect anchorBox = new Rect(inner.x, y + 16f, inner.width, Skin.H_SM);
        int chosen = controls.dropdown(ui, "insp.anchor", anchorBox, ANCHOR_LABELS, placement.anchor().ordinal());
        if (chosen != placement.anchor().ordinal() && chosen >= 0 && chosen < ANCHORS.length) {
            // The offset is reset with the anchor: keeping it would send the
            // widget somewhere nobody chose, because an offset means a distance
            // from a different edge now.
            hud.place(widget.id(), placement.withAnchor(ANCHORS[chosen]).withOffset(4, 4));
            problem = hud.save();
        }
        y += 16f + Skin.H_SM + Skin.S3;

        y = sliderRow(ui, inner, y, "insp.scale", "Scale", Math.round(placement.scale() * 100) + "%",
                (placement.scale() - 0.5f) / 1.5f, widget, true);
        y = sliderRow(ui, inner, y, "insp.opacity", "Opacity", Math.round(placement.opacity() * 100) + "%",
                placement.opacity(), widget, false);

        float half = (inner.width - Skin.S2) / 2f;
        if (controls.secondary(ui, "insp.centre", new Rect(inner.x, y, half, Skin.H_SM), "Centre", true)) {
            hud.place(widget.id(), hud.placement(widget.id()).withAnchor(Anchor.CENTER).withOffset(0, 0));
            note = widget.name() + " centred.";
            problem = hud.save();
        }
        // "Hide" switches the module off, because that is the only switch.
        // Hiding a widget while leaving its module reading "on" is precisely
        // the disagreement this milestone removed.
        if (controls.secondary(ui, "insp.hide", new Rect(inner.x + half + Skin.S2, y, half, Skin.H_SM), "Hide",
                true)) {
            hideSelected();
            return;
        }
        y += Skin.H_SM + Skin.S2;

        // Position only: the anchor and the offset go back, the scale and the
        // opacity somebody spent time on stay. This is the one people want
        // after dragging a widget somewhere they cannot find it again.
        Placement current = hud.placement(widget.id());
        if (controls.secondary(ui, "insp.position", new Rect(inner.x, y, half, Skin.H_SM), "Reset position",
                true)) {
            Placement home = widget.defaultPlacement();
            hud.place(widget.id(), current
                    .withAnchor(home.anchor())
                    .withOffset(home.offsetX(), home.offsetY()));
            note = widget.name() + " is back where it started.";
            problem = hud.save();
        }
        if (controls.danger(ui, "insp.reset", new Rect(inner.x + half + Skin.S2, y, half, Skin.H_SM),
                "Reset widget", true)) {
            hud.place(widget.id(), widget.defaultPlacement());
            note = widget.name() + " reset.";
            problem = hud.save();
        }
    }

    /** One labelled slider in the inspector. Scale runs 0.5 to 2, opacity 0 to 1. */
    private float sliderRow(
            GlRenderer ui, Rect inner, float y, String id, String label, String value, float fraction,
            Widget widget, boolean scale) {
        ui.text(label, inner.x, y + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_FAINT);
        ui.textRight(value, inner.right(), y + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_MUTED);
        Rect track = new Rect(inner.x, y + 16f, inner.width, Controls.SLIDER_HEIGHT);
        double next = controls.slider(ui, id, track, fraction, true);
        if (Math.abs(next - fraction) > 0.001d) {
            Placement placement = hud.placement(widget.id());
            hud.place(widget.id(), scale
                    ? placement.withScale((float) (0.5d + next * 1.5d))
                    : placement.withOpacity((float) next));
            problem = hud.save();
        }
        return y + 16f + Controls.SLIDER_HEIGHT + Skin.S3;
    }

    private Widget widgetNamed(String name) {
        for (Widget widget : hud.registry().all()) {
            if (widget.name().equals(name)) {
                return widget;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Input, from the device
    // -----------------------------------------------------------------------

    private void input(GlRenderer ui) {
        int notches = pointer.wheelNotches();
        if (notches != 0) {
            wheel(notches);
        }

        if (pointer.released(0) && (dragging != null || resizing != null)) {
            if (dragging != null && prefs.separate() && !freehand()) {
                separate(dragging);
            }
            dragging = null;
            resizing = null;
            readout = "";
            guides = new ArrayList<Snap.Guide>();
            // Saved on release rather than on every mouse-move: one file write
            // per drag instead of one per frame.
            problem = hud.save();
        }

        if (!pointer.pressed(0)) {
            return;
        }
        float mouseX = pointer.x();
        float mouseY = pointer.y();

        // Only real controls take the click. The old test rejected the entire
        // 52-pixel strip, including the empty space between the buttons and the
        // profile picker. A widget dropped anywhere in that strip could then
        // never be selected again. The bar is visual chrome; its empty space is
        // deliberately click-through so a covered widget can be dragged out.
        if (toolbarOwns(ui, mouseX, mouseY)) {
            return;
        }
        if (selected != null && !inspectorYields && inspectorArea(ui).contains(mouseX, mouseY)) {
            return;
        }
        // An open dropdown is drawn over everything and takes its own click in
        // drawOverlays, which runs after this. Without this the same press both
        // chose an option and grabbed whatever widget was behind the list.
        if (controls.popoverOpen()) {
            return;
        }

        int scaledX = toScaledX(mouseX);
        int scaledY = toScaledY(mouseY);
        // Reversed, so the widget drawn last -- the one on top -- is the one
        // grabbed when two overlap.
        List<FathomHud.Positioned> positioned = boxes();
        for (int index = positioned.size() - 1; index >= 0; index--) {
            FathomHud.Positioned candidate = positioned.get(index);
            int hx = candidate.box.right();
            int hy = candidate.box.bottom();
            boolean onHandle = scaledX >= hx - HANDLE && scaledX <= hx + HANDLE
                    && scaledY >= hy - HANDLE && scaledY <= hy + HANDLE;

            if (onHandle && candidate.widget.name().equals(selected)) {
                resizing = candidate.widget.id();
                resizeStartScale = candidate.placement.scale();
                resizeStartX = scaledX;
                return;
            }
            if (candidate.box.contains(scaledX, scaledY)) {
                dragging = candidate.widget.id();
                selected = candidate.widget.name();
                grabOffsetX = scaledX - candidate.box.x;
                grabOffsetY = scaledY - candidate.box.y;
                return;
            }
        }
        selected = null;
    }

    private Rect toolbarRow(GlRenderer ui) {
        Rect bar = Rect.of(0f, ui.height() - TOOLBAR_HEIGHT, ui.width(), ui.height());
        return bar.inset(Skin.S5, 0f).vcentre(0f, bar.width - Skin.S5 * 2f, Skin.H_MD);
    }

    /**
     * Whether the pointer is on an actual toolbar control, not merely its backdrop.
     *
     * <p>The backdrop is deliberately click-through: a widget dropped in the
     * empty space between the buttons and the profile picker used to be
     * unreachable for the rest of the session. And when the bar has stepped
     * aside for a widget underneath it, even the buttons let the click past --
     * otherwise a widget parked under one of them would be just as stuck.
     */
    private boolean toolbarOwns(GlRenderer ui, float mouseX, float mouseY) {
        if (barYields) {
            return false;
        }
        for (Rect key : toolbarButtons(ui)) {
            if (key.contains(mouseX, mouseY)) {
                return true;
            }
        }
        return hud.profiles() != null && toolbarPicker(ui).contains(mouseX, mouseY);
    }

    private Rect inspectorArea(GlRenderer ui) {
        return new Rect(ui.width() - INSPECTOR_WIDTH - Skin.S5, Skin.S5, INSPECTOR_WIDTH, INSPECTOR_HEIGHT);
    }

    private void drag(int mouseX, int mouseY) {
        FathomHud.Positioned target = find(dragging);
        if (target == null) {
            dragging = null;
            return;
        }
        int wantedX = mouseX - grabOffsetX;
        int wantedY = mouseY - grabOffsetY;

        if (freehand()) {
            guides = new ArrayList<Snap.Guide>();
            place(target, wantedX, wantedY);
            return;
        }

        // The grid takes precedence when it is on: somebody who turned it on
        // wants columns, and snapping to a neighbour's edge instead would be the
        // editor overruling the tool they just picked.
        if (prefs.grid()) {
            wantedX = Math.round(wantedX / (float) GRID) * GRID;
            wantedY = Math.round(wantedY / (float) GRID) * GRID;
            guides = new ArrayList<Snap.Guide>();
            place(target, wantedX, wantedY);
            return;
        }

        Snap.Box wanted = new Snap.Box(wantedX, wantedY, target.box.width, target.box.height);
        List<Snap.Box> others = new ArrayList<Snap.Box>();
        for (FathomHud.Positioned other : boxes()) {
            if (!other.widget.id().equals(dragging)) {
                others.add(other.box);
            }
        }

        Snap.Result snapped = Snap.snap(wanted, others, screenWidth, screenHeight, prefs.snapPixels());
        guides = snapped.guides();
        place(target, snapped.x, snapped.y);
    }

    /**
     * Move a just-dropped widget off anything it landed on.
     *
     * <p>Only on release, only when it actually overlaps, and never while Alt is
     * held. Overlapping widgets are legitimate — a label over a bar, a crosshair
     * over anything — so this is a nudge out of an accident rather than a rule:
     * {@code Snap.separate} leaves the widget alone when there is nowhere clear
     * to put it.
     */
    private void separate(String widgetId) {
        FathomHud.Positioned target = find(widgetId);
        if (target == null) {
            return;
        }
        List<Snap.Box> others = new ArrayList<Snap.Box>();
        for (FathomHud.Positioned other : boxes()) {
            if (!other.widget.id().equals(widgetId)) {
                others.add(other.box);
            }
        }
        Snap.Box moved = Snap.separate(target.box, others, screenWidth, screenHeight, 2);
        if (moved != target.box) {
            place(target, moved.x, moved.y);
            note = target.widget.name() + " moved clear of its neighbour.";
        }
    }

    private void place(FathomHud.Positioned target, int x, int y) {
        Snap.Box placed =
                Snap.clamp(new Snap.Box(x, y, target.box.width, target.box.height), screenWidth, screenHeight);

        // Re-anchor on the way, not only on release: the outline has to sit
        // where the widget will actually be, and for a right-anchored widget
        // that is different arithmetic than for a left-anchored one.
        Anchor anchor = Anchor.nearest(placed.centerX(), placed.centerY(), screenWidth, screenHeight);
        int offsetX = unscaleHudOffset(
                offsetFor(anchor.horizontal(), placed.x, placed.width, screenWidth));
        int offsetY = unscaleHudOffset(
                offsetFor(anchor.vertical(), placed.y, placed.height, screenHeight));
        hud.place(
                target.widget.id(),
                hud.placement(target.widget.id()).withAnchor(anchor).withOffset(offsetX, offsetY));
        frameBoxes = null;
        readout = anchor.label() + "  " + offsetX + ", " + offsetY
                + "  " + placed.width + "x" + placed.height;
    }

    /**
     * Resize by dragging the corner.
     *
     * <p>Horizontal travel only. A widget's aspect is fixed by its content — a
     * two-line coordinate readout is as tall as two lines — so the only thing a
     * corner drag can change is the scale, and letting the vertical axis
     * contribute would make the same gesture do different things depending on
     * which way the pointer happened to wander.
     */
    private void resize(int mouseX) {
        FathomHud.Positioned target = find(resizing);
        if (target == null) {
            resizing = null;
            return;
        }
        int travel = mouseX - resizeStartX;
        float scale = resizeStartScale + travel / (60f * hudInterfaceScale());
        hud.place(resizing, hud.placement(resizing).withScale(scale));
        frameBoxes = null;
        readout = Math.round(hud.placement(resizing).scale() * 100) + "%";
    }

    /**
     * Turn an absolute position back into an inward offset from an anchor.
     *
     * <p>The inverse of {@link Anchor#x}. A centred anchor has no edge to
     * measure from, so its offset is the distance from where centring would have
     * put it — which is what lets a widget be nudged off centre and stay nudged.
     */
    private static int offsetFor(float anchorFraction, int position, int size, int extent) {
        if (anchorFraction > 0.5f) {
            return extent - (position + size);
        }
        if (anchorFraction < 0.5f) {
            return position;
        }
        return position - Math.round((extent - size) * 0.5f);
    }

    /** Convert a rendered HUD offset back to the logical value stored in a profile. */
    private static int unscaleHudOffset(int renderedOffset) {
        return Math.round(renderedOffset / hudInterfaceScale());
    }

    private static float hudInterfaceScale() {
        float scale = Runtime.uiScale();
        return Float.isNaN(scale) || scale <= 0f ? 1f : scale;
    }

    private FathomHud.Positioned find(String id) {
        for (FathomHud.Positioned positioned : boxes()) {
            if (positioned.widget.id().equals(id)) {
                return positioned;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Wheel and keys
    // -----------------------------------------------------------------------

    private void wheel(int notches) {
        int mouseX = toScaledX(pointer.x());
        int mouseY = toScaledY(pointer.y());
        for (FathomHud.Positioned positioned : boxes()) {
            if (!positioned.box.contains(mouseX, mouseY)) {
                continue;
            }
            Placement placement = hud.placement(positioned.widget.id());
            float step = notches > 0 ? 1f : -1f;
            if (hasShiftDown()) {
                hud.place(positioned.widget.id(), placement.withScale(placement.scale() + step * 0.1f));
            } else {
                hud.place(positioned.widget.id(), placement.withOpacity(placement.opacity() + step * 0.1f));
            }
            selected = positioned.widget.name();
            problem = hud.save();
            return;
        }
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        // Polled per frame, like every other Fathom screen.
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        // As above.
    }

    @Override
    public void handleMouse() {
        // As above, wheel included.
    }

    @Override
    public boolean handleFathomKey(char character, int keyCode) {
        if (closing) {
            return true;
        }
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
                close();
                return true;
            case Keyboard.KEY_G:
                prefs.setGrid(!prefs.grid());
                return true;
            case Keyboard.KEY_B:
                prefs.setBounds(!prefs.bounds());
                return true;
            case Keyboard.KEY_DELETE:
                hideSelected();
                return true;
            case Keyboard.KEY_TAB:
                cycleSelection(hasShiftDown() ? -1 : 1);
                return true;
            default:
                break;
        }
        // Arrow keys nudge by one pixel, which is the only way to place
        // something exactly while snapping keeps helping; with Shift by ten, for
        // crossing the screen without the mouse.
        if (selected != null && nudge(keyCode)) {
            return true;
        }
        return false;
    }

    @Override
    protected void keyPressed(char character, int keyCode) {
        // FrameKeyboard drains this screen's events every rendered frame.
    }

    /**
     * Step the selection to the next widget.
     *
     * <p>The way to reach a widget the chrome is sitting on. The bar and the
     * inspector step aside for whatever is being worked on, but a widget that is
     * entirely under one of them is hard to aim at, and this needs no aim at
     * all: Tab to it, then the arrow keys and the inspector move it.
     */
    private void cycleSelection(int step) {
        List<FathomHud.Positioned> positioned = boxes();
        if (positioned.isEmpty()) {
            selected = null;
            return;
        }
        int at = -1;
        for (int index = 0; index < positioned.size(); index++) {
            if (positioned.get(index).widget.name().equals(selected)) {
                at = index;
                break;
            }
        }
        int next = at < 0
                ? (step > 0 ? 0 : positioned.size() - 1)
                : Math.floorMod(at + step, positioned.size());
        selected = positioned.get(next).widget.name();
        note = selected + " selected. Arrows nudge it.";
    }

    private boolean nudge(int keyCode) {
        int step = hasShiftDown() ? 10 : 1;
        int dx = keyCode == Keyboard.KEY_LEFT ? -step : (keyCode == Keyboard.KEY_RIGHT ? step : 0);
        int dy = keyCode == Keyboard.KEY_UP ? -step : (keyCode == Keyboard.KEY_DOWN ? step : 0);
        if (dx == 0 && dy == 0) {
            return false;
        }
        Widget widget = widgetNamed(selected);
        if (widget == null) {
            return false;
        }
        Placement placement = hud.placement(widget.id());
        // Offsets run inward from the anchor, so on a right- or bottom-anchored
        // widget an arrow key has to move the offset the other way to move the
        // widget the way the arrow points.
        int signX = placement.anchor().horizontal() > 0.5f ? -1 : 1;
        int signY = placement.anchor().vertical() > 0.5f ? -1 : 1;
        hud.place(
                widget.id(),
                placement.withOffset(placement.offsetX() + dx * signX, placement.offsetY() + dy * signY));
        problem = hud.save();
        return true;
    }

    /**
     * Switch the selected widget off.
     *
     * <p>Through the module, so the Mods page agrees on the next frame. The
     * note names where to switch it back on, because a widget that vanishes
     * from the editor with no explanation reads as a crash.
     */
    private void hideSelected() {
        Widget widget = widgetNamed(selected);
        if (widget == null) {
            return;
        }
        net.fathom.optimizations.core.module.FathomModule owner = hud.owner(widget.id());
        if (owner == null || !owner.switchable()) {
            note = widget.name() + " cannot be switched off here.";
            return;
        }
        hud.setWidgetEnabled(widget.id(), false);
        note = owner.name() + " switched off. Turn it back on in Mods.";
        selected = null;
        problem = hud.saveAll();
    }

    private void close() {
        if (closing) {
            return;
        }
        // The editor's own preferences, which belong to no profile.
        prefs.write(hud.gameDirectory());
        problem = hud.saveAll();
        if (problem == null) {
            closing = true;
            dragging = null;
            resizing = null;
            guides = new ArrayList<Snap.Guide>();
            controls.setInputEnabled(false);
            controls.closePopover();
            transition.close(Gfx.clock().seconds());
        }
        // A failed save keeps the editor open with the reason on screen. Closing
        // anyway would silently discard the arrangement somebody just made.
    }
}
