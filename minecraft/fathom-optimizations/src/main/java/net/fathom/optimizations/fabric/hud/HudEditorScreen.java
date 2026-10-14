package net.fathom.optimizations.fabric.hud;

import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Deque;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotUtils;

import net.fathom.optimizations.core.hud.Anchor;
import net.fathom.optimizations.core.hud.EditorPrefs;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.hud.Snap;
import net.fathom.optimizations.core.hud.Widget;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.anim.Transition;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.MinecraftGuiSize;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.hub.FathomHubScreen;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.ui.VisualEffects;
import net.fathom.optimizations.fabric.ui.Graphite;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

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
 * <h2>What keeps widgets apart</h2>
 *
 * <ul>
 *   <li><b>The flow</b> ({@code HudFlow}): widgets that would land on each other
 *       stack instead, in game and here alike. The widget being dragged is held
 *       exactly under the pointer while it moves.
 *   <li><b>Separation on drop</b>: a widget released on another is nudged to the
 *       nearest clear spot. Alt turns it off, with snapping, for the rare
 *       overlap that is meant.
 *   <li><b>The warning outline</b>: whatever still overlaps is outlined in the
 *       warning colour and named in the inspector.
 * </ul>
 *
 * <h2>The aids, and what each is for</h2>
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
 *   <li><b>The inspector</b> is for the things a drag cannot express: the exact
 *       position, the anchor, scale and opacity, and the way back.
 *   <li><b>Undo</b>: every gesture can be taken back, Ctrl+Z or the toolbar.
 * </ul>
 */
public final class HudEditorScreen extends Screen implements FathomScreen {

    /** Grid spacing, in Minecraft's scaled pixels — the space widgets live in. */
    private static final int GRID = 10;

    /** The corner handle's side, in scaled pixels. */
    private static final int HANDLE = 6;
    private static final List<Snap.Guide> NO_GUIDES = Collections.emptyList();

    private static final float TOOLBAR_HEIGHT = 52f;
    private static final float TOOLBAR_TWO_ROW_HEIGHT = 92f;
    /** The floating bar stands this far in from the screen's edges. */
    private static final float TOOLBAR_MARGIN = Skin.S3;

    /** The gap after each toolbar control; retained rectangles own the widths. */
    private static final float[] TOOL_GAPS = {Skin.S3, Skin.S2, Skin.S2, Skin.S2, Skin.S2, Skin.S2, Skin.S4};
    private static final int TOOLS = TOOL_GAPS.length;

    private static final float INSPECTOR_WIDTH = 296f;
    /** Label column in the inspector, as a settings row's label column. */
    private static final float INSPECTOR_LABEL = 84f;
    private static final float INSPECTOR_ROW = Skin.H_SM;
    private static final Anchor[] ANCHORS = Anchor.values();
    private static final String[] ANCHOR_LABELS = anchorLabels();

    /** Undo history depth: gestures, not frames. */
    private static final int UNDO_DEPTH = 48;
    /** Changes to one control closer together than this are one gesture. */
    private static final float GESTURE_SECONDS = 0.8f;

    private static String[] anchorLabels() {
        String[] labels = new String[ANCHORS.length];
        for (int index = 0; index < ANCHORS.length; index++) {
            labels[index] = ANCHORS[index].label();
        }
        return labels;
    }

    private final FathomHud hud;
    private final Controls controls = new Controls();
    private final Pointer pointer = new Pointer();
    private final Transition transition = new Transition(Timing.SCREEN_ENTER, Timing.SCREEN_EXIT);
    /** Toolbar geometry is rebuilt only when the viewport or one of its labels changes. */
    private final Rect[] toolbarKeys = new Rect[TOOLS];
    private Rect cachedToolbarBar;
    private Rect cachedToolbarRow;
    private Rect cachedToolbarPicker;
    private float cachedToolbarWidth = Float.NaN;
    private float cachedToolbarViewportHeight = Float.NaN;
    private float cachedToolbarHeight = TOOLBAR_HEIGHT;
    private boolean cachedToolbarGrid;
    private boolean cachedToolbarBounds;
    private boolean cachedToolbarTwoRows;
    private String cachedToolbarSnap = "";
    /** Profile labels are rebuilt when profiles change, never once per frame. */
    private List<net.fathom.optimizations.core.profile.FathomProfile> toolbarProfiles =
            Collections.emptyList();
    private String[] toolbarProfileOptions = new String[0];

    /** Widget ids, never display names: two widgets may one day share a name. */
    private String dragging;
    private String resizing;
    private String selected;
    private int grabOffsetX;
    private int grabOffsetY;
    private float resizeStartScale;
    private int resizeStartX;
    /** Counts presses, so two drags in a row are two undo steps. */
    private int gestures;
    private List<Snap.Guide> guides = NO_GUIDES;
    /** Reused while dragging; building this list must not create garbage per mouse move. */
    private final List<Snap.Box> snapBoxes = new ArrayList<Snap.Box>();

    private final EditorPrefs prefs;
    /** Where the dragged widget is, drawn beside it while it moves. */
    private String readout = "";
    private String problem;
    private String note = "";
    private int captureFrames;
    private boolean captured;
    private boolean transitionStarted;
    private boolean closing;
    /** Set by "Settings": once the editor has closed, the Hub opens this module's page. */
    private String settingsFor;
    private int benchmarkDragFrame;

    /** Snapshots of every placement, newest first, one per gesture. */
    private final Deque<Map<String, Placement>> undo = new ArrayDeque<Map<String, Placement>>();
    private String gestureKey;
    private float gestureAt;

    /** The coordinate field being typed into: 0 none, 1 X, 2 Y. */
    private int typingAxis;
    private String typingDraft = "";
    /** The typed field as last drawn, so a click elsewhere can finish the entry. */
    private Rect typingField;

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
    /** Which of {@link #frameBoxes} overlap another, by index; and the first one each overlaps. */
    private boolean[] overlapping = new boolean[16];
    private int[] overlapsWith = new int[16];
    private List<FathomHud.Positioned> overlapsFor;

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

    /** The same, for the inspector panel. */
    private boolean inspectorYields;

    /** Which corner the inspector sits in; see {@link #placeInspector}. */
    private int inspectorCorner;

    public HudEditorScreen(FathomHud hud) {
        this(hud, null);
    }

    /**
     * @param widgetId the widget to have selected on opening -- the one whose
     *     module page sent the player here -- or null
     */
    public HudEditorScreen(FathomHud hud, String widgetId) {
        this.hud = hud;
        this.prefs = EditorPrefs.read(hud.gameDirectory());
        this.selected = widgetId;
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

    private static boolean control() {
        return Keyboard.isKeyDown(Keyboard.KEY_LCONTROL) || Keyboard.isKeyDown(Keyboard.KEY_RCONTROL);
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
            selected = hud.registry().all().get(0).id();
        }
        String qaSelect = System.getProperty("fathom.qaEditorSelect", "").trim();
        if (!qaSelect.isEmpty() && selected == null) {
            selected = qaSelect;
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

    @Override
    public void removed() {
        // However the editor goes -- Done, Escape, a disconnect -- nothing may
        // stay pinned, or that widget would stop taking part in the flow.
        hud.pin(null);
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

        Renderer ui = Gfx.beginScreen(client, Runtime.uiScale());
        float delta = Gfx.screenDelta();
        boolean restorePrevious = false;
        try {
            pointer.poll(client, ui);
            controls.begin(pointer, delta);
            mcToLogical = (client.width / (float) Math.max(1, screenWidth)) / ui.scale();

            frameBoxes = null;
            if (selected != null && find(selected) == null) {
                // Switched off from somewhere else, or a profile without it.
                selected = null;
                typingAxis = 0;
            }
            int mouseX = toScaledX(pointer.x());
            int mouseY = toScaledY(pointer.y());
            // Decided before anything reads it: the bar's own controls consult it
            // to know whether to respond, and so does the widget hit test.
            barYields = chromeYields(toolbarArea(ui), mouseX, mouseY);
            placeInspector(ui);
            inspectorYields = selected != null && chromeYields(inspectorArea(ui), mouseX, mouseY);

            input(ui);

            boolean benchmarkDragging = net.fathom.optimizations.fabric.bench.PerfBench.benchHudDrag();
            if (benchmarkDragging) {
                benchmarkDrag();
            }

            if (!benchmarkDragging && dragging != null) {
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
            ui.fill(0f, 0f, ui.width(), ui.height(), Skin.fade(Tokens.COLOR_SHADOW, 0.24f));

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
            Gfx.endScreen();
        }
        if (restorePrevious) {
            if (settingsFor != null) {
                FathomHubScreen.requestModule(settingsFor);
            }
            boolean back = FathomNavigation.back(client, this);
            if (settingsFor != null && !(client.currentScreen instanceof FathomHubScreen)) {
                // Opened from the game rather than from the Hub: there is no
                // Hub to go back to, so open one, on Mods, which takes the request.
                FathomNavigation.openHub(client, hud, "Mods");
            } else if (!back) {
                closing = false;
                controls.setInputEnabled(true);
                problem = "navigation history was lost";
                transition.open(Gfx.clock().seconds());
            }
            settingsFor = null;
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

    /**
     * Which widgets overlap another after the flow: only those with nowhere
     * clear to go, or placed over one another with Alt. The crosshair overlaps
     * by design and is never counted.
     */
    private void findOverlaps() {
        List<FathomHud.Positioned> positioned = boxes();
        if (overlapsFor == positioned && positioned == frameBoxes) {
            return;
        }
        int count = positioned.size();
        if (overlapping.length < count) {
            overlapping = new boolean[count * 2];
            overlapsWith = new int[count * 2];
        }
        for (int index = 0; index < count; index++) {
            overlapping[index] = false;
            overlapsWith[index] = -1;
        }
        for (int a = 0; a < count; a++) {
            FathomHud.Positioned first = positioned.get(a);
            if ("crosshair".equals(first.widget.id())) {
                continue;
            }
            for (int b = a + 1; b < count; b++) {
                FathomHud.Positioned second = positioned.get(b);
                if ("crosshair".equals(second.widget.id()) || !Snap.overlaps(first.box, second.box)) {
                    continue;
                }
                overlapping[a] = true;
                overlapping[b] = true;
                if (overlapsWith[a] < 0) {
                    overlapsWith[a] = b;
                }
                if (overlapsWith[b] < 0) {
                    overlapsWith[b] = a;
                }
            }
        }
        overlapsFor = positioned;
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
    private void grid(Renderer ui) {
        int colour = Skin.argb(0.16f, Skin.ACCENT);
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
    private void safeBounds(Renderer ui) {
        int colour = Skin.argb(0.34f, Skin.ACCENT_DEEP);

        // The hotbar: 182 wide plus the health and hunger rows above it. These
        // are vanilla's own numbers, not estimates.
        int hotbarWidth = 182;
        int hotbarLeft = screenWidth / 2 - hotbarWidth / 2;
        dashed(ui, hotbarLeft, screenHeight - 39, hotbarLeft + hotbarWidth, screenHeight, colour);
        label(ui, "Hotbar", toLogical(hotbarLeft) + 2f, toLogical(screenHeight - 39) - 18f);

        dashed(ui, 0, screenHeight - 48 - 80, 320, screenHeight - 48,
                Skin.argb(0.20f, Skin.ACCENT_DEEP));
        label(ui, "Chat", 4f, toLogical(screenHeight - 48 - 80) - 18f);

        dashed(ui, 0, 0, screenWidth, 80, Skin.argb(0.14f, Skin.ACCENT_DEEP));
        label(ui, "F3", 4f, toLogical(80) + 4f);
    }

    private void label(Renderer ui, String text, float x, float y) {
        float height = ui.lineHeight(Type.CAPTION) + Skin.S1;
        Rect plate = new Rect(x, y, ui.measure(text, Type.CAPTION) + Skin.S2 * 2f, height);
        ui.rounded(plate.x, plate.y, plate.right(), plate.bottom(), Tokens.RADIUS_XS, Skin.OVERLAY);
        ui.text(text, plate.x + Skin.S2, Controls.baseline(ui, plate, Type.CAPTION), Type.CAPTION,
                Skin.ACCENT);
    }

    /** A dashed rectangle, so it reads as a boundary rather than a box. */
    private void dashed(Renderer ui, int left, int top, int right, int bottom, int colour) {
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

    /** Snap guides: the signal colour, the one place the editor uses it besides focus. */
    private void drawGuides(Renderer ui) {
        int colour = Skin.scaleAlpha(Tokens.COLOR_SIGNAL, 0.72f);
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

    private void outlines(Renderer ui, int mouseX, int mouseY) {
        findOverlaps();
        List<FathomHud.Positioned> positioned = boxes();
        for (int index = 0; index < positioned.size(); index++) {
            FathomHud.Positioned each = positioned.get(index);
            String id = each.widget.id();
            boolean hovered = each.box.contains(mouseX, mouseY);
            boolean active = id.equals(dragging) || id.equals(resizing) || id.equals(selected);
            boolean clash = index < overlapping.length && overlapping[index];
            float l = toLogical(each.box.x);
            float t = toLogical(each.box.y);
            float r = toLogical(each.box.right());
            float b = toLogical(each.box.bottom());
            if (active) {
                // Dense means a deeper neutral substrate, never a glow. Drawn
                // only in edit mode and only for the selected widget.
                ui.rounded(l - 1f, t - 1f, r + 1f, b + 1f, Skin.R_SMALL,
                        Skin.fade(Tokens.COLOR_SHADOW, 0.22f));
            }
            // Selected is the editor's focus: the signal outline. An overlap is
            // the warning colour, so it is seen without being looked for. Hover
            // and rest are neutral.
            int rim = active ? Tokens.COLOR_SIGNAL
                    : clash ? Skin.scaleAlpha(Skin.WARN, 0.85f)
                            : hovered ? Skin.argb(0.58f, Skin.ACCENT)
                                    : Skin.argb(0.24f, Skin.ACCENT_DEEP);
            ui.rim(l - 2f, t - 2f, r + 2f, b + 2f, Skin.R_SMALL, rim);

            if (active) {
                // The resize handle, bottom-right, which is where every resize
                // handle has been since windows had corners. Drawn small; it is
                // grabbed over HANDLE scaled pixels either side of the corner.
                float handle = 4.5f;
                ui.rounded(r - handle, b - handle, r + handle, b + handle, handle, Skin.TEXT);
                ui.rim(r - handle, b - handle, r + handle, b + handle, handle, Tokens.COLOR_SIGNAL);
            }
            if (active && !readout.isEmpty() && id.equals(dragging == null ? resizing : dragging)) {
                // Beside the widget, and below it when it is near the top, so
                // the numbers stay on screen while it is dragged into a corner.
                // Coordinates are measured values: Geist Mono, like every
                // other live number.
                Type data = Graphite.floor(ui, Graphite.DATA_S);
                float labelWidth = ui.measure(readout, data) + Skin.S3;
                float labelTop = t - 24f < 0f ? b + 6f : t - 24f;
                float labelLeft = Math.max(0f, Math.min(l - 2f, ui.width() - labelWidth));
                Rect plate = new Rect(labelLeft, labelTop, labelWidth, 18f);
                ui.rounded(plate.x, plate.y, plate.right(), plate.bottom(), Tokens.RADIUS_XS, Skin.OVERLAY);
                ui.text(readout, plate.x + 6f, Graphite.baseline(ui, plate, data), data, Skin.TEXT);
            }
            if (hovered && dragging == null && resizing == null) {
                String name = each.widget.name();
                float labelWidth = ui.measure(name, Type.CAPTION) + Skin.S3;
                float labelTop = t - 24f < 0f ? b + 6f : t - 24f;
                float labelLeft = Math.max(0f, Math.min(l - 2f, ui.width() - labelWidth));
                Rect plate = new Rect(labelLeft, labelTop, labelWidth, 18f);
                ui.rounded(plate.x, plate.y, plate.right(), plate.bottom(), Tokens.RADIUS_XS, Skin.OVERLAY);
                ui.text(name, plate.x + 6f, Controls.baseline(ui, plate, Type.CAPTION),
                        Type.CAPTION, Skin.TEXT);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Chrome
    // -----------------------------------------------------------------------

    /**
     * A floating bar of dense, flat material: the launcher's toolbar, not a
     * strip welded to the bottom of the screen. No backdrop blur -- the world
     * behind it moves every frame, and a blur of a moving scene is a
     * full-screen pass a frame for a bar.
     */
    private void toolbar(Renderer ui) {
        Rect bar = toolbarArea(ui);
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

        floatingSurface(ui, bar);

        if (controls.primary(ui, "edit.done", keys[0], "Done", true)) {
            close();
        }
        if (controls.secondary(ui, "edit.undo", keys[1], "Undo", !undo.isEmpty())) {
            undo();
        }
        if (controls.secondary(ui, "edit.grid", keys[2],
                prefs.grid() ? "Grid on" : "Grid", true)) {
            prefs.setGrid(!prefs.grid());
        }
        if (controls.secondary(ui, "edit.bounds", keys[3],
                prefs.bounds() ? "Bounds on" : "Bounds", true)) {
            prefs.setBounds(!prefs.bounds());
        }
        // Snapping strength, as one cycling button rather than four: it has an
        // order, exactly one value is in force, and a dropdown for four words
        // would be the largest control on the bar.
        if (controls.secondary(ui, "edit.snap", keys[4],
                "Snap: " + prefs.snapLabel(), true)) {
            prefs.cycleSnap();
            note = prefs.snapPixels() == 0
                    ? "Snapping off. A widget lands exactly where it is dropped."
                    : "Snapping " + prefs.snapLabel().toLowerCase(java.util.Locale.ROOT)
                            + ". Hold Alt to place freely.";
        }
        if (controls.secondary(ui, "edit.dup", keys[5], "Duplicate",
                hud.profiles() != null)) {
            // A copy of the whole loadout, made live, so the dragging that follows
            // changes the copy. The HUD belongs to a profile now; it has no names
            // of its own.
            try {
                net.fathom.optimizations.core.profile.FathomProfile copy =
                        hud.profiles().duplicate(hud.profiles().liveId());
                hud.profiles().switchTo(copy.id());
                selected = null;
                undo.clear();
                note = "Now editing " + copy.name() + ".";
            } catch (net.fathom.optimizations.core.profile.ProfileException failure) {
                problem = failure.getMessage();
            }
        }
        if (controls.danger(ui, "edit.reset", keys[6], "Reset all", true)) {
            remember("reset-all");
            hud.resetLayout();
            selected = null;
            typingAxis = 0;
            note = "Every widget is back where it started. Undo puts them back.";
            problem = hud.save();
            gestureKey = null;
        }
        float cursor = cachedToolbarTwoRows ? row.x : keys[TOOLS - 1].right() + Skin.S4;

        // The profile picker, at the right where a document's name goes. The
        // layout being edited is the live profile's, so choosing another profile
        // switches the whole loadout, live, and edits that one's HUD.
        Rect picker = toolbarPicker(ui);
        net.fathom.optimizations.fabric.profile.ProfileRuntime profiles = hud.profiles();
        if (profiles != null) {
            List<net.fathom.optimizations.core.profile.FathomProfile> all = profiles.cachedProfiles();
            syncProfileOptions(all);
            String[] options = toolbarProfileOptions;
            int index = 0;
            for (int at = 0; at < all.size(); at++) {
                if (all.get(at).id().equals(profiles.liveId())) {
                    index = at;
                }
            }
            int chosen = options.length == 0 ? index : controls.dropdown(ui, "edit.profile", picker, options, index);
            if (chosen != index && chosen >= 0 && chosen < options.length) {
                try {
                    profiles.switchTo(all.get(chosen).id());
                    selected = null;
                    typingAxis = 0;
                    // Another profile's layout: the history belongs to the last one.
                    undo.clear();
                    note = "Editing " + options[chosen] + ".";
                } catch (net.fathom.optimizations.core.profile.ProfileException failure) {
                    problem = failure.getMessage();
                }
            }
        }

        String hint = selected == null
                ? "Drag to move, corner to resize, scroll to fade, Shift-scroll to scale. "
                        + "Tab steps through the widgets; hold Alt to place freely."
                : "Arrows nudge, Shift-arrows by ten, Tab for the next, Ctrl+Z undoes, "
                        + "Delete turns it off";
        float room = picker.x - cursor - Skin.S4;
        if (note.isEmpty() && Text.overflows(ui, hint, Type.BODY, room)) {
            // The short form rather than the long one cut off mid-word.
            hint = selected == null
                    ? "Drag to move, corner to resize, scroll to fade."
                    : "Arrows nudge, Tab for the next, Ctrl+Z undoes";
        }
        if (room > 80f) {
            ui.text(Text.fit(ui, note.isEmpty() ? hint : note, Type.BODY, room), cursor,
                    Controls.baseline(ui, row, Type.BODY), Type.BODY,
                    note.isEmpty() ? Skin.TEXT_DISABLED : Skin.TEXT_MUTED);
        }

        if (problem != null) {
            String failure = "Could not save: " + problem;
            float width = Math.min(bar.width, ui.measure(failure, Type.BODY) + Skin.S4 * 2f);
            Rect plate = new Rect(bar.x, bar.y - Skin.S2 - Skin.H_SM, width, Skin.H_SM);
            floatingSurface(ui, plate);
            ui.text(Text.fit(ui, failure, Type.BODY, plate.width - Skin.S4 * 2f), plate.x + Skin.S4,
                    Controls.baseline(ui, plate, Type.BODY), Type.BODY, Skin.BAD);
        }
        ui.popAlpha();
        controls.clearShield();
    }

    /**
     * The editor's panels: graphite ink, nearly opaque, rounded, one soft
     * shadow and the dense rim. The launcher's dense glass is translucent
     * because a blur sits behind it; here there is none (the world moves every
     * frame), and translucent ink over a live HUD let a widget's text show
     * through the inspector's.
     */
    private static void floatingSurface(Renderer ui, Rect area) {
        float radius = Skin.radius(Skin.R_CARD, area);
        VisualEffects.overlayShadow(ui, area, radius);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), radius, PANEL_FACE);
        ui.rim(area.x, area.y, area.right(), area.bottom(), radius, Graphite.Material.DENSE.rim);
    }

    /** Graphite ink at 94%: see {@link #floatingSurface}. */
    private static final int PANEL_FACE = Skin.fade(Graphite.INK_1, 0.94f);

    /** The buttons along the left of the bar, retained until its inputs change. */
    private Rect[] toolbarButtons(Renderer ui) {
        layoutToolbar(ui);
        return toolbarKeys;
    }

    /** The profile picker, at the right of the bar. */
    private Rect toolbarPicker(Renderer ui) {
        layoutToolbar(ui);
        return cachedToolbarPicker;
    }

    /**
     * The inspector for the selected widget.
     *
     * <p>Everything a drag cannot say: exactly where, from which edge, how big,
     * how faint, and the way back. It sits in whichever corner covers the least
     * of the HUD.
     */
    private void inspector(Renderer ui) {
        Widget widget = widget(selected);
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

    /** The inspector's height: its rows are fixed, so this is too. */
    private static float inspectorHeight(Renderer ui) {
        float title = ui.lineHeight(Type.ROW_TITLE) + Skin.S1 + ui.lineHeight(Type.CAPTION);
        // Anchor, from-left, from-top, scale, opacity.
        float rows = INSPECTOR_ROW * 5f + Skin.S2 * 4f;
        float buttons = Skin.H_SM * 2f + Skin.S2;
        return Skin.S4 + title + Skin.S4 + rows + Skin.S4 + buttons + Skin.S4;
    }

    private void inspectorBody(Renderer ui, Widget widget, Rect panel) {
        Placement placement = hud.placement(widget.id());
        FathomHud.Positioned positioned = find(widget.id());
        floatingSurface(ui, panel);

        Rect inner = panel.inset(Skin.S4, Skin.S4);
        float y = inner.y;
        ui.text(Text.fit(ui, widget.name(), Type.ROW_TITLE, inner.width), inner.x,
                y + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE, Skin.TEXT);
        y += ui.lineHeight(Type.ROW_TITLE) + Skin.S1;
        String status = status(positioned);
        boolean warn = status.startsWith("Overlaps");
        ui.text(Text.fit(ui, status, Type.CAPTION, inner.width), inner.x, y + ui.ascent(Type.CAPTION),
                Type.CAPTION, warn ? Skin.WARN : Skin.TEXT_FAINT);
        y += ui.lineHeight(Type.CAPTION) + Skin.S4;

        float controlX = inner.x + INSPECTOR_LABEL;
        float controlWidth = inner.right() - controlX;

        // Anchor: a dropdown, because nine values is too many to step through.
        rowLabel(ui, "Anchor", inner.x, y);
        int chosen = controls.dropdown(ui, "insp.anchor", new Rect(controlX, y, controlWidth, INSPECTOR_ROW),
                ANCHOR_LABELS, placement.anchor().ordinal());
        if (chosen != placement.anchor().ordinal() && chosen >= 0 && chosen < ANCHORS.length) {
            reanchor(widget, positioned, ANCHORS[chosen]);
        }
        y += INSPECTOR_ROW + Skin.S2;

        // Exact position: how far in from the anchored edge, typed or stepped.
        // The label says which edge, because that changes with the anchor.
        rowLabel(ui, axisLabel(placement.anchor().horizontal(), true), inner.x, y);
        numberField(ui, "insp.x", new Rect(controlX, y, controlWidth, INSPECTOR_ROW), 1, placement.offsetX(), widget);
        y += INSPECTOR_ROW + Skin.S2;
        rowLabel(ui, axisLabel(placement.anchor().vertical(), false), inner.x, y);
        numberField(ui, "insp.y", new Rect(controlX, y, controlWidth, INSPECTOR_ROW), 2, placement.offsetY(), widget);
        y += INSPECTOR_ROW + Skin.S2;

        y = sliderRow(ui, inner, controlX, controlWidth, y, "insp.scale", "Scale",
                Math.round(placement.scale() * 100) + "%",
                (placement.scale() - Placement.MIN_SCALE) / (Placement.MAX_SCALE - Placement.MIN_SCALE),
                widget, true);
        y = sliderRow(ui, inner, controlX, controlWidth, y, "insp.opacity", "Opacity",
                Math.round(placement.opacity() * 100) + "%",
                (placement.opacity() - 0.1f) / 0.9f, widget, false);
        y += Skin.S4 - Skin.S2;

        float buttonHalf = (inner.width - Skin.S2) / 2f;
        // Position only: the anchor and the offset go back, the scale and the
        // opacity somebody spent time on stay. This is the one people want
        // after dragging a widget somewhere they cannot find it again.
        if (controls.secondary(ui, "insp.position", new Rect(inner.x, y, buttonHalf, Skin.H_SM),
                "Reset position", true)) {
            remember("reset-position");
            Placement home = widget.defaultPlacement();
            hud.place(widget.id(), hud.placement(widget.id())
                    .withAnchor(home.anchor())
                    .withOffset(home.offsetX(), home.offsetY()));
            typingAxis = 0;
            note = widget.name() + " is back where it started.";
            problem = hud.save();
            gestureKey = null;
        }
        if (controls.secondary(ui, "insp.reset", new Rect(inner.x + buttonHalf + Skin.S2, y, buttonHalf, Skin.H_SM),
                "Reset all of it", true)) {
            remember("reset-widget");
            hud.place(widget.id(), widget.defaultPlacement());
            typingAxis = 0;
            note = widget.name() + ": position, scale and opacity reset.";
            problem = hud.save();
            gestureKey = null;
        }
        y += Skin.H_SM + Skin.S2;
        FathomModule owner = hud.owner(widget.id());
        if (owner != null && controls.secondary(ui, "insp.settings", new Rect(inner.x, y, buttonHalf, Skin.H_SM),
                "Settings", true)) {
            // The module's own page, in the Hub, once the editor has closed.
            settingsFor = owner.id();
            close();
        }
        // "Turn off" switches the module off, because that is the only switch.
        // Hiding a widget while leaving its module reading "on" is precisely
        // the disagreement the module framework removed.
        if (controls.danger(ui, "insp.hide", new Rect(inner.x + buttonHalf + Skin.S2, y, buttonHalf, Skin.H_SM),
                "Turn off", owner != null && owner.switchable())) {
            hideSelected();
        }
    }

    /** One line under the widget's name: what is worth knowing about where it is. */
    private String status(FathomHud.Positioned positioned) {
        if (positioned == null) {
            return "";
        }
        findOverlaps();
        List<FathomHud.Positioned> all = boxes();
        int index = all.indexOf(positioned);
        if (index >= 0 && index < overlapping.length && overlapping[index] && overlapsWith[index] >= 0) {
            return "Overlaps " + all.get(overlapsWith[index]).widget.name() + ". Drag it clear.";
        }
        if (positioned.flowed) {
            return "Moved to clear the widget beside it.";
        }
        HudWidget renderer = hud.renderer(positioned.widget.id());
        if (renderer != null && !renderer.visible(client)) {
            return "Preview: nothing to show in game right now.";
        }
        return positioned.box.width + " × " + positioned.box.height + " at " + positioned.box.x + ", " + positioned.box.y;
    }

    private void rowLabel(Renderer ui, String label, float x, float y) {
        ui.text(label, x, Controls.baseline(ui, y, INSPECTOR_ROW, Type.SMALL), Type.SMALL, Skin.TEXT_MUTED);
    }

    /** What an offset is measured from, for an anchor's position on one axis. */
    private static String axisLabel(float fraction, boolean horizontal) {
        if (fraction < 0.5f) {
            return horizontal ? "From left" : "From top";
        }
        if (fraction > 0.5f) {
            return horizontal ? "From right" : "From bottom";
        }
        return horizontal ? "From centre" : "From middle";
    }

    /**
     * A whole number with a minus, a plus and a field between them that can be
     * typed into. Shift makes the steps ten.
     */
    private void numberField(Renderer ui, String id, Rect area, int axis, int value, Widget widget) {
        float button = Math.min(area.height, 26f);
        Rect minus = new Rect(area.x, area.y, button, area.height);
        Rect plus = new Rect(area.right() - button, area.y, button, area.height);
        Rect field = Rect.of(minus.right() + Skin.S1, area.y, plus.x - Skin.S1, area.bottom());
        boolean typing = typingAxis == axis;
        if (typing) {
            typingField = field;
        }
        if (controls.textField(ui, id, field, typing ? typingDraft : Integer.toString(value), typing)) {
            if (!typing) {
                commitTyping();
                typingAxis = axis;
                typingDraft = "";
                typingField = field;
            }
        }
        int step = hasShiftDown() ? 10 : 1;
        if (controls.iconButton(ui, id + "#-", minus, Icon.MINUS, false, true)) {
            setOffset(widget, axis, value - step, "step:" + axis);
        }
        if (controls.iconButton(ui, id + "#+", plus, Icon.PLUS, false, true)) {
            setOffset(widget, axis, value + step, "step:" + axis);
        }
    }

    /** Set one offset, keeping it within a screen of its edge so it can always be found again. */
    private void setOffset(Widget widget, int axis, int value, String gesture) {
        remember(gesture + ":" + widget.id());
        int limit = axis == 1 ? screenWidth : screenHeight;
        int clamped = Math.max(-limit, Math.min(limit, value));
        Placement placement = hud.placement(widget.id());
        hud.place(widget.id(), axis == 1
                ? placement.withOffset(clamped, placement.offsetY())
                : placement.withOffset(placement.offsetX(), clamped));
        frameBoxes = null;
        problem = hud.save();
    }

    /** Apply what was typed into a coordinate field, if anything was. */
    private void commitTyping() {
        int axis = typingAxis;
        String draft = typingDraft;
        typingAxis = 0;
        typingDraft = "";
        typingField = null;
        Widget widget = widget(selected);
        if (axis == 0 || widget == null || draft.isEmpty() || "-".equals(draft)) {
            return;
        }
        try {
            setOffset(widget, axis, Integer.parseInt(draft), "typed");
            gestureKey = null;
        } catch (NumberFormatException tooLong) {
            note = "That is not a position.";
        }
    }

    /**
     * Change the anchor and keep the widget where it is.
     *
     * <p>The offset is recomputed from the new anchor's edges, so the widget
     * does not move -- it only changes which edge it follows when the screen
     * changes size. Resetting the offset instead sent it somewhere nobody chose.
     * From where its placement puts it, not where the flow moved it: a widget
     * stacked under another keeps its place in the stack.
     */
    private void reanchor(Widget widget, FathomHud.Positioned positioned, Anchor next) {
        remember("anchor");
        Placement placement = hud.placement(widget.id());
        if (positioned == null) {
            hud.place(widget.id(), placement.withAnchor(next));
        } else {
            Snap.Box box = positioned.box;
            hud.place(widget.id(), placement.withAnchor(next).withOffset(
                    offsetFor(next.horizontal(), box.x, box.width, screenWidth),
                    offsetFor(next.vertical(), positioned.naturalY, box.height, screenHeight)));
        }
        frameBoxes = null;
        typingAxis = 0;
        note = widget.name() + " now follows the " + next.label().toLowerCase(java.util.Locale.ROOT)
                + (next == Anchor.CENTER ? "." : " edge.");
        problem = hud.save();
        gestureKey = null;
    }

    /** One labelled slider in the inspector, its value at the right. */
    private float sliderRow(
            Renderer ui, Rect inner, float controlX, float controlWidth, float y, String id, String label,
            String value, float fraction, Widget widget, boolean scale) {
        rowLabel(ui, label, inner.x, y);
        float valueWidth = 40f;
        ui.textRight(value, inner.right(), Controls.baseline(ui, y, INSPECTOR_ROW, Type.SMALL), Type.SMALL,
                Skin.TEXT_MUTED);
        Rect track = new Rect(controlX, y + (INSPECTOR_ROW - Controls.SLIDER_HEIGHT) / 2f,
                controlWidth - valueWidth - Skin.S2, Controls.SLIDER_HEIGHT);
        double next = controls.slider(ui, id, track, fraction, true);
        if (Math.abs(next - fraction) > 0.001d) {
            remember(id);
            Placement placement = hud.placement(widget.id());
            hud.place(widget.id(), scale
                    ? placement.withScale(Math.round((float) (Placement.MIN_SCALE
                            + next * (Placement.MAX_SCALE - Placement.MIN_SCALE)) * 20f) / 20f)
                    : placement.withOpacity(Math.round((float) (0.1d + next * 0.9d) * 20f) / 20f));
            frameBoxes = null;
            problem = hud.save();
        }
        return y + INSPECTOR_ROW + Skin.S2;
    }

    private Widget widget(String id) {
        return id == null ? null : hud.registry().find(id);
    }

    // -----------------------------------------------------------------------
    // Development self-test hooks (EditorSelfTest). Each does exactly what the
    // control it stands in for does; none is reachable from a normal game.
    // -----------------------------------------------------------------------

    /** Logical units per scaled pixel, for a script that aims the pointer at a widget. */
    public float qaMcToLogical() {
        return mcToLogical;
    }

    public String qaSelected() {
        return selected;
    }

    public boolean qaClosing() {
        return closing;
    }

    /** As a click on the X (1) or Y (2) field. */
    public void qaFocus(int axis) {
        commitTyping();
        typingAxis = axis;
        typingDraft = "";
    }

    /** As the toolbar's Undo. */
    public void qaUndo() {
        undo();
    }

    /** As choosing an anchor in the inspector's dropdown. */
    public void qaAnchor(Anchor next) {
        Widget widget = widget(selected);
        if (widget != null) {
            reanchor(widget, find(selected), next);
        }
    }

    /** As the inspector's Settings button. */
    public void qaSettings() {
        FathomModule owner = selected == null ? null : hud.owner(selected);
        if (owner != null) {
            settingsFor = owner.id();
            close();
        }
    }

    /** What the inspector says under the widget's name. */
    public String qaStatus() {
        return selected == null ? "" : status(find(selected));
    }

    // -----------------------------------------------------------------------
    // Undo
    // -----------------------------------------------------------------------

    /**
     * Take a snapshot of every placement before a change, once per gesture.
     *
     * <p>A gesture is one drag, one slider pull, one run of nudges: calls with
     * the same key in quick succession share the snapshot taken by the first,
     * so Undo takes back what a person would call one change.
     */
    private void remember(String key) {
        float now = Gfx.clock().seconds();
        if (key.equals(gestureKey) && now - gestureAt < GESTURE_SECONDS) {
            gestureAt = now;
            return;
        }
        gestureKey = key;
        gestureAt = now;
        Map<String, Placement> snapshot = snapshot();
        if (!undo.isEmpty() && undo.peekFirst().equals(snapshot)) {
            return;
        }
        undo.push(snapshot);
        while (undo.size() > UNDO_DEPTH) {
            undo.removeLast();
        }
    }

    private Map<String, Placement> snapshot() {
        Map<String, Placement> out = new LinkedHashMap<String, Placement>();
        for (Widget widget : hud.registry().all()) {
            out.put(widget.id(), hud.placement(widget.id()));
        }
        return out;
    }

    private void undo() {
        Map<String, Placement> current = snapshot();
        // A gesture that changed nothing -- a click on a widget, say -- left a
        // snapshot identical to now; taking it back would look like nothing.
        while (!undo.isEmpty() && undo.peekFirst().equals(current)) {
            undo.pop();
        }
        if (undo.isEmpty()) {
            note = "Nothing to undo.";
            return;
        }
        Map<String, Placement> previous = undo.pop();
        for (Map.Entry<String, Placement> entry : previous.entrySet()) {
            hud.place(entry.getKey(), entry.getValue());
        }
        frameBoxes = null;
        typingAxis = 0;
        gestureKey = null;
        note = "Undone.";
        problem = hud.save();
    }

    // -----------------------------------------------------------------------
    // Input, from the device
    // -----------------------------------------------------------------------

    private void input(Renderer ui) {
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
            hud.pin(null);
            frameBoxes = null;
            readout = "";
            guides = NO_GUIDES;
            // Saved on release rather than on every mouse-move: one file write
            // per drag instead of one per frame.
            problem = hud.save();
        }

        if (!pointer.pressed(0)) {
            return;
        }
        float mouseX = pointer.x();
        float mouseY = pointer.y();

        // A press anywhere but the field being typed into finishes the entry,
        // as leaving a text box does everywhere else.
        if (typingAxis != 0 && (typingField == null || !typingField.contains(mouseX, mouseY))) {
            commitTyping();
        }

        // Only real controls take the click. The bar's empty space is
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

            if (onHandle && candidate.widget.id().equals(selected)) {
                gestures++;
                remember("resize:" + gestures);
                resizing = candidate.widget.id();
                hud.pin(resizing);
                resizeStartScale = candidate.placement.scale();
                resizeStartX = scaledX;
                return;
            }
            if (candidate.box.contains(scaledX, scaledY)) {
                gestures++;
                remember("drag:" + gestures);
                dragging = candidate.widget.id();
                hud.pin(dragging);
                if (!dragging.equals(selected)) {
                    typingAxis = 0;
                }
                selected = dragging;
                grabOffsetX = scaledX - candidate.box.x;
                grabOffsetY = scaledY - candidate.box.y;
                return;
            }
        }
        selected = null;
        typingAxis = 0;
    }

    private Rect toolbarRow(Renderer ui) {
        layoutToolbar(ui);
        return cachedToolbarRow;
    }

    private Rect toolbarArea(Renderer ui) {
        layoutToolbar(ui);
        return cachedToolbarBar;
    }

    /**
     * Fit the editor toolbar at every supported interface scale. Wide screens
     * keep the document picker on the same line; narrow logical viewports put
     * it on a second line instead of letting two hitboxes overlap.
     */
    private void layoutToolbar(Renderer ui) {
        String snap = prefs.snapLabel();
        if (cachedToolbarWidth == ui.width()
                && cachedToolbarViewportHeight == ui.height()
                && cachedToolbarGrid == prefs.grid()
                && cachedToolbarBounds == prefs.bounds()
                && cachedToolbarSnap.equals(snap)) {
            return;
        }
        cachedToolbarWidth = ui.width();
        cachedToolbarViewportHeight = ui.height();
        cachedToolbarGrid = prefs.grid();
        cachedToolbarBounds = prefs.bounds();
        cachedToolbarSnap = snap;

        String[] labels = {
            "Done",
            "Undo",
            prefs.grid() ? "Grid on" : "Grid",
            prefs.bounds() ? "Bounds on" : "Bounds",
            "Snap: " + snap,
            "Duplicate",
            "Reset all",
        };
        float[] widths = new float[labels.length];
        float buttonsWidth = 0f;
        for (int index = 0; index < labels.length; index++) {
            widths[index] = Math.max(64f, ui.measure(labels[index], Type.BODY_STRONG) + Skin.S4 * 2f);
            buttonsWidth += widths[index] + TOOL_GAPS[index];
        }

        Rect bar = Rect.of(TOOLBAR_MARGIN, 0f, ui.width() - TOOLBAR_MARGIN, 1f);
        float pad = Skin.S2;
        float available = Math.max(1f, bar.width - pad * 2f);
        float pickerWidth = Math.min(200f, Math.max(148f, ui.width() * 0.17f));
        cachedToolbarTwoRows = buttonsWidth + pickerWidth > available;
        cachedToolbarHeight = cachedToolbarTwoRows ? TOOLBAR_TWO_ROW_HEIGHT : TOOLBAR_HEIGHT;
        float top = ui.height() - TOOLBAR_MARGIN - cachedToolbarHeight;
        cachedToolbarBar = new Rect(bar.x, top, bar.width, cachedToolbarHeight);
        float buttonY = cachedToolbarTwoRows ? top + pad : top + (cachedToolbarHeight - Skin.H_MD) / 2f;
        float pickerY = cachedToolbarTwoRows
                ? top + cachedToolbarHeight - pad - Skin.H_MD
                : buttonY;

        // The two-line arrangement has the full row for buttons. Extremely
        // narrow windows compress them uniformly, preserving non-overlapping
        // bounds; labels are clipped by the canonical button primitive.
        float buttonScale = cachedToolbarTwoRows && buttonsWidth > available
                ? available / buttonsWidth : 1f;
        float cursor = bar.x + pad;
        for (int index = 0; index < toolbarKeys.length; index++) {
            float width = widths[index] * buttonScale;
            toolbarKeys[index] = new Rect(cursor, buttonY, width, Skin.H_MD);
            cursor += width + TOOL_GAPS[index] * buttonScale;
        }
        cachedToolbarPicker = new Rect(bar.right() - pad - pickerWidth, pickerY, pickerWidth, Skin.H_MD);
        cachedToolbarRow = new Rect(bar.x + pad + Skin.S2, pickerY, available - Skin.S2, Skin.H_MD);
    }

    private void syncProfileOptions(List<net.fathom.optimizations.core.profile.FathomProfile> all) {
        boolean changed = all.size() != toolbarProfiles.size();
        if (!changed) {
            for (int index = 0; index < all.size(); index++) {
                net.fathom.optimizations.core.profile.FathomProfile next = all.get(index);
                net.fathom.optimizations.core.profile.FathomProfile old = toolbarProfiles.get(index);
                if (!next.id().equals(old.id()) || !next.name().equals(old.name())) {
                    changed = true;
                    break;
                }
            }
        }
        if (!changed) {
            return;
        }
        toolbarProfiles = new ArrayList<net.fathom.optimizations.core.profile.FathomProfile>(all);
        toolbarProfileOptions = new String[all.size()];
        for (int index = 0; index < all.size(); index++) {
            toolbarProfileOptions[index] = all.get(index).name();
        }
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
    private boolean toolbarOwns(Renderer ui, float mouseX, float mouseY) {
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

    private Rect inspectorArea(Renderer ui) {
        return inspectorAt(ui, inspectorCorner);
    }

    /** Top right, top left, bottom right, bottom left: the order they are tried in. */
    private Rect inspectorAt(Renderer ui, int corner) {
        layoutToolbar(ui);
        float margin = Skin.S4;
        float height = inspectorHeight(ui);
        float left = corner % 2 == 0 ? ui.width() - INSPECTOR_WIDTH - margin : margin;
        float top = corner < 2 ? margin : cachedToolbarBar.y - margin - height;
        return new Rect(left, Math.max(margin, top), INSPECTOR_WIDTH, height);
    }

    /**
     * Put the inspector in the corner that covers the least of the HUD.
     *
     * <p>It used to be pinned top right, which is where clocks, ping and
     * coordinates usually live, so the panel sat over exactly the widgets it
     * was being used to adjust. Top right is still preferred; another corner
     * is taken only when it covers less. Held still while a drag, a resize or
     * a typed entry is under way, so the panel never jumps away from under the
     * pointer -- the yield (it fades when a widget is worked on beneath it)
     * covers that case.
     */
    private void placeInspector(Renderer ui) {
        if (dragging != null || resizing != null || typingAxis != 0 || controls.popoverOpen()
                || controls.pointerOverVisible(inspectorArea(ui))) {
            return;
        }
        int best = 0;
        float bestCover = Float.MAX_VALUE;
        for (int corner = 0; corner < 4; corner++) {
            Rect area = inspectorAt(ui, corner);
            float cover = 0f;
            for (FathomHud.Positioned positioned : boxes()) {
                Rect widget = Rect.of(toLogical(positioned.box.x), toLogical(positioned.box.y),
                        toLogical(positioned.box.right()), toLogical(positioned.box.bottom()));
                Rect overlap = area.intersect(widget);
                if (!overlap.empty()) {
                    // The selected widget counts double: covering the thing
                    // being adjusted is the worst place the panel can be.
                    float weight = positioned.widget.id().equals(selected) ? 2f : 1f;
                    cover += overlap.width * overlap.height * weight;
                }
            }
            // A clear corner wins outright; the preferred one wins ties.
            if (cover < bestCover - 0.5f) {
                best = corner;
                bestCover = cover;
            }
            if (bestCover == 0f) {
                break;
            }
        }
        inspectorCorner = best;
    }

    private void drag(int mouseX, int mouseY) {
        FathomHud.Positioned target = find(dragging);
        if (target == null) {
            dragging = null;
            hud.pin(null);
            return;
        }
        int wantedX = mouseX - grabOffsetX;
        int wantedY = mouseY - grabOffsetY;

        if (freehand()) {
            guides = NO_GUIDES;
            place(target, wantedX, wantedY);
            return;
        }

        // The grid takes precedence when it is on: somebody who turned it on
        // wants columns, and snapping to a neighbour's edge instead would be the
        // editor overruling the tool they just picked.
        if (prefs.grid()) {
            wantedX = Math.round(wantedX / (float) GRID) * GRID;
            wantedY = Math.round(wantedY / (float) GRID) * GRID;
            guides = NO_GUIDES;
            place(target, wantedX, wantedY);
            return;
        }

        Snap.Box wanted = new Snap.Box(wantedX, wantedY, target.box.width, target.box.height);
        snapBoxes.clear();
        for (FathomHud.Positioned other : boxes()) {
            if (!other.widget.id().equals(dragging)) {
                snapBoxes.add(other.box);
            }
        }

        Snap.Result snapped = Snap.snap(wanted, snapBoxes, screenWidth, screenHeight, prefs.snapPixels());
        guides = snapped.guides();
        place(target, snapped.x, snapped.y);
    }

    /**
     * Development benchmark only: feeds the ordinary drag and snap path a
     * deterministic back-and-forth pointer. It never exists in a normal run.
     */
    private void benchmarkDrag() {
        List<FathomHud.Positioned> available = boxes();
        if (available.isEmpty()) {
            return;
        }
        FathomHud.Positioned target = dragging == null ? available.get(0) : find(dragging);
        if (target == null) {
            return;
        }
        if (dragging == null) {
            dragging = target.widget.id();
            hud.pin(dragging);
            selected = dragging;
            grabOffsetX = Math.max(1, target.box.width / 2);
            grabOffsetY = Math.max(1, target.box.height / 2);
        }
        int room = Math.max(1, screenWidth - target.box.width - 24);
        int cycle = benchmarkDragFrame++ % 240;
        int across = cycle <= 120 ? cycle : 240 - cycle;
        int wantedX = 12 + Math.round(room * (across / 120f));
        int wantedY = Math.max(12, Math.min(screenHeight - target.box.height - 64,
                screenHeight / 3 + Math.round((float) Math.sin(benchmarkDragFrame * 0.05f) * 24f)));
        drag(wantedX + grabOffsetX, wantedY + grabOffsetY);
    }

    /**
     * Move a just-dropped widget off anything it landed on.
     *
     * <p>Only on release, only when it actually overlaps, and never while Alt is
     * held. {@code Snap.separate} leaves the widget alone when there is nowhere
     * clear to put it, and the warning outline says so.
     */
    private void separate(String widgetId) {
        FathomHud.Positioned target = find(widgetId);
        if (target == null) {
            return;
        }
        snapBoxes.clear();
        for (FathomHud.Positioned other : boxes()) {
            if (!other.widget.id().equals(widgetId) && !"crosshair".equals(other.widget.id())) {
                snapBoxes.add(other.box);
            }
        }
        Snap.Box moved = Snap.separate(target.box, snapBoxes, screenWidth, screenHeight, 2);
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
        int offsetX = offsetFor(anchor.horizontal(), placed.x, placed.width, screenWidth);
        int offsetY = offsetFor(anchor.vertical(), placed.y, placed.height, screenHeight);
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
     * which way the pointer happened to wander. Rounded to five percent, as the
     * inspector's slider is, unless Alt is held.
     */
    private void resize(int mouseX) {
        FathomHud.Positioned target = find(resizing);
        if (target == null) {
            resizing = null;
            hud.pin(null);
            return;
        }
        int travel = mouseX - resizeStartX;
        float scale = resizeStartScale + travel / 60f;
        if (!freehand()) {
            scale = Math.round(scale * 20f) / 20f;
        }
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

    private FathomHud.Positioned find(String id) {
        if (id == null) {
            return null;
        }
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
        FathomHud.Positioned positioned = widgetAt(mouseX, mouseY);
        if (positioned == null) {
            return;
        }
        Placement placement = hud.placement(positioned.widget.id());
        float step = notches > 0 ? 1f : -1f;
        boolean scale = hasShiftDown();
        remember((scale ? "wheel-scale:" : "wheel-opacity:") + positioned.widget.id());
        if (scale) {
            hud.place(positioned.widget.id(), placement.withScale(
                    Math.round((placement.scale() + step * 0.1f) * 20f) / 20f));
        } else {
            hud.place(positioned.widget.id(), placement.withOpacity(
                    Math.round((placement.opacity() + step * 0.1f) * 20f) / 20f));
        }
        frameBoxes = null;
        selected = positioned.widget.id();
        problem = hud.save();
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
        if (typingAxis != 0) {
            return typeCoordinate(character, keyCode);
        }
        if (keyCode == Keyboard.KEY_Z && control()) {
            undo();
            return true;
        }
        switch (keyCode) {
            case Keyboard.KEY_ESCAPE:
                // An open list closes first; otherwise Escape leaves, as it
                // always has -- one press, whatever is selected.
                if (controls.popoverOpen()) {
                    controls.closePopover();
                } else {
                    close();
                }
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

    /**
     * A key while a coordinate is being typed: digits and a leading minus,
     * Backspace, Enter to apply, Escape to leave it as it was, Tab to apply
     * and move to the other axis. Nothing else reaches the editor's shortcuts,
     * so typing a G does not switch the grid.
     */
    private boolean typeCoordinate(char character, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            typingAxis = 0;
            typingDraft = "";
            typingField = null;
            return true;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            commitTyping();
            return true;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            int next = typingAxis == 1 ? 2 : 1;
            commitTyping();
            typingAxis = next;
            typingDraft = "";
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (!typingDraft.isEmpty()) {
                typingDraft = typingDraft.substring(0, typingDraft.length() - 1);
            }
            return true;
        }
        if (character == '-' && typingDraft.isEmpty()) {
            typingDraft = "-";
        } else if (character >= '0' && character <= '9' && typingDraft.length() < 5) {
            typingDraft = typingDraft + character;
        }
        return true;
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
            if (positioned.get(index).widget.id().equals(selected)) {
                at = index;
                break;
            }
        }
        int next = at < 0
                ? (step > 0 ? 0 : positioned.size() - 1)
                : Math.floorMod(at + step, positioned.size());
        selected = positioned.get(next).widget.id();
        note = positioned.get(next).widget.name() + " selected. Arrows nudge it.";
    }

    private boolean nudge(int keyCode) {
        int step = hasShiftDown() ? 10 : 1;
        int dx = keyCode == Keyboard.KEY_LEFT ? -step : (keyCode == Keyboard.KEY_RIGHT ? step : 0);
        int dy = keyCode == Keyboard.KEY_UP ? -step : (keyCode == Keyboard.KEY_DOWN ? step : 0);
        if (dx == 0 && dy == 0) {
            return false;
        }
        Widget widget = widget(selected);
        if (widget == null) {
            return false;
        }
        remember("nudge:" + widget.id());
        Placement placement = hud.placement(widget.id());
        // Offsets run inward from the anchor, so on a right- or bottom-anchored
        // widget an arrow key has to move the offset the other way to move the
        // widget the way the arrow points.
        int signX = placement.anchor().horizontal() > 0.5f ? -1 : 1;
        int signY = placement.anchor().vertical() > 0.5f ? -1 : 1;
        hud.place(
                widget.id(),
                placement.withOffset(placement.offsetX() + dx * signX, placement.offsetY() + dy * signY));
        frameBoxes = null;
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
        Widget widget = widget(selected);
        if (widget == null) {
            return;
        }
        FathomModule owner = hud.owner(widget.id());
        if (owner == null || !owner.switchable()) {
            note = widget.name() + " cannot be switched off here.";
            return;
        }
        hud.setWidgetEnabled(widget.id(), false);
        note = owner.name() + " switched off. Turn it back on in Mods.";
        selected = null;
        typingAxis = 0;
        problem = hud.saveAll();
    }

    private void close() {
        if (closing) {
            return;
        }
        commitTyping();
        // The editor's own preferences, which belong to no profile.
        prefs.write(hud.gameDirectory());
        problem = hud.saveAll();
        if (problem == null) {
            closing = true;
            dragging = null;
            resizing = null;
            hud.pin(null);
            guides = new ArrayList<Snap.Guide>();
            controls.setInputEnabled(false);
            controls.closePopover();
            transition.close(Gfx.clock().seconds());
        } else {
            settingsFor = null;
        }
        // A failed save keeps the editor open with the reason on screen. Closing
        // anyway would silently discard the arrangement somebody just made.
    }
}
