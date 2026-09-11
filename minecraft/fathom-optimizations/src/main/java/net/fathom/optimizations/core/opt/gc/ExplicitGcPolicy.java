package net.fathom.optimizations.core.opt.gc;

/**
 * Whether a given {@code System.gc()} call should be allowed to happen.
 *
 * <h2>What vanilla does</h2>
 *
 * <p>1.8.9 calls {@code System.gc()} explicitly when the world changes — joining a server,
 * leaving to the main menu, going through a portal. On a default JVM that is a full,
 * stop-the-world collection of the entire heap, requested at the precise moment the client
 * is also loading chunks, and it is a large part of why world changes in 1.8.9 stall for
 * seconds rather than milliseconds.
 *
 * <p>The call does not do anything the collector would not have done on its own. It brings
 * a collection forward and forces it to be a full one. What the client actually needs at
 * that moment is for the old world's objects to become collectable, which happens when the
 * references are dropped, with or without this call.
 *
 * <h2>Why this exists as code at all</h2>
 *
 * <p>{@code -XX:+DisableExplicitGC} does the same job from the launcher with no bytecode
 * involved, and where the launcher controls the JVM that is the better fix — one flag, no
 * Mixin, nothing to go wrong. This class covers the case where it does not: a user running
 * our mod under someone else's launcher, or a JVM where the flag was not passed.
 *
 * <p>The two must not both be counted as a win. They are the same win by two routes, and
 * the registry says so.
 *
 * <h2>Why it is a policy and not an empty method</h2>
 *
 * <p>Silently making {@code System.gc()} do nothing, everywhere, forever, is a bigger
 * change than it looks. Something other than the world-change path may call it — a mod, a
 * library, a heap-dump tool, the user through a debugger — with a real reason. So:
 *
 * <ul>
 *   <li>Only calls we recognise as the world-change one are suppressed. Everything else
 *       is passed through untouched.
 *   <li>A rate limit rather than a total ban, so a genuinely wanted collection still
 *       happens eventually.
 *   <li>Every decision is counted, so a benchmark can state how many collections were
 *       actually avoided rather than assuming.
 * </ul>
 *
 * <p>Not thread-safe, and it does not need to be: the world-change path is the client
 * thread.
 */
public final class ExplicitGcPolicy {

    /**
     * The shortest gap between two collections we will allow through this path.
     *
     * <p>Sixty seconds. Long enough that lobby-hopping — the workload where this hurts
     * most, and where a user may change worlds every few seconds — collects at most once a
     * minute instead of once per hop. Short enough that a client left running still gets
     * the occasional full collection if something really did want one.
     */
    public static final long DEFAULT_MINIMUM_INTERVAL_MILLIS = 60_000L;

    /** Where a {@code System.gc()} came from. */
    public enum Site {
        /**
         * The world-change call in vanilla. The one we are here for.
         */
        WORLD_CHANGE,
        /**
         * Anything else — another mod, a tool, the user.
         *
         * <p>Always allowed. It is not our business, and a mod that calls
         * {@code System.gc()} may be doing it for a reason we cannot see.
         */
        OTHER
    }

    /** What we decided, and why. */
    public static final class Decision {
        private final boolean allowed;
        private final String reason;

        Decision(boolean allowed, String reason) {
            this.allowed = allowed;
            this.reason = reason;
        }

        public boolean allowed() {
            return allowed;
        }

        /** Suitable for a log line. Never empty. */
        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return (allowed ? "allow: " : "suppress: ") + reason;
        }
    }

    private final long minimumIntervalMillis;

    private long lastAllowedMillis = Long.MIN_VALUE;
    private long suppressed;
    private long allowed;

    public ExplicitGcPolicy() {
        this(DEFAULT_MINIMUM_INTERVAL_MILLIS);
    }

    public ExplicitGcPolicy(long minimumIntervalMillis) {
        if (minimumIntervalMillis < 0L) {
            throw new IllegalArgumentException(
                    "interval cannot be negative: " + minimumIntervalMillis);
        }
        this.minimumIntervalMillis = minimumIntervalMillis;
    }

    /**
     * Decide about one call.
     *
     * @param site where the call came from
     * @param nowMillis the current time; passed in rather than read, so tests are not
     *     obliged to sleep
     */
    public Decision decide(Site site, long nowMillis) {
        if (site == null) {
            throw new IllegalArgumentException("site");
        }
        if (site != Site.WORLD_CHANGE) {
            allowed++;
            lastAllowedMillis = nowMillis;
            return new Decision(true,
                    "not the world-change call; someone else asked for this and we do not"
                            + " second-guess them");
        }
        if (lastAllowedMillis != Long.MIN_VALUE
                && nowMillis - lastAllowedMillis < minimumIntervalMillis) {
            suppressed++;
            return new Decision(false,
                    "world change, and a full collection already ran "
                            + (nowMillis - lastAllowedMillis) + " ms ago");
        }
        // The first world change of a session is let through. Not because it helps — it
        // does not — but because suppressing every one of them from the first frame makes
        // this indistinguishable from -XX:+DisableExplicitGC, and then a user running both
        // has no way to tell which is in effect when something goes wrong.
        allowed++;
        lastAllowedMillis = nowMillis;
        return new Decision(true, "world change, and no recent full collection to reuse");
    }

    /** Collections avoided so far. The number a benchmark should quote. */
    public long suppressedCount() {
        return suppressed;
    }

    public long allowedCount() {
        return allowed;
    }

    public long minimumIntervalMillis() {
        return minimumIntervalMillis;
    }
}
