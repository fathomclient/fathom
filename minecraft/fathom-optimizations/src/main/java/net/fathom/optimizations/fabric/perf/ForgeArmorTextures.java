package net.fathom.optimizations.fabric.perf;

import java.lang.reflect.Method;

import net.minecraft.item.ArmorItem;
import net.minecraft.item.Item;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

/**
 * {@code render.entity.armor-texture-cache} on Forge.
 *
 * <p>Forge does not draw armour through vanilla's texture lookup, which is
 * where {@link ArmorTextures} hooks: its armour layer calls a method of its own,
 * {@code LayerArmorBase.getArmorResource(Entity, ItemStack, int, String)}, and
 * the vanilla one is never reached (with or without OptiFine, which calls the
 * same Forge method through its reflector). So on Forge the vanilla hook
 * applied and did nothing. Forge's method does more per armour piece per frame
 * than vanilla's did: two {@code String.format}s, a call out to the item for a
 * custom texture, then a map lookup.
 *
 * <p>Its answer depends on the material's texture name, the layer, the overlay
 * suffix and whatever the item's {@code getArmorTexture} returns. That last one
 * is Forge's hook for mods; vanilla items do not override it, so for them the
 * answer is a function of (material, layer, overlay) and is remembered here
 * the first time Forge produces it -- the very object Forge's own map holds.
 * An item class that overrides the hook anywhere below {@link Item} is never
 * cached: its texture may depend on the stack or the wearer.
 */
public final class ForgeArmorTextures {

    private ForgeArmorTextures() {}

    /** Material, then layer, then overlay. Materials added at run time fall outside and are not cached. */
    private static final Identifier[] TABLE = new Identifier[ArmorItem.Material.values().length * 4];

    public static long verified;
    public static long mismatches;

    private static final ClassValue<Boolean> CACHEABLE = new ClassValue<Boolean>() {
        @Override
        protected Boolean computeValue(Class<?> type) {
            return Boolean.valueOf(!overridesTextureHook(type));
        }
    };

    /** Whether a class between {@code type} and {@link Item} declares Forge's per-item texture hook. */
    static boolean overridesTextureHook(Class<?> type) {
        for (Class<?> at = type; at != null && at != Item.class && at != Object.class; at = at.getSuperclass()) {
            for (Method method : at.getDeclaredMethods()) {
                if ("getArmorTexture".equals(method.getName()) && method.getParameterTypes().length == 4) {
                    return true;
                }
            }
        }
        return false;
    }

    /** The remembered texture, or null to let Forge work it out. */
    public static Identifier lookup(ItemStack stack, int slot, String type) {
        if (NametagNames.VERIFY) {
            return null;
        }
        int index = index(stack, slot, type);
        return index < 0 ? null : TABLE[index];
    }

    /** What Forge answered, kept for next time. */
    public static void remember(ItemStack stack, int slot, String type, Identifier texture) {
        int index = index(stack, slot, type);
        if (index < 0 || texture == null) {
            return;
        }
        if (NametagNames.VERIFY && TABLE[index] != null) {
            verified++;
            if (TABLE[index] != texture) {
                mismatches++;
            }
        }
        TABLE[index] = texture;
    }

    static int index(ItemStack stack, int slot, String type) {
        int variant;
        if (type == null) {
            variant = 0;
        } else if ("overlay".equals(type)) {
            variant = 1;
        } else {
            return -1;
        }
        Item item = stack == null ? null : stack.getItem();
        if (!(item instanceof ArmorItem) || !CACHEABLE.get(item.getClass()).booleanValue()) {
            return -1;
        }
        ArmorItem.Material material = ((ArmorItem) item).getMaterial();
        if (material == null) {
            return -1;
        }
        // Forge's own test for the second layer: the leggings slot.
        int index = material.ordinal() * 4 + (slot == 2 ? 2 : 0) + variant;
        return index < TABLE.length ? index : -1;
    }
}
