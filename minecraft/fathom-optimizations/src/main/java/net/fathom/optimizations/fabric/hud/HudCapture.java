package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.util.ScreenshotUtils;

/**
 * Deterministic visual QA for the HUD as it is actually drawn.
 *
 * <h2>Why this is not the HUD editor's capture</h2>
 *
 * <p>The editor draws the widgets from inside a Fathom screen, where a Fathom
 * frame is already open, the world is behind a scrim and the GL state came from
 * a screen. The in-game path shares none of that: it runs at the return of
 * {@code InGameHud.render}, opens its own frame in the middle of the game's own
 * pass, and hands the state back to a renderer that is about to draw the next
 * world frame. That is the path players use and it is the one that can break, so
 * it is the one worth a screenshot.
 *
 * <p>Started with {@code -PcaptureUi=hud}, which loads the development world
 * from the main menu, waits for the player and a settled frame rate, saves a
 * framebuffer capture and stops the client.
 */
public final class HudCapture {

    /** Frames in the world before the shot, so terrain and the frame rate settle. */
    private static final int SETTLE = 140;

    private static int frames;
    private static boolean captured;

    private HudCapture() {}

    /** Read once: this runs every HUD frame, and the answer is fixed at launch. */
    private static final boolean REQUESTED = "hud".equalsIgnoreCase(System.getProperty("fathom.captureUi", ""));

    /** Worn armour, a held item and two effects, so the shot has something to show. */
    private static void dress(MinecraftClient client) {
        net.minecraft.entity.player.ClientPlayerEntity player = client.player;
        net.minecraft.item.ItemStack[] worn = player.inventory.armor;
        worn[3] = damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_HELMET), 0.15f);
        worn[2] = damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_CHESTPLATE), 0.55f);
        worn[1] = damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.LEATHER_LEGGINGS), 0.85f);
        worn[0] = damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.GOLDEN_BOOTS), 0.40f);
        player.inventory.main[player.inventory.selectedSlot] =
                damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_SWORD), 0.10f);
        // Speed II and Fire Resistance: one with a level, one without, both with
        // icons, which is every case the potion widget lays out.
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(1, 60 * 20, 1));
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(12, 8 * 20, 0));
    }

    private static net.minecraft.item.ItemStack damaged(net.minecraft.item.ItemStack stack, float wear) {
        stack.setDamage(Math.round(stack.getMaxDamage() * wear));
        return stack;
    }

    /** Whether this run was asked for an in-game HUD capture. */
    public static boolean requested() {
        return REQUESTED;
    }

    /**
     * Whether this run should dress the player for the shot.
     *
     * <p>The armour and potion widgets draw nothing without armour or effects,
     * so a capture of an empty-handed player is a capture of the two widgets
     * that changed most showing nothing. With {@code -PcaptureHudItems} the
     * development client puts worn armour, a held sword and two effects on the
     * player before the shot -- in the development environment only, on the
     * client's own copy of the player, so nothing is sent to a server and no
     * saved world is touched.
     */
    private static final boolean DRESS = Boolean.getBoolean("fathom.captureHudItems")
            && net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();

    /** Called once per drawn HUD frame. */
    public static void frame(MinecraftClient client) {
        if (!REQUESTED || captured || client == null || client.player == null) {
            return;
        }
        if (DRESS) {
            // Every frame: the integrated server owns the player's inventory and
            // syncs it back, so a one-off change is undone before the shot.
            dress(client);
        }
        if (frames == 0) {
            // Timing starts with the first in-world frame, not with the client,
            // so world load and terrain generation are not averaged into what a
            // HUD costs to draw.
            net.fathom.optimizations.core.instrument.Probe.begin();
        }
        if (++frames < SETTLE) {
            return;
        }
        captured = true;
        net.fathom.optimizations.core.instrument.Probe.end();
        WidgetProfile.report();
        String override = System.getProperty("fathom.hudFont", "").trim();
        String font = override.isEmpty() ? "per-module" : override.toLowerCase(java.util.Locale.ROOT);
        String name = "fathom-hud-" + font + "-" + client.width + "x" + client.height + ".png";
        ScreenshotUtils.saveScreenshot(client.runDirectory, name,
                client.width, client.height, client.getFramebuffer());
        // What the HUD costs with the configured per-module typefaces. A
        // command-line override is only used for controlled comparison runs.
        System.out.println("[Fathom] UI QA hud " + font + ": "
                + net.fathom.optimizations.fabric.Runtime.hud().widgetCount()
                + " widgets, "
                + net.fathom.optimizations.core.instrument.Probe.meanMillis(
                        net.fathom.optimizations.core.instrument.Probe.Section.HUD)
                + " ms mean per frame.");
        client.scheduleStop();
    }
}
