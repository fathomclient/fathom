package net.fathom.optimizations.core.module;

/**
 * The settings that belong to the client itself rather than to any feature.
 *
 * <h2>Why they are not features</h2>
 *
 * <p>A feature can be switched off. "How big is the interface" has no off, so
 * modelling it as a feature would create a state — scale off — that means
 * nothing.
 *
 * <h2>Why they are written down</h2>
 *
 * <p>Because anything the interface lets somebody change has to survive a
 * restart or it should not be offered. An earlier version kept the in-game theme
 * in a field nothing persisted, so choosing one lasted exactly as long as the
 * session.
 *
 * <p>The theme is gone entirely now: the hub's background is black, the world
 * behind it is darkened, and a menu inside a game does not need decoration that
 * competes with the game.
 */
public final class ClientPrefs {

    /**
     * How large Fathom's interface is.
     *
     * <p>Deliberately separate from Minecraft's GUI Scale, which is an integer
     * that scales the whole game's interface — somebody who plays on Large
     * should not get a Fathom Hub with 40-pixel type.
     *
     * <p><b>Auto</b> derives the scale from the framebuffer: the interface is
     * the same physical size on a 1080p laptop and a 1440p monitor. It sits a
     * step under the full window-derived size (0.9 of it): at 100% the Hub
     * read as oversized on a 1080p screen. The percentages multiply the
     * window-derived size directly, for people who want it smaller or larger
     * than the automatic answer; 65% is the compact end.
     *
     * <p>Stored by label, so adding an option leaves every saved choice as it
     * was.
     */
    public static final String[] SCALE_CHOICES = {
        "Auto", "65%", "75%", "85%", "100%", "115%", "125%"
    };
    public static final String[] MENU_BACKGROUND_CHOICES = {"High", "Auto", "Low", "Off"};
    /** What RShift presents behind the interface while a world is visible. */
    public static final String[] RSHIFT_BACKGROUND_CHOICES = {"Fathom background", "Gameplay glass"};
    /** Restrained presets rather than exposing the blur implementation as a shader laboratory. */
    public static final String[] RSHIFT_BLUR_CHOICES = {"Soft", "Balanced", "Strong"};
    /** The legibility scrim over the blurred gameplay frame. */
    public static final String[] RSHIFT_DIM_CHOICES = {"Light", "Balanced", "Deep"};

    // The ends are UiScale.MIN_PREFERENCE and MAX_PREFERENCE. Offering a choice
    // the renderer would clamp away would be a control that lies about what it
    // does, so the two are kept in step by a test rather than by memory.
    private static final float[] SCALE_VALUES = {0.9f, 0.65f, 0.75f, 0.85f, 1f, 1.15f, 1.25f};

    private static final int DEFAULT_SCALE = 0;

    private int scaleChoice = DEFAULT_SCALE;
    private boolean scaleChanged;
    private boolean highQualityUi = true;
    private boolean qualityChanged;
    private static final int DEFAULT_MENU_BACKGROUND = 0;
    private int menuBackgroundChoice = DEFAULT_MENU_BACKGROUND;
    private boolean menuBackgroundChanged;
    private static final int DEFAULT_RSHIFT_BACKGROUND = 1;
    private static final int DEFAULT_RSHIFT_BLUR = 1;
    private static final int DEFAULT_RSHIFT_DIM = 1;
    private static final float[] RSHIFT_BLUR_VALUES = {12f, 18f, 26f};
    private static final float[] RSHIFT_DIM_VALUES = {0.24f, 0.34f, 0.44f};
    private int rshiftBackgroundChoice = DEFAULT_RSHIFT_BACKGROUND;
    private int rshiftBlurChoice = DEFAULT_RSHIFT_BLUR;
    private int rshiftDimChoice = DEFAULT_RSHIFT_DIM;
    private boolean rshiftBackgroundChanged;
    private boolean rshiftBlurChanged;
    private boolean rshiftDimChanged;

    /** Which of {@link #SCALE_CHOICES} is selected. */
    public int scaleChoice() {
        return scaleChoice;
    }

    public String scaleLabel() {
        return SCALE_CHOICES[scaleChoice];
    }

    /** Whether the scale follows the window rather than a fixed percentage. */
    public boolean scaleIsAutomatic() {
        return scaleChoice == 0;
    }

    /** The multiplier on the automatic scale. */
    public float uiScale() {
        return SCALE_VALUES[scaleChoice];
    }

    public void setScaleChoice(int index) {
        if (index < 0 || index >= SCALE_CHOICES.length || index == scaleChoice) {
            return;
        }
        scaleChoice = index;
        scaleChanged = true;
    }

    /** Select by the label that was written to the file. */
    public void setScaleLabel(String label) {
        if (label == null) {
            return;
        }
        String wanted = label.trim();
        for (int index = 0; index < SCALE_CHOICES.length; index++) {
            if (SCALE_CHOICES[index].equalsIgnoreCase(wanted)) {
                setScaleChoice(index);
                return;
            }
        }
        // An unrecognised value — a hand-edited file, or a setting from a later
        // version. Automatic is the safe answer, and it is what a first run
        // would have used anyway.
    }

    public boolean scaleChangedFromDefault() {
        return scaleChanged && scaleChoice != DEFAULT_SCALE;
    }

    /**
     * Which typeface the HUD widgets draw in.
     *
     * <p>This legacy client-wide preference remains readable for old profiles,
     * but no longer controls normal HUD rendering. New HUD typography is owned
     * by each module, whose fresh-profile default is Minecraft. Keeping this
     * wire shape unchanged avoids rewriting old profile data.
     */
    public static final String[] HUD_FONT_CHOICES = {"Fathom", "Minecraft"};

    private static final int DEFAULT_HUD_FONT = 0;

    private int hudFont = DEFAULT_HUD_FONT;
    private boolean hudFontChanged;

    /** Which of {@link #HUD_FONT_CHOICES} is selected. */
    public int hudFontChoice() {
        return hudFont;
    }

    public String hudFontLabel() {
        return HUD_FONT_CHOICES[hudFont];
    }

    public boolean hudFontIsFathom() {
        return hudFont == 0;
    }

    public void setHudFontChoice(int index) {
        if (index < 0 || index >= HUD_FONT_CHOICES.length || index == hudFont) {
            return;
        }
        hudFont = index;
        hudFontChanged = true;
    }

    /** Accepts the stored label, and ignores anything else rather than throwing. */
    public void setHudFontLabel(String wanted) {
        if (wanted == null) {
            return;
        }
        for (int index = 0; index < HUD_FONT_CHOICES.length; index++) {
            if (HUD_FONT_CHOICES[index].equalsIgnoreCase(wanted.trim())) {
                setHudFontChoice(index);
                return;
            }
        }
    }

    public boolean hudFontChangedFromDefault() {
        return hudFontChanged && hudFont != DEFAULT_HUD_FONT;
    }

    /** Whether optional framebuffer backdrop effects are enabled. */
    public boolean highQualityUi() {
        return highQualityUi;
    }

    public void setHighQualityUi(boolean enabled) {
        if (highQualityUi == enabled) {
            return;
        }
        highQualityUi = enabled;
        qualityChanged = true;
    }

    public boolean qualityChangedFromDefault() {
        return qualityChanged && !highQualityUi;
    }

    public int menuBackgroundChoice() {
        return menuBackgroundChoice;
    }

    public String menuBackgroundLabel() {
        return MENU_BACKGROUND_CHOICES[menuBackgroundChoice];
    }

    public void setMenuBackgroundChoice(int index) {
        if (index < 0 || index >= MENU_BACKGROUND_CHOICES.length
                || index == menuBackgroundChoice) {
            return;
        }
        menuBackgroundChoice = index;
        menuBackgroundChanged = true;
    }

    public void setMenuBackgroundLabel(String wanted) {
        if (wanted == null) {
            return;
        }
        for (int index = 0; index < MENU_BACKGROUND_CHOICES.length; index++) {
            if (MENU_BACKGROUND_CHOICES[index].equalsIgnoreCase(wanted.trim())) {
                setMenuBackgroundChoice(index);
                return;
            }
        }
    }

    public boolean menuBackgroundChangedFromDefault() {
        return menuBackgroundChanged && menuBackgroundChoice != DEFAULT_MENU_BACKGROUND;
    }

    public int rshiftBackgroundChoice() {
        return rshiftBackgroundChoice;
    }

    public String rshiftBackgroundLabel() {
        return RSHIFT_BACKGROUND_CHOICES[rshiftBackgroundChoice];
    }

    public boolean gameplayGlass() {
        return rshiftBackgroundChoice == 1;
    }

    public void setRshiftBackgroundChoice(int index) {
        if (index < 0 || index >= RSHIFT_BACKGROUND_CHOICES.length || index == rshiftBackgroundChoice) {
            return;
        }
        rshiftBackgroundChoice = index;
        rshiftBackgroundChanged = true;
    }

    public void setRshiftBackgroundLabel(String wanted) {
        rshiftBackgroundChoice = choice(wanted, RSHIFT_BACKGROUND_CHOICES, DEFAULT_RSHIFT_BACKGROUND);
        rshiftBackgroundChanged = rshiftBackgroundChoice != DEFAULT_RSHIFT_BACKGROUND;
    }

    public boolean rshiftBackgroundChangedFromDefault() {
        return rshiftBackgroundChanged && rshiftBackgroundChoice != DEFAULT_RSHIFT_BACKGROUND;
    }

    public int rshiftBlurChoice() {
        return rshiftBlurChoice;
    }

    public String rshiftBlurLabel() {
        return RSHIFT_BLUR_CHOICES[rshiftBlurChoice];
    }

    public float rshiftBlur() {
        return RSHIFT_BLUR_VALUES[rshiftBlurChoice];
    }

    public void setRshiftBlurChoice(int index) {
        if (index < 0 || index >= RSHIFT_BLUR_CHOICES.length || index == rshiftBlurChoice) {
            return;
        }
        rshiftBlurChoice = index;
        rshiftBlurChanged = true;
    }

    public void setRshiftBlurLabel(String wanted) {
        rshiftBlurChoice = choice(wanted, RSHIFT_BLUR_CHOICES, DEFAULT_RSHIFT_BLUR);
        rshiftBlurChanged = rshiftBlurChoice != DEFAULT_RSHIFT_BLUR;
    }

    public boolean rshiftBlurChangedFromDefault() {
        return rshiftBlurChanged && rshiftBlurChoice != DEFAULT_RSHIFT_BLUR;
    }

    public int rshiftDimChoice() {
        return rshiftDimChoice;
    }

    public String rshiftDimLabel() {
        return RSHIFT_DIM_CHOICES[rshiftDimChoice];
    }

    public float rshiftDim() {
        return RSHIFT_DIM_VALUES[rshiftDimChoice];
    }

    public void setRshiftDimChoice(int index) {
        if (index < 0 || index >= RSHIFT_DIM_CHOICES.length || index == rshiftDimChoice) {
            return;
        }
        rshiftDimChoice = index;
        rshiftDimChanged = true;
    }

    public void setRshiftDimLabel(String wanted) {
        rshiftDimChoice = choice(wanted, RSHIFT_DIM_CHOICES, DEFAULT_RSHIFT_DIM);
        rshiftDimChanged = rshiftDimChoice != DEFAULT_RSHIFT_DIM;
    }

    public boolean rshiftDimChangedFromDefault() {
        return rshiftDimChanged && rshiftDimChoice != DEFAULT_RSHIFT_DIM;
    }

    private static int choice(String wanted, String[] options, int current) {
        if (wanted == null) {
            return current;
        }
        for (int index = 0; index < options.length; index++) {
            if (options[index].equalsIgnoreCase(wanted.trim())) {
                return index;
            }
        }
        return current;
    }
}
