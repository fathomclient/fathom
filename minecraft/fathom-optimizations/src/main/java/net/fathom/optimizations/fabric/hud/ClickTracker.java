package net.fathom.optimizations.fabric.hud;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.hud.value.ClickCounter;

/**
 * Counts mouse clicks for the CPS and keystroke widgets.
 *
 * <h2>Why this polls instead of hooking the event queue</h2>
 *
 * <p>1.8.9 drains the LWJGL mouse queue inside {@code MinecraftClient.tick}, in a loop with
 * no method of its own — there is nothing named to inject into. The options were a Mixin
 * that rewrites part of the tick body, or an edge detector that samples the button state.
 *
 * <p>The edge detector wins, for a reason worth stating: sampling reads state and changes
 * nothing, so a bug here shows a wrong number. Rewriting the input loop could swallow a
 * click, and a client that occasionally does not register an attack is a far worse failure
 * than a counter that reads 11 instead of 12.
 *
 * <h2>What counts as a click</h2>
 *
 * <p>A press the player made at the game. Three conditions, and all of them
 * exclude presses that are not the player clicking in the world:
 *
 * <ul>
 *   <li>the window has focus — clicking in another application does not count,
 *       and neither does the press that gives Minecraft focus back;
 *   <li>no screen is open — clicking a button in the hub, the inventory or the
 *       HUD editor is not clicking at the game;
 *   <li>a player exists — the title screen is not gameplay.
 * </ul>
 *
 * <p>Without these the counter climbed while somebody arranged their HUD, which
 * is a CPS readout measuring the wrong thing entirely. Held state
 * ({@link #leftDown}) is cleared at the same time, so the keystroke display does
 * not leave a mouse button lit after a screen opens.
 *
 * <h2>What it costs</h2>
 *
 * <p>Sampling happens once per rendered frame, so a click that goes down and back up
 * entirely between two frames is not seen. At 60 fps that needs a press shorter than 17 ms;
 * at 30 fps, 33 ms. Hands do not do that, and the ones that do are running something that
 * clicks for them. This is honest about the number it reports rather than accurate about a
 * number nobody produces.
 *
 * <p>The counting itself — the sliding window — is in {@link ClickCounter}, in
 * {@code core}, where it has tests.
 */
public final class ClickTracker {

    private final ClickCounter left = new ClickCounter();
    private final ClickCounter right = new ClickCounter();

    private boolean leftWasDown;
    private boolean rightWasDown;
    private int leftPerSecond;
    private int rightPerSecond;

    /**
     * Sample the buttons. Called once per frame.
     *
     * <p>Both the edge detection and the readout are done here, so the widgets read two
     * fields rather than each recomputing a window scan per frame.
     *
     * @param window how many seconds the rate is averaged over, from the CPS
     *     module's own setting. Passed in rather than looked up, because this is
     *     the class that has no opinion about modules.
     */
    public void sample(int window) {
        if (!Mouse.isCreated()) {
            // Before the display exists, and after it is destroyed. Sampling a
            // mouse that is not there throws; asking first does not.
            return;
        }
        long now = System.nanoTime();

        if (!playing()) {
            // Not a click at the game. The edge state is cleared rather than
            // frozen, so the first press after closing a screen registers as a
            // press and not as "already down".
            leftWasDown = false;
            rightWasDown = false;
            leftPerSecond = left.perSecond(now, window);
            rightPerSecond = right.perSecond(now, window);
            return;
        }

        boolean leftDown = Mouse.isButtonDown(0);
        if (leftDown && !leftWasDown) {
            left.click(now);
        }
        leftWasDown = leftDown;

        boolean rightDown = Mouse.isButtonDown(1);
        if (rightDown && !rightWasDown) {
            right.click(now);
        }
        rightWasDown = rightDown;

        leftPerSecond = left.perSecond(now, window);
        rightPerSecond = right.perSecond(now, window);
    }

    /** Whether the player is at the game, rather than at a screen or elsewhere. */
    private static boolean playing() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null) {
            return false;
        }
        return Display.isCreated() && Display.isActive();
    }

    public int left() {
        return leftPerSecond;
    }

    public int right() {
        return rightPerSecond;
    }

    public boolean leftDown() {
        return leftWasDown;
    }

    public boolean rightDown() {
        return rightWasDown;
    }
}
