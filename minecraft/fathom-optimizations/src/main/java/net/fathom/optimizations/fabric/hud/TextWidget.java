package net.fathom.optimizations.fabric.hud;

import java.util.List;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;

/**
 * A widget that is some lines of text on a plate.
 *
 * <p>Nine of the eleven widgets are this, which is why it is a base class rather than
 * repeated: the interesting part of an FPS readout is the string, not the rectangle behind
 * it.
 *
 * <h2>Why every common setting is read here</h2>
 *
 * <p>Text colour, text shadow, background, background colour and opacity,
 * padding, corner radius and drop shadow are declared once by
 * {@link HudStyle#declare} and read once here. Nine widgets therefore honour all
 * eight without any of them containing code for it, and — the point — a setting
 * cannot be declared for a widget that ignores it, because there is only one
 * place that draws.
 *
 * <h2>Why a plate at all</h2>
 *
 * <p>White text with a drop shadow is legible over most of Minecraft and illegible over
 * snow, sand and the sky at noon. A plate makes the widget readable everywhere, which is
 * the whole job. It scales with the text rather than being a fixed size, so a
 * two-character CPS readout is not a banner.
 */
public abstract class TextWidget implements HudWidget {

    /** Gap between lines, on top of the font height. */
    protected static final int LINE_GAP = 1;

    private final String id;
    private final String placeholder;

    protected TextWidget(String id, String placeholder) {
        this.id = id;
        this.placeholder = placeholder;
    }

    @Override
    public final String id() {
        return id;
    }

    @Override
    public final String placeholder() {
        return placeholder;
    }

    /** The lines to draw. Empty means there is nothing to say. */
    protected abstract List<String> lines(MinecraftClient client);

    /**
     * The module this widget belongs to, for its settings.
     *
     * <p>Looked up rather than held, because the registry is sealed after the
     * widgets are built and a held reference would be a null on the first frame.
     */
    protected final FathomModule module() {
        return Widgets.module(id);
    }

    /**
     * The colour for one line, given what the settings say.
     *
     * <p>Overridden by Ping, which colours by latency. Everything else takes the
     * declared text colour, which is why that is the argument rather than
     * something the override has to look up for itself.
     */
    protected int colourFor(int index, String line, int textColour) {
        return textColour;
    }

    /** Padding between the text and the plate's edge, as declared. */
    protected final int padding() {
        return HudStyle.padding(module());
    }

    @Override
    public boolean visible(MinecraftClient client) {
        List<String> lines = lines(client);
        return lines != null && !lines.isEmpty();
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        float widest = font.width(placeholder);
        List<String> lines = lines(client);
        if (lines != null) {
            for (String line : lines) {
                widest = Math.max(widest, font.width(line));
            }
        }
        // Rounded up, not to nearest. A proportional face measures in fractions
        // of a scaled pixel, and a plate half a pixel narrower than its text is
        // a plate with the last letter hanging off it.
        return (int) Math.ceil(widest) + padding() * 2;
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        List<String> lines = lines(client);
        int count = lines == null || lines.isEmpty() ? 1 : lines.size();
        return (int) Math.ceil(count * (font.lineHeight() + LINE_GAP) - LINE_GAP) + padding() * 2;
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        List<String> lines = lines(client);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        FathomModule module = module();
        int pad = HudStyle.padding(module);
        int boxWidth = width(client, font);
        int boxHeight = height(client, font);

        if (HudStyle.background(module)) {
            // The plate takes a fraction of the widget's opacity, so turning a
            // widget down turns the whole thing down rather than leaving a solid
            // rectangle behind faded text.
            int plateAlpha = HudStyle.backgroundAlpha(module) * alpha / 255;
            Plate.draw(x, y, boxWidth, boxHeight,
                    (plateAlpha << 24) | (HudStyle.backgroundColour(module) & 0x00FFFFFF),
                    HudStyle.radius(module),
                    HudStyle.dropShadow(module));
        }

        int textColour = HudStyle.textColour(module) & 0x00FFFFFF;
        boolean shadow = HudStyle.textShadow(module);
        float lineY = y + pad;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int colour = (alpha << 24) | (colourFor(index, line, textColour) & 0x00FFFFFF);
            font.draw(line, x + pad, lineY, colour, shadow);
            lineY += font.lineHeight() + LINE_GAP;
        }
    }
}
