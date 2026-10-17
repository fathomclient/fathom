package net.fathom.optimizations.fabric.mixin.view;

import java.util.concurrent.FutureTask;

import org.apache.logging.log4j.Logger;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import net.minecraft.util.Util;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/**
 * View benchmark only: work queued for the client thread, which is where 1.8.9
 * handles network packets. The integrated server runs the same method on its
 * own thread; the probe ignores every thread but the client's.
 */
@Mixin(Util.class)
public abstract class ViewTaskMixin {

    @Inject(method = "executeTask(Ljava/util/concurrent/FutureTask;Lorg/apache/logging/log4j/Logger;)Ljava/lang/Object;",
            at = @At("HEAD"), require = 0)
    private static void fathom$viewTaskStart(FutureTask<?> task, Logger logger,
            CallbackInfoReturnable<Object> info) {
        ViewProbe.enter(ViewSection.TASKS);
    }

    @Inject(method = "executeTask(Ljava/util/concurrent/FutureTask;Lorg/apache/logging/log4j/Logger;)Ljava/lang/Object;",
            at = @At("RETURN"), require = 0)
    private static void fathom$viewTaskEnd(FutureTask<?> task, Logger logger,
            CallbackInfoReturnable<Object> info) {
        ViewProbe.exit(ViewSection.TASKS);
    }
}
