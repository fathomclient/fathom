package net.fathom.optimizations.fabric.perf;

import org.lwjgl.opengl.GL11;
import org.lwjgl.opengl.GL13;

/**
 * Replays a vanilla HUD element -- chat, the scoreboard sidebar, the tab list --
 * while everything it is drawn from is unchanged: {@code render.hud.vanilla-replay}.
 *
 * <h2>Why</h2>
 *
 * <p>The multiplayer profile (docs/performance/multiplayer-2026-10.md) found the
 * vanilla HUD, not packets, to be the multiplayer HUD cost: chat 0.06-0.13 ms a
 * frame whenever lines are on screen, the sidebar 0.05-0.09 ms, the tab list
 * 0.35 ms while held. Each redraws every glyph in immediate mode every frame,
 * though its content changes at most once a client tick or when a packet
 * changes it -- thirty-odd identical frames in a row at 700 fps.
 *
 * <h2>How it stays exact</h2>
 *
 * <p>The caller computes a signature from every input the element's drawing
 * reads (its Mixin lists them). The same rules as {@link HudSteadyCache}:
 *
 * <ul>
 *   <li>a frame whose signature changed is drawn live, that frame;
 *   <li>a signature seen twice in a row is drawn live <em>and</em> recorded into
 *       a display list as it is drawn;
 *   <li>while the signature holds, the list is replayed.
 * </ul>
 *
 * <p>GL state: before recording, the state is read back once and forced, so
 * {@code GlStateManager}'s cache and GL agree and no call the element makes is
 * skipped as redundant; the state the element leaves behind is read back after
 * recording. A replay forces the start state, calls the list, and forces the end
 * state, so the code after the element sees exactly what a live draw would have
 * left -- in GL and in {@code GlStateManager}'s cache. The read-backs happen on
 * recording frames only, at most once a client tick.
 *
 * <p>The caller may also veto a recording (obfuscated text, which changes every
 * frame by design). A font reload releases every list, since lists name the glyph
 * textures.
 */
public final class VanillaHudReplay {

    public static final VanillaHudReplay CHAT = new VanillaHudReplay("chat");
    public static final VanillaHudReplay SIDEBAR = new VanillaHudReplay("sidebar");
    public static final VanillaHudReplay TAB_LIST = new VanillaHudReplay("tab list");

    private static final VanillaHudReplay[] ALL = {CHAT, SIDEBAR, TAB_LIST};

    /** Bumped on every scoreboard change; see {@code ScoreboardRevisionMixin}. Client thread. */
    public static int scoreboardRevision;

    /**
     * {@code -Dfathom.verify.caches=true}: every sixteenth replay is checked. The
     * element is drawn both ways into an offscreen framebuffer -- replayed, then
     * live -- and the two images are compared byte for byte. The on-screen element
     * is skipped on those frames; diagnostic runs only.
     */
    private static final boolean VERIFY = NametagNames.VERIFY;
    private static net.minecraft.client.gl.Framebuffer verifyTarget;
    private static java.nio.ByteBuffer verifyReplayed;
    private static java.nio.ByteBuffer verifyLive;
    public long verified;
    public long mismatches;
    private boolean verifying;

    /** Counters for the benchmark: frames replayed, recorded, drawn live. */
    public long replays;
    public long records;
    public long live;

    private final String name;
    private int list;
    private long recordedSignature;
    private long lastSignature;
    private boolean hasLast;
    private boolean recording;
    private boolean vetoed;
    private final HudLayerCache.GlState start = new HudLayerCache.GlState();
    private final HudLayerCache.GlState end = new HudLayerCache.GlState();

    private VanillaHudReplay(String name) {
        this.name = name;
    }

    public String name() {
        return name;
    }

    /**
     * Start drawing the element.
     *
     * @param recordable false when the element must be drawn live whatever the
     *     signature (obfuscated text on screen)
     * @return true when the caller must draw it now (live or recording); false
     *     when a replay already drew it and the caller must skip its own drawing
     */
    public boolean begin(long signature, boolean recordable) {
        long previous = lastSignature;
        boolean seen = hasLast && signature == previous;
        lastSignature = signature;
        hasLast = true;
        if (list != 0 && !vetoed && signature == recordedSignature) {
            if (VERIFY && replays % 16 == 15) {
                replays++;
                return beginVerify();
            }
            start.force();
            GL11.glCallList(list);
            end.force();
            replays++;
            return false;
        }
        if (!seen || !recordable) {
            live++;
            return true;
        }
        if (GL11.glGetInteger(GL13.GL_ACTIVE_TEXTURE) != GL13.GL_TEXTURE0) {
            live++;
            return true;
        }
        if (list == 0) {
            list = GL11.glGenLists(1);
            if (list == 0) {
                live++;
                return true;
            }
        }
        start.capture();
        start.force();
        GL11.glNewList(list, GL11.GL_COMPILE_AND_EXECUTE);
        recording = true;
        vetoed = false;
        recordedSignature = signature;
        return true;
    }

    /** Draw the replay into the check target, then let the caller draw live into it too. */
    private boolean beginVerify() {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        int width = client.width;
        int height = client.height;
        if (verifyTarget == null || verifyTarget.textureWidth != width || verifyTarget.textureHeight != height) {
            if (verifyTarget != null) {
                verifyTarget.delete();
            }
            verifyTarget = new net.minecraft.client.gl.Framebuffer(width, height, true);
            verifyReplayed = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
            verifyLive = org.lwjgl.BufferUtils.createByteBuffer(width * height * 4);
        }
        verifyTarget.setClearColor(0f, 0f, 0f, 0f);
        verifyTarget.clear();
        verifyTarget.bind(true);
        start.force();
        GL11.glCallList(list);
        end.force();
        read(verifyReplayed, width, height);
        verifyTarget.clear();
        verifyTarget.bind(true);
        start.force();
        verifying = true;
        return true;
    }

    private static void read(java.nio.ByteBuffer into, int width, int height) {
        into.clear();
        GL11.glPixelStorei(GL11.GL_PACK_ALIGNMENT, 1);
        GL11.glReadPixels(0, 0, width, height, GL11.GL_RGBA, GL11.GL_UNSIGNED_BYTE, into);
        into.rewind();
    }

    /** Finish a recording {@link #begin} started; nothing otherwise. */
    public void end() {
        if (verifying) {
            verifying = false;
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            read(verifyLive, client.width, client.height);
            verified++;
            if (!verifyLive.equals(verifyReplayed)) {
                mismatches++;
            }
            client.getFramebuffer().bind(true);
            return;
        }
        if (!recording) {
            return;
        }
        recording = false;
        GL11.glEndList();
        end.capture();
        records++;
    }

    /** Whether a recording is in progress: the element is being drawn into a list. */
    public boolean recording() {
        return recording;
    }

    /** Never replay the current recording (decided while drawing it). */
    public void veto() {
        vetoed = true;
    }

    public void release() {
        if (recording) {
            end();
        }
        if (list != 0) {
            GL11.glDeleteLists(list, 1);
            list = 0;
        }
        hasLast = false;
        recordedSignature = 0L;
    }

    /** Font reload, feature switched off. */
    public static void releaseAll() {
        for (VanillaHudReplay replay : ALL) {
            replay.release();
        }
    }

    /** One line per element for a benchmark report. */
    public static String summary() {
        StringBuilder out = new StringBuilder();
        for (VanillaHudReplay replay : ALL) {
            out.append(String.format(java.util.Locale.ROOT,
                    "%s: %d replayed, %d recorded, %d live, %d checked, %d mismatched; ", replay.name,
                    replay.replays, replay.records, replay.live, replay.verified, replay.mismatches));
        }
        return out.toString();
    }

    /** Mix one more input into a signature. */
    public static long mix(long signature, long value) {
        long mixed = (signature ^ value) * 0x9E3779B97F4A7C15L;
        return mixed ^ (mixed >>> 29);
    }
}
