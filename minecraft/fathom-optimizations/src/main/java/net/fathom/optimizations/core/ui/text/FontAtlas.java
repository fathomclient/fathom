package net.fathom.optimizations.core.ui.text;

import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

/**
 * The interface's typefaces, as one multi-channel distance-field atlas: every
 * glyph's metrics, the fonts' kerning, and where each glyph's field sits in
 * the atlas image.
 *
 * <h2>Where it comes from</h2>
 *
 * <p>{@code gradle uiFontAtlas} reads Geist and Geist Mono from the font
 * files, unions each outline, generates its field and packs them
 * ({@code tools/font/UiFontAtlas}). The result --
 * {@code assets/fathom/font/atlas/ui.png} and this file's binary form
 * beside it -- is committed. The game reads both once, the first time a
 * Fathom screen opens, and never parses a font, rasterises a glyph or
 * rebuilds anything when the interface scale changes: a distance field is
 * drawn at any size from the same texels.
 *
 * <h2>Units</h2>
 *
 * <p>Metrics, advances and kerning are in ems (font units over units per em),
 * so a size multiplies them directly. Plane bounds are the glyph quad
 * relative to the pen, in ems, y up. Atlas bounds are in atlas pixels, y down.
 */
public final class FontAtlas {

    private static final int MAGIC = 0x46544C41; // "FTLA"
    public static final int VERSION = 2;

    /**
     * Ligature glyphs have no code point of their own; the atlas gives them
     * private-use ones from here, in the order the font lists them.
     */
    public static final int LIGATURE_BASE = 0xF0000;

    /** Atlas pixels per em the fields were generated at. */
    public final float pixelsPerEm;
    /** The distance, in atlas pixels, from 0 to 1 in a channel. */
    public final float distanceRange;
    public final int width;
    public final int height;
    public final Face[] faces;

    public FontAtlas(float pixelsPerEm, float distanceRange, int width, int height, Face[] faces) {
        this.pixelsPerEm = pixelsPerEm;
        this.distanceRange = distanceRange;
        this.width = width;
        this.height = height;
        this.faces = faces;
    }

    /** The face called {@code name}, or null. */
    public Face face(String name) {
        for (Face face : faces) {
            if (face.name.equals(name)) {
                return face;
            }
        }
        return null;
    }

    /** One typeface at one weight. */
    public static final class Face {

        /** Code points below this are looked up directly; above, by search. */
        private static final int DENSE = 0x2200;

        public final String name;
        public final float ascender;
        public final float descender;
        public final float lineGap;
        public final float capHeight;
        public final float xHeight;
        public final Glyph[] glyphs;
        /** {@code left << 32 | right} code points, sorted, and their adjustments in ems. */
        private final long[] kernPairs;
        private final float[] kernValues;
        private final Glyph[] dense = new Glyph[DENSE];
        private final int[] sparseCodes;
        private final Glyph[] sparseGlyphs;
        private final Glyph fallback;
        /** Whether a code point starts any kerning pair: most do not, and skip the search. */
        private final boolean[] kernsLeft = new boolean[DENSE];
        /**
         * Standard ligatures, longest first within each first character:
         * the component characters and the private-use code point drawn for them.
         */
        private final char[][] ligatureText;
        private final int[] ligatureCode;
        /** Whether a character starts any ligature. */
        private final boolean[] ligatureStart = new boolean[128];

        public Face(String name, float ascender, float descender, float lineGap, float capHeight, float xHeight,
                Glyph[] glyphs, long[] kernPairs, float[] kernValues) {
            this(name, ascender, descender, lineGap, capHeight, xHeight, glyphs, kernPairs, kernValues,
                    new char[0][], new int[0]);
        }

        public Face(String name, float ascender, float descender, float lineGap, float capHeight, float xHeight,
                Glyph[] glyphs, long[] kernPairs, float[] kernValues, char[][] ligatureText, int[] ligatureCode) {
            this.name = name;
            this.ascender = ascender;
            this.descender = descender;
            this.lineGap = lineGap;
            this.capHeight = capHeight;
            this.xHeight = xHeight;
            this.glyphs = glyphs;
            this.kernPairs = kernPairs;
            this.kernValues = kernValues;
            int sparse = 0;
            for (Glyph glyph : glyphs) {
                if (glyph.codePoint < DENSE) {
                    dense[glyph.codePoint] = glyph;
                } else {
                    sparse++;
                }
            }
            sparseCodes = new int[sparse];
            sparseGlyphs = new Glyph[sparse];
            int at = 0;
            for (Glyph glyph : glyphs) {
                if (glyph.codePoint >= DENSE) {
                    sparseCodes[at] = glyph.codePoint;
                    sparseGlyphs[at] = glyph;
                    at++;
                }
            }
            sortSparse();
            Glyph question = glyph('?');
            fallback = question;
            for (long pair : kernPairs) {
                int left = (int) (pair >>> 32);
                if (left < DENSE) {
                    kernsLeft[left] = true;
                }
            }
            // Longest first, so "-->" wins over "->".
            Integer[] order = new Integer[ligatureText.length];
            for (int index = 0; index < order.length; index++) {
                order[index] = Integer.valueOf(index);
            }
            final char[][] texts = ligatureText;
            java.util.Arrays.sort(order, new java.util.Comparator<Integer>() {
                @Override
                public int compare(Integer a, Integer b) {
                    return texts[b.intValue()].length - texts[a.intValue()].length;
                }
            });
            this.ligatureText = new char[order.length][];
            this.ligatureCode = new int[order.length];
            for (int index = 0; index < order.length; index++) {
                this.ligatureText[index] = ligatureText[order[index].intValue()];
                this.ligatureCode[index] = ligatureCode[order[index].intValue()];
                char start = this.ligatureText[index][0];
                if (start < 128) {
                    ligatureStart[start] = true;
                }
            }
        }

        /**
         * The ligature that starts at {@code index} in {@code text}, as a
         * browser applies 'liga': {@code code << 8 | length}, or -1 when none
         * does. {@code lower} matches as if the text were lowercased.
         */
        public long ligatureAt(String text, int index, boolean lower) {
            char first = text.charAt(index);
            if (lower) {
                first = Character.toLowerCase(first);
            }
            if (first >= 128 || !ligatureStart[first]) {
                return -1L;
            }
            int remaining = text.length() - index;
            for (int l = 0; l < ligatureText.length; l++) {
                char[] parts = ligatureText[l];
                if (parts.length > remaining || parts[0] != first) {
                    continue;
                }
                boolean match = true;
                for (int c = 1; c < parts.length; c++) {
                    char ch = text.charAt(index + c);
                    if (lower) {
                        ch = Character.toLowerCase(ch);
                    }
                    if (ch != parts[c]) {
                        match = false;
                        break;
                    }
                }
                if (match) {
                    return ((long) ligatureCode[l] << 8) | parts.length;
                }
            }
            return -1L;
        }

        public int ligatures() {
            return ligatureText.length;
        }

        /**
         * The advance of {@code text} in ems, laid out exactly as it is drawn:
         * advances, GPOS kerning, standard ligatures (none where
         * letter-spacing is set, as CSS has it) and CSS letter-spacing after
         * every character. No allocation.
         *
         * @param lower      lay it out lowercased, as the interface shows it
         * @param tracking   letter-spacing, ems
         */
        public float width(String text, boolean lower, float tracking) {
            float total = 0f;
            int previous = -1;
            int length = text.length();
            int characters = 0;
            for (int index = 0; index < length; index++) {
                int code = text.charAt(index);
                if (lower) {
                    code = Character.toLowerCase(code);
                }
                long ligature = tracking == 0f ? ligatureAt(text, index, lower) : -1L;
                if (ligature >= 0L) {
                    code = (int) (ligature >>> 8);
                    index += (int) (ligature & 0xFF) - 1;
                }
                if (previous >= 0) {
                    total += kerning(previous, code);
                }
                Glyph glyph = glyphOrFallback(code);
                if (glyph != null) {
                    total += glyph.advance;
                }
                characters++;
                previous = code;
            }
            return total + tracking * characters;
        }

        private void sortSparse() {
            for (int i = 1; i < sparseCodes.length; i++) {
                int code = sparseCodes[i];
                Glyph glyph = sparseGlyphs[i];
                int j = i - 1;
                while (j >= 0 && sparseCodes[j] > code) {
                    sparseCodes[j + 1] = sparseCodes[j];
                    sparseGlyphs[j + 1] = sparseGlyphs[j];
                    j--;
                }
                sparseCodes[j + 1] = code;
                sparseGlyphs[j + 1] = glyph;
            }
        }

        /** The glyph for a code point, or null when the face has none. */
        public Glyph glyph(int codePoint) {
            if (codePoint >= 0 && codePoint < DENSE) {
                return dense[codePoint];
            }
            int index = java.util.Arrays.binarySearch(sparseCodes, codePoint);
            return index >= 0 ? sparseGlyphs[index] : null;
        }

        /** The glyph to draw for a code point: itself, or the replacement. */
        public Glyph glyphOrFallback(int codePoint) {
            Glyph glyph = glyph(codePoint);
            return glyph != null ? glyph : fallback;
        }

        /** Pair adjustment in ems, from the font's GPOS kerning. */
        public float kerning(int left, int right) {
            if (left >= 0 && left < DENSE && !kernsLeft[left]) {
                return 0f;
            }
            long key = ((long) left << 32) | (right & 0xFFFFFFFFL);
            int index = java.util.Arrays.binarySearch(kernPairs, key);
            return index >= 0 ? kernValues[index] : 0f;
        }

        public int kerningPairs() {
            return kernPairs.length;
        }
    }

    /** One glyph: its advance, its quad around the pen and its texels. */
    public static final class Glyph {
        public final int codePoint;
        public final float advance;
        /** Quad relative to the pen, ems, y up. Zero size for a blank glyph. */
        public final float planeLeft;
        public final float planeBottom;
        public final float planeRight;
        public final float planeTop;
        /** Texels, atlas pixels, y down. */
        public final float atlasLeft;
        public final float atlasTop;
        public final float atlasRight;
        public final float atlasBottom;

        public Glyph(int codePoint, float advance, float planeLeft, float planeBottom, float planeRight,
                float planeTop, float atlasLeft, float atlasTop, float atlasRight, float atlasBottom) {
            this.codePoint = codePoint;
            this.advance = advance;
            this.planeLeft = planeLeft;
            this.planeBottom = planeBottom;
            this.planeRight = planeRight;
            this.planeTop = planeTop;
            this.atlasLeft = atlasLeft;
            this.atlasTop = atlasTop;
            this.atlasRight = atlasRight;
            this.atlasBottom = atlasBottom;
        }

        public boolean blank() {
            return planeRight <= planeLeft || planeTop <= planeBottom;
        }
    }

    // -----------------------------------------------------------------------
    // Binary form
    // -----------------------------------------------------------------------

    public void write(OutputStream stream) throws IOException {
        DataOutputStream out = new DataOutputStream(stream);
        out.writeInt(MAGIC);
        out.writeInt(VERSION);
        out.writeFloat(pixelsPerEm);
        out.writeFloat(distanceRange);
        out.writeInt(width);
        out.writeInt(height);
        out.writeInt(faces.length);
        for (Face face : faces) {
            out.writeUTF(face.name);
            out.writeFloat(face.ascender);
            out.writeFloat(face.descender);
            out.writeFloat(face.lineGap);
            out.writeFloat(face.capHeight);
            out.writeFloat(face.xHeight);
            out.writeInt(face.glyphs.length);
            for (Glyph glyph : face.glyphs) {
                out.writeInt(glyph.codePoint);
                out.writeFloat(glyph.advance);
                out.writeFloat(glyph.planeLeft);
                out.writeFloat(glyph.planeBottom);
                out.writeFloat(glyph.planeRight);
                out.writeFloat(glyph.planeTop);
                out.writeFloat(glyph.atlasLeft);
                out.writeFloat(glyph.atlasTop);
                out.writeFloat(glyph.atlasRight);
                out.writeFloat(glyph.atlasBottom);
            }
            out.writeInt(face.kernPairs.length);
            for (int index = 0; index < face.kernPairs.length; index++) {
                out.writeLong(face.kernPairs[index]);
                out.writeFloat(face.kernValues[index]);
            }
            out.writeInt(face.ligatureText.length);
            for (int index = 0; index < face.ligatureText.length; index++) {
                out.writeUTF(new String(face.ligatureText[index]));
                out.writeInt(face.ligatureCode[index]);
            }
        }
        out.flush();
    }

    public static FontAtlas read(InputStream stream) throws IOException {
        DataInputStream in = new DataInputStream(stream);
        if (in.readInt() != MAGIC) {
            throw new IOException("not a Fathom font atlas");
        }
        int version = in.readInt();
        if (version != VERSION) {
            throw new IOException("font atlas version " + version + ", expected " + VERSION);
        }
        float pixelsPerEm = in.readFloat();
        float range = in.readFloat();
        int width = in.readInt();
        int height = in.readInt();
        Face[] faces = new Face[in.readInt()];
        for (int f = 0; f < faces.length; f++) {
            String name = in.readUTF();
            float ascender = in.readFloat();
            float descender = in.readFloat();
            float lineGap = in.readFloat();
            float capHeight = in.readFloat();
            float xHeight = in.readFloat();
            Glyph[] glyphs = new Glyph[in.readInt()];
            for (int g = 0; g < glyphs.length; g++) {
                glyphs[g] = new Glyph(in.readInt(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(),
                        in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat(), in.readFloat());
            }
            int pairs = in.readInt();
            long[] keys = new long[pairs];
            float[] values = new float[pairs];
            for (int p = 0; p < pairs; p++) {
                keys[p] = in.readLong();
                values[p] = in.readFloat();
            }
            int ligatureCount = in.readInt();
            char[][] ligatureText = new char[ligatureCount][];
            int[] ligatureCode = new int[ligatureCount];
            for (int l = 0; l < ligatureCount; l++) {
                ligatureText[l] = in.readUTF().toCharArray();
                ligatureCode[l] = in.readInt();
            }
            faces[f] = new Face(name, ascender, descender, lineGap, capHeight, xHeight, glyphs, keys, values,
                    ligatureText, ligatureCode);
        }
        return new FontAtlas(pixelsPerEm, range, width, height, faces);
    }
}
