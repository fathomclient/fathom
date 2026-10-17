package net.fathom.optimizations.forge.mixin;

import net.fathom.optimizations.fabric.perf.ChunkRevision;
import net.minecraft.world.chunk.ClientChunkProvider;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/** {@link ChunkRevision}: the client's set of loaded chunks changed. */
@Mixin(ClientChunkProvider.class)
public abstract class ForgeChunkRevisionMixin {

    @Inject(method = "getOrGenerateChunk(II)Lnet/minecraft/world/chunk/Chunk;", at = @At("RETURN"))
    private void fathom$chunkLoaded(int x, int z, CallbackInfoReturnable<?> info) {
        ChunkRevision.chunkLoaded();
    }

    @Inject(method = "unloadChunk(II)V", at = @At("RETURN"))
    private void fathom$chunkUnloaded(int x, int z, CallbackInfo info) {
        ChunkRevision.chunkLoaded();
    }
}
