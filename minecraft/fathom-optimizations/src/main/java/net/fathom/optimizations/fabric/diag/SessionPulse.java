package net.fathom.optimizations.fabric.diag;

import java.io.File;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;

import org.lwjgl.opengl.Display;

import net.fathom.optimizations.core.instrument.Pulse;
import net.fathom.optimizations.core.instrument.PulseJson;
import net.fathom.optimizations.core.module.LaunchSession;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.minecraft.client.MinecraftClient;

/**
 * The game's half of the performance instrument.
 *
 * <p>One {@link Pulse} for the process, fed by the frame Mixin, read by the Hub's
 * Performance page. When the launcher started this game -- {@code fathom/session.json}
 * is present -- a daemon thread also copies the pulse into
 * {@code fathom/session-pulse.json} every few seconds, which is how the launcher shows
 * the session while it runs and summarises it when it ends.
 *
 * <h2>Where the cost is</h2>
 *
 * <p>On the render thread: one {@code Display.isActive()} read and the pulse's own
 * per-frame arithmetic. Formatting and the file write are the writer thread's, which
 * sleeps between writes and skips one when nothing new was published. A game started
 * some other way never starts that thread.
 */
public final class SessionPulse {

    private static final Pulse PULSE = new Pulse();

    /** How often the launcher's copy is refreshed. Its poll is every two seconds. */
    private static final long WRITE_EVERY_MILLIS = 5_000L;

    private static volatile boolean writing;

    private SessionPulse() {}

    public static Pulse pulse() {
        return PULSE;
    }

    /**
     * One frame ended.
     *
     * @param endNanos the frame's end, already read by the caller
     */
    public static void frame(MinecraftClient client, long endNanos) {
        // The development screenshot tour runs in a window that rarely has focus, and
        // it photographs this instrument; it alone counts unfocused frames.
        boolean play = client != null && client.world != null && (UiTour.ACTIVE || Display.isActive());
        PULSE.frame(endNanos, play);
    }

    /** Something worth a tick on the timeline happened. */
    public static void mark(Pulse.Mark kind) {
        PULSE.mark(kind);
    }

    /**
     * Start the launcher's copy, if a launcher is listening.
     *
     * <p>Called once, after the client marker: from then on the session is a Fathom
     * session the launcher is watching.
     */
    public static synchronized void start(File gameDirectory, final ModuleRegistry features) {
        if (writing || gameDirectory == null) {
            return;
        }
        if (!new File(gameDirectory, LaunchSession.RELATIVE_PATH).isFile()) {
            // Not started by Fathom's launcher. Nothing would read the file.
            return;
        }
        final Path target = gameDirectory.toPath().resolve(PulseJson.RELATIVE_PATH);
        writing = true;
        final Pulse.Snapshot snapshot = new Pulse.Snapshot();
        final StringBuilder document = new StringBuilder(2048);
        final Runnable write = new Runnable() {
            @Override
            public void run() {
                synchronized (snapshot) {
                    if (!PULSE.copyPublished(snapshot)) {
                        return;
                    }
                    int modules = features == null ? 0 : features.enabledCount();
                    PulseJson.write(snapshot, modules, System.currentTimeMillis(), document);
                    save(target, document);
                }
            }
        };
        Thread writer = new Thread(new Runnable() {
            @Override
            public void run() {
                while (true) {
                    try {
                        Thread.sleep(WRITE_EVERY_MILLIS);
                    } catch (InterruptedException stopped) {
                        return;
                    }
                    try {
                        write.run();
                    } catch (Throwable failure) {
                        // Once, then quiet: the launcher shows no live numbers, and
                        // nothing about the game changes.
                        System.err.println("[Fathom] Session pulse stopped: " + failure);
                        return;
                    }
                }
            }
        }, "Fathom session pulse");
        writer.setDaemon(true);
        writer.setPriority(Thread.MIN_PRIORITY);
        writer.start();
        // The last few seconds, written as the game closes, so the launcher's summary
        // covers the whole session rather than ending up to five seconds short.
        try {
            java.lang.Runtime.getRuntime().addShutdownHook(new Thread(new Runnable() {
                @Override
                public void run() {
                    try {
                        write.run();
                    } catch (Throwable ignored) {
                        // Shutting down; there is nobody left to tell.
                    }
                }
            }, "Fathom session pulse (exit)"));
        } catch (IllegalStateException alreadyShuttingDown) {
            // Nothing to add.
        }
    }

    private static void save(Path target, CharSequence document) {
        try {
            Files.createDirectories(target.getParent());
            Path temporary = target.resolveSibling(target.getFileName().toString() + ".tmp");
            Files.write(temporary, document.toString().getBytes(StandardCharsets.UTF_8));
            try {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING,
                        StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException ignored) {
                Files.move(temporary, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (java.io.IOException failure) {
            // A locked file (the launcher mid-read on Windows) is retried next time.
        }
    }
}
