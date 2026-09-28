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
import net.fathom.optimizations.core.ui.layout.Rect;
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
 * <p>The launcher, in the game: its composition over its background. There is
 * no application window. The background chosen in the launcher is the page
 * under a light scrim, and the Hub's
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
 * what floats over everything (menus, popovers) is dense. There is no blur
 * anywhere: see {@code Skin}.
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
 * <h2>No themes, and the world as context</h2>
 *
 * <p>In a world, the world is the background, deepened only enough for ivory
 * type to hold. With no world -- opened from the title screen -- it is the
 * launcher's photograph, as the title screen draws it, never an empty frame.
 *
 * <h2>Input does not come from Minecraft</h2>
 *
 * <p>{@code Screen.mouseClicked} and friends arrive from the game's 20 Hz tick —
 * up to 50 ms of latency on every hover and every click. They are overridden to
 * do nothing; the mouse is read from the device once per frame and turned into
 * edges. The LWJGL keyboard queue is drained here once per rendered frame too,
 * so capture, Escape and text entry do not wait for the 20 TPS loop.
 */
public final class FathomHubScreen extends Screen implements FathomScreen {

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

    public FathomHubScreen(FathomHud hud) {
        this.hud = hud;
    }

    @Override
    public void init() {
        buttons.clear();
        if (context == null) {
            context = new HubContext(client, hud, Runtime.features(), controls, this, pointer);
            sections.add(new QuickSection());
            sections.add(new ModsSection());
            sections.add(new PerformanceSection());
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
            previous = 1;
            sections.get(current).opened(context);
            selectCaptureSection();
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

    // -----------------------------------------------------------------------
    // The frame
    // -----------------------------------------------------------------------

    @Override
    public void render(int ignoredMouseX, int ignoredMouseY, float ignoredDelta) {
        // One video pass; panels add tint and rims, never another blur/capture.
        Gfx.isolated(backdropPass);
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
    private void layout(Renderer ui) {
        float margin = GraphiteTokens.LAYOUT_GAME_MARGIN;
        float width = Math.min(FRAME_WIDTH, ui.width() - margin * 2f);
        float height = Math.min(FRAME_HEIGHT, ui.height() - margin * 2f);
        panel = new Rect((ui.width() - width) / 2f, (ui.height() - height) / 2f, width, height);
    }

    private void draw(Renderer ui) {
        float now = Gfx.clock().seconds();
        float open = transition.enter(now);
        float shown = transition.visibility(now);

        // Groups fade and rise 8dp on entry; on exit they fade.
        ui.pushAlpha(shown);
        ui.pushTransform(0f, (1f - open) * GraphiteTokens.MOTION_TRAVEL);

        // The scene's light pooling behind the Hub (Glow).
        Glow.behind(ui, panel);
        header(ui);
        Rect rail = new Rect(panel.x, panel.y + RAIL_TOP, RAIL_WIDTH, railHeight());
        rail(ui, rail);
        Rect content = Rect.of(panel.x + Math.min(CONTENT_X, panel.width * 0.2f), panel.y + RAIL_TOP,
                panel.right(), panel.bottom());
        content(ui, content);

        // Popovers last, and inside the composition's transform.
        controls.drawOverlays(ui);

        ui.popTransform();
        ui.popAlpha();
    }

    /**
     * The wordmark at the frame's left, centred on y 78; Close at the right
     * edge, Edit HUD 8dp to its left. No bar, no picker, no session line.
     */
    private void header(Renderer ui) {
        float cap = GraphiteTokens.WORDMARK_CAP_GAME;
        float centre = panel.y + 18f;
        Graphite.wordmark(ui, panel.x, centre - cap / 2f, cap);

        // The launcher's window controls: a round 32dp target, secondary ink
        // that comes up to full strength under the pointer, a denser veil
        // while pressed, and its name after the tooltip delay.
        float compact = Graphite.CONTROL_COMPACT;
        Rect close = new Rect(panel.right() - compact, panel.y + 2f, compact, compact);
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
        Type label = Graphite.floor(ui, Graphite.UI);
        String edit = "Edit HUD";
        float editIcon = Graphite.ICON_INLINE;
        float editWidth = ui.measure(edit, label) + editIcon + Graphite.S2 + Graphite.S3 * 2f;
        Rect editHud = new Rect(close.x - Graphite.S2 - editWidth, close.y, editWidth, compact);
        Controls.State editState = controls.state("hub.hud-editor", editHud, true);
        Graphite.surface(ui, editHud, Graphite.R_CONTROL, Graphite.Material.LIGHT,
                editState.hover, editState.press, 0f, false);
        ui.icon(Icon.MOVE, editHud.x + Graphite.S3, editHud.centreY() - editIcon / 2f, editIcon,
                Graphite.TEXT_SECONDARY);
        ui.text(edit, editHud.x + Graphite.S3 + editIcon + Graphite.S2, Graphite.baseline(ui, editHud, label),
                label, Graphite.TEXT);
        controls.focusRing(ui, editState, editHud, Graphite.R_CONTROL);
        if (editState.clicked) {
            context.editHud();
        }

        // A module that needs a restart says so, quietly, beside the wordmark.
        String restart = context.restartNote();
        if (restart != null) {
            Type small = Graphite.floor(ui, Graphite.SMALL);
            float x = panel.x + Graphite.wordmarkWidth(ui, cap) + Graphite.S6;
            ui.text(Text.fit(ui, restart + " needs a restart", small, editHud.x - x - Graphite.S4),
                    x, centre + ui.ascent(small) / 2f, small, Graphite.TEXT_SECONDARY);
        }
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
        float y = rail.y + Graphite.S2;
        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            Rect item = new Rect(rail.x + Graphite.S2, y, rail.width - Graphite.S2 * 2f, RAIL_ITEM);
            boolean selected = index == current;
            Controls.State state = controls.state("rail." + index, item, true);
            float on = controls.towards("rail." + index + "#selected", selected, GraphiteTokens.MOTION_FAST);
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
            ui.icon(section.icon(), item.x + lead, Skin.snap(ui, item.centreY() - glyph / 2f), glyph, ink);
            ui.text(section.title(), item.x + lead + glyph + Graphite.S3,
                    Graphite.baseline(ui, item, label), label, ink);
            if (state.clicked && !selected) {
                previous = current;
                current = index;
                pageSwap.set(0f, now);
                pageSwap.to(1f, now);
                controls.clearFocus();
                context.keybindCapture().cancel();
                sections.get(current).opened(context);
            }
            y += RAIL_ITEM + RAIL_GAP;
        }
        y += Graphite.S2 - RAIL_GAP;
        Graphite.hairline(ui, rail.x + Graphite.S2, rail.right() - Graphite.S2, y);
        y += 1f + Graphite.S2;
        telemetry(ui, rail.x + Graphite.S2 + 4f, y, now);
    }

    /** Frame rate and memory, 4 Hz, in Data S. The dot is signal only while live. */
    private void telemetry(Renderer ui, float x, float y, float now) {
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
        String[] lines = {telemetryFps, telemetryMemory};
        for (int line = 0; line < lines.length; line++) {
            Rect row = new Rect(x, y + line * TELEMETRY_LINE, 150f, TELEMETRY_LINE);
            if (live) {
                ui.rounded(row.x, row.centreY() - dot / 2f, row.x + dot, row.centreY() + dot / 2f,
                        dot / 2f, Graphite.SIGNAL);
            }
            ui.text(lines[line], row.x + dot + Graphite.S2, Graphite.baseline(ui, row, data), data, value);
        }
    }

    private void content(Renderer ui, Rect content) {
        Section section = sections.get(current);
        // Open: the page starts level with the rail and has no pane of its own.
        Rect inner = content;

        // A page change fades and rises 8dp over 180ms.
        float swap = pageSwap.value(Gfx.clock().seconds());
        float rise = (1f - swap) * GraphiteTokens.MOTION_TRAVEL;

        ui.clip(content.x - Graphite.S6, content.y - Graphite.S2, content.right() + Graphite.S6,
                content.bottom() + Graphite.S6);
        ui.pushAlpha(swap);
        ui.pushTransform(0f, rise);

        Rect body;
        if (section.ownsHeading()) {
            body = inner;
        } else {
            Rect heading = FathomComponents.sectionHeader(ui, inner, section.title(), section.subtitle());
            body = inner.belowTop(heading.height + Skin.SECTION_GAP);
        }
        section.render(context, body);

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
