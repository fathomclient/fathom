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

import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.module.ModuleStore;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.hud.HudLayout;
import net.fathom.optimizations.core.hud.HudRegistry;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.hud.Snap;
import net.fathom.optimizations.core.hud.Widget;
import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.module.SessionName;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.GlRenderer;
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
public final class FathomHud {

    private final HudRegistry registry = HudRegistry.standard();
    private final ClickTracker clicks = new ClickTracker();
    private final Map<String, HudWidget> renderers = new LinkedHashMap<String, HudWidget>();
    /** Reused selection and entries: the HUD is an every-game-frame hot path. */
    private final List<Drawable> drawing = new ArrayList<Drawable>();
    private final List<Drawable> drawablePool = new ArrayList<Drawable>();
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
    private final Keybind hubKey = new Keybind("hub", "Open Fathom Hub", 54);

    private HudLayout layout;
    private final ClientPrefs prefs = new ClientPrefs();

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
                net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment());
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
        if (renderer == null || renderers.containsKey(renderer.id())) {
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
            return profiles.saveLiveQuietly();
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
        Widgets.frame();
        if (features.widgetAllowed("cps") || features.widgetAllowed("keystrokes")) {
            clicks.sample(Widgets.Cps.window());
        }
        TextRenderer vanilla = client.textRenderer;
        if (vanilla == null) {
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
            Drawable drawable;
            if (drawableCursor == drawablePool.size()) {
                drawable = new Drawable();
                drawablePool.add(drawable);
            } else {
                drawable = drawablePool.get(drawableCursor);
            }
            drawableCursor++;
            drawing.add(drawable.set(
                    renderer, placement(widget.id()), features.owningWidget(widget.id())));
        }

        if (!drawing.isEmpty()) {
            HudFont minecraftFont = HudFonts.vanilla(vanilla);

            // Minecraft-font widgets stay entirely on Minecraft's renderer. In
            // the fresh/default configuration that is every textual widget, so
            // the Fathom renderer does not open a frame the player did not ask
            // for. This also lets resource packs retain ownership of the font.
            for (int index = 0; index < drawing.size(); index++) {
                Drawable each = drawing.get(index);
                if (!usesFathom(each.owner)) {
                    drawOne(client, minecraftFont, each.renderer, each.placement,
                            screenWidth, screenHeight, editing);
                }
            }

            boolean needsFathom = false;
            for (int index = 0; index < drawing.size(); index++) {
                if (usesFathom(drawing.get(index).owner)) {
                    needsFathom = true;
                    break;
                }
            }
            GlRenderer opened = null;
            try {
                if (needsFathom) {
                    opened = Gfx.beginOverlay(
                            client, net.fathom.optimizations.fabric.Runtime.uiScale());
                    HudFont fathom = fathomFont(client, screenWidth, vanilla, opened);
                    for (int index = 0; index < drawing.size(); index++) {
                        Drawable each = drawing.get(index);
                        if (usesFathom(each.owner)) {
                            drawOne(client, fathom, each.renderer, each.placement,
                                    screenWidth, screenHeight, editing);
                        }
                    }
                }
            } finally {
                Gfx.endOverlay(opened);
            }
        }
        net.fathom.optimizations.core.instrument.Probe.stop(
                net.fathom.optimizations.core.instrument.Probe.Section.HUD, probe);
    }

    /** A widget that is going to be drawn, and where. */
    private static final class Drawable {
        HudWidget renderer;
        Placement placement;
        FathomModule owner;

        Drawable set(HudWidget renderer, Placement placement, FathomModule owner) {
            this.renderer = renderer;
            this.placement = placement;
            this.owner = owner;
            return this;
        }
    }

    /** Whether this module chose Fathom's HUD typeface. Fresh modules choose Minecraft. */
    private static boolean usesFathom(FathomModule module) {
        // Kept as a visual-QA override only. It is not a user preference and is
        // never persisted; normal runs use the owning module's profile setting.
        String override = System.getProperty("fathom.hudFont");
        if (override != null && !override.trim().isEmpty()) {
            return "fathom".equalsIgnoreCase(override.trim());
        }
        return HudStyle.fontIsFathom(module);
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
            MinecraftClient client, int screenWidth, TextRenderer fallback, GlRenderer opened) {
        GlRenderer renderer = opened != null ? opened : Gfx.peek();
        if (renderer == null || !renderer.inFrame()) {
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
    private static float mcToLogical(MinecraftClient client, int screenWidth, GlRenderer renderer) {
        return (client.width / (float) Math.max(1, screenWidth))
                / Math.max(0.0001f, renderer.scale());
    }

    private void drawOne(
            MinecraftClient client,
            HudFont font,
            HudWidget renderer,
            Placement placement,
            int screenWidth,
            int screenHeight,
            boolean editing) {
        Snap.Box box = box(client, font, renderer, placement, screenWidth, screenHeight);
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
        GlStateManager.translate(box.x, box.y, 0f);
        GlStateManager.scale(scale, scale, 1f);
        GlStateManager.enableBlend();
        try {
            if (renderer.visible(client)) {
                renderer.draw(client, font, 0, 0, placement.alpha());
            } else if (editing) {
                drawPlaceholder(font, renderer, placement.alpha());
            }
        } finally {
            // Submitted under this widget's matrix, while it is still current.
            // The batch is drawn wherever the modelview happens to be at the
            // moment it flushes, so a queue that outlived its widget would draw
            // the last one's text at the next one's position.
            GlRenderer active = Gfx.peek();
            if (active != null && active.inFrame()) {
                active.flush();
            }
            // A widget that throws must not leave the matrix stack unbalanced: the
            // next thing to draw would be the rest of the game.
            GlStateManager.popMatrix();
        }
    }

    private static void drawPlaceholder(HudFont font, HudWidget renderer, int alpha) {
        String label = renderer.placeholder();
        int width = (int) Math.ceil(font.width(label)) + 4;
        int height = (int) Math.ceil(font.lineHeight()) + 4;
        net.minecraft.client.gui.DrawableHelper.fill(0, 0, width, height, (alpha * 30 / 100) << 24);
        font.draw(label, 2f, 2f, (alpha << 24) | 0x00888888, true);
    }

    // -----------------------------------------------------------------------
    // Geometry, for the editor
    // -----------------------------------------------------------------------

    /** One widget's rectangle on screen, scale included. */
    public Snap.Box box(
            MinecraftClient client,
            HudFont font,
            HudWidget renderer,
            Placement placement,
            int screenWidth,
            int screenHeight) {
        float scale = placement.scale();
        int width = Math.round(renderer.width(client, font) * scale);
        int height = Math.round(renderer.height(client, font) * scale);
        int x = placement.anchor().x(screenWidth, width, placement.offsetX());
        int y = placement.anchor().y(screenHeight, height, placement.offsetY());
        return new Snap.Box(x, y, width, height);
    }

    /** Every enabled widget's rectangle, newest on top, for hit testing and snapping. */
    public List<Positioned> boxes(MinecraftClient client, int screenWidth, int screenHeight) {
        List<Positioned> out = new ArrayList<Positioned>();
        TextRenderer vanilla = client.textRenderer;
        if (vanilla == null) {
            return out;
        }
        HudFont minecraftFont = HudFonts.vanilla(vanilla);
        HudFont fathomFont = null;
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
                    fathomFont = fathomFont(client, screenWidth, vanilla, null);
                }
                font = fathomFont;
            }
            Placement placement = placement(widget.id());
            out.add(
                    new Positioned(
                            widget,
                            placement,
                            box(client, font, renderer, placement, screenWidth, screenHeight)));
        }
        return out;
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
        public final Widget widget;
        public final Placement placement;
        public final Snap.Box box;

        Positioned(Widget widget, Placement placement, Snap.Box box) {
            this.widget = widget;
            this.placement = placement;
            this.box = box;
        }
    }
}
