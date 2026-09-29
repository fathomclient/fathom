package net.fathom.optimizations.fabric.menu;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SettingsScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.util.ScreenshotUtils;

import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.core.ui.anim.Transition;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.FathomOptimizationsClient;
import net.fathom.optimizations.fabric.gfx.FathomScreen;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.Glow;
import net.fathom.optimizations.fabric.ui.Graphite;
import net.fathom.optimizations.fabric.hud.HudEditorScreen;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * Fathom's title screen, in Graphite + Instrument.
 *
 * <pre>
 *   FATHOM
 *
 *
 *   [ Singleplayer ]
 *   [ Multiplayer  ]        (the processed capture, vignette and grain)
 *   [ Options      ]
 *   [ Quit Game    ]
 * </pre>
 *
 * <p>The backdrop is the launcher's own file: a curated Minecraft capture,
 * processed offline into near-black smoke, so the title screen and the launcher
 * match exactly and nothing is blurred at runtime. Over it, the wordmark at the
 * top left and a left-aligned column of four Light buttons, 240x36, 8dp apart,
 * vertically centred. There is no primary action here: the title screen is a
 * choice between places, and nothing on it is brighter than anything else.
 * Language and resource packs are in Options, as they are in vanilla; the Hub is
 * Right Shift.
 *
 * <p>Layout is on the 1280x720dp reference canvas, centred; on a wider window
 * the extra width stays backdrop.
 *
 * <p>The vanilla title screen draws a rotating skybox and {@code ButtonWidget}s
 * from a nine-slice texture magnified by the GUI scale. This draws at framebuffer
 * resolution and hands off to vanilla's own screens the moment a button is
 * pressed.
 */
public final class FathomMenuScreen extends Screen implements FathomScreen {

    private static final float CANVAS_WIDTH = GraphiteTokens.LAYOUT_CANVAS_WIDTH;
    private static final float CANVAS_HEIGHT = GraphiteTokens.LAYOUT_CANVAS_HEIGHT;
    /** The wordmark's margin from the canvas's top-left corner. */
    private static final float WORDMARK_MARGIN = GraphiteTokens.SPACE_8;
    /**
     * The lockup's ascender. Larger than the Hub's wordmark: at the Hub's
     * size the mark would be twelve pixels, walls two pixels thick, too small
     * to read as a shape; here its unit reaches three pixels and its corners
     * round.
     */
    private static final float WORDMARK_CAP = 16f;

    private static boolean announced;
    /** The button column: x on the canvas, and each button's size and gap. */
    private static final float COLUMN_X = GraphiteTokens.SPACE_12;
    private static final float BUTTON_WIDTH = 264f;
    private static final float BUTTON_HEIGHT = GraphiteTokens.SIZE_PRIMARY;
    private static final float BUTTON_GAP = GraphiteTokens.SPACE_1;
    /**
     * One rounded surface, rows inside it with no borders of their own (the
     * surface's rim is the only line). The 8 inset is what makes a row's small
     * radius (10) concentric with the panel's 18.
     */
    private static final float MENU_PAD = GraphiteTokens.SPACE_2;
    private static final float TRAVEL = GraphiteTokens.MOTION_TRAVEL;

    private static final String[] IDS = {"menu.single", "menu.multi", "menu.options", "menu.quit"};
    private static final String[] LABELS = {"Singleplayer", "Multiplayer", "Options", "Quit Game"};

    /** Reused because the background isolation pass runs once per rendered frame. */
    private final Runnable backgroundPass = new Runnable() {
        @Override
        public void run() {
            drawBackdrop(client);
        }
    };

    private final Controls controls = new Controls();
    private final Pointer pointer = new Pointer();
    private Transition transition;
    private int captureFrames;
    private boolean captured;
    private boolean captureTargetOpened;
    private boolean closeCaptureFinished;

    private float canvasX;
    private float canvasY;
    private Rect menuPanel;
    private final Rect[] stack = new Rect[IDS.length];

    private Screen pendingScreen;
    private boolean pendingHub;
    private boolean pendingQuit;
    private boolean closing;
    private boolean attached;
    private String keyboardActivation;
    /** The signal focus ring is for the keyboard: a pointer press does not show it. */
    private boolean keyboardFocus;

    @Override
    public void init() {
        if (!announced) {
            // Once per session, so a log says for certain which menu the
            // player was given rather than leaving it to be inferred.
            announced = true;
            net.fathom.optimizations.core.module.ModuleLog.say("main menu: Fathom's menu is showing.");
        }
        // The client initializer can run before 1.8.9 has loaded options.txt;
        // the first actual screen is the reliable repair boundary.
        FathomOptimizationsClient.repairLegacyFovCorruption();
        // Minecraft calls init again when the same screen is resized. Preserve
        // its live state in that case; removed() clears the flag, so returning
        // from a submenu still gets a fresh entrance.
        if (attached) {
            Gfx.restartClock();
            pointer.reset();
            return;
        }
        attached = true;
        Gfx.restartClock();
        float now = Gfx.clock().seconds();
        if (transition == null) {
            transition = new Transition(GraphiteTokens.MOTION_PANEL, GraphiteTokens.MOTION_CLOSE,
                    Ease.Curve.GRAPHITE, Ease.Curve.GRAPHITE);
        }
        transition.open(now);
        pendingScreen = null;
        pendingHub = false;
        pendingQuit = false;
        closing = false;
        keyboardActivation = null;
        keyboardFocus = false;
        pointer.reset();
        controls.reset();
    }

    @Override
    public void render(int ignoredMouseX, int ignoredMouseY, float ignoredDelta) {
        Gfx.isolated(backgroundPass);

        Renderer ui = Gfx.beginScreen(client, Runtime.uiScale());
        boolean handoffReady = false;
        try {
            pointer.poll(client, ui);
            controls.begin(pointer, Gfx.screenDelta(), ui);
            float now = Gfx.clock().seconds();
            layout(ui);
            draw(ui, now);
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

    private void layout(Renderer ui) {
        canvasX = Math.max(0f, (ui.width() - CANVAS_WIDTH) / 2f);
        canvasY = Math.max(0f, (ui.height() - CANVAS_HEIGHT) / 2f);
        float stackHeight = BUTTON_HEIGHT * IDS.length + BUTTON_GAP * (IDS.length - 1);
        float top = ui.height() / 2f - stackHeight / 2f;
        menuPanel = new Rect(canvasX + COLUMN_X - MENU_PAD, top - MENU_PAD,
                BUTTON_WIDTH + MENU_PAD * 2f, stackHeight + MENU_PAD * 2f);
        for (int index = 0; index < IDS.length; index++) {
            stack[index] = new Rect(canvasX + COLUMN_X, top + index * (BUTTON_HEIGHT + BUTTON_GAP),
                    BUTTON_WIDTH, BUTTON_HEIGHT);
        }
    }

    private void draw(Renderer ui, float now) {
        // Fade and rise 8dp on entry, 180ms; fade out on exit, 120ms.
        float enter = transition.enter(now);
        float exit = transition.exit(now);
        ui.pushAlpha(enter * (1f - exit));
        ui.pushTransform(0f, (1f - enter) * TRAVEL);

        // The scene's light (Glow): pooling behind the menu, and faintly behind
        // the wordmark, before either is drawn.
        Glow.behind(ui, menuPanel);
        ui.bloom(canvasX + WORDMARK_MARGIN + WORDMARK_CAP * 3.4f, canvasY + WORDMARK_MARGIN + WORDMARK_CAP / 2f,
                WORDMARK_CAP * 7f, Glow.at(GraphiteTokens.GLOW_BLOOM_ECHO_ALPHA));
        // The whole identity here, mark and word: this is the one screen that
        // is Fathom's front door. The Hub keeps the word alone, as the
        // launcher's header does.
        Graphite.lockup(ui, canvasX + WORDMARK_MARGIN, canvasY + WORDMARK_MARGIN, WORDMARK_CAP);

        // One floating glass group gives the controls presence without turning
        // four actions into four opaque cards.
        Graphite.surface(ui, menuPanel, Graphite.R_PANEL, Graphite.Material.STANDARD, true);

        for (int index = 0; index < IDS.length; index++) {
            if (button(ui, IDS[index], stack[index], LABELS[index])) {
                activate(index);
            }
        }

        ui.popTransform();
        ui.popAlpha();
    }

    /** A row inside the shared glass group; no nested material surface. */
    private boolean button(Renderer ui, String id, Rect area, String label) {
        Controls.State state = controls.state(id, area, !closing);
        // Hover and press are density only: a row never gets a rim, so the menu
        // never shows a border inside a border.
        Graphite.segment(ui, area, Math.max(state.hover, state.press), 0f);
        // The row under the pointer is lit, softly, in the scene's accent.
        Glow.current(ui, area, Graphite.R_SEGMENT, state.hover);
        ui.text(label, area.x + Graphite.PAD, Graphite.baseline(ui, area, Graphite.floor(ui, Graphite.UI)),
                Graphite.floor(ui, Graphite.UI), Graphite.TEXT);
        if (keyboardFocus && controls.focused(id)) {
            focusRing(ui, area, Graphite.R_SEGMENT);
        }
        if (state.clicked) {
            keyboardFocus = false;
            return true;
        }
        if (state.enabled && id.equals(keyboardActivation)) {
            keyboardActivation = null;
            return true;
        }
        return false;
    }

    /** The keyboard focus ring: the launcher's -- signal, two device pixels, 2dp outside, following the shape. */
    static void focusRing(Renderer ui, Rect area, float radius) {
        ui.focusRing(area.x, area.y, area.right(), area.bottom(), radius, 2f,
                net.fathom.optimizations.fabric.ui.Skin.FOCUS);
    }

    private void activate(int index) {
        switch (index) {
            case 0:
                navigate(new SelectWorldScreen(this));
                break;
            case 1:
                navigate(new MultiplayerScreen(this));
                break;
            case 2:
                navigate(new SettingsScreen(this, client.options));
                break;
            default:
                quitAfterExit();
                break;
        }
    }

    // -----------------------------------------------------------------------
    // Visual QA (development only)
    // -----------------------------------------------------------------------

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
        } else if ("nanovg".equalsIgnoreCase(target) || "nanovg-live".equalsIgnoreCase(target)) {
            // The renderer showcase. Development only: it needs the isolated
            // classpath `gradle runClient -PcaptureUi=nanovg[-live]` provides.
            client.setScreen(new net.fathom.optimizations.fabric.gfx.nanovg.RendererShowcaseScreen());
        } else {
            FathomNavigation.openHub(client, Runtime.hud());
        }
    }

    /** Thirty frames in, so the entrance has finished; then the client stops. */
    private void captureSettledFrame() {
        captureFrames++;
        if (!Boolean.getBoolean("fathom.captureMenu") || captured
                || captureFrames < Integer.getInteger("fathom.captureMenuFrame", 30)) {
            return;
        }
        captured = true;
        String name = "fathom-main-menu-" + client.width + "x" + client.height
                + (captureFrames < 30 ? "-frame" + captureFrames : "") + ".png";
        ScreenshotUtils.saveScreenshot(client.runDirectory, name,
                client.width, client.height, client.getFramebuffer());
        System.out.println("[Fathom] Menu QA: " + String.format(java.util.Locale.ROOT,
                "%.0f UI fps, %.2f ms Fathom UI",
                Float.valueOf(Gfx.frames().fps()), Float.valueOf(Gfx.frames().uiMeanMillis())) + ".");
        client.scheduleStop();
    }

    // -----------------------------------------------------------------------
    // The backdrop, for other Fathom screens opened from here
    // -----------------------------------------------------------------------

    /**
     * Draw the background the user chose in the launcher, for this screen and
     * every other Fathom screen: the title, the Hub, and Fathom-owned menu
     * backdrops. A background with a frame sequence plays it (the player holds
     * its last frame while the window is unfocused); any other shows its
     * full-resolution still, and the video decoder is released. Call inside
     * {@code Gfx.isolated}.
     */
    public static void drawBackdrop(net.minecraft.client.MinecraftClient client) {
        MenuScenes.Scene scene = MenuScenes.selected();
        if (!scene.animated) {
            MenuVideo.stop();
        }
        MenuScenes.draw(client, scene, Runtime.menuBackgroundQuality());
    }

    /** Stop background decoding when no Fathom screen is presenting it. */
    public static void stopBackdropPlayback() {
        MenuVideo.stop();
    }

    // -----------------------------------------------------------------------
    // Navigation
    // -----------------------------------------------------------------------

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
            // Right Shift opens the Hub from here too.
            openHub();
            return true;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // Nowhere above the title screen; Escape never quits the game.
            return true;
        }
        if (keyCode == Keyboard.KEY_TAB) {
            boolean backwards = Keyboard.isKeyDown(Keyboard.KEY_LSHIFT)
                    || Keyboard.isKeyDown(Keyboard.KEY_RSHIFT);
            moveFocus(backwards);
            keyboardFocus = true;
            return true;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER
                || keyCode == Keyboard.KEY_SPACE) {
            String focused = focusedAction();
            if (focused != null) {
                keyboardActivation = focused;
            }
            return true;
        }
        return false;
    }

    private void moveFocus(boolean backwards) {
        int current = -1;
        for (int index = 0; index < IDS.length; index++) {
            if (controls.focused(IDS[index])) {
                current = index;
                break;
            }
        }
        int next = current < 0
                ? (backwards ? IDS.length - 1 : 0)
                : (current + (backwards ? -1 : 1) + IDS.length) % IDS.length;
        controls.focus(IDS[next]);
    }

    private String focusedAction() {
        for (String id : IDS) {
            if (controls.focused(id)) {
                return id;
            }
        }
        return null;
    }

    @Override
    protected void keyPressed(char character, int keyCode) {
        // FrameKeyboard drains this screen's events every rendered frame.
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        // Presses are read from the device once per frame, not delivered from a
        // 20 Hz tick; calling super would hand every click to the screen twice.
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
        GraphiteBackdrop.dispose();
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
                && isVanillaTitle(screen)
                && net.minecraft.client.MinecraftClient.getInstance() != null
                && !net.minecraft.client.MinecraftClient.getInstance().isDemo();
    }

    /**
     * Vanilla's title screen, however it reached us.
     *
     * <p>Exactly {@code TitleScreen}, or a class Minecraft itself owns that is
     * one -- a patched copy loaded under the game's own package, as OptiFine
     * through OptiFabric can produce. The exact-class test alone let that copy
     * through silently, and the packaged game sat on vanilla's menu for the
     * whole session. Another mod's subclass (any other package) is still left
     * alone: replacing it would delete that mod's buttons.
     */
    static boolean isVanillaTitle(Screen screen) {
        if (screen.getClass() == TitleScreen.class) {
            return true;
        }
        return screen instanceof TitleScreen
                && screen.getClass().getName().startsWith("net.minecraft.");
    }
}
