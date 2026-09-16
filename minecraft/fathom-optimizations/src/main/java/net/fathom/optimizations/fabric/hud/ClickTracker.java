package net.fathom.optimizations.fabric.hud;

import org.lwjgl.input.Mouse;
import org.lwjgl.opengl.Display;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.hud.value.ClickCounter;

/**
 * Counts mouse clicks for the CPS and keystroke widgets.
 *
 * <h2>Presses are counted from the game's own input events</h2>
 *
 * <p>Minecraft drains LWJGL's mouse queue once per tick and hands every press
 * to {@code KeyBinding.onKeyPressed}, which is how an attack or a use is
 * registered. {@code KeyBindingPressMixin} observes that call — it reads the
 * button and changes nothing — so the count is exactly the presses the game
 * itself saw.
 *
 * <p>This replaced sampling the button once per rendered frame, which missed
 * any press shorter than a frame: at 60 fps a quick 12 CPS burst lost clicks,
 * and at a capped 30 fps it lost many. Events cannot be missed; the cost is
 * that a press is timestamped when its tick processes it, up to 50 ms late,
 * which moves a one-second window's edge by at most a twentieth of it.
 *
 * <h2>What counts as a click</h2>
 *
 * <p>A press the player made at the game:
 *
 * <ul>
 *   <li>the mouse is captured by the game — the press that gives Minecraft
 *       focus back is not a click at the game, and neither is one elsewhere;
 *   <li>no screen is open — clicking a button in the hub, the inventory or the
 *       HUD editor is not clicking at the game;
 *   <li>a player exists — the title screen is not gameplay.
 * </ul>
 *
 * <h2>Held state is still sampled</h2>
 *
 * <p>Whether a button is down right now is read per rendered frame, because a
 * keystroke display has to light on the frame the button goes down, not on the
 * next tick.
 *
 * <h2>No state survives a world</h2>
 *
 * <p>{@link #reset} is called when the world or player changes, so a CPS
 * reading from one server is not still decaying on the next.
 */
public final class ClickTracker {

    private final ClickCounter left = new ClickCounter();
    private final ClickCounter right = new ClickCounter();

    private boolean leftDown;
    private boolean rightDown;
    private int leftPerSecond;
    private int rightPerSecond;

    /**
     * A press the game processed. Called from the input Mixin, on the client
     * thread, for every key and button code; anything but the two mouse
     * buttons is ignored.
     *
     * @param code Minecraft's key code: {@code -100} left, {@code -99} right
     */
    public void pressed(int code) {
        if (code != -100 && code != -99) {
            return;
        }
        if (!playing()) {
            return;
        }
        long now = System.nanoTime();
        if (code == -100) {
            left.click(now);
        } else {
            right.click(now);
        }
    }

    /**
     * Refresh the readout and the held state. Called once per frame.
     *
     * @param window how many seconds the rate is averaged over, from the CPS
     *     module's own setting. Passed in rather than looked up, because this is
     *     the class that has no opinion about modules.
     */
    public void sample(int window) {
        long now = System.nanoTime();
        if (!Mouse.isCreated() || !playing()) {
            // Not at the game: nothing is held, so the keystroke display does
            // not leave a button lit behind a screen.
            leftDown = false;
            rightDown = false;
        } else {
            leftDown = Mouse.isButtonDown(0);
            rightDown = Mouse.getButtonCount() > 1 && Mouse.isButtonDown(1);
        }
        leftPerSecond = left.perSecond(now, window);
        rightPerSecond = right.perSecond(now, window);
    }

    /** Forget every click, for a world, server or session change. */
    public void reset() {
        left.reset();
        right.reset();
        leftPerSecond = 0;
        rightPerSecond = 0;
        leftDown = false;
        rightDown = false;
    }

    /** Whether the player is at the game, rather than at a screen or elsewhere. */
    private static boolean playing() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.player == null || client.currentScreen != null || !client.focused) {
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
        return leftDown;
    }

    public boolean rightDown() {
        return rightDown;
    }
}
