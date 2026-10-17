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
    private static long firstFrameNanos;

    /**
     * Seconds in the world before the shot, on top of the settling frames:
     * {@code -Dfathom.captureDelay=30} waits for a server scene to be built.
     */
    private static final double DELAY_SECONDS = doubleProperty("fathom.captureDelay", 0.0);

    /** {@code -Dfathom.captureHoldTab=true}: the player list is held open for the shot. */
    private static final boolean HOLD_TAB = Boolean.getBoolean("fathom.captureHoldTab");

    private static double doubleProperty(String name, double fallback) {
        try {
            return Double.parseDouble(System.getProperty(name, String.valueOf(fallback)));
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }

    private HudCapture() {}

    /** Read once: this runs every HUD frame, and the answer is fixed at launch. */
    private static final boolean REQUESTED = "hud".equalsIgnoreCase(System.getProperty("fathom.captureUi", ""));

    /**
     * A Hub section to open over the settled world instead of capturing the
     * HUD ({@code -PcaptureHubWorld=Mods}); the Hub then captures itself.
     */
    private static final String HUB_SECTION = System.getProperty("fathom.captureHubWorld", "").trim();

    /** Worn armour, a held item and two effects, so the shot has something to show. */
    private static void dress(MinecraftClient client) {
        net.minecraft.entity.player.ClientPlayerEntity player = client.player;
        net.minecraft.item.ItemStack[] worn = player.inventory.armor;
        worn[3] = damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.DIAMOND_HELMET), 0.15f);
        worn[2] = damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.IRON_CHESTPLATE), 0.55f);
        worn[1] = damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.LEATHER_LEGGINGS), 0.85f);
        worn[0] = damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.GOLDEN_BOOTS), 0.40f);
        // A bow and a quiver, so the armour widget's arrow count has something to count.
        player.inventory.main[player.inventory.selectedSlot] =
                damaged(new net.minecraft.item.ItemStack(net.minecraft.item.Items.BOW), 0.10f);
        int quiver = player.inventory.selectedSlot == 8 ? 7 : 8;
        player.inventory.main[quiver] = new net.minecraft.item.ItemStack(net.minecraft.item.Items.ARROW, 37);
        // Speed II and Fire Resistance: one with a level, one without, both with
        // icons, which is every case the potion widget lays out.
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(1, 60 * 20, 1));
        player.addStatusEffect(new net.minecraft.entity.effect.StatusEffectInstance(12, 8 * 20, 0));
        // Night vision with no end, as a lobby gives it: the potion widget
        // prints **:** for it rather than a timer.
        net.minecraft.entity.effect.StatusEffectInstance forever =
                new net.minecraft.entity.effect.StatusEffectInstance(16, 32767, 0);
        forever.setPermanent(true);
        player.addStatusEffect(forever);
        stage(client);
        // Looking up at the sky, so Time Changer's hour is in the shot.
        player.pitch = -35f;
        player.prevPitch = -35f;
    }

    private static boolean staged;

    /**
     * Once per run: a sidebar on the client's scoreboard and a few chat lines,
     * for the Scoreboard and Chat modules. The client's own copies only; the
     * integrated server never hears of either.
     */
    private static void stage(MinecraftClient client) {
        if (staged || client.world == null || client.inGameHud == null) {
            return;
        }
        staged = true;
        net.minecraft.scoreboard.Scoreboard scoreboard = client.world.getScoreboard();
        net.minecraft.scoreboard.ScoreboardObjective objective = scoreboard.getNullableObjective("fathomqa");
        if (objective == null) {
            objective = scoreboard.method_4884("fathomqa", net.minecraft.scoreboard.ScoreboardCriterion.DUMMY);
            objective.setDisplayName("\u00a7e\u00a7lBED WARS");
        }
        String[] lines = {"\u00a77Map: \u00a7aLighthouse", "", "\u00a7cR \u00a7fRed: \u00a7a\u2714",
            "\u00a79B \u00a7fBlue: \u00a7a\u2714", "\u00a7aG \u00a7fGreen: \u00a77YOU", " ", "\u00a7ewww.example.net"};
        for (int index = 0; index < lines.length; index++) {
            scoreboard.getPlayerScore(lines[index], objective).setScore(lines.length - index);
        }
        scoreboard.setObjectiveSlot(1, objective);
        net.minecraft.client.gui.hud.ChatHud chat = client.inGameHud.getChatHud();
        chat.addMessage(new net.minecraft.text.LiteralText("\u00a7aWelcome to the Fathom QA world."));
        for (int index = 0; index < 3; index++) {
            chat.addMessage(new net.minecraft.text.LiteralText("\u00a7eYou have 3 seconds to respawn!"));
        }
        chat.addMessage(new net.minecraft.text.LiteralText("\u00a77This line sits over the Sprint widget."));
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
            && net.fathom.optimizations.fabric.Platform.development();

    /** Set once a backdrop-source capture was requested; the client stops after it. */
    private static final boolean[] STOP_AFTER_SOURCE = {false};

    /** Called once per drawn HUD frame. */
    public static void frame(MinecraftClient client) {
        if (STOP_AFTER_SOURCE[0] && !net.fathom.optimizations.fabric.gfx.WorldBackdrop.pending()) {
            STOP_AFTER_SOURCE[0] = false;
            net.fathom.optimizations.fabric.gfx.WorldBackdrop.release();
            client.scheduleStop();
            return;
        }
        if (!REQUESTED || captured || client == null || client.player == null) {
            return;
        }
        if (DRESS) {
            // Every frame: the integrated server owns the player's inventory and
            // syncs it back, so a one-off change is undone before the shot.
            dress(client);
        }
        if (HOLD_TAB && client.options != null) {
            net.minecraft.client.option.KeyBinding.setKeyPressed(client.options.playerListKey.getCode(), true);
        }
        if (frames == 0) {
            firstFrameNanos = System.nanoTime();
            // Timing starts with the first in-world frame, not with the client,
            // so world load and terrain generation are not averaged into what a
            // HUD costs to draw.
            net.fathom.optimizations.core.instrument.Probe.begin();
        }
        if (++frames < SETTLE || (System.nanoTime() - firstFrameNanos) / 1e9 < DELAY_SECONDS) {
            return;
        }
        captured = true;
        if (Boolean.getBoolean("fathom.captureBackdropSource")) {
            // The next frame's world, before any HUD, saved unprocessed as a
            // curated-capture source; then stop.
            net.fathom.optimizations.fabric.gfx.WorldBackdrop.request();
            STOP_AFTER_SOURCE[0] = true;
            return;
        }
        if (!HUB_SECTION.isEmpty()) {
            net.fathom.optimizations.core.instrument.Probe.end();
            net.fathom.optimizations.fabric.gfx.FathomNavigation.openHub(client,
                    net.fathom.optimizations.fabric.Runtime.hud());
            return;
        }
        net.fathom.optimizations.core.instrument.Probe.end();
        WidgetProfile.report();
        String override = System.getProperty("fathom.hudFont", "").trim();
        String font = override.isEmpty() ? "per-module" : override.toLowerCase(java.util.Locale.ROOT);
        String name = "fathom-hud-" + font + "-" + client.width + "x" + client.height + ".png";
        ScreenshotUtils.saveScreenshot(client.runDirectory, name,
                client.width, client.height, client.getFramebuffer());
        // What the HUD costs with the configured per-module typefaces. A
        // command-line override is only used for controlled comparison runs.
        if (client.getNetworkHandler() != null && !client.isInSingleplayer()) {
            net.minecraft.client.network.PlayerListEntry own = PingWidget.ownEntry(client);
            System.out.println("[Fathom] UI QA ping: own player-list row "
                    + (own == null ? "missing" : "found, latency " + own.getLatency() + " ms")
                    + ", widget reads " + PingWidget.latencyOf(client));
        }
        if (client.world != null) {
            System.out.println("[Fathom] UI QA world: time of day " + client.world.getTimeOfDay()
                    + ", sky angle " + client.world.getSkyAngle(0f)
                    + ", Time Changer ticks " + net.fathom.optimizations.fabric.visual.WorldLighting.skyTicks()
                    + ", lightmap hook seen " + net.fathom.optimizations.fabric.visual.WorldLighting.lightmapSeen()
                    + ", gamma " + net.fathom.optimizations.fabric.visual.WorldLighting.gamma(client.options.gamma));
        }
        System.out.println("[Fathom] UI QA hud " + font + ": "
                + net.fathom.optimizations.fabric.Runtime.hud().widgetCount()
                + " widgets, "
                + net.fathom.optimizations.core.instrument.Probe.meanMillis(
                        net.fathom.optimizations.core.instrument.Probe.Section.HUD)
                + " ms mean per frame.");
        client.scheduleStop();
    }
}
