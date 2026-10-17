package net.fathom.optimizations.fabric.hud;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.font.TextRenderer;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.module.ModuleStore;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.hud.HudFlow;
import net.fathom.optimizations.core.hud.HudLayout;
import net.fathom.optimizations.core.hud.HudRegistry;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.hud.Snap;
import net.fathom.optimizations.core.hud.Widget;
import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.module.SessionName;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.SdfRenderer;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.fabric.gfx.MinecraftGuiSize;

/**
 * The HUD: what is drawn, where, and how the editor sees it.
 *
 * <p>One instance, created when the mod initialises and reached through
 * {@link net.fathom.optimizations.fabric.Runtime}. It owns three things — the registry of
 * what exists, the layout of where things are, and the renderers that draw them — and
 * everything else asks it.
 *
 * <h2>Why the editor and the game share this class</h2>
 *
 * <p>Because they have to agree. {@link #boxes} computes every widget's rectangle from the
 * same placement and the same measured size that {@link #render} draws from, so a widget
 * you can click in the editor is exactly the widget you can see in game. Computing the
 * boxes separately is how an editor ends up with hit targets a few pixels off from what is
 * on screen, and the bug is invisible until somebody complains that a widget is hard to
 * grab.
 */
public final class FathomHud implements net.fathom.optimizations.core.profile.ProfileHost {

    private final HudRegistry registry = HudRegistry.standard();
    private final ClickTracker clicks = new ClickTracker();
    private final Map<String, HudWidget> renderers = new LinkedHashMap<String, HudWidget>();
    /** Reused selection and entries: the HUD is an every-game-frame hot path. */
    private final List<Drawable> drawing = new ArrayList<Drawable>();
    private final List<Drawable> drawablePool = new ArrayList<Drawable>();
    /** Editor geometry is rebuilt while dragging; retain its entries instead of churning them. */
    private final List<Positioned> positioned = new ArrayList<Positioned>();
    private final List<Positioned> positionedPool = new ArrayList<Positioned>();
    private final File gameDirectory;
    private final ModuleRegistry features;
    private final ModuleStore featureStore = new ModuleStore();

    /**
     * The key that opens the hub.
     *
     * <p>Right Shift by default, because that is the key players of this genre
     * already reach for, and rebindable because a fixed key is a key that
     * collides with somebody's setup. It lives here rather than on a feature
     * for one reason: a feature can be switched off, and switching off the only
     * way to open the menu is a state nobody should be able to reach.
     */
    private final Keybind hubKey = new Keybind("hub", "Open Fathom Hub", 54).withoutPrimaryButtons();

    private HudLayout layout;
    private final ClientPrefs prefs = new ClientPrefs();

    /**
     * The widget the editor is dragging, or null. It is drawn exactly where the
     * pointer puts it -- the others still move off it -- so a drag never fights
     * the pointer. See {@link HudFlow}.
     */
    private String pinned;

    /** {@link HudFlow}'s arrays, grown when a widget is added and otherwise reused. */
    private int[] flowX = new int[16];
    private int[] flowY = new int[16];
    /** Where each widget was before the flow moved it, for the editor's note. */
    private int[] flowNaturalY = new int[16];
    private int[] flowW = new int[16];
    private int[] flowH = new int[16];
    private int[] flowDirection = new int[16];
    private int[] flowOrder = new int[16];
    private boolean[] flowFixed = new boolean[16];

    /** The live Fathom Profile and its collection. Null until startup attaches it. */
    private net.fathom.optimizations.fabric.profile.ProfileRuntime profiles;

    public FathomHud(File gameDirectory, ModuleRegistry features) {
        this.gameDirectory = gameDirectory;
        this.features = features;
        for (HudWidget widget : Widgets.all(clicks)) {
            renderers.put(widget.id(), widget);
        }
        this.layout = HudLayout.read(gameDirectory, registry);
        this.featureStore.load(gameDirectory, features);
        this.featureStore.loadClient(gameDirectory, hubKey, prefs);
    }

    public ModuleRegistry features() {
        return features;
    }

    /** Where this installation keeps its files. For the editor preferences. */
    public java.io.File gameDirectory() {
        return gameDirectory;
    }

    /**
     * Attach the profile runtime. From here on the live profile is what gets
     * saved, not the old properties files.
     */
    public void attachProfiles(net.fathom.optimizations.fabric.profile.ProfileRuntime runtime) {
        this.profiles = runtime;
    }

    /** The live profile and its collection, or null before startup attached it. */
    public net.fathom.optimizations.fabric.profile.ProfileRuntime profiles() {
        return profiles;
    }

    /** Replace the layout wholesale. For a profile switch, which owns the whole arrangement. */
    public void replaceLayout(HudLayout next) {
        if (next != null) {
            layout = next;
        }
    }

    public Keybind hubKey() {
        return hubKey;
    }

    /**
     * Preferences that belong to the client rather than to any feature.
     *
     * <p>The interface scale, and nothing else since the themes were removed.
     * It is written down because anything the interface lets somebody change
     * has to survive a restart: the theme that came before it lived in a field
     * nothing persisted, which looked exactly like a bug and was one.
     */
    public ClientPrefs prefs() {
        return prefs;
    }

    /**
     * The live Fathom Profile's name: what the header, the main menu and the
     * Profiles page call the loadout the player is using.
     *
     * <p>This used to be the launcher's installation name, or "Development
     * Profile" under Gradle — a name for how the game was started, shown where a
     * player reads it as a name for their settings. How the game was started is
     * {@link #sessionKind()} now, and only Diagnostics shows it.
     */
    public String sessionName() {
        return profiles == null ? net.fathom.optimizations.core.profile.FathomProfile.DEFAULT_NAME
                : profiles.liveName();
    }

    /**
     * How this game was started: the launcher's installation name, the
     * development client, or a game started by hand. For Diagnostics.
     */
    public String sessionKind() {
        return SessionName.of(
                net.fathom.optimizations.fabric.Runtime.bridge().profileName(),
                net.fathom.optimizations.fabric.Platform.development());
    }

    /**
     * What the launcher said this launch is, with no secret in it.
     *
     * <p>Read from {@code fathom/session.json}, which is written before the
     * process starts and therefore survives the launcher being closed
     * afterwards. Absent for a game somebody started themselves, which is a
     * fact and not a failure — see {@code SessionName}.
     */
    public net.fathom.optimizations.core.module.LaunchSession launcherSession() {
        return net.fathom.optimizations.fabric.Runtime.bridge() == null
                ? net.fathom.optimizations.core.module.LaunchSession.absent()
                : net.fathom.optimizations.fabric.Runtime.bridge().session();
    }

    /** Whether the launcher started this game, and so can be asked things. */
    public boolean startedByLauncher() {
        return SessionName.fromLauncher(net.fathom.optimizations.fabric.Runtime.bridge().profileName());
    }

    /**
     * Register a widget renderer supplied through the public API.
     *
     * @return whether it was accepted. A duplicate id is refused rather than
     *     replacing a widget somebody else is drawing.
     */
    public boolean addRenderer(HudWidget renderer) {
        return addRenderer(renderer, null, null);
    }

    /**
     * Register a widget renderer supplied through the public API, and the
     * widget it draws, so the HUD draws it and the editor can move it.
     *
     * @param owner the module whose switch shows and hides it; it must name the
     *     widget, or the widget would have no switch
     * @param where where it starts, or null for the top-left corner
     */
    public boolean addRenderer(HudWidget renderer, FathomModule owner, Placement where) {
        if (renderer == null || renderers.containsKey(renderer.id()) || registry.contains(renderer.id())) {
            return false;
        }
        if (owner != null && !owner.widgets().contains(renderer.id())) {
            return false;
        }
        Placement start = where == null ? Placement.at(net.fathom.optimizations.core.hud.Anchor.TOP_LEFT, 4, 4) : where;
        String name = owner == null ? renderer.id() : owner.name();
        String summary = owner == null || owner.description().isEmpty() ? renderer.placeholder() : owner.description();
        if (!registry.add(Widget.of(renderer.id(), name, summary, HudRegistry.CATEGORY_MODS,
                owner == null ? net.fathom.optimizations.core.hud.Sensitivity.SAFE : owner.sensitivity(), start))) {
            return false;
        }
        renderers.put(renderer.id(), renderer);
        return true;
    }

    /**
     * Write the layout and every feature's settings.
     *
     * <p>One call, at the moment a screen closes, rather than a write per
     * change. Returns the first failure or null, because the caller is a screen
     * and a screen that refused to close over a failed write would trap the
     * player in a menu.
     */
    public String saveAll() {
        if (profiles != null) {
            long started = System.nanoTime();
            String problem = profiles.saveLiveQuietly();
            if (net.fathom.optimizations.fabric.bench.PerfBench.ACTIVE) {
                System.out.println(String.format(java.util.Locale.ROOT,
                        "[Fathom] Bench: profile save on the render thread took %.2f ms",
                        (System.nanoTime() - started) / 1_000_000.0));
            }
            return problem;
        }
        String problem = save();
        try {
            featureStore.save(gameDirectory, features, hubKey, prefs);
        } catch (java.io.IOException failure) {
            return problem == null ? String.valueOf(failure.getMessage()) : problem;
        }
        return problem;
    }

    public HudRegistry registry() {
        return registry;
    }

    public HudLayout layout() {
        return layout;
    }

    public ClickTracker clicks() {
        return clicks;
    }

    /** Anything either config file complained about, for the log and Diagnostics. */
    public List<String> complaints() {
        List<String> out = new ArrayList<String>(layout.complaints());
        out.addAll(featureStore.complaints());
        return out;
    }

    public Placement placement(String widgetId) {
        Widget widget = registry.find(widgetId);
        Placement fallback = widget == null ? Placement.at(
                net.fathom.optimizations.core.hud.Anchor.TOP_LEFT, 0, 0) : widget.defaultPlacement();
        return layout.get(widgetId, fallback);
    }

    public void place(String widgetId, Placement placement) {
        layout.put(widgetId, placement);
    }

    /**
     * Whether a widget is switched on, asked of the module that owns it.
     *
     * <p>The editor and the hub both ask this rather than reading a flag of
     * their own, so "shown" means the same thing in both.
     */
    public boolean widgetEnabled(String widgetId) {
        return features.widgetAllowed(widgetId);
    }

    /**
     * Switch a widget on or off by switching its module.
     *
     * @return whether anything changed. False when nothing owns the widget,
     *     which is a third-party widget the caller should not be switching.
     */
    public boolean setWidgetEnabled(String widgetId, boolean on) {
        net.fathom.optimizations.core.module.FathomModule owner = features.owningWidget(widgetId);
        if (owner == null || !owner.switchable() || owner.enabled() == on) {
            return false;
        }
        owner.setEnabled(on);
        return true;
    }

    /** The module that owns a widget, for a row that wants to name it. */
    public net.fathom.optimizations.core.module.FathomModule owner(String widgetId) {
        return features.owningWidget(widgetId);
    }

    /** Hold one widget exactly where it is placed while the editor drags it; null lets go. */
    public void pin(String widgetId) {
        pinned = widgetId;
    }

    /** Put everything back where the registry says it belongs. */
    public void resetLayout() {
        String kept = layout.name();
        layout = registry.defaultLayout();
        layout.setName(kept);
    }

    /**
     * Persist the layout.
     *
     * <p>Returns the failure rather than throwing it: this is called when a screen closes,
     * and a screen that refuses to close because a file could not be written would trap
     * the player in the editor.
     */
    public String save() {
        // The live profile holds the layout now. The properties file is only
        // written if the profile runtime could not start, so edits are never lost.
        if (profiles != null) {
            return profiles.saveLiveQuietly();
        }
        try {
            layout.write(gameDirectory);
            return null;
        } catch (IOException problem) {
            return String.valueOf(problem.getMessage());
        }
    }

    // -----------------------------------------------------------------------
    // Drawing
    // -----------------------------------------------------------------------

    /**
     * Draw every enabled widget. Called from the {@code InGameHud} Mixin.
     *
     * <p>{@code editing} is true only from the HUD editor, and changes two things: hidden
     * widgets are drawn as placeholders so they can be arranged, and disabled ones are
     * skipped rather than drawn ghosted — a widget that is off should look off.
     */
    public void render(MinecraftClient client, boolean editing) {
        long probe = net.fathom.optimizations.core.instrument.Probe.start();
        // Per-frame state, advanced once, here, because this is the only place
        // that knows what a frame is. Click sampling is skipped entirely when
        // neither module that reads it is on -- a switched-off module must cost
        // nothing, and polling the mouse is the one thing here that is not free.
        if (ViewProbe.REQUESTED) ViewProbe.enter(ViewSection.FATHOM_HUD_DECIDE);
        Widgets.frame();
        WidgetProfile.frame();
        if (features.widgetAllowed("cps") || features.widgetAllowed("keystrokes")) {
            clicks.sample(Widgets.Cps.window());
        }
        TextRenderer vanilla = client.textRenderer;
        if (vanilla == null) {
            if (ViewProbe.REQUESTED) ViewProbe.exit(ViewSection.FATHOM_HUD_DECIDE);
            net.fathom.optimizations.core.instrument.Probe.stop(
                    net.fathom.optimizations.core.instrument.Probe.Section.HUD, probe);
            return;
        }
        MinecraftGuiSize viewport = MinecraftGuiSize.get(client);
        int screenWidth = viewport.width();
        int screenHeight = viewport.height();

        // Decided before anything opens a frame, because an empty HUD has to
        // cost nothing at all. Taking the GL state is cheap but it is not free,
        // and a player with every widget switched off should not pay for a
        // typeface decision about text that is never drawn.
        drawing.clear();
        int drawableCursor = 0;
        boolean debugOpen = client.options != null && client.options.debugEnabled;
        boolean menuOpen = client.currentScreen != null
                && !(client.currentScreen instanceof net.minecraft.client.gui.screen.ChatScreen);
        for (Widget widget : registry.all()) {
            HudWidget renderer = renderers.get(widget.id());
            if (renderer == null) {
                continue;
            }
            // The ownership question is the module framework's, not the HUD's,
            // and it is the part that runs whether or not anything is drawn --
            // so it is timed separately. `MODULES` is what a switched-off client
            // still costs; `HUD` is what drawing costs.
            long deciding = net.fathom.optimizations.core.instrument.Probe.start();
            boolean allowed = features.widgetAllowed(widget.id());
            net.fathom.optimizations.core.instrument.Probe.stop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MODULES, deciding);
            // One switch, and it is the module's. Switching Keystrokes off in
            // Mods hides its widget on the next frame; there is no second flag
            // in the layout that can disagree with it.
            if (!allowed) {
                continue;
            }
            if (!editing && !renderer.visible(client)) {
                continue;
            }
            if (!editing && HudStyle.hiddenBy(features.owningWidget(widget.id()), debugOpen, menuOpen)) {
                continue;
            }
            // In game the crosshair is drawn where vanilla draws its own, by
            // renderCrosshair; the editor draws it here with everything else.
            if (!editing && CROSSHAIR.equals(widget.id())) {
                continue;
            }
            Drawable drawable;
            if (drawableCursor == drawablePool.size()) {
                drawable = new Drawable();
                drawablePool.add(drawable);
            } else {
                drawable = drawablePool.get(drawableCursor);
            }
            drawableCursor++;
            drawing.add(drawable.set(
                    renderer, placement(widget.id()), features.owningWidget(widget.id()), client));
        }

        if (ViewProbe.REQUESTED) ViewProbe.exit(ViewSection.FATHOM_HUD_DECIDE);
        if (!drawing.isEmpty()) {
            HudFont minecraftFont = HudFonts.vanilla(vanilla);
            arrange(client, minecraftFont, vanilla, screenWidth, screenHeight);

            // Minecraft-font widgets stay entirely on Minecraft's renderer. In
            // the fresh/default configuration that is every textual widget, so
            // the Fathom renderer does not open a frame the player did not ask
            // for. This also lets resource packs retain ownership of the font.
            //
            // With HUD caching on, the slow-changing ones are drawn as one
            // recorded layer (see HudLayerCache) and only the realtime ones --
            // input feedback -- are drawn from Java every frame.
            boolean cached = !editing && net.fathom.optimizations.fabric.perf.PerfLayer.hudCache;
            if (ViewProbe.REQUESTED) ViewProbe.enter(ViewSection.FATHOM_HUD_LAYER);
            if (cached) {
                long signature = layerSignature(client, screenWidth, screenHeight);
                if (signature != 0L
                        && net.fathom.optimizations.fabric.perf.HudLayerCache.beginFrame(signature, System.nanoTime())) {
                    try {
                        for (int index = 0; index < drawing.size(); index++) {
                            Drawable each = drawing.get(index);
                            if (!usesFathom(each.owner) && layered(each)) {
                                drawOne(client, minecraftFont, each, false);
                            }
                        }
                    } finally {
                        net.fathom.optimizations.fabric.perf.HudLayerCache.endRecording();
                    }
                }
            }
            if (ViewProbe.REQUESTED) ViewProbe.exit(ViewSection.FATHOM_HUD_LAYER);
            if (ViewProbe.REQUESTED) ViewProbe.enter(ViewSection.FATHOM_HUD_LIVE);
            // Realtime widgets that are standing still this frame are replayed
            // rather than redrawn (HudSteadyCache); everything else is live.
            long steady = cached && net.fathom.optimizations.fabric.perf.HudSteadyCache.enabled()
                    ? steadySignature(client, minecraftFont, screenWidth, screenHeight) : 0L;
            if (steady != 0L && net.fathom.optimizations.fabric.perf.HudSteadyCache.beginFrame(steady)) {
                try {
                    for (int index = 0; index < drawing.size(); index++) {
                        Drawable each = drawing.get(index);
                        if (each.steady) {
                            drawOne(client, minecraftFont, each, false);
                        }
                    }
                } finally {
                    net.fathom.optimizations.fabric.perf.HudSteadyCache.endRecording();
                }
            }
            for (int index = 0; index < drawing.size(); index++) {
                Drawable each = drawing.get(index);
                if (!usesFathom(each.owner) && !(cached && layered(each)) && !(steady != 0L && each.steady)) {
                    drawOne(client, minecraftFont, each, editing);
                }
            }

            if (ViewProbe.REQUESTED) ViewProbe.exit(ViewSection.FATHOM_HUD_LIVE);
            boolean needsFathom = false;
            for (int index = 0; index < drawing.size(); index++) {
                if (usesFathom(drawing.get(index).owner)) {
                    needsFathom = true;
                    break;
                }
            }
            if (ViewProbe.REQUESTED) ViewProbe.enter(ViewSection.FATHOM_HUD_OWN);
            Renderer opened = null;
            try {
                if (needsFathom) {
                    opened = Gfx.beginHudOverlay(
                            client, net.fathom.optimizations.fabric.Runtime.uiScale());
                    HudFont fathom = fathomFont(client, screenWidth, vanilla, opened);
                    for (int index = 0; index < drawing.size(); index++) {
                        Drawable each = drawing.get(index);
                        if (usesFathom(each.owner)) {
                            drawOne(client, fathom, each, editing);
                        }
                    }
                }
            } finally {
                Gfx.endHudOverlay(opened);
            }
            if (ViewProbe.REQUESTED) ViewProbe.exit(ViewSection.FATHOM_HUD_OWN);
        }
        net.fathom.optimizations.core.instrument.Probe.stop(
                net.fathom.optimizations.core.instrument.Probe.Section.HUD, probe);
    }

    private static final String CROSSHAIR = "crosshair";

    /** How many widgets the last in-game pass drew. For the self-test. */
    public int drawnLastFrame() {
        return drawing.size();
    }

    /**
     * Draw the crosshair widget on its own, at the point in vanilla's HUD where
     * vanilla's crosshair goes.
     *
     * @return whether anything was drawn, so the caller knows to restore state
     */
    public boolean renderCrosshair(MinecraftClient client) {
        HudWidget renderer = renderers.get(CROSSHAIR);
        TextRenderer vanilla = client.textRenderer;
        if (renderer == null || vanilla == null || !features.widgetAllowed(CROSSHAIR) || !renderer.visible(client)) {
            return false;
        }
        MinecraftGuiSize viewport = MinecraftGuiSize.get(client);
        HudFont font = HudFonts.vanilla(vanilla);
        Placement placement = placement(CROSSHAIR);
        // The crosshair takes no part in the flow: it is where the game aims.
        locate(client, font, renderer, placement, viewport.width(), viewport.height());
        float x = locatedX;
        float y = locatedY;
        if (placement.anchor() == net.fathom.optimizations.core.hud.Anchor.CENTER) {
            int factor = viewport.scaleFactor();
            x = centredOnPixels(client.width, locatedWidth, placement.offsetX(), factor);
            y = centredOnPixels(client.height, locatedHeight, placement.offsetY(), factor);
        }
        drawOne(client, font, renderer, placement, x, y, false);
        return true;
    }

    /**
     * Where a centred crosshair's left (or top) edge goes, in GUI units, so its
     * middle is on the screen's middle to within half a screen pixel.
     *
     * <p>Laid out in whole GUI pixels, as every other widget is, a centred box
     * is off by up to half a GUI pixel -- and the GUI width itself is rounded
     * up from a fraction (1366 / 3 is 455.33, drawn as 456). Together that put
     * the crosshair up to two and a half screen pixels from where the game
     * aims; vanilla's own crosshair has the same error. This places the edge on
     * a whole screen pixel nearest the exact centre instead, and the projection
     * GUI drawing uses spans the window exactly, so one GUI unit is exactly
     * {@code factor} screen pixels.
     */
    static float centredOnPixels(int screenPixels, int guiSize, int guiOffset, int factor) {
        float edge = screenPixels / 2f - guiSize * factor / 2f + guiOffset * factor;
        return Math.round(edge) / (float) factor;
    }

    /** Whether a drawable belongs in the cached layer rather than the realtime pass. */
    private boolean layered(Drawable each) {
        return each.layered;
    }

    /**
     * Everything the cached layer's content depends on besides time: which
     * widgets are in it and where (placements are immutable, so a move is a
     * new object), the window and GUI size, the layout, and every setting.
     * Zero when the layer would be empty.
     */
    private long layerSignature(MinecraftClient client, int screenWidth, int screenHeight) {
        long hash = 0xcbf29ce484222325L;
        int members = 0;
        for (int index = 0; index < drawing.size(); index++) {
            Drawable each = drawing.get(index);
            if (usesFathom(each.owner) || !layered(each)) {
                continue;
            }
            members++;
            hash = (hash ^ System.identityHashCode(each.renderer)) * 0x100000001b3L;
            hash = (hash ^ System.identityHashCode(each.placement)) * 0x100000001b3L;
            // Where the flow put it, which the placement alone does not say:
            // a widget above it that grew moves it without its placement changing.
            hash = (hash ^ each.x) * 0x100000001b3L;
            hash = (hash ^ each.y) * 0x100000001b3L;
        }
        if (members == 0) {
            return 0L;
        }
        hash = (hash ^ screenWidth) * 0x100000001b3L;
        hash = (hash ^ screenHeight) * 0x100000001b3L;
        hash = (hash ^ client.width) * 0x100000001b3L;
        hash = (hash ^ client.height) * 0x100000001b3L;
        hash = (hash ^ System.identityHashCode(layout)) * 0x100000001b3L;
        hash = (hash ^ net.fathom.optimizations.core.module.Setting.revision()) * 0x100000001b3L;
        return hash == 0L ? 1L : hash;
    }

    /**
     * Everything the steady realtime widgets' drawing depends on, or zero when
     * none of them can vouch for their content this frame. Marks each drawable
     * that joins the steady set.
     *
     * <p>The widgets supply what only they know (their content, see
     * {@link HudWidget#contentSignature}); this adds what the HUD decides: which
     * widgets, where, how big, at what scale and opacity, on what screen, under
     * what settings. The same inputs as {@link #layerSignature}, plus content.
     */
    private long steadySignature(MinecraftClient client, HudFont font, int screenWidth, int screenHeight) {
        long hash = 0xcbf29ce484222325L;
        int members = 0;
        for (int index = 0; index < drawing.size(); index++) {
            Drawable each = drawing.get(index);
            each.steady = false;
            if (usesFathom(each.owner) || layered(each)) {
                continue;
            }
            long content = each.renderer.contentSignature(client, font);
            if (content == 0L) {
                continue;
            }
            each.steady = true;
            members++;
            hash = (hash ^ System.identityHashCode(each.renderer)) * 0x100000001b3L;
            hash = (hash ^ System.identityHashCode(each.placement)) * 0x100000001b3L;
            hash = (hash ^ content) * 0x100000001b3L;
            hash = (hash ^ each.x) * 0x100000001b3L;
            hash = (hash ^ each.y) * 0x100000001b3L;
            hash = (hash ^ each.renderer.width(client, font)) * 0x100000001b3L;
            hash = (hash ^ each.renderer.height(client, font)) * 0x100000001b3L;
        }
        if (members == 0) {
            return 0L;
        }
        hash = (hash ^ screenWidth) * 0x100000001b3L;
        hash = (hash ^ screenHeight) * 0x100000001b3L;
        hash = (hash ^ client.width) * 0x100000001b3L;
        hash = (hash ^ client.height) * 0x100000001b3L;
        hash = (hash ^ System.identityHashCode(layout)) * 0x100000001b3L;
        hash = (hash ^ net.fathom.optimizations.core.module.Setting.revision()) * 0x100000001b3L;
        return hash == 0L ? 1L : hash;
    }

    /** A widget that is going to be drawn, and where. */
    private static final class Drawable {
        HudWidget renderer;
        Placement placement;
        FathomModule owner;
        /** In this frame's steady set: replayed or recorded, not drawn live. */
        boolean steady;
        /** In this frame's cached layer: slow-changing, and not animating right now. */
        boolean layered;
        /** Where it is drawn this frame, after {@link HudFlow}: Minecraft's scaled pixels. */
        int x;
        int y;

        Drawable set(HudWidget renderer, Placement placement, FathomModule owner, MinecraftClient client) {
            this.renderer = renderer;
            this.placement = placement;
            this.owner = owner;
            this.steady = false;
            this.layered = net.fathom.optimizations.fabric.perf.HudLayerCache.cacheable(renderer.id())
                    && !renderer.animating(client);
            return this;
        }
    }

    /** Whether this module chose Fathom's HUD typeface. Fresh modules choose Minecraft. */
    private static boolean usesFathom(FathomModule module) {
        // Kept as a visual-QA override only. It is not a user preference and is
        // never persisted; normal runs use the owning module's profile setting.
        // Read once at class load: this is asked per widget, per frame.
        if (FONT_OVERRIDE != null) {
            return FONT_OVERRIDE.booleanValue();
        }
        // The benchmark isolates the typeface: with it off, every widget draws
        // through Minecraft's renderer and no Fathom frame is opened for text.
        if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowFathomFont()) {
            return false;
        }
        return HudStyle.fontIsFathom(module);
    }

    private static final Boolean FONT_OVERRIDE = fontOverride();

    private static Boolean fontOverride() {
        String override = System.getProperty("fathom.hudFont");
        if (override == null || override.trim().isEmpty()) {
            return null;
        }
        return Boolean.valueOf("fathom".equalsIgnoreCase(override.trim()));
    }

    /**
     * Geist, or Minecraft's font when Geist cannot be had.
     *
     * <p>The fallback is not theoretical. {@code Gfx.beginOverlay} returns null
     * when a frame is already open -- which is what the HUD editor does, since
     * it draws the widgets from inside its own -- and there the renderer is
     * still perfectly usable, so the editor gets Geist too. What is not usable
     * is a renderer that has never opened a frame and so has no glyph sheets.
     */
    private HudFont fathomFont(
            MinecraftClient client, int screenWidth, TextRenderer fallback, Renderer opened) {
        Renderer renderer = opened != null ? opened : Gfx.activeRenderer();
        if (renderer == null) {
            return HudFonts.vanilla(fallback);
        }
        return HudFonts.fathom(renderer, mcToLogical(client, screenWidth, renderer));
    }

    /**
     * Fathom logical units per Minecraft scaled pixel.
     *
     * <p>The one number that lets a widget measured in Minecraft's space be
     * drawn by a renderer that works in Fathom's. The HUD editor computes the
     * same thing for its grid and its handles; it is here because the HUD needs
     * it whether or not the editor is open.
     */
    private static float mcToLogical(MinecraftClient client, int screenWidth, Renderer renderer) {
        return (client.width / (float) Math.max(1, screenWidth))
                / Math.max(0.0001f, renderer.scale());
    }

    private void drawOne(MinecraftClient client, HudFont font, Drawable each, boolean editing) {
        // Told its edge again: arrange() measured every widget before any was
        // drawn, and the last one measured is not necessarily this one.
        each.renderer.align(each.placement.anchor().horizontal());
        drawOne(client, font, each.renderer, each.placement, each.x, each.y, editing);
    }

    private void drawOne(
            MinecraftClient client,
            HudFont font,
            HudWidget renderer,
            Placement placement,
            float boxX,
            float boxY,
            boolean editing) {
        long profiled = WidgetProfile.start();
        long allocation = net.fathom.optimizations.fabric.diag.ModuleSelfTest.allocationMark();
        float scale = placement.scale();

        GlStateManager.pushMatrix();
        // Scale about the widget's own top-left rather than the screen origin, so
        // a widget pinned to the bottom-right stays pinned when it is made larger.
        // Scaling about the origin moves everything except the top-left corner.
        //
        // Through GlStateManager, not raw GL11: it caches what it believes the
        // driver's state to be, and a raw call desyncs the cache. See the note
        // on `Surface` -- this exact mistake made every translucent quad in the
        // client vanish from the second frame onwards.
        GlStateManager.translate(boxX, boxY, 0f);
        GlStateManager.scale(scale, scale, 1f);
        Renderer activeUi = Gfx.activeRenderer();
        boolean sdfTransform = activeUi instanceof SdfRenderer;
        if (sdfTransform) {
            float logical = mcToLogical(client, MinecraftGuiSize.get(client).width(), activeUi);
            activeUi.pushTransform(boxX * logical, boxY * logical, scale, 0f, 0f);
        }
        GlStateManager.enableBlend();
        try {
            if (renderer.visible(client)) {
                renderer.draw(client, font, 0, 0, placement.alpha());
            } else if (editing) {
                drawPreview(font, renderer, features.owningWidget(renderer.id()), placement.alpha());
            }
        } finally {
            // Submitted under this widget's matrix, while it is still current.
            // The batch is drawn wherever the modelview happens to be at the
            // moment it flushes, so a queue that outlived its widget would draw
            // the last one's text at the next one's position.
            Gfx.flushHudOverlay();
            if (sdfTransform) {
                activeUi.popTransform();
            }
            // A widget that throws must not leave the matrix stack unbalanced: the
            // next thing to draw would be the rest of the game.
            GlStateManager.popMatrix();
            WidgetProfile.stop(renderer.id(), profiled);
            net.fathom.optimizations.fabric.diag.ModuleSelfTest.widgetAllocated(renderer.id(), allocation);
        }
    }

    /**
     * A widget with nothing to show, as the editor shows it: its preview lines
     * on its own plate, in its own colours, at about two thirds strength -- so
     * it reads as the widget, and as standing in for it.
     */
    private static void drawPreview(HudFont font, HudWidget renderer, FathomModule owner, int alpha) {
        int faint = alpha * 2 / 3;
        int width = previewWidth(font, renderer, owner);
        int height = previewHeight(font, renderer, owner);
        int pad = HudStyle.padding(owner);
        QuadBatch.begin();
        try {
            if (owner != null) {
                TextWidget.backdrop(owner, 0, 0, width, height, faint);
            } else {
                QuadBatch.rect(0, 0, width, height, (faint * 40 / 100) << 24);
            }
        } finally {
            QuadBatch.end();
        }
        int colour = (Math.max(0, Math.min(255, faint)) << 24) | (HudStyle.textColour(owner) & 0x00FFFFFF);
        boolean shadow = HudStyle.textShadow(owner);
        float line = font.lineHeight() + TextWidget.LINE_GAP;
        String[] lines = renderer.preview();
        for (int index = 0; index < lines.length; index++) {
            font.draw(lines[index], pad, pad + index * line, colour, shadow);
        }
    }

    /** The preview's own size, so the box the editor grabs is the box it draws. */
    private static int previewWidth(HudFont font, HudWidget renderer, FathomModule owner) {
        float widest = 0f;
        for (String line : renderer.preview()) {
            widest = Math.max(widest, font.width(line));
        }
        return (int) Math.ceil(widest) + HudStyle.padding(owner) * 2;
    }

    private static int previewHeight(HudFont font, HudWidget renderer, FathomModule owner) {
        int lines = Math.max(1, renderer.preview().length);
        return (int) Math.ceil(lines * (font.lineHeight() + TextWidget.LINE_GAP) - TextWidget.LINE_GAP)
                + HudStyle.padding(owner) * 2;
    }

    // -----------------------------------------------------------------------
    // Geometry, for the editor
    // -----------------------------------------------------------------------

    /**
     * One widget's rectangle on screen, scale included.
     *
     * <p>Also where the widget is told which edge it belongs to, so a
     * right-anchored readout can right-align its own text. The editor and the
     * game both come through here, so what can be clicked is what is drawn.
     *
     * <h2>Why the box is kept on screen</h2>
     *
     * <p>An anchor and an inward offset survive a resize on their own — that is
     * the point of storing them rather than a pixel position — but an offset
     * chosen on a 2560-wide window can be larger than an 854-wide one, and a
     * widget whose offset is wider than the screen would be drawn off the edge
     * with no way to reach it. So a widget that fits at all is nudged back
     * inside. The stored placement is untouched: make the window wide again and
     * the widget is where it was left.
     */
    public Snap.Box box(
            MinecraftClient client,
            HudFont font,
            HudWidget renderer,
            Placement placement,
            int screenWidth,
            int screenHeight) {
        locate(client, font, renderer, placement, screenWidth, screenHeight);
        return new Snap.Box(locatedX, locatedY, locatedWidth, locatedHeight);
    }

    private int locatedX;
    private int locatedY;
    private int locatedWidth;
    private int locatedHeight;

    /** {@link #box}, into fields: the drawing path asks once per widget per frame. */
    private void locate(
            MinecraftClient client,
            HudFont font,
            HudWidget renderer,
            Placement placement,
            int screenWidth,
            int screenHeight) {
        renderer.align(placement.anchor().horizontal());
        float scale = placement.scale();
        int width;
        int height;
        if (renderer.visible(client)) {
            width = Math.round(renderer.width(client, font) * scale);
            height = Math.round(renderer.height(client, font) * scale);
        } else {
            // A widget with nothing to say is only ever drawn by the editor, as
            // its preview, and the box has to be the preview's -- an armour
            // readout with no armour measures a few pixels wide, so its label
            // would hang off the edge of its own hit box.
            FathomModule owner = features.owningWidget(renderer.id());
            width = Math.round(previewWidth(font, renderer, owner) * scale);
            height = Math.round(previewHeight(font, renderer, owner) * scale);
        }
        int x = placement.anchor().x(screenWidth, width, placement.offsetX());
        int y = placement.anchor().y(screenHeight, height, placement.offsetY());
        if (width <= screenWidth) {
            x = Math.max(0, Math.min(x, screenWidth - width));
        }
        if (height <= screenHeight) {
            y = Math.max(0, Math.min(y, screenHeight - height));
        }
        locatedX = x;
        locatedY = y;
        locatedWidth = width;
        locatedHeight = height;
    }

    /**
     * Every enabled widget's rectangle, newest on top, for hit testing and snapping.
     *
     * <p>Laid out exactly as {@link #render} lays them out in the editor -- the
     * same measurement, the same {@link HudFlow} -- so what can be grabbed is
     * what is drawn.
     */
    public List<Positioned> boxes(MinecraftClient client, int screenWidth, int screenHeight) {
        positioned.clear();
        TextRenderer vanilla = client.textRenderer;
        if (vanilla == null) {
            return positioned;
        }
        HudFont minecraftFont = HudFonts.vanilla(vanilla);
        HudFont fathomFont = null;
        int cursor = 0;
        for (Widget widget : registry.all()) {
            HudWidget renderer = renderers.get(widget.id());
            if (renderer == null) {
                continue;
            }
            if (!features.widgetAllowed(widget.id())) {
                continue;
            }
            FathomModule owner = features.owningWidget(widget.id());
            HudFont font = minecraftFont;
            if (usesFathom(owner)) {
                if (fathomFont == null) {
                    fathomFont = measuringFont(client, screenWidth, vanilla);
                }
                font = fathomFont;
            }
            Placement placement = placement(widget.id());
            ensureFlow(cursor + 1);
            locate(client, font, renderer, placement, screenWidth, screenHeight);
            stage(cursor, widget.id(), placement);
            Positioned entry;
            if (cursor == positionedPool.size()) {
                entry = new Positioned();
                positionedPool.add(entry);
            } else {
                entry = positionedPool.get(cursor);
            }
            cursor++;
            positioned.add(entry.set(widget, placement, null));
        }
        HudFlow.resolve(cursor, flowX, flowY, flowW, flowH, flowDirection, flowFixed, flowOrder, screenHeight);
        for (int index = 0; index < cursor; index++) {
            Positioned entry = positioned.get(index);
            entry.box = new Snap.Box(flowX[index], flowY[index], flowW[index], flowH[index]);
            entry.flowed = flowY[index] != flowNaturalY[index];
            entry.naturalY = flowNaturalY[index];
        }
        return positioned;
    }

    /**
     * Where every widget in this frame's selection goes: measured once each,
     * then moved off one another by {@link HudFlow}. Before anything is drawn,
     * because a widget's place can depend on the size of one drawn after it.
     */
    private void arrange(MinecraftClient client, HudFont minecraftFont, TextRenderer vanilla,
            int screenWidth, int screenHeight) {
        int count = drawing.size();
        ensureFlow(count);
        HudFont fathomFont = null;
        for (int index = 0; index < count; index++) {
            Drawable each = drawing.get(index);
            HudFont font = minecraftFont;
            if (usesFathom(each.owner)) {
                if (fathomFont == null) {
                    fathomFont = measuringFont(client, screenWidth, vanilla);
                }
                font = fathomFont;
            }
            locate(client, font, each.renderer, each.placement, screenWidth, screenHeight);
            stage(index, each.renderer.id(), each.placement);
        }
        HudFlow.resolve(count, flowX, flowY, flowW, flowH, flowDirection, flowFixed, flowOrder, screenHeight);
        for (int index = 0; index < count; index++) {
            Drawable each = drawing.get(index);
            each.x = flowX[index];
            each.y = flowY[index];
        }
    }

    /** The last {@link #locate} into the flow's arrays, at {@code index}. */
    private void stage(int index, String widgetId, Placement placement) {
        flowX[index] = locatedX;
        flowY[index] = locatedY;
        flowNaturalY[index] = locatedY;
        flowW[index] = locatedWidth;
        flowH[index] = locatedHeight;
        flowDirection[index] = CROSSHAIR.equals(widgetId) ? 0 : HudFlow.direction(placement.anchor());
        flowFixed[index] = widgetId.equals(pinned);
    }

    private void ensureFlow(int count) {
        if (count <= flowX.length) {
            return;
        }
        int size = Math.max(count, flowX.length * 2);
        flowX = new int[size];
        flowY = new int[size];
        flowNaturalY = new int[size];
        flowW = new int[size];
        flowH = new int[size];
        flowDirection = new int[size];
        flowOrder = new int[size];
        flowFixed = new boolean[size];
    }

    /**
     * The typeface Fathom-font widgets are measured in before their frame is
     * open. The compositor measures without a frame once its faces are loaded;
     * until the first frame ever drawn, Minecraft's font stands in, which is
     * the same eight-pixel cell and so very nearly the same size.
     */
    private HudFont measuringFont(MinecraftClient client, int screenWidth, TextRenderer vanilla) {
        Renderer active = Gfx.activeRenderer();
        if (active != null) {
            return fathomFont(client, screenWidth, vanilla, active);
        }
        SdfRenderer compositor = Gfx.peekCompositor();
        if (compositor != null && compositor.canMeasure()) {
            return fathomFont(client, screenWidth, vanilla, compositor);
        }
        return HudFonts.vanilla(vanilla);
    }

    /** How many widgets are switched on, for the visual-QA log. */
    public int widgetCount() {
        int count = 0;
        for (Widget widget : registry.all()) {
            if (renderers.containsKey(widget.id()) && features.widgetAllowed(widget.id())) {
                count++;
            }
        }
        return count;
    }

    public HudWidget renderer(String widgetId) {
        return renderers.get(widgetId);
    }

    /** A widget, where it is configured to be, and where that puts it. */
    public static final class Positioned {
        public Widget widget;
        public Placement placement;
        public Snap.Box box;
        /** Moved off another widget by {@link HudFlow} rather than standing where it was put. */
        public boolean flowed;
        /** Where its placement alone puts its top edge, before the flow. */
        public int naturalY;

        Positioned() {}

        Positioned set(Widget widget, Placement placement, Snap.Box box) {
            this.widget = widget;
            this.placement = placement;
            this.box = box;
            this.flowed = false;
            return this;
        }
    }
}
