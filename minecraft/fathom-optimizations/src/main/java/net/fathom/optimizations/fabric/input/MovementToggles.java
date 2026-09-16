package net.fathom.optimizations.fabric.input;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;
import net.minecraft.entity.player.ClientPlayerEntity;

import net.fathom.optimizations.core.input.MovementToggle;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.Runtime;

/**
 * Toggle Sprint and Toggle Sneak, applied to the running game.
 *
 * <h2>The whole of what this changes</h2>
 *
 * <p>Two {@code KeyBinding.isPressed} reads: the sprint key where the player's
 * movement tick decides whether to start sprinting, and the sneak key where
 * keyboard input decides whether to sneak. While a toggle is latched those reads
 * say "held". Everything downstream is vanilla's — food, blindness, using an
 * item, walking forwards, the sneak slowdown — so the game cannot be made to do
 * anything the physical key could not.
 *
 * <p>Nothing else in the game reads these through Fathom. The keystroke display
 * reads the real key, so it shows what the player's hands are doing.
 *
 * <h2>When a latch is let go</h2>
 *
 * <ul>
 *   <li>the module is switched off, or its mode set back to Hold;
 *   <li>the player dies, respawns, changes world or disconnects
 *       ({@link GameSession} calls {@link #release});
 *   <li>for sneak: never while flying or riding — the latch is kept, but not
 *       applied, because there sneak means descend or dismount, and a latched
 *       dismount would throw the player off every mount they climbed onto.
 * </ul>
 */
public final class MovementToggles {

    private static final MovementToggle SPRINT = new MovementToggle();
    private static final MovementToggle SNEAK = new MovementToggle();

    /** Decided once per player tick, read by both redirects in that tick. */
    private static boolean sprintToggle;
    private static boolean sneakToggle;
    private static boolean sneakApplies;

    private MovementToggles() {}

    private static FathomModule module() {
        return Runtime.features() == null ? null : Runtime.features().find("toggle-sprint");
    }

    /** Advance both toggles. Called at the start of the player's movement tick. */
    public static void tick(ClientPlayerEntity player) {
        MinecraftClient client = MinecraftClient.getInstance();
        GameOptions options = client == null ? null : client.options;
        FathomModule module = module();
        boolean active = module != null && module.enabled() && module.switchable() && options != null;
        sprintToggle = active && "Toggle".equals(module.choice("sprint.mode", "Hold"));
        sneakToggle = active && "Toggle".equals(module.choice("sneak.mode", "Hold"));

        if (player == null || player.getHealth() <= 0f || player.removed) {
            release();
            return;
        }
        sneakApplies = !player.abilities.flying && !player.hasVehicle();
        if (options == null) {
            return;
        }
        SPRINT.tick(sprintToggle, options.sprintKey.isPressed(), true);
        SNEAK.tick(sneakToggle, options.sneakKey.isPressed(), sneakApplies);
    }

    /** What the movement tick should believe about the sprint key. */
    public static boolean sprintKey(boolean pressed) {
        return SPRINT.held(sprintToggle, pressed, true);
    }

    /** What keyboard input should believe about the sneak key. */
    public static boolean sneakKey(boolean pressed) {
        return SNEAK.held(sneakToggle, pressed, sneakApplies);
    }

    /** Drop both latches. */
    public static void release() {
        SPRINT.release();
        SNEAK.release();
    }

    /** Whether sprint is latched on by a toggle right now, for the status text. */
    public static boolean sprintLatched() {
        return sprintToggle && SPRINT.latched();
    }

    /** Whether sneak is latched on and being applied, for the status text. */
    public static boolean sneakLatched() {
        return sneakToggle && sneakApplies && SNEAK.latched();
    }
}
