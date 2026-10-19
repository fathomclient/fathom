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
        multiplayer(out);
        armDistributions(out);
        comparison(out);
        for (String scene : scenes) {
            breakdown(out, scene);
        }
        deltasSection(out);
        topConsumers(out);
        owners(out);
        counters(out);
        packets(out);
        perPlayer(out);
        tail(out);
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
        java.util.List<String> undrawn = new java.util.ArrayList<String>();
        for (String scene : scenes) {
            ViewBucket bucket = bucket(scene, NORMAL);
            if (bucket == null || bucket.frames() == 0) {
                continue;
            }
            double loaded = bucket.counterPerFrame(ViewCounter.PLAYERS_LOADED);
            double drawn = bucket.callsPerFrame(ViewSection.ENTITY_PLAYER);
            if (loaded >= 4d && drawn < loaded * 0.9d) {
                undrawn.add(String.format(Locale.ROOT, "%s (%.1f of %.1f players drawn)", scene, drawn, loaded));
            }
        }
        if (!undrawn.isEmpty()) {
            // Usually entities that reached the client before their chunk did: 1.8
            // files them in the empty placeholder chunk and never draws them. Such a
            // scene is not the scene described; do not use it for comparisons.
            line(out, "INVALID SCENES: players loaded but not drawn in " + undrawn
                    + ". Run with mp-empty first.");
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

    // -----------------------------------------------------------------------
    // Multiplayer profile
    // -----------------------------------------------------------------------

    /** The scene every multiplayer scene is compared with: the singleplayer copy, if it ran. */
    private ViewBucket singleplayer() {
        ViewBucket sp = bucket("sp", NORMAL);
        return sp != null && sp.frames() > 0 ? sp : null;
    }

    private boolean multiplayerRun() {
        for (String scene : scenes) {
            if (scene.startsWith("mp-") || scene.equals("lobby") || scene.equals("live") || scene.equals("sp")) {
                return true;
            }
        }
        return false;
    }

    private void multiplayer(StringBuilder out) {
        java.util.List<String> unequalScenes = new java.util.ArrayList<String>();
        if (!multiplayerRun()) {
            return;
        }
        ViewBucket sp = singleplayer();
        line(out, "MULTIPLAYER VS SINGLEPLAYER (normal arm, ms per frame; delta against 'sp')");
        line(out, "--------------------------------------------------------------------------");
        line(out, String.format(Locale.ROOT, "%-17s %7s %7s %7s %7s | %7s %7s %7s %7s | %6s %6s %6s %7s %8s %8s %6s",
                "scene", "median", "mean", "p95", "p99", "d med", "d mean", "d p95", "d p99",
                "plyrs", "drawn", "tags", "pkt/s", "B/frame", "MB/s", "GC/m"));
        for (String scene : scenes) {
            ViewBucket bucket = bucket(scene, NORMAL);
            if (bucket == null || bucket.frames() == 0) {
                continue;
            }
            double packetsPerSecond = 0d;
            for (PacketKind kind : PacketKind.ALL) {
                if (kind != PacketKind.NOT_A_PACKET) {
                    packetsPerSecond += bucket.packetPerSecond(kind, PacketKind.APPLIED);
                }
            }
            double minutes = bucket.measuredSeconds / 60d;
            line(out, String.format(Locale.ROOT,
                    "%-17s %7.3f %7.3f %7.3f %7.3f | %7s %7s %7s %7s | %6.1f %6.1f %6.1f %7.0f %8.0f %8.1f %6.1f",
                    scene, bucket.frameMillisAt(50d), bucket.meanFrameMillis(), bucket.frameMillisAt(95d),
                    bucket.frameMillisAt(99d),
                    sp == null ? "-" : signed3(bucket.frameMillisAt(50d) - sp.frameMillisAt(50d)),
                    sp == null ? "-" : signed3(bucket.meanFrameMillis() - sp.meanFrameMillis()),
                    sp == null ? "-" : signed3(bucket.frameMillisAt(95d) - sp.frameMillisAt(95d)),
                    sp == null ? "-" : signed3(bucket.frameMillisAt(99d) - sp.frameMillisAt(99d)),
                    bucket.counterPerFrame(ViewCounter.PLAYERS_LOADED), bucket.callsPerFrame(ViewSection.ENTITY_PLAYER),
                    bucket.callsPerFrame(ViewSection.NAMETAG), packetsPerSecond,
                    bucket.clientBytesPerFrame(), bucket.clientAllocationMegabytesPerSecond(),
                    minutes <= 0d ? 0d : bucket.gcCount() / minutes));
            double loaded = bucket.counterPerFrame(ViewCounter.PLAYERS_LOADED);
            double drawn = bucket.callsPerFrame(ViewSection.ENTITY_PLAYER);
            if (loaded >= 4d && drawn < loaded * 0.9d) {
                // The scene did not come up as described: players out of view or not
                // where the driver put them. Its frame times are not comparable with
                // another run's of the same scene; compare per player drawn instead.
                unequalScenes.add(String.format(Locale.ROOT, "%s (%.1f of %.1f drawn)", scene, drawn, loaded));
            }
        }
        if (!unequalScenes.isEmpty()) {
            line(out, "WARNING: fewer players drawn than loaded in " + unequalScenes
                    + ". Compare these with other runs per player drawn (ONE PLAYER), not per frame.");
        }
        line(out, "plyrs = other players loaded; drawn = player entities drawn per frame; tags = labels drawn;");
        line(out, "pkt/s = packets applied on the client thread; B/frame and MB/s = client-thread allocation.");
        line(out, "");
        if (sp == null) {
            return;
        }
        line(out, "WHERE THE MULTIPLAYER TIME GOES (normal arm, mean ms per frame, delta against 'sp')");
        line(out, "-----------------------------------------------------------------------------------");
        ViewSection[] rows = {
            ViewSection.TASKS, ViewSection.TICK, ViewSection.TICK_ENTITIES, ViewSection.TICK_PLAYER,
            ViewSection.TICK_PARTICLES, ViewSection.SETUP_TERRAIN, ViewSection.CHUNK_UPDATES,
            ViewSection.ENTITIES, ViewSection.FATHOM_CULL, ViewSection.ENTITY_PLAYER, ViewSection.MODEL,
            ViewSection.FEATURES, ViewSection.ARMOR, ViewSection.HELD_ITEM, ViewSection.CAPE,
            ViewSection.SHADOW_FIRE, ViewSection.NAMETAG_DECIDE, ViewSection.NAMETAG, ViewSection.ENTITY_ITEM,
            ViewSection.ENTITY, ViewSection.PARTICLES, ViewSection.HUD, ViewSection.HUD_SCOREBOARD,
            ViewSection.HUD_TABLIST, ViewSection.HUD_CHAT, ViewSection.HUD_HOTBAR, ViewSection.FATHOM_HUD,
            ViewSection.TEXT, ViewSection.SWAP,
        };
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-44s %8s", "section (total time)", "sp"));
        for (String scene : scenes) {
            if (!scene.equals("sp")) {
                head.append(String.format(Locale.ROOT, " %9s", shortName(scene)));
            }
        }
        line(out, head.toString());
        for (ViewSection section : rows) {
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-44s %8.4f", section.label,
                    sp.totalMillis(section)));
            for (String scene : scenes) {
                if (scene.equals("sp")) {
                    continue;
                }
                ViewBucket bucket = bucket(scene, NORMAL);
                row.append(bucket == null || bucket.frames() == 0 ? String.format(Locale.ROOT, " %9s", "-")
                        : String.format(Locale.ROOT, " %+9.4f", bucket.totalMillis(section) - sp.totalMillis(section)));
            }
            line(out, row.toString());
        }
        StringBuilder total = new StringBuilder(String.format(Locale.ROOT, "%-44s %8.4f", "frame (mean)",
                sp.meanFrameMillis()));
        for (String scene : scenes) {
            if (scene.equals("sp")) {
                continue;
            }
            ViewBucket bucket = bucket(scene, NORMAL);
            total.append(bucket == null || bucket.frames() == 0 ? String.format(Locale.ROOT, " %9s", "-")
                    : String.format(Locale.ROOT, " %+9.4f", bucket.meanFrameMillis() - sp.meanFrameMillis()));
        }
        line(out, total.toString());
        line(out, "Rows nest (players inside entities, layers inside players) and TEXT overlaps the HUD and");
        line(out, "nametags, so the rows do not add up to the frame. The frame row is the real delta.");
        line(out, "");
    }

    private static String shortName(String scene) {
        return scene.length() <= 9 ? scene : scene.substring(0, 9);
    }

    /** Every arm's distribution and its paired difference from the normal arm, window by window. */
    private void armDistributions(StringBuilder out) {
        if (arms.size() < 2) {
            return;
        }
        line(out, "ARMS: DISTRIBUTION AND PAIRED DIFFERENCE (ms per frame)");
        line(out, "--------------------------------------------------------");
        line(out, "Windows of the same round are paired: an arm and the normal arm measured back to back in the");
        line(out, "same scene. 'paired' = median over rounds of (arm window mean - normal window mean);");
        line(out, "'wins' = rounds in which the arm was faster than normal.");
        line(out, String.format(Locale.ROOT, "%-17s %-34s %7s %7s %7s %7s %8s %8s %8s %8s %8s %5s",
                "scene", "arm", "median", "mean", "p95", "p99", "d med", "d mean", "d p95", "d p99", "paired",
                "wins"));
        for (String scene : scenes) {
            ViewBucket normal = bucket(scene, NORMAL);
            if (normal == null || normal.frames() == 0) {
                continue;
            }
            for (String arm : arms) {
                ViewBucket other = bucket(scene, arm);
                if (other == null || other.frames() == 0) {
                    continue;
                }
                boolean base = arm.equals(NORMAL);
                double[] paired = base ? null : paired(normal, other);
                line(out, String.format(Locale.ROOT, "%-17s %-34s %7.3f %7.3f %7.3f %7.3f %8s %8s %8s %8s %8s %5s",
                        scene, arm.length() > 34 ? arm.substring(0, 34) : arm,
                        other.frameMillisAt(50d), other.meanFrameMillis(),
                        other.frameMillisAt(95d), other.frameMillisAt(99d),
                        base ? "" : signed3(other.frameMillisAt(50d) - normal.frameMillisAt(50d)),
                        base ? "" : signed3(other.meanFrameMillis() - normal.meanFrameMillis()),
                        base ? "" : signed3(other.frameMillisAt(95d) - normal.frameMillisAt(95d)),
                        base ? "" : signed3(other.frameMillisAt(99d) - normal.frameMillisAt(99d)),
                        paired == null ? "" : signed3(paired[0]),
                        paired == null ? "" : String.format(Locale.ROOT, "%d/%d", (int) paired[1], (int) paired[2])));
            }
        }
        line(out, "");
    }

    /** [median paired difference, rounds the arm won, rounds compared]. */
    static double[] paired(ViewBucket normal, ViewBucket arm) {
        List<Double> a = normal.windowMeans();
        List<Double> b = arm.windowMeans();
        int count = Math.min(a.size(), b.size());
        if (count == 0) {
            return new double[] {0d, 0d, 0d};
        }
        double[] differences = new double[count];
        int wins = 0;
        for (int index = 0; index < count; index++) {
            differences[index] = b.get(index).doubleValue() - a.get(index).doubleValue();
            if (differences[index] < 0d) {
                wins++;
            }
        }
        java.util.Arrays.sort(differences);
        double median = count % 2 == 1 ? differences[count / 2]
                : (differences[count / 2 - 1] + differences[count / 2]) / 2d;
        return new double[] {median, wins, count};
    }

    private void packets(StringBuilder out) {
        if (!multiplayerRun()) {
            return;
        }
        line(out, "PACKETS ON THE CLIENT THREAD (normal arm)");
        line(out, "-----------------------------------------");
        line(out, "Per scene: packets queued and applied per second, client-thread microseconds per frame spent");
        line(out, "applying them, microseconds per packet, and chunk sections they invalidated per second.");
        for (String scene : scenes) {
            ViewBucket bucket = bucket(scene, NORMAL);
            if (bucket == null || bucket.frames() == 0) {
                continue;
            }
            line(out, scene + String.format(Locale.ROOT, "  (most packets in one frame: %d)", bucket.packetPeak()));
            line(out, String.format(Locale.ROOT, "  %-56s %9s %9s %9s %9s %9s", "kind", "queued/s", "applied/s",
                    "us/frame", "us/packet", "inval/s"));
            for (PacketKind kind : PacketKind.ALL) {
                double applied = bucket.packetPerSecond(kind, PacketKind.APPLIED);
                double queued = bucket.packetPerSecond(kind, PacketKind.QUEUED);
                if (applied <= 0d && queued <= 0d) {
                    continue;
                }
                double nanosPerFrame = bucket.packetPerFrame(kind, PacketKind.NANOS);
                double perPacket = bucket.packetPerFrame(kind, PacketKind.APPLIED);
                line(out, String.format(Locale.ROOT, "  %-56s %9.1f %9.1f %9.2f %9.2f %9.1f",
                        kind.label.length() > 56 ? kind.label.substring(0, 56) : kind.label, queued, applied,
                        nanosPerFrame / 1000d, perPacket <= 0d ? 0d : nanosPerFrame / perPacket / 1000d,
                        bucket.packetPerSecond(kind, PacketKind.INVALIDATIONS)));
            }
        }
        line(out, "");
    }

    private void perPlayer(StringBuilder out) {
        if (!multiplayerRun()) {
            return;
        }
        line(out, "ONE PLAYER (normal arm, microseconds per player drawn)");
        line(out, "------------------------------------------------------");
        ViewSection[] parts = {
            ViewSection.ENTITY_PLAYER, ViewSection.MODEL, ViewSection.FEATURES, ViewSection.ARMOR,
            ViewSection.HELD_ITEM, ViewSection.CAPE, ViewSection.SHADOW_FIRE, ViewSection.NAMETAG_DECIDE,
            ViewSection.NAMETAG,
        };
        StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "%-17s %7s", "scene", "drawn/f"));
        for (ViewSection part : parts) {
            head.append(String.format(Locale.ROOT, " %9s", shortName(part.name().toLowerCase(Locale.ROOT))));
        }
        line(out, head.toString());
        for (String scene : scenes) {
            ViewBucket bucket = bucket(scene, NORMAL);
            if (bucket == null || bucket.callsPerFrame(ViewSection.ENTITY_PLAYER) <= 0d) {
                continue;
            }
            double drawn = bucket.callsPerFrame(ViewSection.ENTITY_PLAYER);
            StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "%-17s %7.1f", scene, drawn));
            for (ViewSection part : parts) {
                // Total time over players drawn: the layers of mobs (none in these scenes) would land here too.
                row.append(String.format(Locale.ROOT, " %9.2f", bucket.totalMillis(part) * 1000d / drawn));
            }
            line(out, row.toString());
        }
        line(out, "Columns are total (inclusive) time divided by players drawn. entity_pl is the whole draw;");
        line(out, "the rest nest inside it. Probe cost is included (about two timed pairs per column per player).");
        line(out, "");
    }

    private void tail(StringBuilder out) {
        line(out, "WHAT THE SLOW FRAMES CONTAIN (normal arm)");
        line(out, "-----------------------------------------");
        line(out, "Frames ranked by time. 'median band' = ranks 40-60%; 'p95+' = the slowest 5%; 'p99+' = slowest 1%.");
        line(out, "tick% = share of the band's frames that ran a client tick; gc% = share in which a GC finished.");
        for (String scene : scenes) {
            ViewBucket bucket = bucket(scene, NORMAL);
            if (bucket == null || bucket.frames() < 200) {
                continue;
            }
            double[] split = bucket.tickSplit();
            line(out, String.format(Locale.ROOT,
                    "%s  (frames with a tick: %.1f%%, mean %.3f ms; without: %.3f ms; tick cost %.3f ms)", scene,
                    split[2] * 100d, split[0], split[1], split[0] - split[1]));
            double[][] bands = {bucket.band(40d, 60d), bucket.band(95d, 100d), bucket.band(99d, 100d)};
            String[] names = {"median band", "p95+", "p99+"};
            StringBuilder head = new StringBuilder(String.format(Locale.ROOT, "  %-12s %8s %6s %6s %6s", "band",
                    "frame", "tick%", "gc%", "pkts"));
            for (ViewSection section : ViewBucket.TAIL) {
                head.append(String.format(Locale.ROOT, " %9s", shortName(section.name().toLowerCase(Locale.ROOT))));
            }
            line(out, head.toString());
            for (int band = 0; band < bands.length; band++) {
                double[] values = bands[band];
                StringBuilder row = new StringBuilder(String.format(Locale.ROOT, "  %-12s %8.3f %5.1f%% %5.1f%% %6.1f",
                        names[band], values[0], values[1] * 100d, values[2] * 100d, values[3]));
                for (int at = 0; at < ViewBucket.TAIL.length; at++) {
                    row.append(String.format(Locale.ROOT, " %9.4f", values[4 + at]));
                }
                line(out, row.toString());
            }
        }
        line(out, "");
    }

    private static String signed3(double value) {
        return String.format(Locale.ROOT, "%+.3f", value);
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
                    bucket == null ? 0d : bucket.callsPerFrame(ViewSection.ENTITY)
                            + bucket.callsPerFrame(ViewSection.ENTITY_PLAYER)
                            + bucket.callsPerFrame(ViewSection.ENTITY_ITEM)));
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
