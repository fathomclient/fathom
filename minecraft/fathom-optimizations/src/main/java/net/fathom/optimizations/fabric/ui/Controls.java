package net.fathom.optimizations.fabric.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.fabric.gfx.Gfx;

/**
 * Fathom's controls.
 *
 * <h2>Immediate mode, and why it fixed a class of bug</h2>
 *
 * <p>A control here is a method that <em>draws itself and answers whether it
 * was used</em>:
 *
 * <pre>
 *   if (controls.primary(ui, "hud.edit", buttonRect, "Edit HUD")) {
 *       context.editHud();
 *   }
 * </pre>
 *
 * <p>The version this replaces had two methods per control — one that drew it
 * from a rectangle and one that hit-tested a rectangle computed again, by hand,
 * in a different method. Every arithmetic divergence between those two copies
 * was a control that lit up in one place and responded in another, and the
 * sections were full of them. There is now one rectangle, computed once.
 *
 * <h2>The five states</h2>
 *
 * <ul>
 *   <li><b>Idle</b> — the resting surface.
 *   <li><b>Hover</b> — the pointer is over it. Animated, but over 120 ms, so it
 *       is immediate to the eye and not a flicker. Hover is computed from a
 *       mouse polled this frame, not from a 20 Hz tick.
 *   <li><b>Pressed</b> — the button went down over it and has not come up. The
 *       click fires on release <em>over the same control</em>, which is what
 *       every real button does and what lets somebody change their mind by
 *       sliding off.
 *   <li><b>Disabled</b> — drawn dimmer, never hovers, never fires.
 *   <li><b>Focused</b> — has the keyboard. Wears a ring in the accent.
 * </ul>
 *
 * <h2>State by id</h2>
 *
 * <p>The animations live here, keyed by a string id, because a list rebuilds its
 * rows every frame and the hover on row four has to survive that. Ids are
 * hierarchical strings — {@code "mod.toggle.fps"} — so two lists cannot collide.
 */
public final class Controls {

    // -----------------------------------------------------------------------
    // Geometry the callers need to lay out around
    // -----------------------------------------------------------------------

    public static final float TOGGLE_WIDTH = 46f;
    public static final float TOGGLE_HEIGHT = 26f;
    public static final float SWATCH = 26f;
    public static final float SLIDER_HEIGHT = 20f;
    public static final float ICON_BUTTON = 30f;

    // -----------------------------------------------------------------------
    // Frame state
    // -----------------------------------------------------------------------

    private final Map<String, Motion> motions = new HashMap<String, Motion>();
    private final Map<String, Boolean> logicalHover = new HashMap<String, Boolean>();
    /** Horizontal offset of each segmented row that is too wide for its box. */
    private final Map<String, Float> pans = new HashMap<String, Float>();
    private final List<Overlay> overlays = new ArrayList<Overlay>();

    private Pointer pointer;
    private float delta;
    private float now;

    /** The control the pointer went down on, or null. */
    private String pressedId;
    /** The control with the keyboard, or null. */
    private String focusedId;
    /** The dropdown or palette currently showing a popover, or null. */
    private String openId;
    /** What that popover returned, consumed by the control that owns it. */
    private String choiceId;
    private int choiceIndex = -1;

    /** True while an overlay is on top, so the controls under it stop responding. */
    private boolean overlayBlocking;

    /**
     * A region that swallows hover for anything drawn under it, or null.
     *
     * <p>For a floating panel that is drawn <em>after</em> the page it covers —
     * the hub's label tray is the case this exists for. Immediate mode means the
     * page has already hit-tested itself by the time the tray draws, so without
     * this the rows beneath an open tray light up through it. Set the shield
     * before the covered content draws and clear it before the panel itself
     * does.
     */
    private Rect shield;

    /** A popover waiting to be drawn on top of everything. */
    private static final class Overlay {
        final String id;
        final Rect anchor;
        final String[] options;
        final int selected;
        final boolean swatches;

        Overlay(String id, Rect anchor, String[] options, int selected, boolean swatches) {
            this.id = id;
            this.anchor = anchor;
            this.options = options;
            this.selected = selected;
            this.swatches = swatches;
        }
    }

    /**
     * Start a frame.
     *
     * <p>Takes one shared monotonic timestamp for the frame. Controls retarget
     * before sampling that timestamp, so a hit-test made this frame is visible
     * this frame and no hover waits in an animation queue.
     */
    public void begin(Pointer mouse, float seconds) {
        this.pointer = mouse;
        this.delta = seconds;
        this.now = Gfx.clock().seconds();
        this.shield = null;
        overlays.clear();
    }

    /**
     * End a frame.
     *
     * <p>The press is released <em>here</em>, not in {@link #begin}: a control
     * decides it was clicked by seeing the release while it still knows the
     * press belonged to it. Clearing that at the top of the frame would mean no
     * control ever saw a click — which is exactly what happened the first time
     * this was written the other way round.
     */
    public void end() {
        if (pointer != null && !pointer.down(0)) {
            pressedId = null;
        }
    }

    /** Forget every animation and every focus. For a screen that has just opened. */
    public void reset() {
        motions.clear();
        logicalHover.clear();
        pans.clear();
        shield = null;
        pressedId = null;
        focusedId = null;
        openId = null;
        choiceId = null;
        choiceIndex = -1;
        overlays.clear();
    }

    public int animationCount() {
        return motions.size();
    }

    public float delta() {
        return delta;
    }

    /** Give a control the keyboard. Used by the search box. */
    public void focus(String id) {
        focusedId = id;
    }

    public boolean focused(String id) {
        return id != null && id.equals(focusedId);
    }

    public void clearFocus() {
        focusedId = null;
    }

    /**
     * Swallow hover inside this region for everything drawn until it is cleared.
     *
     * <p>Only the pointer is affected. A control under the shield keeps its
     * animation state, so a row that was fading in when the tray opened over it
     * fades back out rather than snapping.
     */
    public void shield(Rect region) {
        this.shield = region;
    }

    public void clearShield() {
        this.shield = null;
    }

    /** Whether a popover is open, so a screen can send Escape to it first. */
    public boolean popoverOpen() {
        return openId != null;
    }

    public void closePopover() {
        openId = null;
    }

    // -----------------------------------------------------------------------
    // The interaction primitive every control is built on
    // -----------------------------------------------------------------------

    /**
     * Hover, press and click for a rectangle.
     *
     * <p>The one place that decides what a pointer is doing to a control, so
     * every control agrees about it.
     */
    public final class State {
        public final boolean hovered;
        public final boolean pressed;
        public final boolean clicked;
        public final boolean enabled;
        public final boolean focused;
        /** 0 to 1, eased: hover for animating. */
        public final float hover;
        /** 0 to 1, eased: press for animating. */
        public final float press;

        State(String id, Rect area, boolean isEnabled) {
            boolean over = isEnabled && !overlayBlocking && pointer != null
                    && area.contains(pointer.x(), pointer.y())
                    && (shield == null || !shield.contains(pointer.x(), pointer.y()));
            Boolean before = logicalHover.put(id, Boolean.valueOf(over));
            // A control appearing under the cursor is a hover change too: it had
            // no state a frame ago and it has one now.
            if (before == null ? over : before.booleanValue() != over) {
                HoverLatency.stateChanged(id, pointer == null ? 0L : pointer.pointerEventNs(),
                        Gfx.clock().frames());
            }
            this.enabled = isEnabled;
            this.hovered = over;
            this.focused = focused(id);
            if (over && pointer.pressed(0)) {
                pressedId = id;
                focusedId = id;
            }
            this.pressed = id.equals(pressedId) && over;
            // Fires on release over the control it went down on. Sliding off
            // and letting go is how somebody changes their mind.
            this.clicked = over && pointer != null && pointer.released(0) && id.equals(pressedId);
            this.hover = towards(id + "#h", over, Skin.HOVER);
            this.press = towards(id + "#p", this.pressed, Skin.PRESS);
        }
    }

    /** The interaction state of a rectangle, without drawing anything. */
    public State state(String id, Rect area, boolean enabled) {
        return new State(id, area, enabled);
    }

    /** The eased 0..1 for a state something is in or heading towards. */
    public float towards(String id, boolean on, float duration) {
        Motion motion = motions.get(id);
        if (motion == null) {
            motion = new Motion(on ? 1f : 0f, duration);
            motions.put(id, motion);
        }
        motion.to(on ? 1f : 0f, now);
        return motion.value(now);
    }

    // -----------------------------------------------------------------------
    // Buttons
    // -----------------------------------------------------------------------

    /** The one action a panel is for. Filled, accented. */
    public boolean primary(Renderer ui, String id, Rect area, String label, boolean enabled) {
        State s = state(id, area, enabled);
        int face = !enabled
                ? Skin.FILL_DISABLED
                : Skin.mix(Skin.mix(Skin.ACCENT, Skin.ACCENT_STRONG, s.hover), Skin.ACCENT_DEEP, s.press);
        button(ui, id, area, face, enabled ? Skin.TEXT : Skin.TEXT_DISABLED, label, s, false);
        return s.clicked;
    }

    /** Everything else with a box around it. */
    public boolean secondary(Renderer ui, String id, Rect area, String label, boolean enabled) {
        State s = state(id, area, enabled);
        int face = !enabled
                ? Skin.FILL_DISABLED
                : Skin.mix(Skin.mix(Skin.FILL, Skin.FILL_HOVER, s.hover), Skin.FILL_PRESSED, s.press);
        int text = !enabled ? Skin.TEXT_DISABLED : Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, s.hover);
        button(ui, id, area, face, text, label, s, true);
        return s.clicked;
    }

    /** No box until you touch it. For actions that should not shout. */
    public boolean ghost(Renderer ui, String id, Rect area, String label, boolean enabled) {
        State s = state(id, area, enabled);
        int face = Skin.mix(Skin.mix(0x00000000, Skin.FILL_HOVER, s.hover), Skin.FILL_PRESSED, s.press);
        int text = !enabled ? Skin.TEXT_DISABLED : Skin.mix(Skin.TEXT_FAINT, Skin.TEXT, s.hover);
        button(ui, id, area, face, text, label, s, false);
        return s.clicked;
    }

    /** Something that destroys work. Quiet until hovered, then unmistakable. */
    public boolean danger(Renderer ui, String id, Rect area, String label, boolean enabled) {
        State s = state(id, area, enabled);
        int face = !enabled
                ? Skin.FILL_DISABLED
                : Skin.mix(Skin.BAD_WASH, Skin.fade(Skin.BAD, 0.3f), Math.max(s.hover, s.press));
        int text = !enabled ? Skin.TEXT_DISABLED : Skin.BAD;
        button(ui, id, area, face, text, label, s, true);
        return s.clicked;
    }

    /** An icon in a square. For a control whose meaning is its shape. */
    public boolean iconButton(Renderer ui, String id, Rect area, Icon icon, boolean active, boolean enabled) {
        State s = state(id, area, enabled);
        float on = towards(id + "#a", active, Skin.SELECTION);
        int face = Skin.mix(Skin.mix(0x00000000, Skin.FILL_HOVER, s.hover), Skin.FILL_PRESSED, s.press);
        if (on > 0.01f) {
            face = Skin.mix(face, Skin.ACCENT_WASH, on);
        }
        // An icon in a circle, not an icon in a rounded square: the launcher
        // draws its icon-only controls at `--r-pill`, and a square one beside
        // them reads as a different family of control.
        float round = Math.min(area.width, area.height) / 2f;
        ui.rounded(area.x, area.y, area.right(), area.bottom(), round, face);
        focusRing(ui, id, area, s, round);
        float size = Math.min(area.width, area.height) * 0.56f;
        int tint = !enabled
                ? Skin.TEXT_DISABLED
                : Skin.mix(Skin.mix(Skin.TEXT_FAINT, Skin.TEXT, s.hover), Skin.ACCENT_STRONG, on);
        ui.icon(icon, area.centreX() - size / 2f, area.centreY() - size / 2f, size, tint);
        return s.clicked;
    }

    private void button(
            Renderer ui, String id, Rect area, int face, int text, String label, State s, boolean outlined) {
        // A pressed button sinks one unit, which is what `.btn:active` does in
        // the launcher: `transform: translateY(1px)`. It was half a unit here,
        // which is below the threshold where a pointer feels an answer.
        float sink = s.press;
        // `--r-sm`, the radius every button in the launcher is drawn at.
        ui.rounded(area.x, area.y + sink, area.right(), area.bottom() + sink, Skin.R_SM, face);
        if (outlined) {
            ui.outline(area.x, area.y + sink, area.right(), area.bottom() + sink, Skin.R_SM, Skin.HAIRLINE, Skin.EDGE);
        }
        focusRing(ui, id, area, s, Skin.R_SM);
        ui.textCentred(
                Text.fit(ui, label, Type.BODY_STRONG, area.width - Skin.S3),
                area.centreX(),
                baseline(ui, area, Type.BODY_STRONG) + sink,
                Type.BODY_STRONG,
                text);
    }

    /** The ring a keyboard-focused control wears, and nothing else does. */
    private void focusRing(Renderer ui, String id, Rect area, State s, float radius) {
        float focus = towards(id + "#f", s.focused && s.enabled, Skin.HOVER);
        if (focus > 0.01f) {
            ui.outline(area.x - 2f, area.y - 2f, area.right() + 2f, area.bottom() + 2f, radius + 2f,
                    Skin.HAIRLINE * 2f, Skin.scaleAlpha(Skin.FOCUS, focus));
        }
    }

    // -----------------------------------------------------------------------
    // Toggle
    // -----------------------------------------------------------------------

    /**
     * The switch.
     *
     * <p>The knob's position and the track's colour are the same animation, so
     * they cannot disagree — they did in an earlier version, where the knob
     * arrived before the colour.
     *
     * @return true when it was just flipped
     */
    public boolean toggle(Renderer ui, String id, Rect area, boolean on, boolean enabled) {
        State s = state(id, area, enabled);
        float t = towards(id + "#t", on, Skin.TOGGLE);
        Rect box = area.vcentreRight(0f, TOGGLE_WIDTH, TOGGLE_HEIGHT);
        float radius = box.height / 2f;

        int off = enabled ? Skin.FILL_SUNKEN : Skin.fade(Skin.FILL_SUNKEN, 0.4f);
        int accent = enabled ? Skin.ACCENT : Skin.fade(Skin.ACCENT, 0.35f);
        int track = Skin.mix(off, accent, t);
        if (s.hovered && enabled) {
            track = Skin.mix(track, Skin.FILL_HOVER, 0.35f);
        }
        ui.rounded(box.x, box.y, box.right(), box.bottom(), radius, track);
        ui.outline(box.x, box.y, box.right(), box.bottom(), radius, Skin.HAIRLINE,
                Skin.scaleAlpha(Skin.EDGE, 1f - t * 0.6f));
        focusRing(ui, id, box, s, radius);

        float inset = 3f;
        float knob = box.height - inset * 2f;
        float travel = box.width - knob - inset * 2f;
        float knobX = box.x + inset + travel * t;
        // The knob stretches slightly while held, the way a physical switch
        // resists before it goes.
        float stretch = s.press * 2f;
        int face = enabled ? 0xFFFFFFFF : 0xFFB9BFC9;
        ui.shadow(knobX, box.y + inset, knobX + knob + stretch, box.y + inset + knob, knob / 2f, 3f,
                Skin.fade(0x000000, 0.4f));
        ui.rounded(knobX, box.y + inset, knobX + knob + stretch, box.y + inset + knob, knob / 2f, face);
        return s.clicked;
    }

    // -----------------------------------------------------------------------
    // Segmented control
    // -----------------------------------------------------------------------

    /**
     * A short list where every option is worth showing.
     *
     * <p>Up to about five: past that the segments are too narrow to read and it
     * should be a {@link #dropdown}.
     *
     * @return the selected index, changed the moment a segment is clicked
     */
    public int segmented(Renderer ui, String id, Rect area, String[] options, int selected) {
        if (options == null || options.length == 0) {
            return selected;
        }
        // The launcher's segmented control: `--r-md` around the track, `--r-sm`
        // around the option that is on.
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_MD, Skin.FILL_SUNKEN);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_MD, Skin.HAIRLINE, Skin.EDGE);

        float segment = area.width / options.length;
        // The selection slides between segments rather than jumping, so the eye
        // follows it instead of re-finding it.
        Motion mark = motions.get(id + "#mark");
        if (mark == null) {
            mark = new Motion(selected, Skin.SELECTION);
            motions.put(id + "#mark", mark);
        }
        mark.to(selected, now);
        float markX = area.x + segment * mark.value(now);
        ui.rounded(markX + 2f, area.y + 2f, markX + segment - 2f, area.bottom() - 2f, Skin.R_SM,
                Skin.FILL_ACTIVE);

        int result = selected;
        for (int index = 0; index < options.length; index++) {
            Rect cell = new Rect(area.x + segment * index, area.y, segment, area.height);
            State s = state(id + "." + index, cell, true);
            boolean active = index == selected;
            ui.textCentred(
                    Text.fit(ui, options[index], Type.BODY_STRONG, cell.width - Skin.S2),
                    cell.centreX(),
                    baseline(ui, cell, Type.BODY_STRONG),
                    Type.BODY_STRONG,
                    active ? Skin.TEXT : Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED, s.hover));
            if (s.clicked) {
                result = index;
            }
        }
        return result;
    }

    /**
     * A segmented control whose cells follow their labels instead of copying
     * the widest label six times. Used by the Mods filter so “Performance” gets
     * its measured width without wasting the same width on “All”.
     *
     * <h2>Three responses to a narrow box, in order</h2>
     *
     * <p>Natural widths first. Then, if the row still does not fit, the
     * horizontal padding drops from 12 to 8 — excess space is the first thing
     * that should go, not a word. Then, and only then, the row scrolls: the
     * wheel moves it, the selected cell is always brought into view, and the
     * ends fade to say there is more.
     *
     * <p>What it never does is shorten a label. A category whose name reads
     * "Performan…" is a category somebody has to guess at, and the guess is the
     * one thing a filter must not require.
     */
    public int segmentedNatural(Renderer ui, String id, Rect area, String[] options, int selected) {
        if (options == null || options.length == 0) {
            return selected;
        }
        float[] widths = new float[options.length];
        float total = measureCells(ui, options, widths, Skin.S3, 48f);
        if (total > area.width) {
            total = measureCells(ui, options, widths, Skin.S2, 42f);
        }

        Rect box = new Rect(area.x, area.y, Math.min(area.width, total), area.height);
        boolean scrolls = total > box.width + 0.5f;

        float selectedX = 0f;
        for (int index = 0; index < Math.max(0, Math.min(selected, widths.length)); index++) {
            selectedX += widths[index];
        }
        float selectedWidth = widths[Math.max(0, Math.min(selected, widths.length - 1))];

        float pan = scrolls ? pan(id, box, total, selectedX, selectedWidth) : 0f;

        ui.rounded(box.x, box.y, box.right(), box.bottom(), Skin.R_MD, Skin.FILL_SUNKEN);
        ui.outline(box.x, box.y, box.right(), box.bottom(), Skin.R_MD, Skin.HAIRLINE, Skin.EDGE);

        ui.clip(box.x, box.y, box.right(), box.bottom());

        Motion markX = motions.get(id + "#mark-x");
        Motion markWidth = motions.get(id + "#mark-width");
        if (markX == null) {
            markX = new Motion(selectedX, Skin.SELECTION);
            markWidth = new Motion(selectedWidth, Skin.SELECTION);
            motions.put(id + "#mark-x", markX);
            motions.put(id + "#mark-width", markWidth);
        }
        markX.to(selectedX, now);
        markWidth.to(selectedWidth, now);
        float drawnX = box.x - pan + markX.value(now);
        float drawnWidth = markWidth.value(now);
        ui.rounded(drawnX + 2f, box.y + 2f, drawnX + drawnWidth - 2f,
                box.bottom() - 2f, Skin.R_SM, Skin.FILL_ACTIVE);

        int result = selected;
        float x = box.x - pan;
        for (int index = 0; index < options.length; index++) {
            Rect cell = new Rect(x, box.y, widths[index], box.height);
            // Hit-tested against the visible part only, so a cell scrolled out
            // of the box cannot be clicked through the panel beside it.
            Rect hitCell = cell.intersect(box);
            State state = state(id + "." + index, hitCell, !hitCell.empty());
            boolean active = index == selected;
            ui.textCentred(options[index], cell.centreX(), baseline(ui, cell, Type.BODY_STRONG),
                    Type.BODY_STRONG,
                    active ? Skin.TEXT : Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED, state.hover));
            if (state.clicked) {
                result = index;
            }
            x += widths[index];
        }

        if (scrolls) {
            edgeFade(ui, box, pan > 1f, pan < total - box.width - 1f);
        }
        ui.unclip();
        return result;
    }

    /** Cell widths for one padding, and their total. */
    private float measureCells(Renderer ui, String[] options, float[] widths,
            float padding, float minimum) {
        float total = 0f;
        for (int index = 0; index < options.length; index++) {
            widths[index] = Math.max(minimum,
                    ui.measure(options[index], Type.BODY_STRONG) + padding * 2f);
            total += widths[index];
        }
        return total;
    }

    /**
     * How far a scrolling segmented row is panned, in units from its left edge.
     *
     * <p>Kept per id and clamped every frame, because the row it belongs to can
     * change width — a drawer opening beside it — between one frame and the
     * next, and an offset that outlived its content scrolls the row into
     * nowhere.
     */
    private float pan(String id, Rect box, float total, float selectedX, float selectedWidth) {
        float limit = Math.max(0f, total - box.width);
        Float stored = pans.get(id);
        float offset = stored == null ? 0f : stored.floatValue();
        if (pointer != null && box.contains(pointer.x(), pointer.y())) {
            offset -= pointer.wheelNotches() * 56f;
        }
        // The selected cell is never off-screen: choosing "Performance" and
        // having it scroll away is worse than not scrolling at all.
        if (selectedX < offset) {
            offset = selectedX;
        } else if (selectedX + selectedWidth > offset + box.width) {
            offset = selectedX + selectedWidth - box.width;
        }
        offset = offset < 0f ? 0f : (offset > limit ? limit : offset);
        pans.put(id, Float.valueOf(offset));
        return offset;
    }

    /** The gradient at each end that says the row continues. */
    private void edgeFade(Renderer ui, Rect box, boolean left, boolean right) {
        float width = 18f;
        int steps = 6;
        for (int step = 0; step < steps; step++) {
            float from = step / (float) steps;
            float to = (step + 1) / (float) steps;
            int shade = Skin.argb(0.55f * (1f - from), 0x05060A);
            if (left) {
                ui.fill(box.x + from * width, box.y + 1f, box.x + to * width, box.bottom() - 1f, shade);
            }
            if (right) {
                ui.fill(box.right() - to * width, box.y + 1f, box.right() - from * width,
                        box.bottom() - 1f, shade);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Dropdown
    // -----------------------------------------------------------------------

    /**
     * A value with a list behind it.
     *
     * <p>The list is drawn by {@link #drawOverlays}, after everything else and
     * outside every clip, because a popover that is clipped by the panel it
     * lives in is a popover that opens into a scrollbar.
     *
     * @return the selected index, changed the moment a row is chosen
     */
    public int dropdown(Renderer ui, String id, Rect area, String[] options, int selected) {
        if (options == null || options.length == 0) {
            return selected;
        }
        boolean open = id.equals(openId);
        State s = state(id, area, true);
        if (s.clicked) {
            openId = open ? null : id;
            open = !open;
        }

        int face = Skin.mix(Skin.mix(Skin.FILL, Skin.FILL_HOVER, s.hover), Skin.FILL_PRESSED, s.press);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, face);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, Skin.HAIRLINE,
                open ? Skin.ACCENT : Skin.EDGE);
        focusRing(ui, id, area, s, Skin.R_XS);

        float chevron = 12f;
        String value = options[Math.max(0, Math.min(options.length - 1, selected))];
        ui.text(
                Text.fit(ui, value, Type.BODY, area.width - Skin.S3 * 2f - chevron),
                area.x + Skin.S3,
                baseline(ui, area, Type.BODY),
                Type.BODY,
                Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, s.hover));
        float spin = towards(id + "#o", open, Skin.SELECTION);
        ui.icon(open ? Icon.CHEVRON_RIGHT : Icon.CHEVRON_DOWN,
                area.right() - Skin.S3 - chevron,
                area.centreY() - chevron / 2f,
                chevron,
                Skin.mix(Skin.TEXT_FAINT, Skin.TEXT, Math.max(s.hover, spin)));

        if (open) {
            overlays.add(new Overlay(id, area, options, selected, false));
        }
        if (id.equals(choiceId)) {
            int chosen = choiceIndex;
            choiceId = null;
            choiceIndex = -1;
            return chosen;
        }
        return selected;
    }

    // -----------------------------------------------------------------------
    // Slider
    // -----------------------------------------------------------------------

    /**
     * A value between two ends.
     *
     * <p>Drag-aware: the press is captured, so the pointer may leave the track
     * vertically without losing the drag, which is how somebody actually moves
     * a slider.
     *
     * @return the fraction, changed while dragging
     */
    public double slider(Renderer ui, String id, Rect area, double fraction, boolean enabled) {
        State s = state(id, area, enabled);
        boolean dragging = id.equals(pressedId) && pointer != null && pointer.down(0);
        double value = Math.max(0d, Math.min(1d, fraction));

        Rect track = area.vcentre(0f, area.width, 5f);

        // The knob travels between its own radii, not between the track's ends.
        // Centred on the very end it hangs half its width outside the row — and
        // at Brightness 0, which is where a lot of people leave it, that read as
        // a control drawn wrong rather than as a control at its minimum. The
        // travel uses the knob's largest radius so growing it on hover cannot
        // shift the value under the cursor.
        float knobMax = 9.5f;
        float travelLeft = track.x + knobMax;
        float travel = Math.max(1f, track.width - knobMax * 2f);
        if (dragging && enabled) {
            value = Math.max(0d, Math.min(1d, (pointer.x() - travelLeft) / travel));
        }

        ui.rounded(track.x, track.y, track.right(), track.bottom(), track.height / 2f, Skin.FILL_SUNKEN);
        float filled = travelLeft + (float) (travel * value);
        ui.rounded(track.x, track.y, filled, track.bottom(), track.height / 2f,
                enabled ? Skin.ACCENT : Skin.fade(Skin.ACCENT, 0.4f));

        float grow = Math.max(s.hover, dragging ? 1f : 0f);
        float knob = 8f + grow * 1.5f;
        ui.shadow(filled - knob, area.centreY() - knob, filled + knob, area.centreY() + knob, knob, 3f,
                Skin.fade(0x000000, 0.45f));
        ui.rounded(filled - knob, area.centreY() - knob, filled + knob, area.centreY() + knob, knob,
                enabled ? 0xFFFFFFFF : 0xFFB9BFC9);
        focusRing(ui, id, area, s, Skin.R_XS);
        return value;
    }

    // -----------------------------------------------------------------------
    // Keybind
    // -----------------------------------------------------------------------

    /**
     * A key, and a way to change it.
     *
     * @return true when it was clicked, which is the caller's cue to start
     *     listening for the next key
     */
    public boolean keybind(Renderer ui, String id, Rect area, String key, boolean listening) {
        State s = state(id, area, true);
        int face = listening
                ? Skin.ACCENT_WASH
                : Skin.mix(Skin.mix(Skin.FILL_SUNKEN, Skin.FILL_HOVER, s.hover), Skin.FILL_PRESSED, s.press);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, face);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, Skin.HAIRLINE,
                listening ? Skin.ACCENT : Skin.EDGE);
        focusRing(ui, id, area, s, Skin.R_XS);
        ui.textCentred(
                Text.fit(ui, listening ? "Press a key…" : key, Type.BODY, area.width - Skin.S2),
                area.centreX(),
                baseline(ui, area, Type.BODY),
                Type.BODY,
                listening ? Skin.TEXT : Skin.mix(Skin.TEXT_FAINT, Skin.TEXT, s.hover));
        return s.clicked;
    }

    // -----------------------------------------------------------------------
    // Stepper
    // -----------------------------------------------------------------------

    /** How wide a stepper needs to be, so a caller can lay one out. */
    public static final float STEPPER_WIDTH = 104f;
    public static final float STEPPER_HEIGHT = 26f;

    /**
     * A whole number, with a minus and a plus.
     *
     * <p>Not a slider, and the difference is the question being asked. A slider
     * asks "roughly where in this range"; padding, decimal places and a sample
     * window are asked exactly, and dragging a track to land on 3 rather than 2
     * is a worse control for that than pressing a button twice.
     *
     * @return -1, 0 or 1: how far the value should move. The caller applies it,
     *     because the caller owns the setting and knows its step.
     */
    public int stepper(
            Renderer ui, String id, Rect area, String value, boolean atMinimum, boolean atMaximum) {
        float button = Math.min(area.height, 26f);
        Rect down = new Rect(area.x, area.y, button, area.height);
        Rect up = new Rect(area.right() - button, area.y, button, area.height);

        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, Skin.FILL_SUNKEN);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, Skin.HAIRLINE, Skin.EDGE);
        ui.textCentred(value, area.centreX(), baseline(ui, area, Type.BODY), Type.BODY, Skin.TEXT);

        // Ghost buttons with a character rather than icons: a minus and a plus
        // are two glyphs the bundled typeface already has, and adding two paths
        // to the icon family to draw a line and a cross would be work for its
        // own sake.
        int moved = 0;
        if (ghost(ui, id + "#-", down, "-", !atMinimum)) {
            moved = -1;
        }
        if (ghost(ui, id + "#+", up, "+", !atMaximum)) {
            moved = 1;
        }
        return moved;
    }

    // -----------------------------------------------------------------------
    // Text
    // -----------------------------------------------------------------------

    /**
     * A short line of text.
     *
     * <p>Draws a caret while it has focus and nothing else: the typing itself is
     * the screen's, because the screen is what receives key events. This is the
     * same division the search box uses, and the reason a text setting could be
     * added to the drawer without a second input path.
     *
     * @return whether it was clicked, which the caller reads as "take focus"
     */
    public boolean textField(Renderer ui, String id, Rect area, String value, boolean editing) {
        State s = state(id, area, true);
        int face = editing
                ? Skin.FILL_SUNKEN
                : Skin.mix(Skin.mix(Skin.FILL_SUNKEN, Skin.FILL_HOVER, s.hover), Skin.FILL_PRESSED, s.press);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, face);
        ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.R_XS, Skin.HAIRLINE,
                editing ? Skin.ACCENT : Skin.EDGE);
        focusRing(ui, id, area, s, Skin.R_XS);

        float room = area.width - Skin.S2 * 2f;
        String shown = Text.fit(ui, value.isEmpty() && !editing ? "None" : value, Type.BODY, room);
        float baseline = baseline(ui, area, Type.BODY);
        ui.text(shown, area.x + Skin.S2, baseline,
                Type.BODY, value.isEmpty() && !editing ? Skin.TEXT_DISABLED : Skin.TEXT);
        if (editing) {
            // A caret that blinks on the same clock as everything else, so it
            // does not look like a second animation system.
            float phase = (Gfx.clock().seconds() % 1.06f) / 1.06f;
            if (phase < 0.55f) {
                float caret = area.x + Skin.S2 + ui.measure(shown, Type.BODY) + 1f;
                ui.fill(caret, area.y + 5f, caret + 1f, area.bottom() - 5f, Skin.ACCENT);
            }
        }
        return s.clicked;
    }

    // -----------------------------------------------------------------------
    // Colour
    // -----------------------------------------------------------------------

    /** The palette a colour control offers. */
    public static final int[] PALETTE = {
        0xFFFFFF, 0xC4303C, 0xE0505C, 0xE0AE57, 0x4FC98A, 0x5B9BD5, 0xB37FE8, 0x8A93A3, 0x000000
    };

    private static final String[] PALETTE_LABELS = {
        "White", "Fathom red", "Light red", "Amber", "Green", "Blue", "Violet", "Grey", "Black"
    };

    /**
     * A colour, with a palette behind it.
     *
     * @return the chosen RGB, changed the moment a swatch is picked
     */
    public int colour(Renderer ui, String id, Rect area, int rgb) {
        boolean open = id.equals(openId);
        State s = state(id, area, true);
        if (s.clicked) {
            openId = open ? null : id;
            open = !open;
        }
        Rect box = area.vcentreRight(0f, SWATCH, SWATCH);
        ui.rounded(box.x, box.y, box.right(), box.bottom(), Skin.R_XS, 0xFF000000 | rgb);
        ui.outline(box.x, box.y, box.right(), box.bottom(), Skin.R_XS, Skin.HAIRLINE,
                open ? Skin.ACCENT : Skin.mix(Skin.EDGE_STRONG, Skin.TEXT, s.hover * 0.4f));
        focusRing(ui, id, box, s, Skin.R_XS);

        if (open) {
            overlays.add(new Overlay(id, box, PALETTE_LABELS, indexOf(rgb), true));
        }
        if (id.equals(choiceId)) {
            int chosen = choiceIndex;
            choiceId = null;
            choiceIndex = -1;
            if (chosen >= 0 && chosen < PALETTE.length) {
                return PALETTE[chosen];
            }
        }
        return rgb;
    }

    private static int indexOf(int rgb) {
        for (int index = 0; index < PALETTE.length; index++) {
            if (PALETTE[index] == rgb) {
                return index;
            }
        }
        return -1;
    }

    // -----------------------------------------------------------------------
    // Rows, labels, badges
    // -----------------------------------------------------------------------

    /** The background behind a list row: hover and selection, animated. */
    public void rowBackground(Renderer ui, String id, Rect area, boolean hovered, boolean selected) {
        float hover = towards(id + "#rh", hovered, Skin.HOVER);
        float mark = towards(id + "#rs", selected, Skin.SELECTION);
        if (hover > 0.01f || mark > 0.01f) {
            ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_XS,
                    Skin.mix(Skin.scaleAlpha(Skin.FILL_HOVER, hover), Skin.FILL_ACTIVE, mark));
        }
        if (mark > 0.01f) {
            // A short accent bar at the leading edge, not a filled block.
            ui.rounded(area.x, area.centreY() - 9f * mark, area.x + 3f, area.centreY() + 9f * mark, 1.5f,
                    Skin.scaleAlpha(Skin.ACCENT, mark));
        }
    }

    public static void separator(Renderer ui, float x, float y, float width) {
        ui.fill(x, y, x + width, y + Skin.HAIRLINE, Skin.CHROME_LINE);
    }

    /** A small upper-case heading. */
    public static void label(Renderer ui, String value, float x, float y) {
        ui.text(value.toUpperCase(java.util.Locale.ROOT), x, y + ui.ascent(Type.LABEL), Type.LABEL, Skin.TEXT_LABEL);
    }

    /** A pill with a word in it. */
    public static void badge(Renderer ui, String value, Rect area, int colour) {
        float height = Math.min(20f, area.height);
        float top = area.y + (area.height - height) / 2f;
        ui.rounded(area.x, top, area.x + badgeWidth(ui, value), top + height, height / 2f, Skin.fade(colour, 0.16f));
        ui.text(value, area.x + Skin.S2, top + (height + ui.ascent(Type.CAPTION) - ui.descent(Type.CAPTION)) / 2f,
                Type.CAPTION, colour);
    }

    public static float badgeWidth(Renderer ui, String value) {
        return ui.measure(value, Type.CAPTION) + Skin.S2 * 2f;
    }

    /**
     * The baseline that centres a line of type in a box.
     *
     * <p>Optical rather than arithmetic: descenders make a font's box taller
     * than the part people read, so centring the box leaves text looking high.
     */
    public static float baseline(Renderer ui, Rect area, Type type) {
        return area.y + (area.height + ui.ascent(type) - ui.descent(type)) / 2f;
    }

    public static float baseline(Renderer ui, float y, float height, Type type) {
        return y + (height + ui.ascent(type) - ui.descent(type)) / 2f;
    }

    // -----------------------------------------------------------------------
    // Overlays
    // -----------------------------------------------------------------------

    /**
     * Draw every open popover, and take the clicks that land on one.
     *
     * <p>Last, so it is on top; outside every clip, so it can leave its parent.
     *
     * <p>The blocking flag it leaves behind is read by the <em>next</em> frame's
     * controls, so a click on an open dropdown does not also reach whatever is
     * underneath. One frame of lag on a rectangle that only exists while a
     * popover is open, in exchange for not having to lay the popover out twice.
     */
    public void drawOverlays(Renderer ui) {
        overlayBlocking = false;
        if (overlays.isEmpty()) {
            return;
        }
        for (int index = 0; index < overlays.size(); index++) {
            Overlay overlay = overlays.get(index);
            if (overlay.swatches) {
                drawSwatches(ui, overlay);
            } else {
                drawList(ui, overlay);
            }
        }
        // A press anywhere else closes it, which is what every popover does.
        if (pointer != null && pointer.pressed(0) && !overlayBlocking) {
            openId = null;
        }
    }

    private void drawList(Renderer ui, Overlay overlay) {
        float rowHeight = 30f;
        float width = Math.max(overlay.anchor.width, 160f);
        float height = overlay.options.length * rowHeight + Skin.S1 * 2f;
        // Opens downwards unless there is no room, which is the only reason to
        // ever open upwards.
        boolean upwards = overlay.anchor.bottom() + height > ui.height() - Skin.S4;
        float top = upwards ? overlay.anchor.y - height - Skin.S1 : overlay.anchor.bottom() + Skin.S1;
        float left = Math.min(overlay.anchor.x, ui.width() - width - Skin.S4);
        Rect panel = new Rect(left, top, width, height);

        float grown = towards(overlay.id + "#pop", true, Skin.DRAWER);
        ui.pushAlpha(grown);
        ui.shadow(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_SM, 16f, Skin.SHADOW);
        ui.rounded(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_SM, Skin.OVERLAY);
        ui.outline(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_SM, Skin.HAIRLINE, Skin.EDGE_STRONG);

        for (int index = 0; index < overlay.options.length; index++) {
            Rect row = new Rect(panel.x + Skin.S1, panel.y + Skin.S1 + rowHeight * index,
                    panel.width - Skin.S1 * 2f, rowHeight);
            boolean over = pointer != null && row.contains(pointer.x(), pointer.y());
            boolean active = index == overlay.selected;
            if (over) {
                ui.rounded(row.x, row.y, row.right(), row.bottom(), Skin.R_XS, Skin.FILL_HOVER);
            }
            ui.text(Text.fit(ui, overlay.options[index], Type.BODY, row.width - Skin.S6),
                    row.x + Skin.S3, baseline(ui, row, Type.BODY), Type.BODY,
                    active ? Skin.TEXT : (over ? Skin.TEXT : Skin.TEXT_MUTED));
            if (active) {
                ui.icon(Icon.CHECK, row.right() - Skin.S3 - 12f, row.centreY() - 6f, 12f, Skin.ACCENT_STRONG);
            }
            if (over && pointer.released(0)) {
                choiceId = overlay.id;
                choiceIndex = index;
                openId = null;
            }
        }
        ui.popAlpha();
        blockIfOver(panel);
    }

    /**
     * A grid of colours.
     *
     * <p>Separate from the list because a grid of colours is a different shape
     * of choice: the names would be noise, so the swatch is the label.
     */
    private void drawSwatches(Renderer ui, Overlay overlay) {
        int columns = 3;
        float cell = 30f;
        int rows = (PALETTE.length + columns - 1) / columns;
        float width = columns * cell + Skin.S2 * 2f;
        float height = rows * cell + Skin.S2 * 2f;
        boolean upwards = overlay.anchor.bottom() + height > ui.height() - Skin.S4;
        float top = upwards ? overlay.anchor.y - height - Skin.S1 : overlay.anchor.bottom() + Skin.S1;
        float left = Math.min(overlay.anchor.x + overlay.anchor.width - width, ui.width() - width - Skin.S4);
        Rect panel = new Rect(Math.max(Skin.S4, left), top, width, height);

        float grown = towards(overlay.id + "#pop", true, Skin.DRAWER);
        ui.pushAlpha(grown);
        ui.shadow(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_SM, 16f, Skin.SHADOW);
        ui.rounded(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_SM, Skin.OVERLAY);
        ui.outline(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_SM, Skin.HAIRLINE, Skin.EDGE_STRONG);

        for (int index = 0; index < PALETTE.length; index++) {
            float cx = panel.x + Skin.S2 + (index % columns) * cell;
            float cy = panel.y + Skin.S2 + (index / columns) * cell;
            Rect swatch = new Rect(cx + 3f, cy + 3f, cell - 6f, cell - 6f);
            boolean over = pointer != null && swatch.inset(-3f).contains(pointer.x(), pointer.y());
            ui.rounded(swatch.x, swatch.y, swatch.right(), swatch.bottom(), Skin.R_XS, 0xFF000000 | PALETTE[index]);
            ui.outline(swatch.x, swatch.y, swatch.right(), swatch.bottom(), Skin.R_XS, Skin.HAIRLINE,
                    index == overlay.selected ? Skin.ACCENT : (over ? Skin.TEXT : Skin.EDGE_STRONG));
            if (over && pointer.released(0)) {
                choiceId = overlay.id;
                choiceIndex = index;
                openId = null;
            }
        }
        ui.popAlpha();
        blockIfOver(panel);
    }

    private void blockIfOver(Rect panel) {
        if (pointer != null && panel.contains(pointer.x(), pointer.y())) {
            overlayBlocking = true;
        }
    }

}
