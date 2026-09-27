package net.fathom.optimizations.core.ui;

import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Which background the user chose in the launcher.
 *
 * <p>The launcher keeps it as {@code "background"} in {@code appearance.json},
 * in its data root -- the directory {@code ProfileLocation.platformRoot()}
 * already finds for profiles. The game's title screen, the Hub and every
 * Fathom-owned menu backdrop read the same value, so one choice is shown
 * everywhere. The HUD is gameplay and never reads it.
 *
 * <p>Parsed, not trusted: the value names a classpath resource, so anything
 * that is not one of the catalogue's ids becomes the default -- the same rule
 * the launcher's Rust allowlist applies.
 */
public final class BackgroundChoice {

    /** The launcher's file, relative to its data root. */
    public static final String FILE = "appearance.json";

    private static final Pattern BACKGROUND =
            Pattern.compile("\"background\"\\s*:\\s*\"([^\"\\\\]{1,64})\"");

    private BackgroundChoice() {}

    /**
     * The catalogue id {@code json} names, or {@code fallback} when it names
     * none, names one not in {@code ids}, or is not readable at all.
     */
    public static String resolve(String json, String[] ids, String fallback) {
        if (json == null || ids == null) {
            return fallback;
        }
        Matcher found = BACKGROUND.matcher(json);
        if (!found.find()) {
            return fallback;
        }
        String wanted = found.group(1).trim();
        for (String id : ids) {
            if (id.equals(wanted)) {
                return id;
            }
        }
        return fallback;
    }
}
