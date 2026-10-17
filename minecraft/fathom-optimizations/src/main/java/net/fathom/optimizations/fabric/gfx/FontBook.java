package net.fathom.optimizations.fabric.gfx;

import java.awt.Font;
import java.io.InputStream;
import java.util.HashMap;
import java.util.Map;

import net.fathom.optimizations.core.ui.render.Type;

/**
 * Geist, the launcher's typeface, in the game.
 *
 * <h2>Why this font and not Minecraft's</h2>
 *
 * <p>Minecraft's font is a 1-bit bitmap on an 8-pixel cell. It is a good font
 * for Minecraft and an impossible one for an interface: it has one size, no
 * anti-aliasing and no weights, so a hierarchy has to be faked with colour
 * alone, and at any GUI scale above 1 it is a magnified bitmap. Every "extremely
 * pixelated typography" complaint about the first hub is that font, magnified.
 *
 * <p>Geist is what the launcher uses. The two halves of Fathom are supposed to
 * be one product, and typography is the first thing that gives that away.
 *
 * <h2>Where the files come from</h2>
 *
 * <p>{@code assets/fathom/font/Geist-*.ttf} are static instances derived from
 * the same variable font the launcher ships, by {@code tools/derive-ui-font.py}.
 * Not downloaded separately: there is one Geist in this repository and these are
 * three weights of it, subset to the characters an interface draws. SIL Open
 * Font License 1.1, which travels with them.
 *
 * <h2>If the font cannot be loaded</h2>
 *
 * <p>The interface falls back to the JDK's logical sans-serif rather than
 * failing. It will not look like Fathom, but it will be legible and
 * anti-aliased, and a menu that renders in the wrong font beats a menu that
 * throws while the player is in a world.
 */
final class FontBook {

    /** One sheet for every size and weight in use at once. 1024² is ample. */
    private static final int SHEET = 1024;

    /**
     * How many size-and-weight combinations are looked up without allocating.
     *
     * <p>Every string drawn asks for a page, so this is on the hottest path in
     * the renderer. Building a {@code String} key per call was a megabyte a
     * second of garbage for nothing: an eight-slot linear scan over ints covers
     * every combination a screen actually uses, and a screen that uses more
     * falls back to the map rather than failing.
     */
    private static final int CACHE = 8;

    private final Map<String, Font> families = new HashMap<String, Font>();
    private final Map<String, GlyphPage> pages = new HashMap<String, GlyphPage>();
    private final int[] recentKeys = new int[CACHE];
    private final GlyphPage[] recentPages = new GlyphPage[CACHE];
    private int recentNext;
    private final Atlas atlas;
    private boolean loaded;
    private boolean usingFallback;

    FontBook(Atlas.BeforeUpload beforeUpload) {
        this.atlas = new Atlas(SHEET, beforeUpload);
    }

    Atlas atlas() {
        return atlas;
    }

    boolean usingFallback() {
        return usingFallback;
    }

    /** How many size/weight combinations are currently rasterised. */
    int pageCount() {
        return pages.size();
    }

    void create() {
        atlas.create();
        load();
    }

    /** Throw away every raster. For a changed UI scale. */
    void reset() {
        pages.clear();
        forgetRecent();
        atlas.delete();
        atlas.create();
    }

    void delete() {
        pages.clear();
        forgetRecent();
        atlas.delete();
    }

    private void forgetRecent() {
        for (int slot = 0; slot < CACHE; slot++) {
            recentKeys[slot] = 0;
            recentPages[slot] = null;
        }
        recentNext = 0;
    }

    /**
     * The page for a piece of type at a device scale.
     *
     * @param scale device pixels per logical unit
     */
    GlyphPage page(Type type, float scale) {
        int pixels = Math.max(6, Math.round(type.size() * scale));
        int key = pixels * 8 + type.weight().ordinal() + 1;
        for (int slot = 0; slot < CACHE; slot++) {
            if (recentKeys[slot] == key) {
                return recentPages[slot];
            }
        }
        GlyphPage page = pages.get(type.weight().name() + '/' + pixels);
        if (page == null) {
            load();
            page = new GlyphPage(family(type.weight()), pixels, atlas);
            pages.put(type.weight().name() + '/' + pixels, page);
        }
        recentKeys[recentNext] = key;
        recentPages[recentNext] = page;
        recentNext = (recentNext + 1) % CACHE;
        return page;
    }

    private Font family(Type.Weight weight) {
        Font font = families.get(weight.name());
        return font == null ? families.get(Type.Weight.REGULAR.name()) : font;
    }

    private void load() {
        if (loaded) {
            return;
        }
        loaded = true;
        boolean any = false;
        any |= read(Type.Weight.REGULAR, "Geist-Regular.ttf");
        any |= read(Type.Weight.MEDIUM, "Geist-Medium.ttf");
        any |= read(Type.Weight.SEMIBOLD, "Geist-SemiBold.ttf");
        // Geist Mono, for values that change while visible. Missing, a mono
        // weight falls back to the sans family of the same weight.
        read(Type.Weight.MONO_REGULAR, "GeistMono-Regular.ttf");
        read(Type.Weight.MONO_MEDIUM, "GeistMono-Medium.ttf");
        if (!any) {
            usingFallback = true;
            families.put(Type.Weight.REGULAR.name(), new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            families.put(Type.Weight.MEDIUM.name(), new Font(Font.SANS_SERIF, Font.PLAIN, 16));
            families.put(Type.Weight.SEMIBOLD.name(), new Font(Font.SANS_SERIF, Font.BOLD, 16));
        }
    }

    private boolean read(Type.Weight weight, String file) {
        InputStream stream = FontBook.class.getResourceAsStream("/assets/fathom/font/" + file);
        if (stream == null) {
            return false;
        }
        try {
            Font font = Font.createFont(Font.TRUETYPE_FONT, stream);
            families.put(weight.name(), font);
            return true;
        } catch (Exception failure) {
            // Deliberately broad: a font file that a JDK refuses can throw
            // FontFormatException, IOException, or something from inside the
            // font system, and the answer to all of them is the same.
            return false;
        } finally {
            try {
                stream.close();
            } catch (Exception ignored) {
                // Nothing useful to do about a stream that will not close.
            }
        }
    }
}
