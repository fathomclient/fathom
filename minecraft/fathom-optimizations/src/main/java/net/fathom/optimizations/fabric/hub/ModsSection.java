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
import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.hud.Sensitivity;
import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.layout.Box;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.TextLeaf;
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
    private static final float CONFLICT_ROW = 18f;
    /** Search and the state filter: {@code Metrics.SEARCH} / {@code SEGMENTED}, both 36. */
    private static final float TOOLBAR_HEIGHT = FathomTheme.Metrics.SEARCH;
    private static final float TOOLBAR_GAP = 12f;
    /** The tab row: compact controls in a light track, as the state filter is. */
    private static final float TAB_HEIGHT = FathomTheme.Metrics.CONTROL_COMPACT;
    private static final float TAB_GAP = FathomTheme.Metrics.SPACE_3;
    private static final float TAB_MAX = 188f;
    private static final float TAB_MIN = 104f;
    /** The close control inside a tab: a 20dp target around a 12dp glyph. */
    private static final float TAB_CLOSE = 20f;
    private static final String ALL_MODS = "All mods";
    /** The mod page's header: the 32 tile and two lines, like a list row. */
    private static final float PAGE_HEADER = FathomTheme.Metrics.ROW;

    private final Scroller scroller = new Scroller();
    private final Scroller detailScroll = new Scroller();
    /** The open mod pages, left to right, as tabs. */
    private final List<FathomModule> tabs = new ArrayList<FathomModule>();
    /** Fades a page in when the active tab changes. */
    private final Motion pageIn = new Motion(1f, Timing.PANEL);
    private List<FathomModule> modules = Collections.emptyList();
    private List<Setting> openSettings = Collections.emptyList();
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

    /** A colour whose hex value is being typed, and what has been typed so far. */
    private Setting.Colour editingColour;
    private String hexDraft = "";
    /**
     * The field being typed into, as last drawn. A press anywhere else ends the
     * entry, as leaving a text box does everywhere else; before, typing carried
     * on into a field the pointer had long since left.
     */
    private Rect editingField;

    /** Where each open page was scrolled to, so switching tabs comes back to the same place. */
    private final Map<FathomModule, Float> pageScroll = new IdentityHashMap<FathomModule, Float>();
    /** And the list's, for coming back to it from a page. */
    private float listScroll;

    @Override
    public boolean typing() {
        return editing != null || editingColour != null;
    }

    /** The active tab's module, or null when the "All mods" tab is active. */
    private FathomModule open;
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
        // Coming back to Mods keeps the list where it was and the filter that
        // was chosen; only what is shown is recomputed.
        modules = context.features().all();
        visibleSearch = null;
        visibleFilter = null;
        visibleStateFilter = -1;
        // Open tabs survive leaving the section and coming back, as a browser's
        // do; only modules that no longer exist are dropped.
        tabs.retainAll(modules);
        if (open != null && !tabs.contains(open)) {
            open = null;
        }
        if (open != null) {
            openSettings = open.settings();
        }
        pageIn.set(1f, context.now());
        cardsEnteredAt = context.now();
        String qaOpen = System.getProperty("fathom.qaOpenSettings", "").trim();
        if (!qaOpen.isEmpty()) {
            // "true" for the first configurable module, or module ids separated
            // by commas -- each opens a tab and the last is shown -- so a
            // capture can be of one particular page with its tab row.
            for (String wanted : qaOpen.split(",")) {
                for (FathomModule feature : modules) {
                    boolean configurable = feature.hasSettings() || !feature.keybinds().isEmpty();
                    if (configurable && (wanted.trim().equalsIgnoreCase(feature.id())
                            || "true".equalsIgnoreCase(wanted.trim()))) {
                        openTab(context, feature);
                        break;
                    }
                }
            }
            pageIn.set(1f, context.now());
        }
        context.keybindCapture().cancel();
    }

    String qaOpenModule() {
        return open == null ? null : open.id();
    }

    float qaScroll() {
        return open == null ? scroller.position() : detailScroll.position();
    }

    void qaTab(HubContext context, String moduleId) {
        if (moduleId == null) {
            activate(context, null);
            return;
        }
        for (FathomModule feature : modules) {
            if (feature.id().equals(moduleId)) {
                openTab(context, feature);
                return;
            }
        }
    }

    @Override
    public void qaReplay(HubContext context) {
        cardsEnteredAt = context.now();
    }

    /**
     * A tab row, and under it either the list or one mod's page.
     *
     * <p>A mod's settings used to open as a panel over the right of the list.
     * It covered the list's switches and text, and at 360dp its own descriptions
     * were cut after a few words. Each mod now gets the whole content area as a
     * page of its own, and the pages are tabs -- open several, switch between
     * them, close them -- so comparing two modules' settings is a click rather
     * than a close and a reopen.
     */
    @Override
    public void render(HubContext context, Rect area) {
        // The HUD editor's "Settings" asked for a module's page.
        String requested = FathomHubScreen.takeModuleRequest();
        if (requested != null) {
            for (FathomModule feature : modules) {
                if (feature.id().equals(requested)) {
                    openTab(context, feature);
                    break;
                }
            }
        }
        // The whole content area, as every other Hub page uses it: one right
        // edge across Quick, Mods, Performance and Profiles. The list used to
        // stop at 744 and the pages beside it did not.
        Rect page = area;
        tabStrip(context, page.top(TAB_HEIGHT));
        Rect body = page.belowTop(TAB_HEIGHT + TAB_GAP);

        float shown = pageIn.value(context.now());
        context.ui().pushAlpha(shown);
        context.ui().pushTransform(0f, (1f - shown) * GraphiteTokens.MOTION_TRAVEL * 0.5f);
        if (open == null) {
            browser(context, body);
        } else {
            modPage(context, body);
        }
        context.ui().popTransform();
        context.ui().popAlpha();
    }

    // -----------------------------------------------------------------------
    // Tabs
    // -----------------------------------------------------------------------

    /** Open a module's page as a tab, or switch to its tab if it is open. */
    private void openTab(HubContext context, FathomModule feature) {
        if (!tabs.contains(feature)) {
            tabs.add(feature);
        }
        activate(context, feature);
    }

    /** Make a tab (or, for null, "All mods") the one showing. */
    private void activate(HubContext context, FathomModule feature) {
        if (open == feature) {
            return;
        }
        // A colour half typed is applied if it is a colour, as leaving the
        // field would; then the page is left where it was scrolled to.
        commitHex(context);
        if (open != null) {
            pageScroll.put(open, Float.valueOf(detailScroll.position()));
        } else {
            listScroll = scroller.position();
        }
        open = feature;
        openSettings = feature == null ? Collections.<Setting>emptyList() : feature.settings();
        if (feature == null) {
            scroller.restore(listScroll);
        } else {
            Float remembered = pageScroll.get(feature);
            detailScroll.restore(remembered == null ? 0f : remembered.floatValue());
        }
        conflictFingerprint = Integer.MIN_VALUE;
        context.keybindCapture().cancel();
        editing = null;
        editingColour = null;
        editingField = null;
        hexDraft = "";
        context.controls().clearFocus();
        context.controls().closePopover();
        pageIn.set(0f, context.now());
        pageIn.to(1f, context.now(), Timing.PANEL);
        if (feature == null) {
            cardsEnteredAt = context.now();
        }
    }

    /** Close a tab; closing the one showing shows its neighbour, as a browser does. */
    private void closeTab(HubContext context, FathomModule feature) {
        int index = tabs.indexOf(feature);
        if (index < 0) {
            return;
        }
        tabs.remove(index);
        pageScroll.remove(feature);
        if (open == feature) {
            FathomModule next = tabs.isEmpty() ? null
                    : tabs.get(Math.min(index, tabs.size() - 1));
            activate(context, next);
        }
    }

    /**
     * The tab row: "All mods", then one tab per open page, in one light track.
     *
     * <p>The same construction as the All / On / Off filter -- a light glass
     * track, the chosen cell a denser segment with full-strength type -- so it
     * reads as part of this page's controls rather than as a second chrome.
     * Tabs share the room they have: they shrink towards {@link #TAB_MIN}
     * before the oldest page that is not showing is closed to make space.
     */
    private void tabStrip(HubContext context, Rect row) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Type type = Graphite.floor(ui, Graphite.UI);
        float inset = FathomTheme.Metrics.SEGMENT_INSET;
        float pad = Graphite.S3;
        float icon = FathomTheme.Metrics.ICON_INLINE;

        float homeWidth = pad + icon + Graphite.S2 + ui.measure(ALL_MODS, type) + pad;
        float room = row.width - inset * 2f - homeWidth;
        while (tabs.size() * TAB_MIN > room && tabs.size() > 1) {
            // Out of room even at the narrowest: close the oldest tab not showing.
            FathomModule oldest = tabs.get(0) == open ? tabs.get(1) : tabs.get(0);
            tabs.remove(oldest);
            pageScroll.remove(oldest);
        }
        float tabWidth = tabs.isEmpty() ? 0f : Math.max(TAB_MIN, Math.min(TAB_MAX, room / tabs.size()));
        float natural = 0f;
        for (FathomModule tab : tabs) {
            natural += Math.min(tabWidth, pad + icon + Graphite.S2 + ui.measure(tab.name(), type)
                    + Graphite.S2 + TAB_CLOSE + Graphite.S1);
        }
        Rect track = new Rect(row.x, row.y,
                Math.min(row.width, inset * 2f + homeWidth + natural), row.height);
        Graphite.surface(ui, track, Graphite.R_CONTROL, Graphite.Material.LIGHT, false);

        float x = track.x + inset;
        Rect home = new Rect(x, track.y + inset, homeWidth, track.height - inset * 2f);
        if (tab(context, "mods.tab.all", home, Icon.GRID, ALL_MODS, open == null, null)) {
            activate(context, null);
        }
        x = home.right();

        FathomModule closing = null;
        for (FathomModule feature : tabs) {
            float width = Math.min(tabWidth, pad + icon + Graphite.S2 + ui.measure(feature.name(), type)
                    + Graphite.S2 + TAB_CLOSE + Graphite.S1);
            Rect cell = new Rect(x, track.y + inset, width, track.height - inset * 2f);
            Rect close = new Rect(cell.right() - Graphite.S1 - TAB_CLOSE,
                    cell.centreY() - TAB_CLOSE / 2f, TAB_CLOSE, TAB_CLOSE);
            if (tab(context, "mods.tab." + feature.id(), cell, Icon.of(feature.icon()),
                    feature.name(), open == feature, close)) {
                activate(context, feature);
            }
            Controls.State closeState = controls.state("mods.tab.close." + feature.id(), close, true);
            Graphite.overlay(ui, close, close.height / 2f,
                    Math.max(closeState.hover, closeState.press * 1.6f), 0f);
            float glyph = FathomTheme.Metrics.ICON_CAPTION;
            ui.icon(Icon.CLOSE, close.centreX() - glyph / 2f, close.centreY() - glyph / 2f, glyph,
                    Colour.mix(Graphite.TEXT_TERTIARY, Graphite.TEXT, closeState.hover));
            controls.tooltip(closeState, close, "Close tab");
            if (closeState.clicked) {
                closing = feature;
            }
            x = cell.right();
        }
        if (closing != null) {
            closeTab(context, closing);
        }
    }

    /**
     * One tab: glyph and label, the chosen one a denser segment. The close
     * control (when there is one) is excluded from the tab's own hit area, so
     * closing a tab never also selects it.
     */
    private boolean tab(HubContext context, String id, Rect cell, Icon icon, String label,
            boolean selected, Rect close) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Rect hit = close == null ? cell : Rect.of(cell.x, cell.y, close.x, cell.bottom());
        Controls.State state = controls.state(id, hit, true);
        float on = controls.towards(id + "#on", selected, GraphiteTokens.MOTION_FAST);
        Graphite.segment(ui, cell, state.hover * (1f - on), on);
        int ink = Colour.mix(Graphite.TEXT_SECONDARY, Graphite.TEXT, Math.max(state.hover, on));
        float glyph = FathomTheme.Metrics.ICON_INLINE;
        float x = cell.x + Graphite.S3;
        ui.icon(icon, x, Math.round(cell.centreY() - glyph / 2f), glyph, ink);
        x += glyph + Graphite.S2;
        Type type = Graphite.floor(ui, Graphite.UI);
        float right = close == null ? cell.right() - Graphite.S3 : close.x - Graphite.S1;
        ui.text(Text.fit(ui, label, type, Math.max(0f, right - x)), x,
                Graphite.baseline(ui, cell, type), type, ink);
        controls.focusRing(ui, state, cell, Graphite.R_SEGMENT);
        return state.clicked && !selected;
    }

    private void browser(HubContext context, Rect area) {
        Renderer ui = context.ui();
        if (!toolbarBuilt) {
            buildToolbar();
        }
        toolbarBox.layout(ui, area.x, area.y, area.width, TOOLBAR_HEIGHT);
        graphiteSearch(context, searchBox.rect());
        graphiteFilters(context, filtersBox.rect());

        float bodyTop = area.y + TOOLBAR_HEIGHT + TOOLBAR_GAP;
        List<FathomModule> cards = visible(context);
        syncRows(cards);
        Rect group = groupRect(area.x, bodyTop, area.width, area.bottom() - bodyTop);
        Graphite.surface(ui, group, Graphite.R_PANEL, Graphite.Material.STANDARD, true);
        float viewX = area.x + LIST_PAD;
        float viewY = bodyTop + LIST_PAD;
        float viewWidth = area.width - LIST_PAD * 2f;
        float viewHeight = area.bottom() - bodyTop - LIST_PAD * 2f;
        // Laid out unscrolled, once per change; scrolling is a translation.
        listBox.layout(ui, viewX, viewY, viewWidth, listed.size() * MODULE_ROW);
        float contentHeight = listed.size() * MODULE_ROW;
        float offset = scroller.offset(context.delta(), contentHeight, viewHeight);

        ui.clip(viewX, viewY, viewX + viewWidth, viewY + viewHeight);
        ui.pushTransform(0f, -offset);
        for (int index = 0; index < listed.size(); index++) {
            ModRow row = listed.get(index);
            Rect card = row.box.rect();
            if (card.bottom() - offset < viewY || card.y - offset > viewY + viewHeight) {
                continue;
            }
            float arrival = Ease.sample(context.now(), cardsEnteredAt,
                    index * Timing.CARD_STAGGER, Timing.CARD_ENTER, Ease.Curve.STANDARD);
            ui.pushAlpha(arrival);
            ui.pushTransform(0f, (1f - arrival) * GraphiteTokens.MOTION_TRAVEL);
            moduleTile(context, row, index == listed.size() - 1);
            ui.popTransform();
            ui.popAlpha();
        }
        ui.popTransform();
        ui.unclip();
        graphiteScrollbar(ui, viewX, viewY, viewWidth, viewHeight, offset, contentHeight, context.now());

        if (cards.isEmpty()) {
            empty(context, new Rect(viewX, viewY, viewWidth, viewHeight));
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
        labelFilters();
        String[] labels = filterLabels;
        Type uiType = Graphite.floor(ui, Graphite.UI);
        for (int index = 0; index < labels.length; index++) {
            Rect cell = filterCells[index].rect();
            Controls.State state = context.controls().state(FILTER_IDS[index], cell, true);
            float selected = context.controls().towards(FILTER_ON_IDS[index],
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
    private void moduleTile(HubContext context, ModRow row, boolean last) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        FathomModule feature = row.module;
        Rect area = row.box.rect();
        boolean visuallyEnabled = feature.enabled() || FORCE_ENABLED_QA;

        Rect toggle = row.toggle.rect();
        Controls.State switchState = controls.state(row.toggleId, toggle, feature.switchable());
        if (switchState.clicked) {
            context.setFeatureEnabled(feature, !feature.enabled());
            visibleSearch = null;
            visibleStateFilter = -1;
        }

        // Keep the parent tile out from under the switch so one pointer gesture
        // cannot both toggle the module and open its page.
        Rect tileHit = row.tileHit(toggle.x - Graphite.S2);
        Controls.State tile = controls.state(row.tileId, tileHit, true);
        boolean selected = false;
        Graphite.overlay(ui, area, Graphite.R_CONTROL, tile.hover, tile.press * 0.5f);

        Graphite.modMark(ui, row.mark.rect(), row.icon(), visuallyEnabled ? 1f : 0f, selected ? 0f : tile.hover);

        // The text column stops short of the chevron slot, so names line up
        // whether or not a row opens a page.
        row.title.draw(ui, Graphite.TEXT);
        row.description.draw(ui, Graphite.TEXT_SECONDARY);

        if (feature.switchable()) {
            float on = controls.towards(row.toggleOnId, visuallyEnabled, GraphiteTokens.MOTION_FAST);
            Graphite.switchControl(ui, toggle, on, true);
        }

        boolean configurable = feature.hasSettings() || !feature.keybinds().isEmpty();
        if (configurable) {
            // A page opens to the right of the switch: say so, quietly.
            Rect chevron = row.chevron.rect();
            ui.icon(Icon.CHEVRON_RIGHT, chevron.x, chevron.y, chevron.width,
                    Colour.mix(Graphite.TEXT_TERTIARY, Graphite.TEXT_SECONDARY, tile.hover));
            controls.tooltip(tile, tileHit, row.tooltip);
        }
        if (tile.clicked && configurable && !switchState.clicked) {
            openTab(context, feature);
        }
        if (!last) {
            Graphite.hairline(ui, area.x + Graphite.S4, area.right() - Graphite.S4, area.bottom());
        }
    }

    private Rect groupCache = new Rect(0f, 0f, 0f, 0f);

    /** The list's glass group, kept while it stays put. */
    private Rect groupRect(float x, float y, float w, float h) {
        Rect cached = groupCache;
        if (cached.x != x || cached.y != y || cached.width != w || cached.height != h) {
            groupCache = new Rect(x, y, w, h);
        }
        return groupCache;
    }

    private Rect viewportCache = new Rect(0f, 0f, 0f, 0f);

    private Rect scrollViewport(float x, float y, float w, float h) {
        Rect cached = viewportCache;
        if (cached.x != x || cached.y != y || cached.width != w || cached.height != h) {
            viewportCache = new Rect(x, y, w, h);
        }
        return viewportCache;
    }

    /** Four-device-pixel thumb; no track and no warm legacy scrollbar. */
    private void graphiteScrollbar(Renderer ui, float viewX, float viewY, float viewWidth, float viewHeight,
            float offset, float contentHeight, float now) {
        if (contentHeight <= viewHeight || contentHeight <= 0f) {
            return;
        }
        Rect viewport = scrollViewport(viewX, viewY, viewWidth, viewHeight);
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

    // -----------------------------------------------------------------------
    // A mod's page
    // -----------------------------------------------------------------------

    /**
     * One module's configuration, as a page of the content area.
     *
     * <p>The header is a list row made large: the tile, the name, what it is,
     * and its switch at the right edge. Under it one scrolling column of
     * settings groups -- Behaviour, Appearance, Keybinds -- built from the same
     * row component as Quick and Performance, with the page's actions at the
     * foot. Nothing draws over anything else.
     */
    private void modPage(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();

        // A press outside the field being typed into ends the entry. Tested
        // against last frame's field, drawn under the same transform.
        if ((editing != null || editingColour != null) && context.pointer().pressed(0)
                && (editingField == null || !controls.pointerOverVisible(editingField))) {
            commitHex(context);
            editing = null;
        }
        editingField = null;

        // The settings groups leave the scroll rail a gutter at the right; the
        // header and footer end where the groups do, so the switch and the
        // reset button line up with the rows' own controls.
        Rect head = area.top(PAGE_HEADER).inset(0f, 0f, Skin.S3, 0f);
        float tile = FathomTheme.Metrics.TILE;
        Rect mark = new Rect(head.x, Math.round(head.centreY() - tile / 2f), tile, tile);
        Graphite.modMark(ui, mark, Icon.of(open.icon()), open.enabled() ? 1f : 0f, 0f);

        float actionsLeft = head.right();
        if (open.switchable()) {
            Rect toggle = new Rect(head.right() - Graphite.SWITCH_W,
                    head.centreY() - Graphite.SWITCH_H / 2f, Graphite.SWITCH_W, Graphite.SWITCH_H);
            Controls.State state = controls.state("mods.page.enabled", toggle, true);
            if (state.clicked) {
                context.setFeatureEnabled(open, !open.enabled());
                visibleSearch = null;
                visibleStateFilter = -1;
            }
            float on = controls.towards("mods.page.enabled#on", open.enabled(), GraphiteTokens.MOTION_FAST);
            Graphite.switchControl(ui, toggle, on, true);
            controls.focusRing(ui, state, toggle, toggle.height / 2f);
            actionsLeft = toggle.x - Graphite.S4;
        }

        float titleX = mark.right() + Graphite.S3;
        float room = Math.max(0f, actionsLeft - titleX);
        Type heading = Graphite.floor(ui, Graphite.HEADING);
        Type small = Graphite.floor(ui, Graphite.SMALL);
        float stack = Controls.stackTop(ui, head, heading, small);
        ui.text(Text.fit(ui, open.name(), heading, room), titleX,
                Controls.stackTitle(ui, stack, heading), heading, Graphite.TEXT);
        String meta = open.category().label() + " · " + open.owner()
                + " · " + (open.enabled() ? "On" : "Off");
        ui.text(Text.fit(ui, meta, small, room), titleX,
                Controls.stackDetail(ui, stack, heading, small), small, Graphite.TEXT_SECONDARY);

        // The footer is always there, so the page never changes height under
        // the pointer when the first setting is changed.
        boolean resettable = open.settingsChanged();
        float footerHeight = FathomTheme.Metrics.CONTROL + Graphite.S4;
        Rect viewport = Rect.of(area.x, head.bottom() + Graphite.S3,
                area.right(), area.bottom() - footerHeight);
        refreshConflictCache(context);
        float contentHeight = detailContentHeight(context);
        float offset = detailScroll.offset(context.delta(), contentHeight, viewport.height);

        ui.clip(viewport.x, viewport.y, viewport.right(), viewport.bottom());
        float y = viewport.y - offset;
        y = detailIntro(context, viewport, y);
        y = settingsGroup(context, viewport, y, false, "Behaviour", "How this module works.");
        y = settingsGroup(context, viewport, y, true, "Appearance",
                "Typography, colour and surface treatment.");
        y = layoutGroup(context, viewport, y);
        keybindGroup(context, viewport, y);
        ui.unclip();
        detailScroll.rail(ui, viewport, context.pointer(), context.now());

        float height = FathomTheme.Metrics.CONTROL;
        Rect footer = new Rect(area.x, area.bottom() - height, area.width - Skin.S3, height);
        String widget = widgetOf(context, open);
        if (widget != null) {
            Rect button = new Rect(footer.x, footer.y,
                    Controls.buttonWidth(ui, "Open HUD editor"), footer.height);
            if (controls.secondary(ui, "mods.page.hud", button, "Open HUD editor", true)) {
                commitHex(context);
                editing = null;
                context.editHud(widget);
            }
        }
        // Settings and keys only: the switch and the widget's position are
        // separate preferences with their own controls, and a reset of one
        // page's settings must not quietly turn the module off or move it.
        float width = Controls.buttonWidth(ui, "Reset to defaults");
        Rect reset = footer.vcentreRight(0f, width, footer.height);
        if (controls.danger(ui, "mods.page.reset", reset, "Reset to defaults", resettable)) {
            commitHex(context);
            editing = null;
            open.resetSettings();
            context.keybindCapture().cancel();
            edited(context, null);
        }
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
        float layout = layoutGroupHeight(context);
        if (layout > 0f) {
            height += Skin.SECTION_GAP + layout;
        }
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

    /** The description line, the note line under it when there is one, and the "off" notice. */
    private float introHeight() {
        float height = compactBadge(open).isEmpty() ? 22f : 48f;
        if (offNotice()) {
            height += Skin.S3 + OFF_NOTICE;
        }
        return height;
    }

    /** Height of the strip that says a module is off. */
    private static final float OFF_NOTICE = 44f;

    /** Whether the page says, above its settings, that the module is off. */
    private boolean offNotice() {
        return open.switchable() && !open.enabled() && !FORCE_ENABLED_QA;
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
            context.ui().text(note, text.x, Controls.stackDetail(context.ui(), text.y, Type.BODY, Type.CAPTION)
                    + Controls.STACK_GAP,
                    Type.CAPTION, unmet(open) ? Skin.WARN : Skin.TEXT_FAINT);
        }
        y = text.y + (compactBadge(open).isEmpty() ? 22f : 48f);
        if (offNotice()) {
            // Off, said plainly where the settings start: they can still be
            // changed, and nothing on the screen will show it until it is on.
            Rect strip = new Rect(viewport.x, y + Skin.S3, viewport.width - Skin.S3, OFF_NOTICE);
            Graphite.surface(context.ui(), strip, Graphite.R_CONTROL, Graphite.Material.LIGHT, false);
            Type small = Graphite.floor(context.ui(), Graphite.SMALL);
            float buttonWidth = Controls.buttonWidth(context.ui(), "Turn on");
            Rect button = new Rect(strip.right() - Skin.S2 - buttonWidth, strip.centreY() - Skin.H_SM / 2f,
                    buttonWidth, Skin.H_SM);
            context.ui().icon(Icon.INFO, strip.x + Skin.S3, strip.centreY() - 8f, 16f, Graphite.TEXT_SECONDARY);
            context.ui().text(Text.fit(context.ui(), open.name() + " is off. Its settings are kept and apply once it is on.",
                            small, button.x - strip.x - Skin.S3 * 2f - 24f),
                    strip.x + Skin.S3 + 24f, Graphite.baseline(context.ui(), strip, small), small,
                    Graphite.TEXT_SECONDARY);
            if (context.controls().secondary(context.ui(), "mods.page.turn-on", button, "Turn on", true)) {
                context.setFeatureEnabled(open, true);
                visibleSearch = null;
                visibleStateFilter = -1;
            }
            y = strip.bottom();
        }
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
                // The value is part of the control, on its left, so a long
                // label can never run underneath it.
                Rect track = Rect.of(control.x + SLIDER_VALUE + Skin.S2, control.y, control.right(), control.bottom());
                double next = controls.slider(ui, id, track, slider.fraction(), true);
                if (Math.abs(next - slider.fraction()) > 0.0001d) {
                    slider.setFraction(next);
                    edited(context, setting);
                }
                ui.textRight(slider.display(), control.x + SLIDER_VALUE, Controls.baseline(ui, area, Type.CAPTION),
                        Type.CAPTION, Skin.TEXT_MUTED);
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
                if (setting == editing) {
                    editingField = control;
                }
                if (controls.textField(ui, id, control, text.get(), setting == editing)) {
                    commitHex(context);
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
                // A hex field beside the swatch: the palette is for picking
                // quickly, the field is for the exact colour somebody has in mind.
                Rect hex = new Rect(control.x, control.y, control.width - Controls.SWATCH - HEX_GAP, control.height);
                boolean typingHere = editingColour == colour;
                if (typingHere) {
                    editingField = hex;
                }
                String shown = typingHere ? "#" + hexDraft : "#" + colour.serialise();
                if (controls.textField(ui, id + "#hex", hex, shown, typingHere)) {
                    if (typingHere) {
                        commitHex(context);
                    } else {
                        commitHex(context);
                        editing = null;
                        editingColour = colour;
                        hexDraft = "";
                        context.keybindCapture().cancel();
                    }
                }
                Rect swatch = new Rect(control.right() - Controls.SWATCH, control.y, Controls.SWATCH, control.height);
                int next = controls.colour(ui, id, swatch, colour.rgb(), colour.fallback());
                if (next != colour.rgb()) {
                    colour.set(next);
                    if (typingHere) {
                        editingColour = null;
                    }
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
                return Math.min(SLIDER_VALUE + Skin.S2 + 150f, rowWidth * 0.5f);
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
                return Controls.SWATCH + HEX_GAP + HEX_WIDTH;
        }
    }

    private static final float HEX_WIDTH = 86f;
    /** Room for a slider's value ("100%", "18 px") at the left of its control. */
    private static final float SLIDER_VALUE = 44f;
    private static final float HEX_GAP = 8f;

    /**
     * Apply a typed hex colour, if it is one. Six digits, or three that stand
     * for six (#F80 is #FF8800); anything else leaves the colour as it was.
     */
    private void commitHex(HubContext context) {
        Setting.Colour colour = editingColour;
        editingColour = null;
        if (colour == null) {
            return;
        }
        Integer parsed = Setting.Colour.parseTyped(hexDraft);
        hexDraft = "";
        if (parsed != null && parsed.intValue() != colour.rgb()) {
            colour.set(parsed.intValue());
            edited(context, colour);
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

    /** The HUD widget a module owns, or null: the one its page positions. */
    private static String widgetOf(HubContext context, FathomModule module) {
        for (net.fathom.optimizations.core.hud.Widget widget : context.hud().registry().all()) {
            if (context.hud().owner(widget.id()) == module) {
                return widget.id();
            }
        }
        return null;
    }

    // -----------------------------------------------------------------------
    // Position and size
    // -----------------------------------------------------------------------

    private static final net.fathom.optimizations.core.hud.Anchor[] ANCHORS =
            net.fathom.optimizations.core.hud.Anchor.values();
    private static final String[] ANCHOR_LABELS = anchorLabels();
    private static final int LAYOUT_ROWS = 4;

    private static String[] anchorLabels() {
        String[] labels = new String[ANCHORS.length];
        for (int index = 0; index < ANCHORS.length; index++) {
            labels[index] = ANCHORS[index].label();
        }
        return labels;
    }

    /** The position row's description, rebuilt only when the placement changes. */
    private net.fathom.optimizations.core.hud.Placement describedPlacement;
    private String positionText = "";

    private float layoutGroupHeight(HubContext context) {
        return widgetOf(context, open) == null ? 0f
                : FathomSettingRows.SECTION_HEADING + FathomSettingRows.ROW * LAYOUT_ROWS;
    }

    /**
     * Where the module's widget sits and how large it is: the same scale,
     * opacity and anchor the HUD editor's inspector sets, so the two can never
     * disagree, and a way back to where it started. Dragging stays in the
     * editor, one button away.
     */
    private float layoutGroup(HubContext context, Rect viewport, float y) {
        String widgetId = widgetOf(context, open);
        if (widgetId == null) {
            return y;
        }
        Renderer ui = context.ui();
        Controls controls = context.controls();
        net.fathom.optimizations.fabric.hud.FathomHud hud = context.hud();
        net.fathom.optimizations.core.hud.Placement placement = hud.placement(widgetId);
        net.fathom.optimizations.core.hud.Widget widget = hud.registry().find(widgetId);

        y += Skin.SECTION_GAP;
        Rect heading = new Rect(viewport.x, y, viewport.width - Skin.S3, FathomSettingRows.SECTION_HEADING);
        FathomSettingRows.heading(ui, heading, "Position and size",
                "Where the widget sits on screen. Drag it in the HUD editor.");
        y = heading.bottom();
        Rect surface = new Rect(viewport.x, y, viewport.width - Skin.S3, FathomSettingRows.ROW * LAYOUT_ROWS);
        FathomSettingRows.surface(ui, surface);
        float sliderWidth = Math.min(SLIDER_VALUE + Skin.S2 + 150f, surface.width * 0.5f);

        // Scale, in five-percent steps, as the inspector's.
        Rect row = new Rect(surface.x, y, surface.width, FathomSettingRows.ROW);
        if (rowVisible(row, viewport)) {
            float min = net.fathom.optimizations.core.hud.Placement.MIN_SCALE;
            float max = net.fathom.optimizations.core.hud.Placement.MAX_SCALE;
            float fraction = (placement.scale() - min) / (max - min);
            float next = layoutSlider(context, "layout.scale." + widgetId, row, "Scale",
                    Math.round(placement.scale() * 100) + "%", fraction, sliderWidth);
            if (next >= 0f) {
                placed(context, widgetId, placement.withScale(Math.round((min + next * (max - min)) * 20f) / 20f));
            }
        }
        y += row.height;

        row = new Rect(surface.x, y, surface.width, FathomSettingRows.ROW);
        if (rowVisible(row, viewport)) {
            float next = layoutSlider(context, "layout.opacity." + widgetId, row, "Opacity",
                    Math.round(placement.opacity() * 100) + "%", (placement.opacity() - 0.1f) / 0.9f, sliderWidth);
            if (next >= 0f) {
                placed(context, widgetId, placement.withOpacity(Math.round((0.1f + next * 0.9f) * 20f) / 20f));
            }
        }
        y += row.height;

        // Anchor: which edge it keeps its distance from when the screen changes.
        row = new Rect(surface.x, y, surface.width, FathomSettingRows.ROW);
        if (rowVisible(row, viewport)) {
            Rect control = FathomSettingRows.row(ui, controls, "layout.row.anchor." + widgetId, row,
                    "Anchor", "The edge it follows when the window or GUI scale changes.",
                    Math.min(150f, row.width * 0.5f), Skin.H_SM, true, controls.pointerOverVisible(row), false);
            int chosen = controls.dropdown(ui, "layout.anchor." + widgetId, control, ANCHOR_LABELS,
                    placement.anchor().ordinal());
            if (chosen != placement.anchor().ordinal() && chosen >= 0 && chosen < ANCHORS.length) {
                placed(context, widgetId, reanchored(context, widgetId, placement, ANCHORS[chosen]));
            }
        }
        y += row.height;

        // Where it is now, and the way back.
        row = new Rect(surface.x, y, surface.width, FathomSettingRows.ROW);
        if (rowVisible(row, viewport)) {
            if (placement != describedPlacement) {
                describedPlacement = placement;
                positionText = placement.offsetX() + " " + edge(placement.anchor().horizontal(), true) + ", "
                        + placement.offsetY() + " " + edge(placement.anchor().vertical(), false);
            }
            float width = Controls.buttonWidth(ui, "Reset position");
            Rect control = FathomSettingRows.row(ui, controls, "layout.row.position." + widgetId, row,
                    "Position", positionText, width, Skin.H_SM, true, controls.pointerOverVisible(row), true);
            boolean home = widget == null
                    || (placement.anchor() == widget.defaultPlacement().anchor()
                            && placement.offsetX() == widget.defaultPlacement().offsetX()
                            && placement.offsetY() == widget.defaultPlacement().offsetY());
            if (controls.secondary(ui, "layout.reset." + widgetId, control, "Reset position", !home)) {
                net.fathom.optimizations.core.hud.Placement start = widget.defaultPlacement();
                placed(context, widgetId, placement.withAnchor(start.anchor())
                        .withOffset(start.offsetX(), start.offsetY()));
            }
        }
        y += row.height;
        return y;
    }

    private static boolean rowVisible(Rect row, Rect viewport) {
        return row.bottom() >= viewport.y && row.y <= viewport.bottom();
    }

    /** "from left", "from the top" and so on: what an offset is measured from. */
    private static String edge(float fraction, boolean horizontal) {
        if (fraction < 0.5f) {
            return horizontal ? "from the left" : "from the top";
        }
        if (fraction > 0.5f) {
            return horizontal ? "from the right" : "from the bottom";
        }
        return horizontal ? "from the centre" : "from the middle";
    }

    /**
     * A slider row whose value sits inside its control, as every setting's does.
     *
     * @return the new fraction, or -1 when it did not move
     */
    private float layoutSlider(HubContext context, String id, Rect row, String label, String value,
            float fraction, float width) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Rect control = FathomSettingRows.row(ui, controls, id + "#row", row, label, "", width,
                Controls.SLIDER_HEIGHT, true, controls.pointerOverVisible(row), false);
        Rect track = Rect.of(control.x + SLIDER_VALUE + Skin.S2, control.y, control.right(), control.bottom());
        double next = controls.slider(ui, id, track, fraction, true);
        ui.textRight(value, control.x + SLIDER_VALUE, Controls.baseline(ui, row, Type.CAPTION), Type.CAPTION,
                Skin.TEXT_MUTED);
        return Math.abs(next - fraction) > 0.001d ? (float) next : -1f;
    }

    /** A new anchor for a widget, with offsets that keep it exactly where it is on screen. */
    private static net.fathom.optimizations.core.hud.Placement reanchored(HubContext context, String widgetId,
            net.fathom.optimizations.core.hud.Placement placement, net.fathom.optimizations.core.hud.Anchor next) {
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        net.fathom.optimizations.fabric.gfx.MinecraftGuiSize size = net.fathom.optimizations.fabric.gfx.MinecraftGuiSize.get(client);
        for (net.fathom.optimizations.fabric.hud.FathomHud.Positioned each
                : context.hud().boxes(client, size.width(), size.height())) {
            if (each.widget.id().equals(widgetId)) {
                net.fathom.optimizations.core.hud.Snap.Box box = each.box;
                // From where the placement puts it, not where the flow moved it.
                return placement.withAnchor(next).withOffset(
                        offsetFor(next.horizontal(), box.x, box.width, size.width()),
                        offsetFor(next.vertical(), each.naturalY, box.height, size.height()));
            }
        }
        return placement.withAnchor(next);
    }

    /** The inverse of {@code Anchor.x}: an absolute position as an inward offset. */
    private static int offsetFor(float fraction, int position, int extent, int screen) {
        if (fraction > 0.5f) {
            return screen - (position + extent);
        }
        if (fraction < 0.5f) {
            return position;
        }
        return position - Math.round((screen - extent) * 0.5f);
    }

    /** Apply a placement from this page: live on the next frame, written with the rest. */
    private static void placed(HubContext context, String widgetId,
            net.fathom.optimizations.core.hud.Placement next) {
        context.hud().place(widgetId, next);
        context.markDirty();
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
        // Minecraft's own bindings count: Fathom consumes nothing, so a shared
        // key fires both actions. The rule itself is core's (KeyConflicts),
        // where it is tested.
        String[] names = null;
        int[] codes = null;
        net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
        if (client != null && client.options != null && client.options.allKeys != null) {
            net.minecraft.client.option.KeyBinding[] all = client.options.allKeys;
            names = new String[all.length];
            codes = new int[all.length];
            for (int index = 0; index < all.length; index++) {
                if (all[index] != null) {
                    names[index] = net.minecraft.client.resource.language.I18n.translate(
                            all[index].getTranslationKey());
                    codes[index] = all[index].getCode();
                }
            }
        }
        return net.fathom.optimizations.core.input.KeyConflicts.sentence(
                net.fathom.optimizations.core.input.KeyConflicts.of(
                        keybind, modules, context.hud().hubKey(), names, codes));
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
        if (editingColour != null) {
            return typeHex(context, character, keyCode);
        }
        if (editing != null) {
            return type(context, character, keyCode);
        }
        if (open != null && keyCode == Keyboard.KEY_ESCAPE && !context.controls().popoverOpen()) {
            // Back to the list, as a browser's Back would; the tab stays open.
            // An open dropdown is closed first, by the Hub, one layer at a time.
            activate(context, null);
            return true;
        }
        return false;
    }

    /** Typing a hex colour: Enter applies it, Escape leaves the colour alone. */
    private boolean typeHex(HubContext context, char character, int keyCode) {
        if (keyCode == Keyboard.KEY_ESCAPE) {
            editingColour = null;
            hexDraft = "";
            return true;
        }
        if (keyCode == Keyboard.KEY_RETURN || keyCode == Keyboard.KEY_NUMPADENTER) {
            commitHex(context);
            return true;
        }
        if (keyCode == Keyboard.KEY_BACK) {
            if (!hexDraft.isEmpty()) {
                hexDraft = hexDraft.substring(0, hexDraft.length() - 1);
            }
            return true;
        }
        char lower = Character.toLowerCase(character);
        boolean digit = (lower >= '0' && lower <= '9') || (lower >= 'a' && lower <= 'f');
        if (digit && hexDraft.length() < 6) {
            hexDraft = hexDraft + Character.toUpperCase(character);
            if (hexDraft.length() == 6) {
                // Six digits is a whole colour: show it straight away.
                commitHex(context);
            }
        }
        return true;
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
        visibleRevision++;
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

    // -----------------------------------------------------------------------
    // The list, retained
    // -----------------------------------------------------------------------

    /** Bumped whenever the visible modules change: the rows are rebuilt for it. */
    private int visibleRevision;
    private int listedRevision = -1;
    /** The list as laid out: one row per visible module, top to bottom, unscrolled. */
    private final Box listBox = Box.column("mods.rows");
    private final Box toolbarBox = Box.row("mods.toolbar");
    private final Box searchBox = Box.fixed("mods.search", 320f, TOOLBAR_HEIGHT);
    private final Box filtersBox = Box.fixed("mods.filters", 222f, TOOLBAR_HEIGHT);
    private final Box[] filterCells = new Box[3];
    private boolean toolbarBuilt;
    private final java.util.Map<FathomModule, ModRow> rowModels =
            new java.util.IdentityHashMap<FathomModule, ModRow>();
    private final List<ModRow> listed = new ArrayList<ModRow>();
    /** "All 15", "On 5", "Off 10": rebuilt when the counts change, not every frame. */
    private final String[] filterLabels = new String[3];
    private int labelledTotal = -1;
    private int labelledOn = -1;
    private static final String[] FILTER_IDS = {"mods.state.0", "mods.state.1", "mods.state.2"};
    private static final String[] FILTER_ON_IDS = {"mods.state.0#on", "mods.state.1#on", "mods.state.2#on"};

    /**
     * One module's row: its boxes, and the strings its controls are named by,
     * made once -- the list used to build three ids and a tooltip per row per
     * frame.
     */
    private static final class ModRow {
        final FathomModule module;
        final String toggleId;
        final String toggleOnId;
        final String tileId;
        final String tooltip;
        final Box box;
        final Box mark;
        final TextLeaf title;
        final TextLeaf description;
        final Box chevron;
        final Box toggle;

        ModRow(FathomModule module) {
            this.module = module;
            toggleId = "mod.toggle." + module.id();
            toggleOnId = toggleId + "#on";
            tileId = "mod.tile." + module.id();
            tooltip = "Open " + module.name() + " settings";
            // [mark] 12 [name / description] 12 [chevron] 12 [switch], 16 in
            // from either edge, centred in the row.
            box = Box.row("mod.row." + module.id()).height(MODULE_ROW)
                    .padding(Graphite.S4, 0f, Graphite.S4, 0f).gap(Graphite.S3).align(Box.Align.CENTRE);
            mark = Box.fixed("mod.mark." + module.id(), GraphiteTokens.SIZE_MOD_ICON, GraphiteTokens.SIZE_MOD_ICON);
            Box text = Box.column("mod.text." + module.id()).width(0f).grow(1f).gap(Controls.STACK_GAP)
                    .justify(Box.Align.CENTRE);
            title = TextLeaf.of("mod.title." + module.id(), Graphite.UI, Graphite.UI.size() * 1.3f);
            description = TextLeaf.of("mod.description." + module.id(), Graphite.SMALL,
                    Graphite.SMALL.size() * 1.3f);
            title.set(module.name());
            description.set(module.description());
            text.add(title.box());
            text.add(description.box());
            chevron = Box.fixed("mod.chevron." + module.id(), FathomTheme.Metrics.ICON_INLINE,
                    FathomTheme.Metrics.ICON_INLINE);
            toggle = Box.fixed("mod.switch." + module.id(), Graphite.SWITCH_W, Graphite.SWITCH_H);
            box.add(mark);
            box.add(text);
            box.add(chevron);
            box.add(toggle);
        }

        private Icon icon;
        private Rect hit;
        private Rect hitFor;
        private float hitRight = Float.NaN;

        Icon icon() {
            if (icon == null) {
                icon = Icon.of(module.icon());
            }
            return icon;
        }

        /** The tile's hitbox -- the row up to the switch -- kept until the row moves. */
        Rect tileHit(float right) {
            Rect area = box.rect();
            if (hitFor != area || right != hitRight) {
                hitFor = area;
                hitRight = right;
                hit = new Rect(area.x, area.y, Math.max(0f, right - area.x), area.height);
            }
            return hit;
        }
    }

    /** Rebuild the list's rows when, and only when, the visible modules changed. */
    private void syncRows(List<FathomModule> cards) {
        if (listedRevision == visibleRevision) {
            return;
        }
        listedRevision = visibleRevision;
        listBox.children().clear();
        listBox.invalidate();
        listed.clear();
        for (FathomModule module : cards) {
            ModRow row = rowModels.get(module);
            if (row == null) {
                row = new ModRow(module);
                rowModels.put(module, row);
            }
            listBox.add(row.box);
            listed.add(row);
        }
    }

    private void buildToolbar() {
        toolbarBuilt = true;
        toolbarBox.height(TOOLBAR_HEIGHT).gap(TOOLBAR_GAP);
        toolbarBox.add(searchBox);
        float inset = FathomTheme.Metrics.SEGMENT_INSET;
        Box cells = Box.row("mods.filters.cells").padding(inset);
        for (int index = 0; index < 3; index++) {
            filterCells[index] = Box.column(FILTER_IDS[index]).width(0f).grow(1f);
            cells.add(filterCells[index]);
        }
        filtersBox.axis(Box.Axis.STACK);
        filtersBox.add(cells);
        toolbarBox.add(filtersBox);
    }

    private void labelFilters() {
        int enabled = 0;
        for (FathomModule module : modules) {
            if (module.enabled()) {
                enabled++;
            }
        }
        if (modules.size() != labelledTotal || enabled != labelledOn) {
            labelledTotal = modules.size();
            labelledOn = enabled;
            filterLabels[0] = "All " + labelledTotal;
            filterLabels[1] = "On " + enabled;
            filterLabels[2] = "Off " + (labelledTotal - enabled);
        }
    }
}
