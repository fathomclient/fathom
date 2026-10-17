package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.google.common.util.concurrent.ListenableFuture;

import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.RenderLayer;
import net.minecraft.client.render.chunk.ChunkBuilder;
import net.minecraft.client.world.BuiltChunk;
import net.minecraft.client.world.ChunkAssemblyHelper;

import net.fathom.optimizations.core.bench.view.ViewCounter;
import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: chunk rebuild traffic. Yarn's {@code ChunkBuilder} here
 * is vanilla's {@code ChunkRenderDispatcher}:
 *
 * <ul>
 *   <li>{@code send(BuiltChunk)} is {@code updateChunkLater}: a rebuild queued for a worker.
 *   <li>{@code upload(BuiltChunk)} is {@code updateChunkNow}: a rebuild done on the client thread.
 *   <li>{@code upload(long)} is {@code runChunkUploads}: finished buffers handed to GL.
 *   <li>{@code upload(RenderLayer, ...)} is {@code uploadChunk}: one layer's buffer, from any thread.
 * </ul>
 *
 * <p>A still camera over a settled world should show none of these in a
 * measured window. Any that do are rebuilds the report has to account for.
 */
@Mixin(ChunkBuilder.class)
public abstract class ViewChunkBuilderMixin {

    @Inject(method = "send(Lnet/minecraft/client/world/BuiltChunk;)Z", at = @At("HEAD"), require = 0)
    private void fathom$viewQueued(BuiltChunk chunk, CallbackInfoReturnable<Boolean> info) {
        ViewProbe.count(ViewCounter.REBUILDS_QUEUED, 1L);
    }

    @Inject(method = "upload(Lnet/minecraft/client/world/BuiltChunk;)Z", at = @At("HEAD"), require = 0)
    private void fathom$viewSyncStart(BuiltChunk chunk, CallbackInfoReturnable<Boolean> info) {
        ViewProbe.count(ViewCounter.REBUILDS_SYNC, 1L);
        ViewProbe.enter(ViewSection.CHUNK_COMPILE);
    }

    @Inject(method = "upload(Lnet/minecraft/client/world/BuiltChunk;)Z", at = @At("RETURN"), require = 0)
    private void fathom$viewSyncEnd(BuiltChunk chunk, CallbackInfoReturnable<Boolean> info) {
        ViewProbe.exit(ViewSection.CHUNK_COMPILE);
    }

    @Inject(method = "upload(J)Z", at = @At("HEAD"), require = 0)
    private void fathom$viewUploadsStart(long finishBy, CallbackInfoReturnable<Boolean> info) {
        ViewProbe.enter(ViewSection.CHUNK_UPLOAD);
    }

    @Inject(method = "upload(J)Z", at = @At("RETURN"), require = 0)
    private void fathom$viewUploadsEnd(long finishBy, CallbackInfoReturnable<Boolean> info) {
        ViewProbe.exit(ViewSection.CHUNK_UPLOAD);
    }

    @Inject(method = "upload(Lnet/minecraft/client/render/RenderLayer;Lnet/minecraft/client/render/BufferBuilder;Lnet/minecraft/client/world/BuiltChunk;Lnet/minecraft/client/world/ChunkAssemblyHelper;)Lcom/google/common/util/concurrent/ListenableFuture;",
            at = @At("HEAD"), require = 0)
    private void fathom$viewLayerUpload(RenderLayer layer, BufferBuilder buffer, BuiltChunk chunk,
            ChunkAssemblyHelper helper, CallbackInfoReturnable<ListenableFuture<Object>> info) {
        ViewProbe.count(ViewCounter.UPLOADS, 1L);
    }
}
