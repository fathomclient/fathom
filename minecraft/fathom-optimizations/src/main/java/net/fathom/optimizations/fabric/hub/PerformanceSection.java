package net.fathom.optimizations.fabric.hub;

import java.util.ArrayList;
import java.util.List;

import net.fathom.optimizations.core.registry.Optimization;
import net.fathom.optimizations.core.registry.Preset;
import net.fathom.optimizations.core.registry.SwitchBoard;
import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.ui.Controls;
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

    private static final float ROW = 54f;
    private static final float METER = 68f;

    private final Scroller scroller = new Scroller();

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

        meters(context, area.top(METER));

        // Stacked on a cursor, on real line heights. Every group is a label,
        // then its control, then a gap to the next group — and each of those
        // three is one value from the spacing scale rather than an offset
        // somebody nudged until a screenshot looked right. The version this
        // replaces drew the Preset label sixteen units above a rectangle whose
        // own position had already been chosen, and the two collided.
        float cursor = area.y + METER + Skin.S5;
        float labelHeight = ui.lineHeight(Type.LABEL) + Skin.S1;

        Controls.label(ui, "Interface", area.x, cursor);
        cursor += labelHeight;
        float interfaceLine = ui.lineHeight(Type.ROW_TITLE) + ui.lineHeight(Type.SMALL);
        Rect interfaceRow = new Rect(area.x, cursor, area.width,
                interfaceLine * 2f + Skin.S3);
        qualityRow(context, interfaceRow);
        cursor = interfaceRow.bottom() + Skin.S5;

        Controls.label(ui, "Preset", area.x, cursor);
        cursor += labelHeight;
        Rect presetRow = new Rect(area.x, cursor, area.width, Skin.H_MD);
        presets(context, switches, presetRow);
        cursor = presetRow.bottom() + Skin.S5;

        Rect body = new Rect(area.x, cursor, area.width,
                Math.max(0f, area.bottom() - cursor));
        List<Optimization> entries = Runtime.registry().all();
        float contentHeight = entries.size() * ROW;
        float offset = scroller.offset(context.delta(), contentHeight, body.height);

        ui.clip(body.x, body.y, body.right(), body.bottom());
        for (int index = 0; index < entries.size(); index++) {
            Rect row = new Rect(body.x, body.y + index * ROW - offset, body.width - Skin.S3, ROW);
            if (row.bottom() < body.y || row.y > body.bottom()) {
                continue;
            }
            row(context, switches, entries.get(index), row);
        }
        ui.unclip();
        scroller.rail(ui, body, context.pointer(), context.now());
    }

    private void qualityRow(HubContext context, Rect area) {
        Renderer ui = context.ui();
        boolean on = context.hud().prefs().highQualityUi();
        float line = ui.lineHeight(Type.ROW_TITLE) + ui.lineHeight(Type.SMALL);
        Rect uiQuality = new Rect(area.x, area.y, area.width, line);
        float room = area.width - Controls.TOGGLE_WIDTH - Skin.S5;
        ui.text("High quality UI rendering", uiQuality.x,
                uiQuality.y + ui.ascent(Type.ROW_TITLE),
                Type.ROW_TITLE, Skin.TEXT);
        ui.text(Text.fit(ui, "Antialiased Fathom geometry and icons.", Type.SMALL, room),
                uiQuality.x, uiQuality.y + ui.lineHeight(Type.ROW_TITLE) + ui.ascent(Type.SMALL),
                Type.SMALL, Skin.TEXT_FAINT);
        Rect toggle = uiQuality.vcentreRight(0f, Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT);
        if (context.controls().toggle(ui, "perf.ui-quality", toggle, on, true)) {
            context.hud().prefs().setHighQualityUi(!on);
            context.markDirty();
        }

        Rect video = new Rect(area.x, area.y + line + Skin.S3, area.width, line);
        float pickerWidth = Math.min(220f, Math.max(160f, area.width * 0.24f));
        Rect picker = video.vcentreRight(0f, pickerWidth, Skin.H_MD);
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
        float gap = Skin.S3;
        float cell = (area.width - gap * 2f) / 3f;
        meter(context, new Rect(area.x, area.y, cell, area.height), "Frame rate",
                String.valueOf(Math.round(frames.fps())), "fps");
        meter(context, new Rect(area.x + cell + gap, area.y, cell, area.height), "Frame time",
                millis(frames.frameMeanMillis()), "ms · p99 " + millis(frames.framePercentileMillis(0.99f)));
        meter(context, new Rect(area.x + (cell + gap) * 2f, area.y, cell, area.height), "Fathom UI",
                millis(frames.uiMeanMillis()), "ms of that");
    }

    private void meter(HubContext context, Rect area, String label, String value, String unit) {
        Renderer ui = context.ui();
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD, Skin.FILL);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_CARD, Skin.HAIRLINE, Skin.EDGE);
        Controls.label(ui, label, area.x + Skin.S3, area.y + Skin.S3);
        float baseline = area.bottom() - Skin.S4;
        float width = ui.text(value, area.x + Skin.S3, baseline, Type.DISPLAY, Skin.TEXT);
        ui.text(Text.fit(ui, unit, Type.SMALL, area.width - width - Skin.S5), area.x + Skin.S3 + width + Skin.S2,
                baseline, Type.SMALL, Skin.TEXT_FAINT);
    }

    private static String millis(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(value));
    }

    private void presets(HubContext context, SwitchBoard switches, Rect area) {
        Preset[] presets = Preset.values();
        String[] labels = new String[presets.length];
        for (int index = 0; index < presets.length; index++) {
            labels[index] = presets[index].label();
        }
        // No preset is "selected": applying one sets a group of switches that
        // the user can then change individually, so the control reports the
        // action rather than a state. Buttons, not a segmented control.
        float cell = Math.min(190f, (area.width - Skin.S2 * (presets.length - 1)) / presets.length);
        for (int index = 0; index < presets.length; index++) {
            Rect button = new Rect(area.x + (cell + Skin.S2) * index, area.y, cell, area.height);
            if (button.right() > area.right()) {
                break;
            }
            if (context.controls().secondary(context.ui(), "preset." + index, button, labels[index], true)) {
                switches.applyPreset(presets[index]);
                context.markDirty();
            }
        }
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
        boolean live = entry.runsInGame() && entry.isGameSide();
        boolean jvm = !entry.isGameSide();
        boolean hovered = row.contains(context.pointer().x(), context.pointer().y());
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
            if (jvm && badge.contains(context.pointer().x(), context.pointer().y())
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

    /** Every switchable entry, for the tests that check nothing unimplemented is. */
    static List<Optimization> switchable() {
        List<Optimization> out = new ArrayList<Optimization>();
        for (Optimization entry : Runtime.registry().all()) {
            if (entry.implementedInCore() && entry.isGameSide()) {
                out.add(entry);
            }
        }
        return out;
    }
}
