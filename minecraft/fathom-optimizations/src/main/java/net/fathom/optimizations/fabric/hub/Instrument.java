package net.fathom.optimizations.fabric.hub;

import java.util.Locale;

import net.fathom.optimizations.core.instrument.Pulse;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.layout.Text;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.diag.SessionPulse;
import net.fathom.optimizations.fabric.gfx.Gfx;
import net.fathom.optimizations.fabric.ui.Controls;
import net.fathom.optimizations.fabric.ui.FathomComponents;
import net.fathom.optimizations.fabric.ui.Graphite;
import net.fathom.optimizations.fabric.ui.Skin;

/**
 * The performance instrument: the top of the Hub's Performance page.
 *
 * <pre>
 *  Frame rate   p95       p99       Memory          Modules          Survival
 *  240 fps      4.10 ms   6.02 ms   1.2 / 4.0 GB    9 on
 *  ▁▁▂▁▁▁▁▁▁▁▃▁▁▁│▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁╿▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁▁
 *  Frame time, last 2 minutes of play                          This page 0.42 ms
 * </pre>
 *
 * <p>The figures are the pulse's: frame rate over the last second of play, the 95th and
 * 99th percentile frame time over the last five seconds, the heap as the JVM reports it.
 * The timeline is one column per second: the second's mean frame time as a faint column
 * with a lit top, and -- when its slowest frame stood well clear of that -- a whisker up to
 * a cap at the slowest frame, so a smooth stretch is calm columns and a stutter stands
 * out. A cap at a hitch is drawn in the warning colour. A faint hairline marks a 60 fps
 * frame. A tick over a column marks a change -- a world joined, a setting
 * changed -- so a step in the graph can be read against what caused it.
 *
 * <h2>Cost</h2>
 *
 * <p>Drawn only while this page is open. Strings are rebuilt when a second of play
 * closes, not per frame; the hover read-out is rebuilt when the pointer moves to another
 * column. The columns are plain fills.
 */
final class Instrument {

    /** The panel's height: the figures, the timeline, the caption line. */
    static final float HEIGHT = 112f;

    private static final float GRAPH = 32f;
    private static final float HITCH_MILLIS = Pulse.HITCH_NANOS / 1_000_000f;
    /** A 60 fps frame: the dotted reference across the timeline. */
    private static final float FRAME_60 = 1000f / 60f;
    /** How far a second's slowest frame must stand above its mean to get a whisker. */
    private static final float UNEVEN = 2.5f;

    private long shownSecond = -1L;
    private float shownAt = -1f;
    private String fps = "—";
    private String p95 = "—";
    private String p99 = "—";
    private String memory = "—";
    private String modules = "—";
    private String uiCost = "";
    private float scaleMillis = 20f;
    private float targetScale = 20f;
    private final float[] sorted = new float[Pulse.SECONDS];

    private int hoverColumn = -1;
    private String hoverText = "";

    void draw(HubContext context, Rect area) {
        Renderer ui = context.ui();
        Pulse pulse = SessionPulse.pulse();
        refresh(context, pulse);

        FathomComponents.panel(ui, area);
        Rect inner = area.inset(Skin.S4, Skin.S3);

        // -- the figures ------------------------------------------------------
        Type label = Graphite.floor(ui, Graphite.CAPTION);
        Type value = Graphite.floor(ui, Graphite.DATA_M);
        float labelLine = ui.lineHeight(label);
        float valueTop = inner.y + labelLine + Skin.S1;
        float valueBaseline = valueTop + ui.ascent(value);
        String profile = context.hud().sessionName();
        float profileWidth = Math.min(inner.width * 0.26f, ui.measure(profile, label));
        float cells = inner.width - profileWidth - Skin.S4;
        float cell = cells / 5f;

        figure(context, "inst.fps", inner.x, inner.y, cell, "Frame rate", fps, label, value, valueBaseline,
                "Frames per second over the last second of play.");
        figure(context, "inst.p95", inner.x + cell, inner.y, cell, "p95", p95, label, value, valueBaseline,
                "One frame in twenty takes longer than this. Last five seconds of play.");
        figure(context, "inst.p99", inner.x + cell * 2f, inner.y, cell, "p99", p99, label, value, valueBaseline,
                "One frame in a hundred takes longer than this: the stutter you feel. Last five seconds.");
        figure(context, "inst.mem", inner.x + cell * 3f, inner.y, cell, "Memory", memory, label, value,
                valueBaseline, "Java heap in use, of the most this game may use.");
        figure(context, "inst.mods", inner.x + cell * 4f, inner.y, cell, "Modules", modules, label, value,
                valueBaseline, "Fathom modules switched on in this profile.");
        ui.textRight(Text.fit(ui, profile, label, profileWidth), inner.right(), inner.y + ui.ascent(label),
                label, Graphite.TEXT_TERTIARY);

        // -- the timeline -------------------------------------------------------
        float captionLine = ui.lineHeight(label);
        float graphBottom = inner.bottom() - captionLine - Skin.S2;
        Rect graph = new Rect(inner.x, graphBottom - GRAPH, inner.width, GRAPH);
        timeline(context, pulse, graph);

        float captionBaseline = inner.bottom() - ui.descent(label);
        String caption = pulse.timelineLength() == 0
                ? "Measured in a world. The timeline starts when you join one."
                : "Frame time, last " + minutes(pulse.timelineLength()) + " of play";
        ui.text(Text.fit(ui, caption, label, inner.width * 0.7f), inner.x, captionBaseline, label,
                Graphite.TEXT_TERTIARY);
        if (!uiCost.isEmpty()) {
            ui.textRight(uiCost, inner.right(), captionBaseline, label, Graphite.TEXT_TERTIARY);
        }
    }

    private void figure(HubContext context, String id, float x, float y, float width, String name,
            String reading, Type label, Type value, float baseline, String explanation) {
        Renderer ui = context.ui();
        ui.text(name, x, y + ui.ascent(label), label, Graphite.TEXT_SECONDARY);
        ui.text(Text.fit(ui, reading, value, width - Skin.S3), x, baseline, value, Graphite.TEXT);
        Rect hit = new Rect(x, y, width - Skin.S3, baseline + ui.descent(value) - y);
        Controls controls = context.controls();
        controls.tooltip(controls.state(id, hit, true), hit, explanation);
    }

    private void timeline(HubContext context, Pulse pulse, Rect graph) {
        Renderer ui = context.ui();
        float pixel = ui.pixel();
        // The floor line: where a frame that took no time would sit.
        ui.fill(graph.x, graph.bottom() - pixel, graph.right(), graph.bottom(), Graphite.HAIRLINE);

        int length = pulse.timelineLength();
        if (length == 0) {
            return;
        }
        // The 60 fps line: one faint hairline, a guide rather than data.
        float reference = Skin.snap(ui, graph.bottom() - height(FRAME_60, graph.height));
        ui.fill(graph.x, reference, graph.right(), reference + pixel, Skin.fade(Graphite.TEXT_TERTIARY, 0.45f));
        float eased = Math.min(1f, context.delta() * 6f);
        scaleMillis += (targetScale - scaleMillis) * eased;

        float column = graph.width / Pulse.SECONDS;
        float gap = column >= 3f ? pixel : 0f;
        // Newest at the right edge, so the graph fills in from the right as play goes on.
        float start = graph.right() - length * column;
        int columnFace = Skin.fade(Graphite.SIGNAL, 0.28f);
        int tipFace = Graphite.SIGNAL;
        int hitchFace = Skin.WARN;
        float tip = Math.max(pixel * 2f, 2f);
        for (int index = 0; index < length; index++) {
            float left = start + index * column;
            float right = left + column - gap;
            float fps = pulse.timelineFps(index);
            float mean = fps <= 0f ? 0f : 1000f / fps;
            float worst = pulse.timelineWorstMillis(index);
            float meanTop = graph.bottom() - height(mean, graph.height);
            float worstTop = graph.bottom() - height(worst, graph.height);
            ui.fill(left, meanTop, right, graph.bottom() - pixel, columnFace);
            if (worst >= mean * UNEVEN) {
                // A slow frame standing well clear of the second: a whisker up to it
                // and a cap on it. Anything closer is the ordinary texture of play
                // and gets only the column's lit top.
                int face = worst >= HITCH_MILLIS ? hitchFace : tipFace;
                float centre = Skin.snap(ui, (left + right) / 2f);
                ui.fill(centre, worstTop, centre + pixel, meanTop, Skin.fade(face, 0.55f));
                ui.fill(left, worstTop, right, Math.min(worstTop + tip, graph.bottom()), face);
            } else {
                ui.fill(left, meanTop - pixel, right, meanTop + pixel, tipFace);
            }
        }

        // Ticks for what changed, above the column of the first second after it.
        for (int mark = 0; mark < pulse.markCount(); mark++) {
            int index = pulse.markTimelineIndex(mark);
            if (index < 0) {
                continue;
            }
            float x = Math.min(start + index * column, graph.right() - pixel);
            ui.fill(x, graph.y, x + pixel, graph.bottom(), Graphite.TEXT_TERTIARY);
        }

        hover(context, pulse, graph, start, column, length);
    }

    /** What the column under the pointer says, in words. */
    private void hover(HubContext context, Pulse pulse, Rect graph, float start, float column, int length) {
        Controls controls = context.controls();
        Controls.State state = controls.state("inst.timeline", graph, true);
        if (!state.hovered) {
            hoverColumn = -1;
            return;
        }
        int index = (int) Math.floor((context.pointer().x() - start) / column);
        if (index < 0 || index >= length) {
            return;
        }
        if (index != hoverColumn) {
            hoverColumn = index;
            int ago = length - index;
            String when = ago <= 1 ? "Last second" : ago + " s of play ago";
            StringBuilder text = new StringBuilder(96);
            text.append(when).append(" · ").append(Math.round(pulse.timelineFps(index))).append(" fps · slowest frame ")
                    .append(String.format(Locale.ROOT, "%.1f", Float.valueOf(pulse.timelineWorstMillis(index))))
                    .append(" ms");
            for (int mark = 0; mark < pulse.markCount(); mark++) {
                if (pulse.markTimelineIndex(mark) == index) {
                    text.append(" · ").append(pulse.markKind(mark).label().toLowerCase(Locale.ROOT))
                            .append(" changed");
                }
            }
            hoverText = text.toString();
        }
        Rect under = new Rect(start + index * column, graph.y, column, graph.height);
        context.ui().fill(under.x, graph.y, under.right(), graph.bottom(), Skin.fade(Graphite.TEXT, 0.06f));
        controls.tooltip(state, under, hoverText);
    }

    private float height(float millis, float room) {
        float fraction = millis / Math.max(scaleMillis, 1f);
        return Math.max(0f, Math.min(1f, fraction)) * room;
    }

    /** Rebuild the strings when a second of play closes, and the menu's cost at 2 Hz. */
    private void refresh(HubContext context, Pulse pulse) {
        float now = context.now();
        long second = pulse.secondsOfPlay();
        if (second != shownSecond) {
            shownSecond = second;
            pulse.read();
            if (second > 0) {
                fps = Math.round(pulse.currentFps()) + " fps";
                p95 = millis(pulse.recentP95Millis());
                p99 = millis(pulse.recentP99Millis());
                memory = gigabytes(pulse.heapUsedBytes()) + " / " + gigabytes(pulse.heapMaxBytes()) + " GB";
            }
            targetScale = scale(pulse);
            if (scaleMillis <= 0f) {
                scaleMillis = targetScale;
            }
        }
        if (shownAt < 0f || now - shownAt >= 0.5f) {
            shownAt = now;
            int on = context.features() == null ? 0 : context.features().enabledCount();
            modules = on + " on";
            float cost = Gfx.frames().uiMeanMillis();
            uiCost = cost > 0f ? "This page " + millis(cost) : "";
        }
    }

    /**
     * The timeline's ceiling: the 90th-percentile slowest frame with room above it,
     * and never less than a little over a 60 fps frame, so the reference line is always
     * in view. The rare spike clips at the top, in its own colour, rather than
     * flattening every other second. The launcher's timeline uses the same rule.
     */
    private float scale(Pulse pulse) {
        int length = pulse.timelineLength();
        for (int index = 0; index < length; index++) {
            sorted[index] = pulse.timelineWorstMillis(index);
        }
        java.util.Arrays.sort(sorted, 0, length);
        float p90 = length == 0 ? 0f : sorted[Math.min(length - 1, (int) Math.ceil(length * 0.9) - 1)];
        return Math.max(FRAME_60 * 1.2f, p90 * 1.25f);
    }

    private static String millis(float value) {
        return String.format(Locale.ROOT, "%.2f ms", Float.valueOf(value));
    }

    private static String gigabytes(long bytes) {
        return String.format(Locale.ROOT, "%.1f", Float.valueOf(bytes / (1024f * 1024f * 1024f)));
    }

    private static String minutes(int seconds) {
        if (seconds < 60) {
            return seconds + " s";
        }
        int whole = seconds / 60;
        return whole == 1 ? "minute" : whole + " minutes";
    }
}
