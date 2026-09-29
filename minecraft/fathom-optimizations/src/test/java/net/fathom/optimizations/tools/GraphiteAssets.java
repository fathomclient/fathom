package net.fathom.optimizations.tools;

import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.image.BufferedImage;
import java.io.File;
import java.util.Locale;
import java.util.Random;

import javax.imageio.ImageIO;

import net.fathom.optimizations.core.ui.BackdropProcess;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * Makes the Graphite + Instrument assets that both renderers share, offline.
 *
 * <pre>
 *   gradle graphiteAssets                         (grain only)
 *   gradle graphiteAssets -PbackdropSource=path   (and the backdrop from a capture)
 * </pre>
 *
 * <ul>
 *   <li><b>The backdrop.</b> A curated Minecraft scene -- captured clean or
 *   authored for the client -- gently focused and graded by
 *   {@link BackdropProcess}, the same code the game runs on the live world,
 *   and shipped at 2560x1440. The launcher and the title screen draw
 *   these identical files; neither blurs anything at runtime.</li>
 *   <li><b>The grain.</b> A 256x256 static monochrome noise tile, drawn by both
 *   at 1:1 device pixels and 3% alpha over the backdrop.</li>
 * </ul>
 *
 * <p>The wordmark's outlines are not made here: {@code tools/generate-wordmark.py}
 * shapes them with HarfBuzz, which applies Geist's GPOS kerning; Java's per-glyph
 * layout does not.
 */
public final class GraphiteAssets {

    private GraphiteAssets() {}

    public static void main(String[] args) throws Exception {
        File repo = new File(args.length > 0 ? args[0] : "../..").getCanonicalFile();
        String source = args.length > 1 ? args[1] : "";

        File gameTextures = new File(repo,
                "minecraft/fathom-optimizations/src/main/resources/assets/fathom-optimizations/textures/menu/graphite");
        File launcherAssets = new File(repo, "desktop/src/lib/graphite");
        File masters = new File(repo, "assets/graphite");
        gameTextures.mkdirs();
        launcherAssets.mkdirs();
        masters.mkdirs();

        BufferedImage grain = grain(Math.round(GraphiteTokens.BACKDROP_GRAIN_TILE));
        for (File dir : new File[] {masters, gameTextures, launcherAssets}) {
            ImageIO.write(grain, "png", new File(dir, "grain.png"));
        }
        System.out.println("grain: " + grain.getWidth() + "x" + grain.getHeight());

        if (!source.isEmpty()) {
            BufferedImage backdrop = backdrop(ImageIO.read(new File(source)));
            // Not the launcher: it draws the approved background image itself
            // and never read this processed copy.
            for (File dir : new File[] {masters, gameTextures}) {
                ImageIO.write(backdrop, "png", new File(dir, "backdrop-default.png"));
            }
            float brightest = 0f;
            for (int y = 0; y < backdrop.getHeight(); y += 4) {
                for (int x = 0; x < backdrop.getWidth(); x += 4) {
                    brightest = Math.max(brightest, BackdropProcess.luminance(backdrop.getRGB(x, y)));
                }
            }
            System.out.println("backdrop: " + backdrop.getWidth() + "x" + backdrop.getHeight()
                    + ", brightest relative luminance " + String.format(Locale.ROOT, "%.4f", brightest));
        }
    }

    /** The shipped backdrop: processed at half size, then scaled up once. */
    static BufferedImage backdrop(BufferedImage capture) {
        int outWidth = Math.round(GraphiteTokens.BACKDROP_WIDTH);
        int outHeight = Math.round(GraphiteTokens.BACKDROP_HEIGHT);
        // Cover-crop the capture to the shipped aspect first.
        float aspect = outWidth / (float) outHeight;
        int cropWidth = capture.getWidth();
        int cropHeight = Math.round(cropWidth / aspect);
        if (cropHeight > capture.getHeight()) {
            cropHeight = capture.getHeight();
            cropWidth = Math.round(cropHeight * aspect);
        }
        // Offline work can afford half resolution. Keeping twice the linear
        // detail of the live capture preserves recognisable block silhouettes
        // on the launcher and title screen without adding runtime work.
        int workWidth = outWidth / 2;
        int workHeight = outHeight / 2;
        BufferedImage work = new BufferedImage(workWidth, workHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = work.createGraphics();
        g.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BILINEAR);
        g.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        int cropX = (capture.getWidth() - cropWidth) / 2;
        int cropY = (capture.getHeight() - cropHeight) / 2;
        g.drawImage(capture, 0, 0, workWidth, workHeight,
                cropX, cropY, cropX + cropWidth, cropY + cropHeight, null);
        g.dispose();

        int[] pixels = work.getRGB(0, 0, workWidth, workHeight, null, 0, workWidth);
        BackdropProcess.process(pixels, workWidth, workHeight, BackdropProcess.sigmaFor(outWidth) / 2f);
        work.setRGB(0, 0, workWidth, workHeight, pixels, 0, workWidth);

        BufferedImage out = new BufferedImage(outWidth, outHeight, BufferedImage.TYPE_INT_RGB);
        Graphics2D up = out.createGraphics();
        up.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC);
        up.setRenderingHint(RenderingHints.KEY_RENDERING, RenderingHints.VALUE_RENDER_QUALITY);
        up.drawImage(work, 0, 0, outWidth, outHeight, null);
        up.dispose();
        return out;
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
