package net.fathom.optimizations.core.experiment;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

import net.fathom.optimizations.core.experiment.Experiment.Arm;

/**
 * The JVM questions this project intends to answer.
 *
 * <p>Every one of these corresponds to something the launcher currently does, or that the
 * research recommends, on the strength of reasoning rather than our own measurement. The
 * milestone brief is explicit that they must not be hardcoded as facts, so they are
 * written down here as open questions with defined experiments instead.
 *
 * <p><strong>None of these has been run.</strong> The launcher's current behaviour is
 * recorded in each entry's {@code currentBehaviour}, and it is unchanged by this
 * milestone: changing a default on the strength of an experiment we have not performed
 * would be the same mistake in the other direction.
 */
public final class Experiments {

    private Experiments() {}

    public static List<Experiment> all() {
        List<Experiment> out = new ArrayList<Experiment>();
        out.add(heapSizing());
        out.add(equalMinMaxHeap());
        out.add(explicitGc());
        out.add(collectorChoice());
        out.add(compileHugeMethods());
        out.add(shenandoahAvailability());
        return Collections.unmodifiableList(out);
    }

    public static Experiment byId(String id) {
        for (Experiment experiment : all()) {
            if (experiment.id().equals(id)) {
                return experiment;
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------

    /** Does a bigger heap actually help, or hurt? */
    static Experiment heapSizing() {
        return Experiment.builder("jvm.heap-size")
                .question(
                        "What heap size gives the best frame-time behaviour for 1.8.9 on Java 8,"
                                + " and does raising it past the working set make pauses worse?")
                .hypothesis(
                        "A modest heap of roughly 2-3 GB is best, and larger heaps make each"
                                + " collection cover more memory and therefore take longer."
                                + " performance.md §5.2 argues this from the tuning literature;"
                                + " we have not measured it on 1.8.9 ourselves.")
                .arm(Arm.of("1g", "Below the stated 0.6-1.5 GB working set; expected to collect often.",
                        "-Xms1024M", "-Xmx1024M"))
                .arm(Arm.of("2g", "The launcher's current default.", "-Xms2048M", "-Xmx2048M"))
                .arm(Arm.of("4g", "Comfortably above the working set.", "-Xms4096M", "-Xmx4096M"))
                .arm(Arm.of("8g", "The size the community routinely recommends.",
                        "-Xms8192M", "-Xmx8192M"))
                .scenarios("chunk-movement", "entity-lobby", "pvp-map")
                .primaryMetric(
                        "0.1% low FPS and p99 frame time. Average FPS is the wrong metric here:"
                                + " the claim is about pause length, which averages hide.")
                .falsifiedBy(
                        "8g showing equal or better 0.1% low FPS than 2g across the chunk-movement"
                                + " and entity-lobby scenarios. That would mean the pause-length"
                                + " argument does not apply at these heap sizes on Java 8, and the"
                                + " settings screen's wording would need to change.")
                .currentBehaviour(
                        "The launcher defaults to 2048 MB and its settings screen says raising it"
                                + " will not increase FPS. That wording is currently based on"
                                + " research, not on our own measurement.")
                .note(
                        "Peak heap used must be recorded per arm. If the 1g arm is at its ceiling"
                                + " the comparison is measuring heap exhaustion rather than"
                                + " collection cost.")
                .build();
    }

    /** Is -Xms == -Xmx actually free? */
    static Experiment equalMinMaxHeap() {
        return Experiment.builder("jvm.equal-min-max-heap")
                .question(
                        "Does setting -Xms equal to -Xmx improve frame-time consistency, and does"
                                + " it cost anything?")
                .hypothesis(
                        "Equal min and max avoids heap-resize pauses at no cost. The 'at no cost'"
                                + " half is the part we have not checked: committing the full heap"
                                + " up front increases resident memory from the first frame, which"
                                + " matters on a machine that is short of RAM.")
                .arm(Arm.of("equal", "The launcher's current behaviour.", "-Xms2048M", "-Xmx2048M"))
                .arm(Arm.of("default-min", "Let the JVM choose the initial heap and grow it.",
                        "-Xmx2048M"))
                .arm(Arm.of("small-min", "A deliberately small initial heap, to make any"
                        + " resize pause visible if one exists.", "-Xms256M", "-Xmx2048M"))
                .scenarios("chunk-movement", "entity-lobby")
                .primaryMetric(
                        "p99 frame time during the first two minutes after warm-up, where any"
                                + " growth pauses would occur, plus committed heap over time.")
                .falsifiedBy(
                        "No measurable difference in p99 frame time between the arms. That would"
                                + " mean equal min/max buys nothing on this workload, and the"
                                + " launcher should stop committing memory it does not need.")
                .currentBehaviour(
                        "build_jvm_args always emits -Xms and -Xmx at the same value, and there is"
                                + " no way to configure otherwise. This experiment is the reason"
                                + " that will become a setting rather than a constant.")
                .note(
                        "Resize pauses are rare and short. Detecting them may need GC logging"
                                + " rather than frame timing; that is a limitation of the"
                                + " in-process instrumentation, not evidence of absence.")
                .build();
    }

    /** Is DisableExplicitGC universally better? */
    static Experiment explicitGc() {
        return Experiment.builder("jvm.explicit-gc")
                .question(
                        "Does -XX:+DisableExplicitGC improve the experience, and is there any"
                                + " workload where suppressing System.gc() makes things worse?")
                .hypothesis(
                        "Vanilla calls System.gc() on world change, which stops the client for as"
                                + " long as a full collection takes; suppressing it removes a"
                                + " visible freeze. The 'universally' part is unverified: a full"
                                + " collection at a natural pause point may leave the heap in a"
                                + " better state for the minutes that follow.")
                .arm(Arm.of("suppressed", "The launcher's current default.", "-XX:+DisableExplicitGC"))
                .arm(Arm.of("allowed", "Let System.gc() run, as stock Java does."))
                .scenarios("chunk-movement", "pvp-map")
                .primaryMetric(
                        "Longest single frame time across a world change, and mean frame time for"
                                + " the two minutes after it. The first captures the freeze; the"
                                + " second captures whether the collection was worth it.")
                .falsifiedBy(
                        "The 'allowed' arm showing better frame times in the minutes after a world"
                                + " change, by more than the freeze cost. That would make it a"
                                + " trade rather than a free win, and the default would need to"
                                + " become a choice.")
                .currentBehaviour(
                        "JvmOptimizations::default() sets disable_explicit_gc = true, and"
                                + " build_jvm_args emits the flag. That default is unchanged by"
                                + " this milestone and remains unmeasured.")
                .note(
                        "The mod-side optimization memory.world-change-gc removes the call"
                                + " instead of suppressing it. If both are available they should"
                                + " be compared: the flag needs no mod, the mod needs no flag.")
                .build();
    }

    /** Which collector, and how much tuning? */
    static Experiment collectorChoice() {
        return Experiment.builder("jvm.collector")
                .question(
                        "Which garbage collector, with how much tuning, gives the best frame-time"
                                + " consistency for a 1.8.9 client on Java 8?")
                .hypothesis(
                        "G1 with modest tuning beats the Java 8 default ParallelGC for"
                                + " pause-sensitivity, and aggressive server-oriented tuning is"
                                + " worse than modest tuning. performance.md §5.3 argues both"
                                + " halves; neither is measured.")
                .arm(Arm.of("parallel", "Java 8's default. The baseline everyone actually runs.",
                        "-XX:+UseParallelGC"))
                .arm(Arm.of("g1-modest", "The launcher's current default.",
                        "-XX:+UseG1GC", "-XX:MaxGCPauseMillis=50"))
                .arm(Arm.of("g1-untuned", "G1 with no pause target, to isolate what the target"
                        + " actually does.", "-XX:+UseG1GC"))
                .arm(Arm.of("cms", "Concurrent mark-sweep: available on Java 8, pause-oriented,"
                        + " and rarely tried for clients.", "-XX:+UseConcMarkSweepGC"))
                .scenarios("chunk-movement", "entity-lobby", "particles")
                .primaryMetric(
                        "0.1% low FPS, plus total GC pause time as a fraction of wall clock from"
                                + " GarbageCollectorMXBean.")
                .falsifiedBy(
                        "ParallelGC matching or beating G1 on 0.1% low FPS. Given it is the"
                                + " default that every unmodded player already runs, that result"
                                + " would mean the launcher should stop overriding it.")
                .note(
                        "Aikar's flags are deliberately not an arm. performance.md §5.3 records"
                                + " that they are server-oriented and produce client stutter;"
                                + " including them would be testing something we already have a"
                                + " documented reason to refuse. If someone wants them measured,"
                                + " that is a separate experiment with its own justification.")
                .currentBehaviour(
                        "build_jvm_args emits -XX:+UseG1GC with -XX:MaxGCPauseMillis=50 on every"
                                + " launch, unconditionally.")
                .build();
    }

    /** Does letting HotSpot compile huge methods help? */
    static Experiment compileHugeMethods() {
        return Experiment.builder("jvm.compile-huge-methods")
                .question(
                        "Does -XX:-DontCompileHugeMethods improve frame times enough to justify"
                                + " the extra code cache and compilation work?")
                .hypothesis(
                        "Several Minecraft methods exceed HotSpot's 8000-byte HugeMethodLimit and"
                                + " therefore run interpreted forever. Letting them compile should"
                                + " help. The cost is more compilation pressure, which is why the"
                                + " matrix classifies it OPTIONAL rather than SAFE.")
                .arm(Arm.of("off", "The launcher's current default."))
                .arm(Arm.of("on", "Compile them, with the enlarged code cache the benchmarked flag"
                        + " set pairs it with.",
                        "-XX:-DontCompileHugeMethods", "-XX:ReservedCodeCacheSize=400M"))
                .arm(Arm.of("on-default-cache", "Without the enlarged cache, to find out whether"
                        + " the cache size is doing the work.", "-XX:-DontCompileHugeMethods"))
                .scenarios("pvp-map", "entity-lobby", "chunk-movement")
                .primaryMetric(
                        "Average FPS and p99 frame time after a long warm-up. Warm-up matters"
                                + " more here than anywhere else: the whole effect is about what"
                                + " eventually gets compiled.")
                .falsifiedBy(
                        "No measurable difference after a long warm-up, or a worse p99 from"
                                + " compilation pressure. Either would mean this stays off.")
                .currentBehaviour(
                        "JvmOptimizations::default() sets compile_huge_methods = false. The flag"
                                + " is available as a per-profile toggle and is off by default.")
                .note(
                        "The default warm-up of 500 frames is probably too short for this one."
                                + " A longer warm-up should be used and recorded.")
                .build();
    }

    /** Is Shenandoah even available to us? */
    static Experiment shenandoahAvailability() {
        return Experiment.builder("jvm.shenandoah-availability")
                .question(
                        "Is Shenandoah present in the OpenJDK 8 builds a user is likely to have"
                                + " (Temurin 8, Corretto 8), or only in Red Hat's?")
                .hypothesis(
                        "It is a Red Hat backport and is probably absent from Temurin and"
                                + " Corretto, which would mean collector choice on the Java 8 path"
                                + " is limited to what shipped with Java 8.")
                .arm(Arm.of("probe", "Start the JVM with the flag and observe whether it starts.",
                        "-XX:+UnlockExperimentalVMOptions", "-XX:+UseShenandoahGC"))
                .arm(Arm.of("control", "Start without it, to confirm the probe is measuring the"
                        + " flag and not something else."))
                .scenarios("entity-lobby")
                .primaryMetric(
                        "Whether the JVM starts at all, and what GarbageCollectorMXBean reports"
                                + " for collector names afterwards. This is a capability check"
                                + " before it is a performance one.")
                .falsifiedBy(
                        "The JVM starting with Shenandoah active on a stock Temurin 8, which would"
                                + " open a collector option performance.md §5.3 assumes is closed.")
                .currentBehaviour(
                        "Not attempted. The launcher passes no collector flag other than G1.")
                .note(
                        "performance.md open question 1. Listed as an experiment because it is"
                                + " cheap to answer and it gates whether jvm.collector has more"
                                + " arms worth testing.")
                .note(
                        "A failed start must be distinguished from a crash. The launcher's"
                                + " Outcome::BadJvmArguments classification already reports"
                                + " 'Unrecognized VM option' distinctly, which is exactly what"
                                + " this probe needs.")
                .build();
    }
}
