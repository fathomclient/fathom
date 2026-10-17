package net.fathom.optimizations.fabric.bench.auto;

import java.io.File;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.MinecraftClient;
import net.minecraft.text.LiteralText;

import net.fathom.optimizations.core.bench.auto.BenchmarkPass;
import net.fathom.optimizations.core.bench.auto.BenchmarkReportWriter;
import net.fathom.optimizations.core.bench.auto.BenchmarkResult;

/**
 * Says the benchmark is done, in the cheapest surface that already exists.
 *
 * <h2>Why chat and not a screen</h2>
 *
 * <p>Because the spec for this feature was not to redesign anything, and a
 * dedicated screen would have to be dismissed -- which means it must not appear
 * while somebody is in a fight on a server. Chat is already the place a client
 * tells you something happened, it works identically in singleplayer and
 * multiplayer, and it leaves the result on screen without taking input away.
 *
 * <p>The full report is a file. This is the pointer to it plus the two numbers
 * that decide whether the file is worth opening.
 */
public final class BenchmarkOverlay {

    private static final String PREFIX = "§c[Fathom]§r ";

    private BenchmarkOverlay() {}

    public static void show(List<BenchmarkResult> results, File report) {
        StringBuilder out = new StringBuilder(256);
        out.append("Benchmark complete.");

        BenchmarkResult normal = find(results, "normal");
        BenchmarkResult minimal = find(results, "minimal");
        if (normal != null) {
            out.append(String.format(Locale.ROOT, "  Normal: %.0f FPS / %.2fms.",
                    normal.averageFps(), normal.meanFrameMillis()));
        }
        if (minimal != null) {
            out.append(String.format(Locale.ROOT, "  Minimal: %.0f FPS / %.2fms.",
                    minimal.averageFps(), minimal.meanFrameMillis()));
        }

        String largest = largestCost(results, minimal);
        if (!largest.isEmpty()) {
            out.append("  Largest: ").append(largest).append('.');
        }

        say(out.toString());
        if (report != null) {
            say("Report: " + report.getName() + " (in logs/)");
        }
    }

    private static String largestCost(List<BenchmarkResult> results, BenchmarkResult minimal) {
        if (minimal == null) {
            return "";
        }
        BenchmarkResult worst = null;
        double worstCost = BenchmarkReportWriter.NOISE_FLOOR_MILLIS;
        for (BenchmarkResult each : results) {
            if (each.pass().kind() != BenchmarkPass.Kind.ISOLATE) {
                continue;
            }
            double cost = each.costOver(minimal);
            if (cost > worstCost) {
                worstCost = cost;
                worst = each;
            }
        }
        if (worst == null) {
            // Said plainly rather than left blank: "nothing cleared the floor"
            // is the most useful single sentence a run can produce, because it
            // redirects the reader away from the subsystem table.
            return "nothing above the noise floor";
        }
        return String.format(Locale.ROOT, "%s (+%.2fms)", worst.pass().label(), worstCost);
    }

    private static BenchmarkResult find(List<BenchmarkResult> results, String id) {
        for (BenchmarkResult each : results) {
            if (each.pass().id().equals(id)) {
                return each;
            }
        }
        return null;
    }

    /** Chat if there is a HUD to put it in; the console regardless. */
    private static void say(String message) {
        System.out.println("[FATHOM BENCH] " + message);
        try {
            MinecraftClient client = MinecraftClient.getInstance();
            if (client != null && client.inGameHud != null) {
                client.inGameHud.getChatHud().addMessage(new LiteralText(PREFIX + message));
            }
        } catch (Throwable ignored) {
            // The console line above is the one that must always happen.
        }
    }
}
