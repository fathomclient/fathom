package net.fathom.optimizations.forge.mixin;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.perf.ForgeSkyBlend;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.World;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Pseudo;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

/**
 * {@code render.forge.sky-blend-cache}: Forge's sky colour blend from
 * {@link ForgeSkyBlend}. Forge's own names, not remapped (see
 * {@link ForgeHudMixin}).
 */
@Pseudo
@Mixin(targets = "net.minecraftforge.client.ForgeHooksClient", remap = false)
public abstract class ForgeSkyBlendMixin {

    @Inject(method = "getSkyBlendColour(Lnet/minecraft/world/World;Lnet/minecraft/util/BlockPos;)I",
            at = @At("HEAD"), cancellable = true, remap = false)
    private static void fathom$skyBlend(World world, BlockPos center, CallbackInfoReturnable<Integer> info) {
        if (Runtime.forgeSkyBlendCache && !ForgeSkyBlend.VERIFY) {
            info.setReturnValue(ForgeSkyBlend.colour(world, center));
        }
    }

    /** QA: Forge computed the answer itself; compare the cache's with it. */
    @Inject(method = "getSkyBlendColour(Lnet/minecraft/world/World;Lnet/minecraft/util/BlockPos;)I",
            at = @At("RETURN"), remap = false)
    private static void fathom$skyBlendVerify(World world, BlockPos center, CallbackInfoReturnable<Integer> info) {
        if (Runtime.forgeSkyBlendCache && ForgeSkyBlend.VERIFY) {
            ForgeSkyBlend.verify(world, center, info.getReturnValue());
        }
    }
}
