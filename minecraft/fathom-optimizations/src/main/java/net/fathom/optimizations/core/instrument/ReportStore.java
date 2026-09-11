package net.fathom.optimizations.core.instrument;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.util.Arrays;
import java.util.Comparator;

/**
 * Where a benchmark run is written so the launcher can read it.
 *
 * <h2>Why a file and not the launcher bridge</h2>
 *
 * <p>{@code LauncherConfig} runs one way — launcher to game, read once at startup — and
 * keeping it that way is what makes it easy to reason about. A benchmark result is the
 * traffic in the other direction, and it is not a setting: it is an artefact of a run,
 * produced at an unpredictable moment, and worth keeping more than one of.
 *
 * <p>So results go into their own directory as timestamped files. The launcher lists them
 * and shows the newest; nothing has to be running for that to work, and a result from
 * three sessions ago is still there.
 *
 * <h2>Why JSON here and properties everywhere else</h2>
 *
 * <p>Because the direction of travel is reversed. Properties are used where <em>Java</em>
 * has to parse — the JDK has a parser and the core has no dependencies. Here the reader is
 * Rust, which has a good JSON parser and no properties parser, and the writer is
 * {@link Report#toJson}, which already existed. Each side gets the format it can read
 * without adding a dependency, which is the same rule producing a different answer.
 */
public final class ReportStore {

    /** Relative to the game directory. */
    public static final String DIRECTORY = "fathom/benchmarks";

    /**
     * How many results to keep.
     *
     * <p>Old runs are worth having — the point of measuring is comparison — but not
     * forever, and an unbounded directory in somebody's game folder is litter. Twenty is
     * enough to cover a session of trying settings and small enough to never be noticed.
     */
    public static final int KEEP = 20;

    private ReportStore() {}

    public static File directory(File gameDirectory) {
        return new File(gameDirectory, DIRECTORY);
    }

    /**
     * Write a report, then prune.
     *
     * @return the file written
     */
    public static File write(File gameDirectory, Report report) throws IOException {
        File directory = directory(gameDirectory);
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("could not create " + directory);
        }
        // Wall-clock start rather than "now": the file name should say when the run
        // happened, and two runs cannot start in the same millisecond.
        File file = new File(directory, report.startedWallMillis() + ".json");
        Writer writer = null;
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            writer = new OutputStreamWriter(out, "UTF-8");
            writer.write(report.toJson());
        } finally {
            close(writer);
            close(out);
        }
        prune(gameDirectory);
        return file;
    }

    /** Everything on disk, newest first. */
    public static File[] list(File gameDirectory) {
        File directory = directory(gameDirectory);
        File[] found = directory.listFiles();
        if (found == null) {
            return new File[0];
        }
        File[] results = new File[found.length];
        int count = 0;
        for (File candidate : found) {
            if (candidate.isFile() && candidate.getName().endsWith(".json")) {
                results[count++] = candidate;
            }
        }
        results = Arrays.copyOf(results, count);
        Arrays.sort(
                results,
                new Comparator<File>() {
                    @Override
                    public int compare(File left, File right) {
                        // By name, which is a timestamp, so this is chronological
                        // without asking the filesystem for a modification time that
                        // a copy would have changed.
                        return right.getName().compareTo(left.getName());
                    }
                });
        return results;
    }

    private static void prune(File gameDirectory) {
        File[] existing = list(gameDirectory);
        for (int index = KEEP; index < existing.length; index++) {
            // A failed delete is not worth failing the write over: the result is
            // saved, and the directory being one file larger than intended is not a
            // problem anybody has.
            if (!existing[index].delete()) {
                break;
            }
        }
    }

    private static void close(java.io.Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // The write already succeeded or already threw; a close failure adds
                // nothing a caller could act on.
            }
        }
    }
}
