package net.fathom.optimizations.fabric.gfx;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL20;

import com.mojang.blaze3d.platform.GLX;
import com.mojang.blaze3d.platform.GlStateManager;

/** The GL state a Fathom screen borrows and must return unchanged. */
final class GlStateSnapshot {

    /** GL_FRAMEBUFFER_BINDING; shared by the core, ARB and EXT variants. */
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;

    /** Reused query storage. Every capture runs on Minecraft's render thread. */
    private static final IntBuffer INT_QUERY = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer FLOAT_QUERY = BufferUtils.createFloatBuffer(16);

    private boolean framebufferSupported;
    private int framebuffer;
    private final int[] viewport = new int[4];
    private final int[] scissorBox = new int[4];
    private boolean scissor;
    private boolean blend;
    private boolean depth;
    private boolean depthMask;
    private boolean alpha;
    private boolean cull;
    private boolean activeTextureEnabled;
    private boolean uiTextureEnabled;
    private int blendSrcRgb;
    private int blendDstRgb;
    private int blendSrcAlpha;
    private int blendDstAlpha;
    private int depthFunc;
    private int alphaFunc;
    private float alphaRef;
    private int cullFace;
    private int activeTexture;
    private int activeTexture2d;
    private int uiTexture2d;
    private boolean shadersSupported;
    private int program;
    private int matrixMode;
    private final float[] colour = new float[4];
    private boolean restored;
    private boolean captured;

    static GlStateSnapshot capture() {
        return new GlStateSnapshot().captureInto();
    }

    GlStateSnapshot() {}

    /** Capture into this owner again, without allocating a snapshot or query buffers. */
    GlStateSnapshot captureInto() {
        if (captured && !restored) {
            throw new IllegalStateException("GL state snapshot is already live");
        }
        restored = false;
        captured = true;
        framebufferSupported = GLX.supportsFbo();
        framebuffer = framebufferSupported ? GL11.glGetInteger(FRAMEBUFFER_BINDING) : 0;
        readInts(GL11.GL_VIEWPORT, viewport);
        readInts(GL11.GL_SCISSOR_BOX, scissorBox);
        scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
        blend = GL11.glIsEnabled(GL11.GL_BLEND);
        depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
        depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
        alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
        cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
        blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
        blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
        blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
        blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
        depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
        alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
        alphaRef = GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF);
        cullFace = GL11.glGetInteger(GL11.GL_CULL_FACE_MODE);
        activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
        activeTextureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
        activeTexture2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
        if (activeTexture == GLX.textureUnit) {
            uiTextureEnabled = activeTextureEnabled;
            uiTexture2d = activeTexture2d;
        } else {
            // Fathom draws on texture unit zero even when a caller left a
            // different unit active. Save both units so neither binding leaks.
            GlStateManager.activeTexture(GLX.textureUnit);
            uiTextureEnabled = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            uiTexture2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            GlStateManager.activeTexture(activeTexture);
        }
        shadersSupported = GLX.areShadersSupported();
        program = shadersSupported ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
        matrixMode = GL11.glGetInteger(GL11.GL_MATRIX_MODE);
        readFloats(GL11.GL_CURRENT_COLOR, colour);

        // Snapshot the matrices themselves, not merely the selected stack.
        // Fathom currently leaves them alone; these guards keep a later backend
        // change from turning a close animation into a stale projection.
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(GL11.GL_TEXTURE);
        GlStateManager.pushMatrix();
        GlStateManager.matrixMode(matrixMode);
        return this;
    }

    void restore() {
        if (restored || !captured) {
            return;
        }
        restored = true;

        if (framebufferSupported) {
            GLX.advancedBindFramebuffer(GLX.framebuffer, framebuffer);
        }

        GlStateManager.matrixMode(GL11.GL_TEXTURE);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_MODELVIEW);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(GL11.GL_PROJECTION);
        GlStateManager.popMatrix();
        GlStateManager.matrixMode(matrixMode);

        GlStateManager.viewport(viewport[0], viewport[1], viewport[2], viewport[3]);
        if (scissor) {
            GL11.glEnable(GL11.GL_SCISSOR_TEST);
        } else {
            GL11.glDisable(GL11.GL_SCISSOR_TEST);
        }
        GL11.glScissor(scissorBox[0], scissorBox[1], scissorBox[2], scissorBox[3]);

        if (shadersSupported) {
            GLX.gl20UseProgram(program);
        }
        GlStateManager.activeTexture(GLX.textureUnit);
        GlStateManager.bindTexture(uiTexture2d);
        setTexture(uiTextureEnabled);
        if (activeTexture != GLX.textureUnit) {
            GlStateManager.activeTexture(activeTexture);
            GlStateManager.bindTexture(activeTexture2d);
            setTexture(activeTextureEnabled);
        }
        setBlend(blend);
        GlStateManager.blendFuncSeparate(blendSrcRgb, blendDstRgb, blendSrcAlpha, blendDstAlpha);
        setDepth(depth);
        GlStateManager.depthMask(depthMask);
        GlStateManager.depthFunc(depthFunc);
        setAlpha(alpha);
        GlStateManager.alphaFunc(alphaFunc, alphaRef);
        setCull(cull);
        GlStateManager.cullFace(cullFace);
        GlStateManager.color(colour[0], colour[1], colour[2], colour[3]);
    }

    private static void setTexture(boolean enabled) {
        if (enabled) GlStateManager.enableTexture(); else GlStateManager.disableTexture();
    }

    private static void setBlend(boolean enabled) {
        if (enabled) GlStateManager.enableBlend(); else GlStateManager.disableBlend();
    }

    private static void setDepth(boolean enabled) {
        if (enabled) GlStateManager.enableDepthTest(); else GlStateManager.disableDepthTest();
    }

    private static void setAlpha(boolean enabled) {
        if (enabled) GlStateManager.enableAlphaTest(); else GlStateManager.disableAlphaTest();
    }

    private static void setCull(boolean enabled) {
        if (enabled) GlStateManager.enableCull(); else GlStateManager.disableCull();
    }

    private static void readInts(int name, int[] values) {
        // LWJGL 2's glGet wrapper validates the destination against OpenGL's
        // maximum possible return width (16), even for four-value queries such
        // as GL_VIEWPORT and GL_SCISSOR_BOX.
        IntBuffer buffer = INT_QUERY;
        buffer.clear();
        GL11.glGetInteger(name, buffer);
        for (int i = 0; i < values.length; i++) values[i] = buffer.get(i);
    }

    private static void readFloats(int name, float[] values) {
        FloatBuffer buffer = FLOAT_QUERY;
        buffer.clear();
        GL11.glGetFloat(name, buffer);
        for (int i = 0; i < values.length; i++) values[i] = buffer.get(i);
    }
}
