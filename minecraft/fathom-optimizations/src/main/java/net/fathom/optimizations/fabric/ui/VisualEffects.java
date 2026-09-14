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
    /** The signature bracket is a deliberate edge, not another hairline. */
    public static final float SIGNATURE_STROKE = 1.35f;
    /** Neutral surface relief from top edge to lower face. */
    public static final float SURFACE_DEPTH = 0.045f;

    private static final Shadow PANEL_SHADOW = Shadow.PANEL.withStrength(SHADOW_STRENGTH);
    private static final Shadow OVERLAY_SHADOW = Shadow.DROPDOWN.withStrength(OVERLAY_SHADOW_STRENGTH);
    private static final Shadow MODAL_SHADOW = Shadow.MODAL.withStrength(MODAL_SHADOW_STRENGTH);

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

    /** A localized active-state light, normally placed behind an icon or state marker. */
    public static void stateGlow(Renderer ui, Rect source, float radius, float active, float hover) {
        glow(ui, source, radius, GLOW_RADIUS * 0.72f,
                active * (0.72f + clamp(hover) * 0.28f), 0.72f);
    }

    /** Tiny halo for keyboard focus; pointer hover deliberately does not trigger it. */
    public static void focusGlow(Renderer ui, Rect area, float radius, float focus) {
        glow(ui, area, radius, GLOW_RADIUS * 0.46f, clamp(focus), 0.42f);
    }

    /**
     * Fathom's signature depth bracket: a short edge segment that steps inward
     * twice. NanoVG receives one vector path; the compatibility renderer draws
     * the same three orthogonal segments from cached solid geometry.
     */
    public static void depthBracket(Renderer ui, Rect area, boolean left, float strength, int colour) {
        float shown = clamp(strength);
        if (shown <= 0.001f || area.width < Skin.SIGNATURE_LENGTH + Skin.S4) {
            return;
        }
        float step = Skin.SIGNATURE_STEP;
        float y = Skin.snap(ui, area.y + Skin.SIGNATURE_INSET);
        float inner = left
                ? area.x + Math.max(Skin.R_CONTROL, Skin.S3)
                : area.right() - Math.max(Skin.R_CONTROL, Skin.S3);
        float direction = left ? 1f : -1f;
        float length = Skin.SIGNATURE_LENGTH * (0.58f + shown * 0.42f);
        float start = inner + direction * length;
        int ink = Skin.scaleAlpha(colour, shown);

        if (ui instanceof FathomRenderer) {
            FathomRenderer vector = (FathomRenderer) ui;
            vector.beginPath();
            vector.moveTo(start, y);
            vector.lineTo(inner + direction * step * 2f, y);
            vector.lineTo(inner + direction * step * 2f, y + step);
            vector.lineTo(inner, y + step);
            vector.lineTo(inner, y + step * 2f);
            vector.strokePath(SIGNATURE_STROKE, ink);
            return;
        }

        float stroke = Math.max(Skin.HAIRLINE, SIGNATURE_STROKE);
        float firstA = Math.min(start, inner + direction * step * 2f);
        float firstB = Math.max(start, inner + direction * step * 2f);
        float secondA = Math.min(inner, inner + direction * step * 2f);
        float secondB = Math.max(inner, inner + direction * step * 2f);
        ui.fill(firstA, y, firstB, y + stroke, ink);
        ui.fill(inner + direction * step * 2f - stroke / 2f, y,
                inner + direction * step * 2f + stroke / 2f, y + step, ink);
        ui.fill(secondA, y + step, secondB, y + step + stroke, ink);
        ui.fill(inner - stroke / 2f, y + step, inner + stroke / 2f, y + step * 2f, ink);
    }

    /** A small square depth marker, used where a full bracket would crowd a circular control. */
    public static void depthMarker(Renderer ui, float centreX, float centreY, float strength) {
        float shown = clamp(strength);
        if (shown <= 0.001f) {
            return;
        }
        float size = 4f;
        float left = Skin.snap(ui, centreX - size / 2f);
        float top = Skin.snap(ui, centreY - size / 2f);
        float right = left + size;
        float bottom = top + size;
        glow(ui, left, top, right, bottom, Skin.R_SMALL, GLOW_RADIUS * 0.72f,
                shown * 0.72f, 0.72f);
        ui.fill(left, top, right, bottom, Skin.scaleAlpha(Skin.ACCENT, shown));
        ui.fill(left + 1f, top + 1f, right, bottom,
                Skin.scaleAlpha(Skin.PANEL, shown * 0.72f));
    }

    /** Directional material separation: nearly imperceptible light above, depth below. */
    public static void layeredSurface(Renderer ui, Rect area, float radius, int face, float hover) {
        int top = Skin.mix(face, 0xFFFFFFFF, 0.018f + clamp(hover) * 0.012f);
        int bottom = Skin.mix(face, 0xFF000000, SURFACE_DEPTH);
        ui.gradient(area.x, area.y, area.right(), area.bottom(), radius, top, bottom);
    }

    /** Near-black environmental falloff for a large content plane. */
    public static void pageDepth(Renderer ui, Rect area) {
        int top = Skin.mix(Skin.PANEL, 0xFFFFFFFF, 0.010f);
        int bottom = Skin.mix(Skin.PANEL, 0xFF000000, 0.052f);
        ui.gradient(area.x, area.y, area.right(), area.bottom(), 0f, top, bottom);
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
        glow(ui, area.x, area.y, area.right(), area.bottom(), radius, feather, strength, opacityScale);
    }

    private static void glow(Renderer ui, float left, float top, float right, float bottom,
            float radius, float feather, float strength, float opacityScale) {
        float alpha = GLOW_OPACITY * opacityScale * Math.min(1.45f, Math.max(0f, strength));
        if (alpha <= 0.001f) {
            return;
        }
        int colour = Skin.scaleAlpha(Skin.ACCENT_DEEP, alpha);
        if (ui instanceof FathomRenderer) {
            ((FathomRenderer) ui).glow(left, top, right, bottom, radius, feather, colour);
        } else {
            // The compatibility renderer uses its one cached falloff atlas.
            // This remains a single batched shadow primitive and creates no
            // texture or CPU-rasterized asset at interaction time.
            ui.shadow(left, top, right, bottom, radius, feather * 0.55f, colour);
        }
    }

    private static float clamp(float value) {
        return Math.max(0f, Math.min(1f, value));
    }
}
