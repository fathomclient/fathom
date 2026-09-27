package net.fathom.optimizations.fabric.gfx;

import java.awt.AlphaComposite;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Rectangle;
import java.awt.geom.AffineTransform;
import java.awt.geom.Area;
import java.awt.geom.Path2D;
import java.awt.geom.RoundRectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

import net.fathom.optimizations.generated.BrandGeometry;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * The Surface wordmark and the Sounding Line, rasterised at the size they are
 * drawn at.
 *
 * <h2>The geometry is not here</h2>
 *
 * <p>The letter outlines are {@link BrandGeometry}, generated from
 * {@code desktop/src/lib/brand/surface.ts} -- the same function the launcher's
 * wordmark and the asset build draw from. What this class does is the part that
 * depends on the pixel size, and it does it the way {@code layoutWordmark} does:
 * each letter starts on a whole pixel, the surface is a pixel row, the
 * displacement below it is a whole number of pixels, and how much of the
 * treatment a size carries is decided by the same two tiers.
 *
 * <h2>Once per size</h2>
 *
 * <p>Java2D fills the outlines anti-aliased into a sheet of its own, the first
 * time a size is asked for, and the result is kept. Drawing the wordmark after
 * that is one textured quad: no path work, no allocation, one texture bind. The
 * menu asks for two sizes and the hub for one, so the sheet holds a handful of
 * entries for the life of the window. It is its own sheet, not the icon sheet,
 * because a large wordmark on a high-density display is wider than the icons'
 * 512 pixels.
 *
 * <h2>Colours are baked in</h2>
 *
 * <p>Unlike an icon, the wordmark is three inks -- ivory above the surface, a
 * warmer ivory below it and, at full size, a one-pixel bronze edge -- so the
 * raster carries them and is drawn untinted. The inks are the brand tokens.
 */
final class BrandArt {

    private static final int SHEET = 1024;
    /** Room the baked shadow needs, in device pixels. */
    private static final int SHADOW_PAD = 2;
    private static final float CAP = Tokens.BRAND_CAP;

    /** Where one raster lives on the sheet, and its size in device pixels. */
    static final class Entry {
        final float u0;
        final float v0;
        final float u1;
        final float v1;
        final int width;
        final int height;
        /** The surface's pixel row from the top, for callers that align to it. */
        final int surfaceY;

        Entry(float u0, float v0, float u1, float v1, int width, int height, int surfaceY) {
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.width = width;
            this.height = height;
            this.surfaceY = surfaceY;
        }
    }

    private static final Entry EMPTY = new Entry(0f, 0f, 0f, 0f, 0, 0, 0);

    private final Atlas atlas;
    private final Map<Integer, Entry> wordmarks = new HashMap<Integer, Entry>();
    private final Map<Integer, Entry> soundings = new HashMap<Integer, Entry>();
    /** The last lookup, which is nearly every lookup: a screen draws one size. */
    private int lastKey = -1;
    private Entry lastEntry;

    BrandArt(Atlas.BeforeUpload beforeUpload) {
        this.atlas = new Atlas(SHEET, beforeUpload);
    }

    Atlas atlas() {
        return atlas;
    }

    void create() {
        atlas.create();
    }

    void reset() {
        forget();
        atlas.delete();
        atlas.create();
    }

    void delete() {
        forget();
        atlas.delete();
    }

    private void forget() {
        wordmarks.clear();
        soundings.clear();
        lastKey = -1;
        lastEntry = null;
    }

    // -----------------------------------------------------------------------
    // The wordmark
    // -----------------------------------------------------------------------

    /** The wordmark at a cap height in device pixels, rasterising if needed. */
    Entry wordmark(int capPixels) {
        int cap = Math.max(4, Math.min(256, capPixels));
        int key = cap;
        if (key == lastKey && lastEntry != null) {
            return lastEntry;
        }
        Entry found = wordmarks.get(Integer.valueOf(cap));
        if (found == null) {
            found = rasteriseWordmark(cap);
            wordmarks.put(Integer.valueOf(cap), found);
        }
        lastKey = key;
        lastEntry = found;
        return found;
    }

    /** Width in device pixels for a cap height, without rasterising. */
    static int wordmarkWidth(int capPixels) {
        int cap = Math.max(4, Math.min(256, capPixels));
        float scale = cap / CAP;
        return (int) Math.ceil(BrandGeometry.WORDMARK_UNITS * scale) + shift(cap) + edge(cap);
    }

    /** The whole-pixel displacement below the surface, by tier. */
    private static int shift(int cap) {
        if (cap < Tokens.BRAND_TIER_REDUCED) {
            return 0;
        }
        if (cap < Tokens.BRAND_TIER_FULL) {
            return 1;
        }
        return Math.max(1, Math.round(Tokens.BRAND_STEM * (cap / CAP) / 6f));
    }

    /** The bronze edge detail: one pixel at full size, none below it. */
    private static int edge(int cap) {
        return cap >= Tokens.BRAND_TIER_FULL ? 1 : 0;
    }

    private Entry rasteriseWordmark(int cap) {
        float scale = cap / CAP;
        int shift = shift(cap);
        int edge = edge(cap);
        // Two pixels of room below the ink for the soft shadow.
        int width = wordmarkWidth(cap) + SHADOW_PAD;
        int height = cap + edge + SHADOW_PAD;
        int surfaceY = Math.round((CAP - Tokens.BRAND_SURFACE) * scale);

        int[] at = atlas.allocate(width, height);
        if (at == null) {
            return EMPTY;
        }

        // All six letters, and each letter alone, in pixels, y down. Each letter
        // starts on a whole pixel so the stems share one subpixel phase.
        Path2D.Float all = new Path2D.Float(Path2D.WIND_EVEN_ODD);
        Path2D.Float[] letters = new Path2D.Float[BrandGeometry.LETTER_POLYGONS.length];
        for (int index = 0; index < letters.length; index++) {
            float letterX = BrandGeometry.LETTER_X[index];
            float origin = Math.round(letterX * scale);
            Path2D.Float letter = new Path2D.Float(Path2D.WIND_EVEN_ODD);
            for (float[] polygon : BrandGeometry.LETTER_POLYGONS[index]) {
                for (int point = 0; point < polygon.length; point += 2) {
                    float x = origin + (polygon[point] - letterX) * scale;
                    float y = (CAP - polygon[point + 1]) * scale;
                    if (point == 0) {
                        letter.moveTo(x, y);
                    } else {
                        letter.lineTo(x, y);
                    }
                }
                letter.closePath();
            }
            letters[index] = letter;
            all.append(letter, false);
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);

        // The launcher's mark shadow, `drop-shadow(0 1px 2px rgba(26,16,6,.45))`:
        // one pixel down, softened by a second, fainter pass a pixel further.
        // It is what keeps ivory letters defined against a bright sky.
        // Cast by the letters as they are drawn: above the surface where they
        // are, below it where they have moved to.
        Area cast = new Area(all);
        cast.intersect(new Area(new Rectangle(0, 0, width, surfaceY)));
        Area moved = new Area(all);
        moved.transform(AffineTransform.getTranslateInstance(shift, 0));
        moved.intersect(new Area(new Rectangle(0, surfaceY, width, height - surfaceY)));
        cast.add(moved);
        graphics.setColor(new Color(26, 16, 6, 70));
        graphics.translate(0, 2);
        graphics.fill(cast);
        graphics.translate(0, -1);
        graphics.setColor(new Color(26, 16, 6, 60));
        graphics.fill(cast);
        graphics.translate(0, -1);

        if (shift == 0) {
            // Plain: the letters alone, at the air's density.
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    Tokens.BRAND_AIR_OPACITY));
            graphics.setColor(opaque(Tokens.BRAND_AIR));
            graphics.fill(all);
        } else {
            // Above the surface, never moved: the bronze edge one pixel down and
            // right, then the air over it, composited together at the air's
            // density -- the launcher draws these as one group with one opacity.
            BufferedImage air = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
            Graphics2D airGraphics = air.createGraphics();
            airGraphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
            airGraphics.clipRect(0, 0, width, surfaceY);
            if (edge > 0) {
                airGraphics.setColor(opaque(Tokens.BRAND_EDGE));
                airGraphics.translate(edge, edge);
                airGraphics.fill(all);
                airGraphics.translate(-edge, -edge);
            }
            airGraphics.setColor(opaque(Tokens.BRAND_AIR));
            airGraphics.fill(all);
            airGraphics.dispose();
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    Tokens.BRAND_AIR_OPACITY));
            graphics.drawImage(air, 0, 0, null);

            // Beneath: displaced to the right by whole pixels, the warmer ink,
            // its own density. The step where a stroke crosses stays sharp.
            graphics.setComposite(AlphaComposite.getInstance(AlphaComposite.SRC_OVER,
                    Tokens.BRAND_BENEATH_OPACITY));
            graphics.setColor(opaque(Tokens.BRAND_BENEATH));
            graphics.clipRect(0, surfaceY, width, height - surfaceY);
            graphics.translate(shift, 0);
            graphics.fill(all);
        }
        graphics.dispose();

        atlas.upload(image, at[0], at[1]);
        return new Entry(atlas.u(at[0]), atlas.v(at[1]),
                atlas.u(at[0] + width), atlas.v(at[1] + height), width, height, surfaceY);
    }

    // -----------------------------------------------------------------------
    // The Sounding Line
    // -----------------------------------------------------------------------

    /**
     * One stem crossing the surface and stepping right beneath it, at a height
     * in device pixels. {@code soundingGeometry} in {@code surface.ts}: the stem
     * proportioned like the wordmark's and never thinner than two pixels.
     */
    Entry sounding(int heightPixels) {
        int height = Math.max(8, Math.min(128, heightPixels));
        int key = 100000 + height;
        if (key == lastKey && lastEntry != null) {
            return lastEntry;
        }
        Entry found = soundings.get(Integer.valueOf(height));
        if (found == null) {
            found = rasteriseSounding(height);
            soundings.put(Integer.valueOf(height), found);
        }
        lastKey = key;
        lastEntry = found;
        return found;
    }

    private Entry rasteriseSounding(int height) {
        int stem = Math.max(2, Math.round(height * Tokens.BRAND_STEM / CAP));
        int step = Math.max(1, Math.round(stem / 6f));
        int surfaceY = Math.round(height * (CAP - Tokens.BRAND_SURFACE) / CAP);
        float radius = Math.min(stem / 2f, height * Tokens.BRAND_CORNER_OUTER / CAP);
        int width = stem + step;

        int[] at = atlas.allocate(width, height);
        if (at == null) {
            return EMPTY;
        }
        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        // White, and tinted when drawn: the line is one ink, and a loading
        // state may want it quieter than the wordmark.
        graphics.setColor(Color.WHITE);
        // Rounded at the top, square where it meets the surface...
        graphics.clipRect(0, 0, width, surfaceY);
        graphics.fill(new RoundRectangle2D.Float(0f, 0f, stem, surfaceY + radius * 2f,
                radius * 2f, radius * 2f));
        // ...and square at the surface, rounded at the bottom, one step right.
        graphics.setClip(0, surfaceY, width, height - surfaceY);
        graphics.fill(new RoundRectangle2D.Float(step, surfaceY - radius * 2f, stem,
                height - surfaceY + radius * 2f, radius * 2f, radius * 2f));
        graphics.dispose();

        atlas.upload(image, at[0], at[1]);
        return new Entry(atlas.u(at[0]), atlas.v(at[1]),
                atlas.u(at[0] + width), atlas.v(at[1] + height), width, height, surfaceY);
    }

    private static Color opaque(int argb) {
        return new Color(argb & 0x00FFFFFF);
    }
}
