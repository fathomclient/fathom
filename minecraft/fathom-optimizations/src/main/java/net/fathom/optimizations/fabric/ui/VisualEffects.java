package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Shadow;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * Fathom's depth vocabulary: glass without blur.
 *
 * <p>Warm translucent fills with a lit top edge and a faint rim; hover and
 * selection are density and luminance, never a hue. There are deliberately no
 * glow or bloom effects. Shadows use the shared {@link Shadow} presets; no
 * helper reads the framebuffer, allocates an image, creates a texture or
 * changes GL state -- a pane is a fill, a fading overlay and an outline, all
 * from the renderer's one sheet.
 */
public final class VisualEffects {

    private VisualEffects() {}

    /** Width of the inner red falloff on an energized perimeter. */
    public static final float INNER_ENERGY_WIDTH = 3f;
    /** Card separation and localized icon-light reach. */
    public static final float CARD_SHADOW_SPREAD = 6f;
    public static final float CARD_SHADOW_OPACITY = 0.55f;
    /** Hover is colour, border and opacity, never geometry. */
    // CARD_HOVER_LIFT, CARD_HOVER_SCALE and ICON_HOVER_SCALE were here and are
    // deliberately gone. They moved and resized a card -- and therefore its
    // text -- while the pointer was over it, so a title shifted as you reached
    // for it. Hover is colour, border and opacity; it is not geometry. If a
    // hover affordance needs strengthening, change `SURFACE_HOVER` or the edge
    // mix, not the transform.
    /** Enable/disable accent change settles within this interval. */
    public static final float ACTIVATION_PULSE_SECONDS = 0.40f;
    /** Brightness mixed into text, icons and borders at full hover. */
    public static final float HOVER_BRIGHTNESS = 0.10f;
    /** Strength of the neutral shadow under an ordinary large panel. */
    public static final float SHADOW_STRENGTH = 0.92f;
    /** Floating controls may separate more clearly than resting panels. */
    public static final float OVERLAY_SHADOW_STRENGTH = 1.00f;
    /** Modals are deepest, but still use one restrained falloff. */
    public static final float MODAL_SHADOW_STRENGTH = 1.00f;
    /** Shared intensity of the keyboard-focus ring. */
    public static final float FOCUS_INTENSITY = 0.78f;
    /**
     * Surface relief from top edge to lower face. Zero: the launcher's control
     * fills are flat, and a deepening gradient under a control is exactly the
     * kind of decoration the two surfaces must not disagree on.
     */
    public static final float SURFACE_DEPTH = 0f;

    private static final Shadow PANEL_SHADOW = Shadow.PANEL.withStrength(SHADOW_STRENGTH);
    private static final Shadow OVERLAY_SHADOW = Shadow.DROPDOWN.withStrength(OVERLAY_SHADOW_STRENGTH);
    private static final Shadow MODAL_SHADOW = Shadow.MODAL.withStrength(MODAL_SHADOW_STRENGTH);

    /** A one-shot hairline ring for a meaningful enable/disable event; no bloom. */
    public static void activationPulse(Renderer ui, Rect area, float radius,
            float progress, boolean activating) {
        float phase = clamp(progress);
        if (phase >= 0.999f) {
            return;
        }
        float remaining = 1f - phase;
        float expansion = (1f - remaining * remaining) * 3f;
        int colour = activating ? Skin.ENERGY_EDGE : Skin.ENERGY;
        ui.outline(area.x - expansion, area.y - expansion,
                area.right() + expansion, area.bottom() + expansion,
                radius + expansion, ui.pixel(),
                Skin.scaleAlpha(colour, remaining * (activating ? 0.80f : 0.45f)));
    }

    /** One thin inner red band just inside an energized perimeter. */
    public static void innerEnergy(Renderer ui, Rect area, float radius, float energy) {
        float shown = clamp(energy);
        if (shown <= 0.001f) {
            return;
        }
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.radius(radius, area),
                INNER_ENERGY_WIDTH, Skin.scaleAlpha(Skin.ENERGY, shown * 0.14f));
    }


    /**
     * Where clipped scrolling content continues: a hairline at the cut edge.
     *
     * <p>Not a fade. A fade is a band of colour, and over transparent glass
     * a band of umber reads as a brown bar across the list.
     */
    public static void scrollEdgeFade(Renderer ui, Rect viewport, boolean top, boolean bottom) {
        float inset = Math.min(Skin.S6, viewport.width * 0.08f);
        if (top) {
            ui.fill(viewport.x + inset, viewport.y, viewport.right() - inset,
                    viewport.y + ui.pixel(), Skin.EDGE_RECESSED);
        }
        if (bottom) {
            ui.fill(viewport.x + inset, viewport.bottom() - ui.pixel(),
                    viewport.right() - inset, viewport.bottom(), Skin.EDGE_RECESSED);
        }
    }

    /**
     * A material face, a touch deeper toward its lower edge; hover lifts it a step.
     *
     * <p>Both shifts move the colour and keep its alpha. They used to mix toward
     * opaque white and opaque black, which on a translucent fill also raised
     * its opacity -- every button grew a dark, nearly solid lower half.
     */
    public static void layeredSurface(Renderer ui, Rect area, float radius, int face, float hover) {
        int top = tone(face, Tokens.PALETTE_IVORY_50, clamp(hover) * 0.06f);
        int bottom = tone(top, Tokens.PALETTE_UMBER_900, SURFACE_DEPTH);
        ui.gradient(area.x, area.y, area.right(), area.bottom(), radius, top, bottom);
    }

    /**
     * A pane of glass: its fill and one rim, the same all the way round.
     *
     * <p>This used to lay a second, brighter hairline along the top's straight
     * run on top of the rim. Over the ring beneath it that read as a top edge
     * twice the weight of the sides -- so every settings group looked more
     * heavily bordered than the Mods list beside it, which has one rim. The
     * brightness the highlight carried is kept by mixing a little of it into
     * the one rim instead of adding a second stroke.
     */
    public static void glass(Renderer ui, Rect area, float radius, int fill, int rimTop, int rimEdge) {
        // Frosted, as the launcher's glass is, when the screen captured its
        // scene this frame (GlRenderer.captureFrost).
        if (ui instanceof net.fathom.optimizations.fabric.gfx.GlRenderer) {
            ((net.fathom.optimizations.fabric.gfx.GlRenderer) ui).frost(
                    area.x, area.y, area.right(), area.bottom(), radius);
        }
        ui.rounded(area.x, area.y, area.right(), area.bottom(), radius, fill);
        ui.rim(area.x, area.y, area.right(), area.bottom(), radius, Skin.mix(rimEdge, rimTop, 0.35f));
    }

    /** Move a colour's RGB toward another's, keeping its alpha. */
    public static int tone(int colour, int towards, float amount) {
        return Skin.fade(Skin.mix(colour | 0xFF000000, towards | 0xFF000000, clamp(amount)),
                Colour.alpha(colour));
    }

    /** Brighten text, an icon or a border by the shared hover amount, toward ivory. */
    public static int hoverBrighten(int colour, float hover) {
        return Skin.mix(colour, Tokens.PALETTE_IVORY_50, clamp(hover) * HOVER_BRIGHTNESS);
    }

    public static void panelShadow(Renderer ui, Rect area, float radius) {
        shadow(ui, area, radius, PANEL_SHADOW);
    }

    public static void overlayShadow(Renderer ui, Rect area, float radius) {
        shadow(ui, area, radius, OVERLAY_SHADOW);
    }

    public static void modalShadow(Renderer ui, Rect area, float radius) {
        shadow(ui, area, radius, MODAL_SHADOW);
    }

    /** Shared renderer/fallback bridge for the three elevation levels. */
    public static void shadow(Renderer ui, Rect area, float radius, Shadow shadow) {
        shadow(ui, area.x, area.y, area.right(), area.bottom(), radius, shadow);
    }

    /** Coordinate overload for hot paths that already have scalar bounds. */
    public static void shadow(Renderer ui, float left, float top, float right, float bottom,
            float radius, Shadow shadow) {
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).shadow(left, top, right, bottom, radius, shadow);
        } else {
            ui.shadow(left, top, right, bottom, radius,
                    shadow.blur() * 0.6f, shadow.argb());
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
