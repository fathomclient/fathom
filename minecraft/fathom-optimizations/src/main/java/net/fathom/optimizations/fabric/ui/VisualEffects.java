package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.render.Paint;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Shadow;

/**
 * Fathom's depth and energy vocabulary.
 *
 * <p>Dark, flat materials; red light only where something is active. Hover
 * never produces light -- it moves, brightens a surface a step, and strengthens
 * a border. Glow is a tight 4-6 unit falloff around an active edge or icon, not
 * a bloom around a component, and there is no white decorative lighting.
 *
 * <p>On NanoVG a glow is one box gradient; the compatibility renderer uses its
 * cached falloff atlas. Shadows use the shared {@link Shadow} presets. No
 * helper reads the framebuffer, allocates an image, or creates a texture per
 * frame.
 */
public final class VisualEffects {

    private VisualEffects() {}

    /** Feather of an active component's edge glow. */
    public static final float GLOW_RADIUS = 6f;
    /** Maximum opacity of the red energy falloff. */
    public static final float GLOW_OPACITY = 0.30f;
    /** Compact controls and markers use a tighter field than cards. */
    public static final float CONTROL_GLOW_RADIUS = 4f;
    /** Width of the inner red falloff on an energized perimeter. */
    public static final float INNER_ENERGY_WIDTH = 3f;
    /** Card separation and localized icon-light reach. */
    public static final float CARD_SHADOW_SPREAD = 6f;
    public static final float CARD_SHADOW_OPACITY = 0.55f;
    public static final float ICON_ENERGY_REACH = 34f;
    /** Hover is motion: a small lift and scale, fast. */
    public static final float CARD_HOVER_LIFT = 1.5f;
    public static final float CARD_HOVER_SCALE = 0.005f;
    public static final float ICON_HOVER_SCALE = 0.03f;
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
    /** Surface relief from top edge to lower face: darkening only, never a sheen. */
    public static final float SURFACE_DEPTH = 0.16f;

    private static final Shadow PANEL_SHADOW = Shadow.PANEL.withStrength(SHADOW_STRENGTH);
    private static final Shadow OVERLAY_SHADOW = Shadow.DROPDOWN.withStrength(OVERLAY_SHADOW_STRENGTH);
    private static final Shadow MODAL_SHADOW = Shadow.MODAL.withStrength(MODAL_SHADOW_STRENGTH);
    // Mutable paints are intentionally shared: the UI renders on one thread and
    // each is consumed before the next helper mutates it. This avoids a hot-path allocation.
    private static final Paint ENERGY_PAINT = new Paint();
    private static final Paint PAGE_PAINT = new Paint();

    /** Tight light behind a selected navigation marker. */
    public static void navigationGlow(Renderer ui, Rect marker, float selected) {
        glow(ui, marker, Skin.pill(marker), CONTROL_GLOW_RADIUS, selected, 0.90f);
    }

    /** One small falloff around an enabled switch track. */
    public static void toggleGlow(Renderer ui, Rect track, float enabled) {
        glow(ui, track, Skin.pill(track), CONTROL_GLOW_RADIUS, enabled, 0.60f);
    }

    /** Selected icon controls use the smallest accent falloff in the system. */
    public static void selectedButtonGlow(Renderer ui, Rect area, float radius, float selected) {
        glow(ui, area, radius, CONTROL_GLOW_RADIUS, selected, 0.60f);
    }

    /** A localized active-state light, normally behind an icon or state marker. */
    public static void stateGlow(Renderer ui, Rect source, float radius, float active) {
        glow(ui, source, radius, CONTROL_GLOW_RADIUS, active, 0.85f);
    }

    /** Tiny halo for keyboard focus; pointer hover deliberately does not trigger it. */
    public static void focusGlow(Renderer ui, Rect area, float radius, float focus) {
        glow(ui, area, radius, CONTROL_GLOW_RADIUS, clamp(focus), 0.60f);
    }

    /** Tight edge falloff for enabled, selected or focused components. */
    public static void energyGlow(Renderer ui, Rect area, float radius, float energy) {
        glow(ui, area, radius, GLOW_RADIUS, clamp(energy), 0.70f);
    }

    /** A one-shot hairline ring for a meaningful enable/disable event; no bloom. */
    public static void activationPulse(Renderer ui, Rect area, float radius,
            float progress, boolean activating) {
        float phase = clamp(progress);
        if (phase >= 0.999f) {
            return;
        }
        float remaining = 1f - phase;
        float expansion = (1f - remaining * remaining) * 3f;
        int colour = activating ? Skin.ACTIVE_RED : Skin.BASE_RED;
        ui.outline(area.x - expansion, area.y - expansion,
                area.right() + expansion, area.bottom() + expansion,
                radius + expansion, 1f,
                Skin.scaleAlpha(colour, remaining * (activating ? 0.80f : 0.45f)));
    }

    /** One thin inner red band just inside an energized perimeter. */
    public static void innerEnergy(Renderer ui, Rect area, float radius, float energy) {
        float shown = clamp(energy);
        if (shown <= 0.001f) {
            return;
        }
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.radius(radius, area),
                INNER_ENERGY_WIDTH, Skin.scaleAlpha(Skin.BASE_RED, shown * 0.14f));
    }

    /** Small dark-red radial light contained by a component, behind an active icon. */
    public static void energyWash(Renderer ui, Rect area, float radius,
            float centreX, float centreY, float reach, float energy) {
        float shown = clamp(energy);
        if (shown <= 0.001f) {
            return;
        }
        int centre = Skin.scaleAlpha(Skin.RED_GLOW, shown * 0.18f);
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).rounded(area.x, area.y, area.right(), area.bottom(),
                    Skin.radius(radius, area), ENERGY_PAINT.radial(centreX, centreY, 0f,
                            Math.max(1f, reach), centre, 0x00841C24));
        } else {
            float end = Math.min(area.right(), area.x + Math.max(24f, reach));
            ui.gradient(area.x, area.y, end, area.bottom(), Skin.radius(radius, area),
                    centre, 0x00841C24);
        }
    }

    /** Soft top/bottom mask for clipped scrolling content. */
    public static void scrollEdgeFade(Renderer ui, Rect viewport, boolean top, boolean bottom) {
        float depth = Math.min(18f, viewport.height * 0.12f);
        int shade = Skin.scaleAlpha(Skin.BACKGROUND, 0.72f);
        if (top) {
            ui.gradient(viewport.x, viewport.y, viewport.right(), viewport.y + depth,
                    0f, shade, 0x00050607);
        }
        if (bottom) {
            ui.gradient(viewport.x, viewport.bottom() - depth, viewport.right(), viewport.bottom(),
                    0f, 0x00050607, shade);
        }
    }

    /** Flat dark material with a slight darkening toward the lower face; hover lifts one step. */
    public static void layeredSurface(Renderer ui, Rect area, float radius, int face, float hover) {
        int top = Skin.mix(face, 0xFFFFFFFF, clamp(hover) * 0.025f);
        int bottom = Skin.mix(top, 0xFF000000, SURFACE_DEPTH);
        ui.gradient(area.x, area.y, area.right(), area.bottom(), radius, top, bottom);
    }

    /** The content plane: near-black charcoal, no coloured light. */
    public static void pageDepth(Renderer ui, Rect area, float outerRadius) {
        float corner = Skin.radius(outerRadius, area);
        if (ui instanceof FathomRenderer) {
            // This plane owns only the hub's bottom-right corner. A square fill
            // here used to paint back over the rounded parent and leave a tiny
            // dark protrusion at the outer corner.
            ((FathomRenderer) ui).rounded(area.x, area.y, area.right(), area.bottom(),
                    0f, 0f, corner, 0f,
                    PAGE_PAINT.vertical(area.y, area.bottom(), Skin.RECESSED, Skin.BACKGROUND));
        } else {
            ui.gradient(area.x, area.y, area.right(), area.bottom(), corner,
                    Skin.RECESSED, Skin.BACKGROUND);
            // Restore the two inner seam corners without disturbing the outer
            // rounded corner in the compatibility backend.
            ui.gradient(area.x, area.y, area.x + corner, area.bottom(), 0f,
                    Skin.RECESSED, Skin.BACKGROUND);
        }
    }

    /** Brighten text, an icon or a border by the shared hover amount. */
    public static int hoverBrighten(int colour, float hover) {
        return Skin.mix(colour, 0xFFFFFFFF, clamp(hover) * HOVER_BRIGHTNESS);
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

    private static void glow(Renderer ui, Rect area, float radius, float feather,
            float strength, float opacityScale) {
        float alpha = GLOW_OPACITY * opacityScale * clamp(strength);
        if (alpha <= 0.001f) {
            return;
        }
        int colour = Skin.scaleAlpha(Skin.RED_GLOW, alpha);
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).glow(area.x, area.y, area.right(), area.bottom(), radius, feather, colour);
        } else {
            // The compatibility renderer uses its one cached falloff atlas.
            ui.shadow(area.x, area.y, area.right(), area.bottom(), radius, feather * 0.55f, colour);
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
