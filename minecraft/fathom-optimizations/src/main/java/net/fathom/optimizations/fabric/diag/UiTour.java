package net.fathom.optimizations.fabric.diag;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.GameMenuScreen;
import net.minecraft.client.util.ScreenshotUtils;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.FathomNavigation;
import net.fathom.optimizations.fabric.hud.HudEditorScreen;
import net.fathom.optimizations.fabric.menu.FathomMenuScreen;

/**
 * A screenshot tour of every Fathom surface, at several interface scales, in
 * one launch. Development only: {@code -Dfathom.uiTour=menu,account,hub:Quick,...}
 * arms it; nothing else does, and a normal launch pays one boolean read a frame.
 *
 * <h2>Steps</h2>
 *
 * <ul>
 *   <li>{@code menu} -- the title screen; {@code account} -- with the account
 *       panel open;
 *   <li>{@code hub:<Section>} -- the Hub on a section;
 *   <li>{@code hud-editor};
 *   <li>{@code world} -- join {@code -Dfathom.uiTour.world}. Steps after it run
 *       in the world: {@code pause}, {@code hub:<Section>}, {@code hud}.
 * </ul>
 *
 * <p>The steps before {@code world} run at every scale in
 * {@code -Dfathom.uiTour.scales} (default 0.65, 1, 1.5 -- the smallest, the
 * automatic and the largest the preference allows), then the world is joined
 * once, then the steps after it run at every scale. Each is held until its
 * entrance has settled (by the clock, not a frame count) and saved to
 * {@code screenshots/tour-<step>-s<scale>-<w>x<h>.png}. The client stops at the end.
 */
public final class UiTour {

    public static final boolean ACTIVE = !System.getProperty("fathom.uiTour", "").trim().isEmpty();

    /** Long enough for every entrance animation to finish, whatever the frame rate. */
    private static final long SETTLE_NANOS = Long.getLong("fathom.uiTour.settleMs", 1200L) * 1_000_000L;
    private static final long WORLD_NANOS = 6_000_000_000L;
    private static final String WORLD = "world";

    /** The scale a tour is showing, or NaN for the user's own. */
    private static float scaleOverride = Float.NaN;

    /** Every (step, scale) in order; a world join is one entry with a NaN scale. */
    private static List<String> planSteps;
    private static List<Float> planScales;
    private static int at;
    private static int frames;
    private static long openedAt;
    private static boolean opened;
    private static boolean started;

    private UiTour() {}

    /** The interface scale preference while a tour runs; otherwise {@code fallback}. */
    public static float scale(float fallback) {
        return scaleOverride == scaleOverride ? scaleOverride : fallback;
    }

    /** Called once per frame on the render thread. */
    public static void frame(MinecraftClient client) {
        if (!ACTIVE) {
            return;
        }
        if (planSteps == null) {
            plan();
        }
        if (!started) {
            // Wait for the title screen to have settled once.
            if (client.currentScreen instanceof FathomMenuScreen && ++frames > 90) {
                started = true;
                frames = 0;
                System.out.println("[Fathom] UI tour: " + planSteps.size() + " shots.");
            }
            return;
        }
        if (at >= planSteps.size()) {
            if (at == planSteps.size()) {
                at++;
                System.out.println("[Fathom] UI tour: done.");
                client.scheduleStop();
            }
            return;
        }
        String step = planSteps.get(at);
        float scale = planScales.get(at).floatValue();
        if (!opened) {
            if (scale == scale) {
                scaleOverride = scale;
            }
            if (!open(client, step)) {
                at++;
                return;
            }
            opened = true;
            openedAt = System.nanoTime();
            return;
        }
        boolean world = WORLD.equals(step);
        if (world && (client.world == null || client.player == null)) {
            openedAt = System.nanoTime();
            return;
        }
        if (System.nanoTime() - openedAt < (world ? WORLD_NANOS : SETTLE_NANOS)) {
            return;
        }
        if (!world) {
            String name = String.format(Locale.ROOT, "tour-%s%s-s%s-%dx%d.png",
                    client.world != null && step.startsWith("hub:") ? "world-" : "",
                    step.replace(':', '-').toLowerCase(Locale.ROOT),
                    trim(scale), client.width, client.height);
            ScreenshotUtils.saveScreenshot(client.runDirectory, name, client.width, client.height,
                    client.getFramebuffer());
        }
        System.setProperty("fathom.qaAccountOpen", "false");
        opened = false;
        at++;
    }

    private static boolean open(MinecraftClient client, String step) {
        boolean inWorld = client.world != null;
        if ("menu".equals(step) || "account".equals(step)) {
            if (inWorld) {
                return false;
            }
            System.setProperty("fathom.qaAccountOpen", String.valueOf("account".equals(step)));
            client.setScreen(new FathomMenuScreen());
            return true;
        }
        if (step.startsWith("hub:")) {
            FathomNavigation.openHub(client, Runtime.hud(), step.substring(4));
            return true;
        }
        if ("hud-editor".equals(step)) {
            FathomNavigation.push(client, new HudEditorScreen(Runtime.hud()));
            return true;
        }
        if (WORLD.equals(step)) {
            String save = System.getProperty("fathom.uiTour.world", "New World");
            client.setScreen(null);
            client.startIntegratedServer(save, save, null);
            return true;
        }
        if ("pause".equals(step)) {
            if (!inWorld) {
                return false;
            }
            client.setScreen(new GameMenuScreen());
            return true;
        }
        if ("hud".equals(step)) {
            if (!inWorld) {
                return false;
            }
            client.setScreen(null);
            return true;
        }
        System.out.println("[Fathom] UI tour: unknown step '" + step + "', skipped.");
        return false;
    }

    private static void plan() {
        List<String> before = new ArrayList<String>();
        List<String> after = new ArrayList<String>();
        boolean joined = false;
        for (String part : System.getProperty("fathom.uiTour", "").split(",")) {
            String step = part.trim();
            if (step.isEmpty()) {
                continue;
            }
            if (WORLD.equals(step)) {
                joined = true;
            } else {
                (joined ? after : before).add(step);
            }
        }
        String[] parts = System.getProperty("fathom.uiTour.scales", "0.65,1,1.5").split(",");
        planSteps = new ArrayList<String>();
        planScales = new ArrayList<Float>();
        for (String scale : parts) {
            for (String step : before) {
                planSteps.add(step);
                planScales.add(Float.valueOf(scale.trim()));
            }
        }
        if (joined) {
            planSteps.add(WORLD);
            planScales.add(Float.valueOf(Float.NaN));
            for (String scale : parts) {
                for (String step : after) {
                    planSteps.add(step);
                    planScales.add(Float.valueOf(scale.trim()));
                }
            }
        }
    }

    private static String trim(float value) {
        String text = String.format(Locale.ROOT, "%.2f", value);
        return text.replaceAll("0+$", "").replaceAll("\\.$", "");
    }
}
