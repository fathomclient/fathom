package net.fathom.optimizations.tools.font;

import java.awt.geom.Path2D;
import java.nio.ByteBuffer;
import java.util.HashMap;
import java.util.Map;

/**
 * The parts of a TrueType font the interface's text needs, read from the file
 * itself: metrics, the character map, advances, GPOS pair kerning and glyph
 * outlines.
 *
 * <h2>Why read the font ourselves</h2>
 *
 * <p>Java's font stack answered all of these before, and two of its answers
 * were wrong for an interface. JDK 8's layout does not apply a font's GPOS
 * kerning, so pairs like "Te" or "ay" sat at their raw advances, and its line
 * metrics are rounded per size. The numbers here are the font's own, in font
 * units, read once by the offline atlas tool; nothing at runtime parses a font.
 *
 * <p>Only what Geist uses is supported: {@code glyf} outlines (simple and
 * composite), {@code cmap} formats 4 and 12, and GPOS lookup type 2 (pair
 * adjustment, formats 1 and 2), directly or through extension lookups.
 */
public final class OpenType {

    public final int unitsPerEm;
    public final int ascender;
    public final int descender;
    public final int lineGap;
    public final int capHeight;
    public final int xHeight;
    public final int glyphCount;

    private final ByteBuffer data;
    private final Map<String, int[]> tables = new HashMap<String, int[]>();
    private final Map<Integer, Integer> cmap = new HashMap<Integer, Integer>();
    private final int[] advances;
    private final int[] glyphOffsets;
    private final int glyfOffset;
    /** Pair adjustments in font units, keyed by {@code left << 16 | right} glyph ids. */
    private final Map<Integer, Integer> kerning = new HashMap<Integer, Integer>();
    /** GSUB 'liga': each ligature's component glyphs and the glyph that replaces them. */
    private final java.util.List<int[]> ligatures = new java.util.ArrayList<int[]>();

    public OpenType(byte[] file) {
        data = ByteBuffer.wrap(file);
        int tableCount = u16(4);
        for (int index = 0; index < tableCount; index++) {
            int record = 12 + index * 16;
            String tag = new String(file, record, 4, java.nio.charset.StandardCharsets.US_ASCII);
            tables.put(tag, new int[] {(int) u32(record + 8), (int) u32(record + 12)});
        }
        int head = table("head");
        unitsPerEm = u16(head + 18);
        int locaFormat = s16(head + 50);
        int hhea = table("hhea");
        int os2 = table("OS/2");
        // Typographic metrics: the launcher's WebView lays lines out by them.
        boolean useTypo = os2 >= 0 && (u16(os2 + 62) & (1 << 7)) != 0;
        ascender = useTypo ? s16(os2 + 68) : s16(hhea + 4);
        descender = useTypo ? s16(os2 + 70) : s16(hhea + 6);
        lineGap = useTypo ? s16(os2 + 72) : s16(hhea + 8);
        capHeight = os2 >= 0 && u16(os2) >= 2 ? s16(os2 + 88) : ascender;
        xHeight = os2 >= 0 && u16(os2) >= 2 ? s16(os2 + 86) : ascender / 2;
        glyphCount = u16(table("maxp") + 4);

        int metricsCount = u16(hhea + 34);
        int hmtx = table("hmtx");
        advances = new int[glyphCount];
        for (int glyph = 0; glyph < glyphCount; glyph++) {
            advances[glyph] = u16(hmtx + Math.min(glyph, metricsCount - 1) * 4);
        }

        int loca = table("loca");
        glyphOffsets = new int[glyphCount + 1];
        for (int glyph = 0; glyph <= glyphCount; glyph++) {
            glyphOffsets[glyph] = locaFormat == 0 ? u16(loca + glyph * 2) * 2 : (int) u32(loca + glyph * 4);
        }
        glyfOffset = table("glyf");

        readCmap(table("cmap"));
        int gpos = table("GPOS");
        if (gpos >= 0) {
            readKerning(gpos);
        }
        int gsub = table("GSUB");
        if (gsub >= 0) {
            readLigatures(gsub);
        }
    }

    /**
     * The font's standard ligatures ('liga', which a browser applies by
     * default): each entry is {ligature glyph, component glyph, component
     * glyph, ...}, in lookup order.
     */
    public java.util.List<int[]> ligatures() {
        return ligatures;
    }

    /** The code point that maps to a glyph, or -1. */
    public int codePointOf(int glyph) {
        for (Map.Entry<Integer, Integer> entry : cmap.entrySet()) {
            if (entry.getValue().intValue() == glyph) {
                return entry.getKey().intValue();
            }
        }
        return -1;
    }

    private void readLigatures(int gsub) {
        int featureList = gsub + u16(gsub + 6);
        int lookupList = gsub + u16(gsub + 8);
        java.util.Set<Integer> lookups = new java.util.TreeSet<Integer>();
        int featureCount = u16(featureList);
        for (int index = 0; index < featureCount; index++) {
            int record = featureList + 2 + index * 6;
            String tag = new String(new byte[] {data.get(record), data.get(record + 1),
                data.get(record + 2), data.get(record + 3)}, java.nio.charset.StandardCharsets.US_ASCII);
            if (!"liga".equals(tag)) {
                continue;
            }
            int feature = featureList + u16(record + 4);
            int count = u16(feature + 2);
            for (int l = 0; l < count; l++) {
                lookups.add(Integer.valueOf(u16(feature + 4 + l * 2)));
            }
        }
        for (Integer lookupIndex : lookups) {
            int lookup = lookupList + u16(lookupList + 2 + lookupIndex.intValue() * 2);
            int type = u16(lookup);
            int subtables = u16(lookup + 4);
            for (int s = 0; s < subtables; s++) {
                int subtable = lookup + u16(lookup + 6 + s * 2);
                int subtype = type;
                if (type == 7) {
                    subtype = u16(subtable + 2);
                    subtable = subtable + (int) u32(subtable + 4);
                }
                if (subtype != 4) {
                    continue;
                }
                int[] first = coverage(subtable + u16(subtable + 2));
                int sets = u16(subtable + 4);
                for (int set = 0; set < sets && set < first.length; set++) {
                    int ligatureSet = subtable + u16(subtable + 6 + set * 2);
                    int count = u16(ligatureSet);
                    for (int l = 0; l < count; l++) {
                        int ligature = ligatureSet + u16(ligatureSet + 2 + l * 2);
                        int glyph = u16(ligature);
                        int components = u16(ligature + 2);
                        int[] entry = new int[components + 1];
                        entry[0] = glyph;
                        entry[1] = first[set];
                        for (int c = 1; c < components; c++) {
                            entry[c + 1] = u16(ligature + 4 + (c - 1) * 2);
                        }
                        ligatures.add(entry);
                    }
                }
            }
        }
    }

    /** The glyph for a code point, or 0 (.notdef) when the font has none. */
    public int glyph(int codePoint) {
        Integer found = cmap.get(Integer.valueOf(codePoint));
        return found == null ? 0 : found.intValue();
    }

    public boolean has(int codePoint) {
        return cmap.containsKey(Integer.valueOf(codePoint));
    }

    /** Every code point the font maps. */
    public int[] codePoints() {
        int[] all = new int[cmap.size()];
        int at = 0;
        for (Integer code : cmap.keySet()) {
            all[at++] = code.intValue();
        }
        java.util.Arrays.sort(all);
        return all;
    }

    /** Horizontal advance, font units. */
    public int advance(int glyph) {
        return advances[glyph];
    }

    /** The GPOS pair adjustment between two glyphs, font units; 0 when none. */
    public int kerning(int left, int right) {
        Integer value = kerning.get(Integer.valueOf(left << 16 | right));
        return value == null ? 0 : value.intValue();
    }

    /**
     * A glyph's outline in font units, y up, with composites resolved. Empty
     * for a glyph with no contours (the space).
     */
    public Path2D.Double outline(int glyph) {
        Path2D.Double path = new Path2D.Double(Path2D.WIND_NON_ZERO);
        appendOutline(glyph, path, 1, 0, 0, 1, 0, 0, 0);
        return path;
    }

    // -----------------------------------------------------------------------
    // glyf
    // -----------------------------------------------------------------------

    private void appendOutline(int glyph, Path2D.Double path,
            double a, double b, double c, double d, double dx, double dy, int depth) {
        if (depth > 8 || glyph < 0 || glyph >= glyphCount) {
            return;
        }
        int start = glyphOffsets[glyph];
        int end = glyphOffsets[glyph + 1];
        if (end <= start) {
            return;
        }
        int at = glyfOffset + start;
        int contours = s16(at);
        if (contours >= 0) {
            simpleGlyph(at, contours, path, a, b, c, d, dx, dy);
        } else {
            compositeGlyph(at + 10, path, a, b, c, d, dx, dy, depth);
        }
    }

    private void simpleGlyph(int at, int contourCount, Path2D.Double path,
            double a, double b, double c, double d, double dx, double dy) {
        int[] ends = new int[contourCount];
        int cursor = at + 10;
        for (int index = 0; index < contourCount; index++) {
            ends[index] = u16(cursor);
            cursor += 2;
        }
        int points = contourCount == 0 ? 0 : ends[contourCount - 1] + 1;
        int instructions = u16(cursor);
        cursor += 2 + instructions;

        int[] flags = new int[points];
        for (int index = 0; index < points; ) {
            int flag = u8(cursor++);
            flags[index++] = flag;
            if ((flag & 8) != 0) {
                int repeat = u8(cursor++);
                for (int r = 0; r < repeat && index < points; r++) {
                    flags[index++] = flag;
                }
            }
        }
        int[] xs = new int[points];
        int value = 0;
        for (int index = 0; index < points; index++) {
            int flag = flags[index];
            if ((flag & 2) != 0) {
                int delta = u8(cursor++);
                value += (flag & 16) != 0 ? delta : -delta;
            } else if ((flag & 16) == 0) {
                value += s16(cursor);
                cursor += 2;
            }
            xs[index] = value;
        }
        int[] ys = new int[points];
        value = 0;
        for (int index = 0; index < points; index++) {
            int flag = flags[index];
            if ((flag & 4) != 0) {
                int delta = u8(cursor++);
                value += (flag & 32) != 0 ? delta : -delta;
            } else if ((flag & 32) == 0) {
                value += s16(cursor);
                cursor += 2;
            }
            ys[index] = value;
        }

        int first = 0;
        for (int contour = 0; contour < contourCount; contour++) {
            int last = ends[contour];
            int count = last - first + 1;
            if (count >= 2) {
                contour(path, xs, ys, flags, first, count, a, b, c, d, dx, dy);
            }
            first = last + 1;
        }
    }

    /** One quadratic contour: on-curve points joined by lines or one off-curve control each. */
    private static void contour(Path2D.Double path, int[] xs, int[] ys, int[] flags, int first, int count,
            double a, double b, double c, double d, double dx, double dy) {
        // Start on an on-curve point; if there is none, at the midpoint of the first two.
        int startIndex = -1;
        for (int i = 0; i < count; i++) {
            if ((flags[first + i] & 1) != 0) {
                startIndex = i;
                break;
            }
        }
        double sx;
        double sy;
        if (startIndex < 0) {
            sx = (xs[first] + xs[first + 1]) / 2.0;
            sy = (ys[first] + ys[first + 1]) / 2.0;
            startIndex = 0;
        } else {
            sx = xs[first + startIndex];
            sy = ys[first + startIndex];
            startIndex++;
        }
        path.moveTo(tx(a, c, dx, sx, sy), ty(b, d, dy, sx, sy));
        boolean pendingControl = false;
        double cx = 0;
        double cy = 0;
        for (int step = 0; step < count; step++) {
            int index = first + (startIndex + step) % count;
            double px = xs[index];
            double py = ys[index];
            boolean on = (flags[index] & 1) != 0;
            if (on) {
                if (pendingControl) {
                    path.quadTo(tx(a, c, dx, cx, cy), ty(b, d, dy, cx, cy), tx(a, c, dx, px, py), ty(b, d, dy, px, py));
                    pendingControl = false;
                } else {
                    path.lineTo(tx(a, c, dx, px, py), ty(b, d, dy, px, py));
                }
            } else {
                if (pendingControl) {
                    double mx = (cx + px) / 2.0;
                    double my = (cy + py) / 2.0;
                    path.quadTo(tx(a, c, dx, cx, cy), ty(b, d, dy, cx, cy), tx(a, c, dx, mx, my), ty(b, d, dy, mx, my));
                }
                cx = px;
                cy = py;
                pendingControl = true;
            }
        }
        if (pendingControl) {
            path.quadTo(tx(a, c, dx, cx, cy), ty(b, d, dy, cx, cy), tx(a, c, dx, sx, sy), ty(b, d, dy, sx, sy));
        }
        path.closePath();
    }

    private static double tx(double a, double c, double dx, double x, double y) {
        return a * x + c * y + dx;
    }

    private static double ty(double b, double d, double dy, double x, double y) {
        return b * x + d * y + dy;
    }

    private void compositeGlyph(int at, Path2D.Double path,
            double a, double b, double c, double d, double dx, double dy, int depth) {
        int cursor = at;
        while (true) {
            int flags = u16(cursor);
            int component = u16(cursor + 2);
            cursor += 4;
            double ox;
            double oy;
            if ((flags & 1) != 0) {
                ox = s16(cursor);
                oy = s16(cursor + 2);
                cursor += 4;
            } else {
                ox = (byte) data.get(cursor);
                oy = (byte) data.get(cursor + 1);
                cursor += 2;
            }
            double ca = 1;
            double cb = 0;
            double cc = 0;
            double cd = 1;
            if ((flags & 8) != 0) {
                ca = cd = f2dot14(cursor);
                cursor += 2;
            } else if ((flags & 0x40) != 0) {
                ca = f2dot14(cursor);
                cd = f2dot14(cursor + 2);
                cursor += 4;
            } else if ((flags & 0x80) != 0) {
                ca = f2dot14(cursor);
                cb = f2dot14(cursor + 2);
                cc = f2dot14(cursor + 4);
                cd = f2dot14(cursor + 6);
                cursor += 8;
            }
            // Compose: the component's transform, then the parent's.
            double na = a * ca + c * cb;
            double nb = b * ca + d * cb;
            double nc = a * cc + c * cd;
            double nd = b * cc + d * cd;
            double ndx = a * ox + c * oy + dx;
            double ndy = b * ox + d * oy + dy;
            appendOutline(component, path, na, nb, nc, nd, ndx, ndy, depth + 1);
            if ((flags & 0x20) == 0) {
                break;
            }
        }
    }

    private double f2dot14(int at) {
        return s16(at) / 16384.0;
    }

    // -----------------------------------------------------------------------
    // cmap
    // -----------------------------------------------------------------------

    private void readCmap(int cmapTable) {
        int count = u16(cmapTable + 2);
        int best = -1;
        int bestFormat = 0;
        for (int index = 0; index < count; index++) {
            int record = cmapTable + 4 + index * 8;
            int platform = u16(record);
            int encoding = u16(record + 2);
            int offset = cmapTable + (int) u32(record + 4);
            int format = u16(offset);
            boolean unicode = platform == 0 || (platform == 3 && (encoding == 1 || encoding == 10));
            if (unicode && (format == 12 || (format == 4 && bestFormat != 12))) {
                best = offset;
                bestFormat = format;
            }
        }
        if (best < 0) {
            throw new IllegalArgumentException("font has no Unicode cmap");
        }
        if (bestFormat == 4) {
            int segments = u16(best + 6) / 2;
            int endCodes = best + 14;
            int startCodes = endCodes + segments * 2 + 2;
            int deltas = startCodes + segments * 2;
            int rangeOffsets = deltas + segments * 2;
            for (int segment = 0; segment < segments; segment++) {
                int end = u16(endCodes + segment * 2);
                int start = u16(startCodes + segment * 2);
                int delta = s16(deltas + segment * 2);
                int rangeOffsetAt = rangeOffsets + segment * 2;
                int rangeOffset = u16(rangeOffsetAt);
                for (int code = start; code <= end && code != 0xFFFF; code++) {
                    int glyph;
                    if (rangeOffset == 0) {
                        glyph = (code + delta) & 0xFFFF;
                    } else {
                        int glyphAt = rangeOffsetAt + rangeOffset + (code - start) * 2;
                        glyph = u16(glyphAt);
                        if (glyph != 0) {
                            glyph = (glyph + delta) & 0xFFFF;
                        }
                    }
                    if (glyph != 0) {
                        cmap.put(Integer.valueOf(code), Integer.valueOf(glyph));
                    }
                }
            }
        } else {
            long groups = u32(best + 12);
            for (int group = 0; group < groups; group++) {
                int record = best + 16 + group * 12;
                long start = u32(record);
                long end = u32(record + 4);
                long glyph = u32(record + 8);
                for (long code = start; code <= end; code++) {
                    cmap.put(Integer.valueOf((int) code), Integer.valueOf((int) (glyph + code - start)));
                }
            }
        }
    }

    // -----------------------------------------------------------------------
    // GPOS pair kerning
    // -----------------------------------------------------------------------

    private void readKerning(int gpos) {
        int featureList = gpos + u16(gpos + 6);
        int lookupList = gpos + u16(gpos + 8);
        int featureCount = u16(featureList);
        java.util.Set<Integer> lookups = new java.util.TreeSet<Integer>();
        for (int index = 0; index < featureCount; index++) {
            int record = featureList + 2 + index * 6;
            String tag = new String(new byte[] {data.get(record), data.get(record + 1),
                data.get(record + 2), data.get(record + 3)}, java.nio.charset.StandardCharsets.US_ASCII);
            if (!"kern".equals(tag)) {
                continue;
            }
            int feature = featureList + u16(record + 4);
            int count = u16(feature + 2);
            for (int l = 0; l < count; l++) {
                lookups.add(Integer.valueOf(u16(feature + 4 + l * 2)));
            }
        }
        // Applied in lookup order; within a lookup the first subtable that
        // covers the pair decides it, as a shaper would.
        java.util.Set<Integer> decided = new java.util.HashSet<Integer>();
        for (Integer lookupIndex : lookups) {
            int lookup = lookupList + u16(lookupList + 2 + lookupIndex.intValue() * 2);
            int type = u16(lookup);
            int subtableCount = u16(lookup + 4);
            decided.clear();
            for (int s = 0; s < subtableCount; s++) {
                int subtable = lookup + u16(lookup + 6 + s * 2);
                int subtype = type;
                if (type == 9) {
                    subtype = u16(subtable + 2);
                    subtable = subtable + (int) u32(subtable + 4);
                }
                if (subtype == 2) {
                    pairSubtable(subtable, decided);
                }
            }
        }
    }

    private void pairSubtable(int at, java.util.Set<Integer> decided) {
        int format = u16(at);
        int[] covered = coverage(at + u16(at + 2));
        int valueFormat1 = u16(at + 4);
        int valueFormat2 = u16(at + 6);
        int size1 = valueSize(valueFormat1);
        int size2 = valueSize(valueFormat2);
        if (format == 1) {
            int setCount = u16(at + 8);
            for (int index = 0; index < setCount && index < covered.length; index++) {
                int left = covered[index];
                int set = at + u16(at + 10 + index * 2);
                int pairs = u16(set);
                for (int p = 0; p < pairs; p++) {
                    int record = set + 2 + p * (2 + size1 + size2);
                    int right = u16(record);
                    int adjust = xAdvance(record + 2, valueFormat1);
                    put(left, right, adjust, decided);
                }
            }
        } else if (format == 2) {
            int classDef1 = at + u16(at + 8);
            int classDef2 = at + u16(at + 10);
            int class1Count = u16(at + 12);
            int class2Count = u16(at + 14);
            int[] classes1 = classes(classDef1);
            int[] classes2 = classes(classDef2);
            int recordSize = size1 + size2;
            for (int left : covered) {
                int c1 = left < classes1.length ? classes1[left] : 0;
                if (c1 >= class1Count) {
                    continue;
                }
                for (int right = 0; right < glyphCount; right++) {
                    int c2 = right < classes2.length ? classes2[right] : 0;
                    if (c2 >= class2Count) {
                        continue;
                    }
                    int record = at + 16 + (c1 * class2Count + c2) * recordSize;
                    int adjust = xAdvance(record, valueFormat1);
                    if (adjust != 0) {
                        put(left, right, adjust, decided);
                    }
                }
            }
        }
    }

    private void put(int left, int right, int adjust, java.util.Set<Integer> decided) {
        Integer key = Integer.valueOf(left << 16 | right);
        if (!decided.add(key)) {
            return;
        }
        if (adjust == 0) {
            return;
        }
        Integer before = kerning.get(key);
        kerning.put(key, Integer.valueOf(adjust + (before == null ? 0 : before.intValue())));
    }

    private int xAdvance(int at, int valueFormat) {
        int cursor = at;
        if ((valueFormat & 1) != 0) {
            cursor += 2;
        }
        if ((valueFormat & 2) != 0) {
            cursor += 2;
        }
        if ((valueFormat & 4) != 0) {
            return s16(cursor);
        }
        return 0;
    }

    private static int valueSize(int valueFormat) {
        return Integer.bitCount(valueFormat & 0xFF) * 2;
    }

    private int[] coverage(int at) {
        int format = u16(at);
        if (format == 1) {
            int count = u16(at + 2);
            int[] glyphs = new int[count];
            for (int index = 0; index < count; index++) {
                glyphs[index] = u16(at + 4 + index * 2);
            }
            return glyphs;
        }
        int ranges = u16(at + 2);
        int total = 0;
        for (int r = 0; r < ranges; r++) {
            int record = at + 4 + r * 6;
            total += u16(record + 2) - u16(record) + 1;
        }
        int[] glyphs = new int[total];
        for (int r = 0; r < ranges; r++) {
            int record = at + 4 + r * 6;
            int start = u16(record);
            int end = u16(record + 2);
            int startIndex = u16(record + 4);
            for (int g = start; g <= end; g++) {
                glyphs[startIndex + g - start] = g;
            }
        }
        return glyphs;
    }

    private int[] classes(int at) {
        int[] result = new int[glyphCount];
        int format = u16(at);
        if (format == 1) {
            int start = u16(at + 2);
            int count = u16(at + 4);
            for (int index = 0; index < count && start + index < glyphCount; index++) {
                result[start + index] = u16(at + 6 + index * 2);
            }
        } else if (format == 2) {
            int ranges = u16(at + 2);
            for (int r = 0; r < ranges; r++) {
                int record = at + 4 + r * 6;
                int start = u16(record);
                int end = u16(record + 2);
                int value = u16(record + 4);
                for (int g = start; g <= end && g < glyphCount; g++) {
                    result[g] = value;
                }
            }
        }
        return result;
    }

    // -----------------------------------------------------------------------
    // Bytes
    // -----------------------------------------------------------------------

    private int table(String tag) {
        int[] entry = tables.get(tag);
        if (entry == null) {
            if ("OS/2".equals(tag) || "GPOS".equals(tag) || "GSUB".equals(tag)) {
                return -1;
            }
            throw new IllegalArgumentException("font has no " + tag + " table");
        }
        return entry[0];
    }

    private int u8(int at) {
        return data.get(at) & 0xFF;
    }

    private int u16(int at) {
        return data.getShort(at) & 0xFFFF;
    }

    private int s16(int at) {
        return data.getShort(at);
    }

    private long u32(int at) {
        return data.getInt(at) & 0xFFFFFFFFL;
    }
}
