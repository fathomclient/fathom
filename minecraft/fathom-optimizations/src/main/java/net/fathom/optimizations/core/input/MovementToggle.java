package net.fathom.optimizations.core.input;

/**
 * Toggle Sprint's and Toggle Sneak's state, without Minecraft.
 *
 * <h2>What "toggle" means here</h2>
 *
 * <p>A press of the key latches it on; the next press lets it go. While latched
 * the game is told the key is held, and nothing else: vanilla still decides
 * whether that means sprinting (hunger, blindness, walking forwards, using an
 * item) and what sneaking does. So a toggle can never do something the key held
 * down by hand could not, which is the line a server is entitled to draw.
 *
 * <p>Holding the key while unlatched still works as holding it, so a player can
 * tap to toggle and hold for a moment without thinking about which mode is on.
 *
 * <h2>When the latch is dropped</h2>
 *
 * <p>{@link #release} is called on death, respawn, world change, disconnect and
 * when the module or mode is switched off. After a release the key has to come
 * up before a press counts again, so a key that happened to be held through a
 * respawn does not immediately re-latch.
 *
 * <p>Presses are only seen from the game's key state, which vanilla clears when
 * a screen opens and does not update while one is open. Typing a capital letter
 * in chat therefore cannot toggle sneak, and the latch is left as it was while a
 * menu is up rather than silently changing underneath it.
 */
public final class MovementToggle {

    private boolean latched;
    private boolean wasDown;

    /**
     * Advance one game tick.
     *
     * @param toggleMode whether the module asks for toggle behaviour; false is vanilla
     * @param keyDown whether the game currently considers the key held
     * @param accept whether a press may change the latch right now. False while
     *     flying or riding for sneak, where the key has another meaning
     */
    public void tick(boolean toggleMode, boolean keyDown, boolean accept) {
        if (!toggleMode) {
            latched = false;
            wasDown = keyDown;
            return;
        }
        if (keyDown && !wasDown && accept) {
            latched = !latched;
        }
        wasDown = keyDown;
    }

    /** Whether the game should see the key as held, given its physical state. */
    public boolean held(boolean toggleMode, boolean keyDown, boolean apply) {
        return keyDown || (toggleMode && apply && latched);
    }

    /** Whether the latch is on. */
    public boolean latched() {
        return latched;
    }

    /** Drop the latch, and wait for the key to come up before counting a press. */
    public void release() {
        latched = false;
        wasDown = true;
    }
}
