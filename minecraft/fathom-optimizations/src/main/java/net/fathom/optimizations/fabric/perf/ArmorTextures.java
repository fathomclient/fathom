package net.fathom.optimizations.fabric.perf;

import net.minecraft.item.ArmorItem;
import net.minecraft.util.Identifier;

/**
 * The armour texture each (material, layer, overlay) resolves to.
 *
 * <p>Vanilla's {@code ArmorFeatureRenderer.getArmorTexture} builds the path with
 * {@code String.format} -- twice for an overlay -- and then looks the result up in
 * its own string-keyed cache, so the same identifier comes back every time at the
 * cost of a formatter, a varargs array and a boxed integer per armour piece per
 * frame. OptiFine's patched class keeps the call. This remembers what the original
 * returned for each combination, so a hit is the very same {@code Identifier}
 * object vanilla's cache would have produced.
 *
 * <p>Only the overlays vanilla uses ({@code null} and {@code "overlay"}) are kept;
 * any other string, or a material beyond the table, takes the original path every
 * time. Client thread only; a stale read on another thread would only choose the
 * equivalent original path.
 */
public final class ArmorTextures {

    private ArmorTextures() {}

    private static final Identifier[] TABLE = new Identifier[ArmorItem.Material.values().length * 4];

    /** See {@link NametagNames#VERIFY}: when set, the original always runs and is compared. */
    public static long verified;
    public static long mismatches;

    public static Identifier lookup(ArmorItem item, boolean secondLayer, String overlay) {
        if (NametagNames.VERIFY) {
            return null;
        }
        int slot = slot(item, secondLayer, overlay);
        return slot < 0 ? null : TABLE[slot];
    }

    public static void remember(ArmorItem item, boolean secondLayer, String overlay, Identifier texture) {
        int slot = slot(item, secondLayer, overlay);
        if (slot >= 0 && texture != null) {
            if (NametagNames.VERIFY && TABLE[slot] != null) {
                verified++;
                if (TABLE[slot] != texture) {
                    mismatches++;
                }
            }
            TABLE[slot] = texture;
        }
    }

    static int slot(ArmorItem item, boolean secondLayer, String overlay) {
        int variant;
        if (overlay == null) {
            variant = 0;
        } else if ("overlay".equals(overlay)) {
            variant = 1;
        } else {
            return -1;
        }
        ArmorItem.Material material = item == null ? null : item.getMaterial();
        if (material == null) {
            return -1;
        }
        int slot = material.ordinal() * 4 + (secondLayer ? 2 : 0) + variant;
        return slot < TABLE.length ? slot : -1;
    }
}
