package net.fathom.optimizations.core.module;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Properties;

/**
 * Where feature configuration is kept.
 *
 * <p>One file per game directory, which is one file per profile, so switching
 * profile switches every feature's settings with it and nothing has to be
 * copied or migrated. The same reasoning as {@code HudLayout}, and the same
 * format, for the same reason: {@code java.util.Properties} is in the JDK, so
 * the core keeps its no-dependency property and stays testable with a bare
 * compiler.
 *
 * <h2>Keys</h2>
 *
 * <pre>
 * feature.&lt;id&gt;.enabled     = true
 * feature.&lt;id&gt;.favourite   = false
 * feature.&lt;id&gt;.set.&lt;key&gt;   = &lt;value&gt;
 * feature.&lt;id&gt;.key.&lt;bind&gt;  = &lt;lwjgl code&gt;
 * </pre>
 *
 * <p>Namespaced by feature so a third-party feature cannot collide with a
 * first-party one, and legible enough that somebody can fix a bad value by
 * hand — which is the point of a text format, and the reason every parse
 * failure falls back rather than throwing.
 *
 * <h2>What is not written</h2>
 *
 * <p>Anything still at its default. A file listing every value of every feature
 * is a file where a real change is invisible in a diff, and it also means a
 * default that improves in a later version is stuck at the old one for
 * everybody who ever opened the hub.
 */
public final class ModuleStore {

    /** Relative to the game directory. */
    public static final String RELATIVE_PATH = "config/fathom-features.properties";

    /** Bumped only for a change an older reader cannot survive. */
    public static final int SCHEMA = 1;

    private final List<String> complaints = new ArrayList<String>();

    /** Anything in the file that could not be used. */
    public List<String> complaints() {
        return complaints;
    }

    /**
     * Settings that belong to the client itself rather than to any feature.
     *
     * <p>Only one so far: the key that opens the hub. It lives here rather than
     * on a feature because a feature can be switched off, and switching off the
     * only way to open the menu is a state nobody should be able to reach.
     *
     * <p>Passed in and out rather than held, so this class stays a reader and a
     * writer rather than becoming somewhere state lives.
     */
    public void loadClient(File gameDirectory, Keybind hubKey, ClientPrefs prefs) {
        Properties properties = read(new File(gameDirectory, RELATIVE_PATH));
        if (properties == null) {
            return;
        }
        String raw = properties.getProperty("client.hub.key");
        if (hubKey != null && raw != null) {
            try {
                int code = Integer.parseInt(raw.trim());
                if (hubKey.accepts(code)) {
                    hubKey.set(code);
                } else {
                    complaints.add("client.hub.key: " + raw + " cannot open the Hub; using the default");
                }
            } catch (NumberFormatException notANumber) {
                complaints.add("client.hub.key: " + raw + " is not a key code");
            }
        }
        if (prefs == null) {
            return;
        }
        String scale = properties.getProperty("client.ui.scale");
        if (scale != null) {
            prefs.setScaleLabel(scale);
        }
        prefs.setHudFontLabel(properties.getProperty("client.hud.font"));
        prefs.setMenuBackgroundLabel(properties.getProperty("client.menu-background-quality"));
        prefs.setRshiftBackgroundLabel(properties.getProperty("client.rshift.background"));
        prefs.setRshiftBlurLabel(properties.getProperty("client.rshift.blur"));
        prefs.setRshiftDimLabel(properties.getProperty("client.rshift.dim"));
        String quality = properties.getProperty("client.ui.high-quality");
        if (quality != null) {
            Boolean parsed = bool(quality);
            if (parsed == null) {
                complaints.add("client.ui.high-quality: " + quality + " is not true or false");
            } else {
                prefs.setHighQualityUi(parsed.booleanValue());
            }
        }
    }

    /**
     * Apply a game directory's stored configuration to a registry.
     *
     * <p>Never throws. A missing file is the normal first-run case, and a
     * corrupt one costs the values it corrupted rather than all of them.
     */
    public void load(File gameDirectory, ModuleRegistry registry) {
        File file = new File(gameDirectory, RELATIVE_PATH);
        if (!file.isFile()) {
            return;
        }
        Properties properties = read(file);
        if (properties == null) {
            return;
        }

        for (FathomModule feature : registry.all()) {
            String prefix = "feature." + feature.id() + ".";

            String enabled = properties.getProperty(prefix + "enabled");
            if (enabled != null) {
                Boolean parsed = bool(enabled);
                if (parsed == null) {
                    complaints.add(feature.id() + ".enabled: " + enabled + " is not true or false");
                } else {
                    feature.setEnabled(parsed.booleanValue());
                }
            }

            String favourite = properties.getProperty(prefix + "favourite");
            if (favourite != null) {
                Boolean parsed = bool(favourite);
                if (parsed != null) {
                    feature.setFavourite(parsed.booleanValue());
                }
            }

            for (Setting setting : feature.settings()) {
                String raw = properties.getProperty(prefix + "set." + setting.id());
                if (raw != null && !setting.parse(raw)) {
                    complaints.add(feature.id() + "." + setting.id() + ": " + raw + " was not usable");
                }
            }

            for (Keybind keybind : feature.keybinds()) {
                String raw = properties.getProperty(prefix + "key." + keybind.id());
                if (raw == null) {
                    continue;
                }
                try {
                    keybind.set(Integer.parseInt(raw.trim()));
                } catch (NumberFormatException notANumber) {
                    complaints.add(feature.id() + "." + keybind.id() + ": " + raw + " is not a key code");
                }
            }

            // Everything this module stores has arrived; let it settle whatever
            // it derives from the values it just read.
            feature.settingChanged(null);
        }

        // A key stored for a feature nothing registered is worth saying out
        // loud: it usually means a mod was removed, and the settings are about
        // to be dropped from the file on the next save.
        for (Object key : properties.keySet()) {
            String name = String.valueOf(key);
            if (!name.startsWith("feature.")) {
                continue;
            }
            String id = name.substring("feature.".length());
            int dot = id.indexOf('.');
            if (dot > 0) {
                id = id.substring(0, dot);
            }
            if (!registry.contains(id) && !complaints.contains(unknown(id))) {
                complaints.add(unknown(id));
            }
        }
    }

    private static String unknown(String id) {
        return id + " is configured here but is not installed";
    }

    /**
     * Write everything that differs from its defaults.
     *
     * <p>Written whole, so a feature that has gone back to its defaults leaves
     * the file rather than lingering as a stale key for ever.
     */
    public void save(File gameDirectory, ModuleRegistry registry) throws IOException {
        save(gameDirectory, registry, null, null);
    }

    /** As above, also writing the client's own settings. */
    public void save(File gameDirectory, ModuleRegistry registry, Keybind hubKey, ClientPrefs prefs)
            throws IOException {
        File file = new File(gameDirectory, RELATIVE_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }

        Properties properties = new Properties();
        properties.setProperty("schema", String.valueOf(SCHEMA));
        if (hubKey != null && hubKey.changed()) {
            properties.setProperty("client.hub.key", String.valueOf(hubKey.key()));
        }
        if (prefs != null && prefs.scaleChangedFromDefault()) {
            properties.setProperty("client.ui.scale", prefs.scaleLabel());
        }
        if (prefs != null && prefs.qualityChangedFromDefault()) {
            properties.setProperty("client.ui.high-quality", String.valueOf(prefs.highQualityUi()));
        }
        if (prefs != null && prefs.hudFontChangedFromDefault()) {
            properties.setProperty("client.hud.font", prefs.hudFontLabel());
        }
        if (prefs != null && prefs.menuBackgroundChangedFromDefault()) {
            properties.setProperty("client.menu-background-quality", prefs.menuBackgroundLabel());
        }
        if (prefs != null && prefs.rshiftBackgroundChangedFromDefault()) {
            properties.setProperty("client.rshift.background", prefs.rshiftBackgroundLabel());
        }
        if (prefs != null && prefs.rshiftBlurChangedFromDefault()) {
            properties.setProperty("client.rshift.blur", prefs.rshiftBlurLabel());
        }
        if (prefs != null && prefs.rshiftDimChangedFromDefault()) {
            properties.setProperty("client.rshift.dim", prefs.rshiftDimLabel());
        }

        for (FathomModule feature : registry.all()) {
            String prefix = "feature." + feature.id() + ".";
            if (feature.enabled() != feature.defaultEnabled()) {
                properties.setProperty(prefix + "enabled", String.valueOf(feature.enabled()));
            }
            if (feature.favourite()) {
                properties.setProperty(prefix + "favourite", "true");
            }
            for (Setting setting : feature.settings()) {
                if (setting.changed()) {
                    properties.setProperty(prefix + "set." + setting.id(), setting.serialise());
                }
            }
            for (Keybind keybind : feature.keybinds()) {
                if (keybind.changed()) {
                    properties.setProperty(prefix + "key." + keybind.id(), String.valueOf(keybind.key()));
                }
            }
        }

        // Written beside the file and moved over it, so a crash or a full disk
        // mid-write leaves the previous settings rather than half a file.
        File temporary = File.createTempFile(file.getName() + ".", ".tmp", parent);
        OutputStream out = null;
        try {
            out = new FileOutputStream(temporary);
            properties.store(out, "Fathom features. Edited in game: Right Shift.");
            close(out);
            out = null;
            try {
                java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING,
                        java.nio.file.StandardCopyOption.ATOMIC_MOVE);
            } catch (java.nio.file.AtomicMoveNotSupportedException notAtomic) {
                java.nio.file.Files.move(temporary.toPath(), file.toPath(),
                        java.nio.file.StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            close(out);
            if (temporary.exists() && !temporary.delete()) {
                temporary.deleteOnExit();
            }
        }
    }

    /** Read the file, or null when there is nothing usable there. */
    private Properties read(File file) {
        if (!file.isFile()) {
            return null;
        }
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            properties.load(in);
            return properties;
        } catch (IOException problem) {
            complaints.add("could not be read (" + problem.getMessage() + ")");
            return null;
        } finally {
            close(in);
        }
    }

    private static Boolean bool(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "on".equals(value) || "yes".equals(value)) {
            return Boolean.TRUE;
        }
        if ("false".equals(value) || "off".equals(value) || "no".equals(value)) {
            return Boolean.FALSE;
        }
        return null;
    }

    private static void close(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a failed close, and throwing here
                // would replace a real error with a less informative one.
            }
        }
    }
}
