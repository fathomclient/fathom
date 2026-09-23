package net.fathom.optimizations.fabric.gfx;

import java.nio.ByteBuffer;
import java.nio.IntBuffer;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;
import org.lwjgl.opengl.GLContext;

import com.mojang.blaze3d.platform.GLX;

/**
 * The GL state a NanoVG frame borrows, and hands back exactly.
 *
 * <h2>Why the existing snapshot is not enough on its own</h2>
 *
 * <p>{@link GlStateSnapshot} restores through {@code GlStateManager}, which is
 * right for Fathom's own renderer: that renderer only ever changes state through
 * {@code GlStateManager} too, so the manager's cache and the driver never
 * disagree. NanoVG does not know {@code GlStateManager} exists. It enables and
 * disables blend, cull, depth, scissor and stencil, binds its own program,
 * textures and vertex buffer, and changes the unpack alignment, all directly. If
 * the cache then says "blend is on" while the driver has it off, every
 * {@code GlStateManager.enableBlend()} after the frame is skipped as a no-op, and
 * the symptom is translucent geometry vanishing somewhere far from here.
 *
 * <p>So the guard works in two layers:
 *
 * <ol>
 *   <li>{@code glPushAttrib(GL_ALL_ATTRIB_BITS)} and
 *       {@code glPushClientAttrib(GL_ALL_CLIENT_ATTRIB_BITS)} save the raw
 *       driver state NanoVG touches -- enables, blend functions, stencil,
 *       colour mask, scissor, viewport, the active texture unit and its
 *       bindings, and pixel store -- and pop it back byte for byte. 1.8.9's
 *       context is a compatibility context, so the attribute stack exists.
 *   <li>What the attribute stack does not cover -- the current program and the
 *       array and element buffer bindings -- is saved and put back explicitly.
 * </ol>
 *
 * <p>Then {@link GlStateSnapshot#restore} runs as usual. By that point the driver
 * is already back where the cache believes it is, so its {@code GlStateManager}
 * calls are no-ops that confirm rather than repair, and the matrices it pushed
 * come back off their stacks.
 *
 * <h2>Evidence, not assumption</h2>
 *
 * <p>{@link #fingerprint} reads the raw state NanoVG is known to change. The
 * spike screen takes one before a NanoVG frame and one after, and reports
 * whether they match -- which is the only way to know this guard does what it
 * says on a given driver.
 */
public final class NanoVgGlGuard {

    /** Canvas permits one vector frame, so one render-thread guard is sufficient. */
    private static final NanoVgGlGuard SHARED = new NanoVgGlGuard();

    private final GlStateSnapshot snapshot = new GlStateSnapshot();
    private boolean buffers;
    private int arrayBuffer;
    private int elementBuffer;
    private boolean shaders;
    private int program;
    private boolean closed = true;

    private NanoVgGlGuard() {}

    private NanoVgGlGuard take(int framebufferWidth, int framebufferHeight) {
        if (!closed) {
            throw new IllegalStateException("NanoVG GL guard is already open");
        }
        snapshot.captureInto();
        boolean attributesPushed = false;
        boolean clientAttributesPushed = false;
        try {
            ContextCapabilities capabilities = GLContext.getCapabilities();
            buffers = capabilities.OpenGL15;
            arrayBuffer = buffers ? GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING) : 0;
            elementBuffer = buffers ? GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING) : 0;
            shaders = GLX.areShadersSupported();
            program = shaders ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;

            GL11.glPushAttrib(GL11.GL_ALL_ATTRIB_BITS);
            attributesPushed = true;
            GL11.glPushClientAttrib(GL11.GL_ALL_CLIENT_ATTRIB_BITS);
            clientAttributesPushed = true;

            // NanoVG sizes its output from the frame it is given and assumes the
            // viewport is the whole framebuffer. The game's GUI pass leaves it that
            // way; this makes it true rather than likely.
            GL11.glViewport(0, 0, Math.max(1, framebufferWidth), Math.max(1, framebufferHeight));
            closed = false;
            return this;
        } catch (RuntimeException failure) {
            unwindFailedOpen(clientAttributesPushed, attributesPushed);
            throw failure;
        } catch (Error failure) {
            unwindFailedOpen(clientAttributesPushed, attributesPushed);
            throw failure;
        }
    }

    private void unwindFailedOpen(boolean clientAttributesPushed, boolean attributesPushed) {
        try {
            if (clientAttributesPushed) {
                GL11.glPopClientAttrib();
            }
            if (attributesPushed) {
                GL11.glPopAttrib();
            }
        } finally {
            snapshot.restore();
        }
    }

    /** Take the state. Pair with {@link #close}, in a {@code finally}. */
    public static NanoVgGlGuard open(int framebufferWidth, int framebufferHeight) {
        net.fathom.optimizations.fabric.diag.GlSync.captured(QUERIES);
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.NANOVG_BEGIN);
        return SHARED.take(framebufferWidth, framebufferHeight);
    }

    /**
     * Driver queries {@code take} makes beyond the snapshot's own.
     *
     * <p>Three explicit {@code glGet}s, and the snapshot it wraps counts its
     * twenty-four separately. The two {@code glPushAttrib} calls are not counted
     * here at all even though they are the most expensive thing in the method on
     * a modern driver -- they are not queries, and a counter that quietly folded
     * them in would be reporting something other than its own name.
     */
    private static final int QUERIES = 3;

    /** Put everything back. Safe to call twice. */
    public void close() {
        if (closed) {
            return;
        }
        closed = true;
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.NANOVG_END);
        try {
            if (buffers) {
                GL15.glBindBuffer(GL15.GL_ARRAY_BUFFER, arrayBuffer);
                GL15.glBindBuffer(GL15.GL_ELEMENT_ARRAY_BUFFER, elementBuffer);
            }
            if (shaders) {
                GLX.gl20UseProgram(program);
            }
            GL11.glPopClientAttrib();
            GL11.glPopAttrib();
        } finally {
            snapshot.restore();
        }
    }

    /**
     * The raw GL state NanoVG changes, as one comparable line.
     *
     * <p>Read straight from the driver, never from {@code GlStateManager}: the
     * question is whether the driver is back where the cache left it.
     */
    public static String fingerprint() {
        StringBuilder out = new StringBuilder(256);
        out.append("blend=").append(GL11.glIsEnabled(GL11.GL_BLEND))
                .append(" func=").append(GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB))
                .append('/').append(GL11.glGetInteger(GL14.GL_BLEND_DST_RGB))
                .append('/').append(GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA))
                .append('/').append(GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA))
                .append(" depth=").append(GL11.glIsEnabled(GL11.GL_DEPTH_TEST))
                .append(" depthMask=").append(GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK))
                .append(" cull=").append(GL11.glIsEnabled(GL11.GL_CULL_FACE))
                .append(" alphaTest=").append(GL11.glIsEnabled(GL11.GL_ALPHA_TEST))
                .append(" scissor=").append(GL11.glIsEnabled(GL11.GL_SCISSOR_TEST))
                .append(" stencil=").append(GL11.glIsEnabled(GL11.GL_STENCIL_TEST))
                .append(" colourMask=").append(colourMask())
                .append(" activeTexture=").append(GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE))
                .append(" texture2d=").append(GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D))
                .append(" texturing=").append(GL11.glIsEnabled(GL11.GL_TEXTURE_2D))
                .append(" unpackAlign=").append(GL11.glGetInteger(GL11.GL_UNPACK_ALIGNMENT))
                .append(" viewport=").append(viewport())
                .append(" matrixMode=").append(GL11.glGetInteger(GL11.GL_MATRIX_MODE));
        if (GLX.areShadersSupported()) {
            out.append(" program=").append(GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM));
        }
        if (GLContext.getCapabilities().OpenGL15) {
            out.append(" arrayBuffer=").append(GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING));
        }
        return out.toString();
    }

    private static String colourMask() {
        // LWJGL 2 validates the destination against glGet's largest possible
        // result (16), even for a four-value query.
        ByteBuffer mask = BufferUtils.createByteBuffer(16);
        GL11.glGetBoolean(GL11.GL_COLOR_WRITEMASK, mask);
        return "" + mask.get(0) + mask.get(1) + mask.get(2) + mask.get(3);
    }

    private static String viewport() {
        IntBuffer box = BufferUtils.createIntBuffer(16);
        GL11.glGetInteger(GL11.GL_VIEWPORT, box);
        return box.get(0) + "," + box.get(1) + "," + box.get(2) + "," + box.get(3);
    }
}
