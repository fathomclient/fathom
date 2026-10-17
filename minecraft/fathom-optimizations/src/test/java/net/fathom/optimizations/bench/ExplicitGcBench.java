package net.fathom.optimizations.bench;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryMXBean;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * What one {@code System.gc()} actually costs.
 *
 * <p>This is the measurement behind {@code memory.world-change-gc} and the
 * {@code jvm.explicit-gc} experiment. Vanilla 1.8.9 calls {@code System.gc()} explicitly
 * on world change; this program calls it the same way, against a heap shaped like a loaded
 * client, and reports how long the client would have been frozen.
 *
 * <h2>Scope, stated before the numbers</h2>
 *
 * <p>The workload is <strong>synthetic</strong>. It is a retained object graph of roughly
 * the right size and shape — many small, long-lived objects with references between them,
 * which is what chunk sections, block states, entities and texture metadata look like on
 * the heap — plus churn to give the young generation something to do. It is not Minecraft,
 * and the pause figures here are not Minecraft's pause figures.
 *
 * <p>What it <em>can</em> establish, and what makes it worth running:
 *
 * <ul>
 *   <li>the order of magnitude of a forced full collection at a given heap size;
 *   <li>that {@code -XX:+DisableExplicitGC} removes it entirely rather than shortening it;
 *   <li>how the cost scales with {@code -Xmx}, which is the thing the "just give it 8 GB"
 *       advice gets backwards.
 * </ul>
 *
 * <p>Each of those is a property of the JVM rather than of the game, which is why a
 * synthetic workload can speak to them at all.
 *
 * <h2>One variable at a time</h2>
 *
 * <p>This program takes no flags of its own. Every arm is a separate JVM invocation
 * differing in exactly one flag, driven from outside. Combining ten JVM changes into one
 * run makes attribution impossible, which is the whole reason these are separate
 * experiments.
 */
public final class ExplicitGcBench {

    /** How many forced collections to time. */
    private static final int COLLECTIONS = 5;

    /**
     * Roughly how much live data to hold, as a fraction of max heap.
     *
     * <p>A loaded 1.8.9 client with a moderate render distance holds a substantial live
     * set — chunk meshes, block state instances, entity objects, the texture atlas
     * metadata. Half the heap is a defensible stand-in and is what makes the collection
     * cost realistic: a full collection of a nearly-empty heap is fast and would flatter
     * every result here.
     */
    private static final double LIVE_FRACTION = 0.5;

    public static void main(String[] args) {
        String label = args.length > 0 ? args[0] : "unlabelled";

        MemoryMXBean memory = ManagementFactory.getMemoryMXBean();
        long maxHeap = Runtime.getRuntime().maxMemory();

        System.out.println("## " + label);
        System.out.println();
        System.out.println("- Java: " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version"));
        System.out.println("- Max heap: " + mb(maxHeap) + " MB");
        System.out.println("- Initial heap: " + mb(memoryInitial(memory)) + " MB");
        System.out.println("- Collectors: " + collectors());
        System.out.println("- JVM arguments: "
                + ManagementFactory.getRuntimeMXBean().getInputArguments());
        System.out.println();

        // Build a live set. Held in a local that stays reachable for the whole run, so the
        // collector has to trace it every time rather than freeing it.
        //
        // By default it scales with the heap, which models "a bigger heap lets more
        // garbage and more cached state accumulate". Pass -Dfathom.bench.liveMb=N to pin
        // it instead, which separates two questions that the scaling default conflates:
        // whether a full collection costs more because the live set is larger, or because
        // -Xmx is larger. They are not the same claim and the second is the one people
        // usually mean.
        long targetLive = Long.getLong("fathom.bench.liveMb", -1L) > 0L
                ? Long.getLong("fathom.bench.liveMb", -1L) * 1024L * 1024L
                : (long) (maxHeap * LIVE_FRACTION);

        // Timed, because this is where a small -Xms shows up: the heap has to grow to hold
        // this, and each growth step is its own pause. It is the closest thing this
        // synthetic workload has to "loading a world".
        long buildStart = System.nanoTime();
        List<Object> live = buildLiveSet(targetLive);
        long buildNanos = System.nanoTime() - buildStart;
        churn();

        System.out.println("- Live set target: " + mb(targetLive) + " MB"
                + (Long.getLong("fathom.bench.liveMb", -1L) > 0L ? " (pinned)" : " (half of max heap)"));
        System.out.println("- Live set build time: " + millis(buildNanos) + " ms");
        System.out.println("- Live set: " + live.size() + " retained objects");
        System.out.println("- Heap used before: " + mb(memory.getHeapMemoryUsage().getUsed())
                + " MB");
        System.out.println();

        long[] pauses = new long[COLLECTIONS];
        long collectionsBefore = totalCollections();
        for (int i = 0; i < COLLECTIONS; i++) {
            churn();
            long start = System.nanoTime();
            // The exact call vanilla makes on world change.
            System.gc();
            pauses[i] = System.nanoTime() - start;
        }
        long collectionsAfter = totalCollections();

        System.out.println("| Forced collection | Wall time |");
        System.out.println("|---|---|");
        for (int i = 0; i < COLLECTIONS; i++) {
            System.out.println("| " + (i + 1) + " | " + millis(pauses[i]) + " ms |");
        }
        long[] sorted = pauses.clone();
        Arrays.sort(sorted);
        System.out.println("| **median** | **" + millis(sorted[sorted.length / 2]) + " ms** |");
        System.out.println();
        System.out.println("- Collections actually performed during the timed section: "
                + (collectionsAfter - collectionsBefore)
                + " (of " + COLLECTIONS + " requested)");
        System.out.println("- Heap used after: " + mb(memory.getHeapMemoryUsage().getUsed())
                + " MB");
        System.out.println();

        // Keep the live set reachable past the last measurement, or the JIT is free to
        // collect it early and every pause after that measures an empty heap.
        System.out.println("<!-- live set retained: " + live.size() + " -->");
    }

    /**
     * Many small objects with references between them.
     *
     * <p>Shape matters more than size here: a collection's cost is dominated by tracing
     * references, so a single huge array would be cheap to collect and would understate
     * the pause badly. This builds a graph.
     */
    private static List<Object> buildLiveSet(long targetBytes) {
        // Each node is a small object plus a short array plus a couple of references.
        // Roughly 128 bytes on a 64-bit JVM with compressed oops; the exact figure does
        // not matter because the heap is measured directly afterwards.
        int nodes = (int) Math.min(Integer.MAX_VALUE / 4, targetBytes / 128L);
        List<Object> out = new ArrayList<Object>(nodes);
        Node previous = null;
        for (int i = 0; i < nodes; i++) {
            Node node = new Node(i, previous);
            out.add(node);
            previous = node;
        }
        return out;
    }

    /** Short-lived garbage, so the young generation is not empty when the collection lands. */
    private static void churn() {
        Object sink = null;
        for (int i = 0; i < 200_000; i++) {
            sink = new int[8];
        }
        if (sink == null) {
            throw new AssertionError();
        }
    }

    static final class Node {
        final int id;
        final Node previous;
        final int[] payload = new int[8];

        Node(int id, Node previous) {
            this.id = id;
            this.previous = previous;
        }
    }

    private static long totalCollections() {
        long total = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            long count = bean.getCollectionCount();
            if (count > 0L) {
                total += count;
            }
        }
        return total;
    }

    private static String collectors() {
        StringBuilder out = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(bean.getName());
        }
        return out.toString();
    }

    private static long memoryInitial(MemoryMXBean memory) {
        long init = memory.getHeapMemoryUsage().getInit();
        return init < 0L ? 0L : init;
    }

    private static long mb(long bytes) {
        return bytes / 1024L / 1024L;
    }

    private static String millis(long nanos) {
        return String.format("%.1f", nanos / 1_000_000.0);
    }

    private ExplicitGcBench() {}
}
