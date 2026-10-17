package net.fathom.optimizations.core.hud;

import java.io.Closeable;
import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.Properties;

/**
 * How the HUD editor is set up, as opposed to what it edits.
 *
 * <h2>Why these are not in the profile</h2>
 *
 * <p>A profile is a loadout: which modules are on, how they are configured and
 * where their widgets sit. Whether the grid is showing and how hard things snap
 * are properties of the tool, not of the arrangement — somebody who likes strong
 * snapping likes it in every profile, and a profile exported to a friend should
 * not change how their editor behaves. So they live in one small file beside the
 * HUD layout, in the same format, and switching profile does not touch them.
 *
 * <p>Never throws. A missing file is the first run; an unreadable one costs the
 * preferences and not the editor.
 */
public final class EditorPrefs {

    /** Relative to the game directory. */
    public static final String RELATIVE_PATH = "config/fathom-hud-editor.properties";

    /** How hard a dragged widget is pulled onto an alignment, in scaled pixels. */
    public static final String[] SNAP_CHOICES = {"Off", "Light", "Normal", "Strong"};
    private static final int[] SNAP_PIXELS = {0, 2, 4, 8};

    private int snap = 2;
    private boolean grid;
    private boolean bounds = true;
    private boolean separate = true;

    /** Which of {@link #SNAP_CHOICES} is in force. */
    public int snapChoice() {
        return snap;
    }

    public String snapLabel() {
        return SNAP_CHOICES[snap];
    }

    /** The snapping threshold in scaled pixels; zero means no snapping. */
    public int snapPixels() {
        return SNAP_PIXELS[snap];
    }

    /** Step to the next strength, wrapping. What pressing the toolbar button does. */
    public void cycleSnap() {
        snap = (snap + 1) % SNAP_CHOICES.length;
    }

    public boolean grid() {
        return grid;
    }

    public void setGrid(boolean value) {
        grid = value;
    }

    public boolean bounds() {
        return bounds;
    }

    public void setBounds(boolean value) {
        bounds = value;
    }

    /** Whether a dropped widget is moved off anything it landed on. */
    public boolean separate() {
        return separate;
    }

    public void setSeparate(boolean value) {
        separate = value;
    }

    public static EditorPrefs read(File gameDirectory) {
        EditorPrefs prefs = new EditorPrefs();
        File file = new File(gameDirectory, RELATIVE_PATH);
        if (!file.isFile()) {
            return prefs;
        }
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            properties.load(in);
        } catch (IOException unreadable) {
            return prefs;
        } finally {
            close(in);
        }
        String snap = properties.getProperty("snap");
        for (int index = 0; index < SNAP_CHOICES.length; index++) {
            if (SNAP_CHOICES[index].equalsIgnoreCase(String.valueOf(snap).trim())) {
                prefs.snap = index;
            }
        }
        prefs.grid = bool(properties.getProperty("grid"), prefs.grid);
        prefs.bounds = bool(properties.getProperty("bounds"), prefs.bounds);
        prefs.separate = bool(properties.getProperty("separate"), prefs.separate);
        return prefs;
    }

    /** Write them. Returns the failure rather than throwing: this runs as a screen closes. */
    public String write(File gameDirectory) {
        File file = new File(gameDirectory, RELATIVE_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return "could not create " + parent;
        }
        Properties properties = new Properties();
        properties.setProperty("snap", snapLabel());
        properties.setProperty("grid", String.valueOf(grid));
        properties.setProperty("bounds", String.valueOf(bounds));
        properties.setProperty("separate", String.valueOf(separate));
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            properties.store(out, "Fathom HUD editor preferences. Not part of a profile.");
            return null;
        } catch (IOException problem) {
            return String.valueOf(problem.getMessage());
        } finally {
            close(out);
        }
    }

    private static boolean bool(String raw, boolean fallback) {
        if (raw == null) {
            return fallback;
        }
        String value = raw.trim();
        if ("true".equalsIgnoreCase(value)) {
            return true;
        }
        return "false".equalsIgnoreCase(value) ? false : fallback;
    }

    private static void close(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Nothing useful to do about a failed close.
            }
        }
    }
}
