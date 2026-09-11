package net.fathom.optimizations.fabric.gfx;

/**
 * A screen drawn by Fathom's renderer.
 *
 * <p>Marks the Fathom main menu, hub, HUD editor and renderer spike. The frame
 * cap uses that fact for one narrow case: vanilla hard-codes 30 fps when there
 * is no world, while Fathom's interactive no-world screens should use the
 * player's configured limit.
 *
 * <p>In-world vanilla screens are deliberately not special-cased at all:
 * inventory, containers, chat and pause retain the same limit as gameplay.
 * Using a marker rather than a class-name list keeps the no-world exception
 * correct as Fathom adds screens.
 */
public interface FathomScreen {

    /**
     * A key-down event drained during a rendered frame.
     *
     * @return true when the screen owned the event
     */
    boolean handleFathomKey(char character, int keyCode);
}
