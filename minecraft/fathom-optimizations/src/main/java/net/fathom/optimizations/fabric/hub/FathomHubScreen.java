package net.fathom.optimizations.fabric.hub;

import java.util.ArrayList;
import java.util.List;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotUtils;

import net.fathom.optimizations.core.input.KeybindCaptureState;
import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Spring;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.anim.Transition;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.hud.FathomHud;
import net.fathom.optimizations.fabric.ui.BrandMark;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.ui.VisualEffects;

/**
 * Fathom Hub — the screen Right Shift opens.
 *
 * <h2>What this is</h2>
 *
 * <p>The launcher, in the game: black chrome around a near-black panel, the
 * launcher's greys, its red, its 4px spacing, its radii, its three durations and
 * one curve, and its typeface at its sizes. Not a config screen with Fathom's
 * colours on it.
 *
 * <pre>
 *   +-------------------------------------------------------+
 *   | [F] Fathom   Session                 Search        x   |  black
 *   +----+--------------------------------------------------+
 *   | [] |  Heading                                         |
 *   | () |  subtitle                                        |
 *   | <> |  content                         (labels on hover) |
 *   +----+--------------------------------------------------+
 * </pre>
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
 * <h2>No themes, and a very dark world</h2>
 *
 * <p>The world behind is darkened hard and left there as context. The blurred
 * photographs and the theme picker are gone: a menu inside a game is already
 * sitting on the most decorated background it will ever have.
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

    /** Units beside a rail icon below which a label is not worth drawing. */
    private static final float LABEL_MIN_ROOM = 30f;
    /** And the travel over which it fades up once it is. */
    private static final float LABEL_FADE_ROOM = 36f;

    private final FathomHud hud;
    private final Controls controls = new Controls();
    private final Pointer pointer = new Pointer();
    private final List<Section> sections = new ArrayList<Section>();

    private HubContext context;
    /** Shared entry/exit lifecycle; entry and exit remain independent. */
    private Transition transition;
    /** Which rail row the marker is on, so it slides rather than jumps. */
    private Spring railMark;
    /** How far through a page change we are, for the content's slide. */
    private Motion pageSwap;
    /** Width of the label tray beyond the always-visible icon rail. */
    private Motion railTray;

    private int current;
    private int previous;
    private boolean closing;
    private boolean railPinned;
    private int captureFrames;
    private boolean captured;
    private float qaReplayAt = -1f;
    private boolean openingMeasured;
    private long openedAtNs;
    private long closeStartedNs;

    private Rect panel = new Rect(0f, 0f, 0f, 0f);
    private Rect searchBox = new Rect(0f, 0f, 0f, 0f);

    public FathomHubScreen(FathomHud hud) {
        this.hud = hud;
    }

    @Override
    public void init() {
        buttons.clear();
        if (context == null) {
            context = new HubContext(client, hud, Runtime.features(), controls, this, pointer);
            sections.add(new ModsSection());
            sections.add(new PerformanceSection());
            sections.add(new ProfilesSection());
            sections.add(new QuickSection());
            sections.add(new LauncherSection());
            sections.add(new DiagnosticsSection());

            float now = Gfx.clock().seconds();
            transition = new Transition(Timing.HUB_ENTER, Timing.HUB_EXIT);
            transition.open(now);
            openedAtNs = System.nanoTime();
            railMark = Spring.accent(0f);
            railTray = new Motion(0f, Timing.NAVIGATION);
            pageSwap = new Motion(1f, Timing.NAVIGATION);
            sections.get(0).opened(context);
            selectCaptureSection();
            // Visual QA for the label tray. A capture is a still, and the tray
            // is the one part of the hub whose interesting state only exists
            // while a pointer is on it, so the gate can ask for it pinned.
            railPinned = Boolean.getBoolean("fathom.captureRail");
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
        Renderer ui = Gfx.beginScreen(client, Runtime.uiScale());
        float delta = Gfx.screenDelta();
        boolean restorePrevious = false;
        try {
            context.setRenderer(ui);
            pointer.poll(client, ui);
            // Offered to the page before anything else sees it, so a keybind
            // waiting for a mouse button gets the button rather than a click on
            // whatever is underneath the cursor.
            for (int button = 0; button < pointer.buttonCount(); button++) {
                if (pointer.pressed(button) && context.keybindCapture().active()) {
                    if (context.keybindCapture().mouse(button) == KeybindCaptureState.Outcome.BOUND) {
                        context.markDirty();
                    }
                    pointer.consume(button);
                    break;
                }
            }
            controls.begin(pointer, delta);
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

    private void selectCaptureSection() {
        String target = System.getProperty("fathom.captureUi", "");
        for (int index = 0; index < sections.size(); index++) {
            if (sections.get(index).title().equalsIgnoreCase(target)) {
                current = index;
                previous = index;
                railMark.set(index);
                sections.get(index).opened(context);
                return;
            }
        }
    }

    private void captureSettledFrame() {
        String target = System.getProperty("fathom.captureUi", "");
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

    private void layout(Renderer ui) {
        float availableWidth = Math.max(320f, ui.width() - Skin.S6 * 2f);
        float availableHeight = Math.max(240f, ui.height() - Skin.S6 * 2f);
        float width = clamp(ui.width() * Skin.SCREEN_FRACTION_X,
                Math.min(Skin.MIN_WIDTH, availableWidth), Math.min(Skin.MAX_WIDTH, availableWidth));
        float height = clamp(ui.height() * Skin.SCREEN_FRACTION_Y,
                Math.min(Skin.MIN_HEIGHT, availableHeight), Math.min(Skin.MAX_HEIGHT, availableHeight));
        panel = Skin.pixelAligned(ui,
                new Rect((ui.width() - width) / 2f, (ui.height() - height) / 2f, width, height));
    }

    private static float clamp(float value, float low, float high) {
        float top = Math.max(low, high);
        return value < low ? low : (value > top ? top : value);
    }

    private void draw(Renderer ui) {
        float now = Gfx.clock().seconds();
        float open = transition.enter(now);
        float exit = transition.exit(now);
        float shown = transition.visibility(now);

        // The world, darkened hard and left as context.
        ui.fill(0f, 0f, ui.width(), ui.height(), Skin.scaleAlpha(Skin.SCRIM, shown));

        ui.pushAlpha(shown);
        // Rises and grows a little as it arrives. Small enough that nobody reads
        // it as an effect; absent, the hub appears rather than opens.
        float scale = 0.990f + 0.010f * open - 0.004f * exit;
        ui.pushTransform(0f, (1f - open) * 8f + exit * 3f,
                scale, panel.centreX(), panel.centreY());

        VisualEffects.panelShadow(ui, panel, Skin.R_PANEL);
        VisualEffects.layeredSurface(ui, panel, Skin.R_PANEL, Skin.BACKGROUND, 0f);
        ui.outline(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_PANEL,
                Skin.BORDER_STANDARD, Skin.EDGE_SURFACE);

        Rect header = panel.top(Skin.HEADER_HEIGHT);
        Rect body = panel.belowTop(Skin.HEADER_HEIGHT);
        Rect rail = body.left(Skin.RAIL_WIDTH);
        Rect content = body.rightOf(Skin.RAIL_WIDTH);

        chrome(ui, header, rail);
        VisualEffects.pageDepth(ui, content, Skin.R_PANEL);
        header(ui, header);

        // The tray is decided before the page draws and drawn after it. Decided
        // first so the page it covers can be shielded from the pointer; drawn
        // last so it is on top. Opening navigation never reflows the page.
        Rect tray = railTray(rail);
        controls.shield(tray);
        content(ui, content);
        controls.clearShield();
        rail(ui, rail, tray);

        // Popovers last, and inside the panel's transform: a dropdown drawn
        // outside it would sit a pixel or two from the control that opened it
        // while the panel is still animating in.
        controls.drawOverlays(ui);

        ui.popTransform();
        ui.popAlpha();
    }

    /** The black frame: a header bar and a rail, with the panel's own corners. */
    private void chrome(Renderer ui, Rect header, Rect rail) {
        int chrome = Skin.BACKGROUND;
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).rounded(header.x, header.y, header.right(), header.bottom(),
                    Skin.R_PANEL, Skin.R_PANEL, 0f, 0f, chrome);
        } else {
            ui.rounded(header.x, header.y, header.right(), header.y + Skin.R_PANEL * 2f,
                    Skin.R_PANEL, chrome);
            ui.fill(header.x, header.y + Skin.R_PANEL, header.right(), header.bottom(), chrome);
        }
        ui.fill(header.x, header.bottom(), header.right(), header.bottom() + Skin.HAIRLINE, Skin.CHROME_LINE_STRONG);

        int railFill = Skin.RECESSED;
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).rounded(rail.x, rail.y, rail.right(), rail.bottom(),
                    0f, 0f, 0f, Skin.R_PANEL, railFill);
        } else {
            ui.fill(rail.x, rail.y, rail.right(), rail.bottom() - Skin.R_PANEL, railFill);
            ui.rounded(rail.x, rail.bottom() - Skin.R_PANEL * 2f, rail.right(), rail.bottom(),
                    Skin.R_PANEL, railFill);
        }
        ui.fill(rail.right() - Skin.HAIRLINE, rail.y, rail.right(),
                rail.bottom() - Skin.R_PANEL, Skin.CHROME_LINE);
    }

    private void header(Renderer ui, Rect header) {
        Rect inner = header.inset(Skin.S5, 0f);
        float cursor = inner.x;

        // Exact Aperture geometry from /assets/brand, drawn directly at the
        // active framebuffer scale rather than through a fixed-size texture.
        float mark = 28f;
        Rect markBox = inner.vcentre(0f, mark, mark);
        BrandMark.draw(ui, markBox.x, markBox.y, mark, Skin.TEXT, Skin.ACCENT);
        cursor += mark + Skin.S3;

        cursor += ui.text("FATHOM", cursor, Controls.baseline(ui, header, Type.TITLE), Type.TITLE, Skin.TEXT);
        cursor += Skin.S5;

        // Header actions are laid out from the right. HUD Editor stays visible
        // at every supported width; session detail yields first on small UIs.
        Rect close = inner.vcentreRight(Skin.S1, Controls.ICON_BUTTON, Controls.ICON_BUTTON);
        float rightOffset = Controls.ICON_BUTTON + Skin.S3 + Skin.S1;
        float hudWidth = 112f;
        Rect hudEditor = inner.vcentreRight(rightOffset, hudWidth, Skin.H_MD);
        rightOffset += hudWidth + Skin.S3;
        boolean searchable = sections.get(current).searchable();
        float searchWidth = Math.min(280f, Math.max(156f, panel.width * 0.22f));
        searchBox = searchable
                ? inner.vcentreRight(rightOffset, searchWidth, Skin.H_MD)
                : new Rect(0f, 0f, 0f, 0f);

        // One toolbar, not three loose controls: a flat recessed tray holds them,
        // its radius concentric with the controls inside.
        float trayLeft = (searchable ? searchBox.x : hudEditor.x) - Skin.S1 - 1f;
        Rect toolbar = Skin.pixelAligned(ui, Rect.of(trayLeft, hudEditor.y - Skin.S1 - 1f,
                close.right() + Skin.S1 + 1f, hudEditor.bottom() + Skin.S1 + 1f));
        float trayRadius = Skin.R_MEDIUM + Skin.S1 + 1f;
        ui.rounded(toolbar.x, toolbar.y, toolbar.right(), toolbar.bottom(), trayRadius, Skin.RECESSED);
        ui.outline(toolbar.x, toolbar.y, toolbar.right(), toolbar.bottom(), trayRadius,
                Skin.BORDER_STANDARD, Skin.EDGE_RECESSED);

        if (controls.iconButton(ui, "hub.close", close, Icon.CLOSE, false, true)) {
            beginClose();
        }
        if (controls.secondary(ui, "hub.hud-editor", hudEditor, "HUD Editor", true)) {
            context.editHud();
        }
        if (searchable) {
            search(ui);
        }

        float room = (searchable ? searchBox.x : hudEditor.x) - cursor - Skin.S5;
        if (room > 60f) {
            sessionLine(ui, header, cursor, room);
        }
    }

    /** The last profile operation that failed, shown in place of the location until the next one works. */
    private String profileProblem = "";

    /**
     * The live Fathom Profile, as a picker, and where the player is.
     *
     * <p>The name here used to be the launcher's installation, or "Development
     * Profile" under Gradle. It is the loadout now, and choosing another one
     * switches it live — modules, settings, HUD and keys, without a restart.
     */
    private void sessionLine(Renderer ui, Rect header, float x, float room) {
        net.fathom.optimizations.fabric.profile.ProfileRuntime profiles = hud.profiles();
        float pickerWidth = 0f;
        if (profiles != null) {
            java.util.List<net.fathom.optimizations.core.profile.FathomProfile> all = profiles.cachedProfiles();
            String[] options = new String[all.size() + 1];
            int selected = 0;
            for (int index = 0; index < all.size(); index++) {
                options[index] = all.get(index).name();
                if (all.get(index).id().equals(profiles.liveId())) {
                    selected = index;
                }
            }
            options[all.size()] = "+ New profile";
            pickerWidth = Math.min(room * 0.5f, Math.max(150f, ui.measure(profiles.liveName(), Type.BODY) + 48f));
            Rect picker = new Rect(x, header.centreY() - Skin.H_MD / 2f, pickerWidth, Skin.H_MD);
            int chosen = controls.dropdown(ui, "hub.profile", picker, options, selected);
            if (chosen != selected && chosen >= 0) {
                try {
                    if (chosen == all.size()) {
                        profiles.create("New profile");
                    } else {
                        profiles.switchTo(all.get(chosen).id());
                    }
                    profileProblem = "";
                } catch (net.fathom.optimizations.core.profile.ProfileException failure) {
                    profileProblem = failure.getMessage();
                }
            }
        }

        float textX = x + pickerWidth + (pickerWidth > 0f ? Skin.S3 : 0f);
        String notice = profiles == null ? "" : profiles.notice();
        String shown;
        int colour;
        if (!profileProblem.isEmpty()) {
            shown = profileProblem;
            colour = Skin.WARN;
        } else if (!notice.isEmpty()) {
            shown = notice;
            colour = Skin.GOOD;
        } else {
            shown = Session.describe(client);
            colour = Skin.TEXT_FAINT;
        }
        if (shown != null && !shown.isEmpty() && textX + 40f < x + room) {
            ui.text(Text.fit(ui, shown, Type.BODY, x + room - textX), textX,
                    Controls.baseline(ui, header, Type.BODY), Type.BODY, colour);
        }

        String restart = context.restartNote();
        if (restart != null) {
            Rect badge = new Rect(x, header.bottom() + Skin.S2, room, 20f);
            controls.badge(ui, "hub.restart", restart + " — restart required", badge, Skin.WARN);
        }
    }

    private void search(Renderer ui) {
        Controls.State s = controls.state(SEARCH_ID, searchBox, true);
        boolean focused = controls.focused(SEARCH_ID);
        float focus = controls.towards(SEARCH_ID + "#focus", focused, Timing.STANDARD);
        float response = Math.max(s.hover, focus);
        // Focus is a red border; no glow.
        ui.rounded(searchBox.x, searchBox.y, searchBox.right(), searchBox.bottom(), Skin.R_MEDIUM,
                Skin.mix(Skin.RECESSED, Skin.SURFACE_HOVER, Math.max(s.hover * 0.6f, focus)));
        ui.outline(searchBox.x, searchBox.y, searchBox.right(), searchBox.bottom(), Skin.R_MEDIUM,
                Skin.BORDER_STANDARD + focus * (Skin.BORDER_ENERGIZED - Skin.BORDER_STANDARD),
                Skin.mix(Skin.mix(Skin.EDGE_SURFACE, Skin.EDGE_ELEVATED, s.hover), Skin.ACTIVE_RED, focus));

        float icon = 13f;
        ui.icon(Icon.SEARCH, searchBox.x + Skin.S3, searchBox.centreY() - icon / 2f, icon,
                Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED, response));

        String value = context.search();
        float textX = searchBox.x + Skin.S3 + icon + Skin.S2;
        float baseline = Controls.baseline(ui, searchBox, Type.BODY);
        float room = searchBox.right() - textX - Skin.S3 - (value.isEmpty() ? 0f : 22f);
        if (value.isEmpty()) {
            ui.text("Search", textX, baseline, Type.BODY,
                    Skin.mix(Skin.TEXT_DISABLED, Skin.TEXT_FAINT, response));
        } else {
            ui.text(Text.fit(ui, value, Type.BODY, room), textX, baseline, Type.BODY, Skin.TEXT);
            Rect clear = searchBox.vcentreRight(Skin.S1, 22f, 22f);
            if (controls.iconButton(ui, "hub.search.clear", clear, Icon.CLOSE, false, true)) {
                context.setSearch("");
            }
        }
        if (focused) {
            // A caret on the animation clock, not on a tick counter.
            float phase = (Gfx.clock().seconds() % 1.06f) / 1.06f;
            if (phase < 0.55f) {
                float caretX = textX + Math.min(room, ui.measure(value, Type.BODY)) + 1.5f;
                ui.fill(caretX, searchBox.y + 7f, caretX + 1.5f, searchBox.bottom() - 7f, Skin.TEXT_MUTED);
            }
        }
    }

    /**
     * How wide the rail is this frame, and where the pointer stands on it.
     *
     * <p>The hit area is whatever is <em>drawn</em>, not whatever the tray is
     * heading towards: a tray caught mid-close still occupies the pixels it is
     * still painting, and a cursor sitting on a label that is visibly there
     * should keep it open rather than watch it retreat out from under itself.
     */
    private Rect railTray(Rect rail) {
        float now = Gfx.clock().seconds();
        float drawnWidth = Skin.RAIL_WIDTH
                + (Skin.RAIL_TRAY_WIDTH - Skin.RAIL_WIDTH) * railTray.value(now);
        float hitWidth = Math.max(Skin.RAIL_WIDTH, drawnWidth);
        boolean overRail = new Rect(rail.x, rail.y, hitWidth, rail.height)
                .contains(pointer.x(), pointer.y());
        if (railPinned && pointer.pressed(0) && !overRail) {
            railPinned = false;
        }
        railTray.to(railPinned || overRail ? 1f : 0f, now);
        // Sampled again after retargeting, so a tray that just began opening is
        // already opening in the rectangle this frame uses.
        float openWidth = Skin.RAIL_WIDTH
                + (Skin.RAIL_TRAY_WIDTH - Skin.RAIL_WIDTH) * railTray.value(now);
        return new Rect(rail.x, rail.y, openWidth, rail.height);
    }

    private void rail(Renderer ui, Rect rail, Rect tray) {
        float now = Gfx.clock().seconds();
        float openWidth = tray.width;
        int trayFill = Skin.RECESSED;
        // Only once it is over something: a shadow under the resting icon rail
        // would be a shadow cast onto the panel edge it is flush with.
        float lift = Math.max(0f, (openWidth - Skin.RAIL_WIDTH)
                / Math.max(1f, Skin.RAIL_TRAY_WIDTH - Skin.RAIL_WIDTH));
        if (lift > 0.01f) {
            ui.shadow(tray.x, tray.y, tray.right(), tray.bottom(), Skin.R_PANEL, 18f,
                    Skin.scaleAlpha(Skin.SHADOW, lift * VisualEffects.SHADOW_STRENGTH));
        }
        float trailingRadius = Skin.R_PANEL * lift;
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).rounded(tray.x, tray.y, tray.right(), tray.bottom(),
                    0f, 0f, trailingRadius, Skin.R_PANEL, trayFill);
        } else {
            ui.fill(tray.x, tray.y, tray.right(), tray.bottom() - Skin.R_PANEL, trayFill);
            ui.rounded(tray.x, tray.bottom() - Skin.R_PANEL * 2f, tray.right(), tray.bottom(),
                    Skin.R_PANEL, trayFill);
        }
        // A centred/full-height edge used to continue through the tray's curved
        // lower-right corner. Stop it where that curve begins.
        ui.fill(tray.right() - Skin.HAIRLINE, tray.y, tray.right(),
                tray.bottom() - trailingRadius, Skin.CHROME_LINE_STRONG);

        float rowHeight = Math.min(Skin.RAIL_ROW, (rail.height - Skin.S4) / sections.size());
        // The selection marker slides between sections instead of being replaced.
        float markerX = Skin.snap(ui, rail.x + 5f);
        float markY = rail.y + Skin.S3 + railMark.update(current, context.delta()) * rowHeight;
        Rect marker = Rect.of(markerX, markY + rowHeight * 0.28f,
                markerX + 3f, markY + rowHeight * 0.72f);
        VisualEffects.navigationGlow(ui, marker, 1f);
        ui.rounded(marker.x, marker.y, marker.right(), marker.bottom(), Skin.pill(marker), Skin.HOT_RED);

        for (int index = 0; index < sections.size(); index++) {
            Section section = sections.get(index);
            Rect row = new Rect(tray.x + Skin.S3, tray.y + Skin.S3 + index * rowHeight,
                    tray.width - Skin.S4, rowHeight - 2f);
            Controls.State s = controls.state("rail." + index, row, true);
            boolean active = index == current;

            float on = controls.towards("rail." + index + "#on", active, Timing.NAVIGATION);
            if (s.hover > 0.01f || on > 0.01f) {
                // Selected: a stronger dark face, a red edge and a small light
                // behind the icon. Hover: a surface step only.
                ui.rounded(row.x, row.y, row.right(), row.bottom(), Skin.R_CONTROL,
                        Skin.mix(Skin.scaleAlpha(Skin.SURFACE_HOVER, s.hover), Skin.ELEVATED, on));
                if (on > 0.01f) {
                    ui.outline(row.x, row.y, row.right(), row.bottom(), Skin.R_CONTROL,
                            Skin.BORDER_STANDARD, Skin.scaleAlpha(Skin.BASE_RED, on));
                    Rect light = new Rect(row.x + Skin.S3, row.centreY() - 8.5f, 17f, 17f);
                    VisualEffects.stateGlow(ui, light, Skin.R_SMALL, on * 0.55f);
                }
            }

            float iconSize = 17f;
            int tint = Skin.mix(Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED, s.hover), Skin.TEXT, on);
            ui.icon(section.icon(), row.x + Skin.S3, row.centreY() - iconSize / 2f, iconSize,
                    Skin.mix(VisualEffects.hoverBrighten(tint, s.hover), Skin.HOT_RED, on * 0.86f));
            // Faded in on the room there actually is for a word, not on how far
            // the tray has travelled. Keyed to travel, the first tenth of the
            // animation left about nine units beside each icon, which is enough
            // for an ellipsis and nothing else — so a tray caught opening drew a
            // column of "…" down the rail. A label that cannot show a letter
            // should not be on screen.
            float labelRoom = row.width - Skin.S3 - iconSize - Skin.S5;
            float labelAlpha = Ease.clamp01((labelRoom - LABEL_MIN_ROOM) / LABEL_FADE_ROOM);
            if (labelAlpha > 0.01f) {
                ui.clip(row.x + Skin.S3 + iconSize + Skin.S2, row.y, row.right(), row.bottom());
                ui.pushAlpha(labelAlpha);
                ui.text(Text.fit(ui, section.title(), Type.ROW_TITLE, labelRoom),
                        row.x + Skin.S3 + iconSize + Skin.S3,
                        Controls.baseline(ui, row, Type.ROW_TITLE), Type.ROW_TITLE, tint);
                ui.popAlpha();
                ui.unclip();
            }

            if (s.clicked && index != current) {
                previous = current;
                current = index;
                pageSwap.set(0f, now);
                pageSwap.to(1f, now);
                controls.clearFocus();
                context.keybindCapture().cancel();
                sections.get(current).opened(context);
            }
        }

        if (openWidth > Skin.RAIL_WIDTH + 36f) {
            Rect pin = new Rect(tray.right() - Skin.S3 - Controls.ICON_BUTTON,
                    tray.bottom() - Skin.S3 - Controls.ICON_BUTTON,
                    Controls.ICON_BUTTON, Controls.ICON_BUTTON);
            if (controls.iconButton(ui, "rail.pin", pin, Icon.PIN, railPinned, true)) {
                railPinned = !railPinned;
            }
        }
    }

    private void content(Renderer ui, Rect content) {
        Section section = sections.get(current);
        Rect inner = content.inset(Skin.S6, Skin.S5);

        // The page slides in from the direction the rail moved, which makes a
        // section change read as movement between pages rather than a redraw.
        float swap = pageSwap.value(Gfx.clock().seconds());
        float direction = current >= previous ? 1f : -1f;
        float slide = (1f - swap) * 22f * direction;

        ui.clip(content.x, content.y, content.right(), content.bottom());
        ui.pushAlpha(swap);
        ui.pushTransform(slide, 0f, 0.994f + swap * 0.006f,
                content.centreX(), content.centreY());

        Rect heading = inner.top(section.subtitle() == null ? 30f : 50f);
        ui.text(section.title(), heading.x, heading.y + ui.ascent(Type.SECTION), Type.SECTION, Skin.TEXT);
        if (section.subtitle() != null) {
            ui.text(
                    Text.fit(ui, section.subtitle(), Type.SMALL, heading.width),
                    heading.x,
                    heading.y + 28f + ui.ascent(Type.SMALL),
                    Type.SMALL,
                    Skin.TEXT_FAINT);
        }

        Rect body = inner.belowTop(heading.height + Skin.S3);
        section.render(context, body);

        ui.popTransform();
        ui.popAlpha();
        ui.unclip();

        // The wheel belongs to whatever is under the pointer.
        int notches = pointer.wheelNotches();
        if (notches != 0 && content.contains(pointer.x(), pointer.y())) {
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
