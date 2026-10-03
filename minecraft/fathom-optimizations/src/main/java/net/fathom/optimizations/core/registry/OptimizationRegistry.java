package net.fathom.optimizations.core.registry;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import net.fathom.optimizations.core.registry.Optimization.Basis;
import net.fathom.optimizations.core.registry.Optimization.Surface;

/**
 * Every optimization this project intends to make, whether or not it is written yet.
 *
 * <p>This is {@code docs/research/PERFORMANCE_MATRIX.md} expressed as data. Entries exist
 * before their implementations do, on purpose: the registry is what the launcher reads to
 * show a user what is available, what it would cost them, and what it conflicts with, and
 * that conversation is worth having before any Mixin is written.
 *
 * <p><strong>Only what was measured in the game is on.</strong> Most entries carry
 * no evidence or {@link Evidence.Level#ISOLATED} evidence — microbenchmarks and draw-call
 * counts, which establish that an implementation works and is not slower than what it
 * replaces, and do not establish that the game got faster. Only an in-game run promotes
 * anything; the first two promoted entries, {@code cpu.allocation.hot-path-reduction} and
 * {@code world.chunk.lookup-cache}, cite the interleaved A/B runs in
 * {@code docs/performance/gameplay-optimization-2026-09.md}. A test asserts that in-game
 * evidence only appears on entries a Mixin runs, so a microbenchmark cannot promote
 * anything.
 */
public final class OptimizationRegistry {

    private final Map<OptimizationId, Optimization> byId;

    private OptimizationRegistry(List<Optimization> entries) {
        Map<OptimizationId, Optimization> map = new LinkedHashMap<OptimizationId, Optimization>();
        for (Optimization entry : entries) {
            Optimization previous = map.put(entry.id(), entry);
            if (previous != null) {
                throw new IllegalStateException("duplicate optimization id: " + entry.id());
            }
        }
        this.byId = Collections.unmodifiableMap(map);
    }

    /** The built-in registry. */
    public static OptimizationRegistry standard() {
        return new OptimizationRegistry(Entries.all());
    }

    /** For tests that need a registry of their own shape. */
    public static OptimizationRegistry of(List<Optimization> entries) {
        return new OptimizationRegistry(entries);
    }

    public Optimization get(OptimizationId id) {
        Optimization found = byId.get(id);
        if (found == null) {
            throw new IllegalArgumentException("no such optimization: " + id);
        }
        return found;
    }

    public Optimization find(String id) {
        return byId.get(OptimizationId.of(id));
    }

    public boolean contains(OptimizationId id) {
        return byId.containsKey(id);
    }

    public List<Optimization> all() {
        return Collections.unmodifiableList(new ArrayList<Optimization>(byId.values()));
    }

    public List<Optimization> inCategory(Category category) {
        List<Optimization> out = new ArrayList<Optimization>();
        for (Optimization entry : byId.values()) {
            if (entry.category() == category) {
                out.add(entry);
            }
        }
        return out;
    }

    public List<Optimization> onSurface(Surface surface) {
        List<Optimization> out = new ArrayList<Optimization>();
        for (Optimization entry : byId.values()) {
            if (entry.surface() == surface) {
                out.add(entry);
            }
        }
        return out;
    }

    public int size() {
        return byId.size();
    }

    // -----------------------------------------------------------------------
    // The entries
    // -----------------------------------------------------------------------

    /**
     * The catalogue, from {@code PERFORMANCE_MATRIX.md}.
     *
     * <p>Not exhaustive: it is the subset with a clear enough problem statement to
     * describe honestly. The matrix has around forty rows; adding one here means being
     * able to say what it does, what it costs, and where the knowledge came from.
     */
    static final class Entries {

        /** OptiFine's renderer rewrite, which most of our rendering work overlaps. */
        private static Conflict optifine(String what) {
            return Conflict.disableOurs(
                    "OptiFine",
                    "OptiFine rewrites large parts of the renderer, including " + what
                            + ". Fathom turns this off when OptiFine is present rather than"
                            + " fighting it: the user chose to install OptiFine.");
        }

        static List<Optimization> all() {
            List<Optimization> out = new ArrayList<Optimization>();

            // The catalogue after the 2026-10-02 audit. Every entry here either
            // runs, or is planned work that survived review. What was removed,
            // merged or replaced, and why, is in
            // docs/performance/optimization-registry.md.

            // -- rendering -------------------------------------------------
            out.add(Optimization.builder("render.font.metrics-cache")
                    .name("Font metrics caching")
                    .description(
                            "Character width lookups are recomputed per frame. Caching them is"
                                    + " output-identical as long as the cache is invalidated on"
                                    + " resource reload and language change.")
                    .group(Optimization.Group.RENDERING)
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit(
                            "Measured 66-86% faster than recomputing, for the measurement work"
                                    + " alone, at realistic churn. Unknown as a fraction of a"
                                    + " frame.")
                    .defaultOnWhenMeasured()
                    .runsInGame()
                    .evidence(Evidence.isolated(
                            "bench-2026-08-26-text-metrics",
                            "One frame of a busy server's text (235 strings, 11,528 characters):"
                                    + " 12.9 us to recompute, 1.8 us cached with a static corpus"
                                    + " and 5.2 us with 5% of strings new each frame. At 50% churn"
                                    + " the difference falls inside the noise floor, and at 100%"
                                    + " the cache is 47% slower."))
                    .conflict(optifine("its font paths"))
                    .reference("docs/research/performance.md §2.1")
                    .reference("docs/performance/benchmarks.md — bench-2026-08-26-text-metrics")
                    .build());

            // -- entities --------------------------------------------------
            out.add(Optimization.builder("render.nametag.replay")
                    .name("Fast nametags")
                    .description(
                            "Vanilla draws a nametag a glyph at a time, twice. Each distinct"
                                    + " label is recorded once and replayed, with the same glyphs"
                                    + " in the same places.")
                    .group(Optimization.Group.ENTITIES)
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .inGameSetting()
                    .runsInGame()
                    .expectedBenefit(
                            "Largest where many named players or armour stands are in view.")
                    .evidence(Evidence.inGame(
                            "performance-layer-2026-09-22",
                            "Isolated on a populated server lobby: 0.155 ms/frame median paired"
                                    + " saving with 33 labels a frame, 18 of 20 pairs, sign test"
                                    + " p = 0.0004. Eight test strings were pixel-identical to"
                                    + " vanilla."))
                    .reference("docs/performance/performance-layer-2026-09-22.md")
                    .build());

            out.add(Optimization.builder("render.entity.distance-limit")
                    .name("Entity draw distance")
                    .description(
                            "Entities past a set distance are not drawn. Players are never"
                                    + " limited inside 48 blocks, and the game still simulates"
                                    + " everything it would have.")
                    .group(Optimization.Group.ENTITIES)
                    .category(Category.OPTIONAL)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .inGameSetting()
                    .runsInGame()
                    .expectedBenefit(
                            "Workload-dependent: large in lobbies full of distant armour stands,"
                                    + " nothing where everything is close.")
                    .evidence(Evidence.inGame(
                            "performance-layer-2026-09-22",
                            "Isolated in a lobby with 52 distant armour stands: 0.060 ms/frame"
                                    + " median paired saving, 14 of 20 pairs (p = 0.115) -- not"
                                    + " consistent enough for a standalone claim. The distance"
                                    + " test itself cost 0.010 ms/frame."))
                    .reference("docs/performance/performance-layer-2026-09-22.md")
                    .build());

            out.add(Optimization.builder("render.tile-entity.distance-limit")
                    .name("Tile entity draw distance")
                    .description(
                            "Chests, signs, skulls and banners past a set distance are not drawn."
                                    + " Beacons, end portals and pistons are never limited.")
                    .group(Optimization.Group.ENTITIES)
                    .category(Category.OPTIONAL)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .inGameSetting()
                    .runsInGame()
                    .expectedBenefit(
                            "Engaged in testing (3 of 68 tile entities culled a frame) but never"
                                    + " isolated, so no frame-time claim is made.")
                    .reference("docs/performance/performance-layer-2026-09-22.md")
                    .build());

            // -- HUD -------------------------------------------------------
            out.add(Optimization.builder("render.hud.caching")
                    .name("HUD caching")
                    .description(
                            "Fathom's HUD widgets that have not changed since the last frame are"
                                    + " replayed from a recording instead of drawn again, and slow"
                                    + " readouts such as the clock and ping are redrawn 20 times a"
                                    + " second. Replaces the planned quad batching: Fathom's"
                                    + " widgets already batch their quads.")
                    .group(Optimization.Group.HUD)
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .inGameSetting()
                    .runsInGame()
                    .expectedBenefit(
                            "Most of what Fathom's own HUD costs a frame, in every scene.")
                    .evidence(Evidence.inGame(
                            "view-2026-10-02-hud-steady",
                            "Replaying unchanged realtime widgets, A/B'd in the view benchmark"
                                    + " (3 interleaved rounds, OptiFine on): -0.026 ms/frame"
                                    + " looking at the sky, -0.027 at the ground, -0.029 with 48"
                                    + " players in view; Fathom's HUD fell from 0.042 to 0.027"
                                    + " ms/frame. Keystrokes and CPS pixel-identical in every"
                                    + " scene."))
                    .reference("docs/performance/view-direction-2026-10-02.md")
                    .build());

            // -- CPU and memory --------------------------------------------
            out.add(Optimization.builder("cpu.allocation.hot-path-reduction")
                    .name("Reduce allocation on hot paths")
                    .description(
                            "Vanilla clones the six-direction array at the top of loops in"
                                    + " per-block light queries, light updates and the per-frame"
                                    + " chunk visibility search. Those loops only read it, so they"
                                    + " share one copy instead. Output-identical. With OptiFine,"
                                    + " only the light-query part applies: OptiFine rewrites the"
                                    + " visibility search.")
                    .group(Optimization.Group.MEMORY)
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .runsInGame()
                    .expectedBenefit(
                            "The clones were 22% of the render thread's allocation in a sampled"
                                    + " run. Together with the chunk lookup cache, an interleaved"
                                    + " in-game A/B cut allocation 55% while panning and 35% while"
                                    + " moving, with 5-8% lower mean frame time in those scenes.")
                    .defaultOnWhenMeasured()
                    .evidence(Evidence.inGame(
                            "perf-2026-09-14-ab",
                            "Interleaved ABBA run in the development world, switches toggled"
                                    + " within one session: pan 51.8 -> 23.4 MB/s allocated and 6 -> 3"
                                    + " collections; moving 203 -> 132 MB/s. Profiler: the six-direction"
                                    + " clones fell from 1890 MB to 91 MB over the run."))
                    .reference("docs/research/performance.md §3.2")
                    .reference("docs/performance/gameplay-optimization-2026-09.md")
                    .build());

            out.add(Optimization.builder("world.chunk.lookup-cache")
                    .name("Chunk lookup cache")
                    .description(
                            "Every light query resolves its chunk through a hash lookup, and"
                                    + " particles ask for light every frame. The client thread"
                                    + " remembers the last few chunks it looked up; the cache is"
                                    + " cleared whenever a chunk loads or unloads, so it always"
                                    + " returns what the lookup would.")
                    .group(Optimization.Group.CHUNKS)
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .runsInGame()
                    .expectedBenefit(
                            "The hash lookup was the hottest method on the render thread (28% of"
                                    + " CPU samples) in a scene with torches. Largest where"
                                    + " particles and entities are drawn.")
                    .defaultOnWhenMeasured()
                    .evidence(Evidence.inGame(
                            "perf-2026-09-14-ab",
                            "Toggled alone, four consecutive interleaved blocks standing still in"
                                    + " alternating ABBA/BAAB order: mean frame time 6.5-9.9% lower"
                                    + " in every block, p99 lower in every block. Profiler: chunk"
                                    + " lookups fell from 14.3% to 5.1% of render-thread samples."))
                    // Not a structural conflict -- OptiFine does not patch the
                    // chunk provider -- but a measured absence of benefit.
                    .conflict(Conflict.disableOurs(
                            "OptiFine",
                            "With OptiFine installed it measured no benefit: A/B'd in the view"
                                    + " benchmark (2026-10-02) it moved nothing outside the noise,"
                                    + " so it is not applied there."))
                    .reference("docs/performance/gameplay-optimization-2026-09.md")
                    .reference("docs/performance/view-direction-2026-10-02.md")
                    .build());

            out.add(Optimization.builder("memory.world-change-gc")
                    .name("Remove System.gc() on world change")
                    .description(
                            "Vanilla calls System.gc() when changing worlds, which stops the"
                                    + " client for as long as a full collection takes.")
                    .group(Optimization.Group.MEMORY)
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit(
                            "Removes a stall of roughly a quarter of a second per world change on"
                                    + " the reference machine, and more on a larger live set. The"
                                    + " JVM flag -XX:+DisableExplicitGC achieves the same thing"
                                    + " without a mod, and the launcher already passes it; this"
                                    + " covers the case where it was not passed.")
                    .defaultOnWhenMeasured()
                    .runsInGame()
                    .evidence(Evidence.isolated(
                            // Isolated, not in-game: the dev world's heap is small, so
                            // the in-game stall measured (three full collections, 22-24
                            // ms each, while joining) says little about a real server.
                            "bench-2026-08-26-explicit-gc",
                            "A forced full collection under a synthetic 512 MB live set costs a"
                                    + " median 226 ms with G1 and 926 ms with ParallelGC, the"
                                    + " collector every unmodded 1.8.9 player is running."
                                    + " -XX:+DisableExplicitGC removes it entirely: zero"
                                    + " collections performed, 0.0 ms. Synthetic workload, not"
                                    + " Minecraft."))
                    .reference("docs/research/performance.md §4")
                    .reference("docs/performance/benchmarks.md — bench-2026-08-26-explicit-gc")
                    .reference("docs/performance/optimization-registry.md — experiment jvm.explicit-gc")
                    .build());

            out.add(Optimization.builder("resources.pack-icon-downscale")
                    .name("Downscale resource pack icons")
                    .description(
                            "Every installed pack's pack.png is uploaded at whatever resolution"
                                    + " its author saved it and kept for the session, though the"
                                    + " resource pack screen never draws one larger than 32"
                                    + " pixels. Downscaling to 64 on load frees the difference.")
                    .group(Optimization.Group.RESOURCES)
                    .category(Category.SAFE)
                    .surface(Surface.GAME)
                    .basis(Basis.THIRD_PARTY_PROBLEM_STATEMENT)
                    .expectedBenefit(
                            "Frees video memory in proportion to how many packs are installed and"
                                    + " how carelessly their icons were exported. Costs nothing at"
                                    + " frame time either way -- this is a memory optimization and"
                                    + " should not be expected to move FPS.")
                    .defaultOnWhenMeasured()
                    // Observed in a running 1.8.9 with OptiFine (2026-10-02): a
                    // 512x512 test icon was uploaded at 64x64, 1,032,192 bytes
                    // smaller. The memory figures below are arithmetic, not timing.
                    .runsInGame()
                    .evidence(Evidence.isolated(
                            "opt-2026-08-26-pack-icons",
                            "Arithmetic over a plausible 40-pack folder: 12 MB of video memory"
                                    + " above what the screen samples, and 4.2 MB for a single"
                                    + " 1024x1024 icon. Exact rather than estimated, because RGBA8"
                                    + " texture size is a calculation and not a measurement."))
                    .reference("docs/research/performance.md §4.1")
                    .reference(
                            "Problem identified by MemoryFix's README and by PolyPatcher's"
                                    + " feature list. No code read or reused.")
                    .reference("docs/performance/benchmarks.md — opt-2026-08-26-pack-icons")
                    .build());

            // -- frames and input ------------------------------------------
            out.add(Optimization.builder("input.unfocused-fps-cap")
                    .name("Cap frame rate when unfocused")
                    .description(
                            "Vanilla renders at full speed into a window you have alt-tabbed"
                                    + " away from. This caps it at "
                                    + net.fathom.optimizations.core.opt.frames.FrameCapPolicy
                                            .UNFOCUSED_CAP
                                    + " frames per second while the window does not have focus,"
                                    + " and changes nothing while it does.")
                    .group(Optimization.Group.FRAMES)
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .runsInGame()
                    .expectedBenefit(
                            "Frees the GPU and CPU when the game is in the background. No effect"
                                    + " on focused performance, by construction: the cap is only"
                                    + " consulted while the window is unfocused.")
                    .defaultOnWhenMeasured()
                    .evidence(Evidence.isolated(
                            "opt-2026-09-04-frame-caps",
                            "Arithmetic rather than a measurement, and exact because of it: the"
                                    + " policy returns the minimum of vanilla's own answer and the"
                                    + " cap, so the frames drawn while unfocused fall from"
                                    + " whatever the machine managed to 10 per second. What that"
                                    + " frees on any given machine has not been measured."))
                    .reference("docs/research/performance.md §6.5")
                    .reference("FATHOM_MODS_AND_LICENSING.md — idle/unfocused FPS cap")
                    .build());

            // -- planned ---------------------------------------------------
            // What is left after the audit: real problems, not yet solved,
            // each needing work that does not belong in a cleanup pass.
            out.add(Optimization.builder("render.entity.occlusion-culling")
                    .name("Entity occlusion culling")
                    .description(
                            "Skip entities fully hidden behind geometry. Must exempt nametags,"
                                    + " ender dragons and withers, and must never reveal anything"
                                    + " vanilla hides. Built and measured in game on 2026-10-03"
                                    + " (branch experiment/entity-occlusion) and not shipped:"
                                    + " proving an entity hidden on the CPU cost more than"
                                    + " drawing it. A sampled test hid a walled-off crowd of 48"
                                    + " armoured zombies but spent 1.8-2.3 ms a frame to save"
                                    + " 1.2; an exact test never errs but proves too few hidden"
                                    + " behind real terrain, and the scene was still 0.35 ms"
                                    + " slower with it on.")
                    .group(Optimization.Group.ENTITIES)
                    .category(Category.OPTIONAL)
                    .implementedInCore()
                    .basis(Basis.THIRD_PARTY_PROBLEM_STATEMENT)
                    .expectedBenefit(
                            "About 25 us per armoured mob drawn is the most it can save, and"
                                    + " players and anything named are exempt. Needs a test far"
                                    + " cheaper than that per entity -- likely GPU occlusion"
                                    + " queries -- before it is worth offering.")
                    .conflict(Conflict.disableOurs(
                            "OptiFine shaders",
                            "PolyPatcher documents that entity culling and OptiFine shaders"
                                    + " cannot be made compatible. Shaders present is a hard gate:"
                                    + " culling is off, not merely adjusted."))
                    .reference("docs/research/performance.md §2.4")
                    .reference("docs/research/security-model.md §9 — must not reveal hidden things")
                    .build());

            out.add(Optimization.builder("render.chunk.rebuild-scheduling")
                    .name("Chunk rebuild scheduling")
                    .description(
                            "Without OptiFine, 1.8.9 rebuilds chunks on exactly two worker"
                                    + " threads whatever the machine. This sizes the pool to the"
                                    + " CPU instead -- half the hardware threads, between two and"
                                    + " five -- with a build buffer per worker. Of the three ideas"
                                    + " merged here, only the pool size survived reading the code:"
                                    + " vanilla already builds nearest chunks first, and the view"
                                    + " benchmark found no redundant rebuilds to skip. Takes"
                                    + " effect at the next renderer reload (joining a world, F3+A).")
                    .group(Optimization.Group.CHUNKS)
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .runsInGame()
                    .expectedBenefit(
                            "Chunks fill in sooner when joining, teleporting or reloading."
                                    + " Steady frame time is untouched: once a view settles"
                                    + " nothing is being rebuilt. Not on by default -- one"
                                    + " machine, and each arm had an outlier.")
                    .evidence(Evidence.inGame(
                            "view-2026-10-03-chunk-workers",
                            "Full renderer reloads on the user's profile without OptiFine, four"
                                    + " launches in on-off-off-on order, three reloads each,"
                                    + " 5 workers against vanilla's 2: reload-to-quiet median"
                                    + " 0.65 s against 0.76 s, mean 0.73 s against 0.87 s"
                                    + " (each including a fixed 0.5 s quiet period)."))
                    .conflict(Conflict.disableOurs(
                            "OptiFine",
                            "OptiFine already sizes its own chunk worker pool to the CPU (up to"
                                    + " five threads)."))
                    .reference("docs/research/performance.md §2.3")
                    .reference("docs/performance/view-direction-2026-10-02.md — chunk worker pool")
                    .build());

            out.add(Optimization.builder("input.frame-limiter-accuracy")
                    .name("Accurate frame limiter")
                    .description(
                            "Vanilla's FPS cap waits using LWJGL's estimates of how long a sleep"
                                    + " and a yield take, so it reaches the right average with"
                                    + " uneven frames around it. This waits for fixed deadlines"
                                    + " on the system's precise clock instead: sleep while more"
                                    + " than 1.5 ms remains, then yield. Only does anything with"
                                    + " a frame cap set.")
                    .group(Optimization.Group.FRAMES)
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .runsInGame()
                    .expectedBenefit(
                            "More even frame pacing at a cap, same average FPS. Nothing at all"
                                    + " uncapped.")
                    .defaultOnWhenMeasured()
                    .evidence(Evidence.inGame(
                            "view-2026-10-02-pace144",
                            "Capped at 144 FPS with OptiFine, three views, three rounds each:"
                                    + " frame-time standard deviation 0.51 -> 0.24 ms (sky),"
                                    + " 0.55 -> 0.38 ms (horizon), 0.52 -> 0.41 ms (45 degrees"
                                    + " down); mean frame time unchanged within 0.02 ms."))
                    .reference("docs/research/performance.md §6.3")
                    .reference("docs/performance/view-direction-2026-10-02.md — frame limiter")
                    .build());

            return out;
        }

        private Entries() {}
    }
}
