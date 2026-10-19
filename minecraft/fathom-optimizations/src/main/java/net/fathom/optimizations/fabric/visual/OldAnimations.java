package net.fathom.optimizations.fabric.visual;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;
import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.util.UseAction;
import net.minecraft.util.hit.BlockHitResult;

/**
 * 1.7 Animations, as the held-item hook asks for them.
 *
 * <h2>The blockhit</h2>
 *
 * <p>1.8 draws an item in use (a sword blocking, food, a potion, a bow) with
 * the swing progress forced to zero, so a swing stops dead the moment you
 * block. 1.7 kept the swing: hitting and blocking in quick succession drew the
 * sword swinging while it blocked. The hook hands the swing progress back to
 * that one transform; everything else about the frame is 1.8's.
 *
 * <h2>Punching while using</h2>
 *
 * <p>1.8 also stopped left-clicking a block while an item is in use; 1.7
 * swung the arm. Only the animation is restored here: the swing is started on
 * the client's own copy of the player, with no packet, so the server sees
 * exactly what an unmodified 1.8 client sends.
 */
public final class OldAnimations {

    /** The frame's partial tick, as {@code renderArmHoldingItem} was called with it. */
    private static float tickDelta;

    /** Whether the held-item hook has ever run: false under a renderer that replaced it. */
    private static boolean hookSeen;

    private OldAnimations() {}

    private static FathomModule active() {
        if (Runtime.features() == null) {
            return null;
        }
        FathomModule module = Runtime.features().find("old-animations");
        return module != null && module.enabled() && module.switchable() ? module : null;
    }

    /** Called as the first-person hand starts drawing. */
    public static void frame(float partialTick) {
        tickDelta = partialTick;
    }

    /**
     * The swing progress the equip transform should use, given what 1.8 passed:
     * zero while an item is in use, the real swing otherwise.
     */
    public static float swing(float original) {
        hookSeen = true;
        if (original != 0f) {
            return original;
        }
        FathomModule module = active();
        if (module == null) {
            return original;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        ClientPlayerEntity player = client.player;
        if (player == null || player.getItemUseTicks() <= 0) {
            return original;
        }
        ItemStack used = player.getUsedItem();
        if (used == null) {
            return original;
        }
        UseAction action = used.getUseAction();
        boolean wanted = action == UseAction.BLOCK
                ? module.toggle("blockhit", true)
                : (action == UseAction.EAT || action == UseAction.DRINK || action == UseAction.BOW)
                        && module.toggle("use-swing", true);
        return wanted ? player.getHandSwingProgress(tickDelta) : original;
    }

    /** Whether vanilla's first-person item code has been seen running this hook. */
    public static boolean hookSeen() {
        return hookSeen;
    }

    /** Once a client tick: the 1.7 swing when attack is held on a block while using an item. */
    public static void tick(MinecraftClient client) {
        ClientPlayerEntity player = client.player;
        if (player == null || client.currentScreen != null || !client.focused
                || player.getUsedItem() == null || !client.options.attackKey.isPressed()) {
            return;
        }
        BlockHitResult target = client.result;
        if (target == null || target.type != BlockHitResult.Type.BLOCK) {
            return;
        }
        FathomModule module = active();
        if (module == null || !module.toggle("punch", true)) {
            return;
        }
        // LivingEntity.swingHand without the packet ClientPlayerEntity adds:
        // restart only once the current swing is half done, as vanilla does.
        int duration = swingDuration(player);
        if (!player.handSwinging || player.handSwingTicks >= duration / 2 || player.handSwingTicks < 0) {
            player.handSwingTicks = -1;
            player.handSwinging = true;
        }
    }

    /** Vanilla's swing length: six ticks, shortened by Haste and lengthened by Mining Fatigue. */
    private static int swingDuration(ClientPlayerEntity player) {
        net.minecraft.entity.effect.StatusEffectInstance haste =
                player.getEffectInstance(net.minecraft.entity.effect.StatusEffect.HASTE);
        if (haste != null) {
            return 6 - (1 + haste.getAmplifier());
        }
        net.minecraft.entity.effect.StatusEffectInstance fatigue =
                player.getEffectInstance(net.minecraft.entity.effect.StatusEffect.MINING_FATIGUE);
        if (fatigue != null) {
            return 6 + (1 + fatigue.getAmplifier()) * 2;
        }
        return 6;
    }
}
