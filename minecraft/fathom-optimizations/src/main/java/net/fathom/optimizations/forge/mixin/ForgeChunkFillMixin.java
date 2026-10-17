package net.fathom.optimizations.forge.mixin;

import net.fathom.optimizations.fabric.perf.ChunkRevision;
import net.minecraft.world.chunk.Chunk;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

/** {@link ChunkRevision}: a chunk's data, biomes included, was replaced. */
@Mixin(Chunk.class)
public abstract class ForgeChunkFillMixin {

    /** Filled from the server's chunk packet. */
    @Inject(method = "method_3895([BIZ)V", at = @At("RETURN"))
    private void fathom$chunkFilled(byte[] data, int sections, boolean full, CallbackInfo info) {
        ChunkRevision.chunkFilled();
    }

    @Inject(method = "setBiomeArray([B)V", at = @At("RETURN"))
    private void fathom$biomesReplaced(byte[] biomes, CallbackInfo info) {
        ChunkRevision.chunkFilled();
    }
}
