package net.fathom.optimizations.core.registry;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;

/**
 * The registry, written out for the launcher.
 *
 * <h2>Why a file and not a second list</h2>
 *
 * <p>The launcher used to keep its own hand-written copy of this catalogue,
 * with a test that only checked the ids matched. Everything else -- names,
 * status, which mods stand an entry down -- drifted freely, and did: the
 * launcher said the chunk lookup cache was on under OptiFine while the game
 * skipped it. Now {@link OptimizationRegistry} is the one source. This writes
 * it to {@code assets/optimizations/registry.json}, the launcher compiles that
 * file in, and a core test fails when the checked-in copy differs from what the
 * registry would write today ({@code gradle exportRegistry} regenerates it).
 *
 * <h2>Why hand-written JSON</h2>
 *
 * <p>The core package has no dependencies, which is what lets it be tested
 * with a bare JDK. A few dozen lines of escaping keep that true. Keys are in a
 * fixed order and the output ends with a newline, so a stale file shows up as
 * a readable diff rather than a reordering.
 */
public final class RegistryJson {

    /** Bumped only for a change an older launcher cannot read. */
    public static final int SCHEMA = 1;

    private RegistryJson() {}

    public static String write(OptimizationRegistry registry) {
        StringBuilder out = new StringBuilder(32_768);
        out.append("{\n");
        out.append("  \"schema\": ").append(SCHEMA).append(",\n");
        out.append("  \"generatedBy\": \"net.fathom.optimizations.core.registry.RegistryJson\",\n");
        out.append("  \"optimizations\": [");
        boolean first = true;
        for (Optimization entry : registry.all()) {
            out.append(first ? "\n" : ",\n");
            first = false;
            entry(out, entry);
        }
        out.append("\n  ]\n}\n");
        return out.toString();
    }

    private static void entry(StringBuilder out, Optimization entry) {
        out.append("    {\n");
        field(out, "id", entry.id().toString(), true);
        field(out, "name", entry.name(), true);
        field(out, "description", entry.description(), true);
        field(out, "group", entry.group().name(), true);
        field(out, "groupLabel", entry.group().label(), true);
        field(out, "category", entry.category().name(), true);
        field(out, "surface", entry.surface().name(), true);
        field(out, "control", entry.control().name(), true);
        field(out, "status", entry.status().name(), true);
        field(out, "expectedBenefit", entry.expectedBenefit(), true);
        out.append("      \"defaultOn\": ").append(entry.defaultEnabled()).append(",\n");
        out.append("      \"minecraft\": [");
        boolean first = true;
        for (String version : entry.compatibility().minecraftVersions()) {
            out.append(first ? "" : ", ");
            first = false;
            string(out, version);
        }
        out.append("],\n");
        Evidence evidence = entry.evidence();
        if (evidence == null) {
            out.append("      \"evidence\": null,\n");
        } else {
            out.append("      \"evidence\": {\n");
            out.append("        \"level\": ");
            string(out, evidence.level().name());
            out.append(",\n        \"runId\": ");
            string(out, evidence.runId());
            out.append(",\n        \"summary\": ");
            string(out, evidence.summary());
            out.append("\n      },\n");
        }
        out.append("      \"conflicts\": [");
        first = true;
        for (Conflict conflict : entry.conflicts()) {
            out.append(first ? "\n" : ",\n");
            first = false;
            out.append("        { \"with\": ");
            string(out, conflict.with());
            out.append(", \"resolution\": ");
            string(out, conflict.resolution().name());
            out.append(", \"detail\": ");
            string(out, conflict.detail());
            out.append(" }");
        }
        out.append(first ? "]\n" : "\n      ]\n");
        out.append("    }");
    }

    private static void field(StringBuilder out, String key, String value, boolean comma) {
        out.append("      \"").append(key).append("\": ");
        string(out, value);
        out.append(comma ? ",\n" : "\n");
    }

    private static void string(StringBuilder out, String value) {
        out.append('"');
        for (int index = 0; index < value.length(); index++) {
            char c = value.charAt(index);
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
    }

    /** {@code gradle exportRegistry}: write the file the launcher compiles in. */
    public static void main(String[] args) throws IOException {
        if (args.length != 1) {
            throw new IllegalArgumentException("usage: RegistryJson <output file>");
        }
        File file = new File(args[0]);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("cannot create " + parent);
        }
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(write(OptimizationRegistry.standard()));
        } finally {
            writer.close();
        }
        System.out.println("wrote " + file.getAbsolutePath());
    }
}
