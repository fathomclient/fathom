package net.fathom.optimizations.fabric.ui;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Shadow;

/**
 * Fathom's restrained depth vocabulary.
 *
 * <p>On NanoVG, accent light is one box gradient and top lighting is one
 * vertical gradient. The compatibility renderer uses its already-cached
 * falloff atlas. Shadows use the shared {@link Shadow} presets. No helper reads
 * the framebuffer, allocates an image, or creates a texture per frame. Keeping the strengths here also prevents a page made
 * of many controls from becoming brighter than a modal simply because each
 * component invented its own opacity.
 *
 * <p>Red light is semantic: these helpers are for enabled, selected, focused,
 * or primary interaction states. Ordinary elevation remains neutral black.
 */
public final class VisualEffects {

    private VisualEffects() {}

    /** Feather width of the ordinary accent bloom; reaches only a few pixels. */
    public static final float GLOW_RADIUS = 7f;
    /** Maximum resting opacity of the dark-red bloom. */
    public static final float GLOW_OPACITY = 0.10f;
    /** Additional light mixed into an edge or icon at full hover. */
    public static final float HOVER_BRIGHTNESS = 0.14f;
    /** How much hover can strengthen an already-semantic accent glow. */
    public static final float HOVER_GLOW_GAIN = 0.45f;
    /** Strength of the neutral shadow under an ordinary large panel. */
    public static final float SHADOW_STRENGTH = 0.72f;
    /** Floating controls may separate more clearly than resting panels. */
    public static final float OVERLAY_SHADOW_STRENGTH = 0.92f;
    /** Modals are deepest, but still use one restrained falloff. */
    public static final float MODAL_SHADOW_STRENGTH = 0.88f;
    /** Shared intensity of the keyboard-focus ring. */
    public static final float FOCUS_INTENSITY = 0.78f;
    /** White added at the top of a surface before fading to transparent. */
    public static final float TOP_LIGHT_OPACITY = 0.04f;

    private static final Shadow PANEL_SHADOW = Shadow.PANEL.withStrength(SHADOW_STRENGTH);
    private static final Shadow OVERLAY_SHADOW = Shadow.DROPDOWN.withStrength(OVERLAY_SHADOW_STRENGTH);
    private static final Shadow MODAL_SHADOW = Shadow.MODAL.withStrength(MODAL_SHADOW_STRENGTH);

    /** A cheap enabled-card bloom; hover only strengthens light that already means "on". */
    public static void moduleGlow(Renderer ui, Rect area, float radius, float enabled, float hover) {
        glow(ui, area, radius, GLOW_RADIUS,
                enabled * (1f + clamp(hover) * HOVER_GLOW_GAIN), 1f);
    }

    /** Soft light behind a selected navigation marker, never behind its icon. */
    public static void navigationGlow(Renderer ui, Rect marker, float selected) {
        glow(ui, marker, Skin.pill(marker), GLOW_RADIUS * 0.72f, selected, 0.72f);
    }

    /** One small falloff around an enabled switch track. */
    public static void toggleGlow(Renderer ui, Rect track, float enabled, float hover) {
        glow(ui, track, Skin.pill(track), GLOW_RADIUS * 0.58f,
                enabled * (1f + clamp(hover) * HOVER_GLOW_GAIN), 0.62f);
    }

    /** Primary actions gain a low hover bloom without turning into luminous slabs. */
    public static void primaryButtonGlow(Renderer ui, Rect area, float radius, float hover) {
        glow(ui, area, radius, GLOW_RADIUS * 0.78f, clamp(hover), 0.72f);
    }

    /** Selected icon controls use the smallest accent bloom in the system. */
    public static void selectedButtonGlow(Renderer ui, Rect area, float radius, float selected, float hover) {
        glow(ui, area, radius, GLOW_RADIUS * 0.58f,
                selected * (0.65f + clamp(hover) * 0.35f), 0.60f);
    }

    /**
     * Faint light from the top of a surface. The full-size path shares the
     * surface radius, avoiding a mismatched inner strip at rounded corners.
     */
    public static void topLight(Renderer ui, float left, float top, float right, float bottom,
            float radius, int face, float strength) {
        float faceAlpha = ((face >>> 24) & 0xFF) / 255f;
        float alpha = TOP_LIGHT_OPACITY * faceAlpha * clamp(strength);
        if (alpha <= 0.001f) {
            return;
        }
        ui.gradient(left, top, right, bottom, radius, Skin.argb(alpha, 0xFFFFFF), 0x00FFFFFF);
    }

    /** Brighten an existing neutral or semantic colour by the shared hover amount. */
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
        float alpha = GLOW_OPACITY * opacityScale * Math.min(1.45f, Math.max(0f, strength));
        if (alpha <= 0.001f) {
            return;
        }
        int colour = Skin.scaleAlpha(Skin.ACCENT_DEEP, alpha);
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).glow(area.x, area.y, area.right(), area.bottom(), radius,
                    feather, colour);
        } else {
            // The compatibility renderer uses its one cached falloff atlas.
            // This remains a single batched shadow primitive and creates no
            // texture or CPU-rasterized asset at interaction time.
            ui.shadow(area.x, area.y, area.right(), area.bottom(), radius,
                    feather * 0.55f, colour);
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
