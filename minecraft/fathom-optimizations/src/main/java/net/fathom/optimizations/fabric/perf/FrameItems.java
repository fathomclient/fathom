package net.fathom.optimizations.fabric.perf;

import net.minecraft.entity.ItemEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.world.World;

/**
 * {@code render.entity.item-frame-entity-reuse}: one item entity for drawing every item
 * frame's item, instead of a new one per frame entity per frame.
 *
 * <p>{@code ItemFrameEntityRenderer} draws a frame's item by constructing an
 * {@code ItemEntity} around the frame's stack -- every frame, for every frame in view --
 * and then reads only that stack back from it (and sets its count to 1 and its hover to
 * 0). Constructing an entity is not free: a random UUID, a data tracker with its lock and
 * map, a bounding box. The renderer uses nothing of the entity but the stack, so one
 * entity, given the current frame's stack each time, hands it exactly the same inputs.
 * OptiFine's patched renderer keeps the construction (checked in its patched bytecode).
 *
 * <p>The entity belongs to one world; another world gets a new one, and a world change
 * drops it ({@link #release}), so a disconnected world is never held. Client thread only.
 */
public final class FrameItems {

    private FrameItems() {}

    private static ItemEntity kept;

    /** The kept entity, now holding {@code stack}, or null to construct one. */
    public static ItemEntity reuse(World world, ItemStack stack) {
        ItemEntity entity = kept;
        if (entity == null || entity.world != world || NametagNames.VERIFY) {
            return null;
        }
        entity.setItemStack(stack);
        return entity;
    }

    public static void keep(ItemEntity entity) {
        kept = entity;
    }

    /** A new world, a disconnect, the feature switched off: let the entity go. */
    public static void release() {
        kept = null;
    }
}
