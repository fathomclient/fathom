package net.fathom.optimizations.core.bench;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.fathom.optimizations.core.bench.Scenario.Reproducibility;
import net.fathom.optimizations.core.instrument.Counters.Counter;

/**
 * The seven workloads this project measures against.
 *
 * <p>They come from the milestone brief, and each is here whether or not we can currently
 * reproduce it. Three of the seven are honestly not reproducible on demand today, and
 * saying so is more useful than quietly measuring something easier and calling it the
 * same thing.
 *
 * <p>The reproducibility spread is the important content of this file:
 *
 * <ul>
 *   <li>Two are <strong>automatable</strong> — the client can build them itself.
 *   <li>Two are <strong>scripted local</strong> — a prepared world and a fixed camera path.
 *   <li>Three need a <strong>live server</strong> we do not control, and produce figures
 *       that are not comparable between sessions.
 * </ul>
 */
public final class Scenarios {

    private Scenarios() {}

    /** All nine, in the order the milestone brief lists them. */
    public static List<Scenario> all() {
        List<Scenario> out = new ArrayList<Scenario>();
        out.add(cpuCeiling());
        out.add(singleplayer());
        out.add(pvpMap());
        out.add(entityLobby());
        out.add(textHeavy());
        out.add(particles());
        out.add(chunkMovement());
        out.add(inventoryGui());
        out.add(resourcePack());
        return Collections.unmodifiableList(out);
    }

    public static Scenario byId(String id) {
        for (Scenario scenario : all()) {
            if (scenario.id().equals(id)) {
                return scenario;
            }
        }
        return null;
    }

    /** Only the ones that produce numbers comparable between sessions. */
    public static List<Scenario> comparable() {
        List<Scenario> out = new ArrayList<Scenario>();
        for (Scenario scenario : all()) {
            if (scenario.comparableBetweenSessions()) {
                out.add(scenario);
            }
        }
        return Collections.unmodifiableList(out);
    }

    // -----------------------------------------------------------------------

    /**
     * 0. The CPU ceiling.
     *
     * <p>The most important scenario in the set, and the least interesting to
     * look at: a wall, filling the screen, at a fixed position, with nothing
     * else in view.
     *
     * <p>It matters because it removes the GPU from the answer. When the scene
     * is trivial to draw, frame time is whatever the client spends deciding
     * <em>what</em> to draw — which is exactly where a mod's overhead lives. A
     * difference measured here is a difference in CPU work; the same difference
     * measured in a forest could be either machine.
     *
     * <p>This is the scene the regression threshold in
     * {@code docs/performance/regression-thresholds.md} is written against.
     */
    static Scenario cpuCeiling() {
        return Scenario.builder("cpu-ceiling")
                .name("CPU ceiling")
                .description(
                        "A flat wall filling the view, from a fixed position, with no entities,"
                                + " no particles and nothing else on screen. Frame time here is"
                                + " CPU work, not drawing.")
                .reproducibility(Reproducibility.AUTOMATABLE)
                .setup(
                        "Superflat world, seed 0, creative, time set to noon and doDaylightCycle"
                                + " off. Teleport to the recorded coordinates facing the prepared"
                                + " stone wall: /tp @s 0 5 0 90 0. Render distance 8. Do not"
                                + " move. The wall is two chunks of stone placed by the setup"
                                + " script, so the same scene is reproducible without a shipped"
                                + " world file.")
                .counters(
                        Counter.DRAW_CALLS_APPROX,
                        Counter.CHUNK_REBUILDS)
                .limitation(
                        "Uncapped by design, so this scenario is sensitive to CPU boost"
                                + " behaviour and thermals. Alternate configurations and discard"
                                + " runs whose clocks differ from the session median.")
                .limitation(
                        "A trivial scene is not a scene anybody plays. This measures overhead,"
                                + " not experience.")
                .seconds(60)
                .build();
    }

    /**
     * 0b. Ordinary singleplayer.
     *
     * <p>What a normal frame looks like: terrain, sky, a few entities, a hotbar.
     * Fixed position and direction so it is the same frame every time.
     *
     * <p>It exists because the CPU ceiling can mislead in the other direction —
     * an overhead that is 4% of a trivial frame may be 0.5% of a real one, and
     * both numbers are worth knowing before deciding whether to care.
     */
    static Scenario singleplayer() {
        return Scenario.builder("singleplayer-still")
                .name("Ordinary singleplayer, still")
                .description(
                        "A normal outdoor scene from a fixed viewpoint: terrain, sky, water and"
                                + " a handful of passive mobs. The camera does not move.")
                .reproducibility(Reproducibility.SCRIPTED_LOCAL)
                .setup(
                        "The prepared benchmark world at the recorded spawn. Teleport with"
                                + " /tp @s -170 71 223 -90 0, set time to 6000 and turn"
                                + " doDaylightCycle and doMobSpawning off, then wait for chunk"
                                + " loading to settle before starting the capture.")
                .counters(
                        Counter.ENTITIES_RENDERED,
                        Counter.TILE_ENTITIES_RENDERED,
                        Counter.CHUNK_REBUILDS,
                        Counter.DRAW_CALLS_APPROX)
                .limitation(
                        "Needs the prepared world. Without it the terrain differs and the"
                                + " numbers are not comparable to anyone else's.")
                .seconds(120)
                .build();
    }

    /** 1. A normal multiplayer PvP map. */
    static Scenario pvpMap() {
        return Scenario.builder("pvp-map")
                .name("Multiplayer PvP map")
                .description(
                        "Mid-size arena, a handful of players in view, combat particles and"
                                + " frequent camera movement. The workload the project exists for.")
                .reproducibility(Reproducibility.SCRIPTED_LOCAL)
                .setup(
                        "Load the prepared benchmark world, spawn a fixed set of armour stands"
                                + " with player-shaped models at recorded positions, and follow a"
                                + " scripted camera path. A real PvP match cannot be replayed;"
                                + " what is reproduced is the render workload, not the game.")
                .counters(
                        Counter.ENTITIES_CONSIDERED,
                        Counter.ENTITIES_RENDERED,
                        Counter.DRAW_CALLS_APPROX,
                        Counter.TEXT_RUNS)
                .limitation(
                        "Armour stands are not players: no skin fetch, no animation state"
                                + " machine, no held-item rendering variety. This measures a"
                                + " player-shaped render load, not a real match.")
                .limitation(
                        "There is no other client sending movement packets, so the network and"
                                + " entity-interpolation cost of a real match is absent.")
                .seconds(180)
                .build();
    }

    /** 2. An entity-heavy lobby. */
    static Scenario entityLobby() {
        return Scenario.builder("entity-lobby")
                .name("Entity-heavy lobby")
                .description(
                        "Hundreds of armour stands and item frames in a small area, most of them"
                                + " behind geometry. The scenario entity occlusion culling exists"
                                + " for.")
                .reproducibility(Reproducibility.AUTOMATABLE)
                .setup(
                        "Spawn a deterministic grid of armour stands in a flat local world, half"
                                + " of them behind a wall, and hold a fixed camera. Fully"
                                + " constructible by the client with no server and no prepared"
                                + " world.")
                .counters(
                        Counter.ENTITIES_CONSIDERED,
                        Counter.ENTITIES_RENDERED,
                        Counter.DRAW_CALLS_APPROX)
                .seconds(120)
                .build();
    }

    /** 3. Scoreboard, tab list and nametags. */
    static Scenario textHeavy() {
        return Scenario.builder("text-heavy")
                .name("Scoreboard, tab list and nametags")
                .description(
                        "A full scoreboard, a populated tab list and many floating nametags."
                                + " The scenario font batching and metrics caching exist for.")
                .reproducibility(Reproducibility.AUTOMATABLE)
                .setup(
                        "Render a synthetic scoreboard of fixed content, a synthetic tab list,"
                                + " and a fixed set of named entities, all constructed by the"
                                + " harness. Deterministic text means a deterministic glyph"
                                + " count, which is what makes this the cleanest of the seven.")
                .counters(Counter.TEXT_RUNS, Counter.GLYPHS, Counter.DRAW_CALLS_APPROX)
                .seconds(120)
                .build();
    }

    /** 4. A particle-heavy environment. */
    static Scenario particles() {
        return Scenario.builder("particles")
                .name("Particle-heavy environment")
                .description("A dense, sustained particle field.")
                .reproducibility(Reproducibility.SCRIPTED_LOCAL)
                .setup(
                        "Emit a fixed particle count per tick from fixed positions in the"
                                + " prepared world, with a fixed camera.")
                .counters(Counter.PARTICLES_RENDERED, Counter.DRAW_CALLS_APPROX)
                .limitation(
                        "Particle lifetime involves randomness in vanilla. The emission rate is"
                                + " fixed, but the exact set of live particles at any instant is"
                                + " not, so run-to-run variance is higher than in the text"
                                + " scenario. Longer runs, and comparing medians rather than"
                                + " single figures.")
                .seconds(180)
                .build();
    }

    /** 5. Rapid chunk movement. */
    static Scenario chunkMovement() {
        return Scenario.builder("chunk-movement")
                .name("Rapid chunk movement")
                .description(
                        "Travelling fast enough to keep the chunk rebuild queue saturated. The"
                                + " scenario the rebuild-priority work exists for.")
                .reproducibility(Reproducibility.SCRIPTED_LOCAL)
                .setup(
                        "Teleport along a fixed path through the prepared world at a fixed rate,"
                                + " forcing continuous chunk loading and meshing.")
                .counters(Counter.CHUNK_REBUILDS, Counter.CHUNK_REBUILD_NANOS, Counter.BUFFER_UPLOADS)
                .limitation(
                        "Chunk meshing runs on worker threads, so its cost lands on frame time"
                                + " only indirectly. The rebuild counters measure the work; the"
                                + " frame statistics measure what the player feels. They are"
                                + " different questions and both are reported.")
                .limitation(
                        "Disk read speed affects chunk loading, so results are not comparable"
                                + " across machines with different storage. Same-machine"
                                + " before-and-after only.")
                .seconds(180)
                .build();
    }

    /** 6. Inventory and GUI. */
    static Scenario inventoryGui() {
        return Scenario.builder("inventory-gui")
                .name("Inventory and GUI")
                .description(
                        "A full inventory screen with many distinct item stacks, which is a"
                                + " model-rendering and quad-batching workload rather than a world"
                                + " one.")
                .reproducibility(Reproducibility.AUTOMATABLE)
                .setup(
                        "Open a container populated with a fixed, deterministic set of stacks"
                                + " and hold it open with the camera still.")
                .counters(Counter.GUI_QUADS, Counter.TEXT_RUNS, Counter.DRAW_CALLS_APPROX)
                .seconds(90)
                .build();
    }

    /** 7. A resource-pack-heavy environment. */
    static Scenario resourcePack() {
        return Scenario.builder("resource-pack")
                .name("Resource-pack-heavy environment")
                .description(
                        "A high-resolution pack with a large texture atlas, measuring texture"
                                + " memory and atlas-related cost rather than draw calls.")
                .reproducibility(Reproducibility.NOT_YET_REPRODUCIBLE)
                .setup(
                        "Not established. Requires a pack we are permitted to redistribute with"
                                + " the harness, and we do not have one.")
                .counters(Counter.DRAW_CALLS_APPROX)
                .limitation(
                        "We cannot ship a third-party resource pack with the benchmark harness:"
                                + " packs are copyrighted works and redistributing one is exactly"
                                + " the act mod-policies.md §7 governs. Measuring against a pack"
                                + " the tester supplies makes results incomparable between"
                                + " testers.")
                .limitation(
                        "The likely resolution is a synthetic pack we generate ourselves at a"
                                + " chosen resolution. That is honest and comparable, but it"
                                + " measures atlas size rather than any real pack's behaviour."
                                + " Not built.")
                .seconds(120)
                .build();
    }
}
