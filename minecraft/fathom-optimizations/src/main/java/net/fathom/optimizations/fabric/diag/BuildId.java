package net.fathom.optimizations.fabric.diag;

import java.io.InputStream;
import java.util.Properties;

/**
 * Which bytes this is.
 *
 * <h2>Why a version string is not enough</h2>
 *
 * <p>Three different jars in this checkout's history have all been named
 * {@code fathom-optimizations-0.3.0-hud.jar} and all reported version
 * {@code 0.3.0-hud}. They had different contents. When one of them is launched
 * by mistake -- because a build was staged but not copied, or copied but not
 * picked up -- the version string agrees with the expected one and the mistake
 * is invisible. Hours go into looking for a bug in code that is not running.
 *
 * <p>So the build stamps a commit and a build time into
 * {@code fathom-build.properties}, and the runtime prints them. A stale jar now
 * says so in its own first line of output.
 *
 * <h2>Why it never throws</h2>
 *
 * <p>This is read during client initialisation, before anything else. A missing
 * or malformed properties file means the identity is unknown, which is worth
 * printing; it is not worth failing a launch over.
 */
public final class BuildId {

    private static final String RESOURCE = "/fathom-build.properties";

    private static final String VERSION;
    private static final String COMMIT;
    private static final String BUILT_AT;

    static {
        String version = "unknown";
        String commit = "unknown";
        String builtAt = "unknown";
        InputStream stream = null;
        try {
            stream = BuildId.class.getResourceAsStream(RESOURCE);
            if (stream != null) {
                Properties properties = new Properties();
                properties.load(stream);
                version = properties.getProperty("version", version);
                commit = properties.getProperty("commit", commit);
                builtAt = properties.getProperty("builtAt", builtAt);
            }
        } catch (Throwable unreadable) {
            // Identity unknown. Said, not thrown.
            commit = "unreadable";
        } finally {
            if (stream != null) {
                try {
                    stream.close();
                } catch (Throwable ignored) {
                    // Nothing useful to do with a failed close of a resource stream.
                }
            }
        }
        VERSION = version;
        COMMIT = commit;
        BUILT_AT = builtAt;
    }

    private BuildId() {}

    public static String version() {
        return VERSION;
    }

    /** The commit this jar was built from, with {@code -dirty} for a modified tree. */
    public static String commit() {
        return COMMIT;
    }

    public static String builtAt() {
        return BUILT_AT;
    }

    /** One line: what a log should print and what a bug report should quote. */
    public static String describe() {
        return VERSION + " (" + COMMIT + ", built " + BUILT_AT + ")";
    }
}
