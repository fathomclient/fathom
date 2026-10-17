package net.fathom.optimizations.bench;

import java.util.List;
import java.util.Random;

import net.fathom.optimizations.core.bench.micro.MicroBench;
import net.fathom.optimizations.core.opt.text.GlyphSource;
import net.fathom.optimizations.core.opt.text.TextMeasurer;
import net.fathom.optimizations.core.opt.text.WidthMemo;

/**
 * Isolated baseline for {@code render.font.metrics-cache} <strong>as shipped</strong>.
 *
 * <h2>Why this exists next to {@link TextMetricsBench}</h2>
 *
 * <p>That benchmark measures {@code TextMetricsCache}, which recomputes widths from a
 * {@link GlyphSource}. This one measures {@link WidthMemo}, which stores the number the
 * game returned. <strong>WidthMemo is the one the Mixin uses</strong>, so it is the one
 * whose numbers may be quoted about the shipped build. Benchmarking the class we did not
 * ship and reporting it as the result would be the most ordinary way to publish a true
 * number about the wrong thing.
 *
 * <h2>What one iteration is</h2>
 *
 * <p>One frame's worth of text measurement on a busy minigame server: scoreboard lines, a
 * tab list, a chat backlog and a screenful of nametags. A per-call figure for
 * {@code getStringWidth} would be true and useless.
 *
 * <h2>The baseline arm is vanilla, exactly</h2>
 *
 * <p>Vanilla walks the string every call. {@link TextMeasurer} is that walk. The candidate
 * arm asks the memo first and falls back to the same walk on a miss — which is precisely
 * what the Mixin does, where the "walk" is the original method body. So the ratio here is
 * the ratio the game would see for this function, and the miss path costs what vanilla
 * costs plus one failed hash lookup.
 *
 * <h2>What this cannot tell us</h2>
 *
 * <p>Whether the game gets faster. This measures one function in a loop.
 * {@code MicroBench.Scope.ISOLATED_FUNCTION} is stamped on every result so it cannot be
 * quoted as an FPS figure. No in-game benchmark has been run against this module.
 */
public final class WidthMemoBench {

    public static void main(String[] args) {
        TextMetricsBench.Font font = new TextMetricsBench.Font();
        List<String> corpus = TextMetricsBench.frameCorpus(new Random(0xF00D));

        System.out.println("# render.font.metrics-cache (WidthMemo, as shipped)");
        System.out.println();
        System.out.println("Corpus: " + corpus.size() + " strings per frame, "
                + TextMetricsBench.totalChars(corpus) + " characters.");
        System.out.println("Java: " + System.getProperty("java.vm.name") + " "
                + System.getProperty("java.version"));
        System.out.println();

        MicroBench bench = new MicroBench();
        System.out.println(MicroBench.markdownHeader());

        double[] churns = {0.0, 0.05, 0.50, 1.0};
        String[] names = {
            "Static corpus, one frame of measurement",
            "5% of strings new each frame",
            "50% new each frame (adversarial)",
            "100% new each frame (worst case: pure overhead)",
        };
        for (int i = 0; i < churns.length; i++) {
            System.out.println(bench.compare(names[i],
                    MicroBench.Scope.ISOLATED_FUNCTION,
                    vanillaArm(font, corpus, churns[i]),
                    memoArm(font, corpus, churns[i]),
                    TextMetricsBench.ITERATIONS).markdownRow());
        }

        System.out.println();
        for (MicroBench.Result result : bench.results()) {
            System.out.println("- **" + result.name() + "** — " + result.claim());
        }

        System.out.println();
        reportHitRates(font, corpus);
    }

    /** Vanilla: walk the string, every call, every frame. */
    static MicroBench.Arm vanillaArm(final GlyphSource font, final List<String> corpus,
            final double churn) {
        return new MicroBench.Arm() {
            private final TextMeasurer measurer = new TextMeasurer(font);
            private final TextMetricsBench.Frames source =
                    new TextMetricsBench.Frames(corpus, churn, TextMetricsBench.FRAMES);

            @Override
            public String label() {
                return "vanilla recompute";
            }

            @Override
            public long run() {
                long sink = 0;
                for (String text : source.nextFrame()) {
                    sink += measurer.width(text);
                }
                return sink;
            }
        };
    }

    /**
     * The shipped path: ask the memo, and on a miss do exactly what vanilla does and
     * remember the answer.
     */
    static MicroBench.Arm memoArm(final GlyphSource font, final List<String> corpus,
            final double churn) {
        return new MicroBench.Arm() {
            private final TextMeasurer measurer = new TextMeasurer(font);
            private final WidthMemo memo = new WidthMemo();
            private final TextMetricsBench.Frames source =
                    new TextMetricsBench.Frames(corpus, churn, TextMetricsBench.FRAMES);

            @Override
            public String label() {
                return "WidthMemo";
            }

            @Override
            public long run() {
                long sink = 0;
                for (String text : source.nextFrame()) {
                    Integer known = memo.lookup(text);
                    if (known == null) {
                        // Exactly what the Mixin does on a miss: run the
                        // original computation and remember its answer.
                        int width = measurer.width(text);
                        memo.remember(text, width);
                        sink += width;
                    } else {
                        sink += known.intValue();
                    }
                }
                return sink;
            }
        };
    }

    /**
     * Hit rate per churn level, and a correctness pass.
     *
     * <p>The correctness pass is not decoration. A cache that is fast and wrong is worse
     * than no cache, and the claim this optimization rests on is that a hit returns the
     * identical {@code int} the miss path produced.
     */
    static void reportHitRates(GlyphSource font, List<String> corpus) {
        System.out.println("Hit rate and correctness by churn:");
        for (double churn : new double[] {0.0, 0.05, 0.50, 1.0}) {
            TextMeasurer measurer = new TextMeasurer(font);
            WidthMemo memo = new WidthMemo();
            TextMetricsBench.Frames source =
                    new TextMetricsBench.Frames(corpus, churn, 400);
            int mismatches = 0;

            for (int frame = 0; frame < 400; frame++) {
                for (String text : source.nextFrame()) {
                    int expected = measurer.width(text);
                    Integer known = memo.lookup(text);
                    if (known == null) {
                        memo.remember(text, expected);
                    } else if (known.intValue() != expected) {
                        mismatches++;
                    }
                }
            }
            System.out.printf("  churn %5.0f%%  hit rate %6.2f%%  mismatches %d%n",
                    churn * 100.0, memo.hitRate() * 100.0, mismatches);
            if (mismatches != 0) {
                throw new AssertionError("the memo returned a width the measurer disagreed with");
            }
        }
    }

    private WidthMemoBench() {}
}
