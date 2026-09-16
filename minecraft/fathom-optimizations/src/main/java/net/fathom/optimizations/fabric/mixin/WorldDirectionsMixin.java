package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Redirect;

import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

import net.fathom.optimizations.fabric.opt.DirectionArrays;

/** Light queries and light updates iterate the six directions. See {@link DirectionArrays}. */
@Mixin(World.class)
public abstract class WorldDirectionsMixin {

    @Redirect(method = {
                "getLightAtPos(Lnet/minecraft/util/math/BlockPos;Lnet/minecraft/world/LightType;)I",
                "calculateLightAtPos(Lnet/minecraft/world/LightType;Lnet/minecraft/util/math/BlockPos;)Z"
            },
            at = @At(value = "INVOKE",
                    target = "Lnet/minecraft/util/math/Direction;values()[Lnet/minecraft/util/math/Direction;"))
    private Direction[] fathom$sharedDirections() {
        return DirectionArrays.values();
    }
}
