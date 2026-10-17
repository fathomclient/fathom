package net.fathom.optimizations.fabric.diag;

import java.io.File;
import java.lang.management.ManagementFactory;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.lwjgl.opengl.Display;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ChatScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.SurvivalInventoryScreen;
import net.minecraft.client.option.KeyBinding;

import net.fathom.optimizations.core.input.HubKeyPolicy;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.MinecraftGuiSize;
import net.fathom.optimizations.fabric.hud.FathomHud;
import net.fathom.optimizations.fabric.hud.ScoreboardWidget;
import net.fathom.optimizations.fabric.hud.WidgetProfile;
import net.fathom.optimizations.fabric.hud.Widgets;
import net.fathom.optimizations.fabric.hud.Zoom;
import net.fathom.optimizations.fabric.input.MovementToggles;
import net.fathom.optimizations.fabric.input.ScreenKinds;
import net.fathom.optimizations.fabric.visual.WorldLighting;

/**
 * The competitive modules, exercised inside a running game.
 *
 * <h2>What the unit tests cannot reach</h2>
 *
 * <p>The core tests prove the arithmetic: the toggle latch, the zoom curve, the
 * click window, the profile round trip. They cannot prove that the latch is
 * applied where vanilla reads the key, that zooming leaves {@code options.txt}
 * alone, that a menu stops the clicks counting, that leaving a server clears
 * what was read from it, or that the HUD pass hands GL back the way it found
 * it. This does, in the game, through the same hooks a player's input goes
 * through, and prints one line per check.
 *
 * <h2>How it is run</h2>
 *
 * <ul>
 *   <li>{@code -Dfathom.qaModules=run}: join a world (the save in
 *       {@code fathom.qaWorld}, or the server in {@code fathom.qaServer}), run
 *       every check, leave with vanilla's own Disconnect sequence, join again,
 *       check what must have been cleared, write a fixed set of settings to the
 *       live profile, and quit;
 *   <li>{@code -Dfathom.qaModules=verify}: the next launch. Check those settings
 *       came back, put them back to their defaults, and quit.
 * </ul>
 *
 * <p>Off unless asked for: a normal run reads one constant and returns.
 */
public final class ModuleSelfTest {

    private static final String MODE = System.getProperty("fathom.qaModules", "").trim();
    public static final boolean ACTIVE = !MODE.isEmpty();
    private static final String WORLD = System.getProperty("fathom.qaWorld", "New World").trim();
    private static final String SERVER = System.getProperty("fathom.qaServer", "").trim();

    private static final List<String> RESULTS = new ArrayList<String>();
    private static int passed;
    private static int failed;
    private static int skipped;

    private static int step;
    private static long stepStarted;
    private static boolean entered;
    private static int sessionBeforeLeave;
    private static String joinedWorld;

    // Measurement windows, filled by the HUD hooks.
    private static boolean measuringAllocation;
    private static long allocatedBytes;
    private static long allocationFrames;
    private static long allocationOverhead = -1;
    private static boolean checkingGl;
    private static int glFrames;
    private static final Set<String> GL_FINDINGS = new LinkedHashSet<String>();

    private ModuleSelfTest() {}

    /**
     * Whether the self-test is standing in for window focus. The test window
     * usually sits behind whatever started it, and Zoom and the click counter
     * rightly do nothing in a window without focus; this lets the checks that
     * are not about focus run anyway. False in every normal run.
     */
    private static boolean assumeFocus;

    public static boolean focusAssumed() {
        return assumeFocus;
    }

    /** Per-widget allocation while measuring, so a failure says where. */
    private static final Map<String, long[]> WIDGET_ALLOCATION = new java.util.TreeMap<String, long[]>();

    public static void widgetAllocated(String id, long mark) {
        if (mark < 0L) {
            return;
        }
        long now = threadAllocated();
        long[] total = WIDGET_ALLOCATION.get(id);
        if (total == null) {
            total = new long[2];
            WIDGET_ALLOCATION.put(id, total);
        }
        total[0] += Math.max(0L, now - mark - Math.max(0L, allocationOverhead));
        total[1]++;
    }

    // -----------------------------------------------------------------------
    // Hooks
    // -----------------------------------------------------------------------

    /** Whether the HUD hooks should snapshot GL around their drawing this frame. */
    public static boolean glChecking() {
        return ACTIVE && checkingGl;
    }

    /** Before and after one HUD hook's drawing: anything left changed, or a cache left lying. */
    public static void glChecked(String where, Map<String, String> before, List<String> desyncBefore) {
        if (!glChecking()) {
            return;
        }
        Map<String, String> after = GlLeakCheck.snapshot();
        for (String change : GlLeakCheck.diff(before, after)) {
            GL_FINDINGS.add(where + ": " + change);
        }
        for (String lie : GlLeakCheck.desync()) {
            if (!desyncBefore.contains(lie)) {
                GL_FINDINGS.add(where + ": " + lie);
            }
        }
        if ("hud".equals(where)) {
            glFrames++;
        }
    }

    /** Bytes this thread has allocated so far, or -1 when the JVM cannot say. */
    public static long allocationMark() {
        if (!ACTIVE || !measuringAllocation) {
            return -1L;
        }
        return threadAllocated();
    }

    /** Allocation across vanilla's whole HUD and Fathom's, for comparing a vanilla piece with Fathom's. */
    private static boolean measuringWholeHud;
    private static long wholeHudBytes;
    private static long wholeHudFrames;

    public static long wholeHudMark() {
        return measuringWholeHud ? threadAllocated() : -1L;
    }

    public static void wholeHudDone(long mark) {
        if (mark < 0L || !measuringWholeHud) {
            return;
        }
        wholeHudBytes += Math.max(0L, threadAllocated() - mark);
        wholeHudFrames++;
    }

    private static void startWholeHud() {
        wholeHudBytes = 0L;
        wholeHudFrames = 0L;
        measuringWholeHud = true;
    }

    /** Bytes a frame since {@link #startWholeHud}, and stop. */
    private static long stopWholeHud() {
        measuringWholeHud = false;
        return wholeHudFrames == 0L ? -1L : wholeHudBytes / wholeHudFrames;
    }

    public static void allocationDone(long mark) {
        if (mark < 0L) {
            return;
        }
        long now = threadAllocated();
        if (now < 0L) {
            return;
        }
        allocatedBytes += Math.max(0L, now - mark - Math.max(0L, allocationOverhead));
        allocationFrames++;
    }

    private static com.sun.management.ThreadMXBean bean;
    private static long renderThread = -1L;

    private static long threadAllocated() {
        if (bean == null) {
            try {
                java.lang.management.ThreadMXBean found = ManagementFactory.getThreadMXBean();
                if (!(found instanceof com.sun.management.ThreadMXBean)) {
                    return -1L;
                }
                bean = (com.sun.management.ThreadMXBean) found;
                renderThread = Thread.currentThread().getId();
            } catch (RuntimeException | LinkageError unsupported) {
                return -1L;
            }
        }
        return bean.getThreadAllocatedBytes(renderThread);
    }

    // -----------------------------------------------------------------------
    // The script
    // -----------------------------------------------------------------------

    /** Called once per frame. */
    public static void frame(MinecraftClient client) {
        if (!ACTIVE || client == null || Runtime.hud() == null) {
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
        stepStarted = System.nanoTime();
    }

    private static FathomModule module(String id) {
        return Runtime.features().find(id);
    }

    private static void advance(MinecraftClient client) {
        if (step == 0) {
            stepStarted = System.nanoTime();
            step = "verify".equals(MODE) ? 900 : 1;
        }
        switch (step) {
            // -- joining ----------------------------------------------------------
            case 1:
                join(client);
                next();
                return;
            case 2:
                if (client.player == null || client.world == null) {
                    if (since() > 120_000L) {
                        fail("join", "no world after two minutes");
                        finish(client);
                    }
                    return;
                }
                if (since() < 4000L) {
                    return;
                }
                entered = true;
                joinedWorld = client.isInSingleplayer() && client.getServer() != null
                        ? client.getServer().getLevelName() : null;
                client.player.abilities.flying = false;
                assumeFocus = true;
                // The test window usually sits behind whatever started it, and
                // vanilla pauses -- opens the pause menu -- when it loses focus,
                // which every in-game check would then rightly refuse to act
                // behind. Put back in finish().
                pausedOnFocus = client.options.pauseOnLostFocus;
                client.options.pauseOnLostFocus = false;
                prepareModules();
                pass("join", client.isInSingleplayer() ? "singleplayer" : "server "
                        + (client.getCurrentServerEntry() == null ? "?" : client.getCurrentServerEntry().address));
                next();
                return;

            // -- cost: allocation and GL ---------------------------------------------
            case 3:
                calibrateAllocation();
                measuringAllocation = true;
                allocatedBytes = 0L;
                allocationFrames = 0L;
                WIDGET_ALLOCATION.clear();
                next();
                return;
            case 4:
                if (since() < 3000L) {
                    return;
                }
                measuringAllocation = false;
                say("allocation calibration: " + allocationOverhead + " bytes per mark");
                if (allocationOverhead < 0L || allocationFrames == 0L) {
                    skip("HUD allocation", "this JVM does not report per-thread allocation");
                } else {
                    long perFrame = allocatedBytes / allocationFrames;
                    StringBuilder where = new StringBuilder();
                    for (Map.Entry<String, long[]> entry : WIDGET_ALLOCATION.entrySet()) {
                        if (entry.getValue()[1] > 0 && entry.getValue()[0] / entry.getValue()[1] > 0) {
                            where.append(where.length() == 0 ? "; " : ", ").append(entry.getKey()).append(' ')
                                    .append(entry.getValue()[0] / entry.getValue()[1]);
                        }
                    }
                    long perSecond = allocatedBytes * 1000L / Math.max(1L, since());
                    String detail = perFrame + " bytes per HUD frame (" + perSecond + " bytes a second) over "
                            + allocationFrames + " frames, "
                            + Runtime.hud().drawnLastFrame() + " widgets" + where;
                    if (perFrame <= 256L) {
                        pass("HUD allocation", detail);
                    } else {
                        fail("HUD allocation", detail);
                    }
                    if ("alloc".equals(MODE)) {
                        finish(client);
                        return;
                    }
                }
                checkingGl = true;
                glFrames = 0;
                GL_FINDINGS.clear();
                next();
                return;
            case 5:
                if (since() < 1500L) {
                    return;
                }
                checkingGl = false;
                if (glFrames == 0) {
                    fail("GL state", "the HUD pass never ran while checking");
                } else if (GL_FINDINGS.isEmpty()) {
                    pass("GL state", glFrames + " HUD frames: nothing left changed, no cache left lying");
                } else {
                    fail("GL state", GL_FINDINGS.toString());
                }
                next();
                return;

            // -- zoom ---------------------------------------------------------------------
            case 6: {
                rememberOptions(client);
                Zoom.qaHeld = Boolean.TRUE;
                next();
                return;
            }
            case 7:
                if (since() < 600L) {
                    return;
                }
                check("zoom hold", Zoom.active() && Zoom.current() > 1.5, "divisor " + Zoom.current());
                check("zoom leaves options alone while zoomed", optionsUnchanged(client), optionsNow(client));
                next();
                return;
            case 8: {
                double before = Zoom.current();
                boolean took = Zoom.scroll(1);
                check("zoom scroll is taken while zoomed", took, "");
                stash = before;
                next();
                return;
            }
            case 9:
                if (since() < 400L) {
                    return;
                }
                check("zoom scroll zooms further", Zoom.current() > stash, stash + " -> " + Zoom.current());
                Zoom.qaHeld = Boolean.FALSE;
                next();
                return;
            case 10:
                if (since() < 600L) {
                    return;
                }
                check("zoom releases", !Zoom.active() && Zoom.current() == 1.0, "divisor " + Zoom.current());
                check("zoom restores everything", optionsUnchanged(client), optionsNow(client));
                check("zoom scroll is left to the hotbar when not zoomed", !Zoom.scroll(1), "");
                setChoice("zoom", "mode", "Toggle");
                Zoom.qaHeld = Boolean.TRUE;
                next();
                return;

            case 11:
                // One press, then let go: toggle mode latches it.
                if (since() < 100L) {
                    return;
                }
                Zoom.qaHeld = Boolean.FALSE;
                next();
                return;
            case 12:
                if (since() < 600L) {
                    return;
                }
                check("zoom toggle latches on a press", Zoom.active(), "divisor " + Zoom.current());
                client.setScreen(new SurvivalInventoryScreen(client.player));
                next();
                return;
            case 13:
                if (since() < 600L) {
                    return;
                }
                check("zoom toggle lets go when a screen opens", !Zoom.active(), "divisor " + Zoom.current());
                client.setScreen(null);
                next();
                return;
            case 14:
                if (since() < 600L) {
                    return;
                }
                check("zoom stays out after the screen closes", !Zoom.active(), "divisor " + Zoom.current());
                check("zoom toggle leaves options alone", optionsUnchanged(client), optionsNow(client));
                setChoice("zoom", "mode", "Hold");
                // Held, but in a window without focus: no zoom.
                assumeFocus = false;
                Zoom.qaHeld = Boolean.TRUE;
                step = 140;
                stepStarted = System.nanoTime();
                return;
            case 140:
                if (since() < 600L) {
                    return;
                }
                check("zoom does nothing in a window without focus", Display.isActive() || !Zoom.active(),
                        "divisor " + Zoom.current());
                Zoom.qaHeld = null;
                assumeFocus = true;
                step = 15;
                stepStarted = System.nanoTime();
                return;

            // -- toggle sneak ---------------------------------------------------------
            case 15:
                setChoice("toggle-sprint", "sneak.mode", "Toggle");
                setChoice("toggle-sprint", "sprint.mode", "Toggle");
                press(client.options.sneakKey, true);
                next();
                return;
            case 16:
                if (since() < 200L) {
                    return;
                }
                press(client.options.sneakKey, false);
                next();
                return;
            case 17:
                if (since() < 300L) {
                    return;
                }
                check("toggle sneak latches on a press", MovementToggles.sneakLatched()
                        && client.player.isSneaking(), "latched " + MovementToggles.sneakLatched()
                        + ", sneaking " + client.player.isSneaking());
                press(client.options.sneakKey, true);
                next();
                return;
            case 18:
                if (since() < 200L) {
                    return;
                }
                press(client.options.sneakKey, false);
                next();
                return;
            case 19:
                if (since() < 300L) {
                    return;
                }
                check("toggle sneak lets go on the next press", !MovementToggles.sneakLatched()
                        && !client.player.isSneaking(), "latched " + MovementToggles.sneakLatched()
                        + ", sneaking " + client.player.isSneaking());
                // Toggle sprint: walking forward, one press of the sprint key.
                // Facing open ground first: vanilla ends a sprint against a
                // wall, and the save may have left the player facing one.
                boxedIn = !faceOpenGround(client);
                press(client.options.forwardKey, true);
                press(client.options.sprintKey, true);
                next();
                return;
            case 20:
                if (since() < 200L) {
                    return;
                }
                press(client.options.sprintKey, false);
                next();
                return;
            case 21:
                if (since() < 500L) {
                    return;
                }
                if (boxedIn) {
                    skip("toggle sprint latches and sprints", "the save left the player walled in on every side, at "
                            + Math.round(client.player.x) + " " + Math.round(client.player.y) + " "
                            + Math.round(client.player.z));
                } else {
                    check("toggle sprint latches and sprints", MovementToggles.sprintLatched()
                            && client.player.isSprinting(), "latched " + MovementToggles.sprintLatched()
                            + ", sprinting " + client.player.isSprinting()
                            // Vanilla ends a sprint against a wall or in water:
                            // say so, so a failure from where the save left the
                            // player is not mistaken for the toggle's.
                            + ", against a block " + client.player.horizontalCollision
                            + ", in water " + client.player.isTouchingWater()
                            + ", at " + Math.round(client.player.x) + " " + Math.round(client.player.y)
                            + " " + Math.round(client.player.z));
                }
                press(client.options.forwardKey, false);
                next();
                return;
            case 22:
                if (since() < 500L) {
                    return;
                }
                check("toggle sprint stays latched standing still", MovementToggles.sprintLatched()
                        && !client.player.isSprinting(), "latched " + MovementToggles.sprintLatched()
                        + ", sprinting " + client.player.isSprinting());
                press(client.options.sprintKey, true);
                next();
                return;
            case 23:
                if (since() < 200L) {
                    return;
                }
                press(client.options.sprintKey, false);
                next();
                return;
            case 24:
                if (since() < 300L) {
                    return;
                }
                check("toggle sprint lets go on the next press", !MovementToggles.sprintLatched(), "");
                // Latched again, for the disconnect check later.
                press(client.options.sneakKey, true);
                next();
                return;
            case 25:
                if (since() < 200L) {
                    return;
                }
                press(client.options.sneakKey, false);
                next();
                return;

            // -- clicks, menus and visibility -----------------------------------------
            case 26: {
                int before = Runtime.hud().clicks().left();
                for (int click = 0; click < 4; click++) {
                    KeyBinding.onKeyPressed(client.options.attackKey.getCode());
                }
                stash = before;
                next();
                return;
            }
            case 27:
                if (since() < 100L) {
                    return;
                }
                check("clicks are counted in game", Runtime.hud().clicks().left() >= stash + 4,
                        stash + " -> " + Runtime.hud().clicks().left());
                client.setScreen(new SurvivalInventoryScreen(client.player));
                next();
                return;
            case 28: {
                if (since() < 300L) {
                    return;
                }
                Screen screen = client.currentScreen;
                check("a container is a container to the Hub key", ScreenKinds.of(screen) == HubKeyPolicy.Screen.CONTAINER
                        && HubKeyPolicy.decide(ScreenKinds.of(screen), false) == HubKeyPolicy.Action.IGNORE,
                        String.valueOf(ScreenKinds.of(screen)));
                int before = Runtime.hud().clicks().left();
                KeyBinding.onKeyPressed(client.options.attackKey.getCode());
                check("clicks behind a menu are not counted", Runtime.hud().clicks().left() == before, "");
                check("no mouse button reads as held behind a menu", !Runtime.hud().clicks().leftDown()
                        && !Runtime.hud().clicks().rightDown(), "");
                stash = Runtime.hud().drawnLastFrame();
                setToggle("keystrokes", HudStyle.HIDE_SCREENS, true);
                next();
                return;
            }
            case 29:
                if (since() < 300L) {
                    return;
                }
                check("Hide behind menus hides the widget", Runtime.hud().drawnLastFrame() == stash - 1,
                        stash + " -> " + Runtime.hud().drawnLastFrame());
                setToggle("keystrokes", HudStyle.HIDE_SCREENS, false);
                client.setScreen(new ChatScreen());
                next();
                return;
            case 30:
                if (since() < 300L) {
                    return;
                }
                check("chat is typing to the Hub key", ScreenKinds.of(client.currentScreen) == HubKeyPolicy.Screen.TEXT_INPUT
                        && HubKeyPolicy.decide(HubKeyPolicy.Screen.TEXT_INPUT, false) == HubKeyPolicy.Action.IGNORE,
                        String.valueOf(ScreenKinds.of(client.currentScreen)));
                client.setScreen(null);
                stash = Runtime.hud().drawnLastFrame();
                client.options.debugEnabled = true;
                setToggle("fps", HudStyle.HIDE_DEBUG, true);
                next();
                return;
            case 31:
                if (since() < 300L) {
                    return;
                }
                check("Hide with F3 hides the widget", Runtime.hud().drawnLastFrame() == stash - 1,
                        stash + " -> " + Runtime.hud().drawnLastFrame());
                client.options.debugEnabled = false;
                setToggle("fps", HudStyle.HIDE_DEBUG, false);
                // On to the secondary modules, which come back to 32.
                step = 40;
                stepStarted = System.nanoTime();
                return;

            // -- the secondary modules ----------------------------------------------------
            //
            // Direction, Clock, Scoreboard and Chat switched on with the rest,
            // a server-style sidebar, then each module's own checks. Back to
            // leaving and rejoining (32) at the end.
            case 40: {
                for (String id : SECONDARY) {
                    FathomModule found = module(id);
                    if (found != null && !found.enabled()) {
                        found.setEnabled(true);
                    }
                }
                sidebar = SecondaryChecks.buildSidebar(client);
                next();
                return;
            }
            case 41: {
                // A second for one-off costs to land outside the window: the
                // first draw of a glyph outside ASCII loads Minecraft's unicode
                // page, sixteen megabytes once, as vanilla's own sidebar would.
                if (since() < 1000L) {
                    return;
                }
                calibrateAllocation();
                measuringAllocation = true;
                allocatedBytes = 0L;
                allocationFrames = 0L;
                WIDGET_ALLOCATION.clear();
                WidgetProfile.qaReset();
                next();
                return;
            }
            case 42: {
                if (since() < 3000L) {
                    return;
                }
                measuringAllocation = false;
                long perFrame = allocationFrames == 0L ? -1L : allocatedBytes / allocationFrames;
                StringBuilder heavy = new StringBuilder();
                StringBuilder where = new StringBuilder();
                for (Map.Entry<String, long[]> entry : WIDGET_ALLOCATION.entrySet()) {
                    long each = entry.getValue()[1] == 0 ? 0 : entry.getValue()[0] / entry.getValue()[1];
                    where.append(where.length() == 0 ? "" : ", ").append(entry.getKey()).append(' ').append(each);
                    // The sidebar is judged against vanilla's below: its text
                    // is the server's, and vanilla's renderer allocates for
                    // every colour code in it whoever calls it.
                    if (each > 256L && !"scoreboard".equals(entry.getKey())) {
                        heavy.append(' ').append(entry.getKey());
                    }
                }
                say("secondary widgets, CPU per frame: " + WidgetProfile.qaSummary());
                check("secondary HUD allocation: no widget over 256 bytes a frame (the sidebar aside)",
                        heavy.length() == 0, perFrame + " bytes per HUD frame over " + allocationFrames + " frames, "
                                + Runtime.hud().drawnLastFrame() + " widgets; " + where);
                // The sidebar against vanilla's: the whole HUD, vanilla's and
                // Fathom's, with no sidebar, vanilla's, then the widget's.
                setEnabled("scoreboard", false);
                SecondaryChecks.removeSidebar(client);
                step = 60;
                stepStarted = System.nanoTime();
                return;
            }
            case 60:
                if (since() < 500L) {
                    return;
                }
                startWholeHud();
                next();
                return;
            case 61:
                if (since() < 2000L) {
                    return;
                }
                hudNoSidebar = stopWholeHud();
                sidebar = SecondaryChecks.buildSidebar(client);
                next();
                return;
            case 62:
                if (since() < 500L) {
                    return;
                }
                startWholeHud();
                next();
                return;
            case 63:
                if (since() < 2000L) {
                    return;
                }
                hudVanillaSidebar = stopWholeHud();
                setEnabled("scoreboard", true);
                next();
                return;
            case 64:
                if (since() < 500L) {
                    return;
                }
                startWholeHud();
                next();
                return;
            case 65: {
                if (since() < 2000L) {
                    return;
                }
                long widget = stopWholeHud();
                long vanillaCost = hudVanillaSidebar - hudNoSidebar;
                long widgetCost = widget - hudNoSidebar;
                check("scoreboard: the widget costs no more than vanilla's own sidebar",
                        hudNoSidebar >= 0 && widgetCost <= vanillaCost + 256L,
                        "whole HUD a frame: no sidebar " + hudNoSidebar + " B, vanilla's sidebar " + hudVanillaSidebar
                                + " B, the widget " + widget + " B");
                checkingGl = true;
                glFrames = 0;
                GL_FINDINGS.clear();
                step = 43;
                stepStarted = System.nanoTime();
                return;
            }
            case 43: {
                if (since() < 1500L) {
                    return;
                }
                checkingGl = false;
                check("GL state with the secondary modules and a sidebar", glFrames > 0 && GL_FINDINGS.isEmpty(),
                        glFrames + " HUD frames" + (GL_FINDINGS.isEmpty() ? "" : ": " + GL_FINDINGS));
                // Scoreboard: what vanilla would show, drawn by the widget instead.
                check("scoreboard: the widget takes the sidebar over",
                        ScoreboardWidget.captured() == sidebar, String.valueOf(ScoreboardWidget.captured()));
                ScoreboardWidget widget = (ScoreboardWidget) Runtime.hud().renderer("scoreboard");
                List<String> drawn = widget.qaLines(net.fathom.optimizations.fabric.hud.HudFonts.vanilla(
                        client.textRenderer));
                List<String> expected = SecondaryChecks.expectedSidebar(sidebar);
                check("scoreboard: the title and fifteen lines are exactly the server's, split teams joined",
                        drawn.equals(expected), drawn.size() + " lines" + (drawn.equals(expected) ? ""
                                : ": drawn " + drawn + ", vanilla " + expected));
                screenshot(client, "fathom-qa-secondary");
                setEnabled("scoreboard", false);
                next();
                return;
            }
            case 44: {
                if (since() < 300L) {
                    return;
                }
                check("scoreboard: off, vanilla draws its own sidebar again", ScoreboardWidget.captured() == null,
                        String.valueOf(ScoreboardWidget.captured()));
                SecondaryChecks.removeSidebar(client);
                setEnabled("scoreboard", true);
                say("modules on before the chat checks: " + modulesOn());
                for (SecondaryChecks.Outcome outcome : SecondaryChecks.chat(client, module("chat"))) {
                    check(outcome.name, outcome.ok, outcome.detail);
                }
                // Fullbright, from off.
                setEnabled("fullbright", false);
                gammaBefore = client.options.gamma;
                next();
                return;
            }
            case 45:
                if (since() < 500L) {
                    return;
                }
                lightBefore = SecondaryChecks.darkestLight(client);
                setEnabled("fullbright", true);
                next();
                return;
            case 46: {
                if (since() < 500L) {
                    return;
                }
                int lit = SecondaryChecks.darkestLight(client);
                check("fullbright: the darkest light is lit", lit >= lightBefore + 40,
                        lightBefore + " -> " + lit + (WorldLighting.lightmapSeen() ? "" : "; the lightmap hook never ran"));
                check("fullbright: the brightness option is never written", client.options.gamma == gammaBefore,
                        gammaBefore + " -> " + client.options.gamma);
                setEnabled("fullbright", false);
                next();
                return;
            }
            case 47: {
                if (since() < 500L) {
                    return;
                }
                int back = SecondaryChecks.darkestLight(client);
                check("fullbright: off, the light is the player's own again", Math.abs(back - lightBefore) <= 3,
                        lightBefore + " -> " + back);
                // Time Changer, from off.
                setEnabled("time-changer", false);
                setChoice("time-changer", "time", "Midnight");
                timeBefore = client.world.getTimeOfDay();
                setEnabled("time-changer", true);
                next();
                return;
            }
            case 48: {
                if (since() < 400L) {
                    return;
                }
                long ticks = WorldLighting.skyTicks();
                float wanted = client.world.dimension.getSkyAngle(ticks, 0f);
                float sky = client.world.getSkyAngle(1f);
                check("time changer: the sky is at the chosen hour", ticks >= 0L && Math.abs(sky - wanted) < 1e-4f,
                        "sky " + sky + ", midnight " + wanted);
                long now = client.world.getTimeOfDay();
                check("time changer: the world's own time is still the server's",
                        Math.abs(now - timeBefore) <= 40L, timeBefore + " -> " + now);
                setEnabled("time-changer", false);
                next();
                return;
            }
            case 49: {
                if (since() < 300L) {
                    return;
                }
                float sky = client.world.getSkyAngle(1f);
                float server = client.world.dimension.getSkyAngle(client.world.getTimeOfDay(), 1f);
                check("time changer: off, the sky follows the server again", Math.abs(sky - server) < 0.01f,
                        "sky " + sky + ", server's " + server);
                oldAnimationChecks(client);
                // The crosshair, in a colour nothing else on screen is.
                crosshairBefore = snapshot("crosshair");
                setChoice("crosshair", "shape", "Cross");
                ((Setting.Whole) module("crosshair").setting("length")).set(4);
                ((Setting.Whole) module("crosshair").setting("thickness")).set(1);
                ((Setting.Whole) module("crosshair").setting("gap")).set(2);
                ((Setting.Whole) module("crosshair").setting("opacity")).set(100);
                ((Setting.Colour) module("crosshair").setting("colour")).set(0xFF00FF);
                setToggle("crosshair", "outline", false);
                setToggle("crosshair", "dot", false);
                setToggle("crosshair", "dynamic", false);
                setToggle("crosshair", "feedback", false);
                guiBefore = client.options.guiScale;
                guiStep = 1;
                client.options.guiScale = guiStep;
                next();
                return;
            }
            case 50: {
                if (since() < 500L) {
                    return;
                }
                if (guiStep == 1) {
                    say("modules on before the crosshair checks: " + modulesOn() + "; screen "
                            + (client.currentScreen == null ? "none" : client.currentScreen.getClass().getName()));
                }
                String name = "fathom-qa-crosshair-gui" + guiStep;
                screenshot(client, name);
                MinecraftGuiSize size = MinecraftGuiSize.get(client);
                try {
                    float[] off = SecondaryChecks.crosshairOffset(new File(new File(client.runDirectory, "screenshots"),
                            name + ".png"));
                    check("crosshair: centred on the screen at GUI scale " + guiStep,
                            off != null && off[2] > 0 && Math.abs(off[0]) <= 0.5f && Math.abs(off[1]) <= 0.5f,
                            off == null ? "unreadable" : String.format(java.util.Locale.ROOT,
                                    "%dx%d, GUI %dx%d: off by %.1f, %.1f screen pixels, %d pixels found",
                                    client.width, client.height, size.width(), size.height(), off[0], off[1],
                                    (int) off[2]));
                } catch (java.io.IOException failure) {
                    fail("crosshair: centred at GUI scale " + guiStep, failure.getMessage());
                }
                if (guiStep < 4) {
                    guiStep++;
                    client.options.guiScale = guiStep;
                    stepStarted = System.nanoTime();
                    return;
                }
                client.options.guiScale = guiBefore;
                restore("crosshair", crosshairBefore);
                // A resource pack whose font is wider than the default.
                fpsWidthBefore = widthOf("fps");
                rawWidthBefore = client.textRenderer.getStringWidth("000 FPS");
                try {
                    widePack = SecondaryChecks.writeWideFontPack(client);
                    check("resource pack: the wide-font pack is selected and reloaded",
                            SecondaryChecks.usePack(client, true), "");
                } catch (java.io.IOException failure) {
                    fail("resource pack: written", failure.getMessage());
                }
                next();
                return;
            }
            case 51: {
                if (since() < 1500L) {
                    return;
                }
                int wide = widthOf("fps");
                check("resource pack: widgets measure the new font after the reload", wide > fpsWidthBefore,
                        "FPS " + fpsWidthBefore + " -> " + wide + " GUI pixels wide; the font says "
                                + rawWidthBefore + " -> " + client.textRenderer.getStringWidth("000 FPS"));
                check("resource pack: the HUD draws after the reload", Runtime.hud().drawnLastFrame() > 0,
                        Runtime.hud().drawnLastFrame() + " widgets");
                screenshot(client, "fathom-qa-wide-font");
                checkingGl = true;
                glFrames = 0;
                GL_FINDINGS.clear();
                SecondaryChecks.usePack(client, false);
                next();
                return;
            }
            case 52: {
                if (since() < 1500L) {
                    return;
                }
                // Longer than the others: the reload itself takes a second,
                // and a text widget gives width back only after its text has
                // stayed narrower for 0.7 s (TextWidget.SHRINK_DELAY_NANOS).
                if (since() < 3000L) {
                    return;
                }
                checkingGl = false;
                check("GL state across two resource reloads", glFrames > 0 && GL_FINDINGS.isEmpty(),
                        glFrames + " HUD frames" + (GL_FINDINGS.isEmpty() ? "" : ": " + GL_FINDINGS));
                int back = widthOf("fps");
                int raw = client.textRenderer.getStringWidth("000 FPS");
                check("resource pack: removed, the font is the default again", raw == rawWidthBefore,
                        rawWidthBefore + " -> " + raw);
                check("resource pack: removed, widgets measure the default font again", back == fpsWidthBefore,
                        "FPS " + fpsWidthBefore + " -> " + back);
                if (widePack != null) {
                    SecondaryChecks.deleteTree(widePack);
                }
                step = 32;
                stepStarted = System.nanoTime();
                return;
            }

            // -- leaving and coming back ------------------------------------------------
            case 32:
                if (since() < 300L) {
                    return;
                }
                sessionBeforeLeave = Widgets.session();
                check("before leaving, a latch is set", MovementToggles.sneakLatched(), "");
                leave(client);
                next();
                return;
            case 33:
                if (client.world != null || since() < 2000L) {
                    if (since() > 60_000L) {
                        fail("leave", "still in a world after a minute");
                        finish(client);
                    }
                    return;
                }
                check("leaving clears the toggle latches", !MovementToggles.sneakLatched()
                        && !MovementToggles.sprintLatched(), "");
                check("leaving ends the session", Widgets.session() != sessionBeforeLeave, "");
                check("leaving zooms out", !Zoom.active(), "");
                check("leaving clears the clicks", Runtime.hud().clicks().left() == 0
                        && Runtime.hud().clicks().right() == 0, "");
                check("leaving drops the sidebar", ScoreboardWidget.captured() == null, "");
                pass("leave", "vanilla's Disconnect sequence");
                join(client);
                next();
                return;
            case 34:
                if (client.player == null || client.world == null) {
                    if (since() > 120_000L) {
                        fail("rejoin", "no world after two minutes");
                        finish(client);
                    }
                    return;
                }
                if (since() < 4000L) {
                    return;
                }
                pass("rejoin", client.isInSingleplayer() ? "singleplayer" : "server");
                check("a new world starts unlatched", !MovementToggles.sneakLatched()
                        && !client.player.isSneaking(), "");
                check("the HUD draws again", Runtime.hud().drawnLastFrame() > 0,
                        Runtime.hud().drawnLastFrame() + " widgets");
                screenshot(client, "fathom-qa-modules");
                next();
                return;

            // -- persistence, across a restart -----------------------------------------
            case 35:
                writeForRestart();
                next();
                return;
            case 36:
                if (since() < 1000L) {
                    return;
                }
                finish(client);
                return;

            case 900:
                // verify: nothing in the world is needed.
                verifyAfterRestart();
                finish(client);
                return;
            default:
                return;
        }
    }

    private static double stash;

    // -- the secondary phase -----------------------------------------------------

    private static final String[] SECONDARY = {"direction", "clock", "scoreboard", "chat", "pack-display"};
    private static net.minecraft.scoreboard.ScoreboardObjective sidebar;
    private static long hudNoSidebar;
    private static long hudVanillaSidebar;
    private static float gammaBefore;
    private static int lightBefore;
    private static long timeBefore;
    private static Map<String, String> crosshairBefore;
    private static int guiBefore;
    private static int guiStep;
    private static int fpsWidthBefore;
    private static int rawWidthBefore;
    private static File widePack;

    private static String modulesOn() {
        StringBuilder out = new StringBuilder();
        for (FathomModule each : Runtime.features().all()) {
            if (each.enabled()) {
                out.append(out.length() == 0 ? "" : " ").append(each.id());
            }
        }
        return out.toString();
    }

    private static void setEnabled(String id, boolean on) {
        FathomModule found = module(id);
        if (found != null && found.enabled() != on) {
            found.setEnabled(on);
        }
    }

    /** Every setting's stored form, to put back exactly. */
    private static Map<String, String> snapshot(String id) {
        Map<String, String> out = new java.util.LinkedHashMap<String, String>();
        for (Setting setting : module(id).settings()) {
            out.put(setting.id(), setting.serialise());
        }
        return out;
    }

    private static void restore(String id, Map<String, String> values) {
        for (Map.Entry<String, String> entry : values.entrySet()) {
            Setting setting = module(id).setting(entry.getKey());
            if (setting != null) {
                setting.parse(entry.getValue());
            }
        }
    }

    /** A widget's width as the HUD lays it out now, in GUI pixels. */
    private static int widthOf(String widgetId) {
        MinecraftClient client = MinecraftClient.getInstance();
        MinecraftGuiSize size = MinecraftGuiSize.get(client);
        for (FathomHud.Positioned each : Runtime.hud().boxes(client, size.width(), size.height())) {
            if (each.widget.id().equals(widgetId)) {
                return each.box.width;
            }
        }
        return -1;
    }
    private static boolean boxedIn;

    /**
     * Turn to the first of the four directions with three blocks of open
     * ground ahead, at feet and head height. False when there is none.
     */
    private static boolean faceOpenGround(MinecraftClient client) {
        net.minecraft.util.math.BlockPos feet = new net.minecraft.util.math.BlockPos(client.player);
        // Minecraft's yaw: 0 faces +Z, 90 faces -X, 180 faces -Z, 270 faces +X.
        int[][] ahead = {{0, 1}, {-1, 0}, {0, -1}, {1, 0}};
        for (int quarter = 0; quarter < 4; quarter++) {
            boolean clear = true;
            for (int step = 1; step <= 3 && clear; step++) {
                net.minecraft.util.math.BlockPos at = feet.add(ahead[quarter][0] * step, 0, ahead[quarter][1] * step);
                clear = client.world.isAir(at) && client.world.isAir(at.add(0, 1, 0));
            }
            if (clear) {
                client.player.yaw = quarter * 90f;
                client.player.pitch = 0f;
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Helpers
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
        String world = joinedWorld != null ? joinedWorld : WORLD;
        client.startIntegratedServer(world, world, null);
    }

    /** Exactly what vanilla's Disconnect button does. */
    private static void leave(MinecraftClient client) {
        boolean local = client.isIntegratedServerRunning();
        client.world.disconnect();
        client.connect((net.minecraft.client.world.ClientWorld) null);
        client.setScreen(local ? new net.minecraft.client.gui.screen.TitleScreen()
                : new net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen(
                        new net.minecraft.client.gui.screen.TitleScreen()));
    }

    /** Every module the checks rely on, switched on, in its default state. */
    private static void prepareModules() {
        String[] on = {"fps", "coordinates", "cps", "keystrokes", "armour", "potions", "toggle-sprint", "zoom",
            "crosshair", "ping"};
        for (String id : on) {
            FathomModule module = module(id);
            if (module != null && !module.enabled()) {
                module.setEnabled(true);
            }
        }
        setChoice("zoom", "mode", "Hold");
        setChoice("toggle-sprint", "sprint.mode", "Hold");
        setChoice("toggle-sprint", "sneak.mode", "Hold");
    }

    /** What one mark-and-read costs by itself: the least of many, so a stray allocation does not count. */
    private static void calibrateAllocation() {
        long least = Long.MAX_VALUE;
        for (int sample = 0; sample < 200; sample++) {
            long first = threadAllocated();
            long second = threadAllocated();
            if (first < 0L || second < 0L) {
                allocationOverhead = -1L;
                return;
            }
            least = Math.min(least, second - first);
        }
        allocationOverhead = Math.max(0L, least);
    }

    private static float fov;
    private static float sensitivity;
    private static boolean smooth;

    private static void rememberOptions(MinecraftClient client) {
        fov = client.options.fov;
        sensitivity = client.options.sensitivity;
        smooth = client.options.smoothCameraEnabled;
    }

    private static boolean optionsUnchanged(MinecraftClient client) {
        return client.options.fov == fov && client.options.sensitivity == sensitivity
                && client.options.smoothCameraEnabled == smooth;
    }

    private static String optionsNow(MinecraftClient client) {
        return "fov " + client.options.fov + " (was " + fov + "), sensitivity " + client.options.sensitivity
                + " (was " + sensitivity + "), smooth " + client.options.smoothCameraEnabled;
    }

    private static void press(KeyBinding binding, boolean down) {
        KeyBinding.setKeyPressed(binding.getCode(), down);
    }

    private static void setChoice(String module, String setting, String value) {
        FathomModule found = module(module);
        if (found != null && found.setting(setting) instanceof Setting.Choice) {
            ((Setting.Choice) found.setting(setting)).set(value);
        }
    }

    private static void setToggle(String module, String setting, boolean value) {
        FathomModule found = module(module);
        if (found != null && found.setting(setting) instanceof Setting.Toggle) {
            ((Setting.Toggle) found.setting(setting)).set(value);
        }
    }

    private static void screenshot(MinecraftClient client, String name) {
        net.minecraft.client.util.ScreenshotUtils.saveScreenshot(client.runDirectory, name + ".png",
                client.width, client.height, client.getFramebuffer());
    }

    // -- the restart pair --------------------------------------------------------

    private static void writeForRestart() {
        setChoice("fps", "format", "FPS: 120");
        ((Setting.Slider) module("zoom").setting("amount")).set(6.5);
        ((Setting.Whole) module("keystrokes").setting("key.size")).set(21);
        ((Setting.Colour) module("crosshair").setting("colour")).set(0x33CCFF);
        setChoice("toggle-sprint", "sprint.mode", "Toggle");
        module("zoom").keybinds().get(0).set(net.fathom.optimizations.core.module.Keybind.mouse(3));
        module("time-changer").setEnabled(true);
        FathomHud hud = Runtime.hud();
        hud.place("clock", net.fathom.optimizations.core.hud.Placement.at(
                net.fathom.optimizations.core.hud.Anchor.BOTTOM_RIGHT, 7, 9));
        try {
            hud.profiles().saveLive();
            pass("settings written for the restart check", "to " + hud.profiles().liveName());
        } catch (net.fathom.optimizations.core.profile.ProfileException failure) {
            fail("settings written for the restart check", failure.getMessage());
        }
    }

    private static void verifyAfterRestart() {
        check("restart: a choice came back", "FPS: 120".equals(module("fps").choice("format", "")), "");
        check("restart: a slider came back", Math.abs(module("zoom").number("amount", 0) - 6.5) < 1e-9, "");
        check("restart: a stepper came back", Math.round(module("keystrokes").number("key.size", 0)) == 21, "");
        check("restart: a colour came back", module("crosshair").colour("colour", 0) == 0x33CCFF, "");
        check("restart: a mode came back", "Toggle".equals(module("toggle-sprint").choice("sprint.mode", "")), "");
        check("restart: a mouse keybind came back", module("zoom").keybinds().get(0).key()
                == net.fathom.optimizations.core.module.Keybind.mouse(3), "");
        check("restart: a switch came back", module("time-changer").enabled(), "");
        net.fathom.optimizations.core.hud.Placement clock = Runtime.hud().placement("clock");
        check("restart: a widget's place came back", clock.anchor() == net.fathom.optimizations.core.hud.Anchor
                .BOTTOM_RIGHT && clock.offsetX() == 7 && clock.offsetY() == 9, "");
        // Put it all back.
        module("fps").setting("format").reset();
        module("zoom").setting("amount").reset();
        module("keystrokes").setting("key.size").reset();
        module("crosshair").setting("colour").reset();
        module("toggle-sprint").setting("sprint.mode").reset();
        module("zoom").keybinds().get(0).reset();
        module("time-changer").setEnabled(module("time-changer").defaultEnabled());
        Runtime.hud().place("clock", Runtime.hud().registry().find("clock").defaultPlacement());
        try {
            Runtime.hud().profiles().saveLive();
        } catch (net.fathom.optimizations.core.profile.ProfileException failure) {
            fail("restart: restoring the profile", failure.getMessage());
        }
    }

    // -- results -----------------------------------------------------------------

    /**
     * 1.7 Animations, without a mouse: an item put in use mid-swing, and the
     * swing the equip transform is handed with the module on and off. Every
     * value is put back, including the module's own settings.
     */
    private static void oldAnimationChecks(MinecraftClient client) {
        net.minecraft.entity.player.ClientPlayerEntity player = client.player;
        FathomModule animations = module("old-animations");
        boolean enabledBefore = animations.enabled();
        boolean blockhitBefore = animations.toggle("blockhit", true);
        boolean useSwingBefore = animations.toggle("use-swing", true);
        check("1.7 animations: the held-item hook runs", net.fathom.optimizations.fabric.visual.OldAnimations.hookSeen(),
                "the first-person equip transform was never wrapped");
        boolean swingingBefore = player.handSwinging;
        int ticksBefore = player.handSwingTicks;
        float lastBefore = player.lastHandSwingProgress;
        float nowBefore = player.handSwingProgress;
        try {
            player.handSwinging = true;
            player.handSwingTicks = 2;
            player.lastHandSwingProgress = 0.2f;
            player.handSwingProgress = 0.4f;
            net.fathom.optimizations.fabric.visual.OldAnimations.frame(0.5f);
            float expected = player.getHandSwingProgress(0.5f);

            player.setUseItem(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_SWORD), 72000);
            setEnabled("old-animations", true);
            setToggle("old-animations", "blockhit", true);
            float blocking = net.fathom.optimizations.fabric.visual.OldAnimations.swing(0f);
            check("1.7 animations: blocking keeps the swing (the blockhit)", expected > 0f && blocking == expected,
                    "expected " + expected + ", got " + blocking);
            setToggle("old-animations", "blockhit", false);
            float blockhitOff = net.fathom.optimizations.fabric.visual.OldAnimations.swing(0f);
            check("1.7 animations: blockhit off, blocking is 1.8's again", blockhitOff == 0f, String.valueOf(blockhitOff));
            setToggle("old-animations", "blockhit", true);
            setEnabled("old-animations", false);
            float moduleOff = net.fathom.optimizations.fabric.visual.OldAnimations.swing(0f);
            check("1.7 animations: off, blocking is 1.8's again", moduleOff == 0f, String.valueOf(moduleOff));

            player.setUseItem(new net.minecraft.item.ItemStack(net.minecraft.item.Items.COOKED_BEEF), 72000);
            setEnabled("old-animations", true);
            setToggle("old-animations", "use-swing", true);
            float eating = net.fathom.optimizations.fabric.visual.OldAnimations.swing(0f);
            check("1.7 animations: eating keeps the swing", eating == expected, "expected " + expected + ", got " + eating);
            setToggle("old-animations", "use-swing", false);
            float eatingOff = net.fathom.optimizations.fabric.visual.OldAnimations.swing(0f);
            check("1.7 animations: item swing off, eating is 1.8's again", eatingOff == 0f, String.valueOf(eatingOff));
            check("1.7 animations: a swing that was already drawn is left alone",
                    net.fathom.optimizations.fabric.visual.OldAnimations.swing(0.7f) == 0.7f, "");
        } finally {
            player.resetUseItem();
            player.handSwinging = swingingBefore;
            player.handSwingTicks = ticksBefore;
            player.lastHandSwingProgress = lastBefore;
            player.handSwingProgress = nowBefore;
            setToggle("old-animations", "blockhit", blockhitBefore);
            setToggle("old-animations", "use-swing", useSwingBefore);
            setEnabled("old-animations", enabledBefore);
        }
    }

    private static void check(String name, boolean ok, String detail) {
        if (ok) {
            pass(name, detail);
        } else {
            fail(name, detail);
        }
    }

    private static void pass(String name, String detail) {
        passed++;
        say("PASS " + name + (detail == null || detail.isEmpty() ? "" : " (" + detail + ")"));
    }

    private static void fail(String name, String detail) {
        failed++;
        say("FAIL " + name + (detail == null || detail.isEmpty() ? "" : ": " + detail));
    }

    private static void skip(String name, String why) {
        skipped++;
        say("SKIP " + name + ": " + why);
    }

    private static void say(String line) {
        RESULTS.add(line);
        System.out.println("[Fathom] QA modules " + line);
    }

    private static boolean finished;
    /** The player's pause-on-lost-focus, while the test holds it off. */
    private static Boolean pausedOnFocus;

    private static void finish(MinecraftClient client) {
        if (finished) {
            return;
        }
        finished = true;
        Zoom.qaHeld = null;
        if (pausedOnFocus != null) {
            client.options.pauseOnLostFocus = pausedOnFocus.booleanValue();
        }
        System.out.println("[Fathom] QA modules (" + MODE + "): " + passed + " passed, " + failed + " failed, "
                + skipped + " skipped" + (entered ? "" : ", never entered a world"));
        client.scheduleStop();
    }
}
