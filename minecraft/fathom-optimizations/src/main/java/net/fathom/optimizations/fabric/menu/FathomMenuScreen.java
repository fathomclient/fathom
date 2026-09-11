package net.fathom.optimizations.fabric.menu;

import org.lwjgl.input.Keyboard;

import net.fabricmc.loader.api.FabricLoader;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SettingsScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.ResourcePackScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.options.LanguageOptionsScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.util.ScreenshotUtils;

import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.FathomOptimizationsClient;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.GlRenderer;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.BrandMark;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.hud.HudEditorScreen;

/**
 * Fathom's main menu.
 *
 * <h2>What it is</h2>
 *
 * <p>The first screen of the game, in the launcher's own design language: a
 * preprocessed looping Minecraft video underneath it, floating glass clusters
 * instead of full-width bars, the Fathom mark-and-wordmark lockup, two primary
 * destinations and a compact secondary-action dock.
 *
 * <pre>
 *             [Aperture]
 *               FATHOM
 *         [ Singleplayer ]
 *         [ Multiplayer  ]
 *
 *       [F] [O] [L] [P] [Q]
 * </pre>
 *
 * <h2>Why it is not vanilla's screen with new colours</h2>
 *
 * <p>Vanilla's title screen is a rotating skybox, a texture-atlas
 * wordmark, and {@code ButtonWidget}s that draw themselves from a nine-slice of
 * `widgets.png`. Every one of those is a fixed-resolution asset magnified by the
 * GUI scale, which is the exact defect the renderer milestone existed to remove.
 * Restyling them was never available: the grey is baked into a PNG.
 *
 * <p>So this draws a preprocessed 1920x1080 frame sequence behind a
 * framebuffer-native interface, and hands off to vanilla's own screens the
 * moment a button is pressed. Decode happens one frame ahead on a worker;
 * background upload and crisp UI rendering remain separate passes.
 * Singleplayer opens {@code SelectWorldScreen}; the world list is Minecraft's
 * and there is no reason for it not to be.
 *
 * <h2>Discoverability</h2>
 *
 * <p>The Fathom button exists because Right Shift is a thing you have to be told
 * once and can then forget. A client whose only entrance is a keystroke is a
 * client half its users think is not installed.
 *
 * <h2>What it does not offer</h2>
 *
 * <p>Accounts. The game process holds a session it was handed at launch and has
 * no way to sign in or out; a button here could only open the launcher, which is
 * what the Fathom button already does. An "Accounts" button that turned out to
 * mean "open the launcher" would be the decorative-control problem again, in the
 * one place where the subject is somebody's Microsoft login.
 */
public final class FathomMenuScreen extends Screen implements FathomScreen {

    private static final float BUTTON_WIDTH = 320f;
    private static final float MENU_BUTTON_HEIGHT = 40f;
    private static final float BUTTON_GAP = 10f;
    private static final float DOCK_BUTTON = 28f;
    private static final float DOCK_GAP = 6f;
    private static final float TOPBAR_HEIGHT = Skin.TOPBAR_HEIGHT;
    private static final float FOOTER_HEIGHT = Skin.BOTTOMBAR_HEIGHT;

    /**
     * The centre lockup.
     *
     * <p>The mark is drawn at 64, its native grid, so each bar is exactly ten
     * units and the ink is 48 across. The wordmark is brand artwork rather than a
     * step on the type scale: Geist 600 at 20, treatment A's tracking, with its
     * cap line 14 units under the mark's ink.
     */
    private static final float MARK_SIZE = 64f;
    private static final float MARK_INK = MARK_SIZE * BrandMark.INK_FRACTION;
    private static final Type WORDMARK = new Type(20f, Type.Weight.SEMIBOLD);
    private static final float WORDMARK_GAP = 14f;
    private static final float LOCKUP_TO_ACTIONS = 40f;

    /** The launcher top bar's brand treatment: 24 mark, body semibold, 0.16em. */
    private static final float CHROME_MARK = 24f;
    private static final Type CHROME_WORDMARK = Type.BODY.weighing(Type.Weight.SEMIBOLD);
    private static final float CHROME_TRACKING = 0.16f;
    private static final Type CRUMB = Type.SMALL.weighing(Type.Weight.MEDIUM);


    /**
     * How long the screen takes to arrive, and how far behind it the dock is.
     *
     * <p>160ms, not {@code Skin.SLOW}'s 220. The hub's durations are for panels
     * that slide over a screen you were already reading, where 220ms is the
     * difference between a panel appearing and a panel arriving. This is the
     * first thing the game shows: it has nothing to arrive over, and every
     * millisecond of it is a millisecond between double-clicking the launcher
     * and being able to press Singleplayer.
     *
     * <p>The dock trails by 40ms, which is not decoration. It says the two rows
     * are different things — one is where you are going, the other is what you
     * can change — in a way that no amount of spacing says on its own.
     */
    private static final float OPEN_SECONDS = 0.16f;
    private static final float DOCK_DELAY_SECONDS = 0.04f;

    private final Controls controls = new Controls();
    private final Pointer pointer = new Pointer();
    private Motion opening;
    private Motion dockOpening;
    private float openedAt;
    private int captureFrames;
    private boolean captured;
    private boolean captureTargetOpened;
    private boolean closeCaptureFinished;

    /** Where the column sits, recomputed each frame from the window. */
    private Rect column = Rect.of(0f, 0f, 0f, 0f);
    /** The top of the mark's ink. */
    private float lockupTop;
    private String dockTooltip;
    private Rect dockTooltipAnchor;

    @Override
    public void init() {
        // The client initializer can run before 1.8.9 has loaded options.txt;
        // the first actual screen is the reliable repair boundary.
        FathomOptimizationsClient.repairLegacyFovCorruption();
        if (opening == null) {
            Gfx.renderer(client);
            Gfx.restartClock();
            float now = Gfx.clock().seconds();
            opening = new Motion(0f, OPEN_SECONDS);
            opening.to(1f, now);
            dockOpening = new Motion(0f, OPEN_SECONDS);
            openedAt = now;
            // The clock has not run since the last Fathom screen closed, so
            // without this the first frame carries all of that time and every
            // animation on the screen starts finished.
            pointer.reset();
            controls.reset();
        }
    }

    @Override
    public void render(int ignoredMouseX, int ignoredMouseY, float ignoredDelta) {
        // The prepared sequence uploads one 1920x1080 frame at 30 Hz and draws
        // it with cover cropping. The Fathom interface begins afterwards and
        // rasterises independently at framebuffer resolution.
        Gfx.isolated(new Runnable() {
            @Override
            public void run() {
                if (worldArt()) {
                    MenuVideo.draw(client, Runtime.menuBackgroundQuality());
                } else {
                    net.minecraft.client.gui.DrawableHelper.fill(0, 0,
                            (int) new net.minecraft.client.util.Window(client).getScaledWidth(),
                            (int) new net.minecraft.client.util.Window(client).getScaledHeight(),
                            0xFF05060A);
                }
            }
        });

        float delta = Gfx.begin(client, Runtime.uiScale());
        try {
            GlRenderer ui = Gfx.renderer(client);
            pointer.poll(client, ui);
            controls.begin(pointer, delta);
            float now = Gfx.clock().seconds();
            if (now - openedAt >= DOCK_DELAY_SECONDS) {
                dockOpening.to(1f, now);
            }

            layout(ui);
            draw(ui);
            controls.end();
        } finally {
            Gfx.end();
        }
        captureSettledFrame();
        openCaptureTarget();
        finishCloseCapture();
    }

    /** Prove the first destination frame after Hub close is a clean restored frame. */
    private void finishCloseCapture() {
        if (closeCaptureFinished || !Boolean.getBoolean("fathom.closeComplete")) {
            return;
        }
        closeCaptureFinished = true;
        String name = "fathom-hub-close-restored-" + client.width + "x" + client.height + ".png";
        ScreenshotUtils.saveScreenshot(client.runDirectory, name,
                client.width, client.height, client.getFramebuffer());
        System.out.println("[Fathom] Hub close QA: destination frame captured after restore.");
        client.scheduleStop();
    }

    /** Move deterministic visual-QA runs to the requested Fathom surface. */
    private void openCaptureTarget() {
        String target = System.getProperty("fathom.captureUi", "");
        if (captureTargetOpened || target.isEmpty() || "menu".equalsIgnoreCase(target)
                || captureFrames < 12) {
            return;
        }
        captureTargetOpened = true;
        if ("hud".equalsIgnoreCase(target)
                && !Boolean.getBoolean("fathom.captureWorldStarted")) {
            // The in-game HUD is drawn by the game's own pass, so the only way
            // to photograph it is to be in a world. The development save is
            // already in the run directory for exactly this kind of check.
            System.setProperty("fathom.captureWorldStarted", "true");
            client.startIntegratedServer("New World", "New World", null);
        } else if ("hud-editor".equalsIgnoreCase(target)) {
            FathomNavigation.push(client, new HudEditorScreen(Runtime.hud()));
        } else {
            FathomNavigation.openHub(client, Runtime.hud());
        }
    }

    /**
     * Development-only framebuffer capture used by the menu redline gate.
     *
     * <p>Thirty frames in, so the opening motion has finished and the poster
     * has been uploaded; then the client stops. Stopping is the point: the gate
     * is meant to be run, looked at and forgotten, and a gate that leaves a
     * Minecraft window open is a gate somebody has to go and close before they
     * can run it again.
     */
    private void captureSettledFrame() {
        captureFrames++;
        if (!Boolean.getBoolean("fathom.captureMenu") || captured
                || captureFrames < Integer.getInteger("fathom.captureMenuFrame", 30)
                || MenuVideo.uploadedFrames()
                        < Integer.getInteger("fathom.captureMenuVideoFrames", 24)) {
            return;
        }
        captured = true;
        String videoDiagnostics = MenuVideo.diagnostics();
        String frameDiagnostics = String.format(java.util.Locale.ROOT,
                "%.0f UI fps, %.2f ms Fathom UI",
                Float.valueOf(Gfx.frames().fps()), Float.valueOf(Gfx.frames().uiMeanMillis()));
        String name = "fathom-main-menu-" + client.width + "x" + client.height
                + (captureFrames < 30 ? "-frame" + captureFrames : "") + ".png";
        ScreenshotUtils.saveScreenshot(client.runDirectory, name,
                client.width, client.height, client.getFramebuffer());
        System.out.println("[Fathom] Menu video QA: " + videoDiagnostics
                + "; " + frameDiagnostics + ".");
        client.scheduleStop();
    }

    /**
     * Where the lockup and the column go.
     *
     * <p>They are placed as one group. Anchoring each to its own percentage of
     * the window left a gap between them that grew with the window, and a large
     * enough gap turns a lockup into a logo floating near some buttons. The group
     * sits a little above the geometric centre, which is where the eye puts it.
     */
    private void layout(GlRenderer ui) {
        float width = Math.min(BUTTON_WIDTH, ui.width() - Skin.S8 * 2f);
        float actions = MENU_BUTTON_HEIGHT * 2f + BUTTON_GAP;
        float group = lockupHeight() + LOCKUP_TO_ACTIONS + actions;
        float dockTop = ui.height() - FOOTER_HEIGHT - DOCK_BUTTON - Skin.S3;
        float top = (ui.height() - group) / 2f - ui.height() * 0.03f;
        top = Math.max(TOPBAR_HEIGHT + Skin.S4, Math.min(top, dockTop - Skin.S6 - group));
        // Whole device pixels, so the mark's ten-unit bars stay one width.
        lockupTop = Math.round(top * ui.scale()) / ui.scale();
        column = new Rect((ui.width() - width) / 2f,
                lockupTop + lockupHeight() + LOCKUP_TO_ACTIONS, width, actions);
    }

    private static float lockupHeight() {
        return MARK_INK + WORDMARK_GAP + WORDMARK.size() * BrandMark.CAP_HEIGHT;
    }

    private void draw(GlRenderer ui) {
        dockTooltip = null;
        dockTooltipAnchor = null;
        chrome(ui);

        // A short rise, not a slide: the screen behind is the game's own
        // backdrop, and anything travelling far across it reads as a transition
        // to somewhere rather than as this arriving.
        float shown = opening.value(Gfx.clock().seconds());
        ui.pushAlpha(shown);
        ui.pushTransform(0f, (1f - shown) * 10f);
        branding(ui);
        primaryActions(ui);
        ui.popTransform();
        ui.popAlpha();

        float dockShown = dockOpening.value(Gfx.clock().seconds());
        ui.pushAlpha(dockShown);
        ui.pushTransform(0f, (1f - dockShown) * 10f);
        dock(ui);
        ui.popTransform();
        ui.popAlpha();

        footer(ui);

        controls.drawOverlays(ui);
    }

    // -----------------------------------------------------------------------
    // Branding
    // -----------------------------------------------------------------------

    /** Floating route and account clusters; the video remains edge-to-edge. */
    private void chrome(GlRenderer ui) {
        // The launcher's top-bar brand, in a floating cluster: 24 mark, S2,
        // wordmark, S3, hairline, S3, route.
        float wordWidth = BrandMark.wordmarkWidth(ui, CHROME_WORDMARK, CHROME_TRACKING);
        float crumbWidth = ui.measure("Home", CRUMB);
        float leftWidth = Skin.S2 + CHROME_MARK + Skin.S2 + wordWidth
                + Skin.S3 * 2f + Skin.HAIRLINE + crumbWidth + Skin.S4;
        Rect left = new Rect(Skin.S3, Skin.S3, leftWidth, TOPBAR_HEIGHT - Skin.S2);
        glassCluster(ui, left);
        float markX = left.x + Skin.S2;
        float markY = Math.round(left.centreY() - CHROME_MARK / 2f);
        BrandMark.drawSmall(ui, markX, markY, CHROME_MARK, Skin.TEXT, Skin.ACCENT);

        float wordX = markX + CHROME_MARK + Skin.S2;
        BrandMark.wordmark(ui, wordX, Controls.baseline(ui, left, CHROME_WORDMARK),
                CHROME_WORDMARK, CHROME_TRACKING, Skin.TEXT);
        float dividerX = wordX + wordWidth + Skin.S3;
        ui.fill(dividerX, left.y + Skin.S3, dividerX + Skin.HAIRLINE,
                left.bottom() - Skin.S3, Skin.CHROME_LINE_STRONG);
        ui.text("Home", dividerX + Skin.S3, Controls.baseline(ui, left, CRUMB),
                CRUMB, Skin.TEXT_FAINT);

        String username = client.getSession() == null ? "" : client.getSession().getUsername();
        String account = username.isEmpty() || username.startsWith("Player") ? "Sign in" : username;
        float accountWidth = Math.max(70f,
                ui.measure(account, Type.CAPTION) + 13f + Skin.S2 * 3f);
        float versionWidth = ui.measure("1.8.9", Type.CAPTION) + Skin.S3 * 2f;
        Rect right = new Rect(ui.width() - Skin.S3 - accountWidth - versionWidth,
                Skin.S3, accountWidth + versionWidth, TOPBAR_HEIGHT - Skin.S2);
        glassCluster(ui, right);
        Rect accountArea = new Rect(right.right() - accountWidth, right.y,
                accountWidth, right.height);
        if (chromeAction(ui, accountArea, account)) {
            if (Runtime.bridge() != null) {
                Runtime.bridge().open();
            }
        }
        ui.textRight("1.8.9", accountArea.x - Skin.S2,
                Controls.baseline(ui, right, Type.CAPTION),
                Type.CAPTION, Skin.TEXT_FAINT);
    }

    /**
     * The launcher's glass without its blur: the resting fill, the 10% edge and
     * the one-unit sheen inside the top. Resting fill rather than the strong one,
     * so the footage reads through the chrome instead of stopping at it.
     */
    private void glassCluster(GlRenderer ui, Rect area) {
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_SM, Skin.GLASS);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_SM,
                Skin.HAIRLINE, Skin.GLASS_EDGE);
        ui.fill(area.x + Skin.R_SM, area.y + Skin.HAIRLINE, area.right() - Skin.R_SM,
                area.y + Skin.HAIRLINE * 2f, Skin.SHEEN);
    }

    private boolean chromeAction(GlRenderer ui, Rect area, String label) {
        Controls.State state = controls.state("menu.account", area, true);
        if (state.hover > 0.01f) {
            ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_XS,
                    Skin.scaleAlpha(Skin.FILL_HOVER, state.hover));
        }
        float iconSize = 13f;
        ui.icon(Icon.USER, area.x + Skin.S2, area.centreY() - iconSize / 2f,
                iconSize, Skin.mix(Skin.TEXT_FAINT, Skin.TEXT, state.hover));
        ui.text(label, area.x + Skin.S2 * 2f + iconSize,
                Controls.baseline(ui, area, Type.CAPTION), Type.CAPTION,
                Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, state.hover));
        return state.clicked;
    }

    /**
     * Mark over wordmark, both centred on the column.
     *
     * <p>The mark is positioned by its ink, not its 64-unit box, and the wordmark
     * by its cap line, not its ascent: the box carries 8 units of safe area and
     * the ascent carries accent room, and measuring the gap from either makes the
     * lockup look loose by exactly that much.
     */
    private void branding(GlRenderer ui) {
        float centre = Math.round(column.centreX());
        float inset = (MARK_SIZE - MARK_INK) / 2f;
        BrandMark.draw(ui, centre - MARK_SIZE / 2f, lockupTop - inset, MARK_SIZE,
                Skin.TEXT, Skin.ACCENT);

        float baseline = lockupTop + MARK_INK + WORDMARK_GAP
                + WORDMARK.size() * BrandMark.CAP_HEIGHT;
        float width = BrandMark.wordmarkWidth(ui, WORDMARK, BrandMark.WORDMARK_TRACKING);
        BrandMark.wordmark(ui, centre - width / 2f, baseline, WORDMARK,
                BrandMark.WORDMARK_TRACKING, Skin.TEXT);
    }

    // -----------------------------------------------------------------------
    // The buttons
    // -----------------------------------------------------------------------

    private void primaryActions(GlRenderer ui) {
        float y = column.y;
        if (menuButton(ui, "menu.single", new Rect(column.x, y, column.width, MENU_BUTTON_HEIGHT),
                "Singleplayer")) {
            client.setScreen(new SelectWorldScreen(this));
        }
        y += MENU_BUTTON_HEIGHT + BUTTON_GAP;

        if (menuButton(ui, "menu.multi", new Rect(column.x, y, column.width, MENU_BUTTON_HEIGHT),
                "Multiplayer")) {
            client.setScreen(new MultiplayerScreen(this));
        }
    }

    /**
     * The secondary dock, at the bottom of the window rather than under the
     * buttons: these are things you change, not places you go, and putting them
     * in the same column made the column read as five destinations of varying
     * importance.
     */
    private void dock(GlRenderer ui) {
        float dockWidth = DOCK_BUTTON * 5f + DOCK_GAP * 4f;
        float y = ui.height() - FOOTER_HEIGHT - DOCK_BUTTON - Skin.S3;
        float x = column.centreX() - dockWidth / 2f;
        if (dockAction(ui, "menu.fathom", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.GRID, "Fathom")) {
            openHub();
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.options", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.SLIDERS, "Options")) {
            client.setScreen(new SettingsScreen(this, client.options));
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.language", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.COMPASS, "Language")) {
            client.setScreen(new LanguageOptionsScreen(this, client.options, client.getLanguageManager()));
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.packs", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.PACKAGE,
                "Resource Packs")) {
            client.setScreen(new ResourcePackScreen(this));
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.quit", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.CLOSE, "Quit")) {
            client.scheduleStop();
        }
        drawDockTooltip(ui);
    }

    private boolean menuButton(GlRenderer ui, String id, Rect area, String label) {
        Controls.State state = controls.state(id, area, true);
        int face = Skin.mix(Skin.mix(Skin.GLASS, Skin.GLASS_HOVER, state.hover), Skin.GLASS_PRESSED, state.press);
        int edge = Skin.mix(Skin.GLASS_EDGE_STRONG, Skin.fade(Skin.ACCENT, 0.76f), state.hover);
        float sink = state.press * 0.5f;
        ui.rounded(area.x, area.y + sink, area.right(), area.bottom() + sink, Skin.R_XS, face);
        if (state.hover > 0.01f) {
            // A wash, not a fill: the hovered button warms toward the accent
            // and stays glass. Solid red is reserved for things that are on.
            ui.rounded(area.x, area.y + sink, area.right(), area.bottom() + sink, Skin.R_XS,
                    Skin.scaleAlpha(Skin.ACCENT_WASH, state.hover * 0.7f));
        }
        ui.outline(area.x, area.y + sink, area.right(), area.bottom() + sink, Skin.R_XS,
                Skin.HAIRLINE, edge);
        if (state.hover > 0.01f) {
            ui.rounded(area.x + Skin.S2, area.y + sink, area.right() - Skin.S2,
                    area.y + sink + 1.5f, 0.75f, Skin.scaleAlpha(Skin.ACCENT, state.hover));
        }
        // The row title, not the caption size. These are the two destinations
        // the screen exists for, on a 38-unit face; a 12px label on it reads as
        // a disabled control rather than as the primary action.
        ui.textCentred(label, area.centreX(), Controls.baseline(ui, area, Type.ROW_TITLE) + sink,
                Type.ROW_TITLE, Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, state.hover));
        return state.clicked;
    }

    /** Compact secondary action with an immediate hover label. */
    private boolean dockAction(GlRenderer ui, String id, Rect area, Icon icon, String label) {
        Controls.State state = controls.state(id, area, true);
        int face = Skin.mix(Skin.mix(Skin.GLASS, Skin.GLASS_HOVER, state.hover), Skin.GLASS_PRESSED, state.press);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, face);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, Skin.HAIRLINE,
                Skin.mix(Skin.GLASS_EDGE, Skin.fade(Skin.ACCENT, 0.72f), state.hover));
        float iconSize = 13f;
        // Muted rather than faint: faint is a hub value, tuned against a panel
        // two shades from black, and it disappears into a snowfield.
        ui.icon(icon, area.centreX() - iconSize / 2f, area.centreY() - iconSize / 2f, iconSize,
                Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, state.hover));

        if (state.hover > 0.02f) {
            dockTooltip = label;
            dockTooltipAnchor = area;
        }
        return state.clicked;
    }

    private void drawDockTooltip(GlRenderer ui) {
        if (dockTooltip == null || dockTooltipAnchor == null) {
            return;
        }
        float tipWidth = ui.measure(dockTooltip, Type.CAPTION) + Skin.S2 * 2f;
        Rect tip = new Rect(dockTooltipAnchor.centreX() - tipWidth / 2f,
                dockTooltipAnchor.y - 25f, tipWidth, 19f);
        ui.rounded(tip.x, tip.y, tip.right(), tip.bottom(), Skin.R_XS, Skin.OVERLAY);
        ui.outline(tip.x, tip.y, tip.right(), tip.bottom(), Skin.R_XS, Skin.HAIRLINE, Skin.EDGE);
        ui.textCentred(dockTooltip, tip.centreX(), Controls.baseline(ui, tip, Type.CAPTION),
                Type.CAPTION, Skin.TEXT_MUTED);
    }

    /**
     * One small status cluster, bottom-left.
     *
     * <p>"Development" appears only in a development environment; a released
     * build says "Connected" when the launcher is on the other end of the bridge
     * and nothing when it is not. The game version is already in the top-right
     * cluster, so it is not repeated down here.
     */
    private void footer(GlRenderer ui) {
        float top = ui.height() - FOOTER_HEIGHT;
        String component = Runtime.hud() == null
                ? ""
                : Runtime.hud().launcherSession().component();
        String line = component.isEmpty()
                ? "Fathom"
                : "Fathom " + component;
        String status = FabricLoader.getInstance().isDevelopmentEnvironment()
                ? "Development"
                : Runtime.bridge() != null
                        && Runtime.bridge().state()
                                == net.fathom.optimizations.fabric.bridge.LauncherBridge.State.READY
                        ? "Connected"
                        : "";
        float dot = 5f;
        float lineWidth = ui.measure(line, Type.CAPTION);
        float statusWidth = status.isEmpty() ? 0f : Skin.S2 + ui.measure(status, Type.CAPTION);
        Rect bar = new Rect(Skin.S3, top + Skin.S1,
                Skin.S3 + dot + Skin.S2 + lineWidth + statusWidth + Skin.S3,
                FOOTER_HEIGHT - Skin.S2);
        glassCluster(ui, bar);
        float baseline = Controls.baseline(ui, bar, Type.CAPTION);
        float dotX = bar.x + Skin.S3;
        ui.rounded(dotX, bar.centreY() - dot / 2f, dotX + dot,
                bar.centreY() + dot / 2f, dot / 2f, Skin.ACCENT);
        float textX = dotX + dot + Skin.S2;
        ui.text(line, textX, baseline, Type.CAPTION, Skin.TEXT_MUTED);
        if (!status.isEmpty()) {
            ui.text(status, textX + lineWidth + Skin.S2, baseline, Type.CAPTION,
                    Skin.TEXT_DISABLED);
        }
    }

    /** Whether to draw the animated scene rather than the plain low-cost field. */
    private static boolean worldArt() {
        if (Runtime.features() == null) {
            return true;
        }
        net.fathom.optimizations.core.module.FathomModule module =
                Runtime.features().find("main-menu");
        return module == null || module.toggle("panorama", true);
    }

    private void openHub() {
        // Sealed on the first open, exactly as the hotkey path does: registration
        // is finished, and a module arriving while a list is being drawn is a
        // module whose settings were never loaded from disk.
        if (Runtime.hud() == null) {
            return;
        }
        Runtime.features().seal();
        FathomNavigation.openHub(client, Runtime.hud());
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    @Override
    public boolean handleFathomKey(char character, int keyCode) {
        if (Runtime.hud() != null && Runtime.hud().hubKey().matches(keyCode)) {
            // Right Shift works here too. It is handled in this screen rather
            // than in the key Mixin because that one deliberately only opens the
            // hub from the game itself, where there is no screen to be ambiguous
            // about.
            openHub();
            return true;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // Nothing. There is nowhere above the main menu, and a menu that
            // quit the game on Escape would be a menu that quits the game on the
            // key everybody presses to back out of things.
            return true;
        }
        return false;
    }

    @Override
    protected void keyPressed(char character, int keyCode) {
        // FrameKeyboard drains this screen's events every rendered frame.
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        // Presses are read from the device once per frame, not delivered from a
        // 20 Hz tick; calling super would hand every click to the screen twice,
        // at two different times.
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
    public boolean shouldPauseGame() {
        return false;
    }

    @Override
    public void removed() {
        MenuVideo.stop();
        pointer.reset();
    }

    /**
     * Whether this screen should stand in for vanilla's.
     *
     * <p>Asked by the Mixin, and false for the demo title screen: the demo has
     * its own buttons and its own flow, and replacing it would mean
     * reimplementing a mode nobody running this launcher is in.
     */
    public static boolean shouldReplace(Screen screen) {
        return screen != null
                && screen.getClass() == TitleScreen.class
                && net.minecraft.client.MinecraftClient.getInstance() != null
                && !net.minecraft.client.MinecraftClient.getInstance().isDemo();
    }
}
