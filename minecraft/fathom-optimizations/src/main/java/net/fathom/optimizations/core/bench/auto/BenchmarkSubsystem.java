package net.fathom.optimizations.core.bench.auto;

/**
 * One thing the benchmark can switch off and measure the absence of.
 *
 * <h2>Why an interface and not an enum</h2>
 *
 * <p>Because the set is not known here. Most subsystems are Fathom modules, and
 * those come from {@code ModuleRegistry} at runtime -- a build with a new module
 * must get a new benchmark subsystem without anybody editing a list. The rest
 * are pieces of rendering infrastructure that have no module, and those are
 * registered explicitly. Both arrive through this one contract.
 *
 * <h2>Why {@link #present} exists</h2>
 *
 * <p>Because "this build does not have that" and "that costs nothing" are
 * different answers, and a report that omits the first silently gives the
 * second. Several systems worth asking about -- cosmetics, custom nametag
 * rendering, a custom scoreboard -- are things Fathom could have and does not.
 * A subsystem that is absent says so, once, and is not measured.
 */
public interface BenchmarkSubsystem {

    /** Stable id, used in the report and to name a pass. */
    String id();

    /** What to call it in the report. */
    String label();

    /**
     * The group this belongs to.
     *
     * <p>Passes are run per group as well as per subsystem: switching one HUD
     * widget off is below the noise floor of a frame-rate measurement, and
     * switching the whole HUD off is not.
     */
    String group();

    /** Whether this build has it at all. An absent subsystem is never measured. */
    boolean present();

    /** Why it is absent, for the report. Empty when {@link #present} is true. */
    String absenceReason();

    /**
     * Whether it can be switched without restarting the game.
     *
     * <p>Anything that reads a system property at class load, or that is decided
     * by a Mixin at class-transform time, cannot. Those are reported rather than
     * measured, because a benchmark that silently skipped them would look like
     * one that found them free.
     */
    boolean runtimeToggle();

    boolean enabled();

    /**
     * Switch it, reversibly.
     *
     * <p>The manager records the state of every subsystem before the first pass
     * and restores it after the last, including when a pass throws. Benchmark
     * mode must not leave a setting changed.
     */
    void setEnabled(boolean on);

    /** Whether this is infrastructure the minimal pass must keep. */
    boolean essential();
}
