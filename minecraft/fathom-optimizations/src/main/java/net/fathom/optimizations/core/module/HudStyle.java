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
 * has to sit over, not by a number in a drawer. The drawer links to the editor
 * instead of duplicating it.
 */
public final class HudStyle {

    public static final String FONT = "font";
    public static final String[] FONT_CHOICES = {"Minecraft", "Fathom"};
    public static final String TEXT_COLOUR = "text.colour";
    public static final String TEXT_SHADOW = "text.shadow";
    public static final String BACKGROUND = "background";
    public static final String BACKGROUND_COLOUR = "background.colour";
    public static final String BACKGROUND_OPACITY = "background.opacity";
    public static final String PADDING = "padding";
    public static final String RADIUS = "radius";
    public static final String DROP_SHADOW = "shadow";

    private static final int DEFAULT_TEXT_COLOUR = 0xFFFFFF;
    private static final int DEFAULT_BACKGROUND_COLOUR = 0x000000;
    private static final int DEFAULT_OPACITY = 40;
    private static final int DEFAULT_PADDING = 3;
    private static final int DEFAULT_RADIUS = 0;

    private HudStyle() {}

    /**
     * Add the common settings to a module.
     *
     * <p>Called last in a module's declaration, so its own settings come first
     * in the drawer: what the module <em>is</em> above how it looks.
     */
    public static FathomModule.Builder declare(FathomModule.Builder builder) {
        return declare(builder, "Background", true);
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
                .setting(new Setting.Toggle(BACKGROUND, "Panel Background", "A plate behind the module.", false))
                .setting(new Setting.Colour(BACKGROUND_COLOUR, "Panel background colour", "", DEFAULT_BACKGROUND_COLOUR))
                .setting(new Setting.Whole(
                        BACKGROUND_OPACITY, "Panel background opacity", "", DEFAULT_OPACITY, 0, 100, 5, "%"))
                .setting(new Setting.Whole(PADDING, "Padding", "Space between the keys and the panel.",
                        DEFAULT_PADDING, 0, 10, 1, "px"))
                .setting(new Setting.Whole(RADIUS, "Corner radius", "", DEFAULT_RADIUS, 0, 8, 1, "px"))
                .setting(new Setting.Toggle(DROP_SHADOW, "Drop shadow", "A soft edge under the panel.", false));
    }

    private static FathomModule.Builder declare(
            FathomModule.Builder builder, String backgroundLabel, boolean backgroundDefault) {
        return builder
                .setting(new Setting.Choice(FONT, "Font", "Typeface for this HUD module.",
                        FONT_CHOICES[0], FONT_CHOICES))
                .setting(new Setting.Colour(TEXT_COLOUR, "Text colour", "", DEFAULT_TEXT_COLOUR))
                .setting(new Setting.Toggle(TEXT_SHADOW, "Text shadow", "A dark edge under the letters.", true))
                .setting(new Setting.Toggle(BACKGROUND, backgroundLabel, "A plate behind the module.",
                        backgroundDefault))
                .setting(new Setting.Colour(BACKGROUND_COLOUR, "Background colour", "", DEFAULT_BACKGROUND_COLOUR))
                .setting(new Setting.Whole(
                        BACKGROUND_OPACITY, "Background opacity", "", DEFAULT_OPACITY, 0, 100, 5, "%"))
                .setting(new Setting.Whole(PADDING, "Padding", "Space between the text and the plate.",
                        DEFAULT_PADDING, 0, 10, 1, "px"))
                .setting(new Setting.Whole(RADIUS, "Corner radius", "", DEFAULT_RADIUS, 0, 8, 1, "px"))
                .setting(new Setting.Toggle(DROP_SHADOW, "Drop shadow", "A soft edge under the plate.", false));
    }

    /** Whether a module was declared with the common settings. */
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
        return module == null || module.toggle(BACKGROUND, true);
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
}
