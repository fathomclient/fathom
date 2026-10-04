package net.fathom.optimizations.tools.font;

import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.imageio.ImageIO;

import net.fathom.optimizations.core.ui.text.FontAtlas;

/**
 * Builds the interface's glyph atlas, offline.
 *
 * <pre>
 *   gradle uiFontAtlas
 * </pre>
 *
 * <p>Reads the five faces the interface draws -- Geist Regular, Medium and
 * SemiBold, Geist Mono Regular and Medium, the files the game already ships --
 * and writes {@code assets/fathom/font/atlas/ui.png} (the fields) and
 * {@code ui.atlas} ({@link FontAtlas}: metrics, kerning, placement). Both are
 * committed; the game only reads them. Deterministic: the same fonts make the
 * same bytes.
 *
 * <h2>The numbers</h2>
 *
 * <p>{@link #PIXELS_PER_EM} 40 and {@link #RANGE} 6 pixels. The smallest text
 * the interface draws is about 8 device pixels (11dp captions at the smallest
 * scale); there the field still spans 6 x 8 / 40 = 1.2 screen pixels, enough
 * for a full anti-aliasing ramp. The largest (a 28dp title at 3x) is a 2.1x
 * magnification, which a multi-channel field takes without rounding corners.
 */
public final class UiFontAtlas {

    static final int PIXELS_PER_EM = 40;
    static final int RANGE = 6;
    static final int ATLAS_WIDTH = 1024;

    private static final String[][] FACES = {
        {"regular", "Geist-Regular.ttf", "sans"},
        {"medium", "Geist-Medium.ttf", "sans"},
        {"semibold", "Geist-SemiBold.ttf", "sans"},
        {"mono-regular", "GeistMono-Regular.ttf", "mono"},
        {"mono-medium", "GeistMono-Medium.ttf", "mono"},
    };

    /** Punctuation and symbols the interface (and player-entered names) use beyond ASCII. */
    private static final int[] EXTRA = {
        0x2013, 0x2014, 0x2018, 0x2019, 0x201A, 0x201C, 0x201D, 0x201E, 0x2022, 0x2026, 0x20AC,
        0x2190, 0x2191, 0x2192, 0x2193, 0x2212,
    };

    private UiFontAtlas() {}

    /** One glyph's field before packing. */
    static final class Cell {
        final int face;
        final int codePoint;
        final float advance;
        final int width;
        final int height;
        /** Pixel origin of the cell in the glyph's own pixel grid (y up). */
        final int originX;
        final int originTop;
        float[] field;
        int x;
        int y;

        Cell(int face, int codePoint, float advance, int width, int height, int originX, int originTop) {
            this.face = face;
            this.codePoint = codePoint;
            this.advance = advance;
            this.width = width;
            this.height = height;
            this.originX = originX;
            this.originTop = originTop;
        }
    }

    public static void main(String[] args) throws Exception {
        File fonts = new File(args.length > 0 ? args[0]
                : "src/main/resources/assets/fathom/font").getCanonicalFile();
        File outDir = new File(fonts, "atlas");
        outDir.mkdirs();
        long started = System.nanoTime();
        Built built = build(fonts);
        ImageIO.write(built.image, "png", new File(outDir, "ui.png"));
        OutputStream out = new FileOutputStream(new File(outDir, "ui.atlas"));
        try {
            built.atlas.write(out);
        } finally {
            out.close();
        }
        int glyphs = 0;
        int pairs = 0;
        for (FontAtlas.Face face : built.atlas.faces) {
            glyphs += face.glyphs.length;
            pairs += face.kerningPairs();
        }
        int ligatures = 0;
        for (FontAtlas.Face face : built.atlas.faces) {
            ligatures += face.ligatures();
        }
        System.out.printf("ui atlas: %dx%d, %d faces, %d glyphs, %d kerning pairs, %d ligatures, %.1f s%n",
                built.atlas.width, built.atlas.height, built.atlas.faces.length, glyphs, pairs, ligatures,
                (System.nanoTime() - started) / 1e9);
    }

    /** The atlas and its image. */
    public static final class Built {
        public final FontAtlas atlas;
        public final BufferedImage image;
        /** The outlines each glyph was generated from, for verification: face -> code point -> outline. */
        public final List<java.util.Map<Integer, java.awt.geom.Area>> outlines;

        Built(FontAtlas atlas, BufferedImage image, List<java.util.Map<Integer, java.awt.geom.Area>> outlines) {
            this.atlas = atlas;
            this.image = image;
            this.outlines = outlines;
        }
    }

    public static Built build(File fontDirectory) throws Exception {
        List<Cell> cells = new ArrayList<Cell>();
        List<Msdf.Outline> outlines = new ArrayList<Msdf.Outline>();
        List<OpenType> fonts = new ArrayList<OpenType>();
        List<int[]> charsets = new ArrayList<int[]>();
        List<java.util.Map<Integer, java.awt.geom.Area>> areas = new ArrayList<java.util.Map<Integer, java.awt.geom.Area>>();
        List<Object[]> ligatureSets = new ArrayList<Object[]>();
        for (int f = 0; f < FACES.length; f++) {
            OpenType font = new OpenType(Files.readAllBytes(new File(fontDirectory, FACES[f][1]).toPath()));
            fonts.add(font);
            int[] charset = charset(font, "mono".equals(FACES[f][2]));
            charsets.add(charset);
            java.util.Map<Integer, java.awt.geom.Area> faceAreas = new java.util.HashMap<Integer, java.awt.geom.Area>();
            areas.add(faceAreas);
            double scale = PIXELS_PER_EM / (double) font.unitsPerEm;
            int pad = (int) Math.ceil(RANGE / 2.0) + 1;
            // Ligatures whose components are all in the charset: drawn from
            // private-use code points, glyph ids remembered for kerning.
            java.util.Map<Integer, Integer> ligatureGlyphs = new java.util.LinkedHashMap<Integer, Integer>();
            java.util.List<char[]> ligatureTexts = new ArrayList<char[]>();
            java.util.List<Integer> ligatureCodes = new ArrayList<Integer>();
            for (int[] ligature : font.ligatures()) {
                char[] text = new char[ligature.length - 1];
                boolean usable = true;
                for (int c = 1; c < ligature.length; c++) {
                    int code = font.codePointOf(ligature[c]);
                    if (code < 0 || code > 0xFFFF || Arrays.binarySearch(charset, code) < 0) {
                        usable = false;
                        break;
                    }
                    text[c - 1] = (char) code;
                }
                if (!usable) {
                    continue;
                }
                int code = FontAtlas.LIGATURE_BASE + ligatureGlyphs.size();
                ligatureGlyphs.put(Integer.valueOf(code), Integer.valueOf(ligature[0]));
                ligatureTexts.add(text);
                ligatureCodes.add(Integer.valueOf(code));
            }
            ligatureSets.add(new Object[] {ligatureGlyphs, ligatureTexts, ligatureCodes});
            int[] drawn = Arrays.copyOf(charset, charset.length + ligatureGlyphs.size());
            int extra = charset.length;
            for (Integer code : ligatureGlyphs.keySet()) {
                drawn[extra++] = code.intValue();
            }
            for (int codePoint : drawn) {
                Integer ligatureGlyph = ligatureGlyphs.get(Integer.valueOf(codePoint));
                int glyph = ligatureGlyph != null ? ligatureGlyph.intValue() : font.glyph(codePoint);
                float advance = font.advance(glyph) / (float) font.unitsPerEm;
                Msdf.Outline outline = new Msdf.Outline(font.outline(glyph));
                if (outline.contours.isEmpty()) {
                    cells.add(new Cell(f, codePoint, advance, 0, 0, 0, 0));
                    outlines.add(null);
                    continue;
                }
                faceAreas.put(Integer.valueOf(codePoint), outline.area);
                int left = (int) Math.floor(outline.bounds.getMinX() * scale) - pad;
                int right = (int) Math.ceil(outline.bounds.getMaxX() * scale) + pad;
                int top = (int) Math.ceil(outline.bounds.getMaxY() * scale) + pad;
                int bottom = (int) Math.floor(outline.bounds.getMinY() * scale) - pad;
                Msdf.colourEdges(outline, 0L);
                cells.add(new Cell(f, codePoint, advance, right - left, top - bottom, left, top));
                outlines.add(outline);
            }
        }

        // Fields, in parallel: each glyph is independent.
        ExecutorService pool = Executors.newFixedThreadPool(Math.max(1, Runtime.getRuntime().availableProcessors()));
        List<Future<?>> work = new ArrayList<Future<?>>();
        for (int index = 0; index < cells.size(); index++) {
            final Cell cell = cells.get(index);
            final Msdf.Outline outline = outlines.get(index);
            if (outline == null) {
                continue;
            }
            final double scale = PIXELS_PER_EM / (double) fonts.get(cell.face).unitsPerEm;
            work.add(pool.submit(new Runnable() {
                @Override
                public void run() {
                    cell.field = Msdf.generate(outline, cell.width, cell.height, scale,
                            cell.originX / scale, cell.originTop / scale, RANGE);
                }
            }));
        }
        for (Future<?> each : work) {
            each.get();
        }
        pool.shutdown();

        // Shelf packing, tallest first, so rows waste little.
        List<Cell> packed = new ArrayList<Cell>();
        for (Cell cell : cells) {
            if (cell.field != null) {
                packed.add(cell);
            }
        }
        Collections.sort(packed, new Comparator<Cell>() {
            @Override
            public int compare(Cell a, Cell b) {
                if (a.height != b.height) {
                    return b.height - a.height;
                }
                if (a.face != b.face) {
                    return a.face - b.face;
                }
                return a.codePoint - b.codePoint;
            }
        });
        int x = 0;
        int y = 0;
        int rowHeight = 0;
        for (Cell cell : packed) {
            if (x + cell.width > ATLAS_WIDTH) {
                x = 0;
                y += rowHeight;
                rowHeight = 0;
            }
            cell.x = x;
            cell.y = y;
            x += cell.width;
            rowHeight = Math.max(rowHeight, cell.height);
        }
        int height = y + rowHeight;
        height = (height + 63) / 64 * 64;

        BufferedImage image = new BufferedImage(ATLAS_WIDTH, height, BufferedImage.TYPE_INT_ARGB);
        for (Cell cell : packed) {
            for (int row = 0; row < cell.height; row++) {
                for (int column = 0; column < cell.width; column++) {
                    int at = (row * cell.width + column) * 4;
                    int r = byteOf(cell.field[at]);
                    int g = byteOf(cell.field[at + 1]);
                    int b = byteOf(cell.field[at + 2]);
                    int a = byteOf(cell.field[at + 3]);
                    image.setRGB(cell.x + column, cell.y + row, a << 24 | r << 16 | g << 8 | b);
                }
            }
        }

        FontAtlas.Face[] faces = new FontAtlas.Face[FACES.length];
        for (int f = 0; f < FACES.length; f++) {
            OpenType font = fonts.get(f);
            float em = font.unitsPerEm;
            List<FontAtlas.Glyph> glyphs = new ArrayList<FontAtlas.Glyph>();
            for (Cell cell : cells) {
                if (cell.face != f) {
                    continue;
                }
                if (cell.field == null) {
                    glyphs.add(new FontAtlas.Glyph(cell.codePoint, cell.advance, 0, 0, 0, 0, 0, 0, 0, 0));
                    continue;
                }
                float pl = cell.originX / (float) PIXELS_PER_EM;
                float pt = cell.originTop / (float) PIXELS_PER_EM;
                float pr = (cell.originX + cell.width) / (float) PIXELS_PER_EM;
                float pb = (cell.originTop - cell.height) / (float) PIXELS_PER_EM;
                glyphs.add(new FontAtlas.Glyph(cell.codePoint, cell.advance, pl, pb, pr, pt,
                        cell.x, cell.y, cell.x + cell.width, cell.y + cell.height));
            }
            int[] charset = charsets.get(f);
            @SuppressWarnings("unchecked")
            java.util.Map<Integer, Integer> ligatureGlyphs = (java.util.Map<Integer, Integer>) ligatureSets.get(f)[0];
            int[] kerned = Arrays.copyOf(charset, charset.length + ligatureGlyphs.size());
            int extra = charset.length;
            for (Integer code : ligatureGlyphs.keySet()) {
                kerned[extra++] = code.intValue();
            }
            List<long[]> kern = new ArrayList<long[]>();
            for (int left : kerned) {
                Integer leftLigature = ligatureGlyphs.get(Integer.valueOf(left));
                int leftGlyph = leftLigature != null ? leftLigature.intValue() : font.glyph(left);
                for (int right : kerned) {
                    Integer rightLigature = ligatureGlyphs.get(Integer.valueOf(right));
                    int rightGlyph = rightLigature != null ? rightLigature.intValue() : font.glyph(right);
                    int value = font.kerning(leftGlyph, rightGlyph);
                    if (value != 0) {
                        kern.add(new long[] {((long) left << 32) | right, Float.floatToIntBits(value / em)});
                    }
                }
            }
            Collections.sort(kern, new Comparator<long[]>() {
                @Override
                public int compare(long[] a, long[] b) {
                    return Long.compare(a[0], b[0]);
                }
            });
            long[] keys = new long[kern.size()];
            float[] values = new float[kern.size()];
            for (int index = 0; index < keys.length; index++) {
                keys[index] = kern.get(index)[0];
                values[index] = Float.intBitsToFloat((int) kern.get(index)[1]);
            }
            @SuppressWarnings("unchecked")
            List<char[]> ligatureTexts = (List<char[]>) ligatureSets.get(f)[1];
            @SuppressWarnings("unchecked")
            List<Integer> ligatureCodes = (List<Integer>) ligatureSets.get(f)[2];
            int[] codes = new int[ligatureCodes.size()];
            for (int index = 0; index < codes.length; index++) {
                codes[index] = ligatureCodes.get(index).intValue();
            }
            faces[f] = new FontAtlas.Face(FACES[f][0], font.ascender / em, -font.descender / em, font.lineGap / em,
                    font.capHeight / em, font.xHeight / em, glyphs.toArray(new FontAtlas.Glyph[0]), keys, values,
                    ligatureTexts.toArray(new char[0][]), codes);
        }
        return new Built(new FontAtlas(PIXELS_PER_EM, RANGE, ATLAS_WIDTH, height, faces), image, areas);
    }

    private static int[] charset(OpenType font, boolean mono) {
        List<Integer> codes = new ArrayList<Integer>();
        for (int c = 0x20; c <= 0x7E; c++) {
            codes.add(Integer.valueOf(c));
        }
        for (int c = 0xA0; c <= 0xFF; c++) {
            if (!mono || c == 0xB0 || c == 0xB7 || c == 0xD7) {
                codes.add(Integer.valueOf(c));
            }
        }
        for (int c : EXTRA) {
            codes.add(Integer.valueOf(c));
        }
        int[] result = new int[codes.size()];
        int n = 0;
        for (Integer code : codes) {
            if (font.has(code.intValue())) {
                result[n++] = code.intValue();
            }
        }
        return Arrays.copyOf(result, n);
    }

    private static int byteOf(float value) {
        int v = Math.round(value * 255f);
        return v < 0 ? 0 : (v > 255 ? 255 : v);
    }
}
