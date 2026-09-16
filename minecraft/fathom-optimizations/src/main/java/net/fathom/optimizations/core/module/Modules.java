package net.fathom.optimizations.core.module;

import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.module.FathomModule.Icon;
import net.fathom.optimizations.core.hud.Sensitivity;
import net.fathom.optimizations.core.opt.frames.FrameCapPolicy;

/**
 * Fathom's own modules.
 *
 * <p>The point of the file is that it is <em>only</em> declarations. Every one
 * goes through the same {@link FathomModule.Builder} a third-party mod would
 * use; none of them has a private hook into the hub, the store or the renderer.
 * If something here needed one, the API would not be finished.
 *
 * <h2>The rule this file is written to</h2>
 *
 * <p><b>Every setting declared here is read by something that draws.</b> The
 * client shipped with modules whose switches changed a boolean nobody read and
 * whose settings were never consulted, which teaches people that Fathom's
 * controls are decorative. So each declaration below names the effect it drives
 * — a widget, an optimization, or a hook by name — and {@code ModuleTests}
 * fails the build for any {@link Implementation#WORKING} module that names none
 * of them. Anything not implemented is {@link Implementation#UNAVAILABLE},
 * which the interface draws without a toggle at all.
 *
 * <h2>Why the common HUD settings are not written out</h2>
 *
 * <p>Font, text colour, shadow, background, opacity, border, padding and corner
 * radius are the same question for ten widgets, so {@link HudStyle#declare} adds
 * them with one call, the same ids and the same defaults, and {@code TextWidget}
 * reads them back through the same class. Position, anchor and scale are
 * deliberately not settings: they are spatial, and they are set by dragging in
 * the HUD editor.
 *
 * <h2>Ids are persisted</h2>
 *
 * <p>A setting id is part of every saved profile. Where an option was renamed
 * the old spelling is kept as an {@link Setting.Choice#alias}; where a setting
 * changed meaning it has a new id, so an old value cannot be read as something
 * it never was.
 *
 * <h2>What is on out of the box</h2>
 *
 * <p>FPS and Coordinates. Everything else is off. A client that arrives with
 * fourteen things drawn on the screen is a client whose first interaction is
 * switching twelve of them off, and the {@link Sensitivity#SERVER_SENSITIVE}
 * ones must not be on by default at all —
 * {@link FathomModule.Builder#onByDefault} is ignored for those, so the rule is
 * enforced by the type rather than by remembering.
 */
final class Modules {

    private Modules() {}

    /** Keeps Zoom's minimum, starting amount and maximum in order when one is edited. */
    private static final FathomModule.ChangeListener ZOOM_LIMITS = new FathomModule.ChangeListener() {
        @Override
        public void changed(FathomModule module, Setting setting) {
            Setting.Slider min = (Setting.Slider) module.setting("min");
            Setting.Slider amount = (Setting.Slider) module.setting("amount");
            Setting.Slider max = (Setting.Slider) module.setting("max");
            if (setting == min && min.get() > amount.get()) {
                amount.set(min.get());
            } else if (setting == max && max.get() < amount.get()) {
                amount.set(max.get());
            } else if (setting == amount) {
                if (amount.get() < min.get()) {
                    min.set(amount.get());
                }
                if (amount.get() > max.get()) {
                    max.set(amount.get());
                }
            }
            if (max.get() < min.get()) {
                max.set(min.get());
            }
        }
    };

    static List<FathomModule> all() {
        List<FathomModule> out = new ArrayList<FathomModule>();

        // -- HUD -----------------------------------------------------------

        out.add(HudStyle.declare(FathomModule.builder("fps")
                .name("FPS")
                .description("Frames per second, however you read it.")
                .category(Category.HUD)
                .icon(Icon.GAUGE)
                .widget("fps")
                .onByDefault()
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("smoothing", "Reading",
                        "Raw is the latest frame. Light and Smooth ease towards it; "
                                + "Average counts every frame since the last update.",
                        "Light", "Raw", "Light", "Smooth", "Average")
                        .alias("Off", "Raw"))
                .setting(new Setting.Choice("format", "Format",
                        "Custom writes the prefix and suffix below around the number.",
                        "120 FPS", "120 FPS", "120", "FPS: 120", "Custom"))
                .setting(new Setting.Text("prefix", "Custom prefix", "Before the number, for Custom.", ""))
                .setting(new Setting.Text("suffix", "Custom suffix", "After the number, for Custom.", " fps"))
                .setting(new Setting.Choice("refresh", "Update every",
                        "The measurement is continuous; only the text is rebuilt this often.",
                        "1 second", "1 second", "500 ms", "250 ms", "100 ms")))
                .build());

        out.add(HudStyle.declare(FathomModule.builder("coordinates")
                .name("Coordinates")
                .description("Where you are, and what you are in.")
                .category(Category.HUD)
                .icon(Icon.COMPASS)
                .widget("coordinates")
                .onByDefault()
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("layout", "Layout",
                        "Lines puts each axis on its own line. Compact fits everything on one.",
                        "Single line", "Single line", "Lines", "Compact"))
                .setting(new Setting.Choice("format", "Format", "",
                        "123 / 64 / -200",
                        "X: 123 Y: 64 Z: -200", "123 / 64 / -200", "123, 64, -200"))
                .setting(new Setting.Toggle("axis.x", "Show X", "", true))
                .setting(new Setting.Toggle("axis.y", "Show Y", "", true))
                .setting(new Setting.Toggle("axis.z", "Show Z", "", true))
                .setting(new Setting.Whole("precision", "Decimal places", "", 0, 0, 3, 1, ""))
                .setting(new Setting.Toggle("direction", "Direction", "The way you are facing.", true))
                .setting(new Setting.Choice("direction.style", "Direction style",
                        "The axis form names the coordinate that grows the way you face.",
                        "North", "North", "N", "+Z", "North (+Z)"))
                .setting(new Setting.Toggle("biome", "Biome", "The biome you are standing in.", false))
                .setting(new Setting.Toggle("dimension", "Dimension", "Overworld, Nether or End.", false))
                .setting(new Setting.Toggle("chunk", "Chunk", "Which chunk you are in.", false)))
                .build());

        out.add(HudStyle.declare(FathomModule.builder("clock")
                .name("Clock")
                .description("The real-world time and date.")
                .category(Category.HUD)
                .icon(Icon.CLOCK)
                .widget("clock")
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("format", "Format",
                        "Custom uses the pattern below.", "24 hour", "24 hour", "12 hour", "Custom"))
                .setting(new Setting.Toggle("seconds", "Seconds", "", false))
                .setting(new Setting.Choice("date", "Date", "", "Off",
                        "Off", "14 Sep", "Mon 14 Sep", "2026-09-14", "14/09/2026", "09/14/2026"))
                .setting(new Setting.Text("pattern", "Custom pattern",
                        "Java date letters, such as HH:mm or EEE d MMM h:mm a.",
                        "EEE HH:mm"))
                .setting(new Setting.Choice("timezone", "Time zone",
                        "System follows this computer's clock. UTC is the same everywhere.",
                        "System", "System", "UTC")))
                .build());

        out.add(HudStyle.declare(FathomModule.builder("pack-display")
                .name("Pack Display")
                .description("The resource packs you are using.")
                .category(Category.HUD)
                .icon(Icon.PACKAGE)
                .widget("pack-display")
                .implementation(Implementation.WORKING)
                // The icon is the one the resource pack screen shows. Vanilla
                // decodes it once when the pack list is built, keeps it on the
                // pack entry, and uploads it on first bind; the widget binds it
                // through that same call, so nothing is re-read or re-uploaded.
                .setting(new Setting.Toggle("icon", "Pack icon", "", true))
                .setting(new Setting.Choice("format", "Format", "",
                        "Name only", "Name only", "Pack: Name"))
                .setting(new Setting.Toggle("description", "Description",
                        "The pack's own one-line description.", false))
                .setting(new Setting.Choice("packs", "Packs shown",
                        "The top pack is the one that wins. Count adds how many are under it.",
                        "Top pack", "Top pack", "Top pack and count", "Every pack")))
                .build());

        out.add(HudStyle.declare(FathomModule.builder("ping")
                .name("Ping")
                .description("Your latency to the server.")
                .category(Category.HUD)
                .sensitivity(Sensitivity.SERVER_SENSITIVE)
                .icon(Icon.SIGNAL)
                .widget("ping")
                // The number the multiplayer connection already knows. There is
                // no separate probe anywhere in this client: inventing one would
                // mean traffic the server did not ask for, to measure something
                // it already tells us.
                .needs(Compatibility.MULTIPLAYER)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("display", "Display", "Bars are the tab list's signal icon.",
                        "Number", "Number", "Bars", "Bars and number"))
                .setting(new Setting.Choice("format", "Format", "",
                        "120 ms", "120 ms", "120", "Ping: 120 ms", "Ping: 120"))
                .setting(new Setting.Choice("refresh", "Update every",
                        "Servers only report latency every few seconds, so faster is rarely different.",
                        "1 second", "500 ms", "1 second", "2 seconds", "5 seconds"))
                .setting(new Setting.Toggle("colour-by-quality", "Colour by quality",
                        "Green, amber or red by the two thresholds below.", true))
                .setting(new Setting.Whole("good", "Good below", "", 100, 20, 500, 10, "ms"))
                .setting(new Setting.Whole("bad", "Poor above", "", 300, 50, 1000, 25, "ms")))
                .build());

        // -- PvP -----------------------------------------------------------

        out.add(HudStyle.declare(FathomModule.builder("cps")
                .name("CPS")
                .description("Clicks per second, left, right or both.")
                .category(Category.PVP)
                .icon(Icon.MOUSE)
                .widget("cps")
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("mode", "Buttons",
                        "Combined adds both buttons into one number.",
                        "Both", "Both", "Left", "Right", "Combined"))
                .setting(new Setting.Toggle("letters", "Button letters", "Write L and R before the numbers.", false))
                .setting(new Setting.Toggle("label", "Label", "Write CPS after the number.", false))
                .setting(new Setting.Whole("window", "Sample window",
                        "How far back to average. One second is what everybody means by CPS.",
                        1, 1, 5, 1, "s")))
                .build());

        out.add(HudStyle.declarePanel(FathomModule.builder("keystrokes")
                .name("Keystrokes")
                .description("Movement and mouse keys, lit up.")
                .category(Category.PVP)
                .icon(Icon.KEYBOARD)
                .widget("keystrokes")
                .implementation(Implementation.WORKING)
                .setting(new Setting.Toggle("wasd", "Movement keys", "", true))
                .setting(new Setting.Toggle("mouse", "Mouse buttons", "", true))
                .setting(new Setting.Toggle("space", "Space bar", "", true))
                .setting(new Setting.Toggle("sneak", "Sneak key", "", false))
                .setting(new Setting.Toggle("cps-on-keys", "CPS on buttons",
                        "Show clicks per second on the mouse buttons.", false))
                .setting(new Setting.Whole("key.size", "Key size", "", 16, 12, 32, 1, "px"))
                .setting(new Setting.Whole("key.gap", "Spacing", "Space between keys.", 2, 0, 8, 1, "px"))
                .setting(new Setting.Toggle("key.background", "Key Background", "", true))
                .setting(new Setting.Colour("key.unpressed.fill", "Unpressed Fill", "", 0x111318))
                .setting(new Setting.Colour("key.pressed.fill", "Pressed Fill", "", 0x373B44))
                .setting(new Setting.Whole("key.opacity", "Opacity", "", 80, 10, 100, 5, "%"))
                .setting(new Setting.Colour("key.unpressed.text", "Unpressed Text", "", 0xE7EAF0))
                .setting(new Setting.Colour("key.pressed.text", "Pressed Text", "", 0xFFFFFF))
                .setting(new Setting.Toggle("key.border", "Border", "", true))
                .setting(new Setting.Colour("key.border.colour", "Border colour", "", 0x5C6573))
                .setting(new Setting.Whole("key.border.width", "Border width", "", 1, 1, 3, 1, "px"))
                .setting(new Setting.Whole("key.fade", "Press fade",
                        "How long a key takes to light and go dark. Zero is instant.", 40, 0, 120, 10, "ms")))
                .build());

        out.add(HudStyle.declare(FathomModule.builder("armour")
                .name("Armour HUD")
                .description("Armour and held item, with durability.")
                .category(Category.PVP)
                .sensitivity(Sensitivity.SERVER_SENSITIVE)
                .icon(Icon.SHIELD)
                .widget("armour")
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("display", "Display", "",
                        "Icons and values", "Icons and values", "Icons only", "Values only"))
                .setting(new Setting.Choice("style", "Durability", "",
                        "Percent", "Percent", "Remaining", "Remaining / max"))
                .setting(new Setting.Choice("label", "Label", "", "None", "None", "Slot", "Item name"))
                .setting(new Setting.Toggle("held", "Held item", "Include what is in your hand.", true))
                .setting(new Setting.Choice("layout", "Layout", "", "Vertical", "Vertical", "Horizontal"))
                .setting(new Setting.Toggle("reverse", "Reverse order", "Boots first instead of the helmet.", false))
                .setting(new Setting.Whole("spacing", "Spacing", "", 1, 0, 10, 1, "px"))
                .setting(new Setting.Toggle("bar", "Durability bar", "The bar the hotbar draws under an item.", false))
                .setting(new Setting.Toggle("condition", "Colour by condition",
                        "Values turn amber and then red as the item wears out.", true))
                .setting(new Setting.Whole("warn", "Amber below", "", 50, 5, 95, 5, "%"))
                .setting(new Setting.Whole("danger", "Red below", "", 20, 1, 90, 1, "%")))
                .build());

        out.add(HudStyle.declare(FathomModule.builder("potions")
                .name("Potion HUD")
                .description("Active effects and time remaining.")
                .category(Category.PVP)
                .sensitivity(Sensitivity.SERVER_SENSITIVE)
                .icon(Icon.FLASK)
                .widget("potions")
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Toggle("icons", "Icons", "", true))
                .setting(new Setting.Toggle("names", "Names", "", true))
                .setting(new Setting.Toggle("level", "Level", "Show the effect level.", true))
                .setting(new Setting.Toggle("duration", "Time left", "", true))
                .setting(new Setting.Toggle("compact", "Compact",
                        "One short line per effect instead of a name above its time.", false))
                .setting(new Setting.Choice("layout", "Layout", "", "Vertical", "Vertical", "Horizontal"))
                .setting(new Setting.Choice("sort", "Order", "", "Applied", "Applied", "Time left", "Name"))
                .setting(new Setting.Whole("spacing", "Spacing", "", 2, 0, 10, 1, "px"))
                .setting(new Setting.Toggle("effect-colour", "Effect colour",
                        "Tint each name with its potion's colour.", false))
                .setting(new Setting.Toggle("ending", "Warn when ending",
                        "Time left turns red for the last ten seconds.", true)))
                .build());

        // -- Utility -------------------------------------------------------

        out.add(HudStyle.declare(FathomModule.builder("toggle-sprint")
                .name("Sprint and sneak")
                .description("Toggle sprint and sneak, and show it.")
                .category(Category.UTILITY)
                .icon(Icon.RUN)
                .widget("toggle-sprint")
                .behaviour(Behaviour.MOVEMENT_TOGGLE)
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("sprint.mode", "Sprint key",
                        "Toggle latches on a press. Hold is Minecraft's own behaviour, untouched.",
                        "Toggle", "Hold", "Toggle"))
                .setting(new Setting.Choice("sneak.mode", "Sneak key",
                        "Toggle waits while flying or riding, where sneak means descend or dismount.",
                        "Hold", "Hold", "Toggle"))
                .setting(new Setting.Toggle("hud", "Show status", "Draw the status text.", true))
                .setting(new Setting.Toggle("sprint", "Show sprinting", "", true))
                .setting(new Setting.Toggle("sneak", "Show sneaking", "", true))
                .setting(new Setting.Text("label.sprint.toggled", "Sprint toggled text",
                        "Empty hides this state.", "Sprinting (Toggled)"))
                .setting(new Setting.Text("label.sprinting", "Sprinting text", "", "Sprinting"))
                .setting(new Setting.Text("label.sneak.toggled", "Sneak toggled text", "", "Sneaking (Toggled)"))
                .setting(new Setting.Text("label.sneaking", "Sneaking text", "", "Sneaking"))
                .setting(new Setting.Text("label.walking", "Walking text", "Empty shows nothing while walking.", ""))
                .setting(new Setting.Colour("toggled.colour", "Toggled colour", "", 0xFFFFFF))
                .setting(new Setting.Colour("walking.colour", "Walking colour", "", 0xAAAAAA)))
                .build());

        // Zoom is the reason keybinds exist in this framework at all.
        //
        // Implemented from vanilla's own field of view rather than from anyone
        // else's zoom: the mod policies research is explicit that OptiFine's
        // implementation must not be copied. The field of view, the mouse
        // sensitivity and the camera smoothing are changed where vanilla reads
        // them for one frame, never in the saved options, so releasing the key
        // has nothing to restore.
        out.add(FathomModule.builder("zoom")
                .name("Zoom")
                .description("Hold a key to narrow the field of view.")
                .category(Category.UTILITY)
                .icon(Icon.MAGNIFIER)
                .behaviour(Behaviour.ZOOM)
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .keybind(new Keybind("zoom", "Zoom", 46 /* C */))
                .setting(new Setting.Slider("amount", "Amount", "How far in a press starts.",
                        4, 1.5, 20, 0.5, 1, "x"))
                .setting(new Setting.Toggle("scroll", "Scroll to adjust",
                        "The mouse wheel zooms further in or out while the key is held.", true))
                .setting(new Setting.Slider("scroll.step", "Scroll step", "How much one notch changes the zoom.",
                        1.25, 1.05, 2, 0.05, 2, "x"))
                .setting(new Setting.Slider("min", "Minimum", "The widest scrolling can go.",
                        1.5, 1.1, 10, 0.1, 1, "x"))
                .setting(new Setting.Slider("max", "Maximum", "The furthest scrolling can go.",
                        20, 2, 50, 1, 0, "x"))
                .setting(new Setting.Choice("release", "On release",
                        "Remember starts the next zoom where scrolling left the last one.",
                        "Reset", "Reset", "Remember"))
                .setting(new Setting.Toggle("animate", "Smooth zoom",
                        "Ease in and out rather than snapping.", true))
                .setting(new Setting.Whole("speed", "Zoom duration", "", 160, 40, 400, 20, "ms"))
                .setting(new Setting.Toggle("sensitivity", "Sensitivity compensation",
                        "Turn slower while zoomed, so a flick covers the same part of the picture.", true))
                .setting(new Setting.Whole("sensitivity.strength", "Compensation strength",
                        "100% matches the zoom exactly.", 100, 0, 100, 10, "%"))
                .setting(new Setting.Toggle("smooth", "Cinematic camera",
                        "Minecraft's smooth camera, only while zoomed.", false))
                .setting(new Setting.Toggle("hide-hand", "Hide hand", "Do not draw your hand while zoomed.", false))
                .onChange(ZOOM_LIMITS)
                .build());

        out.add(FathomModule.builder("main-menu")
                .name("Fathom main menu")
                .description("Fathom's title screen, not Minecraft's.")
                .category(Category.VISUAL)
                .icon(Icon.GRID)
                .behaviour(Behaviour.MAIN_MENU)
                .implementation(Implementation.WORKING)
                .onByDefault()
                // Keep the persisted id for compatibility with existing profiles.
                .setting(new Setting.Toggle("panorama", "Cinematic background",
                        "Fathom's static, preprocessed Minecraft poster behind the menu.", true))
                // The same fifteen the launcher offers, under the same names.
                // `MenuScenes` is the other half of this list and the picker on
                // the menu writes here, so the two surfaces cannot disagree.
                .setting(new Setting.Choice("scene", "Scene",
                        "Which recording sits behind the menu.", "Rainforest Storm",
                        "Cherry Grove", "City Overlook", "Metropolis", "Midnight",
                        "Midnight Coast", "Midnight Metropolis", "Moon Tower", "Neon Grid",
                        "Rainforest Drift", "Rainforest Storm", "Rainforest Twilight",
                        "Red Tower", "Savanna", "Snowfall", "Storm Panorama"))
                .build());

        // -- Visual --------------------------------------------------------

        // The crosshair's shape ids are named in CrosshairPresets, which writes
        // them; the old "size" setting measured the whole cross including the
        // gap and is replaced by "length", so a stored size is not misread.
        out.add(FathomModule.builder("crosshair")
                .name("Crosshair")
                .description("A crosshair of your own design.")
                .category(Category.VISUAL)
                .icon(Icon.CROSSHAIR)
                .widget("crosshair")
                .behaviour(Behaviour.CROSSHAIR)
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice(CrosshairPresets.PRESET, "Preset",
                        "Sets the shape. Colour and opacity are left alone.",
                        "Default", CrosshairPresets.NAMES))
                .setting(new Setting.Choice(CrosshairPresets.STYLE, "Shape",
                        "Vanilla keeps Minecraft's own, including a resource pack's.", "Cross",
                        "Vanilla", "Cross", "Dot", "Circle", "Tee"))
                .setting(new Setting.Whole(CrosshairPresets.LENGTH, "Length", "How far each arm reaches.",
                        4, 0, 20, 1, "px"))
                .setting(new Setting.Whole(CrosshairPresets.THICKNESS, "Thickness", "", 1, 1, 6, 1, "px"))
                .setting(new Setting.Whole(CrosshairPresets.GAP, "Gap", "Space at the centre.", 2, 0, 12, 1, "px"))
                .setting(new Setting.Toggle(CrosshairPresets.DOT, "Centre dot", "", false))
                .setting(new Setting.Whole(CrosshairPresets.DOT_SIZE, "Dot size", "", 1, 1, 8, 1, "px"))
                .setting(new Setting.Toggle(CrosshairPresets.OUTLINE, "Outline",
                        "A dark edge, so it reads on any background.", true))
                .setting(new Setting.Whole(CrosshairPresets.OUTLINE_WIDTH, "Outline thickness", "",
                        1, 1, 3, 1, "px"))
                .setting(new Setting.Colour("colour", "Colour", "", 0xFFFFFF))
                .setting(new Setting.Colour("outline.colour", "Outline colour", "", 0x000000))
                .setting(new Setting.Whole("opacity", "Opacity", "", 100, 10, 100, 5, "%"))
                .setting(new Setting.Toggle("dynamic", "Dynamic",
                        "The gap opens while you move, sprint or fall, and closes when you stop.", false))
                .setting(new Setting.Toggle("feedback", "Target colour",
                        "Change colour while aiming at an entity, and flash when you hit one.", false))
                .setting(new Setting.Colour("feedback.colour", "Target colour value", "", 0xFF5555))
                .setting(new Setting.Toggle("hide.third-person", "Hide in third person",
                        "Minecraft hides its own crosshair there too.", true))
                .setting(new Setting.Toggle("hide.gui", "Hide behind menus",
                        "Hide while a screen such as the inventory is open.", false))
                .setting(new Setting.Toggle("hide-vanilla", "Hide vanilla crosshair",
                        "Otherwise both are drawn, one over the other.", true))
                .onChange(CrosshairPresets.LISTENER)
                .build());

        // -- Performance ---------------------------------------------------
        //
        // This owns nothing of its own: it is a switch on an
        // optimization the performance module already implements. Modelling
        // them as modules anyway is what puts them in the same list, with the
        // same search and the same persistence, as everything else -- rather
        // than being two settings that live somewhere different because of how
        // they happen to be implemented.
        out.add(FathomModule.builder("unfocused-fps")
                .name("Unfocused FPS limit")
                .description("Fewer frames while in the background.")
                .category(Category.PERFORMANCE)
                .icon(Icon.SPARK)
                .optimization(FrameCapPolicy.UNFOCUSED_ID)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice(
                        FrameCapPolicy.UNFOCUSED_CHOICE,
                        "Limit",
                        "Frames per second while the window is in the background.",
                        "30",
                        FrameCapPolicy.UNFOCUSED_CHOICES))
                .onByDefault()
                .build());

        return out;
    }
}
