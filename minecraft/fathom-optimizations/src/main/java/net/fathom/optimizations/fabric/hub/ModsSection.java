package net.fathom.optimizations.fabric.hub;

import java.util.ArrayList;
import java.util.HashMap;
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
import net.fathom.optimizations.core.ui.layout.ResponsiveGrid;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.ui.VisualEffects;

/**
 * The Mods page: every feature, searchable, with its settings a click away.
 *
 * <h2>Responsive cards, not a settings document</h2>
 *
 * <p>Icon, name, state, description and the two actions that matter are visible
 * without leaving the browser. The grid chooses one to four columns from the
 * available width, so opening the settings drawer narrows the same browser
 * rather than covering or scaling it.
 *
 * <h2>Descriptions stay useful at every width</h2>
 *
 * <p>The card width is derived from the viewport, text wraps against that real
 * width, and the settings drawer uses the same font metrics. Nothing is laid
 * out against a guessed 720p rectangle and enlarged later.
 *
 * <h2>Why the settings are a drawer and not another screen</h2>
 *
 * <p>The requirement is "do not require leaving Minecraft for routine mod
 * configuration", and a screen you have to navigate back out of is one step
 * closer to leaving than a panel that slides over half the list. The list
 * narrows rather than being covered, so the row you pressed is still visible
 * beside its own settings.
 *
 * <h2>Nothing here is written per module</h2>
 *
 * <p>The drawer is built from {@code Setting.Kind}: a toggle draws a switch, a
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

    /** Visual-QA only: exercise a full grid of lit cards without changing saved module state. */
    private static final boolean FORCE_ENABLED_QA = Boolean.getBoolean("fathom.qaAllModules");

    private static final float CARD_HEIGHT = 128f;
    private static final float CARD_GAP = 12f;
    /** Space inside the scroller for hover lift, shadow and glow falloff. */
    private static final float CARD_FIELD_PAD = 12f;
    private static final float MIN_CARD_WIDTH = 190f;
    private static final float ICON = 22f;
    private static final float ACTION = 26f;
    private static final float SETTING_ROW = 40f;
    private static final float CONFLICT_ROW = 18f;
    private static final float DRAWER_MAX = 380f;

    private final Scroller scroller = new Scroller();
    private final Scroller drawerScroll = new Scroller();
    private final Motion drawer = new Motion(0f, Timing.PANEL);
    /** Fixed-size per-module event state; entries are created once, never per frame. */
    private final Map<FathomModule, CardEnergy> cardEnergy = new HashMap<FathomModule, CardEnergy>();

    private static final class CardEnergy {
        boolean enabled;
        boolean activating;
        float changedAt = -10f;
    }

    /** The text setting being typed into, or null. */
    private Setting.Text editing;

    /** Null when the list is showing everything. */
    private Category filter;
    /** The feature whose settings are open, or null. */
    private FathomModule open;
    /** Keeps the detail surface alive while its close animation recedes. */
    private boolean drawerClosing;
    /** Start of the current category's tight card-grid entrance. */
    private float cardsEnteredAt;
    private float drawerOpenedAt;
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
        return true;
    }

    @Override
    public String subtitle() {
        return "Choose the modules you want.";
    }

    @Override
    public void opened(HubContext context) {
        scroller.reset();
        open = null;
        drawerClosing = false;
        drawer.set(0f, context.now());
        cardsEnteredAt = context.now();
        String qaOpen = System.getProperty("fathom.qaOpenSettings", "").trim();
        if (!qaOpen.isEmpty()) {
            // Either "true" for the first configurable module, or a module id,
            // so a capture can be of one particular drawer.
            for (FathomModule feature : context.features().all()) {
                boolean configurable = feature.hasSettings() || !feature.keybinds().isEmpty();
                if (configurable && (qaOpen.equalsIgnoreCase(feature.id())
                        || "true".equalsIgnoreCase(qaOpen))) {
                    open = feature;
                    break;
                }
            }
        }
        context.keybindCapture().cancel();
    }

    @Override
    public void qaReplay(HubContext context) {
        for (CardEnergy memory : cardEnergy.values()) {
            memory.activating = true;
            memory.changedAt = context.now();
        }
    }

    @Override
    public void render(HubContext context, Rect area) {
        Renderer ui = context.ui();
        drawer.to(open == null || drawerClosing ? 0f : 1f, context.now());
        float drawn = drawer.value(context.now());

        // Filtering owns the complete page width. Opening settings narrows only
        // the card field beneath it, so the final category never disappears.
        Rect chips = area.top(Skin.H_MD);
        categories(context, chips);

        Rect workspace = area.belowTop(Skin.H_MD + Skin.S4);
        float drawerWidth = Math.min(DRAWER_MAX, workspace.width * 0.42f);
        Rect body = new Rect(workspace.x, workspace.y,
                workspace.width - (drawerWidth + Skin.S5) * drawn, workspace.height);
        List<FathomModule> cards = visible(context);
        float gridWidth = Math.max(1f, body.width - CARD_FIELD_PAD * 2f);
        int columns = ResponsiveGrid.columns(gridWidth, MIN_CARD_WIDTH, CARD_GAP, 4);
        float cardWidth = ResponsiveGrid.cellWidth(gridWidth, CARD_GAP, columns);
        float contentHeight = ResponsiveGrid.contentHeight(cards.size(), columns, CARD_HEIGHT, CARD_GAP)
                + CARD_FIELD_PAD * 2f;
        float offset = scroller.offset(context.delta(), contentHeight, body.height);

        ui.clip(body.x, body.y, body.right(), body.bottom());
        for (int index = 0; index < cards.size(); index++) {
            int rowIndex = index / columns;
            int columnIndex = index % columns;
            Rect card = new Rect(
                    body.x + CARD_FIELD_PAD + columnIndex * (cardWidth + CARD_GAP),
                    body.y + CARD_FIELD_PAD + rowIndex * (CARD_HEIGHT + CARD_GAP) - offset,
                    cardWidth,
                    CARD_HEIGHT);
            if (card.bottom() < body.y || card.y > body.bottom()) {
                continue;
            }
            float arrival = Ease.sample(context.now(), cardsEnteredAt,
                    index * Timing.CARD_STAGGER, Timing.CARD_ENTER, Ease.Curve.STANDARD);
            ui.pushAlpha(arrival);
            ui.pushTransform(0f, (1f - arrival) * 10f,
                    0.98f + arrival * 0.02f, card.centreX(), card.centreY());
            card(context, cards.get(index), card);
            ui.popTransform();
            ui.popAlpha();
        }
        VisualEffects.scrollEdgeFade(ui, body, offset > 0.5f,
                offset + body.height < contentHeight - 0.5f);
        ui.unclip();
        scroller.rail(ui, body, context.pointer(), context.now());

        if (cards.isEmpty()) {
            empty(context, body);
        }

        if (drawn > 0.01f && open != null) {
            Rect panel = new Rect(workspace.right() - drawerWidth, workspace.y,
                    drawerWidth, workspace.height);
            settingsDrawer(context, panel, drawn);
        }
        if (drawerClosing && drawer.settled(context.now()) && drawn <= 0.001f) {
            open = null;
            drawerClosing = false;
        }
    }

    private void empty(HubContext context, Rect body) {
        Renderer ui = context.ui();
        String message = context.search().isEmpty()
                ? "Nothing in this category."
                : "Nothing matches “" + context.search() + "”.";
        ui.text(message, body.x, body.y + Skin.S6 + ui.ascent(Type.BODY), Type.BODY, Skin.TEXT_MUTED);
        if (!context.search().isEmpty()) {
            Rect button = new Rect(body.x, body.y + Skin.S6 + 26f, 140f, Skin.H_MD);
            if (context.controls().secondary(ui, "mods.clearsearch", button, "Clear search", true)) {
                context.setSearch("");
            }
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
        List<Category> present = new ArrayList<Category>();
        for (Category category : Category.values()) {
            if (!context.features().inCategory(category).isEmpty()) {
                present.add(category);
            }
        }
        String[] labels = new String[present.size() + 1];
        labels[0] = "All";
        int selected = 0;
        for (int index = 0; index < present.size(); index++) {
            labels[index + 1] = present.get(index).label();
            if (present.get(index) == filter) {
                selected = index + 1;
            }
        }

        int chosen = context.controls().segmentedNatural(
                context.ui(), "mods.filter", area, labels, selected);
        if (chosen != selected) {
            filter = chosen == 0 ? null : present.get(chosen - 1);
            scroller.reset();
            cardsEnteredAt = context.now();
        }
    }

    private void card(HubContext context, FathomModule feature, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Rect card = area;
        Controls.State surface = controls.state("mod.card." + feature.id(), card, true);
        boolean visuallyEnabled = feature.enabled() || FORCE_ENABLED_QA;
        CardEnergy memory = cardEnergy.get(feature);
        if (memory == null) {
            memory = new CardEnergy();
            memory.enabled = feature.enabled();
            cardEnergy.put(feature, memory);
        } else if (memory.enabled != feature.enabled()) {
            memory.enabled = feature.enabled();
            memory.activating = feature.enabled();
            memory.changedAt = context.now();
        }
        float pulseProgress = Ease.clamp01((context.now() - memory.changedAt)
                / VisualEffects.ACTIVATION_PULSE_SECONDS);
        float enabled = controls.towards("mod.card." + feature.id() + "#enabled",
                visuallyEnabled, Timing.TOGGLE);
        float selected = controls.towards("mod.card." + feature.id() + "#selected",
                feature == open && !drawerClosing, Timing.NAVIGATION);
        float lit = Math.max(enabled, selected);
        float scale = 1f + surface.hover * VisualEffects.CARD_HOVER_SCALE
                - surface.press * 0.008f;
        float lift = surface.press * 0.80f
                - surface.hover * VisualEffects.CARD_HOVER_LIFT * (1f - surface.press);
        ui.pushTransform(0f, lift, scale, card.centreX(), card.centreY());

        // Enabled cards stay dark: the state is the red edge, notch and icon,
        // never a red face or a bloom around the card.
        int resting = Skin.mix(Skin.SURFACE, Skin.ELEVATED, selected * 0.60f);
        int face = Skin.mix(resting, Skin.SURFACE_HOVER, surface.hover);
        ui.shadow(card.x, card.y, card.right(), card.bottom(), Skin.R_CARD,
                VisualEffects.CARD_SHADOW_SPREAD, Skin.scaleAlpha(Skin.SHADOW,
                        VisualEffects.CARD_SHADOW_OPACITY * (0.80f + surface.hover * 0.20f)));
        VisualEffects.activationPulse(ui, card, Skin.R_CARD, pulseProgress, memory.activating);
        VisualEffects.layeredSurface(ui, card, Skin.R_CARD, face, surface.hover);
        cardFooterSurface(ui, card);

        int energizedEdge = Skin.mix(Skin.BASE_RED, Skin.HOT_RED, selected * 0.80f + surface.hover * 0.35f);
        int neutralEdge = Skin.mix(Skin.EDGE_SURFACE, Skin.EDGE_ELEVATED, surface.hover);
        int edge = Skin.mix(neutralEdge, energizedEdge, Math.max(enabled, selected));
        ui.outline(card.x, card.y, card.right(), card.bottom(), Skin.R_CARD,
                Skin.BORDER_STANDARD
                        + enabled * (Skin.BORDER_ENERGIZED - Skin.BORDER_STANDARD)
                        + selected * (Skin.BORDER_SELECTED - Skin.BORDER_ENERGIZED),
                edge);

        Rect iconBox = new Rect(card.x + Skin.S3, card.y + Skin.S3, 40f, 40f);
        float iconScale = 1f + surface.hover * VisualEffects.ICON_HOVER_SCALE
                + (1f - pulseProgress) * (memory.activating ? 0.05f : -0.02f);
        ui.pushTransform(0f, 0f, iconScale, iconBox.centreX(), iconBox.centreY());
        // The one light on an enabled card: a tight falloff around its icon.
        VisualEffects.stateGlow(ui, iconBox, Skin.R_MEDIUM, lit);
        int iconFace = Skin.mix(Skin.RECESSED, Skin.BASE_RED, enabled * 0.22f + selected * 0.10f);
        VisualEffects.layeredSurface(ui, iconBox, Skin.R_MEDIUM, iconFace, 0f);
        VisualEffects.energyWash(ui, iconBox, Skin.R_MEDIUM, iconBox.centreX(), iconBox.centreY(),
                VisualEffects.ICON_ENERGY_REACH, enabled);
        ui.outline(iconBox.x, iconBox.y, iconBox.right(), iconBox.bottom(), Skin.R_MEDIUM,
                Skin.BORDER_STANDARD, Skin.mix(Skin.EDGE_SURFACE, Skin.ACTIVE_RED, lit));
        int icon = Skin.mix(Skin.TEXT_FAINT, Skin.HOT_RED, enabled);
        ui.icon(Icon.of(feature.icon()), iconBox.centreX() - ICON / 2f,
                iconBox.centreY() - ICON / 2f, ICON,
                VisualEffects.hoverBrighten(icon, surface.hover));
        ui.popTransform();

        float titleX = iconBox.right() + Skin.S3;
        float titleRoom = card.right() - Skin.S3 - titleX;
        ui.text(Text.fit(ui, feature.name(), Type.ROW_TITLE, titleRoom), titleX,
                card.y + Skin.S3 + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE,
                Skin.mix(Skin.TEXT_MUTED, Skin.TEXT,
                        Math.max(Math.max(enabled, selected * 0.82f), surface.hover * 0.85f)));
        statusRail(ui, titleX, card.y + 34f, visuallyEnabled, enabled, surface.hover);

        String badge = compactBadge(feature);
        float footerTop = card.bottom() - 38f;
        Rect footer = new Rect(card.x + Skin.S3, footerTop + 6f,
                card.width - Skin.S6, 26f);
        boolean configurable = feature.hasSettings() || !feature.keybinds().isEmpty();
        Rect options = configurable
                ? new Rect(footer.x, footer.y, Math.min(88f, Math.max(82f, footer.width * 0.44f)), footer.height)
                : new Rect(footer.x, footer.y, 0f, footer.height);
        Rect toggle = feature.switchable()
                ? footer.vcentreRight(0f, Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT)
                : new Rect(footer.right(), footer.y, 0f, footer.height);
        float implementationWidth = feature.switchable()
                ? 0f : Controls.badgeWidth(ui, feature.implementation().label());

        float metadataLeft = configurable ? options.right() + Skin.S2 : footer.x;
        float metadataRight = feature.switchable()
                ? toggle.x - Skin.S2 : footer.right() - implementationWidth - Skin.S2;
        float badgeWidth = badge.isEmpty() ? 0f : Controls.badgeWidth(ui, badge);
        boolean badgeInFooter = !badge.isEmpty() && metadataRight - metadataLeft >= badgeWidth;
        float descriptionRight = card.right() - Skin.S3;
        if (!badge.isEmpty() && !badgeInFooter) {
            float width = Math.min(badgeWidth, card.width * 0.42f);
            Rect metadata = new Rect(card.right() - Skin.S3 - width, card.y + 62f, width, 18f);
            controls.badge(ui, "mod.badge." + feature.id(), badge, metadata,
                    unmet(feature) ? Skin.TEXT_MUTED : Skin.WARN);
            descriptionRight = metadata.x - Skin.S2;
        }
        Rect description = new Rect(card.x + Skin.S3, card.y + 62f,
                Math.max(24f, descriptionRight - card.x - Skin.S3), 18f);
        ui.text(Text.fit(ui, feature.description(), Type.SMALL, description.width),
                description.x, Controls.baseline(ui, description, Type.SMALL), Type.SMALL,
                Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED,
                        enabled * 0.52f + surface.hover * 0.38f));

        if (badgeInFooter) {
            controls.badge(ui, "mod.badge." + feature.id(), badge,
                    new Rect(metadataLeft, footer.y, badgeWidth, footer.height),
                    unmet(feature) ? Skin.TEXT_MUTED : Skin.WARN);
        }
        if (configurable) {
            if (controls.recessedAction(ui, "mod.options." + feature.id(), options, "Options", true)) {
                openDrawer(context, feature);
            }
        }
        if (feature.switchable()) {
            if (controls.toggle(ui, "mod.toggle." + feature.id(), toggle, visuallyEnabled, true)) {
                context.setFeatureEnabled(feature, !feature.enabled());
            }
        } else {
            float width = implementationWidth;
            controls.badge(ui, "mod.implementation." + feature.id(), feature.implementation().label(),
                    footer.vcentreRight(0f, width, 20f), Skin.WARN);
        }

        ui.popTransform();

        // Clicking unused card space is an Options shortcut; action controls
        // above have separate bounds and therefore retain their own behavior.
        if (configurable && surface.clicked) {
            openDrawer(context, feature);
        }
    }

    /** Bottom action well, with only its owning lower corners rounded. */
    private static void cardFooterSurface(Renderer ui, Rect card) {
        float inset = 1f;
        float top = card.bottom() - 38f;
        float radius = Skin.innerRadius(Skin.R_CARD, card, inset);
        int face = Skin.scaleAlpha(Skin.BACKGROUND, 0.58f);
        if (ui instanceof net.fathom.optimizations.core.ui.render.FathomRenderer) {
            ((net.fathom.optimizations.core.ui.render.FathomRenderer) ui).rounded(
                    card.x + inset, top, card.right() - inset, card.bottom() - inset,
                    0f, 0f, radius, radius, face);
        } else {
            ui.rounded(card.x + inset, top, card.right() - inset, card.bottom() - inset,
                    Skin.R_MEDIUM, face);
        }
        ui.fill(card.x + Skin.S3, top, card.right() - Skin.S3, top + Skin.HAIRLINE,
                Skin.EDGE_RECESSED);
    }

    /** A compact state pill: a dot and a word, on a dark face. */
    private static void statusRail(Renderer ui, float x, float y,
            boolean on, float enabled, float hover) {
        String label = on ? "ENABLED" : "DISABLED";
        float width = ui.measure(label, Type.CAPTION) + 19f;
        Rect rail = Skin.pixelAligned(ui, new Rect(x, y, width, 16f));
        int face = on ? Skin.mix(Skin.RECESSED, Skin.BASE_RED, 0.16f) : Skin.RECESSED;
        ui.rounded(rail.x, rail.y, rail.right(), rail.bottom(), Skin.pill(rail), face);
        ui.outline(rail.x, rail.y, rail.right(), rail.bottom(), Skin.pill(rail),
                Skin.BORDER_STANDARD,
                on ? Skin.scaleAlpha(Skin.BASE_RED, 0.90f) : Skin.EDGE_RECESSED);
        ui.rounded(rail.x + 5f, rail.centreY() - 2f, rail.x + 9f, rail.centreY() + 2f, 2f,
                on ? Skin.scaleAlpha(Skin.HOT_RED, Math.max(0.56f, enabled)) : Skin.TEXT_DISABLED);
        ui.text(label, rail.x + 12f, Controls.baseline(ui, rail, Type.CAPTION), Type.CAPTION,
                on ? VisualEffects.hoverBrighten(Skin.HOT_RED, hover) : Skin.TEXT_DISABLED);
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

    private void openDrawer(HubContext context, FathomModule feature) {
        if (feature == open && !drawerClosing) {
            drawerClosing = true;
        } else {
            open = feature;
            drawerClosing = false;
            drawerOpenedAt = context.now();
        }
        drawerScroll.reset();
        context.keybindCapture().cancel();
        editing = null;
    }

    // -----------------------------------------------------------------------
    // The settings drawer
    // -----------------------------------------------------------------------

    private void settingsDrawer(HubContext context, Rect area, float shown) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        // Slides in from the right edge and fades. A drawer, not a curtain.
        Rect panel = area.offset((1f - shown) * 28f, 0f);

        ui.pushAlpha(shown);
        VisualEffects.overlayShadow(ui, panel, Skin.R_CARD);
        VisualEffects.layeredSurface(ui, panel, Skin.R_CARD, Skin.FLOATING, 0f);
        ui.outline(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_CARD,
                Skin.BORDER_STANDARD, Skin.mix(Skin.EDGE_ELEVATED, Skin.BASE_RED, 0.55f));

        Rect inner = panel.inset(Skin.S5, Skin.S5);
        Rect head = inner.top(52f);

        // Noted, not applied: the rest of this method draws from `open`, and
        // clearing it here left a null behind a control that had already been
        // hit -- which is a crash inside a render call, and was one.
        Rect close = head.vcentreRight(0f, ACTION, ACTION);
        boolean closing = controls.iconButton(ui, "drawer.close", close, Icon.CLOSE, false, true);

        float titleRight = close.x - Skin.S3;
        if (open.switchable()) {
            Rect toggle = head.vcentreRight(ACTION + Skin.S3, Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT);
            if (controls.toggle(ui, "drawer.enabled", toggle, open.enabled(), true)) {
                context.setFeatureEnabled(open, !open.enabled());
            }
            titleRight = toggle.x - Skin.S3;
        }

        Rect moduleIcon = new Rect(head.x, head.y, 36f, 36f);
        // The only light in the drawer: the selected module's header icon.
        VisualEffects.stateGlow(ui, moduleIcon, Skin.R_MEDIUM, shown);
        VisualEffects.layeredSurface(ui, moduleIcon, Skin.R_MEDIUM,
                Skin.mix(Skin.RECESSED, Skin.BASE_RED, 0.22f), 0f);
        ui.outline(moduleIcon.x, moduleIcon.y, moduleIcon.right(), moduleIcon.bottom(),
                Skin.R_MEDIUM, Skin.BORDER_STANDARD,
                Skin.scaleAlpha(Skin.ACTIVE_RED, shown));
        ui.icon(Icon.of(open.icon()), moduleIcon.centreX() - 11f,
                moduleIcon.centreY() - 11f, 22f, Skin.HOT_RED);
        ui.text(Text.fit(ui, open.name(), Type.SECTION, titleRight - head.x - 46f), head.x + 46f,
                head.y + ui.ascent(Type.SECTION), Type.SECTION, Skin.TEXT);
        ui.text(Text.fit(ui, "by " + open.owner(), Type.CAPTION, titleRight - head.x - 46f), head.x + 46f,
                head.y + 24f + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.TEXT_DISABLED);

        // Clear of the header's red rule, which sits on the header's last unit.
        Rect description = inner.belowTop(62f);
        List<String> lines = Text.wrap(ui, open.description(), Type.SMALL, description.width);
        float y = description.y;
        for (int index = 0; index < Math.min(3, lines.size()); index++) {
            ui.text(lines.get(index), description.x, y + ui.ascent(Type.SMALL), Type.SMALL, Skin.TEXT_FAINT);
            y += 17f;
        }
        y += Skin.S3;
        ModulePreview preview = ModulePreview.of(open);
        if (preview != null && inner.height > preview.height() + 200f) {
            // Only when there is room left for settings under it. A preview that
            // squeezed the rows it previews would be the wrong way round.
            preview.draw(ui, open, new Rect(inner.x, y, inner.width, preview.height()));
            y += preview.height() + Skin.S3;
        }
        Controls.separator(ui, inner.x, y, inner.width);
        y += Skin.S3;

        boolean resettable = open.changed();
        boolean hudEditable = hasHudWidget(context, open);
        float footer = resettable || hudEditable ? Skin.H_MD + Skin.S4 : 0f;
        Rect viewport = Rect.of(inner.x, y, inner.right(), inner.bottom() - footer);
        float contentHeight = (open.settings().size() + open.keybinds().size()) * SETTING_ROW
                + conflictLines(context) * CONFLICT_ROW;
        float offset = drawerScroll.offset(context.delta(), contentHeight, viewport.height);

        // The settings sit in a recessed well: floating drawer, sunken content,
        // raised controls -- three planes instead of rows painted on a slab.
        Rect well = Rect.of(viewport.x - Skin.S2, viewport.y - Skin.S2,
                viewport.right() + Skin.S2, viewport.bottom() + Skin.S2);
        VisualEffects.layeredSurface(ui, well, Skin.R_MEDIUM, Skin.fade(Skin.RECESSED, 0.78f), 0f);
        ui.gradient(well.x, well.y, well.right(), well.y + 14f, Skin.R_MEDIUM,
                0x66000000, 0x00000000);
        ui.outline(well.x, well.y, well.right(), well.bottom(), Skin.R_MEDIUM,
                Skin.BORDER_STANDARD, Skin.EDGE_RECESSED);

        ui.clip(viewport.x, viewport.y, viewport.right(), viewport.bottom());
        float rowY = viewport.y - offset;
        for (Setting setting : open.settings()) {
            Rect row = new Rect(viewport.x, rowY, viewport.width - Skin.S3, SETTING_ROW);
            if (row.bottom() >= viewport.y && row.y <= viewport.bottom()) {
                settingRow(context, setting, row);
            }
            rowY += SETTING_ROW;
        }
        for (Keybind keybind : open.keybinds()) {
            Rect row = new Rect(viewport.x, rowY, viewport.width - Skin.S3, SETTING_ROW);
            if (row.bottom() >= viewport.y && row.y <= viewport.bottom()) {
                keybindRow(context, keybind, row);
            }
            rowY += SETTING_ROW;
            String clash = conflict(context, keybind);
            if (!clash.isEmpty()) {
                ui.text(Text.fit(ui, clash, Type.CAPTION, viewport.width - Skin.S3), viewport.x,
                        rowY + ui.ascent(Type.CAPTION), Type.CAPTION, Skin.WARN);
                rowY += CONFLICT_ROW;
            }
        }
        VisualEffects.scrollEdgeFade(ui, viewport, offset > 0.5f,
                offset + viewport.height < contentHeight - 0.5f);
        ui.unclip();
        drawerScroll.rail(ui, viewport, context.pointer(), context.now());

        // Reset appears only once there is something to undo, so it is not a
        // permanent invitation to lose your settings.
        if (hudEditable) {
            Rect button = new Rect(inner.x, inner.bottom() - Skin.H_MD, 124f, Skin.H_MD);
            if (controls.secondary(ui, "drawer.hud", button, "HUD Editor", true)) {
                context.editHud();
            }
        }
        if (resettable) {
            Rect button = new Rect(inner.right() - 100f, inner.bottom() - Skin.H_MD, 100f, Skin.H_MD);
            if (controls.danger(ui, "drawer.reset", button, "Reset", true)) {
                open.resetToDefaults();
                edited(context, null);
            }
        }
        ui.popAlpha();

        if (closing) {
            drawerClosing = true;
            context.keybindCapture().cancel();
            editing = null;
        }
    }

    private void settingRow(HubContext context, Setting setting, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Rect row = area.inset(0f, 2f);
        boolean hovered = row.contains(context.pointer().x(), context.pointer().y());
        controls.rowBackground(ui, "setting.row." + open.id() + "." + setting.id(),
                row, hovered, false);

        float controlWidth = controlWidth(setting, row.width);

        ui.text(Text.fit(ui, setting.label(), Type.BODY, row.width - controlWidth - Skin.S4), row.x,
                Controls.baseline(ui, row, Type.BODY), Type.BODY, hovered ? Skin.TEXT : Skin.TEXT_MUTED);

        Rect control = row.vcentreRight(0f, controlWidth, controlHeight(setting));
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
                ui.textRight(slider.display(), control.x - Skin.S3, Controls.baseline(ui, row, Type.CAPTION),
                        Type.CAPTION, Skin.TEXT_FAINT);
                break;
            }
            case CHOICE: {
                Setting.Choice choice = (Setting.Choice) setting;
                String[] options = choice.options().toArray(new String[0]);
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
     * <p>Two things follow from a value changing in this drawer: the profile is
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
        if (!keybind.isBound()) {
            return "";
        }
        List<String> others = new ArrayList<String>();
        for (FathomModule module : context.features().all()) {
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

    /** How many extra rows the conflict notes need, for the scroll height. */
    private int conflictLines(HubContext context) {
        int lines = 0;
        for (Keybind keybind : open.keybinds()) {
            if (!conflict(context, keybind).isEmpty()) {
                lines++;
            }
        }
        for (Setting setting : open.settings()) {
            if (setting instanceof Setting.Key && !conflict(context, ((Setting.Key) setting).binding()).isEmpty()) {
                lines++;
            }
        }
        return lines;
    }

    private void keybindRow(HubContext context, Keybind keybind, Rect area) {
        Renderer ui = context.ui();
        Rect row = area.inset(0f, 2f);
        boolean hovered = row.contains(context.pointer().x(), context.pointer().y());
        boolean waiting = context.keybindCapture().capturing(keybind);
        context.controls().rowBackground(ui, "key.row." + open.id() + "." + keybind.id(),
                row, hovered, waiting);

        Rect control = row.vcentreRight(0f, 110f, Skin.H_SM);
        ui.text(Text.fit(ui, keybind.label(), Type.BODY, row.width - control.width - Skin.S4), row.x,
                Controls.baseline(ui, row, Type.BODY), Type.BODY, hovered ? Skin.TEXT : Skin.TEXT_MUTED);
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
            drawerScroll.wheel(notches);
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
        List<FathomModule> out = new ArrayList<FathomModule>();
        for (FathomModule feature : context.features().matching(context.search())) {
            if (filter == null || feature.category() == filter) {
                out.add(feature);
            }
        }
        return out;
    }

    private static float clamp(float value, float low, float high) {
        return value < low ? low : (value > high ? high : value);
    }
}
