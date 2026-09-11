package net.fathom.optimizations.core.config;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.io.Reader;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Properties;

import net.fathom.optimizations.core.registry.OptimizationId;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.SwitchBoard;

/**
 * The launcher's half of the settings, read from disk.
 *
 * <h2>What this is</h2>
 *
 * <p>Fathom's launcher owns the settings UI: it knows the profile, it has the screen
 * space, and it can show what an optimization costs before you enable it. The game side
 * needs the answers, not a second settings screen. This class is the seam — the launcher
 * writes one file before launching, and this reads it.
 *
 * <h2>Why a properties file</h2>
 *
 * <p>Because it can be parsed with nothing. {@code java.util.Properties} is in the JDK,
 * so the {@code core} package keeps its defining property: no dependencies, compiles and
 * tests with a bare JDK 8. JSON would mean either a library — which every user would then
 * have to have on the classpath for the mod to load — or a hand-rolled parser, which is a
 * bug surface in exchange for syntax nobody reads.
 *
 * <p>The format is deliberately dull:
 *
 * <pre>
 * schema = 1
 * preset = safe
 * opt.render.font.batching = true
 * opt.render.entity.occlusion-culling = false
 * </pre>
 *
 * <h2>What it refuses to do</h2>
 *
 * <p>It never enables something the registry does not have, and it never fails the game
 * because a file was malformed. An unreadable, absent or nonsense config produces the
 * registry defaults — which is everything off — and a list of complaints the caller can
 * log. A performance mod that prevents Minecraft from starting because it could not parse
 * its own settings has done considerably more harm than the frames it was going to save.
 */
public final class LauncherConfig {

    /** The file the launcher writes, relative to the game directory. */
    public static final String RELATIVE_PATH = "config/fathom-performance.properties";

    /**
     * The format version. Bumped only for a change old readers cannot survive; adding a
     * key is not such a change, because an unknown key is ignored by design.
     */
    public static final int SCHEMA = 1;

    private static final String PREFIX = "opt.";

    /**
     * Optimizations the launcher stood down for another mod.
     *
     * <p>A separate prefix from {@code opt.} because it is a separate statement.
     * {@code opt.x = false} is "the user chose off"; {@code conflict.x = ...} is
     * "the user's choice is being overridden, and here is the sentence to show
     * them". Collapsing the two would lose exactly the sentence, and a switch
     * that reads as on and does nothing is the defect the module milestone was
     * about.
     */
    private static final String CONFLICT_PREFIX = "conflict.";

    private final Properties values;
    private final List<String> complaints;

    private LauncherConfig(Properties values, List<String> complaints) {
        this.values = values;
        this.complaints = Collections.unmodifiableList(complaints);
    }

    /** An empty configuration. What you get when there is no launcher. */
    public static LauncherConfig empty() {
        return new LauncherConfig(new Properties(), new ArrayList<String>());
    }

    /**
     * Read the config from a game directory.
     *
     * <p>Never throws. A missing file is the normal case for someone running the mod
     * without Fathom, and is not a complaint.
     */
    public static LauncherConfig fromGameDirectory(File gameDirectory) {
        File file = new File(gameDirectory, RELATIVE_PATH);
        if (!file.isFile()) {
            return empty();
        }
        List<String> complaints = new ArrayList<String>();
        Properties values = new Properties();
        InputStream stream = null;
        try {
            stream = new FileInputStream(file);
            // Explicit UTF-8. `Properties.load(InputStream)` is ISO-8859-1 by
            // specification, which would mangle any non-ASCII the launcher wrote.
            Reader reader = new InputStreamReader(stream, Charset.forName("UTF-8"));
            values.load(reader);
        } catch (IOException error) {
            complaints.add("could not read " + file + ": " + error.getMessage());
            values = new Properties();
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (IOException ignored) {
                    // Nothing useful to do, and not worth failing a launch over.
                }
            }
        }
        return new LauncherConfig(values, complaints);
    }

    /** Parse an already-loaded set of values. For tests. */
    public static LauncherConfig of(Properties values) {
        return new LauncherConfig(values, new ArrayList<String>());
    }

    /** Whether the launcher wrote anything at all. */
    public boolean isPresent() {
        return !values.isEmpty();
    }

    /**
     * The schema the file declares, or 0 if it does not.
     *
     * <p>A file from the future is still read: unknown keys are ignored and known ones
     * mean what they have always meant, which is the whole reason the format is flat.
     */
    public int schema() {
        String raw = values.getProperty("schema");
        if (raw == null) {
            return 0;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException error) {
            return 0;
        }
    }

    /** The preset the launcher recorded, lower-case, or empty. */
    public String preset() {
        String raw = values.getProperty("preset");
        return raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT);
    }

    /**
     * Anything wrong with the file, in the order it was found.
     *
     * <p>Returned rather than logged so the caller decides where it goes; `core` has no
     * logger and acquiring one would be a dependency.
     */
    public List<String> complaints() {
        return complaints;
    }

    /**
     * Build a switch board from the registry defaults plus whatever the launcher said.
     *
     * <p>Switches are applied as {@link SwitchBoard.Reason#USER}: the settings came from
     * a person clicking things in the launcher, and that is exactly the distinction
     * {@code Reason} exists to preserve — a conflict safeguard may later turn one off,
     * and it needs to know it is overriding a human.
     */
    public SwitchBoard apply(OptimizationRegistry registry) {
        SwitchBoard board = SwitchBoard.defaults(registry);
        List<String> found = new ArrayList<String>(complaints);

        for (String key : values.stringPropertyNames()) {
            if (!key.startsWith(PREFIX)) {
                continue;
            }
            String id = key.substring(PREFIX.length()).trim();
            if (id.isEmpty()) {
                found.add("empty optimization id in key: " + key);
                continue;
            }
            if (registry.find(id) == null) {
                // Expected whenever the launcher is newer than the mod. Recorded, not
                // treated as an error: dropping a switch we do not have is correct.
                found.add("unknown optimization, ignored: " + id);
                continue;
            }
            Boolean enabled = parseBoolean(values.getProperty(key));
            if (enabled == null) {
                found.add("not a boolean, ignored: " + key + " = " + values.getProperty(key));
                continue;
            }
            board.setByUser(OptimizationId.of(id), enabled.booleanValue());
        }

        // After the user's own switches, and deliberately: somebody who
        // installed OptiFine made a later and more specific decision than the
        // one recorded in a settings file, and `disableForConflict` records
        // that it is overriding a human rather than pretending they chose it.
        standdowns.clear();
        for (String key : values.stringPropertyNames()) {
            if (!key.startsWith(CONFLICT_PREFIX)) {
                continue;
            }
            String id = key.substring(CONFLICT_PREFIX.length()).trim();
            if (id.isEmpty() || registry.find(id) == null) {
                found.add("stand-down for something not in this build, ignored: " + id);
                continue;
            }
            String detail = String.valueOf(values.getProperty(key)).trim();
            board.disableForConflict(OptimizationId.of(id), detail);
            standdowns.add(new String[] {id, detail});
        }

        this.complaintsFrom(found);
        return board;
    }

    /**
     * What was stood down and why, in the order the file listed it.
     *
     * <p>Read by Diagnostics. Kept as the id and the reason rather than as a
     * finished sentence: the registry knows the optimization's display name and
     * this class deliberately does not carry a second copy of it.
     */
    public List<String[]> standdowns() {
        return Collections.unmodifiableList(standdowns);
    }

    /**
     * Strict boolean parsing.
     *
     * <p>{@code Boolean.parseBoolean} maps every non-"true" string to false, which would
     * silently read a typo as "off" and leave the user wondering why their setting did
     * nothing. Anything unrecognised is rejected instead.
     *
     * <p>Public so it can be tested directly. The distinction between "false" and "not a
     * boolean" is the whole point of the method, and it is worth asserting rather than
     * inferring from a switch that happened not to turn on.
     *
     * @return {@code null} when the value is not a boolean at all.
     */
    public static Boolean parseBoolean(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(java.util.Locale.ROOT);
        if (value.equals("true")) {
            return Boolean.TRUE;
        }
        if (value.equals("false")) {
            return Boolean.FALSE;
        }
        return null;
    }

    /** Mutable accumulation during {@link #apply}, kept out of the public surface. */
    private List<String> accumulated = new ArrayList<String>();

    private final List<String[]> standdowns = new ArrayList<String[]>();

    private void complaintsFrom(List<String> found) {
        this.accumulated = found;
    }

    /** Complaints from the last {@link #apply}, including parse-time ones. */
    public List<String> lastComplaints() {
        return Collections.unmodifiableList(accumulated);
    }
}
