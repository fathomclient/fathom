package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.NetworkThreadUtils;
import net.minecraft.network.Packet;
import net.minecraft.network.listener.PacketListener;
import net.minecraft.util.ThreadExecutor;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.fabric.bench.view.PacketKinds;

/**
 * View benchmark only: what each scheduled client-thread task was.
 *
 * <p>Every play handler starts with {@code forceMainThread}. On the network
 * thread it queues the handler for the client thread and returns by exception;
 * on the client thread it returns and the handler runs. So the same call names
 * the packet when it is queued and when it is applied, and the time of the
 * task it runs in ({@code TASKS}) can be charged to its kind.
 */
@Mixin(NetworkThreadUtils.class)
public abstract class ViewPacketMixin {

    @Inject(method = "forceMainThread(Lnet/minecraft/network/Packet;Lnet/minecraft/network/listener/PacketListener;Lnet/minecraft/util/ThreadExecutor;)V",
            at = @At("HEAD"), require = 0)
    private static <T extends PacketListener> void fathom$viewPacket(Packet<T> packet, T listener,
            ThreadExecutor executor, CallbackInfo info) {
        ViewProbe.packet(PacketKinds.of(packet));
    }
}
