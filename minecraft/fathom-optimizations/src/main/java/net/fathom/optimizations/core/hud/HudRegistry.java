package net.fathom.optimizations.core.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Every widget Fathom draws.
 *
 * <p>The same shape as {@code OptimizationRegistry}, and for the same reason: the list of
 * things that exist is data, so the hub screen, the HUD editor and the launcher can all
 * read it instead of each keeping their own copy.
 *
 * <h2>Why the default layout looks like this</h2>
 *
 * <p>Nothing overlaps vanilla's own HUD, and nothing overlaps anything else. The hotbar
 * owns the bottom centre, chat owns the bottom left, and the debug screen owns the top
 * left when it is open — so the defaults use the top-left column (which F3 covers, and F3
 * is a deliberate act) and the right-hand edges. A widget whose default position sat under
 * the hotbar would be a widget nobody ever saw working.
 *
 * <h2>What this does not decide</h2>
 *
 * <p>Whether a widget is drawn. That is its module's switch, in
 * {@code ModuleRegistry}, and it is deliberately the only one — a layout that
 * could also hide a widget is a layout that silently overrules the toggle
 * somebody just used. This registry answers what exists and where it starts.
 */
public final class HudRegistry {

    public static final String CATEGORY_PERFORMANCE = "Performance";
    public static final String CATEGORY_INPUT = "Input";
    public static final String CATEGORY_WORLD = "World";
    public static final String CATEGORY_STATUS = "Status";

    private final Map<String, Widget> byId;

    private HudRegistry(List<Widget> widgets) {
        Map<String, Widget> map = new LinkedHashMap<String, Widget>();
        for (Widget widget : widgets) {
            if (map.put(widget.id(), widget) != null) {
                throw new IllegalStateException("duplicate widget id: " + widget.id());
            }
        }
        this.byId = Collections.unmodifiableMap(map);
    }

    public static HudRegistry standard() {
        return new HudRegistry(Entries.all());
    }

    public static HudRegistry of(List<Widget> widgets) {
        return new HudRegistry(widgets);
    }

    public Widget find(String id) {
        return byId.get(id);
    }

    public boolean contains(String id) {
        return byId.containsKey(id);
    }

    public List<Widget> all() {
        return Collections.unmodifiableList(new ArrayList<Widget>(byId.values()));
    }

    public List<Widget> matching(String query) {
        List<Widget> out = new ArrayList<Widget>();
        for (Widget widget : byId.values()) {
            if (widget.matches(query)) {
                out.add(widget);
            }
        }
        return out;
    }

    /** The categories present, in registry order. */
    public List<String> categories() {
        List<String> out = new ArrayList<String>();
        for (Widget widget : byId.values()) {
            if (!out.contains(widget.category())) {
                out.add(widget.category());
            }
        }
        return out;
    }

    public int size() {
        return byId.size();
    }

    /** A layout with every widget where the registry says it belongs. */
    public HudLayout defaultLayout() {
        HudLayout layout = new HudLayout();
        for (Widget widget : byId.values()) {
            layout.put(widget.id(), widget.defaultPlacement());
        }
        return layout;
    }

    static final class Entries {

        static List<Widget> all() {
            List<Widget> out = new ArrayList<Widget>();

            out.add(Widget.of(
                    "fps",
                    "FPS",
                    "Frames per second, smoothed over a second so it is readable.",
                    CATEGORY_PERFORMANCE,
                    Sensitivity.SAFE,
                    Placement.at(Anchor.TOP_LEFT, 4, 4)));

            out.add(Widget.of(
                    "coordinates",
                    "Coordinates",
                    "Your X, Y and Z, and the direction you are facing.",
                    CATEGORY_WORLD,
                    Sensitivity.SAFE,
                    Placement.at(Anchor.TOP_LEFT, 4, 16)));

            out.add(Widget.of(
                    "clock",
                    "Clock",
                    "The real-world time, for when you have lost track of it.",
                    CATEGORY_STATUS,
                    Sensitivity.SAFE,
                    Placement.at(Anchor.TOP_RIGHT, 4, 4)));

            out.add(Widget.of(
                    "cps",
                    "CPS",
                    "Left and right clicks per second, counted over the last second.",
                    CATEGORY_INPUT,
                    Sensitivity.SAFE,
                    Placement.at(Anchor.MIDDLE_LEFT, 4, 0)));

            out.add(Widget.of(
                    "keystrokes",
                    "Keystrokes",
                    "WASD, the mouse buttons and space, lit while held.",
                    CATEGORY_INPUT,
                    Sensitivity.SAFE,
                    Placement.at(Anchor.MIDDLE_LEFT, 4, 40)));

            out.add(Widget.of(
                    "toggle-sprint",
                    "Sprint and sneak",
                    "Whether you are sprinting or sneaking right now.",
                    CATEGORY_INPUT,
                    Sensitivity.SAFE,
                    Placement.at(Anchor.BOTTOM_LEFT, 4, 30)));

            // Ping is read from the client's own player list, which is the same
            // number the tab list shows. It is SERVER_SENSITIVE not because the
            // number is private but because reading it on servers that hide the
            // tab list is the kind of thing a server may object to, and the
            // conservative reading costs a user one click.
            out.add(Widget.of(
                    "ping",
                    "Ping",
                    "Your latency to the server, as the tab list reports it.",
                    CATEGORY_STATUS,
                    Sensitivity.SERVER_SENSITIVE,
                    Placement.at(Anchor.TOP_RIGHT, 4, 16)));

            out.add(Widget.of(
                    "armour",
                    "Armour",
                    "The durability left on what you are wearing and holding.",
                    CATEGORY_STATUS,
                    Sensitivity.SERVER_SENSITIVE,
                    Placement.at(Anchor.BOTTOM_RIGHT, 4, 40)));

            out.add(Widget.of(
                    "potions",
                    "Potion effects",
                    "Active effects and how long each has left.",
                    CATEGORY_STATUS,
                    Sensitivity.SERVER_SENSITIVE,
                    Placement.at(Anchor.TOP_RIGHT, 4, 30)));

            out.add(Widget.of(
                    "pack-display",
                    "Pack display",
                    "Which resource pack you are using.",
                    CATEGORY_STATUS,
                    Sensitivity.SAFE,
                    Placement.at(Anchor.BOTTOM_RIGHT, 4, 4)));

            // The one widget whose default position is not a preference.
            //
            // A crosshair belongs at the centre of the screen because that is
            // where the game aims; it is in the layout at all so that somebody
            // who wants it slightly high, or on a second monitor's optical
            // centre, can move it. Nothing else in this list defaults to CENTER.
            out.add(Widget.of(
                    "crosshair",
                    "Crosshair",
                    "Replaces the vanilla crosshair with one you choose.",
                    CATEGORY_WORLD,
                    Sensitivity.SAFE,
                    Placement.at(Anchor.CENTER, 0, 0)));

            return out;
        }

        private Entries() {}
    }
}
