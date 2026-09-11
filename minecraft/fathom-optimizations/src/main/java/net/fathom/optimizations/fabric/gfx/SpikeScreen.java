package net.fathom.optimizations.fabric.gfx;

import org.lwjgl.input.Keyboard;

import net.minecraft.client.gui.screen.Screen;

import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.Skin;

/**
 * The renderer spike, and the control gallery.
 *
 * <h2>Why it exists and why it stayed</h2>
 *
 * <p>The renderer brief asked for a comparison screen before any migration: the
 * launcher's font at several sizes, an icon, a rounded dark panel, a red button,
 * a toggle, a dropdown, a scrollable area, a hover transition and an open/close
 * transition — measured, and looked at, before the hub was moved onto the new
 * renderer.
 *
 * <p>It stayed because it is now two useful things at once: the fastest way to
 * see what a change to the text or shape pipeline did, and <b>every control in
 * the system on one screen</b>. A control that looks wrong here looks wrong
 * everywhere, and it is easier to notice on a page whose only job is to show
 * them side by side.
 *
 * <h2>What the numbers mean</h2>
 *
 * <ul>
 *   <li><b>Frame</b> — milliseconds between frames, mean and percentiles. The
 *       whole game, not just the interface.
 *   <li><b>Fathom</b> — milliseconds inside this renderer, from taking the GL
 *       state to putting it back.
 *   <li><b>Geometry</b> — quads and draw calls in the last completed frame.
 *   <li><b>Allocation</b> — heap growth across one frame. Approximate by
 *       construction: a collection in between reads as zero rather than as a
 *       negative number.
 * </ul>
 */
public final class SpikeScreen extends Screen implements FathomScreen {

    private static final String[] SEGMENTS = {"Balanced", "Competitive", "Max FPS"};
    private static final String[] OPTIONS = {"Auto", "Off", "On", "Only in menus", "Only in game"};
    private static final String SAMPLE = "The quick brown fox jumps over the lazy dog — 0123456789 · Fathom";

    private final Controls controls = new Controls();
    private final Pointer pointer = new Pointer();
    private final Motion opening = new Motion(0f, Skin.HUB);
    private final Motion panel = new Motion(1f, Skin.DRAWER);

    private boolean toggleOn = true;
    private boolean toggleOff;
    private boolean panelOpen = true;
    private boolean listening;
    private int segment;
    private int option;
    private int colour = 0xC4303C;
    private double slider = 0.62d;
    private float scroll;
    private long previousHeap;
    private long allocated;
    private boolean closing;

    public SpikeScreen() {}

    @Override
    public void init() {
        buttons.clear();
        Gfx.renderer(client);
        Gfx.restartClock();
        opening.to(1f, Gfx.clock().seconds());
        Gfx.frames().reset();
        pointer.reset();
        controls.reset();
        previousHeap = usedHeap();
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    @Override
    public void render(int ignoredX, int ignoredY, float ignoredDelta) {
        float delta = Gfx.begin(client, Runtime.uiScale());
        try {
            GlRenderer ui = Gfx.renderer(client);
            pointer.poll(client, ui);
            controls.begin(pointer, delta);
            long heap = usedHeap();
            allocated = Math.max(0L, heap - previousHeap);
            previousHeap = heap;

            draw(ui);
            controls.end();

            if (closing && opening.settled(Gfx.clock().seconds())) {
                if (!FathomNavigation.back(client, this)) {
                    throw new IllegalStateException("renderer spike has no navigation return target");
                }
            }
        } finally {
            Gfx.end();
        }
    }

    private void draw(GlRenderer ui) {
        float open = opening.value(Gfx.clock().seconds());
        ui.fill(0f, 0f, ui.width(), ui.height(), Skin.scaleAlpha(Skin.SCRIM, open));

        float width = Math.min(1100f, ui.width() - Skin.S8 * 2f);
        float height = Math.min(720f, ui.height() - Skin.S8 * 2f);
        Rect frame = new Rect((ui.width() - width) / 2f, (ui.height() - height) / 2f, width, height);

        ui.pushAlpha(open);
        ui.pushTransform(0f, (1f - open) * 14f, 0.985f + 0.015f * open, frame.centreX(), frame.centreY());

        ui.shadow(frame.x, frame.y, frame.right(), frame.bottom(), Skin.R_LG, 28f, Skin.SHADOW);
        ui.rounded(frame.x, frame.y, frame.right(), frame.bottom(), Skin.R_LG, Skin.PANEL);
        ui.outline(frame.x, frame.y, frame.right(), frame.bottom(), Skin.R_LG, Skin.HAIRLINE, Skin.EDGE_STRONG);

        Rect inner = frame.inset(Skin.S6, Skin.S6);
        ui.text("Renderer spike", inner.x, inner.y + ui.ascent(Type.SECTION), Type.SECTION, Skin.TEXT);
        ui.textRight("Escape to go back", inner.right(), inner.y + ui.ascent(Type.SMALL), Type.SMALL,
                Skin.TEXT_DISABLED);

        float y = inner.y + 34f;
        measurements(ui, new Rect(inner.x, y, inner.width, 72f));
        y += 72f + Skin.S5;

        y = type(ui, new Rect(inner.x, y, inner.width, 0f));
        y += Skin.S4;

        Rect columns = Rect.of(inner.x, y, inner.right(), inner.bottom());
        float columnWidth = (columns.width - Skin.S6) / 2f;
        gallery(ui, new Rect(columns.x, columns.y, columnWidth, columns.height));
        scroller(ui, new Rect(columns.x + columnWidth + Skin.S6, columns.y, columnWidth, columns.height));

        controls.drawOverlays(ui);

        ui.popTransform();
        ui.popAlpha();
    }

    private void measurements(GlRenderer ui, Rect area) {
        Gfx.Frames frames = Gfx.frames();
        GlRenderer.Stats stats = ui.stats();
        String[][] cells = {
            {"Frame", millis(frames.frameMeanMillis()) + " ms",
                "p95 " + millis(frames.framePercentileMillis(0.95f))
                        + "  p99 " + millis(frames.framePercentileMillis(0.99f))},
            {"Fathom", millis(frames.uiMeanMillis()) + " ms",
                "p99 " + millis(frames.uiPercentileMillis(0.99f)) + " of that"},
            {"Geometry", stats.lastQuads + " quads", stats.lastDraws + " draw calls"},
            {"Allocation", kilobytes(allocated), "per frame, approximate"},
        };
        float gap = Skin.S3;
        float cell = (area.width - gap * (cells.length - 1)) / cells.length;
        for (int index = 0; index < cells.length; index++) {
            Rect box = new Rect(area.x + (cell + gap) * index, area.y, cell, area.height);
            ui.rounded(box.x, box.y, box.right(), box.bottom(), Skin.R_SM, Skin.FILL);
            ui.outline(box.x, box.y, box.right(), box.bottom(), Skin.R_SM, Skin.HAIRLINE, Skin.EDGE);
            Controls.label(ui, cells[index][0], box.x + Skin.S3, box.y + Skin.S3);
            ui.text(cells[index][1], box.x + Skin.S3, box.y + 48f, Type.TITLE, Skin.TEXT);
            ui.text(Text.fit(ui, cells[index][2], Type.CAPTION, box.width - Skin.S3 * 2f), box.x + Skin.S3,
                    box.y + 64f, Type.CAPTION, Skin.TEXT_FAINT);
        }
    }

    private float type(GlRenderer ui, Rect area) {
        Controls.label(ui, "Type — Geist, at the launcher's sizes", area.x, area.y);
        float y = area.y + 22f;
        Type[] scale = {Type.CAPTION, Type.SMALL, Type.BODY, Type.ROW_TITLE, Type.SECTION};
        for (Type type : scale) {
            String prefix = Math.round(type.size()) + "  ";
            ui.text(prefix + Text.fit(ui, SAMPLE, type, area.width - 40f), area.x, y + ui.ascent(type), type,
                    type == Type.SECTION ? Skin.TEXT : Skin.TEXT_MUTED);
            y += ui.lineHeight(type) + 2f;
        }
        return y;
    }

    /** Every control in the system, in the order somebody would meet them. */
    private void gallery(GlRenderer ui, Rect area) {
        Controls.label(ui, "Controls", area.x, area.y);
        float y = area.y + 22f;

        Rect row = new Rect(area.x, y, area.width, Skin.H_MD);
        float third = (row.width - Skin.S2 * 2f) / 3f;
        controls.primary(ui, "spike.primary", new Rect(row.x, row.y, third, Skin.H_MD), "Primary", true);
        controls.secondary(ui, "spike.secondary", new Rect(row.x + third + Skin.S2, row.y, third, Skin.H_MD),
                "Secondary", true);
        controls.ghost(ui, "spike.ghost", new Rect(row.x + (third + Skin.S2) * 2f, row.y, third, Skin.H_MD),
                "Ghost", true);
        y += Skin.H_MD + Skin.S2;

        row = new Rect(area.x, y, area.width, Skin.H_MD);
        controls.danger(ui, "spike.danger", new Rect(row.x, row.y, third, Skin.H_MD), "Danger", true);
        controls.secondary(ui, "spike.disabled", new Rect(row.x + third + Skin.S2, row.y, third, Skin.H_MD),
                "Disabled", false);
        controls.iconButton(ui, "spike.icon",
                new Rect(row.x + (third + Skin.S2) * 2f, row.y, Controls.ICON_BUTTON, Controls.ICON_BUTTON),
                Icon.GAUGE, false, true);
        controls.iconButton(ui, "spike.icon.on",
                new Rect(row.x + (third + Skin.S2) * 2f + Controls.ICON_BUTTON + Skin.S2, row.y,
                        Controls.ICON_BUTTON, Controls.ICON_BUTTON),
                Icon.STAR_FILLED, true, true);
        y += Skin.H_MD + Skin.S4;

        row = new Rect(area.x, y, area.width, Controls.TOGGLE_HEIGHT);
        ui.text("Toggle", row.x, Controls.baseline(ui, row, Type.BODY), Type.BODY, Skin.TEXT_MUTED);
        if (controls.toggle(ui, "spike.toggle.on",
                row.vcentreRight(Skin.S8 + Controls.TOGGLE_WIDTH, Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT),
                toggleOn, true)) {
            toggleOn = !toggleOn;
        }
        if (controls.toggle(ui, "spike.toggle.off",
                row.vcentreRight(0f, Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT), toggleOff, true)) {
            toggleOff = !toggleOff;
        }
        y += Controls.TOGGLE_HEIGHT + Skin.S3;

        row = new Rect(area.x, y, area.width, Skin.H_SM);
        segment = controls.segmented(ui, "spike.segment", row, SEGMENTS, segment);
        y += Skin.H_SM + Skin.S3;

        row = new Rect(area.x, y, area.width, Skin.H_SM);
        ui.text("Dropdown", row.x, Controls.baseline(ui, row, Type.BODY), Type.BODY, Skin.TEXT_MUTED);
        option = controls.dropdown(ui, "spike.dropdown", row.vcentreRight(0f, 180f, Skin.H_SM), OPTIONS, option);
        y += Skin.H_SM + Skin.S3;

        row = new Rect(area.x, y, area.width, Controls.SLIDER_HEIGHT);
        ui.text("Slider", row.x, Controls.baseline(ui, row, Type.BODY), Type.BODY, Skin.TEXT_MUTED);
        slider = controls.slider(ui, "spike.slider", row.vcentreRight(0f, 200f, Controls.SLIDER_HEIGHT), slider, true);
        y += Controls.SLIDER_HEIGHT + Skin.S3;

        row = new Rect(area.x, y, area.width, Skin.H_SM);
        ui.text("Keybind", row.x, Controls.baseline(ui, row, Type.BODY), Type.BODY, Skin.TEXT_MUTED);
        if (controls.keybind(ui, "spike.keybind", row.vcentreRight(0f, 140f, Skin.H_SM), "Right Shift", listening)) {
            listening = !listening;
        }
        y += Skin.H_SM + Skin.S3;

        row = new Rect(area.x, y, area.width, Controls.SWATCH);
        ui.text("Colour", row.x, Controls.baseline(ui, row, Type.BODY), Type.BODY, Skin.TEXT_MUTED);
        colour = controls.colour(ui, "spike.colour", row.vcentreRight(0f, Controls.SWATCH, Controls.SWATCH), colour);
        y += Controls.SWATCH + Skin.S4;

        // The open/close transition, on something big enough to see.
        panel.to(panelOpen ? 1f : 0f, Gfx.clock().seconds());
        Rect box = Rect.of(area.x, y, area.right(), area.bottom());
        if (box.height < 40f) {
            return;
        }
        Controls.State s = controls.state("spike.panel", box, true);
        if (s.clicked) {
            panelOpen = !panelOpen;
        }
        float shown = panel.value(Gfx.clock().seconds());
        float grown = Skin.H_MD + (box.height - Skin.H_MD) * shown;
        ui.rounded(box.x, box.y, box.right(), box.y + grown, Skin.R_MD, Skin.RAISED);
        ui.outline(box.x, box.y, box.right(), box.y + grown, Skin.R_MD, Skin.HAIRLINE, Skin.EDGE);
        ui.text(panelOpen ? "Click to close" : "Click to open", box.x + Skin.S3, box.y + Skin.H_MD / 2f + 4f,
                Type.BODY, Skin.TEXT_MUTED);
        if (shown > 0.3f) {
            ui.pushAlpha((shown - 0.3f) / 0.7f);
            ui.gradient(box.x + Skin.S3, box.y + Skin.H_MD, box.right() - Skin.S3, box.y + Skin.H_MD + 44f,
                    Skin.R_SM, Skin.ACCENT, Skin.ACCENT_DEEP);
            ui.textCentred("Gradient, rounded, anti-aliased", box.centreX(), box.y + Skin.H_MD + 28f,
                    Type.BODY_STRONG, Skin.TEXT);
            float[] radii = {0f, 4f, 10f, 20f};
            float slot = (box.width - Skin.S3 * 2f) / radii.length;
            for (int index = 0; index < radii.length; index++) {
                float left = box.x + Skin.S3 + slot * index;
                float top = box.y + Skin.H_MD + 56f;
                float size = Math.min(44f, slot - Skin.S3);
                ui.rounded(left, top, left + size, top + size, radii[index], Skin.FILL_ACTIVE);
                ui.outline(left, top, left + size, top + size, radii[index], Skin.HAIRLINE, Skin.EDGE_STRONG);
                ui.textCentred("r" + Math.round(radii[index]), left + size / 2f, top + size + 14f, Type.CAPTION,
                        Skin.TEXT_FAINT);
            }
            ui.popAlpha();
        }
    }

    /** A scrollable area, clipped, with a rail and per-row hover. */
    private void scroller(GlRenderer ui, Rect area) {
        Controls.label(ui, "Scroll, clip and hover", area.x, area.y);
        Rect list = area.belowTop(22f);
        float rowHeight = 34f;
        int rows = 26;
        float contentHeight = rows * rowHeight;

        int notches = pointer.wheelNotches();
        if (notches != 0 && list.contains(pointer.x(), pointer.y())) {
            scroll -= notches * 60f;
        }
        scroll = Math.max(0f, Math.min(scroll, Math.max(0f, contentHeight - list.height)));

        ui.rounded(list.x, list.y, list.right(), list.bottom(), Skin.R_SM, Skin.FILL_SUNKEN);
        ui.clip(list.x, list.y, list.right(), list.bottom());
        for (int index = 0; index < rows; index++) {
            Rect row = new Rect(list.x + 4f, list.y + index * rowHeight - scroll, list.width - 8f, rowHeight - 2f);
            if (row.bottom() < list.y || row.y > list.bottom()) {
                continue;
            }
            boolean hovered = row.contains(pointer.x(), pointer.y());
            controls.rowBackground(ui, "spike.row." + index, row, hovered, false);
            Icon icon = Icon.values()[index % Icon.values().length];
            ui.icon(icon, row.x + Skin.S3, row.centreY() - 9f, 18f, hovered ? Skin.ACCENT : Skin.TEXT_DISABLED);
            ui.text("Row " + (index + 1) + " — " + icon.name().toLowerCase(java.util.Locale.ROOT),
                    row.x + Skin.S3 + 26f, Controls.baseline(ui, row, Type.BODY), Type.BODY,
                    hovered ? Skin.TEXT : Skin.TEXT_MUTED);
        }
        ui.unclip();

        if (contentHeight > list.height) {
            float trackX = list.right() - 6f;
            ui.rounded(trackX, list.y, trackX + 4f, list.bottom(), 2f, Skin.argb(0.18f, 0x000000));
            float handle = Math.max(32f, list.height * list.height / contentHeight);
            float progress = scroll / Math.max(1f, contentHeight - list.height);
            float handleY = list.y + (list.height - handle) * progress;
            ui.rounded(trackX, handleY, trackX + 4f, handleY + handle, 2f, Skin.argb(0.28f, 0xFFFFFF));
        }
    }

    private static long usedHeap() {
        java.lang.Runtime jvm = java.lang.Runtime.getRuntime();
        return jvm.totalMemory() - jvm.freeMemory();
    }

    private static String millis(float value) {
        return String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(value));
    }

    private static String kilobytes(long bytes) {
        return String.format(java.util.Locale.ROOT, "%.1f KB", Double.valueOf(bytes / 1024d));
    }

    @Override
    protected void mouseClicked(int mouseX, int mouseY, int button) {
        // Input is polled per frame, like every other Fathom screen.
    }

    @Override
    protected void mouseReleased(int mouseX, int mouseY, int button) {
        // As above.
    }

    @Override
    public void handleMouse() {
        // As above.
    }

    @Override
    public boolean handleFathomKey(char character, int keyCode) {
        if (listening && keyCode != Keyboard.KEY_ESCAPE) {
            listening = false;
            return true;
        }
        if (keyCode == Keyboard.KEY_ESCAPE) {
            if (controls.popoverOpen()) {
                controls.closePopover();
                return true;
            }
            closing = true;
            opening.to(0f, Gfx.clock().seconds());
            return true;
        }
        return false;
    }

    @Override
    protected void keyPressed(char character, int keyCode) {
        // FrameKeyboard drains this screen's events every rendered frame.
    }
}
