package net.fathom.optimizations.fabric.diag;

import java.io.File;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.List;


/**
 * What is actually running, printed once at startup.
 *
 * <h2>Why this exists</h2>
 *
 * <p>"OptiFine is installed" and "OptiFine is running" are different claims, and
 * only one of them can be settled by looking at a folder. A jar in a mods
 * directory proves a file exists. It does not prove the loader read it, that
 * OptiFabric patched it, that the patched classes reached the class loader, or
 * that the game directory the launcher configured is the one the process opened.
 *
 * <p>So this asks the running JVM instead. Versions come from mod containers,
 * paths from the containers' own roots, and the OptiFine and OptiFabric verdicts
 * from {@link Class#forName} against classes that only exist once the patch has
 * been applied. Nothing here infers a runtime fact from a filename.
 *
 * <h2>What is deliberately not printed</h2>
 *
 * <p>The access token, the session id and anything else the launcher passed as a
 * credential. {@link #redact} drops the value after any argument whose name
 * looks like a secret, so a log somebody pastes into an issue cannot sign anyone
 * in. The argument's <em>presence</em> is still shown, because "was a token
 * passed at all" is a real diagnostic question.
 */
public final class RuntimeTrace {

    /** Argument names whose following value never appears in the log. */
    private static final String[] SECRET_ARGUMENTS = {
        "--accessToken", "--session", "--uuid", "--xuid", "--clientId", "--password",
    };

    /**
     * Classes that exist only when OptiFine's own code has been loaded.
     *
     * <p>{@code Config} is OptiFine's 1.8.9 entry point and lives in the default
     * package; {@code net.optifine.shaders.Shaders} is the shader module. Either
     * one resolving means OptiFine's bytecode is on the class path the game is
     * actually using -- which is the question, and the one a file listing cannot
     * answer.
     */
    private static final String[] OPTIFINE_CLASSES = {
        "Config", "net.optifine.shaders.Shaders", "net.optifine.Log",
    };

    /** OptiFabric's own classes. Present only when its jar was loaded as a mod. */
    private static final String[] OPTIFABRIC_CLASSES = {
        "me.modmuss50.optifabric.mod.OptifabricSetup",
        "me.modmuss50.optifabric.mod.OptifineVersion",
        "me.modmuss50.optifabric.patcher.ClassCache",
    };

    private static boolean printed;

    private RuntimeTrace() {}

    /** Whether OptiFine's classes are loadable in this process. Not a file check. */
    public static boolean optifineActive() {
        return anyLoadable(OPTIFINE_CLASSES);
    }

    /** Whether OptiFabric's classes are loadable in this process. Not a file check. */
    public static boolean optifabricActive() {
        return anyLoadable(OPTIFABRIC_CLASSES);
    }

    /**
     * The OptiFine build string, read from its own loaded class.
     *
     * <p>Through reflection because Fathom does not compile against OptiFine and
     * must keep loading when it is absent. Null when OptiFine is not running or
     * the method is not where it used to be, which is itself worth seeing.
     */
    public static String optifineVersion() {
        try {
            Class<?> config = Class.forName("Config", false, loader());
            Object value = config.getMethod("getVersion").invoke(null);
            return value == null ? null : value.toString();
        } catch (Throwable missing) {
            return null;
        }
    }

    /**
     * Print the block. Once per process; later calls do nothing.
     *
     * <p>Nothing this class does may take the client down with it. It runs first
     * in {@code onInitializeClient}, so an exception here would fail the whole
     * mod's initialisation -- and the one thing worse than a session with no
     * diagnostic is a session with no Fathom because of the diagnostic. Every
     * individual reader already degrades to a string; this is the backstop for
     * the ones that cannot.
     */
    public static synchronized void print() {
        if (printed) {
            return;
        }
        printed = true;
        if (!"on".equalsIgnoreCase(System.getProperty("fathom.diagnose", "off"))) {
            // The full block is pages of classpath. The three lines that matter
            // every launch are printed by FramePerf's verdict instead.
            return;
        }
        try {
            for (String line : lines()) {
                System.out.println(line);
            }
        } catch (Throwable failure) {
            System.out.println("[FATHOM RUNTIME] could not be read: " + failure);
        }
    }

    /** The block, as lines, so a test can read it without capturing stdout. */
    public static List<String> lines() {
        List<String> out = new ArrayList<String>();
        out.add("[FATHOM RUNTIME]");
        out.add("Fathom build:    " + BuildId.describe());
        out.add("Mod version:     " + modVersion("fathom-optimizations"));
        out.add("Fathom jar:      " + modRoot("fathom-optimizations"));
        File gameDirectory = gameDirectory();
        out.add("Game directory:  " + path(gameDirectory));
        out.add("Java:            " + java());
        out.add("Loader:          " + loaderLine());
        out.add("Minecraft:       " + modVersion("minecraft"));
        File mods = gameDirectory == null ? null : new File(gameDirectory, "mods");
        out.add("Mods directory:  " + path(mods));
        for (String name : listing(mods)) {
            out.add("  - " + name);
        }
        out.add("OptiFabric:      " + optifabricLine());
        out.add("OptiFine:        " + optifineLine(mods));
        out.add("Classpath:");
        for (String entry : classpath()) {
            out.add("  " + entry);
        }
        out.add("Launch arguments:");
        for (String entry : launchArguments()) {
            out.add("  " + entry);
        }
        out.add("[FATHOM RUNTIME] end");
        return out;
    }

    // -----------------------------------------------------------------------
    // The two verdicts
    // -----------------------------------------------------------------------

    /**
     * OptiFabric's line.
     *
     * <p>Three separate facts, because they fail separately: the loader listed
     * the mod, the mod's classes resolve, and the jar it came from. A mod that is
     * listed but whose classes do not resolve is a mod that failed to initialise,
     * and that is a different problem from a mod that is not installed.
     */
    private static String optifabricLine() {
        boolean listed = net.fathom.optimizations.fabric.Platform.isModLoaded("optifabric");
        boolean loadable = optifabricActive();
        StringBuilder out = new StringBuilder();
        out.append(loadable ? "ACTIVE" : "NOT ACTIVE");
        out.append(" (loader lists mod: ").append(listed);
        out.append(", classes resolve: ").append(loadable).append(")");
        if (listed) {
            out.append(" version ").append(modVersion("optifabric"));
            out.append(" at ").append(modRoot("optifabric"));
        }
        return out.toString();
    }

    /**
     * OptiFine's line.
     *
     * <p>The verdict comes from the class loader. The jar name is printed after
     * it, and only after it, so the two read as what they are: a fact about the
     * running process, then a fact about the disk.
     */
    private static String optifineLine(File mods) {
        boolean loadable = optifineActive();
        StringBuilder out = new StringBuilder();
        out.append(loadable ? "ACTIVE" : "NOT ACTIVE");
        out.append(" (classes resolve: ").append(loadable).append(")");
        String version = optifineVersion();
        if (version != null) {
            out.append(" version ").append(version);
        }
        List<String> jars = new ArrayList<String>();
        for (String name : listing(mods)) {
            if (name.toLowerCase(java.util.Locale.ROOT).contains("optifine")) {
                jars.add(name);
            }
        }
        out.append(" jar on disk: ").append(jars.isEmpty() ? "none in mods" : jars.toString());
        return out.toString();
    }

    // -----------------------------------------------------------------------
    // Reading the process
    // -----------------------------------------------------------------------

    private static ClassLoader loader() {
        ClassLoader mine = RuntimeTrace.class.getClassLoader();
        return mine == null ? ClassLoader.getSystemClassLoader() : mine;
    }

    /**
     * Whether any of these class names resolves.
     *
     * <p>{@code initialize} is false: asking whether a class exists must not run
     * its static initialiser, which for a rendering mod means touching GL from
     * whatever thread happened to ask.
     */
    private static boolean anyLoadable(String[] names) {
        for (String name : names) {
            try {
                Class.forName(name, false, loader());
                return true;
            } catch (Throwable absent) {
                // The next candidate, or a negative verdict. Throwable rather
                // than ClassNotFoundException: a half-transformed class fails
                // with NoClassDefFoundError or a LinkageError, and "it did not
                // load" is the answer either way.
                continue;
            }
        }
        return false;
    }

    private static String modVersion(String id) {
        String version = net.fathom.optimizations.fabric.Platform.modVersion(id);
        return version == null ? "not loaded" : version;
    }

    private static String modRoot(String id) {
        if (!net.fathom.optimizations.fabric.Platform.fabric()) {
            // FML keeps no root for a jar on the class path; this one is ours.
            java.security.CodeSource source = RuntimeTrace.class.getProtectionDomain().getCodeSource();
            return "fathom-optimizations".equals(id) && source != null
                    ? String.valueOf(source.getLocation()) : "not loaded";
        }
        return FabricRoots.of(id);
    }

    /** Fabric's mod roots, kept apart so this class links without Fabric. */
    private static final class FabricRoots {
        static String of(String id) {
            try {
                java.util.Optional<net.fabricmc.loader.api.ModContainer> found =
                        net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(id);
                if (!found.isPresent()) {
                    return "not loaded";
                }
                // getRootPaths, not the deprecated single-root getRootPath: a mod can
                // be several jars, and a nested one reports each. Printing all of
                // them is the point -- "which file is this actually" is the question.
                return found.get().getRootPaths().toString();
            } catch (Throwable unreadable) {
                return "unreadable (" + unreadable.getClass().getSimpleName() + ")";
            }
        }
    }

    private static File gameDirectory() {
        try {
            return net.fathom.optimizations.fabric.Platform.gameDir();
        } catch (Throwable unreadable) {
            return null;
        }
    }

    private static String loaderLine() {
        return net.fathom.optimizations.fabric.Platform.loaderDescription()
                + ", main class " + System.getProperty("sun.java.command", "?").split(" ")[0]
                + ", environment "
                + (net.fathom.optimizations.fabric.Platform.development() ? "development" : "production");
    }

    private static String java() {
        return System.getProperty("java.version", "?")
                + " " + System.getProperty("java.vendor", "?")
                + " (" + System.getProperty("java.vm.name", "?") + ")"
                + " at " + System.getProperty("java.home", "?")
                + ", heap max " + (java.lang.Runtime.getRuntime().maxMemory() >> 20) + " MB";
    }

    private static String path(File file) {
        if (file == null) {
            return "unknown";
        }
        return file.getAbsolutePath() + (file.exists() ? "" : "  (MISSING)");
    }

    /** A directory's files, sorted, so two runs can be compared line by line. */
    private static List<String> listing(File directory) {
        List<String> out = new ArrayList<String>();
        if (directory == null || !directory.isDirectory()) {
            return out;
        }
        File[] found = directory.listFiles();
        if (found == null) {
            return out;
        }
        for (File each : found) {
            if (each.isFile()) {
                out.add(each.getName() + "  (" + each.length() + " bytes)");
            }
        }
        Collections.sort(out);
        return out;
    }

    private static List<String> classpath() {
        String raw = System.getProperty("java.class.path", "");
        List<String> out = new ArrayList<String>();
        if (raw.isEmpty()) {
            out.add("(empty)");
            return out;
        }
        out.addAll(Arrays.asList(raw.split(java.util.regex.Pattern.quote(File.pathSeparator))));
        return out;
    }

    /**
     * The JVM's own arguments and the game's, with credentials removed.
     *
     * <p>{@code sun.java.command} carries the main class and the game arguments;
     * the management bean carries the JVM flags. Both are read defensively --
     * neither is guaranteed, and a diagnostic that throws is worse than a
     * diagnostic that says it could not look.
     */
    private static List<String> launchArguments() {
        List<String> out = new ArrayList<String>();
        try {
            for (String flag : java.lang.management.ManagementFactory
                    .getRuntimeMXBean().getInputArguments()) {
                out.add("jvm: " + flag);
            }
        } catch (Throwable unreadable) {
            out.add("jvm: unreadable (" + unreadable.getClass().getSimpleName() + ")");
        }
        String command = System.getProperty("sun.java.command", "");
        if (command.isEmpty()) {
            out.add("game: unreadable");
        } else {
            out.add("game: " + redact(command));
        }
        return out;
    }

    /**
     * Drop the value that follows any secret-looking argument name.
     *
     * <p>The name is kept. Whether a token was passed at all is a diagnostic
     * fact; what the token is never is.
     */
    static String redact(String command) {
        String[] parts = command.split(" ");
        StringBuilder out = new StringBuilder();
        boolean skipNext = false;
        for (String part : parts) {
            if (out.length() > 0) {
                out.append(' ');
            }
            if (skipNext) {
                out.append("<redacted>");
                skipNext = false;
                continue;
            }
            out.append(part);
            for (String secret : SECRET_ARGUMENTS) {
                if (secret.equalsIgnoreCase(part)) {
                    skipNext = true;
                    break;
                }
            }
        }
        return out.toString();
    }
}
