package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.MinecraftClient;

/**
 * The drawing half of a widget.
 *
 * <p>Paired with a {@code net.fathom.optimizations.core.hud.Widget}, which is the metadata
 * half and lives in {@code core} so the launcher, the hub screen and the tests can all
 * read it without a Minecraft class on the path. This interface is the part that genuinely
 * needs the game.
 *
 * <h2>Why size is asked for separately from drawing</h2>
 *
 * <p>The editor needs a widget's box before anything is drawn: to hit-test a click, to
 * snap edges against neighbours, and to keep it on screen. Asking a widget to draw itself
 * into a measuring context instead would mean every widget having a "do not actually draw"
 * mode, which is a bug waiting to be introduced once.
 *
 * <h2>Why the font is an interface</h2>
 *
 * <p>{@link HudFont}, not Minecraft's {@code TextRenderer}: the widgets are
 * drawn in Minecraft's own font by default and in Fathom's font by choice on
 * each module, and the measurements a widget takes have to come from whichever
 * one will draw it.
 * The units either way are Minecraft's scaled pixels, which is the space the
 * saved layout and the editor's hit boxes are in.
 *
 * <p>Both sizes are asked for every frame rather than cached. A CPS readout is three
 * characters wide and then four; a coordinate readout changes width as you walk. A cached
 * size would be wrong exactly when the widget changed, which is the only time it matters.
 */
public interface HudWidget {

    /** Matches the id in the core registry. */
    String id();

    /** Width in unscaled pixels, before the placement's scale is applied. */
    int width(MinecraftClient client, HudFont font);

    /** Height in unscaled pixels, before the placement's scale is applied. */
    int height(MinecraftClient client, HudFont font);

    /**
     * Draw at the given position.
     *
     * <p>The caller has already applied scale and translated the matrix, so the widget
     * draws at {@code (x, y)} in its own coordinate space and takes no interest in either.
     *
     * @param alpha the placement's opacity as a 0-255 byte, to be packed into every colour
     *     this widget draws. A widget that ignores it is a widget whose opacity slider does
     *     nothing.
     */
    void draw(MinecraftClient client, HudFont font, int x, int y, int alpha);

    /**
     * Whether this widget has anything to show right now.
     *
     * <p>A ping readout in singleplayer and an armour readout with no armour are both
     * "nothing to say". They are skipped rather than drawn empty, so the HUD does not
     * accumulate blank rectangles.
     *
     * <p>The editor overrides this: a widget that cannot be seen cannot be moved, so the
     * editor draws a placeholder for anything hidden. See {@link #placeholder()}.
     */
    boolean visible(MinecraftClient client);

    /** What the editor draws when {@link #visible} is false, so it can still be arranged. */
    String placeholder();

    /**
     * Where the widget sits across the screen, before it is measured and drawn.
     *
     * <p>0 for a left anchor, 0.5 centred, 1 for a right anchor. Text widgets
     * align their lines to it, so a right-anchored readout grows leftwards from
     * the screen edge instead of leaving a ragged gap as its numbers change.
     */
    default void align(float horizontal) {
    }
}
