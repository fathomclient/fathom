package net.fathom.optimizations.core.ui;

import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * The Graphite backdrop treatment: a Minecraft frame turned into cinematic,
 * recognisable atmosphere. One implementation, used offline for the shipped captures
 * (the launcher and the title screen) and once, at quarter resolution, when an
 * in-game menu opens over the live world -- so both come out the same.
 *
 * <p>In order: a restrained Gaussian focus, partial desaturation, and a
 * luminance shoulder that keeps bright terrain behind text without flattening
 * its structure. Vignette and grain are
 * not baked in: they are drawn over the result at runtime, so the flat fallback
 * gets exactly the same treatment.
 *
 * <p>Pixels are packed ARGB, alpha ignored and written opaque. Everything works
 * in place on caller-owned arrays; {@link #process} allocates two scratch rows
 * of floats per call and nothing per pixel.
 */
public final class BackdropProcess {

    private static final float SATURATION = GraphiteTokens.BACKDROP_SATURATION;
    private static final float SATURATION_MAX = GraphiteTokens.BACKDROP_SATURATION_MAX;
    private static final float LUMINANCE_SCALE = GraphiteTokens.BACKDROP_LUMINANCE_SCALE;
    private static final float KNEE = GraphiteTokens.BACKDROP_LUMINANCE_KNEE;
    private static final float CEILING = GraphiteTokens.BACKDROP_LUMINANCE_CEILING;

    /** sRGB byte to linear light, once. */
    private static final float[] TO_LINEAR = new float[256];
    /** Linear light to sRGB byte, at 4096 steps: plenty for values this dark. */
    private static final int LINEAR_STEPS = 4096;
    private static final int[] TO_SRGB = new int[LINEAR_STEPS + 1];

    static {
        for (int value = 0; value < 256; value++) {
            float c = value / 255f;
            TO_LINEAR[value] = c <= 0.04045f ? c / 12.92f : (float) Math.pow((c + 0.055f) / 1.055f, 2.4f);
        }
        for (int step = 0; step <= LINEAR_STEPS; step++) {
            float linear = step / (float) LINEAR_STEPS;
            float c = linear <= 0.0031308f ? linear * 12.92f
                    : 1.055f * (float) Math.pow(linear, 1f / 2.4f) - 0.055f;
            TO_SRGB[step] = Math.max(0, Math.min(255, Math.round(c * 255f)));
        }
    }

    private BackdropProcess() {}

    /** The blur's sigma in pixels, for a frame whose longer edge is {@code longEdge}. */
    public static float sigmaFor(int longEdge) {
        return Math.max(0.5f, GraphiteTokens.BACKDROP_BLUR_SIGMA * longEdge);
    }

    /**
     * Box-average {@code source} down by a whole factor. The in-game capture
     * runs at a quarter of the framebuffer, and the offline tool at a quarter of
     * the shipped size: heavily blurred, the difference cannot be seen.
     */
    public static int[] downsample(int[] source, int width, int height, int factor, int[] into) {
        int outWidth = Math.max(1, width / factor);
        int outHeight = Math.max(1, height / factor);
        int[] out = into != null && into.length >= outWidth * outHeight ? into : new int[outWidth * outHeight];
        int area = factor * factor;
        for (int y = 0; y < outHeight; y++) {
            for (int x = 0; x < outWidth; x++) {
                int r = 0;
                int g = 0;
                int b = 0;
                for (int dy = 0; dy < factor; dy++) {
                    int row = (y * factor + dy) * width + x * factor;
                    for (int dx = 0; dx < factor; dx++) {
                        int pixel = source[row + dx];
                        r += (pixel >> 16) & 0xFF;
                        g += (pixel >> 8) & 0xFF;
                        b += pixel & 0xFF;
                    }
                }
                out[y * outWidth + x] = 0xFF000000 | ((r / area) << 16) | ((g / area) << 8) | (b / area);
            }
        }
        return out;
    }

    /** Blur, desaturate and darken {@code pixels} in place. */
    public static void process(int[] pixels, int width, int height, float sigma) {
        blur(pixels, width, height, sigma);
        for (int index = 0; index < width * height; index++) {
            pixels[index] = grade(pixels[index]);
        }
    }

    /** Desaturate and darken one pixel. Public for the tests. */
    public static int grade(int pixel) {
        float r = ((pixel >> 16) & 0xFF) / 255f;
        float g = ((pixel >> 8) & 0xFF) / 255f;
        float b = (pixel & 0xFF) / 255f;

        // Keep 35% of the saturation: toward the pixel's own luma.
        float luma = 0.2126f * r + 0.7152f * g + 0.0722f * b;
        r = luma + (r - luma) * SATURATION;
        g = luma + (g - luma) * SATURATION;
        b = luma + (b - luma) * SATURATION;

        // Then no pixel above HSV saturation 0.12, whatever biome it came from.
        float max = Math.max(r, Math.max(g, b));
        float min = Math.min(r, Math.min(g, b));
        if (max > 0f) {
            float saturation = (max - min) / max;
            if (saturation > SATURATION_MAX) {
                float keep = SATURATION_MAX / saturation;
                r = max - (max - r) * keep;
                g = max - (max - g) * keep;
                b = max - (max - b) * keep;
            }
        }

        // Darken in linear light, by luminance, keeping the hue that is left.
        float lr = TO_LINEAR[clampByte(r)];
        float lg = TO_LINEAR[clampByte(g)];
        float lb = TO_LINEAR[clampByte(b)];
        float y = 0.2126f * lr + 0.7152f * lg + 0.0722f * lb;
        if (y <= 0f) {
            return 0xFF000000;
        }
        float target = softClip(y * LUMINANCE_SCALE);
        float ratio = target / y;
        return 0xFF000000 | (toSrgb(lr * ratio) << 16) | (toSrgb(lg * ratio) << 8) | toSrgb(lb * ratio);
    }

    /** Linear below the knee, then an exponential shoulder that never passes the ceiling. */
    public static float softClip(float y) {
        if (y <= KNEE) {
            return y;
        }
        float room = CEILING - KNEE;
        return KNEE + room * (1f - (float) Math.exp(-(y - KNEE) / room));
    }

    /** Separable Gaussian, clamped at the edges, three channels. */
    public static void blur(int[] pixels, int width, int height, float sigma) {
        int radius = Math.max(1, (int) Math.ceil(sigma * 3f));
        float[] kernel = new float[radius * 2 + 1];
        float sum = 0f;
        for (int k = -radius; k <= radius; k++) {
            float weight = (float) Math.exp(-(k * k) / (2f * sigma * sigma));
            kernel[k + radius] = weight;
            sum += weight;
        }
        for (int k = 0; k < kernel.length; k++) {
            kernel[k] /= sum;
        }
        int longest = Math.max(width, height);
        float[] line = new float[longest * 3];
        // Rows, then columns.
        for (int y = 0; y < height; y++) {
            pass(pixels, y * width, 1, width, kernel, radius, line);
        }
        for (int x = 0; x < width; x++) {
            pass(pixels, x, width, height, kernel, radius, line);
        }
    }

    private static void pass(int[] pixels, int start, int stride, int count,
            float[] kernel, int radius, float[] line) {
        for (int i = 0; i < count; i++) {
            int pixel = pixels[start + i * stride];
            line[i * 3] = (pixel >> 16) & 0xFF;
            line[i * 3 + 1] = (pixel >> 8) & 0xFF;
            line[i * 3 + 2] = pixel & 0xFF;
        }
        for (int i = 0; i < count; i++) {
            float r = 0f;
            float g = 0f;
            float b = 0f;
            for (int k = -radius; k <= radius; k++) {
                int at = i + k;
                at = at < 0 ? 0 : (at >= count ? count - 1 : at);
                float weight = kernel[k + radius];
                r += line[at * 3] * weight;
                g += line[at * 3 + 1] * weight;
                b += line[at * 3 + 2] * weight;
            }
            pixels[start + i * stride] = 0xFF000000 | (clamp255(r) << 16) | (clamp255(g) << 8) | clamp255(b);
        }
    }

    /** Relative luminance of a packed sRGB pixel, for checks. */
    public static float luminance(int pixel) {
        return 0.2126f * TO_LINEAR[(pixel >> 16) & 0xFF]
                + 0.7152f * TO_LINEAR[(pixel >> 8) & 0xFF]
                + 0.0722f * TO_LINEAR[pixel & 0xFF];
    }

    private static int toSrgb(float linear) {
        int step = Math.round(Math.max(0f, Math.min(1f, linear)) * LINEAR_STEPS);
        return TO_SRGB[step];
    }

    private static int clampByte(float value) {
        return Math.max(0, Math.min(255, Math.round(value * 255f)));
    }

    private static int clamp255(float value) {
        return Math.max(0, Math.min(255, Math.round(value)));
    }
}
