package net.fathom.optimizations.fabric.gfx;

import java.nio.FloatBuffer;
import java.nio.IntBuffer;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import org.lwjgl.BufferUtils;
import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;
import org.lwjgl.opengl.GL14;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GL20;

import com.mojang.blaze3d.platform.GLX;

/**
 * Sparse, attributed GL-state comparison at Phase 1 optimization boundaries.
 *
 * <p>A paired epoch requests one sample during discarded stabilization. The
 * request rotates through the nametag, HUD-cache, entity-limit and tile-limit
 * hooks. Ordinary invocations execute one boolean read and no GL query. A
 * claimed sample records three points: immediately before Fathom, immediately
 * after Fathom, and after the surrounding vanilla/OptiFine method returns.
 *
 * <p>A difference is attributed to Fathom only when the feature was active,
 * the invocation actually issued a GL mutation, and the state differs at the
 * second point. A later-only change is retained in the diagnostic record but
 * is not counted against Fathom.
 */
public final class GlStateAudit {
    public static final String NAMETAG = "EntityRenderer.renderLabelIfPresent/nametag";
    public static final String HUD_CACHE = "InGameHud.render/HudLayerCache";
    public static final String HUD_STEADY = "InGameHud.render/HudSteadyCache";
    public static final String ENTITY_LIMIT = "EntityRenderDispatcher.shouldRender/entity-limit";
    public static final String TILE_LIMIT = "BlockEntityRenderDispatcher.renderEntity/tile-limit";

    private static final String[] TARGETS = { NAMETAG, HUD_CACHE, HUD_STEADY, ENTITY_LIMIT, TILE_LIMIT };
    private static final int FRAMEBUFFER_BINDING = 0x8CA6;
    private static final IntBuffer INT_QUERY = BufferUtils.createIntBuffer(16);
    private static final FloatBuffer FLOAT_QUERY = BufferUtils.createFloatBuffer(16);
    private static final List<String> RECORDS = new ArrayList<String>();

    private static boolean requested;
    private static String requestedHook;
    private static int nextTarget;
    private static Scope active;
    private static int samples;
    private static int attributableMismatches;
    private static int laterOnlyChanges;
    private static int successfulControls;
    private static String lastDifference = "none";

    private GlStateAudit() {}

    public static void requestSample() {
        // Do not discard an unclaimed request merely because an A arm did not
        // execute (for example) the HUD cache. It will be claimed by the next
        // invocation of that exact hook, normally the adjacent B arm.
        if (requested || active != null) return;
        requestedHook = TARGETS[nextTarget++ % TARGETS.length];
        requested = true;
    }

    /** Claim the pending diagnostic sample at the exact Fathom hook boundary. */
    public static Scope beginRequested(String hook, boolean featureActive) {
        if (!requested || active != null || !hook.equals(requestedHook)) return null;
        requested = false;
        Scope scope = new Scope(hook, featureActive);
        scope.before.capture();
        active = scope;
        return scope;
    }

    /** Record the state at the instant the Fathom code returns. */
    public static void afterFathom(Scope scope, boolean mutated, String path) {
        if (scope == null || active != scope || scope.afterCaptured) return;
        scope.mutated = mutated;
        scope.path = path == null ? "unknown" : path;
        scope.afterFathom.capture();
        scope.afterCaptured = true;
    }

    /** Complete a sample after its surrounding vanilla/OptiFine method returns. */
    public static void finish(String hook) {
        Scope scope = active;
        if (scope == null || !scope.hook.equals(hook) || !scope.afterCaptured) return;
        scope.afterSurrounding.capture();
        active = null;
        samples++;

        String immediate = scope.before.changes(scope.afterFathom);
        String surrounding = scope.before.changes(scope.afterSurrounding);
        boolean attributable = scope.featureActive && scope.mutated && !"none".equals(immediate);
        boolean laterOnly = "none".equals(immediate) && !"none".equals(surrounding);
        if (attributable) {
            attributableMismatches++;
            lastDifference = immediate;
        }
        if (laterOnly) laterOnlyChanges++;

        // Keep every failure and only a small set of successful controls.
        if (attributable || laterOnly || successfulControls < 4) {
            if (!attributable && !laterOnly) successfulControls++;
            if (RECORDS.size() < 24) {
                RECORDS.add(scope.record(immediate, surrounding, attributable, laterOnly));
            }
        }
    }

    public static int samples() { return samples; }
    public static int leaks() { return attributableMismatches; }
    public static int laterOnlyChanges() { return laterOnlyChanges; }
    public static String lastDifference() { return lastDifference; }
    public static List<String> records() { return Collections.unmodifiableList(RECORDS); }

    public static void reset() {
        requested = false;
        requestedHook = null;
        nextTarget = 0;
        active = null;
        samples = 0;
        attributableMismatches = 0;
        laterOnlyChanges = 0;
        successfulControls = 0;
        lastDifference = "none";
        RECORDS.clear();
    }

    /** One diagnostic invocation; allocated only for a requested sample. */
    public static final class Scope {
        private final String hook;
        private final boolean featureActive;
        private final Snapshot before = new Snapshot();
        private final Snapshot afterFathom = new Snapshot();
        private final Snapshot afterSurrounding = new Snapshot();
        private boolean mutated;
        private boolean afterCaptured;
        private String path = "unknown";

        private Scope(String hook, boolean featureActive) {
            this.hook = hook;
            this.featureActive = featureActive;
        }

        private String record(String immediate, String surrounding, boolean attributable,
                boolean laterOnly) {
            return "hook=" + hook
                    + "; path=" + path
                    + "; feature-active=" + featureActive
                    + "; fathom-gl-mutation=" + mutated
                    + "; before={" + before.summary() + "}"
                    + "; after-fathom={" + afterFathom.summary() + "}"
                    + "; after-surrounding={" + afterSurrounding.summary() + "}"
                    + "; immediate-change=" + immediate
                    + "; surrounding-change=" + surrounding
                    + "; attribution=" + (attributable ? "FATHOM" : laterOnly ? "LATER" : "NONE");
        }
    }

    private static final class Snapshot {
        int framebuffer;
        final int[] viewport = new int[4];
        final int[] scissorBox = new int[4];
        boolean scissor;
        boolean blend;
        boolean depth;
        boolean depthMask;
        boolean alpha;
        boolean texture;
        boolean lighting;
        boolean cull;
        int blendSrcRgb;
        int blendDstRgb;
        int blendSrcAlpha;
        int blendDstAlpha;
        int depthFunc;
        int alphaFunc;
        int alphaRefBits;
        int activeTexture;
        int texture2d;
        final int[] colourBits = new int[4];
        int program;
        int arrayBuffer;
        int elementBuffer;

        void capture() {
            framebuffer = GLX.supportsFbo() ? GL11.glGetInteger(FRAMEBUFFER_BINDING) : 0;
            readInts(GL11.GL_VIEWPORT, viewport);
            readInts(GL11.GL_SCISSOR_BOX, scissorBox);
            scissor = GL11.glIsEnabled(GL11.GL_SCISSOR_TEST);
            blend = GL11.glIsEnabled(GL11.GL_BLEND);
            depth = GL11.glIsEnabled(GL11.GL_DEPTH_TEST);
            depthMask = GL11.glGetBoolean(GL11.GL_DEPTH_WRITEMASK);
            alpha = GL11.glIsEnabled(GL11.GL_ALPHA_TEST);
            texture = GL11.glIsEnabled(GL11.GL_TEXTURE_2D);
            lighting = GL11.glIsEnabled(GL11.GL_LIGHTING);
            cull = GL11.glIsEnabled(GL11.GL_CULL_FACE);
            blendSrcRgb = GL11.glGetInteger(GL14.GL_BLEND_SRC_RGB);
            blendDstRgb = GL11.glGetInteger(GL14.GL_BLEND_DST_RGB);
            blendSrcAlpha = GL11.glGetInteger(GL14.GL_BLEND_SRC_ALPHA);
            blendDstAlpha = GL11.glGetInteger(GL14.GL_BLEND_DST_ALPHA);
            depthFunc = GL11.glGetInteger(GL11.GL_DEPTH_FUNC);
            alphaFunc = GL11.glGetInteger(GL11.GL_ALPHA_TEST_FUNC);
            alphaRefBits = Float.floatToIntBits(GL11.glGetFloat(GL11.GL_ALPHA_TEST_REF));
            activeTexture = GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE);
            texture2d = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            FLOAT_QUERY.clear();
            GL11.glGetFloat(GL11.GL_CURRENT_COLOR, FLOAT_QUERY);
            for (int i = 0; i < colourBits.length; i++) {
                colourBits[i] = Float.floatToIntBits(FLOAT_QUERY.get(i));
            }
            program = GLX.areShadersSupported() ? GL11.glGetInteger(GL20.GL_CURRENT_PROGRAM) : 0;
            arrayBuffer = GL11.glGetInteger(GL15.GL_ARRAY_BUFFER_BINDING);
            elementBuffer = GL11.glGetInteger(GL15.GL_ELEMENT_ARRAY_BUFFER_BINDING);
        }

        String changes(Snapshot other) {
            StringBuilder out = new StringBuilder(240);
            change(out, framebuffer != other.framebuffer, "framebuffer", framebuffer, other.framebuffer);
            change(out, !same(viewport, other.viewport), "viewport", values(viewport), values(other.viewport));
            change(out, scissor != other.scissor || !same(scissorBox, other.scissorBox),
                    "scissor", scissor + ":" + values(scissorBox), other.scissor + ":" + values(other.scissorBox));
            change(out, blend != other.blend, "blend-enable", blend, other.blend);
            change(out, blendSrcRgb != other.blendSrcRgb || blendDstRgb != other.blendDstRgb
                    || blendSrcAlpha != other.blendSrcAlpha || blendDstAlpha != other.blendDstAlpha,
                    "blend-function", blendFunction(), other.blendFunction());
            change(out, depth != other.depth, "depth-enable", depth, other.depth);
            change(out, depthMask != other.depthMask, "depth-mask", depthMask, other.depthMask);
            change(out, depthFunc != other.depthFunc, "depth-function", depthFunc, other.depthFunc);
            change(out, alpha != other.alpha, "alpha-enable", alpha, other.alpha);
            change(out, alphaFunc != other.alphaFunc || alphaRefBits != other.alphaRefBits,
                    "alpha-function", alphaFunction(), other.alphaFunction());
            change(out, texture != other.texture, "texture-enable", texture, other.texture);
            change(out, lighting != other.lighting, "lighting-enable", lighting, other.lighting);
            change(out, cull != other.cull, "cull-enable", cull, other.cull);
            change(out, activeTexture != other.activeTexture, "active-texture", activeTexture, other.activeTexture);
            change(out, texture2d != other.texture2d, "texture-binding", texture2d, other.texture2d);
            change(out, !same(colourBits, other.colourBits), "colour", colour(), other.colour());
            change(out, program != other.program, "shader-program", program, other.program);
            change(out, arrayBuffer != other.arrayBuffer, "array-buffer", arrayBuffer, other.arrayBuffer);
            change(out, elementBuffer != other.elementBuffer, "element-buffer", elementBuffer, other.elementBuffer);
            return out.length() == 0 ? "none" : out.toString();
        }

        String summary() {
            return "blend=" + blend + "/" + blendFunction()
                    + ", alpha=" + alpha + "/" + alphaFunction()
                    + ", depth=" + depth + "/mask=" + depthMask + "/func=" + depthFunc
                    + ", texture=" + texture + "/unit=" + activeTexture + "/binding=" + texture2d
                    + ", colour=" + colour()
                    + ", lighting=" + lighting + ", cull=" + cull
                    + ", scissor=" + scissor + ", framebuffer=" + framebuffer;
        }

        private String blendFunction() {
            return blendSrcRgb + "/" + blendDstRgb + "/" + blendSrcAlpha + "/" + blendDstAlpha;
        }

        private String alphaFunction() {
            return alphaFunc + "/" + Float.intBitsToFloat(alphaRefBits);
        }

        private String colour() {
            return Float.intBitsToFloat(colourBits[0]) + "/" + Float.intBitsToFloat(colourBits[1])
                    + "/" + Float.intBitsToFloat(colourBits[2]) + "/" + Float.intBitsToFloat(colourBits[3]);
        }
    }

    private static void change(StringBuilder out, boolean changed, String label, Object before, Object after) {
        if (!changed) return;
        if (out.length() > 0) out.append(", ");
        out.append(label).append('=').append(before).append("->").append(after);
    }

    private static boolean same(int[] a, int[] b) {
        for (int i = 0; i < a.length; i++) if (a[i] != b[i]) return false;
        return true;
    }

    private static String values(int[] values) {
        StringBuilder out = new StringBuilder();
        for (int i = 0; i < values.length; i++) {
            if (i > 0) out.append('/');
            out.append(values[i]);
        }
        return out.toString();
    }

    private static void readInts(int name, int[] values) {
        INT_QUERY.clear();
        GL11.glGetInteger(name, INT_QUERY);
        for (int i = 0; i < values.length; i++) values[i] = INT_QUERY.get(i);
    }
}
