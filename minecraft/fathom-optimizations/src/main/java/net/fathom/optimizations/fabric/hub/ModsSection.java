package net.fathom.optimizations.fabric.hub;

import net.fathom.optimizations.generated.FathomTheme;

import java.util.ArrayList;
import java.util.Collections;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;

import org.lwjgl.input.Keyboard;

import net.fathom.optimizations.core.module.Category;
import net.fathom.optimizations.core.module.Compatibility;
import net.fathom.optimizations.core.module.Implementation;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.core.hud.Sensitivity;
import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.FathomComponents;
import net.fathom.optimizations.fabric.ui.FathomSettingRows;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.ui.VisualEffects;
import net.fathom.optimizations.fabric.ui.Graphite;
import net.fathom.optimizations.generated.GraphiteTokens;

/**
 * The Mods page: every feature, searchable, with its settings a click away.
 *
 * <h2>A compact browser and a dedicated configuration page</h2>
 *
 * <p>Icon, name, state, description, keybind and configuration affordance are
 * visible in one quiet row. Selecting a configurable module replaces the list
 * with a full-width detail page inside the Hub; it never stacks a drawer over a
 * browser that was already narrow.
 *
 * <p>The detail page groups related settings into shared surfaces. Individual
 * settings remain lightweight rows, so a module with many values reads like a
 * settings document rather than a wall of nested cards.
 *
 * <h2>Nothing here is written per module</h2>
 *
 * <p>The page is built from {@code Setting.Kind}: a toggle draws a switch, a
 * whole number draws a stepper, a key draws a rebinding control, and a module
 * that adds a setting gets a control for it without this file changing. That is
 * why a module can declare eight common HUD settings in one call and have all
 * eight appear, laid out the same way as everything else.
 *
 * <h2>A module that does not work does not get a switch</h2>
 *
 * <p>{@link Implementation#UNAVAILABLE} rows draw the reason where the toggle
 * would be. This is the visible half of the rule the module registry enforces:
 * a switch that changes a boolean nothing reads is worse than no switch, because
 * the only way to discover it is to trust it and be wrong.
 */
final class ModsSection implements Section {

    /** Visual-QA only: exercise a full list of lit rows without changing saved module state. */
    private static final boolean FORCE_ENABLED_QA = Boolean.getBoolean("fathom.qaAllModules");

    /** The launcher's list row ({@code Metrics.ROW}): a 32 tile and two lines. */
    private static final float MODULE_ROW = FathomTheme.Metrics.ROW;
    /**
     * Rows share one group and are divided by a hairline, as the launcher's
     * list items are -- no gap, and no card of their own.
     */
    private static final float ROW_GAP = 0f;
    /** The group's inner padding; rows are concentric with its corners. */
    private static final float LIST_PAD = Skin.S2;
    private static final float DETAIL_HEADER = 88f;
    private static final float CONFLICT_ROW = 18f;
    /** Search and the state filter: {@code Metrics.SEARCH} / {@code SEGMENTED}, both 36. */
    private static final float TOOLBAR_HEIGHT = FathomTheme.Metrics.SEARCH;
    /** Every row in the detail panel -- enabled, setting, keybind: one height. */
    private static final float DETAIL_ROW = FathomTheme.Metrics.ROW;
    private static final float TOOLBAR_GAP = 12f;
    private static final float DETAIL_WIDTH = 360f;

    private final Scroller scroller = new Scroller();
    private final Scroller detailScroll = new Scroller();
    private final Motion detail = new Motion(0f, Timing.DETAIL);
    private List<FathomModule> modules = Collections.emptyList();
    private List<Setting> openSettings = Collections.emptyList();
    private final List<Category> presentCategories = new ArrayList<Category>();
    private String[] categoryLabels = new String[0];
    private final List<FathomModule> visibleModules = new ArrayList<FathomModule>();
    private String visibleSearch;
    private Category visibleFilter;
    /** Mockup B's All / On / Off segmented filter. */
    private int stateFilter;
    private int visibleStateFilter = -1;
    private final Map<Setting.Choice, String[]> choiceOptions =
            new IdentityHashMap<Setting.Choice, String[]>();
    private final Map<Setting, Boolean> appearanceSettings =
            new IdentityHashMap<Setting, Boolean>();
    private final Map<Keybind, String> conflictCache =
            new IdentityHashMap<Keybind, String>();
    private int conflictFingerprint = Integer.MIN_VALUE;
    private final Map<FathomModule, RowMeta> rowMeta = new IdentityHashMap<FathomModule, RowMeta>();
    /** The text setting being typed into, or null. */
    private Setting.Text editing;

    /** Null when the list is showing everything. */
    private Category filter;
    /** The feature whose settings are open, or null. */
    private FathomModule open;
    /** Keeps the detail surface alive while its back transition recedes. */
    private boolean detailClosing;
    /** Start of the current category's tight row entrance. */
    private float cardsEnteredAt;
    @Override
    public String title() {
        return "Mods";
    }

    @Override
    public Icon icon() {
        return Icon.GRID;
    }

    @Override
    public boolean searchable() {
        return open == null;
    }

    @Override
    public String subtitle() {
        return "Choose the modules you want.";
    }

    @Override
    public boolean ownsHeading() {
        // The browser draws its own heading so the browser/detail transition
        // keeps one stable content rectangle in both directions.
        return true;
    }

    @Override
    public void opened(HubContext context) {
        scroller.reset();
        modules = context.features().all();
        cacheCategories(context);
        visibleSearch = null;
        visibleFilter = null;
        stateFilter = 0;
        visibleStateFilter = -1;
        open = null;
        detailClosing = false;
        detail.set(0f, context.now());
        cardsEnteredAt = context.now();
        String qaOpen = System.getProperty("fathom.qaOpenSettings", "").trim();
        if (!qaOpen.isEmpty()) {
            // Either "true" for the first configurable module, or a module id,
            // so a capture can be of one particular detail page.
            for (FathomModule feature : modules) {
                boolean configurable = feature.hasSettings() || !feature.keybinds().isEmpty();
                if (configurable && (qaOpen.equalsIgnoreCase(feature.id())
                        || "true".equalsIgnoreCase(qaOpen))) {
                    open = feature;
                    openSettings = feature.settings();
                    detail.set(1f, context.now());
                    conflictFingerprint = Integer.MIN_VALUE;
                    break;
                }
            }
        }
        context.keybindCapture().cancel();
    }

    @Override
    public void qaReplay(HubContext context) {
        cardsEnteredAt = context.now();
    }

    @Override
    public void render(HubContext context, Rect area) {
        detail.to(open == null || detailClosing ? 0f : 1f, context.now());
        float shown = detail.value(context.now());

        Rect panel = new Rect(area.right() - DETAIL_WIDTH, area.y, DETAIL_WIDTH, area.height);
        if (shown > 0.001f) {
            // The detail is an overlay, not a replacement page. Only its own
            // footprint shields the grid beneath it.
            context.controls().shield(panel);
        }
        browser(context, area);
        context.controls().clearShield();
        if (shown > 0.001f && open != null) {
            context.ui().pushAlpha(shown);
            context.ui().pushTransform((1f - shown) * GraphiteTokens.MOTION_TRAVEL, 0f);
            graphiteDetailPanel(context, panel);
            context.ui().popTransform();
            context.ui().popAlpha();
        }
        if (detailClosing && detail.settled(context.now()) && shown <= 0.001f) {
            open = null;
            openSettings = Collections.emptyList();
            detailClosing = false;
            cardsEnteredAt = context.now();
        }
    }

    private void browser(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Rect search = new Rect(area.x, area.y, 320f, TOOLBAR_HEIGHT);
        graphiteSearch(context, search);
        Rect filters = new Rect(search.right() + TOOLBAR_GAP, area.y, 222f, TOOLBAR_HEIGHT);
        graphiteFilters(context, filters);

        Rect body = Rect.of(area.x, area.y + TOOLBAR_HEIGHT + TOOLBAR_GAP, area.right(), area.bottom());
        List<FathomModule> cards = visible(context);
        Rect group = new Rect(body.x, body.y, Math.min(744f, body.width), body.height);
        Graphite.surface(ui, group, Graphite.R_PANEL, Graphite.Material.STANDARD, true);
        Rect viewport = group.inset(LIST_PAD);
        float contentHeight = cards.size() * MODULE_ROW;
        float offset = scroller.offset(context.delta(), contentHeight, viewport.height);

        ui.clip(viewport.x, viewport.y, viewport.right(), viewport.bottom());
        for (int index = 0; index < cards.size(); index++) {
            Rect card = new Rect(viewport.x,
                    viewport.y + index * MODULE_ROW - offset,
                    viewport.width, MODULE_ROW);
            if (card.bottom() < viewport.y || card.y > viewport.bottom()) {
                continue;
            }
            float arrival = Ease.sample(context.now(), cardsEnteredAt,
                    index * Timing.CARD_STAGGER, Timing.CARD_ENTER, Ease.Curve.STANDARD);
            ui.pushAlpha(arrival);
            ui.pushTransform(0f, (1f - arrival) * GraphiteTokens.MOTION_TRAVEL);
            moduleTile(context, cards.get(index), card, index == cards.size() - 1);
            ui.popTransform();
            ui.popAlpha();
        }
        ui.unclip();
        graphiteScrollbar(ui, viewport, offset, contentHeight, context.now());

        if (cards.isEmpty()) {
            empty(context, viewport);
        }
    }

    /** Mockup B's 320x36 Light search control. Typing anywhere focuses it. */
    private void graphiteSearch(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Controls.State state = context.controls().state("hub.search", area, true);
        if (state.clicked) {
            context.controls().focus("hub.search");
        }
        Graphite.surface(ui, area, Graphite.R_CONTROL, Graphite.Material.LIGHT,
                state.hover, state.press, 0f, false);
        float icon = Graphite.ICON_INLINE;
        ui.icon(Icon.SEARCH, area.x + Graphite.S3, area.centreY() - icon / 2f,
                icon, Graphite.TEXT_SECONDARY);
        String value = context.search();
        Type type = Graphite.floor(ui, Graphite.BODY);
        int ink = value.isEmpty() ? Graphite.TEXT_SECONDARY : Graphite.TEXT;
        float clearSize = 24f;
        float textRoom = area.width - Graphite.S3 * 2f - icon - Graphite.S2
                - (value.isEmpty() ? 0f : clearSize);
        ui.text(net.fathom.optimizations.core.ui.layout.Text.fit(ui,
                        value.isEmpty() ? "Search mods" : value, type, textRoom),
                area.x + Graphite.S3 + icon + Graphite.S2,
                Graphite.baseline(ui, area, type), type, ink);
        // The launcher's clear button: present only while there is something
        // to clear.
        if (!value.isEmpty()) {
            Rect clear = new Rect(area.right() - 6f - clearSize, area.centreY() - clearSize / 2f,
                    clearSize, clearSize);
            if (context.controls().iconButton(ui, "hub.search.clear", clear, Icon.CLOSE, false, true)) {
                context.setSearch("");
                context.controls().focus("hub.search");
            }
        }
        if (context.controls().focused("hub.search")) {
            ui.focusRing(area.x, area.y, area.right(), area.bottom(), Graphite.R_CONTROL, 2f, Skin.FOCUS);
        }
    }

    /** All / On / Off; selection is density, never colour. */
    private void graphiteFilters(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Graphite.surface(ui, area, Graphite.R_CONTROL, Graphite.Material.LIGHT, false);
        int enabled = 0;
        for (FathomModule module : modules) {
            if (module.enabled()) {
                enabled++;
            }
        }
        String[] labels = {
                "All " + modules.size(),
                "On " + enabled,
                "Off " + (modules.size() - enabled)
        };
        float inset = FathomTheme.Metrics.SEGMENT_INSET;
        float cellWidth = (area.width - inset * 2f) / labels.length;
        Type uiType = Graphite.floor(ui, Graphite.UI);
        for (int index = 0; index < labels.length; index++) {
            Rect cell = new Rect(area.x + inset + index * cellWidth, area.y + inset,
                    cellWidth, area.height - inset * 2f);
            Controls.State state = context.controls().state("mods.state." + index, cell, true);
            float selected = context.controls().towards("mods.state." + index + "#on",
                    stateFilter == index, GraphiteTokens.MOTION_FAST);
            Graphite.segment(ui, cell, state.hover * (1f - selected), selected);
            int ink = net.fathom.optimizations.core.ui.Colour.mix(
                    Graphite.TEXT_SECONDARY, Graphite.TEXT, Math.max(state.hover, selected));
            ui.textCentred(labels[index], cell.centreX(), Graphite.baseline(ui, cell, uiType), uiType, ink);
            if (state.clicked && stateFilter != index) {
                stateFilter = index;
                visibleSearch = null;
                visibleStateFilter = -1;
                scroller.reset();
                cardsEnteredAt = context.now();
            }
        }
    }

    /** One row in the shared glass group. The switch owns only its own hitbox. */
    private void moduleTile(HubContext context, FathomModule feature, Rect area, boolean last) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        boolean visuallyEnabled = feature.enabled() || FORCE_ENABLED_QA;

        Rect toggle = new Rect(area.right() - Graphite.S4 - Graphite.SWITCH_W,
                area.centreY() - Graphite.SWITCH_H / 2f,
                Graphite.SWITCH_W, Graphite.SWITCH_H);
        Controls.State switchState = controls.state("mod.toggle." + feature.id(), toggle,
                feature.switchable());
        if (switchState.clicked) {
            context.setFeatureEnabled(feature, !feature.enabled());
            visibleSearch = null;
            visibleStateFilter = -1;
        }

        // Keep the parent tile out from under the switch so one pointer gesture
        // cannot both toggle the module and open its detail panel.
        Rect tileHit = new Rect(area.x, area.y,
                Math.max(0f, toggle.x - Graphite.S2 - area.x), area.height);
        Controls.State tile = controls.state("mod.tile." + feature.id(), tileHit, true);
        boolean selected = open == feature && !detailClosing;
        Graphite.overlay(ui, area, Graphite.R_CONTROL,
                selected ? 0f : tile.hover, selected ? 1f : tile.press * 0.5f);

        float mark = GraphiteTokens.SIZE_MOD_ICON;
        Graphite.modMark(ui, new Rect(area.x + Graphite.S4, area.centreY() - mark / 2f, mark, mark),
                Icon.of(feature.icon()), visuallyEnabled ? 1f : 0f, selected ? 0f : tile.hover);

        float textX = area.x + Graphite.S4 + mark + Graphite.S3;
        float textRoom = Math.max(0f, toggle.x - Graphite.S3 - textX);
        Type title = Graphite.floor(ui, Graphite.UI);
        Type description = Graphite.floor(ui, Graphite.SMALL);
        float stack = Controls.stackTop(ui, area, title, description);
        ui.text(Text.fit(ui, feature.name(), title, textRoom), textX,
                Controls.stackTitle(ui, stack, title), title, Graphite.TEXT);
        ui.text(Text.fit(ui, feature.description(), description, textRoom), textX,
                Controls.stackDetail(ui, stack, title, description), description, Graphite.TEXT_SECONDARY);

        if (feature.switchable()) {
            float on = controls.towards("mod.toggle." + feature.id() + "#on",
                    visuallyEnabled, GraphiteTokens.MOTION_FAST);
            Graphite.switchControl(ui, toggle, on, true);
        }

        boolean configurable = feature.hasSettings() || !feature.keybinds().isEmpty();
        if (tile.clicked && configurable && !switchState.clicked) {
            openDetail(context, feature);
        }
        if (!last) {
            Graphite.hairline(ui, area.x + Graphite.S4, area.right() - Graphite.S4, area.bottom());
        }
    }

    /** Four-device-pixel thumb; no track and no warm legacy scrollbar. */
    private void graphiteScrollbar(Renderer ui, Rect viewport, float offset,
            float contentHeight, float now) {
        if (contentHeight <= viewport.height || contentHeight <= 0f) {
            return;
        }
        // The shared scrollbar geometry: 4 wide, 3 in from the edge, 20%, and
        // (as `Scroller` does) both ends kept out of the panel's corners.
        float endInset = Math.min(Skin.R_SMALL, viewport.height / 2f);
        float track = viewport.height - endInset * 2f;
        if (track <= 0f) {
            return;
        }
        float handle = Math.min(track, Math.max(32f, track * viewport.height / contentHeight));
        float travel = track - handle;
        float progress = offset / Math.max(1f, contentHeight - viewport.height);
        float x = viewport.right() - 4f - 3f;
        float y = viewport.y + endInset + travel * progress;
        ui.rounded(x, y, x + 4f, y + handle,
                net.fathom.optimizations.core.ui.render.RoundedGeometry.pill(4f, handle),
                net.fathom.optimizations.generated.FathomTheme.Tokens.CONTROL_SCROLLBAR);
    }

    private void empty(HubContext context, Rect body) {
        Renderer ui = context.ui();
        if (context.search().isEmpty()) {
            Graphite.emptyState(ui, body, Icon.GRID, "No mods in this view",
                    "Choose another category to see the rest.");
        } else {
            Graphite.emptyState(ui, body, Icon.SEARCH, "No matching mods",
                    "Nothing matches “" + context.search() + "”.");
        }
    }

    /**
     * The category filter.
     *
     * <p>A segmented control rather than a row of loose chips: the categories
     * are a small fixed set, exactly one is active, and a segmented control is
     * what that shape of choice looks like everywhere else.
     */
    private void categories(HubContext context, Rect area) {
        int selected = 0;
        for (int index = 0; index < presentCategories.size(); index++) {
            if (presentCategories.get(index) == filter) {
                selected = index + 1;
            }
        }

        int chosen = context.controls().segmentedNatural(
                context.ui(), "mods.filter", area, categoryLabels, selected);
        if (chosen != selected) {
            filter = chosen == 0 ? null : presentCategories.get(chosen - 1);
            scroller.reset();
            cardsEnteredAt = context.now();
        }
    }

    private void cacheCategories(HubContext context) {
        presentCategories.clear();
        for (Category category : Category.values()) {
            for (FathomModule feature : modules) {
                if (feature.category() == category) {
                    presentCategories.add(category);
                    break;
                }
            }
        }
        categoryLabels = new String[presentCategories.size() + 1];
        categoryLabels[0] = "All";
        for (int index = 0; index < presentCategories.size(); index++) {
            categoryLabels[index + 1] = presentCategories.get(index).label();
        }
    }

    /**
     * One module: a row in the list's group, not a card. The launcher's list
     * item -- a hairline above it, a faint fill under the pointer, the item's
     * mark, its title and one line, its controls on the right.
     */
    private void moduleRow(HubContext context, FathomModule feature, Rect area, float radius, boolean first) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Controls.State surface = controls.state("mod.row." + feature.id(), area, true);
        boolean visuallyEnabled = feature.enabled() || FORCE_ENABLED_QA;
        float enabled = controls.towards("mod.row." + feature.id() + "#enabled",
                visuallyEnabled, Timing.TOGGLE);
        if (surface.hover > 0.01f) {
            ui.rounded(area.x, area.y, area.right(), area.bottom(), radius,
                    Skin.scaleAlpha(Skin.FILL_HOVER, surface.hover));
        } else if (!first) {
            ui.fill(area.x + Skin.CARD_PADDING, area.y, area.right() - Skin.CARD_PADDING,
                    area.y + ui.pixel(), Skin.EDGE_RECESSED);
        }

        float tile = FathomTheme.Metrics.TILE;
        Rect iconBox = new Rect(area.x + Skin.CARD_PADDING, Skin.snap(ui, area.centreY() - tile / 2f), tile, tile);
        Graphite.modMark(ui, iconBox, Icon.of(feature.icon()), enabled, surface.hover);

        boolean configurable = feature.hasSettings() || !feature.keybinds().isEmpty();
        float right = area.right() - Skin.CARD_PADDING;
        Rect toggle = feature.switchable()
                ? new Rect(right - Controls.TOGGLE_WIDTH,
                        area.centreY() - Controls.TOGGLE_HEIGHT / 2f,
                        Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT)
                : new Rect(right, area.y, 0f, 0f);
        Rect options = configurable
                ? new Rect(toggle.x - Skin.S3 - 90f, area.centreY() - Skin.H_SM / 2f,
                        90f, Skin.H_SM)
                : new Rect(toggle.x, area.y, 0f, 0f);
        float metaRight = configurable ? options.x : toggle.x;
        float titleX = iconBox.right() + Skin.S3;
        float titleRoom = Math.max(0f, metaRight - titleX - Skin.S5);
        float stack = Controls.stackTop(ui, area, Type.ROW_TITLE, Type.SMALL);
        float titleBaseline = Controls.stackTitle(ui, stack, Type.ROW_TITLE);
        ui.text(Text.fit(ui, feature.name(), Type.ROW_TITLE, titleRoom), titleX,
                titleBaseline, Type.ROW_TITLE,
                Skin.mix(Skin.TEXT_MUTED, Skin.TEXT,
                        Math.max(enabled, surface.hover * 0.85f)));
        if (Text.overflows(ui, feature.name(), Type.ROW_TITLE, titleRoom)) {
            controls.tooltip(surface, area, feature.name());
        }

        ui.text(Text.fit(ui, feature.description(), Type.SMALL, titleRoom),
                titleX, Controls.stackDetail(ui, stack, Type.ROW_TITLE, Type.SMALL), Type.SMALL,
                Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED, surface.hover * 0.42f));

        String metadata = metadata(feature);
        ui.textRight(Text.fit(ui, metadata, Type.CAPTION,
                        Math.max(0f, metaRight - titleX - Skin.S3)),
                metaRight - Skin.S3, titleBaseline,
                Type.CAPTION, visuallyEnabled ? Skin.TEXT_MUTED : Skin.TEXT_DISABLED);
        if (configurable) {
            if (controls.recessedAction(ui, "mod.options." + feature.id(), options, "Configure", true)) {
                openDetail(context, feature);
            }
        }
        if (feature.switchable()) {
            if (controls.toggle(ui, "mod.toggle." + feature.id(), toggle, visuallyEnabled, true)) {
                context.setFeatureEnabled(feature, !feature.enabled());
            }
        } else {
            float width = Controls.badgeWidth(ui, feature.implementation().label());
            controls.badge(ui, "mod.implementation." + feature.id(), feature.implementation().label(),
                    new Rect(right - width, area.centreY() - 10f, width, 20f), Skin.WARN);
        }

        // Clicking unused card space is an Options shortcut; action controls
        // above have separate bounds and therefore retain their own behavior.
        if (configurable && surface.clicked) {
            openDetail(context, feature);
        }
    }

    /** A row's metadata line, rebuilt only when its key or its status word changes. */
    private String metadata(FathomModule feature) {
        String key = "";
        for (Keybind keybind : feature.keybinds()) {
            if (keybind.isBound()) {
                key = keybind.keyName();
                break;
            }
        }
        String badge = compactBadge(feature);
        RowMeta meta = rowMeta.get(feature);
        if (meta == null) {
            meta = new RowMeta();
            rowMeta.put(feature, meta);
        }
        if (!key.equals(meta.key) || !badge.equals(meta.badge)) {
            meta.key = key;
            meta.badge = badge;
            meta.text = feature.category().label()
                    + (key.isEmpty() ? "" : "  ·  " + key)
                    + (badge.isEmpty() ? "" : "  ·  " + badge);
        }
        return meta.text;
    }

    /** The parts a metadata line was built from, and the line. */
    private static final class RowMeta {
        String key;
        String badge;
        String text;
    }

    /**
     * The one fact worth the second line.
     *
     * <p>Ordered by what would surprise somebody most. "Only on a multiplayer
     * server" explains an empty screen; a sensitivity warning explains why it is
     * off; the description explains what it is, and is the least urgent of the
     * three precisely because the name already half says it.
     */
    private static String compactBadge(FathomModule feature) {
        if (unmet(feature)) {
            return feature.compatibility().needsServer() ? "Multiplayer" : "In game";
        }
        if (feature.sensitivity() != Sensitivity.SAFE) {
            return feature.sensitivity().label();
        }
        return "";
    }

    /**
     * Whether this module is on and cannot act where we are.
     *
     * <p>Asked of the live client, not of the declaration: "Only on a
     * multiplayer server" is worth a row while you are in singleplayer and is
     * noise while you are on a server, where the module is simply working. The
     * first version said it always, which pushed out the description to tell
     * somebody something they could see was untrue.
     */
    private static boolean unmet(FathomModule feature) {
        if (!feature.enabled() || feature.compatibility().note().isEmpty()) {
            return false;
        }
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        boolean inWorld = client != null && client.player != null;
        boolean onServer = inWorld && !client.isInSingleplayer();
        return !feature.compatibility().satisfied(inWorld, onServer);
    }

    private void openDetail(HubContext context, FathomModule feature) {
        open = feature;
        openSettings = feature.settings();
        detailClosing = false;
        detail.set(0f, context.now());
        detail.to(1f, context.now(), Timing.DETAIL);
        detailScroll.reset();
        conflictFingerprint = Integer.MIN_VALUE;
        context.keybindCapture().cancel();
        editing = null;
        context.controls().clearFocus();
        context.controls().closePopover();
    }

    // -----------------------------------------------------------------------
    // Mockup B detail overlay
    // -----------------------------------------------------------------------

    /** Dense 360dp overlay, with one shadow and rows directly inside it. */
    private void graphiteDetailPanel(HubContext context, Rect panel) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Graphite.surface(ui, panel, Graphite.R_PANEL, Graphite.Material.DENSE, true);

        Rect inner = panel.inset(Graphite.PAD);
        float tile = FathomTheme.Metrics.TILE;
        Rect icon = new Rect(inner.x, inner.y, tile, tile);
        Graphite.modMark(ui, icon, Icon.of(open.icon()), open.enabled() ? 1f : 0f, 0f);

        float compact = FathomTheme.Metrics.CONTROL_COMPACT;
        Rect close = new Rect(inner.right() - compact, inner.y, compact, compact);
        Controls.State closeState = controls.state("detail.close", close, true);
        Graphite.overlay(ui, close, Graphite.R_SEGMENT, Math.max(closeState.hover, closeState.press * 1.6f), 0f);
        float glyph = FathomTheme.Metrics.ICON_INLINE;
        ui.icon(Icon.CLOSE, close.centreX() - glyph / 2f, close.centreY() - glyph / 2f, glyph,
                net.fathom.optimizations.core.ui.Colour.mix(Graphite.TEXT_SECONDARY, Graphite.TEXT, closeState.hover));
        controls.tooltip(closeState, close, "Close");
        if (closeState.clicked) {
            closeDetail(context);
        }

        float titleX = icon.right() + Graphite.S3;
        Type heading = Graphite.floor(ui, Graphite.HEADING);
        Type data = Graphite.floor(ui, Graphite.DATA_S);
        // Title and metadata centred as one block against the tile beside them.
        float stack = Controls.stackTop(ui, icon, heading, data);
        ui.text(Text.fit(ui, open.name(), heading, close.x - titleX - Graphite.S2),
                titleX, Controls.stackTitle(ui, stack, heading), heading, Graphite.TEXT);
        String meta = open.category().label() + " · " + open.owner();
        ui.text(Text.fit(ui, meta, data, close.x - titleX - Graphite.S2),
                titleX, Controls.stackDetail(ui, stack, heading, data), data, Graphite.TEXT_SECONDARY);

        float top = icon.bottom() + Graphite.S3;
        Graphite.hairline(ui, inner.x, inner.right(), top);
        top += Graphite.S2;
        Rect viewport = Rect.of(inner.x, top, inner.right(), inner.bottom());

        float contentHeight = graphiteDetailHeight();
        float offset = detailScroll.offset(context.delta(), contentHeight, viewport.height);
        float y = viewport.y - offset;
        ui.clip(viewport.x, viewport.y, viewport.right(), viewport.bottom());

        Rect enabledRow = new Rect(viewport.x, y, viewport.width, DETAIL_ROW);
        graphiteEnabledRow(context, enabledRow);
        y += enabledRow.height;

        for (Setting setting : openSettings) {
            float height = DETAIL_ROW;
            Rect row = new Rect(viewport.x, y, viewport.width, height);
            if (row.bottom() >= viewport.y && row.y <= viewport.bottom()) {
                graphiteSettingRow(context, setting, row);
            }
            y += height;
        }
        for (Keybind keybind : open.keybinds()) {
            Rect row = new Rect(viewport.x, y, viewport.width, DETAIL_ROW);
            if (row.bottom() >= viewport.y && row.y <= viewport.bottom()) {
                graphiteKeybindRow(context, keybind, row);
            }
            y += row.height;
        }
        ui.unclip();
        graphiteDetailScrollbar(ui, viewport, offset, contentHeight);
    }

    private float graphiteDetailHeight() {
        return DETAIL_ROW * (1 + openSettings.size() + open.keybinds().size());
    }

    private void graphiteEnabledRow(HubContext context, Rect row) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Type label = Graphite.floor(ui, Graphite.UI);
        ui.text("Enabled", row.x, Graphite.baseline(ui, row, label), label, Graphite.TEXT);
        if (open.switchable()) {
            Rect toggle = new Rect(row.right() - Graphite.SWITCH_W,
                    row.centreY() - Graphite.SWITCH_H / 2f,
                    Graphite.SWITCH_W, Graphite.SWITCH_H);
            Controls.State state = controls.state("detail.enabled", toggle, true);
            if (state.clicked) {
                context.setFeatureEnabled(open, !open.enabled());
                visibleSearch = null;
                visibleStateFilter = -1;
            }
            float on = controls.towards("detail.enabled#on", open.enabled(), GraphiteTokens.MOTION_FAST);
            Graphite.switchControl(ui, toggle, on, true);
        }
        Graphite.hairline(ui, row.x, row.right(), row.bottom());
    }

    private void graphiteSettingRow(HubContext context, Setting setting, Rect row) {
        Renderer ui = context.ui();
        boolean hovered = context.controls().pointerOverVisible(row);
        Graphite.overlay(ui, row, Graphite.R_CONTROL,
                context.controls().towards("detail.row." + setting.id(), hovered,
                        GraphiteTokens.MOTION_FAST), 0f);

        Type label = Graphite.floor(ui, Graphite.UI);
        Type small = Graphite.floor(ui, Graphite.SMALL);
        boolean toggle = setting.kind() == Setting.Kind.TOGGLE;
        float controlWidth = toggle ? Graphite.SWITCH_W : 120f;
        float controlHeight = toggle ? Graphite.SWITCH_H : FathomTheme.Metrics.CONTROL_COMPACT;
        Rect control = new Rect(row.right() - controlWidth,
                Skin.snap(ui, row.centreY() - controlHeight / 2f), controlWidth, controlHeight);
        float room = Math.max(0f, control.x - row.x - Graphite.S4);
        float stack = Controls.stackTop(ui, row, label, small);
        float labelY = setting.description().isEmpty()
                ? Graphite.baseline(ui, row, label)
                : Controls.stackTitle(ui, stack, label);
        ui.text(Text.fit(ui, setting.label(), label, room), row.x, labelY, label, Graphite.TEXT);
        if (!setting.description().isEmpty()) {
            ui.text(Text.fit(ui, setting.description(), small, room), row.x,
                    Controls.stackDetail(ui, stack, label, small), small, Graphite.TEXT_SECONDARY);
        }
        graphiteSettingControl(context, setting, control);
        Graphite.hairline(ui, row.x, row.right(), row.bottom());
    }

    private void graphiteSettingControl(HubContext context, Setting setting, Rect control) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        String id = "detail.setting." + setting.id();
        Controls.State state = controls.state(id, control, true);
        switch (setting.kind()) {
            case TOGGLE: {
                Setting.Toggle toggle = (Setting.Toggle) setting;
                if (state.clicked) {
                    toggle.set(!toggle.get());
                    edited(context, setting);
                }
                float on = controls.towards(id + "#on", toggle.get(), GraphiteTokens.MOTION_FAST);
                Graphite.switchControl(ui, control, on, true);
                return;
            }
            case SLIDER: {
                Setting.Slider slider = (Setting.Slider) setting;
                if (state.pressed) {
                    double next = (context.pointer().x() - control.x) / Math.max(1f, control.width);
                    slider.setFraction(Math.max(0d, Math.min(1d, next)));
                    edited(context, setting);
                }
                float trackY = control.centreY() - 2f;
                ui.rounded(control.x, trackY, control.right(), trackY + 4f, 2f,
                        GraphiteTokens.COLOR_SLIDER_TRACK);
                float knobX = control.x + (float) slider.fraction() * control.width;
                ui.rounded(control.x, trackY, knobX, trackY + 4f, 2f, Graphite.TEXT);
                ui.rounded(knobX - 7f, control.centreY() - 7f,
                        knobX + 7f, control.centreY() + 7f, 7f, Graphite.TEXT);
                Type data = Graphite.floor(ui, Graphite.DATA_S);
                ui.textRight(slider.display(), control.right(), control.y - 2f, data, Graphite.TEXT_TERTIARY);
                return;
            }
            case CHOICE: {
                Setting.Choice choice = (Setting.Choice) setting;
                if (state.clicked) {
                    choice.advance();
                    edited(context, setting);
                }
                graphiteCompactControl(ui, control, state, choice.display());
                return;
            }
            case INTEGER: {
                Setting.Whole whole = (Setting.Whole) setting;
                if (state.clicked) {
                    whole.nudge(whole.atMaximum() ? -1 : 1);
                    edited(context, setting);
                }
                graphiteCompactControl(ui, control, state, whole.display());
                return;
            }
            case TEXT: {
                Setting.Text text = (Setting.Text) setting;
                if (state.clicked) {
                    editing = editing == text ? null : text;
                    context.keybindCapture().cancel();
                }
                graphiteCompactControl(ui, control, state, text.get());
                return;
            }
            case KEY: {
                Setting.Key key = (Setting.Key) setting;
                if (state.clicked) {
                    context.keybindCapture().toggle(key.binding());
                    editing = null;
                }
                graphiteCompactControl(ui, control, state,
                        context.keybindCapture().capturing(key.binding()) ? "Press a key…" : key.binding().keyName());
                return;
            }
            case COLOUR:
            default:
                graphiteCompactControl(ui, control, state, setting.display());
        }
    }

    private void graphiteCompactControl(Renderer ui, Rect control, Controls.State state, String value) {
        ui.rounded(control.x, control.y, control.right(), control.bottom(), Graphite.R_CONTROL,
                net.fathom.optimizations.core.ui.Colour.scaleAlpha(Graphite.STATE_HOVER,
                        0.5f + state.hover * 0.5f));
        Graphite.rim(ui, control, Graphite.R_CONTROL,
                net.fathom.optimizations.core.ui.Colour.mix(Graphite.RIM, Graphite.RIM_HOVER, state.hover));
        Type data = Graphite.floor(ui, Graphite.DATA_S);
        ui.textCentred(Text.fit(ui, value, data, control.width - Graphite.S2),
                control.centreX(), Graphite.baseline(ui, control, data), data, Graphite.TEXT);
    }

    private void graphiteKeybindRow(HubContext context, Keybind keybind, Rect row) {
        Renderer ui = context.ui();
        Type label = Graphite.floor(ui, Graphite.UI);
        ui.text(keybind.label(), row.x, Graphite.baseline(ui, row, label), label, Graphite.TEXT);
        float compact = FathomTheme.Metrics.CONTROL_COMPACT;
        Rect control = new Rect(row.right() - 120f, Skin.snap(ui, row.centreY() - compact / 2f), 120f, compact);
        Controls.State state = context.controls().state("detail.key." + keybind.id(), control, true);
        if (state.clicked) {
            context.keybindCapture().toggle(keybind);
            editing = null;
        }
        graphiteCompactControl(ui, control, state,
                context.keybindCapture().capturing(keybind) ? "Press a key…" : keybind.keyName());
        Graphite.hairline(ui, row.x, row.right(), row.bottom());
    }

    private void graphiteDetailScrollbar(Renderer ui, Rect viewport, float offset, float contentHeight) {
        graphiteScrollbar(ui, viewport, offset, contentHeight, 0f);
    }

    // -----------------------------------------------------------------------
    // The previous full-page configuration view (retained for the unrolled
    // sections, but no longer used by the proof-pass Mods target).
    // -----------------------------------------------------------------------

    private void settingsPage(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Rect head = area.top(DETAIL_HEADER);

        // The way back names where it goes. Escape does the same.
        Rect back = new Rect(head.x - Skin.S2, head.y, Controls.backWidth(ui, title()), Skin.H_MD);
        if (controls.back(ui, "detail.back", back, title())) {
            closeDetail(context);
        }

        Rect moduleIcon = new Rect(head.x, back.bottom() + Skin.S3, 40f, 40f);
        Graphite.modMark(ui, moduleIcon, Icon.of(open.icon()), open.enabled() ? 1f : 0f, 0f);

        float titleX = moduleIcon.right() + Skin.S3;
        float actionsLeft = head.right();
        if (open.switchable()) {
            Rect toggle = new Rect(head.right() - Controls.TOGGLE_WIDTH,
                    moduleIcon.centreY() - Controls.TOGGLE_HEIGHT / 2f,
                    Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT);
            if (controls.toggle(ui, "detail.enabled", toggle, open.enabled(), true)) {
                context.setFeatureEnabled(open, !open.enabled());
            }
            actionsLeft = toggle.x - Skin.S4;
        }
        ui.text(Text.fit(ui, open.name(), Type.SECTION, Math.max(0f, actionsLeft - titleX)),
                titleX, moduleIcon.y + ui.ascent(Type.SECTION), Type.SECTION, Skin.TEXT);
        String metadata = open.category().label() + "  ·  by " + open.owner()
                + "  ·  " + (open.enabled() ? "Enabled" : "Disabled");
        ui.text(Text.fit(ui, metadata, Type.CAPTION, Math.max(0f, actionsLeft - titleX)),
                titleX, moduleIcon.y + 25f + ui.ascent(Type.CAPTION), Type.CAPTION,
                open.enabled() ? Skin.TEXT_MUTED : Skin.TEXT_DISABLED);

        float footerHeight = Skin.H_MD + Skin.S4;
        Rect viewport = Rect.of(area.x, head.bottom() + Skin.S4,
                area.right(), area.bottom() - footerHeight);
        refreshConflictCache(context);
        float contentHeight = detailContentHeight(context);
        float offset = detailScroll.offset(context.delta(), contentHeight, viewport.height);

        ui.clip(viewport.x, viewport.y, viewport.right(), viewport.bottom());
        float y = viewport.y - offset;
        y = detailIntro(context, viewport, y);
        y = settingsGroup(context, viewport, y, false,
                "Behaviour", "How this module works.");
        y = settingsGroup(context, viewport, y, true,
                "Appearance", "Typography, colour and surface treatment.");
        y = keybindGroup(context, viewport, y);
        VisualEffects.scrollEdgeFade(ui, viewport, offset > 0.5f,
                offset + viewport.height < contentHeight - 0.5f);
        ui.unclip();
        detailScroll.rail(ui, viewport, context.pointer(), context.now());

        boolean hudEditable = hasHudWidget(context, open);
        Rect footer = new Rect(area.x, area.bottom() - Skin.H_MD, area.width, Skin.H_MD);
        if (hudEditable) {
            Rect button = new Rect(footer.x, footer.y, 132f, footer.height);
            if (controls.secondary(ui, "detail.hud", button, "Edit position", true)) {
                context.editHud();
            }
        }
        if (open.changed()) {
            Rect button = footer.vcentreRight(0f, 116f, footer.height);
            if (controls.danger(ui, "detail.reset", button, "Reset defaults", true)) {
                open.resetToDefaults();
                edited(context, null);
            }
        }
    }

    private void closeDetail(HubContext context) {
        if (open == null || detailClosing) {
            return;
        }
        detailClosing = true;
        detail.to(0f, context.now(), Timing.DETAIL);
        context.keybindCapture().cancel();
        context.controls().closePopover();
        context.controls().clearFocus();
        editing = null;
    }

    private float detailContentHeight(HubContext context) {
        float height = introHeight();
        ModulePreview preview = ModulePreview.of(open);
        if (preview != null) {
            height += Skin.S4 + preview.height();
        }
        float behaviour = settingsGroupHeight(context, false);
        float appearance = settingsGroupHeight(context, true);
        float keys = keybindGroupHeight(context);
        if (behaviour > 0f) {
            height += Skin.SECTION_GAP + behaviour;
        }
        if (appearance > 0f) {
            height += Skin.SECTION_GAP + appearance;
        }
        if (keys > 0f) {
            height += Skin.SECTION_GAP + keys;
        }
        return height + Skin.S4;
    }

    private float introHeight() {
        return 48f;
    }

    /**
     * What the module is, as the page's own type -- not a panel. A box around
     * one sentence was one more container between the reader and the settings.
     */
    private float detailIntro(HubContext context, Rect viewport, float y) {
        Rect intro = new Rect(viewport.x, y, viewport.width - Skin.S3, introHeight());
        Rect text = intro.inset(Skin.S1, 0f);
        context.ui().text(Text.fit(context.ui(), open.description(), Type.BODY, text.width),
                text.x, text.y + context.ui().ascent(Type.BODY), Type.BODY, Skin.TEXT_MUTED);
        String note = compactBadge(open);
        if (!note.isEmpty()) {
            context.ui().text(note, text.x, text.y + 27f + context.ui().ascent(Type.CAPTION),
                    Type.CAPTION, unmet(open) ? Skin.WARN : Skin.TEXT_FAINT);
        }
        y = intro.bottom();
        ModulePreview preview = ModulePreview.of(open);
        if (preview != null) {
            y += Skin.S4;
            Rect previewArea = new Rect(viewport.x, y, viewport.width - Skin.S3, preview.height());
            preview.draw(context.ui(), open, previewArea);
            y = previewArea.bottom();
        }
        return y;
    }

    private float settingsGroupHeight(HubContext context, boolean appearance) {
        int count = 0;
        float rows = 0f;
        for (Setting setting : openSettings) {
            if (isAppearance(setting) != appearance) {
                continue;
            }
            count++;
            rows += FathomSettingRows.height(setting.description());
            if (setting instanceof Setting.Key
                    && !conflict(context, ((Setting.Key) setting).binding()).isEmpty()) {
                rows += CONFLICT_ROW;
            }
        }
        return count == 0 ? 0f : FathomSettingRows.SECTION_HEADING + rows;
    }

    private float settingsGroup(
            HubContext context, Rect viewport, float y, boolean appearance,
            String title, String description) {
        int count = 0;
        for (Setting setting : openSettings) {
            if (isAppearance(setting) == appearance) {
                count++;
            }
        }
        if (count == 0) {
            return y;
        }
        y += Skin.SECTION_GAP;
        Rect heading = new Rect(viewport.x, y, viewport.width - Skin.S3,
                FathomSettingRows.SECTION_HEADING);
        FathomSettingRows.heading(context.ui(), heading, title, description);
        y = heading.bottom();

        float rowsHeight = settingsGroupHeight(context, appearance) - FathomSettingRows.SECTION_HEADING;
        Rect surface = new Rect(viewport.x, y, viewport.width - Skin.S3, rowsHeight);
        FathomSettingRows.surface(context.ui(), surface);
        int drawn = 0;
        for (Setting setting : openSettings) {
            if (isAppearance(setting) != appearance) {
                continue;
            }
            drawn++;
            float rowHeight = FathomSettingRows.height(setting.description());
            Rect row = new Rect(surface.x, y, surface.width, rowHeight);
            if (row.bottom() >= viewport.y && row.y <= viewport.bottom()) {
                settingRow(context, setting, row, drawn == count);
            }
            y += rowHeight;
            if (setting instanceof Setting.Key) {
                String clash = conflict(context, ((Setting.Key) setting).binding());
                if (!clash.isEmpty()) {
                    context.ui().text(Text.fit(context.ui(), clash, Type.CAPTION,
                                    surface.width - Skin.CARD_PADDING * 2f),
                            surface.x + Skin.CARD_PADDING,
                            y + context.ui().ascent(Type.CAPTION), Type.CAPTION, Skin.WARN);
                    y += CONFLICT_ROW;
                }
            }
        }
        return y;
    }

    private float keybindGroupHeight(HubContext context) {
        if (open.keybinds().isEmpty()) {
            return 0f;
        }
        float rows = 0f;
        for (Keybind keybind : open.keybinds()) {
            rows += FathomSettingRows.COMPACT_ROW;
            if (!conflict(context, keybind).isEmpty()) {
                rows += CONFLICT_ROW;
            }
        }
        return FathomSettingRows.SECTION_HEADING + rows;
    }

    private float keybindGroup(HubContext context, Rect viewport, float y) {
        if (open.keybinds().isEmpty()) {
            return y;
        }
        y += Skin.SECTION_GAP;
        Rect heading = new Rect(viewport.x, y, viewport.width - Skin.S3,
                FathomSettingRows.SECTION_HEADING);
        FathomSettingRows.heading(context.ui(), heading, "Keybinds",
                "Click a key, then press its replacement. Esc keeps it; Backspace clears it.");
        y = heading.bottom();
        float rowsHeight = keybindGroupHeight(context) - FathomSettingRows.SECTION_HEADING;
        Rect surface = new Rect(viewport.x, y, viewport.width - Skin.S3, rowsHeight);
        FathomSettingRows.surface(context.ui(), surface);
        for (int index = 0; index < open.keybinds().size(); index++) {
            Keybind keybind = open.keybinds().get(index);
            Rect row = new Rect(surface.x, y, surface.width, FathomSettingRows.COMPACT_ROW);
            if (row.bottom() >= viewport.y && row.y <= viewport.bottom()) {
                keybindRow(context, keybind, row, index == open.keybinds().size() - 1);
            }
            y += row.height;
            String clash = conflict(context, keybind);
            if (!clash.isEmpty()) {
                context.ui().text(Text.fit(context.ui(), clash, Type.CAPTION,
                                surface.width - Skin.CARD_PADDING * 2f),
                        surface.x + Skin.CARD_PADDING,
                        y + context.ui().ascent(Type.CAPTION), Type.CAPTION, Skin.WARN);
                y += CONFLICT_ROW;
            }
        }
        return y;
    }

    private boolean isAppearance(Setting setting) {
        Boolean cached = appearanceSettings.get(setting);
        if (cached != null) {
            return cached.booleanValue();
        }
        String id = setting.id().toLowerCase(java.util.Locale.ROOT);
        boolean appearance = setting.kind() == Setting.Kind.COLOUR
                || id.contains("font") || id.contains("colour") || id.contains("color")
                || id.contains("opacity") || id.contains("background") || id.contains("border")
                || id.contains("padding") || id.contains("radius") || id.contains("shadow")
                || id.contains("spacing") || id.contains("style");
        appearanceSettings.put(setting, Boolean.valueOf(appearance));
        return appearance;
    }

    private void settingRow(HubContext context, Setting setting, Rect area, boolean last) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        boolean hovered = context.controls().pointerOverVisible(area);
        float controlWidth = controlWidth(setting, area.width);
        Rect control = FathomSettingRows.row(ui, controls,
                "setting.row." + open.id() + "." + setting.id(),
                area, setting.label(), setting.description(), controlWidth,
                controlHeight(setting), true, hovered, last);
        String id = "set." + open.id() + "." + setting.id();
        switch (setting.kind()) {
            case TOGGLE: {
                Setting.Toggle toggle = (Setting.Toggle) setting;
                if (controls.toggle(ui, id, control, toggle.get(), true)) {
                    toggle.set(!toggle.get());
                    edited(context, setting);
                }
                break;
            }
            case SLIDER: {
                Setting.Slider slider = (Setting.Slider) setting;
                double next = controls.slider(ui, id, control, slider.fraction(), true);
                if (Math.abs(next - slider.fraction()) > 0.0001d) {
                    slider.setFraction(next);
                    edited(context, setting);
                }
                ui.textRight(slider.display(), control.x - Skin.S3, Controls.baseline(ui, area, Type.CAPTION),
                        Type.CAPTION, Skin.TEXT_FAINT);
                break;
            }
            case CHOICE: {
                Setting.Choice choice = (Setting.Choice) setting;
                String[] options = choiceOptions.get(choice);
                if (options == null) {
                    options = choice.options().toArray(new String[choice.options().size()]);
                    choiceOptions.put(choice, options);
                }
                int chosen = controls.dropdown(ui, id, control, options, choice.index());
                if (chosen != choice.index() && chosen >= 0 && chosen < options.length) {
                    choice.set(options[chosen]);
                    edited(context, setting);
                }
                break;
            }
            case INTEGER: {
                Setting.Whole whole = (Setting.Whole) setting;
                int moved = controls.stepper(ui, id, control, whole.display(),
                        whole.atMinimum(), whole.atMaximum());
                if (moved != 0) {
                    whole.nudge(moved);
                    edited(context, setting);
                }
                break;
            }
            case TEXT: {
                Setting.Text text = (Setting.Text) setting;
                if (controls.textField(ui, id, control, text.get(), setting == editing)) {
                    editing = setting == editing ? null : text;
                    context.keybindCapture().cancel();
                }
                break;
            }
            case KEY: {
                // A key that belongs to a value rather than to the module. Same
                // control as a module-wide binding, because it is the same
                // question and a second design for it would be a second thing to
                // learn.
                Setting.Key key = (Setting.Key) setting;
                boolean waiting = context.keybindCapture().capturing(key.binding());
                if (controls.keybind(ui, id, control, key.binding().keyName(), waiting)) {
                    context.keybindCapture().toggle(key.binding());
                    editing = null;
                }
                break;
            }
            case COLOUR:
            default: {
                Setting.Colour colour = (Setting.Colour) setting;
                int next = controls.colour(ui, id, control, colour.rgb());
                if (next != colour.rgb()) {
                    colour.set(next);
                    edited(context, setting);
                }
                break;
            }
        }
    }

    /**
     * Record an edit made here.
     *
     * <p>Two things follow from a value changing on this page: the profile is
     * dirty, and the module may want to keep its other settings consistent — a
     * crosshair preset writes the shape it names, and editing that shape turns
     * the preset row to Custom. The module is told through its own declared
     * listener, so this file needs no case for any particular module.
     */
    private void edited(HubContext context, Setting setting) {
        open.settingChanged(setting);
        context.markDirty();
    }

    private static float controlWidth(Setting setting, float rowWidth) {
        switch (setting.kind()) {
            case TOGGLE:
                return Controls.TOGGLE_WIDTH;
            case SLIDER:
                return Math.min(150f, rowWidth * 0.45f);
            case CHOICE:
                return Math.min(150f, rowWidth * 0.5f);
            case INTEGER:
                return Math.min(Controls.STEPPER_WIDTH, rowWidth * 0.5f);
            case TEXT:
                return Math.min(180f, rowWidth * 0.55f);
            case KEY:
                return 110f;
            case COLOUR:
            default:
                return Controls.SWATCH;
        }
    }

    private static float controlHeight(Setting setting) {
        switch (setting.kind()) {
            case TOGGLE:
                return Controls.TOGGLE_HEIGHT;
            case SLIDER:
                return Controls.SLIDER_HEIGHT;
            case CHOICE:
            case TEXT:
            case KEY:
                return Skin.H_SM;
            case INTEGER:
                return Controls.STEPPER_HEIGHT;
            case COLOUR:
            default:
                return Controls.SWATCH;
        }
    }

    private static boolean hasHudWidget(HubContext context, FathomModule module) {
        for (net.fathom.optimizations.core.hud.Widget widget : context.hud().registry().all()) {
            if (context.hud().owner(widget.id()) == module) {
                return true;
            }
        }
        return false;
    }

    // -----------------------------------------------------------------------
    // Key conflicts
    // -----------------------------------------------------------------------

    /**
     * What else uses this key, as a sentence, or empty when nothing does.
     *
     * <p>Shown and not enforced. Two modules on one key is usually a mistake and
     * occasionally exactly what somebody meant, and a client that refused the
     * binding would be wrong in the second case with no way round it. Saying
     * nothing would be wrong in the first case, which is the common one.
     */
    private String conflict(HubContext context, Keybind keybind) {
        String cached = conflictCache.get(keybind);
        if (cached != null) {
            return cached;
        }
        String calculated = calculateConflict(context, keybind);
        conflictCache.put(keybind, calculated);
        return calculated;
    }

    private void refreshConflictCache(HubContext context) {
        int fingerprint = bindingFingerprint(context);
        if (fingerprint != conflictFingerprint) {
            conflictFingerprint = fingerprint;
            conflictCache.clear();
        }
    }

    private int bindingFingerprint(HubContext context) {
        int value = 17;
        for (FathomModule module : modules) {
            for (Keybind binding : module.keybinds()) {
                value = value * 31 + binding.key();
            }
        }
        for (Setting setting : openSettings) {
            if (setting instanceof Setting.Key) {
                value = value * 31 + ((Setting.Key) setting).binding().key();
            }
        }
        value = value * 31 + context.hud().hubKey().key();
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client != null && client.options != null && client.options.allKeys != null) {
            for (net.minecraft.client.option.KeyBinding vanilla : client.options.allKeys) {
                value = value * 31 + (vanilla == null ? 0 : vanilla.getCode());
            }
        }
        return value;
    }

    private String calculateConflict(HubContext context, Keybind keybind) {
        if (!keybind.isBound()) {
            return "";
        }
        List<String> others = new ArrayList<String>();
        for (FathomModule module : modules) {
            for (Keybind other : module.keybinds()) {
                if (other != keybind && other.key() == keybind.key() && !others.contains(module.name())) {
                    others.add(module.name());
                }
            }
        }
        if (context.hud().hubKey() != keybind && context.hud().hubKey().key() == keybind.key()) {
            others.add("Open Fathom Hub");
        }
        // Minecraft's own bindings count. Fathom consumes nothing, so a shared
        // key fires both actions -- which is worth knowing before wondering why
        // zooming also drops an item.
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client != null && client.options != null && client.options.allKeys != null) {
            for (net.minecraft.client.option.KeyBinding vanilla : client.options.allKeys) {
                if (vanilla == null || vanilla.getCode() != keybind.key()) {
                    continue;
                }
                String name = net.minecraft.client.resource.language.I18n.translate(
                        vanilla.getTranslationKey());
                if (!others.contains(name)) {
                    others.add(name);
                }
            }
        }
        if (others.isEmpty()) {
            return "";
        }
        StringBuilder out = new StringBuilder("This key is also used by ");
        for (int index = 0; index < others.size(); index++) {
            if (index > 0) {
                out.append(index == others.size() - 1 ? " and " : ", ");
            }
            out.append(others.get(index));
        }
        return out.append('.').toString();
    }

    private void keybindRow(HubContext context, Keybind keybind, Rect area, boolean last) {
        Renderer ui = context.ui();
        boolean hovered = context.controls().pointerOverVisible(area);
        boolean waiting = context.keybindCapture().capturing(keybind);
        Rect control = FathomSettingRows.row(ui, context.controls(),
                "key.row." + open.id() + "." + keybind.id(), area,
                keybind.label(), waiting ? "Press a key; Escape cancels and Backspace clears." : "",
                140f, Skin.H_SM, true, hovered || waiting, last);
        if (context.controls().keybind(ui, "key." + keybind.id(), control, keybind.keyName(), waiting)) {
            context.keybindCapture().toggle(keybind);
        }
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    @Override
    public void scroll(int notches) {
        if (open != null) {
            detailScroll.wheel(notches);
        } else {
            scroller.wheel(notches);
        }
    }

    /**
     * Text entry remains page-owned. Key capture is screen-owned so the event
     * cannot also reach the global Hub hotkey or a different setting kind.
     */
    @Override
    public boolean key(HubContext context, char character, int keyCode) {
        if (editing != null) {
            return type(context, character, keyCode);
        }
        if (open != null && keyCode == Keyboard.KEY_ESCAPE) {
            closeDetail(context);
            return true;
        }
        return false;
    }

    /** Typing into a text setting. Escape leaves it; the value is already set. */
    private boolean type(HubContext context, char character, int keyCode) {
        Setting.Text field = editing;
        if (keyCode == Keyboard.KEY_ESCAPE || keyCode == Keyboard.KEY_RETURN) {
            editing = null;
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            String value = field.get();
            if (!value.isEmpty()) {
                field.set(value.substring(0, value.length() - 1));
                edited(context, field);
            }
            return true;
        }
        // The same filter the search box uses: printable characters only, so a
        // function key does not leave a box glyph in a HUD widget.
        if (character >= ' ' && character != 127) {
            field.set(field.get() + character);
            edited(context, field);
        }
        return true;
    }

    private List<FathomModule> visible(HubContext context) {
        String search = context.search();
        if (search.equals(visibleSearch) && stateFilter == visibleStateFilter) {
            return visibleModules;
        }
        visibleModules.clear();
        for (FathomModule feature : context.features().matching(context.search())) {
            if (stateFilter == 1 && !feature.enabled()) continue;
            if (stateFilter == 2 && feature.enabled()) continue;
            visibleModules.add(feature);
        }
        visibleSearch = search;
        visibleStateFilter = stateFilter;
        return visibleModules;
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : (value > high ? high : value);
    }
}
