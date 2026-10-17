package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.core.hud.Compass;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;

/**
 * The Direction HUD: a compass strip that turns with the player.
 *
 * <p>The marks come from {@link Compass}, which is tested without a game; this
 * draws them. Ticks go in one batch and the letters after, the same two passes
 * every widget with shapes uses. The heading is read from the player each
 * frame, so the strip moves with the camera, not with the tick.
 */
final class DirectionWidget implements HudWidget {

    static final String ID = "direction";

    private static final int TICK = 3;
    private static final int CARDINAL_TICK = 5;

    private final float[] xs = new float[Compass.MAX_MARKS];
    /** Eight letters and a reading: measured once each. */
    private final MeasuredWidths widths = new MeasuredWidths(10);
    private final String[] labels = new String[Compass.MAX_MARKS];

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String placeholder() {
        return "N  ·  NE  ·  E";
    }

    private static int stripWidth() {
        return (int) Math.round(Widgets.optionNumber(ID, "width", 140));
    }

    private static boolean degrees() {
        return Widgets.option(ID, "degrees", true);
    }

    private int readingDegrees = -1;
    private String readingText = "";

    /** "46° NE", rebuilt only when the whole degree changes. */
    private String reading(double heading) {
        int degrees = (int) (Math.round(heading) % 360);
        if (degrees != readingDegrees) {
            readingDegrees = degrees;
            readingText = degrees + "° " + Compass.name(heading);
        }
        return readingText;
    }

    @Override
    public boolean visible(MinecraftClient client) {
        return client.player != null;
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        return stripWidth() + HudStyle.padding(Widgets.module(ID)) * 2;
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        int line = (int) Math.ceil(font.lineHeight());
        return line + CARDINAL_TICK + 1 + (degrees() ? line + 1 : 0) + HudStyle.padding(Widgets.module(ID)) * 2;
    }

    @Override
    public long contentSignature(MinecraftClient client, HudFont font) {
        if (client.player == null) {
            return 0L;
        }
        // Half a degree is less than a pixel on any sensible strip.
        long hash = 0xcbf29ce484222325L;
        hash = (hash ^ Math.round(Compass.heading(client.player.yaw) * 2.0)) * 0x100000001b3L;
        hash = (hash ^ System.identityHashCode(font)) * 0x100000001b3L;
        return hash == 0L ? 1L : hash;
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        if (client.player == null) {
            return;
        }
        FathomModule module = Widgets.module(ID);
        int pad = HudStyle.padding(module);
        int strip = stripWidth();
        int span = (int) Math.round(Widgets.optionNumber(ID, "span", 180));
        double heading = Compass.heading(client.player.yaw);
        int count = Compass.layout(heading, span, strip, Widgets.option(ID, "between", true), xs, labels);

        int line = (int) Math.ceil(font.lineHeight());
        int left = x + pad;
        int top = y + pad;
        int tickTop = top + line + 1;
        int colour = (Math.max(0, Math.min(255, alpha)) << 24) | (HudStyle.textColour(module) & 0xFFFFFF);
        int faint = (Math.max(0, Math.min(255, alpha * 150 / 255)) << 24) | (HudStyle.textColour(module) & 0xFFFFFF);
        int accent = (Math.max(0, Math.min(255, alpha)) << 24)
                | (Widgets.optionColour(ID, "marker.colour", 0xFF5555) & 0xFFFFFF);

        QuadBatch.begin();
        try {
            TextWidget.backdrop(module, x, y, width(client, font), height(client, font), alpha);
            for (int index = 0; index < count; index++) {
                int markX = left + Math.round(xs[index]);
                int length = labels[index] == null ? TICK : CARDINAL_TICK;
                QuadBatch.rect(markX, tickTop, markX + 1, tickTop + length, labels[index] == null ? faint : colour);
            }
            // The marker: where the player is facing.
            int centre = left + strip / 2;
            QuadBatch.rect(centre, tickTop - 1, centre + 1, tickTop + CARDINAL_TICK + 1, accent);
        } finally {
            QuadBatch.end();
        }

        boolean shadow = HudStyle.textShadow(module);
        for (int index = 0; index < count; index++) {
            String label = labels[index];
            if (label == null) {
                continue;
            }
            float labelWidth = widths.of(font, label);
            float labelX = left + xs[index] - labelWidth / 2f;
            // Letters at the very ends are dropped rather than drawn half off the strip.
            if (labelX < left || labelX + labelWidth > left + strip) {
                continue;
            }
            font.draw(label, Math.round(labelX), top, colour, shadow);
        }
        if (degrees()) {
            String reading = reading(heading);
            float readingX = left + (strip - widths.of(font, reading)) / 2f;
            font.draw(reading, Math.round(readingX), tickTop + CARDINAL_TICK + 1, colour, shadow);
        }
    }
}
