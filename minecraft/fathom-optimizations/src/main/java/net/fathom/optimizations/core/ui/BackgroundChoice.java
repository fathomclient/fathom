package net.fathom.optimizations.core.ui;

import java.io.File;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import net.fathom.optimizations.core.profile.Json;
import net.fathom.optimizations.core.profile.ProfileException;

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

    private static final Pattern STANDARD_CAPITALIZATION =
            Pattern.compile("\"standardCapitalization\"\\s*:\\s*(true|false)");

    private BackgroundChoice() {}

    /**
     * Whether the launcher's "Standard capitalization" switch is on. Off -- the
     * launcher's default, and what an absent file means -- is lowercase.
     */
    public static boolean standardCapitalization(String json) {
        if (json == null) {
            return false;
        }
        Matcher found = STANDARD_CAPITALIZATION.matcher(json);
        return found.find() && "true".equals(found.group(1));
    }

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

    /** Whether {@code wanted} is one of the generated catalogue ids. */
    public static boolean approved(String wanted, String[] ids) {
        if (wanted == null || ids == null) {
            return false;
        }
        for (String id : ids) {
            if (id.equals(wanted)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Replace only the background in an appearance document.
     *
     * <p>The launcher owns other keys in this object. Keeping them here is the
     * sync contract: choosing a scene in-game cannot reset transparency,
     * blur, capitalization, or a future launcher preference. An unreadable
     * document is repaired to the smallest valid one.
     */
    @SuppressWarnings("unchecked")
    public static String updated(String json, String wanted, String[] ids) {
        if (!approved(wanted, ids)) {
            throw new IllegalArgumentException("unknown background: " + wanted);
        }
        Map<String, Object> stored = new LinkedHashMap<String, Object>();
        if (json != null) {
            try {
                Object parsed = Json.parse(json.replace("\ufeff", ""));
                if (parsed instanceof Map) {
                    stored.putAll((Map<String, Object>) parsed);
                }
            } catch (ProfileException unreadable) {
                // Repaired below. Appearance is cosmetic and must not strand
                // the picker because a hand-edited file is malformed.
            }
        }
        stored.put("background", wanted);
        stored.remove("animated_background");
        return Json.write(stored);
    }

    /**
     * Persist a choice to the launcher's shared appearance file atomically.
     * Returns false when no launcher root is known; callers keep the current
     * session usable and can surface the failure in the log.
     */
    public static boolean choose(File root, String wanted, String[] ids) {
        if (root == null || !approved(wanted, ids)) {
            return false;
        }
        try {
            Files.createDirectories(root.toPath());
            File target = new File(root, FILE);
            String old = target.isFile()
                    ? new String(Files.readAllBytes(target.toPath()), StandardCharsets.UTF_8)
                    : null;
            byte[] next = updated(old, wanted, ids).getBytes(StandardCharsets.UTF_8);
            File temporary = new File(root, FILE + ".tmp");
            Files.write(temporary.toPath(), next);
            try {
                Files.move(temporary.toPath(), target.toPath(),
                        StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary.toPath(), target.toPath(), StandardCopyOption.REPLACE_EXISTING);
            }
            return true;
        } catch (IOException | RuntimeException failed) {
            return false;
        }
    }
}
