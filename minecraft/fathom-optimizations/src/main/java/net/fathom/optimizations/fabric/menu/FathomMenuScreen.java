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
import net.fathom.optimizations.core.ui.layout.Rect;
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
import net.fathom.optimizations.fabric.gfx.GlRenderer;
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
public final class FathomMenuScreen extends Screen implements FathomScreen {

    // -- the launcher's shell metrics (theme: layout.shell, layout.rail) -------
    private static final float HEADER = 56f;
    private static final float RAIL_COLUMN = 76f;
    private static final float RAIL_LEFT = GraphiteTokens.SPACE_3;
    private static final float RAIL_WIDTH = 48f;
    private static final float RAIL_TRAY = 200f;
    private static final float RAIL_ITEM = GraphiteTokens.SIZE_CONTROL;
    private static final float RAIL_INSET = (RAIL_WIDTH - RAIL_ITEM) / 2f;
    private static final float RAIL_GAP = GraphiteTokens.SPACE_1;
    /** The rail's lower group sits above where the launcher's status row was. */
    private static final float RAIL_FOOT = 44f;

    // -- the installation card (Home.svelte .launch-composition) --------------
    private static final float CARD_LEFT = RAIL_COLUMN + 28f;
    private static final float CARD_BOTTOM = 40f;
    private static final float CARD_WIDTH = 440f;
    private static final float CARD_PAD = 24f;
    private static final float PRIMARY_WIDTH = GraphiteTokens.SIZE_PRIMARY_MIN_WIDTH;
    private static final float PRIMARY_HEIGHT = GraphiteTokens.SIZE_PRIMARY;
    /** The launcher's wordmark: a 13px ascender, 24 in, centred in the header. */
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
    private final Pointer pointer = new Pointer();
    private Transition transition;
    private int captureFrames;
    private boolean captured;
    private boolean captureTargetOpened;
    private boolean closeCaptureFinished;

    private Rect card;
    private Rect railTop;
    private Rect railBottom;

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
        account = LauncherAccount.get(client.runDirectory);
        account.check();
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

    private void layout(Renderer ui) {
        float width = Math.min(CARD_WIDTH, Math.max(280f, ui.width() - CARD_LEFT - 56f));
        float height = cardHeight(ui);
        card = new Rect(CARD_LEFT, ui.height() - CARD_BOTTOM - height, width, height);
        railTop = new Rect(RAIL_LEFT, HEADER + GraphiteTokens.SPACE_1, RAIL_WIDTH, groupHeight(RAIL_TOP.length));
        float bottomHeight = groupHeight(RAIL_BOTTOM.length);
        railBottom = new Rect(RAIL_LEFT, ui.height() - RAIL_FOOT - bottomHeight, RAIL_WIDTH, bottomHeight);
    }

    private static float groupHeight(int items) {
        return RAIL_INSET * 2f + RAIL_ITEM * items + RAIL_GAP * (items - 1);
    }

    /** Line boxes as the launcher's CSS sets them: size times line height. */
    private static float line(Type type, float ratio) {
        return Math.round(type.size() * ratio);
    }

    private static float cardHeight(Renderer ui) {
        return CARD_PAD
                + line(Graphite.CAPTION, 1.4f) + 6f            // eyebrow
                + line(Graphite.TITLE, 1.3f)                   // installation name
                + 4f + line(Graphite.SMALL, 1.4f)              // version line
                + 18f                                          // heading's margin
                + PRIMARY_HEIGHT + 10f                         // actions, progress line
                + 14f + 14f                                    // hairline footer
                + line(Graphite.CAPTION, 1.4f) + 3f + line(Graphite.UI, 1.4f)
                + CARD_PAD;
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
        rail(ui, railTop, RAIL_TOP, "rail.top");
        rail(ui, railBottom, RAIL_BOTTOM, "rail.bottom");
        installation(ui, now);
        legal(ui);
        if (accountOpen) {
            accountPanel(ui);
        }

        ui.popTransform();
        ui.popAlpha();
    }

    /** The launcher's header: wordmark at the left, version and account at the right. */
    private void header(Renderer ui) {
        float cap = WORDMARK_CAP;
        Graphite.wordmark(ui, 24f, Math.round((HEADER - cap) / 2f), cap);

        float compact = GraphiteTokens.SIZE_CONTROL_COMPACT;
        float right = ui.width() - GraphiteTokens.SPACE_3;
        float top = (HEADER - compact) / 2f;

        String name = playerName();
        Type nameType = Graphite.floor(ui, Graphite.UI);
        float avatar = 24f;
        float chevron = 14f;
        float capsuleWidth = 5f + avatar + GraphiteTokens.SPACE_2 + measureKept(ui, name, nameType)
                + GraphiteTokens.SPACE_2 + chevron + 10f;
        Rect capsule = new Rect(right - capsuleWidth, top, capsuleWidth, compact);
        accountPill = capsule;
        // The launcher's account capsule: clear glass, the head, the name, and
        // a chevron, because it opens something.
        Controls.State pill = controls.state("account.pill", capsule, !closing);
        Graphite.surface(ui, capsule, capsule.height / 2f, Graphite.Material.CLEAR,
                pill.hover, pill.press, accountOpen ? 1f : 0f, false);
        Rect face = new Rect(capsule.x + 5f, capsule.centreY() - avatar / 2f, avatar, avatar);
        head(ui, face, name);
        keepCase(ui, true);
        ui.text(name, face.right() + GraphiteTokens.SPACE_2, Graphite.baseline(ui, capsule, nameType),
                nameType, Graphite.TEXT);
        keepCase(ui, false);
        ui.icon(accountOpen ? Icon.CHEVRON_DOWN : Icon.CHEVRON_DOWN, capsule.right() - 10f - chevron,
                capsule.centreY() - chevron / 2f, chevron, Graphite.TEXT_SECONDARY);
        if (pill.clicked) {
            accountOpen = !accountOpen;
            if (accountOpen && account != null) {
                account.check();
            }
        }

        Type version = Graphite.floor(ui, Graphite.SMALL);
        ui.textRight(minecraftVersion(), capsule.x - GraphiteTokens.SPACE_3,
                Graphite.baseline(ui, capsule, version), version, Graphite.TEXT_SECONDARY);
    }

    /**
     * One of the rail's two capsules: icons, widening to show labels while the
     * pointer is over it, and only it -- the launcher's rule since its own
     * rail widened both together.
     */
    private void rail(Renderer ui, Rect closed, String[][] items, String id) {
        boolean over = live() && pointer.x() >= closed.x && pointer.y() >= closed.y
                && pointer.x() <= closed.x + RAIL_TRAY && pointer.y() <= closed.bottom()
                && (pointer.x() <= closed.right() || controls.towards(id + "#open", true, 0f) > 0.5f);
        float open = controls.towards(id + "#open", over, GraphiteTokens.MOTION_FAST);
        Rect group = new Rect(closed.x, closed.y, closed.width + (RAIL_TRAY - closed.width) * open, closed.height);
        Graphite.surface(ui, group, Graphite.R_PANEL, Graphite.Material.STANDARD, open > 0.01f);

        float y = group.y + RAIL_INSET;
        Type label = Graphite.floor(ui, Graphite.UI);
        for (String[] item : items) {
            Rect row = new Rect(group.x + RAIL_INSET, y, group.width - RAIL_INSET * 2f, RAIL_ITEM);
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
                    iconSize, ink);
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
            y += RAIL_ITEM + RAIL_GAP;
        }
    }

    /** The installation card, as on the launcher's Home. */
    private void installation(Renderer ui, float now) {
        Graphite.surface(ui, card, Graphite.R_PANEL, Graphite.Material.STANDARD, true);
        float x = card.x + CARD_PAD;
        float inner = card.width - CARD_PAD * 2f;
        float y = card.y + CARD_PAD;

        Type eyebrow = Graphite.floor(ui, Graphite.CAPTION);
        ui.text("CURRENT INSTALLATION", x, y + ui.ascent(eyebrow) + 1f, eyebrow, Graphite.TEXT_SECONDARY);
        y += line(Graphite.CAPTION, 1.4f) + 6f;

        Type title = Graphite.floor(ui, Graphite.TITLE);
        ui.text(Text.fit(ui, installationName(), title, inner), x, y + ui.ascent(title) + 2f, title, Graphite.TEXT);
        y += line(Graphite.TITLE, 1.3f) + 4f;

        Type small = Graphite.floor(ui, Graphite.SMALL);
        ui.text(minecraftVersion() + " · " + loaderName(), x, y + ui.ascent(small) + 2f, small,
                Graphite.TEXT_SECONDARY);
        y += line(Graphite.SMALL, 1.4f) + 18f;

        // Singleplayer is the Play of the title screen: the bright primary.
        Rect primary = new Rect(x, y, PRIMARY_WIDTH, PRIMARY_HEIGHT);
        String primaryLabel = SINGLE.equals(busy) ? "Opening worlds…" : "Singleplayer";
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
            working(ui, new Rect(primary.x + 16f, primary.bottom() + 6f, primary.width - 32f, 2f), now);
        }

        float gap = GraphiteTokens.SPACE_3;
        String multiLabel = MULTI.equals(busy) ? "Opening…" : "Multiplayer";
        float multiWidth = Controls.buttonWidth(ui, "Multiplayer");
        Rect multi = new Rect(primary.right() + gap, y, multiWidth, PRIMARY_HEIGHT);
        if (cardButton(ui, MULTI, multi, multiLabel, false) || activated(MULTI)) {
            press(MULTI, new Runnable() {
                @Override
                public void run() {
                    client.setScreen(new MultiplayerScreen(FathomMenuScreen.this));
                }
            });
        }
        focus(ui, MULTI, multi, Graphite.R_CONTROL);

        Rect quit = new Rect(multi.right() + gap, y, PRIMARY_HEIGHT, PRIMARY_HEIGHT);
        if (quit.right() <= card.right() - CARD_PAD + 0.5f) {
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
        y += PRIMARY_HEIGHT + 10f + 14f;

        // The footer: three labelled facts between hairlines, 14 above and below.
        Graphite.hairline(ui, x, x + inner, y - 14f);
        float column = inner / 3f;
        String[][] facts = {
            {"Client", "Fathom"},
            {"Preset", presetName()},
            {"Hub", hubKey()},
        };
        Type label = Graphite.floor(ui, Graphite.CAPTION);
        Type value = Graphite.floor(ui, Graphite.UI);
        for (int index = 0; index < facts.length; index++) {
            float cx = x + column * index + (index == 0 ? 0f : 12f);
            if (index > 0) {
                float divider = x + column * index;
                ui.fill(divider, y, divider + ui.pixel(), y + line(Graphite.CAPTION, 1.4f) + 3f
                        + line(Graphite.UI, 1.4f), Graphite.HAIRLINE);
            }
            float room = column - (index == 0 ? 12f : 24f);
            ui.text(Text.fit(ui, facts[index][0], label, room), cx, y + ui.ascent(label) + 1f, label,
                    Graphite.TEXT_TERTIARY);
            ui.text(Text.fit(ui, facts[index][1], value, room), cx,
                    y + line(Graphite.CAPTION, 1.4f) + 3f + ui.ascent(value) + 2f, value, Graphite.TEXT);
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
            type = Graphite.floor(ui, new Type(GraphiteTokens.TYPE_PRIMARY_SIZE, Type.Weight.SEMIBOLD));
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
        if (texture > 0 && ui instanceof GlRenderer) {
            ((GlRenderer) ui).roundedImage(texture, face.x, face.y, face.right(), face.bottom(), radius);
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
    private void accountPanel(Renderer ui) {
        float width = 300f;
        float pad = GraphiteTokens.SPACE_4;
        LauncherBridge.SavedAccount[] saved = account == null ? new LauncherBridge.SavedAccount[0]
                : account.savedAccounts();
        String current = sessionUuid().replace("-", "").toLowerCase(java.util.Locale.ROOT);
        java.util.List<LauncherBridge.SavedAccount> others = new java.util.ArrayList<LauncherBridge.SavedAccount>();
        for (LauncherBridge.SavedAccount each : saved) {
            if (!each.uuid.equals(current)) {
                others.add(each);
            }
        }
        String switched = account == null ? "" : account.name();
        boolean pendingSwitch = !switched.isEmpty() && !switched.equalsIgnoreCase(playerName())
                && account.phase() == LauncherAccount.Phase.SIGNED_IN;

        float row = GraphiteTokens.SIZE_CONTROL;
        float height = pad + 48f + pad + 1f + pad + line(Graphite.CAPTION, 1.4f) + GraphiteTokens.SPACE_2
                + Math.max(1, others.size()) * (row + GraphiteTokens.SPACE_1)
                + GraphiteTokens.SPACE_2 + row + (pendingSwitch ? row + GraphiteTokens.SPACE_2 : 0f) + pad;
        Rect panel = new Rect(accountPill.right() - width, accountPill.bottom() + GraphiteTokens.SPACE_2,
                width, height);

        // Anything outside the panel closes it; registered after the page and
        // before the panel's own controls, so neither the page nor the panel
        // sees a click that was meant for the other.
        Controls.State outside = controls.state("account.outside", new Rect(0f, 0f, ui.width(), ui.height()),
                !closing);
        Controls.State inside = controls.state("account.inside", panel, !closing);
        if (outside.clicked && !inside.hovered && !accountPill.contains(pointer.x(), pointer.y())) {
            accountOpen = false;
        }

        Graphite.surface(ui, panel, Graphite.R_PANEL, Graphite.Material.DENSE, true);
        float x = panel.x + pad;
        float inner = panel.width - pad * 2f;
        float y = panel.y + pad;

        // Who: the head, the name, the account and its id.
        Rect face = new Rect(x, y, 48f, 48f);
        String name = playerName();
        head(ui, face, name);
        Type title = Graphite.floor(ui, Graphite.HEADING);
        Type small = Graphite.floor(ui, Graphite.SMALL);
        Type data = Graphite.floor(ui, Graphite.DATA_S);
        float textX = face.right() + GraphiteTokens.SPACE_3;
        float room = panel.right() - pad - textX;
        keepCase(ui, true);
        ui.text(Text.fit(ui, name.isEmpty() ? "Not signed in" : name, title, room), textX,
                y + ui.ascent(title) + 2f, title, Graphite.TEXT);
        keepCase(ui, false);
        ui.text(Text.fit(ui, accountKind(), small, room), textX, y + 22f + ui.ascent(small), small,
                Graphite.TEXT_SECONDARY);
        ui.text(Text.fit(ui, shortUuid(current), data, room), textX, y + 38f + ui.ascent(data), data,
                Graphite.TEXT_TERTIARY);
        y += 48f + pad;
        Graphite.hairline(ui, x, x + inner, y);
        y += 1f + pad;

        Type label = Graphite.floor(ui, Graphite.CAPTION);
        ui.text("Other accounts", x, y + ui.ascent(label) + 1f, label, Graphite.TEXT_SECONDARY);
        y += line(Graphite.CAPTION, 1.4f) + GraphiteTokens.SPACE_2;

        Type rowType = Graphite.floor(ui, Graphite.UI);
        if (others.isEmpty()) {
            ui.text("None yet. Add one below.", x, y + (row + ui.ascent(small) - ui.descent(small)) / 2f, small,
                    Graphite.TEXT_TERTIARY);
            y += row + GraphiteTokens.SPACE_1;
        }
        for (LauncherBridge.SavedAccount other : others) {
            Rect area = new Rect(x - GraphiteTokens.SPACE_2, y, inner + GraphiteTokens.SPACE_4, row);
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
            y += row + GraphiteTokens.SPACE_1;
        }
        y += GraphiteTokens.SPACE_2;

        if (pendingSwitch) {
            // Selected in the launcher; the game still plays as who it started as.
            Rect quit = new Rect(x, y, inner, row);
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
            y += row + GraphiteTokens.SPACE_2;
        }

        Rect add = new Rect(x, y, inner, row);
        Controls.State addState = controls.state("account.add", add, !closing && account != null);
        Graphite.surface(ui, add, Graphite.R_CONTROL, Graphite.Material.LIGHT, addState.hover, addState.press, 0f,
                false);
        String notice = account == null ? null : account.notice();
        ui.textCentred(Text.fit(ui, notice != null && account.working() ? notice : "Add an account", rowType,
                add.width - GraphiteTokens.SPACE_4), add.centreX(), Graphite.baseline(ui, add, rowType), rowType,
                Graphite.TEXT);
        if (addState.clicked) {
            account.signIn();
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
    private void working(Renderer ui, Rect track, float now) {
        ui.rounded(track.x, track.y, track.right(), track.bottom(), 1f, Graphite.HAIRLINE);
        float phase = (now * 0.9f) % 1f;
        float segment = track.width * 0.4f;
        float left = track.x - segment + (track.width + segment) * phase;
        ui.clip(track.x, track.y, track.right(), track.bottom());
        ui.rounded(left, track.y, left + segment, track.bottom(), 1f, Graphite.TEXT_SECONDARY);
        ui.unclip();
    }

    /** The required notice, in its own words and its own case, bottom right. */
    private void legal(Renderer ui) {
        Type caption = Graphite.floor(ui, Graphite.CAPTION);
        keepCase(ui, true);
        ui.textRight(LEGAL, ui.width() - GraphiteTokens.SPACE_4, ui.height() - GraphiteTokens.SPACE_4,
                caption, Graphite.TEXT_TERTIARY);
        keepCase(ui, false);
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
        if (ui instanceof GlRenderer) {
            if (on) {
                ((GlRenderer) ui).pushKeepCase();
            } else {
                ((GlRenderer) ui).popKeepCase();
            }
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
