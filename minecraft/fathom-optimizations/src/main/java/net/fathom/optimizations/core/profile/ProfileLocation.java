package net.fathom.optimizations.core.profile;

import java.io.File;
import java.util.Locale;

/**
 * Where the running game finds the one Fathom Profile collection.
 *
 * <h2>In order</h2>
 *
 * <ol>
 *   <li>An explicit override, {@code -Dfathom.profilesDir}, for tests and
 *       visual-QA runs that must not touch a real collection.
 *   <li>The data root the launcher wrote into {@code fathom/session.json}. This
 *       is how a portable install works, where the root is not the default.
 *   <li>The platform default, when the launcher is installed — computed the way
 *       the {@code directories} crate computes it, because that is how the
 *       launcher computes it.
 *   <li>{@code <game directory>/fathom/profiles}, when there is no launcher at
 *       all: a copy of the mod in somebody's own instance. A supported case, not
 *       a failure. Those profiles are simply local to that game.
 * </ol>
 *
 * <h2>The platform rows are a contract</h2>
 *
 * <p>They were read out of {@code directories} 6.0.0's source, not remembered:
 * Windows appends {@code data}, macOS uses the bundle id, and Linux lowercases the
 * application name. If the launcher's crate changes those rules, the launcher and
 * the game stop sharing a collection, and {@code ProfileLocationTests} is where
 * that is supposed to be noticed.
 *
 * <p>Pure: every input is a parameter, so the rules are tested on every platform
 * from one.
 */
public final class ProfileLocation {

    /** Must match {@code crate::PRODUCT_NAME}. */
    static final String PRODUCT = "Fathom";

    /** Which rule chose the directory, for Diagnostics. */
    public enum Source {
        OVERRIDE("Override"),
        LAUNCHER("Launcher"),
        PLATFORM("Launcher data"),
        STANDALONE("This game only");

        private final String label;

        Source(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    /** A directory and why. */
    public static final class Resolved {
        public final File directory;
        public final Source source;

        Resolved(File directory, Source source) {
            this.directory = directory;
            this.source = source;
        }
    }

    /** Whether a directory exists. A parameter so the rules can be tested without one. */
    public interface Probe {
        boolean isDirectory(File directory);
    }

    private ProfileLocation() {}

    /** The live answer, from this JVM's environment. */
    public static Resolved resolve(String sessionRoot, File gameDirectory) {
        return resolve(
                System.getProperty("fathom.profilesDir"),
                sessionRoot,
                System.getProperty("os.name", ""),
                System.getenv("LOCALAPPDATA"),
                System.getProperty("user.home"),
                System.getenv("XDG_DATA_HOME"),
                gameDirectory,
                new Probe() {
                    @Override
                    public boolean isDirectory(File directory) {
                        return directory.isDirectory();
                    }
                });
    }

    public static Resolved resolve(
            String override,
            String sessionRoot,
            String osName,
            String localAppData,
            String userHome,
            String xdgDataHome,
            File gameDirectory,
            Probe probe) {
        if (present(override)) {
            return new Resolved(new File(override), Source.OVERRIDE);
        }
        if (present(sessionRoot)) {
            return new Resolved(new File(sessionRoot, ProfileStore.DIRECTORY), Source.LAUNCHER);
        }
        File root = platformRoot(osName, localAppData, userHome, xdgDataHome);
        // Only when the launcher has actually created its data root. Creating it
        // from the game would make a launcher-shaped directory for somebody who
        // has never installed the launcher.
        if (root != null && probe.isDirectory(root)) {
            return new Resolved(new File(root, ProfileStore.DIRECTORY), Source.PLATFORM);
        }
        return new Resolved(new File(new File(gameDirectory, "fathom"), ProfileStore.DIRECTORY), Source.STANDALONE);
    }

    /**
     * The launcher's data root on this platform, or null when the environment does
     * not say enough to know.
     */
    static File platformRoot(String osName, String localAppData, String userHome, String xdgDataHome) {
        String os = osName == null ? "" : osName.toLowerCase(Locale.ROOT);
        if (os.startsWith("windows")) {
            // known_folder_local_app_data \ organization \ application \ data,
            // with an empty organization.
            return present(localAppData) ? new File(new File(localAppData, PRODUCT), "data") : null;
        }
        if (os.contains("mac") || os.contains("darwin")) {
            // ~/Library/Application Support/<bundle id>, and the bundle id of
            // ("", "", "Fathom") is just "Fathom".
            return present(userHome) ? new File(new File(userHome, "Library/Application Support"), PRODUCT) : null;
        }
        // $XDG_DATA_HOME when it is set and absolute, else ~/.local/share, then
        // the application name trimmed and lowercased.
        String product = PRODUCT.toLowerCase(Locale.ROOT);
        // POSIX-absolute, decided by the string. File.isAbsolute answers for the
        // host, and on a Windows JVM checking these rules "/data/xdg" is not
        // absolute at all — which is exactly how this was first written wrong.
        if (present(xdgDataHome) && xdgDataHome.startsWith("/")) {
            return new File(xdgDataHome, product);
        }
        return present(userHome) ? new File(new File(userHome, ".local/share"), product) : null;
    }

    private static boolean present(String value) {
        return value != null && !value.trim().isEmpty();
    }
}
