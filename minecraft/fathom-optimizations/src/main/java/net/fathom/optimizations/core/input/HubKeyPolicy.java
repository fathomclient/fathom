package net.fathom.optimizations.core.input;

/**
 * What a press of the Hub key is allowed to do, given what is on screen.
 *
 * <h2>Why this is a rule and not a check in the poll</h2>
 *
 * <p>The Hub key is polled from the physical keyboard every frame, ahead of
 * whatever screen is current, so it sees presses that were meant for something
 * else. Right Shift is the default, and Right Shift is also how a capital letter
 * is typed: before this existed, typing "Hello" in chat opened the Hub over the
 * chat, and typing a capital in the Hub's own search box closed it. Opening it
 * over a chest was worse — the container screen was torn down on the client
 * while the server still had it open.
 *
 * <p>So the decision is made here, from a description of the screen rather than
 * the screen itself, which keeps it free of Minecraft types and lets the tests
 * assert every row of the table below.
 *
 * <table>
 *   <caption>The rule</caption>
 *   <tr><th>On screen</th><th>Press does</th></tr>
 *   <tr><td>nothing (playing)</td><td>open</td></tr>
 *   <tr><td>a menu with nothing to type into</td><td>open</td></tr>
 *   <tr><td>anything that takes text</td><td>nothing — it is typing</td></tr>
 *   <tr><td>a container (chest, inventory, anvil)</td><td>nothing</td></tr>
 *   <tr><td>the Hub, idle</td><td>close</td></tr>
 *   <tr><td>the Hub, typing or listening for a key</td><td>nothing</td></tr>
 *   <tr><td>another Fathom screen</td><td>nothing — it owns its keys</td></tr>
 * </table>
 */
public final class HubKeyPolicy {

    /** What kind of screen is current, as far as the Hub key cares. */
    public enum Screen {
        /** No screen: the player is in the world. */
        NONE,
        /** A vanilla or modded screen with no text input: options, pause, death. */
        MENU,
        /** Chat, a sign, a book, a server address — anything the key may be typing into. */
        TEXT_INPUT,
        /** A container screen. The server has it open too. */
        CONTAINER,
        /** The Hub itself. */
        HUB,
        /** The HUD editor, the Fathom title screen, a proof screen. */
        OTHER_FATHOM
    }

    /** The outcome of a press. */
    public enum Action {
        OPEN,
        CLOSE,
        IGNORE
    }

    private HubKeyPolicy() {}

    /**
     * @param screen what is current
     * @param hubBusy only read for {@link Screen#HUB}: whether the Hub is
     *     typing into a field or listening for a key, in which case the press
     *     belongs to that
     */
    public static Action decide(Screen screen, boolean hubBusy) {
        if (screen == null) {
            return Action.IGNORE;
        }
        switch (screen) {
            case NONE:
            case MENU:
                return Action.OPEN;
            case HUB:
                return hubBusy ? Action.IGNORE : Action.CLOSE;
            case TEXT_INPUT:
            case CONTAINER:
            case OTHER_FATHOM:
            default:
                return Action.IGNORE;
        }
    }
}
