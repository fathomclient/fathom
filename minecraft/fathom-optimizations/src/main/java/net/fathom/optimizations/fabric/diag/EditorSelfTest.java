package net.fathom.optimizations.fabric.diag;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.hud.Anchor;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.hud.Snap;
import net.fathom.optimizations.core.hud.Widget;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.gfx.MinecraftGuiSize;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.hub.FathomHubScreen;
import net.fathom.optimizations.fabric.hud.FathomHud;
import net.fathom.optimizations.fabric.hud.HudEditorScreen;

/**
 * The HUD editor and the Mods page, used the way a player uses them, in the game.
 *
 * <p>Development only, and off unless {@code -Dfathom.qaEditor} is set; a normal
 * run reads one constant. The pointer is driven through {@link Pointer}'s
 * scripted position and primary button, so a drag here goes through exactly the
 * press, move and release a mouse does; keys go through the screens' own key
 * handlers. The few controls whose rectangles only the screen knows are pressed
 * through hooks that do exactly what the control does.
 *
 * <ul>
 *   <li>{@code run}: join {@code fathom.qaWorld} (or {@code fathom.qaServer}),
 *       switch a set of HUD modules on at their defaults, check nothing
 *       overlaps, then drag, undo, nudge, type, re-anchor, fade and resize the
 *       clock in the editor, follow its Settings button to the Mods page, check
 *       the page keeps its scroll across tabs, close everything, write what
 *       the clock ended up as, and quit.
 *   <li>{@code verify}: the next launch. Check the clock came back exactly so,
 *       put everything back, and quit.
 * </ul>
 */
public final class EditorSelfTest {

    private static final String MODE = System.getProperty("fathom.qaEditor", "").trim();
    public static final boolean ACTIVE = !MODE.isEmpty();
    private static final String WORLD = System.getProperty("fathom.qaWorld", "New World").trim();
    private static final String SERVER = System.getProperty("fathom.qaServer", "").trim();
    private static final String EXPECTED = "fathom-qa-editor.properties";
    private static final String[] MODULES = {"fps", "coordinates", "clock", "ping", "potions", "keystrokes",
        "cps", "toggle-sprint", "direction", "armour"};
    private static final String CLOCK = "clock";

    private static int step;
    private static int frame;
    private static long stepStarted;
    private static int passed;
    private static int failed;
    private static boolean finished;
    private static Boolean pausedOnFocus;
    private static final List<String> switchedOn = new ArrayList<String>();

    private static HudEditorScreen editor;
    private static Snap.Box before;
    private static float targetX;
    private static float targetY;
    private static float scrolled;

    private EditorSelfTest() {}

    /** Once per frame, before the screen draws. */
    public static void frame(MinecraftClient client) {
        if (!ACTIVE || client == null || Runtime.hud() == null || finished) {
            return;
        }
        try {
            advance(client);
        } catch (RuntimeException | AssertionError unexpected) {
            fail("self-test", "threw " + unexpected);
            unexpected.printStackTrace();
            finish(client);
        }
    }

    private static long since() {
        return (System.nanoTime() - stepStarted) / 1_000_000L;
    }

    private static void next() {
        step++;
        frame = 0;
        stepStarted = System.nanoTime();
    }

    private static void advance(MinecraftClient client) {
        FathomHud hud = Runtime.hud();
        if (step == 0) {
            stepStarted = System.nanoTime();
            step = "verify".equals(MODE) ? 900 : 1;
        }
        frame++;
        switch (step) {
            case 1:
                join(client);
                next();
                return;
            case 2:
                if (client.player == null || client.world == null || since() < 4000L) {
                    if (since() > 120_000L) {
                        fail("join", "no world after two minutes");
                        finish(client);
                    }
                    return;
                }
                pass("join", client.isInSingleplayer() ? "singleplayer" : "server " + SERVER);
                // A pause menu opened by a lost focus would replace the editor.
                pausedOnFocus = client.options.pauseOnLostFocus;
                client.options.pauseOnLostFocus = false;
                for (String id : MODULES) {
                    FathomModule module = Runtime.features().find(id);
                    if (module != null && !module.enabled()) {
                        module.setEnabled(true);
                        switchedOn.add(id);
                    }
                    Widget widget = hud.registry().find(id);
                    if (widget != null) {
                        hud.place(id, widget.defaultPlacement());
                    }
                }
                next();
                return;
            case 3: {
                if (since() < 600L) {
                    return;
                }
                MinecraftGuiSize size = MinecraftGuiSize.get(client);
                String clash = overlaps(hud, size);
                check("ten HUD modules at their defaults do not overlap", clash.isEmpty(),
                        size.width() + "x" + size.height() + (clash.isEmpty() ? "" : ": " + clash));
                // The editor opened from the Hub, as a player opens it.
                FathomNavigation.openHub(client, hud, "Quick");
                next();
                return;
            }
            case 4:
                if (since() < 900L) {
                    return;
                }
                editor = new HudEditorScreen(hud);
                FathomNavigation.push(client, editor);
                next();
                return;

            // -- a drag -----------------------------------------------------------
            case 5:
                if (since() < 1200L) {
                    return;
                }
                before = box(hud, client, CLOCK);
                MinecraftGuiSize size = MinecraftGuiSize.get(client);
                targetX = size.width() * 0.22f;
                targetY = size.height() * 0.5f;
                aim(before.x + before.width / 2f, before.y + before.height / 2f);
                Pointer.scriptPrimary(Boolean.FALSE);
                next();
                return;
            case 6:
                if (frame == 2) {
                    Pointer.scriptPrimary(Boolean.TRUE);
                } else if (frame > 2 && frame <= 14) {
                    float t = (frame - 2) / 12f;
                    float startX = before.x + before.width / 2f;
                    float startY = before.y + before.height / 2f;
                    aim(startX + (targetX - startX) * t, startY + (targetY - startY) * t);
                } else if (frame == 16) {
                    Pointer.scriptPrimary(Boolean.FALSE);
                } else if (frame > 20) {
                    Placement placed = hud.placement(CLOCK);
                    FathomHud.Positioned dropped = positioned(hud, client, CLOCK);
                    Snap.Box now = dropped.box;
                    check("drag: the clock is selected", CLOCK.equals(editor.qaSelected()), editor.qaSelected());
                    check("drag: it takes the anchor of the third it was dropped in",
                            placed.anchor() == Anchor.MIDDLE_LEFT, String.valueOf(placed.anchor()));
                    // Where the placement puts it. Once let go it is no longer
                    // fixed, so HudFlow may move it down off a widget it was
                    // dropped onto (Keystrokes, at small GUI sizes); that is
                    // the flow working, and "clear of everything" covers it.
                    float dx = now.x + now.width / 2f - targetX;
                    float dy = dropped.naturalY + now.height / 2f - targetY;
                    check("drag: it lands under the pointer (snapping aside)",
                            Math.abs(dx) <= 10f && Math.abs(dy) <= 10f, "off by " + dx + ", " + dy
                                    + (dropped.flowed ? "; flowed " + (now.y - dropped.naturalY) + " down" : ""));
                    check("drag: it was dropped clear of everything", overlapsWith(hud, client, CLOCK).isEmpty(),
                            overlapsWith(hud, client, CLOCK));
                    next();
                }
                return;

            // -- undo, nudge, type --------------------------------------------------
            case 7: {
                editor.qaUndo();
                Placement back = hud.placement(CLOCK);
                check("undo: the drag is taken back in one step",
                        back.equals(hud.registry().find(CLOCK).defaultPlacement()), String.valueOf(back));
                next();
                return;
            }
            case 8: {
                Placement start = hud.placement(CLOCK);
                for (int index = 0; index < 3; index++) {
                    editor.handleFathomKey((char) 0, Keyboard.KEY_LEFT);
                }
                Placement nudged = hud.placement(CLOCK);
                // Top right: an offset runs inward from the right edge, so
                // moving left makes it larger.
                check("arrows: three presses move it three pixels left",
                        nudged.offsetX() == start.offsetX() + 3 && nudged.offsetY() == start.offsetY(),
                        start + " -> " + nudged);
                editor.qaFocus(2);
                editor.handleFathomKey('g', Keyboard.KEY_G);
                editor.handleFathomKey('2', Keyboard.KEY_2);
                editor.handleFathomKey('5', Keyboard.KEY_5);
                editor.handleFathomKey((char) 13, Keyboard.KEY_RETURN);
                Placement typed = hud.placement(CLOCK);
                check("typing: a Y typed into the field is applied, and a letter is ignored",
                        typed.offsetY() == 25 && typed.offsetX() == nudged.offsetX(), String.valueOf(typed));
                next();
                return;
            }

            // -- anchor, fade, resize ------------------------------------------------
            case 9:
                if (since() < 200L) {
                    return;
                }
                before = box(hud, client, CLOCK);
                editor.qaAnchor(Anchor.CENTER);
                next();
                return;
            case 10: {
                if (since() < 200L) {
                    return;
                }
                Snap.Box after = box(hud, client, CLOCK);
                check("anchor: changing it keeps the widget where it is",
                        hud.placement(CLOCK).anchor() == Anchor.CENTER
                                && Math.abs(after.x - before.x) <= 1 && Math.abs(after.y - before.y) <= 1,
                        before.x + "," + before.y + " -> " + after.x + "," + after.y);
                aim(after.x + after.width / 2f, after.y + after.height / 2f);
                next();
                return;
            }
            case 11:
                if (frame == 2) {
                    Pointer.scriptWheel(-1);
                } else if (frame > 5) {
                    check("wheel: scrolling over it fades it by a tenth",
                            Math.abs(hud.placement(CLOCK).opacity() - 0.9f) < 0.001f,
                            String.valueOf(hud.placement(CLOCK).opacity()));
                    before = box(hud, client, CLOCK);
                    aim(before.right(), before.bottom());
                    next();
                }
                return;
            case 12:
                if (frame == 3) {
                    Pointer.scriptPrimary(Boolean.TRUE);
                } else if (frame > 3 && frame <= 13) {
                    aim(before.right() + 3f * (frame - 3), before.bottom());
                } else if (frame == 15) {
                    Pointer.scriptPrimary(Boolean.FALSE);
                } else if (frame > 18) {
                    float scale = hud.placement(CLOCK).scale();
                    check("resize: dragging the corner 30 pixels scales it by half", Math.abs(scale - 1.5f) < 0.051f,
                            String.valueOf(scale));
                    // What the restart has to bring back.
                    writeExpected(client, hud.placement(CLOCK));
                    next();
                }
                return;

            // -- Settings, to the module's page -----------------------------------------
            case 13:
                Pointer.script(Float.NaN, Float.NaN);
                Pointer.scriptPrimary(null);
                editor.qaSettings();
                next();
                return;
            case 14: {
                if (since() < 1500L) {
                    return;
                }
                FathomHubScreen hub = client.currentScreen instanceof FathomHubScreen
                        ? (FathomHubScreen) client.currentScreen : null;
                check("settings: the editor closes onto the Hub", hub != null,
                        client.currentScreen == null ? "no screen" : client.currentScreen.getClass().getSimpleName());
                check("settings: the Hub shows Mods with the clock's page open",
                        hub != null && "Mods".equals(hub.qaSection()) && CLOCK.equals(hub.qaOpenModule()),
                        hub == null ? "" : hub.qaSection() + " / " + hub.qaOpenModule());
                if (hub == null) {
                    finish(client);
                    return;
                }
                net.fathom.optimizations.core.ui.render.Renderer ui =
                        net.fathom.optimizations.fabric.gfx.Gfx.peekCompositor();
                float centreX = ui == null ? 600f : ui.width() * 0.55f;
                float centreY = ui == null ? 380f : ui.height() * 0.55f;
                Pointer.script(centreX, centreY);
                Pointer.scriptWheel(-6);
                next();
                return;
            }
            case 15: {
                if (since() < 800L) {
                    return;
                }
                FathomHubScreen hub = (FathomHubScreen) client.currentScreen;
                scrolled = hub.qaScroll();
                hub.qaTab(null);
                next();
                return;
            }
            case 16: {
                if (since() < 400L) {
                    return;
                }
                FathomHubScreen hub = (FathomHubScreen) client.currentScreen;
                check("tabs: All mods shows the list", hub.qaOpenModule() == null, hub.qaOpenModule());
                hub.qaTab(CLOCK);
                next();
                return;
            }
            case 17: {
                if (since() < 400L) {
                    return;
                }
                FathomHubScreen hub = (FathomHubScreen) client.currentScreen;
                check("tabs: the page comes back scrolled where it was left",
                        scrolled > 1f && Math.abs(hub.qaScroll() - scrolled) < 1f,
                        "left at " + scrolled + ", back at " + hub.qaScroll());
                hub.handleFathomKey((char) 0, Keyboard.KEY_ESCAPE);
                check("escape: a module page goes back to the list", hub.qaOpenModule() == null, hub.qaOpenModule());
                Pointer.script(Float.NaN, Float.NaN);
                step = 30;
                frame = 0;
                stepStarted = System.nanoTime();
                return;
            }

            // -- the module grid ---------------------------------------------------------
            case 30:
            case 31:
            case 32:
            case 33:
            case 34:
            case 35:
            case 36:
            case 37:
            case 38:
            case 39:
            case 40:
            case 41:
            case 42:
            case 43:
            case 44:
            case 45:
            case 46:
            case 47:
                grid(client, (FathomHubScreen) client.currentScreen);
                return;
            case 18:
                if (since() < 1200L) {
                    return;
                }
                check("the Hub closes back to the game", client.currentScreen == null,
                        client.currentScreen == null ? "" : client.currentScreen.getClass().getSimpleName());
                try {
                    hud.profiles().saveLive();
                    pass("written for the restart check", hud.profiles().liveName());
                } catch (net.fathom.optimizations.core.profile.ProfileException failure) {
                    fail("written for the restart check", failure.getMessage());
                }
                next();
                return;
            case 19:
                if (since() > 800L) {
                    finish(client);
                }
                return;

            case 900:
                verify(client, hud);
                finish(client);
                return;
            default:
                return;
        }
    }

    // -----------------------------------------------------------------------

    private static void join(MinecraftClient client) {
        if (!SERVER.isEmpty()) {
            String host = SERVER;
            int port = 25565;
            int colon = SERVER.lastIndexOf(':');
            if (colon > 0) {
                host = SERVER.substring(0, colon);
                port = Integer.parseInt(SERVER.substring(colon + 1).trim());
            }
            client.setScreen(new net.minecraft.client.gui.screen.ConnectScreen(
                    new net.minecraft.client.gui.screen.TitleScreen(), client, host, port));
            return;
        }
        client.startIntegratedServer(WORLD, WORLD, null);
    }

    // -- the module grid, as a player uses it -------------------------------------

    private static int gridTotal;
    private static boolean clockWasOn;
    private static String keyed;
    private static boolean keyedWasOn;
    private static int clickPhase;
    private static long clickAt;

    /**
     * Search, a card's body, its switch and its star by the pointer, the
     * category and state filters, then the keyboard: arrows, Space and Enter.
     * Every pointer press lands on the rectangle the card last drew that part
     * in, so a click meant for one control that reached another would show.
     */
    private static void grid(MinecraftClient client, FathomHubScreen hub) {
        if (hub == null) {
            fail("grid", "the Hub is not open");
            finish(client);
            return;
        }
        FathomModule clock = Runtime.features().find(CLOCK);
        switch (step) {
            case 30:
                if (since() < 400L) {
                    return;
                }
                gridTotal = hub.qaCards().size();
                check("grid: lists every module", gridTotal == Runtime.features().all().size()
                        && hub.qaColumns() >= 2, gridTotal + " cards in " + hub.qaColumns() + " columns");
                for (char c : CLOCK.toCharArray()) {
                    hub.handleFathomKey(c, 0);
                }
                next();
                return;
            case 31: {
                if (since() < 400L) {
                    return;
                }
                List<String> cards = hub.qaCards();
                boolean allMatch = !cards.isEmpty();
                for (String id : cards) {
                    allMatch &= Runtime.features().find(id).matches(CLOCK);
                }
                check("search: typing filters the grid to what matches",
                        cards.contains(CLOCK) && cards.size() < gridTotal && allMatch,
                        "\"" + hub.qaSearch() + "\": " + cards);
                clockWasOn = clock.enabled();
                next();
                return;
            }
            case 32:
                if (!click(hub, CLOCK, "card")) {
                    return;
                }
                check("card: clicking a module with settings opens its page", CLOCK.equals(hub.qaOpenModule()),
                        hub.qaOpenModule());
                check("card: and leaves it switched as it was", clock.enabled() == clockWasOn, "");
                hub.handleFathomKey((char) 0, Keyboard.KEY_ESCAPE);
                next();
                return;
            case 33:
                if (since() < 400L || !click(hub, CLOCK, "toggle")) {
                    return;
                }
                check("switch: a click on the switch toggles the module", clock.enabled() != clockWasOn,
                        "enabled " + clock.enabled());
                check("switch: and opens nothing", hub.qaOpenModule() == null, hub.qaOpenModule());
                next();
                return;
            case 34:
                if (!click(hub, CLOCK, "toggle")) {
                    return;
                }
                check("switch: a second click switches it back", clock.enabled() == clockWasOn, "");
                next();
                return;
            case 35:
                if (!click(hub, CLOCK, "star")) {
                    return;
                }
                check("favourite: the star pins the module", clock.favourite(), "");
                check("favourite: and neither opens nor switches it",
                        hub.qaOpenModule() == null && clock.enabled() == clockWasOn, hub.qaOpenModule());
                // Escape clears the search, one layer at a time.
                hub.handleFathomKey((char) 0, Keyboard.KEY_ESCAPE);
                next();
                return;
            case 36: {
                if (since() < 400L) {
                    return;
                }
                List<String> cards = hub.qaCards();
                boolean pinnedFirst = cards.contains(CLOCK);
                for (int index = 0; index < cards.indexOf(CLOCK); index++) {
                    pinnedFirst &= Runtime.features().find(cards.get(index)).favourite();
                }
                check("search: Escape clears it and every module is back",
                        hub.qaSearch().isEmpty() && cards.size() == gridTotal, cards.size() + " cards");
                check("favourite: a pinned module lists before the rest", pinnedFirst,
                        "clock at " + cards.indexOf(CLOCK));
                next();
                return;
            }
            case 37:
                if (!click(hub, CLOCK, "star")) {
                    return;
                }
                check("favourite: the star again unpins it", !clock.favourite(), "");
                hub.qaCategory(clock.category().label());
                next();
                return;
            case 38: {
                if (since() < 400L) {
                    return;
                }
                List<String> cards = hub.qaCards();
                boolean same = cards.contains(CLOCK);
                for (String id : cards) {
                    same &= Runtime.features().find(id).category() == clock.category();
                }
                check("category: " + clock.category().label() + " lists only its own modules",
                        same && cards.size() < gridTotal, cards.size() + " cards");
                hub.qaCategory(null);
                next();
                return;
            }
            case 39:
                if (since() < 300L) {
                    return;
                }
                check("category: All lists everything again", hub.qaCards().size() == gridTotal, "");
                hub.qaStateFilter(1);
                next();
                return;
            case 40:
            case 41: {
                if (since() < 300L) {
                    return;
                }
                boolean wantOn = step == 40;
                List<String> cards = hub.qaCards();
                boolean all = !cards.isEmpty();
                for (String id : cards) {
                    all &= Runtime.features().find(id).enabled() == wantOn;
                }
                check(wantOn ? "filter: Enabled lists only enabled modules"
                        : "filter: Disabled lists only disabled modules", all, cards.size() + " cards");
                hub.qaStateFilter(wantOn ? 2 : 0);
                next();
                return;
            }
            case 42: {
                if (since() < 300L) {
                    return;
                }
                int columns = hub.qaColumns();
                // The first arrow only shows the focus, on the card last clicked.
                hub.handleFathomKey((char) 0, Keyboard.KEY_RIGHT);
                int start = hub.qaGridFocus();
                check("keyboard: the first arrow shows the focus where it was",
                        start == hub.qaCards().indexOf(CLOCK), "on " + start);
                hub.handleFathomKey((char) 0, Keyboard.KEY_RIGHT);
                hub.handleFathomKey((char) 0, Keyboard.KEY_DOWN);
                int expected = Math.min(gridTotal - 1, start + 1 + columns);
                check("keyboard: arrows move across and down the grid", hub.qaGridFocus() == expected,
                        "on " + hub.qaGridFocus() + ", expected " + expected + " (" + columns + " columns)");
                hub.handleFathomKey((char) 0, Keyboard.KEY_LEFT);
                check("keyboard: Left steps back", hub.qaGridFocus() == expected - 1, "on " + hub.qaGridFocus());
                keyed = hub.qaCards().get(hub.qaGridFocus());
                FathomModule module = Runtime.features().find(keyed);
                keyedWasOn = module.enabled();
                hub.handleFathomKey(' ', Keyboard.KEY_SPACE);
                check("keyboard: Space switches the card it is on",
                        !module.switchable() || module.enabled() != keyedWasOn, keyed);
                hub.handleFathomKey(' ', Keyboard.KEY_SPACE);
                check("keyboard: Space again switches it back", module.enabled() == keyedWasOn, keyed);
                // Over to the clock's card, and Enter.
                int target = hub.qaCards().indexOf(CLOCK);
                while (hub.qaGridFocus() > target) {
                    hub.handleFathomKey((char) 0, Keyboard.KEY_LEFT);
                }
                while (hub.qaGridFocus() < target) {
                    hub.handleFathomKey((char) 0, Keyboard.KEY_RIGHT);
                }
                hub.handleFathomKey((char) 13, Keyboard.KEY_RETURN);
                next();
                return;
            }
            case 43:
                if (since() < 400L) {
                    return;
                }
                check("keyboard: Enter opens the card's settings", CLOCK.equals(hub.qaOpenModule()),
                        hub.qaOpenModule());
                check("keyboard: and leaves it switched as it was", clock.enabled() == clockWasOn, "");
                hub.handleFathomKey((char) 0, Keyboard.KEY_ESCAPE);
                next();
                return;
            case 44:
                if (since() < 300L) {
                    return;
                }
                check("keyboard: Escape goes back to the grid", hub.qaOpenModule() == null, hub.qaOpenModule());
                hub.beginClose();
                Pointer.script(Float.NaN, Float.NaN);
                Pointer.scriptPrimary(null);
                step = 18;
                frame = 0;
                stepStarted = System.nanoTime();
                return;
            default:
                return;
        }
    }

    /**
     * Point at a card's part, rest there so it is hovered, press and let go.
     * True once the click has landed and the screen has had a moment with it.
     */
    private static boolean click(FathomHubScreen hub, String moduleId, String part) {
        long now = System.nanoTime();
        long waited = (now - clickAt) / 1_000_000L;
        switch (clickPhase) {
            case 0: {
                Rect where = hub.qaCardPart(moduleId, part);
                if (where == null) {
                    if (since() > 3000L) {
                        fail("grid: " + moduleId + "'s " + part, "never drawn");
                        clickPhase = 0;
                        return true;
                    }
                    return false;
                }
                // A card's body: on its name, clear of every control on it.
                float x = "card".equals(part) ? where.x + 18f : where.centreX();
                float y = "card".equals(part) ? where.y + where.height * 0.6f : where.centreY();
                Pointer.script(x, y);
                clickPhase = 1;
                clickAt = now;
                return false;
            }
            case 1:
                if (waited < 250L) {
                    return false;
                }
                Pointer.scriptPrimary(Boolean.TRUE);
                clickPhase = 2;
                clickAt = now;
                return false;
            case 2:
                if (waited < 80L) {
                    return false;
                }
                Pointer.scriptPrimary(Boolean.FALSE);
                clickPhase = 3;
                clickAt = now;
                return false;
            default:
                if (waited < 350L) {
                    return false;
                }
                clickPhase = 0;
                return true;
        }
    }

    /** Put the pointer on a point in Minecraft's scaled space. */
    private static void aim(float scaledX, float scaledY) {
        float scale = editor == null ? 1f : editor.qaMcToLogical();
        Pointer.script(scaledX * scale, scaledY * scale);
    }

    private static Snap.Box box(FathomHud hud, MinecraftClient client, String id) {
        return positioned(hud, client, id).box;
    }

    private static FathomHud.Positioned positioned(FathomHud hud, MinecraftClient client, String id) {
        MinecraftGuiSize size = MinecraftGuiSize.get(client);
        for (FathomHud.Positioned each : hud.boxes(client, size.width(), size.height())) {
            if (each.widget.id().equals(id)) {
                return each;
            }
        }
        throw new AssertionError(id + " is not on screen");
    }

    private static String overlaps(FathomHud hud, MinecraftGuiSize size) {
        MinecraftClient client = MinecraftClient.getInstance();
        List<FathomHud.Positioned> all = hud.boxes(client, size.width(), size.height());
        StringBuilder out = new StringBuilder();
        for (int a = 0; a < all.size(); a++) {
            for (int b = a + 1; b < all.size(); b++) {
                String first = all.get(a).widget.id();
                String second = all.get(b).widget.id();
                if (!"crosshair".equals(first) && !"crosshair".equals(second)
                        && Snap.overlaps(all.get(a).box, all.get(b).box)) {
                    out.append(' ').append(first).append('/').append(second);
                }
            }
        }
        return out.toString().trim();
    }

    private static String overlapsWith(FathomHud hud, MinecraftClient client, String id) {
        String all = overlaps(hud, MinecraftGuiSize.get(client));
        StringBuilder out = new StringBuilder();
        for (String pair : all.split(" ")) {
            if (pair.startsWith(id + "/") || pair.endsWith("/" + id)) {
                out.append(pair).append(' ');
            }
        }
        return out.toString().trim();
    }

    // -- the restart pair ----------------------------------------------------

    private static void writeExpected(MinecraftClient client, Placement placement) {
        Properties out = new Properties();
        out.setProperty("anchor", placement.anchor().name());
        out.setProperty("x", Integer.toString(placement.offsetX()));
        out.setProperty("y", Integer.toString(placement.offsetY()));
        out.setProperty("scale", Float.toString(placement.scale()));
        out.setProperty("opacity", Float.toString(placement.opacity()));
        StringBuilder on = new StringBuilder();
        for (String id : switchedOn) {
            on.append(on.length() == 0 ? "" : ",").append(id);
        }
        out.setProperty("switchedOn", on.toString());
        try (OutputStream stream = new FileOutputStream(new File(client.runDirectory, EXPECTED))) {
            out.store(stream, "EditorSelfTest: what the next launch must find");
        } catch (IOException failure) {
            fail("expected values written", failure.getMessage());
        }
    }

    private static void verify(MinecraftClient client, FathomHud hud) {
        Properties expected = new Properties();
        File file = new File(client.runDirectory, EXPECTED);
        try (InputStream stream = new FileInputStream(file)) {
            expected.load(stream);
        } catch (IOException failure) {
            fail("restart: the run left its expectations", failure.getMessage());
            return;
        }
        Placement clock = hud.placement(CLOCK);
        check("restart: the clock's anchor came back", clock.anchor().name().equals(expected.getProperty("anchor")),
                clock.anchor().name());
        check("restart: its exact position came back",
                Integer.toString(clock.offsetX()).equals(expected.getProperty("x"))
                        && Integer.toString(clock.offsetY()).equals(expected.getProperty("y")),
                clock.offsetX() + "," + clock.offsetY());
        check("restart: its scale and opacity came back",
                Float.toString(clock.scale()).equals(expected.getProperty("scale"))
                        && Float.toString(clock.opacity()).equals(expected.getProperty("opacity")),
                clock.scale() + " / " + clock.opacity());
        // Put it all back.
        for (Widget widget : hud.registry().all()) {
            hud.place(widget.id(), widget.defaultPlacement());
        }
        for (String id : expected.getProperty("switchedOn", "").split(",")) {
            FathomModule module = id.isEmpty() ? null : Runtime.features().find(id);
            if (module != null) {
                module.setEnabled(false);
            }
        }
        try {
            hud.profiles().saveLive();
        } catch (net.fathom.optimizations.core.profile.ProfileException failure) {
            fail("restart: restoring the profile", failure.getMessage());
        }
        if (!file.delete()) {
            System.out.println("[Fathom] QA editor: could not delete " + file);
        }
    }

    // -- results -----------------------------------------------------------------

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass(name, detail);
        } else {
            fail(name, detail);
        }
    }

    private static void pass(String name, String detail) {
        passed++;
        System.out.println("[Fathom] QA editor PASS " + name
                + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static void fail(String name, String detail) {
        failed++;
        System.out.println("[Fathom] QA editor FAIL " + name
                + (detail == null || detail.isEmpty() ? "" : ": " + detail));
    }

    private static void finish(MinecraftClient client) {
        if (finished) {
            return;
        }
        finished = true;
        if (pausedOnFocus != null) {
            client.options.pauseOnLostFocus = pausedOnFocus.booleanValue();
        }
        Pointer.script(Float.NaN, Float.NaN);
        Pointer.scriptPrimary(null);
        System.out.println(String.format(Locale.ROOT, "[Fathom] QA editor (%s): %d passed, %d failed",
                MODE, passed, failed));
        client.scheduleStop();
    }
}
