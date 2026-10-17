package net.fathom.optimizations.core.profile;

import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

/**
 * The three properties files, turned into profiles once and safely.
 *
 * <p>The Java half of {@code crates/launcher-core/src/loadout/migrate.rs}. The
 * game is where these files are actually found — including the development
 * client, which never has a launcher — so it migrates too, by the same rules.
 *
 * <h2>The rules</h2>
 *
 * <ol>
 *   <li><b>Back up first.</b> Every source is copied into
 *       {@code fathom/backups/<stamp>/} before a profile is written, and the
 *       originals are then left where they are.
 *   <li><b>Never delete what could not be mapped.</b> Unknown keys are reported
 *       and survive in the backup and in the original.
 *   <li><b>Never overwrite an edited Default.</b> A blank placeholder Default is
 *       filled in; one somebody has changed is kept, and the migrated data goes
 *       into {@code Default (imported)} beside it.
 *   <li><b>Once per game directory, per collection.</b> Recorded in
 *       {@code fathom/profiles-migrated}, one collection per line. A second run
 *       into the same collection does nothing, so it cannot produce a second
 *       Default; a different collection — the launcher's, after the game had
 *       only ever run standalone — still gets the data.
 * </ol>
 */
public final class ProfileMigration {

    /** Relative to the game directory. Must match {@code loadout::migrate}. */
    public static final String MARKER = "fathom/profiles-migrated";

    public static final String IMPORTED_NAME = "Default (imported)";

    /** What migration did. */
    public static final class Report {
        public final List<String> created = new ArrayList<String>();
        public final List<String> sources = new ArrayList<String>();
        public final List<String> unmapped = new ArrayList<String>();
        public File backup;
        public boolean alreadyDone;

        public String summary() {
            if (alreadyDone) {
                return "profiles already migrated";
            }
            if (created.isEmpty()) {
                return "nothing to migrate";
            }
            return "migrated " + sources.size() + " file(s) into " + created.size() + " profile(s): " + join(created);
        }
    }

    private ProfileMigration() {}

    public static Report run(ProfileStore store, File gameDirectory) throws ProfileException {
        Report report = new Report();
        File marker = new File(gameDirectory, MARKER);
        String key = collectionKey(store.directory());
        if (markerLists(marker, key)) {
            report.alreadyDone = true;
            return report;
        }

        File config = new File(gameDirectory, "config");
        File features = new File(config, "fathom-features.properties");
        File performance = new File(config, "fathom-performance.properties");
        File unnamedLayout = null;
        TreeMap<String, File> namedLayouts = new TreeMap<String, File>();
        File[] files = config.listFiles();
        if (files != null) {
            for (File file : files) {
                String name = file.getName();
                if (!file.isFile()) {
                    continue;
                }
                if ("fathom-hud.properties".equals(name)) {
                    unnamedLayout = file;
                } else if (name.startsWith("fathom-hud-") && name.endsWith(".properties")) {
                    namedLayouts.put(name.substring("fathom-hud-".length(), name.length() - ".properties".length()), file);
                }
            }
        }
        if (!features.isFile() && !performance.isFile() && unnamedLayout == null && namedLayouts.isEmpty()) {
            // Not recorded: a fresh install that later has files restored should
            // still get to migrate them.
            return report;
        }

        // -- back up -------------------------------------------------------------
        String stamp = String.valueOf(FathomProfile.now());
        File backup = new File(gameDirectory, "fathom/backups/" + stamp);
        if (!backup.isDirectory() && !backup.mkdirs()) {
            throw new ProfileException("could not create a backup directory at " + backup);
        }
        List<File> sources = new ArrayList<File>();
        sources.add(features);
        sources.add(performance);
        if (unnamedLayout != null) {
            sources.add(unnamedLayout);
        }
        sources.addAll(namedLayouts.values());
        for (File source : sources) {
            if (!source.isFile()) {
                continue;
            }
            try {
                Files.copy(source.toPath(), new File(backup, source.getName()).toPath(),
                        StandardCopyOption.REPLACE_EXISTING);
            } catch (IOException failure) {
                // No backup, no migration. The originals are untouched, so failing
                // here costs nothing but a retry next start.
                throw new ProfileException("could not back up " + source.getName() + " (" + failure.getMessage() + ")", failure);
            }
            report.sources.add(source.getPath());
        }
        report.backup = backup;

        // -- the default ---------------------------------------------------------
        FathomProfile migrated = FathomProfile.defaultProfile();
        applyFeatures(migrated, read(features), report.unmapped);
        applyPerformance(migrated, read(performance));
        if (unnamedLayout != null) {
            applyHud(migrated, read(unnamedLayout));
        }
        migrated.prune();

        FathomProfile base = migrated;
        if (store.fileFor(FathomProfile.DEFAULT_ID).isFile() && !blankDefault(store)) {
            String id = ProfileStore.idFromName("Default imported", store.ids());
            if (id == null) {
                throw new ProfileException("no free id for the imported profile");
            }
            base = migrated.duplicate(id, IMPORTED_NAME);
        }
        store.save(base);
        report.created.add(base.name());

        // -- every named layout becomes a profile --------------------------------
        for (Map.Entry<String, File> layout : namedLayouts.entrySet()) {
            String id = ProfileStore.idFromName(layout.getKey(), store.ids());
            if (id == null) {
                report.unmapped.add("hud layout " + layout.getKey() + ": unusable name");
                continue;
            }
            FathomProfile profile = base.duplicate(id, titleCase(layout.getKey()));
            profile.hud().clear();
            applyHud(profile, read(layout.getValue()));
            profile.prune();
            store.save(profile);
            report.created.add(profile.name());
        }

        // -- record --------------------------------------------------------------
        if (store.lastProfileId() == null) {
            store.setLastProfileId(base.id());
        }
        store.setMigratedFrom(stamp);
        appendMarker(marker, key);
        return report;
    }

    // -----------------------------------------------------------------------
    // Mapping
    // -----------------------------------------------------------------------

    static void applyFeatures(FathomProfile profile, TreeMap<String, String> keys, List<String> unmapped) {
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String key = entry.getKey();
            String value = entry.getValue();
            if ("schema".equals(key)) {
                continue;
            }
            if (key.startsWith("client.")) {
                String rest = key.substring("client.".length());
                if ("hub.key".equals(rest)) {
                    profile.client().hubKey = integer(value);
                } else if ("ui.scale".equals(rest)) {
                    profile.client().uiScale = value;
                } else if ("ui.high-quality".equals(rest)) {
                    profile.client().highQualityUi = bool(value);
                } else if ("hud.font".equals(rest)) {
                    profile.client().hudFont = value;
                } else {
                    unmapped.add(key);
                }
                continue;
            }
            if (!key.startsWith("feature.")) {
                unmapped.add(key);
                continue;
            }
            String rest = key.substring("feature.".length());
            int dot = rest.indexOf('.');
            if (dot <= 0) {
                unmapped.add(key);
                continue;
            }
            FathomProfile.ModuleState state = profile.module(rest.substring(0, dot));
            String what = rest.substring(dot + 1);
            if ("enabled".equals(what)) {
                state.enabled = bool(value);
            } else if ("favourite".equals(what)) {
                state.favourite = Boolean.TRUE.equals(bool(value));
            } else if (what.startsWith("set.")) {
                state.settings.put(what.substring("set.".length()), value);
            } else if (what.startsWith("key.")) {
                state.keys.put(what.substring("key.".length()), value);
            } else {
                unmapped.add(key);
            }
        }
    }

    static void applyPerformance(FathomProfile profile, TreeMap<String, String> keys) {
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String key = entry.getKey();
            if ("schema".equals(key)) {
                continue;
            }
            if ("preset".equals(key)) {
                profile.performance().preset = entry.getValue();
                continue;
            }
            // opt.<id> is one optimization's switch, by the registry's dotted id.
            // conflict.<id> is the launcher's own stand-down, recomputed every
            // launch: a fact about this installation rather than a choice, so it
            // stays out of a loadout.
            if (!key.startsWith("opt.")) {
                continue;
            }
            Boolean on = bool(entry.getValue());
            if (on != null) {
                profile.performance().switches.put(key.substring("opt.".length()).trim(), on);
            }
        }
    }

    static void applyHud(FathomProfile profile, TreeMap<String, String> keys) {
        for (Map.Entry<String, String> entry : keys.entrySet()) {
            String key = entry.getKey();
            if (!key.startsWith("widget.")) {
                continue;
            }
            String rest = key.substring("widget.".length());
            int dot = rest.lastIndexOf('.');
            if (dot <= 0) {
                continue;
            }
            String widget = rest.substring(0, dot);
            String field = rest.substring(dot + 1);
            FathomProfile.HudEntry placement = profile.hud().get(widget);
            if (placement == null) {
                placement = new FathomProfile.HudEntry();
                profile.hud().put(widget, placement);
            }
            String value = entry.getValue().trim();
            if ("anchor".equals(field)) {
                // The old files wrote the enum name, TOP_LEFT.
                placement.anchor = value.toLowerCase(Locale.ROOT).replace('_', '-');
            } else if ("x".equals(field)) {
                Integer parsed = integer(value);
                placement.x = parsed == null ? 0 : parsed.intValue();
            } else if ("y".equals(field)) {
                Integer parsed = integer(value);
                placement.y = parsed == null ? 0 : parsed.intValue();
            } else if ("scale".equals(field)) {
                Float parsed = decimal(value);
                if (parsed != null && !parsed.isNaN() && !parsed.isInfinite() && parsed.floatValue() > 0f) {
                    placement.scale = parsed.floatValue();
                }
            } else if ("opacity".equals(field)) {
                Float parsed = decimal(value);
                if (parsed != null && parsed.floatValue() >= 0f && parsed.floatValue() <= 1f) {
                    placement.opacity = parsed.floatValue();
                }
            } else if ("hidden".equals(field)) {
                placement.hidden = Boolean.TRUE.equals(bool(value));
            }
        }
    }

    // -----------------------------------------------------------------------
    // Helpers
    // -----------------------------------------------------------------------

    private static boolean blankDefault(ProfileStore store) {
        try {
            return store.load(FathomProfile.DEFAULT_ID).isBlank();
        } catch (ProfileException unreadable) {
            // A Default that will not parse is still somebody's file. Not blank.
            return false;
        }
    }

    static TreeMap<String, String> read(File file) {
        TreeMap<String, String> out = new TreeMap<String, String>();
        if (file == null || !file.isFile()) {
            return out;
        }
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            properties.load(in);
        } catch (IOException unreadable) {
            return out;
        } finally {
            if (in != null) {
                try {
                    in.close();
                } catch (IOException ignored) {
                    // Nothing useful to do.
                }
            }
        }
        for (String name : properties.stringPropertyNames()) {
            out.put(name.trim(), properties.getProperty(name));
        }
        return out;
    }

    /**
     * One line identifying a collection, normalised because the launcher and the
     * game spell the same path differently.
     */
    static String collectionKey(File directory) {
        String key = directory.getAbsolutePath().replace('\\', '/');
        while (key.endsWith("/")) {
            key = key.substring(0, key.length() - 1);
        }
        return key.toLowerCase(Locale.ROOT);
    }

    private static boolean markerLists(File marker, String key) {
        if (!marker.isFile()) {
            return false;
        }
        try {
            String text = new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8);
            for (String line : text.split("\n")) {
                if (line.trim().equals(key)) {
                    return true;
                }
            }
        } catch (IOException unreadable) {
            return false;
        }
        return false;
    }

    private static void appendMarker(File marker, String key) throws ProfileException {
        StringBuilder text = new StringBuilder();
        if (marker.isFile()) {
            try {
                text.append(new String(Files.readAllBytes(marker.toPath()), StandardCharsets.UTF_8));
            } catch (IOException unreadable) {
                // Rewritten below with just this collection.
            }
        }
        if (text.length() > 0 && text.charAt(text.length() - 1) != '\n') {
            text.append('\n');
        }
        text.append(key).append('\n');
        try {
            ProfileStore.atomicWrite(marker, text.toString());
        } catch (IOException failure) {
            throw new ProfileException("could not record the migration (" + failure.getMessage() + ")", failure);
        }
    }

    private static Boolean bool(String raw) {
        if (raw == null) {
            return null;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static Integer integer(String raw) {
        try {
            return Integer.valueOf(raw.trim());
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    private static Float decimal(String raw) {
        try {
            return Float.valueOf(raw.trim());
        } catch (RuntimeException notANumber) {
            return null;
        }
    }

    private static String titleCase(String raw) {
        StringBuilder out = new StringBuilder();
        boolean start = true;
        for (int index = 0; index < raw.length(); index++) {
            char c = raw.charAt(index);
            if (c == '-' || c == '_') {
                out.append(' ');
                start = true;
            } else if (start) {
                out.append(Character.toUpperCase(c));
                start = false;
            } else {
                out.append(c);
            }
        }
        return out.toString();
    }

    private static String join(List<String> values) {
        StringBuilder out = new StringBuilder();
        for (String value : values) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(value);
        }
        return out.toString();
    }
}
