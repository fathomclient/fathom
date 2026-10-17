package net.fathom.optimizations.fabric.perf;

import java.util.List;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.text.Text;

/**
 * {@code render.blockentity.sign-text-cache}: each sign's four lines, wrapped and
 * formatted once rather than every frame.
 *
 * <h2>What vanilla does</h2>
 *
 * <p>{@code SignBlockEntityRenderer.render} runs, for every line of every sign in view,
 * every frame: {@code Texts.wrapLines(line, 90, font, false, true)} -- which copies the
 * text component, walks it with Guava iterators, deep-copies each style and measures it
 * -- and then {@code asFormattedString()} on the first wrapped line, which walks it again
 * building the {@code §} codes. Both are pure functions of the line's text and the font.
 * In the multiplayer profile they were 40-45% of everything the render thread allocated
 * (`docs/performance/multiplayer-2026-10-13.md`). OptiFine's patched renderer keeps both
 * calls.
 *
 * <h2>What this keeps</h2>
 *
 * <p>Per sign, per line: the {@code Text} the sign held (by identity), the list the
 * original wrap returned for it, and the string the original {@code asFormattedString}
 * returned for that list's first element. A hit returns those very objects. Anything
 * else runs the original untouched:
 *
 * <ul>
 *   <li>a line whose {@code Text} object changed -- a sign update replaces the objects,
 *       and so does each keystroke while a sign is edited;
 *   <li>a different font, width or flags;
 *   <li>any font change ({@link #fontChanged}: a resource reload or the unicode toggle),
 *       since wrapping depends on glyph widths.
 * </ul>
 *
 * <p>Client thread only. With {@code -Dfathom.verify.caches=true} the original always
 * runs and every would-be hit is compared with it.
 */
public final class SignTexts {

    private SignTexts() {}

    /** Bumped whenever glyph widths may have changed. */
    private static int generation;

    public static long verified;
    public static long mismatches;

    /** A resource reload or the unicode toggle: every wrapped line may wrap differently. */
    public static void fontChanged() {
        generation++;
    }

    /** Implemented on {@code SignBlockEntity} by {@code SignBlockEntityTextsMixin}. */
    public interface Holder {
        Entry fathom$signTexts();
    }

    /** What one sign last wrapped and formatted. Lives on the sign; see the Mixin. */
    public static final class Entry {
        final Text[] source = new Text[4];
        @SuppressWarnings("unchecked")
        final List<Text>[] wrapped = new List[4];
        final Text[] first = new Text[4];
        final String[] formatted = new String[4];
        final TextRenderer[] font = new TextRenderer[4];
        final int[] width = new int[4];
        final int[] flags = new int[4];
        final int[] generation = new int[4];
    }

    /** Which line of the sign this text object is, by identity; -1 if none. */
    static int line(Text[] lines, Text text) {
        if (lines == null || text == null) {
            return -1;
        }
        int count = Math.min(lines.length, 4);
        for (int index = 0; index < count; index++) {
            if (lines[index] == text) {
                return index;
            }
        }
        return -1;
    }

    /**
     * The remembered wrap of {@code text}, or null to run the original.
     *
     * @param lines the sign's own text array
     */
    public static List<Text> wrapped(Entry entry, Text[] lines, Text text, int width, TextRenderer font,
            boolean a, boolean b) {
        int line = line(lines, text);
        if (line < 0 || entry.source[line] != text || entry.font[line] != font || entry.width[line] != width
                || entry.flags[line] != flags(a, b) || entry.generation[line] != generation) {
            return null;
        }
        return entry.wrapped[line];
    }

    /** Remember what the original wrap returned for {@code text}. */
    public static void rememberWrap(Entry entry, Text[] lines, Text text, int width, TextRenderer font, boolean a,
            boolean b, List<Text> result) {
        int line = line(lines, text);
        if (line < 0) {
            return;
        }
        if (VERIFY_ACTIVE && entry.source[line] == text && entry.font[line] == font && entry.width[line] == width
                && entry.flags[line] == flags(a, b) && entry.generation[line] == generation) {
            verified++;
            if (!sameTexts(entry.wrapped[line], result)) {
                mismatches++;
            }
            // Keep the original's list for the formatted-string check that follows.
        }
        entry.source[line] = text;
        entry.wrapped[line] = result;
        entry.first[line] = result != null && !result.isEmpty() ? result.get(0) : null;
        entry.formatted[line] = null;
        entry.font[line] = font;
        entry.width[line] = width;
        entry.flags[line] = flags(a, b);
        entry.generation[line] = generation;
    }

    /** The remembered formatted string of a wrapped first line, or null to run the original. */
    public static String formatted(Entry entry, Text wrappedFirst) {
        int line = line(entry.first, wrappedFirst);
        return line < 0 ? null : entry.formatted[line];
    }

    /** Remember what the original {@code asFormattedString} returned. */
    public static void rememberFormatted(Entry entry, Text wrappedFirst, String result) {
        int line = line(entry.first, wrappedFirst);
        if (line < 0) {
            return;
        }
        if (VERIFY_ACTIVE && entry.formatted[line] != null) {
            verified++;
            if (!entry.formatted[line].equals(result)) {
                mismatches++;
            }
        }
        entry.formatted[line] = result;
    }

    /** With verification on, nothing is ever served: the original always runs and is compared. */
    public static final boolean VERIFY_ACTIVE = NametagNames.VERIFY;

    private static int flags(boolean a, boolean b) {
        return (a ? 1 : 0) | (b ? 2 : 0);
    }

    /** Two wraps of the same line compare equal when their formatted strings do. */
    private static boolean sameTexts(List<Text> left, List<Text> right) {
        if (left == null || right == null) {
            return left == right;
        }
        if (left.size() != right.size()) {
            return false;
        }
        for (int index = 0; index < left.size(); index++) {
            if (!left.get(index).asFormattedString().equals(right.get(index).asFormattedString())) {
                return false;
            }
        }
        return true;
    }
}
