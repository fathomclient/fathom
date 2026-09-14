package net.fathom.optimizations.fabric.gfx.nanovg;

import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.util.ScreenshotUtils;

import net.fathom.optimizations.core.ui.Colour;
import net.fathom.optimizations.core.ui.anim.Clock;
import net.fathom.optimizations.core.ui.anim.Ease;
import net.fathom.optimizations.core.ui.anim.Motion;
import net.fathom.optimizations.core.ui.anim.Timing;
import net.fathom.optimizations.core.ui.render.FathomRenderer;
import net.fathom.optimizations.core.ui.render.FrameScale;
import net.fathom.optimizations.core.ui.render.ImageRef;
import net.fathom.optimizations.core.ui.render.Paint;
import net.fathom.optimizations.core.ui.render.Shadow;
import net.fathom.optimizations.core.ui.render.TextAlign;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.gfx.Canvas;
import net.fathom.optimizations.fabric.gfx.NanoVgGlGuard;
import net.fathom.optimizations.generated.FathomTheme;

/**
 * Development only: every primitive of {@link FathomRenderer} on one screen.
 *
 * <p>Not reachable from anything a player can click. It opens only from the
 * visual-QA flag -- {@code gradle runClient -PcaptureUi=nanovg} photographs it
 * and stops, {@code -PcaptureUi=nanovg-live} leaves it open -- and the NanoVG
 * backend it shows exists only in those development runs.
 *
 * <p>It draws exclusively through {@link Canvas} and {@link FathomRenderer}, the
 * way production screens will: no NanoVG, no GL. Animation is Fathom's own
 * {@link Clock}, {@link Motion} and {@link Ease}. The one thing it does that a
 * real screen would not is compare raw GL state before and after its frame, and
 * draw a line of Minecraft's own text afterwards as a canary.
 */
public final class RendererShowcaseScreen extends Screen {

    private static final int CAPTURE_FRAME = 90;
    private static final float MARGIN = 40f;
    private static final float GAP = 14f;
    private static final float HEADER = 92f;
    private static final float FOOTER = 52f;
    private static final float CARD_RADIUS = 16f;
    private static final float PAD = 18f;

    private static final int TEXT = FathomTheme.COLOR_TEXT;
    private static final int MUTED = FathomTheme.COLOR_TEXT_MUTED;
    private static final int FAINT = FathomTheme.COLOR_TEXT_FAINT;
    private static final int LABEL = FathomTheme.COLOR_TEXT_LABEL;
    private static final int RED = FathomTheme.COLOR_ACCENT_DEEP;
    private static final int RED_BRIGHT = FathomTheme.COLOR_ACCENT;
    private static final int EDGE = 0x1FFFFFFF;
    private static final int CARD = 0xB30A0B0F;

    private static final String[] TITLES = {
        "SHAPES", "GRADIENTS", "TEXT", "CLIPPING", "IMAGES", "SHADOWS", "TRANSPARENCY", "MOTION"
    };
    private static final String[] ROWS = {
        "Performance", "Mods", "HUD layout", "Profiles", "Privacy", "Launcher", "Keybinds", "About"
    };

    private static final String[] SHADOW_NAMES = {"panel", "dropdown", "modal"};
    private static final Shadow[] SHADOWS = {Shadow.PANEL, Shadow.DROPDOWN, Shadow.MODAL};
    private static final Shadow KNOB_SHADOW = new Shadow(6f, 0f, 0f, 1f, 0x66000000, false);

    private static final ImageRef SCENE =
            ImageRef.resource("/assets/fathom-optimizations/textures/menu/scenes/bg_6midnight.jpg");
    private static final ImageRef STILL =
            ImageRef.resource("/assets/fathom-optimizations/textures/menu/scenes/bg_5rain.jpg");

    private final Clock clock = new Clock(System.nanoTime());
    private final Motion[] entrance = new Motion[TITLES.length];
    private final Motion toggle = new Motion(0f, Timing.TOGGLE);
    private final Motion lift = new Motion(0f, Timing.STANDARD);
    private float lastFlip;
    private boolean on;

    private final Paint paint = new Paint();
    private final float[] bounds = new float[4];

    private int frames;
    private float drawMillis;
    private String stateReport = "GL state not checked yet";
    private boolean stateMatched = true;
    private boolean reported;
    private boolean captured;

    public RendererShowcaseScreen() {
        for (int i = 0; i < entrance.length; i++) {
            entrance[i] = new Motion(0f, Timing.SCREEN_ENTER);
        }
    }

    @Override
    public boolean shouldPauseGame() {
        return false;
    }

    @Override
    public void render(int mouseX, int mouseY, float tickDelta) {
        DrawableHelper.fill(0, 0, width, height, 0xFF000000);
        clock.advance(System.nanoTime());
        float now = clock.seconds();
        animate(now);

        boolean check = frames % 30 == 0;
        String before = check ? NanoVgGlGuard.fingerprint() : null;
        long started = System.nanoTime();

        FathomRenderer ui = Canvas.begin(client, FrameScale.Mode.FATHOM);
        if (ui == null) {
            textRenderer.drawWithShadow("Renderer unavailable: " + NanoVg.failure(), 8f, 8f, 0xFFFF8080);
            return;
        }
        try {
            draw(ui, now);
        } finally {
            Canvas.end();
        }

        float cost = (System.nanoTime() - started) / 1_000_000f;
        drawMillis = frames == 0 ? cost : drawMillis * 0.9f + cost * 0.1f;
        if (check) {
            stateMatched = before.equals(NanoVgGlGuard.fingerprint());
            stateReport = stateMatched ? "GL state restored" : "GL state CHANGED";
            if (!stateMatched) {
                System.out.println("[Fathom] showcase: GL state changed across the frame");
            }
        }
        // Minecraft's own text, after the frame: the canary for leaked state.
        textRenderer.drawWithShadow("Minecraft text after the frame: " + stateReport,
                4f, height - 12f, stateMatched ? 0xFF9EE6A8 : 0xFFFF8080);

        frames++;
        report(ui);
        captureIfAsked(ui);
    }

    private void animate(float now) {
        for (int i = 0; i < entrance.length; i++) {
            if (now >= 0.05f + i * 0.04f) {
                entrance[i].to(1f, now);
            }
        }
        if (now - lastFlip > 1.6f) {
            lastFlip = now;
            on = !on;
            toggle.to(on ? 1f : 0f, now);
            lift.to(on ? 1f : 0f, now);
        }
    }

    // -----------------------------------------------------------------------

    private void draw(FathomRenderer ui, float now) {
        float w = ui.width();
        float h = ui.height();

        // Backdrop: a scene still, darkened towards the bottom.
        ui.imageCover(SCENE, 0f, 0f, w, h, 0f, 1f);
        ui.gradient(0f, 0f, w, h, 0f, 0x8C000000, 0xF2000000);

        ui.text("Renderer", MARGIN, 52f, Type.SECTION, TEXT, TextAlign.LEFT | TextAlign.BASELINE);
        ui.textFit(ui.describe(), MARGIN, 74f, w * 0.62f, Type.CAPTION, FAINT, TextAlign.LEFT | TextAlign.BASELINE);
        ui.text(String.format("%.2f ms", drawMillis), w - MARGIN, 52f, Type.BODY_STRONG, TEXT,
                TextAlign.RIGHT | TextAlign.BASELINE);
        ui.text((int) (w * ui.scale()) + " x " + (int) (h * ui.scale()) + "  @ " + String.format("%.2f", ui.scale()),
                w - MARGIN, 74f, Type.CAPTION, FAINT, TextAlign.RIGHT | TextAlign.BASELINE);

        float gridTop = HEADER;
        float gridBottom = h - FOOTER;
        float cardW = (w - MARGIN * 2f - GAP * 3f) / 4f;
        float cardH = (gridBottom - gridTop - GAP) / 2f;

        for (int i = 0; i < TITLES.length; i++) {
            float x = MARGIN + (i % 4) * (cardW + GAP);
            float y = gridTop + (i / 4) * (cardH + GAP);
            float shown = entrance[i].value(now);
            if (shown <= 0f) {
                continue;
            }
            ui.pushAlpha(shown);
            ui.pushTransform(0f, (1f - shown) * 14f);
            card(ui, i, x, y, x + cardW, y + cardH, now);
            ui.popTransform();
            ui.popAlpha();
        }
    }

    private void card(FathomRenderer ui, int index, float l, float t, float r, float b, float now) {
        ui.shadow(l, t, r, b, CARD_RADIUS, Shadow.PANEL);
        ui.rounded(l, t, r, b, CARD_RADIUS, CARD);
        ui.outline(l, t, r, b, CARD_RADIUS, 1f, EDGE);
        ui.text(TITLES[index], l + PAD, t + PAD, Type.LABEL, LABEL, TextAlign.LEFT | TextAlign.TOP);

        float cl = l + PAD;
        float ct = t + PAD + 28f;
        float cr = r - PAD;
        float cb = b - PAD;
        switch (index) {
            case 0:
                shapes(ui, cl, ct, cr, cb);
                break;
            case 1:
                gradients(ui, cl, ct, cr, cb);
                break;
            case 2:
                text(ui, cl, ct, cr, cb, now);
                break;
            case 3:
                clipping(ui, cl, ct, cr, cb, now);
                break;
            case 4:
                images(ui, cl, ct, cr, cb, now);
                break;
            case 5:
                shadows(ui, cl, ct, cr, cb, now);
                break;
            case 6:
                transparency(ui, cl, ct, cr, cb, now);
                break;
            default:
                motion(ui, cl, ct, cr, cb, now);
                break;
        }
    }

    private void shapes(FathomRenderer ui, float l, float t, float r, float b) {
        float cell = (r - l) / 3f;
        float size = Math.min(cell - 16f, (b - t) / 3f - 14f);
        float row1 = t + 4f;
        float row2 = row1 + size + 22f;
        float row3 = row2 + size + 22f;
        float c0 = l + (cell - size) / 2f;
        float c1 = c0 + cell;
        float c2 = c1 + cell;

        ui.fill(c0, row1, c0 + size, row1 + size, TEXT);
        ui.rounded(c1, row1, c1 + size, row1 + size, 12f, RED);
        ui.rounded(c2, row1, c2 + size, row1 + size, size / 2f, 4f, size / 2f, 4f, 0x33FFFFFF);

        ui.circle(c0 + size / 2f, row2 + size / 2f, size / 2f, RED_BRIGHT);
        ui.ring(c1 + size / 2f, row2 + size / 2f, size / 2f - 2f, 3f, TEXT);
        ui.line(c2 + 4f, row2 + size - 4f, c2 + size - 4f, row2 + 4f, 3f, MUTED);

        // A convex path: a hexagon. And an open stroke: a check mark.
        float hx = c0 + size / 2f;
        float hy = row3 + size / 2f;
        float hr = size / 2f;
        ui.beginPath();
        for (int i = 0; i < 6; i++) {
            double angle = Math.PI / 6.0 + i * Math.PI / 3.0;
            float px = hx + (float) Math.cos(angle) * hr;
            float py = hy + (float) Math.sin(angle) * hr;
            if (i == 0) {
                ui.moveTo(px, py);
            } else {
                ui.lineTo(px, py);
            }
        }
        ui.closePath();
        ui.fillPath(0x40FFFFFF);

        ui.beginPath();
        ui.moveTo(c1 + size * 0.2f, row3 + size * 0.55f);
        ui.lineTo(c1 + size * 0.42f, row3 + size * 0.76f);
        ui.lineTo(c1 + size * 0.82f, row3 + size * 0.28f);
        ui.strokePath(4f, TEXT);

        ui.outline(c2, row3, c2 + size, row3 + size, 0f, 12f, 12f, 0f, 2f, RED_BRIGHT);
    }

    private void gradients(FathomRenderer ui, float l, float t, float r, float b) {
        float mid = (l + r) / 2f;
        float half = (t + b) / 2f;
        float g = 6f;
        ui.gradient(l, t, mid - g, half - g, 12f, RED, 0xFF000000);
        ui.horizontalGradient(mid + g, t, r, half - g, 12f, 0xFF000000, RED_BRIGHT);
        ui.rounded(l, half + g, mid - g, b, 12f,
                paint.linear(l, half + g, mid - g, b, TEXT, RED));
        float cx = (mid + g + r) / 2f;
        float cy = (half + g + b) / 2f;
        ui.rounded(mid + g, half + g, r, b, 12f, 0xFF050506);
        ui.rounded(mid + g, half + g, r, b, 12f,
                paint.radial(cx, cy, 2f, Math.min(r - mid, b - half) * 0.55f, 0xE6C4303C, 0x00841C24));
    }

    private void text(FathomRenderer ui, float l, float t, float r, float b, float now) {
        float y = t + 22f;
        ui.text("Fathom", l, y, Type.DISPLAY, TEXT, TextAlign.LEFT | TextAlign.BASELINE);
        y += 24f;
        ui.text("Semibold heading", l, y, Type.SECTION.at(15f), TEXT, TextAlign.LEFT | TextAlign.BASELINE);
        y += 19f;
        ui.text("Medium label", l, y, Type.BODY_STRONG, MUTED, TextAlign.LEFT | TextAlign.BASELINE);
        y += 17f;
        ui.text("Regular body at 50% opacity", l, y, Type.BODY, Colour.fade(TEXT, 0.5f),
                TextAlign.LEFT | TextAlign.BASELINE);

        // Alignment against a guide, with measured bounds.
        float mid = (l + r) / 2f;
        float top = y + 20f;
        float bottom = top + 58f;
        ui.fill(mid - 0.5f, top, mid + 0.5f, bottom, 0x99C4303C);
        float row = top + 14f;
        ui.text("left", mid + 6f, row, Type.CAPTION, MUTED, TextAlign.LEFT | TextAlign.MIDDLE);
        ui.text("right", mid - 6f, row, Type.CAPTION, MUTED, TextAlign.RIGHT | TextAlign.MIDDLE);
        row += 26f;
        ui.textBounds("centred", mid, row, Type.BODY_STRONG, TextAlign.CENTER | TextAlign.MIDDLE, bounds);
        ui.rounded(bounds[0] - 8f, bounds[1] - 3f, bounds[2] + 8f, bounds[3] + 3f, 6f, 0xE60A0B0F);
        ui.outline(bounds[0] - 8f, bounds[1] - 3f, bounds[2] + 8f, bounds[3] + 3f, 6f, 1f, 0x33FFFFFF);
        ui.text("centred", mid, row, Type.BODY_STRONG, TEXT, TextAlign.CENTER | TextAlign.MIDDLE);

        // Truncation in a box that breathes.
        float breathe = 0.5f + 0.5f * (float) Math.sin(now * 1.3f);
        float boxR = l + 70f + (r - l - 70f) * breathe;
        float boxT = b - 30f;
        ui.rounded(l, boxT, boxR, b, 8f, 0x14FFFFFF);
        ui.textFit("Fits, then truncates with an ellipsis", l + 10f, (boxT + b) / 2f, boxR - l - 20f,
                Type.BODY, TEXT, TextAlign.LEFT | TextAlign.MIDDLE);
    }

    private void clipping(FathomRenderer ui, float l, float t, float r, float b, float now) {
        float rowH = 28f;
        float scroll = (0.5f + 0.5f * (float) Math.sin(now * 0.9f)) * (ROWS.length * rowH - (b - t));
        ui.outline(l - 1f, t - 1f, r + 1f, b + 1f, 10f, 1f, 0x26FFFFFF);
        ui.clip(l, t, r, b);
        for (int i = 0; i < ROWS.length; i++) {
            float y = t + i * rowH - Math.max(0f, scroll);
            ui.rounded(l + 4f, y + 3f, r - 4f, y + rowH - 3f, 7f, i % 2 == 0 ? 0x0FFFFFFF : 0x06FFFFFF);
            ui.text(ROWS[i], l + 14f, y + rowH / 2f, Type.BODY, MUTED, TextAlign.LEFT | TextAlign.MIDDLE);
        }
        // Nested: a band that intersects the clip above, redrawing rows in red.
        float band = l + (r - l) * 0.55f;
        ui.clip(band, t - 40f, r, b + 40f);
        ui.fill(band, t, r, b, 0x55841C24);
        for (int i = 0; i < ROWS.length; i++) {
            float y = t + i * rowH - Math.max(0f, scroll);
            ui.text(ROWS[i], l + 14f, y + rowH / 2f, Type.BODY, TEXT, TextAlign.LEFT | TextAlign.MIDDLE);
        }
        ui.unclip();
        ui.unclip();
        // Outside every clip again.
        ui.line(band, b + 6f, r, b + 6f, 2f, RED_BRIGHT);
    }

    private void images(FathomRenderer ui, float l, float t, float r, float b, float now) {
        float split = t + (b - t) * 0.62f;
        if (ui.imageState(STILL) != ImageRef.State.READY) {
            ui.rounded(l, t, r, split, 12f, 0x0FFFFFFF);
            ui.text("loading...", (l + r) / 2f, (t + split) / 2f, Type.CAPTION, FAINT,
                    TextAlign.CENTER | TextAlign.MIDDLE);
        }
        ui.imageCover(STILL, l, t, r, split, 12f, 1f);

        float size = b - split - 12f;
        float ax = l;
        float ay = split + 12f;
        ui.imageCover(SCENE, ax, ay, ax + size, ay + size, size / 2f, 1f);
        ui.ring(ax + size / 2f, ay + size / 2f, size / 2f + 2f, 1.5f, RED_BRIGHT);

        float fade = 0.25f + 0.75f * (0.5f + 0.5f * (float) Math.sin(now * 1.7f));
        ui.imageCover(STILL, ax + size + 12f, ay, r, b, 10f, fade);
        ui.text(Math.round(fade * 100f) + "%", r - 8f, b - 8f, Type.CAPTION, TEXT,
                TextAlign.RIGHT | TextAlign.BOTTOM);
    }

    private void shadows(FathomRenderer ui, float l, float t, float r, float b, float now) {
        // A lighter surface, so the shadows have something to fall on.
        ui.rounded(l, t, r, t + 110f, 12f, 0xFF2A2C33);
        float w = (r - l - 44f) / 3f;
        float top = t + 20f;
        float bottom = top + 64f;
        for (int i = 0; i < 3; i++) {
            float x = l + 12f + i * (w + 10f);
            ui.shadow(x, top, x + w, bottom, 10f, SHADOWS[i]);
            // Translucent on purpose: the shadow is cut out, so it does not show through.
            ui.rounded(x, top, x + w, bottom, 10f, 0x8C2A2C33);
            ui.outline(x, top, x + w, bottom, 10f, 1f, EDGE);
            ui.text(SHADOW_NAMES[i], x + w / 2f, bottom - 12f, Type.CAPTION, MUTED, TextAlign.CENTER | TextAlign.BASELINE);
        }

        float pulse = 0.5f + 0.5f * (float) Math.sin(now * 2.4f);
        float pl = l + 20f;
        float pr = r - 20f;
        float pt = b - 50f;
        float pb = b - 14f;
        ui.glow(pl, pt, pr, pb, 18f, 16f + 10f * pulse, Colour.fade(RED_BRIGHT, 0.35f + 0.3f * pulse));
        ui.gradient(pl, pt, pr, pb, 18f, RED_BRIGHT, RED);
        ui.text("Glow accent", (pl + pr) / 2f, (pt + pb) / 2f, Type.BODY_STRONG, TEXT,
                TextAlign.CENTER | TextAlign.MIDDLE);
    }

    private void transparency(FathomRenderer ui, float l, float t, float r, float b, float now) {
        float cx = (l + r) / 2f;
        float cy = t + (b - t) * 0.36f;
        float radius = Math.min(r - l, b - t) * 0.2f;
        ui.circle(cx - radius * 0.55f, cy, radius, 0x99841C24);
        ui.circle(cx + radius * 0.55f, cy, radius, 0x66FFFFFF);
        ui.circle(cx, cy + radius * 0.8f, radius, 0x80000000);
        ui.ring(cx, cy + radius * 0.8f, radius, 1f, 0x40FFFFFF);

        // A group faded as one: pushAlpha, not every colour knowing it fades.
        float group = 0.15f + 0.85f * (0.5f + 0.5f * (float) Math.sin(now * 1.1f));
        float gt = b - 64f;
        ui.pushAlpha(group);
        ui.rounded(l, gt, r, b, 12f, 0xE6121318);
        ui.outline(l, gt, r, b, 12f, 1f, EDGE);
        ui.circle(l + 26f, (gt + b) / 2f, 11f, RED_BRIGHT);
        ui.text("Group opacity", l + 46f, (gt + b) / 2f - 7f, Type.BODY_STRONG, TEXT, TextAlign.LEFT | TextAlign.MIDDLE);
        ui.text(Math.round(group * 100f) + "% via pushAlpha", l + 46f, (gt + b) / 2f + 9f, Type.CAPTION, MUTED,
                TextAlign.LEFT | TextAlign.MIDDLE);
        ui.popAlpha();
    }

    private void motion(FathomRenderer ui, float l, float t, float r, float b, float now) {
        float value = toggle.value(now);

        // Toggle: knob position and track colour both sampled from one Motion.
        float tl = l;
        float tt = t + 4f;
        float tw = 46f;
        float th = 26f;
        ui.rounded(tl, tt, tl + tw, tt + th, th / 2f, Colour.mix(0x26FFFFFF, RED_BRIGHT, value));
        float knob = tl + 3f + (tw - th) * value;
        ui.shadow(knob, tt + 3f, knob + th - 6f, tt + th - 3f, th, KNOB_SHADOW);
        ui.circle(knob + (th - 6f) / 2f, tt + th / 2f, (th - 6f) / 2f, TEXT);
        ui.text(on ? "On" : "Off", tl + tw + 12f, tt + th / 2f, Type.BODY_STRONG, TEXT,
                TextAlign.LEFT | TextAlign.MIDDLE);

        // Progress on Fathom's easing curve.
        float bt = tt + th + 22f;
        ui.rounded(l, bt, r, bt + 6f, 3f, 0x1AFFFFFF);
        ui.rounded(l, bt, l + (r - l) * value, bt + 6f, 3f, RED_BRIGHT);

        // A spinner: an arc stroked along a path, rotating with time.
        float sx = l + 18f;
        float sy = bt + 44f;
        float spin = now * 4f;
        ui.ring(sx, sy, 14f, 3f, 0x1FFFFFFF);
        ui.beginPath();
        ui.arc(sx, sy, 14f, spin, spin + 1.8f + 0.9f * (float) Math.sin(now * 2f));
        ui.strokePath(3f, TEXT);
        ui.text("Clock + Motion + Ease", sx + 28f, sy, Type.CAPTION, MUTED, TextAlign.LEFT | TextAlign.MIDDLE);

        // A card that lifts: scale about its centre, shadow deepening with it.
        float lifted = lift.value(now);
        float ct = Math.max(sy + 32f, b - 64f);
        float ccx = (l + r) / 2f;
        float ccy = (ct + b) / 2f;
        ui.pushTransform(0f, -4f * lifted, 1f + 0.04f * lifted, ccx, ccy);
        // Strength through the opacity stack: no new Shadow per frame.
        ui.pushAlpha(0.3f + 0.7f * lifted);
        ui.shadow(l, ct, r, b, 12f, Shadow.DROPDOWN);
        ui.popAlpha();
        ui.rounded(l, ct, r, b, 12f, 0xF214161B);
        ui.outline(l, ct, r, b, 12f, 1f, Colour.mix(EDGE, 0x80C4303C, lifted));
        ui.text("Lift", l + 14f, ccy, Type.BODY_STRONG, TEXT, TextAlign.LEFT | TextAlign.MIDDLE);
        ui.popTransform();
    }

    // -----------------------------------------------------------------------

    private void report(FathomRenderer ui) {
        if (reported || frames != 3) {
            return;
        }
        reported = true;
        FrameScale scale = Canvas.scale();
        System.out.println("[Fathom] showcase: " + ui.describe());
        System.out.println("[Fathom] showcase: framebuffer " + scale.framebufferWidth() + "x"
                + scale.framebufferHeight() + ", ratio " + scale.ratio() + ", logical "
                + scale.logicalWidth() + "x" + scale.logicalHeight() + "; " + stateReport);
    }

    private void captureIfAsked(FathomRenderer ui) {
        if (captured || frames < CAPTURE_FRAME
                || !"nanovg".equalsIgnoreCase(System.getProperty("fathom.captureUi", ""))) {
            return;
        }
        captured = true;
        String name = "fathom-renderer-showcase-" + client.width + "x" + client.height + ".png";
        ScreenshotUtils.saveScreenshot(client.runDirectory, name, client.width, client.height,
                client.getFramebuffer());
        System.out.println("[Fathom] showcase captured: " + name + "; " + stateReport + "; "
                + String.format("%.2f ms per frame", drawMillis) + "; " + ui.describe());
        client.scheduleStop();
    }
}
