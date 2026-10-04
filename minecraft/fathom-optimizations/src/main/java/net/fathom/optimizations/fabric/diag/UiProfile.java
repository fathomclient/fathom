package net.fathom.optimizations.fabric.diag;

import java.lang.management.ManagementFactory;
import java.lang.management.ThreadMXBean;
import java.util.Arrays;
import java.util.Locale;

import org.lwjgl.opengl.ARBTimerQuery;
import org.lwjgl.opengl.ContextCapabilities;
import org.lwjgl.opengl.GL15;
import org.lwjgl.opengl.GLContext;

/**
 * What one Fathom screen frame costs: CPU, GPU, bytes allocated, draw calls.
 *
 * <p>Development only. Armed by {@code -Dfathom.uiProfile=true} or by a UI
 * tour's {@code measure:} steps; otherwise {@link #ACTIVE} is false, every hook
 * is one static read, and nothing here is created.
 *
 * <h2>What is measured, exactly</h2>
 *
 * <ul>
 *   <li><b>CPU</b>: wall time from the screen frame's begin to its end on the
 *       render thread -- layout, input, recording, upload, the GL calls and the
 *       state restore. Minecraft's own work is outside it.
 *   <li><b>GPU</b>: a {@code GL_TIME_ELAPSED} query around the same span, read
 *       back three frames later so the read never waits on the driver.
 *   <li><b>Allocation</b>: the render thread's allocated bytes across the span
 *       (HotSpot's per-thread counter; precise to the TLAB, not sampled).
 *   <li><b>Draws</b> and <b>quads</b>: what the renderer submitted, from its
 *       own counters, passed in at the end.
 * </ul>
 *
 * <p>A window is opened with {@link #start} and closed with {@link #stop},
 * which prints one line: means and 95th percentiles over the window's frames.
 */
public final class UiProfile {

    public static final boolean ACTIVE = Boolean.getBoolean("fathom.uiProfile")
            || System.getProperty("fathom.uiTour", "").contains("measure:");

    private static final int MAX_FRAMES = 4096;
    private static final int QUERIES = 4;

    private static final long[] cpu = new long[MAX_FRAMES];
    private static final long[] gpu = new long[MAX_FRAMES];
    private static final long[] alloc = new long[MAX_FRAMES];
    private static final int[] draws = new int[MAX_FRAMES];
    private static final int[] quads = new int[MAX_FRAMES];
    private static final long[] whole = new long[MAX_FRAMES];
    private static int frames;
    private static int gpuFrames;
    private static boolean recording;
    private static String label = "";

    private static long beganAt;
    private static long allocatedAt;
    private static long lastFrameAt;
    private static ThreadMXBean threads;
    private static boolean allocationSupported;

    private static int[] queryIds;
    private static final boolean[] queryPending = new boolean[QUERIES];
    private static int queryNext;
    private static boolean queryOpen;
    private static boolean timerSupported;
    private static boolean initialised;

    private UiProfile() {}

    /** Open a measurement window. Frames before this are not counted. */
    public static void start(String name) {
        label = name;
        frames = 0;
        gpuFrames = 0;
        lastFrameAt = 0L;
        recording = true;
    }

    /** Close the window and print its summary. */
    public static String stop() {
        recording = false;
        String line = summary();
        System.out.println("[Fathom] UI profile " + line);
        return line;
    }

    public static boolean recording() {
        return recording;
    }

    /** Called where a Fathom screen frame begins, before anything is drawn. */
    public static void frameBegin() {
        if (!ACTIVE) {
            return;
        }
        init();
        long now = System.nanoTime();
        if (recording && lastFrameAt != 0L && frames < MAX_FRAMES) {
            whole[frames] = now - lastFrameAt;
        }
        lastFrameAt = now;
        collectQueries();
        if (timerSupported && !queryOpen) {
            int slot = queryNext;
            if (!queryPending[slot]) {
                GL15.glBeginQuery(ARBTimerQuery.GL_TIME_ELAPSED, queryIds[slot]);
                queryOpen = true;
            }
        }
        allocatedAt = allocatedBytes();
        beganAt = System.nanoTime();
    }

    /** Called where it ends, after the last draw call and the state restore. */
    public static void frameEnd(int drawCalls, int quadCount) {
        if (!ACTIVE) {
            return;
        }
        long spent = System.nanoTime() - beganAt;
        long bytes = allocatedBytes() - allocatedAt;
        if (queryOpen) {
            GL15.glEndQuery(ARBTimerQuery.GL_TIME_ELAPSED);
            queryOpen = false;
            queryPending[queryNext] = recording;
            queryNext = (queryNext + 1) % QUERIES;
        }
        if (!recording || frames >= MAX_FRAMES) {
            return;
        }
        cpu[frames] = spent;
        alloc[frames] = Math.max(0L, bytes);
        draws[frames] = drawCalls;
        quads[frames] = quadCount;
        frames++;
    }

    private static void collectQueries() {
        if (!timerSupported) {
            return;
        }
        for (int slot = 0; slot < QUERIES; slot++) {
            if (!queryPending[slot]) {
                continue;
            }
            if (GL15.glGetQueryObjecti(queryIds[slot], GL15.GL_QUERY_RESULT_AVAILABLE) == 0) {
                continue;
            }
            long nanos = ARBTimerQuery.glGetQueryObjectui64(queryIds[slot], GL15.GL_QUERY_RESULT);
            queryPending[slot] = false;
            if (recording && gpuFrames < MAX_FRAMES) {
                gpu[gpuFrames++] = nanos;
            }
        }
    }

    private static void init() {
        if (initialised) {
            return;
        }
        initialised = true;
        ContextCapabilities caps = GLContext.getCapabilities();
        timerSupported = caps.OpenGL33 || caps.GL_ARB_timer_query;
        if (timerSupported) {
            queryIds = new int[QUERIES];
            for (int slot = 0; slot < QUERIES; slot++) {
                queryIds[slot] = GL15.glGenQueries();
            }
        }
        threads = ManagementFactory.getThreadMXBean();
        try {
            if (threads instanceof com.sun.management.ThreadMXBean) {
                com.sun.management.ThreadMXBean hotspot = (com.sun.management.ThreadMXBean) threads;
                if (!hotspot.isThreadAllocatedMemoryEnabled()) {
                    hotspot.setThreadAllocatedMemoryEnabled(true);
                }
                allocationSupported = hotspot.isThreadAllocatedMemorySupported();
            }
        } catch (Throwable unsupported) {
            allocationSupported = false;
        }
    }

    private static long allocatedBytes() {
        if (!allocationSupported) {
            return 0L;
        }
        return ((com.sun.management.ThreadMXBean) threads).getThreadAllocatedBytes(Thread.currentThread().getId());
    }

    private static String summary() {
        int n = frames;
        if (n == 0) {
            return String.format(Locale.ROOT, "%s: no frames", label);
        }
        double drawMean = 0;
        double quadMean = 0;
        for (int i = 0; i < n; i++) {
            drawMean += draws[i];
            quadMean += quads[i];
        }
        drawMean /= n;
        quadMean /= n;
        return String.format(Locale.ROOT,
                "%s: frames=%d fps=%.0f cpu_ms mean=%.3f p95=%.3f gpu_ms mean=%.3f p95=%.3f (n=%d)"
                        + " alloc_B/frame mean=%.0f p95=%.0f draws=%.1f quads=%.0f",
                label, n, 1e9 / Math.max(1d, mean(whole, Math.max(1, n - 1), 1)),
                mean(cpu, n, 0) / 1e6, percentile(cpu, n, 0.95f) / 1e6,
                gpuFrames == 0 ? Double.NaN : mean(gpu, gpuFrames, 0) / 1e6,
                gpuFrames == 0 ? Double.NaN : percentile(gpu, gpuFrames, 0.95f) / 1e6, gpuFrames,
                mean(alloc, n, 0), percentile(alloc, n, 0.95f), drawMean, quadMean);
    }

    private static double mean(long[] values, int count, int from) {
        double total = 0;
        int used = 0;
        for (int i = from; i < from + count && i < values.length; i++) {
            total += values[i];
            used++;
        }
        return used == 0 ? 0 : total / used;
    }

    private static double percentile(long[] values, int count, float fraction) {
        long[] sorted = Arrays.copyOf(values, count);
        Arrays.sort(sorted);
        return sorted[Math.min(count - 1, Math.round(fraction * (count - 1)))];
    }
}
