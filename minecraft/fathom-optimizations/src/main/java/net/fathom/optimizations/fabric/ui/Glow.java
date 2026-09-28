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
     * Light falling on a pane from above: a wide round pool centred on its top
     * edge, inside the pane only. Drawn after the pane's fill, before its
     * content.
     */
    public static void sheen(Renderer ui, Rect pane) {
        float radius = Math.max(pane.width * 0.6f, pane.height * 0.7f);
        ui.clip(pane.x, pane.y, pane.right(), pane.bottom());
        ui.bloom(pane.centreX(), pane.y, radius, at(GraphiteTokens.GLOW_SHEEN_ALPHA));
        ui.unclip();
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
        ui.rounded(row.x, row.y, row.right(), row.bottom(), radius,
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
        ui.shadow(button.x, button.y, button.right(), button.bottom(), radius,
                GraphiteTokens.GLOW_HALO_BLUR * 0.6f, at(GraphiteTokens.GLOW_HALO_ALPHA * strength));
    }
}
