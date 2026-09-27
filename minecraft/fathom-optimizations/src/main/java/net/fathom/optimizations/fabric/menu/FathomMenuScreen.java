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
import net.fathom.optimizations.fabric.bridge.LauncherAccount;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.Gfx;
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
 * <p>The first screen of the game, in the launcher's own design language: the
 * launcher's golden-hour photograph under the launcher's warm scrim, the
 * Surface wordmark in the sky, the two destinations on the water below the
 * horizon, and a small pane of secondary actions at the foot.
 *
 * <pre>
 *                FATHOM                      (the sky)
 *     ~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~~     (the horizon, left clear)
 *         [ Singleplayer ]
 *         [ Multiplayer  ]                   (the water)
 *
 *        [F] [O] [L] [P] [C] [Q]
 * </pre>
 *
 * <h2>Glass, without blur</h2>
 *
 * <p>The destinations are standard glass -- they carry a label over a moving
 * photograph and need the density -- and the dock is one pane of light glass
 * with flat controls inside it, as a launcher pane holds its controls. Hover
 * and focus are a step denser and a brighter rim. Nothing glows, nothing is
 * red, nothing lifts. The wordmark's surface line is kept well above the
 * picture's horizon; the brand's surface is not the sea's.
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
 * <p>A second sign-in. The account button shows the player's username and head
 * and opens a textured account picker that can switch remembered accounts or
 * start the launcher's Microsoft sign-in through the bridge (see
 * {@code LauncherAccount}). The game never sees
 * a password, a code or a token; it learns the public username and UUID when the
 * launcher is done.
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
                MenuScenes.draw(client, null, Runtime.menuBackgroundQuality());
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
     * The wordmark, by its cap height in logical units. At GUI scale 2 this is
     * well past the brand's full tier, so it carries the whole refraction and
     * the bronze edge; at scale 1 it carries the one-pixel form.
     */
    private static final float WORDMARK_CAP = 34f;
    /**
     * Where the wordmark's top sits, as a fraction of the window's height: in
     * open sky, clear of the palms' crowns (which reach up to about a quarter)
     * and far from the horizon.
     */
    private static final float WORDMARK_AT = 0.15f;
    /** Where the destinations start: on the water, below the horizon at ~48%. */
    private static final float ACTIONS_AT = 0.56f;
    private static final float LOCKUP_TO_ACTIONS = 40f;


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
     * The destinations: standard glass at rest, dense under the pointer. They
     * carry a label over the photograph and need the density to hold it.
     */
    private static final int MENU_GLASS = Skin.MATERIAL_STANDARD;
    private static final int MENU_GLASS_HOVER = Skin.MATERIAL_DENSE;
    private static final int MENU_GLASS_PRESSED = Skin.MATERIAL_DENSE_SOLID;

    /** The dock is one pane of light glass; its controls are flat inside it. */
    private static final int DOCK_GLASS = Skin.MATERIAL_LIGHT;

    /** The launcher's warm modal scrim: the menu stays visible behind a panel. */
    private static final int MODAL_SCRIM = 0x57160E06;

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
     * <p>{@code ATTRIBUTION.md} is the repository's record, which is the wrong
     * place for a player to have to look: the photograph is credited where it
     * is shown, in the launcher's own words. Label, subject, and who.
     */
    private static final String[][] CREDITS = {
        {"Background", "Palm Tree Sunset Water", "By showtime, at Wallpapers.com"},
        {"Typeface", "Geist", "Vercel and basement.studio, SIL Open Font Licence 1.1"},
    };

    private static final String[] CREDITS_FOOTER = {
        "Full credits in ATTRIBUTION.md.",
        "Not an official Minecraft product. Not approved by or associated with Mojang.",
    };

    private static final float CREDITS_CLOSE_WIDTH = 86f;

    /** How long an account notice stays up before it fades on its own. */
    private static final float NOTICE_SECONDS = 5f;

    /** The picker's grid, for when the approved set holds more than one. */
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
    /** Textured account manager shown without redirecting to the launcher. */
    private boolean accountOpen;
    private Motion accountFade;
    private float openedAt;
    private int captureFrames;
    private boolean captured;
    private boolean captureTargetOpened;
    private boolean closeCaptureFinished;

    /** Where the column sits, recomputed each frame from the window. */
    private Rect column = Rect.of(0f, 0f, 0f, 0f);
    /** The top of the wordmark's ink. */
    private float lockupTop;
    /** What the account button last did, and when it said so. */
    private String notice;
    /** The last account notice already shown, so each is shown once. */
    private int accountNoticeSerial;
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
        // Who the launcher has signed in, asked off the render thread. Only
        // matters for a game Fathom did not start; the answer is ready a
        // quarter-second later at worst.
        LauncherAccount launcherAccount = account();
        accountNoticeSerial = launcherAccount.noticeSerial();
        launcherAccount.check();
        Gfx.restartClock();
        float now = Gfx.clock().seconds();
        if (transition == null) {
            transition = new Transition(Timing.SCREEN_ENTER, Timing.SCREEN_EXIT);
            dockOpening = new Motion(0f, Timing.STANDARD);
            creditsFade = new Motion(0f, Timing.PANEL);
            scenesFade = new Motion(0f, Timing.PANEL);
            accountFade = new Motion(0f, Timing.PANEL);
        }
        transition.open(now);
        dockOpening.set(0f, now);
        creditsFade.set(0f, now);
        scenesFade.set(0f, now);
        accountFade.set(0f, now);
        creditsOpen = false;
        scenesOpen = false;
        accountOpen = false;
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
            openAccount(Runtime.bridge().startedByLauncher(), account());
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
                || scene().animated && MenuVideo.uploadedFrames()
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
     * Where the wordmark and the column go.
     *
     * <p>The wordmark sits in the sky and the destinations on the water, with
     * the horizon -- a little under half way down the picture -- left clear
     * between them. On a short window the two close up towards each other
     * before anything is allowed to reach the dock.
     */
    private void layout(Renderer ui) {
        float width = Math.min(BUTTON_WIDTH, ui.width() - Skin.S8 * 2f);
        float actions = MENU_BUTTON_HEIGHT * 2f + BUTTON_GAP;
        float dockTop = ui.height() - FOOTER_HEIGHT - DOCK_BUTTON - Skin.S3 - Skin.S2;
        float top = Math.max(TOPBAR_HEIGHT + Skin.S4, ui.height() * WORDMARK_AT);
        float actionsTop = Math.max(top + WORDMARK_CAP + LOCKUP_TO_ACTIONS, ui.height() * ACTIONS_AT);
        float overflow = actionsTop + actions - (dockTop - Skin.S6);
        if (overflow > 0f) {
            actionsTop -= overflow;
            top = Math.max(TOPBAR_HEIGHT + Skin.S2,
                    Math.min(top, actionsTop - LOCKUP_TO_ACTIONS - WORDMARK_CAP));
        }
        // Whole device pixels, so the wordmark's rows land where it was drawn.
        lockupTop = Skin.snap(ui, top);
        column = Skin.pixelAligned(ui, new Rect((ui.width() - width) / 2f,
                actionsTop, width, actions));
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
        accountPanel(ui);

        controls.drawOverlays(ui);
        ui.popTransform();
        ui.popAlpha();
    }

    // -----------------------------------------------------------------------
    // Branding
    // -----------------------------------------------------------------------

    /**
     * The account control, top right. Nothing top left: the wordmark in the
     * sky is the brand, and a second, smaller one in a corner was only
     * repeating it.
     */
    private void chrome(Renderer ui) {
        String username = client.getSession() == null ? "" : client.getSession().getUsername();
        String sessionUuid = client.getSession() == null ? "" : client.getSession().getUuid();
        LauncherAccount launcherAccount = account();
        if (launcherAccount.noticeSerial() != accountNoticeSerial) {
            accountNoticeSerial = launcherAccount.noticeSerial();
            if (launcherAccount.notice() != null) {
                notice(launcherAccount.notice());
            }
        }
        // The game's own session when it is a real account -- always, when
        // Fathom started the game -- otherwise whoever the launcher has signed in.
        boolean realSession = Runtime.bridge().startedByLauncher()
                || Runtime.bridge().session().present()
                || !LauncherAccount.isPlaceholder(username);
        String account;
        String headUuid;
        if (realSession) {
            account = username;
            headUuid = sessionUuid;
        } else if (launcherAccount.phase() == LauncherAccount.Phase.SIGNED_IN) {
            account = launcherAccount.name();
            headUuid = launcherAccount.uuid();
        } else if (launcherAccount.phase() == LauncherAccount.Phase.SIGNING_IN
                || launcherAccount.working()) {
            account = "Signing in…";
            headUuid = "";
        } else {
            account = "Sign in";
            headUuid = "";
        }
        int head = AccountHead.texture(launcherAccount.launcherRoot(), headUuid);
        float accountWidth = Math.max(70f,
                ui.measure(account, Type.CAPTION) + 13f + Skin.S2 * 3f);
        // The version used to sit beside this on the bare sky, where no grade of
        // champagne type holds; it is in the footer now, on the scrim.
        Rect accountArea = Skin.pixelAligned(ui, new Rect(ui.width() - Skin.S3 - accountWidth,
                Skin.S3, accountWidth, TOPBAR_HEIGHT - Skin.S2));
        if (chromeAction(ui, accountArea, account, head)) {
            openAccount(realSession, launcherAccount);
        }
    }

    private boolean chromeAction(Renderer ui, Rect area, String label, int headTexture) {
        String id = "menu.account";
        Controls.State state = controls.state(id, area, !modal());
        // A pill, like every other icon-led control here and in the launcher.
        //
        // It used to be inset two pixels so its curve sat inside a surrounding
        // cluster's. There is no surrounding cluster any more, so the inset
        // only produced a visible gap between two borders -- and the radius was
        // still being derived from `R_MEDIUM` after the cluster moved to
        // `Skin.pill`, so the two curves did not even nest. It fills its area
        // and is fully round, full stop.
        Rect pill = area;
        float round = Skin.pill(area);
        float press = tactilePress(state);
        float sink = Skin.snap(ui, press);
        // Drawn at rest, not only under the pointer. `--fill` is the launcher's
        // resting control material -- white at 4.5% -- and on the cluster's
        // glass it is just enough to say "this is a button" to somebody who has
        // not put a pointer on it yet. Without it the only thing here was a
        // label, and a label is not a control.
        // Light glass on the sky, a step denser under the pointer: the
        // launcher's account capsule, without its blur.
        int face = Skin.mix(Skin.mix(Skin.MATERIAL_LIGHT, Skin.MATERIAL_STANDARD, state.hover),
                Skin.MATERIAL_DENSE, press);
        VisualEffects.glass(ui, Rect.of(pill.x, pill.y + sink, pill.right(), pill.bottom() + sink),
                round, face, Skin.RIM_TOP_LIGHT, Skin.RIM_EDGE_LIGHT);
        float focus = controls.towards(id + "#menu-focus", state.focused, Timing.HOVER_IN);
        float light = Math.max(state.hover, focus);
        float iconSize = 14f;
        float iconX = Skin.snap(ui, area.x + Skin.S2);
        float iconY = Skin.snap(ui, area.centreY() + sink - iconSize / 2f);
        if (headTexture > 0 && ui instanceof net.fathom.optimizations.fabric.gfx.GlRenderer) {
            // The player's face where the generic user glyph would be: same
            // square, same place, a hair of rounding so it sits in the pill.
            ((net.fathom.optimizations.fabric.gfx.GlRenderer) ui).image(headTexture, iconX, iconY,
                    iconX + iconSize, iconY + iconSize, 0f, 1f, 0);
        } else {
            ui.icon(Icon.USER, iconX, iconY, iconSize,
                    VisualEffects.hoverBrighten(Skin.TEXT_FAINT, light));
        }
        ui.text(label, area.x + Skin.S2 * 2f + iconSize,
                Controls.baseline(ui, area, Type.CAPTION) + sink, Type.CAPTION, Skin.TEXT);
        controls.focusRing(ui, state, pill, round);
        return activated(id, state);
    }

    /**
     * The account button.
     *
     * <p>When this game has no real account, it signs in: the launcher runs its
     * own Microsoft sign-in, opening Microsoft's page in the browser, and the
     * button follows it to the end through {@code LauncherAccount}. No second
     * authentication implementation exists in the game, and nothing secret
     * reaches it.
     *
     * <p>When it does have one -- always, when Fathom started it -- the button
     * brings the launcher forward on its account, starting it if it is
     * installed and closed.
     *
     * <p>Every press says what happened. A button that silently does nothing is
     * the thing this client spent a milestone removing, and this one was exactly
     * that for a game Fathom had not started.
     */
    private void openAccount(boolean realSession, LauncherAccount launcherAccount) {
        accountOpen = true;
        accountFade.to(1f, Gfx.clock().seconds());
        launcherAccount.check();
    }

    private void closeAccount() {
        accountOpen = false;
        accountFade.to(0f, Gfx.clock().seconds(), Timing.SCREEN_EXIT, Ease.Curve.EXIT);
    }

    /**
     * Account management without leaving the game. Tokens remain entirely in
     * the launcher; this panel receives only public Minecraft profiles and the
     * short device code Microsoft asks the player to type.
     */
    private void accountPanel(Renderer ui) {
        float shown = accountFade.value(Gfx.clock().seconds());
        if (shown <= 0.001f) {
            return;
        }
        ui.pushAlpha(shown);
        ui.fill(0f, 0f, ui.width(), ui.height(), MODAL_SCRIM);

        LauncherAccount state = account();
        net.fathom.optimizations.fabric.bridge.LauncherBridge.SavedAccount[] saved =
                state.savedAccounts();
        int visibleAccounts = Math.min(saved.length, 6);
        float width = Math.min(390f, ui.width() - Skin.S8 * 2f);
        float row = 42f;
        float codeHeight = state.userCode().isEmpty() ? 0f : 62f;
        float height = Skin.S5 + ui.lineHeight(Type.TITLE) + Skin.S4
                + ui.lineHeight(Type.CAPTION) + Skin.S3 + codeHeight
                + visibleAccounts * (row + Skin.S2) + Skin.S4 + Skin.H_MD + Skin.S5;
        Rect panel = new Rect(Math.round((ui.width() - width) / 2f),
                Math.round((ui.height() - height) / 2f), width, height);
        // No drop shadow: over the photograph the renderer's box falloff reads as
        // dark bands above and below the pane. The dense glass and the scrim
        // already put it in front.
        VisualEffects.glass(ui, panel, Skin.R_CARD, Skin.FLOATING,
                Skin.RIM_TOP_DENSE, Skin.RIM_EDGE_DENSE);

        float x = panel.x + Skin.S5;
        float y = panel.y + Skin.S5;
        float inner = panel.width - Skin.S5 * 2f;
        ui.text("Account", x, y + ui.ascent(Type.TITLE), Type.TITLE, Skin.TEXT);
        y += ui.lineHeight(Type.TITLE) + Skin.S4;
        String summary = state.phase() == LauncherAccount.Phase.SIGNED_IN
                ? "Signed in as " + state.name() + ". Changes apply to the next launch."
                : state.working() ? "Waiting for Microsoft…" : "Choose an account or add one.";
        float summaryX = x;
        if (state.working()) {
            // The launcher's loading glyph, where it waits for Microsoft.
            float glyph = ui.lineHeight(Type.CAPTION) - 2f;
            summaryX += BrandMark.soundingLine(ui, x, y + 1f, glyph, Skin.TEXT_MUTED) + Skin.S2;
        }
        ui.text(Text.fit(ui, summary, Type.CAPTION, inner - (summaryX - x)), summaryX,
                y + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_MUTED);
        y += ui.lineHeight(Type.CAPTION) + Skin.S3;

        if (!state.userCode().isEmpty()) {
            ui.text("Paste this copied code at microsoft.com/link", x,
                    y + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_FAINT);
            ui.text(state.userCode(), x, y + ui.lineHeight(Type.CAPTION)
                    + ui.ascent(Type.TITLE), Type.TITLE, Skin.TEXT);
            y += codeHeight;
        }

        for (int index = 0; index < visibleAccounts; index++) {
            net.fathom.optimizations.fabric.bridge.LauncherBridge.SavedAccount savedAccount = saved[index];
            Rect area = new Rect(x, y, inner, row);
            Controls.State choice = controls.state("menu.account.saved." + index, area,
                    !state.working() && !savedAccount.active);
            // The active account is the denser row with the brighter rim.
            ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_CONTROL,
                    savedAccount.active ? Skin.FILL_ACTIVE
                            : Skin.mix(Skin.FILL, Skin.FILL_HOVER, choice.hover));
            ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_CONTROL,
                    Skin.HAIRLINE, savedAccount.active ? Skin.EDGE_ELEVATED : Skin.EDGE_SURFACE);
            int head = AccountHead.texture(state.launcherRoot(), savedAccount.uuid);
            float avatar = 26f;
            if (head > 0 && ui instanceof net.fathom.optimizations.fabric.gfx.GlRenderer) {
                ((net.fathom.optimizations.fabric.gfx.GlRenderer) ui).image(head,
                        area.x + Skin.S2, area.centreY() - avatar / 2f,
                        area.x + Skin.S2 + avatar, area.centreY() + avatar / 2f,
                        3f, 1f, Skin.FILL_SUNKEN);
            } else {
                ui.icon(Icon.USER, area.x + Skin.S2 + 5f, area.centreY() - 8f,
                        16f, Skin.TEXT_FAINT);
            }
            ui.text(savedAccount.name, area.x + Skin.S2 * 2f + avatar,
                    Controls.baseline(ui, area, Type.BODY_STRONG), Type.BODY_STRONG, Skin.TEXT);
            ui.textRight(savedAccount.active ? "Active" : "Switch",
                    area.right() - Skin.S3, Controls.baseline(ui, area, Type.CAPTION),
                    Type.CAPTION, savedAccount.active ? Skin.TEXT : Skin.TEXT_MUTED);
            if (choice.clicked) {
                state.switchTo(savedAccount.uuid);
            }
            y += row + Skin.S2;
        }

        Rect add = new Rect(x, panel.bottom() - Skin.S5 - Skin.H_MD,
                Math.max(170f, inner - 96f - Skin.S2), Skin.H_MD);
        if (controls.primary(ui, "menu.account.add", add,
                saved.length == 0 ? "Sign in with Microsoft" : "Add Microsoft account",
                !state.working())) {
            state.signIn();
        }
        Rect close = new Rect(panel.right() - Skin.S5 - 88f, add.y, 88f, Skin.H_MD);
        if (controls.secondary(ui, "menu.account.close", close, "Close", true)) {
            closeAccount();
        }
        ui.popAlpha();
    }

    private static LauncherAccount account() {
        return LauncherAccount.get(Runtime.bridge().gameDirectory());
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
        VisualEffects.layeredSurface(ui, box, Skin.R_CONTROL, Skin.FLOATING, 0f);
        ui.outline(box.x, box.y, box.right(), box.bottom(), Skin.R_CONTROL,
                Skin.BORDER_STANDARD, Skin.EDGE_ELEVATED);
        ui.text(notice, box.x + Skin.S3, Controls.baseline(ui, box, Type.CAPTION), Type.CAPTION,
                Skin.TEXT_MUTED);
        ui.popTransform();
        ui.popAlpha();
    }

    /**
     * The Surface wordmark, centred, in the sky.
     *
     * <p>Positioned by its ink: the top of the cap is {@code lockupTop}. It
     * arrives by fading and rising a few units -- no scale, so the raster is
     * drawn pixel for pixel from its first visible frame.
     */
    private void branding(Renderer ui) {
        float centre = column.centreX();
        float now = Gfx.clock().seconds();
        float shown = Ease.sample(now, openedAt, 0f, 0.22f, Ease.Curve.STANDARD);
        float width = BrandMark.wordmarkWidth(ui, WORDMARK_CAP);
        ui.pushAlpha(shown);
        ui.pushTransform(0f, Math.round((1f - shown) * 4f));
        BrandMark.wordmark(ui, Skin.snap(ui, centre - width / 2f), lockupTop, WORDMARK_CAP);
        ui.popTransform();
        ui.popAlpha();
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
        boolean pickable = MenuScenes.pickable();
        int count = pickable ? 7 : 6;
        float dockWidth = DOCK_BUTTON * count + DOCK_GAP * (count - 1);
        float y = Skin.snap(ui, ui.height() - FOOTER_HEIGHT - DOCK_BUTTON - Skin.S3);
        float x = Skin.snap(ui, column.centreX() - dockWidth / 2f);
        // One pane, fully round, holding the controls flat inside it. No
        // marker sliding under them: the control under the pointer is the
        // denser one, which says the same thing without a bar.
        Rect deck = Skin.pixelAligned(ui, new Rect(x - Skin.S2, y - Skin.S2,
                dockWidth + Skin.S4, DOCK_BUTTON + Skin.S4));
        VisualEffects.overlayShadow(ui, deck, Skin.pill(deck));
        VisualEffects.glass(ui, deck, Skin.pill(deck), DOCK_GLASS,
                Skin.RIM_TOP_LIGHT, Skin.RIM_EDGE_LIGHT);

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
        if (pickable) {
            // Only when there is a choice to make, as in the launcher.
            x += DOCK_BUTTON + DOCK_GAP;
            if (dockAction(ui, "menu.scenes", new Rect(x, y, DOCK_BUTTON, DOCK_BUTTON), Icon.LAYERS,
                    "Background")) {
                scenesOpen = true;
                scenesFade.to(1f, Gfx.clock().seconds());
            }
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
        return creditsOpen || scenesOpen || accountOpen
                || creditsFade.value(now) > 0.001f
                || scenesFade.value(now) > 0.001f
                || accountFade.value(now) > 0.001f;
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
        int face = Skin.mix(Skin.mix(MENU_GLASS, MENU_GLASS_HOVER, emphasis),
                MENU_GLASS_PRESSED, press);
        int edge = Skin.mix(Skin.RIM_EDGE, Skin.EDGE_ELEVATED, emphasis);
        // Hover is colour and border only. This used to lift the entire visual
        // by 1.5 units, including its label, as the pointer arrived.
        float lift = press * 1.2f;
        float inset = press * 0.70f;
        Rect visual = Skin.pixelAligned(ui, Rect.of(area.x + inset, area.y + lift + inset * 0.18f,
                area.right() - inset, area.bottom() + lift - inset * 0.18f));
        float radius = Skin.pill(visual);
        ui.shadow(visual.x, visual.y, visual.right(), visual.bottom(), radius,
                8f * (1f - press * 0.5f), Skin.scaleAlpha(Skin.SHADOW, 0.55f * (1f - press * 0.7f)));
        VisualEffects.glass(ui, visual, radius, face, Skin.RIM_TOP, edge);
        float chevron = 12f;
        ui.icon(Icon.CHEVRON_RIGHT,
                visual.right() - Skin.S4 - chevron,
                visual.centreY() - chevron / 2f, chevron,
                Skin.scaleAlpha(Skin.TEXT_MUTED, emphasis));
        ui.textCentred(label, visual.centreX(),
                Controls.baseline(ui, visual, Type.ROW_TITLE),
                Type.ROW_TITLE, Skin.TEXT);
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
        // Flat inside the dock's pane, as a launcher pane's controls are:
        // nothing at rest, the control fill under the pointer, the selected
        // fill while its panel is open.
        int face = Skin.mix(Skin.mix(Skin.fade(Skin.FILL_HOVER, 0f), Skin.SURFACE_HOVER,
                Math.max(state.hover, focus)), Skin.ELEVATED, Math.max(press, active && !fathom ? 1f : 0f));
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
        ui.rounded(visual.x, visual.y, visual.right(), visual.bottom(), round, face);
        float iconSize = DOCK_ICON;
        float iconX = Skin.snap(ui, visual.centreX() - iconSize / 2f);
        float iconY = Skin.snap(ui, visual.centreY() - iconSize / 2f);
        // Fathom's own entrance is the one at full strength; the rest are
        // champagne until touched.
        int iconTint = Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, selected);
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

        // The launcher's modal scrim: the photograph credited here stays in view.
        ui.fill(0f, 0f, ui.width(), ui.height(), MODAL_SCRIM);

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

        VisualEffects.glass(ui, panel, Skin.R_CARD, Skin.FLOATING,
                Skin.RIM_TOP_DENSE, Skin.RIM_EDGE_DENSE);

        float x = panel.x + pad;
        float y = panel.y + pad;
        ui.text("Credits", x, y + ui.ascent(Type.TITLE), Type.TITLE, Skin.TEXT);
        y += ui.lineHeight(Type.TITLE) + Skin.S4;

        for (String[] entry : CREDITS) {
            ui.text(entry[0], x, y + ui.ascent(Type.LABEL), Type.LABEL, Skin.TEXT_MUTED);
            y += labelLine;
            ui.text(entry[1], x, y + ui.ascent(Type.BODY_STRONG), Type.BODY_STRONG, Skin.TEXT);
            y += titleLine;
            ui.text(entry[2], x, y + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_MUTED);
            y += captionLine + Skin.S4;
        }

        for (String line : CREDITS_FOOTER) {
            ui.text(line, x, y + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_FAINT);
            y += captionLine;
        }

        // The launcher's own control, on the dense glass it was drawn for.
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
     * <p>The approved set under the launcher's names, the same tick on the one
     * in use, and the credit underneath. Offered only when the set holds more
     * than one picture ({@link MenuScenes#pickable()}).
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
        ui.fill(0f, 0f, ui.width(), ui.height(), MODAL_SCRIM);

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

        VisualEffects.glass(ui, panel, Skin.R_CARD, Skin.FLOATING,
                Skin.RIM_TOP_DENSE, Skin.RIM_EDGE_DENSE);

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
                        0xFF857750, 0xFF2A1C0E);
            } else {
                if (!MenuScenes.thumbnail(ui, scene, tile, Skin.R_CONTROL, Skin.PANEL)) {
                    ui.rounded(tile.x, tile.y, tile.right(), tile.bottom(), Skin.R_CONTROL,
                            Skin.FILL_SUNKEN);
                }
            }

            int edge = selected
                    ? Skin.TEXT
                    : Skin.mix(Skin.EDGE, Skin.EDGE_STRONG, state.hover);
            ui.outline(tile.x, tile.y, tile.right(), tile.bottom(), Skin.R_CONTROL,
                    selected ? Skin.HAIRLINE * 2f : Skin.HAIRLINE, edge);

            if (selected) {
                // The launcher's tick, in the same corner.
                float mark = 15f;
                Rect tick = new Rect(tile.right() - mark - Skin.S1, tile.y + Skin.S1, mark, mark);
                ui.rounded(tick.x, tick.y, tick.right(), tick.bottom(), Skin.pill(tick), Skin.TEXT);
                ui.icon(Icon.CHECK, tick.x + 3f, tick.y + 3f, mark - 6f, Skin.TEXT_INVERSE);
            }

            ui.text(Text.fit(ui, scene.label, Type.CAPTION, tileWidth), tile.x,
                    tile.bottom() + 2f + ui.ascent(Type.CAPTION), Type.CAPTION,
                    selected ? Skin.TEXT : Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, state.hover));

            if (state.clicked) {
                chosen = scene;
            }
        }
        y += rows * rowHeight;

        // The credit belongs where the picture is chosen, which is here.
        String credit = current.credited() ? current.credit : "";
        ui.text(Text.fit(ui, credit, Type.CAPTION, width - pad * 2f), x,
                y + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_FAINT);

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
     * and nothing when it is not. The game version is here too: this row sits
     * on the scrim's deepest band, where champagne type holds.
     */
    private void footer(Renderer ui) {
        float top = ui.height() - FOOTER_HEIGHT;
        String component = Runtime.hud() == null
                ? ""
                : Runtime.hud().launcherSession().component();
        String line = (component.isEmpty() ? "Fathom" : "Fathom " + component)
                + "  ·  Minecraft 1.8.9";
        boolean development = FabricLoader.getInstance().isDevelopmentEnvironment();
        String status = development
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
        // Deliberately no glass here. Nothing in this row can be clicked --
        // it is a build, a version and a connection state -- and a pane is the
        // shape this interface uses for things that can be. It sits on the
        // scrim's deepest band instead, which is what makes the type hold.
        float baseline = Controls.baseline(ui, bar, Type.CAPTION);
        float dotX = bar.x + Skin.S3;
        Rect statusDot = Rect.of(dotX, bar.centreY() - dot / 2f,
                dotX + dot, bar.centreY() + dot / 2f);
        // A still dot, in a status tone only when there is a status.
        boolean connected = "Connected".equals(status);
        ui.rounded(statusDot.x, statusDot.y, statusDot.right(), statusDot.bottom(), Skin.pill(statusDot),
                connected ? Skin.GOOD : Skin.TEXT_FAINT);
        float textX = dotX + dot + Skin.S2;
        ui.text(line, textX, baseline, Type.CAPTION, Skin.TEXT_MUTED);
        if (!status.isEmpty()) {
            ui.text(status, textX + lineWidth + Skin.S2, baseline, Type.CAPTION, Skin.TEXT_MUTED);
        }
    }

    /** Whether to draw the picture rather than the warm low-cost field. */
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
            } else if (accountOpen) {
                closeAccount();
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

    private static float clamp(float value, float low, float high) {
        return value < low ? low : (value > high ? high : value);
    }
}
