package net.fathom.optimizations.fabric.hub;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.core.ui.VideoOptionDomain;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.Skin;

/**
 * Quick Settings: the handful of options people change mid-session.
 *
 * <h2>Why these and not the whole video menu</h2>
 *
 * <p>A copy of the video menu is a copy of the video menu, and there is already
 * one two keypresses away. What earns a place here is a setting somebody changes
 * <em>during</em> a session for a reason that has just come up: the view
 * distance because a lobby is heavy, the frame cap because the fans came on, the
 * FOV because they are about to fight someone, brightness because they went into
 * a cave.
 *
 * <h2>Fathom's own two</h2>
 *
 * <p>The key that opens the hub, and how large the hub is. The scale is
 * deliberately separate from Minecraft's GUI Scale: somebody who plays the game
 * on Large should not get a Fathom Hub with forty-pixel type.
 *
 * <h2>Why Minecraft's options save immediately</h2>
 *
 * <p>They live in {@code options.txt} and the game writes that file itself.
 * Deferring them to the hub's own save would mean a crash between the change and
 * the close reverted somebody's brightness without explanation.
 */
final class QuickSection implements Section {

    /**
     * A slider target is a type, never an integer supplied by input.
     *
     * <p>This boundary matters: LWJGL key codes are integers too. The old
     * {@code which} switch made it possible for a capture-routing mistake to
     * turn a pressed key into a video-setting selector; FOV happened to be
     * selector 2. There is no numeric path from key capture to these setters
     * now.
     */
    private enum VideoSetting {
        VIEW_DISTANCE,
        FRAME_LIMIT,
        FOV,
        GAMMA
    }

    private final Scroller scroller = new Scroller();

    private static final float ROW = 44f;
    private static final float SLIDER_ROW = 58f;
    private static final float CONTROL = 220f;

    /**
     * The interface-scale control is wider than the rest.
     *
     * <p>It has six options where the others have two or four. The natural-width
     * segmented control measures those labels with the active renderer and can
     * pan rather than clipping if a narrow window cannot give it all 320 units.
     */
    private static final float SCALE_CONTROL = 320f;

    @Override
    public String title() {
        return "Quick";
    }

    @Override
    public Icon icon() {
        return Icon.SLIDERS;
    }

    @Override
    public boolean searchable() {
        return false;
    }

    @Override
    public String subtitle() {
        return "Common settings.";
    }

    @Override
    public void scroll(int notches) {
        scroller.wheel(notches);
    }

    @Override
    public void opened(HubContext context) {
        scroller.reset();
    }

    @Override
    public void render(HubContext context, Rect area) {
        Renderer ui = context.ui();
        GameOptions options = context.client().options;
        if (options == null) {
            ui.text("Game options are not available.", area.x, area.y + ui.ascent(Type.BODY), Type.BODY, Skin.WARN);
            return;
        }

        // One column, against the left margin. A row is a name and a control at
        // opposite ends of it, and at 2560 the full content width would put an
        // arm's length of nothing between the two — while the slider tracks,
        // which had their own 360-unit cap, ended a third of the way across and
        // read as broken rather than as short.
        float width = Math.min(Skin.COLUMN, area.width - Skin.S4);
        float labelStep = ui.lineHeight(Type.LABEL) + Skin.S2;

        // Every height here is a constant, so the page can be measured before it
        // is drawn rather than after. It has to be: at 1280x720 the last two
        // rows fall past the bottom of the panel, and until this page had a
        // scroller they were simply unreachable.
        float footer = Skin.S4 + ui.lineHeight(Type.SMALL);
        float contentHeight = labelStep * 3f + ROW * 5f + SLIDER_ROW * 4f
                + Skin.S4 * 2f + footer;
        float offset = scroller.offset(context.delta(), contentHeight, area.height);

        ui.clip(area.x, area.y, area.right(), area.bottom());
        float y = area.y - offset;
        Controls.label(ui, "Fathom", area.x, y);
        y += labelStep;
        y = hubKeyRow(context, new Rect(area.x, y, width, ROW));
        y = scaleRow(context, new Rect(area.x, y, width, ROW));

        y += Skin.S4;
        Controls.label(ui, "Video", area.x, y);
        y += labelStep;
        y = slider(context, new Rect(area.x, y, width, SLIDER_ROW), "quick.distance", "Render distance",
                options.viewDistance + " chunks", fraction(options.viewDistance, 2, 16),
                VideoSetting.VIEW_DISTANCE);
        y = slider(context, new Rect(area.x, y, width, SLIDER_ROW), "quick.fps", "Frame limit",
                frameLimit(options.maxFramerate), fraction(options.maxFramerate, 10, 260),
                VideoSetting.FRAME_LIMIT);
        y = slider(context, new Rect(area.x, y, width, SLIDER_ROW), "quick.fov", "Field of view",
                fov(options.fov), VideoOptionDomain.fovFraction(options.fov),
                VideoSetting.FOV);
        y = slider(context, new Rect(area.x, y, width, SLIDER_ROW), "quick.gamma", "Brightness",
                brightness(options.gamma), options.gamma, VideoSetting.GAMMA);

        y += Skin.S4;
        Controls.label(ui, "Display", area.x, y);
        y += labelStep;
        y = toggle(context, new Rect(area.x, y, width, ROW), "quick.vsync", "VSync", options.vsync);
        y = toggle(context, new Rect(area.x, y, width, ROW), "quick.bob", "View bobbing", options.bobView);
        y = toggle(context, new Rect(area.x, y, width, ROW), "quick.shadows", "Entity shadows",
                options.entityShadows);

        y += Skin.S4;
        ui.text("Changes apply instantly.", area.x, y + ui.ascent(Type.SMALL),
                Type.SMALL, Skin.TEXT_DISABLED);

        ui.unclip();
        // Beside the column, not against the far edge of a panel the column
        // does not reach: a scrollbar half a screen away from what it scrolls
        // belongs to nothing.
        scroller.rail(ui, new Rect(area.x, area.y, width + Skin.S4, area.height),
                context.pointer(), context.now());
    }

    /**
     * The key that opens this menu.
     *
     * <p>First thing on the first page, because somebody whose Right Shift is
     * already bound to something needs to find this before they need anything
     * else.
     */
    private float hubKeyRow(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Rect row = area.inset(0f, 2f);
        label(context, row, "Fathom Hub key", "Opens and closes this menu.");
        Keybind key = context.hud().hubKey();
        Rect control = row.vcentreRight(0f, 140f, Skin.H_SM);
        boolean listening = context.keybindCapture().capturing(key);
        if (context.controls().keybind(ui, "quick.hubkey", control, key.keyName(), listening)) {
            context.keybindCapture().toggle(key);
        }
        return area.bottom();
    }

    /** How large Fathom's interface is, with what that currently works out to. */
    private float scaleRow(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Rect row = area.inset(0f, 2f);
        ClientPrefs prefs = context.hud().prefs();
        int framebufferWidth = Math.round(ui.width() * ui.scale());
        int framebufferHeight = Math.round(ui.height() * ui.scale());
        String effective = "Currently "
                + String.format(java.util.Locale.ROOT, "%.2f", Float.valueOf(ui.scale()))
                + "× on " + framebufferWidth + "×" + framebufferHeight;
        label(context, row, "Fathom UI scale", effective);

        Rect control = row.vcentreRight(0f, SCALE_CONTROL, Skin.H_SM);
        int chosen = context.controls().segmentedNatural(
                ui, "quick.uiscale", control, ClientPrefs.SCALE_CHOICES, prefs.scaleChoice());
        if (chosen != prefs.scaleChoice()) {
            prefs.setScaleChoice(chosen);
            context.markDirty();
        }
        return area.bottom();
    }

    private float slider(HubContext context, Rect area, String id, String name, String value, float fraction,
            VideoSetting setting) {
        Renderer ui = context.ui();
        Rect row = area.inset(0f, 2f);
        boolean hovered = row.contains(context.pointer().x(), context.pointer().y());
        ui.text(name, row.x, row.y + ui.ascent(Type.BODY), Type.BODY, hovered ? Skin.TEXT : Skin.TEXT_MUTED);
        ui.textRight(value, row.right(), row.y + ui.ascent(Type.BODY), Type.BODY, Skin.TEXT_FAINT);

        Rect track = new Rect(row.x, row.y + ui.lineHeight(Type.BODY) + Skin.S2,
                row.width, Controls.SLIDER_HEIGHT);
        double next = context.controls().slider(ui, id, track, fraction, true);
        if (Math.abs(next - fraction) > 0.0005d) {
            apply(context, setting, next);
        }
        return area.bottom();
    }

    private float toggle(HubContext context, Rect area, String id, String name, boolean on) {
        Rect row = area.inset(0f, 2f);
        label(context, row, name, null);
        Rect control = row.vcentreRight(0f, Controls.TOGGLE_WIDTH, Controls.TOGGLE_HEIGHT);
        if (context.controls().toggle(context.ui(), id, control, on, true)) {
            flip(context, id);
        }
        return area.bottom();
    }

    private void label(HubContext context, Rect row, String name, String detail) {
        Renderer ui = context.ui();
        boolean hovered = row.contains(context.pointer().x(), context.pointer().y());
        boolean twoLines = detail != null && !detail.isEmpty();
        if (!twoLines) {
            ui.text(name, row.x, Controls.baseline(ui, row, Type.BODY), Type.BODY,
                    hovered ? Skin.TEXT : Skin.TEXT_MUTED);
            return;
        }
        // Two lines centred as a block on their own heights, rather than each
        // one nudged a few units either side of the centre.
        float top = row.centreY() - (ui.lineHeight(Type.BODY) + ui.lineHeight(Type.CAPTION)) / 2f;
        ui.text(name, row.x, top + ui.ascent(Type.BODY), Type.BODY,
                hovered ? Skin.TEXT : Skin.TEXT_MUTED);
        ui.text(detail, row.x, top + ui.lineHeight(Type.BODY) + ui.ascent(Type.CAPTION),
                Type.CAPTION, Skin.TEXT_DISABLED);
    }

    private void flip(HubContext context, String id) {
        GameOptions options = context.client().options;
        if (options == null) {
            return;
        }
        if ("quick.vsync".equals(id)) {
            options.vsync = !options.vsync;
            // The one vanilla option that needs the display updating to take
            // effect, and vanilla has a method for exactly that.
            context.client().updateDisplay();
        } else if ("quick.bob".equals(id)) {
            options.bobView = !options.bobView;
        } else if ("quick.shadows".equals(id)) {
            options.entityShadows = !options.entityShadows;
        }
        save(context.client());
    }

    private void apply(HubContext context, VideoSetting setting, double fraction) {
        GameOptions options = context.client().options;
        if (options == null) {
            return;
        }
        switch (setting) {
            case VIEW_DISTANCE:
                options.viewDistance = 2 + (int) Math.round(fraction * 14);
                break;
            case FRAME_LIMIT:
                // Stepped in tens, and the top of the range means unlimited —
                // which is what vanilla's own slider does and what the number
                // 260 is doing in the range.
                options.maxFramerate = 10 + (int) Math.round(fraction * 25) * 10;
                break;
            case FOV:
                // In these 1.8.9 mappings the field is degrees (30..110).
                // Treating it as a modern 0..1 option produced a 1-degree FOV
                // and persisted options.txt as -1.725.
                options.fov = VideoOptionDomain.fovDegrees(fraction);
                break;
            case GAMMA:
                options.gamma = (float) fraction;
                break;
            default:
                throw new AssertionError(setting);
        }
        save(context.client());
    }

    private static void save(MinecraftClient client) {
        if (client != null && client.options != null) {
            client.options.save();
        }
    }

    private static float fraction(float value, float low, float high) {
        return (value - low) / (float) (high - low);
    }

    private static String frameLimit(int value) {
        return value >= 260 ? "Unlimited" : value + " fps";
    }

    /**
     * These 1.8.9 mappings store the actual 30-to-110 degree value.
     *
     * <p>Reproduced rather than approximated, so the number in Fathom's hub is
     * the number in the vanilla menu. Two clients disagreeing about a player's
     * FOV is a small thing that undermines everything else.
     */
    private static String fov(float value) {
        int degrees = Math.round(value);
        return degrees == 70 ? "70 (normal)" : String.valueOf(degrees);
    }

    private static String brightness(float value) {
        if (value <= 0.001f) {
            return "Moody";
        }
        if (value >= 0.999f) {
            return "Bright";
        }
        return Math.round(value * 100) + "%";
    }
}
