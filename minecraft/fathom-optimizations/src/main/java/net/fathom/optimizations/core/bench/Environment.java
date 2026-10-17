package net.fathom.optimizations.core.bench;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * The settings two runs must share before their numbers may be compared.
 *
 * <h2>The rule</h2>
 *
 * <p><b>Never compare two builds with different graphics settings and call the
 * difference an optimization.</b> Render distance alone can move frame time by a
 * factor of three; a comparison that changed it has measured render distance.
 *
 * <p>This is not advice. A run records every field below, and
 * {@link #differencesFrom} lists what changed between two runs — so a comparison
 * that should not have been made says so, in the summary, rather than being
 * published.
 *
 * <h2>Why it is captured rather than configured</h2>
 *
 * <p>Because the thing that goes wrong is not choosing the wrong settings, it is
 * <em>not noticing</em> that something changed them: a resource pack that
 * survived a profile switch, VSync that came back on after a driver update, a
 * window that was resized between runs. Asking the client what it is actually
 * doing at the moment of the run catches all three; asking a human to confirm
 * catches none of them.
 *
 * <h2>Why the JVM fields are here</h2>
 *
 * <p>A different heap is a different garbage collector schedule, and a different
 * Java is a different JIT. Both change frame time in ways that look exactly like
 * a renderer change.
 */
public final class Environment {

    private final Map<String, String> values = new LinkedHashMap<String, String>();

    /** Every field, in the order a report shows them. */
    public static final String[] FIELDS = {
        "resolution",
        "display",
        "renderDistance",
        "graphics",
        "smoothLighting",
        "particles",
        "vbo",
        "vsync",
        "fpsLimit",
        "guiScale",
        "resourcePacks",
        "mipmaps",
        "entityShadows",
        "fov",
        "java",
        "javaVendor",
        "heapMaxMb",
        "jvmArguments",
        "os",
        "cpuCores",
        "minecraft",
        "loader",
        "fathomVersion",
    };

    /**
     * Fields that must match exactly for a comparison to mean anything.
     *
     * <p>Not all of them. The Fathom version is expected to differ between a
     * baseline and a candidate — that is the point of the comparison — and the
     * loader differs between configurations A and B by design. What must not
     * differ is anything that changes how much work a frame is.
     */
    public static final String[] MUST_MATCH = {
        "resolution",
        "display",
        "renderDistance",
        "graphics",
        "smoothLighting",
        "particles",
        "vbo",
        "vsync",
        "fpsLimit",
        "guiScale",
        "resourcePacks",
        "mipmaps",
        "entityShadows",
        "fov",
        "java",
        "heapMaxMb",
        "jvmArguments",
        "os",
        "cpuCores",
        "minecraft",
    };

    public Environment set(String field, String value) {
        values.put(field, value == null ? "" : value);
        return this;
    }

    public Environment set(String field, int value) {
        return set(field, String.valueOf(value));
    }

    public Environment set(String field, boolean value) {
        return set(field, value ? "on" : "off");
    }

    public String get(String field) {
        String value = values.get(field);
        return value == null ? "" : value;
    }

    public boolean has(String field) {
        return values.containsKey(field) && !values.get(field).isEmpty();
    }

    /** Fields that were never recorded. A run missing any of them is suspect. */
    public List<String> missing() {
        List<String> out = new ArrayList<String>();
        for (String field : FIELDS) {
            if (!has(field)) {
                out.add(field);
            }
        }
        return out;
    }

    /**
     * What differs between this run and another, among the fields that matter.
     *
     * <p>An empty list is permission to compare. Anything else is a list of
     * reasons the two numbers are not about the same thing, and belongs at the
     * top of the summary rather than in a footnote.
     */
    public List<String> differencesFrom(Environment other) {
        List<String> out = new ArrayList<String>();
        if (other == null) {
            return out;
        }
        for (String field : MUST_MATCH) {
            String mine = get(field);
            String theirs = other.get(field);
            if (!mine.equals(theirs)) {
                out.add(field + ": " + describe(theirs) + " → " + describe(mine));
            }
        }
        return out;
    }

    private static String describe(String value) {
        return value.isEmpty() ? "(not recorded)" : value;
    }

    /** Every recorded field, for the report. */
    public Map<String, String> all() {
        return new LinkedHashMap<String, String>(values);
    }

    /**
     * The JSON fragment a report embeds.
     *
     * <p>Written by hand for the same reason the rest of this module's JSON is:
     * {@code core} builds with a bare JDK, and adding a JSON library to serialise
     * twenty strings would be a dependency carried for punctuation.
     */
    public String toJson(String indent) {
        StringBuilder out = new StringBuilder(512);
        out.append("{\n");
        int written = 0;
        for (String field : FIELDS) {
            if (!values.containsKey(field)) {
                continue;
            }
            if (written > 0) {
                out.append(",\n");
            }
            out.append(indent).append("  \"").append(field).append("\": \"")
                    .append(escape(values.get(field))).append('"');
            written++;
        }
        out.append('\n').append(indent).append('}');
        return out.toString();
    }

    private static String escape(String value) {
        StringBuilder out = new StringBuilder(value.length());
        for (int index = 0; index < value.length(); index++) {
            char character = value.charAt(index);
            if (character == '"' || character == '\\') {
                out.append('\\').append(character);
            } else if (character < ' ') {
                out.append(' ');
            } else {
                out.append(character);
            }
        }
        return out.toString();
    }
}
