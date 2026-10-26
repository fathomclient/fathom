package net.fathom.optimizations.fabric.gfx;

import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.ThreadFactory;

/**
 * One background thread for reading and decoding the mod's own resources, so
 * the render thread only has to upload them.
 *
 * <p>Only file reads and image decoding go here. Anything that touches OpenGL
 * stays on the thread that owns the context: a task returns plain bytes and the
 * render thread uploads them.
 */
final class AssetPrep {

    private static ExecutorService executor;

    private AssetPrep() {}

    static synchronized <T> Future<T> submit(Callable<T> task) {
        if (executor == null) {
            executor = Executors.newSingleThreadExecutor(new ThreadFactory() {
                @Override
                public Thread newThread(Runnable work) {
                    Thread thread = new Thread(work, "Fathom asset prep");
                    thread.setDaemon(true);
                    thread.setPriority(Thread.NORM_PRIORITY - 1);
                    return thread;
                }
            });
        }
        return executor.submit(task);
    }
}
