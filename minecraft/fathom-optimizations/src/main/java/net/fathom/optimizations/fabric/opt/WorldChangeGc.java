package net.fathom.optimizations.fabric.opt;

import net.fathom.optimizations.core.opt.gc.ExplicitGcPolicy;
import net.fathom.optimizations.fabric.Runtime;

/**
 * The world-change {@code System.gc()} — {@code memory.world-change-gc}.
 *
 * <p>Vanilla forces a full collection when starting singleplayer and on every
 * {@code connect} (joining a server, changing dimension, leaving to the menu), at
 * the moment the client is also loading chunks. With the switch on, the tested
 * {@link ExplicitGcPolicy} decides: the first world change of a session still
 * collects, later ones inside its interval are skipped. Collection itself is
 * unaffected — the old world's objects become collectable when their references
 * drop, and the collector reclaims them on its own schedule.
 *
 * <p>Every collection this path does run is timed and logged, so the stall it
 * costs is a measured number rather than an assumption. Client thread only.
 */
public final class WorldChangeGc {

    private static final ExplicitGcPolicy POLICY = new ExplicitGcPolicy();

    private WorldChangeGc() {}

    public static void requested(String where) {
        boolean managed = Runtime.switches() != null && Runtime.switches().isEnabled(Runtime.WORLD_CHANGE_GC);
        if (managed) {
            ExplicitGcPolicy.Decision decision =
                    POLICY.decide(ExplicitGcPolicy.Site.WORLD_CHANGE, System.currentTimeMillis());
            if (!decision.allowed()) {
                System.out.println("[Fathom] World-change GC skipped (" + where + "): " + decision.reason());
                return;
            }
        }
        long started = System.nanoTime();
        System.gc();
        System.out.println(String.format(java.util.Locale.ROOT,
                "[Fathom] World-change GC (%s): full collection took %.1f ms", where,
                (System.nanoTime() - started) / 1_000_000.0));
    }
}
