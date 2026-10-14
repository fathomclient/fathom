package net.fathom.optimizations.fabric.hub;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotUtils;

import net.fathom.optimizations.core.input.KeybindCaptureState;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.anim.Transition;
import net.fathom.optimizations.core.ui.layout.Box;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.TextLeaf;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.hud.FathomHud;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.FathomComponents;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.ui.Glow;
import net.fathom.optimizations.fabric.ui.Graphite;
import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * Fathom Hub — the screen Right Shift opens.
 *
 * <h2>What this is</h2>
 *
 * <p>The launcher, in the game: its composition over either the chosen Fathom
 * background or the live world through shared GPU glass. There is no
 * application window. The Hub's
 * parts float over it the way the launcher's rail, selectors and Play control
 * float over the recording:
 *
 * <pre>
 *    FATHOM  (Profile v)  Session              (Search) (HUD Editor)  x
 *
 *   ( Mods        )    Heading
 *   ( Performance )    subtitle
 *   ( Profiles    )    ( light group of rows ...                       )
 *   ( Quick       )
 *
 *   ( Launcher    )
 *   ( Diagnostics )
 * </pre>
 *
 * <p>The header is loose capsules, not a bar. Navigation is two floating
 * groups of light glass; the current section is the standard-glass capsule
 * with the brighter rim, and no marker bar. The content area is open: the
 * page's own groups are light glass, rows inside them are not cards, and only
 * what floats over everything (menus, popovers) is dense. Gameplay glass uses
 * one full-screen blur; individual panes never run their own blur.
 *
 * <h2>It takes the screen</h2>
 *
 * <p>Around 88% of the width and 82% of the height, up to sensible maxima. The
 * first version was a small pane in the middle of a dark screen, because it was
 * laid out in Minecraft's scaled-resolution space — 427×240 at GUI scale 3 — and
 * there was no room in that coordinate system for anything else. There is no
 * reason for a client menu to be small: while it is open it is the only thing
 * the user is looking at.
 *
 * <h2>No themes, and two presentation modes</h2>
 *
 * <p>Gameplay glass preserves, blurs and tints the live world. Fathom background
 * uses the chosen library image with its normal legibility treatment. With no
 * world -- opened from the title screen -- the latter is always used, never an
 * empty frame.
 *
 * <h2>Input does not come from Minecraft</h2>
 *
 * <p>{@code Screen.mouseClicked} and friends arrive from the game's 20 Hz tick —
 * up to 50 ms of latency on every hover and every click. They are overridden to
 * do nothing; the mouse is read from the device once per frame and turned into
 * edges. The LWJGL keyboard queue is drained here once per rendered frame too,
 * so capture, Escape and text entry do not wait for the 20 TPS loop.
 */
public final class FathomHubScreen extends Screen implements FathomScreen,
        net.fathom.optimizations.fabric.gfx.LaidOut {

    private static final String SEARCH_ID = "hub.search";


    /**
     * The background chosen in the launcher (see {@code FathomMenuScreen.drawBackdrop}).
     * A moving one pauses decoding and holds its last frame whenever the game
     * window loses focus.
     */
    private final Runnable backdropPass = new Runnable() {
        @Override
        public void run() {
            net.fathom.optimizations.fabric.menu.FathomMenuScreen.drawBackdrop(client);
        }
    };
    /** Mockup B's reference canvas and the content frame inside it, in dp. */
    private static final float FRAME_WIDTH = 1040f;
    private static final float FRAME_HEIGHT = 600f;
    private static final float RAIL_WIDTH = 184f;
    private static final float RAIL_TOP = 52f;
    private static final float RAIL_ITEM = GraphiteTokens.SIZE_CONTROL;
    private static final float RAIL_GAP = GraphiteTokens.SPACE_1;
    private static final float CONTENT_X = 200f;
    private static final float TELEMETRY_LINE = 16f;

    /** Telemetry text, rebuilt at 4 Hz rather than every frame. */
    private String telemetryFps = "";
    private String telemetryMemory = "";
    private float telemetryAt = -1f;

    private final FathomHud hud;
    private final Controls controls = new Controls();
    private final Pointer pointer = new Pointer();
    private final List<Section> sections = new ArrayList<Section>();

    private HubContext context;
    /** Shared entry/exit lifecycle; entry and exit remain independent. */
    private Transition transition;
    /** How far through a page change we are, for the content's slide. */
    private Motion pageSwap;
    private int current;
    private int previous;
    private boolean closing;
    private int captureFrames;
    private boolean captured;
    private float qaReplayAt = -1f;
    private boolean openingMeasured;
    private long openedAtNs;
    private long closeStartedNs;

    private Rect panel = new Rect(0f, 0f, 0f, 0f);

    // -- the frame as a layout tree: built when the sections are, laid out
    //    again only when the window, the scale or the restart note changes.
    private final Box root = Box.column("hub").justify(Box.Align.CENTRE).align(Box.Align.CENTRE);
    private final Box frame = Box.column("hub.frame");
    private final Box headerRow = Box.row("hub.header");
    private final Box wordmarkBox = Box.leaf("hub.wordmark", new Box.Measure() {
        @Override
        public float width(Renderer ui) {
            return Graphite.lockupWidth(ui, GraphiteTokens.WORDMARK_CAP_GAME);
        }

        @Override
        public float height(Renderer ui, float width) {
            return GraphiteTokens.WORDMARK_CAP_GAME;
        }
    });
    private final TextLeaf restartNote = TextLeaf.of("hub.restart", Graphite.SMALL, 16f);
    private final TextLeaf editLabel = TextLeaf.of("hub.hud-editor.label", Graphite.UI, 16f);
    private final Box editHudBox = Box.row("hub.hud-editor");
    private final Box editIconBox = Box.fixed("hub.hud-editor.icon", Graphite.ICON_INLINE, Graphite.ICON_INLINE);
    private final Box closeBox = Box.fixed("hub.close", Graphite.CONTROL_COMPACT, Graphite.CONTROL_COMPACT);
    private final Box railBox = Box.column("hub.rail");
    private final Box railRule = Box.column("hub.rail.rule").height(1f);
    private final Box[] telemetryRows = new Box[2];
    private final Box contentBox = Box.column("hub.content").grow(1f).alignSelf(Box.Align.STRETCH);
    private Box[] railItems = new Box[0];
    private String[] railIds = new String[0];
    private String[] railSelectedIds = new String[0];
    private boolean built;

    public FathomHubScreen(FathomHud hud) {
        this.hud = hud;
    }

    /** The section to open on, by title, instead of Mods. */
    private String startAt;

    /** Open on the section titled {@code title} (Quick, Mods, Performance...). */
    @Override
    public Box layoutRoot() {
        return built ? root : null;
    }

    /** A section the UI tour's measurement selects on the next frame, as a rail click would; -1 for none. */
    private int scriptedSection = -1;

    /** Select section {@code index} next frame exactly as clicking its rail item does. Development only. */
    public void scriptSection(int index) {
        scriptedSection = index;
    }

    /**
     * A module whose page should open the next time a Hub is shown: the HUD
     * editor's "Settings" sets it and closes, and the Hub it returns to (or a
     * new one) takes it. Render thread only.
     */
    private static String requestedModule;

    public static void requestModule(String moduleId) {
        requestedModule = moduleId;
    }

    /** The requested module, once: the Mods page asks, and opens its tab. */
    static String takeModuleRequest() {
        String requested = requestedModule;
        requestedModule = null;
        return requested;
    }

    // -- development self-test hooks (EditorSelfTest) ------------------------

    /** The section showing. */
    public String qaSection() {
        return sections.isEmpty() ? "" : sections.get(current).title();
    }

    /** The Mods page's open module, or null for the list or another section. */
    public String qaOpenModule() {
        Section section = sections.isEmpty() ? null : sections.get(current);
        return section instanceof ModsSection ? ((ModsSection) section).qaOpenModule() : null;
    }

    /** Where the Mods page showing is scrolled to. */
    public float qaScroll() {
        Section section = sections.isEmpty() ? null : sections.get(current);
        return section instanceof ModsSection ? ((ModsSection) section).qaScroll() : -1f;
    }

    /** As clicking a Mods tab: a module's, or "All mods" for null. */
    public void qaTab(String moduleId) {
        Section section = sections.isEmpty() ? null : sections.get(current);
        if (section instanceof ModsSection) {
            ((ModsSection) section).qaTab(context, moduleId);
        }
    }

    /** How many sections the rail lists. */
    public int sectionCount() {
        return sections.size();
    }

    /** Development motion tour: open a retained dropdown by its control id. */
    public void scriptPopover(String id) {
        controls.scriptPopover(id);
    }

    public FathomHubScreen startingAt(String title) {
        startAt = title;
        return this;
    }

    @Override
    public void init() {
        buttons.clear();
        if (context == null) {
            context = new HubContext(client, hud, Runtime.features(), controls, this, pointer);
            sections.add(new QuickSection());
            sections.add(new ModsSection());
            sections.add(new PerformanceSection());
            sections.add(new AppearanceSection());
            sections.add(new ProfilesSection());
            sections.add(new DiagnosticsSection());
            sections.add(new LauncherSection());

            float now = Gfx.clock().seconds();
            transition = new Transition(GraphiteTokens.MOTION_PANEL, GraphiteTokens.MOTION_CLOSE,
                    Ease.Curve.GRAPHITE, Ease.Curve.GRAPHITE);
            transition.open(now);
            openedAtNs = System.nanoTime();
            pageSwap = new Motion(1f, GraphiteTokens.MOTION_PANEL, Ease.Curve.GRAPHITE);
            // Mods is where the Hub opens.
            current = 1;
            for (int index = 0; startAt != null && index < sections.size(); index++) {
                if (sections.get(index).title().equalsIgnoreCase(startAt)) {
                    current = index;
                }
            }
            previous = current;
            sections.get(current).opened(context);
            selectCaptureSection();
        }
        // Back from the HUD editor's "Settings": show Mods, which opens the page.
        if (requestedModule != null) {
            for (int index = 0; index < sections.size(); index++) {
                if (sections.get(index) instanceof ModsSection && index != current) {
                    scriptedSection = index;
                }
            }
        }
        // Also reset on return from a child screen. A release that happened in
        // the editor must not click a Hub control on the first frame back.
        Gfx.restartClock();
        Gfx.frames().reset();
        pointer.reset();
        controls.reset();
        controls.setInputEnabled(!closing);
    }

    @Override
    public boolean shouldPauseGame() {
        // Half of what the hub shows — the frame rate, the ping, whether a
        // widget is in the way — only means anything while the game is moving.
        return false;
    }

    /**
     * Start closing.
     *
     * <p>Not {@code setScreen(null)}: the hub animates out, which it can only do
     * while it is still the screen being drawn. The last frame of the animation
     * hands the game back.
     */
    public void beginClose() {
        if (closing) {
            return;
        }
        closing = true;
        closeStartedNs = System.nanoTime();
        transition.close(Gfx.clock().seconds());
        // Exit is a distinct state, not opening in reverse. Make the fading UI
        // inert immediately so a release or repeat cannot mutate settings.
        controls.setInputEnabled(false);
        context.keybindCapture().cancel();
        controls.closePopover();
    }

    /** The frame-rate RShift edge: close, or reopen an exit already in flight. */
    public void toggleOpen() {
        if (!closing) {
            beginClose();
            return;
        }
        closing = false;
        transition.reopen(Gfx.clock().seconds());
        controls.setInputEnabled(true);
        closeStartedNs = 0L;
    }

    // -----------------------------------------------------------------------
    // The frame
    // -----------------------------------------------------------------------

    /** Unsaved changes are written after this long without another. */
    private static final long AUTOSAVE_QUIET_NANOS = 1_500_000_000L;

    @Override
    public void render(int ignoredMouseX, int ignoredMouseY, float ignoredDelta) {
        // Changes are written when the Hub closes, and also after a moment's
        // quiet while it stays open: a crash or a killed process with the Hub
        // up must not cost the last ten minutes of settings. The write itself
        // is the profile store's background one; this only hands it over.
        if (context != null && context.quietNanos() > AUTOSAVE_QUIET_NANOS && !closing) {
            String failure = hud.saveAll();
            if (failure != null) {
                System.out.println("[Fathom] could not save settings: " + failure);
            }
            context.saved();
        }
        // Gameplay glass leaves the freshly rendered world in the target
        // framebuffer. Fathom background retains the existing scene pass.
        if (!gameplayGlass()) {
            Gfx.isolated(backdropPass);
        }
        Renderer ui = Gfx.beginScreen(client, Runtime.uiScale());
        float delta = Gfx.screenDelta();
        boolean restorePrevious = false;
        try {
            context.setRenderer(ui);
            pointer.poll(client, ui);
            // Offered to the page before anything else sees it, so a keybind
            // waiting for a mouse button gets the button rather than a click on
            // whatever is underneath the cursor.
            //
            // Except the left button. It is how somebody moves on -- clicks
            // another row, another control, empty space -- and binding it there
            // turned "I changed my mind" into an attack key bound to a module.
            // While a key is listening, a left click cancels and does nothing
            // else; the old key stays. The other buttons still bind.
            for (int button = 0; button < pointer.buttonCount(); button++) {
                if (pointer.pressed(button) && context.keybindCapture().active()) {
                    if (button == 0) {
                        context.keybindCapture().cancel();
                    } else if (context.keybindCapture().mouse(button) == KeybindCaptureState.Outcome.BOUND) {
                        context.markDirty();
                    }
                    pointer.consume(button);
                    break;
                }
            }
            controls.begin(pointer, delta, ui);
            context.setDelta(delta);

            layout(ui);
            draw(ui);
            controls.end();

            restorePrevious = transition.finished(Gfx.clock().seconds());
        } finally {
            Gfx.endScreen();
        }
        if (!openingMeasured && !closing && transition.entered(Gfx.clock().seconds())) {
            openingMeasured = true;
            if (Boolean.getBoolean("fathom.transitionInstrumentation")) {
                System.out.println("[Fathom] Hub open QA: "
                        + millis(System.nanoTime() - openedAtNs) + " ms wall, "
                        + Gfx.frames().uiPercentileMillis(0.99f) + " ms UI p99, "
                        + Gfx.frames().framePercentileMillis(0.99f) + " ms frame p99.");
            }
        }
        // Screen replacement initializes the destination immediately. It must
        // happen only after Fathom has restored the framebuffer, matrices,
        // viewport and fixed-function state borrowed for the final Hub frame.
        if (restorePrevious) {
            float uiP99 = Gfx.frames().uiPercentileMillis(0.99f);
            float frameP99 = Gfx.frames().framePercentileMillis(0.99f);
            if (!FathomNavigation.back(client, this)) {
                throw new IllegalStateException("Fathom Hub has no navigation return target");
            }
            if (Boolean.getBoolean("fathom.transitionInstrumentation")) {
                System.out.println("[Fathom] Hub close QA: "
                        + millis(System.nanoTime() - closeStartedNs) + " ms wall, "
                        + uiP99 + " ms UI p99, " + frameP99
                        + " ms frame p99; GL state restored before handoff.");
            }
            if ("close".equalsIgnoreCase(System.getProperty("fathom.captureUi", ""))) {
                System.setProperty("fathom.closeComplete", "true");
            }
        }
        captureSettledFrame();
        if (!closing && captureFrames >= 90
                && "close".equalsIgnoreCase(System.getProperty("fathom.captureUi", ""))) {
            beginClose();
        }
    }

    private static String millis(long nanos) {
        return String.format(java.util.Locale.ROOT, "%.1f", nanos / 1_000_000d);
    }

    /** The section a visual-QA run photographs: over the world, or over the title screen. */
    private static String captureTarget() {
        String world = System.getProperty("fathom.captureHubWorld", "").trim();
        return world.isEmpty() ? System.getProperty("fathom.captureUi", "") : world;
    }

    private void selectCaptureSection() {
        String target = captureTarget();
        for (int index = 0; index < sections.size(); index++) {
            if (sections.get(index).title().equalsIgnoreCase(target)) {
                current = index;
                previous = index;
                sections.get(index).opened(context);
                return;
            }
        }
    }

    private void captureSettledFrame() {
        String target = captureTarget();
        captureFrames++;
        if (captured || target.isEmpty() || "menu".equalsIgnoreCase(target)
                || "hud".equalsIgnoreCase(target)
                || "hud-editor".equalsIgnoreCase(target)
                || "close".equalsIgnoreCase(target)
                || captureFrames < 90) {
            return;
        }
        if (captureFrames == 90 && Boolean.getBoolean("fathom.qaFocusSearch")) {
            controls.focus(SEARCH_ID);
        }
        // Settle on time, not only frames: at a high frame rate ninety frames is
        // shorter than the staggered card entrance. Visual-QA replay restarts the
        // page switch and card activation and photographs them this many seconds in.
        String replay = System.getProperty("fathom.qaReplay", "");
        float now = Gfx.clock().seconds();
        if (qaReplayAt < 0f) {
            qaReplayAt = now;
            if (!replay.isEmpty()) {
                previous = Math.max(0, current - 1);
                pageSwap.set(0f, now);
                pageSwap.to(1f, now);
                sections.get(current).qaReplay(context);
            }
            return;
        }
        if (now - qaReplayAt < (replay.isEmpty() ? 0.75f : Float.parseFloat(replay))) {
            return;
        }
        captured = true;
        String quality = Runtime.highQualityUi() ? "hq" : "fast";
        String name = "fathom-" + target.toLowerCase(java.util.Locale.ROOT) + "-"
                + quality + (replay.isEmpty() ? "" : "-replay" + replay)
                + "-" + client.width + "x" + client.height + ".png";
        ScreenshotUtils.saveScreenshot(client.runDirectory, name,
                client.width, client.height, client.getFramebuffer());
        System.out.println("[Fathom] UI QA " + target + " " + quality + ": mean "
                + Gfx.frames().uiMeanMillis() + " ms, "
                + net.fathom.optimizations.fabric.ui.HoverLatency.summary() + ".");
        client.scheduleStop();
    }

    /**
     * Mockup B: the content frame, 1040x600dp, centred on the screen and never
     * drawn. On a screen smaller than the reference canvas it shrinks to fit
     * with a 16dp game-menu margin; the extra width on a wider one stays
     * backdrop.
     */
    /**
     * The frame: a header row (wordmark, the restart note, Edit HUD and
     * Close), then the rail and the page side by side. Built once the sections
     * exist; Mockup B's measurements, from paddings and gaps.
     */
    private void buildTree() {
        built = true;
        headerRow.height(RAIL_ITEM).gap(Graphite.S2).align(Box.Align.CENTRE);
        headerRow.add(wordmarkBox);
        headerRow.add(Box.column("hub.header.gap").width(Graphite.S6));
        headerRow.add(restartNote.box().grow(1f));
        editHudBox.height(Graphite.CONTROL_COMPACT).padding(Graphite.S3, 0f, Graphite.S3, 0f).gap(Graphite.S2)
                .align(Box.Align.CENTRE);
        editHudBox.add(editIconBox);
        editHudBox.add(editLabel.box());
        headerRow.add(editHudBox);
        headerRow.add(closeBox);
        frame.add(headerRow);
        frame.add(Box.column("hub.header.after").height(RAIL_TOP - RAIL_ITEM));

        Box body = Box.row("hub.body").grow(1f).align(Box.Align.START);
        railBox.width(RAIL_WIDTH).padding(Graphite.S2).gap(RAIL_GAP).align(Box.Align.STRETCH);
        int count = sections.size();
        railItems = new Box[count];
        railIds = new String[count];
        railSelectedIds = new String[count];
        for (int index = 0; index < count; index++) {
            railIds[index] = "rail." + index;
            railSelectedIds[index] = "rail." + index + "#selected";
            railItems[index] = Box.column(railIds[index]).height(RAIL_ITEM);
            railBox.add(railItems[index]);
        }
        // The divider and the telemetry: S2 under the last item, S2 under the rule.
        railBox.add(Box.column("hub.rail.rule.before").height(Graphite.S2 - RAIL_GAP * 2f));
        railBox.add(railRule);
        railBox.add(Box.column("hub.rail.rule.after").height(Graphite.S2 - RAIL_GAP * 2f));
        Box telemetry = Box.column("hub.telemetry");
        for (int line = 0; line < telemetryRows.length; line++) {
            telemetryRows[line] = Box.column("hub.telemetry." + line).height(TELEMETRY_LINE)
                    .padding(4f, 0f, 0f, 0f);
            telemetry.add(telemetryRows[line]);
        }
        railBox.add(telemetry);
        body.add(railBox);
        body.add(Box.column("hub.body.gap").width(CONTENT_X - RAIL_WIDTH));
        body.add(contentBox);
        frame.add(body);
        root.add(frame);
        editLabel.set("Edit HUD");
    }

    private void layout(Renderer ui) {
        if (!built) {
            buildTree();
        }
        float margin = GraphiteTokens.LAYOUT_GAME_MARGIN;
        frame.size(Math.min(FRAME_WIDTH, ui.width() - margin * 2f), Math.min(FRAME_HEIGHT, ui.height() - margin * 2f));
        String restart = context.restartNote();
        restartNote.set(restart == null ? "" : restartNoteText(restart));
        root.layout(ui, 0f, 0f, ui.width(), ui.height());
        panel = frame.rect();
    }

    /** "<module> needs a restart", kept until the module changes. */
    private String restartNoteText(String module) {
        if (!module.equals(restartFor)) {
            restartFor = module;
            restartText = module + " needs a restart";
        }
        return restartText;
    }

    private String restartFor = "";
    private String restartText = "";

    private void draw(Renderer ui) {
        float now = Gfx.clock().seconds();
        float open = transition.enter(now);
        float shown = transition.visibility(now);

        if (gameplayGlass()) {
            // One full-screen glass primitive asks the compositor to capture
            // the world framebuffer at the BACKDROP boundary. SdfBackdrop
            // downsamples and blurs it entirely on the GPU; the tint then
            // restores predictable contrast without replacing the scene.
            ui.pushAlpha(shown);
            Graphite.captureFrost(ui);
            ui.glass(0f, 0f, ui.width(), ui.height(), 0f,
                    Runtime.rshiftBlur(), 1.06f, 0.97f);
            ui.rounded(0f, 0f, ui.width(), ui.height(), 0f,
                    Colour.argb(Runtime.rshiftDim(), 0x071019));
            ui.popAlpha();
        }

        // Groups fade and rise 8dp on entry; on exit they fade.
        ui.pushAlpha(shown);
        ui.pushTransform(0f, (1f - open) * GraphiteTokens.MOTION_TRAVEL);

        // The scene behind the glass, so the Hub's panes are frosted like the
        // title screen's and the launcher's. No light pooled behind the panel:
        // it read as a pale haze the launcher does not have.
        Graphite.captureFrost(ui);
        header(ui);
        rail(ui, railBox.rect());
        content(ui, contentBox.rect());

        // Popovers last, and inside the composition's transform.
        controls.drawOverlays(ui);

        ui.popTransform();
        ui.popAlpha();
    }

    /** Gameplay glass is meaningful only with an actual world framebuffer. */
    private boolean gameplayGlass() {
        return client != null && client.world != null && Runtime.gameplayGlass();
    }

    /**
     * The lockup, mark and wordmark, at the frame's left, centred on y 78; Close at the right
     * edge, Edit HUD 8dp to its left. No bar, no picker, no session line.
     */
    private void header(Renderer ui) {
        float cap = GraphiteTokens.WORDMARK_CAP_GAME;
        Rect mark = wordmarkBox.rect();
        Graphite.lockup(ui, mark.x, mark.y, cap);

        // The launcher's window controls: a round 32dp target, secondary ink
        // that comes up to full strength under the pointer, a denser veil
        // while pressed, and its name after the tooltip delay.
        Rect close = closeBox.rect();
        Controls.State closeState = controls.state("hub.close", close, true);
        Graphite.overlay(ui, close, Graphite.R_CONTROL, Math.max(closeState.hover, closeState.press * 1.6f), 0f);
        ui.icon(Icon.CLOSE, close.centreX() - Graphite.ICON_INLINE / 2f,
                close.centreY() - Graphite.ICON_INLINE / 2f, Graphite.ICON_INLINE,
                Colour.mix(Graphite.TEXT_SECONDARY, Graphite.TEXT, closeState.hover));
        controls.tooltip(closeState, close, "Close");
        if (closeState.clicked) {
            beginClose();
        }

        // Edit HUD: a secondary capsule with the HUD-editor glyph, as the
        // launcher draws an action with an icon.
        Rect editHud = editHudBox.rect();
        Controls.State editState = controls.state("hub.hud-editor", editHud, true);
        Graphite.surface(ui, editHud, Graphite.R_CONTROL, Graphite.Material.LIGHT,
                editState.hover, editState.press, 0f, false);
        Rect editIcon = editIconBox.rect();
        ui.icon(Icon.MOVE, editIcon.x, editIcon.y, editIcon.width, Graphite.TEXT_SECONDARY);
        editLabel.draw(ui, Graphite.TEXT);
        controls.focusRing(ui, editState, editHud, Graphite.R_CONTROL);
        if (editState.clicked) {
            context.editHud();
        }

        // A module that needs a restart says so, quietly, beside the wordmark.
        restartNote.draw(ui, Graphite.TEXT_SECONDARY);
    }

    private float railHeight() {
        int count = sections.size();
        float items = count * RAIL_ITEM + (count - 1) * RAIL_GAP;
        return Graphite.S2 + items + Graphite.S2 + 1f + Graphite.S2 + TELEMETRY_LINE * 2f + Graphite.S2;
    }

    /**
     * The section rail: one Light group, 184dp wide, radius 12, 8dp padding,
     * six items 36dp tall and 4dp apart. The current section is the selected
     * overlay with a 16% rim and primary text -- no colour. Under a hairline,
     * two lines of live telemetry in Geist Mono, each after a 6dp signal dot:
     * the only cyan on the screen, and only while the game is running.
     */
    private void rail(Renderer ui, Rect rail) {
        float now = Gfx.clock().seconds();
        Graphite.surface(ui, rail, Graphite.R_PANEL, Graphite.Material.LIGHT, false);
        Type label = Graphite.floor(ui, Graphite.UI);
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            Rect item = railItems[index].rect();
            boolean selected = index == current;
            Controls.State state = controls.state(railIds[index], item, true);
            float on = controls.towards(railSelectedIds[index], selected, GraphiteTokens.MOTION_FAST);
            // Concentric with the rail: its 18 radius less its 8dp padding is
            // the small radius (10), which is what an item inside a panel is.
            Graphite.overlay(ui, item, Graphite.R_SEGMENT, state.hover * (1f - on), on);
            // The current section is the one lit place in the rail.
            Glow.current(ui, item, Graphite.R_SEGMENT, on);
            int ink = Colour.mix(Graphite.TEXT_SECONDARY, Graphite.TEXT, Math.max(on, state.hover));
            // The navigation glyph size, centred in the item's leading square,
            // exactly as the launcher's rail draws its icons.
            float glyph = Graphite.ICON_NAV;
            float lead = (RAIL_ITEM - glyph) / 2f;
            ui.icon(section.icon(), item.x + lead, Skin.snap(ui, item.centreY() - glyph / 2f), glyph,
                    Colour.mix(ink, Graphite.BRAND_LIGHT, on));
            ui.text(section.title(), item.x + lead + glyph + Graphite.S3,
                    Graphite.baseline(ui, item, label), label, ink);
            if ((state.clicked || index == scriptedSection) && !selected) {
                // When a second navigation input arrives mid-crossfade, retain
                // the current page at exactly its visible opacity. A settled
                // page starts a fresh transition at zero as usual.
                float continuation = pageSwap.settled(now) ? 0f : pageSwap.opacity(now);
                previous = current;
                current = index;
                pageSwap.set(continuation, now);
                pageSwap.to(1f, now);
                controls.closePopover();
                controls.clearFocus();
                context.keybindCapture().cancel();
                sections.get(current).opened(context);
            }
        }
        scriptedSection = -1;
        Rect rule = railRule.rect();
        Graphite.hairline(ui, rule.x, rule.right(), rule.y);
        telemetry(ui, now);
    }

    /** Frame rate and memory, 4 Hz, in Data S. The dot is signal only while live. */
    private void telemetry(Renderer ui, float now) {
        if (telemetryAt < 0f || now - telemetryAt >= 0.25f) {
            telemetryAt = now;
            telemetryFps = Math.round(Gfx.frames().fps()) + " FPS";
            java.lang.Runtime memory = java.lang.Runtime.getRuntime();
            float used = (memory.totalMemory() - memory.freeMemory()) / (1024f * 1024f * 1024f);
            float max = memory.maxMemory() / (1024f * 1024f * 1024f);
            telemetryMemory = String.format(java.util.Locale.ROOT, "%.1f / %.1f GB", used, max);
        }
        boolean live = !client.isPaused();
        int value = live ? Graphite.TEXT : Graphite.TEXT_SECONDARY;
        Type data = Graphite.floor(ui, Graphite.DATA_S);
        float dot = 6f;
        for (int line = 0; line < telemetryRows.length; line++) {
            Rect row = telemetryRows[line].content();
            if (live) {
                ui.rounded(row.x, row.centreY() - dot / 2f, row.x + dot, row.centreY() + dot / 2f,
                        dot / 2f, Graphite.SIGNAL);
            }
            ui.text(line == 0 ? telemetryFps : telemetryMemory, row.x + dot + Graphite.S2,
                    Graphite.baseline(ui, row, data), data, value);
        }
    }

    private void content(Renderer ui, Rect content) {
        Section section = sections.get(current);
        // Open: the page starts level with the rail and has no pane of its own.
        Rect inner = content;

        // A page change crossfades the old page out while the new page rises
        // in. The destination is interactive from its first frame; the old
        // page is presentation-only while it leaves.
        float swap = pageSwap.value(Gfx.clock().seconds());
        float rise = (1f - swap) * GraphiteTokens.MOTION_TRAVEL;

        ui.clip(content.x - Graphite.S6, content.y - Graphite.S2, content.right() + Graphite.S6,
                content.bottom() + Graphite.S6);
        if (previous != current && swap < 1f) {
            controls.setInputEnabled(false);
            ui.pushAlpha(1f - swap);
            ui.pushTransform(0f, -swap * GraphiteTokens.MOTION_TRAVEL * 0.5f);
            drawSection(ui, sections.get(previous), inner);
            ui.popTransform();
            ui.popAlpha();
            controls.setInputEnabled(!closing);
        }
        ui.pushAlpha(swap);
        ui.pushTransform(0f, rise);
        drawSection(ui, section, inner);
        ui.popTransform();
        ui.popAlpha();
        ui.unclip();

        int notches = pointer.wheelNotches();
        // Anywhere over the Hub: the rail and header do not scroll, so a wheel
        // turned over them means the page. (A scripted benchmark's
        // synthetic wheel is not tied to wherever an unfocused pointer sits.)
        if (notches != 0 && (panel.contains(pointer.x(), pointer.y())
                || net.fathom.optimizations.fabric.bench.PerfBench.ACTIVE)) {
            section.scroll(notches);
        }
    }

    private void drawSection(Renderer ui, Section section, Rect inner) {
        Rect body;
        if (section.ownsHeading()) {
            body = inner;
        } else {
            Rect heading = FathomComponents.sectionHeader(ui, inner, section.title(), section.subtitle());
            body = inner.belowTop(heading.height + Skin.SECTION_GAP);
        }
        section.render(context, body);
    }

    // -----------------------------------------------------------------------
    // Minecraft's own input, deliberately unused
    // -----------------------------------------------------------------------

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        // Nothing. Presses are read from the device once per frame, not
        // delivered from a 20 Hz tick; calling super would hand every click to
        // the screen twice, at two different times.
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        // Nothing, for the same reason.
    }

    @Override
    protected void mouseDragged(int mouseX, int mouseY, int button, long heldMillis) {
        // Nothing, for the same reason.
    }

    @Override
    public void handleMouse() {
        // Not even the wheel: it is polled with everything else.
    }

    @Override
    public boolean handleFathomKey(char character, int keyCode) {
        if (closing) {
            return true;
        }
        if (context.keybindCapture().active()) {
            net.fathom.optimizations.core.module.Keybind target = context.keybindCapture().target();
            KeybindCaptureState.Outcome result =
                    context.keybindCapture().keyboard(keyCode, Keyboard.KEY_ESCAPE);
            if (result == KeybindCaptureState.Outcome.CLEARED
                    && Runtime.hud() != null && target == Runtime.hud().hubKey()) {
                // The one key that cannot be unbound: with it cleared there is
                // no way back into the Hub to bind it again.
                target.reset();
            }
            if (result == KeybindCaptureState.Outcome.BOUND || result == KeybindCaptureState.Outcome.CLEARED) {
                context.markDirty();
            }
            return true;
        }
        if (sections.get(current).key(context, character, keyCode)) {
            return true;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // One layer at a time: a popover, then the search, then the hub.
            if (controls.popoverOpen()) {
                controls.closePopover();
            } else if (!context.search().isEmpty() && sections.get(current).searchable()) {
                context.setSearch("");
                // Done typing: the Hub key closes the Hub again.
                controls.clearFocus();
            } else {
                beginClose();
            }
            return true;
        }
        // FrameInput owns the Hub key. It suppresses the opening event and does
        // not toggle while capture is active.
        if (Runtime.hud() != null && Runtime.hud().hubKey().matches(keyCode)) {
            return true;
        }
        if (!sections.get(current).searchable()) {
            return false;
        }
        // Typing goes to the search box whether or not it has been clicked. In a
        // screen whose only text field is the search box, making somebody click
        // it first is ceremony.
        if (keyCode == Keyboard.KEY_BACK) {
            String value = context.search();
            if (!value.isEmpty()) {
                context.setSearch(value.substring(0, value.length() - 1));
            }
            controls.focus(SEARCH_ID);
            return true;
        }
        if (character >= ' ' && character != 127) {
            context.setSearch(context.search() + character);
            controls.focus(SEARCH_ID);
            return true;
        }
        return false;
    }

    @Override
    protected void keyPressed(char character, int keyCode) {
        // FrameKeyboard owns the queue while this screen is current.
    }

    public boolean capturingKeybind() {
        return context != null && context.keybindCapture().active();
    }

    /**
     * Whether a press of the Hub key belongs to something in the Hub: a key
     * being listened for, the search box after somebody has typed into it, or
     * a field on the current page.
     */
    public boolean busy() {
        return capturingKeybind()
                || controls.focused(SEARCH_ID)
                || (!sections.isEmpty() && sections.get(current).typing());
    }

    /** Keep a mouse-bound Hub close gesture out of the control under it. */
    public void consumeMouseButton(int button) {
        pointer.consume(button);
    }

    @Override
    public void removed() {
        net.fathom.optimizations.fabric.menu.FathomMenuScreen.stopBackdropPlayback();
        if (context != null && context.dirty()) {
            String failure = hud.saveAll();
            if (failure != null) {
                System.out.println("[Fathom] could not save settings: " + failure);
            }
            context.saved();
        }
        pointer.reset();
        if (context != null) {
            context.keybindCapture().cancel();
        }
    }
}
