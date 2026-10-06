package net.fathom.optimizations.fabric.hub;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.List;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.instrument.Report;
import net.fathom.optimizations.core.instrument.ReportStore;
import net.fathom.optimizations.core.instrument.Session;
import net.fathom.optimizations.core.module.LaunchSession;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Box;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.bench.Capture;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.GlRenderer;
import net.fathom.optimizations.fabric.gfx.SdfRenderer;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.FathomComponents;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.ui.Graphite;

/**
 * Diagnostics: what the client is actually doing.
 *
 * <h2>Why a client menu has a page like this</h2>
 *
 * <p>Because everything else in the hub is a claim. "Fourteen features",
 * "nineteen optimizations", "the launcher is connected" — this is the page that
 * says how many are switched on, which ones failed to register, and what the
 * frame rate actually was, so the claims can be checked rather than believed.
 *
 * <p>It is also where a benchmark starts and where the renderer's own cost is
 * reported: a measurement anybody can take is the difference between a
 * performance page and an advertisement.
 *
 * <h2>Logs</h2>
 *
 * <p>Two of them, and they are different things. The module log is what Fathom
 * itself said, in order, with nothing in between — twenty lines that answer "did
 * my optimizations apply". The game log is the tail of Minecraft's own
 * `latest.log`, which is where the answer lives when it is not in the first one.
 *
 * <p>Both can be copied to the clipboard or written to a file, because the
 * thing somebody does after reading them is send them to somebody else. What
 * goes in neither is any part of the session descriptor's account fields — there
 * are none; see {@code LaunchSession}.
 */
final class DiagnosticsSection implements Section {

    private static final float FACT_ROW = 28f;

    /** How many lines of each log are shown, newest last. */
    private static final int LOG_LINES = 14;

    private final Scroller scroller = new Scroller();
    private String note = "";
    private final Box page = Box.column("diagnostics.page");
    private final Box toolbarSlot = Box.stack("diagnostics.toolbar");
    private final Box toolbarAfter = Box.column("diagnostics.toolbar.after").height(Skin.S5);
    private final Box factsViewport = Box.stack("diagnostics.facts.viewport").grow(1f);
    private final Box factsLayout = Box.column("diagnostics.facts").scroll(true)
            .padding(Skin.S3, Skin.S2, Skin.S4, Skin.S2);
    private final Box benchBox = Box.row("diagnostics.benchmark").height(Skin.H_MD);
    private final Box scenarioBox = Box.row("diagnostics.scenario").height(Skin.H_MD);
    private final Box spikeBox = Box.row("diagnostics.spike").height(Skin.H_MD);
    private final Box copyBox = Box.row("diagnostics.copy").height(Skin.H_MD);
    private final Box saveBox = Box.row("diagnostics.save").height(Skin.H_MD);
    private final Box launcherBox = Box.row("diagnostics.launcher").height(Skin.H_MD);
    private final java.util.List<Box> factBoxes = new java.util.ArrayList<Box>();
    private static final String[] SCENARIOS = scenarioNames();

    DiagnosticsSection() {
        page.add(toolbarSlot);
        page.add(toolbarAfter);
        page.add(factsViewport);
    }

    /**
     * Whether the window kept focus for the whole run.
     *
     * <p>Sampled every frame the page is drawn, and every frame the game is
     * drawn — see {@code FathomHud.render}. A run that lost focus reports the
     * unfocused frame cap rather than the machine, which is how six
     * configurations come to produce six identical numbers.
     */
    private static Capture.Focus focus() {
        return Runtime.focus();
    }

    /** Which scenario is being played, for the run's own record. */
    private String scenario = "cpu-ceiling";

    /** The game log, read once per open rather than per frame. */
    private List<String> gameLog = new ArrayList<String>();

    private static String[] scenarioNames() {
        java.util.List<net.fathom.optimizations.core.bench.Scenario> all =
                net.fathom.optimizations.core.bench.Scenarios.all();
        String[] names = new String[all.size()];
        for (int index = 0; index < names.length; index++) {
            names[index] = all.get(index).id();
        }
        return names;
    }

    @Override
    public String title() {
        return "Diagnostics";
    }

    @Override
    public Icon icon() {
        return Icon.STETHOSCOPE;
    }

    @Override
    public boolean searchable() {
        return false;
    }

    private static boolean hasDigit(String text) {
        for (int index = 0; index < text.length(); index++) {
            if (Character.isDigit(text.charAt(index))) {
                return true;
            }
        }
        return false;
    }

    @Override
    public String subtitle() {
        return "Client status and tests.";
    }

    @Override
    public void opened(HubContext context) {
        scroller.reset();
        // Read here and not in `render`: this is a file on disk, and a screen
        // that read one sixty times a second would be the "do not poll disk in
        // render" rule broken in the one page that exists to report on
        // performance.
        gameLog = tailOfGameLog();
    }

    @Override
    public void render(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        focus().sample();

        boolean recording = Runtime.session() != null;

        // Every button is as wide as its label needs. When the two clusters do
        // not fit on one line they take two, rather than being drawn over each
        // other at fixed widths.
        float pad = Skin.S4 * 2f;
        float benchWidth = Math.max(120f, ui.measure(recording ? "Stop recording" : "Benchmark", Type.BODY_STRONG) + pad);
        float spikeWidth = ui.measure("Renderer proof", Type.BODY_STRONG) + pad;
        float launcherWidth = ui.measure("Launcher diagnostics", Type.BODY_STRONG) + pad;
        float saveWidth = Math.max(72f, ui.measure("Save", Type.BODY_STRONG) + pad);
        float copyWidth = Math.max(72f, ui.measure("Copy", Type.BODY_STRONG) + pad);
        float leftWidth = benchWidth + Skin.S3 + 170f + Skin.S3 + spikeWidth;
        float rightWidth = launcherWidth + Skin.S2 + saveWidth + Skin.S2 + copyWidth;
        boolean twoLines = leftWidth + Skin.S6 + rightWidth > area.width;
        float toolbarHeight = twoLines ? Skin.H_MD * 2f + Skin.S2 : Skin.H_MD;
        toolbarSlot.height(toolbarHeight);
        page.layout(ui, area.x, area.y, area.width, area.height);
        Rect bar = toolbarSlot.rect().top(Skin.H_MD);
        Rect rightBar = twoLines ? toolbarSlot.rect().belowTop(Skin.H_MD + Skin.S2).top(Skin.H_MD) : bar;

        benchBox.layout(ui, bar.x, bar.y, benchWidth, Skin.H_MD);
        Rect bench = benchBox.rect();
        if (recording
                ? controls.danger(ui, "diag.bench", bench, "Stop recording", true)
                : controls.primary(ui, "diag.bench", bench, "Benchmark", true)) {
            benchmark(context);
        }

        // Which scene this run is of. Recorded into the file, because a frame
        // time without a scene is not comparable to anything -- a CPU-ceiling
        // number and a forest number differ by more than any optimization will.
        int chosenScenario = 0;
        for (int index = 0; index < SCENARIOS.length; index++) {
            if (SCENARIOS[index].equals(scenario)) {
                chosenScenario = index;
            }
        }
        scenarioBox.layout(ui, bench.right() + Skin.S3, bar.y, 170f, Skin.H_MD);
        Rect picker = scenarioBox.rect();
        int picked = controls.dropdown(ui, "diag.scenario", picker, SCENARIOS, chosenScenario);
        if (picked != chosenScenario && picked >= 0 && picked < SCENARIOS.length) {
            scenario = SCENARIOS[picked];
        }

        spikeBox.layout(ui, picker.right() + Skin.S3, bar.y, spikeWidth, Skin.H_MD);
        Rect spike = spikeBox.rect();
        if (controls.secondary(ui, "diag.spike", spike, "Renderer proof", true)) {
            context.openSpike();
        }

        // Copy and Save sit at the right, where a document's actions go, and
        // "Open launcher diagnostics" beside them — disabled when the launcher
        // is not running, because a button that silently does nothing is the
        // thing this client spent a milestone removing.
        boolean connected = Runtime.bridge() != null
                && Runtime.bridge().state() == net.fathom.optimizations.fabric.bridge.LauncherBridge.State.READY;
        float launcherX = twoLines ? rightBar.x + copyWidth + Skin.S2 + saveWidth + Skin.S2
                : rightBar.right() - launcherWidth;
        launcherBox.layout(ui, launcherX, rightBar.y, launcherWidth, Skin.H_MD);
        Rect launcher = launcherBox.rect();
        if (controls.secondary(ui, "diag.launcher", launcher, "Launcher diagnostics", connected)) {
            note = Runtime.bridge().openDiagnostics()
                    ? "Opened in the launcher."
                    : "The launcher did not answer.";
        }
        saveBox.layout(ui, launcher.x - Skin.S2 - saveWidth, rightBar.y, saveWidth, Skin.H_MD);
        Rect save = saveBox.rect();
        if (controls.secondary(ui, "diag.save", save, "Save", true)) {
            saveReport(context);
        }
        copyBox.layout(ui, save.x - Skin.S2 - copyWidth, rightBar.y, copyWidth, Skin.H_MD);
        Rect copy = copyBox.rect();
        if (controls.secondary(ui, "diag.copy", copy, "Copy", true)) {
            net.minecraft.client.gui.screen.Screen.setClipboard(report(context));
            note = "Copied to the clipboard.";
        }

        // The status line sits after the last control on whichever line has room.
        Rect messageBar = twoLines ? rightBar : bar;
        float messageX = twoLines ? launcher.right() + Skin.S4 : spike.right() + Skin.S4;
        float messageRight = twoLines ? messageBar.right() : copy.x - Skin.S4;
        if (recording) {
            Session running = Runtime.session();
            String state = running.warmupComplete()
                    ? "Recording. Play, then stop."
                    : running.warmupRemaining() + " warm-up frames left";
            ui.text(Text.fit(ui, state, Type.BODY, Math.max(0f, messageRight - messageX)), messageX,
                    Controls.baseline(ui, messageBar, Type.BODY), Type.BODY, Skin.TEXT_FAINT);
        } else if (!note.isEmpty()) {
            // A confirmation, not a status colour: a small success dot and
            // neutral words.
            float dot = 6f;
            float cy = messageBar.centreY();
            ui.rounded(messageX, cy - dot / 2f, messageX + dot, cy + dot / 2f, dot / 2f, Skin.GOOD);
            ui.text(Text.fit(ui, note, Type.BODY, Math.max(0f, messageRight - messageX - dot - Skin.S2)),
                    messageX + dot + Skin.S2, Controls.baseline(ui, messageBar, Type.BODY), Type.BODY,
                    Skin.TEXT_MUTED);
        }

        Rect body = factsViewport.rect();
        FathomComponents.group(ui, body, Skin.R_CARD);
        List<String[]> facts = facts(context);
        float contentHeight = facts.size() * FACT_ROW + Skin.S2 * 2f;
        float offset = scroller.offset(context.delta(), contentHeight, body.height);
        Rect viewport = body;
        syncFactBoxes(facts.size());
        factsLayout.scrollTo(offset);
        factsLayout.layout(ui, body.x, body.y, body.width, body.height);
        body = factsLayout.content();

        ui.clip(body.x, body.y, body.right(), body.bottom());
        for (int index = 0; index < facts.size(); index++) {
            Rect row = factBoxes.get(index).rect();
            if (row.bottom() < body.y || row.y > body.bottom()) {
                continue;
            }
            String[] fact = facts.get(index);
            if (fact.length > 2 && "log".equals(fact[2])) {
                // A log line: full width, quiet, and clipped rather than
                // wrapped. A wrapped stack trace in a fact list is unreadable in
                // a different way from a truncated one, and truncated at least
                // keeps one line per event.
                ui.text(Text.fit(ui, fact[0], Type.CAPTION, row.width), row.x,
                        Controls.baseline(ui, row, Type.CAPTION), Type.CAPTION, Skin.TEXT_DISABLED);
            } else if (fact[1] == null) {
                Controls.label(ui, fact[0], row.x, row.y + 10f);
            } else {
                ui.text(Text.fit(ui, fact[0], Type.BODY, row.width * 0.6f), row.x,
                        Controls.baseline(ui, row, Type.BODY), Type.BODY, Skin.TEXT_FAINT);
                // Measured values, versions and paths are data: Geist Mono.
                // A value that is only words ("Default", "Not Fathom") stays Geist.
                Type value = hasDigit(fact[1]) || fact[1].indexOf('\\') >= 0
                        ? Graphite.floor(ui, Graphite.DATA_S) : Type.BODY;
                ui.textRight(Text.fit(ui, fact[1], value, row.width * 0.4f), row.right(),
                        Controls.baseline(ui, row, value), value, colourOf(fact));
            }
        }
        ui.unclip();
        scroller.rail(ui, viewport, context.pointer(), context.now());
    }

    private void syncFactBoxes(int count) {
        if (factBoxes.size() == count) {
            return;
        }
        factsLayout.children().clear();
        factsLayout.invalidate();
        factBoxes.clear();
        for (int index = 0; index < count; index++) {
            Box row = Box.row("diagnostics.fact." + index).height(FACT_ROW);
            factBoxes.add(row);
            factsLayout.add(row);
        }
    }

    /**
     * Start or finish a benchmark run.
     *
     * <p>A run records what it was — which configuration, which scenario, and
     * every setting that has to match before two runs may be compared — and is
     * refused rather than saved when any of that is missing or when the window
     * lost focus. See {@code Run.rejectionReason} for why each of those matters.
     */
    private void benchmark(HubContext context) {
        if (Runtime.session() == null) {
            focus().reset();
            String configuration = Capture.configuration();
            Runtime.beginSession("in-game")
                    .configuration(configuration)
                    .scenario(scenario)
                    .environment(Capture.environment(MinecraftClient.getInstance()));
            note = "Recording " + configuration + ". Do not alt-tab.";
            return;
        }
        Report report = Runtime.endSession();
        if (report == null) {
            note = "Nothing was recorded.";
            return;
        }

        // The check that stops a bad run being published. Every one of these has
        // happened during this milestone's own testing, and each produces a
        // number that looks fine and means nothing.
        String refusal = net.fathom.optimizations.core.bench.Run.rejectionReason(report, focus().held());
        if (refusal != null) {
            note = "Discarded: " + refusal;
            return;
        }

        try {
            File written = net.fathom.optimizations.core.bench.Run.save(gameDirectory(), report);
            note = Math.round(report.frames().averageFps())
                    + " fps average, "
                    + Math.round(report.frames().onePercentLowFps())
                    + " fps 1% low, Fathom "
                    + String.format(java.util.Locale.ROOT, "%.3f", Double.valueOf(
                            report.fathomMillisPerFrame()))
                    + " ms/frame. Saved as "
                    + written.getName();
        } catch (IOException problem) {
            note = "Could not save: " + problem.getMessage();
        }
    }

    /** Warnings read as warnings, and everything else reads as data. */
    private static int colourOf(String[] fact) {
        return fact.length > 2 && "warn".equals(fact[2]) ? Skin.WARN : Skin.TEXT;
    }

    private static String[] heading(String title) {
        return new String[] {title, null};
    }

    private static String[] fact(String label, String value) {
        return new String[] {label, value};
    }

    private static String[] warning(String label, String value) {
        return new String[] {label, value, "warn"};
    }

    private static String[] logLine(String text) {
        return new String[] {text, "", "log"};
    }

    private List<String[]> facts(HubContext context) {
        List<String[]> out = new ArrayList<String[]>();

        out.add(heading("Session"));
        out.add(fact("Profile", context.hud().sessionName()));
        if (context.hud().profiles() != null) {
            net.fathom.optimizations.core.profile.ProfileLocation.Resolved where =
                    context.hud().profiles().location();
            out.add(fact("Profiles saved in", where.source.label()));
            out.add(fact("Profile folder", where.directory.getPath()));
            for (String complaint : context.hud().profiles().complaints()) {
                out.add(warning("Profile problem", complaint));
            }
        }
        // How the game was started is a fact about this session for somebody
        // debugging it, not a name for the loadout, so it is here and nowhere else.
        out.add(fact("Installation", context.hud().sessionKind()));
        LaunchSession session = context.hud().launcherSession();
        if (session.present()) {
            out.add(fact("Minecraft", session.minecraft()));
            out.add(fact("Loader", session.loaderVersion().isEmpty()
                    ? session.loader()
                    : session.loader() + " " + session.loaderVersion()));
            out.add(fact("Performance preset", session.preset()));
            if (!session.component().isEmpty()) {
                out.add(fact("Fathom component", session.component()));
            }
            if (!session.launcherVersion().isEmpty()) {
                out.add(fact("Launcher", session.launcherVersion()));
            }
        } else {
            // Not a failure, and it does not read as one. The game was started
            // some other way and there is nothing to report about a launcher
            // that was not involved.
            out.add(fact("Started by", "Not Fathom"));
            out.add(fact("Minecraft", "1.8.9"));
            out.add(fact("Loader", loaderName()));
        }

        out.add(heading("Now"));
        out.add(fact("Frames per second", String.valueOf(MinecraftClient.getCurrentFps())));
        out.add(fact(
                "Memory",
                megabytes(java.lang.Runtime.getRuntime().totalMemory() - java.lang.Runtime.getRuntime().freeMemory())
                        + " of "
                        + megabytes(java.lang.Runtime.getRuntime().maxMemory())));

        out.add(heading("Interface renderer"));
        Gfx.Frames frames = Gfx.frames();
        out.add(fact("Frame time", millis(frames.frameMeanMillis()) + " ms mean"));
        out.add(fact("Frame time p95 / p99",
                millis(frames.framePercentileMillis(0.95f)) + " / " + millis(frames.framePercentileMillis(0.99f))
                        + " ms"));
        out.add(fact("Fathom draw",
                millis(frames.uiMeanMillis()) + " ms mean, p99 " + millis(frames.uiPercentileMillis(0.99f))));
        Renderer activeUi = context.ui();
        out.add(fact("Backend", Gfx.screenBackend()));
        if (activeUi instanceof SdfRenderer) {
            SdfRenderer renderer = (SdfRenderer) activeUi;
            SdfRenderer.Stats stats = renderer.stats();
            out.add(fact("Geometry", stats.lastQuads + " quads in " + stats.lastDraws + " draw calls"));
            out.add(fact("Upload", stats.lastBytes + " bytes; " + stats.lastBlurPasses + " blur passes"));
            out.add(fact("Scale", String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(renderer.scale()))
                    + "× on " + renderer.scaleInfo().framebufferWidth() + "×"
                    + renderer.scaleInfo().framebufferHeight()
                    + (context.hud().prefs().scaleIsAutomatic() ? " (automatic)" : " (" + context.hud().prefs()
                            .scaleLabel() + ")")));
        } else if (activeUi instanceof GlRenderer) {
            GlRenderer renderer = (GlRenderer) activeUi;
            GlRenderer.Stats stats = renderer.stats();
            out.add(fact("Geometry", stats.lastQuads + " quads in " + stats.lastDraws + " draw calls"));
            out.add(fact("Caches", stats.glyphPages + " glyph pages, " + stats.iconEntries + " icons"));
            out.add(fact("Scale", String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(renderer.scale()))
                    + "× on " + renderer.scaleInfo().framebufferWidth() + "×"
                    + renderer.scaleInfo().framebufferHeight()
                    + (context.hud().prefs().scaleIsAutomatic() ? " (automatic)" : " (" + context.hud().prefs()
                            .scaleLabel() + ")")));
            if (stats.fallbackFont) {
                out.add(warning("Typeface", "Geist missing, using a fallback"));
            }
        } else {
            int framebufferWidth = Math.round(activeUi.width() * activeUi.scale());
            int framebufferHeight = Math.round(activeUi.height() * activeUi.scale());
            out.add(fact("Scale", String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(activeUi.scale()))
                    + "× on " + framebufferWidth + "×" + framebufferHeight
                    + (context.hud().prefs().scaleIsAutomatic() ? " (automatic)" : " ("
                            + context.hud().prefs().scaleLabel() + ")")));
        }
        out.add(fact("Animations", String.valueOf(context.controls().animationCount())));
        // The frame that discovers a hover is the frame that draws it.
        long worst = net.fathom.optimizations.fabric.ui.HoverLatency.worstFramesWaited();
        String hover = net.fathom.optimizations.fabric.ui.HoverLatency.summary();
        out.add(worst > 0L ? warning("Hover latency", hover) : fact("Hover latency", hover));

        out.add(heading("Client"));
        out.add(fact("Features", context.features().enabledCount() + " of " + context.features().size() + " on"));
        int widgets = 0;
        for (net.fathom.optimizations.core.hud.Widget widget : context.hud().registry().all()) {
            if (context.hud().widgetEnabled(widget.id())) {
                widgets++;
            }
        }
        out.add(fact("HUD widgets", widgets + " of " + context.hud().registry().size() + " shown"));
        out.add(fact("Module version", version()));

        out.add(heading("Performance"));
        if (Runtime.switches() == null) {
            out.add(warning("Switch board", "not running"));
        } else {
            out.add(fact("Optimizations",
                    Runtime.switches().enabled().size() + " of " + Runtime.registry().size() + " on"));
            int off = Runtime.switches().offAutomatically().size();
            if (off > 0) {
                out.add(warning("Switched off for you", String.valueOf(off)));
            }
        }
        out.add(fact("Saved benchmarks", String.valueOf(ReportStore.list(gameDirectory()).length)));

        out.add(heading("Loaded mods"));
        List<String> loaded = loadedMods();
        out.add(fact("Count", String.valueOf(loaded.size())));
        for (String mod : loaded) {
            out.add(logLine(mod));
        }

        out.add(heading("Launcher"));
        out.add(fact("Bridge", Runtime.bridge().state().name().toLowerCase(java.util.Locale.ROOT)));

        out.add(heading("Fathom module log"));
        List<String> module = net.fathom.optimizations.core.module.ModuleLog.recent(LOG_LINES);
        if (module.isEmpty()) {
            out.add(fact("Nothing recorded", ""));
        }
        for (String line : module) {
            out.add(logLine(line));
        }

        out.add(heading("Game log"));
        if (gameLog.isEmpty()) {
            out.add(fact("logs/latest.log", "not readable"));
        }
        for (String line : gameLog) {
            out.add(logLine(line));
        }

        List<String> complaints = new ArrayList<String>(context.features().complaints());
        complaints.addAll(context.hud().complaints());
        if (!complaints.isEmpty()) {
            out.add(heading("Problems"));
            for (String complaint : complaints) {
                out.add(warning(complaint, ""));
            }
        }
        return out;
    }

    private static String millis(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(value));
    }

    private static String version() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer("fathom-optimizations")
                    .get()
                    .getMetadata()
                    .getVersion()
                    .getFriendlyString();
        } catch (RuntimeException unavailable) {
            // Running outside a loader, or under one that does not know us. A
            // dash is better than a guess.
            return "—";
        }
    }

    private static String megabytes(long bytes) {
        return Math.round(bytes / (1024.0 * 1024.0)) + " MB";
    }

    /**
     * Everything Fabric loaded, by name and version.
     *
     * <p>Asked of the loader rather than by listing the mods directory: the
     * loader's answer is what is actually running, and a jar on disk that failed
     * to load is exactly the case where the two differ and the difference is the
     * answer somebody needs.
     */
    private static List<String> loadedMods() {
        List<String> out = new ArrayList<String>();
        try {
            for (net.fabricmc.loader.api.ModContainer container :
                    FabricLoader.getInstance().getAllMods()) {
                String id = container.getMetadata().getId();
                // Fabric's own internals are not what somebody means by "loaded
                // mods", and listing them pushes the four that matter off the
                // screen.
                if (id.startsWith("fabric") && !id.equals("fabric-api")) {
                    continue;
                }
                if (id.equals("minecraft") || id.equals("java")) {
                    continue;
                }
                out.add(container.getMetadata().getName() + "  "
                        + container.getMetadata().getVersion().getFriendlyString());
            }
        } catch (RuntimeException unavailable) {
            // Running outside a loader. A shorter list is better than a screen
            // that throws while reporting on the health of the client.
        }
        java.util.Collections.sort(out);
        return out;
    }

    /** Which loader is running, when there is no descriptor to ask. */
    private static String loaderName() {
        try {
            return "Fabric " + FabricLoader.getInstance()
                    .getModContainer("fabricloader")
                    .get()
                    .getMetadata()
                    .getVersion()
                    .getFriendlyString();
        } catch (RuntimeException unavailable) {
            return "—";
        }
    }

    /**
     * The last lines of Minecraft's own log.
     *
     * <p>Read from the end: `latest.log` is megabytes by the time anybody looks
     * at it, and reading all of it to show fourteen lines would stall the frame
     * that opened this page.
     */
    private static List<String> tailOfGameLog() {
        List<String> out = new ArrayList<String>();
        File log = new File(gameDirectory(), "logs/latest.log");
        if (!log.isFile()) {
            return out;
        }
        java.io.RandomAccessFile file = null;
        try {
            file = new java.io.RandomAccessFile(log, "r");
            long length = file.length();
            long window = Math.min(length, 64L * 1024L);
            file.seek(length - window);
            byte[] bytes = new byte[(int) window];
            file.readFully(bytes);
            String[] all = new String(bytes, "UTF-8").split("\n");
            // The first line of the window is almost certainly half a line.
            int from = Math.max(all.length - LOG_LINES, window < length ? 1 : 0);
            for (int index = from; index < all.length; index++) {
                String line = all[index].trim();
                if (!line.isEmpty()) {
                    out.add(line);
                }
            }
        } catch (Exception unreadable) {
            // A log being written to by another thread, a permission problem, a
            // missing file. None of them is worth a failure on this page.
        } finally {
            if (file != null) {
                try {
                    file.close();
                } catch (IOException ignored) {
                    // Nothing useful to do with a failed close on a read.
                }
            }
        }
        return out;
    }

    /**
     * Everything on this page, as text.
     *
     * <p>What Copy puts on the clipboard and what Save writes. Built from the
     * same {@code facts} the screen draws, so a bug report contains what the
     * user was looking at rather than a second, differently assembled summary.
     */
    private String report(HubContext context) {
        StringBuilder out = new StringBuilder(4096);
        out.append("Fathom diagnostics\n");
        for (String[] fact : facts(context)) {
            if (fact.length > 2 && "log".equals(fact[2])) {
                out.append("    ").append(fact[0]).append('\n');
            } else if (fact[1] == null) {
                out.append('\n').append(fact[0]).append('\n');
            } else {
                out.append("  ").append(fact[0]).append(": ").append(fact[1]).append('\n');
            }
        }
        return out.toString();
    }

    private void saveReport(HubContext context) {
        File directory = new File(gameDirectory(), "fathom");
        if (!directory.isDirectory() && !directory.mkdirs()) {
            note = "Could not create " + directory.getName() + ".";
            return;
        }
        File file = new File(directory, "diagnostics-" + System.currentTimeMillis() + ".txt");
        java.io.Writer writer = null;
        try {
            writer = new java.io.OutputStreamWriter(new java.io.FileOutputStream(file), "UTF-8");
            writer.write(report(context));
            note = "Saved as " + file.getName();
        } catch (IOException problem) {
            note = "Could not save: " + problem.getMessage();
        } finally {
            if (writer != null) {
                try {
                    writer.close();
                } catch (IOException ignored) {
                    // The message above has already been set either way.
                }
            }
        }
    }

    private static File gameDirectory() {
        return FabricLoader.getInstance().getGameDir().toFile();
    }

    @Override
    public void scroll(int notches) {
        scroller.wheel(notches);
    }
}
