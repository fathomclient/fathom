package net.fathom.optimizations.fabric.bench.view;

import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.nio.charset.StandardCharsets;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Collections;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import com.mojang.authlib.GameProfile;
import org.lwjgl.opengl.Display;
import org.lwjgl.opengl.GL11;

import net.minecraft.block.BlockState;
import net.minecraft.block.Blocks;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.player.OtherClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.server.integrated.IntegratedServer;
import net.minecraft.util.math.BlockPos;

import net.fathom.optimizations.core.bench.view.ViewBucket;
import net.fathom.optimizations.core.bench.view.ViewCounter;
import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewReport;
import net.fathom.optimizations.core.bench.view.ViewSection;
import net.fathom.optimizations.core.instrument.MemorySampler;
import net.fathom.optimizations.fabric.bench.auto.BenchmarkState;

/**
 * The view-direction benchmark: where a frame's time goes as the camera turns
 * from the sky to the ground, and what entities, particles and translucency add.
 *
 * <h2>How a run is laid out</h2>
 *
 * <p>Start the client with {@code -Dfathom.benchmark=view} and, to have it load
 * a save itself, {@code -Dfathom.view.world=<folder>}. After the world has
 * settled it calibrates the probe, warms every scene up once without recording
 * (JIT, and every chunk the camera will see compiled), then runs rounds. A
 * round visits every scene; within a scene every arm gets one window; the arm
 * order reverses on alternate rounds, so drift and order effects cancel
 * across rounds instead of landing on one arm.
 *
 * <h2>What makes a frame count</h2>
 *
 * <p>The window has focus, no screen is open, the world is loaded, and the
 * window is past its settle phase -- which lasts until no chunk has been
 * rebuilt or uploaded for half a second, so a measured window is the steady
 * state of its view rather than the tail of turning to it. Anything else is
 * rejected and counted, never silently dropped.
 *
 * <h2>Arms</h2>
 *
 * <ul>
 *   <li>{@code normal}: every section timed.
 *   <li>{@code probes-off}: nothing but the frame interval timed, to measure
 *       what the timing itself costs.
 *   <li>{@code fathom-off}: Fathom's hooks bypassed (the core benchmark's
 *       bypass) and its performance layer forced off.
 *   <li>{@code drain}: {@code glFinish} before every swap, to show how much GPU
 *       and driver work is still queued when the CPU is done.
 *   <li>{@code of:<field>}: one OptiFine option flipped from the profile's
 *       setting, through OptiFine's own option handler, for that window only.
 * </ul>
 */
public final class ViewBenchmark implements ViewProbe.Sink {

    // -----------------------------------------------------------------------
    // Configuration
    // -----------------------------------------------------------------------

    private static final String WORLD = System.getProperty("fathom.view.world", "").trim();
    private static final int ROUNDS = intProperty("fathom.view.rounds", 3);
    private static final double SETTLE_SECONDS = doubleProperty("fathom.view.settle", 1.0d);
    private static final double SETTLE_CAP_SECONDS = doubleProperty("fathom.view.settleCap", 20.0d);
    private static final double QUIET_SECONDS = doubleProperty("fathom.view.quiet", 0.5d);
    private static final double MEASURE_SECONDS = doubleProperty("fathom.view.seconds", 3.0d);
    private static final double WARMUP_SECONDS = doubleProperty("fathom.view.warmup", 2.5d);
    private static final double JOIN_MIN_SECONDS = doubleProperty("fathom.view.joinSettle", 20.0d);
    private static final double JOIN_CAP_SECONDS = doubleProperty("fathom.view.joinSettleCap", 180.0d);
    /**
     * Whether a frame needs window focus to count. Off by default: 1.8.9 draws
     * an unfocused, unpaused window exactly as it draws a focused one, the
     * benchmark already lifts Fathom's unfocused frame cap and the pause-on-
     * focus-loss menu, and requiring focus meant either stealing the keyboard
     * from whoever is using the machine or losing whole windows. The share of
     * unfocused frames is reported per bucket either way.
     */
    private static final boolean REQUIRE_FOCUS = Boolean.getBoolean("fathom.view.requireFocus");
    /**
     * Save one screenshot per scene and arm, at the end of its first recorded
     * window, to {@code screenshots/view-<scene>-<arm>.png}. For checking that an
     * arm draws the same picture as the normal arm, pixel for pixel. Taken
     * between windows, so the save's own hitch lands in the next settle phase.
     */
    private static final boolean SCREENSHOTS = Boolean.getBoolean("fathom.view.screenshots");
    /**
     * A frame cap for the run, in memory only (0 leaves the profile's own).
     * The frame limiter can only be measured against a cap, and the profiles
     * Fathom ships are uncapped.
     */
    private static final int FPS_CAP = intProperty("fathom.view.fpsCap", 0);
    private static final boolean QUIT = !"false".equalsIgnoreCase(System.getProperty("fathom.view.quit", "true"));
    private static final String DEFAULT_COMMANDS =
            "gamerule doDaylightCycle false;time set 6000;weather clear 1000000;difficulty 0;"
                    + "gamerule doMobSpawning false";

    /** A fixed camera, and optionally something added in front of it. */
    enum Scene {
        SKY("sky", -90f, "looking straight up: sky, almost no geometry"),
        HORIZON("horizon", 0f, "level view to the horizon"),
        DOWN45("down45", 45f, "looking 45 degrees down at terrain"),
        DOWN90("down90", 90f, "looking straight down at the ground"),
        ENTITIES("entities", 0f, "level view with 48 armoured, sword-holding players with nametags in front"),
        PARTICLES("particles", 0f, "level view with a steady field of flame and smoke particles in front"),
        TRANSLUCENT("translucent", 0f, "level view through two stained-glass walls and a water wall"),
        /**
         * Chunk loading, not a steady view: every window starts by rebuilding
         * every chunk (vanilla's F3+A) and ends once nothing has been rebuilt or
         * uploaded for the quiet period. The window's length is the rebuild time.
         * Not in the default scene list.
         */
        RELOAD("reload", 0f, "level view while every chunk is rebuilt (F3+A)");

        final String id;
        final float pitch;
        final String description;

        Scene(String id, float pitch, String description) {
            this.id = id;
            this.pitch = pitch;
            this.description = description;
        }

        static Scene byId(String id) {
            for (Scene scene : values()) {
                if (scene.id.equalsIgnoreCase(id.trim())) {
                    return scene;
                }
            }
            return null;
        }
    }

    private static final List<Scene> SCENES = scenes();
    private static final List<String> ARMS = arms();

    private static List<Scene> scenes() {
        String list = System.getProperty("fathom.view.scenes",
                "sky,horizon,down45,down90,entities,particles,translucent");
        List<Scene> out = new ArrayList<Scene>();
        for (String id : list.split(",")) {
            Scene scene = Scene.byId(id);
            if (scene != null && !out.contains(scene)) {
                out.add(scene);
            }
        }
        return out.isEmpty() ? Arrays.asList(Scene.values()) : out;
    }

    private static List<String> arms() {
        String list = System.getProperty("fathom.view.arms", "normal,probes-off,fathom-off,drain");
        List<String> out = new ArrayList<String>();
        out.add(ViewReport.NORMAL);
        for (String arm : list.split(",")) {
            String trimmed = arm.trim();
            if (!trimmed.isEmpty() && !out.contains(trimmed)) {
                out.add(trimmed);
            }
        }
        return out;
    }

    // -----------------------------------------------------------------------
    // State
    // -----------------------------------------------------------------------

    private enum Phase { WAIT_WORLD, JOIN_SETTLE, SETTLE, MEASURE, DONE }

    /** One window: a scene under an arm, recorded or (warm-up) not. */
    private static final class Step {
        final int round;
        final Scene scene;
        final String arm;
        final boolean record;

        Step(int round, Scene scene, String arm, boolean record) {
            this.round = round;
            this.scene = scene;
            this.arm = arm;
            this.record = record;
        }
    }

    private static final ViewBenchmark INSTANCE = new ViewBenchmark();

    private final List<Step> steps = new ArrayList<Step>();
    private final Map<String, ViewBucket> buckets = new LinkedHashMap<String, ViewBucket>();
    private final List<ViewBucket> tags = new ArrayList<ViewBucket>();
    private final Map<String, String> sceneNotes = new LinkedHashMap<String, String>();
    private final List<String> runNotes = new ArrayList<String>();

    private Phase phase = Phase.WAIT_WORLD;
    private boolean installed;
    private boolean worldRequested;
    private int framesSeen;
    private long phaseStarted;
    private int stepIndex = -1;
    private Scene activeScene;
    private String activeArm = ViewReport.NORMAL;
    private double probePairNanos;
    private String fathomBuild = "?";

    private double startX;
    private double startY;
    private double startZ;
    private float startYaw;

    private long lastChunkActivity;
    private long lastHitch;
    private double steadyFrameNanos;

    // Window accounting, read on the client thread only.
    private int measuringTag = -1;
    private long measureStarted;
    private long windowFrameNanos;
    private int windowFrames;
    private long gcCountAtStart;
    private long gcMillisAtStart;
    private long allocatedAtStart;
    private int settleTimeouts;
    private MemorySampler memory;

    // Scene props.
    private final List<Integer> spawned = new ArrayList<Integer>();
    private final List<BlockPos> placed = new ArrayList<BlockPos>();
    private long particlesSpawnedAt;

    private ViewBenchmark() {}

    /** Called once per frame from the benchmark runner, in the post-frame window. */
    public static void frame(MinecraftClient client) {
        INSTANCE.drive(client);
    }

    public static boolean finished() {
        return INSTANCE.phase == Phase.DONE;
    }

    // -----------------------------------------------------------------------
    // Sink: one finished frame, at the next loop head
    // -----------------------------------------------------------------------

    @Override
    public void frame(long periodNanos, long[] total, long[] child, int[] calls, long[] counters, int tag,
            boolean valid) {
        long now = System.nanoTime();
        if (counters[ViewCounter.REBUILDS_QUEUED.ordinal()] > 0L
                || counters[ViewCounter.REBUILDS_SYNC.ordinal()] > 0L
                || counters[ViewCounter.UPLOADS.ordinal()] > 0L) {
            lastChunkActivity = now;
        }
        long frameNanos = periodNanos - total[ViewSection.HARNESS.ordinal()];
        trackHitches(now, frameNanos);
        if (tag < 0 || tag >= tags.size()) {
            return;
        }
        ViewBucket bucket = tags.get(tag);
        if (!valid) {
            bucket.reject();
            return;
        }
        bucket.add(frameNanos, total, child, calls, counters);
        if (tag == measuringTag) {
            windowFrameNanos += frameNanos;
            windowFrames++;
        }
    }

    /** A frame four times the steady average (and at least 16 ms) is a hitch. */
    private void trackHitches(long now, long frameNanos) {
        if (frameNanos <= 0L) {
            return;
        }
        if (steadyFrameNanos == 0d) {
            steadyFrameNanos = frameNanos;
        }
        if (frameNanos > Math.max(steadyFrameNanos * 4d, 16_000_000d)) {
            lastHitch = now;
        } else {
            steadyFrameNanos += (frameNanos - steadyFrameNanos) * 0.05d;
        }
    }

    // -----------------------------------------------------------------------
    // Driver: once per frame, between frames
    // -----------------------------------------------------------------------

    private void drive(MinecraftClient client) {
        long started = ViewProbe.harnessStart();
        try {
            step(client);
        } catch (Throwable failure) {
            // A harness that throws every frame turns a measurement into a fault.
            System.out.println("[FATHOM VIEW] harness failed, stopping: " + failure);
            failure.printStackTrace(System.out);
            finish(client, "harness failure: " + failure);
        } finally {
            ViewProbe.harnessStop(started);
        }
    }

    private void step(MinecraftClient client) {
        if (!installed) {
            installed = true;
            ViewProbe.install(this);
            if (client.options != null) {
                // A window that loses focus must not open the pause menu: the
                // frame is rejected instead, and the run carries on when focus returns.
                client.options.pauseOnLostFocus = false;
                if (FPS_CAP > 0) {
                    client.options.maxFramerate = FPS_CAP;
                }
            }
            System.out.println("[FATHOM VIEW] armed: scenes " + sceneIds() + ", arms " + ARMS
                    + ", " + ROUNDS + " rounds of " + MEASURE_SECONDS + " s windows.");
        }
        framesSeen++;
        long now = System.nanoTime();
        ClientPlayerEntity player = client.player;
        boolean inWorld = client.world != null && player != null;
        boolean focused = Display.isActive();
        ViewProbe.markFrame(inWorld && client.currentScreen == null && (focused || !REQUIRE_FOCUS));
        int ending = ViewProbe.currentTag();
        if (!focused && ending >= 0 && ending < tags.size()) {
            tags.get(ending).unfocused();
        }

        switch (phase) {
            case WAIT_WORLD:
                waitWorld(client, now);
                return;
            case DONE:
                return;
            default:
                break;
        }
        if (!inWorld) {
            finish(client, "the world closed mid-run");
            return;
        }
        Step current = stepIndex >= 0 && stepIndex < steps.size() ? steps.get(stepIndex) : null;
        if (current != null) {
            hold(player, current.scene.pitch);
        }
        if (current != null && current.scene == Scene.PARTICLES) {
            particles(client, now);
        }
        double elapsed = (now - phaseStarted) / 1_000_000_000d;

        switch (phase) {
            case JOIN_SETTLE:
                ViewProbe.tagNextFrame(-1, true, false);
                double quiet = (now - lastHitch) / 1_000_000_000d;
                if ((elapsed >= JOIN_MIN_SECONDS && quiet >= 5d) || elapsed >= JOIN_CAP_SECONDS) {
                    runNotes.add(String.format(Locale.ROOT,
                            "World settled after %.0f s (%.0f s without a hitch, steady frame %.3f ms).",
                            elapsed, quiet, steadyFrameNanos / 1_000_000d));
                    beginRun(client, now);
                }
                return;
            case SETTLE:
                ViewProbe.tagNextFrame(-1, !ViewReport.PROBES_OFF.equals(activeArm),
                        ViewReport.DRAIN.equals(activeArm));
                double sinceChunks = (now - lastChunkActivity) / 1_000_000_000d;
                boolean timedOut = elapsed >= SETTLE_CAP_SECONDS;
                if ((elapsed >= SETTLE_SECONDS && sinceChunks >= QUIET_SECONDS) || timedOut) {
                    if (timedOut) {
                        settleTimeouts++;
                    }
                    beginMeasure(now);
                }
                return;
            case MEASURE:
                double window = current.record ? MEASURE_SECONDS : WARMUP_SECONDS;
                double measured = (now - measureStarted) / 1_000_000_000d;
                boolean done = current.scene == Scene.RELOAD
                        ? (measured >= QUIET_SECONDS
                                && (now - lastChunkActivity) / 1_000_000_000d >= QUIET_SECONDS)
                                || measured >= SETTLE_CAP_SECONDS
                        : measured >= window;
                if (done) {
                    endMeasure(client, current, now);
                    advance(client, now);
                }
                return;
            default:
                return;
        }
    }

    private void waitWorld(MinecraftClient client, long now) {
        ViewProbe.tagNextFrame(-1, true, false);
        if (client.world != null && client.player != null) {
            phase = Phase.JOIN_SETTLE;
            phaseStarted = now;
            lastHitch = now;
            runCommands(client);
            System.out.println("[FATHOM VIEW] in the world; waiting for it to settle.");
            return;
        }
        if (!worldRequested && !WORLD.isEmpty() && framesSeen > 120 && client.currentScreen != null) {
            worldRequested = true;
            System.out.println("[FATHOM VIEW] loading save '" + WORLD + "'.");
            client.startIntegratedServer(WORLD, WORLD, null);
        }
    }

    /** Fix the time of day, the weather and mob spawning, so every window sees the same world. */
    private void runCommands(MinecraftClient client) {
        final IntegratedServer server = client.getServer();
        if (server == null) {
            runNotes.add("Not a local world: time, weather and spawning were not fixed.");
            return;
        }
        final String commands = System.getProperty("fathom.view.commands", DEFAULT_COMMANDS);
        runNotes.add("World commands: " + commands);
        server.submit(new Runnable() {
            @Override
            public void run() {
                for (String command : commands.split(";")) {
                    if (!command.trim().isEmpty()) {
                        server.getCommandManager().execute(server, command.trim());
                    }
                }
            }
        });
    }

    private void beginRun(MinecraftClient client, long now) {
        // Taken here, after the world has settled, and not at the first frame
        // with a player: that frame has the player at world spawn, and the
        // server's position packet moves it to where it was saved a moment
        // later. Scene props built from the first frame's position landed
        // hundreds of blocks away from the camera.
        ClientPlayerEntity player = client.player;
        startX = player.x;
        startY = player.y;
        startZ = player.z;
        startYaw = player.yaw;
        if (Boolean.getBoolean("fathom.view.packIconCheck")) {
            // Scans the resource pack folder the way the pack screen does, which
            // loads every pack's icon -- so the icon downscale can be seen working
            // in a running game. Before the warm-up, never inside a window.
            client.getResourcePackLoader().initResourcePacks();
            runNotes.add("Resource pack folder scanned for the pack icon check.");
        }
        fathomBuild = net.fathom.optimizations.fabric.diag.BuildId.describe();
        probePairNanos = ViewProbe.calibrate(1_000_000);
        System.out.println(String.format(Locale.ROOT,
                "[FATHOM VIEW] probe pair costs %.1f ns; starting warm-up.", probePairNanos));
        memory = new MemorySampler();
        for (Scene scene : SCENES) {
            steps.add(new Step(-1, scene, ViewReport.NORMAL, false));
        }
        for (int round = 0; round < ROUNDS; round++) {
            List<String> order = new ArrayList<String>(ARMS);
            if (round % 2 == 1) {
                Collections.reverse(order);
            }
            for (Scene scene : SCENES) {
                for (String arm : order) {
                    steps.add(new Step(round, scene, arm, true));
                }
            }
        }
        // Buckets in report order, so tags are stable.
        for (Scene scene : SCENES) {
            for (String arm : ARMS) {
                ViewBucket bucket = new ViewBucket(scene.id, arm);
                buckets.put(ViewReport.key(scene.id, arm), bucket);
                tags.add(bucket);
            }
        }
        stepIndex = -1;
        advance(client, now);
    }

    private void advance(MinecraftClient client, long now) {
        stepIndex++;
        if (stepIndex >= steps.size()) {
            finish(client, null);
            return;
        }
        Step next = steps.get(stepIndex);
        if (next.scene != activeScene) {
            teardown(client);
            setup(client, next.scene);
            activeScene = next.scene;
            System.out.println("[FATHOM VIEW] " + (next.record ? "round " + (next.round + 1) : "warm-up")
                    + ": " + next.scene.id + " (" + next.scene.description + ")");
        }
        if (!next.arm.equals(activeArm)) {
            restoreArm(client, activeArm);
            applyArm(client, next.arm);
            activeArm = next.arm;
        }
        phase = Phase.SETTLE;
        phaseStarted = now;
        ViewProbe.tagNextFrame(-1, !ViewReport.PROBES_OFF.equals(activeArm), ViewReport.DRAIN.equals(activeArm));
    }

    private void beginMeasure(long now) {
        Step current = steps.get(stepIndex);
        phase = Phase.MEASURE;
        measureStarted = now;
        if (current.scene == Scene.RELOAD) {
            // The rebuild is the workload: start it with the window, and count
            // the frame that starts it as chunk activity so the window cannot
            // end before the first rebuild is even queued.
            MinecraftClient.getInstance().worldRenderer.reload();
            lastChunkActivity = now;
        }
        windowFrames = 0;
        windowFrameNanos = 0L;
        long[] gc = gcTotals();
        gcCountAtStart = gc[0];
        gcMillisAtStart = gc[1];
        allocatedAtStart = memory.sample().allocatedBytesSinceStart();
        if (current.record) {
            measuringTag = tags.indexOf(buckets.get(ViewReport.key(current.scene.id, current.arm)));
        } else {
            measuringTag = -1;
        }
        ViewProbe.tagNextFrame(measuringTag, !ViewReport.PROBES_OFF.equals(activeArm),
                ViewReport.DRAIN.equals(activeArm));
    }

    private void endMeasure(MinecraftClient client, Step current, long now) {
        ViewProbe.tagNextFrame(-1, !ViewReport.PROBES_OFF.equals(activeArm), ViewReport.DRAIN.equals(activeArm));
        if (!current.record || measuringTag < 0) {
            measuringTag = -1;
            return;
        }
        ViewBucket bucket = tags.get(measuringTag);
        long[] gc = gcTotals();
        double seconds = (now - measureStarted) / 1_000_000_000d;
        bucket.window(windowFrames == 0 ? 0d : windowFrameNanos / 1_000_000d / windowFrames, seconds,
                gc[0] - gcCountAtStart, gc[1] - gcMillisAtStart,
                memory.sample().allocatedBytesSinceStart() - allocatedAtStart);
        measuringTag = -1;
        if (ViewReport.NORMAL.equals(current.arm)) {
            sceneNotes.put(current.scene.id, sceneState(client));
        }
        if (SCREENSHOTS && current.round == 0) {
            net.minecraft.client.util.ScreenshotUtils.saveScreenshot(client.runDirectory,
                    "view-" + current.scene.id + "-" + current.arm.replaceAll("[^A-Za-z0-9.-]", "_") + ".png",
                    client.width, client.height, client.getFramebuffer());
        }
        System.out.println(String.format(Locale.ROOT,
                "[FATHOM VIEW] %s/%s round %d: %d frames, mean %.4f ms",
                current.scene.id, current.arm, current.round + 1, windowFrames,
                windowFrames == 0 ? 0d : windowFrameNanos / 1_000_000d / windowFrames));
    }

    // -----------------------------------------------------------------------
    // Scenes
    // -----------------------------------------------------------------------

    private void hold(ClientPlayerEntity player, float pitch) {
        player.yaw = startYaw;
        player.prevYaw = startYaw;
        player.pitch = pitch;
        player.prevPitch = pitch;
        player.velocityX = 0d;
        player.velocityZ = 0d;
    }

    private void setup(MinecraftClient client, Scene scene) {
        if (scene == Scene.ENTITIES) {
            spawnPlayers(client);
        } else if (scene == Scene.TRANSLUCENT) {
            buildWalls(client);
        }
    }

    private void teardown(MinecraftClient client) {
        ClientWorld world = client.world;
        if (world == null) {
            spawned.clear();
            placed.clear();
            return;
        }
        for (Integer id : spawned) {
            world.removeEntity(id.intValue());
        }
        spawned.clear();
        BlockState air = Blocks.AIR.getDefaultState();
        for (BlockPos pos : placed) {
            world.setBlockState(pos, air);
        }
        placed.clear();
    }

    /**
     * Forty-eight client-side players in a grid in front of the camera, in
     * iron armour with diamond swords: a crowded PvP view. Client-only, so the
     * save never sees them, and removed when the scene ends.
     */
    private void spawnPlayers(MinecraftClient client) {
        ClientWorld world = client.world;
        double radians = Math.toRadians(startYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        int id = -7000;
        for (int row = 0; row < 6; row++) {
            for (int column = 0; column < 8; column++) {
                double distance = 5d + row * 3d;
                double offset = (column - 3.5d) * 1.6d;
                double x = startX + forwardX * distance + forwardZ * offset;
                double z = startZ + forwardZ * distance - forwardX * offset;
                BlockPos top = world.getTopPosition(new BlockPos(x, 0d, z));
                GameProfile profile = new GameProfile(new UUID(0xF47A0000L, row * 8L + column),
                        String.format(Locale.ROOT, "Bench%02d", row * 8 + column));
                OtherClientPlayerEntity other = new OtherClientPlayerEntity(world, profile);
                other.refreshPositionAndAngles(x, top.getY(), z, startYaw + 180f, 0f);
                other.inventory.armor[0] = new ItemStack(Items.IRON_BOOTS);
                other.inventory.armor[1] = new ItemStack(Items.IRON_LEGGINGS);
                other.inventory.armor[2] = new ItemStack(Items.IRON_CHESTPLATE);
                other.inventory.armor[3] = new ItemStack(Items.IRON_HELMET);
                other.inventory.main[0] = new ItemStack(Items.DIAMOND_SWORD);
                other.inventory.selectedSlot = 0;
                world.addEntity(id, other);
                spawned.add(Integer.valueOf(id));
                id--;
            }
        }
    }

    /**
     * Two stained-glass walls with a water wall between them, client-side, in
     * front of the camera. Only air is replaced, every placed block is put back
     * to air when the scene ends, and the server never hears of any of it.
     */
    private void buildWalls(MinecraftClient client) {
        ClientWorld world = client.world;
        double radians = Math.toRadians(startYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        BlockState glass = Blocks.STAINED_GLASS.getDefaultState();
        BlockState water = Blocks.WATER.getDefaultState();
        int[] distances = {7, 10, 13};
        BlockState[] materials = {glass, water, glass};
        int baseY = (int) Math.floor(startY);
        for (int wall = 0; wall < distances.length; wall++) {
            for (int across = -10; across < 10; across++) {
                double x = startX + forwardX * distances[wall] + forwardZ * across;
                double z = startZ + forwardZ * distances[wall] - forwardX * across;
                for (int up = 0; up < 10; up++) {
                    BlockPos pos = new BlockPos(x, baseY + up, z);
                    if (world.isAir(pos) && !placed.contains(pos)) {
                        world.setBlockState(pos, materials[wall]);
                        placed.add(pos);
                    }
                }
            }
        }
    }

    /** A steady field of flame and smoke in front of the camera, at a fixed rate on the wall clock. */
    private void particles(MinecraftClient client, long now) {
        if (client.world == null || now - particlesSpawnedAt < 16_000_000L) {
            return;
        }
        particlesSpawnedAt = now;
        double radians = Math.toRadians(startYaw);
        double forwardX = -Math.sin(radians);
        double forwardZ = Math.cos(radians);
        for (int column = 0; column < 12; column++) {
            for (int row = 0; row < 3; row++) {
                double distance = 6 + row * 3;
                double offset = (column - 5.5) * 1.5;
                double x = startX + forwardX * distance + forwardZ * offset;
                double z = startZ + forwardZ * distance - forwardX * offset;
                double y = startY + 0.5 + (column % 3) * 0.4;
                client.world.addParticle(net.minecraft.client.particle.ParticleType.FIRE, x, y, z, 0, 0, 0);
                client.world.addParticle(net.minecraft.client.particle.ParticleType.SMOKE, x, y + 0.2, z, 0, 0, 0);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Arms
    // -----------------------------------------------------------------------

    private void applyArm(MinecraftClient client, String arm) {
        if (ViewReport.FATHOM_OFF.equals(arm)) {
            BenchmarkState.applyCorePass("core-bypass");
            net.fathom.optimizations.fabric.perf.PerfLayer.forceOff(true);
        } else if (arm.startsWith("of:")) {
            String outcome = flipOptiFine(client, arm.substring(3));
            if (!runNotes.contains(outcome)) {
                runNotes.add(outcome);
            }
        } else if ("hud-steady-off".equals(arm)) {
            net.fathom.optimizations.fabric.perf.HudSteadyCache.setEnabled(false);
        } else if (arm.startsWith("off:")) {
            String outcome = setSwitch(arm.substring(4), false);
            if (!runNotes.contains(outcome)) {
                runNotes.add(outcome);
            }
        }
    }

    /** What an {@code off:} arm switched off, so it can be put back exactly. */
    private final Map<String, Boolean> switchesBefore = new LinkedHashMap<String, Boolean>();

    /**
     * Turn one Fathom optimization off (or back) for a window, through the same
     * switchboard the Hub uses, so the arm is exactly what a user toggling it
     * would get. Not saved.
     */
    private String setSwitch(String id, boolean on) {
        net.fathom.optimizations.core.registry.SwitchBoard board = net.fathom.optimizations.fabric.Runtime.switches();
        if (board == null) {
            return "Switch arm " + id + ": no switchboard.";
        }
        net.fathom.optimizations.core.registry.OptimizationId key =
                net.fathom.optimizations.core.registry.OptimizationId.of(id);
        boolean normal = board.isEnabled(key);
        if (!on) {
            switchesBefore.put(id, Boolean.valueOf(normal));
            board.setByUser(key, false);
        } else {
            Boolean before = switchesBefore.remove(id);
            normal = before == null || before.booleanValue();
            board.setByUser(key, normal);
        }
        net.fathom.optimizations.fabric.Runtime.refreshHotSwitches();
        return "Switch arm " + id + ": " + (normal ? "on" : "off") + " in the normal arm, off in this arm.";
    }

    private void restoreArm(MinecraftClient client, String arm) {
        if (ViewReport.FATHOM_OFF.equals(arm)) {
            BenchmarkState.applyCorePass("normal-core");
            net.fathom.optimizations.fabric.perf.PerfLayer.forceOff(false);
        } else if (arm.startsWith("of:")) {
            flipOptiFine(client, arm.substring(3));
        } else if ("hud-steady-off".equals(arm)) {
            net.fathom.optimizations.fabric.perf.HudSteadyCache.setEnabled(true);
        } else if (arm.startsWith("off:")) {
            setSwitch(arm.substring(4), true);
        }
    }

    /**
     * Flip one OptiFine boolean option through OptiFine's own handler, exactly
     * as clicking its button would, then reload the renderers so the change
     * reaches every chunk. The settle phase waits out the rebuild.
     */
    private String flipOptiFine(MinecraftClient client, String fieldName) {
        try {
            Object options = client.options;
            Field field = options.getClass().getField(fieldName);
            boolean before = field.getBoolean(options);
            String constant = fieldName.replaceFirst("^of", "").replaceAll("([a-z])([A-Z])", "$1_$2")
                    .toUpperCase(Locale.ROOT);
            Class<?> optionType = null;
            for (Class<?> inner : options.getClass().getDeclaredClasses()) {
                if (inner.isEnum()) {
                    optionType = inner;
                }
            }
            if (optionType == null) {
                return "OptiFine arm " + fieldName + ": no option enum found.";
            }
            Object option = null;
            for (Object value : optionType.getEnumConstants()) {
                if (((Enum<?>) value).name().equals(constant)) {
                    option = value;
                }
            }
            if (option == null) {
                return "OptiFine arm " + fieldName + ": OptiFine has no option " + constant + ".";
            }
            // The boolean/cycle setter (vanilla setOptionValue) is the only
            // (Option, int) method on GameOptions, so it is found by shape
            // rather than by a name that differs between mappings.
            Method set = null;
            for (Method method : options.getClass().getMethods()) {
                Class<?>[] parameters = method.getParameterTypes();
                if (parameters.length == 2 && parameters[0] == optionType && parameters[1] == int.class
                        && method.getReturnType() == void.class) {
                    set = method;
                }
            }
            if (set == null) {
                return "OptiFine arm " + fieldName + ": no option setter found.";
            }
            set.invoke(options, option, Integer.valueOf(1));
            boolean after = field.getBoolean(options);
            client.worldRenderer.reload();
            return "OptiFine arm " + fieldName + ": " + before + " -> " + after + " via " + constant + ".";
        } catch (Throwable failure) {
            return "OptiFine arm " + fieldName + " unavailable: " + failure;
        }
    }

    // -----------------------------------------------------------------------
    // End of run
    // -----------------------------------------------------------------------

    private void finish(MinecraftClient client, String reason) {
        if (phase == Phase.DONE) {
            return;
        }
        phase = Phase.DONE;
        ViewProbe.tagNextFrame(-1, false, false);
        try {
            restoreArm(client, activeArm);
            activeArm = ViewReport.NORMAL;
            teardown(client);
        } catch (Throwable ignored) {
            // Restoring is best effort; the report still gets written.
        }
        if (reason != null) {
            runNotes.add("Run ended early: " + reason);
        }
        try {
            write(client);
        } catch (Throwable failure) {
            System.out.println("[FATHOM VIEW] could not write the report: " + failure);
        }
        if (QUIT && reason == null) {
            client.scheduleStop();
        }
    }

    private void write(MinecraftClient client) throws IOException {
        List<String> environment = environment(client);
        Map<String, String> overlays = new LinkedHashMap<String, String>();
        overlays.put(Scene.ENTITIES.id, Scene.HORIZON.id);
        overlays.put(Scene.PARTICLES.id, Scene.HORIZON.id);
        overlays.put(Scene.TRANSLUCENT.id, Scene.HORIZON.id);
        String focus = SCENES.contains(Scene.DOWN45) ? Scene.DOWN45.id : SCENES.get(SCENES.size() - 1).id;
        ViewReport report = new ViewReport(sceneIds(), ARMS, buckets, environment, probePairNanos,
                SCENES.get(0).id, focus, overlays);
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(new Date());
        File directory = new File(client.runDirectory, "logs");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            throw new IOException("cannot create " + directory);
        }
        File text = new File(directory, "fathom-view-" + stamp + ".txt");
        File csv = new File(directory, "fathom-view-" + stamp + ".csv");
        writeFile(text, report.render());
        writeFile(csv, report.csv());
        System.out.println("[FATHOM VIEW] report: " + text.getAbsolutePath());
        System.out.println("[FATHOM VIEW] data:   " + csv.getAbsolutePath());
    }

    private List<String> environment(MinecraftClient client) {
        List<String> out = new ArrayList<String>();
        out.add("Fathom build: " + fathomBuild);
        out.add("OptiFine active: " + net.fathom.optimizations.fabric.diag.RuntimeTrace.optifineActive()
                + "; OptiFabric active: " + net.fathom.optimizations.fabric.diag.RuntimeTrace.optifabricActive());
        try {
            out.add("GL: " + GL11.glGetString(GL11.GL_RENDERER) + " / " + GL11.glGetString(GL11.GL_VERSION)
                    + " / " + GL11.glGetString(GL11.GL_VENDOR));
        } catch (Throwable unavailable) {
            out.add("GL: unreadable");
        }
        if (client.options != null) {
            out.add(String.format(Locale.ROOT,
                    "Display: %dx%d, fullscreen %s, vsync %s, maxFps %d (260 = unlimited), guiScale %d",
                    client.width, client.height, client.options.fullscreen, client.options.vsync,
                    client.options.maxFramerate, client.options.guiScale));
            out.add(String.format(Locale.ROOT,
                    "Video: renderDistance %d, VBO %s (supported %s), FBO %s, fancy %s, smooth lighting %d, "
                            + "mipmaps %d, clouds %d, particles %d, entity shadows %s",
                    client.options.viewDistance, client.options.vbo,
                    com.mojang.blaze3d.platform.GLX.supportsVbo(), client.options.fbo,
                    client.options.fancyGraphics, client.options.ao, client.options.mipmapLevels,
                    client.options.cloudMode, client.options.particle, client.options.entityShadows));
            out.add("OptiFine options: " + optiFineFields(client.options));
        }
        out.add("OptiFine runtime: " + optiFineRuntime());
        out.add(String.format(Locale.ROOT, "Camera: (%.1f, %.1f, %.1f) yaw %.1f; %s", startX, startY, startZ,
                startYaw, WORLD.isEmpty() ? "world joined by hand" : "save '" + WORLD + "'"));
        out.add("Java " + System.getProperty("java.version") + ", heap max "
                + (java.lang.Runtime.getRuntime().maxMemory() >> 20) + " MB, collectors " + collectors()
                + ", " + java.lang.Runtime.getRuntime().availableProcessors() + " hardware threads");
        out.add(String.format(Locale.ROOT,
                "Method: %d round(s); per scene per round one %.1f s window per arm, arm order reversed on "
                        + "alternate rounds; settle >= %.1f s and %.1f s without chunk rebuilds/uploads "
                        + "(cap %.0f s, hit %d times); warm-up %.1f s per scene, not recorded.",
                ROUNDS, MEASURE_SECONDS, SETTLE_SECONDS, QUIET_SECONDS, SETTLE_CAP_SECONDS, settleTimeouts,
                WARMUP_SECONDS));
        if (FPS_CAP > 0) {
            out.add("Frame cap for this run: " + FPS_CAP + " fps (in memory, not saved).");
        }
        out.add(REQUIRE_FOCUS
                ? "Frames rejected when: window unfocused, a screen open, or the world not loaded."
                : "Frames rejected when: a screen open, or the world not loaded. Unfocused frames count "
                        + "(no pause, no frame cap in this mode) and are reported per bucket.");
        for (Scene scene : SCENES) {
            out.add("Scene " + scene.id + ": " + scene.description
                    + (sceneNotes.containsKey(scene.id) ? " | " + sceneNotes.get(scene.id) : ""));
        }
        List<String> unseen = new ArrayList<String>();
        for (ViewSection section : ViewSection.ALL) {
            boolean seen = false;
            for (ViewBucket bucket : buckets.values()) {
                if (bucket.arm.equals(ViewReport.NORMAL) && bucket.callsPerFrame(section) > 0d) {
                    seen = true;
                }
            }
            if (!seen) {
                unseen.add(section.name());
            }
        }
        out.add("Sections never entered in the normal arm (not reached, or the hook did not apply): " + unseen);
        out.addAll(runNotes);
        return out;
    }

    /** The world renderer's own debug lines, read once at the end of a window, never per frame. */
    private static String sceneState(MinecraftClient client) {
        StringBuilder out = new StringBuilder();
        try {
            out.append(client.worldRenderer.getChunksDebugString());
            out.append(" | ").append(client.worldRenderer.getEntitiesDebugString());
            out.append(" | particles ").append(client.particleManager.getDebugString());
        } catch (Throwable unreadable) {
            out.append("debug strings unreadable: ").append(unreadable);
        }
        return out.toString();
    }

    private static String optiFineFields(Object options) {
        StringBuilder out = new StringBuilder();
        for (Field field : options.getClass().getFields()) {
            if (!field.getName().startsWith("of") || Modifier.isStatic(field.getModifiers())
                    || !(field.getType().isPrimitive() || field.getType() == String.class)) {
                continue;
            }
            try {
                out.append(field.getName().substring(2)).append('=').append(field.get(options)).append(' ');
            } catch (Throwable unreadable) {
                // Skip a field that will not read rather than lose the line.
            }
        }
        return out.length() == 0 ? "none (OptiFine not loaded)" : out.toString().trim();
    }

    private static String optiFineRuntime() {
        String[] probes = {"isFastRender", "isRenderRegions", "isSmartAnimations", "isVbo", "isShaders",
            "isAnimatedTerrain", "isAnimatedTextures", "isConnectedTextures", "isDynamicLights",
            "isFogFancy", "isFogFast", "isFogOff", "isTreesFancy", "isMultiTexture"};
        StringBuilder out = new StringBuilder();
        try {
            Class<?> config = Class.forName("Config");
            for (String name : probes) {
                try {
                    Method method = config.getMethod(name);
                    if (method.getReturnType() == boolean.class && method.getParameterTypes().length == 0) {
                        out.append(name.substring(2)).append('=').append(method.invoke(null)).append(' ');
                    }
                } catch (NoSuchMethodException absent) {
                    // Not in this OptiFine build.
                }
            }
        } catch (Throwable unavailable) {
            return "not loaded";
        }
        return out.toString().trim();
    }

    private static String collectors() {
        StringBuilder out = new StringBuilder();
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            if (out.length() > 0) {
                out.append('/');
            }
            out.append(bean.getName());
        }
        return out.toString();
    }

    private static long[] gcTotals() {
        long count = 0L;
        long millis = 0L;
        for (GarbageCollectorMXBean bean : ManagementFactory.getGarbageCollectorMXBeans()) {
            count += Math.max(0L, bean.getCollectionCount());
            millis += Math.max(0L, bean.getCollectionTime());
        }
        return new long[] {count, millis};
    }

    private static List<String> sceneIds() {
        List<String> out = new ArrayList<String>();
        for (Scene scene : SCENES) {
            out.add(scene.id);
        }
        return out;
    }

    private static void writeFile(File file, String text) throws IOException {
        Writer writer = new OutputStreamWriter(new FileOutputStream(file), StandardCharsets.UTF_8);
        try {
            writer.write(text);
        } finally {
            writer.close();
        }
    }

    private static int intProperty(String name, int fallback) {
        try {
            return Integer.parseInt(System.getProperty(name, String.valueOf(fallback)).trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }

    private static double doubleProperty(String name, double fallback) {
        try {
            return Double.parseDouble(System.getProperty(name, String.valueOf(fallback)).trim());
        } catch (NumberFormatException invalid) {
            return fallback;
        }
    }
}
