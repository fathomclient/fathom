package net.fathom.optimizations.fabric.hub;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

import net.fathom.optimizations.core.module.ClientPrefs;
import net.fathom.optimizations.core.module.Keybind;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Box;
import net.fathom.optimizations.core.ui.render.Icon;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.core.ui.VideoOptionDomain;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.FathomComponents;
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

    private static final float ROW = Skin.ROW_HEIGHT;
    /** A slider is one row: name, track, value. It was two, the track under the name. */
    private static final float SLIDER_ROW = Skin.ROW_HEIGHT;
    /** The name's column and the value's, either side of the track. */
    private static final float SLIDER_LABEL = 168f;
    private static final float SLIDER_VALUE = 96f;
    private static final float CONTROL = 220f;

    /**
     * The interface-scale control is wider than the rest.
     *
     * <p>It has six options where the others have two or four. The natural-width
     * segmented control measures those labels with the active renderer and can
     * pan rather than clipping if a narrow window cannot give it all 320 units.
     */
    /** Seven options -- Auto and six sizes -- at their natural widths. */
    private static final float SCALE_CONTROL = 372f;

    /** Retained page geometry: built once, read by drawing and hit testing. */
    private final Box page = Box.column("quick.page").gap(Skin.SECTION_GAP);
    private final Box fathomGroup = Box.column("quick.fathom").padding(Skin.CARD_PADDING);
    private final Box fathomLabel = Box.column("quick.fathom.label");
    private final Box[] fathomRows = new Box[2];
    private final Box videoGroup = Box.column("quick.video").padding(Skin.CARD_PADDING);
    private final Box videoLabel = Box.column("quick.video.label");
    private final Box[] videoRows = new Box[4];
    private final Box displayGroup = Box.column("quick.display").padding(Skin.CARD_PADDING);
    private final Box displayLabel = Box.column("quick.display.label");
    private final Box[] displayRows = new Box[3];
    private final Box footerBox = Box.column("quick.footer");

    QuickSection() {
        fathomGroup.add(fathomLabel);
        for (int index = 0; index < fathomRows.length; index++) {
            fathomRows[index] = Box.row("quick.fathom.row." + index).height(ROW);
            fathomGroup.add(fathomRows[index]);
        }
        videoGroup.add(videoLabel);
        for (int index = 0; index < videoRows.length; index++) {
            videoRows[index] = Box.row("quick.video.row." + index).height(SLIDER_ROW);
            videoGroup.add(videoRows[index]);
        }
        displayGroup.add(displayLabel);
        for (int index = 0; index < displayRows.length; index++) {
            displayRows[index] = Box.row("quick.display.row." + index).height(ROW);
            displayGroup.add(displayRows[index]);
        }
        page.add(fathomGroup);
        page.add(videoGroup);
        page.add(displayGroup);
        page.add(footerBox);
    }

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
        float width = Math.min(Skin.COLUMN_WIDE, area.width - Skin.SCROLL_GUTTER);
        float labelStep = ui.lineHeight(Type.LABEL) + Skin.S2;

        // Every height here is a constant, so the page can be measured before it
        // is drawn rather than after. It has to be: at 1280x720 the last two
        // rows fall past the bottom of the panel, and until this page had a
        // scroller they were simply unreachable.
        float groupPad = Skin.CARD_PADDING;
        float fathomHeight = groupPad * 2f + labelStep + ROW * 2f;
        float videoHeight = groupPad * 2f + labelStep + SLIDER_ROW * 4f;
        float displayHeight = groupPad * 2f + labelStep + ROW * 3f;
        float footer = Skin.SECTION_GAP + ui.lineHeight(Type.SMALL);
        float contentHeight = fathomHeight + videoHeight + displayHeight
                + Skin.SECTION_GAP * 2f + footer;
        float offset = scroller.offset(context.delta(), contentHeight, area.height);

        fathomLabel.height(labelStep);
        videoLabel.height(labelStep);
        displayLabel.height(labelStep);
        footerBox.height(footer);
        page.width(width).height(contentHeight);
        page.layout(ui, area.x, area.y - offset, width, contentHeight);

        ui.clip(area.x, area.y, area.right(), area.bottom());
        Rect fathom = fathomGroup.rect();
        FathomComponents.panel(ui, fathom);
        Controls.label(ui, "Fathom", fathomGroup.content().x, fathomGroup.content().y);
        hubKeyRow(context, fathomRows[0].rect());
        scaleRow(context, fathomRows[1].rect());

        Rect video = videoGroup.rect();
        FathomComponents.panel(ui, video);
        Controls.label(ui, "Video", videoGroup.content().x, videoGroup.content().y);
        slider(context, videoRows[0].rect(), "quick.distance", "Render distance",
                options.viewDistance + " chunks", fraction(options.viewDistance, 2, 16),
                VideoSetting.VIEW_DISTANCE);
        slider(context, videoRows[1].rect(), "quick.fps", "Frame limit",
                frameLimit(options.maxFramerate), fraction(options.maxFramerate, 10, 260),
                VideoSetting.FRAME_LIMIT);
        slider(context, videoRows[2].rect(), "quick.fov", "Field of view",
                fov(options.fov), VideoOptionDomain.fovFraction(options.fov),
                VideoSetting.FOV);
        slider(context, videoRows[3].rect(), "quick.gamma", "Brightness",
                brightness(options.gamma), options.gamma, VideoSetting.GAMMA);

        Rect display = displayGroup.rect();
        FathomComponents.panel(ui, display);
        Controls.label(ui, "Display", displayGroup.content().x, displayGroup.content().y);
        toggle(context, displayRows[0].rect(), "quick.vsync", "VSync", options.vsync);
        toggle(context, displayRows[1].rect(), "quick.bob", "View bobbing", options.bobView);
        toggle(context, displayRows[2].rect(), "quick.shadows", "Entity shadows",
                options.entityShadows);

        Rect footerArea = footerBox.rect();
        ui.text("Changes apply instantly.", footerArea.x + Skin.S1, footerArea.y + ui.ascent(Type.SMALL),
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

        // Against the row's right edge, where every other row's control ends.
        float natural = context.controls().segmentedNaturalWidth(
                ui, ClientPrefs.SCALE_CHOICES, SCALE_CONTROL);
        Rect control = row.vcentreRight(0f, natural, Skin.H_SM);
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
        float trackLeft = row.x + Math.min(SLIDER_LABEL, row.width * 0.35f);
        net.fathom.optimizations.fabric.ui.FathomSettingRows.label(ui, row, row.x, trackLeft - row.x - Skin.S3,
                name, null, true);
        ui.textRight(value, row.right(), Controls.baseline(ui, row, Type.BODY), Type.BODY, Skin.TEXT_MUTED);

        float trackRight = row.right() - SLIDER_VALUE;
        Rect track = new Rect(trackLeft, row.centreY() - Controls.SLIDER_HEIGHT / 2f,
                Math.max(40f, trackRight - trackLeft), Controls.SLIDER_HEIGHT);
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

    /** The shared settings-row label, so Quick reads as a module page does. */
    private void label(HubContext context, Rect row, String name, String detail) {
        net.fathom.optimizations.fabric.ui.FathomSettingRows.label(context.ui(), row, row.x, row.width * 0.55f,
                name, detail, true);
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
