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

        private static Conflict polypatcher(String what) {
            return Conflict.disableOurs(
                    "PolyPatcher",
                    "PolyPatcher covers " + what + " as well. Where a user has chosen it,"
                            + " Fathom defers rather than double-patching the same methods.");
        }

        static List<Optimization> all() {
            List<Optimization> out = new ArrayList<Optimization>();

            // -- rendering: draw call reduction ----------------------------
            out.add(Optimization.builder("render.font.batching")
                    .name("Batched font rendering")
                    .description(
                            "Vanilla issues one draw call per glyph. Batching a text run into a"
                                    + " single buffer turns a line of chat from dozens of calls"
                                    + " into one.")
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit(
                            "Expected to be the largest 2D win on text-heavy screens. The draw-call"
                                    + " reduction is measured; the frame-time effect is not.")
                    .defaultOnWhenMeasured()
                    .implementedInCore()
                    .evidence(Evidence.isolated(
                            "opt-2026-08-26-quad-batching",
                            "A 240-glyph line spanning two font pages issues 3 draw calls instead"
                                    + " of 240, with a vertex stream compared element by element"
                                    + " against the unbatched path and found identical. That is the"
                                    + " mechanism working, not a frame-time result."))
                    .conflict(optifine("its font paths"))
                    .conflict(polypatcher("font rendering"))
                    .reference("docs/research/performance.md §2.1")
                    .reference("docs/performance/benchmarks.md — opt-2026-08-26-quad-batching")
                    .build());

            out.add(Optimization.builder("render.font.metrics-cache")
                    .name("Font metrics caching")
                    .description(
                            "Character width lookups are recomputed per frame. Caching them is"
                                    + " output-identical as long as the cache is invalidated on"
                                    + " resource reload and language change.")
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

            out.add(Optimization.builder("render.hud.quad-batching")
                    .name("Batched HUD and GUI quads")
                    .description(
                            "Quads sharing a texture are collected into one buffer instead of"
                                    + " being submitted individually.")
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit("Expected to help HUD-heavy scenes. Unmeasured.")
                    .defaultOnWhenMeasured()
                    .conflict(optifine("HUD drawing"))
                    .reference("docs/research/performance.md §2.2")
                    .build());

            out.add(Optimization.builder("render.entity.model-batching")
                    .name("Batched entity and item model rendering")
                    .description("Entity and item models sharing state are drawn together.")
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit("Expected to help entity-dense scenes. Unmeasured.")
                    .defaultOnWhenMeasured()
                    .conflict(optifine("entity rendering"))
                    .reference("docs/research/performance.md §2.4")
                    .build());

            out.add(Optimization.builder("render.tile-entity.batching")
                    .name("Tile entity batching by texture")
                    .description("Tile entities sharing a texture are grouped into one draw.")
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit("Expected to help builds with many tile entities. Unmeasured.")
                    .defaultOnWhenMeasured()
                    .reference("docs/research/performance.md §2.5")
                    .build());

            out.add(Optimization.builder("render.tile-entity.double-render-fix")
                    .name("Fix double-rendered tile entities")
                    .description(
                            "Some special tile entities are drawn twice per frame. Drawing them"
                                    + " once is a straightforward defect fix.")
                    .category(Category.SAFE)
                    .basis(Basis.THIRD_PARTY_PROBLEM_STATEMENT)
                    .expectedBenefit("Halves the cost of the affected tile entities. Unmeasured.")
                    .defaultOnWhenMeasured()
                    .reference("docs/research/performance.md §2.5")
                    .reference(
                            "Problem identified by PolyPatcher's changelog. No code read or"
                                    + " reused; the fix is our own reading of vanilla.")
                    .build());

            // -- rendering: culling ----------------------------------------
            out.add(Optimization.builder("render.entity.occlusion-culling")
                    .name("Entity occlusion culling")
                    .description(
                            "Skip entities fully hidden behind geometry. Must exempt nametags,"
                                    + " ender dragons and withers, and must never reveal anything"
                                    + " vanilla hides. Only the refusal half is written: the rules"
                                    + " that say an entity must be drawn whatever an occlusion"
                                    + " test concluded. There is no occlusion test yet, so this"
                                    + " cannot currently make anything faster and is not claimed"
                                    + " to.")
                    .category(Category.OPTIONAL)
                    .implementedInCore()
                    .basis(Basis.THIRD_PARTY_PROBLEM_STATEMENT)
                    .expectedBenefit(
                            "Expected to be very large in lobbies with hundreds of armour stands."
                                    + " Unmeasured, and breaks visibly if the culling test is"
                                    + " wrong.")
                    .conflict(Conflict.disableOurs(
                            "OptiFine shaders",
                            "PolyPatcher documents that entity culling and OptiFine shaders"
                                    + " cannot be made compatible. Shaders present is a hard gate:"
                                    + " culling is off, not merely adjusted."))
                    .reference("docs/research/performance.md §2.4")
                    .reference("docs/research/security-model.md §9 — must not reveal hidden things")
                    .build());

            // -- rendering: chunks -----------------------------------------
            out.add(Optimization.builder("render.chunk.rebuild-priority")
                    .name("Prioritised chunk rebuild queue")
                    .description(
                            "Rebuild nearby and in-view chunks first. Reorders work rather than"
                                    + " throttling it, so nothing is deferred indefinitely.")
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit("Expected to remove most join-stutter. Unmeasured.")
                    .defaultOnWhenMeasured()
                    .reference("docs/research/performance.md §2.3")
                    .build());

            out.add(Optimization.builder("render.chunk.skip-redundant-rebuilds")
                    .name("Skip rebuilds that cannot change the mesh")
                    .description(
                            "A block update that cannot alter the rendered geometry should not"
                                    + " trigger a rebuild.")
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit("Expected to cut rebuild counts substantially. Unmeasured.")
                    .defaultOnWhenMeasured()
                    .reference("docs/research/performance.md §2.3")
                    .build());

            out.add(Optimization.builder("render.chunk.frustum-false-negative-fix")
                    .name("Fix invisible chunks (MC-63020 / MC-70850)")
                    .description(
                            "Vanilla's frustum culling has false negatives that make chunks"
                                    + " disappear. Fixing it is a correctness win that costs"
                                    + " performance.")
                    .category(Category.OPTIONAL)
                    .basis(Basis.MOJANG_BUG)
                    .expectedBenefit(
                            "Negative for frame rate, positive for correctness. A trade that"
                                    + " belongs to the user, not to us.")
                    .reference("Mojang bug reports MC-63020, MC-70850")
                    .reference("docs/research/performance.md §2.3")
                    .build());

            // -- CPU --------------------------------------------------------
            out.add(Optimization.builder("cpu.allocation.hot-path-reduction")
                    .name("Reduce allocation on hot paths")
                    .description(
                            "Vanilla clones the six-direction array at the top of loops in"
                                    + " per-block light queries, light updates and the per-frame"
                                    + " chunk visibility search. Those loops only read it, so they"
                                    + " share one copy instead. Output-identical.")
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
                    .reference("docs/performance/gameplay-optimization-2026-09.md")
                    .build());

            // -- memory -----------------------------------------------------
            out.add(Optimization.builder("memory.world-change-gc")
                    .name("Remove System.gc() on world change")
                    .description(
                            "Vanilla calls System.gc() when changing worlds, which stops the"
                                    + " client for as long as a full collection takes.")
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
                    .category(Category.SAFE)
                    .surface(Surface.GAME)
                    .basis(Basis.THIRD_PARTY_PROBLEM_STATEMENT)
                    .expectedBenefit(
                            "Frees video memory in proportion to how many packs are installed and"
                                    + " how carelessly their icons were exported. Costs nothing at"
                                    + " frame time either way — this is a memory optimization and"
                                    + " should not be expected to move FPS.")
                    .defaultOnWhenMeasured()
                    .implementedInCore()
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

            // -- input ------------------------------------------------------
            out.add(Optimization.builder("input.mc-67665-look-vector")
                    .name("Fix MC-67665 look-vector staleness")
                    .description(
                            "The reported 1.8 'input lag' is a one-frame-stale look vector, not"
                                    + " input latency. Mojang fixed it in a later version.")
                    .category(Category.SAFE)
                    .basis(Basis.MOJANG_BUG)
                    .expectedBenefit(
                            "Removes a frame of apparent aim latency. Perceptual; hard to measure"
                                    + " with frame timing alone, which is a limitation the"
                                    + " methodology document records.")
                    .defaultOnWhenMeasured()
                    .reference("Mojang bug report MC-67665")
                    .reference("docs/research/performance.md §6.1")
                    .build());

            out.add(Optimization.builder("input.frame-limiter-accuracy")
                    .name("Accurate frame limiter")
                    .description(
                            "Vanilla's FPS cap busy-waits imprecisely, producing uneven frame"
                                    + " pacing at capped rates.")
                    .category(Category.SAFE)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit(
                            "Expected to reduce frame time variance at a cap without changing"
                                    + " average FPS. Exactly the thing percentile metrics exist to"
                                    + " show. Unmeasured.")
                    .defaultOnWhenMeasured()
                    .reference("docs/research/performance.md §6.3")
                    .build());

            out.add(Optimization.builder("input.unfocused-fps-cap")
                    .name("Cap frame rate when unfocused")
                    .description(
                            "Vanilla renders at full speed into a window you have alt-tabbed"
                                    + " away from. This caps it at "
                                    + net.fathom.optimizations.core.opt.frames.FrameCapPolicy
                                            .UNFOCUSED_CAP
                                    + " frames per second while the window does not have focus,"
                                    + " and changes nothing while it does.")
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

            // -- experimental ----------------------------------------------
            out.add(Optimization.builder("render.chunk.worker-pool-sizing")
                    .name("Size the chunk worker pool to the machine")
                    .description(
                            "1.8's worker count is conservative for modern core counts. Sizing it"
                                    + " to the actual machine is a one-line change with a"
                                    + " hardware-dependent effect.")
                    .category(Category.EXPERIMENTAL)
                    .basis(Basis.VANILLA_ANALYSIS)
                    .expectedBenefit(
                            "Expected to help chunk loading on many-core machines and to do"
                                    + " nothing, or harm, on few-core ones. Hardware-dependent by"
                                    + " construction, which is why it is EXPERIMENTAL.")
                    .reference("docs/research/performance.md §2.3")
                    .build());

            out.add(Optimization.builder("render.particle.brightness-cache")
                    .name("Cache particle brightness")
                    .description(
                            "Particles re-query lighting every frame. Caching it means they do"
                                    + " not re-light as they drift, which is visible if you look"
                                    + " for it.")
                    .category(Category.OPTIONAL)
                    .basis(Basis.THIRD_PARTY_PROBLEM_STATEMENT)
                    .expectedBenefit(
                            "Expected to help particle-heavy scenes. Not output-identical, hence"
                                    + " OPTIONAL rather than SAFE.")
                    .reference("docs/research/performance.md §2.6")
                    .build());

            return out;
        }

        private Entries() {}
    }
}
