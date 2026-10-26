package net.fathom.optimizations.fabric.hub;

import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Icon;

/**
 * One page of the hub.
 *
 * <h2>Why an interface and not seven branches in one screen</h2>
 *
 * <p>Because the sections have nothing in common except their frame. Mods is a
 * searchable list with a dedicated detail page; HUD is a list plus a door into a full-screen
 * editor; Diagnostics is read-only text. Written as branches they would share a
 * method and no logic, and the shared method would grow a parameter every time
 * one of them needed something the others did not.
 *
 * <p>Written as objects, the shell owns the chrome — the scrim, the header, the
 * rail, the search box, the open and close animations — and a section owns a
 * rectangle. A section cannot draw outside it, cannot add a tab, and cannot
 * replace the screen.
 *
 * <h2>One method, not two</h2>
 *
 * <p>There is no {@code click}. A section draws its controls and the controls
 * report whether they were used, in the same call, from the same rectangle —
 * see {@code Controls}. The version with a separate click method computed every
 * rectangle twice and disagreed with itself often enough to be a bug source of
 * its own.
 */
public interface Section {

    /** The rail label. One or two words. */
    String title();

    /** The rail icon. */
    Icon icon();

    /**
     * Whether the header's search box applies here.
     *
     * <p>Only Mods and HUD have anything to search. Showing the box on
     * Diagnostics would be offering a control that cannot do anything.
     */
    boolean searchable();

    /** A sentence under the page's heading, or null for none. */
    String subtitle();

    /**
     * Whether this section currently supplies its own page heading.
     * Detail pages use the whole content region for their breadcrumb and hero.
     */
    default boolean ownsHeading() {
        return false;
    }

    /**
     * Draw the page, and act on anything the user does to it.
     *
     * @param area the content rectangle, in logical units, already inset
     */
    void render(HubContext context, Rect area);

    /** The wheel turned over the content area. Positive is away from the user. */
    default void scroll(int notches) {
        // Only the scrolling sections care.
    }

    /**
     * Which page inside this section is showing, as a stable name (a Settings
     * tab, a module id), for reopening the Hub where it was left. Empty for a
     * section with only the one page.
     */
    default String pageId() {
        return "";
    }

    /** How far the page showing is scrolled, to come back to. */
    default float scrollPosition() {
        return 0f;
    }

    /**
     * Show {@code pageId} scrolled to {@code scroll}, after {@link #opened}.
     * A page that no longer exists is ignored: the section's own page shows.
     */
    default void restore(HubContext context, String pageId, float scroll) {
    }

    /**
     * The wheel turned with the pointer at ({@code x}, {@code y}) in logical
     * units, so a page with a scrolling list inside it can give the wheel to
     * the list under the pointer and the rest to itself. Pages with one
     * scrolling area need not override this.
     */
    default void scroll(int notches, float x, float y) {
        scroll(notches);
    }

    /**
     * A key was pressed while this section had the screen.
     *
     * <p>Called before the shell's own handling, so a section that is capturing
     * keys — rebinding one, for instance — can take Escape rather than having
     * the hub close underneath it.
     *
     * @return true when the section consumed it
     */
    default boolean key(HubContext context, char character, int keyCode) {
        return false;
    }

    /**
     * A mouse button went down while this section had the screen.
     *
     * <p>Called before the controls see it, and only exists for one case: a
     * keybind that is listening wants the button itself, not a click on
     * whatever happens to be underneath. A section that returns true has taken
     * the press, and nothing else this frame sees it.
     *
     * @param button the LWJGL button number, zero for the left one
     * @return true when the section consumed it
     */
    default boolean mouse(HubContext context, int button) {
        return false;
    }

    /** Called when the section becomes visible, to reset scroll and selection. */
    /**
     * Whether a text field on this page is taking typing right now.
     *
     * <p>Asked by the Hub key: while somebody is typing, Right Shift is how a
     * capital letter is made, not a request to close the Hub.
     */
    default boolean typing() {
        return false;
    }

    default void opened(HubContext context) {
        // Most sections have nothing to reset.
    }

    /** Visual QA only: restart this page's one-shot state animations. */
    default void qaReplay(HubContext context) {
        opened(context);
    }
}
