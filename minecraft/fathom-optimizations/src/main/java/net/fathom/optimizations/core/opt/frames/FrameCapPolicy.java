package net.fathom.optimizations.core.opt.frames;

/**
 * The frame limit Fathom returns after vanilla has made its decision.
 *
 * <p>Minecraft 1.8.9 has one special rule: with no world and a screen open it
 * returns 30 instead of the user's video-setting limit. That rule belongs to
 * the title screen only. Inventory, containers, chat, the pause screen and an
 * in-world Fathom Hub must all retain the user's limit.
 *
 * <p>Fathom's own no-world screens are interactive, so they opt out of the
 * vanilla title-screen cap. The optional unfocused cap is the only policy here
 * that lowers an otherwise valid user limit.
 */
public final class FrameCapPolicy {

    public static final String UNFOCUSED_ID = "input.unfocused-fps-cap";
    public static final int UNFOCUSED_CAP = 30;
    public static final String[] UNFOCUSED_CHOICES = {"10", "30", "60", "Unlimited"};
    public static final String UNFOCUSED_CHOICE = "limit";
    public static final int VANILLA_MENU_CAP = 30;

    private FrameCapPolicy() {}

    /** A configured cap, or zero for Unlimited/an invalid value. */
    public static int capFor(String choice) {
        if ("10".equals(choice)) {
            return 10;
        }
        if ("30".equals(choice)) {
            return 30;
        }
        if ("60".equals(choice)) {
            return 60;
        }
        if ("120".equals(choice)) {
            return 120;
        }
        return 0;
    }

    /**
     * Resolve the active limit.
     *
     * @param vanillaCap the value returned by Minecraft
     * @param userCap the current value in the video settings
     * @param focused whether the display owns focus
     * @param unfocusedEnabled whether Fathom's background-window cap is enabled
     * @param unfocusedCap configured background-window cap, or zero for none
     * @param inWorld true for gameplay and every screen over a loaded world
     * @param fathomTitle true only for Fathom's interactive no-world screen
     */
    public static int effective(
            int vanillaCap,
            int userCap,
            boolean focused,
            boolean unfocusedEnabled,
            int unfocusedCap,
            boolean inWorld,
            boolean fathomTitle) {
        int cap = (inWorld || fathomTitle) ? positive(userCap, vanillaCap) : vanillaCap;
        if (unfocusedEnabled && !focused && unfocusedCap > 0) {
            cap = Math.min(cap, unfocusedCap);
        }
        return positive(cap, vanillaCap);
    }

    private static int positive(int preferred, int fallback) {
        return preferred > 0 ? preferred : fallback;
    }
}
