package net.fathom.optimizations.core.opt.text;

/**
 * Remembers widths the game has already computed.
 *
 * <h2>Why this exists next to {@link TextMetricsCache}</h2>
 *
 * <p>{@link TextMetricsCache} computes the width itself, from a {@link GlyphSource}, via
 * {@link TextMeasurer}. That is a reimplementation of vanilla's layout rules — format
 * codes, the bold overdraw, the unicode page split — and a reimplementation can be
 * <em>wrong</em>. Every string it disagrees with vanilla about is a misaligned scoreboard
 * or a centred title off by a pixel, and the bug would be invisible in a unit test that
 * shares the reimplementation's assumptions.
 *
 * <p>This class removes that entire class of risk by never computing anything. It stores
 * the number <em>vanilla itself returned</em> and hands it back when the same string is
 * measured again. Output-identical is not an argument here, it is arithmetic: the value
 * returned on a hit is the same {@code int} the game produced on the miss.
 *
 * <p>The cost is that the first measurement of a string is unchanged. That is the right
 * trade for this workload: the corpus that dominates a frame — scoreboard lines, tab list
 * entries, chat backlog, nametags — is measured repeatedly and changes slowly, so the
 * hit rate is what matters and the miss path is already vanilla's.
 *
 * <h2>The two ways it could still go wrong</h2>
 *
 * <ul>
 *   <li><strong>Stale font.</strong> A resource-pack reload or a unicode-mode switch
 *       changes what vanilla would return. {@link #invalidate()} drops the table, and the
 *       caller is responsible for calling it at exactly those points. Everything else
 *       here is safe by construction; this is the one obligation.
 *   <li><strong>Unbounded growth.</strong> Chat is adversarial: a server can emit a
 *       distinct string every tick forever. Fixed capacity, so the worst case is a
 *       cache that never hits rather than a client that runs out of heap.
 * </ul>
 *
 * <h2>Storage</h2>
 *
 * <p>A {@link StringTable}: open-addressed, generational, nothing allocated on a hit.
 * It replaced an access-ordered {@code LinkedHashMap} that relinked an entry on every
 * lookup and boxed every width it stored. Widths are boxed once, on insert, and the
 * same {@code Integer} is handed back on every hit, so a caller returning it through a
 * Mixin callback allocates nothing either.
 *
 * <p>Deliberately unsynchronised. 1.8.9 lays out text on the render thread and nowhere
 * else, and synchronising would add back the cost this exists to remove.
 */
public final class WidthMemo {

    /**
     * How many distinct strings to remember.
     *
     * <p>Sized for the workload, not for a round number: a busy server's frame measures a
     * few hundred distinct strings, and 4096 holds several seconds of churn on top of
     * that for well under a megabyte.
     */
    public static final int DEFAULT_CAPACITY = 4096;

    private final StringTable<Integer> widths;

    private long hits;
    private long misses;
    private long invalidations;

    public WidthMemo() {
        this(DEFAULT_CAPACITY);
    }

    public WidthMemo(int capacity) {
        if (capacity < 1) {
            throw new IllegalArgumentException("capacity must be positive: " + capacity);
        }
        this.widths = new StringTable<Integer>(capacity);
    }

    /**
     * The remembered width of {@code text}, or {@code null} if it has not been measured
     * since the last {@link #invalidate()}.
     *
     * <p>Returns a boxed {@code Integer} rather than a sentinel {@code -1}: a width of
     * {@code -1} is not obviously impossible, and a sentinel that collides with a real
     * answer is how a cache starts lying.
     *
     * <p>A {@code null} string misses without being counted as one. It is not a
     * measurement the game asked for, and counting it would drag {@link #hitRate()} down
     * with lookups that were never going to be in the table.
     */
    public Integer lookup(String text) {
        if (text == null) {
            return null;
        }
        Integer known = widths.get(text);
        if (known == null) {
            misses++;
            return null;
        }
        hits++;
        return known;
    }

    /** Record what the game computed. */
    public void remember(String text, int width) {
        if (text == null) {
            return;
        }
        widths.put(text, Integer.valueOf(width));
    }

    /**
     * Forget everything.
     *
     * <p>Must be called whenever the font changes. Not calling it is the only way this
     * class can produce a wrong answer.
     */
    public void invalidate() {
        if (widths.size() > 0) {
            invalidations++;
        }
        widths.clear();
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

    /** Hit rate over the whole session, or {@code 0} before anything was looked up. */
    public double hitRate() {
        long total = hits + misses;
        return total == 0L ? 0.0d : (double) hits / (double) total;
    }
}
