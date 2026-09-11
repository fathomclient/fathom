package net.fathom.optimizations.core.opt.resource;

/**
 * How large a resource pack's {@code pack.png} is allowed to be in video memory.
 *
 * <h2>What vanilla does</h2>
 *
 * <p>1.8.9 uploads every installed pack's {@code pack.png} as a GPU texture at whatever
 * resolution the pack author saved it, and keeps it for the session. The resource pack
 * screen draws each one into a 32x32 slot.
 *
 * <p>Nothing in the game ever samples those textures at more than 32 pixels across. A pack
 * that ships a 1024x1024 icon is therefore holding 4 MB of video memory to fill a square
 * the size of a fingernail, and the cost is per pack, paid at startup, before the user has
 * entered a world. A user with a folder of packs — which is the normal state for anyone
 * who has ever tried more than one — can lose hundreds of megabytes to icons they are not
 * looking at.
 *
 * <p>This is the second of the three defects MemoryFix names for 1.8.9, and it is the one
 * with the cleanest fix: the pixels are genuinely not needed.
 *
 * <h2>What this changes</h2>
 *
 * <p>The icon is downscaled once, on load, to {@link #MAX_EDGE} pixels on its longest
 * edge, and the full-size image is dropped. The visible result at a 32x32 draw is a
 * texture with a 2x margin over the size it is sampled at, which is more than a
 * bilinear filter needs.
 *
 * <h2>Why 64 and not 32</h2>
 *
 * <p>32 would be exactly enough for today's resource pack screen and would save four times
 * as much again. 64 leaves headroom for a GUI scale or a future screen that draws the icon
 * larger, and the difference between 64x64 and 32x32 is 12 KB per pack — not worth being
 * clever about, and being wrong here means a blurry icon rather than a saved megabyte.
 *
 * <h2>What this deliberately does not touch</h2>
 *
 * <p>Only {@code pack.png}. Block, item, GUI and entity textures are sampled at their full
 * resolution and downscaling any of them is a visible change to the game. This class takes
 * a pack icon and nothing else, so that it cannot grow into a texture-quality setting by
 * accident.
 */
public final class PackIconPolicy {

    /** The longest edge we keep. See the class comment for why this number. */
    public static final int MAX_EDGE = 64;

    /** What the resource pack screen actually draws an icon at, in 1.8.9. */
    public static final int DRAWN_EDGE = 32;

    /** Bytes per pixel once uploaded: RGBA8. */
    public static final int BYTES_PER_PIXEL = 4;

    /** A decision about one icon. */
    public static final class Plan {
        private final int sourceWidth;
        private final int sourceHeight;
        private final int targetWidth;
        private final int targetHeight;

        Plan(int sourceWidth, int sourceHeight, int targetWidth, int targetHeight) {
            this.sourceWidth = sourceWidth;
            this.sourceHeight = sourceHeight;
            this.targetWidth = targetWidth;
            this.targetHeight = targetHeight;
        }

        public int sourceWidth() {
            return sourceWidth;
        }

        public int sourceHeight() {
            return sourceHeight;
        }

        public int targetWidth() {
            return targetWidth;
        }

        public int targetHeight() {
            return targetHeight;
        }

        /** Whether anything needs doing. Most packs ship a 128x128 icon; some ship 64x64. */
        public boolean changed() {
            return targetWidth != sourceWidth || targetHeight != sourceHeight;
        }

        /** Video memory this icon would have taken unmodified. */
        public long sourceBytes() {
            return (long) sourceWidth * sourceHeight * BYTES_PER_PIXEL;
        }

        /** Video memory it takes after this plan. */
        public long targetBytes() {
            return (long) targetWidth * targetHeight * BYTES_PER_PIXEL;
        }

        /** What this saves, per pack. Never negative. */
        public long savedBytes() {
            return sourceBytes() - targetBytes();
        }

        @Override
        public String toString() {
            if (!changed()) {
                return sourceWidth + "x" + sourceHeight + " kept as is";
            }
            return sourceWidth + "x" + sourceHeight + " -> " + targetWidth + "x" + targetHeight
                    + " (" + savedBytes() + " bytes saved)";
        }
    }

    /**
     * Decide what to do with an icon of this size.
     *
     * <p>Aspect ratio is preserved, and neither edge is ever allowed to round down to
     * zero. Pack icons are square in practice, but "in practice" is not a guarantee about
     * files supplied by strangers, and a zero-width texture upload is a crash rather than
     * a blurry icon.
     */
    public static Plan plan(int width, int height) {
        if (width <= 0 || height <= 0) {
            throw new IllegalArgumentException("an icon must have positive dimensions, got "
                    + width + "x" + height);
        }
        int longest = Math.max(width, height);
        if (longest <= MAX_EDGE) {
            // Already small. Do not upscale: a 16x16 icon drawn at 32x32 is what the pack
            // author chose, and blowing it up to 64x64 would cost memory to change nothing.
            return new Plan(width, height, width, height);
        }
        double scale = (double) MAX_EDGE / (double) longest;
        int targetWidth = Math.max(1, (int) Math.round(width * scale));
        int targetHeight = Math.max(1, (int) Math.round(height * scale));
        return new Plan(width, height, targetWidth, targetHeight);
    }

    /**
     * Total video memory saved across a set of icons.
     *
     * <p>Takes sizes rather than images so the arithmetic can be checked against real pack
     * dimensions without decoding anything.
     */
    public static long savedBytes(int[][] sizes) {
        long saved = 0L;
        for (int[] size : sizes) {
            saved += plan(size[0], size[1]).savedBytes();
        }
        return saved;
    }

    private PackIconPolicy() {}
}
