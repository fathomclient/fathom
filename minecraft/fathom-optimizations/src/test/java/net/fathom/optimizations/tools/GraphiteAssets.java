package net.fathom.optimizations.tools;

import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.RenderingHints;
import java.awt.Shape;
import java.awt.font.FontRenderContext;
import java.awt.geom.AffineTransform;
import java.awt.geom.PathIterator;
import java.awt.geom.Rectangle2D;
import java.awt.image.BufferedImage;
import java.io.File;
import java.io.FileInputStream;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.Locale;
import java.util.Random;

import javax.imageio.ImageIO;

import net.fathom.optimizations.core.ui.BackdropProcess;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * Makes the Graphite + Instrument assets that both renderers share, offline.
 *
 * <pre>
 *   gradle graphiteAssets                         (wordmark and grain only)
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
 *   <li><b>The wordmark.</b> "FATHOM" in Geist Medium, tracked +10%, converted
 *   to one outline path -- the launcher draws it as SVG and the game fills the
 *   same path -- so it is one drawing, not a font rasterised twice.</li>
 * </ul>
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

        wordmark(repo);
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

    /**
     * FATHOM in Geist Medium capitals, tracked +10% of the em, as one path.
     * Units: cap height 100, y down, the cap's top at 0 and the baseline at 100,
     * the first letter's ink at x 0.
     */
    static void wordmark(File repo) throws Exception {
        File ttf = new File(repo,
                "minecraft/fathom-optimizations/src/main/resources/assets/fathom/font/Geist-Medium.ttf");
        Font base;
        try (InputStream in = new FileInputStream(ttf)) {
            base = Font.createFont(Font.TRUETYPE_FONT, in);
        }
        FontRenderContext frc = new FontRenderContext(new AffineTransform(), true, true);
        // Size the font so a capital is 100 units tall.
        Font probe = base.deriveFont(1000f);
        double capAt1000 = probe.createGlyphVector(frc, "H").getOutline().getBounds2D().getHeight();
        float size = (float) (1000.0 * 100.0 / capAt1000);
        Font font = base.deriveFont(size);
        float tracking = GraphiteTokens.WORDMARK_TRACKING * size;

        String text = GraphiteTokens.WORDMARK_TEXT;
        StringBuilder path = new StringBuilder();
        double pen = 0;
        double inkLeft = Double.NaN;
        double inkRight = 0;
        for (int index = 0; index < text.length(); index++) {
            String letter = String.valueOf(text.charAt(index));
            java.awt.font.GlyphVector glyph = font.createGlyphVector(frc, letter);
            Shape outline = glyph.getOutline((float) pen, 100f);
            Rectangle2D bounds = outline.getBounds2D();
            if (Double.isNaN(inkLeft)) {
                inkLeft = bounds.getMinX();
            }
            inkRight = Math.max(inkRight, bounds.getMaxX());
            append(path, outline, 0);
            pen += glyph.getGlyphMetrics(0).getAdvance() + tracking;
        }
        // Shift so the ink starts at 0.
        String shifted = shiftX(path.toString(), -inkLeft);
        double width = inkRight - inkLeft;

        String ts = "/* Generated by GraphiteAssets (gradle graphiteAssets). Do not edit. */\n"
                + "/** FATHOM in Geist Medium, tracked +10%: cap 100 units, y down, baseline at 100. */\n"
                + "export const GRAPHITE_WORDMARK = {\n"
                + "  d: '" + shifted + "',\n"
                + "  width: " + fmt(width) + ",\n"
                + "  cap: 100,\n"
                + "} as const;\n";
        Files.write(new File(repo, "desktop/src/lib/brand/graphiteWordmark.ts").toPath(),
                ts.getBytes(StandardCharsets.UTF_8));

        StringBuilder java = new StringBuilder();
        java.append("package net.fathom.optimizations.generated;\n\n");
        java.append("/**\n * The Graphite wordmark: FATHOM in Geist Medium, tracked +10%, as one SVG path.\n");
        java.append(" * Generated by GraphiteAssets (gradle graphiteAssets); the launcher draws the same\n");
        java.append(" * path. Cap height 100 units, y down, baseline at 100. Do not edit.\n */\n");
        java.append("public final class GraphiteWordmark {\n    private GraphiteWordmark() {}\n\n");
        java.append("    public static final float CAP = 100f;\n");
        java.append("    public static final float WIDTH = ").append(fmt(width)).append("f;\n");
        java.append("    public static final String PATH =\n");
        String[] chunks = shifted.split("(?<=Z)");
        for (int index = 0; index < chunks.length; index++) {
            java.append("            \"").append(chunks[index]).append("\"")
                    .append(index + 1 < chunks.length ? " +\n" : ";\n");
        }
        java.append("}\n");
        Files.write(new File(repo, "minecraft/fathom-optimizations/src/main/java/net/fathom/optimizations/generated/GraphiteWordmark.java").toPath(),
                java.toString().getBytes(StandardCharsets.UTF_8));
        System.out.println("wordmark: width " + fmt(width) + " at cap 100, " + shifted.length() + " path chars");
    }

    private static void append(StringBuilder out, Shape shape, double dx) {
        PathIterator it = shape.getPathIterator(null);
        float[] c = new float[6];
        while (!it.isDone()) {
            switch (it.currentSegment(c)) {
                case PathIterator.SEG_MOVETO:
                    out.append('M').append(fmt(c[0] + dx)).append(' ').append(fmt(c[1]));
                    break;
                case PathIterator.SEG_LINETO:
                    out.append('L').append(fmt(c[0] + dx)).append(' ').append(fmt(c[1]));
                    break;
                case PathIterator.SEG_QUADTO:
                    out.append('Q').append(fmt(c[0] + dx)).append(' ').append(fmt(c[1])).append(' ')
                            .append(fmt(c[2] + dx)).append(' ').append(fmt(c[3]));
                    break;
                case PathIterator.SEG_CUBICTO:
                    out.append('C').append(fmt(c[0] + dx)).append(' ').append(fmt(c[1])).append(' ')
                            .append(fmt(c[2] + dx)).append(' ').append(fmt(c[3])).append(' ')
                            .append(fmt(c[4] + dx)).append(' ').append(fmt(c[5]));
                    break;
                case PathIterator.SEG_CLOSE:
                    out.append('Z');
                    break;
                default:
                    break;
            }
            it.next();
        }
    }

    /** Move every x coordinate of an absolute M/L/Q/C/Z path. */
    private static String shiftX(String path, double dx) {
        StringBuilder out = new StringBuilder();
        java.util.regex.Matcher m = java.util.regex.Pattern.compile("([MLQCZ])([^MLQCZ]*)").matcher(path);
        while (m.find()) {
            out.append(m.group(1));
            String body = m.group(2).trim();
            if (body.isEmpty()) {
                continue;
            }
            String[] numbers = body.split(" ");
            for (int index = 0; index < numbers.length; index++) {
                double value = Double.parseDouble(numbers[index]);
                if (index % 2 == 0) {
                    value += dx;
                }
                out.append(index == 0 ? "" : " ").append(fmt(value));
            }
        }
        return out.toString();
    }

    private static String fmt(double value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        if (text.contains(".")) {
            text = text.replaceAll("0+$", "").replaceAll("\\.$", "");
        }
        return "-0".equals(text) ? "0" : text;
    }
}
