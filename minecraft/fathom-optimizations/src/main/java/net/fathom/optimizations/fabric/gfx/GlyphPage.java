package net.fathom.optimizations.fabric.gfx;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.font.TextAttribute;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.Arrays;
import java.util.Collections;
import java.util.HashMap;
import java.util.Map;

/**
 * One font, at one pixel size, rasterised.
 *
 * <h2>Why rasterise per size</h2>
 *
 * <p>Because it is the highest quality thing that can be done without a shader.
 * A glyph drawn from a raster made at exactly the size it will be drawn at is as
 * sharp as the font allows. A glyph drawn from a raster made at some other size
 * is either blurry or blocky.
 *
 * <p>The cost is that a page is only good for one size, and a changed UI scale
 * throws them all away. That happens when a window is resized, not while
 * anything is animating.
 *
 * <h2>Quarter-pixel positions</h2>
 *
 * <p>Advances are fractional, so the pen almost never lands on a whole pixel.
 * The first version drew every glyph from one raster, snapped to the nearest
 * whole pixel: each letter was off its true position by up to half a pixel,
 * in a different direction for each letter, which is what made a line like
 * "Changes apply instantly." look unevenly spaced -- some pairs a pixel too
 * tight, the next a pixel too loose.
 *
 * <p>Each glyph is now rasterised at up to {@link #PHASES} horizontal offsets
 * (0, ¼, ½, ¾ of a pixel) and drawn from the one nearest to where the pen really
 * is. The quad is still on whole pixels, so the raster reaches the screen
 * one-to-one and stays crisp; the fraction is inside the raster. This is how
 * browsers and DirectWrite position text. A phase is rasterised the first time
 * it is needed, once, into the atlas; drawing costs the same as before.
 *
 * <h2>Kerning</h2>
 *
 * <p>Pair adjustments from the font's own kerning, looked up once per pair and
 * kept. {@link #width} and drawing use the same {@link #kern} values, so a string
 * measures exactly as wide as it draws -- the reason the first version had no
 * kerning at all.
 */
final class GlyphPage {

    /** Horizontal raster offsets per glyph: quarter pixels. */
    static final int PHASES = 4;

    /** How hard partial coverage is pulled toward solid. See {@link #darkenStems}. */
    private static final float STEM_GAMMA = 0.8f;

    /** What is rasterised up front, at phase zero. */
    private static final char FIRST = 32;
    private static final char LAST = 126;
    private static final int ASCII = LAST - FIRST + 1;

    private final Font font;
    private final Font kerningFont;
    private final Atlas atlas;
    private final FontRenderContext context;
    private final Map<Character, Glyph> glyphs = new HashMap<Character, Glyph>(256);
    /** Kerning for printable-ASCII pairs; NaN until first asked for. */
    private final float[] asciiKerning = new float[ASCII * ASCII];
    private final float ascent;
    private final float descent;
    private final float lineHeight;

    /** One raster of a glyph at one horizontal phase. */
    static final class Raster {
        /** Ink offset from the pen's whole pixel, in device pixels. */
        final float offsetX;
        final float offsetY;
        final float width;
        final float height;
        final float u0;
        final float v0;
        final float u1;
        final float v1;

        Raster(float offsetX, float offsetY, float width, float height,
                float u0, float v0, float u1, float v1) {
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.width = width;
            this.height = height;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
        }
    }

    /** One character: its advance and, lazily, its rasters. */
    static final class Glyph {
        final char character;
        final float advance;
        final boolean blank;
        final Raster[] rasters;

        Glyph(char character, float advance, boolean blank) {
            this.character = character;
            this.advance = advance;
            this.blank = blank;
            this.rasters = blank ? null : new Raster[PHASES];
        }
    }

    GlyphPage(Font base, float pixelSize, Atlas atlas) {
        this.font = base.deriveFont(Font.PLAIN, pixelSize);
        Map<TextAttribute, Object> kerning = Collections.<TextAttribute, Object>singletonMap(
                TextAttribute.KERNING, TextAttribute.KERNING_ON);
        this.kerningFont = font.deriveFont(kerning);
        this.atlas = atlas;
        // Anti-aliased, with fractional metrics, which is what makes advances
        // real numbers instead of the integers that made the old text drift.
        this.context = new FontRenderContext(null, true, true);
        Arrays.fill(asciiKerning, Float.NaN);

        // The face's own metrics, unrounded. FontMetrics answers in whole
        // pixels, each rounded on its own at the rasterised size, so every
        // type and every scale carried a different half-pixel error into
        // centring: two labels in two equal controls sat at different heights.
        // Layout works in the true values; only the baseline a line is finally
        // drawn on is rounded, once, by the renderer.
        java.awt.font.LineMetrics metrics = font.getLineMetrics("Hg", context);
        this.ascent = metrics.getAscent();
        this.descent = metrics.getDescent();
        this.lineHeight = metrics.getAscent() + metrics.getDescent() + metrics.getLeading();

        for (char c = FIRST; c <= LAST; c++) {
            Glyph glyph = glyph(c);
            if (!glyph.blank) {
                raster(glyph, 0);
            }
        }
    }

    float ascent() {
        return ascent;
    }

    float descent() {
        return descent;
    }

    float lineHeight() {
        return lineHeight;
    }

    /** The glyph for a character, measuring it if this is the first time. */
    Glyph glyph(char c) {
        Glyph found = glyphs.get(Character.valueOf(c));
        if (found != null) {
            return found;
        }
        return measure(c);
    }

    /** The raster of a glyph at a phase, rasterising it the first time. */
    Raster raster(Glyph glyph, int phase) {
        Raster found = glyph.rasters[phase];
        if (found == null) {
            found = rasterise(glyph, phase);
            glyph.rasters[phase] = found;
        }
        return found;
    }

    /**
     * The adjustment between two characters, in device pixels: negative pulls
     * {@code right} towards {@code left}.
     */
    float kern(char left, char right) {
        int a = left - FIRST;
        int b = right - FIRST;
        boolean ascii = a >= 0 && a < ASCII && b >= 0 && b < ASCII;
        if (ascii) {
            float cached = asciiKerning[a * ASCII + b];
            if (!Float.isNaN(cached)) {
                return cached;
            }
        } else {
            // Outside printable ASCII the pair table is not kept; unkerned is
            // correct, only slightly looser.
            return 0f;
        }
        float value = 0f;
        try {
            char[] pair = {left, right};
            GlyphVector laid = kerningFont.layoutGlyphVector(
                    context, pair, 0, 2, Font.LAYOUT_LEFT_TO_RIGHT);
            if (laid.getNumGlyphs() == 2) {
                float second = (float) laid.getGlyphPosition(1).getX();
                value = second - glyph(left).advance;
                // Anything larger than a third of the size is not kerning.
                if (Math.abs(value) > font.getSize2D() / 3f) {
                    value = 0f;
                }
            }
        } catch (RuntimeException ignored) {
            value = 0f;
        }
        asciiKerning[a * ASCII + b] = value;
        return value;
    }

    /** The width of a string: exactly the advances and kerning drawing will use. */
    float width(String text) {
        float total = 0f;
        char previous = 0;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            if (previous != 0) {
                total += kern(previous, c);
            }
            total += glyph(c).advance;
            previous = c;
        }
        return total;
    }

    private Glyph measure(char c) {
        Character key = Character.valueOf(c);
        if (!font.canDisplay(c) && c != '?') {
            // Not in the subset. Draw the replacement rather than nothing, so a
            // missing glyph looks like a missing glyph and not like a bug.
            Glyph replacement = glyph('?');
            glyphs.put(key, replacement);
            return replacement;
        }
        GlyphVector vector = font.createGlyphVector(context, String.valueOf(c));
        float advance = (float) vector.getGlyphMetrics(0).getAdvanceX();
        Rectangle2D ink = vector.getVisualBounds();
        Glyph glyph = new Glyph(c, advance, ink.getWidth() <= 0 || ink.getHeight() <= 0);
        glyphs.put(key, glyph);
        return glyph;
    }

    private Raster rasterise(Glyph glyph, int phase) {
        float shift = phase / (float) PHASES;
        GlyphVector vector = font.createGlyphVector(context, String.valueOf(glyph.character));
        Rectangle2D ink = vector.getVisualBounds();

        // One pixel of margin on every side, plus the phase's shift on the right.
        int x0 = (int) Math.floor(ink.getMinX()) - 1;
        int y0 = (int) Math.floor(ink.getMinY()) - 1;
        int width = (int) Math.ceil(ink.getMaxX() + shift) + 1 - x0;
        int height = (int) Math.ceil(ink.getMaxY()) + 1 - y0;

        int[] at = atlas.allocate(width, height);
        if (at == null) {
            return new Raster(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f);
        }

        BufferedImage image = new BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        graphics.setRenderingHint(RenderingHints.KEY_ANTIALIASING, RenderingHints.VALUE_ANTIALIAS_ON);
        graphics.setRenderingHint(
                RenderingHints.KEY_TEXT_ANTIALIASING, RenderingHints.VALUE_TEXT_ANTIALIAS_ON);
        graphics.setRenderingHint(
                RenderingHints.KEY_FRACTIONALMETRICS, RenderingHints.VALUE_FRACTIONALMETRICS_ON);
        graphics.setRenderingHint(
                RenderingHints.KEY_STROKE_CONTROL, RenderingHints.VALUE_STROKE_PURE);
        graphics.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        // White, so the alpha channel is pure coverage and the colour comes
        // from the vertex.
        graphics.setColor(Color.WHITE);
        graphics.setFont(font);
        graphics.drawGlyphVector(vector, -x0 + shift, -y0);
        graphics.dispose();

        darkenStems(image);
        atlas.upload(image, at[0], at[1]);
        return new Raster(x0, y0, width, height,
                atlas.u(at[0]), atlas.v(at[1]), atlas.u(at[0] + width), atlas.v(at[1] + height));
    }

    /**
     * Thicken the coverage a little, the way a browser does.
     *
     * <p>Java2D's greyscale antialiasing is linear coverage: a stem half a pixel
     * wide comes out at 50% alpha, and at a caption size on a dark background
     * that reads as a grey smear rather than as a letter. {@code alpha^0.8}
     * lands partial pixels a little darker than linear, so stems keep their
     * weight at small sizes without their edges blooming into a halo.
     */
    private static void darkenStems(BufferedImage image) {
        int width = image.getWidth();
        int height = image.getHeight();
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int pixel = image.getRGB(x, y);
                int alpha = pixel >>> 24;
                if (alpha == 0 || alpha == 255) {
                    continue;
                }
                int lifted = Math.round(255f * (float) Math.pow(alpha / 255f, STEM_GAMMA));
                image.setRGB(x, y, (Math.min(255, lifted) << 24) | (pixel & 0xFFFFFF));
            }
        }
    }
}
