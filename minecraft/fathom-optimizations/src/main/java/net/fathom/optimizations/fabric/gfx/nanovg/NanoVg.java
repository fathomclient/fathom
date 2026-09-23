package net.fathom.optimizations.fabric.gfx.nanovg;

import java.io.File;
import java.io.IOException;
import java.net.MalformedURLException;
import java.net.URL;
import java.net.URLClassLoader;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.List;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.ui.render.FathomRenderer;

/**
 * Brings up the NanoVG backend without letting LWJGL 3 anywhere near the game.
 *
 * <h2>The problem</h2>
 *
 * <p>Minecraft 1.8.9 ships LWJGL 2.9.4. NanoVG exists only in LWJGL 3, and the
 * two cannot share a classloader: both define {@code org.lwjgl.BufferUtils},
 * {@code org.lwjgl.PointerBuffer} and the {@code org.lwjgl.opengl.GL*} classes,
 * with different APIs. Whichever loaded first would win, and the game or the
 * backend would fail with a {@code NoSuchMethodError} at the first call into the
 * other's version.
 *
 * <h2>The arrangement</h2>
 *
 * <p>A {@link URLClassLoader} over LWJGL 3's jars and the {@code nanovg} source
 * set's classes, whose parent is the loader that loaded Fathom. It is
 * <b>child-first for exactly two prefixes</b> -- {@code org.lwjgl.} and
 * {@code net.fathom.optimizations.nanovg.} -- and parent-first for everything
 * else. So:
 *
 * <ul>
 *   <li>inside it, {@code org.lwjgl.opengl.GL} is LWJGL 3's, which is what
 *       NanoVG's GL backend looks up by name;
 *   <li>{@code net.fathom.optimizations.core.ui.render.FathomRenderer} is the
 *       game's copy, so the backend can be handed back and cast to it;
 *   <li>Fathom's own resources (the Geist fonts) resolve from the mod as usual.
 * </ul>
 *
 * <p>Natives come from LWJGL 3's natives jars on that same classpath and are
 * extracted by LWJGL into the game directory rather than the system temp
 * folder -- a visible, per-instance place a user can inspect and delete.
 *
 * <h2>Scope</h2>
 *
 * <p>Development only in this phase. The classpath is listed by the build
 * ({@code gradle runClient -Pnanovg}) and handed over as
 * {@code -Dfathom.nanovg.classpathFile}. Without that property this class does
 * nothing and reports why. Packaging these jars into a shipped mod is the next
 * phase; see {@code docs/ingame-ui-nanovg.md}.
 */
public final class NanoVg {

    private static final String BACKEND = "net.fathom.optimizations.nanovg.NanoVgRenderer";
    private static final String CLASSPATH_PROPERTY = "fathom.nanovg.classpathFile";
    private static final String ENABLED_PROPERTY = "fathom.nanovg";
    private static final String EXTRACT_PROPERTY = "org.lwjgl.system.SharedLibraryExtractPath";

    private static FathomRenderer renderer;
    /** The isolated loader owns open jar handles as well as classes. */
    private static URLClassLoader loader;
    private static String failure = "not attempted";
    private static boolean attempted;

    private NanoVg() {}

    /**
     * The backend, created on first use; null when it cannot be had.
     *
     * <p>Creating it touches no GL: the backend initialises inside its first
     * {@code beginFrame}, on the render thread, with the context current.
     */
    public static FathomRenderer renderer(MinecraftClient client) {
        if (attempted) {
            return renderer;
        }
        attempted = true;
        try {
            renderer = create(client);
            failure = null;
        } catch (Exception | LinkageError problem) {
            renderer = null;
            closeLoader();
            failure = problem.getClass().getSimpleName() + ": " + problem.getMessage();
            // Absent is the *normal* state in a shipped client: the NanoVG
            // source set is a development-only build (`build.gradle`: "Nothing
            // here is packaged"), so every release build reaches this line and
            // falls back to the GL renderer exactly as intended.
            //
            // It used to say so on stderr, which had two consequences for
            // people who had never seen a Gradle command: the launcher's game
            // console treats stderr as something worth showing, so a red line
            // appeared on an ordinary launch; and the line advised running
            // `gradle runClient -Pnanovg`, which is not a thing a player can
            // do. Both were a development diagnostic escaping into a product.
            //
            // So it is reported where it is useful and nowhere else: on stdout
            // for a developer who asked for the backend, and silently otherwise
            // -- `failure()` still carries the reason for Diagnostics, which is
            // where somebody investigating would actually look.
            if (System.getProperty("fathom.nanovg.classpathFile") != null) {
                System.out.println("[Fathom] NanoVG backend unavailable: " + failure);
            }
        }
        return renderer;
    }

    /** Why {@link #renderer} returned null, or null if it did not. */
    public static String failure() {
        return failure;
    }

    /**
     * Give up on the backend for the rest of the session, saying why.
     *
     * <p>Called when initialisation or a frame fails in a way retrying would only
     * repeat. Every later {@link #renderer} returns null, and callers fall back.
     */
    public static void fail(String why, Throwable cause) {
        failure = why + (cause == null ? "" : ": " + cause);
        System.err.println("[Fathom] NanoVG disabled for this session: " + failure);
        if (cause != null) {
            cause.printStackTrace();
        }
        FathomRenderer broken = renderer;
        renderer = null;
        attempted = true;
        if (broken != null) {
            try {
                broken.dispose();
            } catch (RuntimeException | LinkageError ignored) {
                // Already failing; the context goes away with the game.
            }
        }
        closeLoader();
    }

    /** Release the backend's GPU resources. Call on the render thread. */
    public static void dispose() {
        if (renderer != null) {
            renderer.dispose();
            renderer = null;
        }
        closeLoader();
        attempted = false;
        failure = "disposed";
    }

    private static FathomRenderer create(MinecraftClient client) throws Exception {
        if ("false".equalsIgnoreCase(System.getProperty(ENABLED_PROPERTY, ""))) {
            throw new IllegalStateException("disabled by -D" + ENABLED_PROPERTY + "=false");
        }
        String listing = System.getProperty(CLASSPATH_PROPERTY);
        if (listing == null || listing.trim().isEmpty()) {
            throw new IllegalStateException("no " + CLASSPATH_PROPERTY
                    + "; start the client with gradle runClient -Pnanovg");
        }
        File file = new File(listing.trim());
        if (!file.isFile()) {
            throw new IllegalStateException("classpath listing missing: " + file);
        }

        List<URL> urls = new ArrayList<URL>();
        for (String line : Files.readAllLines(file.toPath(), StandardCharsets.UTF_8)) {
            String entry = line.trim();
            if (!entry.isEmpty()) {
                urls.add(toUrl(new File(entry)));
            }
        }

        if (System.getProperty(EXTRACT_PROPERTY) == null) {
            File natives = new File(client.runDirectory, ".fathom/natives/lwjgl3");
            if (!natives.isDirectory() && !natives.mkdirs()) {
                throw new IOException("could not create " + natives);
            }
            System.setProperty(EXTRACT_PROPERTY, natives.getAbsolutePath());
        }

        loader = new IsolatedLoader(urls.toArray(new URL[0]), NanoVg.class.getClassLoader());
        Class<?> type = Class.forName(BACKEND, true, loader);
        Object backend = type.getConstructor().newInstance();
        if (!(backend instanceof FathomRenderer)) {
            // The one thing that would mean the loader delegates the wrong way:
            // the backend saw a different FathomRenderer from the one we did.
            throw new IllegalStateException(BACKEND + " is not this game's FathomRenderer; "
                    + "check the isolated loader's delegation");
        }
        return (FathomRenderer) backend;
    }

    /** Close the isolated classpath so reloads do not accumulate jar handles. */
    private static void closeLoader() {
        URLClassLoader current = loader;
        loader = null;
        if (current == null) {
            return;
        }
        try {
            current.close();
        } catch (IOException problem) {
            System.err.println("[Fathom] could not close NanoVG classpath: " + problem.getMessage());
        }
    }

    private static URL toUrl(File entry) {
        try {
            return entry.toURI().toURL();
        } catch (MalformedURLException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    /** Child-first for LWJGL 3 and the backend; the game's loader for everything else. */
    private static final class IsolatedLoader extends URLClassLoader {

        IsolatedLoader(URL[] urls, ClassLoader parent) {
            super(urls, parent);
        }

        private static boolean owned(String name) {
            return name.startsWith("org.lwjgl.")
                    || name.startsWith("net.fathom.optimizations.nanovg.");
        }

        @Override
        protected Class<?> loadClass(String name, boolean resolve) throws ClassNotFoundException {
            synchronized (getClassLoadingLock(name)) {
                Class<?> found = findLoadedClass(name);
                if (found == null && owned(name)) {
                    try {
                        found = findClass(name);
                    } catch (ClassNotFoundException notOurs) {
                        // Not in LWJGL 3 or the backend; the parent may still have it.
                    }
                }
                if (found == null) {
                    found = super.loadClass(name, false);
                }
                if (resolve) {
                    resolveClass(found);
                }
                return found;
            }
        }

        @Override
        public URL getResource(String name) {
            // LWJGL 3 looks its natives up as resources ("windows/x64/org/lwjgl/
            // lwjgl.dll") and must find its own, never LWJGL 2's.
            if (name.startsWith("org/lwjgl/") || name.startsWith("windows/")
                    || name.startsWith("linux/") || name.startsWith("macos/")) {
                URL local = findResource(name);
                if (local != null) {
                    return local;
                }
            }
            return super.getResource(name);
        }
    }
}
