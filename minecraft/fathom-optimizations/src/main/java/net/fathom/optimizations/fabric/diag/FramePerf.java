package net.fathom.optimizations.fabric.diag;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.instrument.Probe;

/**
 * Where a frame goes, aggregated and printed every few seconds.
 *
 * <h2>Why aggregated and not per frame</h2>
 *
 * <p>Because a line per frame at a thousand frames a second is a thousand string
 * concatenations and a thousand writes to a file the game is also rendering
 * from, which is a measurement that creates the problem it is looking for. This
 * accumulates into primitive fields -- no allocation, no formatting -- and pays
 * for a line once per window.
 *
 * <h2>What it measures, and what it cannot</h2>
 *
 * <p>The total is the whole render loop, timed by the frame Mixin. The named
 * sections are Fathom's own, from {@link Probe}. Everything the total has that
 * the sections do not is vanilla plus OptiFine plus the driver, reported as
 * {@code rest} rather than attributed to something this code cannot see. A
 * profiler that guesses at the attribution of its own blind spot is worse than
 * one that names it.
 *
 * <h2>Why the first window is discarded</h2>
 *
 * <p>The first seconds of a session are class loading, shader compilation, atlas
 * uploads and terrain generation. Averaging those into "what a frame costs"
 * produces a number that describes start-up and is then quoted as though it
 * described play.
 */
public final class FramePerf {

    /** How long a reporting window is. */
    private static final long WINDOW_NANOS = 5L * 1_000_000_000L;

    /** Windows skipped before anything is printed. One, for warm-up. */
    private static final int DISCARD_WINDOWS = 1;

    /**
     * Whether the per-frame profile runs.
     *
     * <p>Off unless {@code -Dfathom.diagnose=on}. It was on by default while the
     * regression was being hunted; that is the wrong default to ship. The probe
     * costs a pair of {@code nanoTime} calls per section per frame, which is
     * small but not nothing, and a line of console output every five seconds in
     * a client somebody is playing is noise in their log forever.
     *
     * <p>The startup verdict below is separate and always runs: it is three
     * lines once per launch, and "is OptiFine actually active" is a question
     * worth answering in every log rather than only in a log somebody thought
     * to ask for.
     */
    private static final boolean ENABLED =
            "on".equalsIgnoreCase(System.getProperty("fathom.diagnose", "off"))
                    && !net.fathom.optimizations.fabric.bench.auto.BenchmarkState.ACTIVE;

    private static boolean started;
    private static boolean verdictPrinted;
    private static boolean settingsPrinted;
    /** What the last settings line said, so a change can be noticed. */
    private static String settingsFingerprint = "";
    private static int windowsSeen;

    private static long windowStartNanos;
    private static long frames;
    private static long totalNanos;
    private static long worstNanos;

    private FramePerf() {}

    public static boolean enabled() {
        return ENABLED;
    }

    /**
     * Begin measuring.
     *
     * <p>Called from client initialisation. Turning {@link Probe} on here rather
     * than at class load means the sections that ran during loading are not in
     * the first window's totals even before that window is discarded.
     */
    public static void start() {
        verdict();
        // The benchmark owns the Probe while it runs: it begins and ends it per
        // pass, and this window profiler resets it every five seconds. Both
        // driving the same accumulator would make each report the other's
        // leftovers. The benchmark wins because it is the one being asked for.
        if (net.fathom.optimizations.fabric.bench.auto.BenchmarkState.ACTIVE) {
            System.out.println("[FATHOM PERF] frame profile suspended: benchmark mode owns the probe.");
            return;
        }
        if (!ENABLED || started) {
            return;
        }
        started = true;
        Probe.begin();
        windowStartNanos = System.nanoTime();
        System.out.println("[FATHOM PERF] frame profile on, one line every "
                + (WINDOW_NANOS / 1_000_000_000L) + "s.");
    }

    /**
     * The three facts worth having in every log.
     *
     * <p>Always printed, once. Whether OptiFine is active is decided by asking
     * the class loader, never by looking for a file: the whole point is that a
     * jar sitting in a folder proves nothing about what got loaded. The build id
     * is here because a launcher that staged a new jar and a runtime that loaded
     * an old one agree on every other string.
     */
    private static void verdict() {
        if (verdictPrinted) {
            return;
        }
        verdictPrinted = true;
        try {
            boolean optifine = RuntimeTrace.optifineActive();
            boolean optifabric = RuntimeTrace.optifabricActive();
            System.out.println("[FATHOM PERF]");
            System.out.println("[FATHOM PERF] OptiFine active: " + optifine);
            System.out.println("[FATHOM PERF] OptiFabric active: " + optifabric);
            System.out.println("[FATHOM PERF] Fathom build: " + BuildId.describe());

            // One of the two present without the other is the configuration that
            // looks installed and silently is not, so it is called out rather
            // than left for somebody to infer from two `false`s.
            if (optifine != optifabric) {
                System.out.println("[FATHOM PERF] WARNING: OptiFine and OptiFabric disagree. "
                        + "OptiFine cannot load on Fabric without OptiFabric, and OptiFabric "
                        + "does nothing without an OptiFine jar. Re-add both from the launcher.");
            } else if (!optifine) {
                System.out.println("[FATHOM PERF] NOTE: running without OptiFine. "
                        + "Fathom's own renderer optimizations are all available in this "
                        + "configuration; none of them were stood down.");
            }
        } catch (Throwable failure) {
            System.out.println("[FATHOM PERF] could not read the runtime verdict: " + failure);
        }
    }

    /**
     * One rendered frame.
     *
     * @param frameNanos what the frame Mixin timed, or zero when it could not
     */
    public static void frame(long frameNanos) {
        if (!ENABLED || !started || frameNanos <= 0L) {
            return;
        }
        frames++;
        totalNanos += frameNanos;
        if (frameNanos > worstNanos) {
            worstNanos = frameNanos;
        }
        long now = System.nanoTime();
        if (now - windowStartNanos < WINDOW_NANOS) {
            return;
        }
        try {
            report(now - windowStartNanos);
        } catch (Throwable failure) {
            // Once, then never again: a profiler that throws every frame turns a
            // diagnostic into the fault it was added to find.
            started = false;
            System.out.println("[FATHOM PERF] profile stopped after a failure: " + failure);
        }
        windowStartNanos = now;
        frames = 0L;
        totalNanos = 0L;
        worstNanos = 0L;
        Probe.reset();
        GlSync.reset();
    }

    private static void report(long windowNanos) {
        windowsSeen++;
        if (windowsSeen <= DISCARD_WINDOWS) {
            // Warm-up. Said once, so a reader is not left wondering why the
            // first line is missing.
            System.out.println("[FATHOM PERF] warm-up window discarded.");
            return;
        }
        if (frames == 0L) {
            return;
        }
        double seconds = windowNanos / 1_000_000_000d;
        double meanMillis = totalNanos / 1_000_000d / frames;
        double fps = frames / seconds;

        double hud = Probe.meanMillis(Probe.Section.HUD);
        double screen = Probe.meanMillis(Probe.Section.SCREEN);
        double modules = Probe.meanMillis(Probe.Section.MODULES);
        double hooks = Probe.meanMillis(Probe.Section.HOOKS);
        double other = Probe.meanMillis(Probe.Section.OTHER);
        double fathom = hud + screen + modules + hooks + other;

        StringBuilder out = new StringBuilder(256);
        out.append("[FATHOM PERF PROFILE - ").append(round(seconds, 1)).append("s] ");
        out.append("fps ").append(round(fps, 0));
        out.append("  avg frame ").append(round(meanMillis, 3)).append("ms");
        out.append("  worst ").append(round(worstNanos / 1_000_000d, 2)).append("ms");
        out.append("  | Fathom total ").append(round(fathom, 3)).append("ms");
        out.append(" (HUD ").append(round(hud, 3));
        out.append(", UI ").append(round(screen, 3));
        out.append(", modules ").append(round(modules, 3));
        out.append(", hooks ").append(round(hooks, 3));
        out.append(", other ").append(round(other, 3)).append(")");
        out.append("  | rest ").append(round(Math.max(0d, meanMillis - fathom), 3)).append("ms");
        out.append("  | heap ").append(usedHeapMegabytes()).append("MB");
        // Per frame, because that is the figure that decides whether the state
        // guards are a rounding error or the whole problem.
        out.append("  | GL state captures/frame ")
                .append(round(GlSync.captures() / (double) frames, 2));
        out.append(" (").append(round(GlSync.queries() / (double) frames, 1))
                .append(" driver queries/frame)");
        out.append("  | getStringWidth/frame ")
                .append(round(GlSync.widthQueries() / (double) frames, 0));
        System.out.println(out.toString());

        if (!settingsPrinted) {
            settingsPrinted = true;
            printSettings();
        } else {
            settingsChanged();
        }
    }

    /**
     * Reprint the settings line when one of the values in it has changed.
     *
     * <p>It used to print once per launch, and that made it actively
     * misleading: toggling VBO mid-session left the log asserting `vbo false`
     * for the rest of the run, next to frame rates measured with it on. A
     * settings line that can disagree with the settings is worse than no
     * settings line, because it is quoted.
     *
     * <p>Compared as one string rather than field by field: the comparison is
     * then exactly the thing that gets printed, so a value can never change
     * without the check noticing it.
     */
    private static void settingsChanged() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        String now = fingerprint(client);
        if (now.equals(settingsFingerprint)) {
            return;
        }
        System.out.println("[FATHOM PERF] settings changed since the last line:");
        printSettings();
    }

    /** Every value the settings line reports, as one comparable string. */
    private static String fingerprint(MinecraftClient client) {
        return new StringBuilder(96)
                .append(client.options.maxFramerate).append('|')
                .append(client.options.vsync).append('|')
                .append(client.options.fullscreen).append('|')
                .append(client.width).append('x').append(client.height).append('|')
                .append(client.options.viewDistance).append('|')
                .append(client.options.guiScale).append('|')
                .append(client.options.mipmapLevels).append('|')
                .append(client.options.vbo)
                .toString();
    }

    /**
     * The settings that decide what a frame costs, recorded once.
     *
     * <p>Recorded, not changed. A frame rate quoted without the frame cap, the
     * resolution and the render distance it was measured at is not a measurement
     * of anything, and the first thing anybody reading a regression report asks
     * is whether the two runs were comparable.
     */
    private static void printSettings() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            System.out.println("[FATHOM PERF] settings: unreadable");
            return;
        }
        StringBuilder out = new StringBuilder(256);
        out.append("[FATHOM PERF] settings: ");
        settingsFingerprint = fingerprint(client);
        out.append("maxFps ").append(client.options.maxFramerate);
        out.append(" (260 is 1.8.9's Unlimited)");
        out.append(", vsync ").append(client.options.vsync);
        out.append(", fullscreen ").append(client.options.fullscreen);
        out.append(", framebuffer ").append(client.width).append("x").append(client.height);
        out.append(", renderDistance ").append(client.options.viewDistance);
        out.append(", guiScale ").append(client.options.guiScale);
        out.append(", mipmaps ").append(client.options.mipmapLevels);
        out.append(", vbo ").append(client.options.vbo);
        System.out.println(out.toString());

        StringBuilder jvm = new StringBuilder(256);
        jvm.append("[FATHOM PERF] jvm: ");
        jvm.append("java ").append(System.getProperty("java.version", "?"));
        jvm.append(", heap max ").append(java.lang.Runtime.getRuntime().maxMemory() >> 20).append("MB");
        jvm.append(", collectors ");
        try {
            for (java.lang.management.GarbageCollectorMXBean bean
                    : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
                jvm.append(bean.getName()).append(' ');
            }
        } catch (Throwable unreadable) {
            jvm.append("unreadable");
        }
        System.out.println(jvm.toString());
    }

    private static long usedHeapMegabytes() {
        java.lang.Runtime vm = java.lang.Runtime.getRuntime();
        return (vm.totalMemory() - vm.freeMemory()) >> 20;
    }

    /**
     * A number with a fixed number of decimals, without {@code String.format}.
     *
     * <p>{@code String.format} parses its pattern and allocates a formatter every
     * call. This runs once per window rather than once per frame, so the cost
     * would be harmless -- but the whole point of this class is that it does not
     * disturb what it measures, and there is no reason to make an exception for
     * the one path a reader will assume is cheap.
     */
    private static String round(double value, int decimals) {
        long scale = 1L;
        for (int step = 0; step < decimals; step++) {
            scale *= 10L;
        }
        long scaled = Math.round(value * scale);
        if (decimals == 0) {
            return Long.toString(scaled);
        }
        String digits = Long.toString(Math.abs(scaled));
        while (digits.length() <= decimals) {
            digits = "0" + digits;
        }
        String sign = scaled < 0 ? "-" : "";
        return sign + digits.substring(0, digits.length() - decimals)
                + "." + digits.substring(digits.length() - decimals);
    }
}
