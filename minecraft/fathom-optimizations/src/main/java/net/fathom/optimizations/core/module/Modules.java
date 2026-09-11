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
 * <p>Colour, background, opacity, padding, corner radius and shadow are the
 * same question for ten widgets, so {@link HudStyle#declare} adds them with one
 * call, the same ids and the same defaults, and {@code TextWidget} reads them
 * back through the same class. Position, anchor and scale are deliberately not
 * settings: they are spatial, and they are set by dragging in the HUD editor.
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

    static List<FathomModule> all() {
        List<FathomModule> out = new ArrayList<FathomModule>();

        // -- HUD -----------------------------------------------------------

        out.add(HudStyle.declare(FathomModule.builder("fps")
                .name("FPS")
                .description("Frames per second, smoothed.")
                .category(Category.HUD)
                .icon(Icon.GAUGE)
                .widget("fps")
                .onByDefault()
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("smoothing", "Smoothing",
                        "How hard to average the reading. Off shows the raw counter.",
                        "Light", "Off", "Light", "Smooth"))
                .setting(new Setting.Choice("format", "Format", "",
                        "120 FPS", "120 FPS", "120", "FPS: 120"))
                .setting(new Setting.Choice("refresh", "Display refresh",
                        "The measurement stays continuous; only the displayed text is cached.",
                        "1 second", "1 second", "500 ms", "250 ms")))
                .build());

        out.add(HudStyle.declare(FathomModule.builder("coordinates")
                .name("Coordinates")
                .description("Your position and facing.")
                .category(Category.HUD)
                .icon(Icon.COMPASS)
                .widget("coordinates")
                .onByDefault()
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Toggle("axis.x", "Show X", "", true))
                .setting(new Setting.Toggle("axis.y", "Show Y", "", true))
                .setting(new Setting.Toggle("axis.z", "Show Z", "", true))
                .setting(new Setting.Toggle("labels", "Axis labels", "Write X, Y and Z before the numbers.", false))
                .setting(new Setting.Whole("precision", "Decimal places", "", 0, 0, 3, 1, ""))
                .setting(new Setting.Toggle("direction", "Direction", "The compass direction you face.", true))
                .setting(new Setting.Toggle("biome", "Biome", "The biome you are standing in.", false)))
                .build());

        out.add(HudStyle.declare(FathomModule.builder("clock")
                .name("Clock")
                .description("The real-world time.")
                .category(Category.HUD)
                .icon(Icon.CLOCK)
                .widget("clock")
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("format", "Format", "", "24 hour", "24 hour", "12 hour"))
                .setting(new Setting.Toggle("seconds", "Seconds", "", false))
                .setting(new Setting.Choice("timezone", "Time zone",
                        "System follows this computer's clock. UTC is the same everywhere.",
                        "System", "System", "UTC")))
                .build());

        // Pack Display has no icon setting, on purpose.
        //
        // 1.8.9 does not keep a decoded pack icon around once the resource
        // reload has finished, so drawing one means re-reading and uploading a
        // texture from inside a HUD renderer. A setting that switched on an
        // icon which never appeared is exactly the defect this milestone exists
        // to remove, so the setting is absent rather than inert.
        out.add(HudStyle.declare(FathomModule.builder("pack-display")
                .name("Pack Display")
                .description("The resource pack you are using.")
                .category(Category.HUD)
                .icon(Icon.PACKAGE)
                .widget("pack-display")
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("format", "Format", "",
                        "Name only", "Name only", "Pack: Name")))
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
                .setting(new Setting.Choice("format", "Format", "",
                        "120 ms", "120 ms", "120", "Ping: 120 ms"))
                .setting(new Setting.Toggle("colour-by-quality", "Colour by quality",
                        "Green under 100 ms, amber to 300, red above.", true)))
                .build());

        // -- PvP -----------------------------------------------------------

        out.add(HudStyle.declare(FathomModule.builder("cps")
                .name("CPS")
                .description("Clicks per second.")
                .category(Category.PVP)
                .icon(Icon.MOUSE)
                .widget("cps")
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("mode", "Buttons", "", "Both", "Both", "Left", "Right"))
                .setting(new Setting.Whole("window", "Sample window",
                        "How far back to count. One second is what everybody means by CPS.",
                        1, 1, 5, 1, "s"))
                .setting(new Setting.Toggle("label", "Label", "Write CPS after the number.", false)))
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
                        "Show clicks per second inside the mouse keys.", false))
                .setting(new Setting.Toggle("key.background", "Key Background", "", true))
                .setting(new Setting.Toggle("key.border", "Border", "", true))
                .setting(new Setting.Colour("key.pressed.fill", "Pressed Fill", "", 0x373B44))
                .setting(new Setting.Colour("key.unpressed.fill", "Unpressed Fill", "", 0x111318))
                .setting(new Setting.Colour("key.pressed.text", "Pressed Text", "", 0xFFFFFF))
                .setting(new Setting.Colour("key.unpressed.text", "Unpressed Text", "", 0xE7EAF0))
                .setting(new Setting.Colour("key.border.colour", "Border colour", "", 0x5C6573))
                .setting(new Setting.Whole("key.opacity", "Opacity", "", 80, 10, 100, 5, "%")))
                .build());

        out.add(HudStyle.declare(FathomModule.builder("armour")
                .name("Armour HUD")
                .description("Durability of your armour and held item.")
                .category(Category.PVP)
                .sensitivity(Sensitivity.SERVER_SENSITIVE)
                .icon(Icon.SHIELD)
                .widget("armour")
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Toggle("held", "Held item", "Include what is in your hand.", true))
                .setting(new Setting.Toggle("names", "Slot names", "Write Helmet, Chest and so on.", true))
                .setting(new Setting.Choice("style", "Style", "", "Percent", "Percent", "Remaining")))
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
                .setting(new Setting.Toggle("level", "Level", "Show the effect level.", true))
                .setting(new Setting.Toggle("duration", "Time left", "", true)))
                .build());

        // -- Utility -------------------------------------------------------

        out.add(HudStyle.declare(FathomModule.builder("toggle-sprint")
                .name("Sprint and sneak")
                .description("Whether you are sprinting or sneaking.")
                .category(Category.UTILITY)
                .icon(Icon.RUN)
                .widget("toggle-sprint")
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Toggle("sprint", "Show sprinting", "", true))
                .setting(new Setting.Toggle("sneak", "Show sneaking", "", true)))
                .build());

        // Zoom is the reason keybinds exist in this framework at all.
        //
        // Implemented from vanilla's own field of view rather than from anyone
        // else's zoom: `docs/research/mod-policies.md` is explicit that
        // OptiFine's implementation must not be copied, and there is nothing to
        // copy here anyway -- the whole of it is "multiply the FOV while a key
        // is held".
        out.add(FathomModule.builder("zoom")
                .name("Zoom")
                .description("Hold a key to narrow the field of view.")
                .category(Category.UTILITY)
                .icon(Icon.MAGNIFIER)
                .behaviour(Behaviour.ZOOM)
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .keybind(new Keybind("zoom", "Zoom", 46 /* C */))
                .setting(new Setting.Slider("amount", "Amount", "How far in.", 4, 1.5, 10, 0.5, 1, "x"))
                .setting(new Setting.Toggle("sensitivity", "Lower sensitivity",
                        "Slow the mouse in proportion to the zoom.", true))
                .setting(new Setting.Toggle("smooth", "Smooth camera",
                        "Ease the camera while zoomed.", false))
                .setting(new Setting.Toggle("animate", "Animate",
                        "Ease in and out rather than snapping.", true))
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
                .build());

        // -- Visual --------------------------------------------------------

        out.add(FathomModule.builder("crosshair")
                .name("Crosshair")
                .description("A crosshair of your own choosing.")
                .category(Category.VISUAL)
                .icon(Icon.CROSSHAIR)
                .widget("crosshair")
                .behaviour(Behaviour.CROSSHAIR)
                .needs(Compatibility.IN_GAME)
                .implementation(Implementation.WORKING)
                .setting(new Setting.Choice("shape", "Shape", "", "Cross",
                        "Vanilla", "Cross", "Dot", "Circle", "Tee"))
                .setting(new Setting.Slider("size", "Size", "", 9, 3, 21, 2, 0, "px"))
                .setting(new Setting.Slider("gap", "Gap", "Space at the centre.", 2, 0, 8, 1, 0, "px"))
                .setting(new Setting.Toggle("outline", "Outline",
                        "A dark edge, so it reads on any background.", true))
                .setting(new Setting.Toggle("hide-vanilla", "Hide vanilla crosshair",
                        "Otherwise both are drawn, one over the other.", true))
                .setting(new Setting.Colour("colour", "Colour", "", 0xFFFFFF))
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
