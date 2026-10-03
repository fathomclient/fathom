package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Unique;
import org.spongepowered.asm.mixin.injection.Constant;
import org.spongepowered.asm.mixin.injection.ModifyConstant;

import net.minecraft.client.render.chunk.ChunkBuilder;

import net.fathom.optimizations.core.opt.chunk.ChunkWorkerPolicy;
import net.fathom.optimizations.fabric.Runtime;

/**
 * The chunk-build worker pool, sized to the CPU -- {@code render.chunk.rebuild-scheduling}.
 *
 * <p>Vanilla's constructor (Yarn's {@code ChunkBuilder} is the
 * {@code ChunkRenderDispatcher}) starts {@code 2} workers and fills a buffer
 * queue of capacity {@code 5} with {@code 5} buffers. The {@code 2} becomes
 * {@link ChunkWorkerPolicy#workers}; both {@code 5}s become
 * {@link ChunkWorkerPolicy#buffers}, because the queue must hold every buffer
 * the loop adds. Those are the only {@code 2} and {@code 5} in the constructor.
 *
 * <p>A chunk builder is made whenever the renderers reload -- joining a world,
 * F3+A, changing some video settings -- so the switch takes effect at the next
 * of those. Skipped entirely under OptiFine, which sizes its own pool (see
 * {@code FathomMixinPlugin}).
 */
@Mixin(ChunkBuilder.class)
public abstract class ChunkBuilderWorkersMixin {

    @ModifyConstant(method = "<init>()V", constant = @Constant(intValue = 2))
    private int fathom$workers(int vanilla) {
        if (!fathom$enabled()) {
            return vanilla;
        }
        int workers = ChunkWorkerPolicy.workers(java.lang.Runtime.getRuntime().availableProcessors());
        // Once per chunk builder, which is once per renderer reload: the line
        // that says the pool really is the size the switch promised.
        System.out.println("[Fathom] Chunk builder: " + workers + " workers, "
                + ChunkWorkerPolicy.buffers(workers) + " buffers (vanilla 2 and 5).");
        return workers;
    }

    @ModifyConstant(method = "<init>()V", constant = @Constant(intValue = 5))
    private int fathom$buffers(int vanilla) {
        return fathom$enabled()
                ? ChunkWorkerPolicy.buffers(
                        ChunkWorkerPolicy.workers(java.lang.Runtime.getRuntime().availableProcessors()))
                : vanilla;
    }

    @Unique
    private static boolean fathom$enabled() {
        return Runtime.switches() != null && Runtime.switches().isEnabled(Runtime.CHUNK_WORKERS);
    }
}
