package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.network.ClientConnection;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: the connection's tick on the client thread. */
@Mixin(ClientConnection.class)
public abstract class ViewConnectionMixin {

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void fathom$viewNetworkStart(CallbackInfo info) {
        ViewProbe.enter(ViewSection.TICK_NETWORK);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void fathom$viewNetworkEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK_NETWORK);
    }
}
