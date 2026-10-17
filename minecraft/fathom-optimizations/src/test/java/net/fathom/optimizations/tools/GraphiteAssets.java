package net.fathom.optimizations.tools;

import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Random;

import javax.imageio.ImageIO;

import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * Makes the launcher's grain tile, offline.
 *
 * <pre>
 *   gradle graphiteAssets
 * </pre>
 *
 * <p>A 256x256 static monochrome noise tile, drawn at 1:1 device pixels and
 * 3% alpha over the background, so a large dark gradient never bands. The
 * same seed every time, so the file only changes when the tile size does.
 *
 * <p>The wordmark's outlines are not made here: {@code tools/generate-wordmark.py}
 * shapes them with HarfBuzz, which applies Geist's GPOS kerning; Java's per-glyph
 * layout does not.
 */
public final class GraphiteAssets {

    private GraphiteAssets() {}

    public static void main(String[] args) throws Exception {
        File repo = new File(args.length > 0 ? args[0] : "../..").getCanonicalFile();
        File launcherAssets = new File(repo, "desktop/src/lib/graphite");
        launcherAssets.mkdirs();

        BufferedImage grain = grain(Math.round(GraphiteTokens.BACKDROP_GRAIN_TILE));
        ImageIO.write(grain, "png", new File(launcherAssets, "grain.png"));
        System.out.println("grain: " + grain.getWidth() + "x" + grain.getHeight());
    }

    /** Static monochrome noise, the same every build. */
    static BufferedImage grain(int size) {
        BufferedImage tile = new BufferedImage(size, size, BufferedImage.TYPE_INT_RGB);
        Random random = new Random(0x6A5A11EL);
        for (int y = 0; y < size; y++) {
            for (int x = 0; x < size; x++) {
                int v = random.nextInt(256);
                tile.setRGB(x, y, (v << 16) | (v << 8) | v);
            }
        }
        return tile;
    }
}
