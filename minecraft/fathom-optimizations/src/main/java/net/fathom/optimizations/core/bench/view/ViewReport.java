package net.fathom.optimizations.core.bench.view;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Turns the view benchmark's buckets into the report a person reads.
 *
 * <p>Pure: no Minecraft, no files, no clock. Everything it says is arithmetic
 * on what the buckets hold, which is what lets it be tested without a game.
 *
 * <h2>The arithmetic that matters</h2>
 *
 * <ul>
 *   <li>Frame time is the loop-head-to-loop-head interval minus the harness's
 *       own time, so it is what the frame rate is made of.
 *   <li>Self time is a section's total minus the sections that were actually
 *       open inside it. Self times add up to the attributed part of the frame;
 *       what is left is reported as untimed rather than spread around.
 *   <li>A scene's delta against the sky is taken per section on self time, so
 *       the deltas sum to the frame-time delta (plus the untimed remainder)
 *       and a share of the gap can be read straight off them.
 * </ul>
 */
public final class ViewReport {

    public static final String NORMAL = "normal";
    public static final String PROBES_OFF = "probes-off";
    public static final String FATHOM_OFF = "fathom-off";
    public static final String DRAIN = "drain";

    private final List<String> scenes;
    private final List<String> arms;
    private final Map<String, ViewBucket> buckets;
    private final List<String> environment;
    private final double probePairNanos;
    private final String baselineScene;
    private final String focusScene;
    private final Map<String, String> overlayBaselines;

    /**
     * @param overlayBaselines scenes that add something on top of another view
     *        (entities on the horizon, say), mapped to that view, so their delta
     *        is reported against the view they were added to as well as the sky
     */
    public ViewReport(List<String> scenes, List<String> arms, Map<String, ViewBucket> buckets,
            List<String> environment, double probePairNanos, String baselineScene, String focusScene,
            Map<String, String> overlayBaselines) {
        this.scenes = scenes;
        this.arms = arms;
        this.buckets = buckets;
        this.environment = environment;
        this.probePairNanos = probePairNanos;
        this.baselineScene = baselineScene;
        this.focusScene = focusScene;
        this.overlayBaselines = overlayBaselines;
    }

    public static String key(String scene, String arm) {
        return scene + "|" + arm;
    }

    private ViewBucket bucket(String scene, String arm) {
        return buckets.get(key(scene, arm));
    }

    // -----------------------------------------------------------------------
    // Derived figures
    // -----------------------------------------------------------------------

    /** Attributed time: every section's self time except the harness. */
    static double attributedMillis(ViewBucket bucket) {
        double sum = 0d;
        for (ViewSection section : ViewSection.ALL) {
            if (section != ViewSection.HARNESS) {
                sum += bucket.selfMillis(section);
            }
        }
        return sum;
    }

    static double untimedMillis(ViewBucket bucket) {
        return bucket.meanFrameMillis() - attributedMillis(bucket);
    }

    static double terrainMillis(ViewBucket bucket) {
        return bucket.totalMillis(ViewSection.SETUP_TERRAIN)
                + bucket.totalMillis(ViewSection.CHUNK_COMPILE)
                + bucket.totalMillis(ViewSection.CHUNK_UPDATES)
                + bucket.totalMillis(ViewSection.LAYER_SOLID)
                + bucket.totalMillis(ViewSection.LAYER_CUTOUT_MIPPED)
                + bucket.totalMillis(ViewSection.LAYER_CUTOUT)
                + bucket.totalMillis(ViewSection.LAYER_TRANSLUCENT);
    }

    static double fathomMillis(ViewBucket bucket) {
        return bucket.totalMillis(ViewSection.FATHOM_HUD)
                + bucket.totalMillis(ViewSection.FATHOM_FRAME)
                + bucket.selfMillis(ViewSection.POST_FRAME);
    }

    static double sectionsDrawn(ViewBucket bucket) {
        return bucket.counterPerFrame(ViewCounter.SECTIONS_SOLID)
                + bucket.counterPerFrame(ViewCounter.SECTIONS_CUTOUT_MIPPED)
                + bucket.counterPerFrame(ViewCounter.SECTIONS_CUTOUT)
                + bucket.counterPerFrame(ViewCounter.SECTIONS_TRANSLUCENT);
    }

    /** Coefficient of variation of the per-window means. */
    static double windowSpread(ViewBucket bucket) {
        List<Double> means = bucket.windowMeans();
        if (means.size() < 2) {
            return 0d;
        }
        double sum = 0d;
        for (Double value : means) {
            sum += value.doubleValue();
        }
        double mean = sum / means.size();
        double square = 0d;
        for (Double value : means) {
            double difference = value.doubleValue() - mean;
            square += difference * difference;
        }
        return mean <= 0d ? 0d : Math.sqrt(square / (means.size() - 1)) / mean;
    }

    /** One section's change in self time between two buckets. */
    public static final class Delta {
        public final ViewSection section;
        public final double from;
        public final double to;

        Delta(ViewSection section, double from, double to) {
            this.section = section;
            this.from = from;
            this.to = to;
        }

        public double change() {
            return to - from;
        }
    }

    /** Self-time deltas for every section, largest increase first. */
    public static List<Delta> deltas(ViewBucket from, ViewBucket to) {
        List<Delta> out = new ArrayList<Delta>();
        for (ViewSection section : ViewSection.ALL) {
            if (section == ViewSection.HARNESS) {
                continue;
            }
            out.add(new Delta(section, from.selfMillis(section), to.selfMillis(section)));
        }
        Collections.sort(out, new Comparator<Delta>() {
            @Override
            public int compare(Delta a, Delta b) {
                return Double.compare(b.change(), a.change());
            }
        });
        return out;
    }

    /** Sections by self time, largest first. */
    public static List<ViewSection> consumers(final ViewBucket bucket) {
        List<ViewSection> out = new ArrayList<ViewSection>();
        for (ViewSection section : ViewSection.ALL) {
            if (section != ViewSection.HARNESS && bucket.callsPerFrame(section) > 0d) {
                out.add(section);
            }
        }
        Collections.sort(out, new Comparator<ViewSection>() {
            @Override
            public int compare(ViewSection a, ViewSection b) {
                return Double.compare(bucket.selfMillis(b), bucket.selfMillis(a));
            }
        });
        return out;
    }

    /** Self time per owner. */
    public static Map<ViewSection.Owner, Double> byOwner(ViewBucket bucket) {
        Map<ViewSection.Owner, Double> out = new LinkedHashMap<ViewSection.Owner, Double>();
        for (ViewSection.Owner owner : ViewSection.Owner.values()) {
            out.put(owner, Double.valueOf(0d));
        }
        for (ViewSection section : ViewSection.ALL) {
            if (section == ViewSection.HARNESS) {
                continue;
            }
            out.put(section.owner, Double.valueOf(out.get(section.owner).doubleValue()
                    + bucket.selfMillis(section)));
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    public String render() {
        StringBuilder out = new StringBuilder(32_768);
        line(out, "FATHOM VIEW-DIRECTION FRAME PROFILE");
        line(out, "===================================");
        for (String fact : environment) {
            line(out, fact);
        }
        line(out, "");
        line(out, "Frame time = loop head to loop head, minus the harness. All figures are ms per frame");
        line(out, "unless stated. 'self' excludes nested sections that were timed separately.");
        line(out, String.format(Locale.ROOT,
                "Probe cost (calibrated, best of 5): %.1f ns per timed enter/exit pair.", probePairNanos));
        line(out, "");

        validity(out);
        summary(out);
        comparison(out);
        for (String scene : scenes) {
            breakdown(out, scene);
        }
        deltasSection(out);
        topConsumers(out);
        owners(out);
        counters(out);
        arms(out);
        return out.toString();
    }

    private void validity(StringBuilder out) {
        line(out, "SAMPLE VALIDITY");
        line(out, "---------------");
        line(out, String.format(Locale.ROOT, "%-12s %-11s %8s %8s %9s %8s %9s %9s %10s",
                "scene", "arm", "frames", "rejected", "unfocused", "windows", "win CoV", "GC (n/ms)",
                "alloc MB/s"));
        for (String scene : scenes) {
            for (String arm : arms) {
                ViewBucket bucket = bucket(scene, arm);
                if (bucket == null) {
                    continue;
                }
                line(out, String.format(Locale.ROOT, "%-12s %-11s %8d %8d %9d %8d %8.1f%% %4d/%-4d %10.1f",
                        scene, arm, bucket.frames(), bucket.rejected(), bucket.unfocusedFrames(),
                        bucket.windowMeans().size(),
                        windowSpread(bucket) * 100d, bucket.gcCount(), bucket.gcMillis(),
                        bucket.allocationMegabytesPerSecond()));
            }
        }
        line(out, "");
    }

    private void summary(StringBuilder out) {
        line(out, "SCENES (normal arm)");
        line(out, "-------------------");
        line(out, String.format(Locale.ROOT, "%-12s %8s %8s %8s %8s %8s %7s %7s %7s %7s %7s %7s %7s",
                "scene", "median", "mean", "p95", "p99", "fps", "world", "terrain", "entity", "partic",
                "HUD", "Fathom", "swap"));
        for (String scene : scenes) {
            ViewBucket bucket = bucket(scene, NORMAL);
            if (bucket == null || bucket.frames() == 0) {
                continue;
            }
            double mean = bucket.meanFrameMillis();
            line(out, String.format(Locale.ROOT,
                    "%-12s %8.3f %8.3f %8.3f %8.3f %8.0f %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f %7.3f",
                    scene, bucket.frameMillisAt(50d), mean, bucket.frameMillisAt(95d),
                    bucket.frameMillisAt(99d), mean <= 0d ? 0d : 1000d / mean,
                    bucket.totalMillis(ViewSection.WORLD), terrainMillis(bucket),
                    bucket.totalMillis(ViewSection.ENTITIES), bucket.totalMillis(ViewSection.PARTICLES),
                    bucket.totalMillis(ViewSection.HUD), fathomMillis(bucket),
                    bucket.totalMillis(ViewSection.SWAP)));
        }
        line(out, "");
    }

    /** The short per-scene block the investigation asked for. */
    private void comparison(StringBuilder out) {
        line(out, "VIEW COMPARISON (normal arm, mean ms per frame)");
        line(out, "-----------------------------------------------");
        for (String scene : scenes) {
            ViewBucket bucket = bucket(scene, NORMAL);
            if (bucket == null || bucket.frames() == 0) {
                continue;
            }
            line(out, scene + ":");
            row(out, "Total", bucket.meanFrameMillis());
            row(out, "Simulation (tick + tasks)",
                    bucket.totalMillis(ViewSection.TICK) + bucket.totalMillis(ViewSection.TASKS));
            row(out, "World render", bucket.totalMillis(ViewSection.WORLD));
            row(out, "  Terrain", terrainMillis(bucket));
            row(out, "  Sky + clouds", bucket.totalMillis(ViewSection.SKY) + bucket.totalMillis(ViewSection.CLOUDS));
            row(out, "  Entities", bucket.totalMillis(ViewSection.ENTITIES));
            row(out, "  Particles", bucket.totalMillis(ViewSection.PARTICLES));
            row(out, "  Hand", bucket.totalMillis(ViewSection.HAND));
            row(out, "HUD (vanilla)", bucket.totalMillis(ViewSection.HUD) - bucket.totalMillis(ViewSection.FATHOM_HUD));
            row(out, "Fathom", fathomMillis(bucket));
            row(out, "Blit + swap + glGetError", bucket.totalMillis(ViewSection.BLIT)
                    + bucket.totalMillis(ViewSection.SWAP) + bucket.totalMillis(ViewSection.GL_ERROR));
            row(out, "Untimed", untimedMillis(bucket));
            line(out, "");
        }
    }

    private void breakdown(StringBuilder out, String scene) {
        ViewBucket bucket = bucket(scene, NORMAL);
        if (bucket == null || bucket.frames() == 0) {
            return;
        }
        double frame = bucket.meanFrameMillis();
        line(out, "BREAKDOWN: " + scene + String.format(Locale.ROOT,
                "  (mean frame %.3f ms, %d frames)", frame, bucket.frames()));
        line(out, String.format(Locale.ROOT, "  %-46s %8s %8s %8s %8s %8s %6s  %s",
                "section", "total", "self", "self p50", "self p95", "calls/f", "%frame", "owner"));
        for (ViewSection section : ViewSection.ALL) {
            if (section == ViewSection.HARNESS || bucket.callsPerFrame(section) <= 0d) {
                continue;
            }
            StringBuilder name = new StringBuilder();
            for (int depth = 0; depth < section.depth(); depth++) {
                name.append("  ");
            }
            name.append(section.label);
            line(out, String.format(Locale.ROOT, "  %-46s %8.4f %8.4f %8.4f %8.4f %8.2f %5.1f%%  %s",
                    name, bucket.totalMillis(section), bucket.selfMillis(section),
                    bucket.selfMillisAt(section, 50d), bucket.selfMillisAt(section, 95d),
                    bucket.callsPerFrame(section),
                    frame <= 0d ? 0d : bucket.selfMillis(section) / frame * 100d, section.owner.label));
        }
        line(out, String.format(Locale.ROOT, "  %-46s %8s %8.4f", "untimed (between timed sections)", "",
                untimedMillis(bucket)));
        line(out, "");
    }

    private void deltasSection(StringBuilder out) {
        ViewBucket base = bucket(baselineScene, NORMAL);
        if (base == null || base.frames() == 0) {
            return;
        }
        line(out, "DELTAS AGAINST " + baselineScene.toUpperCase(Locale.ROOT)
                + " (self time, normal arm)");
        line(out, "-------------------------------------------");
        for (String scene : scenes) {
            if (scene.equals(baselineScene)) {
                continue;
            }
            delta(out, base, scene);
            String overlay = overlayBaselines.get(scene);
            if (overlay != null && bucket(overlay, NORMAL) != null) {
                line(out, "  ...and against " + overlay + ", the view it was added to:");
                delta(out, bucket(overlay, NORMAL), scene);
            }
        }
    }

    private void delta(StringBuilder out, ViewBucket base, String scene) {
        ViewBucket target = bucket(scene, NORMAL);
        if (target == null || target.frames() == 0) {
            return;
        }
        double frameDelta = target.meanFrameMillis() - base.meanFrameMillis();
        line(out, String.format(Locale.ROOT, "%s - %s: frame %+.3f ms (%.3f -> %.3f)",
                scene, base.scene, frameDelta, base.meanFrameMillis(), target.meanFrameMillis()));
        double explained = 0d;
        int shown = 0;
        for (Delta delta : deltas(base, target)) {
            if (shown >= 12 || Math.abs(delta.change()) < 0.002d) {
                continue;
            }
            shown++;
            explained += delta.change();
            line(out, String.format(Locale.ROOT, "  %+8.4f  %-46s %7.4f -> %7.4f  %5.1f%% of delta  [%s]",
                    delta.change(), delta.section.label, delta.from, delta.to,
                    frameDelta == 0d ? 0d : delta.change() / frameDelta * 100d, delta.section.owner.label));
        }
        double untimed = untimedMillis(target) - untimedMillis(base);
        line(out, String.format(Locale.ROOT, "  %+8.4f  %-46s", untimed, "untimed remainder"));
        line(out, String.format(Locale.ROOT, "  listed sections explain %.1f%% of the frame delta",
                frameDelta == 0d ? 0d : explained / frameDelta * 100d));
        line(out, "");
    }

    private void topConsumers(StringBuilder out) {
        ViewBucket bucket = bucket(focusScene, NORMAL);
        if (bucket == null || bucket.frames() == 0) {
            return;
        }
        double frame = bucket.meanFrameMillis();
        line(out, "TOP 10 FRAME-TIME CONSUMERS: " + focusScene + " (self time)");
        line(out, "--------------------------------------------");
        int rank = 0;
        for (ViewSection section : consumers(bucket)) {
            if (++rank > 10) {
                break;
            }
            line(out, String.format(Locale.ROOT, "%2d. %-46s %8.4f ms  %5.1f%%  [%s]", rank, section.label,
                    bucket.selfMillis(section), frame <= 0d ? 0d : bucket.selfMillis(section) / frame * 100d,
                    section.owner.label));
        }
        line(out, "");
    }

    private void owners(StringBuilder out) {
        line(out, "SELF TIME BY OWNER (normal arm)");
        line(out, "-------------------------------");
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-12s", "scene"));
        for (ViewSection.Owner owner : ViewSection.Owner.values()) {
            if (owner != ViewSection.Owner.HARNESS) {
                head.append(String.format(Locale.ROOT, " %26s", owner.label));
            }
        }
        line(out, head.toString());
        for (String scene : scenes) {
            ViewBucket bucket = bucket(scene, NORMAL);
            if (bucket == null || bucket.frames() == 0) {
                continue;
            }
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-12s", scene));
            for (Map.Entry<ViewSection.Owner, Double> entry : byOwner(bucket).entrySet()) {
                if (entry.getKey() != ViewSection.Owner.HARNESS) {
                    row.append(String.format(Locale.ROOT, " %26.4f", entry.getValue().doubleValue()));
                }
            }
            line(out, row.toString());
        }
        line(out, "");
    }

    private void counters(StringBuilder out) {
        line(out, "CHUNK AND DRAW COUNTERS (per frame, normal arm)");
        line(out, "-----------------------------------------------");
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-34s", "counter"));
        for (String scene : scenes) {
            head.append(String.format(Locale.ROOT, " %11s", scene));
        }
        line(out, head.toString());
        for (ViewCounter counter : ViewCounter.ALL) {
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-34s", counter.label));
            for (String scene : scenes) {
                ViewBucket bucket = bucket(scene, NORMAL);
                row.append(String.format(Locale.ROOT, " %11.3f",
                        bucket == null ? 0d : bucket.counterPerFrame(counter)));
            }
            line(out, row.toString());
        }
        StringBuilder drawn = new StringBuilder(String.format(Locale.ROOT, "%-34s", "sections drawn: all layers"));
        StringBuilder entities = new StringBuilder(String.format(Locale.ROOT, "%-34s", "entities drawn"));
        StringBuilder tiles = new StringBuilder(String.format(Locale.ROOT, "%-34s", "block entities drawn"));
        StringBuilder tags = new StringBuilder(String.format(Locale.ROOT, "%-34s", "nametags drawn"));
        StringBuilder pairs = new StringBuilder(String.format(Locale.ROOT, "%-34s", "timed probe pairs"));
        for (String scene : scenes) {
            ViewBucket bucket = bucket(scene, NORMAL);
            drawn.append(String.format(Locale.ROOT, " %11.1f", bucket == null ? 0d : sectionsDrawn(bucket)));
            entities.append(String.format(Locale.ROOT, " %11.1f",
                    bucket == null ? 0d : bucket.callsPerFrame(ViewSection.ENTITY)));
            tiles.append(String.format(Locale.ROOT, " %11.1f",
                    bucket == null ? 0d : bucket.callsPerFrame(ViewSection.BLOCK_ENTITY)));
            tags.append(String.format(Locale.ROOT, " %11.1f",
                    bucket == null ? 0d : bucket.callsPerFrame(ViewSection.NAMETAG)));
            pairs.append(String.format(Locale.ROOT, " %11.1f",
                    bucket == null ? 0d : bucket.probePairsPerFrame()));
        }
        line(out, drawn.toString());
        line(out, entities.toString());
        line(out, tiles.toString());
        line(out, tags.toString());
        line(out, pairs.toString());
        line(out, "");
    }

    private void arms(StringBuilder out) {
        line(out, "ARMS (interleaved with the normal arm, same scenes)");
        line(out, "---------------------------------------------------");
        line(out, "probes-off: only the frame interval is timed. normal - probes-off = what timing costs.");
        line(out, "fathom-off: Fathom's hooks bypassed and its performance layer forced off.");
        line(out, "            normal - fathom-off = Fathom's net cost (positive) or saving (negative).");
        line(out, "drain:      glFinish before every swap. The drain time is GPU/driver work still queued");
        line(out, "            when the CPU finished the frame; near zero means the GPU is not the limit.");
        line(out, String.format(Locale.ROOT, "%-12s %10s %10s %10s %10s %10s %10s %10s %10s",
                "scene", "normal", "probes-off", "overhead", "estimate", "fathom-off", "Fathom net",
                "drain wait", "drain frm"));
        for (String scene : scenes) {
            ViewBucket normal = bucket(scene, NORMAL);
            if (normal == null || normal.frames() == 0) {
                continue;
            }
            ViewBucket off = bucket(scene, PROBES_OFF);
            ViewBucket fathom = bucket(scene, FATHOM_OFF);
            ViewBucket drain = bucket(scene, DRAIN);
            double estimate = normal.probePairsPerFrame() * probePairNanos / 1_000_000d;
            line(out, String.format(Locale.ROOT, "%-12s %10.4f %10s %10s %10.4f %10s %10s %10s %10s",
                    scene, normal.meanFrameMillis(),
                    off == null ? "-" : fmt(off.meanFrameMillis()),
                    off == null ? "-" : signed(normal.meanFrameMillis() - off.meanFrameMillis()),
                    estimate,
                    fathom == null ? "-" : fmt(fathom.meanFrameMillis()),
                    fathom == null ? "-" : signed(normal.meanFrameMillis() - fathom.meanFrameMillis()),
                    drain == null ? "-" : fmt(drain.totalMillis(ViewSection.DRAIN)),
                    drain == null ? "-" : fmt(drain.meanFrameMillis())));
        }
        line(out, "");
        // Arms beyond the standard four: OptiFine option toggles and the like.
        for (String arm : arms) {
            if (arm.equals(NORMAL) || arm.equals(PROBES_OFF) || arm.equals(FATHOM_OFF) || arm.equals(DRAIN)) {
                continue;
            }
            line(out, "ARM " + arm + " against normal:");
            for (String scene : scenes) {
                ViewBucket normal = bucket(scene, NORMAL);
                ViewBucket other = bucket(scene, arm);
                if (normal == null || other == null || other.frames() == 0) {
                    continue;
                }
                line(out, String.format(Locale.ROOT,
                        "  %-12s normal %.4f ms -> %.4f ms (%+.4f ms, %+.1f%%); median %.4f -> %.4f; terrain %.4f -> %.4f",
                        scene, normal.meanFrameMillis(), other.meanFrameMillis(),
                        other.meanFrameMillis() - normal.meanFrameMillis(),
                        normal.meanFrameMillis() <= 0d ? 0d
                                : (other.meanFrameMillis() - normal.meanFrameMillis()) / normal.meanFrameMillis() * 100d,
                        normal.frameMillisAt(50d), other.frameMillisAt(50d),
                        terrainMillis(normal), terrainMillis(other)));
                line(out, String.format(Locale.ROOT,
                        "  %-12s pacing: sd %.4f -> %.4f ms; p1 %.4f -> %.4f; p99 %.4f -> %.4f",
                        "", normal.frameStddevMillis(), other.frameStddevMillis(),
                        normal.frameMillisAt(1d), other.frameMillisAt(1d),
                        normal.frameMillisAt(99d), other.frameMillisAt(99d)));
                line(out, String.format(Locale.ROOT,
                        "  %-12s window length: %.3f -> %.3f s (the rebuild time, in the reload scene)",
                        "", normal.meanWindowSeconds(), other.meanWindowSeconds()));
            }
            line(out, "");
        }
    }

    /** Every bucket, every section, as CSV for anyone who wants to redo the arithmetic. */
    public String csv() {
        StringBuilder out = new StringBuilder(65_536);
        out.append("scene,arm,frames,rejected,frame_mean_ms,frame_p50_ms,frame_p95_ms,frame_p99_ms,section,owner,"
                + "total_ms,self_ms,self_p50_ms,self_p95_ms,calls_per_frame\n");
        for (String scene : scenes) {
            for (String arm : arms) {
                ViewBucket bucket = bucket(scene, arm);
                if (bucket == null || bucket.frames() == 0) {
                    continue;
                }
                String prefix = String.format(Locale.ROOT, "%s,%s,%d,%d,%.5f,%.5f,%.5f,%.5f,", scene, arm,
                        bucket.frames(), bucket.rejected(), bucket.meanFrameMillis(), bucket.frameMillisAt(50d),
                        bucket.frameMillisAt(95d), bucket.frameMillisAt(99d));
                for (ViewSection section : ViewSection.ALL) {
                    out.append(prefix).append(section.name()).append(',').append(section.owner.name())
                            .append(String.format(Locale.ROOT, ",%.6f,%.6f,%.6f,%.6f,%.4f\n",
                                    bucket.totalMillis(section), bucket.selfMillis(section),
                                    bucket.selfMillisAt(section, 50d), bucket.selfMillisAt(section, 95d),
                                    bucket.callsPerFrame(section)));
                }
                for (ViewCounter counter : ViewCounter.ALL) {
                    out.append(prefix).append("COUNTER_").append(counter.name()).append(",,")
                            .append(String.format(Locale.ROOT, "%.4f,,,,\n", bucket.counterPerFrame(counter)));
                }
            }
        }
        return out.toString();
    }

    private static void row(StringBuilder out, String label, double millis) {
        line(out, String.format(Locale.ROOT, "  %-28s %8.4f ms", label + ":", millis));
    }

    private static String fmt(double value) {
        return String.format(Locale.ROOT, "%.4f", value);
    }

    private static String signed(double value) {
        return String.format(Locale.ROOT, "%+.4f", value);
    }

    private static void line(StringBuilder out, String text) {
        out.append(text).append('\n');
    }
}
