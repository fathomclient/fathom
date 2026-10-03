package net.fathom.optimizations.fabric.hud;

import java.util.List;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;

/**
 * A widget that is some lines of text on a plate.
 *
 * <p>Most of the widgets are this, which is why it is a base class rather than
 * repeated: the interesting part of an FPS readout is the string, not the
 * rectangle behind it.
 *
 * <h2>Why every common setting is read here</h2>
 *
 * <p>Font, text colour, text shadow, background, background colour and opacity,
 * border, border colour and width, padding, corner radius and drop shadow are
 * declared once by {@link HudStyle#declare} and read once here. Every text
 * widget therefore honours all of them without any of them containing code for
 * it, and — the point — a setting cannot be declared for a widget that ignores
 * it, because there is only one place that draws.
 *
 * <h2>Why a plate at all</h2>
 *
 * <p>White text with a drop shadow is legible over most of Minecraft and
 * illegible over snow, sand and the sky at noon. A plate makes the widget
 * readable everywhere, which is the whole job. It scales with the text rather
 * than being a fixed size, so a two-character CPS readout is not a banner.
 *
 * <h2>Two rules that stop a readout twitching</h2>
 *
 * <p>A coordinate readout changes width as you walk: 99 becomes 100, and -1000
 * becomes -999. Two rules keep that from moving the whole widget about:
 *
 * <ul>
 *   <li><b>Lines are aligned to the widget's own anchor.</b> A widget anchored
 *       to the right edge draws its lines right-aligned, so the digits that
 *       change are the ones nearest the middle of the screen and the edge stays
 *       still. {@link #align} is told the anchor before each frame.
 *   <li><b>The box shrinks late.</b> It grows the moment the text needs it and
 *       gives the space back only after the text has stayed narrower for
 *       {@link #SHRINK_DELAY_NANOS}, so a number oscillating across a digit
 *       boundary does not make the plate flicker between two widths.
 * </ul>
 */
public abstract class TextWidget implements HudWidget {

    /** Gap between lines, on top of the font height. */
    protected static final int LINE_GAP = 1;

    /** How long the text has to stay narrow before the plate gives the width back. */
    private static final long SHRINK_DELAY_NANOS = 700_000_000L;

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

    /*
     * One frame's answers, computed once.
     *
     * A widget is asked for its lines to test visibility, to size its box
     * (twice) and to draw, and the line builder allocates strings each time --
     * six to eight builds a frame per widget. They are remembered for the
     * frame number Widgets.frame advances; sizes are also keyed on the font,
     * because the same frame can measure in both typefaces.
     */
    private long linesFrame = -1L;
    private List<String> frameLines;
    private long sizeFrame = -1L;
    private HudFont sizeFont;
    private int frameWidth;
    private int frameHeight;
    private int stableWidth;
    private long narrowSince;

    /** 0 at a left anchor, 0.5 centred, 1 at a right anchor. */
    private float alignment;

    @Override
    public void align(float horizontal) {
        alignment = horizontal;
    }

    /** Where the lines sit inside the box. */
    protected final float alignment() {
        return alignment;
    }

    /** The line builder, at most once per HUD frame. */
    protected final List<String> currentLines(MinecraftClient client) {
        long frame = Widgets.frameNumber();
        if (frame != linesFrame || frameLines == null) {
            frameLines = lines(client);
            linesFrame = frame;
        }
        return frameLines;
    }

    private void measure(MinecraftClient client, HudFont font) {
        long frame = Widgets.frameNumber();
        if (frame == sizeFrame && font == sizeFont) {
            return;
        }
        List<String> lines = currentLines(client);
        float widest = font.width(placeholder);
        if (lines != null) {
            for (int index = 0; index < lines.size(); index++) {
                widest = Math.max(widest, font.width(lines.get(index)));
            }
        }
        int pad = padding();
        // Rounded up, not to nearest. A proportional face measures in fractions
        // of a scaled pixel, and a plate half a pixel narrower than its text is
        // a plate with the last letter hanging off it.
        int wanted = (int) Math.ceil(widest) + pad * 2;
        frameWidth = holdWidth(wanted);
        int count = lines == null || lines.isEmpty() ? 1 : lines.size();
        frameHeight = (int) Math.ceil(count * (font.lineHeight() + LINE_GAP) - LINE_GAP) + pad * 2;
        sizeFrame = frame;
        sizeFont = font;
    }

    /** Grow at once, shrink only once the text has stayed narrower for a moment. */
    private int holdWidth(int wanted) {
        if (wanted >= stableWidth) {
            stableWidth = wanted;
            narrowSince = 0L;
            return stableWidth;
        }
        long now = System.nanoTime();
        if (narrowSince == 0L) {
            narrowSince = now;
        } else if (now - narrowSince >= SHRINK_DELAY_NANOS) {
            stableWidth = wanted;
            narrowSince = 0L;
        }
        return stableWidth;
    }

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
     * <p>Overridden by the widgets that colour by value — Ping by latency,
     * Sprint and sneak by which state is latched. Everything else takes the
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
        List<String> lines = currentLines(client);
        return lines != null && !lines.isEmpty();
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        measure(client, font);
        return frameWidth;
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        measure(client, font);
        return frameHeight;
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        List<String> lines = currentLines(client);
        if (lines == null || lines.isEmpty()) {
            return;
        }
        FathomModule module = module();
        int pad = HudStyle.padding(module);
        int boxWidth = width(client, font);
        int boxHeight = height(client, font);

        backdrop(module, x, y, boxWidth, boxHeight, alpha);

        int textColour = HudStyle.textColour(module) & 0x00FFFFFF;
        boolean shadow = HudStyle.textShadow(module);
        float lineY = y + pad;
        float room = boxWidth - pad * 2;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            int colour = (alpha << 24) | (colourFor(index, line, textColour) & 0x00FFFFFF);
            float lineX = x + pad + Math.round((room - font.width(line)) * alignment);
            font.draw(line, lineX, lineY, colour, shadow);
            lineY += font.lineHeight() + LINE_GAP;
        }
    }

    /**
     * The plate and its border, for a module's settings.
     *
     * <p>Shared with the widgets that are not text — Keystrokes, the potion list,
     * the armour list — so every module's Background, Border and Corner radius
     * rows mean the same thing wherever they are drawn.
     */
    /**
     * The lines, their colours and the box they sit in. A text widget draws
     * nothing that is not a function of these and its settings, which the HUD
     * hashes itself. Obfuscated text is the exception -- Minecraft redraws
     * {@code §k} with random glyphs every frame -- and is never vouched for.
     */
    @Override
    public long contentSignature(MinecraftClient client, HudFont font) {
        List<String> lines = currentLines(client);
        if (lines == null) {
            return 0L;
        }
        int textColour = HudStyle.textColour(module()) & 0x00FFFFFF;
        long hash = 0xcbf29ce484222325L;
        for (int index = 0; index < lines.size(); index++) {
            String line = lines.get(index);
            if (line.indexOf("§k") >= 0 || line.indexOf("§K") >= 0) {
                return 0L;
            }
            hash = (hash ^ line.hashCode()) * 0x100000001b3L;
            hash = (hash ^ colourFor(index, line, textColour)) * 0x100000001b3L;
        }
        hash = (hash ^ width(client, font)) * 0x100000001b3L;
        hash = (hash ^ height(client, font)) * 0x100000001b3L;
        hash = (hash ^ Float.floatToIntBits(alignment)) * 0x100000001b3L;
        hash = (hash ^ System.identityHashCode(font)) * 0x100000001b3L;
        return hash == 0L ? 1L : hash;
    }

    static void backdrop(FathomModule module, int x, int y, int width, int height, int alpha) {
        HudSurface.draw(module, x, y, width, height, alpha);
    }
}
