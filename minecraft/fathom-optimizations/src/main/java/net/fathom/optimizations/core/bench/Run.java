package net.fathom.optimizations.core.bench;

import java.io.File;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

import net.fathom.optimizations.core.instrument.Report;

/**
 * One benchmark run: start, wait, discard the warm-up, save.
 *
 * <h2>Why a fixed duration and not a fixed frame count</h2>
 *
 * <p>Because the configurations being compared run at different frame rates, and
 * a fixed frame count would give the slow one a longer wall-clock window — more
 * time for a garbage collection, a chunk load or a thermal step to land in it.
 * Sixty seconds is sixty seconds in every configuration; what differs is how
 * many frames fit, which is the thing being measured.
 *
 * <h2>Why the warm-up is discarded and not just noted</h2>
 *
 * <p>The first few hundred frames of a JVM are not the program. The JIT has not
 * compiled the render path, the chunk meshes are being built for the first time,
 * and the texture atlas is still being uploaded. Including them does not add
 * noise, it adds a consistent bias — and it biases the slower configuration
 * less, because it spends proportionally less of a fixed window warming up.
 * That is a bias in the direction of the wrong conclusion.
 *
 * <p>{@code Session} already drops its first {@link
 * net.fathom.optimizations.core.instrument.Session#DEFAULT_WARMUP_FRAMES}
 * frames. This adds a wall-clock floor on top, because five hundred frames at
 * 400 fps is a second and a quarter, which is not enough time for a chunk queue
 * to settle.
 *
 * <h2>Where results go</h2>
 *
 * <pre>
 *   benchmarks/2026-09-07/d-performance-off.json
 *   benchmarks/2026-09-07/summary.md
 * </pre>
 *
 * <p>Under the game directory, one directory per day, one file per
 * configuration. The date rather than a timestamp because the unit of work is a
 * session of runs, and a second run of the same configuration on the same day is
 * a correction of the first rather than a second data point — see
 * {@link #fileFor}.
 */
public final class Run {

    /** The directory results go in, relative to the game directory. */
    public static final String DIRECTORY = "benchmarks";

    /**
     * The shortest run worth saving.
     *
     * <p>Thirty seconds is roughly ten thousand frames at 300 fps, which is
     * enough for a 0.1% low to mean something: it is the worst ten frames, and
     * ten frames out of a thousand is one unlucky garbage collection.
     */
    public static final int MINIMUM_SECONDS = 30;

    /** The wall-clock warm-up, on top of the frame count the session discards. */
    public static final int WARMUP_SECONDS = 10;

    private Run() {}

    /**
     * Where a configuration's result goes today.
     *
     * <p>Overwrites a previous run of the same configuration on the same day.
     * Deliberately: a benchmark session is "run all six, compare", and a second
     * attempt at one of them means the first was wrong — kept alongside, it
     * would be a file nobody can choose between later.
     */
    public static File fileFor(File gameDirectory, String configurationId) {
        return new File(dayDirectory(gameDirectory), safe(configurationId) + ".json");
    }

    /** Today's results directory, created if needed. */
    public static File dayDirectory(File gameDirectory) {
        return new File(new File(gameDirectory, DIRECTORY), today());
    }

    /** The summary for today's session. */
    public static File summaryFile(File gameDirectory) {
        return new File(dayDirectory(gameDirectory), "summary.md");
    }

    /**
     * Write a report.
     *
     * @return the file written
     */
    public static File save(File gameDirectory, Report report) throws IOException {
        File directory = dayDirectory(gameDirectory);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }
        String id = report.configuration().isEmpty() ? report.label() : report.configuration();
        File file = new File(directory, safe(id) + ".json");
        Writer writer = null;
        try {
            writer = new OutputStreamWriter(new java.io.FileOutputStream(file), "UTF-8");
            writer.write(report.toJson());
        } finally {
            if (writer != null) {
                writer.close();
            }
        }
        return file;
    }

    /**
     * Whether a report is worth keeping.
     *
     * <p>The check that stops a bad run being published. All four of these have
     * happened during this milestone's own testing, and each of them produces a
     * number that looks fine and means nothing.
     *
     * @return the reason to discard it, or null when it is sound
     */
    public static String rejectionReason(Report report, boolean windowWasFocused) {
        if (!windowWasFocused) {
            // The one that bit hardest: automation loses focus, the unfocused
            // frame cap engages, and every configuration reports exactly 30 fps.
            return "the window lost focus during the run, so the unfocused frame cap was in "
                    + "effect and every reading is the cap rather than the machine";
        }
        if (report.durationMillis() < MINIMUM_SECONDS * 1000L) {
            return "the run was " + (report.durationMillis() / 1000L) + " seconds; "
                    + MINIMUM_SECONDS + " is the shortest that supports a 1% low";
        }
        if (report.frames().sampleCount() < 1000) {
            return "only " + report.frames().sampleCount()
                    + " frames were recorded, which is too few for a percentile";
        }
        if (report.configuration().isEmpty()) {
            return "the run did not record which configuration it was, so it cannot be compared "
                    + "to anything";
        }
        java.util.List<String> missing = report.environment().missing();
        if (!missing.isEmpty()) {
            return "these settings were not recorded, so another run cannot be checked against "
                    + "it: " + join(missing);
        }
        return null;
    }

    private static String join(java.util.List<String> values) {
        StringBuilder out = new StringBuilder();
        for (int index = 0; index < values.size(); index++) {
            if (index > 0) {
                out.append(", ");
            }
            out.append(values.get(index));
        }
        return out.toString();
    }

    /**
     * Today, as the launcher's date formatting produces it.
     *
     * <p>UTC, so a session that crosses midnight in one timezone does not split
     * across two directories in another.
     */
    public static String today() {
        SimpleDateFormat format = new SimpleDateFormat("yyyy-MM-dd", Locale.ROOT);
        format.setTimeZone(TimeZone.getTimeZone("UTC"));
        return format.format(new Date());
    }

    /** A configuration id, made safe to be a filename. */
    private static String safe(String id) {
        StringBuilder out = new StringBuilder(id.length());
        for (int index = 0; index < id.length(); index++) {
            char character = id.charAt(index);
            boolean ok = (character >= 'a' && character <= 'z')
                    || (character >= 'A' && character <= 'Z')
                    || (character >= '0' && character <= '9')
                    || character == '-'
                    || character == '_';
            out.append(ok ? character : '-');
        }
        String cleaned = out.toString();
        return cleaned.isEmpty() ? "run" : cleaned;
    }
}
