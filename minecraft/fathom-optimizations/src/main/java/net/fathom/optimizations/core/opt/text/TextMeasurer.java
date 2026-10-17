package net.fathom.optimizations.core.opt.text;

/**
 * How wide a string is, computed the way vanilla computes it.
 *
 * <p>This is the reference implementation — the "before" in every before-and-after. It is
 * deliberately not fast. Its job is to be <em>right</em>, so that
 * {@link TextMetricsCache} has something to be checked against.
 *
 * <h2>Why we reimplement rather than call vanilla</h2>
 *
 * <p>We need a copy of this that runs without Minecraft loaded, or the cache cannot be
 * differentially tested outside the game — and a cache that has never been checked against
 * the thing it is caching is not a cache, it is a source of misaligned text.
 *
 * <p>What is reproduced here is the <em>observable rule</em>, which is short enough to
 * state in prose and which any correct implementation must follow: walk the string; a
 * section sign followed by any character is a formatting code, contributes no width, and
 * sets or clears bold; every other character contributes its font advance, plus one pixel
 * if bold is currently set. That rule is a compatibility requirement — text that measures
 * differently from vanilla is text that renders in the wrong place — not a design choice
 * of ours.
 *
 * <h2>What this costs vanilla</h2>
 *
 * <p>Nothing, per call. The problem is the call count. {@code getStringWidth} is invoked
 * from centring, wrapping, trimming and the scoreboard, chat and tab-list layout paths,
 * every frame, for strings that overwhelmingly did not change since the last frame. The
 * work is O(length) and repeated at frame rate over a corpus that is nearly static.
 */
public final class TextMeasurer {

    private final GlyphSource font;

    public TextMeasurer(GlyphSource font) {
        if (font == null) {
            throw new IllegalArgumentException("font");
        }
        this.font = font;
    }

    /**
     * Vanilla's width for {@code text}, in pixels.
     *
     * <p>{@code null} measures zero rather than throwing, matching vanilla — several call
     * sites rely on it.
     */
    public int width(String text) {
        if (text == null) {
            return 0;
        }
        float total = 0f;
        boolean bold = false;
        int i = 0;
        int length = text.length();
        while (i < length) {
            char c = text.charAt(i);
            if (c == SECTION && i + 1 < length) {
                char code = Character.toLowerCase(text.charAt(i + 1));
                if (code == 'l') {
                    bold = true;
                } else if (code == 'r' || isColour(code)) {
                    // A colour code resets every style, bold included. This is the part a
                    // naive implementation gets wrong, and it is visible: a bold word
                    // followed by a colour change measures one pixel per character too
                    // wide for the rest of the line.
                    bold = false;
                }
                i += 2;
                continue;
            }
            float advance = font.advance(c);
            total += advance;
            if (bold && advance > 0f) {
                // The bold pass draws the glyph a second time one pixel to the right, so
                // it occupies one more pixel. A zero-width character has no second draw
                // and so gains nothing.
                total += 1f;
            }
            i++;
        }
        return (int) total;
    }

    /** Whether {@code lowercase} is one of the sixteen colour codes. */
    static boolean isColour(char lowercase) {
        return (lowercase >= '0' && lowercase <= '9') || (lowercase >= 'a' && lowercase <= 'f');
    }

    /** The formatting-code introducer, U+00A7. */
    public static final char SECTION = '\u00a7';
}
