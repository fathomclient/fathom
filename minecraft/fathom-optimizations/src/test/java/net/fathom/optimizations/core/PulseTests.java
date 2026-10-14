package net.fathom.optimizations.core;

import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.instrument.Pulse;
import net.fathom.optimizations.core.instrument.PulseJson;

/**
 * The instrument's arithmetic: what counts as play, how seconds close, and that the
 * percentiles and the launcher's copy say what the frames said.
 */
public final class PulseTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    private static final long MS = 1_000_000L;

    public static void main(String[] args) {
        theFirstFrameOnlySetsTheClock();
        framesOutsidePlayAreNotCounted();
        aStallIsAPauseNotAFrame();
        secondsCloseOnPlayTimeAndReportTheirRate();
        theTimelineKeepsTheNewestSeconds();
        recentPercentilesSeeASpike();
        recentPercentilesLookBackFiveSecondsAtMost();
        sessionAverageIsFramesOverTime();
        sessionPercentilesComeFromTheHistogram();
        hitchesAreFramesOfFiftyMillisecondsOrMore();
        marksSitWhereTheyHappened();
        aRepeatedMarkInOneSecondIsOne();
        memoryIsReadWhenASecondCloses();
        thePublishedCopyCarriesTheTimeline();
        anUnchangedCopyIsNotCopiedAgain();
        theDocumentHasTheLaunchersShape();

        System.out.println("PulseTests: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    // -----------------------------------------------------------------------

    /** Feed {@code count} frames of {@code each} nanoseconds, all counted. */
    private static long play(Pulse pulse, long start, int count, long each) {
        long now = start;
        for (int index = 0; index < count; index++) {
            now += each;
            pulse.frame(now, true);
        }
        return now;
    }

    private static Pulse fresh() {
        return new Pulse(new FixedMemory(512L * 1024 * 1024, 4096L * 1024 * 1024));
    }

    static void theFirstFrameOnlySetsTheClock() {
        Pulse pulse = fresh();
        pulse.frame(1_000 * MS, true);
        check(pulse.sessionFrames() == 0, "a first frame has no interval to record");
        pulse.frame(1_010 * MS, true);
        check(pulse.sessionFrames() == 1, "the second frame records the interval between them");
    }

    static void framesOutsidePlayAreNotCounted() {
        Pulse pulse = fresh();
        long now = play(pulse, MS, 10, 10 * MS);
        for (int index = 0; index < 30; index++) {
            now += 33 * MS;
            pulse.frame(now, false);
        }
        check(pulse.sessionFrames() == 9, "menu frames are not play: " + pulse.sessionFrames());
        // The first frame back is timed from the last frame seen, not the last counted.
        pulse.frame(now + 10 * MS, true);
        check(pulse.sessionFrames() == 10, "play resumes with the next frame");
        check(Math.abs(pulse.sessionWorstMillis() - 10f) < 0.01f,
                "the gap spent in a menu is not a slow frame: " + pulse.sessionWorstMillis());
    }

    static void aStallIsAPauseNotAFrame() {
        Pulse pulse = fresh();
        long now = play(pulse, MS, 5, 10 * MS);
        pulse.frame(now + 3_000 * MS, true);
        check(pulse.sessionFrames() == 4, "a three-second world load is not a frame");
        check(pulse.hitches() == 0, "nor a hitch");
    }

    static void secondsCloseOnPlayTimeAndReportTheirRate() {
        Pulse pulse = fresh();
        play(pulse, MS, 101, 10 * MS);
        check(pulse.secondsOfPlay() == 1, "one hundred 10 ms frames is one second of play");
        check(Math.abs(pulse.currentFps() - 100f) < 0.01f, "at 100 fps: " + pulse.currentFps());
        check(Math.abs(pulse.timelineWorstMillis(0) - 10f) < 0.01f, "worst frame of the second");
    }

    static void theTimelineKeepsTheNewestSeconds() {
        Pulse pulse = fresh();
        long now = play(pulse, MS, 1 + 100 * Pulse.SECONDS, 10 * MS);
        play(pulse, now, 50, 20 * MS);
        check(pulse.timelineLength() == Pulse.SECONDS, "the timeline is bounded");
        int newest = pulse.timelineLength() - 1;
        check(Math.abs(pulse.timelineFps(newest) - 50f) < 0.01f,
                "the newest second is last: " + pulse.timelineFps(newest));
        check(Math.abs(pulse.timelineFps(0) - 100f) < 0.01f, "older seconds shift left");
    }

    static void recentPercentilesSeeASpike() {
        Pulse pulse = fresh();
        long now = play(pulse, MS, 1 + 99, 5 * MS);
        now += 40 * MS;
        pulse.frame(now, true);
        play(pulse, now, 200, 5 * MS);
        pulse.read();
        check(Math.abs(pulse.recentP95Millis() - 5f) < 0.01f, "p95 ignores one frame in 300");
        check(Math.abs(pulse.recentP99Millis() - 5f) < 0.01f, "and so does p99, by nearest rank");
        check(pulse.recentSamples() == 300, "every recent frame is in the window: " + pulse.recentSamples());

        Pulse spiky = fresh();
        long time = MS;
        for (int index = 0; index < 400; index++) {
            time += index % 20 == 0 ? 30 * MS : 5 * MS;
            spiky.frame(time, true);
        }
        spiky.read();
        check(Math.abs(spiky.recentP99Millis() - 30f) < 0.01f,
                "one frame in twenty at 30 ms is the p99: " + spiky.recentP99Millis());
        check(Math.abs(spiky.recentP95Millis() - 5f) < 0.01f, "but not the p95");
    }

    static void recentPercentilesLookBackFiveSecondsAtMost() {
        Pulse pulse = fresh();
        // Six seconds at 100 fps with every frame 10 ms, then one slow second.
        long now = play(pulse, MS, 1 + 600, 10 * MS);
        play(pulse, now, 1000, 4 * MS);
        pulse.read();
        // 2048 frames are kept; 1000 of them at 4 ms is 4 s, then 100 frames of 10 ms
        // fill the window to five seconds.
        check(pulse.recentSamples() == 1100, "five seconds of frames: " + pulse.recentSamples());
    }

    static void sessionAverageIsFramesOverTime() {
        Pulse pulse = fresh();
        long now = play(pulse, MS, 1 + 100, 5 * MS);
        play(pulse, now, 100, 15 * MS);
        // 200 frames in 2 s is 100 fps; the mean of per-frame rates would say 133.
        check(Math.abs(pulse.sessionAverageFps() - 100f) < 0.01f,
                "frames over time: " + pulse.sessionAverageFps());
    }

    static void sessionPercentilesComeFromTheHistogram() {
        Pulse pulse = fresh();
        long now = MS;
        pulse.frame(now, true);
        for (int index = 0; index < 1000; index++) {
            now += index < 990 ? 4_250_000L : 12_340_000L;
            pulse.frame(now, true);
        }
        check(Math.abs(pulse.sessionPercentileMillis(0.95) - 4.3f) < 0.001f,
                "to the slot's upper edge: " + pulse.sessionPercentileMillis(0.95));
        check(Math.abs(pulse.sessionPercentileMillis(0.99) - 4.3f) < 0.001f, "990 of 1000 are fast");
        check(Math.abs(pulse.sessionPercentileMillis(0.995) - 12.4f) < 0.001f,
                "the slowest ten are 12.34 ms: " + pulse.sessionPercentileMillis(0.995));
        Pulse slow = fresh();
        play(slow, MS, 3, 400 * MS);
        check(Math.abs(slow.sessionPercentileMillis(0.99) - 400f) < 0.01f,
                "beyond the histogram, the worst frame: " + slow.sessionPercentileMillis(0.99));
    }

    static void hitchesAreFramesOfFiftyMillisecondsOrMore() {
        Pulse pulse = fresh();
        long now = play(pulse, MS, 10, 10 * MS);
        now = play(pulse, now, 1, 49 * MS);
        now = play(pulse, now, 1, 50 * MS);
        play(pulse, now, 1, 200 * MS);
        check(pulse.hitches() == 2, "two frames at or over 50 ms: " + pulse.hitches());
    }

    static void marksSitWhereTheyHappened() {
        Pulse pulse = fresh();
        long now = play(pulse, MS, 1 + 300, 10 * MS);
        pulse.mark(Pulse.Mark.SETTINGS);
        play(pulse, now, 200, 10 * MS);
        check(pulse.markCount() == 1, "one mark");
        check(pulse.markKind(0) == Pulse.Mark.SETTINGS, "of the kind given");
        check(pulse.markTimelineIndex(0) == 3,
                "after three seconds of play: " + pulse.markTimelineIndex(0));
        Pulse scrolled = fresh();
        long time = play(scrolled, MS, 101, 10 * MS);
        scrolled.mark(Pulse.Mark.WORLD);
        play(scrolled, time, 100 * (Pulse.SECONDS + 5), 10 * MS);
        check(scrolled.markTimelineIndex(0) == -1, "a mark older than the timeline is off it");
    }

    static void aRepeatedMarkInOneSecondIsOne() {
        Pulse pulse = fresh();
        play(pulse, MS, 50, 10 * MS);
        pulse.mark(Pulse.Mark.SETTINGS);
        pulse.mark(Pulse.Mark.SETTINGS);
        pulse.mark(Pulse.Mark.WORLD);
        check(pulse.markCount() == 2, "dragging a slider is one change, not sixty: " + pulse.markCount());
    }

    static void memoryIsReadWhenASecondCloses() {
        FixedMemory memory = new FixedMemory(100, 1000);
        Pulse pulse = new Pulse(memory);
        long now = play(pulse, MS, 50, 10 * MS);
        check(pulse.heapUsedBytes() == 0, "not before a second closes");
        play(pulse, now, 60, 10 * MS);
        check(pulse.heapUsedBytes() == 100 && pulse.heapMaxBytes() == 1000, "read at the close");
        check(memory.reads == 1, "once, not once a frame: " + memory.reads);
    }

    static void thePublishedCopyCarriesTheTimeline() {
        Pulse pulse = fresh();
        long now = play(pulse, MS, 1 + 100 * 25, 10 * MS);
        pulse.mark(Pulse.Mark.WORLD);
        play(pulse, now, 100 * 10, 5 * MS);
        Pulse.Snapshot copy = new Pulse.Snapshot();
        check(pulse.copyPublished(copy), "a copy is available after a second closes");
        check(copy.buckets == 3, "thirty seconds is three buckets: " + copy.buckets);
        check(Math.abs(copy.bucketFps[0] - 100f) < 0.01f, "oldest first");
        // Five seconds at 100 fps and five at 200: 1,500 frames in ten seconds.
        check(Math.abs(copy.bucketFps[2] - 150f) < 0.01f, "the newest bucket: " + copy.bucketFps[2]);
        check(copy.marks == 1 && copy.markBucket[0] == 2, "the mark is in the bucket it fell in: "
                + (copy.marks > 0 ? copy.markBucket[0] : -1));
        check(copy.secondsOfPlay == 30, "seconds of play: " + copy.secondsOfPlay);
        check(copy.heapUsed == 512L * 1024 * 1024, "memory travels with it");
    }

    static void anUnchangedCopyIsNotCopiedAgain() {
        Pulse pulse = fresh();
        play(pulse, MS, 101, 10 * MS);
        Pulse.Snapshot copy = new Pulse.Snapshot();
        check(pulse.copyPublished(copy), "the first copy");
        check(!pulse.copyPublished(copy), "nothing new until the next second");
    }

    static void theDocumentHasTheLaunchersShape() {
        Pulse pulse = fresh();
        long now = play(pulse, MS, 1 + 100 * 10, 10 * MS);
        pulse.mark(Pulse.Mark.SETTINGS);
        play(pulse, now, 100 * 10, 10 * MS);
        Pulse.Snapshot copy = new Pulse.Snapshot();
        pulse.copyPublished(copy);
        StringBuilder out = new StringBuilder();
        PulseJson.write(copy, 7, 1_700_000_000_000L, out);
        String json = out.toString();
        // The same document crates/launcher-core/src/session_pulse.rs parses in its test.
        String expected = "{\"schema\":1,\"updatedAt\":1700000000000,\"secondsOfPlay\":20,"
                + "\"frames\":2000,\"currentFps\":100.0,\"averageFps\":100.0,\"p95Ms\":10.00,"
                + "\"p99Ms\":10.00,\"worstMs\":10.00,\"hitches\":0,\"heapUsedMb\":512,"
                + "\"heapMaxMb\":4096,\"modules\":7,\"bucketSeconds\":10,"
                + "\"timelineFps\":[100.0,100.0],\"timelineWorstMs\":[10.0,10.0],"
                + "\"marks\":[{\"bucket\":1,\"kind\":\"settings\"}]}";
        check(json.equals(expected), "the document:\n    " + json + "\n  expected\n    " + expected);
    }

    // -----------------------------------------------------------------------

    private static final class FixedMemory implements Pulse.Memory {
        private final long used;
        private final long max;
        int reads;

        FixedMemory(long used, long max) {
            this.used = used;
            this.max = max;
        }

        @Override
        public long used() {
            reads++;
            return used;
        }

        @Override
        public long max() {
            return max;
        }
    }

    private static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(what);
        }
    }
}
