package net.fathom.optimizations.core.bench.auto;

/**
 * How many times a thing happened, per frame.
 *
 * <h2>What this is for</h2>
 *
 * <p>A subsystem can be slow because it is expensive, or because it runs more
 * often than anyone intended. Those look identical in a frame-time total and
 * need opposite fixes. Counting the calls separates them: "the HUD costs 0.2 ms"
 * and "the HUD costs 0.2 ms and rendered twice" are different findings.
 *
 * <p>The ratios worth flagging are the ones that should be exactly one per
 * frame. A vector renderer that opens two frames inside one Minecraft frame is
 * doing its most expensive setup twice; a render callback invoked three times is
 * a listener registered three times. {@link #suspicious} names those rather than
 * leaving a reader to divide the numbers.
 *
 * <h2>Cost</h2>
 *
 * <p>A {@code long[]} indexed by ordinal, incremented without synchronisation on
 * the render thread. No allocation, no map lookup, no branch beyond the enabled
 * check the caller already makes. Counting is the cheapest instrumentation
 * available and it is the only kind that can be left in a hot path.
 */
public final class RenderCounters {

    /** Things worth counting once per frame. */
    public enum Counter {
        /** Minecraft frames, the denominator for everything else. */
        FRAMES("frames", 0d, 0d),
        /** Vector-renderer frames opened. Expected: at most one per frame. */
        NANOVG_BEGIN("NanoVG beginFrame", 1d, 1.05d),
        NANOVG_END("NanoVG endFrame", 1d, 1.05d),
        /** GL-renderer frames opened. */
        GL_FRAME_BEGIN("Fathom GL beginFrame", 1d, 1.05d),
        /** Whole-HUD render passes. Expected: exactly one per frame. */
        HUD_PASS("Fathom HUD pass", 1d, 1.05d),
        /** Individual widget draws. Many per frame is normal. */
        HUD_WIDGET("HUD widget draw", 0d, 0d),
        /** Fathom screen render passes. At most one. */
        SCREEN_PASS("Fathom screen pass", 1d, 1.05d),
        /** GL state captures. Each is ~24 synchronous driver queries. */
        GL_STATE_CAPTURE("GL state capture", 1d, 1.05d),
        /** Text measured through the vanilla renderer. */
        STRING_WIDTH("getStringWidth", 0d, 0d),
        /** Glyphs drawn by Fathom's own font path. */
        FATHOM_GLYPH("Fathom glyph", 0d, 0d),
        /** Texture binds Fathom issued. */
        TEXTURE_BIND("texture bind", 0d, 0d),
        /** Framebuffer rebinds Fathom issued. */
        FRAMEBUFFER_BIND("framebuffer bind", 0d, 0d),
        /** Whole-game timing boundaries. */
        GAME_LOOP("Minecraft.runGameLoop", 1d, 1.05d),
        CLIENT_TICK("Minecraft.runTick", 0d, 0d),
        CAMERA_AND_RENDER("EntityRenderer.updateCameraAndRender", 1d, 1.05d),
        RENDER_WORLD("EntityRenderer.renderWorld", 1d, 1.05d),
        RENDER_ENTITIES("RenderGlobal.renderEntities", 1d, 1.05d),
        CHUNK_RENDER("RenderGlobal.sortAndRender", 0d, 0d),
        RENDER_ENTITY("RenderManager.doRenderEntity", 0d, 0d),
        VANILLA_HUD("GuiIngame.renderGameOverlay", 1d, 1.05d),
        /** Fathom integration boundaries. */
        MIXIN_CALLBACK("Fathom mixin callback", 0d, 0d),
        EVENT_DISPATCH("Fathom injected dispatch", 0d, 0d),
        EVENT_LISTENER("Fathom direct listener", 0d, 0d),
        GL_WRAPPER("Fathom GL/state wrapper", 0d, 0d),
        OPTIFINE_OVERLAP_HOOK("Fathom hook on an OptiFine-modified method", 0d, 0d);

        private final String label;
        /** Expected per frame; zero means "no expectation, just report it". */
        private final double expected;
        /** Above this per frame it is flagged. Zero disables flagging. */
        private final double flagAbove;

        Counter(String label, double expected, double flagAbove) {
            this.label = label;
            this.expected = expected;
            this.flagAbove = flagAbove;
        }

        public String label() {
            return label;
        }

        public double expected() {
            return expected;
        }

        public double flagAbove() {
            return flagAbove;
        }
    }

    private static final Counter[] ALL = Counter.values();

    private final long[] values = new long[ALL.length];

    /** Add one. The only method that runs in a hot path. */
    public void hit(Counter counter) {
        values[counter.ordinal()]++;
    }

    public void add(Counter counter, long amount) {
        values[counter.ordinal()] += amount;
    }

    public long get(Counter counter) {
        return values[counter.ordinal()];
    }

    public void reset() {
        java.util.Arrays.fill(values, 0L);
    }

    public RenderCounters copy() {
        RenderCounters out = new RenderCounters();
        System.arraycopy(values, 0, out.values, 0, values.length);
        return out;
    }

    public long frames() {
        return values[Counter.FRAMES.ordinal()];
    }

    /** Per frame, or zero when no frames were counted. */
    public double perFrame(Counter counter) {
        long frames = frames();
        if (frames <= 0L) {
            return 0d;
        }
        return values[counter.ordinal()] / (double) frames;
    }

    /**
     * Counters running above what one frame should need.
     *
     * <p>Each entry is a sentence, because the number alone does not say what is
     * wrong with it. Only counters with a stated expectation are considered: a
     * widget draw count of eleven is not suspicious, it is eleven widgets.
     */
    public java.util.List<String> suspicious() {
        java.util.List<String> out = new java.util.ArrayList<String>();
        if (frames() <= 0L) {
            return out;
        }
        for (Counter counter : ALL) {
            if (counter.flagAbove() <= 0d) {
                continue;
            }
            double rate = perFrame(counter);
            if (rate > counter.flagAbove()) {
                out.add(String.format(java.util.Locale.ROOT,
                        "%s ran %.2f times per frame, expected %.0f -- %s",
                        counter.label(), rate, counter.expected(),
                        rate >= counter.expected() * 2d
                                ? "the work is being done more than once"
                                : "more often than one pass per frame"));
            }
        }
        return out;
    }
}
