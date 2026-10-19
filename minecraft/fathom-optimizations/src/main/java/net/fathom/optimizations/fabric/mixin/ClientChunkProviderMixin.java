package net.fathom.optimizations.fabric.mixin;

import java.util.Arrays;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.Redirect;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.util.collection.LongObjectStorage;
import net.minecraft.world.World;
import net.minecraft.world.chunk.Chunk;
import net.minecraft.world.chunk.ClientChunkProvider;

import net.fathom.optimizations.fabric.Runtime;

/**
 * Chunk lookup cache — {@code world.chunk.lookup-cache}.
 *
 * <h2>What profiling found</h2>
 *
 * <p>Every block-light query resolves its chunk through
 * {@code ClientChunkProvider.getChunk}, which is a {@code LongObjectStorage} hash
 * lookup. A particle asks for light every frame, and a light query touches the
 * chunk several times (the position, then its neighbours for slabs and stairs).
 * In a sampled benchmark run that hash lookup was the single hottest method on
 * the render thread: 28% of its CPU samples.
 *
 * <h2>Why it cannot return a different chunk</h2>
 *
 * <p>It remembers what the map returned for the last few chunk keys and hands the
 * same object back. The map only changes in {@code getOrGenerateChunk} (a chunk
 * arriving) and {@code unloadChunk}; both clear the whole cache before they run.
 * A key the map did not contain is remembered as absent and the caller still
 * substitutes the empty chunk, exactly as vanilla does.
 *
 * <p>Only the thread that created the provider — the client thread — uses or
 * fills the cache. Any other caller gets vanilla's lookup untouched, so no
 * cross-thread state is introduced.
 */
@Mixin(ClientChunkProvider.class)
public abstract class ClientChunkProviderMixin {

    /**
     * Power of two, sized for the chunk columns in view rather than for a hot
     * handful: entity rendering resolves the chunk of every visible render
     * section, interleaved across hundreds of columns (about 625 at render
     * distance 12), and a 16-slot table missed nearly every time. 1024 slots is
     * about 17 KB per world.
     */
    @Unique private static final int SLOTS = 1024;
    @Unique private static final int SHIFT = 64 - 10;

    @Unique private final long[] fathom$keys = new long[SLOTS];
    @Unique private final Object[] fathom$chunks = new Object[SLOTS];
    @Unique private final boolean[] fathom$filled = new boolean[SLOTS];
    @Unique private Thread fathom$owner;

    @Inject(method = "<init>(Lnet/minecraft/world/World;)V", at = @At("RETURN"))
    private void fathom$remember(World world, CallbackInfo info) {
        fathom$owner = Thread.currentThread();
    }

    @Redirect(method = "getChunk(II)Lnet/minecraft/world/chunk/Chunk;",
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/collection/LongObjectStorage;get(J)Ljava/lang/Object;"))
    private Object fathom$cachedLookup(LongObjectStorage<?> storage, long key) {
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
        if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()
                || !net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowOptifineOverlapHooks()) {
            return storage.get(key);
        }
        if (!Runtime.chunkLookupCache || Thread.currentThread() != fathom$owner) {
            return storage.get(key);
        }
        int slot = (int) ((key * 0x9E3779B97F4A7C15L) >>> SHIFT);
        if (fathom$filled[slot] && fathom$keys[slot] == key) {
            net.fathom.optimizations.core.bench.view.ViewProbe.engaged(
                    net.fathom.optimizations.core.bench.view.ViewCounter.ENGAGED_CHUNK_LOOKUP);
            return fathom$chunks[slot];
        }
        Object found = storage.get(key);
        fathom$keys[slot] = key;
        fathom$chunks[slot] = found;
        fathom$filled[slot] = true;
        return found;
    }

    @Inject(method = "getOrGenerateChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("HEAD"))
    private void fathom$chunkArriving(int x, int z, CallbackInfoReturnable<Chunk> info) {
        fathom$invalidate();
    }

    @Inject(method = "unloadChunk(II)V", at = @At("HEAD"))
    private void fathom$chunkLeaving(int x, int z, CallbackInfo info) {
        fathom$invalidate();
    }

    @Unique
    private void fathom$invalidate() {
        Arrays.fill(fathom$filled, false);
        Arrays.fill(fathom$chunks, null);
    }
}
