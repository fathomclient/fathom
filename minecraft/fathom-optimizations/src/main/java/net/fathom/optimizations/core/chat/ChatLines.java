package net.fathom.optimizations.core.chat;

import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.TimeZone;

/**
 * The Chat module's text, without Minecraft: timestamps and repeat counts.
 *
 * <h2>Where they are added</h2>
 *
 * <p>When a message arrives, once, before vanilla wraps it into lines and
 * stores it. Vanilla re-wraps the stored messages whenever the chat width or
 * scale changes, so anything added later — at draw time — would either be lost
 * on the first re-wrap or added a second time. Added on arrival, a timestamp is
 * part of the message from then on, and the chat replay cache sees ordinary
 * lines.
 */
public final class ChatLines {

    private ChatLines() {}

    /** {@code [14:05]}, {@code [2:05 PM]}, with seconds when asked for. */
    public static String stamp(long millis, boolean twelveHour, boolean seconds, TimeZone zone) {
        String pattern = (twelveHour ? "h:mm" : "HH:mm") + (seconds ? ":ss" : "") + (twelveHour ? " a" : "");
        SimpleDateFormat format = new SimpleDateFormat(pattern, Locale.ROOT);
        format.setTimeZone(zone == null ? TimeZone.getDefault() : zone);
        return "[" + format.format(new Date(millis)) + "]";
    }

    /** The suffix a repeated message carries: {@code (x3)}. Empty for the first. */
    public static String repeats(int count) {
        return count <= 1 ? "" : " (x" + count + ")";
    }

    /**
     * Counts consecutive identical messages.
     *
     * <p>Only the most recent message is compared, and only its visible text:
     * a message that differs only in colour is the same message to a reader,
     * and anything in between breaks the run, because folding a repeat into a
     * message that is no longer the newest would move it up the chat.
     */
    public static final class Repeats {
        private String last;
        private int count;

        /** Offer the next message; returns how many times in a row it has now arrived. */
        public int offer(String plain) {
            if (plain != null && plain.equals(last)) {
                count++;
            } else {
                last = plain;
                count = 1;
            }
            return count;
        }

        /** Forget the run: chat was cleared, or something else was added. */
        public void reset() {
            last = null;
            count = 0;
        }
    }
}
