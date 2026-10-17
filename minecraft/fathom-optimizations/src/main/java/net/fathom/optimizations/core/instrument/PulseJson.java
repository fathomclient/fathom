package net.fathom.optimizations.core.instrument;

import java.util.Locale;

/**
 * The pulse as the launcher reads it: {@code fathom/session-pulse.json}.
 *
 * <p>One fixed shape, written by hand rather than through a JSON library because the
 * core has none and the shape is small. The launcher's reader is
 * {@code crates/launcher-core/src/session_pulse.rs}; the field names here and the
 * {@code serde} names there are the contract, and both sides test the same example.
 *
 * <p>Nothing in it identifies anybody: frame rates, frame times, a heap size, a module
 * count, and the kind of each timeline mark. No world name, no server address, no
 * player name. It is written into the game's own directory and read by the launcher on
 * the same machine; it never goes anywhere else.
 */
public final class PulseJson {

    /** Where it is, relative to the game directory. Must match the Rust side. */
    public static final String RELATIVE_PATH = "fathom/session-pulse.json";

    /** Format version. Must match {@code session_pulse::SCHEMA}. */
    public static final int SCHEMA = 1;

    private PulseJson() {}

    /**
     * Write one document into {@code out}, which is cleared first.
     *
     * @param modules       Fathom modules switched on
     * @param updatedMillis wall-clock time of writing, for the launcher's staleness check
     */
    public static void write(Pulse.Snapshot snapshot, int modules, long updatedMillis, StringBuilder out) {
        out.setLength(0);
        out.append("{\"schema\":").append(SCHEMA);
        out.append(",\"updatedAt\":").append(updatedMillis);
        out.append(",\"secondsOfPlay\":").append(snapshot.secondsOfPlay);
        out.append(",\"frames\":").append(snapshot.frames);
        number(out, "currentFps", snapshot.currentFps, 1);
        number(out, "averageFps", snapshot.averageFps, 1);
        number(out, "p95Ms", snapshot.p95Millis, 2);
        number(out, "p99Ms", snapshot.p99Millis, 2);
        number(out, "worstMs", snapshot.worstMillis, 2);
        out.append(",\"hitches\":").append(snapshot.hitches);
        out.append(",\"heapUsedMb\":").append(snapshot.heapUsed / (1024L * 1024L));
        out.append(",\"heapMaxMb\":").append(snapshot.heapMax / (1024L * 1024L));
        out.append(",\"modules\":").append(Math.max(0, modules));
        out.append(",\"bucketSeconds\":").append(Pulse.BUCKET_SECONDS);
        out.append(",\"timelineFps\":[");
        for (int index = 0; index < snapshot.buckets; index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append(format(snapshot.bucketFps[index], 1));
        }
        out.append("],\"timelineWorstMs\":[");
        for (int index = 0; index < snapshot.buckets; index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append(format(snapshot.bucketWorstMillis[index], 1));
        }
        out.append("],\"marks\":[");
        for (int index = 0; index < snapshot.marks; index++) {
            if (index > 0) {
                out.append(',');
            }
            out.append("{\"bucket\":").append(snapshot.markBucket[index]);
            out.append(",\"kind\":\"").append(snapshot.markKind[index].name().toLowerCase(Locale.ROOT));
            out.append("\"}");
        }
        out.append("]}");
    }

    private static void number(StringBuilder out, String name, float value, int places) {
        out.append(",\"").append(name).append("\":").append(format(value, places));
    }

    /** Finite and plain: JSON has no NaN, and a reader should never meet one. */
    private static String format(float value, int places) {
        if (Float.isNaN(value) || Float.isInfinite(value)) {
            value = 0f;
        }
        return String.format(Locale.ROOT, "%." + places + "f", Float.valueOf(value));
    }
}
