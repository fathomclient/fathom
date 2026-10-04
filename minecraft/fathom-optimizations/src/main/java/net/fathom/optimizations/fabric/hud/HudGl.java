package net.fathom.optimizations.fabric.hud;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.render.BufferBuilder;
import net.minecraft.client.render.DiffuseLighting;
import net.minecraft.client.render.Tessellator;
import net.minecraft.client.render.VertexFormats;
import net.minecraft.client.render.item.ItemRenderer;
import net.minecraft.item.ItemStack;
import net.minecraft.util.Identifier;

import net.fathom.optimizations.fabric.gfx.Gfx;

/**
 * The two things a HUD widget can draw that are not a rectangle: a sprite from
 * one of Minecraft's own sheets, and an item.
 *
 * <h2>Why items are drawn by Minecraft and not by Fathom</h2>
 *
 * <p>An item is a baked model, a resource-pack texture, an enchantment glint and
 * a damage bar. Drawing a copy of it would mean a second opinion about every one
 * of those, and the first pack that retextured a sword would show Fathom's
 * armour readout still holding the old one. So the armour widget calls the same
 * {@code ItemRenderer} the hotbar does, inside the same GL state the hotbar sets
 * up, and resource packs, custom models and glints all keep working.
 *
 * <h2>State is left exactly as it was found</h2>
 *
 * <p>Both calls below set what they need and put it back: item lighting is
 * enabled and disabled around the item, blending and the current colour are
 * restored after a sprite. Anything Fathom's own renderer had queued is flushed
 * first — it batches through the same tessellator, and a batch left open across
 * a raw GL draw comes out at the wrong place or not at all.
 */
final class HudGl {

    private HudGl() {}

    /** Make sure nothing of Fathom's is queued before raw drawing. */
    static void flushPending() {
        Gfx.flushHudOverlay();
    }

    /**
     * Start a run of sprites from one sheet.
     *
     * <p>Every sprite in a run shares one texture bind, one state change and one
     * draw call. The potion list draws one icon per effect, and a draw call each
     * was the most expensive thing on a screen with five effects.
     */
    static void spritesBegin(MinecraftClient client, Identifier sheet, int alpha) {
        flushPending();
        client.getTextureManager().bindTexture(sheet);
        GlStateManager.enableTexture();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, Math.max(0, Math.min(255, alpha)) / 255f);
        Tessellator.getInstance().getBuffer().begin(7, VertexFormats.POSITION_TEXTURE);
    }

    /** One sprite inside a run. Coordinates as {@link #sprite}. */
    static void spriteQuad(float x, float y, float width, float height,
            int u, int v, int spriteWidth, int spriteHeight) {
        final float unit = 1f / 256f;
        BufferBuilder buffer = Tessellator.getInstance().getBuffer();
        buffer.vertex(x, y + height, 0d).texture(u * unit, (v + spriteHeight) * unit).next();
        buffer.vertex(x + width, y + height, 0d)
                .texture((u + spriteWidth) * unit, (v + spriteHeight) * unit).next();
        buffer.vertex(x + width, y, 0d).texture((u + spriteWidth) * unit, v * unit).next();
        buffer.vertex(x, y, 0d).texture(u * unit, v * unit).next();
    }

    /** Draw the run and put the state back. */
    static void spritesEnd() {
        Tessellator.getInstance().draw();
        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend();
    }

    /**
     * Start a run of items: the GUI item lighting the hotbar sets up, once for
     * however many items follow, rather than once each.
     */
    static void itemsBegin() {
        flushPending();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        DiffuseLighting.enable();
    }

    /** One item inside a run. */
    static void itemQuad(MinecraftClient client, ItemStack stack, int x, int y, boolean overlay) {
        if (stack == null) {
            return;
        }
        ItemRenderer renderer = client.getItemRenderer();
        if (renderer == null) {
            return;
        }
        renderer.renderInGuiWithOverrides(stack, x, y);
        if (overlay && client.textRenderer != null) {
            renderer.renderGuiItemOverlay(client.textRenderer, stack, x, y);
        }
    }

    /** End a run of items, leaving the state as it was found. */
    static void itemsEnd() {
        DiffuseLighting.disable();
        GlStateManager.disableRescaleNormal();
        GlStateManager.disableBlend();
        GlStateManager.color(1f, 1f, 1f, 1f);
    }

    /**
     * A sprite from a 256×256 sheet.
     *
     * @param u left edge in the sheet, in pixels
     * @param v top edge in the sheet, in pixels
     * @param alpha 0-255, so a widget's opacity reaches its icons
     */
    static void sprite(MinecraftClient client, Identifier sheet, float x, float y,
            float width, float height, int u, int v, int spriteWidth, int spriteHeight, int alpha) {
        if (client == null || client.getTextureManager() == null || alpha <= 0) {
            return;
        }
        flushPending();
        client.getTextureManager().bindTexture(sheet);
        GlStateManager.enableTexture();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, alpha / 255f);

        final float unit = 1f / 256f;
        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(7, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(x, y + height, 0d).texture(u * unit, (v + spriteHeight) * unit).next();
        buffer.vertex(x + width, y + height, 0d)
                .texture((u + spriteWidth) * unit, (v + spriteHeight) * unit).next();
        buffer.vertex(x + width, y, 0d).texture((u + spriteWidth) * unit, v * unit).next();
        buffer.vertex(x, y, 0d).texture(u * unit, v * unit).next();
        tessellator.draw();

        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend();
    }

    /**
     * A quad of whatever texture is already bound, drawn whole.
     *
     * <p>For a resource pack icon: Minecraft owns that texture and binds it
     * itself, so the caller binds and this only draws.
     */
    static void boundTexture(float x, float y, float width, float height, int alpha) {
        if (alpha <= 0) {
            return;
        }
        GlStateManager.enableTexture();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        GlStateManager.color(1f, 1f, 1f, alpha / 255f);

        Tessellator tessellator = Tessellator.getInstance();
        BufferBuilder buffer = tessellator.getBuffer();
        buffer.begin(7, VertexFormats.POSITION_TEXTURE);
        buffer.vertex(x, y + height, 0d).texture(0d, 1d).next();
        buffer.vertex(x + width, y + height, 0d).texture(1d, 1d).next();
        buffer.vertex(x + width, y, 0d).texture(1d, 0d).next();
        buffer.vertex(x, y, 0d).texture(0d, 0d).next();
        tessellator.draw();

        GlStateManager.color(1f, 1f, 1f, 1f);
        GlStateManager.disableBlend();
    }

    /**
     * One item, the way the hotbar draws it.
     *
     * @param overlay whether to add vanilla's stack count and damage bar
     */
    static void item(MinecraftClient client, ItemStack stack, int x, int y, boolean overlay) {
        if (client == null || stack == null) {
            return;
        }
        ItemRenderer renderer = client.getItemRenderer();
        if (renderer == null || client.textRenderer == null) {
            return;
        }
        flushPending();
        GlStateManager.enableRescaleNormal();
        GlStateManager.enableBlend();
        GlStateManager.blendFuncSeparate(770, 771, 1, 0);
        DiffuseLighting.enable();
        try {
            renderer.renderInGuiWithOverrides(stack, x, y);
            if (overlay) {
                renderer.renderGuiItemOverlay(client.textRenderer, stack, x, y);
            }
        } finally {
            DiffuseLighting.disable();
            GlStateManager.disableRescaleNormal();
            GlStateManager.disableBlend();
            GlStateManager.color(1f, 1f, 1f, 1f);
        }
    }
}
