package net.fathom.optimizations.core.chat;

/**
 * Reading game chat for Auto GG and Bedwars Stats, without Minecraft.
 *
 * <p>Works on the unformatted text of one incoming line. Player chat has a
 * colon after the sender's name, and is never read as a game event, so
 * nobody can type a line that makes you say gg or counts as a kill.
 */
public final class GameChat {

    /** Lines Hypixel and most minigame servers print once when a game ends. */
    private static final String[] GAME_OVER = {
        "1st Killer - ",
        "1st Place - ",
        "1st Place: ",
        "Winner: ",
        "Winners: ",
        "Winning Team - ",
        "Winning Team: ",
        " won the game!",
        "Top Survivors",
        "Reward Summary",
    };

    private GameChat() {}

    /** Whether a line is a player talking rather than the server announcing. */
    public static boolean playerChat(String line) {
        int colon = line.indexOf(": ");
        if (colon < 0) {
            return false;
        }
        // "Winner: Red" and "1st Place: Name" are announcements; anything else
        // with a colon early in the line is somebody's message.
        String before = line.substring(0, colon).trim();
        return !before.equals("Winner") && !before.equals("Winners") && !before.endsWith("Place")
                && !before.equals("Winning Team");
    }

    /** Whether a line says the game just ended. */
    public static boolean gameOver(String line) {
        if (line == null || playerChat(line)) {
            return false;
        }
        String text = line.trim();
        for (String marker : GAME_OVER) {
            String wanted = marker.trim();
            if (marker.startsWith(" ") ? text.endsWith(wanted) : text.startsWith(wanted)) {
                return true;
            }
        }
        return false;
    }

    /** What a line means for your own stats. */
    public enum Event {
        NONE,
        KILL,
        FINAL_KILL,
        DEATH,
        FINAL_DEATH,
        BED
    }

    /**
     * Reads a kill, death or bed line naming {@code you}.
     *
     * <p>Kill lines name the victim first and the killer last ("A was
     * knocked into the void by B."), with " FINAL KILL!" after a final kill.
     * Bed lines start "BED DESTRUCTION" and end with the breaker's name.
     */
    public static Event read(String line, String you) {
        if (line == null || you == null || you.isEmpty() || playerChat(line)) {
            return Event.NONE;
        }
        String text = line.trim();
        if (text.startsWith("BED DESTRUCTION")) {
            return endsWithName(stripFinal(text), you) ? Event.BED : Event.NONE;
        }
        boolean finalKill = text.endsWith("FINAL KILL!");
        String body = stripFinal(text);
        if (!body.endsWith(".") && !body.endsWith("!")) {
            return Event.NONE;
        }
        String words = body.substring(0, body.length() - 1);
        if (words.startsWith(you + " ")) {
            return finalKill ? Event.FINAL_DEATH : Event.DEATH;
        }
        if (words.endsWith(" " + you) && words.indexOf(' ') < words.length() - you.length() - 1) {
            return finalKill ? Event.FINAL_KILL : Event.KILL;
        }
        return Event.NONE;
    }

    private static String stripFinal(String text) {
        return text.endsWith("FINAL KILL!") ? text.substring(0, text.length() - "FINAL KILL!".length()).trim() : text;
    }

    private static boolean endsWithName(String text, String you) {
        String body = text.endsWith("!") || text.endsWith(".") ? text.substring(0, text.length() - 1) : text;
        return body.endsWith(" " + you);
    }
}
