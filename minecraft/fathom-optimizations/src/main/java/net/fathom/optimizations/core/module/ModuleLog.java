package net.fathom.optimizations.core.module;

import java.util.ArrayList;
import java.util.List;

/**
 * What Fathom's own module has said, kept where the interface can show it.
 *
 * <h2>Why the client keeps its own log at all</h2>
 *
 * <p>Everything here also goes to standard output, which Minecraft writes into
 * `logs/latest.log`. That file is the right place for it and the wrong place to
 * read it from: it is tens of thousands of lines of resource loading, sound
 * engine chatter and chunk building, and the twenty lines Fathom contributed are
 * scattered through it in load order.
 *
 * <p>Somebody in Diagnostics asking "did my optimizations apply?" wants those
 * twenty lines. So they are also kept here, in order, with nothing else in
 * between, and the game log is shown separately for when the answer is not in
 * them.
 *
 * <h2>Bounded, and cheap</h2>
 *
 * <p>A fixed ring of {@link #CAPACITY} lines. The module logs at start-up and
 * when something changes, which is a few dozen lines a session — but a bounded
 * buffer is not a size estimate, it is a guarantee: a mod using the public API
 * can write to this, and an unbounded log a third party can append to is a
 * memory leak with an owner who did not mean it.
 *
 * <h2>No timestamps</h2>
 *
 * <p>They would double the width of every line to tell somebody reading a
 * twenty-line list what they can already see: the order. `latest.log` has them,
 * and that is where a question about *when* is answered.
 */
public final class ModuleLog {

    /** How many lines are kept. Older ones fall off the front. */
    public static final int CAPACITY = 256;

    private static final String[] LINES = new String[CAPACITY];
    private static int head;
    private static int count;

    private ModuleLog() {}

    /**
     * Record a line, and print it.
     *
     * <p>Both, deliberately. The console is where a crash report and a bug
     * report get their copy; this is where the interface gets its copy. Writing
     * to only one of them means one of those two audiences is missing something.
     */
    public static synchronized void say(String line) {
        if (line == null || line.isEmpty()) {
            return;
        }
        String trimmed = line.length() > 400 ? line.substring(0, 400) + "…" : line;
        LINES[head] = trimmed;
        head = (head + 1) % CAPACITY;
        if (count < CAPACITY) {
            count++;
        }
        System.out.println("[Fathom] " + trimmed);
    }

    /** Everything kept, oldest first. */
    public static synchronized List<String> lines() {
        List<String> out = new ArrayList<String>(count);
        for (int step = count; step > 0; step--) {
            out.add(LINES[Math.floorMod(head - step, CAPACITY)]);
        }
        return out;
    }

    /** The most recent {@code many} lines, oldest first. */
    public static synchronized List<String> recent(int many) {
        List<String> all = lines();
        if (many >= all.size()) {
            return all;
        }
        return new ArrayList<String>(all.subList(all.size() - many, all.size()));
    }

    public static synchronized int size() {
        return count;
    }

    /** Forget everything. For tests, and for nothing else. */
    public static synchronized void clear() {
        head = 0;
        count = 0;
        for (int index = 0; index < CAPACITY; index++) {
            LINES[index] = null;
        }
    }
}
