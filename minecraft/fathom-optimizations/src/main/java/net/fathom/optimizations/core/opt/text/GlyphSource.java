package net.fathom.optimizations.core.opt.text;

/**
 * The font's own opinion of how wide a character is.
 *
 * <p>This interface is the seam between our code and Minecraft's. 1.8.9's
 * {@code FontRenderer} holds two width tables — a byte array for the ASCII page and a
 * packed nibble array for the unicode pages — and consults the active resource pack to
 * decide which applies. None of that is our business. We need one number per character,
 * and asking for exactly that keeps this package compilable and testable without the game.
 *
 * <p>A test implementation lives in the test tree. The real implementation is three lines
 * of Mixin accessor.
 */
public interface GlyphSource {

    /**
     * The advance width of {@code c} in pixels, excluding the bold overdraw.
     *
     * <p>Fractional on purpose: unicode glyph widths in 1.8.9 are computed from packed
     * start/end columns and divided down, and rounding per character rather than per
     * string is one of the ways a reimplementation drifts away from vanilla's answer.
     *
     * <p>Return {@code 0} for a character the font does not draw. Do <em>not</em> return a
     * negative sentinel for the section sign the way {@code FontRenderer.getCharWidth}
     * does; format-code handling belongs to {@link TextMeasurer}, which can see the
     * following character and therefore knows whether a section sign actually starts a
     * code.
     */
    float advance(char c);

    /**
     * A value that changes whenever the answers above would change.
     *
     * <p>Resource-pack reloads swap the font. A cache keyed on the string alone would
     * happily serve widths from the previous pack, and text would be laid out against a
     * font that is no longer loaded. Every cache in this package keys on this as well,
     * so a stale entry is impossible rather than merely unlikely.
     */
    int generation();
}
