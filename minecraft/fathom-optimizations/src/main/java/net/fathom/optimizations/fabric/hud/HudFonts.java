package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.font.TextRenderer;

import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.core.ui.render.Renderer;

/**
 * The two fonts a HUD widget can be drawn in.
 *
 * @see HudFont
 */
public final class HudFonts {

    private HudFonts() {}

    /** Minecraft's own bitmap font, in its own units. */
    public static HudFont vanilla(TextRenderer font) {
        // Reused while the game keeps the same renderer, so the HUD does not
        // allocate a wrapper every frame and per-frame size memos keyed on the
        // font stay valid across the frame.
        Vanilla cached = lastVanilla;
        if (cached == null || cached.font != font) {
            cached = new Vanilla(font);
            lastVanilla = cached;
        }
        return cached;
    }

    private static Vanilla lastVanilla;

    /**
     * Geist, rasterised at framebuffer resolution and measured back into
     * Minecraft's scaled pixels.
     *
     * @param mcToLogical Fathom logical units per Minecraft scaled pixel
     */
    public static HudFont fathom(Renderer renderer, float mcToLogical) {
        Fathom cached = lastFathom;
        if (cached == null || cached.renderer != renderer
                || Float.floatToIntBits(cached.requestedScale) != Float.floatToIntBits(mcToLogical)) {
            cached = new Fathom(renderer, mcToLogical);
            lastFathom = cached;
        }
        return cached;
    }

    private static Fathom lastFathom;

    private static final class Vanilla implements HudFont {

        private final TextRenderer font;

        Vanilla(TextRenderer font) {
            this.font = font;
        }

        @Override
        public float width(String text) {
            return font.getStringWidth(text);
        }

        @Override
        public float lineHeight() {
            return font.fontHeight;
        }

        @Override
        public void draw(String text, float x, float top, int argb, boolean shadow) {
            if (shadow) {
                font.drawWithShadow(text, x, top, argb);
            } else {
                font.draw(text, x, top, argb, false);
            }
        }

        @Override
        public boolean fathom() {
            return false;
        }

        @Override
        public float capHeight() {
            // Minecraft's glyphs are drawn from the top of an eight-pixel cell;
            // capitals fill the top seven rows and the eighth is descender.
            return 7f;
        }

        @Override
        public float capTop() {
            return 0f;
        }
    }

    /**
     * Geist at the size that matches what it replaces.
     *
     * <h2>Choosing the size</h2>
     *
     * <p>Minecraft's font draws on an eight-pixel cell in scaled-GUI space. A
     * face chosen in Fathom's logical units has to end up occupying that same
     * eight scaled pixels, or every widget's plate, every saved position and
     * every snap in the editor would move the day the font changed.
     *
     * <p>So the size is derived rather than picked: eight scaled pixels
     * converted into logical units, rounded to a whole number. Rounding matters
     * — the glyph sheet is cached per size, and a size that drifted with the
     * window would rasterise a new one on every resize frame. Whole numbers mean
     * a handful of sheets across every window size and GUI scale a player will
     * ever use.
     *
     * <h2>Medium, not regular</h2>
     *
     * <p>The hub's body text sits on Fathom's own near-black panel. This sits on
     * whatever the player happens to be looking at, which includes snow at noon.
     * The plate does most of that work; the extra weight does the rest.
     */
    private static final class Fathom implements HudFont {

        /** Minecraft's glyph cell, in scaled pixels. The size to match. */
        private static final float CELL = 8f;

        private final Renderer renderer;
        private final float requestedScale;
        private final float mcToLogical;
        private final Type type;

        Fathom(Renderer renderer, float mcToLogical) {
            this.renderer = renderer;
            this.requestedScale = mcToLogical;
            this.mcToLogical = Math.max(0.0001f, mcToLogical);
            this.type = Type.BODY_STRONG.at(
                    Math.max(6, Math.round(CELL * this.mcToLogical)));
        }

        @Override
        public float width(String text) {
            return renderer.measure(text, type) / mcToLogical;
        }

        @Override
        public float lineHeight() {
            return renderer.lineHeight(type) / mcToLogical;
        }

        @Override
        public void draw(String text, float x, float top, int argb, boolean shadow) {
            float left = x * mcToLogical;
            float baseline = top * mcToLogical + renderer.ascent(type);
            if (shadow) {
                // Minecraft's own shadow: the same colour at a quarter
                // brightness, one scaled pixel down and right. Matching it means
                // switching fonts changes the letterforms and nothing else.
                int dark = (argb & 0xFF000000) | ((argb & 0xFCFCFC) >> 2);
                renderer.text(text, left + mcToLogical, baseline + mcToLogical, type, dark);
            }
            renderer.text(text, left, baseline, type, argb);
            // Not flushed here. Fathom's renderer batches into the game's one
            // shared Tessellator, and so does everything else the HUD draws, so
            // a queue must not survive across vanilla drawing or across a change
            // of modelview. Both of those have an owner: Plate flushes before it
            // draws, and the HUD flushes at the end of each widget. Flushing per
            // string as well only costs draw calls — a four-line coordinate
            // readout is one quad batch, not four.
        }

        @Override
        public boolean fathom() {
            return true;
        }

        /** Geist's capitals are 0.71 of its em, which is the type size. */
        private static final float CAP_RATIO = 0.71f;

        @Override
        public float capHeight() {
            return type.size() * CAP_RATIO / mcToLogical;
        }

        @Override
        public float capTop() {
            return (renderer.ascent(type) - type.size() * CAP_RATIO) / mcToLogical;
        }
    }
}
