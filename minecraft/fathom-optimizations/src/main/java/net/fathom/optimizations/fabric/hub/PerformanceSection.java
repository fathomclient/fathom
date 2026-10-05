package net.fathom.optimizations.fabric.hub;

import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.PerformanceLayer;
import net.fathom.optimizations.core.module.Setting;
import net.fathom.optimizations.core.registry.Optimization;
import net.fathom.optimizations.core.registry.Preset;
import net.fathom.optimizations.core.registry.SwitchBoard;
import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Box;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.perf.PerfLayer;
import net.fathom.optimizations.fabric.ui.FathomComponents;
import net.fathom.optimizations.fabric.ui.Skin;

/**
 * The Performance page: the same catalogue the launcher shows, live.
 *
 * <h2>The same settings, not a copy of them</h2>
 *
 * <p>This reads the registry the launcher reads and writes the switch board the
 * Mixins read. There is no second list and no second vocabulary — a change here
 * is the change the launcher's Performance page makes, and it takes effect on
 * the next frame because the Mixins consult the switch board every time.
 *
 * <h2>What "Restart required" means here</h2>
 *
 * <p>Almost nothing, and saying so is the point. Every game-side optimization
 * this module implements is read per frame, so all of them are live. The two JVM
 * flags are not: they are command-line arguments, fixed when the process
 * started. Those rows say so and are not switchable, rather than offering a
 * control that would silently do nothing until next time.
 *
 * <h2>The strip at the top</h2>
 *
 * <p>The frame cost of the thing you are looking at. A performance page that
 * cannot say what it costs to show you a performance page is asking to be taken
 * on faith.
 */
final class PerformanceSection implements Section {

    private static final float ROW = Skin.ROW_HEIGHT;
    private static final float METER = 68f;
    private static final float GROUP = 34f;

    private final Scroller scroller = new Scroller();
    private List<Row> rows;
    private List<Optimization> cachedEngineEntries;
    private final Box page = Box.column("performance.page");
    private final Box metersBox = Box.row("performance.meters").height(METER).gap(Skin.S3);
    private final Box[] meterBoxes = new Box[3];
    private final Box interfaceLabel = Box.column("performance.interface.label");
    private final Box interfaceBox = Box.column("performance.interface");
    private final Box presetLabel = Box.column("performance.preset.label");
    private final Box presetBox = Box.row("performance.preset").height(Skin.H_MD);
    private final Box listViewport = Box.stack("performance.list.viewport").grow(1f);
    private final Box listLayout = Box.column("performance.list").padding(Skin.S2).scroll(true);
    private final java.util.List<Box> layerBoxes = new java.util.ArrayList<Box>();
    private final java.util.List<Box> engineBoxes = new java.util.ArrayList<Box>();
    private Box engineHeading;

    PerformanceSection() {
        for (int index = 0; index < meterBoxes.length; index++) {
            meterBoxes[index] = Box.row("performance.meter." + index).grow(1f);
            metersBox.add(meterBoxes[index]);
        }
        page.add(metersBox);
        page.add(Box.column("performance.meters.after").height(Skin.S5));
        page.add(interfaceLabel);
        page.add(interfaceBox);
        page.add(Box.column("performance.interface.after").height(Skin.S5));
        page.add(presetLabel);
        page.add(presetBox);
        page.add(Box.column("performance.preset.after").height(Skin.S5));
        page.add(listViewport);
    }

    @Override
    public String title() {
        return "Performance";
    }

    @Override
    public Icon icon() {
        return Icon.GAUGE;
    }

    @Override
    public boolean searchable() {
        return false;
    }

    @Override
    public String subtitle() {
        return "Game optimizations and interface cost.";
    }

    @Override
    public void opened(HubContext context) {
        scroller.reset();
    }

    @Override
    public void render(HubContext context, Rect area) {
        Renderer ui = context.ui();
        SwitchBoard switches = Runtime.switches();
        if (switches == null) {
            ui.text("The performance module is not running.", area.x, area.y + ui.ascent(Type.BODY), Type.BODY,
                    Skin.WARN);
            return;
        }

        float labelHeight = ui.lineHeight(Type.LABEL) + Skin.S1;
        float interfaceLine = ui.lineHeight(Type.ROW_TITLE) + ui.lineHeight(Type.SMALL);
        interfaceLabel.height(labelHeight);
        interfaceBox.height(interfaceLine * 5f + Skin.S3 * 4f + Skin.S3 * 2f);
        presetLabel.height(labelHeight);
        page.layout(ui, area.x, area.y, area.width, area.height);
        meters(context, metersBox.rect());

        // Stacked on a cursor, on real line heights. Every group is a label,
        // then its control, then a gap to the next group — and each of those
        // three is one value from the spacing scale rather than an offset
        // somebody nudged until a screenshot looked right. The version this
        // replaces drew the Preset label sixteen units above a rectangle whose
        // own position had already been chosen, and the two collided.
        Controls.label(ui, "Interface", interfaceLabel.rect().x, interfaceLabel.rect().y);
        // A group of its own, as on Quick: its rows' descriptions need the
        // group's glass under them to hold over a bright sky.
        Rect interfaceGroup = interfaceBox.rect();
        FathomComponents.panel(ui, interfaceGroup);
        presentationRows(context, interfaceGroup.inset(Skin.CARD_PADDING, Skin.S3));
        Controls.label(ui, "Preset", presetLabel.rect().x, presetLabel.rect().y);
        Rect presetRow = presetBox.rect();
        presets(context, switches, presetRow);
        Rect body = listViewport.rect();
        List<Optimization> entries = cachedEngineEntries();
        List<Row> layerRows = layerRows();
        float layerHeight = 0f;
        for (int index = 0; index < layerRows.size(); index++) {
            layerHeight += layerRows.get(index).height();
        }
        float contentHeight = layerHeight + GROUP + entries.size() * ROW;
        // Every setting below the presets in one group of light glass, its
        // sub-headings inside it -- not rows loose on the background.
        FathomComponents.group(ui, body, Skin.R_CARD);
        syncListBoxes(layerRows, entries);
        float innerHeight = Math.max(0f, body.height - Skin.S2 * 2f);
        float offset = scroller.offset(context.delta(), contentHeight, innerHeight);
        listLayout.scrollTo(offset);
        listLayout.layout(ui, body.x, body.y, body.width, body.height);
        body = listLayout.content();

        ui.clip(body.x, body.y, body.right(), body.bottom());
        FathomModule module = PerfLayer.module();
        for (int index = 0; index < layerRows.size(); index++) {
            Row each = layerRows.get(index);
            Rect row = layerBoxes.get(index).rect();
            if (row.bottom() < body.y || row.y > body.bottom()) {
                continue;
            }
            each.draw(context, module, row);
        }
        Rect engineHeader = engineHeading.rect();
        if (engineHeader.bottom() >= body.y && engineHeader.y <= body.bottom()) {
            Controls.label(ui, "Engine optimizations", engineHeader.x + Skin.S3, engineHeader.y + Skin.S3);
        }
        for (int index = 0; index < entries.size(); index++) {
            Rect row = engineBoxes.get(index).rect();
            if (row.bottom() < body.y || row.y > body.bottom()) {
                continue;
            }
            row(context, switches, entries.get(index), row);
        }
        ui.unclip();
        scroller.rail(ui, body, context.pointer(), context.now());
    }

    private List<Optimization> cachedEngineEntries() {
        if (cachedEngineEntries == null) {
            cachedEngineEntries = engineEntries();
        }
        return cachedEngineEntries;
    }

    /** The catalogue is stable for the process; retain one box per row. */
    private void syncListBoxes(List<Row> layerRows, List<Optimization> entries) {
        if (!layerBoxes.isEmpty() || engineHeading != null) {
            return;
        }
        for (int index = 0; index < layerRows.size(); index++) {
            Box box = Box.row("performance.layer." + index).height(layerRows.get(index).height())
                    .padding(0f, 0f, Skin.S3, 0f);
            layerBoxes.add(box);
            listLayout.add(box);
        }
        engineHeading = Box.row("performance.engine.heading").height(GROUP)
                .padding(0f, 0f, Skin.S3, 0f);
        listLayout.add(engineHeading);
        for (int index = 0; index < entries.size(); index++) {
            Box box = Box.row("performance.engine." + entries.get(index).id()).height(ROW)
                    .padding(0f, 0f, Skin.S3, 0f);
            engineBoxes.add(box);
            listLayout.add(box);
        }
    }

    // -----------------------------------------------------------------------
    // Fathom's own performance layer
    // -----------------------------------------------------------------------

    /**
     * The layer's settings, grouped the way a player thinks about them: what is
     * being limited, then how far.
     *
     * <p>These are the module's own settings, drawn here rather than copied: the
     * same objects the Mods page shows and the settings file stores, so there is
     * one place a distance can be changed and one place it is kept.
     */
    private List<Row> layerRows() {
        if (rows == null) {
            List<Row> out = new ArrayList<Row>();
            out.add(Row.group("Entity rendering"));
            out.add(Row.setting(PerformanceLayer.ENTITY_PRESET));
            out.add(Row.setting(PerformanceLayer.PLAYER_DISTANCE));
            out.add(Row.setting(PerformanceLayer.MOB_DISTANCE));
            out.add(Row.setting(PerformanceLayer.ITEM_DISTANCE));
            out.add(Row.setting(PerformanceLayer.MISC_DISTANCE));
            out.add(Row.group("Nametags"));
            out.add(Row.setting(PerformanceLayer.FAST_NAMETAGS));
            out.add(Row.group("Tile entities"));
            out.add(Row.setting(PerformanceLayer.TILE_PRESET));
            out.add(Row.setting(PerformanceLayer.TILE_DISTANCE));
            out.add(Row.group("HUD"));
            out.add(Row.setting(PerformanceLayer.HUD_CACHE));
            out.add(Row.group("Advanced"));
            out.add(Row.setting(PerformanceLayer.STATS));
            rows = out;
        }
        return rows;
    }

    /** One line of the performance-layer block: a group heading, or a setting. */
    private static final class Row {
        private static final float HEADING = 34f;
        private static final float CONTROL = 46f;

        private final String heading;
        private final String settingId;
        private String[] choiceOptions;

        private Row(String heading, String settingId) {
            this.heading = heading;
            this.settingId = settingId;
        }

        static Row group(String heading) {
            return new Row(heading, null);
        }

        static Row setting(String settingId) {
            return new Row(null, settingId);
        }

        float height() {
            return heading != null ? HEADING : CONTROL;
        }

        void draw(HubContext context, FathomModule module, Rect area) {
            Renderer ui = context.ui();
            if (heading != null) {
                Controls.label(ui, heading, area.x + Skin.S3, area.y + Skin.S3);
                return;
            }
            if (module == null) {
                return;
            }
            Setting setting = module.setting(settingId);
            if (setting == null) {
                return;
            }
            Controls controls = context.controls();
            Rect row = area.inset(0f, 2f);
            boolean hovered = context.controls().pointerOverVisible(row);
            controls.rowBackground(ui, "perf.layer.row." + settingId, row, hovered, false);

            float controlWidth = setting.kind() == Setting.Kind.TOGGLE
                    ? Controls.TOGGLE_WIDTH
                    : Math.min(240f, Math.max(150f, row.width * 0.32f));
            float controlHeight = setting.kind() == Setting.Kind.TOGGLE
                    ? Controls.TOGGLE_HEIGHT : Skin.H_MD;
            Rect control = row.vcentreRight(Skin.S3, controlWidth, controlHeight);

            float room = control.x - row.x - Skin.S5;
            float top = row.centreY() - (ui.lineHeight(Type.ROW_TITLE) + ui.lineHeight(Type.SMALL)) / 2f;
            ui.text(Text.fit(ui, setting.label(), Type.ROW_TITLE, room), row.x + Skin.S3,
                    top + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE, Skin.TEXT);
            ui.text(Text.fit(ui, setting.description(), Type.SMALL, room), row.x + Skin.S3,
                    top + ui.lineHeight(Type.ROW_TITLE) + ui.ascent(Type.SMALL), Type.SMALL,
                    Skin.TEXT_FAINT);

            String id = "perf.layer." + settingId;
            switch (setting.kind()) {
                case TOGGLE: {
                    Setting.Toggle toggle = (Setting.Toggle) setting;
                    if (controls.toggle(ui, id, control, toggle.get(), true)) {
                        toggle.set(!toggle.get());
                        edited(context, module, setting);
                    }
                    break;
                }
                case CHOICE: {
                    Setting.Choice choice = (Setting.Choice) setting;
                    String[] options = choiceOptions;
                    if (options == null) {
                        options = choice.options().toArray(new String[choice.options().size()]);
                        choiceOptions = options;
                    }
                    int chosen = controls.dropdown(ui, id, control, options, choice.index());
                    if (chosen != choice.index() && chosen >= 0 && chosen < options.length) {
                        choice.set(options[chosen]);
                        edited(context, module, setting);
                    }
                    break;
                }
                case INTEGER: {
                    // A slider, not the stepper the Mods page uses: these are
                    // distances with a wide range, and somebody setting one is
                    // choosing roughly how far, not counting blocks.
                    Setting.Whole whole = (Setting.Whole) setting;
                    double span = Math.max(1, whole.maximum() - whole.minimum());
                    double fraction = (whole.get() - whole.minimum()) / span;
                    double next = controls.slider(ui, id, control, fraction, true);
                    if (Math.abs(next - fraction) > 0.0001d) {
                        whole.set((int) Math.round(whole.minimum() + next * span));
                        edited(context, module, setting);
                    }
                    ui.textRight(whole.display(), control.x - Skin.S3,
                            Controls.baseline(ui, row, Type.CAPTION), Type.CAPTION, Skin.TEXT_FAINT);
                    break;
                }
                default:
                    break;
            }
        }

        private static void edited(HubContext context, FathomModule module, Setting setting) {
            module.settingChanged(setting);
            context.markDirty();
        }
    }

    private void presentationRows(HubContext context, Rect area) {
        Renderer ui = context.ui();
        boolean on = context.hud().prefs().highQualityUi();
        float line = ui.lineHeight(Type.ROW_TITLE) + ui.lineHeight(Type.SMALL);
        float step = line + Skin.S3;
        Rect uiQuality = new Rect(area.x, area.y, area.width, line);
        float room = area.width - Controls.TOGGLE_WIDTH - Skin.S5;
        ui.text("Backdrop effects", uiQuality.x,
                uiQuality.y + ui.ascent(Type.ROW_TITLE),
                Type.ROW_TITLE, Skin.TEXT);
        ui.text(Text.fit(ui, "Blur and colour processing behind glass. Text and corners stay native-resolution.", Type.SMALL, room),
                uiQuality.x, uiQuality.y + ui.lineHeight(Type.ROW_TITLE) + ui.ascent(Type.SMALL),
                Type.SMALL, Skin.TEXT_FAINT);
        Rect toggle = uiQuality.vcentreRight(0f, Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT);
        if (context.controls().toggle(ui, "perf.ui-quality", toggle, on, true)) {
            context.hud().prefs().setHighQualityUi(!on);
            context.markDirty();
        }

        Rect mode = new Rect(area.x, area.y + step, area.width, line);
        float pickerWidth = Math.min(220f, Math.max(160f, area.width * 0.30f));
        Rect picker = mode.vcentreRight(0f, pickerWidth, Skin.H_MD);
        ui.text("RShift background", mode.x,
                mode.y + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE, Skin.TEXT);
        ui.text(Text.fit(ui, "Blur live gameplay in-world, or use the selected Fathom background.",
                        Type.SMALL, picker.x - mode.x - Skin.S5),
                mode.x, mode.y + ui.lineHeight(Type.ROW_TITLE) + ui.ascent(Type.SMALL),
                Type.SMALL, Skin.TEXT_FAINT);
        int background = context.hud().prefs().rshiftBackgroundChoice();
        int backgroundChosen = context.controls().dropdown(ui, "perf.rshift-background", picker,
                ClientPrefs.RSHIFT_BACKGROUND_CHOICES, background);
        if (backgroundChosen != background) {
            context.hud().prefs().setRshiftBackgroundChoice(backgroundChosen);
            context.markDirty();
        }

        Rect blur = new Rect(area.x, area.y + step * 2f, area.width, line);
        picker = blur.vcentreRight(0f, pickerWidth, Skin.H_MD);
        ui.text("Glass blur", blur.x, blur.y + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE, Skin.TEXT);
        ui.text(Text.fit(ui, "Three tuned GPU blur strengths; Balanced is the default.", Type.SMALL,
                        picker.x - blur.x - Skin.S5),
                blur.x, blur.y + ui.lineHeight(Type.ROW_TITLE) + ui.ascent(Type.SMALL),
                Type.SMALL, Skin.TEXT_FAINT);
        int blurChoice = context.hud().prefs().rshiftBlurChoice();
        int blurChosen = context.controls().dropdown(ui, "perf.rshift-blur", picker,
                ClientPrefs.RSHIFT_BLUR_CHOICES, blurChoice);
        if (blurChosen != blurChoice) {
            context.hud().prefs().setRshiftBlurChoice(blurChosen);
            context.markDirty();
        }

        Rect dim = new Rect(area.x, area.y + step * 3f, area.width, line);
        picker = dim.vcentreRight(0f, pickerWidth, Skin.H_MD);
        ui.text("Glass dim", dim.x, dim.y + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE, Skin.TEXT);
        ui.text(Text.fit(ui, "A restrained neutral tint keeps labels legible over bright scenes.", Type.SMALL,
                        picker.x - dim.x - Skin.S5),
                dim.x, dim.y + ui.lineHeight(Type.ROW_TITLE) + ui.ascent(Type.SMALL),
                Type.SMALL, Skin.TEXT_FAINT);
        int dimChoice = context.hud().prefs().rshiftDimChoice();
        int dimChosen = context.controls().dropdown(ui, "perf.rshift-dim", picker,
                ClientPrefs.RSHIFT_DIM_CHOICES, dimChoice);
        if (dimChosen != dimChoice) {
            context.hud().prefs().setRshiftDimChoice(dimChosen);
            context.markDirty();
        }

        Rect video = new Rect(area.x, area.y + step * 4f, area.width, line);
        picker = video.vcentreRight(0f, pickerWidth, Skin.H_MD);
        ui.text("Menu background quality", video.x,
                video.y + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE, Skin.TEXT);
        ui.text(Text.fit(ui, "Video decode quality; Fathom UI remains native-resolution.",
                        Type.SMALL, picker.x - video.x - Skin.S5),
                video.x, video.y + ui.lineHeight(Type.ROW_TITLE) + ui.ascent(Type.SMALL),
                Type.SMALL, Skin.TEXT_FAINT);
        int selected = context.hud().prefs().menuBackgroundChoice();
        int chosen = context.controls().dropdown(ui, "perf.menu-background", picker,
                ClientPrefs.MENU_BACKGROUND_CHOICES, selected);
        if (chosen != selected) {
            context.hud().prefs().setMenuBackgroundChoice(chosen);
            context.markDirty();
        }
    }

    /** Three numbers: what the game is running at, and what this menu costs. */
    private void meters(HubContext context, Rect area) {
        Gfx.Frames frames = Gfx.frames();
        meter(context, meterBoxes[0].rect(), "Frame rate",
                String.valueOf(Math.round(frames.fps())), "fps");
        meter(context, meterBoxes[1].rect(), "Frame time",
                millis(frames.frameMeanMillis()), "ms · p99 " + millis(frames.framePercentileMillis(0.99f)));
        meter(context, meterBoxes[2].rect(), "Fathom UI",
                millis(frames.uiMeanMillis()), "ms of that");
    }

    /** Live measurements: Geist Mono, so the digits hold still as they change. */
    private static final Type STAT = new Type(24f, Type.Weight.MONO_MEDIUM);

    private void meter(HubContext context, Rect area, String label, String value, String unit) {
        Renderer ui = context.ui();
        FathomComponents.card(ui, area, 0f, 0f);
        Controls.label(ui, label, area.x + Skin.S3, area.y + Skin.S3);
        float baseline = area.bottom() - Skin.S4;
        float width = ui.text(value, area.x + Skin.S3, baseline, STAT, Skin.TEXT);
        ui.text(Text.fit(ui, unit, Type.SMALL, area.width - width - Skin.S5), area.x + Skin.S3 + width + Skin.S2,
                baseline, Type.SMALL, Skin.TEXT_FAINT);
    }

    private static String millis(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(value));
    }

    private void presets(HubContext context, SwitchBoard switches, Rect area) {
        Preset[] presets = Preset.values();
        // The launcher's preset control: a segmented choice that says which
        // preset is in effect, and "Custom" once a switch has been changed by
        // hand. Choosing Custom changes nothing; it is where the board already is.
        Preset current = switches.matchingPreset();
        int selected = presets.length;
        for (int index = 0; index < presets.length; index++) {
            if (presets[index] == current) {
                selected = index;
            }
        }
        int chosen = context.controls().segmentedNatural(context.ui(), "presets", area, PRESET_LABELS, selected);
        if (chosen != selected && chosen >= 0 && chosen < presets.length) {
            switches.applyPreset(presets[chosen]);
            context.markDirty();
        }
    }

    private static final String[] PRESET_LABELS = presetLabels();

    private static String[] presetLabels() {
        Preset[] presets = Preset.values();
        String[] labels = new String[presets.length + 1];
        for (int index = 0; index < presets.length; index++) {
            labels[index] = presets[index].label();
        }
        labels[presets.length] = "Custom";
        return labels;
    }

    private void row(HubContext context, SwitchBoard switches, Optimization entry, Rect area) {
        Renderer ui = context.ui();
        Controls controls = context.controls();
        Rect row = area.inset(0f, 2f);
        boolean on = switches.isEnabled(entry.id());
        // `runsInGame`, not `implementedInCore`. The second means the logic
        // exists and has tests; only the first means a Mixin calls it, and
        // gating a switch on the wrong one is how four optimizations came to
        // offer a control that changed a boolean nothing read.
        boolean live = (entry.status() == Optimization.Status.RUNS_IN_GAME
                || entry.status() == Optimization.Status.IN_MODULE)
                && entry.isGameSide() && entry.control() == Optimization.Control.SWITCH;
        boolean jvm = !entry.isGameSide();
        boolean hovered = context.controls().pointerOverVisible(row);
        controls.rowBackground(ui, "perf." + entry.id(), row, hovered, false);

        float room;
        if (live) {
            Rect toggle = row.vcentreRight(Skin.S3, Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT);
            if (controls.toggle(ui, "perf.toggle." + entry.id(), toggle, on, true)) {
                switches.setByUser(entry.id(), !on);
                context.markDirty();
            }
            room = toggle.x - row.x - Skin.S5;
        } else {
            // A word instead of a control. "Restart required" for the JVM flags,
            // which are fixed for the life of the process; the readiness for
            // anything that is described and not written.
            String state = jvm
                    ? "Restart required"
                    : entry.implementedInCore() ? "Written, not hooked up" : "Planned";
            float badgeWidth = Controls.badgeWidth(ui, state);
            Rect badge = row.vcentreRight(Skin.S3, badgeWidth, 20f);
            controls.badge(ui, "perf.badge." + entry.id(), state, badge,
                    jvm ? Skin.WARN : Skin.TEXT_DISABLED);
            room = badge.x - row.x - Skin.S5;
            if (jvm && context.controls().pointerOverVisible(badge)
                    && context.pointer().released(0)) {
                context.needsRestart(entry.name());
            }
        }

        // Two lines centred on the row, on their own heights.
        float top = row.centreY()
                - (ui.lineHeight(Type.ROW_TITLE) + ui.lineHeight(Type.SMALL)) / 2f;
        ui.text(Text.fit(ui, entry.name(), Type.ROW_TITLE, room), row.x + Skin.S3,
                top + ui.ascent(Type.ROW_TITLE), Type.ROW_TITLE,
                live && on ? Skin.TEXT : Skin.TEXT_MUTED);
        // The claim, not the argument. The registry documents each entry at
        // length because that is where the reasoning belongs; the row has one
        // line, and a paragraph pushed into it is a paragraph nobody reads.
        ui.text(Text.fit(ui, Text.firstSentence(entry.description()), Type.SMALL, room),
                row.x + Skin.S3, top + ui.lineHeight(Type.ROW_TITLE) + ui.ascent(Type.SMALL),
                Type.SMALL, Skin.TEXT_DISABLED);
    }

    @Override
    public void scroll(int notches) {
        scroller.wheel(notches);
    }

    /**
     * The registry's entries that belong in the engine list. Entries set in the
     * performance layer's own settings are left out: they are already drawn
     * above as the layer's controls, and a second, registry switch for them
     * would be a switch that nothing reads.
     */
    static List<Optimization> engineEntries() {
        List<Optimization> out = new ArrayList<Optimization>();
        for (Optimization entry : Runtime.registry().all()) {
            if (entry.control() != Optimization.Control.IN_GAME) {
                out.add(entry);
            }
        }
        return out;
    }

    /** Every switchable entry, for the tests that check nothing unimplemented is. */
    static List<Optimization> switchable() {
        List<Optimization> out = new ArrayList<Optimization>();
        for (Optimization entry : Runtime.registry().all()) {
            if (entry.implementedInCore() && entry.isGameSide()
                    && entry.control() == Optimization.Control.SWITCH) {
                out.add(entry);
            }
        }
        return out;
    }
}
