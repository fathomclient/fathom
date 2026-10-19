package net.fathom.optimizations.fabric.perf;

import java.util.Locale;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.texture.TextureManager;
import net.minecraft.util.Identifier;

import net.fathom.optimizations.core.registry.OptimizationId;
import net.fathom.optimizations.fabric.Runtime;

/**
 * {@code render.font.glyph-page-preload}: the font's symbol pages loaded with
 * the font, not the first time a label needs them.
 *
 * <h2>The stall</h2>
 *
 * <p>1.8.9's font draws anything outside its ASCII sheet from a 256x256 page of
 * the unicode font, and binds that page through
 * {@code TextureManager.bindTexture}. The first bind of a page has no texture
 * yet, so the manager makes one there and then: a PNG read and decoded on the
 * render thread, inside the frame that wanted the glyph. Lobby labels are
 * exactly that text -- stars, hearts, arrows, bullets, ticks -- so the first
 * time a hologram comes into view the frame stops for one decode per new page.
 * Later frames find the texture and pay nothing.
 *
 * <h2>The change</h2>
 *
 * <p>The pages lobbies draw from are bound once while the font loads (the
 * loading screen) instead. Same identifiers the font builds, so its own later
 * binds find the very same texture objects; the texture manager reloads them
 * with every other texture on a resource reload, as it would have once they
 * were used. Nothing is drawn differently. The cost is the pages' memory, 256
 * KB each, for pages a session might never have needed.
 *
 * <p>Through the texture manager rather than the font's private page method,
 * so a font OptiFine has patched cannot turn this into a failed Mixin.
 */
public final class GlyphPages {

    private GlyphPages() {}

    private static final OptimizationId ID = OptimizationId.of("render.font.glyph-page-preload");

    /**
     * General punctuation (bullets, daggers), arrows, mathematical operators,
     * box shapes (circles, squares, triangles), miscellaneous symbols (stars,
     * suns, card suits), dingbats (ticks, crosses, hearts, sparkles, heavy
     * arrows) and the extra shapes (large star and squares).
     */
    static final int[] PAGES = {0x20, 0x21, 0x22, 0x25, 0x26, 0x27, 0x2B};

    /** A font has loaded since the pages were last bound. */
    private static boolean wanted;
    private static int frames;

    /** What the last preload cost, for Diagnostics and the log. */
    public static double lastMillis = -1d;

    /** The font loaded or reloaded. Preloads now if the switch can already be read. */
    public static void fontLoaded() {
        wanted = true;
        preloadIfOn();
    }

    /**
     * Once a frame. Picks up a font that loaded before the switches were
     * known, and a switch turned on later; checked every 64 frames rather
     * than every frame, since nothing here is urgent.
     */
    public static void frame() {
        if (wanted && (frames++ & 63) == 0) {
            preloadIfOn();
        }
    }

    private static void preloadIfOn() {
        if (!Runtime.ready() || !Runtime.switches().isEnabled(ID)) {
            return;
        }
        MinecraftClient client = MinecraftClient.getInstance();
        TextureManager textures = client == null ? null : client.getTextureManager();
        if (textures == null) {
            return;
        }
        wanted = false;
        long started = System.nanoTime();
        for (int page : PAGES) {
            // The font's own page identifier: "textures/font/unicode_page_%02x.png".
            textures.bindTexture(new Identifier(String.format(Locale.ROOT, "textures/font/unicode_page_%02x.png", page)));
        }
        lastMillis = (System.nanoTime() - started) / 1_000_000d;
        System.out.println(String.format(Locale.ROOT, "[Fathom] Preloaded %d symbol glyph pages in %.1f ms.",
                PAGES.length, lastMillis));
    }
}
