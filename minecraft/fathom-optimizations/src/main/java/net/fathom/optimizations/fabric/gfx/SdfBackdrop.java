package net.fathom.optimizations.fabric.gfx;

import java.nio.FloatBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ARBFramebufferObject;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL12;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GL30;
import org.lwjgl.opengl.GLContext;

import com.mojang.blaze3d.platform.GlStateManager;

/**
 * The backdrop blur: what is behind the interface, blurred, for glass.
 *
 * <h2>The pipeline</h2>
 *
 * <ol>
 *   <li>The frame as it stands -- the world or the menu scene, and anything in
 *       {@code Layer.BACKGROUND} -- is copied once into a full-size texture.
 *       A copy rather than a blit, because a copy resolves a multisampled
 *       framebuffer and a scaled blit from one is an error.
 *   <li>Two linear 2:1 blits take it to a quarter of the size: each averages
 *       exactly 2x2 texels, so the result is a 4x4 box filter.
 *   <li>A separable Gaussian, horizontal then vertical, at that size, with
 *       taps placed between texels so each fetch weighs two.
 * </ol>
 *
 * <p>CSS's {@code blur(r)} is a Gaussian of standard deviation {@code r}; the
 * launcher's standard material is 18px, so at a 1.5 interface scale this is a
 * 27-pixel sigma -- 6.75 at quarter size, which is twenty-odd texels each way
 * in eleven fetches. Up to three strengths a frame, each two passes at
 * quarter size.
 *
 * <p>Nothing here runs unless a frame drew glass.
 */
final class SdfBackdrop {

    static final int SLOTS = 3;

    private final boolean core;
    private int width;
    private int height;
    private int quarterWidth;
    private int quarterHeight;

    private int fullTexture;
    private int fullFbo;
    private int halfTexture;
    private int halfFbo;
    private int scratchTexture;
    private int scratchFbo;
    private final int[] resultTexture = new int[SLOTS];
    private final int[] resultFbo = new int[SLOTS];
    private final float[] resultSigma = new float[SLOTS];
    private int resultCount;

    private int quadBuffer;
    private final float[] offsets = new float[SdfShaders.BLUR_TAPS];
    private final float[] weights = new float[SdfShaders.BLUR_TAPS];
    private final float[] raw = new float[SdfShaders.BLUR_TAPS * 2];

    /** Passes run this frame, for the statistics. */
    int passes;

    SdfBackdrop() {
        ContextCapabilities caps = GLContext.getCapabilities();
        core = caps.OpenGL30;
    }

    static boolean supported() {
        ContextCapabilities caps = GLContext.getCapabilities();
        return caps.OpenGL30 || caps.GL_ARB_framebuffer_object;
    }

    /** Forget last frame's strengths. */
    void beginFrame() {
        resultCount = 0;
        passes = 0;
    }

    /**
     * The slot for a blur strength, reusing one within a pixel of it; -1 when
     * three strengths are already in use (the nearest is used).
     *
     * @param sigma standard deviation, device pixels
     */
    int slot(float sigma) {
        int nearest = -1;
        float distance = Float.MAX_VALUE;
        for (int index = 0; index < resultCount; index++) {
            float d = Math.abs(resultSigma[index] - sigma);
            if (d < 1f) {
                return index;
            }
            if (d < distance) {
                distance = d;
                nearest = index;
            }
        }
        if (resultCount < SLOTS) {
            resultSigma[resultCount] = sigma;
            return resultCount++;
        }
        return nearest;
    }

    int slots() {
        return resultCount;
    }

    int texture(int slot) {
        return resultTexture[slot];
    }

    /**
     * Blur the frame now bound for reading into every requested slot. Leaves
     * the blur program bound and {@code sourceFbo} bound as the framebuffer,
     * with the viewport the full frame; the caller rebinds its own program.
     */
    void run(SdfShaders shaders, int sourceFbo, int frameWidth, int frameHeight) {
        if (resultCount == 0) {
            return;
        }
        ensure(frameWidth, frameHeight);
        // 1. The frame, copied (and resolved, if multisampled).
        GlStateManager.bindTexture(fullTexture);
        GL11.glCopyTexSubImage2D(GL11.GL_TEXTURE_2D, 0, 0, 0, 0, 0, width, height);
        // 2. Two exact 2:1 averages.
        blit(fullFbo, width, height, halfFbo, width / 2, height / 2);
        blit(halfFbo, width / 2, height / 2, resultFbo[0], quarterWidth, quarterHeight);
        passes += 3;
        // Every slot starts from the same quarter-size frame.
        for (int slot = 1; slot < resultCount; slot++) {
            blit(resultFbo[0], quarterWidth, quarterHeight, resultFbo[slot], quarterWidth, quarterHeight);
            passes++;
        }

        // 3. The Gaussians.
        GL20.glUseProgram(shaders.blurProgram);
        GL20.glUniform1i(shaders.blurSource, 0);
        GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadBuffer);
        GL20.glEnableVertexAttribArray(0);
        GL20.glVertexAttribPointer(0, 2, GL11.GL_FLOAT, false, 8, 0L);
        GlStateManager.disableBlend();
        GL11.glViewport(0, 0, quarterWidth, quarterHeight);
        for (int slot = 0; slot < resultCount; slot++) {
            float sigma = resultSigma[slot] / 4f;
            int taps = kernel(sigma);
            shaders.setBlurKernel(offsets, weights, taps);
            pass(shaders, resultTexture[slot], scratchFbo, 1f / quarterWidth, 0f);
            pass(shaders, scratchTexture, resultFbo[slot], 0f, 1f / quarterHeight);
            passes += 2;
        }
        GL20.glDisableVertexAttribArray(0);
        GlStateManager.enableBlend();
        bindFramebuffer(sourceFbo);
        GL11.glViewport(0, 0, frameWidth, frameHeight);
    }

    private void pass(SdfShaders shaders, int source, int target, float stepX, float stepY) {
        bindFramebuffer(target);
        GlStateManager.activeTexture(GL13.GL_TEXTURE0);
        GlStateManager.bindTexture(source);
        GL20.glUniform2f(shaders.blurStep, stepX, stepY);
        GL11.glDrawArrays(GL11.GL_TRIANGLES, 0, 3);
    }

    /**
     * Gaussian weights, folded in pairs: tap i (i >= 1) sits between texels
     * 2i-1 and 2i at the offset that makes one linear fetch weigh both.
     *
     * @return the number of taps used, the centre included
     */
    private int kernel(float sigma) {
        int radius = Math.max(1, Math.min((SdfShaders.BLUR_TAPS - 1) * 2, (int) Math.ceil(sigma * 3f)));
        float total = 0f;
        for (int i = 0; i <= radius; i++) {
            raw[i] = (float) Math.exp(-(i * i) / (2.0 * sigma * sigma));
            total += i == 0 ? raw[i] : 2f * raw[i];
        }
        for (int i = 0; i <= radius; i++) {
            raw[i] /= total;
        }
        java.util.Arrays.fill(offsets, 0f);
        java.util.Arrays.fill(weights, 0f);
        weights[0] = raw[0];
        int taps = 1;
        for (int i = 1; i <= radius; i += 2) {
            float a = raw[i];
            float b = i + 1 <= radius ? raw[i + 1] : 0f;
            float w = a + b;
            offsets[taps] = w == 0f ? i : (i * a + (i + 1) * b) / w;
            weights[taps] = w;
            taps++;
        }
        return taps;
    }

    private void blit(int from, int fromWidth, int fromHeight, int to, int toWidth, int toHeight) {
        if (core) {
            GL30.glBindFramebuffer(GL30.GL_READ_FRAMEBUFFER, from);
            GL30.glBindFramebuffer(GL30.GL_DRAW_FRAMEBUFFER, to);
            GL30.glBlitFramebuffer(0, 0, fromWidth, fromHeight, 0, 0, toWidth, toHeight,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        } else {
            ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_READ_FRAMEBUFFER, from);
            ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_DRAW_FRAMEBUFFER, to);
            ARBFramebufferObject.glBlitFramebuffer(0, 0, fromWidth, fromHeight, 0, 0, toWidth, toHeight,
                    GL11.GL_COLOR_BUFFER_BIT, GL11.GL_LINEAR);
        }
    }

    private void bindFramebuffer(int fbo) {
        if (core) {
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
        } else {
            ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, fbo);
        }
    }

    private void ensure(int frameWidth, int frameHeight) {
        if (quadBuffer == 0) {
            // One triangle covering the viewport.
            FloatBuffer quad = BufferUtils.createFloatBuffer(6);
            quad.put(new float[] {-1f, -1f, 3f, -1f, -1f, 3f}).flip();
            quadBuffer = GL15.glGenBuffers();
            GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, quadBuffer);
            GL15.glBufferData(GL15.GL_ARRAY_BUFFER, quad, GL15.GL_STATIC_DRAW);
        }
        if (frameWidth == width && frameHeight == height && fullTexture != 0) {
            return;
        }
        deleteTargets();
        width = frameWidth;
        height = frameHeight;
        quarterWidth = Math.max(1, width / 4);
        quarterHeight = Math.max(1, height / 4);
        fullTexture = texture(width, height);
        fullFbo = framebuffer(fullTexture);
        halfTexture = texture(Math.max(1, width / 2), Math.max(1, height / 2));
        halfFbo = framebuffer(halfTexture);
        scratchTexture = texture(quarterWidth, quarterHeight);
        scratchFbo = framebuffer(scratchTexture);
        for (int slot = 0; slot < SLOTS; slot++) {
            resultTexture[slot] = texture(quarterWidth, quarterHeight);
            resultFbo[slot] = framebuffer(resultTexture[slot]);
        }
    }

    private static int texture(int w, int h) {
        int id = GL11.glGenTextures();
        GlStateManager.bindTexture(id);
        GL11.glTexImage2D(GL11.GL_TEXTURE_2D, 0, GL11.GL_RGBA8, w, h, 0, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE,
                (java.nio.ByteBuffer) null);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MIN_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_MAG_FILTER, GL11.GL_LINEAR);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_S, GL12.GL_CLAMP_TO_EDGE);
        GL11.glTexParameteri(GL11.GL_TEXTURE_2D, GL11.GL_TEXTURE_WRAP_T, GL12.GL_CLAMP_TO_EDGE);
        return id;
    }

    private int framebuffer(int texture) {
        int fbo;
        if (core) {
            fbo = GL30.glGenFramebuffers();
            GL30.glBindFramebuffer(GL30.GL_FRAMEBUFFER, fbo);
            GL30.glFramebufferTexture2D(GL30.GL_FRAMEBUFFER, GL30.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D,
                    texture, 0);
        } else {
            fbo = ARBFramebufferObject.glGenFramebuffers();
            ARBFramebufferObject.glBindFramebuffer(ARBFramebufferObject.GL_FRAMEBUFFER, fbo);
            ARBFramebufferObject.glFramebufferTexture2D(ARBFramebufferObject.GL_FRAMEBUFFER,
                    ARBFramebufferObject.GL_COLOR_ATTACHMENT0, GL11.GL_TEXTURE_2D, texture, 0);
        }
        return fbo;
    }

    private void deleteTargets() {
        int[] textures = {fullTexture, halfTexture, scratchTexture, resultTexture[0], resultTexture[1],
            resultTexture[2]};
        int[] fbos = {fullFbo, halfFbo, scratchFbo, resultFbo[0], resultFbo[1], resultFbo[2]};
        for (int id : textures) {
            if (id != 0) {
                GlStateManager.deleteTexture(id);
            }
        }
        for (int id : fbos) {
            if (id != 0) {
                if (core) {
                    GL30.glDeleteFramebuffers(id);
                } else {
                    ARBFramebufferObject.glDeleteFramebuffers(id);
                }
            }
        }
        fullTexture = halfTexture = scratchTexture = 0;
        fullFbo = halfFbo = scratchFbo = 0;
        java.util.Arrays.fill(resultTexture, 0);
        java.util.Arrays.fill(resultFbo, 0);
    }

    void delete() {
        deleteTargets();
        if (quadBuffer != 0) {
            GL15.glDeleteBuffers(quadBuffer);
            quadBuffer = 0;
        }
        width = height = 0;
    }
}
