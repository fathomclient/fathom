package net.fathom.optimizations.core.module;

/**
 * What the launcher said this launch is.
 *
 * <h2>Why this is not the bridge handshake</h2>
 *
 * <p>The handshake carries a token, because its job is to let the game drive the
 * launcher. This carries no secret at all — a profile name, a version, a loader,
 * a preset — and that difference is worth a second file:
 *
 * <ul>
 *   <li>the bridge is a listener that can fail to bind, and a profile name
 *       should not disappear from the main menu because a port was busy;
 *   <li>a file with no secret in it can be shown in Diagnostics and pasted into
 *       a bug report, which is exactly what somebody reporting a launch problem
 *       needs to send;
 *   <li>this is written by the launcher's own launch path, so a game started
 *       from the command-line tool has it too.
 * </ul>
 *
 * <h2>Why the parser is here</h2>
 *
 * <p>Because it is the part that can be wrong. The document is written by
 * {@code crates/launcher-core/src/session.rs} in one fixed shape, so the reader
 * only has to handle that shape — and a reader that only handles one shape is a
 * reader that can be tested with a bare JDK, in {@code core}, without a game.
 *
 * <p>Nothing here throws. A missing file, a truncated file, a file from a newer
 * launcher: all of them produce {@link #absent()}, and the interface says
 * "External Session" rather than showing an error about a file the user has
 * never heard of.
 */
public final class LaunchSession {

    /** Where it is, relative to the game directory. Must match the Rust side. */
    public static final String RELATIVE_PATH = "fathom/session.json";

    /** Format version. Must match {@code session::SCHEMA}. */
    public static final int SCHEMA = 1;

    private static final LaunchSession ABSENT = new LaunchSession(false, "", "", "", "", "", "", "", "", "");

    private final boolean present;
    private final String profileId;
    private final String profileName;
    private final String minecraft;
    private final String loader;
    private final String loaderVersion;
    private final String preset;
    private final String component;
    private final String launcher;
    /** The launcher's data root, so the game opens the same profile collection. */
    private final String root;

    private LaunchSession(
            boolean present,
            String profileId,
            String profileName,
            String minecraft,
            String loader,
            String loaderVersion,
            String preset,
            String component,
            String launcher,
            String root) {
        this.present = present;
        this.profileId = profileId;
        this.profileName = profileName;
        this.minecraft = minecraft;
        this.loader = loader;
        this.loaderVersion = loaderVersion;
        this.preset = preset;
        this.component = component;
        this.launcher = launcher;
        this.root = root;
    }

    /** No launcher started this game, or it said nothing this reader understands. */
    public static LaunchSession absent() {
        return ABSENT;
    }

    /**
     * Read a descriptor.
     *
     * @param document the file's contents, or null when there is no file
     */
    public static LaunchSession parse(String document) {
        if (document == null || document.isEmpty()) {
            return ABSENT;
        }
        String schema = value(document, "schema");
        // An unreadable or newer schema is treated as no descriptor at all.
        // Guessing at a document written by a launcher that knows something we
        // do not is how a main menu ends up displaying half a profile.
        if (!String.valueOf(SCHEMA).equals(schema)) {
            return ABSENT;
        }
        String profileName = value(document, "profileName");
        if (profileName.isEmpty()) {
            // The one field with no sensible default. Without it there is
            // nothing to show, and a descriptor with nothing to show is the
            // same as none.
            return ABSENT;
        }
        return new LaunchSession(
                true,
                value(document, "profile"),
                profileName,
                value(document, "minecraft"),
                value(document, "loader"),
                value(document, "loaderVersion"),
                value(document, "preset"),
                value(document, "component"),
                value(document, "launcher"),
                value(document, "root"));
    }

    /**
     * One value out of the document.
     *
     * <p>Not a JSON parser. The document is written by one function in one
     * shape, every value is either a bare number or a quoted string, and the
     * keys are unique — so finding {@code "key"} and reading what follows the
     * colon is both sufficient and much less code than something general.
     *
     * <p>The one thing it does properly is unescaping, because a profile name
     * is user-chosen and can contain a quote.
     */
    private static String value(String document, String key) {
        String needle = '"' + key + '"';
        int at = document.indexOf(needle);
        if (at < 0) {
            return "";
        }
        int colon = document.indexOf(':', at + needle.length());
        if (colon < 0) {
            return "";
        }
        int cursor = colon + 1;
        while (cursor < document.length() && Character.isWhitespace(document.charAt(cursor))) {
            cursor++;
        }
        if (cursor >= document.length()) {
            return "";
        }
        if (document.charAt(cursor) != '"') {
            // A bare number: the schema, and nothing else today.
            int end = cursor;
            while (end < document.length() && "-0123456789.".indexOf(document.charAt(end)) >= 0) {
                end++;
            }
            return document.substring(cursor, end);
        }
        StringBuilder out = new StringBuilder();
        for (int index = cursor + 1; index < document.length(); index++) {
            char character = document.charAt(index);
            if (character == '\\' && index + 1 < document.length()) {
                char escaped = document.charAt(++index);
                switch (escaped) {
                    case 'n':
                        out.append(' ');
                        break;
                    case 'r':
                    case 't':
                        out.append(' ');
                        break;
                    default:
                        out.append(escaped);
                        break;
                }
                continue;
            }
            if (character == '"') {
                return out.toString();
            }
            out.append(character);
        }
        // Unterminated: the file was truncated mid-write, which is a launcher
        // that died between the open and the flush. Nothing rather than half.
        return "";
    }

    /**
     * The launcher's data root, or empty.
     *
     * <p>Where the shared Fathom Profile collection is. Absent from descriptors
     * written by launchers older than the profile split, in which case the game
     * falls back to the platform default; see {@code ProfileLocation}.
     */
    public String root() {
        return root;
    }

    /** Whether the launcher introduced this session. */
    public boolean present() {
        return present;
    }

    public String profileId() {
        return profileId;
    }

    /** The profile's display name, e.g. {@code Bedwars}. */
    public String profileName() {
        return profileName;
    }

    public String minecraft() {
        return minecraft;
    }

    /** The loader's display name, e.g. {@code Legacy Fabric}. */
    public String loader() {
        return loader;
    }

    public String loaderVersion() {
        return loaderVersion;
    }

    /** The performance preset's wire name, e.g. {@code competitive}. */
    public String preset() {
        return preset;
    }

    /** The version of Fathom's own module the launcher installed here. */
    public String component() {
        return component;
    }

    public String launcherVersion() {
        return launcher;
    }

    /**
     * The second line of the main menu's branding: {@code 1.8.9 · Legacy Fabric}.
     *
     * <p>Built here rather than in the screen so the same string appears in the
     * hub header, in Diagnostics and on the menu without three near-copies of
     * the same join.
     *
     * <p>Falls back to what is known. A session with a version and no loader
     * says the version; one with neither says nothing, and the caller draws the
     * line only when there is something on it.
     */
    public String subtitle() {
        if (!present) {
            return "";
        }
        if (!minecraft.isEmpty() && !loader.isEmpty()) {
            return minecraft + " · " + loader;
        }
        return minecraft.isEmpty() ? loader : minecraft;
    }
}
