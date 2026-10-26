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
 * Where the Hub was left, and whether to go back there.
 *
 * <h2>Why this is not in the profile</h2>
 *
 * <p>The last page you looked at is a fact about this machine and this session,
 * not part of a loadout; a profile exported to a friend should not open their
 * Hub on your Settings tab. So, like the HUD editor's preferences, it lives in
 * one small file beside the HUD layout, and switching profile does not touch it.
 *
 * <p>The page is remembered by name (the rail section, the tab inside it, a
 * module id), never by index, so a page that has gone away or been renamed
 * falls back to where the Hub opens by default rather than to the wrong page.
 * Written when the Hub closes or the preference changes, never per frame.
 * Never throws.
 */
public final class HubState {

    /** Relative to the game directory. */
    public static final String RELATIVE_PATH = "config/fathom-hub-state.properties";

    private boolean reopenLast = true;
    private String section = "";
    private String page = "";
    private float scroll;
    private boolean changed;

    /** Whether the Hub reopens on the page it was left on. On by default. */
    public boolean reopenLast() {
        return reopenLast;
    }

    public void setReopenLast(boolean value) {
        if (reopenLast != value) {
            reopenLast = value;
            changed = true;
        }
    }

    /** The rail section last showing, by title; empty when none was recorded. */
    public String section() {
        return section;
    }

    /** The page inside it (a Settings tab, a module id); empty for the section's own page. */
    public String page() {
        return page;
    }

    /** How far that page was scrolled, never negative. */
    public float scroll() {
        return scroll;
    }

    /** Record where the Hub is. */
    public void remember(String sectionTitle, String pageId, float scrollPosition) {
        String nextSection = sectionTitle == null ? "" : sectionTitle;
        String nextPage = pageId == null ? "" : pageId;
        float nextScroll = scrollPosition > 0f && !Float.isNaN(scrollPosition)
                && !Float.isInfinite(scrollPosition) ? scrollPosition : 0f;
        if (!nextSection.equals(section) || !nextPage.equals(page) || nextScroll != scroll) {
            section = nextSection;
            page = nextPage;
            scroll = nextScroll;
            changed = true;
        }
    }

    /** Whether there is anything to write. */
    public boolean changed() {
        return changed;
    }

    public static HubState read(File gameDirectory) {
        HubState state = new HubState();
        File file = new File(gameDirectory, RELATIVE_PATH);
        if (!file.isFile()) {
            return state;
        }
        Properties properties = new Properties();
        InputStream in = null;
        try {
            in = new FileInputStream(file);
            properties.load(in);
        } catch (IOException unreadable) {
            return new HubState();
        } finally {
            close(in);
        }
        state.reopenLast = !"false".equalsIgnoreCase(String.valueOf(properties.getProperty("reopenLast")).trim());
        state.section = clean(properties.getProperty("section"));
        state.page = clean(properties.getProperty("page"));
        try {
            float parsed = Float.parseFloat(String.valueOf(properties.getProperty("scroll")).trim());
            state.scroll = parsed > 0f && !Float.isInfinite(parsed) ? parsed : 0f;
        } catch (NumberFormatException none) {
            state.scroll = 0f;
        }
        return state;
    }

    /** Write it if it changed. Returns the failure rather than throwing: this runs as a screen closes. */
    public String write(File gameDirectory) {
        if (!changed) {
            return null;
        }
        File file = new File(gameDirectory, RELATIVE_PATH);
        File parent = file.getParentFile();
        if (parent != null && !parent.isDirectory() && !parent.mkdirs()) {
            return "could not create " + parent;
        }
        Properties properties = new Properties();
        properties.setProperty("reopenLast", String.valueOf(reopenLast));
        properties.setProperty("section", section);
        properties.setProperty("page", page);
        properties.setProperty("scroll", String.valueOf(scroll));
        OutputStream out = null;
        try {
            out = new FileOutputStream(file);
            properties.store(out, "Fathom Hub navigation. Not part of a profile.");
            changed = false;
            return null;
        } catch (IOException problem) {
            return String.valueOf(problem.getMessage());
        } finally {
            close(out);
        }
    }

    private static String clean(String raw) {
        return raw == null ? "" : raw.trim();
    }

    private static void close(Closeable stream) {
        if (stream != null) {
            try {
                stream.close();
            } catch (IOException ignored) {
                // Nothing useful to do.
            }
        }
    }
}
