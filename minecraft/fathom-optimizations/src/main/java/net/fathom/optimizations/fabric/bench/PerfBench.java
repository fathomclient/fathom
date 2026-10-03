package net.fathom.optimizations.fabric.bench;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.ManagementFactory;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ClientPlayerEntity;

import net.fathom.optimizations.core.instrument.GcLog;
import net.fathom.optimizations.core.instrument.Report;
import net.fathom.optimizations.core.instrument.Session;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.hud.HudEditorScreen;

/**
 * Development-only, scripted performance benchmark.
 *
 * <p>Started with {@code gradle runClient -Pnanovg -PperfBench=world} (gameplay
 * scenarios in the development save) or {@code -PperfBench=soak} (long idle runs
 * of the main menu, Hub, full-page module settings and HUD editor, to catch frame-rate
 * degradation and memory growth). Absent the property this class does nothing:
 * {@link #ACTIVE} is a constant and the frame hook's call folds away.
 *
 * <p>Every scenario is driven the same way on every run — fixed camera angles,
 * fixed movement speed, fixed durations, a settle period before measuring — so a
 * before and an after can be compared. Measurement reuses {@link Session}: frame
 * log, percentiles, 1% lows, Fathom's own section costs, heap and GC.
 *
 * <p>Two measurement controls, both only while a benchmark runs: the unfocused
 * frame cap is bypassed (a launched window often lacks focus, and a cap would
 * report 30 fps rather than the machine), and pause-on-focus-loss is disabled in
 * memory. Neither is saved and neither changes rendering work.
 */
public final class PerfBench {

    public static final String MODE = System.getProperty("fathom.perfBench", "").trim().toLowerCase(Locale.ROOT);
    public static final boolean ACTIVE = !MODE.isEmpty();

    private static final float SETTLE_SECONDS = floatProperty("fathom.perfBench.settle", 4f);
    private static final float MEASURE_SECONDS = floatProperty("fathom.perfBench.seconds", 20f);
    private static final float SOAK_SECONDS = floatProperty("fathom.perfBench.soakSeconds", 120f);
    private static final float SOAK_WINDOW_SECONDS = 10f;
    /**
     * The <em>minimum</em> wait after joining the world, before the first
     * scenario. Terrain streaming, world generation and JIT all happen in here.
     *
     * <p>A minimum rather than the whole answer, because a fixed wait cannot
     * know when the world has finished arriving. This was 45 seconds and that
     * was not enough: measured at 1920x1080 and render distance 12, the first
     * scenario after a 45-second wait reported a 125 ms worst frame, a 47 fps
     * 1% low and a frame-time coefficient of variation of 1.175 -- all of it
     * chunk generation still in flight. The same scenario after waiting for
     * quiet reported a 5.4 ms worst frame, a 246 fps 1% low and a CoV of 0.130,
     * and the mean frame rate of the panning scenario doubled.
     *
     * <p>That is the anomaly {@code docs/performance/gameplay-optimization-2026-09.md}
     * records as "the same still view measured 844 fps in one scenario and 1468
     * fps in another in the same run". It was not machine noise.
     */
    private static final float JOIN_SETTLE_SECONDS = floatProperty("fathom.perfBench.joinSettle", 45f);

    /**
     * How long the frame rate must stay free of hitches before measuring.
     *
     * <p>This is what actually decides the run is ready. A frame counts as a
     * hitch when it takes more than {@link #HITCH_MULTIPLE} times the recent
     * average or more than {@link #HITCH_FLOOR_MILLIS}, whichever is larger --
     * the floor so that a machine holding a steady 1 ms frame does not flag
     * ordinary jitter, the multiple so that a slower machine is not held to an
     * absolute number it can never meet.
     */
    private static final float JOIN_QUIET_SECONDS = floatProperty("fathom.perfBench.joinQuiet", 10f);

    /** Give up waiting for quiet and measure anyway, saying so. */
    private static final float JOIN_SETTLE_MAX_SECONDS =
            floatProperty("fathom.perfBench.joinSettleMax", 300f);

    /**
     * Quiet required before a scenario's own measurement window opens.
     *
     * <p>Shorter than the join requirement because the world is already loaded
     * by then; this is about the tail of whatever the previous scenario did.
     */
    private static final float SCENARIO_QUIET_SECONDS =
            floatProperty("fathom.perfBench.scenarioQuiet", 3f);

    /** How long to wait for that quiet before measuring anyway. */
    private static final float SCENARIO_QUIET_CEILING_SECONDS =
            floatProperty("fathom.perfBench.scenarioQuietCeiling", 20f);

    private static final double HITCH_MULTIPLE = 4d;
    private static final double HITCH_FLOOR_MILLIS = 16d;

    /** Rolling mean frame time while waiting for the world to settle. */
    private static double settleMeanNanos;
    /** When the last hitch was seen, so quiet can be measured from it. */
    private static long lastHitchNanos;
    /**
     * When the current scenario's measurement window opened.
     *
     * <p>The window used to be timed from the scenario's start, which was fine
     * while measuring began at a fixed offset. Now that it waits for quiet, a
     * scenario that took a few seconds to settle would otherwise have those
     * seconds taken out of its measurement rather than added to its wait.
     */
    private static long measureStartNanos;

    private enum Scenario {
        HUD_OFF("e-hud-off", "standing still with Fathom HUD modules off"),
        STILL("a-still", "standing still with the configured HUD set"),
        HUD("g-hud", "every HUD module on, standing still"),
        PAN("b-pan", "panning across terrain at 45 deg/s"),
        GROUND("c-ground", "looking straight down"),
        PARTICLES("h-particles", "torch-style flame and smoke particles in view, standing still"),
        HUB("f-hub", "Fathom Hub open over the world"),
        // Last on purpose. Flying 12 blocks/s for the length of the scenario
        // leaves a long tail of terrain still arriving, and whichever scenario
        // ran next inherited it -- the particles scene reported a 42 ms worst
        // frame standing perfectly still, which was this. The per-scenario
        // quiet gate shortens that tail but cannot remove it, because it only
        // guarantees a quiet start and terrain can arrive mid-window. Running
        // the one moving scenario last removes the path entirely. The ids are
        // unchanged, so reports and comparisons are unaffected.
        MOVE("d-move", "flying through terrain at 12 blocks/s");

        final String id;
        final String description;

        Scenario(String id, String description) {
            this.id = id;
            this.description = description;
        }
    }

    private enum SoakPhase {
        MENU("menu", "main menu"),
        HUB("hub", "Hub over the main menu"),
        SCROLL("scroll", "Hub module list scrolling continuously"),
        SETTINGS("settings", "Hub with full-page module settings open"),
        EDITOR("editor", "HUD editor over the main menu"),
        DRAG("drag", "HUD editor while a module is dragged and snapped");

        final String id;
        final String description;

        SoakPhase(String id, String description) {
            this.id = id;
            this.description = description;
        }
    }

    private static long startedNanos;
    private static long phaseStartNanos;
    private static int frames;
    private static boolean worldRequested;
    private static boolean joined;
    private static int index = -1;
    private static boolean measuring;
    private static double startX;
    private static double startY;
    private static double startZ;
    private static float startYaw;
    private static final List<FathomModule> hudForced = new ArrayList<FathomModule>();
    private static final List<FathomModule> hudSuppressed = new ArrayList<FathomModule>();
    private static final List<String> summary = new ArrayList<String>();

    // Soak state.
    private static int windowFrames;
    private static long windowStartNanos;
    private static final List<String> windows = new ArrayList<String>();
    private static long soakHeapAtStart;

    private PerfBench() {}

    /** Whether the unfocused cap should stand aside for a benchmark run. */
    public static boolean suppressUnfocusedCap() {
        // The view benchmark measures with the window unfocused when the user
        // is working in another window; a 30 fps cap would void every frame.
        return ACTIVE || net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED;
    }

    /** Called once per frame, after the frame has been timed. */
    public static void frame(MinecraftClient client, long frameNanos) {
        if (!ACTIVE || client == null) {
            return;
        }
        long now = System.nanoTime();
        if (startedNanos == 0L) {
            startedNanos = now;
            if (client.options != null) {
                client.options.pauseOnLostFocus = false;
            }
            // A/B control: `-Dfathom.perfBench.off=a.b,c.d` and `.on=` set
            // optimization switches for this run only. Not saved.
            applySwitches("fathom.perfBench.off", false);
            applySwitches("fathom.perfBench.on", true);
        }
        frames++;
        if ("soak".equals(MODE)) {
            soak(client, now);
        } else if ("ab".equals(MODE)) {
            ab(client, now, frameNanos);
        } else {
            world(client, now, frameNanos);
        }
    }

    // -----------------------------------------------------------------------
    // Interleaved A/B: the same scene, switches alternating in ABBA windows
    // -----------------------------------------------------------------------

    /**
     * Scenario order, overridable with {@code -Dfathom.perfBench.abScenarios=a-still,c-ground}
     * (repeats allowed). Each entry alternates switches in ABBA order, three
     * cycles; {@code -Dfathom.perfBench.abReverse=true} makes every other entry
     * BAAB, so an effect that follows window position rather than the switch shows up.
     */
    private static final Scenario[] AB_SCENARIOS = abScenarios();
    private static final boolean AB_REVERSE = Boolean.getBoolean("fathom.perfBench.abReverse");
    /** A B B A, three times: order effects and drift cancel within a scenario. */
    private static final boolean[] AB_ORDER = {
        false, true, true, false, false, true, true, false, false, true, true, false
    };

    private static Scenario[] abScenarios() {
        String list = System.getProperty("fathom.perfBench.abScenarios", "").trim();
        if (list.isEmpty()) {
            return new Scenario[] {Scenario.STILL, Scenario.PAN, Scenario.GROUND, Scenario.PARTICLES, Scenario.MOVE};
        }
        List<Scenario> out = new ArrayList<Scenario>();
        for (String id : list.split(",")) {
            for (Scenario scenario : Scenario.values()) {
                if (scenario.id.equals(id.trim())) {
                    out.add(scenario);
                }
            }
        }
        return out.toArray(new Scenario[0]);
    }

    /** Whether window {@code window} of scenario entry {@code entry} has the switches on. */
    private static boolean abOn(int entry, int window) {
        boolean on = AB_ORDER[window];
        return AB_REVERSE && entry % 2 == 1 ? !on : on;
    }
    private static final float AB_WINDOW_SECONDS = floatProperty("fathom.perfBench.window", 5f);
    private static final float AB_WINDOW_SETTLE = 1f;

    private static int abWindow;
    private static final long[][] abFrames = new long[2][];
    private static final int[] abCounts = new int[2];
    private static final long[] abAllocated = new long[2];
    private static final long[] abGcCount = new long[2];
    private static final long[] abGcMillis = new long[2];
    private static long windowAllocStart;
    private static long windowGcCountStart;
    private static long windowGcMillisStart;
    /** Created on first use: this class is loaded in every client for its ACTIVE flag. */
    private static net.fathom.optimizations.core.instrument.MemorySampler allocation;

    private static long allocatedBytes() {
        if (allocation == null) {
            allocation = new net.fathom.optimizations.core.instrument.MemorySampler();
        }
        return allocation.sample().allocatedBytesSinceStart();
    }

    private static void ab(MinecraftClient client, long now, long frameNanos) {
        if (!joinWorld(client, now)) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }
        float elapsed = (now - phaseStartNanos) / 1_000_000_000f;
        if (index < 0) {
            hold(player, 0f, 0f);
            if (elapsed >= JOIN_SETTLE_SECONDS) {
                abScenario(0, now);
            }
            return;
        }
        Scenario scenario = AB_SCENARIOS[index];
        boolean on = abOn(index, abWindow);
        // Every window replays the same motion from the same start, so A and B
        // windows see the same scene.
        drive(client, player, scenario, elapsed);

        if (!measuring) {
            if (elapsed >= AB_WINDOW_SETTLE) {
                measuring = true;
                windowAllocStart = allocatedBytes();
                long[] gc = gcTotals();
                windowGcCountStart = gc[0];
                windowGcMillisStart = gc[1];
            }
            return;
        }
        int condition = on ? 1 : 0;
        record(condition, frameNanos);
        if (elapsed >= AB_WINDOW_SETTLE + AB_WINDOW_SECONDS) {
            measuring = false;
            abAllocated[condition] += allocatedBytes() - windowAllocStart;
            long[] gc = gcTotals();
            abGcCount[condition] += gc[0] - windowGcCountStart;
            abGcMillis[condition] += gc[1] - windowGcMillisStart;
            end(client, scenario);
            abWindow++;
            if (abWindow < AB_ORDER.length) {
                setAbSwitches(abOn(index, abWindow));
                phaseStartNanos = now;
                return;
            }
            abReport(scenario);
            if (index + 1 < AB_SCENARIOS.length) {
                abScenario(index + 1, now);
            } else {
                setAbSwitches(true);
                finish(client);
            }
        }
    }

    private static void abScenario(int next, long now) {
        index = next;
        abWindow = 0;
        phaseStartNanos = now;
        for (int condition = 0; condition < 2; condition++) {
            abFrames[condition] = new long[4096];
            abCounts[condition] = 0;
            abAllocated[condition] = 0L;
            abGcCount[condition] = 0L;
            abGcMillis[condition] = 0L;
        }
        setAbSwitches(abOn(next, 0));
        System.out.println("[Fathom] Bench A/B: " + AB_SCENARIOS[next].id + (AB_REVERSE && next % 2 == 1 ? " (BAAB)" : " (ABBA)"));
    }

    private static void record(int condition, long frameNanos) {
        long[] log = abFrames[condition];
        if (abCounts[condition] == log.length) {
            log = java.util.Arrays.copyOf(log, log.length * 2);
            abFrames[condition] = log;
        }
        log[abCounts[condition]++] = frameNanos;
    }

    private static void setAbSwitches(boolean on) {
        String list = System.getProperty("fathom.perfBench.ab", "").trim();
        if (list.isEmpty() || Runtime.switches() == null) {
            return;
        }
        for (String id : list.split(",")) {
            if (!id.trim().isEmpty()) {
                Runtime.switches().setByUser(
                        net.fathom.optimizations.core.registry.OptimizationId.of(id.trim()), on);
            }
        }
        Runtime.refreshHotSwitches();
    }

    private static long[] gcTotals() {
        long count = 0L;
        long millis = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0L, bean.getCollectionCount());
            millis += Math.max(0L, bean.getCollectionTime());
        }
        return new long[] {count, millis};
    }

    private static void abReport(Scenario scenario) {
        String[] names = {"off", "on "};
        double[] mean = new double[2];
        for (int condition = 0; condition < 2; condition++) {
            net.fathom.optimizations.core.instrument.FrameStatistics stats =
                    net.fathom.optimizations.core.instrument.FrameStatistics.of(
                            java.util.Arrays.copyOf(abFrames[condition], abCounts[condition]));
            mean[condition] = stats.meanFrameTimeMillis();
            double seconds = stats.totalSeconds();
            String line = String.format(Locale.ROOT,
                    "[Fathom] Bench A/B %s %s: %d frames avg %.1f fps | mean %.4f ms p50 %.4f p95 %.4f p99 %.4f"
                            + " | 1%% low %.1f 0.1%% low %.1f | alloc %.1f MB/s | GC %d (%d ms)",
                    scenario.id, names[condition], stats.sampleCount(), stats.averageFps(),
                    stats.meanFrameTimeMillis(), stats.medianFrameTimeMillis(), stats.p95FrameTimeMillis(),
                    stats.p99FrameTimeMillis(), stats.onePercentLowFps(), stats.pointOnePercentLowFps(),
                    seconds <= 0 ? 0 : abAllocated[condition] / seconds / (1024.0 * 1024.0),
                    abGcCount[condition], abGcMillis[condition]);
            System.out.println(line);
            summary.add(line);
        }
        MinecraftClient client = MinecraftClient.getInstance();
        String particles = client == null || client.particleManager == null
                ? "?" : client.particleManager.getDebugString();
        String delta = String.format(Locale.ROOT,
                "[Fathom] Bench A/B %s: mean frame time %+.2f%% with switches on (particles live at end: %s)",
                scenario.id, mean[0] <= 0 ? 0 : (mean[1] - mean[0]) / mean[0] * 100.0, particles);
        System.out.println(delta);
        summary.add(delta);
    }

    /** Load the development world and remember where the player started. */
    private static boolean joinWorld(MinecraftClient client, long now) {
        if (joined) {
            return true;
        }
        if (client.world == null) {
            if (!worldRequested && frames > 60 && client.currentScreen != null) {
                worldRequested = true;
                System.out.println("[Fathom] Bench: loading development world.");
                client.startIntegratedServer("New World", "New World", null);
            }
            return false;
        }
        if (client.player == null) {
            return false;
        }
        joined = true;
        ClientPlayerEntity player = client.player;
        startX = player.x;
        startY = player.y;
        startZ = player.z;
        startYaw = player.yaw;
        phaseStartNanos = now;
        return false;
    }

    // -----------------------------------------------------------------------
    // Gameplay scenarios
    // -----------------------------------------------------------------------

    private static void world(MinecraftClient client, long now, long frameNanos) {
        if (!joinWorld(client, now)) {
            return;
        }
        ClientPlayerEntity player = client.player;
        if (player == null) {
            return;
        }

        float elapsed = (now - phaseStartNanos) / 1_000_000_000f;
        if (index < 0) {
            hold(player, 0f, 0f);
            if (settled(now, frameNanos, elapsed)) {
                begin(client, 0, now);
            }
            return;
        }

        Scenario scenario = Scenario.values()[index];
        drive(client, player, scenario, elapsed);

        // The same question the join gate asks, asked again per scenario. One
        // settled world at the start of a run does not stay settled: a scenario
        // that moves the player leaves terrain arriving behind it, and the next
        // scenario measured that rather than itself. Looking-down measured a
        // 75 ms worst frame this way, in a scene whose whole point is that it
        // draws almost nothing.
        float quiet = quietSeconds(now, frameNanos);
        if (!measuring
                && elapsed >= SETTLE_SECONDS
                && (quiet >= SCENARIO_QUIET_SECONDS
                        || elapsed >= SETTLE_SECONDS + SCENARIO_QUIET_CEILING_SECONDS
                        || !needsQuiet(scenario))) {
            measuring = true;
            measureStartNanos = now;
            Session session = Runtime.beginSessionFor("bench-" + scenario.id, 0);
            session.scenario(scenario.id).configuration(Capture.configuration())
                    .environment(Capture.environment(client));
        } else if (measuring
                && (now - measureStartNanos) / 1_000_000_000f >= MEASURE_SECONDS) {
            measuring = false;
            record(client, scenario.id, scenario.description, Runtime.endSession());
            end(client, scenario);
            if (index + 1 < Scenario.values().length) {
                begin(client, index + 1, now);
            } else {
                finish(client);
            }
        }
    }

    /**
     * Whether the world has stopped arriving and measuring can start.
     *
     * <p>Three conditions, in order of who they protect:
     *
     * <ol>
     *   <li>{@link #JOIN_SETTLE_SECONDS} must have passed. A floor, so a world
     *       that happens to look quiet for ten seconds while a distant region
     *       is still generating does not start a run early.
     *   <li>No hitch for {@link #JOIN_QUIET_SECONDS}. This is the real test:
     *       chunk generation shows up as frames an order of magnitude longer
     *       than their neighbours, and a run started while they are still
     *       happening measures world generation rather than rendering.
     *   <li>{@link #JOIN_SETTLE_MAX_SECONDS} ends the wait regardless, with a
     *       line in the log. A benchmark that silently waits forever on a world
     *       that never goes quiet is worse than one that measures and says the
     *       measurement is suspect.
     * </ol>
     */
    /**
     * Feed one frame to the hitch tracker and report how long it has been quiet.
     *
     * <p>Shared by the join gate and the per-scenario gate, because they are the
     * same question asked at two moments: has the world stopped arriving?
     */
    private static float quietSeconds(long now, long frameNanos) {
        if (frameNanos > 0L) {
            if (settleMeanNanos == 0d) {
                settleMeanNanos = frameNanos;
            }
            double hitchAbove = Math.max(settleMeanNanos * HITCH_MULTIPLE, HITCH_FLOOR_MILLIS * 1_000_000d);
            if (frameNanos > hitchAbove) {
                lastHitchNanos = now;
            } else {
                // Only quiet frames move the average, so one 120 ms hitch does
                // not raise the bar it is being judged against.
                settleMeanNanos += (frameNanos - settleMeanNanos) * 0.05d;
            }
        }
        if (lastHitchNanos == 0L) {
            lastHitchNanos = now;
        }
        return (now - lastHitchNanos) / 1_000_000_000f;
    }

    /**
     * Whether this scenario should wait for a quiet world before measuring.
     *
     * <p>False for {@link Scenario#MOVE} alone, and that is not an oversight:
     * flying through terrain loads chunks continuously, so it never goes quiet,
     * and the chunk loading <em>is</em> the workload being measured. Requiring
     * quiet there would wait out the ceiling on every run and then measure the
     * same thing anyway. Every other scenario holds position, so a world that
     * will not settle is a world still finishing the previous scenario's work.
     */
    private static boolean needsQuiet(Scenario scenario) {
        return scenario != Scenario.MOVE;
    }

    private static boolean settled(long now, long frameNanos, float elapsed) {
        float quiet = quietSeconds(now, frameNanos);

        if (elapsed >= JOIN_SETTLE_MAX_SECONDS) {
            System.out.println(String.format(java.util.Locale.ROOT,
                    "[Fathom] Bench: world never went quiet in %.0f s; measuring anyway. "
                            + "Treat the first scenario's 1%% low and worst frame as suspect.",
                    elapsed));
            return true;
        }
        if (elapsed < JOIN_SETTLE_SECONDS) {
            return false;
        }
        if (quiet < JOIN_QUIET_SECONDS) {
            return false;
        }
        System.out.println(String.format(java.util.Locale.ROOT,
                "[Fathom] Bench: world settled after %.0f s (%.0f s without a hitch, "
                        + "steady frame %.2f ms).",
                elapsed, quiet, settleMeanNanos / 1_000_000d));
        return true;
    }

    private static void begin(MinecraftClient client, int next, long now) {
        index = next;
        phaseStartNanos = now;
        Scenario scenario = Scenario.values()[next];
        System.out.println("[Fathom] Bench: " + scenario.id + " (" + scenario.description + ")");
        if (scenario == Scenario.HUD_OFF) {
            for (FathomModule module : Runtime.features().all()) {
                if (!module.widgets().isEmpty() && module.enabled() && module.switchable()) {
                    module.setEnabled(false);
                    hudSuppressed.add(module);
                }
            }
        } else if (scenario == Scenario.HUD) {
            for (FathomModule module : Runtime.features().all()) {
                if (!module.widgets().isEmpty() && !module.enabled() && module.switchable()) {
                    module.setEnabled(true);
                    hudForced.add(module);
                }
            }
        } else if (scenario == Scenario.HUB) {
            Runtime.features().seal();
            FathomNavigation.openHub(client, Runtime.hud());
        }
    }

    private static void end(MinecraftClient client, Scenario scenario) {
        if (scenario == Scenario.HUD_OFF) {
            for (FathomModule module : hudSuppressed) {
                module.setEnabled(true);
            }
            hudSuppressed.clear();
        } else if (scenario == Scenario.HUD) {
            for (FathomModule module : hudForced) {
                module.setEnabled(false);
            }
            hudForced.clear();
        }
        ClientPlayerEntity player = client.player;
        if (player != null) {
            player.noClip = false;
            player.abilities.flying = false;
            player.updatePositionAndAngles(startX, startY, startZ, startYaw, 0f);
        }
    }

    private static void drive(MinecraftClient client, ClientPlayerEntity player, Scenario scenario, float t) {
        switch (scenario) {
            case PAN:
                hold(player, t * 45f, 0f);
                break;
            case GROUND:
                hold(player, 0f, 90f);
                break;
            case MOVE: {
                player.noClip = true;
                player.abilities.allowFlying = true;
                player.abilities.flying = true;
                player.velocityX = 0;
                player.velocityY = 0;
                player.velocityZ = 0;
                double distance = 12.0 * t;
                double radians = Math.toRadians(startYaw);
                double x = startX - Math.sin(radians) * distance;
                double z = startZ + Math.cos(radians) * distance;
                player.updatePositionAndAngles(x, startY + 12, z, startYaw, 15f);
                player.prevYaw = startYaw;
                player.prevPitch = 15f;
                break;
            }
            case PARTICLES:
                hold(player, 0f, 20f);
                particles(client, player);
                break;
            default:
                hold(player, 0f, 0f);
                break;
        }
    }

    private static long particlesSpawnedAt;
    private static long particlesReportedAt;

    /**
     * A steady field of vanilla flame and smoke particles in front of the camera,
     * the pair a torch emits. Deterministic positions, a fixed spawn rate on the
     * wall clock, so every run and every window sees the same load.
     */
    private static void particles(MinecraftClient client, ClientPlayerEntity player) {
        if (client.world == null) {
            return;
        }
        long now = System.nanoTime();
        if (now - particlesSpawnedAt < 16_000_000L) {
            return;
        }
        particlesSpawnedAt = now;
        if (now - particlesReportedAt > 3_000_000_000L) {
            particlesReportedAt = now;
            System.out.println("[Fathom] Bench: " + client.particleManager.getDebugString() + " particles live");
        }
        double radians = Math.toRadians(startYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        double sideX = forwardZ;
        double sideZ = -forwardX;
        for (int column = 0; column < 12; column++) {
            for (int row = 0; row < 3; row++) {
                double distance = 6 + row * 3;
                double offset = (column - 5.5) * 1.5;
                double x = startX + forwardX * distance + sideX * offset;
                double z = startZ + forwardZ * distance + sideZ * offset;
                double y = startY + 0.5 + (column % 3) * 0.4;
                client.world.addParticle(net.minecraft.client.particle.ParticleType.FIRE, x, y, z, 0, 0, 0);
                client.world.addParticle(net.minecraft.client.particle.ParticleType.SMOKE, x, y + 0.2, z, 0, 0, 0);
            }
        }
    }

    /** Pin the camera; {@code yawOffset} is relative to the yaw the player joined with. */
    private static void hold(ClientPlayerEntity player, float yawOffset, float pitch) {
        float yaw = startYaw + yawOffset;
        player.yaw = yaw;
        player.prevYaw = yaw;
        player.pitch = pitch;
        player.prevPitch = pitch;
        player.velocityX = 0;
        player.velocityZ = 0;
    }

    // -----------------------------------------------------------------------
    // Soak: does anything degrade while a screen stays open?
    // -----------------------------------------------------------------------

    private static void soak(MinecraftClient client, long now) {
        if (index < 0) {
            // The main menu has to be up, and the poster decoded, before phase one.
            if (frames < 180 || client.currentScreen == null) {
                return;
            }
            soakBegin(client, 0, now);
            return;
        }
        SoakPhase phase = SoakPhase.values()[index];
        float elapsed = (now - phaseStartNanos) / 1_000_000_000f;

        if (!measuring && elapsed >= SETTLE_SECONDS) {
            measuring = true;
            soakHeapAtStart = settledHeap();
            Runtime.beginSessionFor("soak-" + phase.id, 0).scenario("soak-" + phase.id)
                    .environment(Capture.environment(client));
            windowFrames = 0;
            windowStartNanos = System.nanoTime();
            windows.clear();
            return;
        }
        if (!measuring) {
            return;
        }
        windowFrames++;
        float windowElapsed = (now - windowStartNanos) / 1_000_000_000f;
        if (windowElapsed >= SOAK_WINDOW_SECONDS) {
            windows.add(String.format(Locale.ROOT, "%.0f", windowFrames / windowElapsed));
            windowFrames = 0;
            windowStartNanos = now;
        }
        if (elapsed >= SETTLE_SECONDS + SOAK_SECONDS) {
            measuring = false;
            Report report = Runtime.endSession();
            long heapAtEnd = settledHeap();
            record(client, "soak-" + phase.id, phase.description, report);
            String line = String.format(Locale.ROOT,
                    "[Fathom] Bench soak-%s: fps per %.0fs window %s; heap after GC %d MB -> %d MB",
                    phase.id, SOAK_WINDOW_SECONDS, windows,
                    soakHeapAtStart / (1024 * 1024), heapAtEnd / (1024 * 1024));
            System.out.println(line);
            summary.add(line);
            if (index + 1 < SoakPhase.values().length) {
                soakBegin(client, index + 1, System.nanoTime());
            } else {
                finish(client);
            }
        }
    }

    private static void soakBegin(MinecraftClient client, int next, long now) {
        index = next;
        phaseStartNanos = now;
        SoakPhase phase = SoakPhase.values()[next];
        System.out.println("[Fathom] Bench: soak-" + phase.id + " (" + phase.description + ")");
        // Every phase starts from the menu so screens do not stack.
        if (client.currentScreen instanceof net.fathom.optimizations.fabric.hub.FathomHubScreen
                || client.currentScreen instanceof HudEditorScreen) {
            client.setScreen(null);
        }
        Runtime.features().seal();
        switch (phase) {
            case HUB:
            case SCROLL:
                FathomNavigation.openHub(client, Runtime.hud());
                break;
            case SETTINGS:
                System.setProperty("fathom.qaOpenSettings", "true");
                FathomNavigation.openHub(client, Runtime.hud());
                break;
            case EDITOR:
            case DRAG:
                System.setProperty("fathom.qaOpenSettings", "false");
                FathomNavigation.push(client, new HudEditorScreen(Runtime.hud()));
                break;
            default:
                break;
        }
    }

    /**
     * Synthetic wheel input for the scrolling phase: one notch every 90 ms,
     * reversing every eleven, so the list glides through its whole length and
     * back for the whole phase. Zero at any other time, and {@link #ACTIVE}
     * folds the caller away when no benchmark runs.
     */
    public static int benchWheelNotches() {
        if (!"soak".equals(MODE) || index < 0 || SoakPhase.values()[index] != SoakPhase.SCROLL) {
            return 0;
        }
        long step = (System.nanoTime() - phaseStartNanos) / 90_000_000L;
        if (step == lastWheelStep) {
            return 0;
        }
        lastWheelStep = step;
        return (step / 11) % 2 == 0 ? -1 : 1;
    }

    /** Whether the editor should drive its real drag/snap path for this soak phase. */
    public static boolean benchHudDrag() {
        return "soak".equals(MODE) && index >= 0 && SoakPhase.values()[index] == SoakPhase.DRAG;
    }

    private static long lastWheelStep = -1L;

    /** Heap in use after a full collection: the figure a leak moves. Outside measurement. */
    private static long settledHeap() {
        System.gc();
        return ManagementFactory.getMemoryMXBean().getHeapMemoryUsage().getUsed();
    }

    // -----------------------------------------------------------------------
    // Output
    // -----------------------------------------------------------------------

    private static void record(MinecraftClient client, String id, String description, Report report) {
        if (report == null) {
            return;
        }
        net.fathom.optimizations.core.instrument.FrameStatistics stats = report.frames();
        StringBuilder sections = new StringBuilder();
        for (Map.Entry<String, Double> entry : report.sections().entrySet()) {
            if (sections.length() > 0) {
                sections.append(", ");
            }
            sections.append(entry.getKey()).append(' ')
                    .append(String.format(Locale.ROOT, "%.3f", entry.getValue()));
        }
        long gcCount = 0L;
        long gcMillis = 0L;
        for (GcLog.Delta delta : report.gc().values()) {
            gcCount += delta.collections();
            gcMillis += delta.totalPauseMillis();
        }
        String line = String.format(Locale.ROOT,
                "[Fathom] Bench %s: avg %.1f fps | frame mean %.3f ms p50 %.3f p95 %.3f p99 %.3f max %.2f"
                        + " | 1%% low %.1f fps 0.1%% low %.1f fps | CoV %.3f | Fathom ms/frame: %s (worst %.2f)"
                        + " | heap mean %d MB peak %d MB | alloc %.1f MB/s | GC %d (%d ms)",
                id, stats.averageFps(), stats.meanFrameTimeMillis(), stats.medianFrameTimeMillis(),
                stats.p95FrameTimeMillis(), stats.p99FrameTimeMillis(), stats.maxFrameTimeMillis(),
                stats.onePercentLowFps(), stats.pointOnePercentLowFps(),
                stats.frameTimeCoefficientOfVariation(), sections, report.worstFathomFrameMillis(),
                report.meanHeapUsedBytes() / (1024 * 1024), report.peakHeapUsedBytes() / (1024 * 1024),
                report.allocationBytesPerSecond() / (1024.0 * 1024.0), gcCount, gcMillis);
        System.out.println(line);
        summary.add(line);
        write(client, id, report);
    }

    private static void write(MinecraftClient client, String id, Report report) {
        String label = System.getProperty("fathom.perfBench.label", "run").replaceAll("[^A-Za-z0-9._-]", "_");
        File directory = new File(client.runDirectory, "fathom-bench");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            return;
        }
        File out = new File(directory, label + "-" + id + ".json");
        try (Writer writer = new OutputStreamWriter(new FileOutputStream(out), StandardCharsets.UTF_8)) {
            writer.write(report.toJson());
        } catch (IOException failure) {
            System.err.println("[Fathom] Bench: could not write " + out + ": " + failure);
        }
    }

    private static void finish(MinecraftClient client) {
        // What a settings save costs the frame it happens in: the Hub and the
        // HUD editor save on the render thread when they close or on edits.
        // The live profile is rewritten with its own unchanged contents.
        if (Runtime.hud() != null) {
            for (int attempt = 0; attempt < 5; attempt++) {
                Runtime.hud().saveAll();
            }
        }
        System.out.println("[Fathom] Bench complete (" + summary.size() + " results).");
        client.scheduleStop();
    }

    private static void applySwitches(String property, boolean on) {
        String list = System.getProperty(property, "").trim();
        if (list.isEmpty() || Runtime.switches() == null) {
            return;
        }
        for (String id : list.split(",")) {
            if (id.trim().isEmpty()) {
                continue;
            }
            Runtime.switches().setByUser(
                    net.fathom.optimizations.core.registry.OptimizationId.of(id.trim()), on);
            System.out.println("[Fathom] Bench: " + id.trim() + (on ? " on" : " off") + " for this run.");
        }
        Runtime.refreshHotSwitches();
    }

    private static float floatProperty(String name, float fallback) {
        try {
            return Float.parseFloat(System.getProperty(name, String.valueOf(fallback)));
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
