package net.fathom.optimizations.bench;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

import net.fathom.optimizations.core.bench.micro.MicroBench;
import net.fathom.optimizations.core.opt.text.GlyphSource;
import net.fathom.optimizations.core.opt.text.TextMeasurer;
import net.fathom.optimizations.core.opt.text.TextMetricsCache;

/**
 * Isolated baseline for {@code render.font.metrics-cache}.
 *
 * <p>One "iteration" is <strong>one frame's worth of text measurement</strong> on a busy
 * minigame server: a scoreboard, a tab list, a chat backlog and a screenful of nametags,
 * all measured the way the layout, centring and trimming paths measure them. That shape is
 * chosen so the number means something — a per-call figure for {@code getStringWidth}
 * would be true and useless.
 *
 * <h2>Two corpora, because the honest answer depends on which</h2>
 *
 * <ul>
 *   <li><strong>Static.</strong> Nothing changes between frames. The best case, and closer
 *       to the truth than it sounds: a scoreboard and a tab list really are unchanged for
 *       seconds at a time.
 *   <li><strong>Churning.</strong> A fraction of the corpus is new every frame — chat
 *       arriving, timers counting down, health values changing. This is the case that
 *       decides whether the cache is worth having, because it is the one where it pays
 *       for its misses.
 * </ul>
 *
 * <h2>What this cannot tell us</h2>
 *
 * <p>Whether the game gets faster. This measures one function in a loop with the whole
 * cache to itself. {@code MicroBench.Scope.ISOLATED_FUNCTION} is stamped on every result
 * so it cannot be quoted as an FPS figure.
 */
public final class TextMetricsBench {

    /** The same font shape as the tests: varying widths, fractional above ASCII. */
    static final class Font implements GlyphSource {
        @Override
        public float advance(char c) {
            if (c == ' ') {
                return 0f;
            }
            return c < 128 ? 2f + (c % 5) : 3.5f + (c % 3) * 0.25f;
        }

        @Override
        public int generation() {
            return 1;
        }
    }

    /**
     * How many distinct frames to pre-build per arm.
     *
     * <p>Enough that the cache cannot simply memorise a short cycle and report a hit rate
     * the real workload would never reach.
     */
    static final int FRAMES = 512;

    /** Iterations per timed run; sized so one run takes tens of milliseconds, not microseconds. */
    static final int ITERATIONS = 2000;

    public static void main(String[] args) {
        Font font = new Font();
        List<String> corpus = frameCorpus(new Random(0xF00D));
        System.out.println("# render.font.metrics-cache — isolated baseline");
        System.out.println();
        System.out.println("Corpus: " + corpus.size() + " strings per frame, "
                + totalChars(corpus) + " characters.");
        System.out.println("Java: " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version"));
        System.out.println();

        MicroBench bench = new MicroBench();
        System.out.println(MicroBench.markdownHeader());

        System.out.println(bench.compare("Static corpus, one frame of measurement",
                MicroBench.Scope.ISOLATED_FUNCTION,
                measurerArm(font, corpus, 0.0),
                cacheArm(font, corpus, 0.0),
                ITERATIONS).markdownRow());

        System.out.println(bench.compare("5% of strings new each frame",
                MicroBench.Scope.ISOLATED_FUNCTION,
                measurerArm(font, corpus, 0.05),
                cacheArm(font, corpus, 0.05),
                ITERATIONS).markdownRow());

        System.out.println(bench.compare("50% new each frame (adversarial)",
                MicroBench.Scope.ISOLATED_FUNCTION,
                measurerArm(font, corpus, 0.50),
                cacheArm(font, corpus, 0.50),
                ITERATIONS).markdownRow());

        System.out.println(bench.compare("100% new each frame (worst case: pure overhead)",
                MicroBench.Scope.ISOLATED_FUNCTION,
                measurerArm(font, corpus, 1.0),
                cacheArm(font, corpus, 1.0),
                ITERATIONS).markdownRow());

        System.out.println();
        for (MicroBench.Result result : bench.results()) {
            System.out.println("- **" + result.name() + "** — " + result.claim());
        }

        System.out.println();
        reportHitRates(font, corpus);
    }

    /**
     * Vanilla: recompute everything, every frame.
     */
    static MicroBench.Arm measurerArm(final GlyphSource font, final List<String> corpus,
            final double churn) {
        return new MicroBench.Arm() {
            private final TextMeasurer measurer = new TextMeasurer(font);
            private final Frames source = new Frames(corpus, churn, FRAMES);

            @Override
            public long run() {
                long total = 0L;
                for (String text : source.nextFrame()) {
                    total += measurer.width(text);
                }
                return total;
            }

            @Override
            public String label() {
                return "recompute";
            }
        };
    }

    static MicroBench.Arm cacheArm(final GlyphSource font, final List<String> corpus,
            final double churn) {
        return new MicroBench.Arm() {
            private final TextMetricsCache cache = new TextMetricsCache(font);
            private final Frames source = new Frames(corpus, churn, FRAMES);

            @Override
            public long run() {
                long total = 0L;
                for (String text : source.nextFrame()) {
                    total += cache.width(text);
                }
                return total;
            }

            @Override
            public String label() {
                return "cache";
            }
        };
    }

    /**
     * Pre-built frames, so nothing but the measurement is measured.
     *
     * <p>Building the churned strings inside the timed loop was the first version of this
     * benchmark, and it was wrong: it put {@code Random} and string concatenation into
     * both arms, which inflated the noise floor past the effect being measured and made
     * every result read as "no measurable difference". The frames are built once up front
     * and cycled.
     */
    static final class Frames {
        private final List<List<String>> frames;
        private int next;

        Frames(List<String> base, double fraction, int count) {
            this.frames = new ArrayList<List<String>>(count);
            Random random = new Random(0xC0FFEE);
            List<String> current = new ArrayList<String>(base);
            for (int frame = 0; frame < count; frame++) {
                if (fraction > 0.0) {
                    current = new ArrayList<String>(current);
                    int replacements = (int) Math.round(base.size() * fraction);
                    for (int i = 0; i < replacements; i++) {
                        int slot = fraction >= 1.0 ? i : random.nextInt(current.size());
                        // A string that is genuinely new: a chat line with a timestamp, a
                        // countdown, a health value that changed.
                        current.set(slot, base.get(slot % base.size()) + " " + frame + ":" + i);
                    }
                }
                frames.add(current);
            }
        }

        List<String> nextFrame() {
            List<String> frame = frames.get(next);
            next = (next + 1) % frames.size();
            return frame;
        }
    }

    /**
     * A frame of text from a busy minigame server.
     *
     * <p>Counts and shapes chosen to match what is actually on screen: fifteen scoreboard
     * lines, eighty tab entries, a hundred chat lines, forty nametags. Format codes
     * throughout, because servers colour everything.
     */
    static List<String> frameCorpus(Random random) {
        List<String> out = new ArrayList<String>();
        for (int i = 0; i < 15; i++) {
            out.add("§e§lKills: §f" + random.nextInt(30)
                    + "  §7Deaths: §c" + random.nextInt(30));
        }
        for (int i = 0; i < 80; i++) {
            out.add("§" + "0123456789abcdef".charAt(random.nextInt(16))
                    + "[MVP§b+§" + "6a".charAt(random.nextInt(2)) + "] Player"
                    + random.nextInt(10000));
        }
        for (int i = 0; i < 100; i++) {
            out.add("§7[§bGuild§7] §fPlayer" + random.nextInt(1000)
                    + "§7: §fthis is a chat message of a fairly ordinary length");
        }
        for (int i = 0; i < 40; i++) {
            out.add("§cNPC §f" + random.nextInt(100) + " §7§oright click me");
        }
        return out;
    }

    static int totalChars(List<String> corpus) {
        int total = 0;
        for (String text : corpus) {
            total += text.length();
        }
        return total;
    }

    /** How often the cache actually helps, which is the number that decides everything. */
    static void reportHitRates(GlyphSource font, List<String> corpus) {
        System.out.println("| Churn per frame | Hit rate over 600 frames | Entries held |");
        System.out.println("|---|---|---|");
        for (double churn : new double[] {0.0, 0.01, 0.05, 0.25, 0.5, 1.0}) {
            TextMetricsCache cache = new TextMetricsCache(font);
            Frames source = new Frames(corpus, churn, 600);
            for (int frame = 0; frame < 600; frame++) {
                for (String text : source.nextFrame()) {
                    cache.width(text);
                }
            }
            System.out.println("| " + String.format("%.0f%%", churn * 100.0) + " | "
                    + String.format("%.1f%%", cache.hitRate() * 100.0) + " | " + cache.size()
                    + " |");
        }
    }

    private TextMetricsBench() {}
}
