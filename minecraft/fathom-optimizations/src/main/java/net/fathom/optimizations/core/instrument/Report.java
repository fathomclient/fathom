package net.fathom.optimizations.core.instrument;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * What one measurement run found.
 *
 * <p>Immutable. Serialised to JSON and written beside the profile, so a run can be
 * compared against another run weeks later, or attached to an issue.
 *
 * <h2>Caveats travel with the data</h2>
 *
 * <p>Every figure whose meaning is qualified carries its qualification in the object:
 * whether the frame log wrapped, whether allocation was measured or estimated, whether a
 * "0.1% low" covered enough frames to mean anything, and what the GC API cannot see. A
 * report that had to be read alongside a separate document to be understood would
 * eventually be read without it.
 */
public final class Report {

    private final String label;
    private final long startedWallMillis;
    private final long durationMillis;
    private final FrameStatistics frames;
    private final boolean frameLogWrapped;
    private final Map<Counters.Counter, Long> counters;
    private final List<MemorySampler.Sample> memorySamples;
    private final boolean allocationMeasured;
    private final String allocationMethod;
    private final Map<String, GcLog.Delta> gc;

    /** Which of the six comparison configurations, or empty. */
    private final String configuration;
    /** Which scenario, or empty. */
    private final String scenario;
    /** The settings this run happened under. */
    private final net.fathom.optimizations.core.bench.Environment environment;
    /**
     * Where Fathom's own time went, in milliseconds per frame, by section.
     *
     * <p>The number this milestone exists to produce. A total tells you Fathom
     * costs something; this tells you which part, which is the difference
     * between a report and an action.
     */
    private final Map<String, Double> sections;
    /** The most Fathom cost any single frame, in milliseconds. */
    private final double worstFathomFrameMillis;

    Report(
            String label,
            String configuration,
            String scenario,
            net.fathom.optimizations.core.bench.Environment environment,
            Map<String, Double> sections,
            double worstFathomFrameMillis,
            long startedWallMillis,
            long durationMillis,
            FrameStatistics frames,
            boolean frameLogWrapped,
            Map<Counters.Counter, Long> counters,
            List<MemorySampler.Sample> memorySamples,
            boolean allocationMeasured,
            String allocationMethod,
            Map<String, GcLog.Delta> gc) {
        this.label = label;
        this.configuration = configuration == null ? "" : configuration;
        this.scenario = scenario == null ? "" : scenario;
        this.environment = environment == null
                ? new net.fathom.optimizations.core.bench.Environment()
                : environment;
        this.sections = sections == null
                ? Collections.<String, Double>emptyMap()
                : Collections.unmodifiableMap(sections);
        this.worstFathomFrameMillis = worstFathomFrameMillis;
        this.startedWallMillis = startedWallMillis;
        this.durationMillis = durationMillis;
        this.frames = frames;
        this.frameLogWrapped = frameLogWrapped;
        this.counters = counters;
        this.memorySamples = Collections.unmodifiableList(memorySamples);
        this.allocationMeasured = allocationMeasured;
        this.allocationMethod = allocationMethod;
        this.gc = gc;
    }

    public String label() {
        return label;
    }

    public String configuration() {
        return configuration;
    }

    public String scenario() {
        return scenario;
    }

    public net.fathom.optimizations.core.bench.Environment environment() {
        return environment;
    }

    /** Fathom's own cost per frame, in milliseconds, by section. */
    public Map<String, Double> sections() {
        return sections;
    }

    /** Everything Fathom spent per frame, in milliseconds. */
    public double fathomMillisPerFrame() {
        double total = 0d;
        for (Double value : sections.values()) {
            total += value.doubleValue();
        }
        return total;
    }

    /**
     * The worst single frame Fathom was responsible for, in milliseconds.
     *
     * <p>A mean cannot answer "does Fathom ever cause a stutter" and every other
     * number here is a mean. This one can.
     */
    public double worstFathomFrameMillis() {
        return worstFathomFrameMillis;
    }

    public long startedWallMillis() {
        return startedWallMillis;
    }

    public long durationMillis() {
        return durationMillis;
    }

    public FrameStatistics frames() {
        return frames;
    }

    /** Whether older frames were overwritten; if true the statistics cover only the tail. */
    public boolean frameLogWrapped() {
        return frameLogWrapped;
    }

    public Map<Counters.Counter, Long> counters() {
        return counters;
    }

    public List<MemorySampler.Sample> memorySamples() {
        return memorySamples;
    }

    public boolean allocationMeasured() {
        return allocationMeasured;
    }

    public String allocationMethod() {
        return allocationMethod;
    }

    public Map<String, GcLog.Delta> gc() {
        return gc;
    }

    public long peakHeapUsedBytes() {
        long peak = 0L;
        for (MemorySampler.Sample sample : memorySamples) {
            peak = Math.max(peak, sample.heapUsedBytes());
        }
        return peak;
    }

    public long meanHeapUsedBytes() {
        if (memorySamples.isEmpty()) {
            return 0L;
        }
        long total = 0L;
        for (MemorySampler.Sample sample : memorySamples) {
            total += sample.heapUsedBytes();
        }
        return total / memorySamples.size();
    }

    /** Bytes per second across the run, or {@code -1} when it could not be determined. */
    public double allocationBytesPerSecond() {
        if (memorySamples.size() < 2) {
            return -1.0;
        }
        return MemorySampler.allocationBytesPerSecond(
                memorySamples.get(0), memorySamples.get(memorySamples.size() - 1));
    }

    /** Mean nanoseconds per chunk rebuild, or 0 when none happened. */
    public double meanChunkRebuildMillis() {
        long rebuilds = counters.get(Counters.Counter.CHUNK_REBUILDS);
        long nanos = counters.get(Counters.Counter.CHUNK_REBUILD_NANOS);
        if (rebuilds <= 0L) {
            return 0.0;
        }
        return (nanos / (double) rebuilds) / 1_000_000.0;
    }

    /**
     * Glyphs per text run.
     *
     * <p>The number font batching is supposed to change: before batching, draw calls
     * track glyphs; after, they should track runs. Recorded so the effect is visible in
     * a counter rather than only in a frame time.
     */
    public double glyphsPerTextRun() {
        long runs = counters.get(Counters.Counter.TEXT_RUNS);
        return runs <= 0L ? 0.0 : counters.get(Counters.Counter.GLYPHS) / (double) runs;
    }

    /**
     * Everything about this run that a reader must know before trusting a number.
     *
     * <p>Empty when there is nothing to qualify, which is the point: an empty list is
     * itself a claim, and a populated one is a warning that travels with the data.
     */
    public List<String> caveats() {
        List<String> out = new ArrayList<String>();

        if (frames.isEmpty()) {
            out.add("No frames were measured. Every frame figure in this report is zero"
                    + " because nothing was recorded, not because the game was infinitely"
                    + " fast.");
            return Collections.unmodifiableList(out);
        }
        if (frameLogWrapped) {
            out.add("The frame log wrapped: these statistics describe the last "
                    + frames.sampleCount() + " frames, not the whole run.");
        }
        if (!frames.hasEnoughSamplesFor(0.001)) {
            out.add("0.1% low is computed over fewer than 10 frames at this sample count ("
                    + frames.sampleCount() + "), so it is effectively a single-frame"
                    + " percentile and should not be compared between runs.");
        }
        if (!frames.hasEnoughSamplesFor(0.01)) {
            out.add("1% low is computed over fewer than 10 frames at this sample count ("
                    + frames.sampleCount() + ").");
        }
        if (!allocationMeasured) {
            out.add("Allocation was estimated, not measured: " + allocationMethod
                    + ". Treat the figure as a lower bound.");
        }
        if (memorySamples.size() < 2) {
            out.add("Fewer than two memory samples were taken, so allocation rate could not"
                    + " be computed at all.");
        }
        out.add("Garbage collection: " + GcLog.limitations());
        return Collections.unmodifiableList(out);
    }

    /**
     * A JSON object.
     *
     * <p>Hand-written rather than via a library: this module has no dependencies outside
     * the JDK, and adding one so a benchmark could serialise itself would be a poor
     * trade. The escaping below covers what actually appears in these fields — labels,
     * counter names, and our own prose.
     */
    public String toJson() {
        StringBuilder json = new StringBuilder(2048);
        json.append("{\n");
        json.append("  \"label\": ").append(quote(label)).append(",\n");
        json.append("  \"configuration\": ").append(quote(configuration)).append(",\n");
        json.append("  \"scenario\": ").append(quote(scenario)).append(",\n");
        json.append("  \"startedWallMillis\": ").append(startedWallMillis).append(",\n");
        json.append("  \"durationMillis\": ").append(durationMillis).append(",\n");

        // The settings this run happened under, so a comparison can refuse
        // itself. Two runs at different render distances are two measurements
        // of render distance.
        json.append("  \"environment\": ").append(environment.toJson("  ")).append(",\n");

        // Where Fathom's own time went. The point of the milestone: a total says
        // Fathom costs something, and this says which part.
        json.append("  \"fathom\": {\n");
        appendNumber(json, "    ", "millisPerFrame", fathomMillisPerFrame(), true);
        appendNumber(json, "    ", "worstFrameMillis", worstFathomFrameMillis, true);
        json.append("    \"sections\": {\n");
        int sectionsWritten = 0;
        for (Map.Entry<String, Double> section : sections.entrySet()) {
            if (sectionsWritten > 0) {
                json.append(",\n");
            }
            json.append("      ").append(quote(section.getKey())).append(": ")
                    .append(number(section.getValue().doubleValue()));
            sectionsWritten++;
        }
        json.append("\n    }\n");
        json.append("  },\n");

        json.append("  \"frames\": {\n");
        json.append("    \"sampleCount\": ").append(frames.sampleCount()).append(",\n");
        json.append("    \"wrapped\": ").append(frameLogWrapped).append(",\n");
        appendNumber(json, "    ", "averageFps", frames.averageFps(), true);
        appendNumber(json, "    ", "meanFrameTimeMillis", frames.meanFrameTimeMillis(), true);
        appendNumber(json, "    ", "medianFrameTimeMillis", frames.medianFrameTimeMillis(), true);
        appendNumber(json, "    ", "p95FrameTimeMillis", frames.p95FrameTimeMillis(), true);
        appendNumber(json, "    ", "p99FrameTimeMillis", frames.p99FrameTimeMillis(), true);
        appendNumber(json, "    ", "onePercentLowFps", frames.onePercentLowFps(), true);
        appendNumber(json, "    ", "pointOnePercentLowFps", frames.pointOnePercentLowFps(), true);
        appendNumber(json, "    ", "minFrameTimeMillis", frames.minFrameTimeMillis(), true);
        appendNumber(json, "    ", "maxFrameTimeMillis", frames.maxFrameTimeMillis(), true);
        appendNumber(
                json,
                "    ",
                "frameTimeCoefficientOfVariation",
                frames.frameTimeCoefficientOfVariation(),
                false);
        json.append("  },\n");

        json.append("  \"memory\": {\n");
        json.append("    \"samples\": ").append(memorySamples.size()).append(",\n");
        json.append("    \"peakHeapUsedBytes\": ").append(peakHeapUsedBytes()).append(",\n");
        json.append("    \"meanHeapUsedBytes\": ").append(meanHeapUsedBytes()).append(",\n");
        json.append("    \"allocationMeasured\": ").append(allocationMeasured).append(",\n");
        json.append("    \"allocationMethod\": ").append(quote(allocationMethod)).append(",\n");
        appendNumber(json, "    ", "allocationBytesPerSecond", allocationBytesPerSecond(), false);
        json.append("  },\n");

        json.append("  \"gc\": {\n");
        json.append("    \"collectors\": [");
        boolean firstCollector = true;
        for (GcLog.Delta delta : gc.values()) {
            if (!firstCollector) {
                json.append(",");
            }
            firstCollector = false;
            json.append("\n      {\"name\": ").append(quote(delta.collectorName()));
            json.append(", \"collections\": ").append(delta.collections());
            json.append(", \"totalPauseMillis\": ").append(delta.totalPauseMillis());
            json.append(", \"meanPauseMillis\": ").append(number(delta.meanPauseMillis()));
            json.append("}");
        }
        json.append(gc.isEmpty() ? "]," : "\n    ],\n");
        json.append("    \"limitations\": ").append(quote(GcLog.limitations())).append("\n");
        json.append("  },\n");

        json.append("  \"counters\": {\n");
        boolean firstCounter = true;
        for (Map.Entry<Counters.Counter, Long> entry : counters.entrySet()) {
            if (!firstCounter) {
                json.append(",\n");
            }
            firstCounter = false;
            json.append("    ").append(quote(entry.getKey().name())).append(": ")
                    .append(entry.getValue());
        }
        json.append("\n  },\n");

        json.append("  \"caveats\": [");
        List<String> caveats = caveats();
        for (int i = 0; i < caveats.size(); i++) {
            json.append(i == 0 ? "\n    " : ",\n    ").append(quote(caveats.get(i)));
        }
        json.append(caveats.isEmpty() ? "]\n" : "\n  ]\n");
        json.append("}\n");
        return json.toString();
    }

    private static void appendNumber(
            StringBuilder json, String indent, String name, double value, boolean comma) {
        json.append(indent)
                .append(quote(name))
                .append(": ")
                .append(number(value))
                .append(comma ? ",\n" : "\n");
    }

    /**
     * A JSON number.
     *
     * <p>Non-finite values become {@code null} rather than the literal {@code NaN}, which
     * is not valid JSON and would make a report unparseable by whatever reads it later.
     */
    private static String number(double value) {
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return "null";
        }
        return String.format(java.util.Locale.ROOT, "%.4f", value);
    }

    private static String quote(String raw) {
        StringBuilder out = new StringBuilder(raw.length() + 16);
        out.append('"');
        for (int i = 0; i < raw.length(); i++) {
            char c = raw.charAt(i);
            switch (c) {
                case '"':
                    out.append("\\\"");
                    break;
                case '\\':
                    out.append("\\\\");
                    break;
                case '\n':
                    out.append("\\n");
                    break;
                case '\r':
                    out.append("\\r");
                    break;
                case '\t':
                    out.append("\\t");
                    break;
                default:
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
            }
        }
        out.append('"');
        return out.toString();
    }

    @Override
    public String toString() {
        return label + ": " + frames;
    }
}
