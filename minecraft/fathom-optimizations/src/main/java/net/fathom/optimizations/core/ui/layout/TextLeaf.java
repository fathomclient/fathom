package net.fathom.optimizations.core.ui.layout;

import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;

/**
 * One line of text as a layout leaf: measured with the renderer's real
 * metrics, placed in its CSS line box, and drawn on the baseline the face's
 * own ascent and descent put it on ({@link TextStack#baseline}).
 *
 * <p>Retained: {@link #set} invalidates the layout only when the string
 * changed, and the measured width and the fitted (ellipsised) string are kept
 * until the text, the box's width or the interface scale changes -- a label
 * that says the same thing frame after frame is measured once and allocates
 * nothing.
 *
 * <p>Type is held at {@link #MINIMUM_PIXELS} device pixels at small interface
 * scales, as the launcher's legibility floor does.
 */
public final class TextLeaf implements Box.Measure {

    /** Below this many device pixels a face is held at it. */
    public static final float MINIMUM_PIXELS = 9f;

    private final Box box;
    private final Type type;
    private final float lineHeight;
    private boolean keepCase;
    private String text = "";

    // Cached per scale, text and width.
    private float flooredFor = -1f;
    private Type floored;
    private float measuredScale = -1f;
    private String measuredText;
    private float measured;
    private String fitted;
    private String fittedText;
    private float fittedWidth = -1f;
    private float fittedScale = -1f;

    private TextLeaf(String id, Type type, float lineHeight) {
        this.type = type;
        this.lineHeight = lineHeight;
        this.box = Box.leaf(id, this);
    }

    /**
     * @param lineHeight the CSS line box, logical units: what the line takes
     *     in a stack, whatever its face
     */
    public static TextLeaf of(String id, Type type, float lineHeight) {
        return new TextLeaf(id, type, lineHeight);
    }

    /** Names and the like keep their case in a lowercase interface. */
    public TextLeaf keepCase(boolean value) {
        keepCase = value;
        return this;
    }

    public Box box() {
        return box;
    }

    public String text() {
        return text;
    }

    /** Change the text; the layout is invalidated only when it differs. */
    public TextLeaf set(String value) {
        String next = value == null ? "" : value;
        if (!next.equals(text)) {
            text = next;
            box.invalidate();
        }
        return this;
    }

    /** The face drawn, held at the legibility floor for the renderer's scale. */
    public Type face(Renderer ui) {
        float scale = ui.scale();
        if (scale != flooredFor) {
            flooredFor = scale;
            floored = type.size() * scale >= MINIMUM_PIXELS ? type : type.at(MINIMUM_PIXELS / scale);
        }
        return floored;
    }

    @Override
    public float width(Renderer ui) {
        float scale = ui.scale();
        if (scale != measuredScale || measuredText != text) {
            measuredScale = scale;
            measuredText = text;
            if (keepCase) {
                ui.pushKeepCase();
            }
            measured = ui.measure(text, face(ui));
            if (keepCase) {
                ui.popKeepCase();
            }
        }
        return measured;
    }

    @Override
    public float height(Renderer ui, float width) {
        return lineHeight;
    }

    /** The baseline in the box's line box. */
    public float baseline(Renderer ui) {
        Rect r = box.rect();
        return TextStack.baseline(ui, r.y, r.height, face(ui));
    }

    /** The text, ellipsised to the box's width; cached. */
    public String fitted(Renderer ui) {
        Rect r = box.rect();
        float scale = ui.scale();
        if (fittedText != text || fittedWidth != r.width || fittedScale != scale) {
            fittedText = text;
            fittedWidth = r.width;
            fittedScale = scale;
            if (keepCase) {
                ui.pushKeepCase();
            }
            fitted = Text.fit(ui, text, face(ui), r.width + 0.5f);
            if (keepCase) {
                ui.popKeepCase();
            }
        }
        return fitted;
    }

    /** Draw at the box's left edge. */
    public void draw(Renderer ui, int argb) {
        if (box.isHidden()) {
            return;
        }
        String shown = fitted(ui);
        if (keepCase) {
            ui.pushKeepCase();
        }
        ui.text(shown, box.rect().x, baseline(ui), face(ui), argb);
        if (keepCase) {
            ui.popKeepCase();
        }
    }

    /** Draw against the box's right edge. */
    public void drawRight(Renderer ui, int argb) {
        if (box.isHidden()) {
            return;
        }
        String shown = fitted(ui);
        if (keepCase) {
            ui.pushKeepCase();
        }
        ui.textRight(shown, box.rect().right(), baseline(ui), face(ui), argb);
        if (keepCase) {
            ui.popKeepCase();
        }
    }

    /** Draw centred in the box. */
    public void drawCentred(Renderer ui, int argb) {
        if (box.isHidden()) {
            return;
        }
        String shown = fitted(ui);
        if (keepCase) {
            ui.pushKeepCase();
        }
        ui.textCentred(shown, box.rect().centreX(), baseline(ui), face(ui), argb);
        if (keepCase) {
            ui.popKeepCase();
        }
    }
}
