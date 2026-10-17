package net.fathom.optimizations.tools.font;

import java.awt.geom.Area;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.util.Locale;
import java.util.Map;

import javax.imageio.ImageIO;

import net.fathom.optimizations.core.ui.text.FontAtlas;

/**
 * Proves the committed atlas against the fonts: every glyph, reconstructed
 * from its field exactly as the shader does (bilinear sample, median,
 * one-screen-pixel ramp), compared with the outline's exact coverage
 * (16 x 16 supersampled) at several pixel sizes.
 *
 * <p>Two numbers per size: the mean coverage error over pixels that the
 * glyph touches (how faithful the edges are) and the largest single-pixel
 * error (whether any texel is an artifact -- a wrong sign or an unresolved
 * channel clash shows up as an error near 1). Also writes a montage, the
 * reference above the reconstruction.
 *
 * <pre>gradle uiFontAtlasCheck</pre>
 */
public final class FontAtlasCheck {

    private static final int SUPERSAMPLE = 16;
    private static final float[] SIZES = {8f, 11f, 13f, 16f, 21f, 32f, 64f};

    private FontAtlasCheck() {}

    public static void main(String[] args) throws Exception {
        File fonts = new File(args.length > 0 ? args[0] : "src/main/resources/assets/fathom/font").getCanonicalFile();
        File montageOut = args.length > 1 && !args[1].isEmpty() ? new File(args[1]) : null;
        UiFontAtlas.Built built = UiFontAtlas.build(fonts);
        FontAtlas committed;
        InputStream in = new FileInputStream(new File(fonts, "atlas/ui.atlas"));
        try {
            committed = FontAtlas.read(in);
        } finally {
            in.close();
        }
        BufferedImage image = ImageIO.read(new File(fonts, "atlas/ui.png"));
        boolean fresh = sameImage(image, built.image) && committed.faces.length == built.atlas.faces.length;
        System.out.println("committed atlas matches the fonts: " + fresh);

        int width = image.getWidth();
        int height = image.getHeight();
        float[] texels = new float[width * height * 4];
        for (int y = 0; y < height; y++) {
            for (int x = 0; x < width; x++) {
                int argb = image.getRGB(x, y);
                int at = (y * width + x) * 4;
                texels[at] = ((argb >> 16) & 0xFF) / 255f;
                texels[at + 1] = ((argb >> 8) & 0xFF) / 255f;
                texels[at + 2] = (argb & 0xFF) / 255f;
                texels[at + 3] = ((argb >>> 24) & 0xFF) / 255f;
            }
        }

        boolean failed = false;
        for (float size : SIZES) {
            double totalError = 0;
            long touched = 0;
            double worst = 0;
            String worstGlyph = "";
            for (int f = 0; f < committed.faces.length; f++) {
                FontAtlas.Face face = committed.faces[f];
                Map<Integer, Area> areas = built.outlines.get(f);
                for (FontAtlas.Glyph glyph : face.glyphs) {
                    if (glyph.blank()) {
                        continue;
                    }
                    Area area = areas.get(Integer.valueOf(glyph.codePoint));
                    float[] result = compare(committed, texels, width, height, glyph, area, size, null);
                    totalError += result[0];
                    touched += (long) result[1];
                    if (result[2] > worst) {
                        worst = result[2];
                        worstGlyph = face.name + " '" + new String(Character.toChars(glyph.codePoint)) + "'";
                    }
                }
            }
            double mean = touched == 0 ? 0 : totalError / touched;
            System.out.printf(Locale.ROOT, "%5.1f px: mean |coverage error| %.4f over %d edge pixels, worst %.3f (%s)%n",
                    size, mean, touched, worst, worstGlyph);
            if (worst > 0.75) {
                failed = true;
            }
        }
        if (montageOut != null) {
            montage(committed, texels, width, height, built, montageOut);
        }
        if (!fresh) {
            System.out.println("FAIL: assets/fathom/font/atlas is stale; run gradle uiFontAtlas");
            System.exit(1);
        }
        if (failed) {
            System.out.println("FAIL: a glyph reconstructs with an artifact");
            System.exit(1);
        }
    }

    /**
     * Reconstruct one glyph at {@code size} px and compare with its outline.
     *
     * @return {sum of |error| over touched pixels, touched pixel count, max |error|}
     */
    static float[] compare(FontAtlas atlas, float[] texels, int width, int height, FontAtlas.Glyph glyph,
            Area area, float size, float[][] images) {
        float scale = size; // device pixels per em
        float left = glyph.planeLeft * scale;
        float right = glyph.planeRight * scale;
        float top = glyph.planeTop * scale;
        float bottom = glyph.planeBottom * scale;
        int x0 = (int) Math.floor(left);
        int x1 = (int) Math.ceil(right);
        int y0 = (int) Math.floor(-top);
        int y1 = (int) Math.ceil(-bottom);
        float screenPxRange = atlas.distanceRange * size / atlas.pixelsPerEm;
        double unitsPerEm = 1000.0;
        float sum = 0;
        int touched = 0;
        float worst = 0;
        int w = x1 - x0;
        int h = y1 - y0;
        float[] reconstructed = images == null ? null : new float[w * h];
        float[] reference = images == null ? null : new float[w * h];
        for (int py = y0; py < y1; py++) {
            for (int px = x0; px < x1; px++) {
                // Pixel centre in em space (y up).
                float ex = (px + 0.5f) / scale;
                float ey = -(py + 0.5f) / scale;
                float coverage = 0f;
                if (ex >= glyph.planeLeft && ex <= glyph.planeRight && ey >= glyph.planeBottom && ey <= glyph.planeTop) {
                    float u = glyph.atlasLeft + (ex - glyph.planeLeft) / (glyph.planeRight - glyph.planeLeft)
                            * (glyph.atlasRight - glyph.atlasLeft);
                    float v = glyph.atlasTop + (glyph.planeTop - ey) / (glyph.planeTop - glyph.planeBottom)
                            * (glyph.atlasBottom - glyph.atlasTop);
                    float[] rgb = bilinear(texels, width, height, u, v);
                    float median = Msdf.median(rgb[0], rgb[1], rgb[2]);
                    // As the shader: within a texel of the true distance.
                    float slack = 1f / atlas.distanceRange;
                    median = Math.max(rgb[3] - slack, Math.min(rgb[3] + slack, median));
                    coverage = clamp01((median - 0.5f) * screenPxRange + 0.5f);
                }
                // Exact coverage of the pixel, supersampled, in font units.
                int inside = 0;
                for (int sy = 0; sy < SUPERSAMPLE; sy++) {
                    for (int sx = 0; sx < SUPERSAMPLE; sx++) {
                        double fx = (px + (sx + 0.5) / SUPERSAMPLE) / scale * unitsPerEm;
                        double fy = -(py + (sy + 0.5) / SUPERSAMPLE) / scale * unitsPerEm;
                        if (area.contains(fx, fy)) {
                            inside++;
                        }
                    }
                }
                float exact = inside / (float) (SUPERSAMPLE * SUPERSAMPLE);
                float error = Math.abs(coverage - exact);
                if (exact > 0f || coverage > 0f) {
                    if (exact < 1f || coverage < 1f) {
                        sum += error;
                        touched++;
                    }
                }
                worst = Math.max(worst, error);
                if (reconstructed != null) {
                    reconstructed[(py - y0) * w + (px - x0)] = coverage;
                    reference[(py - y0) * w + (px - x0)] = exact;
                }
            }
        }
        if (images != null) {
            images[0] = reference;
            images[1] = reconstructed;
            images[2] = new float[] {w, h};
        }
        return new float[] {sum, touched, worst};
    }

    private static float[] bilinear(float[] texels, int width, int height, float u, float v) {
        float x = u - 0.5f;
        float y = v - 0.5f;
        int ix = (int) Math.floor(x);
        int iy = (int) Math.floor(y);
        float fx = x - ix;
        float fy = y - iy;
        float[] out = new float[4];
        for (int c = 0; c < 4; c++) {
            float a = texel(texels, width, height, ix, iy, c);
            float b = texel(texels, width, height, ix + 1, iy, c);
            float d = texel(texels, width, height, ix, iy + 1, c);
            float e = texel(texels, width, height, ix + 1, iy + 1, c);
            out[c] = (a * (1 - fx) + b * fx) * (1 - fy) + (d * (1 - fx) + e * fx) * fy;
        }
        return out;
    }

    private static float texel(float[] texels, int width, int height, int x, int y, int c) {
        x = Math.max(0, Math.min(width - 1, x));
        y = Math.max(0, Math.min(height - 1, y));
        return texels[(y * width + x) * 4 + c];
    }

    private static float clamp01(float v) {
        return v < 0f ? 0f : (v > 1f ? 1f : v);
    }

    private static boolean sameImage(BufferedImage a, BufferedImage b) {
        if (a.getWidth() != b.getWidth() || a.getHeight() != b.getHeight()) {
            return false;
        }
        for (int y = 0; y < a.getHeight(); y++) {
            for (int x = 0; x < a.getWidth(); x++) {
                if (a.getRGB(x, y) != b.getRGB(x, y)) {
                    return false;
                }
            }
        }
        return true;
    }

    /** "Rag hamburgefonstiv 0123" in every face at 13 and 64 px: reference over reconstruction. */
    private static void montage(FontAtlas atlas, float[] texels, int width, int height,
            UiFontAtlas.Built built, File out) throws Exception {
        String sample = "Rag hamburgefonstiv 0123 @&%";
        float[] sizes = {13f, 64f};
        int canvasWidth = 2000;
        int canvasHeight = 0;
        for (float size : sizes) {
            canvasHeight += (int) (size * 1.4f) * 2 * atlas.faces.length + 10;
        }
        BufferedImage canvas = new BufferedImage(canvasWidth, canvasHeight, BufferedImage.TYPE_INT_RGB);
        int baseY = 0;
        for (float size : sizes) {
            int line = (int) (size * 1.4f);
            for (int f = 0; f < atlas.faces.length; f++) {
                FontAtlas.Face face = atlas.faces[f];
                for (int row = 0; row < 2; row++) {
                    float pen = 8f;
                    int baseline = baseY + (int) (size * 1.05f);
                    int previous = -1;
                    for (int i = 0; i < sample.length(); i++) {
                        int code = sample.charAt(i);
                        FontAtlas.Glyph glyph = face.glyphOrFallback(code);
                        if (previous >= 0) {
                            pen += face.kerning(previous, code) * size;
                        }
                        previous = code;
                        if (!glyph.blank()) {
                            float[][] images = new float[3][];
                            compare(atlas, texels, width, height, glyph,
                                    built.outlines.get(f).get(Integer.valueOf(glyph.codePoint)), size, images);
                            int w = (int) images[2][0];
                            int h = (int) images[2][1];
                            int ox = (int) Math.floor(pen) + (int) Math.floor(glyph.planeLeft * size);
                            int oy = baseline + (int) Math.floor(-glyph.planeTop * size);
                            float[] pixels = images[row];
                            for (int y = 0; y < h; y++) {
                                for (int x = 0; x < w; x++) {
                                    int cx = ox + x;
                                    int cy = oy + y;
                                    if (cx < 0 || cy < 0 || cx >= canvasWidth || cy >= canvasHeight) {
                                        continue;
                                    }
                                    int before = canvas.getRGB(cx, cy) & 0xFF;
                                    int value = Math.min(255, before + Math.round(pixels[y * w + x] * 255f));
                                    canvas.setRGB(cx, cy, value << 16 | value << 8 | value);
                                }
                            }
                        }
                        pen += glyph.advance * size;
                    }
                    baseY += line;
                }
            }
            baseY += 10;
        }
        ImageIO.write(canvas, "png", out);
    }
}
