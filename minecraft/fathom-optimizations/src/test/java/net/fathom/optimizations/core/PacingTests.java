package net.fathom.optimizations.core;

import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.opt.frames.FramePacer;

/** The frame limiter's schedule, on a clock this test controls. */
public final class PacingTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        anEarlyFrameWaitsForItsDeadline();
        deadlinesDoNotDrift();
        aLateFrameResynchronisesInsteadOfBursting();
        itSleepsWhileThereIsRoomAndYieldsAtTheEnd();
        aChangedCapStartsAFreshSchedule();

        System.out.println("PacingTests: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** Sleeping takes exactly 1.2 ms; yielding 10 us. Counts both. */
    private static final class FakeClock implements FramePacer.Clock {
        long now = 1_000_000_000L;
        int sleeps;
        int yields;

        @Override
        public long nanoTime() {
            return now;
        }

        @Override
        public void sleepOneMillisecond() {
            sleeps++;
            now += 1_200_000L;
        }

        @Override
        public void yieldThread() {
            yields++;
            now += 10_000L;
        }
    }

    private static final long PERIOD_144 = 1_000_000_000L / 144;

    static void anEarlyFrameWaitsForItsDeadline() {
        FakeClock clock = new FakeClock();
        FramePacer pacer = new FramePacer();
        pacer.sync(144, clock);
        long start = clock.now;
        clock.now += 2_000_000L;
        pacer.sync(144, clock);
        long frame = clock.now - start;
        check(frame >= PERIOD_144 && frame < PERIOD_144 + 20_000L,
                "a 2 ms frame lasted " + frame + " ns for a " + PERIOD_144 + " ns period");
    }

    static void deadlinesDoNotDrift() {
        FakeClock clock = new FakeClock();
        FramePacer pacer = new FramePacer();
        pacer.sync(144, clock);
        long first = clock.now;
        for (int frame = 0; frame < 1000; frame++) {
            clock.now += 2_000_000L; // the frame's own work: 2 ms
            pacer.sync(144, clock);
        }
        long expected = first + 1000L * PERIOD_144;
        long error = Math.abs(clock.now - expected);
        // Every frame ends within one yield of its deadline, and the deadlines
        // are a period apart: a thousand frames later the error is still one
        // yield, not a thousand of them.
        check(error < 20_000L, "after 1000 frames the schedule is off by " + error + " ns");
    }

    static void aLateFrameResynchronisesInsteadOfBursting() {
        FakeClock clock = new FakeClock();
        FramePacer pacer = new FramePacer();
        pacer.sync(144, clock);
        clock.now += 50_000_000L; // a 50 ms hitch
        long hitchEnded = clock.now;
        pacer.sync(144, clock);
        check(clock.now == hitchEnded, "the late frame is released at once, not held a period more");
        clock.now += 1_000_000L;
        pacer.sync(144, clock);
        long frame = clock.now - hitchEnded;
        // Catching up would release the next frames immediately; a resync
        // paces them a period apart from where the hitch ended.
        check(frame >= PERIOD_144 && frame < PERIOD_144 + 20_000L,
                "the frame after a hitch lasted " + frame + " ns");
    }

    static void itSleepsWhileThereIsRoomAndYieldsAtTheEnd() {
        FakeClock clock = new FakeClock();
        FramePacer pacer = new FramePacer();
        pacer.sync(60, clock);
        pacer.sync(60, clock);
        check(clock.sleeps > 0, "a 16.7 ms wait should sleep");
        check(clock.yields > 0, "and finish by yielding");
        check(clock.yields * 10_000L <= FramePacer.SLEEP_MARGIN_NANOS + 1_200_000L,
                "yielding should cover only the margin, not the whole wait: " + clock.yields);
    }

    static void aChangedCapStartsAFreshSchedule() {
        FakeClock clock = new FakeClock();
        FramePacer pacer = new FramePacer();
        pacer.sync(30, clock);
        pacer.sync(30, clock);
        long start = clock.now;
        pacer.sync(240, clock);
        check(clock.now == start, "a new cap starts a schedule rather than finishing the old one");
        pacer.sync(240, clock);
        long waited = clock.now - start;
        long period240 = 1_000_000_000L / 240;
        check(waited >= period240 && waited < period240 + 20_000L,
                "and paces from there: waited " + waited + " ns");
    }

    static void check(boolean condition, String what) {
        if (condition) {
            passed++;
        } else {
            failures.add(what);
        }
    }
}
