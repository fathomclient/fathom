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

import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.anim.Transition;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.FathomOptimizationsClient;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.MinecraftGuiSize;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.BrandMark;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.ui.VisualEffects;
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

    private static final float BUTTON_WIDTH = 360f;
    private static final float MENU_BUTTON_HEIGHT = 44f;
    private static final float BUTTON_GAP = 10f;
    /** Big enough to be a target and to read at a glance, with the icon at 15. */
    private static final float DOCK_BUTTON = 34f;
    private static final float DOCK_GAP = 7f;
    private static final float DOCK_ICON = 16f;
    private static final float TOPBAR_HEIGHT = Skin.TOPBAR_HEIGHT;
    private static final float FOOTER_HEIGHT = Skin.BOTTOMBAR_HEIGHT;
    private static final float ENTRANCE_RISE = 6f;
    private static final float CHROME_ENTRANCE_RISE = 5f;
    private static final float IMMEDIATE_PRESS = 0.38f;

    /** Reused because the background isolation pass runs once per rendered frame. */
    private final Runnable backgroundPass = new Runnable() {
        @Override
        public void run() {
            if (worldArt()) {
                MenuScenes.draw(client, scene(), Runtime.menuBackgroundQuality());
            } else {
                MinecraftGuiSize viewport = MinecraftGuiSize.get(client);
                net.minecraft.client.gui.DrawableHelper.fill(
                        0, 0, viewport.width(), viewport.height(), 0xFF05060A);
            }
        }
    };

    /** Keyboard order covers only controls this screen intentionally owns. */
    private static final String[] FOCUS_ORDER = {
        "menu.single", "menu.multi", "menu.fathom", "menu.options",
        "menu.language", "menu.packs", "menu.scenes", "menu.credits",
        "menu.quit", "menu.account"
    };

    /**
     * The centre lockup.
     *
     * <p>The mark is drawn at 64, its native grid, so each bar is exactly ten
     * units and the ink is 48 across. The wordmark is brand artwork rather than a
     * step on the type scale: Geist 600 at 20, treatment A's tracking, with its
     * cap line 14 units under the mark's ink.
     */
    private static final float MARK_SIZE = 78f;
    private static final float MARK_INK = MARK_SIZE * BrandMark.INK_FRACTION;
    private static final Type WORDMARK = new Type(25f, Type.Weight.SEMIBOLD);
    private static final float WORDMARK_GAP = 16f;
    private static final float LOCKUP_TO_ACTIONS = 40f;

    /** The launcher top bar's brand treatment: 24 mark, body semibold, 0.16em. */
    private static final float CHROME_MARK = 24f;
    private static final Type CHROME_WORDMARK = Type.BODY.weighing(Type.Weight.SEMIBOLD);
    private static final float CHROME_TRACKING = 0.16f;
    private static final Type CRUMB = Type.SMALL.weighing(Type.Weight.MEDIUM);


    /**
     * How long the screen takes to arrive, and how far behind it the dock is.
     *
     * <p>The standard transition is intentionally shorter than a panel motion.
     * that slide over a screen you were already reading, where 220ms is the
     * difference between a panel appearing and a panel arriving. This is the
     * first thing the game shows: it has nothing to arrive over, and every
     * millisecond of it is a millisecond between double-clicking the launcher
     * and being able to press Singleplayer.
     *
     * <p>Everything after the lockup arrives one {@link Timing#STAGGER} after the
     * thing above it, which is how the launcher brings a list of rows in: 22ms
     * apart, in order, down the screen. The dock waits three of those rather
     * than one, and that gap is not decoration. It says the two groups are
     * different things — one is where you are going, the other is what you can
     * change — in a way that no amount of spacing says on its own.
     */
    private static final float DOCK_DELAY_SECONDS = Timing.STAGGER * 3f;

    /**
     * The launcher's glass, at the launcher's own opacities.
     *
     * <p>None of these are new numbers. {@code .launch} in
     * {@code desktop/src/routes/Home.svelte} -- the slab the launcher's home
     * page is built around -- is {@code rgba(12, 14, 19, 0.68)} with the strong
     * edge, the strong sheen and a shadow under it. The small pills along its
     * chrome are {@code --glass-fill}: the same family at 0.58, with the plain
     * edge and the plain sheen. Fathom's tokens already carry both colours, so
     * this is the launcher's material named in the launcher's own terms rather
     * than a hand-matched approximation of it.
     *
     * <p>What cannot come across is the blur, and that is why the opacities
     * here are lower than the launcher's rather than equal to them. The launcher
     * backs its glass with {@code backdrop-filter}: the fill can afford to be
     * heavy because the blur behind it is doing the work of looking like a
     * material. This renderer draws over a video frame already on the
     * framebuffer, and blurring it again would cost a pass over the whole window
     * every frame to soften footage the source is already defocusing. Matching
     * the launcher's 0.68 exactly was tried and read as a flat tint, because
     * without the blur the only thing left saying "glass" is what you can see
     * through it. So: the launcher's colours and its edge and sheen, at an
     * opacity low enough that the footage is visibly behind the button.
     */
    private static final int MENU_GLASS = Skin.fade(Skin.GLASS_HOVER, 0.42f);
    private static final int MENU_GLASS_HOVER = Skin.fade(Skin.GLASS_HOVER, 0.58f);
    private static final int MENU_GLASS_PRESSED = Skin.fade(Skin.GLASS_PRESSED, 0.74f);

    /**
     * The dock's pills, thinner again.
     *
     * <p>Two rows, two weights. The destinations carry a label and have to hold
     * it against whatever the video is doing; the dock carries a 13-unit icon
     * and can afford to be barely there until the pointer arrives.
     */
    private static final int DOCK_GLASS = Skin.fade(Skin.GLASS, 0.34f);
    private static final int DOCK_GLASS_HOVER = Skin.fade(Skin.GLASS, 0.52f);
    private static final int DOCK_GLASS_PRESSED = Skin.fade(Skin.GLASS_PRESSED, 0.68f);

    /**
     * The lit top edge.
     *
     * <p>{@code --glass-sheen} rather than the strong one the launcher's slab
     * uses. The strong value is a 13% white line directly under a 1-pixel
     * border, and measured off the framebuffer the two together came out as a
     * 49 and a 42 on neighbouring rows -- a top edge twice the weight of the
     * outline everywhere else on the same button, which is what made the ring
     * look like it changed thickness as it went round.
     */

    /**
     * How far the shadow under a primary button reaches.
     *
     * <p>The launcher's is {@code 0 26px 70px}, which is a drop shadow on a page
     * that scrolls. Here the buttons sit in the middle of the window with
     * nothing under them, so the same spread would read as a smudge; this is the
     * part of the launcher's material that is matched in spirit rather than in
     * numbers.
     */
    private static final float MENU_SHADOW_SPREAD = 14f;

    /**
     * What this screen owes, and to whom.
     *
     * <p>The menu video was recorded in someone else's world, on terms that ask
     * for credit. {@code ATTRIBUTION.md} is the repository's record of that,
     * which is the right place for it and the wrong place for a player to have
     * to look: the people owed the credit are credited where the footage is
     * actually shown. Label, subject, and who it belongs to.
     */
    private static final String[][] CREDITS = {
        {"Menu background", "Tideworn - 8 sqkm Forgotten Flooded City",
            "A world by milkshake_mp3, on CurseForge"},
        {"Launcher backgrounds", "Tokyo Inspired City",
            "A world by CoinCoffer, on CurseForge"},
        {"Typeface", "Geist", "Vercel and basement.studio, SIL Open Font Licence 1.1"},
    };

    private static final String[] CREDITS_FOOTER = {
        "Recorded in Minecraft by the Fathom project. Full credits in ATTRIBUTION.md.",
        "Not an official Minecraft product. Not approved by or associated with Mojang.",
    };

    private static final float CREDITS_CLOSE_WIDTH = 86f;

    /** How long an account notice stays up before it fades on its own. */
    private static final float NOTICE_SECONDS = 5f;

    /** Five across, three down: the fifteen scenes the launcher offers. */
    private static final int SCENE_COLUMNS = 5;
    private static final float SCENES_PANEL_WIDTH = 660f;

    private final Controls controls = new Controls();
    private final Pointer pointer = new Pointer();
    private Transition transition;
    private Motion dockOpening;
    private Motion creditsFade;
    /** Whether the credits panel is up. While it is, nothing behind it answers. */
    private boolean creditsOpen;
    private Motion scenesFade;
    /** Whether the scene picker is up. Same rule: it owns the pointer. */
    private boolean scenesOpen;
    private float openedAt;
    private int captureFrames;
    private boolean captured;
    private boolean captureTargetOpened;
    private boolean closeCaptureFinished;

    /** Where the column sits, recomputed each frame from the window. */
    private Rect column = Rect.of(0f, 0f, 0f, 0f);
    /** The top of the mark's ink. */
    private float lockupTop;
    /** What the account button last did, and when it said so. */
    private String notice;
    private float noticeAt;
    private Screen pendingScreen;
    private boolean pendingHub;
    private boolean pendingQuit;
    private boolean closing;
    private boolean attached;
    private String keyboardActivation;

    @Override
    public void init() {
        // The client initializer can run before 1.8.9 has loaded options.txt;
        // the first actual screen is the reliable repair boundary.
        FathomOptimizationsClient.repairLegacyFovCorruption();
        // Minecraft calls init again when the same screen is resized. Preserve
        // its live hover/focus/entrance state in that case; removed() clears the
        // flag, so returning from a submenu still gets a fresh short entrance.
        if (attached) {
            Gfx.restartClock();
            pointer.reset();
            return;
        }
        attached = true;
        Gfx.restartClock();
        float now = Gfx.clock().seconds();
        if (transition == null) {
            transition = new Transition(Timing.SCREEN_ENTER, Timing.SCREEN_EXIT);
            dockOpening = new Motion(0f, Timing.STANDARD);
            creditsFade = new Motion(0f, Timing.PANEL);
            scenesFade = new Motion(0f, Timing.PANEL);
        }
        transition.open(now);
        dockOpening.set(0f, now);
        creditsFade.set(0f, now);
        scenesFade.set(0f, now);
        creditsOpen = false;
        scenesOpen = false;
        openedAt = now;
        pendingScreen = null;
        pendingHub = false;
        pendingQuit = false;
        closing = false;
        keyboardActivation = null;
        // The clock has not run since the last Fathom screen closed, so
        // without this the first frame carries all of that time and every
        // animation on the screen starts finished.
        pointer.reset();
        controls.reset();
    }

    @Override
    public void render(int ignoredMouseX, int ignoredMouseY, float ignoredDelta) {
        // The prepared sequence uploads one 1920x1080 frame at 30 Hz and draws
        // it with cover cropping. The Fathom interface begins afterwards and
        // rasterises independently at framebuffer resolution.
        Gfx.isolated(backgroundPass);

        Renderer ui = Gfx.beginScreen(client, Runtime.uiScale());
        boolean handoffReady = false;
        try {
            pointer.poll(client, ui);
            controls.begin(pointer, Gfx.screenDelta());
            float now = Gfx.clock().seconds();
            if (now - openedAt >= DOCK_DELAY_SECONDS) {
                dockOpening.to(1f, now);
            }

            layout(ui);
            draw(ui);
            controls.end();
            handoffReady = transition.finished(now);
        } finally {
            Gfx.endScreen();
        }
        if (handoffReady) {
            finishNavigation();
            return;
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
        } else if ("account".equalsIgnoreCase(target)) {
            // Presses the account button, so the gate can photograph what it
            // says back. In a development client there is no launcher on the
            // other end, which is the case worth seeing.
            openAccount(true);
        } else if ("nanovg".equalsIgnoreCase(target) || "nanovg-live".equalsIgnoreCase(target)) {
            // The renderer showcase. Development only: it needs the isolated
            // classpath `gradle runClient -PcaptureUi=nanovg[-live]` provides.
            client.setScreen(new net.fathom.optimizations.fabric.gfx.nanovg.RendererShowcaseScreen());
        } else if ("scenes".equalsIgnoreCase(target)) {
            scenesOpen = true;
            scenesFade.to(1f, Gfx.clock().seconds());
        } else if ("credits".equalsIgnoreCase(target)) {
            // The credits are a panel on this screen rather than a screen of
            // their own, so the gate opens them here and lets the menu capture
            // photograph them in place.
            creditsOpen = true;
            creditsFade.to(1f, Gfx.clock().seconds());
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
    private void layout(Renderer ui) {
        float width = Math.min(BUTTON_WIDTH, ui.width() - Skin.S8 * 2f);
        float actions = MENU_BUTTON_HEIGHT * 2f + BUTTON_GAP;
        float group = lockupHeight() + LOCKUP_TO_ACTIONS + actions;
        float dockTop = ui.height() - FOOTER_HEIGHT - DOCK_BUTTON - Skin.S3;
        float top = (ui.height() - group) / 2f - ui.height() * 0.03f;
        top = Math.max(TOPBAR_HEIGHT + Skin.S4, Math.min(top, dockTop - Skin.S6 - group));
        // Whole device pixels, so the mark's ten-unit bars stay one width.
        lockupTop = Skin.snap(ui, top);
        column = Skin.pixelAligned(ui, new Rect((ui.width() - width) / 2f,
                lockupTop + lockupHeight() + LOCKUP_TO_ACTIONS, width, actions));
    }

    private static float lockupHeight() {
        return MARK_INK + WORDMARK_GAP + WORDMARK.size() * BrandMark.CAP_HEIGHT;
    }

    private void draw(Renderer ui) {
        float now = Gfx.clock().seconds();
        float enter = transition.enter(now);
        float exit = transition.exit(now);
        ui.pushAlpha(1f - exit);
        ui.pushTransform(0f, exit * 3f,
                0.992f + enter * 0.008f - exit * 0.003f,
                ui.width() / 2f, ui.height() / 2f);
        ui.pushAlpha(enter);
        ui.pushTransform(0f, (1f - enter) * CHROME_ENTRANCE_RISE);
        chrome(ui);
        ui.popTransform();
        ui.popAlpha();

        // A short rise, not a slide: the screen behind is the game's own
        // backdrop, and anything travelling far across it reads as a transition
        // to somewhere rather than as this arriving.
        float shown = transition.enter(now);
        ui.pushAlpha(shown);
        ui.pushTransform(0f, (1f - shown) * ENTRANCE_RISE);
        branding(ui);
        ui.popTransform();
        ui.popAlpha();
        // Not inside the block above: each destination arrives on its own clock.
        primaryActions(ui);

        float dockShown = dockOpening.value(Gfx.clock().seconds());
        ui.pushAlpha(dockShown);
        ui.pushTransform(0f, (1f - dockShown) * CHROME_ENTRANCE_RISE);
        dock(ui);
        ui.popTransform();
        ui.popAlpha();

        ui.pushAlpha(enter);
        ui.pushTransform(0f, (1f - enter) * CHROME_ENTRANCE_RISE);
        footer(ui);
        ui.popTransform();
        ui.popAlpha();
        notices(ui);
        scenes(ui);
        credits(ui);

        controls.drawOverlays(ui);
        ui.popTransform();
        ui.popAlpha();
    }

    // -----------------------------------------------------------------------
    // Branding
    // -----------------------------------------------------------------------

    /** Floating route and account clusters; the video remains edge-to-edge. */
    private void chrome(Renderer ui) {
        // The launcher's top-bar brand, in a floating cluster: 24 mark, S2,
        // wordmark, S3, hairline, S3, route.
        float wordWidth = BrandMark.wordmarkWidth(ui, CHROME_WORDMARK, CHROME_TRACKING);
        float crumbWidth = ui.measure("Home", CRUMB);
        // S3 at both ends. It used to lead with S2 and trail with S4, so the
        // cluster had twice as much air after "Home" as it had before the mark.
        float leftWidth = Skin.S3 + CHROME_MARK + Skin.S2 + wordWidth
                + Skin.S3 * 2f + Skin.HAIRLINE + crumbWidth + Skin.S3;
        Rect left = Skin.pixelAligned(ui,
                new Rect(Skin.S3, Skin.S3, leftWidth, TOPBAR_HEIGHT - Skin.S2));
        glassCluster(ui, left);
        float markX = left.x + Skin.S3;
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
        Rect right = Skin.pixelAligned(ui,
                new Rect(ui.width() - Skin.S3 - accountWidth - versionWidth,
                        Skin.S3, accountWidth + versionWidth, TOPBAR_HEIGHT - Skin.S2));
        glassCluster(ui, right);
        Rect accountArea = new Rect(right.right() - accountWidth, right.y,
                accountWidth, right.height);
        if (chromeAction(ui, accountArea, account)) {
            openAccount(username.isEmpty() || username.startsWith("Player"));
        }
        ui.textRight("1.8.9", accountArea.x - Skin.S2,
                Controls.baseline(ui, right, Type.CAPTION),
                Type.CAPTION, Skin.TEXT_FAINT);
    }

    /**
     * The launcher's glass without its blur: the resting fill, the 10% edge and
     * shared faint top light. Resting fill rather than the strong one,
     * so the footage reads through the chrome instead of stopping at it.
     */
    private void glassCluster(Renderer ui, Rect area) {
        // Fully round. These are bar-height strips floating on the footage, and
        // the launcher draws everything of that shape -- its rail items, its
        // switches, its icon controls -- at `--r-pill`. Half the height is the
        // whole way round.
        float round = Skin.pill(area);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), round, Skin.GLASS);
        ui.outline(area.x, area.y, area.right(), area.bottom(), round,
                Skin.HAIRLINE, Skin.GLASS_EDGE);
        VisualEffects.topLight(ui, area.x, area.y, area.right(), area.bottom(),
                round, Skin.GLASS, 0.72f);
    }

    private boolean chromeAction(Renderer ui, Rect area, String label) {
        String id = "menu.account";
        Controls.State state = controls.state(id, area, !modal());
        // A pill, like every other icon-led control here and in the launcher,
        // inset from the cluster so its curve sits inside the cluster's own
        // rather than running along it.
        Rect pill = new Rect(area.x + 2f, area.y + 2f, area.width - 4f, area.height - 4f);
        float round = Skin.pill(pill);
        float press = tactilePress(state);
        float sink = Skin.snap(ui, press);
        // Drawn at rest, not only under the pointer. `--fill` is the launcher's
        // resting control material -- white at 4.5% -- and on the cluster's
        // glass it is just enough to say "this is a button" to somebody who has
        // not put a pointer on it yet. Without it the only thing here was a
        // label, and a label is not a control.
        int face = Skin.mix(Skin.mix(Skin.FILL, Skin.FILL_HOVER, state.hover),
                Skin.FILL_PRESSED, press);
        ui.rounded(pill.x, pill.y + sink, pill.right(), pill.bottom() + sink, round, face);
        ui.outline(pill.x, pill.y + sink, pill.right(), pill.bottom() + sink, round, Skin.HAIRLINE,
                Skin.mix(Skin.GLASS_EDGE, Skin.GLASS_EDGE_STRONG, state.hover));
        float focus = controls.towards(id + "#menu-focus", state.focused, Timing.HOVER_IN);
        float light = Math.max(state.hover, focus);
        float iconSize = 14f;
        float iconX = Skin.snap(ui, area.x + Skin.S2);
        float iconY = Skin.snap(ui, area.centreY() + sink - iconSize / 2f);
        ui.icon(Icon.USER, iconX, iconY, iconSize,
                VisualEffects.hoverBrighten(Skin.TEXT_FAINT, light));
        ui.text(label, area.x + Skin.S2 * 2f + iconSize,
                Controls.baseline(ui, area, Type.CAPTION) + sink, Type.CAPTION,
                VisualEffects.hoverBrighten(Skin.TEXT_MUTED, light));
        controls.focusRing(ui, state, pill, round);
        return activated(id, state);
    }

    /**
     * The account button: raise the launcher, and say what happened.
     *
     * <p>There is no sign-in here and there cannot be one. The game process was
     * handed a session at launch and has no way to obtain another; signing in is
     * the launcher's job, and Microsoft's. So the button does the one useful
     * thing available to it -- brings the launcher to the front, starting it if
     * it is installed and closed -- and then reports the outcome.
     *
     * <p>Reporting it is the point. This button used to call
     * {@code bridge.open()} when a bridge existed and nothing at all when one
     * did not, which meant that in a game not started by Fathom -- a development
     * client, for instance -- pressing "Sign in" did nothing and said nothing.
     * {@code DiagnosticsSection} puts the rule plainly: a button that silently
     * does nothing is the thing this client spent a milestone removing.
     */
    private void openAccount(boolean signedOut) {
        net.fathom.optimizations.fabric.bridge.LauncherBridge bridge = Runtime.bridge();
        if (bridge == null) {
            notice(signedOut
                    ? "Sign in from the Fathom launcher. This game was not started by one."
                    : "Fathom is not attached to this game.");
            return;
        }
        if (!bridge.open()) {
            notice("The Fathom launcher did not answer. Start it and sign in there.");
            return;
        }
        String detail = bridge.detail();
        if (detail != null && !detail.isEmpty() && !"Connected.".equals(detail)) {
            // The bridge says "Fathom is starting..." while it waits out its own
            // cooldown, which is more use than anything this screen could invent.
            notice(detail);
        } else {
            notice(signedOut ? "Opening Fathom. Sign in there." : "Opening Fathom.");
        }
    }

    private void notice(String message) {
        notice = message;
        noticeAt = Gfx.clock().seconds();
    }

    /**
     * The one-line answer to pressing the account button.
     *
     * <p>Under the cluster it belongs to, gone by itself after five seconds. Not
     * a dialog: nothing here needs acknowledging, and a screen that opens a box
     * over its own menu to say "opening Fathom" is worse than the silence it
     * replaced.
     */
    private void notices(Renderer ui) {
        if (notice == null) {
            return;
        }
        float age = Gfx.clock().seconds() - noticeAt;
        if (age >= NOTICE_SECONDS) {
            notice = null;
            return;
        }
        float now = Gfx.clock().seconds();
        float arriving = Ease.sample(now, noticeAt, 0f, Timing.TOOLTIP, Ease.Curve.STANDARD);
        float leaving = 1f - Ease.sample(now, noticeAt,
                NOTICE_SECONDS - Timing.SCREEN_EXIT, Timing.SCREEN_EXIT, Ease.Curve.EXIT);
        float shown = Math.min(arriving, leaving);

        float width = ui.measure(notice, Type.CAPTION) + Skin.S3 * 2f;
        Rect box = new Rect(ui.width() - Skin.S3 - width, TOPBAR_HEIGHT + Skin.S2, width,
                Skin.H_SM);
        ui.pushAlpha(shown);
        ui.pushTransform(0f, (1f - arriving) * -6f);
        VisualEffects.overlayShadow(ui, box, Skin.R_CONTROL);
        ui.rounded(box.x, box.y, box.right(), box.bottom(), Skin.R_CONTROL, Skin.OVERLAY);
        VisualEffects.topLight(ui, box.x, box.y, box.right(), box.bottom(),
                Skin.R_CONTROL, Skin.OVERLAY, 1f);
        ui.outline(box.x, box.y, box.right(), box.bottom(), Skin.R_CONTROL, Skin.HAIRLINE, Skin.EDGE);
        ui.text(notice, box.x + Skin.S3, Controls.baseline(ui, box, Type.CAPTION), Type.CAPTION,
                Skin.TEXT_MUTED);
        ui.popTransform();
        ui.popAlpha();
    }

    /**
     * Mark over wordmark, both centred on the column.
     *
     * <p>The mark is positioned by its ink, not its 64-unit box, and the wordmark
     * by its cap line, not its ascent: the box carries 8 units of safe area and
     * the ascent carries accent room, and measuring the gap from either makes the
     * lockup look loose by exactly that much.
     */
    private void branding(Renderer ui) {
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

    private void primaryActions(Renderer ui) {
        float y = column.y;
        if (arrivingMenuButton(ui, 1, "menu.single",
                new Rect(column.x, column.y, column.width, MENU_BUTTON_HEIGHT), "Singleplayer")) {
            navigate(new SelectWorldScreen(this));
        }
        y += MENU_BUTTON_HEIGHT + BUTTON_GAP;

        if (arrivingMenuButton(ui, 2, "menu.multi",
                new Rect(column.x, y, column.width, MENU_BUTTON_HEIGHT), "Multiplayer")) {
            navigate(new MultiplayerScreen(this));
        }
    }

    /**
     * Draw something as it arrives, `index` stagger steps after the screen did.
     *
     * <p>The launcher staggers a list by giving each row the same transition and
     * a later delay. There are no CSS transitions here, so the delay is taken
     * off the clock and the same curve is evaluated by hand -- {@link Ease#out}
     * is the launcher's shared standard curve and {@link Timing#STANDARD} is
     * the duration the whole screen opens over.
     */
    private boolean arrivingMenuButton(Renderer ui, int index, String id, Rect area, String label) {
        float shown = Ease.sample(Gfx.clock().seconds(), openedAt,
                index * Timing.STAGGER, Timing.STANDARD, Ease.Curve.STANDARD);
        ui.pushAlpha(shown);
        ui.pushTransform(0f, (1f - shown) * ENTRANCE_RISE);
        try {
            return menuButton(ui, id, area, label);
        } finally {
            ui.popTransform();
            ui.popAlpha();
        }
    }

    /**
     * The secondary dock, at the bottom of the window rather than under the
     * buttons: these are things you change, not places you go, and putting them
     * in the same column made the column read as five destinations of varying
     * importance.
     */
    private void dock(Renderer ui) {
        float dockWidth = DOCK_BUTTON * 7f + DOCK_GAP * 6f;
        float y = Skin.snap(ui, ui.height() - FOOTER_HEIGHT - DOCK_BUTTON - Skin.S3);
        float x = Skin.snap(ui, column.centreX() - dockWidth / 2f);
        if (dockAction(ui, "menu.fathom", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.GRID, "Fathom")) {
            openHub();
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.options", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.SLIDERS, "Options")) {
            navigate(new SettingsScreen(this, client.options));
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.language", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.COMPASS, "Language")) {
            navigate(new LanguageOptionsScreen(this, client.options, client.getLanguageManager()));
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.packs", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.PACKAGE,
                "Resource Packs")) {
            navigate(new ResourcePackScreen(this));
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.scenes", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.LAYERS,
                "Background")) {
            scenesOpen = true;
            scenesFade.to(1f, Gfx.clock().seconds());
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.credits", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.STAR,
                "Credits")) {
            creditsOpen = true;
            creditsFade.to(1f, Gfx.clock().seconds());
        }
        x += DOCK_BUTTON + DOCK_GAP;
        if (dockAction(ui, "menu.quit", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.CLOSE, "Quit")) {
            quitAfterExit();
        }
    }

    /** Whether a panel is over the screen, in which case nothing under it answers. */
    private boolean modal() {
        float now = Gfx.clock().seconds();
        return creditsOpen || scenesOpen
                || creditsFade.value(now) > 0.001f
                || scenesFade.value(now) > 0.001f;
    }

    /** Give the first observed down-frame a visible answer, even at low FPS. */
    private static float tactilePress(Controls.State state) {
        return state.pressed ? Math.max(IMMEDIATE_PRESS, state.press) : state.press;
    }

    /** Pointer and keyboard activation meet at the owned action boundary. */
    private boolean activated(String id, Controls.State state) {
        if (state.clicked) {
            return true;
        }
        if (state.enabled && id.equals(keyboardActivation)) {
            keyboardActivation = null;
            return true;
        }
        return false;
    }

    private void moveFocus(boolean backwards) {
        int current = -1;
        for (int index = 0; index < FOCUS_ORDER.length; index++) {
            if (controls.focused(FOCUS_ORDER[index])) {
                current = index;
                break;
            }
        }
        int next;
        if (current < 0) {
            next = backwards ? FOCUS_ORDER.length - 1 : 0;
        } else {
            next = (current + (backwards ? -1 : 1) + FOCUS_ORDER.length) % FOCUS_ORDER.length;
        }
        controls.focus(FOCUS_ORDER[next]);
    }

    private String focusedAction() {
        for (String id : FOCUS_ORDER) {
            if (controls.focused(id)) {
                return id;
            }
        }
        return null;
    }

    private boolean menuButton(Renderer ui, String id, Rect area, String label) {
        Controls.State state = controls.state(id, area, !modal());
        float focus = controls.towards(id + "#menu-focus", state.focused, Timing.HOVER_IN);
        float emphasis = Math.max(state.hover, focus);
        float press = tactilePress(state);
        int face = Skin.mix(Skin.mix(MENU_GLASS, MENU_GLASS_HOVER, state.hover),
                MENU_GLASS_PRESSED, press);
        int edge = Skin.mix(Skin.GLASS_EDGE, Skin.fade(Skin.ACCENT, 0.72f), emphasis);
        edge = VisualEffects.hoverBrighten(edge, state.hover * 0.5f);
        // `.launch` in the launcher rises a unit under the pointer and presses a
        // unit into the surface: `translateY(-1px)` on hover, `translateY(1px)`
        // on active, the second winning while a press is held. Both eased by
        // `Controls`, on the launcher's own hover and press durations.
        float lift = press * 1.35f - state.hover * 0.75f;
        float inset = press * 0.70f;
        Rect visual = Skin.pixelAligned(ui, Rect.of(area.x + inset, area.y + lift + inset * 0.18f,
                area.right() - inset, area.bottom() + lift - inset * 0.18f));
        float radius = Skin.R_CARD;
        // The launcher's slab casts a shadow, and loses it on the way down --
        // a control that has moved toward the surface should not still be
        // floating above it.
        ui.shadow(visual.x, visual.y, visual.right(), visual.bottom(), radius,
                MENU_SHADOW_SPREAD * (1f - press * 0.5f),
                Skin.scaleAlpha(Skin.SHADOW,
                        VisualEffects.SHADOW_STRENGTH * (1f - press * 0.7f)));
        VisualEffects.primaryButtonGlow(ui, visual, radius, emphasis);
        VisualEffects.layeredSurface(ui, visual, radius, face, state.hover);
        if (state.hover > 0.01f) {
            // A wash, not a fill: the hovered button warms toward the accent
            // and stays glass. Solid red is reserved for things that are on.
            ui.rounded(visual.x, visual.y, visual.right(), visual.bottom(), radius,
                    Skin.scaleAlpha(Skin.ACCENT_WASH, state.hover * 0.7f));
        }
        ui.outline(visual.x, visual.y, visual.right(), visual.bottom(), radius,
                Skin.HAIRLINE, edge);
        VisualEffects.topLight(ui, visual.x, visual.y, visual.right(), visual.bottom(),
                radius, face, 0.72f + state.hover * 0.28f);
        // The stepped edge grows toward the pointer/focus instead of replacing
        // the whole face with red. It remains visible at rest, so the control
        // still belongs to Fathom in a static screenshot.
        VisualEffects.depthBracket(ui, visual, false, 0.54f + emphasis * 0.46f, Skin.ACCENT_STRONG);
        // The row title, not the caption size. These are the two destinations
        // the screen exists for, on a 38-unit face; a 12px label on it reads as
        // a disabled control rather than as the primary action.
        ui.textCentred(label, visual.centreX(), Controls.baseline(ui, visual, Type.ROW_TITLE),
                Type.ROW_TITLE, VisualEffects.hoverBrighten(Skin.TEXT_MUTED, emphasis));
        controls.focusRing(ui, state, area, radius);
        return activated(id, state);
    }

    /** Compact secondary action with a settled-pointer tooltip. */
    private boolean dockAction(Renderer ui, String id, Rect area, Icon icon, String label) {
        Controls.State state = controls.state(id, area, !modal());
        float focus = controls.towards(id + "#menu-focus", state.focused, Timing.HOVER_IN);
        boolean fathom = "menu.fathom".equals(id);
        boolean active = fathom && !modal()
                || "menu.scenes".equals(id) && scenesFade.value(Gfx.clock().seconds()) > 0.01f
                || "menu.credits".equals(id) && creditsFade.value(Gfx.clock().seconds()) > 0.01f;
        float selected = Math.max(active ? 1f : 0f, focus);
        float press = tactilePress(state);
        // The launcher's small pill: --glass-fill, the plain edge, the plain
        // sheen. Lighter than the two destinations above it, which is the
        // difference between a place to go and a thing to change.
        int face = Skin.mix(Skin.mix(DOCK_GLASS, DOCK_GLASS_HOVER, state.hover),
                DOCK_GLASS_PRESSED, press);
        // An icon with no label is a circle here, as it is in the launcher,
        // where every icon-only control is drawn at `--r-pill`. These are
        // square, so half the height is the whole way round.
        float sink = press;
        float inset = press * 0.75f;
        Rect visual = Skin.pixelAligned(ui, Rect.of(area.x + inset, area.y + sink + inset,
                area.right() - inset, area.bottom() + sink - inset));
        float round = Skin.pill(visual);
        // No hover lift on these. The launcher's plain button does not rise
        // under the pointer either -- only its one big destination does -- and
        // five small things lifting in a row reads as the dock twitching.
        VisualEffects.selectedButtonGlow(ui, visual, round, selected, state.hover);
        ui.rounded(visual.x, visual.y, visual.right(), visual.bottom(), round, face);
        int hoverEdge = fathom ? Skin.fade(Skin.ACCENT, 0.68f) : Skin.GLASS_EDGE_STRONG;
        int edge = Skin.mix(Skin.GLASS_EDGE, hoverEdge, state.hover);
        edge = Skin.mix(edge, Skin.fade(Skin.ACCENT, 0.78f), Math.max(focus, selected * 0.72f));
        ui.outline(visual.x, visual.y, visual.right(), visual.bottom(), round, Skin.HAIRLINE, edge);
        VisualEffects.depthMarker(ui, visual.centreX(), visual.bottom() - 1.5f, selected);
        VisualEffects.topLight(ui, visual.x, visual.y, visual.right(), visual.bottom(),
                round, face, 0.72f + state.hover * 0.28f);
        float iconSize = DOCK_ICON;
        // Muted rather than faint: faint is a hub value, tuned against a panel
        // two shades from black, and it disappears into a snowfield.
        float iconX = Skin.snap(ui, visual.centreX() - iconSize / 2f);
        float iconY = Skin.snap(ui, visual.centreY() - iconSize / 2f);
        int iconTint = Skin.mix(Skin.TEXT_MUTED, Skin.ACCENT_STRONG, selected * 0.72f);
        ui.icon(icon, iconX, iconY, iconSize,
                VisualEffects.hoverBrighten(iconTint, Math.max(state.hover, focus)));
        controls.focusRing(ui, state, area, Skin.pill(area));
        boolean invoked = activated(id, state);
        if (!invoked) {
            controls.tooltip(state, area, label);
        }
        return invoked;
    }


    // -----------------------------------------------------------------------
    // Credits
    // -----------------------------------------------------------------------

    /**
     * The credits panel.
     *
     * <p>A panel over the menu rather than a screen of its own, because leaving
     * this screen stops the video: a credits page for footage, with the footage
     * switched off behind it, would be a strange way to show somebody what they
     * are being shown. The world darkens, the panel is Fathom's own near-black,
     * and everything underneath stops answering the pointer while it is up.
     */
    private void credits(Renderer ui) {
        float shown = creditsFade.value(Gfx.clock().seconds());
        if (shown <= 0.001f) {
            return;
        }
        ui.pushAlpha(shown);

        // Darker than the chrome but lighter than the hub's scrim: the video is
        // the subject of half these credits, so it stays legible behind them.
        ui.fill(0f, 0f, ui.width(), ui.height(), Skin.fade(0x000000, 0.62f));

        float pad = Skin.S5;
        float labelLine = ui.lineHeight(Type.LABEL);
        float titleLine = ui.lineHeight(Type.BODY_STRONG);
        float captionLine = ui.lineHeight(Type.CAPTION);

        float text = 0f;
        for (String[] entry : CREDITS) {
            text = Math.max(text, ui.measure(entry[0], Type.LABEL));
            text = Math.max(text, ui.measure(entry[1], Type.BODY_STRONG));
            text = Math.max(text, ui.measure(entry[2], Type.CAPTION));
        }
        for (String line : CREDITS_FOOTER) {
            text = Math.max(text, ui.measure(line, Type.CAPTION));
        }
        float width = Math.min(ui.width() - Skin.S8 * 2f, text + pad * 2f);
        float height = pad
                + ui.lineHeight(Type.TITLE) + Skin.S4
                + CREDITS.length * (labelLine + titleLine + captionLine + Skin.S4)
                + CREDITS_FOOTER.length * captionLine + Skin.S4
                + Skin.H_MD + pad;
        Rect panel = new Rect(Math.round((ui.width() - width) / 2f),
                Math.round((ui.height() - height) / 2f), width, height);

        VisualEffects.modalShadow(ui, panel, Skin.R_MEDIUM);
        ui.rounded(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_MEDIUM, Skin.PANEL);
        VisualEffects.topLight(ui, panel.x, panel.y, panel.right(), panel.bottom(),
                Skin.R_MEDIUM, Skin.PANEL, 1f);
        ui.outline(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_MEDIUM, Skin.HAIRLINE,
                Skin.EDGE);
        VisualEffects.depthBracket(ui, panel, false, shown, Skin.ACCENT_STRONG);

        float x = panel.x + pad;
        float y = panel.y + pad;
        ui.text("Credits", x, y + ui.ascent(Type.TITLE), Type.TITLE, Skin.TEXT);
        y += ui.lineHeight(Type.TITLE) + Skin.S4;

        for (String[] entry : CREDITS) {
            ui.text(entry[0], x, y + ui.ascent(Type.LABEL), Type.LABEL, Skin.TEXT_FAINT);
            y += labelLine;
            ui.text(entry[1], x, y + ui.ascent(Type.BODY_STRONG), Type.BODY_STRONG, Skin.TEXT);
            y += titleLine;
            ui.text(entry[2], x, y + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_MUTED);
            y += captionLine + Skin.S4;
        }

        for (String line : CREDITS_FOOTER) {
            ui.text(line, x, y + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_DISABLED);
            y += captionLine;
        }

        // The launcher's own control, unthinned: it is on Fathom's near-black
        // here, which is the surface that material was drawn for.
        Rect close = new Rect(panel.right() - pad - CREDITS_CLOSE_WIDTH,
                panel.bottom() - pad - Skin.H_MD, CREDITS_CLOSE_WIDTH, Skin.H_MD);
        if (controls.secondary(ui, "menu.credits.close", close, "Close", true)) {
            closeCredits();
        }

        ui.popAlpha();
    }


    // -----------------------------------------------------------------------
    // The scene picker
    // -----------------------------------------------------------------------

    /** Which scene is behind the menu, as the profile last recorded it. */
    private MenuScenes.Scene scene() {
        net.fathom.optimizations.core.module.FathomModule module =
                Runtime.features() == null ? null : Runtime.features().find("main-menu");
        return MenuScenes.byLabel(module == null
                ? MenuScenes.DEFAULT_LABEL
                : module.choice("scene", MenuScenes.DEFAULT_LABEL));
    }

    /**
     * Switch scenes, and write it down.
     *
     * <p>Saved immediately rather than when something closes, because this
     * screen does not close: somebody who picks a background and then presses
     * Singleplayer has left the menu for the rest of the session, and a
     * preference that only survives when you leave a screen the polite way is
     * not a preference.
     */
    private void chooseScene(MenuScenes.Scene scene) {
        net.fathom.optimizations.core.module.FathomModule module =
                Runtime.features() == null ? null : Runtime.features().find("main-menu");
        net.fathom.optimizations.core.module.Setting setting =
                module == null ? null : module.setting("scene");
        if (!(setting instanceof net.fathom.optimizations.core.module.Setting.Choice)) {
            return;
        }
        ((net.fathom.optimizations.core.module.Setting.Choice) setting).set(scene.label);
        if (!scene.animated) {
            // Let the decoder and its thread go: nothing is playing now.
            MenuVideo.stop();
        }
        if (Runtime.hud() != null) {
            String failure = Runtime.hud().saveAll();
            if (failure != null) {
                notice("The background changed, but could not be saved: " + failure);
            }
        }
    }

    private void closeScenes() {
        scenesOpen = false;
        scenesFade.to(0f, Gfx.clock().seconds(), Timing.SCREEN_EXIT, Ease.Curve.EXIT);
    }

    /**
     * The launcher's background picker, in the game.
     *
     * <p>The same fifteen scenes under the same names, the same tick on the one
     * in use, and the same credit underneath -- the launcher puts the world and
     * its creator under the grid because that is where the footage is chosen,
     * and this is the other place it is chosen.
     *
     * <p>Choosing is immediate. There is no confirm step and no preview pane:
     * the menu behind this panel <em>is</em> the preview.
     */
    private void scenes(Renderer ui) {
        float shown = scenesFade.value(Gfx.clock().seconds());
        if (shown <= 0.001f) {
            return;
        }
        ui.pushAlpha(shown);
        ui.fill(0f, 0f, ui.width(), ui.height(), Skin.fade(0x000000, 0.62f));

        MenuScenes.Scene[] all = MenuScenes.all();
        float pad = Skin.S5;
        float gap = Skin.S2;
        float width = Math.min(SCENES_PANEL_WIDTH, ui.width() - Skin.S8 * 2f);
        float tileWidth = (width - pad * 2f - gap * (SCENE_COLUMNS - 1)) / SCENE_COLUMNS;
        float tileHeight = tileWidth * 9f / 16f;
        float labelLine = ui.lineHeight(Type.CAPTION);
        float rowHeight = tileHeight + 2f + labelLine + gap;
        int rows = (all.length + SCENE_COLUMNS - 1) / SCENE_COLUMNS;

        float height = pad
                + ui.lineHeight(Type.TITLE) + Skin.S4
                + rows * rowHeight
                + labelLine + Skin.S4
                + Skin.H_MD + pad;
        Rect panel = new Rect(Math.round((ui.width() - width) / 2f),
                Math.round((ui.height() - height) / 2f), width, height);

        VisualEffects.modalShadow(ui, panel, Skin.R_CARD);
        ui.rounded(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_CARD, Skin.PANEL);
        VisualEffects.topLight(ui, panel.x, panel.y, panel.right(), panel.bottom(),
                Skin.R_CARD, Skin.PANEL, 1f);
        ui.outline(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_CARD, Skin.HAIRLINE,
                Skin.EDGE);
        VisualEffects.depthBracket(ui, panel, false, shown, Skin.ACCENT_STRONG);

        float x = panel.x + pad;
        float y = panel.y + pad;
        ui.text("Background", x, y + ui.ascent(Type.TITLE), Type.TITLE, Skin.TEXT);
        y += ui.lineHeight(Type.TITLE) + Skin.S4;

        MenuScenes.Scene current = scene();
        MenuScenes.Scene chosen = null;
        for (int index = 0; index < all.length; index++) {
            MenuScenes.Scene scene = all[index];
            float tileX = x + (index % SCENE_COLUMNS) * (tileWidth + gap);
            float tileY = y + (index / SCENE_COLUMNS) * rowHeight;
            Rect tile = new Rect(tileX, tileY, tileWidth, tileHeight);
            boolean selected = scene.label.equals(current.label);
            Controls.State state = controls.state("menu.scene." + scene.id, tile, true);

            if (scene.gradient) {
                // No picture for the flat field, so the tile is the field.
                ui.gradient(tile.x, tile.y, tile.right(), tile.bottom(), Skin.R_CONTROL,
                        0xFF0D1119, 0xFF030408);
            } else {
                if (!MenuScenes.thumbnail(ui, scene, tile, Skin.R_CONTROL, Skin.PANEL)) {
                    ui.rounded(tile.x, tile.y, tile.right(), tile.bottom(), Skin.R_CONTROL,
                            Skin.FILL_SUNKEN);
                }
            }

            int edge = selected
                    ? Skin.ACCENT
                    : Skin.mix(Skin.EDGE, Skin.EDGE_STRONG, state.hover);
            ui.outline(tile.x, tile.y, tile.right(), tile.bottom(), Skin.R_CONTROL,
                    selected ? Skin.HAIRLINE * 2f : Skin.HAIRLINE, edge);

            if (selected) {
                // The launcher's tick, in the same corner.
                float mark = 15f;
                Rect tick = new Rect(tile.right() - mark - Skin.S1, tile.y + Skin.S1, mark, mark);
                ui.rounded(tick.x, tick.y, tick.right(), tick.bottom(), Skin.pill(tick), Skin.ACCENT);
                ui.icon(Icon.CHECK, tick.x + 3f, tick.y + 3f, mark - 6f, 0xFFFFFFFF);
            }

            ui.text(Text.fit(ui, scene.label, Type.CAPTION, tileWidth), tile.x,
                    tile.bottom() + 2f + ui.ascent(Type.CAPTION), Type.CAPTION,
                    selected ? Skin.TEXT : Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, state.hover));

            if (state.clicked) {
                chosen = scene;
            }
        }
        y += rows * rowHeight;

        // The credit belongs where the footage is chosen, which is here.
        String credit = current.credited()
                ? "Recorded in " + current.world + ", a world by " + current.creator
                : "Recorded in Minecraft by the Fathom project.";
        ui.text(Text.fit(ui, credit, Type.CAPTION, width - pad * 2f), x,
                y + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_DISABLED);

        Rect close = new Rect(panel.right() - pad - CREDITS_CLOSE_WIDTH,
                panel.bottom() - pad - Skin.H_MD, CREDITS_CLOSE_WIDTH, Skin.H_MD);
        if (controls.secondary(ui, "menu.scenes.close", close, "Close", true)) {
            closeScenes();
        }

        ui.popAlpha();

        // Applied after the loop, because choosing changes what the loop reads.
        if (chosen != null) {
            chooseScene(chosen);
        }
    }

    private void closeCredits() {
        creditsOpen = false;
        creditsFade.to(0f, Gfx.clock().seconds(), Timing.SCREEN_EXIT, Ease.Curve.EXIT);
    }

    /**
     * One small status cluster, bottom-left.
     *
     * <p>"Development" appears only in a development environment; a released
     * build says "Connected" when the launcher is on the other end of the bridge
     * and nothing when it is not. The game version is already in the top-right
     * cluster, so it is not repeated down here.
     */
    private void footer(Renderer ui) {
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
        Rect bar = Skin.pixelAligned(ui, new Rect(Skin.S3, top + Skin.S1,
                Skin.S3 + dot + Skin.S2 + lineWidth + statusWidth + Skin.S3,
                FOOTER_HEIGHT - Skin.S2));
        glassCluster(ui, bar);
        float baseline = Controls.baseline(ui, bar, Type.CAPTION);
        float dotX = bar.x + Skin.S3;
        Rect statusDot = Rect.of(dotX, bar.centreY() - dot / 2f,
                dotX + dot, bar.centreY() + dot / 2f);
        ui.rounded(statusDot.x, statusDot.y, statusDot.right(), statusDot.bottom(),
                Skin.pill(statusDot), Skin.mix(Skin.ACCENT_DEEP, Skin.ACCENT, 0.32f));
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
        if (closing) {
            return;
        }
        pendingHub = true;
        beginExit();
    }

    private void navigate(Screen destination) {
        if (destination == null || closing) {
            return;
        }
        pendingScreen = destination;
        beginExit();
    }

    private void quitAfterExit() {
        if (closing) {
            return;
        }
        pendingQuit = true;
        beginExit();
    }

    private void beginExit() {
        closing = true;
        controls.setInputEnabled(false);
        controls.closePopover();
        transition.close(Gfx.clock().seconds());
    }

    /** Called after Gfx.end(), so the destination inherits a restored GL state. */
    private void finishNavigation() {
        if (pendingQuit) {
            client.scheduleStop();
        } else if (pendingHub) {
            FathomNavigation.openHub(client, Runtime.hud());
        } else if (pendingScreen != null) {
            client.setScreen(pendingScreen);
        }
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    @Override
    public boolean handleFathomKey(char character, int keyCode) {
        if (closing) {
            return true;
        }
        if (Runtime.hud() != null && Runtime.hud().hubKey().matches(keyCode)) {
            // Right Shift works here too. It is handled in this screen rather
            // than in the key Mixin because that one deliberately only opens the
            // hub from the game itself, where there is no screen to be ambiguous
            // about.
            openHub();
            return true;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // Escape backs out of the credits, and otherwise does nothing: there
            // is nowhere above the main menu, and a menu that quit the game on
            // Escape would be a menu that quits the game on the key everybody
            // presses to back out of things.
            if (creditsOpen) {
                closeCredits();
            } else if (scenesOpen) {
                closeScenes();
            }
            return true;
        }
        if (!modal() && keyCode == Keyboard.KEY_TAB) {
            boolean backwards = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                    || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            moveFocus(backwards);
            return true;
        }
        if (!modal() && (keyCode == Keyboard.KEY_RETURN
                || keyCode == Keyboard.KEY_NUMPADENTER
                || keyCode == Keyboard.KEY_SPACE)) {
            String focused = focusedAction();
            if (focused != null) {
                keyboardActivation = focused;
            }
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
        attached = false;
        MenuVideo.stop();
        pointer.reset();
    }

    /** Release every Fathom-owned main-menu GL resource after renderer disposal. */
    public static void disposeResources() {
        MenuVideo.dispose();
        MenuScenes.dispose();
        MenuPoster.dispose();
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
