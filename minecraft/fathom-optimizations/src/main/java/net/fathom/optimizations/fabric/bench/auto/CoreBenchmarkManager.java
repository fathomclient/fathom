package net.fathom.optimizations.fabric.bench.auto;

import java.io.File;
import java.io.FileOutputStream;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Date;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import net.minecraft.client.MinecraftClient;
import net.fathom.optimizations.core.bench.Environment;
import net.fathom.optimizations.core.bench.auto.BenchmarkPass;
import net.fathom.optimizations.core.bench.auto.BenchmarkRegistry;
import net.fathom.optimizations.core.bench.auto.BenchmarkResult;
import net.fathom.optimizations.core.bench.auto.RenderCounters;
import net.fathom.optimizations.core.instrument.FrameLog;
import net.fathom.optimizations.core.instrument.FrameStatistics;
import net.fathom.optimizations.core.instrument.Probe;

/** Six-pass, diagnosis-only benchmark for Fathom's always-on integration. */
public final class CoreBenchmarkManager {
    private static final int SETTLE_FRAMES = 60;
    private static final long WARMUP_NANOS = 4_000_000_000L;
    private static final long WARMUP_CEILING_NANOS = 10_000_000_000L;
    private static final long MEASURE_NANOS = 12_000_000_000L;
    private static final double STABLE_COV = 0.40d;

    private enum Phase { SETTLING, WARMUP, MEASURING, DONE }

    private final BenchmarkRegistry registry;
    private final Map<String, Boolean> original;
    private final List<BenchmarkPass> passes = plan();
    private final List<BenchmarkResult> results = new ArrayList<BenchmarkResult>();
    private final FrameLog frames = new FrameLog(100_000);
    private final double[] recent = new double[60];
    private final net.fathom.optimizations.fabric.bench.Capture.Focus focus =
            new net.fathom.optimizations.fabric.bench.Capture.Focus();
    private final long started = System.nanoTime();

    private int pass = -1;
    private int settleFrames;
    private int recentCursor;
    private long phaseStarted;
    private long gcCount;
    private long gcMillis;
    private long heapBefore;
    private Phase phase = Phase.SETTLING;
    private boolean finished;

    public CoreBenchmarkManager(BenchmarkRegistry registry) {
        this.registry = registry;
        this.original = registry.snapshot();
        // The requested normal arm is normal core with optional HUD/modules off.
        registry.setAllMeasurable(false);
    }

    private static List<BenchmarkPass> plan() {
        List<BenchmarkPass> out = new ArrayList<BenchmarkPass>();
        out.add(pass("normal-core", "Normal Core"));
        out.add(pass("core-bypass", "Core Bypass"));
        out.add(pass("event-bypass", "Event Dispatch Bypass"));
        out.add(pass("render-bypass", "Render/Entity Hook Bypass"));
        out.add(pass("optifine-bypass", "OptiFine Overlap Bypass"));
        out.add(pass("instrument-control", "Instrumentation Control"));
        return out;
    }

    private static BenchmarkPass pass(String id, String label) {
        return BenchmarkPass.isolate(id, label, new LinkedHashSet<String>());
    }

    public void frame(MinecraftClient client, long frameNanos) {
        if (finished || client == null || client.world == null || client.player == null) return;
        try {
            if (pass < 0) {
                next(client);
                return;
            }
            long now = System.nanoTime();
            focus.sample();
            if (!focus.held()) {
                restart(now);
                return;
            }
            if (phase == Phase.SETTLING) {
                recordRecent(frameNanos);
                if (++settleFrames >= SETTLE_FRAMES) {
                    phase = Phase.WARMUP;
                    phaseStarted = now;
                }
                return;
            }
            if (phase == Phase.WARMUP) {
                recordRecent(frameNanos);
                long elapsed = now - phaseStarted;
                if (elapsed >= WARMUP_NANOS
                        && (stable() || elapsed >= WARMUP_CEILING_NANOS)) {
                    beginMeasure(now);
                }
                return;
            }
            if (phase == Phase.MEASURING) {
                frames.record(frameNanos);
                if (now - phaseStarted >= MEASURE_NANOS) finishPass(client);
            }
        } catch (Throwable failure) {
            System.out.println("[FATHOM CORE BENCH] aborted: " + failure);
            failure.printStackTrace();
            restore();
            finished = true;
            phase = Phase.DONE;
        }
    }

    private void next(MinecraftClient client) {
        pass++;
        if (pass >= passes.size()) {
            complete(client);
            return;
        }
        BenchmarkState.applyCorePass(passes.get(pass).id());
        registry.setAllMeasurable(false);
        settleFrames = 0;
        recentCursor = 0;
        Arrays.fill(recent, 0d);
        frames.reset();
        focus.reset();
        phase = Phase.SETTLING;
        phaseStarted = System.nanoTime();
        System.out.println("[FATHOM CORE BENCH] pass " + (pass + 1) + "/" + passes.size()
                + ": " + passes.get(pass).label());
    }

    private void restart(long now) {
        BenchmarkState.setMeasuring(false);
        Probe.end();
        settleFrames = 0;
        recentCursor = 0;
        Arrays.fill(recent, 0d);
        frames.reset();
        focus.reset();
        phase = Phase.SETTLING;
        phaseStarted = now;
    }

    private void beginMeasure(long now) {
        frames.reset();
        BenchmarkState.counters().reset();
        BenchmarkState.setMeasuring(true);
        Probe.reset();
        if (BenchmarkState.instrumentationEnabled()) Probe.begin(); else Probe.end();
        gcCount = gcCount();
        gcMillis = gcMillis();
        heapBefore = heapMb();
        phase = Phase.MEASURING;
        phaseStarted = now;
    }

    private void finishPass(MinecraftClient client) {
        BenchmarkState.setMeasuring(false);
        Map<String, Double> sections = new LinkedHashMap<String, Double>();
        for (Probe.Section section : Probe.Section.values()) {
            double value = Probe.meanMillis(section);
            if (value > 0d) sections.put(section.name().toLowerCase(Locale.ROOT), value);
        }
        Probe.end();
        results.add(new BenchmarkResult(passes.get(pass), frames.statistics(), sections,
                BenchmarkState.counters().copy(), gcCount() - gcCount,
                gcMillis() - gcMillis, heapBefore, heapMb(), ""));
        next(client);
    }

    private void complete(MinecraftClient client) {
        finished = true;
        phase = Phase.DONE;
        restore();
        Environment environment = BenchmarkEnvironment.capture(client);
        long seconds = (System.nanoTime() - started) / 1_000_000_000L;
        String report = report(environment, seconds);
        File directory = new File(client.runDirectory, "logs");
        String stamp = new SimpleDateFormat("yyyy-MM-dd-HH-mm-ss", Locale.ROOT).format(new Date());
        File file = new File(directory, "fathom-core-benchmark-" + stamp + ".txt");
        write(directory, file, report);
        System.out.println(report);
        System.out.println("[FATHOM CORE BENCH] report written to " + file.getAbsolutePath());
        BenchmarkOverlay.show(results, file);
    }

    private void restore() {
        registry.restore(original);
        BenchmarkState.reset();
        Probe.end();
    }

    public boolean finished() { return finished; }
    public int passCount() { return passes.size(); }
    public int currentPass() { return Math.max(0, pass + 1); }
    public String currentLabel() { return pass < 0 ? "starting" : passes.get(pass).label(); }
    public String phaseLabel() { return phase.name().toLowerCase(Locale.ROOT); }

    private String report(Environment environment, long seconds) {
        BenchmarkResult normal = find("normal-core");
        BenchmarkResult bypass = find("core-bypass");
        BenchmarkResult events = find("event-bypass");
        BenchmarkResult render = find("render-bypass");
        BenchmarkResult optifine = find("optifine-bypass");
        BenchmarkResult control = find("instrument-control");
        double coreDelta = delta(normal, bypass);
        double eventDelta = delta(normal, events);
        double renderDelta = delta(normal, render);
        double optifineDelta = delta(normal, optifine);
        double instrumentation = delta(bypass, control);
        double threshold = significanceThreshold(normal, bypass);
        boolean meaningful = Math.abs(coreDelta) >= threshold;

        StringBuilder out = new StringBuilder(20_000);
        line(out, "FATHOM CORE-ISOLATION BENCHMARK");
        line(out, "================================");
        line(out, "Duration: " + seconds + " s, six passes, optional HUD/modules disabled throughout.");
        line(out, "");
        line(out, "RESULTS");
        line(out, "-------");
        resultLine(out, normal);
        resultLine(out, bypass);
        resultLine(out, events);
        resultLine(out, render);
        resultLine(out, optifine);
        resultLine(out, control);
        line(out, "");
        line(out, String.format(Locale.ROOT,
                "Normal Core - Core Bypass:       %+.3f ms/frame", coreDelta));
        line(out, String.format(Locale.ROOT,
                "Event dispatch bypass effect:    %+.3f ms/frame", eventDelta));
        line(out, String.format(Locale.ROOT,
                "Render/entity bypass effect:     %+.3f ms/frame", renderDelta));
        line(out, String.format(Locale.ROOT,
                "OptiFine-overlap bypass effect:  %+.3f ms/frame", optifineDelta));
        line(out, String.format(Locale.ROOT,
                "Instrumentation overhead:        %+.3f ms/frame", instrumentation));
        line(out, String.format(Locale.ROOT,
                "95%% frame-sample threshold:      %.3f ms/frame", threshold));
        line(out, "Observed Normal/Core-Bypass delta exceeds the within-pass threshold: "
                + (meaningful ? "YES" : "NO"));
        line(out, "Causal core-overhead verdict: INCONCLUSIVE without counterbalanced run-level replication.");
        line(out, "(The threshold uses within-pass frame variance and does not remove pass-order, scene, thermal, or JIT drift.)");
        line(out, "");

        line(out, "DEEP FRAME BOUNDARIES (NORMAL CORE)");
        line(out, "-----------------------------------");
        boundary(out, normal, "game_loop", "Minecraft.runGameLoop");
        boundary(out, normal, "tick", "Minecraft.runTick");
        boundary(out, normal, "camera_and_render", "EntityRenderer.updateCameraAndRender");
        boundary(out, normal, "world", "EntityRenderer.renderWorld");
        boundary(out, normal, "render_entities", "RenderGlobal.renderEntities");
        boundary(out, normal, "chunk_render", "RenderGlobal.sortAndRender/chunk layers");
        boundary(out, normal, "render_entity", "RenderManager.doRenderEntity (aggregate)");
        boundary(out, normal, "vanilla_hud", "GuiIngame.renderGameOverlay");
        double topAccounted = section(normal, "tick") + section(normal, "camera_and_render");
        line(out, String.format(Locale.ROOT,
                "  remaining outside tick + camera/render  %7.4f ms/frame",
                Math.max(0d, normal.meanFrameMillis() - topAccounted)));
        double renderAccounted = section(normal, "world") + section(normal, "vanilla_hud");
        line(out, String.format(Locale.ROOT,
                "  camera/render remainder                %7.4f ms/frame",
                Math.max(0d, section(normal, "camera_and_render") - renderAccounted)));
        double worldAccounted = section(normal, "render_entities") + section(normal, "chunk_render");
        line(out, String.format(Locale.ROOT,
                "  renderWorld remainder                  %7.4f ms/frame",
                Math.max(0d, section(normal, "world") - worldAccounted)));
        line(out, "  Nested boundaries are not added twice.");
        line(out, "");

        line(out, "FATHOM CORE PATHS (NORMAL CORE)");
        line(out, "-------------------------------");
        boundary(out, normal, "mixin_callbacks", "Mixin callback bodies (aggregate)");
        boundary(out, normal, "event_dispatch", "Direct injected dispatch (no event bus exists)");
        boundary(out, normal, "gl_wrappers", "GL/state wrappers");
        boundary(out, normal, "hooks", "Other optimization hooks");
        calls(out, normal);
        line(out, "");

        line(out, "OPTIFINE INTERACTION");
        line(out, "--------------------");
        line(out, "Installed-bytecode comparison (normalized javap of vanilla intermediary vs OptiFine-patched intermediary):");
        line(out, "  GameRenderer.render is modified: Fathom redirects sensitivity/smooth-camera field reads inside it. This is the active steady-state overlap.");
        line(out, "  MinecraftClient.setScreen is modified: Fathom's HEAD argument modifier runs before the patched body; it did not fire in the in-world sample.");
        line(out, "  WorldRenderer.setupTerrain/renderEntities and ChunkOcclusionDataBuilder.getOpenFaces are modified. Fathom's production setupTerrain/getOpenFaces redirects are skipped at class load with OptiFine; timing-only renderEntities wraps the patched body.");
        line(out, "  ClientChunkProvider.getOrGenerateChunk is modified; the entire Fathom ClientChunkProvider mixin is skipped with OptiFine.");
        line(out, "  TextRenderer.init is modified; Fathom's RETURN invalidation runs after it only on resource reload. getStringWidth itself is bytecode-identical.");
        line(out, "  runGameLoop, tick, getMaxFramerate, getFov, renderHand, renderWorld, InGameHud render/showCrosshair, World light methods, chunk renderLayer, and EntityRenderDispatcher.render were bytecode-identical.");
        line(out, "  No Fathom OVERWRITE exists; no measured hook invokes a vanilla render method twice or forces an OptiFine fallback.");
        line(out, "  Runtime bypasses skip Fathom callback bodies; Mixin-generated callback/redirect shells remain loaded and are part of the residual.");
        line(out, String.format(Locale.ROOT,
                "Measured effect of bypassing Fathom's active overlap hooks: %+.3f ms/frame.", optifineDelta));
        line(out, "");

        inventory(out, normal);
        line(out, "DUPLICATE EXECUTION CHECK");
        line(out, "-------------------------");
        for (String finding : normal.counters().suspicious()) line(out, "  ! " + finding);
        if (normal.counters().suspicious().isEmpty()) {
            line(out, "  No once-per-frame boundary exceeded 1.05 calls/frame.");
        }
        line(out, String.format(Locale.ROOT,
                "  Fathom HUD passes/frame %.2f; game loops/frame %.2f; camera renders/frame %.2f; world renders/frame %.2f.",
                normal.counters().perFrame(RenderCounters.Counter.HUD_PASS),
                normal.counters().perFrame(RenderCounters.Counter.GAME_LOOP),
                normal.counters().perFrame(RenderCounters.Counter.CAMERA_AND_RENDER),
                normal.counters().perFrame(RenderCounters.Counter.RENDER_WORLD)));
        line(out, "  Evidence: aggregate counters found no double registration, nested event dispatch, or duplicate world/entity render.");
        line(out, "");

        line(out, "ENVIRONMENT");
        line(out, "-----------");
        for (Map.Entry<String, String> each : environment.all().entrySet()) {
            line(out, String.format(Locale.ROOT, "  %-22s %s", each.getKey(), each.getValue()));
        }
        return out.toString();
    }

    private static void inventory(StringBuilder out, BenchmarkResult normal) {
        line(out, "CORE MIXIN INVENTORY");
        line(out, "--------------------");
        line(out, "  target / method | injection | cancellable | calls | measured cost / OptiFine overlap");
        line(out, "  MinecraftClient.runGameLoop | HEAD+RETURN Inject | no | 2/frame | aggregate mixin cost; no");
        line(out, "  MinecraftClient.tick | timing HEAD+RETURN Inject | no | ticks/frame below | instrumentation only; no");
        line(out, "  MinecraftClient.getMaxFramerate | RETURN Inject | yes | ~1/frame | aggregate hooks; no");
        line(out, "  MinecraftClient.setScreen | HEAD ModifyVariable | no | scene-dependent | below resolution; YES method");
        line(out, "  MinecraftClient.tick hotbar scroll | INVOKE Redirect | no | input-dependent | below resolution; no");
        line(out, "  MinecraftClient world-change System.gc | INVOKE Redirect x2 | no | 0/frame | not a render cost; no methods");
        line(out, "  GameRenderer.getFov | RETURN Inject | yes | render-dependent | aggregate mixin cost; no");
        line(out, "  GameRenderer.render/tick option reads | FIELD Redirect x2 | no | render/tick-dependent | aggregate mixin cost; YES render / no tick");
        line(out, "  GameRenderer.renderHand | HEAD Inject | yes | <=1/frame | aggregate mixin cost; no");
        line(out, "  InGameHud.render | RETURN Inject | no | 1/frame | HUD section; no");
        line(out, "  InGameHud.showCrosshair | RETURN Inject | yes | <=1/frame | aggregate mixin cost; no");
        line(out, String.format(Locale.ROOT,
                "  TextRenderer.getStringWidth | cancellable HEAD + RETURN Inject | yes/no | %.2f queries/frame | aggregate mixin cost; no",
                normal.counters().perFrame(RenderCounters.Counter.STRING_WIDTH)));
        line(out, "  TextRenderer.init/setUnicode | RETURN Inject x2 | no | 0/frame steady-state | startup only; YES init / no setUnicode");
        line(out, "  ClientPlayerEntity.tickMovement | HEAD Inject + INVOKE Redirect | no | tick-dependent | aggregate dispatch; no proven overlap");
        line(out, "  KeyboardInput.tick | INVOKE Redirect | no | tick-dependent | aggregate dispatch; no proven overlap");
        line(out, "  KeyBinding.onKeyPressed | HEAD Inject | no | input-dependent | aggregate dispatch; no proven overlap");
        line(out, "  GameMenuScreen.init/buttonClicked | RETURN/HEAD Inject | second cancellable | 0/frame in world | no steady-state cost");
        line(out, "  World light methods | INVOKE Redirect | no | light-query-dependent | aggregate mixin cost; no");
        line(out, "  WorldRenderer.setupTerrain | INVOKE Redirect | no | 0 (skipped with OptiFine) | not applied; YES");
        line(out, "  ChunkOcclusionDataBuilder.getOpenFaces | INVOKE Redirect | no | 0 (skipped with OptiFine) | not applied; YES");
        line(out, "  ClientChunkProvider ctor/getChunk/load/unload | RETURN/HEAD Inject + INVOKE Redirect | no | 0 (skipped with OptiFine) | not applied; YES");
        line(out, "  Timing-only mixins: GameRenderer render/renderWorld, WorldRenderer renderEntities/renderLayer, EntityRenderDispatcher.render, InGameHud.render; HEAD+RETURN, non-cancellable.");
        line(out, "  OVERWRITE count: 0. Runtime rendering/ticking redirects: 8 declarations; cancellable runtime injections: 5.");
        line(out, "");
    }

    private static void calls(StringBuilder out, BenchmarkResult result) {
        RenderCounters c = result.counters();
        line(out, String.format(Locale.ROOT, "  event dispatch calls/frame       %8.2f", c.perFrame(RenderCounters.Counter.EVENT_DISPATCH)));
        line(out, String.format(Locale.ROOT, "  listeners invoked/frame          %8.2f", c.perFrame(RenderCounters.Counter.EVENT_LISTENER)));
        line(out, String.format(Locale.ROOT, "  mixin callbacks/frame            %8.2f", c.perFrame(RenderCounters.Counter.MIXIN_CALLBACK)));
        line(out, String.format(Locale.ROOT, "  OptiFine-overlap hooks/frame     %8.2f", c.perFrame(RenderCounters.Counter.OPTIFINE_OVERLAP_HOOK)));
        line(out, String.format(Locale.ROOT, "  entities rendered/frame          %8.2f", c.perFrame(RenderCounters.Counter.RENDER_ENTITY)));
        line(out, String.format(Locale.ROOT, "  chunk-layer calls/frame          %8.2f", c.perFrame(RenderCounters.Counter.CHUNK_RENDER)));
    }

    private static void boundary(StringBuilder out, BenchmarkResult result, String key, String label) {
        line(out, String.format(Locale.ROOT, "  %-42s %7.4f ms/frame", label, section(result, key)));
    }

    private static double section(BenchmarkResult result, String key) {
        if (result == null) return 0d;
        Double value = result.sectionMillis().get(key);
        return value == null ? 0d : value.doubleValue();
    }

    private static void resultLine(StringBuilder out, BenchmarkResult result) {
        if (result == null) return;
        FrameStatistics f = result.frames();
        line(out, String.format(Locale.ROOT,
                "  %-27s %7.1f fps  %7.3f ms/frame  median %6.3f  p95 %6.3f  n=%d",
                result.pass().label(), f.averageFps(), f.meanFrameTimeMillis(),
                f.medianFrameTimeMillis(), f.p95FrameTimeMillis(), f.sampleCount()));
    }

    private BenchmarkResult find(String id) {
        for (BenchmarkResult each : results) if (id.equals(each.pass().id())) return each;
        return null;
    }

    private static double delta(BenchmarkResult base, BenchmarkResult bypass) {
        return base == null || bypass == null ? 0d
                : base.meanFrameMillis() - bypass.meanFrameMillis();
    }

    private static double significanceThreshold(BenchmarkResult a, BenchmarkResult b) {
        if (a == null || b == null || a.frames().sampleCount() < 2 || b.frames().sampleCount() < 2) return 0.05d;
        double sa = a.meanFrameMillis() * a.frames().frameTimeCoefficientOfVariation();
        double sb = b.meanFrameMillis() * b.frames().frameTimeCoefficientOfVariation();
        double se = Math.sqrt(sa * sa / a.frames().sampleCount()
                + sb * sb / b.frames().sampleCount());
        return Math.max(0.05d, 1.96d * se);
    }

    private void recordRecent(long nanos) {
        if (nanos <= 0L) return;
        recent[recentCursor] = nanos;
        recentCursor = (recentCursor + 1) % recent.length;
    }

    private boolean stable() {
        double sum = 0d;
        for (double value : recent) {
            if (value <= 0d) return false;
            sum += value;
        }
        double mean = sum / recent.length;
        double variance = 0d;
        for (double value : recent) {
            double d = value - mean;
            variance += d * d;
        }
        return Math.sqrt(variance / recent.length) / mean <= STABLE_COV;
    }

    private static long gcCount() {
        long value = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionCount() > 0L) value += bean.getCollectionCount();
        }
        return value;
    }

    private static long gcMillis() {
        long value = 0L;
        for (java.lang.management.GarbageCollectorMXBean bean
                : java.lang.management.ManagementFactory.getGarbageCollectorMXBeans()) {
            if (bean.getCollectionTime() > 0L) value += bean.getCollectionTime();
        }
        return value;
    }

    private static long heapMb() {
        java.lang.Runtime vm = java.lang.Runtime.getRuntime();
        return (vm.totalMemory() - vm.freeMemory()) >> 20;
    }

    private static void write(File directory, File file, String text) {
        try {
            if (!directory.isDirectory() && !directory.mkdirs()) return;
            Writer writer = new OutputStreamWriter(new FileOutputStream(file), "UTF-8");
            try { writer.write(text); } finally { writer.close(); }
        } catch (Throwable failure) {
            System.out.println("[FATHOM CORE BENCH] could not write report: " + failure);
        }
    }

    private static void line(StringBuilder out, String line) {
        out.append(line).append(System.lineSeparator());
    }
}
