package net.fathom.optimizations.fabric.gfx;

import java.awt.Color;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.font.FontRenderContext;
import java.awt.font.GlyphVector;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.util.HashMap;
import java.util.Map;

/**
 * One font, at one pixel size, rasterised.
 *
 * <h2>Why rasterise per size</h2>
 *
 * <p>Because it is the highest quality thing that can be done without a shader,
 * and quality was the point of this milestone. A glyph drawn from a raster made
 * at exactly the size it will be drawn at is as sharp as the font's own hinting
 * allows. A glyph drawn from a raster made at some other size is either blurry
 * or blocky, and blocky at 3× is what the interface looked like before.
 *
 * <p>The cost is that a page is only good for one size, and a changed UI scale
 * throws them all away. That happens when a window is resized. It does not
 * happen while anything is animating, because animation scales the quads and
 * not the raster.
 *
 * <h2>Lazy, but not per frame</h2>
 *
 * <p>ASCII is rasterised when the page is created — about 95 glyphs, a few
 * milliseconds, once. Anything else arrives the first time it is drawn, which
 * is what makes a Turkish world name work without carrying every alphabet in
 * memory.
 *
 * <h2>No kerning, on purpose</h2>
 *
 * <p>Advances come from the glyph vector, one glyph at a time, and measurement
 * sums exactly the advances drawing will use. Kerning would make text very
 * slightly tighter and would put measurement and drawing on different code
 * paths — which is how the first hub ended up clipping labels that "fitted".
 */
final class GlyphPage {

    /** What is rasterised up front. */
    private static final char FIRST = 32;
    private static final char LAST = 126;

    private final Font font;
    private final Atlas atlas;
    private final FontRenderContext context;
    private final Map<Character, Glyph> glyphs = new HashMap<Character, Glyph>(256);
    private final float ascent;
    private final float descent;
    private final float lineHeight;
    private Glyph fallback;

    /** Where one character's picture is, and where to put it. */
    static final class Glyph {
        final float advance;
        /** Ink offset from the pen position, in device pixels. */
        final float offsetX;
        final float offsetY;
        final float width;
        final float height;
        final float u0;
        final float v0;
        final float u1;
        final float v1;
        final boolean blank;

        Glyph(float advance) {
            this(advance, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, true);
        }

        Glyph(float advance, float offsetX, float offsetY, float width, float height,
              float u0, float v0, float u1, float v1, boolean blank) {
            this.advance = advance;
            this.offsetX = offsetX;
            this.offsetY = offsetY;
            this.width = width;
            this.height = height;
            this.u0 = u0;
            this.v0 = v0;
            this.u1 = u1;
            this.v1 = v1;
            this.blank = blank;
        }
    }

    GlyphPage(Font base, float pixelSize, Atlas atlas) {
        this.font = base.deriveFont(Font.PLAIN, pixelSize);
        this.atlas = atlas;
        // Anti-aliased, with fractional metrics, which is what makes advances
        // real numbers instead of the integers that made the old text drift.
        this.context = new FontRenderContext(null, true, true);

        BufferedImage probe = new BufferedImage(1, 1, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = probe.createGraphics();
        graphics.setFont(font);
        java.awt.FontMetrics metrics = graphics.getFontMetrics();
        this.ascent = metrics.getAscent();
        this.descent = metrics.getDescent();
        this.lineHeight = metrics.getHeight();
        graphics.dispose();

        for (char c = FIRST; c <= LAST; c++) {
            rasterise(c);
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

    /** The glyph for a character, rasterising it if this is the first time. */
    Glyph glyph(char c) {
        Glyph found = glyphs.get(Character.valueOf(c));
        if (found != null) {
            return found;
        }
        return rasterise(c);
    }

    /** The width of a string: the sum of the advances drawing will use. */
    float width(String text) {
        float total = 0f;
        for (int index = 0; index < text.length(); index++) {
            total += glyph(text.charAt(index)).advance;
        }
        return total;
    }

    private Glyph rasterise(char c) {
        Character key = Character.valueOf(c);
        if (!font.canDisplay(c)) {
            // Not in the subset. Draw the replacement rather than nothing, so a
            // missing glyph looks like a missing glyph and not like a bug.
            if (c != '?') {
                Glyph replacement = glyph('?');
                glyphs.put(key, replacement);
                return replacement;
            }
        }
        GlyphVector vector = font.createGlyphVector(context, String.valueOf(c));
        float advance = (float) vector.getGlyphMetrics(0).getAdvanceX();
        Rectangle2D ink = vector.getVisualBounds();
        if (ink.getWidth() <= 0 || ink.getHeight() <= 0) {
            Glyph blank = new Glyph(advance);
            glyphs.put(key, blank);
            return blank;
        }

        int x0 = (int) Math.floor(ink.getMinX()) - 1;
        int y0 = (int) Math.floor(ink.getMinY()) - 1;
        int width = (int) Math.ceil(ink.getMaxX()) + 1 - x0;
        int height = (int) Math.ceil(ink.getMaxY()) + 1 - y0;

        int[] at = atlas.allocate(width, height);
        if (at == null) {
            Glyph blank = new Glyph(advance);
            glyphs.put(key, blank);
            return blank;
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
        // White, so the alpha channel is pure coverage and the colour comes
        // from the vertex. Anything else would tint every string in the
        // interface towards this colour at the edges of its letters.
        graphics.setColor(Color.WHITE);
        graphics.setFont(font);
        graphics.drawGlyphVector(vector, -x0, -y0);
        graphics.dispose();

        atlas.upload(image, at[0], at[1]);

        Glyph glyph = new Glyph(
                advance,
                x0,
                y0,
                width,
                height,
                atlas.u(at[0]),
                atlas.v(at[1]),
                atlas.u(at[0] + width),
                atlas.v(at[1] + height),
                false);
        glyphs.put(key, glyph);
        return glyph;
    }
}
