package net.fathom.optimizations.core.opt.text;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * {@link TextMeasurer} with the answers remembered.
 *
 * <p>Width is a pure function of the string and the loaded font. Vanilla recomputes it at
 * frame rate over a corpus — scoreboard lines, tab list entries, chat backlog, nametags —
 * that is almost entirely unchanged between frames. Remembering the answer is therefore
 * output-identical by construction, which is the strongest correctness argument any
 * optimization in this project has: there is no approximation here to be wrong about.
 *
 * <h2>The three ways a cache like this goes wrong, and what stops each</h2>
 *
 * <ul>
 *   <li><strong>Stale font.</strong> A resource-pack reload changes glyph widths. Every
 *       entry is keyed on {@link GlyphSource#generation()} as well as the string, and a
 *       generation change drops the table wholesale. Serving a width measured against the
 *       previous pack would misalign every centred string in the game.
 *   <li><strong>Unbounded growth.</strong> Chat is adversarial input: a server can emit a
 *       distinct string every tick forever. The table is a fixed-capacity LRU, so the
 *       worst case is a cache that never hits, not a client that runs out of heap. A
 *       memory leak is explicitly on this project's list of things that disqualify an
 *       optimization however fast it is.
 *   <li><strong>Wrong thread.</strong> 1.8.9 lays out text on the render thread and
 *       nowhere else. This class is deliberately unsynchronised, because synchronising it
 *       would add the cost back; {@link #isConfinedToCallingThread()} records the
 *       assumption and {@link #assertOwningThread()} checks it in development.
 * </ul>
 *
 * <h2>What it does not do</h2>
 *
 * <p>It caches width and nothing else. Caching laid-out glyph positions is a larger win
 * and a larger risk, and it is not in this batch.
 */
public final class TextMetricsCache {

    /**
     * How many distinct strings to remember.
     *
     * <p>Sized for the workload rather than for a round number. A busy Hypixel frame
     * measures on the order of a few hundred distinct strings — fifteen scoreboard lines,
     * eighty tab entries, a hundred chat lines, plus nametags. 4096 holds several seconds
     * of churn on top of that while costing well under a megabyte.
     */
    public static final int DEFAULT_CAPACITY = 4096;

    private final TextMeasurer measurer;
    private final GlyphSource font;
    private final Map<String, Integer> widths;
    private final Thread owner;

    private int generation;
    private long hits;
    private long misses;
    private long invalidations;

    public TextMetricsCache(GlyphSource font) {
        this(font, DEFAULT_CAPACITY);
    }

    public TextMetricsCache(GlyphSource font, final int capacity) {
        if (font == null) {
            throw new IllegalArgumentException("font");
        }
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.font = font;
        this.measurer = new TextMeasurer(font);
        this.generation = font.generation();
        this.owner = Thread.currentThread();
        // 0.75f load factor, access-ordered: LinkedHashMap's own LRU, which is the one
        // piece of caching machinery the JDK already gets right.
        this.widths =
                new LinkedHashMap<String, Integer>(capacity * 4 / 3 + 1, 0.75f, true) {
                    private static final long serialVersionUID = 1L;

                    @Override
                    protected boolean removeEldestEntry(Map.Entry<String, Integer> eldest) {
                        return size() > capacity;
                    }
                };
    }

    /**
     * The width of {@code text}, identical to {@link TextMeasurer#width(String)}.
     *
     * <p>If this ever returns a different number from the measurer for the same input and
     * generation, that is a bug in this class and not a tuning question.
     */
    public int width(String text) {
        if (text == null) {
            return 0;
        }
        checkGeneration();
        Integer cached = widths.get(text);
        if (cached != null) {
            hits++;
            return cached.intValue();
        }
        misses++;
        int computed = measurer.width(text);
        widths.put(text, Integer.valueOf(computed));
        return computed;
    }

    /**
     * Drop everything if the font changed underneath us.
     *
     * <p>Checked on every lookup rather than hooked to a reload event. A missed event is a
     * silent correctness failure that would survive until the next restart; an integer
     * comparison per lookup is cheap enough that buying certainty with it is obviously
     * the right trade.
     */
    private void checkGeneration() {
        int current = font.generation();
        if (current != generation) {
            generation = current;
            widths.clear();
            invalidations++;
        }
    }

    /** Forget everything. For a resource reload we were told about explicitly. */
    public void invalidate() {
        widths.clear();
        generation = font.generation();
        invalidations++;
    }

    public int size() {
        return widths.size();
    }

    public long hits() {
        return hits;
    }

    public long misses() {
        return misses;
    }

    public long invalidations() {
        return invalidations;
    }

    /**
     * Hit rate so far, or {@code NaN} before the first lookup.
     *
     * <p>{@code NaN} rather than zero: "no lookups yet" and "nothing ever hit" are
     * different facts and a benchmark that conflates them reports a cache as useless when
     * it was simply never used.
     */
    public double hitRate() {
        long total = hits + misses;
        return total == 0L ? Double.NaN : (double) hits / (double) total;
    }

    /** This cache assumes single-threaded use. Stated, not implied. */
    public boolean isConfinedToCallingThread() {
        return true;
    }

    /** Whether we are still on the thread that built this. For assertions in development. */
    public boolean onOwningThread() {
        return Thread.currentThread() == owner;
    }

    public void assertOwningThread() {
        if (!onOwningThread()) {
            throw new IllegalStateException(
                    "TextMetricsCache is confined to " + owner.getName()
                            + " but was used from " + Thread.currentThread().getName());
        }
    }
}
