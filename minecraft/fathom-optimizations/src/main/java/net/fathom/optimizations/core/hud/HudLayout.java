package net.fathom.optimizations.core.hud;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Properties;

/**
 * Where every widget is, saved next to the game.
 *
 * <h2>Why the game owns this file and the launcher does not</h2>
 *
 * <p>The HUD editor is in the game: you drag a widget while looking at the scene it sits
 * over, because that is the only place the question "is this in the way" can be answered.
 * A layout the launcher also wrote would mean two writers and a merge, and the merge would
 * be resolved wrongly at exactly the moment somebody had just spent five minutes arranging
 * their screen.
 *
 * <p>So the seam runs the other way from {@code LauncherConfig}: the launcher tells the
 * module which optimizations to run, and the module tells nobody where its widgets are.
 * The file lives in the profile's own game directory, so "per-profile HUD layout" needs no
 * mechanism at all — a different profile is a different directory.
 *
 * <h2>Why properties again</h2>
 *
 * <p>Same reason as the launcher bridge: {@code java.util.Properties} is in the JDK, so
 * the core keeps its no-dependency property and stays testable with a bare compiler. The
 * file is also legible and hand-editable, which matters for a file somebody might want to
 * copy between profiles.
 */
public final class HudLayout {

    /** Relative to the game directory. */
    public static final String RELATIVE_PATH = "config/fathom-hud.properties";

    /**
     * The layout in use when nobody has made another.
     *
     * <p>Named rather than nameless so the picker has something to show, and
     * stored in the file above rather than in a slot so an install that never
     * touches layouts has exactly one config file.
     */
    public static final String DEFAULT_NAME = "Default";

    /** Where a named layout lives, relative to the game directory. */
    public static String pathFor(String name) {
        if (name == null || name.equals(DEFAULT_NAME)) {
            return RELATIVE_PATH;
        }
        return "config/fathom-hud-" + slug(name) + ".properties";
    }

    /**
     * A name reduced to something safe to put in a filename.
     *
     * <p>Lowercase, alphanumerics and hyphens. Everything else becomes a
     * hyphen, runs collapse, and the result is capped — because this string
     * comes from a text field somebody typed into, and a filename is not the
     * place to find out what they typed.
     */
    static String slug(String name) {
        StringBuilder out = new StringBuilder();
        boolean lastWasDash = false;
        for (int i = 0; i < name.length() && out.length() < 40; i++) {
            char c = Character.toLowerCase(name.charAt(i));
            if ((c >= 'a' && c <= 'z') || (c >= '0' && c <= '9')) {
                out.append(c);
                lastWasDash = false;
            } else if (!lastWasDash && out.length() > 0) {
                out.append('-');
                lastWasDash = true;
            }
        }
        while (out.length() > 0 && out.charAt(out.length() - 1) == '-') {
            out.setLength(out.length() - 1);
        }
        return out.length() == 0 ? "layout" : out.toString();
    }

    /**
     * Every layout in a game directory, the default first.
     *
     * <p>Read from the filesystem rather than from an index, because an index
     * is a second thing to keep in step and the answer is one directory
     * listing. The display name is stored inside each file, so a slug in a
     * filename never has to be turned back into words.
     */
    public static List<String> list(File gameDirectory) {
        List<String> out = new ArrayList<String>();
        out.add(DEFAULT_NAME);
        File config = new File(gameDirectory, "config");
        File[] found = config.listFiles();
        if (found == null) {
            return out;
        }
        java.util.Arrays.sort(found);
        for (File file : found) {
            String fileName = file.getName();
            if (!file.isFile() || !fileName.startsWith("fathom-hud-") || !fileName.endsWith(".properties")) {
                continue;
            }
            Properties properties = new Properties();
            InputStream in = null;
            try {
                in = new FileInputStream(file);
                properties.load(in);
            } catch (IOException unreadable) {
                continue;
            } finally {
                close(in);
            }
            String name = properties.getProperty("name");
            if (name != null && !name.trim().isEmpty() && !out.contains(name.trim())) {
                out.add(name.trim());
            }
        }
        return out;
    }

    /** Bumped only for a change an older reader cannot survive. */
    public static final int SCHEMA = 1;

    private final Map<String, Placement> placements = new LinkedHashMap<String, Placement>();
    private final List<String> complaints = new ArrayList<String>();
    private String name = DEFAULT_NAME;

    /** What this layout is called. Shown in the editor and the picker. */
    public String name() {
        return name;
    }

    public void setName(String value) {
        if (value != null && !value.trim().isEmpty()) {
            name = value.trim();
        }
    }

    /** An independent copy under a new name, for duplicating a layout. */
    public HudLayout copyAs(String newName) {
        HudLayout copy = new HudLayout();
        copy.setName(newName);
        for (Map.Entry<String, Placement> entry : placements.entrySet()) {
            copy.put(entry.getKey(), entry.getValue());
        }
        return copy;
    }

    public void put(String widgetId, Placement placement) {
        placements.put(widgetId, placement);
    }

    /** The placement for a widget, or the given fallback when nothing is stored. */
    public Placement get(String widgetId, Placement fallback) {
        Placement found = placements.get(widgetId);
        return found == null ? fallback : found;
    }

    public boolean has(String widgetId) {
        return placements.containsKey(widgetId);
    }

    public void remove(String widgetId) {
        placements.remove(widgetId);
    }

    public Map<String, Placement> all() {
        return Collections.unmodifiableMap(placements);
    }

    public int size() {
        return placements.size();
    }

    /** Anything wrong with the file that was read, for logging rather than throwing. */
    public List<String> complaints() {
        return Collections.unmodifiableList(complaints);
    }

    // -----------------------------------------------------------------------
    // Reading
    // -----------------------------------------------------------------------

    /**
     * Read the layout for a game directory, falling back to the registry defaults.
     *
     * <p>Never throws and never returns null. A missing file is the normal first-run case;
     * a corrupt one is somebody's text editor, and losing a whole HUD over one bad line
     * would be a worse answer than ignoring the line. Every rejected key is recorded in
     * {@link #complaints()} so the reason is visible in the log.
     */
    public static HudLayout read(File gameDirectory, HudRegistry registry) {
        return read(gameDirectory, registry, DEFAULT_NAME);
    }

    /** As above, for a named layout. */
    public static HudLayout read(File gameDirectory, HudRegistry registry, String name) {
        HudLayout layout = registry.defaultLayout();
        layout.setName(name);
        File file = new File(gameDirectory, pathFor(name));
        if (!file.isFile()) {
            return layout;
        }
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            properties.load(in);
        } catch (IOException problem) {
            layout.complaints.add("could not be read (" + problem.getMessage() + ")");
            return layout;
        } finally {
            close(in);
        }
        String stored = properties.getProperty("name");
        if (stored != null && !stored.trim().isEmpty()) {
            layout.setName(stored);
        }
        layout.apply(properties, registry);
        return layout;
    }

    private void apply(Properties properties, HudRegistry registry) {
        for (Widget widget : registry.all()) {
            String prefix = "widget." + widget.id() + ".";
            if (!hasAny(properties, prefix)) {
                continue;
            }
            Placement base = get(widget.id(), widget.defaultPlacement());
            Anchor anchor =
                    anchor(properties.getProperty(prefix + "anchor"), base.anchor(), widget.id());
            int x = integer(properties.getProperty(prefix + "x"), base.offsetX(), widget.id() + ".x");
            int y = integer(properties.getProperty(prefix + "y"), base.offsetY(), widget.id() + ".y");
            float scale =
                    decimal(properties.getProperty(prefix + "scale"), base.scale(), widget.id() + ".scale");
            float opacity =
                    decimal(
                            properties.getProperty(prefix + "opacity"),
                            base.opacity(),
                            widget.id() + ".opacity");
            // A `widget.*.enabled` written by an older build is read and
            // dropped. Whether a widget is drawn is its module's switch now, and
            // honouring a stale layout flag would re-create the defect where a
            // module reads as on and shows nothing.
            put(widget.id(), new Placement(anchor, x, y, scale, opacity));
        }
    }

    private static boolean hasAny(Properties properties, String prefix) {
        for (Object key : properties.keySet()) {
            if (String.valueOf(key).startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    private Anchor anchor(String raw, Anchor fallback, String where) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Anchor.valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            complaints.add(where + ": " + raw + " is not an anchor");
            return fallback;
        }
    }

    private int integer(String raw, int fallback, String where) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Integer.parseInt(raw.trim());
        } catch (NumberFormatException notANumber) {
            complaints.add(where + ": " + raw + " is not a whole number");
            return fallback;
        }
    }

    private float decimal(String raw, float fallback, String where) {
        if (raw == null) {
            return fallback;
        }
        try {
            return Float.parseFloat(raw.trim());
        } catch (NumberFormatException notANumber) {
            complaints.add(where + ": " + raw + " is not a number");
            return fallback;
        }
    }

    private static boolean bool(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim().toLowerCase(Locale.ROOT);
        if ("true".equals(value) || "yes".equals(value) || "on".equals(value)) {
            return true;
        }
        if ("false".equals(value) || "no".equals(value) || "off".equals(value)) {
            return false;
        }
        return fallback;
    }

    // -----------------------------------------------------------------------
    // Writing
    // -----------------------------------------------------------------------

    /**
     * Save into a game directory.
     *
     * <p>Written whole. A layout is small, and the alternative — rewriting individual keys
     * — would let a stale entry for a widget that no longer exists survive forever.
     */
    public void write(File gameDirectory) throws IOException {
        File file = new File(gameDirectory, pathFor(name));
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            throw new IOException("could not create " + parent);
        }
        Properties properties = new Properties();
        properties.setProperty("schema", String.valueOf(SCHEMA));
        properties.setProperty("name", name);
        for (Map.Entry<String, Placement> entry : placements.entrySet()) {
            String prefix = "widget." + entry.getKey() + ".";
            Placement placement = entry.getValue();
            properties.setProperty(prefix + "anchor", placement.anchor().name());
            properties.setProperty(prefix + "x", String.valueOf(placement.offsetX()));
            properties.setProperty(prefix + "y", String.valueOf(placement.offsetY()));
            properties.setProperty(prefix + "scale", String.valueOf(placement.scale()));
            properties.setProperty(prefix + "opacity", String.valueOf(placement.opacity()));
        }
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            properties.store(out, "Fathom HUD layout. Edited in game: Right Shift, then HUD Editor.");
        } finally {
            close(out);
        }
    }

    private static void close(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a failed close, and throwing here would
                // replace a real error with a less informative one.
            }
        }
    }
}
