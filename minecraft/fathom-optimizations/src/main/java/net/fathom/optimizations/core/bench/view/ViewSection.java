package net.fathom.optimizations.core.bench.view;

/**
 * Every boundary the view profiler times, and who owns the code inside it.
 *
 * <h2>Why a fixed catalogue</h2>
 *
 * <p>The profiler accumulates into arrays indexed by ordinal, so the hot path
 * is an array store and nothing else -- no map, no string, no allocation. The
 * price is that a section has to be declared here before a hook can time it,
 * which is also the point: a report can only name what was declared, so it
 * cannot quietly grow a column nobody can trace back to a method.
 *
 * <h2>Nesting</h2>
 *
 * <p>{@link #parent} is where a section is <em>expected</em> to sit, for laying
 * out the report. Self time is not derived from it: {@link ViewProbe} records
 * the section that was actually open when each one began, so a method OptiFine
 * moved somewhere else is still subtracted from the right parent.
 */
public enum ViewSection {
    /** The whole pass through {@code runGameLoop}, head to return. */
    LOOP("game loop", null, Owner.VANILLA),
    /** Packets and other work queued for the client thread ({@code Util.executeTask}). */
    TASKS("scheduled tasks (network packets)", LOOP, Owner.VANILLA),
    /** {@code MinecraftClient.tick}: the 20 Hz client simulation. */
    TICK("client tick (simulation)", LOOP, Owner.VANILLA),
    TICK_ENTITIES("tick: entities + block entities", TICK, Owner.VANILLA),
    TICK_WORLD("tick: world (chunk unload, weather)", TICK, Owner.VANILLA),
    TICK_DISPLAY("tick: random block display ticks", TICK, Owner.VANILLA),
    TICK_PARTICLES("tick: particles", TICK, Owner.VANILLA),
    TICK_NETWORK("tick: connection", TICK, Owner.VANILLA),
    TICK_TEXTURES("tick: animated textures", TICK, Owner.VANILLA_OPTIFINE),
    TICK_RENDERERS("tick: game + world renderer", TICK, Owner.VANILLA),
    /** {@code GameRenderer.render}: everything drawn this frame. */
    RENDER("render", LOOP, Owner.VANILLA),
    /** {@code GameRenderer.renderWorld(FJ)}. Its self time is camera, fog, clear and GL state. */
    WORLD("world", RENDER, Owner.VANILLA_OPTIFINE),
    /** {@code Camera.update}: reads the modelview, projection and viewport back from GL. */
    CAMERA("camera info (glGet x3 + unproject)", WORLD, Owner.VANILLA),
    /** {@code Frustum.getInstance}: reads both matrices back from GL to build the frustum. */
    FRUSTUM("frustum setup (glGet x2)", WORLD, Owner.VANILLA),
    SKY("sky", WORLD, Owner.VANILLA_OPTIFINE),
    CLOUDS("clouds", WORLD, Owner.VANILLA_OPTIFINE),
    /** Visibility search: which chunk sections are drawn this frame. */
    SETUP_TERRAIN("terrain: visibility (setupTerrain)", WORLD, Owner.VANILLA_OPTIFINE),
    /** Main-thread compile of a section, when one is rebuilt synchronously. */
    CHUNK_COMPILE("terrain: synchronous chunk compile", WORLD, Owner.VANILLA_OPTIFINE),
    /** {@code updateChunks}: scheduling rebuilds and uploading finished ones. */
    CHUNK_UPDATES("terrain: chunk rebuild scheduling + upload", WORLD, Owner.VANILLA_OPTIFINE),
    CHUNK_UPLOAD("terrain: buffer upload", CHUNK_UPDATES, Owner.VANILLA_OPTIFINE),
    LAYER_SOLID("terrain: solid layer", WORLD, Owner.VANILLA_OPTIFINE),
    LAYER_CUTOUT_MIPPED("terrain: cutout-mipped layer", WORLD, Owner.VANILLA_OPTIFINE),
    LAYER_CUTOUT("terrain: cutout layer", WORLD, Owner.VANILLA_OPTIFINE),
    LAYER_TRANSLUCENT("terrain: translucent layer", WORLD, Owner.VANILLA_OPTIFINE),
    /** {@code renderEntities}: entities and block entities. Its self time is culling and setup. */
    ENTITIES("entities + block entities", WORLD, Owner.VANILLA_OPTIFINE),
    ENTITY("entity draws", ENTITIES, Owner.VANILLA_OPTIFINE),
    NAMETAG("nametags", ENTITY, Owner.VANILLA_FATHOM),
    BLOCK_ENTITY("block entity draws", ENTITIES, Owner.VANILLA_OPTIFINE),
    SELECTION("selection box", WORLD, Owner.VANILLA),
    BLOCK_DAMAGE("block damage overlay", WORLD, Owner.VANILLA),
    PARTICLES("particles", WORLD, Owner.VANILLA_OPTIFINE),
    WEATHER("weather", WORLD, Owner.VANILLA_OPTIFINE),
    WORLD_BORDER("world border", WORLD, Owner.VANILLA),
    HAND("hand / held item", WORLD, Owner.VANILLA_OPTIFINE),
    /**
     * Vanilla's string drawing, wherever it is called from: the HUD, chat,
     * nametags, Fathom's Minecraft-font widgets. Overlaps the sections it is
     * called inside, so it is reported for its own sake, not added to them.
     */
    TEXT("text drawing (TextRenderer.draw, all callers)", null, Owner.VANILLA_OPTIFINE),
    /** {@code InGameHud.render}, including Fathom's HUD drawn at its end. */
    HUD("HUD (vanilla + Fathom)", RENDER, Owner.VANILLA),
    FATHOM_HUD("Fathom HUD", HUD, Owner.FATHOM),
    /** Deciding which widgets draw this frame: module checks, visibility, click sampling. */
    FATHOM_HUD_DECIDE("Fathom HUD: deciding what to draw", FATHOM_HUD, Owner.FATHOM),
    /** The cached layer: recorded when stale, replayed otherwise. */
    FATHOM_HUD_LAYER("Fathom HUD: cached layer", FATHOM_HUD, Owner.FATHOM),
    /** Widgets drawn from Java every frame with the Minecraft font. */
    FATHOM_HUD_LIVE("Fathom HUD: live widgets", FATHOM_HUD, Owner.FATHOM),
    /** Widgets drawn with Fathom's own renderer, which opens a GL frame. */
    FATHOM_HUD_OWN("Fathom HUD: Fathom-renderer widgets", FATHOM_HUD, Owner.FATHOM),
    /** Fathom reading GL state back from the driver before it draws. */
    FATHOM_GL_STATE("Fathom GL state readback (glGet)", FATHOM_HUD, Owner.FATHOM),
    /** The world framebuffer drawn to the window. */
    BLIT("framebuffer blit to window", LOOP, Owner.VANILLA),
    /** {@code glGetError}, which vanilla calls twice a frame. */
    GL_ERROR("glGetError checks", LOOP, Owner.VANILLA),
    /** Drain arm only: {@code glFinish} before the swap. */
    DRAIN("glFinish drain (drain arm only)", LOOP, Owner.DRIVER),
    /** Vanilla's {@code Thread.yield()} after the swap. */
    YIELD("Thread.yield after the swap", LOOP, Owner.VANILLA),
    /** Fathom's frame-cap hook on {@code getMaxFramerate}. */
    FATHOM_FRAME_CAP("Fathom frame-cap hook", LOOP, Owner.FATHOM),
    /** {@code Display.update}: the buffer swap, where the driver makes the CPU wait. */
    SWAP("buffer swap (Display.update)", LOOP, Owner.DRIVER),
    /** Fathom's per-frame work at the head of the loop: input, session, menu checks. */
    FATHOM_FRAME("Fathom frame hooks", LOOP, Owner.FATHOM),
    /** After the loop returns: Fathom's frame bookkeeping, then the harness. */
    POST_FRAME("between frames (Fathom post-frame)", null, Owner.FATHOM),
    /** The profiler driving the camera and filing samples. Never counted against anything. */
    HARNESS("benchmark harness", POST_FRAME, Owner.HARNESS);

    /** Who wrote the code a section times. Decides which column of the report it lands in. */
    public enum Owner {
        VANILLA("vanilla"),
        /** A vanilla method OptiFine patches when it is installed. */
        VANILLA_OPTIFINE("vanilla, OptiFine-patched"),
        /** A vanilla method Fathom can replace (fast nametags). */
        VANILLA_FATHOM("vanilla or Fathom path"),
        FATHOM("Fathom"),
        /** Not code: the driver making the CPU wait. */
        DRIVER("driver / GPU wait"),
        HARNESS("harness");

        public final String label;

        Owner(String label) {
            this.label = label;
        }
    }

    public static final ViewSection[] ALL = values();
    public static final int COUNT = ALL.length;

    public final String label;
    public final ViewSection parent;
    public final Owner owner;

    ViewSection(String label, ViewSection parent, Owner owner) {
        this.label = label;
        this.parent = parent;
        this.owner = owner;
    }

    /** How deep this sits in the expected tree, for indenting the report. */
    public int depth() {
        int depth = 0;
        for (ViewSection at = parent; at != null; at = at.parent) {
            depth++;
        }
        return depth;
    }

    /** The four terrain layers, in draw order. */
    public static boolean isLayer(ViewSection section) {
        return section == LAYER_SOLID || section == LAYER_CUTOUT_MIPPED
                || section == LAYER_CUTOUT || section == LAYER_TRANSLUCENT;
    }
}
