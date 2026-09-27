package net.fathom.optimizations.fabric.ui;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;

import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Shadow;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.gfx.Pointer;
import net.fathom.optimizations.generated.FathomTheme.Tokens;

/**
 * Fathom's controls: every reusable component the hub and its pages draw.
 *
 * <h2>Immediate mode</h2>
 *
 * <p>A control is a method that <em>draws itself and answers whether it was
 * used</em>, from one rectangle computed once:
 *
 * <pre>
 *   if (controls.primary(ui, "hud.edit", buttonRect, "Edit HUD", true)) {
 *       context.editHud();
 *   }
 * </pre>
 *
 * <p>Everything draws through {@link Renderer}. On the vector renderer the
 * optional {@link FathomRenderer} extras (real drop shadows, horizontal
 * gradients) are used; on the GL fallback the same control degrades to the
 * shapes {@code Renderer} has. No control knows which one it is talking to
 * beyond that check, and none of them touches NanoVG or GL.
 *
 * <h2>State: one slot per control</h2>
 *
 * <p>Hover, press, focus and "active" animations live in a {@link Slot} keyed by
 * the control's id and looked up once per draw, not in four string-keyed maps
 * with a concatenated key each. Slots not drawn for a few seconds are pruned,
 * so a search that shows a hundred different rows over an hour does not leave a
 * hundred dead animations behind.
 *
 * <h2>Response</h2>
 *
 * <ul>
 *   <li>Hit-testing uses the mouse polled at the top of this frame, and the
 *       animation is retargeted before it is sampled, so the frame that finds a
 *       hover draws it.
 *   <li>Hover rises over {@link #HOVER_IN} and falls over {@link #HOVER_OUT}:
 *       light at once, fade without flicker. Time-based, never frame-counted.
 *   <li>{@link #setInputEnabled} turns every control deaf at once -- the hub
 *       uses it the moment it starts closing, so nothing under a fading panel
 *       can be clicked.
 * </ul>
 */
public final class Controls {

    // -----------------------------------------------------------------------
    // Geometry callers lay out around
    // -----------------------------------------------------------------------

    public static final float TOGGLE_WIDTH = 40f;
    public static final float TOGGLE_HEIGHT = 22f;
    public static final float SWATCH = 24f;
    public static final float SLIDER_HEIGHT = 20f;
    public static final float ICON_BUTTON = 30f;
    public static final float STEPPER_WIDTH = 104f;
    public static final float STEPPER_HEIGHT = 26f;

    /** How fast a hover lights, and how slowly it lets go. Seconds. */
    public static final float HOVER_IN = Timing.HOVER_IN;
    public static final float HOVER_OUT = Timing.HOVER_OUT;
    /** How long the pointer rests on something before its tooltip shows. */
    public static final float TOOLTIP_DELAY = 0.45f;

    private static final int PRUNE_EVERY_FRAMES = 300;
    private static final int PRUNE_UNSEEN_FRAMES = 600;

    // -----------------------------------------------------------------------
    // Frame state
    // -----------------------------------------------------------------------

    /** The animation memory of one control. */
    private static final class Slot {
        final Motion hover = new Motion(0f, HOVER_OUT);
        final Motion press = new Motion(0f, Timing.PRESS);
        final Motion focus = new Motion(0f, Timing.HOVER_OUT);
        final Motion active = new Motion(0f, Timing.NAVIGATION);
        boolean over;
        boolean activeInitialised;
        float hoverSince = -1f;
        long seen;
    }

    /** A free-standing animation and the last frame that used it. */
    private static final class TrackedMotion {
        final Motion motion;
        long seen;

        TrackedMotion(float initial, float duration) {
            motion = new Motion(initial, duration);
        }
    }

    private final Map<String, Slot> slots = new HashMap<String, Slot>();
    private final Map<String, TrackedMotion> motions = new HashMap<String, TrackedMotion>();
    /** Horizontal offset of each segmented row that is too wide for its box. */
    private final Map<String, Float> pans = new HashMap<String, Float>();
    private final List<Overlay> overlays = new ArrayList<Overlay>();
    private final List<Overlay> overlayPool = new ArrayList<Overlay>();
    /** Reused every frame; state() no longer creates one short-lived object per control. */
    private final List<State> statePool = new ArrayList<State>();
    /** Scratch widths for segmented controls; grown rarely, never allocated per frame. */
    private float[] segmentWidths = new float[8];

    private Pointer pointer;
    private float delta;
    private float now;
    private long frame;
    private int stateCursor;
    private int overlayCursor;
    private boolean inputEnabled = true;

    private String pressedId;
    private String focusedId;
    private String openId;
    private String choiceId;
    private int choiceIndex = -1;
    private boolean overlayBlocking;
    private Rect shield;

    /** The tooltip that won this frame: the last hovered control that asked for one. */
    private String tooltipText;
    private Rect tooltipAnchor;
    private String lastTooltipText;
    private Rect lastTooltipAnchor;
    private final Motion tooltipMotion = new Motion(0f, Timing.TOOLTIP);
    private final Motion popoverMotion = new Motion(0f, Timing.PANEL);
    /** Retained only while the single open popover finishes its exit. */
    private Overlay visibleOverlay;

    /** A popover waiting to be drawn on top of everything. */
    private static final class Overlay {
        String id;
        Rect anchor;
        String[] options;
        int selected;
        boolean swatches;

        Overlay set(String id, Rect anchor, String[] options, int selected, boolean swatches) {
            this.id = id;
            this.anchor = anchor;
            this.options = options;
            this.selected = selected;
            this.swatches = swatches;
            return this;
        }
    }

    /** Start a frame: one timestamp, one pointer, every control agrees on both. */
    public void begin(Pointer mouse, float seconds) {
        this.pointer = mouse;
        this.delta = seconds;
        this.now = Gfx.clock().seconds();
        this.shield = null;
        this.frame++;
        this.stateCursor = 0;
        this.overlayCursor = 0;
        this.tooltipText = null;
        this.tooltipAnchor = null;
        overlays.clear();
        overlayCursor = 0;
        if (frame % PRUNE_EVERY_FRAMES == 0) {
            prune();
        }
    }

    /**
     * End a frame. The press is released here, not in {@link #begin}: a control
     * decides it was clicked by seeing the release while it still owns the press.
     */
    public void end() {
        if (pointer != null && !pointer.down(0)) {
            pressedId = null;
        }
    }

    /** Forget every animation and every focus. For a screen that has just opened. */
    public void reset() {
        slots.clear();
        motions.clear();
        pans.clear();
        shield = null;
        pressedId = null;
        focusedId = null;
        openId = null;
        choiceId = null;
        choiceIndex = -1;
        overlays.clear();
        visibleOverlay = null;
        popoverMotion.set(0f, Gfx.clock().seconds());
        inputEnabled = true;
        tooltipText = null;
        lastTooltipText = null;
        lastTooltipAnchor = null;
        tooltipMotion.set(0f, Gfx.clock().seconds());
    }

    /**
     * Whether controls respond to the pointer at all.
     *
     * <p>Off: nothing hovers, presses, clicks or drags, and open popovers stop
     * taking choices. Animations keep running, so hovers fade out rather than
     * snapping off.
     */
    public void setInputEnabled(boolean enabled) {
        inputEnabled = enabled;
        if (!enabled) {
            pressedId = null;
        }
    }

    public boolean inputEnabled() {
        return inputEnabled;
    }

    public int animationCount() {
        return slots.size() + motions.size();
    }

    public float delta() {
        return delta;
    }

    public void focus(String id) {
        focusedId = id;
    }

    public boolean focused(String id) {
        return id != null && id.equals(focusedId);
    }

    public void clearFocus() {
        focusedId = null;
    }

    /** Swallow hover inside this region for everything drawn until it is cleared. */
    public void shield(Rect region) {
        this.shield = region;
    }

    public void clearShield() {
        this.shield = null;
    }

    public boolean popoverOpen() {
        return openId != null;
    }

    public void closePopover() {
        openId = null;
    }

    private void prune() {
        long oldest = frame - PRUNE_UNSEEN_FRAMES;
        Iterator<Slot> slotIterator = slots.values().iterator();
        while (slotIterator.hasNext()) {
            if (slotIterator.next().seen < oldest) {
                slotIterator.remove();
            }
        }
        Iterator<TrackedMotion> motionIterator = motions.values().iterator();
        while (motionIterator.hasNext()) {
            if (motionIterator.next().seen < oldest) {
                motionIterator.remove();
            }
        }
        if (pans.size() > 64) {
            pans.clear();
        }
    }

    private Slot slot(String id) {
        Slot slot = slots.get(id);
        if (slot == null) {
            slot = new Slot();
            slots.put(id, slot);
        }
        slot.seen = frame;
        return slot;
    }

    private boolean pointerOver(Rect area) {
        return inputEnabled && !overlayBlocking && pointer != null
                && area.contains(pointer.x(), pointer.y())
                && (shield == null || !shield.contains(pointer.x(), pointer.y()));
    }

    // -----------------------------------------------------------------------
    // The interaction primitive every control is built on
    // -----------------------------------------------------------------------

    /** Hover, press and click for a rectangle: the one place that decides them. */
    public final class State {
        public boolean hovered;
        public boolean pressed;
        public boolean clicked;
        public boolean enabled;
        public boolean focused;
        /** 0 to 1, eased: hover for animating. */
        public float hover;
        /** 0 to 1, eased: press for animating. */
        public float press;
        private Slot slot;

        private State() {}

        private void update(String id, Rect area, boolean isEnabled) {
            slot = slot(id);
            boolean over = isEnabled && pointerOver(area);
            if (over != slot.over) {
                HoverLatency.stateChanged(id, pointer == null ? 0L : pointer.pointerEventNs(),
                        Gfx.clock().frames());
                slot.hoverSince = over ? now : -1f;
            }
            slot.over = over;
            this.enabled = isEnabled;
            this.hovered = over;
            this.focused = focused(id);
            if (over && pointer.pressed(0)) {
                pressedId = id;
                focusedId = id;
            }
            this.pressed = over && id.equals(pressedId);
            this.clicked = over && pointer.released(0) && id.equals(pressedId);
            slot.hover.to(over ? 1f : 0f, now, over ? HOVER_IN : HOVER_OUT);
            slot.press.to(pressed ? 1f : 0f, now);
            this.hover = slot.hover.value(now);
            this.press = slot.press.value(now);
        }

        /** How long the pointer has rested here, in seconds; 0 when it is not here. */
        public float dwell() {
            return slot.hoverSince < 0f ? 0f : now - slot.hoverSince;
        }
    }

    public State state(String id, Rect area, boolean enabled) {
        State state;
        if (stateCursor == statePool.size()) {
            state = new State();
            statePool.add(state);
        } else {
            state = statePool.get(stateCursor);
        }
        stateCursor++;
        state.update(id, area, enabled);
        return state;
    }

    /** The eased 0..1 for a state something is in or heading towards. */
    public float towards(String id, boolean on, float duration) {
        TrackedMotion tracked = motions.get(id);
        if (tracked == null) {
            tracked = new TrackedMotion(on ? 1f : 0f, duration);
            motions.put(id, tracked);
        }
        tracked.seen = frame;
        Motion motion = tracked.motion;
        motion.to(on ? 1f : 0f, now);
        return motion.value(now);
    }

    private float active(State s, boolean on) {
        if (!s.slot.activeInitialised) {
            s.slot.activeInitialised = true;
            s.slot.active.set(on ? 1f : 0f, now);
        }
        s.slot.active.to(on ? 1f : 0f, now, Timing.NAVIGATION);
        return s.slot.active.value(now);
    }

    // -----------------------------------------------------------------------
    // Shared drawing helpers
    // -----------------------------------------------------------------------

    /** A real drop shadow on the vector renderer, the old falloff on the GL one. */
    public static void shadow(Renderer ui, float l, float t, float r, float b, float radius, Shadow shadow) {
        VisualEffects.shadow(ui, l, t, r, b, radius, shadow);
    }

    /** A flat surface with its radius normalised to the geometry. No sheen. */
    public static void surface(Renderer ui, float l, float t, float r, float b, float radius, int face) {
        float normal = net.fathom.optimizations.core.ui.render.RoundedGeometry.radius(radius, r - l, b - t);
        ui.rounded(l, t, r, b, normal, face);
    }

    /** A filled surface and its inside stroke, sharing one normalised path. */
    public static void borderedSurface(Renderer ui, Rect area, float radius, int face, int edge) {
        Rect visual = Skin.pixelAligned(ui, area);
        float normal = Skin.radius(radius, visual);
        surface(ui, visual.x, visual.y, visual.right(), visual.bottom(), normal, face);
        ui.outline(visual.x, visual.y, visual.right(), visual.bottom(), normal, Skin.HAIRLINE, edge);
    }

    /** The baseline that centres a line of type in a box, optically. */
    public static float baseline(Renderer ui, Rect area, Type type) {
        return area.y + (area.height + ui.ascent(type) - ui.descent(type)) / 2f;
    }

    public static float baseline(Renderer ui, float y, float height, Type type) {
        return y + (height + ui.ascent(type) - ui.descent(type)) / 2f;
    }

    /** A line of text vertically centred in a box, fitted to the room it has. */
    public static float middle(Renderer ui, String text, float x, Rect box, float room, Type type, int colour) {
        return ui.text(Text.fit(ui, text, type, room), x, baseline(ui, box, type), type, colour);
    }

    // -----------------------------------------------------------------------
    // Typography components
    // -----------------------------------------------------------------------

    /**
     * A page or panel heading with an optional line under it.
     *
     * @return the height it took, including the gap below it
     */
    public static float sectionHeader(Renderer ui, String title, String subtitle, float x, float y, float width) {
        float titleHeight = ui.lineHeight(Type.SECTION);
        ui.text(Text.fit(ui, title, Type.SECTION, width), x, y + ui.ascent(Type.SECTION), Type.SECTION, Skin.TEXT);
        float height = titleHeight;
        if (subtitle != null && !subtitle.isEmpty()) {
            height += 2f;
            ui.text(Text.fit(ui, subtitle, Type.SMALL, width), x, y + height + ui.ascent(Type.SMALL),
                    Type.SMALL, Skin.TEXT_FAINT);
            height += ui.lineHeight(Type.SMALL);
        }
        return height;
    }

    /** A small group label, in sentence case as the launcher sets them, with its top at {@code y}. */
    public static void label(Renderer ui, String value, float x, float y) {
        ui.text(value, x, y + ui.ascent(Type.LABEL), Type.LABEL, Skin.TEXT_LABEL);
    }

    public static void separator(Renderer ui, float x, float y, float width) {
        ui.fill(x, y, x + width, y + Skin.HAIRLINE, Skin.CHROME_LINE);
    }

    /** A pill with a word in it, centred vertically in {@code area}. */
    public static void badge(Renderer ui, String value, Rect area, int colour) {
        float height = Math.min(20f, area.height);
        float top = area.y + (area.height - height) / 2f;
        Rect pill = new Rect(area.x, top, Math.min(area.width, badgeWidth(ui, value)), height);
        ui.rounded(pill.x, pill.y, pill.right(), pill.bottom(), Skin.pill(pill), Skin.fade(colour, 0.14f));
        ui.text(Text.fit(ui, value, Type.CAPTION, Math.max(0f, pill.width - Skin.S2 * 2f)),
                pill.x + Skin.S2, baseline(ui, pill, Type.CAPTION), Type.CAPTION, colour);
    }

    /**
     * A transient badge. It fades and grows the first time it appears, then is
     * completely still for as long as it remains present.
     */
    public void badge(Renderer ui, String id, String value, Rect area, int colour) {
        TrackedMotion tracked = motions.get(id);
        if (tracked == null) {
            tracked = new TrackedMotion(0f, Timing.TOOLTIP);
            motions.put(id, tracked);
        } else if (tracked.seen < frame - 1L) {
            // It disappeared and has just returned: that is a new appearance,
            // not a permanently half-live idle animation.
            tracked.motion.set(0f, now);
        }
        tracked.seen = frame;
        tracked.motion.to(1f, now, Timing.TOOLTIP);
        float reveal = tracked.motion.opacity(now);
        ui.pushAlpha(reveal);
        ui.pushTransform(0f, 0f, 0.985f + 0.015f * reveal, area.centreX(), area.centreY());
        badge(ui, value, area, colour);
        ui.popTransform();
        ui.popAlpha();
    }

    public static float badgeWidth(Renderer ui, String value) {
        return ui.measure(value, Type.CAPTION) + Skin.S2 * 2f;
    }

    // -----------------------------------------------------------------------
    // Buttons
    // -----------------------------------------------------------------------

    /**
     * The one action a panel is for: the launcher's prominent fill -- denser and
     * darker than everything around it, with the brighter rim. Never coloured.
     */
    public boolean primary(Renderer ui, String id, Rect area, String label, boolean enabled) {
        State s = state(id, area, enabled);
        int face = !enabled
                ? Skin.FILL_DISABLED
                : Skin.mix(Skin.PROMINENT, Skin.PROMINENT_HOVER, Math.max(s.hover, s.press));
        int edge = !enabled ? Skin.EDGE_RECESSED
                : Skin.mix(Skin.EDGE_ELEVATED, Skin.ENERGY_EDGE, s.hover);
        int text = !enabled ? Skin.TEXT_DISABLED : Skin.TEXT;
        button(ui, s, area, face, edge, text, label, true);
        return s.clicked;
    }

    /** Everything else with a surface. */
    public boolean secondary(Renderer ui, String id, Rect area, String label, boolean enabled) {
        State s = state(id, area, enabled);
        int face = !enabled
                ? Skin.FILL_DISABLED
                : Skin.mix(Skin.mix(Skin.SURFACE, Skin.ELEVATED, s.hover), Skin.RECESSED, s.press);
        int text = !enabled ? Skin.TEXT_DISABLED : Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, s.hover);
        int edge = Skin.mix(Skin.EDGE_SURFACE, Skin.EDGE_ELEVATED, Math.max(s.hover, s.press * 0.65f));
        button(ui, s, area, face, edge, text, label, false);
        return s.clicked;
    }

    /**
     * A compact action embedded in a larger surface. Its moving chevron and
     * recessed material distinguish it from a generic secondary button.
     */
    public boolean recessedAction(Renderer ui, String id, Rect area, String label, boolean enabled) {
        State s = state(id, area, enabled);
        float scale = 1f - s.press * 0.010f;
        float sink = s.press * 0.65f;
        float radius = Skin.pill(area);
        int face = !enabled
                ? Skin.FILL_DISABLED
                : Skin.mix(Skin.mix(Skin.RECESSED, Skin.SURFACE_HOVER, s.hover), Skin.BACKGROUND, s.press);
        int edge = !enabled
                ? Skin.EDGE_RECESSED
                : Skin.mix(Skin.EDGE_SURFACE, Skin.EDGE_ELEVATED, Math.max(s.hover, s.press));

        ui.pushTransform(0f, sink, scale, area.centreX(), area.centreY());
        VisualEffects.layeredSurface(ui, area, radius, face, s.hover);
        ui.outline(area.x, area.y, area.right(), area.bottom(), radius,
                Skin.BORDER_STANDARD, edge);
        middle(ui, label, area.x + Skin.S3, area,
                Math.max(0f, area.width - Skin.S3 * 2f - 14f), Type.CAPTION,
                enabled ? Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, s.hover) : Skin.TEXT_DISABLED);
        float icon = 11f;
        // Fixed. This used to slide 1.5px right on hover, which is an icon
        // moving under the pointer that is pointing at it. The hover response
        // is the colour mix on the next line.
        float arrowX = area.right() - Skin.S2 - icon;
        ui.icon(Icon.CHEVRON_RIGHT, arrowX, area.centreY() - icon / 2f, icon,
                enabled ? Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED, s.hover) : Skin.TEXT_DISABLED);
        ui.popTransform();
        focusRing(ui, s, area, radius);
        return s.clicked;
    }

    /** No surface until touched. */
    public boolean ghost(Renderer ui, String id, Rect area, String label, boolean enabled) {
        State s = state(id, area, enabled);
        int face = Skin.mix(Skin.mix(Skin.fade(Skin.FILL_HOVER, 0f), Skin.FILL_HOVER, s.hover), Skin.FILL_PRESSED, s.press);
        int text = !enabled ? Skin.TEXT_DISABLED : Skin.mix(Skin.TEXT_FAINT, Skin.TEXT, s.hover);
        int edge = Skin.scaleAlpha(Skin.EDGE_STRONG, Math.max(s.hover, s.press) * 0.72f);
        button(ui, s, area, face, edge, text, label, false);
        return s.clicked;
    }

    /**
     * Something that destroys work: the launcher's caution button. At rest it
     * is an ordinary control with terracotta type; under the pointer it warms
     * to the danger wash. Never a filled red panel.
     */
    public boolean danger(Renderer ui, String id, Rect area, String label, boolean enabled) {
        State s = state(id, area, enabled);
        float heat = Math.max(s.hover, s.press);
        int face = !enabled
                ? Skin.FILL_DISABLED
                : Skin.mix(Skin.SURFACE, Skin.BAD_WASH, heat);
        int edge = enabled ? Skin.mix(Skin.EDGE_SURFACE, Tokens.COLOR_DANGER_RIM, heat) : Skin.EDGE;
        button(ui, s, area, face, edge, enabled ? Skin.BAD : Skin.TEXT_DISABLED, label, false);
        return s.clicked;
    }

    /** An icon in a circle. */
    public boolean iconButton(Renderer ui, String id, Rect area, Icon icon, boolean active, boolean enabled) {
        State s = state(id, area, enabled);
        float on = active(s, active);
        int face = Skin.mix(Skin.mix(Skin.fade(Skin.FILL_HOVER, 0f), Skin.FILL_HOVER, s.hover), Skin.FILL_PRESSED, s.press);
        if (on > 0.01f) {
            face = Skin.mix(face, Skin.scaleAlpha(Skin.ENERGY, 0.32f), on);
        }
        float round = Skin.pill(area);
        float scale = 1f - s.press * 0.006f;
        ui.pushTransform(0f, 0f, scale, area.centreX(), area.centreY());
        ui.rounded(area.x, area.y, area.right(), area.bottom(), round, face);
        if (s.hover > 0.01f || on > 0.01f) {
            ui.outline(area.x, area.y, area.right(), area.bottom(), round, Skin.HAIRLINE,
                    Skin.mix(Skin.scaleAlpha(Skin.EDGE_STRONG, s.hover),
                            Skin.scaleAlpha(Skin.ENERGY_EDGE, on), on));
        }
        float size = Math.round(Math.min(area.width, area.height) * 0.54f);
        int tint = !enabled
                ? Skin.TEXT_DISABLED
                : Skin.mix(Skin.mix(Skin.TEXT_FAINT, Skin.TEXT, s.hover), Skin.ENERGY_MARK, on);
        ui.icon(icon, area.centreX() - size / 2f, area.centreY() - size / 2f, size, tint);
        ui.popTransform();
        // Focus belongs to the fixed hitbox, not the compressed press surface.
        focusRing(ui, s, area, round);
        return s.clicked;
    }

    private void button(Renderer ui, State s, Rect area, int face, int edge, int text, String label,
            boolean signature) {
        // The visual compresses around a fixed hitbox. Six tenths of a percent
        // is felt as contact without making neighbouring controls appear to move.
        float scale = 1f - s.press * 0.006f;
        float sink = s.press * 0.45f;
        float radius = Skin.pill(area);
        ui.pushTransform(0f, sink, scale, area.centreX(), area.centreY());
        if (signature && s.enabled) {
            // The primary action: the densest face on the panel and the
            // brighter rim. Hover deepens it; it never lights up.
            VisualEffects.layeredSurface(ui, area, radius, face, 0f);
            ui.outline(area.x, area.y, area.right(), area.bottom(), radius,
                    Skin.BORDER_STANDARD, edge);
        } else {
            borderedSurface(ui, area, radius, face, edge);
        }
        ui.textCentred(
                Text.fit(ui, label, Type.BODY_STRONG, area.width - Skin.S3),
                area.centreX(),
                baseline(ui, area, Type.BODY_STRONG),
                Type.BODY_STRONG,
                text);
        ui.popTransform();
        focusRing(ui, s, area, radius);
    }

    /** Draw the shared animated keyboard-focus ring around a custom-owned control. */
    public void focusRing(Renderer ui, State s, Rect area, float radius) {
        s.slot.focus.to(s.focused && s.enabled && s.slot.over ? 0f : (s.focused && s.enabled ? 1f : 0f), now);
        float focus = s.slot.focus.value(now);
        if (focus > 0.01f) {
            ui.outline(area.x - 2f, area.y - 2f, area.right() + 2f, area.bottom() + 2f, radius + 2f,
                    Skin.HAIRLINE * 1.5f,
                    Skin.scaleAlpha(Skin.FOCUS, focus * VisualEffects.FOCUS_INTENSITY));
        }
    }

    // -----------------------------------------------------------------------
    // Navigation
    // -----------------------------------------------------------------------

    /**
     * A sidebar destination: icon and label on one optical centre line, a
     * capsule on hover, and when current the denser capsule with the brighter
     * rim and full-strength type. No marker bar.
     *
     * @return true when clicked
     */
    public boolean navItem(Renderer ui, String id, Rect row, Icon icon, String label, boolean current) {
        State s = state(id, row, true);
        float on = active(s, current);
        float hover = s.hover * (1f - on);
        if (hover > 0.01f || on > 0.01f) {
            ui.rounded(row.x, row.y, row.right(), row.bottom(), Skin.pill(row),
                    Skin.mix(Skin.scaleAlpha(Skin.SURFACE_HOVER, hover), Skin.ELEVATED, on));
        }
        if (on > 0.01f) {
            ui.outline(row.x, row.y, row.right(), row.bottom(), Skin.pill(row), Skin.HAIRLINE,
                    Skin.scaleAlpha(Skin.EDGE_ELEVATED, on));
        }
        float iconSize = 16f;
        int tint = Skin.mix(Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED, s.hover), Skin.TEXT, on);
        float x = row.x + Skin.S4;
        ui.icon(icon, x, row.centreY() - iconSize / 2f, iconSize, Skin.mix(tint, Skin.ACCENT_STRONG, on * 0.85f));
        x += iconSize + Skin.S3;
        middle(ui, label, x, row, row.right() - x - Skin.S2, current ? Type.BODY_STRONG : Type.BODY, tint);
        return s.clicked;
    }

    // -----------------------------------------------------------------------
    // Toggle
    // -----------------------------------------------------------------------

    /**
     * The switch. Knob position and track colour are one animation, so they
     * cannot disagree. The box is centred vertically in {@code area} and
     * right-aligned, so a label drawn with {@link #baseline} on the same row
     * shares its centre line exactly.
     *
     * @return true when it was just flipped
     */
    public boolean toggle(Renderer ui, String id, Rect area, boolean on, boolean enabled) {
        State s = state(id, area, enabled);
        if (!s.slot.activeInitialised) {
            s.slot.activeInitialised = true;
            s.slot.active.set(on ? 1f : 0f, now);
        }
        s.slot.active.to(on ? 1f : 0f, now, Timing.TOGGLE);
        float t = s.slot.active.value(now);

        float boxY = Skin.snap(ui, area.centreY() - TOGGLE_HEIGHT / 2f);
        Rect box = new Rect(area.right() - TOGGLE_WIDTH, boxY, TOGGLE_WIDTH, TOGGLE_HEIGHT);
        float radius = Skin.pill(box);

        // The launcher's switch: a sunken track with a rim when off, champagne
        // when on, and an ivory knob either way. On is luminance, not a hue.
        int off = Skin.mix(Tokens.CONTROL_FILL_SUNKEN, Tokens.CONTROL_FILL_SUNKEN_HOVER, s.hover);
        int accent = Skin.mix(Tokens.PALETTE_CHAMPAGNE_300, Tokens.PALETTE_CHAMPAGNE_200, s.hover);
        int track = Skin.mix(off, accent, t);
        if (!enabled) {
            track = Skin.scaleAlpha(track, 0.45f);
        }
        ui.rounded(box.x, box.y, box.right(), box.bottom(), radius, track);
        // Emphasis is an inside stroke. Growing the geometry by one unit
        // made the switch wider and taller only while active.
        ui.outline(box.x, box.y, box.right(), box.bottom(), radius, Skin.BORDER_STANDARD,
                Skin.mix(Skin.EDGE_SURFACE, Skin.EDGE_ELEVATED, Math.max(s.hover, t)));
        focusRing(ui, s, box, radius);

        float inset = 3f;
        float knob = box.height - inset * 2f;
        float travel = box.width - knob - inset * 2f;
        float knobX = box.x + inset + travel * t;
        int face = enabled ? Tokens.PALETTE_IVORY_50 : Tokens.PALETTE_CHAMPAGNE_300;
        ui.shadow(knobX, box.y + inset, knobX + knob, box.y + inset + knob, knob / 2f, 2.5f,
                Skin.SHADOW);
        Rect thumb = new Rect(knobX, box.y + inset, knob, knob);
        ui.rounded(thumb.x, thumb.y, thumb.right(), thumb.bottom(), Skin.pill(thumb), face);
        return s.clicked;
    }

    // -----------------------------------------------------------------------
    // Segmented controls
    // -----------------------------------------------------------------------

    public int segmented(Renderer ui, String id, Rect area, String[] options, int selected) {
        if (options == null || options.length == 0) {
            return selected;
        }
        float outerRadius = Skin.pill(area);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), outerRadius, Skin.RECESSED);
        ui.outline(area.x, area.y, area.right(), area.bottom(), outerRadius,
                Skin.BORDER_STANDARD, Skin.EDGE_RECESSED);

        float segment = area.width / options.length;
        String markId = id + "#mark";
        TrackedMotion trackedMark = motions.get(markId);
        if (trackedMark == null) {
            trackedMark = new TrackedMotion(selected, Timing.NAVIGATION);
            motions.put(markId, trackedMark);
        }
        trackedMark.seen = frame;
        Motion mark = trackedMark.motion;
        mark.to(selected, now);
        float markX = area.x + segment * mark.value(now);
        Rect markArea = new Rect(markX + 2f, area.y + 2f, segment - 4f, area.height - 4f);
        float innerRadius = Skin.pill(markArea);
        surface(ui, markX + 2f, area.y + 2f, markX + segment - 2f, area.bottom() - 2f,
                innerRadius, Skin.ELEVATED);
        ui.outline(markX + 2f, area.y + 2f, markX + segment - 2f, area.bottom() - 2f,
                innerRadius, Skin.BORDER_STANDARD, Skin.scaleAlpha(Skin.ENERGY_EDGE, 0.62f));

        int result = selected;
        for (int index = 0; index < options.length; index++) {
            Rect cell = new Rect(area.x + segment * index, area.y, segment, area.height);
            State s = state(id + "." + index, cell, true);
            boolean active = index == selected;
            float on = active(s, active);
            if (!active && s.hover > 0.01f) {
                ui.rounded(cell.x + 2f, cell.y + 2f, cell.right() - 2f, cell.bottom() - 2f, innerRadius,
                        Skin.scaleAlpha(Skin.SURFACE_HOVER, s.hover * 0.72f));
            }
            ui.textCentred(
                    Text.fit(ui, options[index], Type.BODY_STRONG, cell.width - Skin.S2),
                    cell.centreX(), baseline(ui, cell, Type.BODY_STRONG), Type.BODY_STRONG,
                    Skin.mix(Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED, s.hover), Skin.TEXT, on));
            if (s.clicked) {
                result = index;
            }
        }
        return result;
    }

    /**
     * A segmented control whose cells follow their labels. Narrows padding
     * before it scrolls, and never shortens a label.
     */
    public int segmentedNatural(Renderer ui, String id, Rect area, String[] options, int selected) {
        if (options == null || options.length == 0) {
            return selected;
        }
        float[] widths = segmentWidths(options.length);
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

        // One quiet rounded track; the categories inside are text until chosen.
        ui.rounded(box.x, box.y, box.right(), box.bottom(), Skin.pill(box), Skin.RECESSED);
        ui.clip(box.x, box.y, box.right(), box.bottom());

        String markXId = id + "#mark-x";
        String markWidthId = id + "#mark-width";
        TrackedMotion trackedX = motions.get(markXId);
        TrackedMotion trackedWidth = motions.get(markWidthId);
        if (trackedX == null || trackedWidth == null) {
            trackedX = new TrackedMotion(selectedX, Timing.NAVIGATION);
            trackedWidth = new TrackedMotion(selectedWidth, Timing.NAVIGATION);
            motions.put(markXId, trackedX);
            motions.put(markWidthId, trackedWidth);
        }
        trackedX.seen = frame;
        trackedWidth.seen = frame;
        Motion markX = trackedX.motion;
        Motion markWidth = trackedWidth.motion;
        markX.to(selectedX, now);
        markWidth.to(selectedWidth, now);
        float drawnX = box.x - pan + markX.value(now);
        float drawnWidth = markWidth.value(now);
        Rect activeMark = Rect.of(drawnX + 2f, box.y + 2f,
                drawnX + drawnWidth - 2f, box.bottom() - 2f);
        float innerRadius = Skin.pill(activeMark);
        // A rounded pill that slides between categories; its travel is the motion.
        ui.rounded(activeMark.x, activeMark.y, activeMark.right(), activeMark.bottom(),
                innerRadius, Skin.ELEVATED);
        ui.outline(activeMark.x, activeMark.y, activeMark.right(), activeMark.bottom(),
                innerRadius, Skin.BORDER_STANDARD, Skin.scaleAlpha(Skin.ENERGY_EDGE, 0.70f));

        int result = selected;
        float x = box.x - pan;
        for (int index = 0; index < options.length; index++) {
            Rect cell = new Rect(x, box.y, widths[index], box.height);
            Rect hitCell = cell.intersect(box);
            State state = state(id + "." + index, hitCell, !hitCell.empty());
            boolean active = index == selected;
            float on = active(state, active);
            boolean fullyVisible = cell.x >= box.x && cell.right() <= box.right();
            if (!active && fullyVisible && state.hover > 0.01f) {
                ui.rounded(cell.x + 2f, cell.y + 2f, cell.right() - 2f, cell.bottom() - 2f, innerRadius,
                        Skin.scaleAlpha(Skin.SURFACE_HOVER, state.hover * 0.72f));
            }
            ui.textCentred(options[index], cell.centreX(), baseline(ui, cell, Type.BODY_STRONG),
                    Type.BODY_STRONG,
                    Skin.mix(Skin.mix(Skin.TEXT_FAINT, Skin.TEXT_MUTED, state.hover), Skin.TEXT, on));
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

    private float measureCells(Renderer ui, String[] options, float[] widths, float padding, float minimum) {
        float total = 0f;
        for (int index = 0; index < options.length; index++) {
            widths[index] = Math.max(minimum, ui.measure(options[index], Type.BODY_STRONG) + padding * 2f);
            total += widths[index];
        }
        return total;
    }

    private float[] segmentWidths(int needed) {
        if (segmentWidths.length < needed) {
            int capacity = segmentWidths.length;
            while (capacity < needed) {
                capacity *= 2;
            }
            segmentWidths = new float[capacity];
        }
        return segmentWidths;
    }

    private float pan(String id, Rect box, float total, float selectedX, float selectedWidth) {
        float limit = Math.max(0f, total - box.width);
        Float stored = pans.get(id);
        float offset = stored == null ? 0f : stored.floatValue();
        if (inputEnabled && pointer != null && box.contains(pointer.x(), pointer.y())) {
            offset -= pointer.wheelNotches() * 56f;
        }
        if (selectedX < offset) {
            offset = selectedX;
        } else if (selectedX + selectedWidth > offset + box.width) {
            offset = selectedX + selectedWidth - box.width;
        }
        offset = offset < 0f ? 0f : (offset > limit ? limit : offset);
        if (stored == null || stored.floatValue() != offset) {
            pans.put(id, Float.valueOf(offset));
        }
        return offset;
    }

    private void edgeFade(Renderer ui, Rect box, boolean left, boolean right) {
        float width = 18f;
        int shade = Skin.fade(Tokens.PALETTE_UMBER_900, 0.55f);
        int clear = Skin.fade(Tokens.PALETTE_UMBER_900, 0f);
        if (ui instanceof FathomRenderer) {
            FathomRenderer vector = (FathomRenderer) ui;
            if (left) {
                vector.horizontalGradient(box.x, box.y + 1f, box.x + width, box.bottom() - 1f, 0f, shade, clear);
            }
            if (right) {
                vector.horizontalGradient(box.right() - width, box.y + 1f, box.right(), box.bottom() - 1f, 0f,
                        clear, shade);
            }
            return;
        }
        int steps = 6;
        for (int step = 0; step < steps; step++) {
            float from = step / (float) steps;
            float to = (step + 1) / (float) steps;
            int stepShade = Skin.argb(0.55f * (1f - from), 0x05060A);
            if (left) {
                ui.fill(box.x + from * width, box.y + 1f, box.x + to * width, box.bottom() - 1f, stepShade);
            }
            if (right) {
                ui.fill(box.right() - to * width, box.y + 1f, box.right() - from * width, box.bottom() - 1f,
                        stepShade);
            }
        }
    }

    // -----------------------------------------------------------------------
    // Dropdown
    // -----------------------------------------------------------------------

    /** A value with a list behind it; the list is drawn by {@link #drawOverlays}. */
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
        float spin = active(s, open);

        int face = Skin.mix(Skin.mix(Skin.RECESSED, Skin.ELEVATED, Math.max(s.hover, spin)),
                Skin.RECESSED, s.press);
        float radius = Skin.pill(area);
        VisualEffects.layeredSurface(ui, area, radius, face, s.hover);
        int edge = Skin.mix(Skin.mix(Skin.EDGE_SURFACE, Skin.EDGE_ELEVATED, s.hover),
                Skin.ENERGY_EDGE, spin * 0.82f);
        ui.outline(area.x, area.y, area.right(), area.bottom(), radius,
                Skin.BORDER_STANDARD, edge);
        focusRing(ui, s, area, radius);

        float chevron = 12f;
        String value = options[Math.max(0, Math.min(options.length - 1, selected))];
        middle(ui, value, area.x + Skin.S3, area, area.width - Skin.S3 * 2f - chevron - Skin.S1, Type.BODY,
                Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, Math.max(s.hover, spin)));
        ui.icon(Icon.CHEVRON_DOWN, area.right() - Skin.S3 - chevron, area.centreY() - chevron / 2f + spin,
                chevron, Skin.mix(Skin.TEXT_FAINT, Skin.TEXT, Math.max(s.hover, spin)));

        if (open) {
            overlays.add(overlay(id, area, options, selected, false));
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

    /** A value between two ends. The press is captured, so dragging off the track keeps working. */
    public double slider(Renderer ui, String id, Rect area, double fraction, boolean enabled) {
        State s = state(id, area, enabled);
        boolean dragging = inputEnabled && id.equals(pressedId) && pointer != null && pointer.down(0);
        double value = Math.max(0d, Math.min(1d, fraction));

        float trackHeight = 4f;
        float trackY = area.centreY() - trackHeight / 2f;
        float knobMax = 9f;
        float travelLeft = area.x + knobMax;
        float travel = Math.max(1f, area.width - knobMax * 2f);
        if (dragging && enabled) {
            value = Math.max(0d, Math.min(1d, (pointer.x() - travelLeft) / travel));
        }

        String progressId = id + "#progress";
        TrackedMotion tracked = motions.get(progressId);
        if (tracked == null) {
            tracked = new TrackedMotion((float) value, Timing.TOGGLE);
            motions.put(progressId, tracked);
        }
        tracked.seen = frame;
        if (dragging) {
            // A control under the pointer must follow it exactly. Animation is
            // for external/programmatic changes, not input latency.
            tracked.motion.set((float) value, now);
        } else {
            tracked.motion.to((float) value, now, Timing.TOGGLE);
        }
        float drawnValue = tracked.motion.value(now);

        ui.rounded(area.x, trackY, area.right(), trackY + trackHeight, trackHeight / 2f,
                Skin.mix(Tokens.CONTROL_FILL_SUNKEN, Tokens.CONTROL_FILL_SUNKEN_HOVER, s.hover));
        float filled = travelLeft + travel * drawnValue;
        ui.rounded(area.x, trackY, filled, trackY + trackHeight, trackHeight / 2f,
                enabled ? Skin.mix(Skin.ACCENT_DEEP, Skin.ACCENT, 0.6f + 0.4f * s.hover) : Skin.fade(Skin.ACCENT, 0.4f));

        // Hover may change colour, never geometry. Growing this knob made the
        // control visibly change size as the pointer crossed it.
        float knob = 8f;
        float cy = area.centreY();
        ui.shadow(filled - knob, cy - knob, filled + knob, cy + knob, knob, 2.5f, Skin.SHADOW);
        ui.rounded(filled - knob, cy - knob, filled + knob, cy + knob, knob,
                enabled ? Tokens.PALETTE_IVORY_50 : Tokens.PALETTE_CHAMPAGNE_300);
        focusRing(ui, s, area, Skin.R_SMALL);
        return value;
    }

    // -----------------------------------------------------------------------
    // Keybind
    // -----------------------------------------------------------------------

    /** A key, and a way to change it. @return true when clicked: start listening. */
    public boolean keybind(Renderer ui, String id, Rect area, String key, boolean listening) {
        State s = state(id, area, true);
        float listen = active(s, listening);
        float pulse = listening ? 0.5f + 0.5f * (float) Math.sin(now * 6.0) : 0f;
        int face = Skin.mix(Skin.mix(Skin.mix(Skin.RECESSED, Skin.ELEVATED, s.hover), Skin.BACKGROUND, s.press),
                Skin.scaleAlpha(Skin.ENERGY, 0.28f), listen);
        float radius = Skin.pill(area);
        VisualEffects.layeredSurface(ui, area, radius, face, s.hover);
        if (listen > 0.01f) {
            ui.outline(area.x, area.y, area.right(), area.bottom(), radius,
                    Skin.BORDER_ENERGIZED,
                    Skin.scaleAlpha(Skin.ENERGY_MARK, listen * (0.64f + 0.26f * pulse)));
        }
        focusRing(ui, s, area, radius);
        ui.textCentred(
                Text.fit(ui, listening ? "Press a key…" : key, Type.BODY_STRONG, area.width - Skin.S2),
                area.centreX(), baseline(ui, area, Type.BODY_STRONG), Type.BODY_STRONG,
                listening ? Skin.TEXT : Skin.mix(Skin.TEXT_MUTED, Skin.TEXT, s.hover));
        return s.clicked;
    }

    // -----------------------------------------------------------------------
    // Stepper
    // -----------------------------------------------------------------------

    /** A whole number with a minus and a plus. @return -1, 0 or 1 */
    public int stepper(Renderer ui, String id, Rect area, String value, boolean atMinimum, boolean atMaximum) {
        float button = Math.min(area.height, 26f);
        Rect down = new Rect(area.x, area.y, button, area.height);
        Rect up = new Rect(area.right() - button, area.y, button, area.height);

        float radius = Skin.pill(area);
        ui.rounded(area.x, area.y, area.right(), area.bottom(), radius, Skin.RECESSED);
        ui.outline(area.x, area.y, area.right(), area.bottom(), radius,
                Skin.BORDER_STANDARD, Skin.EDGE_RECESSED);
        ui.textCentred(value, area.centreX(), baseline(ui, area, Type.BODY_STRONG), Type.BODY_STRONG, Skin.TEXT);

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

    /** A short line of text; typing is the screen's. @return clicked, i.e. take focus */
    public boolean textField(Renderer ui, String id, Rect area, String value, boolean editing) {
        State s = state(id, area, true);
        float edit = active(s, editing);
        int face = Skin.mix(Skin.mix(Skin.RECESSED, Skin.ELEVATED, s.hover * (1f - edit)), Skin.BACKGROUND,
                s.press);
        float radius = Skin.pill(area);
        VisualEffects.layeredSurface(ui, area, radius, face, s.hover);
        if (edit > 0.01f) {
            ui.outline(area.x, area.y, area.right(), area.bottom(), radius,
                    Skin.BORDER_ENERGIZED, Skin.scaleAlpha(Skin.ENERGY_EDGE, edit));
        }
        focusRing(ui, s, area, radius);

        float room = area.width - Skin.S3 * 2f;
        boolean placeholder = value.isEmpty() && !editing;
        String shown = Text.fit(ui, placeholder ? "None" : value, Type.BODY, room);
        float baseline = baseline(ui, area, Type.BODY);
        ui.text(shown, area.x + Skin.S3, baseline, Type.BODY, placeholder ? Skin.TEXT_DISABLED : Skin.TEXT);
        if (editing) {
            float phase = (now % 1.06f) / 1.06f;
            if (phase < 0.55f) {
                float caret = area.x + Skin.S3 + ui.measure(shown, Type.BODY) + 1f;
                ui.fill(caret, area.y + 6f, caret + 1.5f, area.bottom() - 6f, Skin.ENERGY_MARK);
            }
        }
        return s.clicked;
    }

    // -----------------------------------------------------------------------
    // Colour
    // -----------------------------------------------------------------------

    public static final int[] PALETTE = {
        0xFFFFFF, 0xC4303C, 0xE0505C, 0xE0AE57, 0x4FC98A, 0x5B9BD5, 0xB37FE8, 0x8A93A3, 0x000000
    };

    private static final String[] PALETTE_LABELS = {
        "White", "Fathom red", "Light red", "Amber", "Green", "Blue", "Violet", "Grey", "Black"
    };

    /** A colour with a palette behind it. @return the chosen RGB */
    public int colour(Renderer ui, String id, Rect area, int rgb) {
        boolean open = id.equals(openId);
        Rect box = area.vcentreRight(0f, SWATCH, SWATCH);
        State s = state(id, box, true);
        if (s.clicked) {
            openId = open ? null : id;
            open = !open;
        }
        // Press may scale; hover may not. A 4.5% hover scale on a swatch this
        // small is a visible jump of the thing you are trying to click.
        float scale = 1f - s.press * 0.018f;
        ui.pushTransform(0f, 0f, scale, box.centreX(), box.centreY());
        ui.rounded(box.x, box.y, box.right(), box.bottom(), Skin.R_SMALL, Skin.RECESSED);
        Rect colourFace = box.inset(3f, 3f);
        ui.rounded(colourFace.x, colourFace.y, colourFace.right(), colourFace.bottom(),
                Skin.innerRadius(Skin.R_SMALL, box, 3f), 0xFF000000 | rgb);
        ui.outline(colourFace.x, colourFace.y, colourFace.right(), colourFace.bottom(),
                Skin.innerRadius(Skin.R_SMALL, box, 3f), Skin.HAIRLINE,
                Skin.EDGE_ELEVATED);
        ui.outline(box.x, box.y, box.right(), box.bottom(), Skin.R_SMALL, Skin.BORDER_STANDARD,
                open ? Skin.ENERGY_MARK : Skin.mix(Skin.EDGE_SURFACE, Skin.EDGE_ELEVATED, s.hover));
        ui.popTransform();
        focusRing(ui, s, box, Skin.R_SMALL);

        if (open) {
            overlays.add(overlay(id, box, PALETTE_LABELS, indexOf(rgb), true));
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

    /** Reuse the one or two overlay descriptors a frame can need. */
    private Overlay overlay(String id, Rect anchor, String[] options, int selected, boolean swatches) {
        Overlay value;
        if (overlayCursor == overlayPool.size()) {
            value = new Overlay();
            overlayPool.add(value);
        } else {
            value = overlayPool.get(overlayCursor);
        }
        overlayCursor++;
        return value.set(id, anchor, options, selected, swatches);
    }

    // -----------------------------------------------------------------------
    // Rows and tooltips
    // -----------------------------------------------------------------------

    /** The background behind a list row: hover and selection, animated. */
    public void rowBackground(Renderer ui, String id, Rect area, boolean hovered, boolean selected) {
        Slot slot = slot(id);
        slot.hover.to(hovered && inputEnabled ? 1f : 0f, now, hovered ? HOVER_IN : HOVER_OUT);
        if (!slot.activeInitialised) {
            slot.activeInitialised = true;
            slot.active.set(selected ? 1f : 0f, now);
        }
        slot.active.to(selected ? 1f : 0f, now);
        float hover = slot.hover.value(now);
        float mark = slot.active.value(now);
        if (hover > 0.01f || mark > 0.01f) {
            ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.pill(area),
                    Skin.mix(Skin.scaleAlpha(Skin.SURFACE_HOVER, hover), Skin.ELEVATED, mark));
        }
        if (mark > 0.01f) {
            ui.outline(area.x, area.y, area.right(), area.bottom(), Skin.pill(area), Skin.HAIRLINE,
                    Skin.scaleAlpha(Skin.EDGE_ELEVATED, mark));
        }
    }

    /**
     * Ask for a tooltip over a control once the pointer has rested on it.
     *
     * <p>Drawn by {@link #drawOverlays}, above everything and outside every clip.
     * The last control to ask in a frame wins, which is the innermost one.
     */
    public void tooltip(State s, Rect anchor, String text) {
        if (text == null || text.isEmpty() || !s.hovered || s.dwell() < TOOLTIP_DELAY) {
            return;
        }
        tooltipText = text;
        tooltipAnchor = anchor;
    }

    // -----------------------------------------------------------------------
    // Overlays
    // -----------------------------------------------------------------------

    /**
     * Draw every open popover and the tooltip, and take the clicks that land on
     * a popover. Last, so it is on top; outside every clip, so it can leave its
     * parent.
     */
    public void drawOverlays(Renderer ui) {
        overlayBlocking = false;
        Overlay current = overlays.isEmpty() ? null : overlays.get(overlays.size() - 1);
        if (current == null && openId != null) {
            // The control that owned this popover disappeared (normally after
            // a category switch). Do not resurrect a stale dropdown when that
            // page is visited again.
            openId = null;
        }
        if (current != null) {
            if (visibleOverlay == null || !visibleOverlay.id.equals(current.id)) {
                popoverMotion.set(0f, now);
            }
            visibleOverlay = current;
            popoverMotion.to(1f, now, Timing.PANEL, Ease.Curve.STANDARD);
        } else if (visibleOverlay != null) {
            popoverMotion.to(0f, now, Timing.SCREEN_EXIT, Ease.Curve.EXIT);
        }

        float shown = popoverMotion.opacity(now);
        if (visibleOverlay != null && shown > 0.001f) {
            boolean interactive = current != null;
            if (visibleOverlay.swatches) {
                drawSwatches(ui, visibleOverlay, shown, interactive);
            } else {
                drawList(ui, visibleOverlay, shown, interactive);
            }
        }
        if (current != null && inputEnabled && pointer != null && pointer.pressed(0) && !overlayBlocking) {
            openId = null;
        }
        if (current == null && popoverMotion.settled(now)) {
            visibleOverlay = null;
        }
        drawTooltip(ui);
    }

    private void drawTooltip(Renderer ui) {
        boolean showing = tooltipText != null && overlays.isEmpty() && visibleOverlay == null;
        if (showing) {
            lastTooltipText = tooltipText;
            lastTooltipAnchor = tooltipAnchor;
        }
        tooltipMotion.to(showing ? 1f : 0f, now, Timing.TOOLTIP);
        float tooltipAlpha = tooltipMotion.opacity(now);
        if (!showing && tooltipMotion.settled(now)) {
            lastTooltipText = null;
            lastTooltipAnchor = null;
        }
        if (lastTooltipText == null || lastTooltipAnchor == null || tooltipAlpha <= 0.01f) {
            return;
        }
        float width = Math.min(280f, ui.measure(lastTooltipText, Type.SMALL) + Skin.S3 * 2f);
        float height = 26f;
        float left = Math.max(Skin.S3,
                Math.min(lastTooltipAnchor.centreX() - width / 2f, ui.width() - width - Skin.S3));
        float top = lastTooltipAnchor.y - height - 6f;
        if (top < Skin.S3) {
            top = lastTooltipAnchor.bottom() + 6f;
        }
        ui.pushAlpha(tooltipAlpha);
        ui.pushTransform(0f, (1f - tooltipAlpha) * 3f,
                0.985f + tooltipAlpha * 0.015f,
                left + width / 2f, top + height / 2f);
        Rect tooltip = new Rect(left, top, width, height);
        VisualEffects.overlayShadow(ui, tooltip, Skin.R_CONTROL);
        VisualEffects.layeredSurface(ui, tooltip, Skin.R_CONTROL, Skin.FLOATING, 0f);
        ui.outline(left, top, left + width, top + height, Skin.R_CONTROL,
                Skin.BORDER_STANDARD, Skin.EDGE_ELEVATED);
        ui.text(Text.fit(ui, lastTooltipText, Type.SMALL, width - Skin.S3 * 2f), left + Skin.S3,
                baseline(ui, top, height, Type.SMALL), Type.SMALL, Skin.TEXT_MUTED);
        ui.popTransform();
        ui.popAlpha();
    }

    private void drawList(Renderer ui, Overlay overlay, float grown, boolean interactive) {
        float rowHeight = 30f;
        float width = Math.max(overlay.anchor.width, 160f);
        float height = overlay.options.length * rowHeight + Skin.S1 * 2f;
        boolean upwards = overlay.anchor.bottom() + height > ui.height() - Skin.S4;
        float top = upwards ? overlay.anchor.y - height - Skin.S1 : overlay.anchor.bottom() + Skin.S1;
        float left = Math.min(overlay.anchor.x, ui.width() - width - Skin.S4);
        Rect panel = new Rect(left, top, width, height);

        ui.pushAlpha(grown);
        ui.pushTransform(0f, (1f - grown) * (upwards ? 4f : -4f));
        VisualEffects.overlayShadow(ui, panel, Skin.R_MEDIUM);
        VisualEffects.layeredSurface(ui, panel, Skin.R_MEDIUM, Skin.FLOATING, 0f);
        ui.outline(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_MEDIUM,
                Skin.BORDER_STANDARD, Skin.mix(Skin.EDGE_ELEVATED, Skin.ENERGY_EDGE, 0.40f));

        for (int index = 0; index < overlay.options.length; index++) {
            Rect row = new Rect(panel.x + Skin.S1, panel.y + Skin.S1 + rowHeight * index,
                    panel.width - Skin.S1 * 2f, rowHeight);
            boolean over = interactive && inputEnabled && pointer != null
                    && row.contains(pointer.x(), pointer.y());
            boolean active = index == overlay.selected;
            if (over) {
                ui.rounded(row.x, row.y, row.right(), row.bottom(), Skin.R_CONTROL, Skin.SURFACE_HOVER);
            }
            ui.text(Text.fit(ui, overlay.options[index], Type.BODY, row.width - Skin.S6), row.x + Skin.S3,
                    baseline(ui, row, Type.BODY), Type.BODY, active || over ? Skin.TEXT : Skin.TEXT_MUTED);
            if (active) {
                ui.icon(Icon.CHECK, row.right() - Skin.S3 - 12f, row.centreY() - 6f, 12f, Skin.ENERGY_MARK);
            }
            if (over && pointer.released(0)) {
                choiceId = overlay.id;
                choiceIndex = index;
                openId = null;
            }
        }
        ui.popTransform();
        ui.popAlpha();
        blockIfOver(panel);
    }

    private void drawSwatches(Renderer ui, Overlay overlay, float grown, boolean interactive) {
        int columns = 3;
        float cell = 30f;
        int rows = (PALETTE.length + columns - 1) / columns;
        float width = columns * cell + Skin.S2 * 2f;
        float height = rows * cell + Skin.S2 * 2f;
        boolean upwards = overlay.anchor.bottom() + height > ui.height() - Skin.S4;
        float top = upwards ? overlay.anchor.y - height - Skin.S1 : overlay.anchor.bottom() + Skin.S1;
        float left = Math.min(overlay.anchor.x + overlay.anchor.width - width, ui.width() - width - Skin.S4);
        Rect panel = new Rect(Math.max(Skin.S4, left), top, width, height);

        ui.pushAlpha(grown);
        VisualEffects.overlayShadow(ui, panel, Skin.R_MEDIUM);
        VisualEffects.layeredSurface(ui, panel, Skin.R_MEDIUM, Skin.FLOATING, 0f);
        ui.outline(panel.x, panel.y, panel.right(), panel.bottom(), Skin.R_MEDIUM,
                Skin.BORDER_STANDARD, Skin.mix(Skin.EDGE_ELEVATED, Skin.ENERGY_EDGE, 0.40f));

        for (int index = 0; index < PALETTE.length; index++) {
            float cx = panel.x + Skin.S2 + (index % columns) * cell;
            float cy = panel.y + Skin.S2 + (index / columns) * cell;
            Rect swatch = new Rect(cx + 3f, cy + 3f, cell - 6f, cell - 6f);
            boolean over = interactive && inputEnabled && pointer != null
                    && swatch.inset(-3f).contains(pointer.x(), pointer.y());
            ui.rounded(swatch.x, swatch.y, swatch.right(), swatch.bottom(), Skin.R_SMALL, 0xFF000000 | PALETTE[index]);
            ui.outline(swatch.x, swatch.y, swatch.right(), swatch.bottom(), Skin.R_SMALL, Skin.HAIRLINE,
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
