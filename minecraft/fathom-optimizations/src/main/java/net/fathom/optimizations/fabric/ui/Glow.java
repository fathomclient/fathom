package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.fabric.menu.MenuScenes;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * Light: the launcher's glow (desktop/src/lib/glow.css), in the game.
 *
 * <p>One colour, the chosen background's accent ({@code accent} in
 * assets/backgrounds/manifest.json), at the strengths in the theme's
 * {@code graphite.glow}. The launcher's two rules hold here too:
 *
 * <ul>
 *   <li><b>Round.</b> Every glow is a radial falloff ({@link Renderer#bloom})
 *       or a halo that follows the rounded shape it belongs to. No bands.</li>
 *   <li><b>Subtle.</b> A glow says "here" and is otherwise barely there; only
 *       the primary action and the current place get one.</li>
 * </ul>
 *
 * <p>Strokes are untouched: rims stay one device pixel, and light goes behind
 * and around a shape, never into its border.
 */
public final class Glow {

    private Glow() {}

    /** The accent of the background showing now, 0xRRGGBB. */
    public static int accent() {
        return MenuScenes.selected().accent & 0x00FFFFFF;
    }

    /** The accent at a strength, 0-1, as ARGB. */
    public static int at(float alpha) {
        int a = Math.round(Math.max(0f, Math.min(1f, alpha)) * 255f);
        return (a << 24) | accent();
    }

    /**
     * The scene's light pooling behind a surface: a large round bloom centred
     * on it, and a fainter echo high on the far side of the screen so the
     * light has somewhere to come from. Drawn before the surface, over the
     * backdrop.
     */
    public static void behind(Renderer ui, Rect surface) {
        float reach = GraphiteTokens.GLOW_BLOOM_SIZE * Math.max(ui.width(), ui.height()) / 2f;
        ui.bloom(surface.centreX(), surface.centreY(), reach, at(GraphiteTokens.GLOW_BLOOM_ALPHA));
        float echo = reach * 0.7f;
        boolean left = surface.centreX() < ui.width() / 2f;
        ui.bloom(left ? ui.width() - echo * 0.2f : echo * 0.2f, echo * 0.15f, echo,
                at(GraphiteTokens.GLOW_BLOOM_ECHO_ALPHA));
    }

    /**
     * Light falling on a pane from above, fading to nothing towards its foot,
     * inside the pane's own rounded shape. Drawn after the pane's fill, before
     * its content.
     *
     * <p>This was a round bloom clipped to the pane -- and a clip is a
     * rectangle, so the light filled the square corners outside the pane's
     * curve: on a narrow pane like the title screen's rail, a faint box around
     * the capsule. A rounded fade cannot leave the shape.
     */
    public static void sheen(Renderer ui, Rect pane, float radius) {
        // --glow-sheen: radial-gradient(120% 90% at 50% 0%, accent, transparent 68%).
        ui.radial(pane.x, pane.y, pane.right(), pane.bottom(), radius, pane.centreX(), pane.y,
                pane.width * 1.2f, pane.height * 0.9f, at(GraphiteTokens.GLOW_SHEEN_ALPHA), 0.68f);
    }

    /**
     * The current place, or the row under the pointer: the accent washed into
     * its own rounded shape at {@code strength} (0-1, usually an animated hover
     * or selection). Drawn after the row's fill, before its content.
     *
     * <p>A tint of the shape, not a bloom clipped to it: a clip is a rectangle,
     * and light reaching a rounded row's corners would square them off.
     */
    public static void current(Renderer ui, Rect row, float radius, float strength) {
        if (strength <= 0.001f) {
            return;
        }
        // --glow-selected: 0 0 18px -2px, the accent at 14% -- light around
        // the current item, never a coloured fill on it.
        ui.boxShadow(row.x, row.y, row.right(), row.bottom(), radius, 18f, -2f, 0f, 0f,
                at(GraphiteTokens.GLOW_SELECTED_ALPHA * strength));
    }

    /**
     * The one primary action's halo: a soft falloff of the accent all round
     * its rounded shape, never a coloured outline. ({@link Renderer#shadow}
     * paints only outside the box it is given, so the button covers nothing
     * of it and there is no gap between the shape and its light.)
     */
    public static void halo(Renderer ui, Rect button, float radius, float strength) {
        if (strength <= 0.001f) {
            return;
        }
        // The launcher's `box-shadow: 0 0 24px -6px`: the falloff starts 6dp
        // inside the button's edge (the negative spread) and runs half the
        // blur, so the light reaches about 6dp past the shape. This used to
        // ignore the spread and run 14dp out -- past the 12dp gap to the
        // next button, whose translucent glass then showed it, as if the
        // primary were bleeding into its neighbour.
        // --glow-halo: 0 10px 24px -6px, exactly.
        ui.boxShadow(button.x, button.y, button.right(), button.bottom(), radius, GraphiteTokens.GLOW_HALO_BLUR,
                GraphiteTokens.GLOW_HALO_SPREAD, 0f, 10f, at(GraphiteTokens.GLOW_HALO_ALPHA * strength));
    }
}
