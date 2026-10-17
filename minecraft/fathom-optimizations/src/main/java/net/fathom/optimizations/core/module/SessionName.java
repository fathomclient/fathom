package net.fathom.optimizations.core.module;

/**
 * What to call the session the player is in.
 *
 * <h2>The bug this replaces</h2>
 *
 * <p>The header used to read "Unknown profile" whenever the launcher had not
 * introduced itself, which was every development run and every copy of the mod
 * somebody dropped into their own instance. "Unknown" is what a program says
 * when it has failed to find something it expected; here there was nothing to
 * find, and saying so as a failure is both wrong and slightly alarming.
 *
 * <p>There are exactly three cases and the client can tell them apart, so it
 * names all three.
 *
 * <h2>Why the decision is in {@code core}</h2>
 *
 * <p>It is a three-way choice over two booleans and a string, which is the kind
 * of thing that gets quietly wrong in a screen and stays wrong. Here it has a
 * test.
 */
public final class SessionName {

    /** A game somebody started themselves, with Fathom's mod but not its launcher. */
    public static final String EXTERNAL = "Not from the launcher";

    /**
     * A game started by Gradle, from the module's own source.
     *
     * <p>Deliberately not "Development Profile", which is what this used to say.
     * A profile is a loadout now, and this names how the game was started; it is
     * shown in Diagnostics only.
     */
    public static final String DEVELOPMENT = "Development client";

    private SessionName() {}

    /**
     * The name to show.
     *
     * @param launcherProfile what the launcher said it started, or null
     * @param developmentEnvironment whether the loader says this is a dev run
     */
    public static String of(String launcherProfile, boolean developmentEnvironment) {
        if (launcherProfile != null && !launcherProfile.trim().isEmpty()) {
            return launcherProfile.trim();
        }
        return developmentEnvironment ? DEVELOPMENT : EXTERNAL;
    }

    /**
     * Whether this name came from the launcher.
     *
     * <p>Used to decide whether to offer things only the launcher can do —
     * choosing the next profile, for instance — rather than offering them and
     * failing.
     */
    public static boolean fromLauncher(String launcherProfile) {
        return launcherProfile != null && !launcherProfile.trim().isEmpty();
    }
}
