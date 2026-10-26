package net.fathom.optimizations.fabric.hub;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.texture.Sprite;
import net.minecraft.client.texture.SpriteAtlasTexture;
import net.minecraft.client.texture.Texture;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.item.Item;
import net.minecraft.util.Identifier;

import org.lwjgl.opengl.GL11;

import net.fathom.optimizations.core.ui.render.Renderer;

/**
 * The game's own art, drawn in the Hub: item sprites from the block atlas,
 * status-effect icons from the inventory sheet, and text in Minecraft's
 * bitmap font. So a module preview shows the textures the HUD draws -- a
 * resource pack that restyles them restyles the preview -- rather than
 * stand-ins.
 *
 * <p>Nothing here renders through Minecraft: each is one textured rectangle
 * ({@link Renderer#sprite}) in the Hub's own batch, from the texture the game
 * already has loaded. The texture is looked up by name every call, so a
 * resource reload that replaces it is picked up on the next frame.
 */
final class NativeArt {

    private static final Identifier POTION_SHEET = new Identifier("textures/gui/container/inventory.png");
    private static final Identifier FONT = new Identifier("textures/font/ascii.png");
    private static final int WHITE = 0xFFFFFFFF;

    private NativeArt() {}

    /** The GL name of a game texture, loading it the way the game would if it is not yet. 0 when unavailable. */
    private static int texture(Identifier id) {
        MinecraftClient client = MinecraftClient.getInstance();
        TextureManager manager = client == null ? null : client.getTextureManager();
        if (manager == null) {
            return 0;
        }
        Texture texture = manager.getTexture(id);
        if (texture == null) {
            // First use: bindTexture loads it. Put the binding back, so the
            // frame's state is as it was.
            int previous = GL11.glGetInteger(GL11.GL_TEXTURE_BINDING_2D);
            manager.bindTexture(id);
            com.mojang.blaze3d.platform.GlStateManager.bindTexture(previous);
            texture = manager.getTexture(id);
        }
        return texture == null ? 0 : texture.getGlId();
    }

    /** One item's sprite, as the hotbar shows it, {@code size} units square. @return whether it drew */
    static boolean item(Renderer ui, Item item, float x, float y, float size, float alpha) {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.getItemRenderer() == null || item == null) {
            return false;
        }
        Sprite sprite = client.getItemRenderer().getModels().getSprite(item);
        int atlas = texture(SpriteAtlasTexture.BLOCK_ATLAS_TEX);
        if (sprite == null || atlas == 0) {
            return false;
        }
        ui.sprite(atlas, x, y, x + size, y + size, sprite.getMinU(), sprite.getMinV(), sprite.getMaxU(),
                sprite.getMaxV(), argb(alpha, WHITE));
        return true;
    }

    /** A status effect's icon from the inventory sheet, 18 pixels in the sheet. @return whether it drew */
    static boolean effect(Renderer ui, StatusEffect effect, float x, float y, float size, float alpha) {
        int sheet = texture(POTION_SHEET);
        if (effect == null || !effect.hasIcon() || sheet == 0) {
            return false;
        }
        int level = effect.getIconLevel();
        float u = (level % 8 * 18) / 256f;
        float v = (198 + level / 8 * 18) / 256f;
        ui.sprite(sheet, x, y, x + size, y + size, u, v, u + 18f / 256f, v + 18f / 256f, argb(alpha, WHITE));
        return true;
    }

    /** Whether text can be drawn in Minecraft's bitmap font: not while the game is forced to its Unicode font. */
    static boolean fontAvailable() {
        MinecraftClient client = MinecraftClient.getInstance();
        return client != null && client.textRenderer != null && client.options != null
                && !client.options.forcesUnicodeFont && texture(FONT) != 0;
    }

    /**
     * A font scale that lands every font pixel on whole device pixels: the
     * nearest whole multiple of {@code wanted}, so the glyphs stay sharp.
     */
    static float crisp(Renderer ui, float wanted) {
        float device = Math.max(1f, Math.round(wanted * ui.scale()));
        return device / ui.scale();
    }

    /** How wide {@code text} is in Minecraft's font at {@code scale} units per font pixel. */
    static float width(String text, float scale) {
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        return font == null ? 0f : font.getStringWidth(text) * scale;
    }

    /** The font's line height (9 font pixels) at {@code scale}. */
    static float lineHeight(float scale) {
        return 9f * scale;
    }

    /**
     * Text in Minecraft's font, its top at {@code top}, with the font's own
     * one-pixel shadow when asked: printable ASCII from {@code ascii.png},
     * each glyph as wide as the font says. Anything else is left out.
     */
    static void text(Renderer ui, String text, float x, float top, float scale, int argb, boolean shadow) {
        TextRenderer font = MinecraftClient.getInstance().textRenderer;
        int sheet = texture(FONT);
        if (font == null || sheet == 0 || text == null) {
            return;
        }
        if (shadow) {
            int shade = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
            glyphs(ui, font, sheet, text, x + scale, top + scale, scale, shade);
        }
        glyphs(ui, font, sheet, text, x, top, scale, argb);
    }

    private static void glyphs(Renderer ui, TextRenderer font, int sheet, String text, float x, float top,
            float scale, int argb) {
        float cursor = x;
        for (int index = 0; index < text.length(); index++) {
            char c = text.charAt(index);
            int advance = font.getCharWidth(c);
            if (c > ' ' && c < 127 && advance > 0) {
                float u = (c % 16 * 8) / 128f;
                float v = (c / 16 * 8) / 128f;
                float glyph = advance - 1f;
                ui.sprite(sheet, cursor, top, cursor + glyph * scale, top + 8f * scale,
                        u, v, u + (glyph - 0.01f) / 128f, v + 7.99f / 128f, argb);
            }
            cursor += Math.max(0, advance) * scale;
        }
    }

    private static int argb(float alpha, int rgb) {
        int a = Math.max(0, Math.min(255, Math.round(alpha * 255f)));
        return (a << 24) | (rgb & 0xFFFFFF);
    }
}
