package net.fathom.optimizations.forge.mixin;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.ForgeRenderEvents;
import net.minecraft.client.render.entity.LivingEntityRenderer;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Coerce;
import org.spongepowered.asm.mixin.injection.Redirect;

/**
 * {@code render.forge.unheard-render-events}: OptiFine's check before each of
 * its four reflective Forge event posts in {@code RendererLivingEntity}, also
 * false when the event has no listener. See {@link ForgeRenderEvents}.
 *
 * <p>The target only exists in OptiFine's version of the class; on Forge
 * without OptiFine, Forge posts the events directly and nothing here applies.
 */
@Mixin(LivingEntityRenderer.class)
public abstract class ForgeRenderEventsMixin {

    /** {@code RenderLivingEvent.Specials.Pre} and {@code .Post}, around the name label. */
    @Redirect(method = "method_10256(Lnet/minecraft/entity/LivingEntity;DDD)V",
            at = @At(value = "INVOKE", target = "Lnet/optifine/reflect/ReflectorConstructor;exists()Z", remap = false),
            require = 0)
    private boolean fathom$labelEventHeard(@Coerce Object constructor) {
        return Runtime.forgeUnheardEvents ? ForgeRenderEvents.exists(constructor) : ForgeRenderEvents.original(constructor);
    }

    /** {@code RenderLivingEvent.Pre} and {@code .Post}, around the body. */
    @Redirect(method = "render(Lnet/minecraft/entity/LivingEntity;DDDFF)V",
            at = @At(value = "INVOKE", target = "Lnet/optifine/reflect/ReflectorConstructor;exists()Z", remap = false),
            require = 0)
    private boolean fathom$bodyEventHeard(@Coerce Object constructor) {
        return Runtime.forgeUnheardEvents ? ForgeRenderEvents.exists(constructor) : ForgeRenderEvents.original(constructor);
    }
}
