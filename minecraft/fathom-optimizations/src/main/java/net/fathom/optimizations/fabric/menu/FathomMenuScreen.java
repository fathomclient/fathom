package net.fathom.optimizations.fabric.menu;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.SettingsScreen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.util.ScreenshotUtils;

import net.fathom.optimizations.core.module.LaunchSession;
import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.core.ui.anim.Transition;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.layout.Box;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.TextLeaf;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.bridge.LauncherAccount;
import net.fathom.optimizations.fabric.bridge.LauncherBridge;
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
 * Fathom's title screen: the launcher's Home, in the game.
 *
 * <pre>
 *   fathom                                       1.8.9  ( @ name )
 *   ╭──╮
 *   │⌂ │  home, mods, performance, profiles (the Hub's pages)
 *   ╰──╯
 *        ╭──────────────────────────────╮
 *        │ CURRENT INSTALLATION         │
 *        │ optimized                    │
 *        │ 1.8.9 · Legacy Fabric        │
 *        │ [ Singleplayer ] [Multi] [x] │
 *        │ client · preset · hub key    │
 *   ╭──╮ ╰──────────────────────────────╯
 *   │⚙ │  quick settings, options
 *   ╰──╯                                Not affiliated with Mojang or Microsoft...
 * </pre>
 *
 * <p>It used to be its own thing -- a lockup and a column of four quiet rows in
 * a panel -- and beside the launcher it read as a different product. Now it is
 * the launcher's composition with the launcher's numbers: the 56 header with
 * the wordmark and the account, the 76 rail column with two glass capsules that
 * widen to 200 under the pointer, and the 440 installation card 28 in from the
 * rail and 40 up from the foot, its Play-sized primary, its 14/14 hairline
 * footer. The glass is frosted over the scene, as the launcher's is.
 *
 * <p>Pressing something answers at once: the press shows on the frame it
 * happens, and the hand-off to Minecraft's own screen is on the next one. The
 * old 120ms fade-out before every hand-off, followed by vanilla reading every
 * save before it drew a thing, looked like a button that had not worked and
 * then a frozen game.
 */
public final class FathomMenuScreen extends Screen implements FathomScreen,
        net.fathom.optimizations.fabric.gfx.LaidOut {

    // -- the launcher's shell metrics (theme: layout.shell, layout.rail) -------
    private static final float HEADER = 56f;
    private static final float RAIL_COLUMN = 76f;
    private static final float RAIL_LEFT = GraphiteTokens.SPACE_3;
    private static final float RAIL_WIDTH = 48f;
    private static final float RAIL_TRAY = 200f;
    private static final float RAIL_ITEM = GraphiteTokens.SIZE_CONTROL;
    private static final float RAIL_INSET = (RAIL_WIDTH - RAIL_ITEM) / 2f;
    private static final float RAIL_GAP = GraphiteTokens.SPACE_1;
    /** The rail's lower group ends 56 above the bottom, as the launcher's does. */
    private static final float RAIL_FOOT = 56f;
    /** The header's controls: the launcher's 36dp capsules, 12 apart. */
    private static final float HEADER_CONTROL = GraphiteTokens.SIZE_CONTROL;
    /** The account capsule's name: 13/500 at the button tracking (theme: type.button). */
    private static final Type CAPSULE_TYPE = Graphite.UI.tracked(-0.005f);
    /** The footer's labels set no letter-spacing (Home.svelte .installation span). */
    private static final Type FACT_LABEL = Graphite.CAPTION.tracked(0f);

    // -- the installation card (Home.svelte .launch-composition) --------------
    private static final float CARD_LEFT = RAIL_COLUMN + 28f;
    private static final float CARD_BOTTOM = 40f;
    private static final float CARD_WIDTH = 440f;
    private static final float CARD_PAD = 24f;
    private static final float PRIMARY_WIDTH = GraphiteTokens.SIZE_PRIMARY_MIN_WIDTH;
    private static final float PRIMARY_HEIGHT = GraphiteTokens.SIZE_PRIMARY;
    /** The launcher's lockup: the mark and a 13px-ascender wordmark, 24 in, centred in the header. */
    private static final float WORDMARK_CAP = GraphiteTokens.WORDMARK_CAP_LAUNCHER;

    private static final float TRAVEL = GraphiteTokens.MOTION_TRAVEL;

    /** The required short notice, verbatim. The long form is in ATTRIBUTION.md. */
    static final String LEGAL = "Not affiliated with Mojang or Microsoft. Do not distribute!";

    private static final String SINGLE = "menu.single";
    private static final String MULTI = "menu.multi";
    private static final String QUIT = "menu.quit";

    /** The rail: id, label, icon, and the Hub section it opens (null: not the Hub). */
    private static final String[][] RAIL_TOP = {
        {"rail.home", "Home", "HOME", null},
        {"rail.mods", "Mods", "MODS", "Mods"},
        {"rail.performance", "Performance", "GAUGE", "Performance"},
        {"rail.profiles", "Profiles", "USER", "Profiles"},
    };
    private static final String[][] RAIL_BOTTOM = {
        {"rail.quick", "Quick settings", "SLIDERS", "Quick"},
        {"rail.options", "Options", "SETTINGS", null},
    };

    /** Tab order: the card's actions, then the rail. */
    private static final String[] FOCUS_ORDER = {
        SINGLE, MULTI, QUIT,
        "rail.mods", "rail.performance", "rail.profiles", "rail.quick", "rail.options",
    };

    /** Reused because the background isolation pass runs once per rendered frame. */
    private final Runnable backgroundPass = new Runnable() {
        @Override
        public void run() {
            drawBackdrop(client);
        }
    };

    private static boolean announced;

    private final Controls controls = new Controls();
    /** Lines of text in line boxes; one, reused every frame. */
    private final net.fathom.optimizations.core.ui.layout.TextStack stack =
            new net.fathom.optimizations.core.ui.layout.TextStack();
    /** The launcher's Play label: 15/600. Made once, not per frame. */
    private static final Type PRIMARY_TYPE = new Type(GraphiteTokens.TYPE_PRIMARY_SIZE, Type.Weight.SEMIBOLD);
    private final Pointer pointer = new Pointer();
    private Transition transition;
    private int captureFrames;
    private boolean captured;
    private boolean captureTargetOpened;
    private boolean closeCaptureFinished;

    // -- the layout: built once, laid out again only when the window, the scale
    //    or a string changes. Everything below draws and hit-tests from it.
    private final Box root = Box.stack("home");
    private final Box header = Box.row("header");
    private final Box wordmarkBox = Box.leaf("header.wordmark", new Box.Measure() {
        @Override
        public float width(Renderer ui) {
            return Graphite.lockupWidth(ui, WORDMARK_CAP);
        }

        @Override
        public float height(Renderer ui, float width) {
            return WORDMARK_CAP;
        }
    });
    private final TextLeaf version = TextLeaf.of("header.version", Graphite.SMALL, 16f);
    private final Box capsule = Box.row("header.account");
    private final Box avatarBox = Box.fixed("header.account.head", 24f, 24f);
    private final TextLeaf accountName = TextLeaf.of("header.account.name", CAPSULE_TYPE, 13f).keepCase(true);
    private final Box chevronBox = Box.fixed("header.account.chevron", 16f, 16f);
    private final Box railTop = Box.column("rail.top");
    private final Box railBottom = Box.column("rail.bottom");
    private final Box[] railTopItems = new Box[RAIL_TOP.length];
    private final Box[] railBottomItems = new Box[RAIL_BOTTOM.length];
    private final Box card = Box.column("card");
    private final TextLeaf eyebrow = TextLeaf.of("card.eyebrow", Graphite.CAPTION, 14f);
    private final TextLeaf title = TextLeaf.of("card.title", Graphite.TITLE, 26f);
    private final TextLeaf build = TextLeaf.of("card.build", Graphite.SMALL, 16f);
    private final Box primaryBox = Box.fixed("card.single", PRIMARY_WIDTH, PRIMARY_HEIGHT);
    private final Box multiBox = Box.leaf("card.multi", new Box.Measure() {
        @Override
        public float width(Renderer ui) {
            return Controls.buttonWidth(ui, "Multiplayer");
        }

        @Override
        public float height(Renderer ui, float width) {
            return PRIMARY_HEIGHT;
        }
    });
    private final Box quitBox = Box.fixed("card.quit", PRIMARY_HEIGHT, PRIMARY_HEIGHT);
    private final Box progressBox = Box.column("card.progress").height(10f);
    private final Box ruleBox = Box.column("card.rule").height(1f);
    private final Box[] factColumns = new Box[3];
    private final TextLeaf[] factLabels = new TextLeaf[3];
    private final TextLeaf[] factValues = new TextLeaf[3];
    private final TextLeaf legalLine = TextLeaf.of("legal", FACT_LABEL, 14f).keepCase(true);
    /** Floats under the account capsule; its retained children determine its height. */
    private final Box accountPanelBox = Box.column("account.panel").width(ACCOUNT_PANEL_WIDTH)
            .padding(GraphiteTokens.SPACE_4);
    private final Box accountIdentityBox = Box.row("account.identity").height(48f);
    private final Box accountFaceBox = Box.fixed("account.face", 48f, 48f);
    private final Box accountIdentityText = Box.column("account.identity.text").grow(1f);
    private final Box accountRuleBox = Box.column("account.rule")
            .height(GraphiteTokens.SPACE_4 + 1f + GraphiteTokens.SPACE_4);
    private final Box accountLabelBox = Box.column("account.label")
            .height(net.fathom.optimizations.core.ui.layout.TextStack.lineBox(Graphite.CAPTION, 1.4f)
                    + GraphiteTokens.SPACE_2);
    private final Box accountRowsBox = Box.column("account.rows").gap(GraphiteTokens.SPACE_1);
    private final Box accountRowsAfter = Box.column("account.rows.after")
            .height(GraphiteTokens.SPACE_1 + GraphiteTokens.SPACE_2);
    private final Box accountQuitBox = Box.row("account.quit").height(GraphiteTokens.SIZE_CONTROL);
    private final Box accountQuitAfter = Box.column("account.quit.after").height(GraphiteTokens.SPACE_2);
    private final Box accountStatusBox = Box.column("account.status");
    private final Box accountStatusAfter = Box.column("account.status.after").height(GraphiteTokens.SPACE_2);
    private final Box accountAddBox = Box.row("account.add").height(GraphiteTokens.SIZE_CONTROL);
    private final java.util.List<Box> accountRowBoxes = new java.util.ArrayList<Box>();
    private final java.util.List<LauncherBridge.SavedAccount> accountOthers =
            new java.util.ArrayList<LauncherBridge.SavedAccount>();
    private final java.util.List<String> accountRowIds = new java.util.ArrayList<String>();
    private boolean built;
    private static final float ACCOUNT_PANEL_WIDTH = 300f;
    private static final String[] FACT_NAMES = {"Client", "Preset", "Hub"};

    /** What was pressed, drawn as working until the hand-off. */
    private String busy;
    private Runnable handoff;
    /** Frames drawn since the press: the hand-off waits for one to be shown. */
    private int handoffFrames;
    private boolean closing;
    private boolean attached;
    private String keyboardActivation;
    /** The signal focus ring is for the keyboard: a pointer press does not show it. */
    private boolean keyboardFocus;

    /** The account panel under the header's account pill. */
    private boolean accountOpen;
    private final Motion accountPanelMotion = new Motion(0f, Timing.PANEL);
    private boolean accountPanelPresent;
    private Rect accountPill;
    private LauncherAccount account;

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
        busy = null;
        handoff = null;
        handoffFrames = 0;
        closing = false;
        keyboardActivation = null;
        keyboardFocus = false;
        pointer.reset();
        controls.reset();
        accountOpen = Boolean.getBoolean("fathom.qaAccountOpen");
        accountPanelMotion.set(accountOpen ? 1f : 0f, now);
        accountPanelPresent = accountOpen;
        account = LauncherAccount.get(client.runDirectory);
        account.check();
    }

    @Override
    public Box layoutRoot() {
        return built ? root : null;
    }

    /** Whether the page under the account panel takes input. */
    private boolean live() {
        return !closing && !accountOpen;
    }

    @Override
    public void render(int ignoredMouseX, int ignoredMouseY, float ignoredDelta) {
        // The press was drawn last frame and that frame is on screen: hand over
        // now, before drawing again, so whatever the destination does on
        // opening happens under the frame that says it is opening.
        if (handoff != null && handoffFrames > 0) {
            Runnable next = handoff;
            handoff = null;
            next.run();
            return;
        }

        Gfx.isolated(backgroundPass);

        Renderer ui = Gfx.beginScreen(client, Runtime.uiScale());
        try {
            pointer.poll(client, ui);
            controls.begin(pointer, Gfx.screenDelta(), ui);
            float now = Gfx.clock().seconds();
            accountPanelMotion.to(accountOpen ? 1f : 0f, now,
                    accountOpen ? Timing.PANEL : Timing.SCREEN_EXIT,
                    accountOpen ? Ease.Curve.STANDARD : Ease.Curve.EXIT);
            accountPanelPresent = accountOpen || accountPanelMotion.opacity(now) > 0f;
            layout(ui);
            draw(ui, now);
            controls.end();
        } finally {
            Gfx.endScreen();
        }
        if (handoff != null) {
            handoffFrames++;
            return;
        }
        captureSettledFrame();
        openCaptureTarget();
        finishCloseCapture();
    }

    // -----------------------------------------------------------------------
    // Layout
    // -----------------------------------------------------------------------

    /**
     * The launcher's Home, as a tree: the header, the rail's two groups and the
     * installation card, each from its own paddings and gaps (Home.svelte,
     * App.svelte; measured from the running launcher by desktop/proof). Built
     * once.
     */
    private void buildTree() {
        built = true;
        // Header: the wordmark 24 in, the version and the account capsule 12
        // from the right, all centred in its 56.
        header.height(HEADER).padding(24f, 0f, GraphiteTokens.SPACE_3, 0f).gap(GraphiteTokens.SPACE_3)
                .align(Box.Align.CENTRE);
        header.add(wordmarkBox);
        header.add(Box.spacer("header.space"));
        header.add(version.box());
        capsule.height(HEADER_CONTROL).padding(5f, 0f, GraphiteTokens.SPACE_3, 0f).gap(GraphiteTokens.SPACE_2)
                .align(Box.Align.CENTRE);
        capsule.add(avatarBox);
        capsule.add(accountName.box());
        capsule.add(chevronBox);
        header.add(capsule);
        Box headerSlot = Box.column("slot.header");
        headerSlot.add(header);
        root.add(headerSlot);

        // The rail: two groups, 12 in, below the header and above the foot.
        Box railSlot = Box.column("slot.rail").padding(RAIL_LEFT, HEADER + GraphiteTokens.SPACE_1, 0f, RAIL_FOOT)
                .justify(Box.Align.BETWEEN).align(Box.Align.START);
        railGroup(railTop, RAIL_TOP, railTopItems);
        railGroup(railBottom, RAIL_BOTTOM, railBottomItems);
        railSlot.add(railTop);
        railSlot.add(railBottom);
        root.add(railSlot);

        // The card: 104 in, 40 up, its heading, actions and facts in line boxes.
        Box cardSlot = Box.column("slot.card").padding(CARD_LEFT, 0f, 0f, CARD_BOTTOM)
                .justify(Box.Align.END).align(Box.Align.START);
        card.padding(CARD_PAD);
        card.add(eyebrow.box());
        card.add(Box.column("card.eyebrow.after").height(6f));
        card.add(title.box());
        card.add(Box.column("card.title.after").height(4f));
        card.add(build.box());
        card.add(Box.column("card.heading.after").height(18f));
        Box actions = Box.row("card.actions").gap(GraphiteTokens.SPACE_3).align(Box.Align.START);
        actions.add(primaryBox);
        actions.add(multiBox);
        actions.add(quitBox);
        card.add(actions);
        card.add(progressBox);
        card.add(Box.column("card.rule.before").height(14f));
        card.add(ruleBox);
        card.add(Box.column("card.rule.after").height(14f));
        Box facts = Box.row("card.facts");
        for (int index = 0; index < 3; index++) {
            factLabels[index] = TextLeaf.of("card.fact.label." + index, FACT_LABEL, 14f);
            factValues[index] = TextLeaf.of("card.fact.value." + index, Graphite.UI, 16f);
            factLabels[index].set(FACT_NAMES[index]);
            // Three equal columns (CSS grid 1fr), each 12 in on the right; the
            // second and third also 12 in on the left, past their 1px divider.
            Box column = Box.column("card.fact." + index).width(0f).grow(1f)
                    .padding(index == 0 ? 0f : 12f + 1f, 0f, 12f, 0f);
            column.add(factLabels[index].box());
            column.add(Box.column("card.fact.gap." + index).height(3f));
            column.add(factValues[index].box());
            facts.add(column);
            factColumns[index] = column;
        }
        card.add(facts);
        cardSlot.add(card);
        root.add(cardSlot);

        Box legalSlot = Box.column("slot.legal").padding(0f, 0f, GraphiteTokens.SPACE_4, GraphiteTokens.SPACE_3)
                .justify(Box.Align.END).align(Box.Align.END);
        legalSlot.add(legalLine.box());
        root.add(legalSlot);

        root.addFloating(accountPanelBox, capsule, Box.Place.BELOW_END, GraphiteTokens.SPACE_2);
        accountIdentityBox.gap(GraphiteTokens.SPACE_3).align(Box.Align.CENTRE);
        accountIdentityBox.add(accountFaceBox);
        accountIdentityBox.add(accountIdentityText);
        accountPanelBox.add(accountIdentityBox);
        accountPanelBox.add(accountRuleBox);
        accountPanelBox.add(accountLabelBox);
        accountPanelBox.add(accountRowsBox);
        accountPanelBox.add(accountRowsAfter);
        accountPanelBox.add(accountQuitBox);
        accountPanelBox.add(accountQuitAfter);
        accountPanelBox.add(accountStatusBox);
        accountPanelBox.add(accountStatusAfter);
        accountPanelBox.add(accountAddBox);
        eyebrow.set("Current installation");
        legalLine.set(LEGAL);
    }

    private void railGroup(Box group, String[][] items, Box[] boxes) {
        group.width(RAIL_WIDTH).padding(RAIL_INSET).gap(RAIL_GAP).align(Box.Align.START);
        for (int index = 0; index < items.length; index++) {
            boxes[index] = Box.fixed(items[index][0], RAIL_ITEM, RAIL_ITEM);
            group.add(boxes[index]);
        }
    }

    /** Tell the tree what the strings say now; it lays out again only if they changed. */
    private void layout(Renderer ui) {
        if (!built) {
            buildTree();
        }
        version.set(minecraftVersion());
        accountName.set(playerName());
        title.set(installationName());
        build.set(buildLine());
        factValues[0].set("Fathom");
        factValues[1].set(presetName());
        factValues[2].set(hubKey());
        card.width(Math.min(CARD_WIDTH, Math.max(280f, ui.width() - CARD_LEFT - 56f)));
        accountPanelBox.hidden(!accountPanelPresent);
        if (accountPanelPresent) {
            syncAccountLayout();
        }
        root.layout(ui, 0f, 0f, ui.width(), ui.height());
    }

    /** "1.8.9 · Legacy Fabric", kept until either part changes. */
    private String buildLine() {
        String versionText = minecraftVersion();
        String loader = loaderName();
        if (!versionText.equals(buildVersion) || !loader.equals(buildLoader)) {
            buildVersion = versionText;
            buildLoader = loader;
            buildText = versionText + " \u00b7 " + loader;
        }
        return buildText;
    }

    private String buildVersion = "";
    private String buildLoader = "";
    private String buildText = "";

    /** Line boxes as the launcher's CSS sets them: size times line height. */
    private static float line(Type type, float ratio) {
        return Math.round(type.size() * ratio);
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    private void draw(Renderer ui, float now) {
        // Fade and rise 8dp on entry, as the launcher's pages do.
        float enter = transition.enter(now);
        ui.pushAlpha(enter);
        ui.pushTransform(0f, (1f - enter) * TRAVEL);

        // The scene behind the glass, before anything is drawn over it.
        Graphite.captureFrost(ui);

        header(ui);
        rail(ui, railTop, railTopItems, RAIL_TOP, "rail.top");
        rail(ui, railBottom, railBottomItems, RAIL_BOTTOM, "rail.bottom");
        installation(ui, now);
        legal(ui);
        if (accountPanelPresent) {
            ui.pushLayer(net.fathom.optimizations.core.ui.render.Layer.POPOVER);
            float panelShown = accountPanelMotion.opacity(now);
            ui.pushAlpha(panelShown);
            ui.pushTransform(0f, (1f - panelShown) * GraphiteTokens.MOTION_TRAVEL);
            if (!accountOpen) {
                controls.setInputEnabled(false);
            }
            accountPanel(ui);
            if (!accountOpen) {
                controls.setInputEnabled(!closing);
            }
            ui.popTransform();
            ui.popAlpha();
            ui.popLayer();
        }
        // The top layer: tooltips (and any popover), over the account panel.
        // This screen registered tooltips -- "Quit game" -- and never drew them.
        controls.drawOverlays(ui);

        ui.popTransform();
        ui.popAlpha();
    }

    /** The launcher's header: wordmark at the left, version and account at the right. */
    private void header(Renderer ui) {
        Rect mark = wordmarkBox.rect();
        Graphite.lockup(ui, mark.x, Math.round(mark.y), WORDMARK_CAP);
        version.draw(ui, Graphite.TEXT_SECONDARY);

        // The launcher's account capsule: clear glass that answers the
        // pointer (material-interactive), the head, the name, a chevron.
        Rect pill = capsule.rect();
        accountPill = pill;
        Controls.State state = controls.state("account.pill", pill, !closing);
        float press = state.press;
        pressScale(ui, pill, press);
        net.fathom.optimizations.core.ui.style.Material.CLEAR.paintInteractive(ui, pill.x, pill.y, pill.right(),
                pill.bottom(), Graphite.R_CONTROL, Math.max(state.hover, accountOpen ? 1f : 0f), press, false);
        head(ui, avatarBox.rect(), accountName.text());
        accountName.draw(ui, Graphite.TEXT);
        Rect chevron = chevronBox.rect();
        ui.icon(Icon.CHEVRON_DOWN, chevron.x, chevron.y, chevron.width, Graphite.TEXT_SECONDARY);
        ui.popTransform();
        if (state.clicked) {
            accountOpen = !accountOpen;
            if (accountOpen && account != null) {
                account.clearFinishedAttempt();
                account.check();
            }
        }
    }

    /** CSS's {@code transform: scale(var(--press-scale))} while pressed, about the control's centre. */
    private static void pressScale(Renderer ui, Rect area, float press) {
        float scale = 1f - (1f - net.fathom.optimizations.core.ui.style.ControlSurface.PRESS_SCALE) * press;
        ui.pushTransform(0f, 0f, scale, area.centreX(), area.centreY());
    }

    /**
     * One of the rail's two capsules: icons, widening to show labels while the
     * pointer is over it, and only it -- the launcher's rule since its own
     * rail widened both together.
     */
    private void rail(Renderer ui, Box groupBox, Box[] itemBoxes, String[][] items, String id) {
        Rect closed = groupBox.rect();
        boolean over = live() && pointer.x() >= closed.x && pointer.y() >= closed.y
                && pointer.x() <= closed.x + RAIL_TRAY && pointer.y() <= closed.bottom()
                && (pointer.x() <= closed.right() || controls.towards(id + "#open", true, 0f) > 0.5f);
        float open = controls.towards(id + "#open", over, GraphiteTokens.MOTION_FAST);
        float wide = closed.width + (RAIL_TRAY - closed.width) * open;
        // The launcher's rail groups are its standard material (material-standard).
        net.fathom.optimizations.core.ui.style.Material.STANDARD.paint(ui, closed.x, closed.y, closed.x + wide,
                closed.bottom(), Graphite.R_PANEL);

        Type label = Graphite.floor(ui, Graphite.UI);
        for (int index = 0; index < items.length; index++) {
            String[] item = items[index];
            Rect slot = itemBoxes[index].rect();
            Rect row = open > 0.001f ? new Rect(slot.x, slot.y, wide - RAIL_INSET * 2f, slot.height) : slot;
            boolean current = "rail.home".equals(item[0]);
            Controls.State state = controls.state(item[0], row, live() && !current);
            Graphite.overlay(ui, row, Graphite.R_CONTROL, current ? 0f : Math.max(state.hover, state.press),
                    current ? 1f : 0f);
            if (current) {
                Glow.current(ui, row, Graphite.R_CONTROL, 1f);
            }
            int ink = current || state.hover > 0.5f ? Graphite.TEXT : Graphite.TEXT_SECONDARY;
            float iconSize = 20f;
            ui.icon(Icon.valueOf(item[2]), row.x + (RAIL_ITEM - iconSize) / 2f, row.centreY() - iconSize / 2f,
                    iconSize, current ? Graphite.BRAND_LIGHT : ink);
            if (open > 0.01f) {
                ui.pushAlpha(open);
                ui.text(item[1], row.x + RAIL_ITEM + GraphiteTokens.SPACE_1,
                        Graphite.baseline(ui, row, label), label, ink);
                ui.popAlpha();
            }
            if (keyboardFocus && controls.focused(item[0])) {
                focusRing(ui, row, Graphite.R_CONTROL);
            }
            if (state.clicked || (state.enabled && item[0].equals(keyboardActivation))) {
                keyboardActivation = null;
                railAction(item);
            }
        }
    }

    /** The installation card, as on the launcher's Home. */
    private void installation(Renderer ui, float now) {
        Rect area = card.rect();
        Graphite.surface(ui, area, Graphite.R_PANEL, Graphite.Material.STANDARD, true);
        eyebrow.draw(ui, Graphite.TEXT_SECONDARY);
        title.draw(ui, Graphite.TEXT);
        build.draw(ui, Graphite.TEXT_SECONDARY);

        // Singleplayer is the Play of the title screen: the bright primary.
        Rect primary = primaryBox.rect();
        String primaryLabel = SINGLE.equals(busy) ? "Opening worlds\u2026" : "Singleplayer";
        if (cardButton(ui, SINGLE, primary, primaryLabel, true) || activated(SINGLE)) {
            press(SINGLE, new Runnable() {
                @Override
                public void run() {
                    client.setScreen(new SelectWorldScreen(FathomMenuScreen.this));
                }
            });
        }
        focus(ui, SINGLE, primary, Graphite.R_CONTROL);
        if (busy != null) {
            Rect slot = progressBox.rect();
            working(ui, slot.x + 16f, slot.y + 6f, primary.width - 32f, now);
        }

        Rect multi = multiBox.rect();
        String multiLabel = MULTI.equals(busy) ? "Opening\u2026" : "Multiplayer";
        if (cardButton(ui, MULTI, multi, multiLabel, false) || activated(MULTI)) {
            press(MULTI, new Runnable() {
                @Override
                public void run() {
                    client.setScreen(new MultiplayerScreen(FathomMenuScreen.this));
                }
            });
        }
        focus(ui, MULTI, multi, Graphite.R_CONTROL);

        Rect quit = quitBox.rect();
        if (quit.right() <= card.content().right() + 0.5f) {
            // The launcher's square beside Play: light glass, the control radius.
            Controls.State state = controls.state(QUIT, quit, live());
            Graphite.surface(ui, quit, Graphite.R_CONTROL, Graphite.Material.LIGHT,
                    state.hover, state.press, 0f, false);
            float iconSize = 16f;
            ui.icon(Icon.CLOSE, quit.centreX() - iconSize / 2f, quit.centreY() - iconSize / 2f, iconSize,
                    Graphite.TEXT);
            controls.tooltip(state, quit, "Quit game");
            if (state.clicked || activated(QUIT)) {
                press(QUIT, new Runnable() {
                    @Override
                    public void run() {
                        client.scheduleStop();
                    }
                });
            }
            focus(ui, QUIT, quit, Graphite.R_CONTROL);
        }

        // The footer: three labelled facts under a hairline, dividers between.
        Rect rule = ruleBox.rect();
        Graphite.hairline(ui, rule.x, rule.right(), rule.y);
        for (int index = 0; index < 3; index++) {
            if (index > 0) {
                Rect column = factColumns[index].rect();
                ui.fill(column.x, column.y, column.x + ui.pixel(), column.bottom(), Graphite.HAIRLINE);
            }
            factLabels[index].draw(ui, Graphite.TEXT_TERTIARY);
            factValues[index].draw(ui, Graphite.TEXT);
        }
    }

    /**
     * A button of the card, in Home's own shapes: the control radius, not a
     * pill. The primary is the launcher's Play -- the action fill, its dark
     * type at 15/600, and the accent halo spilling out beneath it. The other
     * is light glass, like the square beside Play.
     */
    private boolean cardButton(Renderer ui, String id, Rect area, String label, boolean primary) {
        Controls.State state = controls.state(id, area, live());
        float radius = Graphite.R_CONTROL;
        Type type;
        int ink;
        if (primary) {
            boolean working = id.equals(busy);
            if (!working) {
                Glow.halo(ui, area, radius, 1f + 0.5f * state.hover);
            }
            int fill = working ? GraphiteTokens.MATERIAL_STANDARD_FILL
                    : net.fathom.optimizations.core.ui.Colour.mix(
                            net.fathom.optimizations.core.ui.Colour.mix(GraphiteTokens.COLOR_ACTION,
                                    GraphiteTokens.COLOR_ACTION_HOVER, state.hover),
                            GraphiteTokens.COLOR_ACTION_PRESSED, state.press);
            if (working) {
                Graphite.surface(ui, area, radius, Graphite.Material.STANDARD, false);
            } else {
                ui.rounded(area.x, area.y, area.right(), area.bottom(), radius, fill);
            }
            type = Graphite.floor(ui, PRIMARY_TYPE);
            ink = working ? Graphite.TEXT_SECONDARY : GraphiteTokens.COLOR_ACTION_TEXT;
        } else {
            Graphite.surface(ui, area, radius, Graphite.Material.LIGHT, state.hover, state.press, 0f, false);
            type = Graphite.floor(ui, Graphite.UI);
            ink = Graphite.TEXT;
        }
        ui.textCentred(Text.fit(ui, label, type, area.width - GraphiteTokens.SPACE_4), area.centreX(),
                Graphite.baseline(ui, area, type), type, ink);
        return state.clicked;
    }

    /** The account's head: the launcher's cut of the skin, or the name's initial. */
    private void head(Renderer ui, Rect face, String name) {
        int texture = 0;
        if (account != null) {
            String uuid = sessionUuid();
            texture = AccountHead.texture(account.launcherRoot(), uuid);
        }
        float radius = face.width / 2f;
        if (texture > 0 && ui instanceof net.fathom.optimizations.fabric.gfx.UiCanvas) {
            ((net.fathom.optimizations.fabric.gfx.UiCanvas) ui).roundedImage(
                    texture, face.x, face.y, face.right(), face.bottom(), radius);
        } else {
            ui.rounded(face.x, face.y, face.right(), face.bottom(), radius, 0x2EFFFFFF);
            Type initial = Graphite.floor(ui, Graphite.CAPTION.weighing(Type.Weight.SEMIBOLD)
                    .at(Math.max(Graphite.CAPTION.size(), face.height * 0.42f)));
            keepCase(ui, true);
            ui.textCentred(name.isEmpty() ? "" : name.substring(0, 1).toUpperCase(java.util.Locale.ROOT),
                    face.centreX(), Graphite.baseline(ui, face, initial), initial, Graphite.TEXT);
            keepCase(ui, false);
        }
        Graphite.rim(ui, face, radius, GraphiteTokens.COLOR_RIM_HOVER);
    }

    /**
     * Who is playing, and the other accounts the launcher remembers.
     *
     * <p>A running 1.8.9 cannot change the account it was started with, and the
     * game never holds an account's token -- the launcher does. So switching
     * asks the launcher to select that account, and then offers to quit to it:
     * the next Play is as the new account.
     */
    /** Update the account subtree only when accounts or conditional rows changed. */
    private void syncAccountLayout() {
        String current = compactSessionUuid();
        accountOthers.clear();
        LauncherBridge.SavedAccount[] saved = account == null ? null : account.savedAccounts();
        if (saved != null) {
            for (LauncherBridge.SavedAccount each : saved) {
                if (!each.uuid.equals(current)) {
                    accountOthers.add(each);
                }
            }
        }
        boolean changed = accountRowBoxes.isEmpty() || accountRowIds.size() != accountOthers.size();
        if (!changed) {
            for (int index = 0; index < accountOthers.size(); index++) {
                if (!accountOthers.get(index).uuid.equals(accountRowIds.get(index))) {
                    changed = true;
                    break;
                }
            }
        }
        if (changed) {
            accountRowsBox.children().clear();
            accountRowsBox.invalidate();
            accountRowBoxes.clear();
            accountRowIds.clear();
            int count = Math.max(1, accountOthers.size());
            for (int index = 0; index < count; index++) {
                String id = index < accountOthers.size() ? accountOthers.get(index).uuid : "empty";
                Box row = Box.row("account.row." + id).height(GraphiteTokens.SIZE_CONTROL);
                accountRowsBox.add(row);
                accountRowBoxes.add(row);
                if (index < accountOthers.size()) {
                    accountRowIds.add(id);
                }
            }
        }
        String switched = account == null ? "" : account.name();
        boolean pendingSwitch = !switched.isEmpty() && !switched.equalsIgnoreCase(playerName())
                && account.phase() == LauncherAccount.Phase.SIGNED_IN;
        float status = statusHeight();
        accountQuitBox.hidden(!pendingSwitch);
        accountQuitAfter.hidden(!pendingSwitch);
        accountStatusBox.hidden(status <= 0f).height(status);
        accountStatusAfter.hidden(status <= 0f);
    }

    private String compactSessionUuid() {
        String uuid = sessionUuid();
        if (!uuid.equals(compactSource)) {
            compactSource = uuid;
            compactUuid = uuid.replace("-", "").toLowerCase(java.util.Locale.ROOT);
        }
        return compactUuid;
    }

    private String compactSource = "";
    private String compactUuid = "";

    private void accountPanel(Renderer ui) {
        float pad = GraphiteTokens.SPACE_4;
        String current = compactSessionUuid();
        String switched = account == null ? "" : account.name();
        boolean pendingSwitch = !switched.isEmpty() && !switched.equalsIgnoreCase(playerName())
                && account.phase() == LauncherAccount.Phase.SIGNED_IN;

        float status = statusHeight();
        // Placed by the layout: under the capsule, right edges aligned, inside the screen.
        Rect panel = accountPanelBox.rect();

        // Anything outside the panel closes it; registered after the page and
        // before the panel's own controls, so neither the page nor the panel
        // sees a click that was meant for the other.
        Controls.State outside = controls.state("account.outside", root.rect(),
                !closing);
        Controls.State inside = controls.state("account.inside", panel, !closing);
        if (outside.clicked && !inside.hovered && !accountPill.contains(pointer.x(), pointer.y())) {
            accountOpen = false;
        }

        Graphite.surface(ui, panel, Graphite.R_PANEL, Graphite.Material.DENSE, true);
        float x = accountPanelBox.content().x;
        float inner = accountPanelBox.content().width;

        // Who: the head, the name, the account and its id.
        Rect face = accountFaceBox.rect();
        String name = playerName();
        head(ui, face, name);
        Type title = Graphite.floor(ui, Graphite.HEADING);
        Type small = Graphite.floor(ui, Graphite.SMALL);
        Type data = Graphite.floor(ui, Graphite.DATA_S);
        float textX = accountIdentityText.rect().x;
        float room = panel.right() - pad - textX;
        // Name, kind and id as one block, centred on the head the way the
        // launcher's account row centres its text column.
        float block = net.fathom.optimizations.core.ui.layout.TextStack.lineBox(Graphite.HEADING, 1.3f)
                + net.fathom.optimizations.core.ui.layout.TextStack.lineBox(Graphite.SMALL, 1.3f)
                + net.fathom.optimizations.core.ui.layout.TextStack.lineBox(Graphite.DATA_S, 1.3f);
        stack.begin(ui, face.centreY() - block / 2f);
        keepCase(ui, true);
        ui.text(Text.fit(ui, name.isEmpty() ? "Not signed in" : name, title, room), textX,
                stack.line(Graphite.HEADING, 1.3f, title), title, Graphite.TEXT);
        keepCase(ui, false);
        ui.text(Text.fit(ui, accountKind(), small, room), textX, stack.line(Graphite.SMALL, 1.3f, small), small,
                Graphite.TEXT_SECONDARY);
        ui.text(Text.fit(ui, shortUuid(current), data, room), textX, stack.line(Graphite.DATA_S, 1.3f, data), data,
                Graphite.TEXT_TERTIARY);
        float y = accountRuleBox.rect().centreY();
        Graphite.hairline(ui, x, x + inner, y);

        Type label = Graphite.floor(ui, Graphite.CAPTION);
        ui.text("Other accounts", x, stack.begin(ui, accountLabelBox.rect().y)
                .line(Graphite.CAPTION, 1.4f, label), label,
                Graphite.TEXT_SECONDARY);

        Type rowType = Graphite.floor(ui, Graphite.UI);
        if (accountOthers.isEmpty()) {
            ui.text("None yet. Add one below.", x, Graphite.baseline(ui, accountRowBoxes.get(0).rect(), small), small,
                    Graphite.TEXT_TERTIARY);
        }
        for (int index = 0; index < accountOthers.size(); index++) {
            LauncherBridge.SavedAccount other = accountOthers.get(index);
            Rect rowRect = accountRowBoxes.get(index).rect();
            Rect area = new Rect(rowRect.x - GraphiteTokens.SPACE_2, rowRect.y,
                    rowRect.width + GraphiteTokens.SPACE_4, rowRect.height);
            Controls.State state = controls.state("account.switch." + other.uuid, area,
                    !closing && account != null && !account.working());
            Graphite.overlay(ui, area, Graphite.R_CONTROL, Math.max(state.hover, state.press), 0f);
            Rect small24 = new Rect(x, area.centreY() - 12f, 24f, 24f);
            ui.rounded(small24.x, small24.y, small24.right(), small24.bottom(), 12f, 0x2EFFFFFF);
            Type initial = Graphite.floor(ui, Graphite.CAPTION.weighing(Type.Weight.SEMIBOLD));
            keepCase(ui, true);
            ui.textCentred(other.name.substring(0, 1).toUpperCase(java.util.Locale.ROOT), small24.centreX(),
                    Graphite.baseline(ui, small24, initial), initial, Graphite.TEXT);
            ui.text(other.name, small24.right() + GraphiteTokens.SPACE_2, Graphite.baseline(ui, area, rowType),
                    rowType, Graphite.TEXT);
            keepCase(ui, false);
            ui.textRight("Switch", area.right() - GraphiteTokens.SPACE_2, Graphite.baseline(ui, area, small),
                    small, state.hover > 0.5f ? Graphite.TEXT : Graphite.TEXT_SECONDARY);
            if (state.clicked) {
                account.switchTo(other.uuid);
            }
        }

        if (pendingSwitch) {
            // Selected in the launcher; the game still plays as who it started as.
            Rect quit = accountQuitBox.rect();
            if (cardButton(ui, "account.quit", quit, "Quit to play as " + switched, true)) {
                final net.minecraft.client.MinecraftClient game = client;
                account.openLauncher(new Runnable() {
                    @Override
                    public void run() {
                        game.scheduleStop();
                    }
                });
                accountOpen = false;
            }
        }

        // Where the attempt is: what the launcher and Microsoft are doing now.
        if (status > 0f) {
            drawStatus(ui, accountStatusBox.rect());
        }

        // Add, Cancel while Microsoft's page is open, Try again after a failure.
        LauncherAccount.Attempt attempt = account == null ? LauncherAccount.Attempt.NONE : account.attempt();
        Rect add = accountAddBox.rect();
        Controls.State addState = controls.state("account.add", add, !closing && account != null);
        Graphite.surface(ui, add, Graphite.R_CONTROL, Graphite.Material.LIGHT, addState.hover, addState.press, 0f,
                false);
        String action = attempt.active() ? "Cancel sign-in"
                : attempt == LauncherAccount.Attempt.FAILED ? "Try again" : "Add an account";
        ui.textCentred(Text.fit(ui, action, rowType, add.width - GraphiteTokens.SPACE_4), add.centreX(),
                Graphite.baseline(ui, add, rowType), rowType, Graphite.TEXT);
        if (addState.clicked) {
            if (attempt.active()) {
                account.cancelAttempt();
            } else {
                account.addAccount();
            }
        }
    }

    /** Lines of the attempt's status: the sentence, and the code while Microsoft's page asks for it. */
    private float statusHeight() {
        if (account == null || account.attempt() == LauncherAccount.Attempt.NONE || account.notice() == null) {
            return 0f;
        }
        float lines = net.fathom.optimizations.core.ui.layout.TextStack.lineBox(Graphite.SMALL, 1.4f) * 2f;
        if (!account.attemptCode().isEmpty()) {
            lines += GraphiteTokens.SPACE_1 + net.fathom.optimizations.core.ui.layout.TextStack.lineBox(
                    Graphite.DATA_L, 1.3f);
        }
        return lines;
    }

    /**
     * The attempt, in words: opening the page, waiting for Microsoft (with the
     * code to type), checking the Minecraft entitlement, done, or what went
     * wrong. Failure is in the warning ink; nothing here is a token.
     */
    private void drawStatus(Renderer ui, Rect area) {
        LauncherAccount.Attempt attempt = account.attempt();
        Type small = Graphite.floor(ui, Graphite.SMALL);
        int ink = attempt == LauncherAccount.Attempt.FAILED ? net.fathom.optimizations.fabric.ui.Skin.WARN
                : attempt == LauncherAccount.Attempt.DONE ? Graphite.TEXT : Graphite.TEXT_SECONDARY;
        java.util.List<String> lines = Text.wrap(ui, account.notice(), small, area.width);
        stack.begin(ui, area.y);
        for (int index = 0; index < 2; index++) {
            float baseline = stack.line(Graphite.SMALL, 1.4f, small);
            if (index < lines.size()) {
                String text = index == 1 && lines.size() > 2
                        ? Text.fit(ui, lines.get(1) + " " + lines.get(2), small, area.width) : lines.get(index);
                ui.text(text, area.x, baseline, small, ink);
            }
        }
        String code = account.attemptCode();
        if (!code.isEmpty()) {
            stack.gap(GraphiteTokens.SPACE_1);
            Type data = Graphite.floor(ui, Graphite.DATA_L);
            keepCase(ui, true);
            ui.text(code, area.x, stack.line(Graphite.DATA_L, 1.3f, data), data, Graphite.TEXT);
            keepCase(ui, false);
        }
    }

    private String sessionUuid() {
        try {
            String uuid = client.getSession() == null ? null : client.getSession().getUuid();
            return uuid == null ? "" : uuid;
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    /** How this game is signed in, in one phrase. */
    private String accountKind() {
        LaunchSession session = session();
        if (session == null || !session.present()) {
            return "Offline session";
        }
        return "Microsoft account";
    }

    private static String shortUuid(String compact) {
        if (compact == null || compact.length() < 12) {
            return "";
        }
        return compact.substring(0, 8) + "…" + compact.substring(compact.length() - 4);
    }

    /** The launcher's indeterminate line: a 40% segment sweeping a faint track. */
    private void working(Renderer ui, float x, float y, float width, float now) {
        float bottom = y + 2f;
        ui.rounded(x, y, x + width, bottom, 1f, Graphite.HAIRLINE);
        float phase = (now * 0.9f) % 1f;
        float segment = width * 0.4f;
        float left = x - segment + (width + segment) * phase;
        ui.clip(x, y, x + width, bottom);
        ui.rounded(left, y, left + segment, bottom, 1f, Graphite.TEXT_SECONDARY);
        ui.unclip();
    }

    /** The required notice, in its own words and its own case, bottom right. */
    private void legal(Renderer ui) {
        legalLine.draw(ui, Graphite.TEXT_TERTIARY);
    }

    private void focus(Renderer ui, String id, Rect area, float radius) {
        if (keyboardFocus && controls.focused(id)) {
            focusRing(ui, area, radius);
        }
    }

    /** The keyboard focus ring: the launcher's -- signal, two device pixels, 2dp outside, following the shape. */
    static void focusRing(Renderer ui, Rect area, float radius) {
        ui.focusRing(area.x, area.y, area.right(), area.bottom(), radius, 2f,
                net.fathom.optimizations.fabric.ui.Skin.FOCUS);
    }

    private static void keepCase(Renderer ui, boolean on) {
        if (on) {
            ui.pushKeepCase();
        } else {
            ui.popKeepCase();
        }
    }

    private static float measureKept(Renderer ui, String text, Type type) {
        keepCase(ui, true);
        float width = ui.measure(text, type);
        keepCase(ui, false);
        return width;
    }

    // -----------------------------------------------------------------------
    // What the card says
    // -----------------------------------------------------------------------

    private LaunchSession session() {
        return Runtime.bridge() == null ? null : Runtime.bridge().session();
    }

    private String installationName() {
        LaunchSession session = session();
        String name = session == null ? null : session.profileName();
        return name == null || name.trim().isEmpty() ? "Fathom" : name.trim();
    }

    private String minecraftVersion() {
        LaunchSession session = session();
        String version = session == null ? null : session.minecraft();
        return version == null || version.trim().isEmpty() ? "1.8.9" : version.trim();
    }

    private String loaderName() {
        LaunchSession session = session();
        String loader = session == null ? null : session.loader();
        return loader == null || loader.trim().isEmpty() ? "Legacy Fabric" : loader.trim();
    }

    /** The launcher's preset id as its label: "maximum-fps" is "Maximum FPS". */
    private String presetName() {
        LaunchSession session = session();
        String preset = session == null ? null : session.preset();
        if (preset == null || preset.trim().isEmpty()) {
            return "—";
        }
        StringBuilder out = new StringBuilder();
        for (String word : preset.trim().split("-")) {
            if (word.isEmpty()) {
                continue;
            }
            if (out.length() > 0) {
                out.append(' ');
            }
            if ("fps".equalsIgnoreCase(word)) {
                out.append("FPS");
            } else {
                out.append(out.length() == 0 ? Character.toUpperCase(word.charAt(0)) + word.substring(1) : word);
            }
        }
        return out.toString();
    }

    private String hubKey() {
        return Runtime.hud() == null ? "Right Shift" : Runtime.hud().hubKey().keyName();
    }

    private String playerName() {
        try {
            String name = client.getSession() == null ? null : client.getSession().getUsername();
            return name == null ? "" : name;
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    // -----------------------------------------------------------------------
    // Actions
    // -----------------------------------------------------------------------

    private boolean activated(String id) {
        if (id.equals(keyboardActivation)) {
            keyboardActivation = null;
            return true;
        }
        return false;
    }

    /**
     * Show the press now, act on the next frame. Input stops so a second press
     * cannot start a second hand-off.
     */
    private void press(String id, Runnable action) {
        if (closing) {
            return;
        }
        closing = true;
        keyboardFocus = false;
        busy = id;
        handoff = action;
        handoffFrames = 0;
        controls.setInputEnabled(false);
        controls.closePopover();
    }

    private void railAction(final String[] item) {
        if ("rail.options".equals(item[0])) {
            press(item[0], new Runnable() {
                @Override
                public void run() {
                    client.setScreen(new SettingsScreen(FathomMenuScreen.this, client.options));
                }
            });
            return;
        }
        if (item[3] != null) {
            openHub(item[0], item[3]);
        }
    }

    private void openHub(String id, final String section) {
        // Sealed on the first open, exactly as the hotkey path does: registration
        // is finished, and a module arriving while a list is being drawn is a
        // module whose settings were never loaded from disk.
        if (Runtime.hud() == null) {
            return;
        }
        Runtime.features().seal();
        press(id, new Runnable() {
            @Override
            public void run() {
                if (section == null) {
                    FathomNavigation.openHub(client, Runtime.hud());
                } else {
                    FathomNavigation.openHub(client, Runtime.hud(), section);
                }
            }
        });
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

    /**
     * Stop background decoding when no Fathom screen is presenting it, and, over
     * a world, release what only a menu draws (see {@link #releaseForPlay}).
     */
    public static void stopBackdropPlayback() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client != null && client.world != null) {
            releaseForPlay();
        } else {
            MenuVideo.stop();
        }
    }

    /**
     * A world is being played: free the menu video's frame, the picker's
     * thumbnails, the fallback poster and every background texture but the
     * selected one, which the Hub draws over the world. Render thread only.
     */
    public static void releaseForPlay() {
        MenuVideo.dispose();
        MenuScenes.keepOnly(MenuScenes.selected());
        MenuPoster.dispose();
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
            openHub("hub.key", null);
            return true;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            // Closes the account panel; otherwise there is nowhere above the
            // title screen, and Escape never quits the game.
            accountOpen = false;
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
        for (int index = 0; index < FOCUS_ORDER.length; index++) {
            if (controls.focused(FOCUS_ORDER[index])) {
                current = index;
                break;
            }
        }
        int count = FOCUS_ORDER.length;
        int next = current < 0
                ? (backwards ? count - 1 : 0)
                : (current + (backwards ? -1 : 1) + count) % count;
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
