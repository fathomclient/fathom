package net.fathom.optimizations.core.module;

/**
 * The settings every HUD module has, declared once.
 *
 * <h2>Why this is not written out per module</h2>
 *
 * <p>Ten HUD modules each want a text colour, a plate behind the text, a colour
 * and an opacity for that plate, padding, a corner radius and a shadow. Written
 * per module that is seventy declarations, seventy chances to name one of them
 * differently, and a client where the Clock's background setting is called
 * something else from the FPS one and behaves slightly differently.
 *
 * <p>So a module says {@link #declare} and gets all of them, with the same ids,
 * the same defaults and the same order — and {@code TextWidget} reads them
 * through the accessors here, so the drawing agrees with the declaration by
 * construction.
 *
 * <h2>What is not here</h2>
 *
 * <p>Position, anchor and scale. Those live in the HUD layout, because they are
 * spatial: they are set by dragging in the editor against the scene the widget
 * has to sit over, not by a number on a settings page. The page links to the editor
 * instead of duplicating it.
 */
public final class HudStyle {

    public static final String FONT = "font";
    public static final String[] FONT_CHOICES = {"Minecraft", "Fathom"};
    public static final String TEXT_COLOUR = "text.colour";
    public static final String TEXT_SHADOW = "text.shadow";
    public static final String BACKGROUND = "background";
    public static final String[] MATERIAL_CHOICES = {"None", "Light", "Standard", "Dense"};
    public static final String BACKGROUND_COLOUR = "background.colour";
    public static final String BACKGROUND_OPACITY = "background.opacity";
    public static final String PADDING = "padding";
    public static final String RADIUS = "radius";
    public static final String DROP_SHADOW = "shadow";
    public static final String BORDER = "border";
    public static final String BORDER_COLOUR = "border.colour";
    public static final String BORDER_WIDTH = "border.width";
    /** Hidden while the debug screen is open, which covers the top-left anyway. */
    public static final String HIDE_DEBUG = "hide.debug";
    /** Hidden while a screen other than chat is open. */
    public static final String HIDE_SCREENS = "hide.screens";

    // The neutral Graphite HUD: value #F2F2F2 on a #101010 plate. These were
    // the warm golden-hour cream and umber, left behind when the rest of the
    // product moved to neutral glass.
    private static final int DEFAULT_TEXT_COLOUR = 0xF2F2F2;
    private static final int DEFAULT_BACKGROUND_COLOUR = 0x101010;
    private static final int DEFAULT_OPACITY = 100;
    private static final int DEFAULT_PADDING = 4;
    private static final int DEFAULT_RADIUS = 4;
    private static final int DEFAULT_BORDER_COLOUR = 0x5C5C5C;

    private HudStyle() {}

    /**
     * Add the common settings to a module.
     *
     * <p>Called last in a module's declaration, so its own settings come first
     * on the detail page: what the module <em>is</em> above how it looks.
     */
    public static FathomModule.Builder declare(FathomModule.Builder builder) {
        return declare(builder, "Material", "Standard");
    }

    /** A quieter substrate for short status labels such as sprint and sneak. */
    public static FathomModule.Builder declareLight(FathomModule.Builder builder) {
        return declare(builder, "Material", "Light");
    }

    /** The same shared style, with Keystrokes' outer plate named unambiguously. */
    public static FathomModule.Builder declarePanel(FathomModule.Builder builder) {
        // Keystrokes owns pressed and unpressed text colours, so it deliberately
        // does not also expose the generic Text colour row. Every setting this
        // declaration adds is consumed by its renderer.
        return builder
                .setting(new Setting.Choice(FONT, "Font", "Typeface for this HUD module.",
                        FONT_CHOICES[0], FONT_CHOICES))
                .setting(new Setting.Toggle(TEXT_SHADOW, "Text shadow", "A dark edge under the letters.", true))
                .setting(material("Panel material", "None"))
                .setting(new Setting.Colour(BACKGROUND_COLOUR, "Panel background colour", "", DEFAULT_BACKGROUND_COLOUR))
                .setting(new Setting.Whole(
                        BACKGROUND_OPACITY, "Panel material strength", "", DEFAULT_OPACITY, 0, 100, 5, "%"))
                .setting(new Setting.Whole(PADDING, "Padding", "Space between the keys and the panel.",
                        DEFAULT_PADDING, 0, 10, 1, "px"))
                .setting(new Setting.Whole(RADIUS, "Corner radius", "", DEFAULT_RADIUS, 0, 8, 1, "px"))
                .setting(new Setting.Toggle(DROP_SHADOW, "Drop shadow", "A soft edge under the panel.", false))
                .setting(new Setting.Toggle(BORDER, "Panel border", "An outline around the panel.", false))
                .setting(new Setting.Colour(BORDER_COLOUR, "Panel border colour", "", DEFAULT_BORDER_COLOUR))
                .setting(new Setting.Whole(BORDER_WIDTH, "Panel border width", "", 1, 1, 3, 1, "px"))
                .setting(hideDebug())
                .setting(hideScreens());
    }

    /**
     * The common settings with defaults chosen to look like something vanilla
     * already draws: for a module that replaces a vanilla element, whose first
     * frame switched on should look like the element it replaced.
     */
    public static FathomModule.Builder declareVanilla(FathomModule.Builder builder, int textColour,
            boolean shadow, int padding) {
        return declare(builder, "Material", "None", textColour, shadow, padding);
    }

    private static FathomModule.Builder declare(
            FathomModule.Builder builder, String backgroundLabel, String materialDefault) {
        return declare(builder, backgroundLabel, materialDefault, DEFAULT_TEXT_COLOUR, true, DEFAULT_PADDING);
    }

    private static FathomModule.Builder declare(FathomModule.Builder builder, String backgroundLabel,
            String materialDefault, int textColour, boolean shadow, int padding) {
        return builder
                .setting(new Setting.Choice(FONT, "Font", "Typeface for this HUD module.",
                        FONT_CHOICES[0], FONT_CHOICES))
                .setting(new Setting.Colour(TEXT_COLOUR, "Text colour", "", textColour))
                .setting(new Setting.Toggle(TEXT_SHADOW, "Text shadow", "A dark edge under the letters.", shadow))
                .setting(material(backgroundLabel, materialDefault))
                .setting(new Setting.Colour(BACKGROUND_COLOUR, "Background colour", "", DEFAULT_BACKGROUND_COLOUR))
                .setting(new Setting.Whole(
                        BACKGROUND_OPACITY, "Material strength", "", DEFAULT_OPACITY, 0, 100, 5, "%"))
                .setting(new Setting.Whole(PADDING, "Padding", "Space between the text and the plate.",
                        padding, 0, 10, 1, "px"))
                .setting(new Setting.Whole(RADIUS, "Corner radius", "", DEFAULT_RADIUS, 0, 8, 1, "px"))
                .setting(new Setting.Toggle(DROP_SHADOW, "Drop shadow", "A soft edge under the plate.", false))
                .setting(new Setting.Toggle(BORDER, "Border", "An outline around the plate.", false))
                .setting(new Setting.Colour(BORDER_COLOUR, "Border colour", "", DEFAULT_BORDER_COLOUR))
                .setting(new Setting.Whole(BORDER_WIDTH, "Border width", "", 1, 1, 3, 1, "px"))
                .setting(hideDebug())
                .setting(hideScreens());
    }

    /** Keep the id stable and accept the old toggle's stored values. */
    private static Setting.Choice material(String label, String fallback) {
        return new Setting.Choice(BACKGROUND, label,
                "The plate behind the text. None draws the text alone.", fallback, MATERIAL_CHOICES)
                .alias("true", "Standard")
                .alias("false", "None");
    }

    /** Whether a module was declared with the common settings. */
    private static Setting.Toggle hideDebug() {
        return new Setting.Toggle(HIDE_DEBUG, "Hide with F3", "While the debug screen is open.", false);
    }

    private static Setting.Toggle hideScreens() {
        return new Setting.Toggle(HIDE_SCREENS, "Hide behind menus",
                "While the inventory or another menu is open. Chat does not count.", false);
    }

    /**
     * Whether a module's own visibility rules hide it right now.
     *
     * @param debugOpen whether the debug screen is showing
     * @param menuOpen whether a screen other than chat is open
     */
    public static boolean hiddenBy(FathomModule module, boolean debugOpen, boolean menuOpen) {
        if (module == null) {
            return false;
        }
        return (debugOpen && module.toggle(HIDE_DEBUG, false)) || (menuOpen && module.toggle(HIDE_SCREENS, false));
    }

    public static boolean declaredOn(FathomModule module) {
        return module != null
                && module.setting(FONT) != null
                && module.setting(BACKGROUND) != null
                && module.setting(PADDING) != null;
    }

    // -----------------------------------------------------------------------
    // Reading, at frame rate
    // -----------------------------------------------------------------------

    /** Per-module typography; Minecraft is intentionally the fresh-profile default. */
    public static boolean fontIsFathom(FathomModule module) {
        return module != null && "Fathom".equals(module.choice(FONT, FONT_CHOICES[0]));
    }

    public static int textColour(FathomModule module) {
        return module == null ? DEFAULT_TEXT_COLOUR : module.colour(TEXT_COLOUR, DEFAULT_TEXT_COLOUR);
    }

    public static boolean textShadow(FathomModule module) {
        return module == null || module.toggle(TEXT_SHADOW, true);
    }

    public static boolean background(FathomModule module) {
        return !"None".equals(material(module));
    }

    public static String material(FathomModule module) {
        return module == null ? "Standard" : module.choice(BACKGROUND, "Standard");
    }

    public static int backgroundColour(FathomModule module) {
        return module == null ? DEFAULT_BACKGROUND_COLOUR : module.colour(BACKGROUND_COLOUR, DEFAULT_BACKGROUND_COLOUR);
    }

    /** 0 to 255, ready to become the alpha byte of a colour. */
    public static int backgroundAlpha(FathomModule module) {
        int percent = module == null
                ? DEFAULT_OPACITY
                : (int) Math.round(module.number(BACKGROUND_OPACITY, DEFAULT_OPACITY));
        return Math.max(0, Math.min(255, percent * 255 / 100));
    }

    public static int padding(FathomModule module) {
        return module == null ? DEFAULT_PADDING : (int) Math.round(module.number(PADDING, DEFAULT_PADDING));
    }

    public static int radius(FathomModule module) {
        return module == null ? DEFAULT_RADIUS : (int) Math.round(module.number(RADIUS, DEFAULT_RADIUS));
    }

    public static boolean dropShadow(FathomModule module) {
        return module != null && module.toggle(DROP_SHADOW, false);
    }

    /**
     * Why a style setting has no effect with the module's other settings as
     * they are, or null when it does. The page greys such a row and says why,
     * rather than offering a control that silently changes nothing: with no
     * material there is no plate to colour, shadow or round, and with no
     * border there is no outline to colour or widen.
     */
    public static String inactiveReason(FathomModule module, String settingId) {
        if (module == null || module.setting(BACKGROUND) == null) {
            return null;
        }
        boolean plate = background(module);
        boolean outline = border(module);
        if (BACKGROUND_COLOUR.equals(settingId) || BACKGROUND_OPACITY.equals(settingId)
                || DROP_SHADOW.equals(settingId)) {
            return plate ? null : NEEDS_MATERIAL;
        }
        if (RADIUS.equals(settingId)) {
            return plate || outline ? null : NEEDS_MATERIAL_OR_BORDER;
        }
        if (BORDER_COLOUR.equals(settingId) || BORDER_WIDTH.equals(settingId)) {
            return outline ? null : NEEDS_BORDER;
        }
        return null;
    }

    static final String NEEDS_MATERIAL = "No effect while the material is None.";
    static final String NEEDS_MATERIAL_OR_BORDER = "No effect without a material or a border.";
    static final String NEEDS_BORDER = "No effect while Border is off.";

    /** Whether an outline is drawn around the plate. Independent of the plate itself. */
    public static boolean border(FathomModule module) {
        return module != null && module.toggle(BORDER, false);
    }

    public static int borderColour(FathomModule module) {
        return module == null ? DEFAULT_BORDER_COLOUR : module.colour(BORDER_COLOUR, DEFAULT_BORDER_COLOUR);
    }

    public static int borderWidth(FathomModule module) {
        return module == null ? 1 : (int) Math.round(module.number(BORDER_WIDTH, 1));
    }
}
