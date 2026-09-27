package net.fathom.optimizations.core;

import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import net.fathom.optimizations.core.bench.Scenario;
import net.fathom.optimizations.core.bench.Scenarios;
import net.fathom.optimizations.core.experiment.Experiment;
import net.fathom.optimizations.core.experiment.Experiments;
import net.fathom.optimizations.core.guard.Guard;
import net.fathom.optimizations.core.instrument.Counters;
import net.fathom.optimizations.core.instrument.FrameLog;
import net.fathom.optimizations.core.instrument.FrameStatistics;
import net.fathom.optimizations.core.instrument.Report;
import net.fathom.optimizations.core.instrument.Session;
import net.fathom.optimizations.core.registry.Category;
import net.fathom.optimizations.core.registry.Compatibility;
import net.fathom.optimizations.core.registry.Conflict;
import net.fathom.optimizations.core.registry.Evidence;
import net.fathom.optimizations.core.registry.Optimization;
import net.fathom.optimizations.core.registry.OptimizationId;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.Preset;
import net.fathom.optimizations.core.registry.SwitchBoard;

/**
 * Tests for everything in {@code core}, which has no Minecraft or Fabric dependencies and
 * therefore runs anywhere a JDK does.
 *
 * <p>A hand-rolled runner rather than JUnit: this module has no dependencies outside the
 * JDK, and the Gradle build that would fetch JUnit cannot run in every environment the
 * code needs to be checkable in. Under Gradle these become JUnit tests; here they run
 * with {@code java CoreTests}.
 */
public final class CoreTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        // -- registry ------------------------------------------------------
        idsRejectAnythingThatIsNotNamespaced();
        anIdIsPermanentAndComparable();
        everyRegistryEntryIsCompleteAndCoherent();
        nothingIsOnByDefaultUntilItIsMeasured();
        onlySafeOptimizationsMayEverDefaultOn();
        aConflictMustSayWhatGoesWrong();
        compatibilityRefusesTheWrongVersionOrLoader();

        // -- switching -----------------------------------------------------
        everyOptimizationIsIndividuallySwitchable();
        aUserChoiceSurvivesAPreset();
        theBoardReportsThePresetItMatches();
        aConflictDoesNotOverrideADeliberateUserChoice();
        anIncompatibilityOverridesEverything();
        resolvedConflictsAreOfferedBack();
        onlyUserChoicesArePersisted();
        anUnknownSavedIdIsSkippedNotFatal();
        presetsNeverIncludeExperimental();
        anUnregisteredIdReadsAsOffRatherThanThrowing();

        // -- frame statistics ----------------------------------------------
        averageFpsIsFramesOverTimeNotMeanOfRates();
        percentilesUseNearestRankAndNeverInventAFrame();
        lowFpsIsTheMeanOfTheWorstSliceNotAPercentile();
        aTooSmallSliceReportsZeroRatherThanASingleFrame();
        emptyStatisticsAreEmptyNotZeroFps();
        aStutterMovesTheLowsButNotTheAverage();

        // -- frame log -----------------------------------------------------
        theFrameLogAllocatesOnceAndWraps();
        nonPositiveFrameTimesAreIgnored();

        // -- session and report --------------------------------------------
        warmupFramesAreDiscarded();
        aReportCarriesItsOwnCaveats();
        aReportSerialisesToParseableJson();

        // -- counters ------------------------------------------------------
        countersDrainAtomicallyEnough();

        // -- scenarios -----------------------------------------------------
        allSevenScenariosExist();
        theProbeCostsNothingWhenItIsOff();
        theProbeAttributesTimeToSections();
        anEnvironmentRefusesAComparisonWhoseSettingsDiffer();
        theSixConfigurationsAreDistinctAndNameTheOverheadPair();
        aRunIsRefusedWhenItCannotBeTrusted();
        aReportCarriesWhatItWasAndWhereTheTimeWent();
        anythingNotAutomatableStatesItsLimitations();
        onlyReproducibleScenariosClaimComparability();

        // -- experiments ---------------------------------------------------
        everyContestedJvmClaimHasAnExperiment();
        everyExperimentIsFalsifiable();
        everyExperimentNamesRealScenarios();

        // -- guards --------------------------------------------------------
        aFailingOptimizationFallsBackToVanilla();
        aRepeatedlyFailingOptimizationIsSwitchedOff();
        aDisabledOptimizationRunsVanillaWithoutTrying();
        vanillaFailingIsNotAbsorbed();
        aNullResultCountsAsAFailure();
        guardsCatchErrorsNotJustExceptions();

        report();
    }

    // =======================================================================
    // Registry
    // =======================================================================

    static void idsRejectAnythingThatIsNotNamespaced() {
        for (String good : new String[] {"render.font.batching", "cpu.allocation.hot-path", "a.b"}) {
            OptimizationId.of(good);
        }
        for (String bad :
                new String[] {"", "nodots", "Render.Font", "render..font", ".leading", "trailing.",
                        "render font", "render/font", "render.font.", "-x.y"}) {
            expectThrows("id " + bad, new Runnable() {
                private String value = bad;

                @Override
                public void run() {
                    OptimizationId.of(value);
                }
            });
        }
        pass("ids reject anything that is not namespaced");
    }

    static void anIdIsPermanentAndComparable() {
        OptimizationId a = OptimizationId.of("render.font.batching");
        OptimizationId b = OptimizationId.of("render.font.batching");
        check(a.equals(b), "equal ids compare equal");
        check(a.hashCode() == b.hashCode(), "equal ids hash equal");
        check(a.namespace().equals("render"), "namespace is the leading segment");
        check(a.compareTo(OptimizationId.of("zzz.x")) < 0, "ids sort");
        pass("an id is permanent and comparable");
    }

    static void everyRegistryEntryIsCompleteAndCoherent() {
        OptimizationRegistry registry = OptimizationRegistry.standard();
        check(registry.size() > 0, "the registry is not empty");

        Set<String> seen = new HashSet<String>();
        for (Optimization entry : registry.all()) {
            check(seen.add(entry.id().value()), "duplicate id " + entry.id());
            check(!entry.name().trim().isEmpty(), entry.id() + " has a name");
            check(entry.description().length() > 30, entry.id() + " has a real description");
            check(!entry.expectedBenefit().trim().isEmpty(), entry.id() + " states a benefit");
            check(!entry.references().isEmpty(), entry.id() + " cites something");
            check(
                    entry.compatibility().applies("1.8.9", "legacy-fabric", 8),
                    entry.id() + " applies to the target platform");
            check(!entry.defaultExplanation().trim().isEmpty(), entry.id() + " explains its default");
        }
        pass("every registry entry is complete and coherent");
    }

    /**
     * The milestone rule: benchmark before defaults.
     *
     * <p>This used to assert that no entry had an in-game measurement at all,
     * which was true while the Fabric layer had never run. It now has, so the
     * rule is narrowed to what must stay true: in-game evidence only on an entry
     * a Mixin actually runs, and nothing on by default without it.
     */
    static void nothingIsOnByDefaultUntilItIsMeasured() {
        OptimizationRegistry registry = OptimizationRegistry.standard();
        for (Optimization entry : registry.all()) {
            if (entry.measuredInGame()) {
                check(entry.runsInGame(),
                        entry.id() + " claims an in-game measurement but nothing in the game runs it");
            }
            if (entry.defaultEnabled()) {
                check(entry.measuredInGame(),
                        entry.id() + " is on by default without an in-game measurement");
            }
            if (entry.evidence() != null) {
                check(entry.implementedInCore(),
                        entry.id() + " has evidence but no implementation to have measured");
            }
        }

        // And the mechanism, not just the current state: an entry that asks to default on
        // still does not until it is measured.
        Optimization wants =
                Optimization.builder("render.test.thing")
                        .name("x")
                        .description("a description long enough to be a real one")
                        .category(Category.SAFE)
                        .basis(Optimization.Basis.VANILLA_ANALYSIS)
                        .expectedBenefit("something")
                        .defaultOnWhenMeasured()
                        .build();
        check(!wants.defaultEnabled(), "unmeasured SAFE does not default on");

        Optimization measured =
                Optimization.builder("render.test.thing")
                        .name("x")
                        .description("a description long enough to be a real one")
                        .category(Category.SAFE)
                        .basis(Optimization.Basis.VANILLA_ANALYSIS)
                        .expectedBenefit("something")
                        .defaultOnWhenMeasured()
                        .evidence(Evidence.isolated("run-1", "faster in a loop"))
                        .build();
        // An isolated measurement is real evidence that the implementation works, and is
        // still not grounds for turning anything on. This is the distinction the old
        // boolean could not express, and the one that stops a microbenchmark becoming an
        // FPS claim on the way to a changelog.
        check(!measured.defaultEnabled(), "an isolated measurement does not promote anything");
        check(measured.measured(), "though it counts as having been measured at all");
        check(!measured.measuredInGame(), "just not in the game");

        Optimization inGame =
                Optimization.builder("render.test.thing")
                        .name("x")
                        .description("a description long enough to be a real one")
                        .category(Category.SAFE)
                        .basis(Optimization.Basis.VANILLA_ANALYSIS)
                        .expectedBenefit("something")
                        .defaultOnWhenMeasured()
                        .evidence(Evidence.inGame("run-2", "frame times moved in entity-lobby"))
                        .build();
        check(inGame.defaultEnabled(), "an in-game measurement does promote it");
        pass("nothing is on by default until it is measured in the game");
    }

    static void onlySafeOptimizationsMayEverDefaultOn() {
        check(Category.SAFE.mayDefaultOn(), "SAFE may default on");
        check(!Category.OPTIONAL.mayDefaultOn(), "OPTIONAL may not");
        check(!Category.EXPERIMENTAL.mayDefaultOn(), "EXPERIMENTAL may not");

        for (final Category category : new Category[] {Category.OPTIONAL, Category.EXPERIMENTAL}) {
            expectThrows("defaultOn with " + category, new Runnable() {
                @Override
                public void run() {
                    Optimization.builder("render.test.thing")
                            .name("x")
                            .description("a description long enough to be a real one")
                            .category(category)
                            .basis(Optimization.Basis.VANILLA_ANALYSIS)
                            .expectedBenefit("something")
                            .defaultOnWhenMeasured()
                            .build();
                }
            });
        }
        pass("only SAFE optimizations may ever default on");
    }

    static void aConflictMustSayWhatGoesWrong() {
        expectThrows("empty conflict detail", new Runnable() {
            @Override
            public void run() {
                Conflict.warn("OptiFine", "   ");
            }
        });
        Conflict conflict = Conflict.disableOurs("OptiFine", "it rewrites the font renderer");
        check(conflict.resolution() == Conflict.Resolution.DISABLE_OURS, "default posture is ours off");
        pass("a conflict must say what goes wrong");
    }

    static void compatibilityRefusesTheWrongVersionOrLoader() {
        Compatibility baseline = Compatibility.baseline();
        check(baseline.applies("1.8.9", "legacy-fabric", 8), "the target platform applies");
        check(!baseline.applies("1.12.2", "legacy-fabric", 8), "a different version does not");
        check(!baseline.applies("1.8.9", "forge", 8), "a different loader does not");
        check(!baseline.applies("1.8.9", "legacy-fabric", 7), "an older JVM does not");
        check(
                baseline.whyNotApplicable("1.12.2", "legacy-fabric", 8).contains("1.8.9"),
                "and it says why");
        pass("compatibility refuses the wrong version or loader");
    }

    // =======================================================================
    // Switching
    // =======================================================================

    private static SwitchBoard board() {
        return SwitchBoard.defaults(OptimizationRegistry.standard());
    }

    static void everyOptimizationIsIndividuallySwitchable() {
        SwitchBoard switches = board();
        for (Optimization entry : switches.registry().all()) {
            check(switches.setByUser(entry.id(), true), entry.id() + " can be switched on");
            check(switches.isEnabled(entry.id()), entry.id() + " is on");
            check(switches.setByUser(entry.id(), false), entry.id() + " can be switched off");
            check(!switches.isEnabled(entry.id()), entry.id() + " is off");
            check(!switches.setByUser(entry.id(), false), "setting the same value is a no-op");
        }
        pass("every optimization is individually switchable");
    }

    static void aUserChoiceSurvivesAPreset() {
        SwitchBoard switches = board();
        OptimizationId id = switches.registry().all().get(0).id();

        switches.setByUser(id, true);
        switches.applyPreset(Preset.VANILLA);

        check(switches.isEnabled(id), "a preset must not override a user's explicit choice");
        check(switches.state(id).chosenByUser(), "and the reason is still USER");
        pass("a user choice survives a preset");
    }

    static void theBoardReportsThePresetItMatches() {
        SwitchBoard switches = board();
        switches.applyPreset(Preset.VANILLA);
        check(switches.matchingPreset() == Preset.VANILLA, "a freshly applied preset is the one in effect");

        OptimizationId id = switches.registry().all().get(0).id();
        switches.setByUser(id, true);
        check(switches.matchingPreset() != Preset.VANILLA, "one switch changed by hand is no longer that preset");
        pass("the board reports the preset it matches");
    }

    static void aConflictDoesNotOverrideADeliberateUserChoice() {
        SwitchBoard switches = board();
        OptimizationId id = switches.registry().all().get(0).id();

        switches.setByUser(id, true);
        boolean moved = switches.disableForConflict(id, "OptiFine is present");

        check(!moved, "a conflict must not silently undo a deliberate choice");
        check(switches.isEnabled(id), "it stays on");
        pass("a conflict does not override a deliberate user choice");
    }

    static void anIncompatibilityOverridesEverything() {
        SwitchBoard switches = board();
        OptimizationId id = switches.registry().all().get(0).id();

        switches.setByUser(id, true);
        switches.disableAsIncompatible(id, "wrong Minecraft version");

        check(!switches.isEnabled(id), "there is nothing for it to attach to, so preference cannot help");
        check(switches.state(id).offAutomatically(), "and it is marked as ours, not the user's");
        pass("an incompatibility overrides everything");
    }

    static void resolvedConflictsAreOfferedBack() {
        SwitchBoard switches = board();
        OptimizationId withOptifine = null;
        for (Optimization entry : switches.registry().all()) {
            for (Conflict conflict : entry.conflicts()) {
                if (conflict.with().equals("OptiFine")) {
                    withOptifine = entry.id();
                    break;
                }
            }
            if (withOptifine != null) {
                break;
            }
        }
        check(withOptifine != null, "the registry has an OptiFine conflict to test with");

        switches.disableForConflict(withOptifine, "OptiFine present");
        check(!switches.isEnabled(withOptifine), "off while OptiFine is present");

        List<OptimizationId> released =
                switches.releaseResolvedConflicts(new ArrayList<String>());
        check(released.contains(withOptifine), "offered back once OptiFine is gone");

        // A user's own "off" must not be swept up by that.
        SwitchBoard other = board();
        other.setByUser(withOptifine, false);
        check(
                !other.releaseResolvedConflicts(new ArrayList<String>()).contains(withOptifine),
                "a user's own off is not released");
        pass("resolved conflicts are offered back");
    }

    static void onlyUserChoicesArePersisted() {
        SwitchBoard switches = board();
        List<Optimization> all = switches.registry().all();

        switches.setByUser(all.get(0).id(), true);
        switches.disableForConflict(all.get(1).id(), "OptiFine");
        switches.applyPreset(Preset.SAFE_INCLUDING_UNMEASURED);

        Map<OptimizationId, Boolean> saved = switches.userChoices();
        check(saved.size() == 1, "only the user's own choice is saved, got " + saved.size());
        check(saved.containsKey(all.get(0).id()), "and it is the right one");
        pass("only user choices are persisted");
    }

    static void anUnknownSavedIdIsSkippedNotFatal() {
        SwitchBoard switches = board();
        Map<String, Boolean> saved = new LinkedHashMap<String, Boolean>();
        saved.put(switches.registry().all().get(0).id().value(), Boolean.TRUE);
        saved.put("render.removed.thing", Boolean.TRUE);
        saved.put("not a valid id at all", Boolean.TRUE);

        List<String> unknown = switches.restoreUserChoices(saved);

        check(unknown.size() == 2, "both unknown ids reported, got " + unknown);
        check(
                switches.isEnabled(switches.registry().all().get(0).id()),
                "the known one still restored");
        pass("an unknown saved id is skipped, not fatal");
    }

    static void presetsNeverIncludeExperimental() {
        OptimizationRegistry registry = OptimizationRegistry.standard();
        List<Optimization> experimental = registry.inCategory(Category.EXPERIMENTAL);
        check(!experimental.isEmpty(), "there is an experimental entry to test with");

        for (Preset preset : Preset.values()) {
            check(!preset.mayIncludeExperimental(), preset + " must not include experimental");
            for (Optimization entry : experimental) {
                check(
                        !preset.includes(entry),
                        preset + " includes experimental " + entry.id());
            }
            check(!preset.description().trim().isEmpty(), preset + " describes itself");
        }
        pass("presets never include experimental");
    }

    static void anUnregisteredIdReadsAsOffRatherThanThrowing() {
        SwitchBoard switches = board();
        // A Mixin guarded on a stale id should do nothing, not crash the game.
        check(!switches.isEnabled(OptimizationId.of("render.gone.away")), "unknown reads as off");
        pass("an unregistered id reads as off rather than throwing");
    }

    // =======================================================================
    // Frame statistics
    // =======================================================================

    private static long ms(double millis) {
        return (long) (millis * 1_000_000L);
    }

    static void averageFpsIsFramesOverTimeNotMeanOfRates() {
        // One slow frame among fast ones. Mean-of-rates would be misleadingly high.
        long[] frames = new long[10];
        for (int i = 0; i < 9; i++) {
            frames[i] = ms(5.0); // 200 fps
        }
        frames[9] = ms(955.0); // one very long frame; total is exactly 1000 ms

        FrameStatistics stats = FrameStatistics.of(frames);
        check(
                Math.abs(stats.totalSeconds() - 1.0) < 1e-9,
                "total is 1 second, got " + stats.totalSeconds());
        check(
                Math.abs(stats.averageFps() - 10.0) < 1e-6,
                "10 frames in 1 second is 10 fps, got " + stats.averageFps());

        // Mean of per-frame rates would be (9*200 + 1.047)/10 = about 180. Very different.
        check(stats.averageFps() < 20.0, "average fps must not be the mean of rates");
        pass("average fps is frames over time, not the mean of rates");
    }

    static void percentilesUseNearestRankAndNeverInventAFrame() {
        long[] frames = new long[100];
        for (int i = 0; i < 100; i++) {
            frames[i] = ms(i + 1);
        }
        FrameStatistics stats = FrameStatistics.of(frames);

        check(near(stats.medianFrameTimeMillis(), 50.0), "median is 50, got " + stats.medianFrameTimeMillis());
        check(near(stats.p95FrameTimeMillis(), 95.0), "p95 is 95, got " + stats.p95FrameTimeMillis());
        check(near(stats.p99FrameTimeMillis(), 99.0), "p99 is 99, got " + stats.p99FrameTimeMillis());
        check(near(stats.minFrameTimeMillis(), 1.0), "min is 1");
        check(near(stats.maxFrameTimeMillis(), 100.0), "max is 100");

        // Every reported percentile is a frame that actually occurred.
        Set<Double> actual = new HashSet<Double>();
        for (int i = 1; i <= 100; i++) {
            actual.add((double) i);
        }
        for (double p : new double[] {50.0, 90.0, 95.0, 99.0}) {
            double value = stats.percentileFrameTimeMillis(p);
            check(
                    actual.contains(Math.round(value * 1000.0) / 1000.0),
                    "p" + p + " = " + value + " is not a frame that occurred");
        }
        pass("percentiles use nearest rank and never invent a frame");
    }

    static void lowFpsIsTheMeanOfTheWorstSliceNotAPercentile() {
        // 1000 frames: 990 at 5 ms, 10 at 100 ms.
        long[] frames = new long[1000];
        for (int i = 0; i < 990; i++) {
            frames[i] = ms(5.0);
        }
        for (int i = 990; i < 1000; i++) {
            frames[i] = ms(100.0);
        }
        FrameStatistics stats = FrameStatistics.of(frames);

        // The worst 1% is exactly those 10 frames, mean 100 ms, so 10 fps.
        check(near(stats.onePercentLowFps(), 10.0), "1% low is 10 fps, got " + stats.onePercentLowFps());

        // p99 frame time, on the same data, is 5 ms — and that is correct. Exactly 99%
        // of frames are 5 ms, so the 99th percentile lands on the last fast one. Half a
        // percent further up, it is 100 ms.
        //
        // This is the whole reason both metrics exist and the whole reason conflating
        // them is dangerous: on identical data, "1% low = 10 fps" and "p99 = 5 ms" are
        // both true and describe the same stutter completely differently.
        check(
                near(stats.p99FrameTimeMillis(), 5.0),
                "p99 sits on the boundary at 5 ms, got " + stats.p99FrameTimeMillis());
        check(
                near(stats.percentileFrameTimeMillis(99.5), 100.0),
                "p99.5 is inside the slow frames, got "
                        + stats.percentileFrameTimeMillis(99.5));
        pass("low fps is the mean of the worst slice, not a percentile");
    }

    static void aTooSmallSliceReportsZeroRatherThanASingleFrame() {
        long[] frames = new long[200];
        Arrays.fill(frames, ms(5.0));
        FrameStatistics stats = FrameStatistics.of(frames);

        // 0.1% of 200 is 0.2 frames, which floors to zero.
        check(stats.pointOnePercentLowFps() == 0.0, "too small a slice reports zero");
        check(!stats.hasEnoughSamplesFor(0.001), "and says it does not have enough samples");
        check(
                !stats.hasEnoughSamplesFor(0.01),
                "1% of 200 frames is 2 frames, which is also not enough to mean anything");
        pass("a too-small slice reports zero rather than a single frame");
    }

    static void emptyStatisticsAreEmptyNotZeroFps() {
        FrameStatistics empty = FrameStatistics.of(new long[0]);
        check(empty.isEmpty(), "no samples is empty");
        check(empty.sampleCount() == 0, "zero samples");
        check(empty.averageFps() == 0.0, "and zero fps rather than a division by zero");
        check(FrameStatistics.of(null).isEmpty(), "null is empty too");
        pass("empty statistics are empty, not zero fps");
    }

    /** The reason percentile metrics exist at all. */
    static void aStutterMovesTheLowsButNotTheAverage() {
        long[] smooth = new long[1000];
        Arrays.fill(smooth, ms(10.0));

        long[] stuttery = new long[1000];
        Arrays.fill(stuttery, ms(9.0));
        for (int i = 0; i < 100; i++) {
            stuttery[i * 10] = ms(19.0); // one bad frame in ten
        }

        FrameStatistics a = FrameStatistics.of(smooth);
        FrameStatistics b = FrameStatistics.of(stuttery);

        check(near(a.averageFps(), b.averageFps(), 0.5), "the two have the same average fps");
        check(
                b.onePercentLowFps() < a.onePercentLowFps(),
                "but the stuttery one has worse lows");
        check(
                b.frameTimeCoefficientOfVariation() > a.frameTimeCoefficientOfVariation(),
                "and more variance");
        pass("a stutter moves the lows but not the average");
    }

    // =======================================================================
    // Frame log
    // =======================================================================

    static void theFrameLogAllocatesOnceAndWraps() {
        FrameLog log = new FrameLog(10);
        for (int i = 1; i <= 10; i++) {
            log.record(ms(i));
        }
        check(log.size() == 10, "full");
        check(!log.wrapped(), "not yet wrapped");

        log.record(ms(11));
        check(log.size() == 10, "still 10 after wrapping");
        check(log.wrapped(), "and it says so");

        long[] samples = log.samples();
        check(samples[0] == ms(2), "oldest sample was dropped, got " + samples[0]);
        check(samples[9] == ms(11), "newest sample is last");
        pass("the frame log allocates once and wraps");
    }

    static void nonPositiveFrameTimesAreIgnored() {
        FrameLog log = new FrameLog(10);
        log.record(0L);
        log.record(-5L);
        check(log.size() == 0, "a clock going backwards must not drag percentiles down");
        log.record(ms(10));
        check(log.size() == 1, "a real frame is recorded");
        pass("non-positive frame times are ignored");
    }

    // =======================================================================
    // Session and report
    // =======================================================================

    static void warmupFramesAreDiscarded() {
        Session session = new Session("test", 5, 100);
        session.start();
        check(!session.warmupComplete(), "warm-up not complete at start");

        for (int i = 0; i < 5; i++) {
            session.frame(ms(100.0)); // deliberately slow "cold" frames
        }
        check(session.warmupComplete(), "warm-up complete after 5 frames");

        for (int i = 0; i < 10; i++) {
            session.frame(ms(10.0));
        }
        Report report = session.stop();

        check(report.frames().sampleCount() == 10, "only measured frames counted, got "
                + report.frames().sampleCount());
        check(
                near(report.frames().meanFrameTimeMillis(), 10.0),
                "the cold frames did not drag the mean, got " + report.frames().meanFrameTimeMillis());
        pass("warm-up frames are discarded");
    }

    static void aReportCarriesItsOwnCaveats() {
        Session session = new Session("tiny", 0, 10);
        session.start();
        for (int i = 0; i < 5; i++) {
            session.frame(ms(10.0));
        }
        Report report = session.stop();

        List<String> caveats = report.caveats();
        check(!caveats.isEmpty(), "a five-frame run has caveats");

        boolean mentionsSmallSlice = false;
        boolean mentionsGc = false;
        for (String caveat : caveats) {
            if (caveat.contains("0.1% low")) {
                mentionsSmallSlice = true;
            }
            if (caveat.contains("Garbage collection")) {
                mentionsGc = true;
            }
        }
        check(mentionsSmallSlice, "it says the 0.1% low is meaningless at this sample count");
        check(mentionsGc, "and it states the GC API's limitations");

        Report empty = emptyReport();
        check(
                empty.caveats().get(0).contains("not because the game was infinitely fast"),
                "an empty run says why its numbers are zero");
        pass("a report carries its own caveats");
    }

    private static Report emptyReport() {
        Session session = new Session("empty", 0, 10);
        session.start();
        return session.stop();
    }

    static void aReportSerialisesToParseableJson() {
        Session session = new Session("json", 0, 100);
        session.start();
        for (int i = 0; i < 50; i++) {
            session.frame(ms(10.0 + (i % 3)));
        }
        session.counters().add(Counters.Counter.CHUNK_REBUILDS, 7);
        session.counters().add(Counters.Counter.GLYPHS, 100);
        session.counters().add(Counters.Counter.TEXT_RUNS, 10);
        Report report = session.stop();

        String json = report.toJson();
        check(json.startsWith("{"), "starts as an object");
        check(json.trim().endsWith("}"), "ends as one");
        check(braced(json), "braces and brackets balance");
        check(json.contains("\"averageFps\""), "carries the headline metric");
        check(json.contains("\"caveats\""), "and its caveats");
        check(!json.contains("NaN"), "no NaN, which is not valid JSON");
        check(!json.contains("Infinity"), "no Infinity either");
        check(near(report.glyphsPerTextRun(), 10.0), "glyphs per run is computed");
        pass("a report serialises to parseable json");
    }

    private static boolean braced(String json) {
        int curly = 0;
        int square = 0;
        boolean inString = false;
        boolean escaped = false;
        for (int i = 0; i < json.length(); i++) {
            char c = json.charAt(i);
            if (escaped) {
                escaped = false;
                continue;
            }
            if (c == '\\') {
                escaped = true;
                continue;
            }
            if (c == '"') {
                inString = !inString;
                continue;
            }
            if (inString) {
                continue;
            }
            if (c == '{') {
                curly++;
            } else if (c == '}') {
                curly--;
            } else if (c == '[') {
                square++;
            } else if (c == ']') {
                square--;
            }
            if (curly < 0 || square < 0) {
                return false;
            }
        }
        return curly == 0 && square == 0 && !inString;
    }

    static void countersDrainAtomicallyEnough() {
        Counters counters = new Counters();
        counters.increment(Counters.Counter.CHUNK_REBUILDS);
        counters.add(Counters.Counter.GLYPHS, 500);

        Map<Counters.Counter, Long> drained = counters.drain();
        check(drained.get(Counters.Counter.CHUNK_REBUILDS) == 1L, "increment counted");
        check(drained.get(Counters.Counter.GLYPHS) == 500L, "add counted");
        check(counters.get(Counters.Counter.GLYPHS) == 0L, "drain resets");
        check(drained.get(Counters.Counter.PARTICLES_RENDERED) == 0L, "untouched counters are zero");
        pass("counters drain atomically enough");
    }

    // =======================================================================
    // The profiler and the harness
    // =======================================================================

    static void theProbeCostsNothingWhenItIsOff() {
        net.fathom.optimizations.core.instrument.Probe.end();
        net.fathom.optimizations.core.instrument.Probe.reset();

        // `start` returns zero when off, and `stop` on a zero records nothing.
        // That is what makes the call site free: the whole of it folds away.
        long at = net.fathom.optimizations.core.instrument.Probe.start();
        check(at == 0L, "an off probe does not read the clock");
        net.fathom.optimizations.core.instrument.Probe.stop(
                net.fathom.optimizations.core.instrument.Probe.Section.HUD, at);
        check(net.fathom.optimizations.core.instrument.Probe.calls(
                net.fathom.optimizations.core.instrument.Probe.Section.HUD) == 0L,
                "and nothing was recorded");
        pass("the probe costs nothing when it is off");
    }

    static void theProbeAttributesTimeToSections() {
        net.fathom.optimizations.core.instrument.Probe.begin();
        try {
            for (int frame = 0; frame < 3; frame++) {
                long hud = net.fathom.optimizations.core.instrument.Probe.start();
                spin(200_000L);
                net.fathom.optimizations.core.instrument.Probe.stop(
                        net.fathom.optimizations.core.instrument.Probe.Section.HUD, hud);

                long modules = net.fathom.optimizations.core.instrument.Probe.start();
                net.fathom.optimizations.core.instrument.Probe.stop(
                        net.fathom.optimizations.core.instrument.Probe.Section.MODULES, modules);

                net.fathom.optimizations.core.instrument.Probe.frameEnded();
            }
        } finally {
            net.fathom.optimizations.core.instrument.Probe.end();
        }

        check(net.fathom.optimizations.core.instrument.Probe.frames() == 3L, "three frames");
        check(net.fathom.optimizations.core.instrument.Probe.calls(
                net.fathom.optimizations.core.instrument.Probe.Section.HUD) == 3L,
                "three HUD sections");
        check(net.fathom.optimizations.core.instrument.Probe.meanMillis(
                net.fathom.optimizations.core.instrument.Probe.Section.HUD) > 0d,
                "and the HUD section took time");
        // The section that did nothing must not have borrowed the other's time.
        check(net.fathom.optimizations.core.instrument.Probe.meanMillis(
                net.fathom.optimizations.core.instrument.Probe.Section.HUD)
                        > net.fathom.optimizations.core.instrument.Probe.meanMillis(
                                net.fathom.optimizations.core.instrument.Probe.Section.MODULES),
                "time lands in the section that spent it");
        check(net.fathom.optimizations.core.instrument.Probe.worstFrameMillis() > 0d,
                "and a worst frame was recorded");

        net.fathom.optimizations.core.instrument.Probe.reset();
        pass("the probe attributes time to sections");
    }

    /** Busy work, so a section has something to measure. */
    private static void spin(long nanos) {
        long until = System.nanoTime() + nanos;
        while (System.nanoTime() < until) {
            // Deliberately empty. Sleeping would measure the scheduler.
        }
    }

    static void anEnvironmentRefusesAComparisonWhoseSettingsDiffer() {
        net.fathom.optimizations.core.bench.Environment first =
                new net.fathom.optimizations.core.bench.Environment();
        net.fathom.optimizations.core.bench.Environment second =
                new net.fathom.optimizations.core.bench.Environment();
        for (String field : net.fathom.optimizations.core.bench.Environment.FIELDS) {
            first.set(field, "same");
            second.set(field, "same");
        }
        check(first.differencesFrom(second).isEmpty(), "identical settings compare");
        check(first.missing().isEmpty(), "and nothing is unrecorded");

        second.set("renderDistance", "4");
        java.util.List<String> differences = first.differencesFrom(second);
        check(differences.size() == 1, "one difference: " + differences);
        check(differences.get(0).contains("renderDistance"), "and it names the field");

        // The Fathom version is expected to differ between a baseline and a
        // candidate -- that is the point of the comparison -- so it must not
        // block one.
        second.set("renderDistance", "same");
        second.set("fathomVersion", "0.4.0");
        check(first.differencesFrom(second).isEmpty(),
                "the version under test is allowed to differ");
        pass("an environment refuses a comparison whose settings differ");
    }

    static void theSixConfigurationsAreDistinctAndNameTheOverheadPair() {
        java.util.List<net.fathom.optimizations.core.bench.Configuration> all =
                net.fathom.optimizations.core.bench.Configuration.all();
        check(all.size() == 6, "six configurations, got " + all.size());

        java.util.List<String> ids = new java.util.ArrayList<String>();
        for (net.fathom.optimizations.core.bench.Configuration configuration : all) {
            check(!ids.contains(configuration.id()), "duplicate id " + configuration.id());
            ids.add(configuration.id());
            check(!configuration.setup().trim().isEmpty(),
                    configuration.id() + " says how to set it up");
            check(!configuration.purpose().trim().isEmpty(),
                    configuration.id() + " says what it is for");
        }

        // The pair whose difference is Fathom's own overhead. Named rather than
        // left to a reader: comparing D against A instead would fold the
        // loader's cost into Fathom's.
        java.util.List<String> pair =
                net.fathom.optimizations.core.bench.Configuration.overheadPair();
        check(pair.size() == 2, "the overhead pair is a pair");
        check("b-loader".equals(pair.get(0)) && "d-performance-off".equals(pair.get(1)),
                "and it is the loader against Fathom-with-nothing-on: " + pair);

        // The two that Fathom cannot measure, because Fathom is not in them.
        check(net.fathom.optimizations.core.bench.Configuration.byId("a-vanilla").stack()
                        == net.fathom.optimizations.core.bench.Configuration.Stack.VANILLA,
                "A is vanilla");
        check(net.fathom.optimizations.core.bench.Configuration.byId("b-loader").stack()
                        == net.fathom.optimizations.core.bench.Configuration.Stack.LOADER_ONLY,
                "B is the loader alone");
        pass("the six configurations are distinct and name the overhead pair");
    }

    static void aRunIsRefusedWhenItCannotBeTrusted() {
        // Every one of these has happened while building this milestone, and
        // each produces a number that looks fine and means nothing.
        net.fathom.optimizations.core.instrument.Session session =
                new net.fathom.optimizations.core.instrument.Session("t", 0, 4096);
        session.configuration("d-performance-off");
        net.fathom.optimizations.core.bench.Environment environment =
                new net.fathom.optimizations.core.bench.Environment();
        for (String field : net.fathom.optimizations.core.bench.Environment.FIELDS) {
            environment.set(field, "recorded");
        }
        session.environment(environment);
        session.start();
        for (int frame = 0; frame < 2000; frame++) {
            session.frame(5_000_000L);
        }
        net.fathom.optimizations.core.instrument.Report report = session.stop();

        String lostFocus = net.fathom.optimizations.core.bench.Run.rejectionReason(report, false);
        check(lostFocus != null && lostFocus.contains("focus"),
                "a run that lost focus is refused: " + lostFocus);

        // The run above is two thousand frames but only milliseconds of wall
        // clock, so the duration check catches it -- which is the point: a
        // short run cannot support a 1% low.
        String tooShort = net.fathom.optimizations.core.bench.Run.rejectionReason(report, true);
        check(tooShort != null, "a run shorter than the minimum is refused: " + tooShort);
        pass("a run is refused when it cannot be trusted");
    }

    static void aReportCarriesWhatItWasAndWhereTheTimeWent() {
        net.fathom.optimizations.core.instrument.Session session =
                new net.fathom.optimizations.core.instrument.Session("t", 0, 512);
        session.configuration("c-core").scenario("cpu-ceiling");
        session.start();
        for (int frame = 0; frame < 10; frame++) {
            long at = net.fathom.optimizations.core.instrument.Probe.start();
            spin(100_000L);
            net.fathom.optimizations.core.instrument.Probe.stop(
                    net.fathom.optimizations.core.instrument.Probe.Section.HUD, at);
            net.fathom.optimizations.core.instrument.Probe.frameEnded();
            session.frame(5_000_000L);
        }
        net.fathom.optimizations.core.instrument.Report report = session.stop();

        check("c-core".equals(report.configuration()), "the configuration is recorded");
        check("cpu-ceiling".equals(report.scenario()), "and the scenario");
        check(report.sections().containsKey("hud"), "sections are reported by name");
        check(report.fathomMillisPerFrame() > 0d, "and Fathom's own cost is a number");

        String json = report.toJson();
        check(json.contains(QUOTE + "configuration" + QUOTE), "the json says which configuration");
        check(json.contains(QUOTE + "sections" + QUOTE), "and where the time went");
        check(json.contains(QUOTE + "environment" + QUOTE), "and under what settings");
        pass("a report carries what it was and where the time went");
    }

    /** One double quote, so the assertions above read without escaping. */
    private static final String QUOTE = String.valueOf((char) 34);

    // =======================================================================
    // Scenarios
    // =======================================================================

    static void allSevenScenariosExist() {
        List<Scenario> scenarios = Scenarios.all();
        // Nine now: the performance milestone added the CPU ceiling and the
        // still singleplayer scene. The first is the one the overhead threshold
        // is written against -- a trivial scene, where frame time is CPU work
        // rather than drawing -- and without it a comparison cannot separate
        // Fathom's cost from the machine's.
        check(scenarios.size() == 9, "expected nine scenarios, got " + scenarios.size());

        String[] expected = {
            "cpu-ceiling", "singleplayer-still",
            "pvp-map", "entity-lobby", "text-heavy", "particles", "chunk-movement",
            "inventory-gui", "resource-pack"
        };
        for (String id : expected) {
            check(Scenarios.byId(id) != null, "scenario " + id + " exists");
        }
        for (Scenario scenario : scenarios) {
            check(!scenario.setup().trim().isEmpty(), scenario.id() + " says how to set it up");
            check(!scenario.primaryCounters().isEmpty(), scenario.id() + " names its counters");
            check(scenario.recommendedSeconds() > 0, scenario.id() + " has a duration");
        }
        pass("all seven scenarios exist");
    }

    static void anythingNotAutomatableStatesItsLimitations() {
        for (Scenario scenario : Scenarios.all()) {
            if (scenario.reproducibility() == Scenario.Reproducibility.AUTOMATABLE) {
                continue;
            }
            check(
                    !scenario.limitations().isEmpty(),
                    scenario.id() + " is not automatable and must say why");
        }
        // And the one we genuinely cannot reproduce is marked as such rather than omitted.
        Scenario pack = Scenarios.byId("resource-pack");
        check(
                pack.reproducibility() == Scenario.Reproducibility.NOT_YET_REPRODUCIBLE,
                "the resource pack scenario is honest about not being reproducible");
        pass("anything not automatable states its limitations");
    }

    static void onlyReproducibleScenariosClaimComparability() {
        for (Scenario scenario : Scenarios.all()) {
            boolean expected =
                    scenario.reproducibility() == Scenario.Reproducibility.AUTOMATABLE
                            || scenario.reproducibility()
                                    == Scenario.Reproducibility.SCRIPTED_LOCAL;
            check(
                    scenario.comparableBetweenSessions() == expected,
                    scenario.id() + " misreports comparability");
        }
        check(Scenarios.comparable().size() < Scenarios.all().size(), "not all are comparable");
        pass("only reproducible scenarios claim comparability");
    }

    // =======================================================================
    // Experiments
    // =======================================================================

    /** The claims the milestone brief says must not be hardcoded. */
    static void everyContestedJvmClaimHasAnExperiment() {
        for (String id :
                new String[] {
                    "jvm.equal-min-max-heap", "jvm.explicit-gc", "jvm.collector", "jvm.heap-size"
                }) {
            Experiment experiment = Experiments.byId(id);
            check(experiment != null, "there is an experiment for " + id);
            check(
                    !experiment.currentBehaviour().trim().isEmpty(),
                    id + " records what the launcher does today");
        }
        pass("every contested jvm claim has an experiment");
    }

    static void everyExperimentIsFalsifiable() {
        List<Experiment> experiments = Experiments.all();
        check(!experiments.isEmpty(), "there are experiments");
        for (Experiment experiment : experiments) {
            check(
                    !experiment.falsifiedBy().trim().isEmpty(),
                    experiment.id() + " must say what would prove it wrong");
            check(experiment.arms().size() >= 2, experiment.id() + " compares at least two arms");
            check(
                    !experiment.primaryMetric().trim().isEmpty(),
                    experiment.id() + " names the deciding metric");
            for (Experiment.Arm arm : experiment.arms()) {
                check(
                        !arm.rationale().trim().isEmpty(),
                        experiment.id() + "/" + arm.label() + " justifies its inclusion");
            }
        }
        pass("every experiment is falsifiable");
    }

    static void everyExperimentNamesRealScenarios() {
        for (Experiment experiment : Experiments.all()) {
            for (String scenarioId : experiment.scenarioIds()) {
                check(
                        Scenarios.byId(scenarioId) != null,
                        experiment.id() + " names unknown scenario " + scenarioId);
            }
        }
        pass("every experiment names real scenarios");
    }

    // =======================================================================
    // Guards
    // =======================================================================

    private static final class Flag {
        boolean set;
    }

    static void aFailingOptimizationFallsBackToVanilla() {
        SwitchBoard switches = board();
        final OptimizationId id = switches.registry().all().get(0).id();
        switches.setByUser(id, true);

        Guard guard = new Guard(switches, null);
        final Flag vanillaRan = new Flag();

        guard.run(
                id,
                new Guard.Action() {
                    @Override
                    public void run() {
                        throw new IllegalStateException("boom");
                    }
                },
                new Guard.Action() {
                    @Override
                    public void run() {
                        vanillaRan.set = true;
                    }
                });

        check(vanillaRan.set, "vanilla ran after the optimization threw");
        check(guard.failureCount(id) == 1, "and the failure was recorded");
        check(switches.isEnabled(id), "one failure is not enough to switch it off");
        pass("a failing optimization falls back to vanilla");
    }

    static void aRepeatedlyFailingOptimizationIsSwitchedOff() {
        SwitchBoard switches = board();
        final OptimizationId id = switches.registry().all().get(0).id();
        switches.setByUser(id, true);

        final List<Guard.Record> tripped = new ArrayList<Guard.Record>();
        Guard guard =
                new Guard(
                        switches,
                        new Guard.Listener() {
                            @Override
                            public void tripped(Guard.Record record) {
                                tripped.add(record);
                            }
                        });

        for (int i = 0; i < Guard.TRIP_THRESHOLD; i++) {
            guard.run(
                    id,
                    new Guard.Action() {
                        @Override
                        public void run() {
                            throw new IllegalStateException("failure " + System.nanoTime());
                        }
                    },
                    new Guard.Action() {
                        @Override
                        public void run() {}
                    });
        }

        check(!switches.isEnabled(id), "switched off after the threshold");
        check(switches.state(id).reason() == SwitchBoard.Reason.SAFEGUARD, "and marked as such");
        check(tripped.size() == 1, "the listener was told exactly once");
        check(
                tripped.get(0).firstFailure().contains("IllegalStateException"),
                "the first failure is reported, not the last");
        check(guard.tripped().size() == 1, "and it is in the tripped list");
        pass("a repeatedly failing optimization is switched off");
    }

    static void aDisabledOptimizationRunsVanillaWithoutTrying() {
        SwitchBoard switches = board();
        final OptimizationId id = switches.registry().all().get(0).id();
        switches.setByUser(id, false);

        Guard guard = new Guard(switches, null);
        final Flag optimizedRan = new Flag();
        final Flag vanillaRan = new Flag();

        guard.run(
                id,
                new Guard.Action() {
                    @Override
                    public void run() {
                        optimizedRan.set = true;
                    }
                },
                new Guard.Action() {
                    @Override
                    public void run() {
                        vanillaRan.set = true;
                    }
                });

        check(!optimizedRan.set, "the optimization was not attempted");
        check(vanillaRan.set, "vanilla ran");
        check(guard.clean(), "and nothing was recorded as a failure");
        pass("a disabled optimization runs vanilla without trying");
    }

    static void vanillaFailingIsNotAbsorbed() {
        SwitchBoard switches = board();
        final OptimizationId id = switches.registry().all().get(0).id();
        switches.setByUser(id, false);

        final Guard guard = new Guard(switches, null);
        expectThrows("vanilla failure propagates", new Runnable() {
            @Override
            public void run() {
                guard.run(
                        id,
                        new Guard.Action() {
                            @Override
                            public void run() {}
                        },
                        new Guard.Action() {
                            @Override
                            public void run() {
                                throw new IllegalStateException("vanilla is broken");
                            }
                        });
            }
        });
        pass("vanilla failing is not absorbed");
    }

    static void aNullResultCountsAsAFailure() {
        SwitchBoard switches = board();
        final OptimizationId id = switches.registry().all().get(0).id();
        switches.setByUser(id, true);

        Guard guard = new Guard(switches, null);
        String result =
                guard.compute(
                        id,
                        new Guard.Computation<String>() {
                            @Override
                            public String compute() {
                                return null;
                            }
                        },
                        new Guard.Computation<String>() {
                            @Override
                            public String compute() {
                                return "vanilla";
                            }
                        });

        check("vanilla".equals(result), "the fallback value is used, got " + result);
        check(guard.failureCount(id) == 1, "and a null counts as a failure");
        pass("a null result counts as a failure");
    }

    /** A Mixin that did not apply throws an Error, not an Exception. */
    static void guardsCatchErrorsNotJustExceptions() {
        SwitchBoard switches = board();
        final OptimizationId id = switches.registry().all().get(0).id();
        switches.setByUser(id, true);

        Guard guard = new Guard(switches, null);
        final Flag vanillaRan = new Flag();

        guard.run(
                id,
                new Guard.Action() {
                    @Override
                    public void run() throws Throwable {
                        throw new NoSuchMethodError("func_71410_x");
                    }
                },
                new Guard.Action() {
                    @Override
                    public void run() {
                        vanillaRan.set = true;
                    }
                });

        check(vanillaRan.set, "a NoSuchMethodError from a bad Mixin falls back to vanilla");
        check(guard.failureCount(id) == 1, "and is recorded");
        pass("guards catch errors, not just exceptions");
    }

    // =======================================================================
    // Harness
    // =======================================================================

    private static boolean near(double a, double b) {
        return near(a, b, 1e-6);
    }

    private static boolean near(double a, double b, double tolerance) {
        return Math.abs(a - b) <= tolerance;
    }

    private static void check(boolean condition, String what) {
        if (!condition) {
            throw new AssertionError(what);
        }
    }

    private static void expectThrows(String what, Runnable action) {
        try {
            action.run();
        } catch (RuntimeException expected) {
            return;
        }
        throw new AssertionError("expected " + what + " to throw");
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  ok   " + name);
    }

    private static void report() {
        System.out.println();
        if (failures.isEmpty()) {
            System.out.println(passed + " passed, 0 failed");
        } else {
            for (String failure : failures) {
                System.out.println("FAIL " + failure);
            }
            System.out.println(passed + " passed, " + failures.size() + " failed");
            System.exit(1);
        }
    }

    private CoreTests() {}
}
