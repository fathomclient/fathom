package net.fathom.optimizations.fabric.gfx;

import java.util.HashMap;
import java.util.Map;

/**
 * The one small texture every rectangle in Fathom's interface is made of.
 *
 * <h2>Anti-aliasing without a shader</h2>
 *
 * <p>1.8.9 gives us textured triangles and no fragment shader we are willing to
 * require. But a rounded corner is a <em>coverage function</em>, and a coverage
 * function can live in a texture: one quarter disc, computed once by
 * supersampling, sampled bilinearly. A corner of any radius — 3 units or 40 — is
 * that same quarter disc on a quad of the right size, and the edge it produces
 * is as smooth as the sampler, which is smooth enough that nobody can tell it
 * from a real rasteriser.
 *
 * <p>The old renderer built corners out of one integer-height row per pixel of
 * radius. That is a staircase, and at GUI scale 3 it was a staircase magnified
 * three times.
 *
 * <h2>What is in the sheet</h2>
 *
 * <ul>
 *   <li><b>White.</b> A solid block, so untextured fills come from the same
 *       texture as everything else and never break a batch.
 *   <li><b>The disc.</b> 128×128 of coverage: 1 inside the quarter circle, 0
 *       outside, and a real fraction on the boundary.
 *   <li><b>The glow.</b> A softer disc for the corner of a shadow.
 *   <li><b>The ramp.</b> One row, opaque to transparent, for a shadow's edges.
 * </ul>
 */
final class Shapes {

    static final int SHEET = 512;

    /** The solid block. */
    static final int WHITE_X = 2;
    static final int WHITE_Y = 2;
    static final int WHITE_SIZE = 4;

    /** The quarter disc. Its centre is at its top-left corner. */
    static final int DISC_X = 8;
    static final int DISC_Y = 8;
    static final int DISC_SIZE = 128;

    /** The soft quarter disc, for shadow corners. */
    static final int GLOW_X = 144;
    static final int GLOW_Y = 8;
    static final int GLOW_SIZE = 64;

    /** A whole disc, for stamping round-capped strokes along a curve. */
    static final int DOT_X = 144;
    static final int DOT_Y = 80;
    static final int DOT_SIZE = 64;

    /** One row: opaque at the left, transparent at the right. */
    static final int RAMP_X = 8;
    static final int RAMP_Y = 144;
    static final int RAMP_SIZE = 64;

    private final Atlas atlas;
    private final Map<Integer, Mask> exactDiscs = new HashMap<Integer, Mask>();
    private final Map<Integer, Mask> exactRings = new HashMap<Integer, Mask>();
    private final Map<Integer, Mask> exactNotches = new HashMap<Integer, Mask>();

    /** One framebuffer-sized coverage mask in the shared shape atlas. */
    static final class Mask {
        final int x;
        final int y;
        final int size;

        Mask(int x, int y, int size) {
            this.x = x;
            this.y = y;
            this.size = size;
        }
    }

    Shapes(Atlas atlas) {
        this.atlas = atlas;
    }

    /** Fill the sheet. Called once, when the renderer wakes up. */
    void build() {
        exactDiscs.clear();
        exactRings.clear();
        exactNotches.clear();
        byte[] white = new byte[WHITE_SIZE * WHITE_SIZE];
        for (int index = 0; index < white.length; index++) {
            white[index] = (byte) 0xFF;
        }
        atlas.uploadCoverage(white, WHITE_X, WHITE_Y, WHITE_SIZE, WHITE_SIZE);

        atlas.uploadCoverage(disc(DISC_SIZE), DISC_X, DISC_Y, DISC_SIZE, DISC_SIZE);
        atlas.uploadCoverage(glow(GLOW_SIZE), GLOW_X, GLOW_Y, GLOW_SIZE, GLOW_SIZE);
        atlas.uploadCoverage(ramp(RAMP_SIZE), RAMP_X, RAMP_Y, RAMP_SIZE, 1);
        atlas.uploadCoverage(dot(DOT_SIZE), DOT_X, DOT_Y, DOT_SIZE, DOT_SIZE);
        atlas.reserveRows(152);
    }

    /** A corner mask rasterised at its final device-pixel radius. */
    Mask exactDisc(int pixels) {
        int size = Math.max(1, Math.min(96, pixels));
        Mask found = exactDiscs.get(Integer.valueOf(size));
        if (found != null) {
            return found;
        }
        int[] at = atlas.allocate(size, size);
        if (at == null) {
            return null;
        }
        atlas.uploadCoverage(exactDiscCoverage(size), at[0], at[1], size, size);
        found = new Mask(at[0], at[1], size);
        exactDiscs.put(Integer.valueOf(size), found);
        return found;
    }

    /**
     * The corner a rounded rectangle does <em>not</em> cover.
     *
     * <p>Everything else here masks in what should be drawn. This masks in what
     * should be painted over: the little square outside the curve. It is how an
     * image gets rounded corners without the renderer being able to sample two
     * textures at once -- draw the picture square, then paint its four corners
     * out in whatever is behind it.
     */
    Mask exactNotch(int pixels) {
        int size = Math.max(1, Math.min(96, pixels));
        Mask found = exactNotches.get(Integer.valueOf(size));
        if (found != null) {
            return found;
        }
        int[] at = atlas.allocate(size, size);
        if (at == null) {
            return null;
        }
        byte[] coverage = exactDiscCoverage(size);
        for (int index = 0; index < coverage.length; index++) {
            coverage[index] = (byte) (255 - (coverage[index] & 0xFF));
        }
        atlas.uploadCoverage(coverage, at[0], at[1], size, size);
        found = new Mask(at[0], at[1], size);
        exactNotches.put(Integer.valueOf(size), found);
        return found;
    }

    /** The antialiased quarter-ring for one outline corner. */
    Mask exactRing(int pixels, int thickness) {
        int size = Math.max(1, Math.min(96, pixels));
        int width = Math.max(1, Math.min(size, thickness));
        int key = size * 128 + width;
        Mask found = exactRings.get(Integer.valueOf(key));
        if (found != null) {
            return found;
        }
        int[] at = atlas.allocate(size, size);
        if (at == null) {
            return null;
        }
        atlas.uploadCoverage(exactRingCoverage(size, width), at[0], at[1], size, size);
        found = new Mask(at[0], at[1], size);
        exactRings.put(Integer.valueOf(key), found);
        return found;
    }

    /**
     * Coverage of a quarter circle centred on the sheet's top-left corner.
     *
     * <p>Supersampled 4×4 per texel rather than computed from the distance to
     * the centre, because the analytic version is only right for a circle
     * sampled at its own resolution and this one is stretched to every radius
     * the interface uses.
     */
    private static byte[] disc(int size) {
        byte[] coverage = new byte[size * size];
        final int samples = 4;
        float radius = size;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int inside = 0;
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        float px = x + (sx + 0.5f) / samples;
                        float py = y + (sy + 0.5f) / samples;
                        if (px * px + py * py <= radius * radius) {
                            inside++;
                        }
                    }
                }
                coverage[y * size + x] = (byte) (inside * 255 / (samples * samples));
            }
        }
        return coverage;
    }

    private static byte[] exactDiscCoverage(int size) {
        return exactCoverage(size, 0);
    }

    /**
     * An antialiased quarter-ring whose core is solid at every angle.
     *
     * <p>Not area coverage, which is what {@link #exactCoverage} measures and
     * what a fill wants. A stroke wants a distance field: how far each texel is
     * from the middle of the band, solid until the last half pixel and fading
     * over it. The difference is the whole of "the corners look different from
     * the sides".
     *
     * <p>Area coverage is exactly right and still looks wrong on a curve. A
     * two-pixel band lying along the grid covers whole pixels and arrives at
     * full strength; the same band at 45 degrees cuts every pixel at an angle
     * and no texel is ever entirely inside it, so the arc peaked at about half
     * the brightness of the straight edge it ran into. Measured on the hub's
     * cards: 195 along the top, 100 at the corner's diagonal. A distance field
     * reaches 255 down the centre of the stroke wherever that centre happens to
     * be, so the corner arrives at the weight of the side, and only the fringe
     * is soft -- which is what antialiasing is meant to be for.
     */
    private static byte[] exactRingCoverage(int size, int width) {
        byte[] coverage = new byte[size * size];
        float middle = size - width / 2f;
        // A hairline needs a wider solid core than its own width suggests. A
        // one-pixel band saturates only where the curve happens to pass through
        // a pixel's centre, and everywhere else it lands short -- so a hairline
        // corner measured 36 against its straight edge's 49 while a two-pixel
        // one already matched at 195. Half a pixel of extra core costs a curve
        // that reads a shade softer at its outer fringe and buys one that
        // reads at the same weight as the edge it joins.
        float half = Math.max(width / 2f, 1f);
        float fade = width <= 1 ? 0.25f : 0.5f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float px = x + 0.5f;
                float py = y + 0.5f;
                float distance = (float) Math.sqrt(px * px + py * py);
                // Solid through the core, then a fade over the last half
                // pixel. Each framebuffer pixel samples exactly its own texel
                // (`GlRenderer.maskCorner`), so nothing softens the stroke a
                // second time: at 0.5 a band of two or more pixels puts
                // exactly its own width of ink across every radius, the same
                // as the straight side it joins. The hairline already has the
                // extra core above, so its fade is a quarter pixel.
                float inside = half + fade - Math.abs(distance - middle);
                int value = Math.round(Math.max(0f, Math.min(1f, inside)) * 255f);
                coverage[y * size + x] = (byte) value;
            }
        }
        return coverage;
    }

    /**
     * Eight-by-eight subpixel coverage at the size the corner reaches the
     * framebuffer.
     *
     * <p>Fills only. A stroke is {@link #exactRingCoverage} instead.
     */
    private static byte[] exactCoverage(int size, int ringWidth) {
        byte[] coverage = new byte[size * size];
        final int samples = 8;
        float outer = size;
        float inner = ringWidth == 0 ? 0f : Math.max(0f, size - ringWidth);
        float outerSquared = outer * outer;
        float innerSquared = inner * inner;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int inside = 0;
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        float px = x + (sx + 0.5f) / samples;
                        float py = y + (sy + 0.5f) / samples;
                        float distance = px * px + py * py;
                        if (distance <= outerSquared && (ringWidth == 0 || distance >= innerSquared)) {
                            inside++;
                        }
                    }
                }
                coverage[y * size + x] = (byte) (inside * 255 / (samples * samples));
            }
        }
        return coverage;
    }


    /**
     * A whole circle, filling the region.
     *
     * <p>What a round pen nib looks like. Stamped along an arc it draws a
     * stroke with round caps and real anti-aliasing, which is how the rounded
     * outlines in this interface are drawn without a shader and without the
     * stencil buffer.
     */
    private static byte[] dot(int size) {
        byte[] coverage = new byte[size * size];
        final int samples = 4;
        float radius = size / 2f;
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int inside = 0;
                for (int sy = 0; sy < samples; sy++) {
                    for (int sx = 0; sx < samples; sx++) {
                        float px = x + (sx + 0.5f) / samples - radius;
                        float py = y + (sy + 0.5f) / samples - radius;
                        if (px * px + py * py <= radius * radius) {
                            inside++;
                        }
                    }
                }
                coverage[y * size + x] = (byte) (inside * 255 / (samples * samples));
            }
        }
        return coverage;
    }

    /** The same corner, faded by distance instead of cut at the edge. */
    private static byte[] glow(int size) {
        byte[] coverage = new byte[size * size];
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                float distance = (float) Math.sqrt(x * x + y * y) / size;
                coverage[y * size + x] = (byte) Math.round(255f * falloff(distance));
            }
        }
        return coverage;
    }

    /** One row of the same falloff, for the straight sides of a shadow. */
    private static byte[] ramp(int size) {
        byte[] coverage = new byte[size];
        for (int x = 0; x < size; x++) {
            coverage[x] = (byte) Math.round(255f * falloff((x + 0.5f) / size));
        }
        return coverage;
    }

    /**
     * How a shadow fades.
     *
     * <p>Quadratic rather than linear: a linear shadow has a visible edge where
     * it reaches zero, because the eye is good at spotting a discontinuity in
     * the first derivative and bad at spotting one in the second.
     */
    private static float falloff(float t) {
        float x = t < 0f ? 0f : (t > 1f ? 1f : t);
        float inverse = 1f - x;
        return inverse * inverse;
    }
}
