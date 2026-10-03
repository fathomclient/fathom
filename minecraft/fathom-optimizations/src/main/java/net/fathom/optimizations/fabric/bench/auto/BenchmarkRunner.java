package net.fathom.optimizations.fabric.bench.auto;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.ConnectScreen;

import net.fathom.optimizations.core.bench.auto.BenchmarkRegistry;

/**
 * The entry point the frame hook calls.
 *
 * <h2>Why this is separate from the manager</h2>
 *
 * <p>So the frame hook can be one static call with no null handling and no
 * knowledge of when the benchmark starts. The manager needs a world to exist
 * before it can build a registry -- the module list is not final until the
 * client has initialised -- and this defers that until the first frame, which
 * is the first moment everything it reads is ready.
 */
public final class BenchmarkRunner {

    private static BenchmarkManager manager;
    private static CoreBenchmarkManager coreManager;
    private static PairedCoreBenchmarkManager pairedManager;
    private static boolean started;
    private static boolean announced;
    private static boolean autoJoinRequested;
    private static int autoJoinWaitFrames;

    private BenchmarkRunner() {}

    /** Called once per rendered frame while {@link BenchmarkState#ACTIVE}. */
    public static void frame(long frameNanos) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null) {
            return;
        }
        maybeAutoJoin(client);
        maybeTakeFocus(client);
        if (BenchmarkState.VIEW) {
            // Its own driver: it loads its world, owns its scenes and arms, and
            // never uses the subsystem registry the other modes measure.
            net.fathom.optimizations.fabric.bench.view.ViewBenchmark.frame(client);
            return;
        }
        if (!started) {
            if (!announce(client)) {
                return;
            }
            BenchmarkRegistry registry = FathomSubsystems.build();
            if (BenchmarkState.CORE_PAIRED) {
                pairedManager = new PairedCoreBenchmarkManager(registry);
            } else if (BenchmarkState.PERF_PAIRED) {
                pairedManager = new PairedCoreBenchmarkManager(
                        registry, PairedCoreBenchmarkManager.Mode.PERF);
            } else if (BenchmarkState.CORE) {
                coreManager = new CoreBenchmarkManager(registry);
            } else {
                manager = new BenchmarkManager(registry, BenchmarkManager.plan(registry));
            }
            started = true;
            int count = pairedManager != null ? pairedManager.epochCount()
                    : BenchmarkState.CORE ? coreManager.passCount() : manager.passCount();
            System.out.println("[FATHOM BENCH] " + registry.measurable().size()
                    + " measurable subsystems, " + count
                    + (pairedManager != null ? " paired epochs queued." : " passes queued."));
        }
        if (pairedManager != null && !pairedManager.finished()) {
            pairedManager.frame(client, frameNanos);
        } else if (coreManager != null && !coreManager.finished()) {
            coreManager.frame(client, frameNanos);
        } else if (manager != null && !manager.finished()) {
            manager.frame(client, frameNanos);
        }
    }

    /**
     * Take the game's own focus back when the window has it but the game does not.
     *
     * <p>An unattended run is started from a terminal, so the window comes up
     * behind whatever launched it and Minecraft releases the mouse. That is not
     * a benchmark of anything: the unfocused frame cap pins every frame at
     * 33 ms and every pair is thrown away for losing focus. {@code grabMouse}
     * is what the game itself calls when you click back into it, and it does
     * nothing unless the window is already active, so it cannot steal focus
     * from whatever the person is actually doing.
     *
     * <p>Benchmark mode only, and only while a run is waiting for frames.
     */
    private static void maybeTakeFocus(MinecraftClient client) {
        if (client.world == null || client.focused || client.currentScreen != null) {
            return;
        }
        if (++focusAttempts > 200 || !org.lwjgl.opengl.Display.isActive()) {
            return;
        }
        client.grabMouse();
    }

    private static int focusAttempts;

    /**
     * Optional automation for unattended diagnostic runs. This is deliberately
     * property-gated and absent from ordinary launches.
     */
    private static void maybeAutoJoin(MinecraftClient client) {
        if ((!BenchmarkState.CORE && !BenchmarkState.CORE_PAIRED && !BenchmarkState.PERF_PAIRED)
                || autoJoinRequested || client.world != null) {
            return;
        }
        String server = System.getProperty("fathom.benchmark.server", "").trim();
        if (server.length() == 0 || client.currentScreen == null || ++autoJoinWaitFrames <= 60) {
            return;
        }
        int port = Integer.getInteger("fathom.benchmark.port", 25565).intValue();
        autoJoinRequested = true;
        System.out.println("[FATHOM BENCH] connecting to benchmark server " + server + ':' + port);
        client.setScreen(new ConnectScreen(client.currentScreen, client, server, port));
    }

    /**
     * Wait for a world, and say so once.
     *
     * <p>A benchmark started at the main menu would otherwise look hung: the
     * passes cannot begin without a scene, and nothing on the title screen says
     * the client is waiting for one.
     */
    private static boolean announce(MinecraftClient client) {
        if (client.world == null || client.player == null) {
            if (!announced) {
                announced = true;
                System.out.println("[FATHOM BENCH] benchmark mode armed; waiting for a world. "
                        + "Join a server or open a save and it will start automatically.");
            }
            return false;
        }
        return true;
    }

    /** Whether a run is in progress, for anything that wants to stay out of the way. */
    public static boolean running() {
        if (BenchmarkState.VIEW) {
            return !net.fathom.optimizations.fabric.bench.view.ViewBenchmark.finished();
        }
        return BenchmarkState.ACTIVE
                && ((pairedManager != null && !pairedManager.finished())
                || (coreManager != null && !coreManager.finished())
                || (manager != null && !manager.finished()));
    }

    public static String status() {
        if (manager == null && coreManager == null && pairedManager == null) {
            return "waiting for a world";
        }
        if (pairedManager != null) {
            if (pairedManager.finished()) return "complete";
            return pairedManager.status();
        }
        if (coreManager != null) {
            if (coreManager.finished()) return "complete";
            return "pass " + coreManager.currentPass() + "/" + coreManager.passCount()
                    + " (" + coreManager.currentLabel() + ") " + coreManager.phaseLabel();
        }
        if (manager.finished()) {
            return "complete";
        }
        return "pass " + manager.currentPass() + "/" + manager.passCount()
                + " (" + manager.currentLabel() + ") " + manager.phaseLabel();
    }
}
