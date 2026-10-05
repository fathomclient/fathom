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
 *   <li>{@code measure:<kind>[:seconds]} -- profile the screen the previous
 *       step opened ({@link UiProfile}) while a script drives it: {@code idle}
 *       (pointer parked), {@code hover} (pointer sweeping the screen),
 *       {@code scroll} (wheel, both ways), {@code pages} (Hub sections in turn,
 *       as rail clicks), {@code typing} (Hub search, typed and erased),
 *       {@code openclose} (the Hub opened and closed in turn). One line per
 *       step in the log; no screenshot.
 *   <li>{@code motion:<kind>} -- a half-size 60 fps PNG sequence for
 *       {@code open}, {@code close}, {@code rapid}, {@code tabs},
 *       {@code hover}, {@code popover}, or {@code scroll}.
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
    /** Appended to shot names after a {@code text:} step. */
    private static String tag = "";

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
                MotionCapture.shutdown();
                System.out.println("[Fathom] UI tour: done.");
                client.scheduleStop();
            }
            return;
        }
        String step = planSteps.get(at);
        float scale = planScales.get(at).floatValue();
        if (step.startsWith("text:")) {
            // Development: text weight parameters for the shots that follow,
            // which carry them in their names.
            String[] values = step.substring(5).split("/");
            net.fathom.optimizations.fabric.gfx.SdfRenderer.tuneText(Float.parseFloat(values[0]),
                    Float.parseFloat(values[1]), Float.parseFloat(values[2]));
            tag = "-" + step.substring(5).replace('/', '_');
            at++;
            return;
        }
        if (step.startsWith("measure:")) {
            if (scale == scale) {
                scaleOverride = scale;
            }
            if (measure(client, step, scale)) {
                at++;
            }
            return;
        }
        if (step.startsWith("motion:")) {
            if (scale == scale) {
                scaleOverride = scale;
            }
            if (motion(client, step.substring("motion:".length()))) {
                at++;
            }
            return;
        }
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
            String name = String.format(Locale.ROOT, "tour-%s%s%s-s%s-%dx%d.png",
                    client.world != null && step.startsWith("hub:") ? "world-" : "",
                    step.replace(':', '-').toLowerCase(Locale.ROOT), tag,
                    trim(scale), client.width, client.height);
            ScreenshotUtils.saveScreenshot(client.runDirectory, name, client.width, client.height,
                    client.getFramebuffer());
            if (Boolean.getBoolean("fathom.uiTour.geometry")
                    && client.currentScreen instanceof net.fathom.optimizations.fabric.gfx.LaidOut) {
                dumpGeometry(name, ((net.fathom.optimizations.fabric.gfx.LaidOut) client.currentScreen).layoutRoot());
            }
        }
        System.setProperty("fathom.qaAccountOpen", "false");
        opened = false;
        at++;
    }

    private static long measureStartedAt;
    private static boolean measuring;
    private static int measureTick;

    private static String motionKind;
    private static long motionWarmupUntil;
    private static long motionStartedAt;
    private static int motionAction;
    private static boolean motionRecording;

    /** Script one interaction while {@link MotionCapture} samples completed frames. */
    private static boolean motion(MinecraftClient client, String requested) {
        String kind = requested.trim().toLowerCase(Locale.ROOT);
        long now = System.nanoTime();
        if (!kind.equals(motionKind)) {
            MotionCapture.finish();
            motionKind = kind;
            motionWarmupUntil = 0L;
            motionStartedAt = 0L;
            motionAction = 0;
            motionRecording = false;
            net.fathom.optimizations.fabric.gfx.Pointer.script(Float.NaN, Float.NaN);
        }

        net.fathom.optimizations.fabric.hub.FathomHubScreen hub =
                client.currentScreen instanceof net.fathom.optimizations.fabric.hub.FathomHubScreen
                        ? (net.fathom.optimizations.fabric.hub.FathomHubScreen) client.currentScreen : null;

        if (!motionRecording) {
            if ("open".equals(kind) || "rapid".equals(kind)) {
                if (hub != null) {
                    hub.beginClose();
                    return false;
                }
                if (motionWarmupUntil == 0L) {
                    MotionCapture.prepare(client);
                    motionWarmupUntil = now + 250_000_000L;
                    return false;
                }
                if (now < motionWarmupUntil) {
                    return false;
                }
                startMotion(client, kind, now);
                FathomNavigation.openHub(client, Runtime.hud(), "Mods");
            } else {
                int section = "popover".equals(kind) ? 2 : "scroll".equals(kind) ? 1 : 1;
                if (hub == null) {
                    FathomNavigation.openHub(client, Runtime.hud(), section == 2 ? "Performance" : "Mods");
                    motionWarmupUntil = now + 500_000_000L;
                    return false;
                }
                if (motionWarmupUntil == 0L) {
                    MotionCapture.prepare(client);
                    hub.scriptSection(section);
                    motionWarmupUntil = now + 500_000_000L;
                    return false;
                }
                if (now < motionWarmupUntil) {
                    return false;
                }
                startMotion(client, kind, now);
                if ("close".equals(kind)) {
                    hub.beginClose();
                } else if ("tabs".equals(kind)) {
                    hub.scriptSection(0);
                } else if ("popover".equals(kind)) {
                    hub.scriptPopover("perf.rshift-background");
                } else if ("hover".equals(kind)) {
                    net.fathom.optimizations.fabric.gfx.Pointer.script(210f, 124f);
                }
            }
        }

        float elapsed = (now - motionStartedAt) / 1_000_000_000f;
        hub = client.currentScreen instanceof net.fathom.optimizations.fabric.hub.FathomHubScreen
                ? (net.fathom.optimizations.fabric.hub.FathomHubScreen) client.currentScreen : null;
        if ("rapid".equals(kind) && hub != null) {
            float[] edges = {0.055f, 0.095f, 0.135f, 0.175f};
            while (motionAction < edges.length && elapsed >= edges[motionAction]) {
                hub.toggleOpen();
                motionAction++;
            }
        } else if ("tabs".equals(kind) && hub != null && motionAction == 0 && elapsed >= 0.18f) {
            hub.scriptSection(2);
            motionAction++;
        } else if ("hover".equals(kind)) {
            // Sweep vertically through the retained rail button bounds. The
            // pointer target is changed before the next rendered frame and the
            // control responds in that frame; there is no tick-loop delay.
            float phase = Math.min(1f, elapsed / 0.50f);
            net.fathom.optimizations.fabric.gfx.Pointer.script(210f, 124f + phase * 230f);
        } else if ("scroll".equals(kind)) {
            net.fathom.optimizations.fabric.gfx.Pointer.script(720f, 430f);
            int edge = (int) (elapsed / 0.045f);
            if (edge > motionAction) {
                net.fathom.optimizations.fabric.gfx.Pointer.scriptWheel(elapsed < 0.30f ? -1 : 1);
                motionAction = edge;
            }
        }

        MotionCapture.frame(client);
        float duration = "rapid".equals(kind) || "hover".equals(kind) || "scroll".equals(kind)
                ? 0.55f : 0.38f;
        if (elapsed < duration) {
            return false;
        }
        MotionCapture.finish();
        net.fathom.optimizations.fabric.gfx.Pointer.script(Float.NaN, Float.NaN);
        motionKind = null;
        motionRecording = false;
        return true;
    }

    private static void startMotion(MinecraftClient client, String kind, long now) {
        motionStartedAt = now;
        motionAction = 0;
        motionRecording = true;
        MotionCapture.start(client, kind, 60);
    }

    /** One frame of a measurement step; true when it has finished. */
    private static boolean measure(MinecraftClient client, String step, float scale) {
        String[] parts = step.split(":");
        String kind = parts.length > 1 ? parts[1] : "idle";
        float seconds = parts.length > 2 ? Float.parseFloat(parts[2]) : 4f;
        long now = System.nanoTime();
        if (!measuring) {
            measuring = true;
            measureTick = 0;
            measureStartedAt = now;
            String screen = client.currentScreen == null ? "none" : client.currentScreen.getClass().getSimpleName();
            UiProfile.start(String.format(Locale.ROOT, "%s %s%s s%s %dx%d", kind, screen,
                    client.world != null ? " (world)" : "", trim(scale), client.width, client.height));
        }
        float t = (now - measureStartedAt) / 1e9f;
        measureTick++;
        float width = 1280f;
        float height = 720f;
        net.fathom.optimizations.core.ui.render.Renderer drawing =
                net.fathom.optimizations.fabric.gfx.Gfx.peekCompositor() != null
                        ? net.fathom.optimizations.fabric.gfx.Gfx.peekCompositor()
                        : net.fathom.optimizations.fabric.gfx.Gfx.peek();
        if (drawing != null) {
            width = drawing.width();
            height = drawing.height();
        }
        if ("idle".equals(kind)) {
            net.fathom.optimizations.fabric.gfx.Pointer.script(-1000f, -1000f);
        } else if ("hover".equals(kind)) {
            // A slow Lissajous over the whole screen: every control is entered and left.
            float x = width * (0.5f + 0.48f * (float) Math.sin(t * 1.3f));
            float y = height * (0.5f + 0.46f * (float) Math.sin(t * 2.1f + 0.7f));
            net.fathom.optimizations.fabric.gfx.Pointer.script(x, y);
        } else if ("scroll".equals(kind)) {
            net.fathom.optimizations.fabric.gfx.Pointer.script(width * 0.55f, height * 0.55f);
            if (measureTick % 4 == 0) {
                net.fathom.optimizations.fabric.gfx.Pointer.scriptWheel(((int) (t / 1.0f)) % 2 == 0 ? -1 : 1);
            }
        } else if ("pages".equals(kind)) {
            net.fathom.optimizations.fabric.gfx.Pointer.script(-1000f, -1000f);
            if (client.currentScreen instanceof net.fathom.optimizations.fabric.hub.FathomHubScreen
                    && measureTick % 30 == 0) {
                net.fathom.optimizations.fabric.hub.FathomHubScreen hub =
                        (net.fathom.optimizations.fabric.hub.FathomHubScreen) client.currentScreen;
                hub.scriptSection((measureTick / 30) % hub.sectionCount());
            }
        } else if ("typing".equals(kind)) {
            net.fathom.optimizations.fabric.gfx.Pointer.script(-1000f, -1000f);
            if (client.currentScreen instanceof net.fathom.optimizations.fabric.gfx.FathomScreen
                    && measureTick % 6 == 0) {
                String word = "render";
                int phase = (measureTick / 6) % (word.length() * 2);
                net.fathom.optimizations.fabric.gfx.FathomScreen screen =
                        (net.fathom.optimizations.fabric.gfx.FathomScreen) client.currentScreen;
                if (phase < word.length()) {
                    screen.handleFathomKey(word.charAt(phase), 0);
                } else {
                    screen.handleFathomKey((char) 0, org.lwjgl.input.Keyboard.KEY_BACK);
                }
            }
        } else if ("openclose".equals(kind)) {
            net.fathom.optimizations.fabric.gfx.Pointer.script(-1000f, -1000f);
            if (measureTick % 40 == 0) {
                if (client.currentScreen instanceof net.fathom.optimizations.fabric.hub.FathomHubScreen) {
                    ((net.fathom.optimizations.fabric.hub.FathomHubScreen) client.currentScreen).toggleOpen();
                } else {
                    FathomNavigation.openHub(client, Runtime.hud(), "Mods");
                }
            }
        }
        if (t < seconds) {
            return false;
        }
        UiProfile.stop();
        net.fathom.optimizations.fabric.gfx.Pointer.script(Float.NaN, Float.NaN);
        measuring = false;
        return true;
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
        if ("account-add".equals(step)) {
            // The panel, with Add pressed: what the attempt's state looks like.
            if (inWorld) {
                return false;
            }
            System.setProperty("fathom.qaAccountOpen", "true");
            client.setScreen(new FathomMenuScreen());
            net.fathom.optimizations.fabric.bridge.LauncherAccount.get(client.runDirectory).addAccount();
            return true;
        }
        if (step.startsWith("hub:")) {
            FathomNavigation.openHub(client, Runtime.hud(), step.substring(4));
            return true;
        }
        if ("proof".equals(step)) {
            // The compositor's proof scene (development only).
            FathomNavigation.push(client, new net.fathom.optimizations.fabric.gfx.RendererProofScreen());
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

    /** Every box of the screen's layout, one line each, for comparison with the launcher's. */
    private static void dumpGeometry(String shot, net.fathom.optimizations.core.ui.layout.Box root) {
        if (root == null) {
            return;
        }
        final StringBuilder out = new StringBuilder();
        root.visit(new net.fathom.optimizations.core.ui.layout.Box.Visitor() {
            @Override
            public void box(net.fathom.optimizations.core.ui.layout.Box box, int depth) {
                net.fathom.optimizations.core.ui.layout.Rect r = box.rect();
                out.append(String.format(Locale.ROOT, "[Fathom] geometry %s %s %.2f %.2f %.2f %.2f%n",
                        shot, box.id, r.x, r.y, r.width, r.height));
            }
        });
        System.out.print(out);
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
