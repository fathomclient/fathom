package net.fathom.optimizations.core.ui.layout;

import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * Fitting text into a space, using the measurement that will actually draw it.
 *
 * <h2>The clipping bug this exists to end</h2>
 *
 * <p>Text was clipped in the first hub because two different things had opinions
 * about how wide it was: the bitmap font's integer {@code getStringWidth}, and
 * the transform that then scaled the result. Anything that fits by one
 * measurement and is drawn by another will eventually be cut off, and no amount
 * of subtracting a safety margin fixes it — it just moves the failure to a
 * different string.
 *
 * <p>So every function here takes the {@link Renderer} that is going to draw the
 * text and asks <em>it</em>. There is one measurement, it is the drawing
 * measurement, and it is in the same fractional units.
 *
 * <h2>Ellipsis is requested, never automatic</h2>
 *
 * <p>{@link #fit} truncates because the caller asked it to. A label that is too
 * long and was not passed through here draws past its box, visibly, which is a
 * bug you can see and fix rather than one that silently eats a word.
 */
public final class Text {

    /** What a truncated string ends with. One character, not three dots. */
    public static final String ELLIPSIS = "…";

    private Text() {}

    /**
     * The string, or as much of it as fits with an ellipsis.
     *
     * <p>Returns the original when it fits — no allocation, no ellipsis, no
     * "almost fits" fudge.
     */
    public static String fit(Renderer renderer, String text, Type type, float maxWidth) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        if (maxWidth <= 0f) {
            return "";
        }
        if (renderer.measure(text, type) <= maxWidth) {
            return text;
        }
        float ellipsis = renderer.measure(ELLIPSIS, type);
        if (ellipsis > maxWidth) {
            // Not even the ellipsis fits. Drawing one anyway would overflow the
            // box to say "there is text here", which is worse than silence.
            return "";
        }
        // Binary search rather than a character-at-a-time walk: a 200-character
        // description in a 90-unit box is eight measurements instead of 190.
        int low = 0;
        int high = text.length();
        while (low < high) {
            int middle = (low + high + 1) >>> 1;
            if (renderer.measure(text.substring(0, middle), type) + ellipsis <= maxWidth) {
                low = middle;
            } else {
                high = middle - 1;
            }
        }
        if (low == 0) {
            return ELLIPSIS;
        }
        // Do not end on a space before the ellipsis; " …" reads as a pause.
        int end = low;
        while (end > 1 && text.charAt(end - 1) == ' ') {
            end--;
        }
        return text.substring(0, end) + ELLIPSIS;
    }

    /**
     * The first sentence of a longer piece of prose.
     *
     * <p>For a row that has one line and a registry entry that has a paragraph.
     * The optimization registry documents what each entry does and why, at
     * length, because that is where the reasoning belongs; a row in the
     * Performance list has room for the claim and not for the argument. Taking
     * the first sentence keeps the two from drifting apart the way a second,
     * hand-written short description would.
     *
     * <p>A full stop only ends a sentence when a space follows it, so decimals
     * and "1.8.9" survive. Returns the whole string when it is already one
     * sentence.
     */
    public static String firstSentence(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        for (int index = 0; index < text.length() - 2; index++) {
            if (text.charAt(index) == '.' && text.charAt(index + 1) == ' ') {
                return text.substring(0, index + 1);
            }
        }
        return text;
    }

    /** Whether {@link #fit} would have to cut this. */
    public static boolean overflows(Renderer renderer, String text, Type type, float maxWidth) {
        return text != null && !text.isEmpty() && renderer.measure(text, type) > maxWidth;
    }

    /**
     * Break a string into lines that each fit.
     *
     * <p>On spaces where it can, mid-word where a single word is longer than the
     * box. Never returns an empty list for a non-empty string.
     */
    public static List<String> wrap(Renderer renderer, String text, Type type, float maxWidth) {
        List<String> lines = new ArrayList<String>();
        if (text == null || text.isEmpty() || maxWidth <= 0f) {
            return lines;
        }
        int start = 0;
        int lastSpace = -1;
        for (int index = 0; index <= text.length(); index++) {
            if (index < text.length() && text.charAt(index) == ' ') {
                lastSpace = index;
            }
            boolean end = index == text.length();
            if (!end && renderer.measure(text.substring(start, index + 1), type) <= maxWidth) {
                continue;
            }
            if (end) {
                if (start < text.length()) {
                    lines.add(text.substring(start));
                }
                break;
            }
            int cut = lastSpace > start ? lastSpace : index;
            if (cut <= start) {
                // One character is already too wide. Emit it rather than loop.
                cut = start + 1;
            }
            lines.add(text.substring(start, cut));
            start = cut;
            while (start < text.length() && text.charAt(start) == ' ') {
                start++;
            }
            index = start - 1;
            lastSpace = -1;
        }
        return lines;
    }
}
