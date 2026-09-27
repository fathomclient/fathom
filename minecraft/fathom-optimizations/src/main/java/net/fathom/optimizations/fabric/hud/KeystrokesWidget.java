package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;

/**
 * Movement keys, mouse buttons, space and sneak, lit while held.
 *
 * <h2>Why it is not a text widget</h2>
 *
 * <p>It is a small keyboard, not a line of text, so it implements
 * {@link HudWidget} directly. It still reads the shared style settings —
 * background, opacity, padding, radius, border, shadow, font — through
 * {@link HudStyle} and {@link TextWidget#backdrop}, because a module whose
 * common settings did nothing would be the one that taught people to distrust
 * them.
 *
 * <h2>Immediate, but not harsh</h2>
 *
 * <p>A keystroke display is watched to see what somebody's hands did, so the
 * lighting is driven straight from the key state every frame, never from a tick.
 * The fade between the two colours defaults to 40 ms — enough to take the
 * strobing edge off a fast tap, far too short to read as an animation — and it
 * can be set to zero for none at all. Nothing here eases over a tenth of a
 * second the way the menus do; that would make the display feel late, which is
 * the one thing it must never be.
 *
 * <h2>Layout follows the switches</h2>
 *
 * <p>Rows are laid out in order and only if they are on, so switching the
 * movement keys off leaves the mouse row at the top rather than a gap, and the
 * box measures exactly what is drawn. The keys read are the player's own
 * bindings, not the physical W A S D, so somebody on a Dvorak or ESDF layout
 * sees their own keys light up — and the sneak key shows the real key even when
 * Toggle Sneak is holding it down, because this display is about hands.
 */
public final class KeystrokesWidget implements HudWidget {

    private static final int W = 0;
    private static final int A = 1;
    private static final int S = 2;
    private static final int D = 3;
    private static final int LEFT = 4;
    private static final int RIGHT = 5;
    private static final int SPACE = 6;
    private static final int SNEAK = 7;

    private final ClickTracker clicks;

    /** How lit each key is, 0 to 1, advanced in real time. */
    private final float[] lit = new float[8];
    private long lastNanos;

    public KeystrokesWidget(ClickTracker clicks) {
        this.clicks = clicks;
    }

    @Override
    public String id() {
        return "keystrokes";
    }

    @Override
    public String placeholder() {
        return "WASD";
    }

    private static FathomModule module() {
        return Widgets.module("keystrokes");
    }

    private static int keySize() {
        return (int) Math.round(Widgets.optionNumber("keystrokes", "key.size", 18));
    }

    private static int keyGap() {
        return (int) Math.round(Widgets.optionNumber("keystrokes", "key.gap", 3));
    }

    private static int pad() {
        return HudStyle.padding(module());
    }

    /** The width of the three-key row everything else lines up with. */
    private static int rowWidth() {
        return keySize() * 3 + keyGap() * 2;
    }

    private static int rows() {
        int rows = 0;
        if (Widgets.option("keystrokes", "wasd", true)) {
            rows += 2;
        }
        if (Widgets.option("keystrokes", "mouse", true)) {
            rows++;
        }
        if (Widgets.option("keystrokes", "space", true)) {
            rows++;
        }
        if (Widgets.option("keystrokes", "sneak", false)) {
            rows++;
        }
        return rows;
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        return rows() == 0 ? 0 : rowWidth() + pad() * 2;
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        int rows = rows();
        if (rows == 0) {
            return 0;
        }
        return rows * (keySize() + keyGap()) - keyGap() + pad() * 2;
    }

    @Override
    public boolean visible(MinecraftClient client) {
        return client.options != null && rows() > 0;
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        if (client.options == null) {
            return;
        }
        FathomModule module = module();
        advance(client, module);

        // Two passes over the same layout: every rectangle in one batched draw,
        // then the labels. Keys never overlap, so drawing all plates before any
        // label produces the same pixels as alternating.
        QuadBatch.begin();
        try {
            TextWidget.backdrop(module, x, y, width(client, font), height(client, font), alpha);
            keys(client, font, module, x, y, alpha, true);
        } finally {
            QuadBatch.end();
        }
        keys(client, font, module, x, y, alpha, false);
    }

    /** Move every key towards its real state, in real time. */
    private void advance(MinecraftClient client, FathomModule module) {
        long now = System.nanoTime();
        float elapsed = lastNanos == 0L ? 0f : Math.min(0.25f, (now - lastNanos) / 1_000_000_000f);
        lastNanos = now;
        double fadeMillis = module == null ? 40 : module.number("key.fade", 40);
        float step = fadeMillis <= 0 ? 1f : (float) (elapsed * 1000.0 / fadeMillis);

        set(W, pressed(client.options.forwardKey), step);
        set(A, pressed(client.options.leftKey), step);
        set(S, pressed(client.options.backKey), step);
        set(D, pressed(client.options.rightKey), step);
        set(LEFT, clicks.leftDown(), step);
        set(RIGHT, clicks.rightDown(), step);
        set(SPACE, pressed(client.options.jumpKey), step);
        set(SNEAK, pressed(client.options.sneakKey), step);
    }

    private void set(int key, boolean down, float step) {
        float target = down ? 1f : 0f;
        if (step >= 1f) {
            lit[key] = target;
            return;
        }
        lit[key] = target > lit[key] ? Math.min(target, lit[key] + step) : Math.max(target, lit[key] - step);
    }

    /** Lay the keys out once; {@code surfaces} chooses rectangles or labels. */
    private void keys(MinecraftClient client, HudFont font, FathomModule module,
            int x, int y, int alpha, boolean surfaces) {
        KeyStyle style = KeyStyle.read(module, alpha);
        int size = keySize();
        int gap = keyGap();
        int wide = rowWidth();
        int left = x + pad();
        int row = y + pad();

        if (Widgets.option("keystrokes", "wasd", true)) {
            key(font, style, left + size + gap, row, size, size, "W", lit[W], surfaces);
            row += size + gap;
            key(font, style, left, row, size, size, "A", lit[A], surfaces);
            key(font, style, left + size + gap, row, size, size, "S", lit[S], surfaces);
            key(font, style, left + (size + gap) * 2, row, size, size, "D", lit[D], surfaces);
            row += size + gap;
        }
        if (Widgets.option("keystrokes", "mouse", true)) {
            boolean numbers = Widgets.option("keystrokes", "cps-on-keys", false);
            int half = (wide - gap) / 2;
            key(font, style, left, row, half, size,
                    numbers ? String.valueOf(clicks.left()) : "LMB", lit[LEFT], surfaces);
            key(font, style, left + half + gap, row, wide - half - gap, size,
                    numbers ? String.valueOf(clicks.right()) : "RMB", lit[RIGHT], surfaces);
            row += size + gap;
        }
        if (Widgets.option("keystrokes", "space", true)) {
            key(font, style, left, row, wide, size, "SPACE", lit[SPACE], surfaces);
            row += size + gap;
        }
        if (Widgets.option("keystrokes", "sneak", false)) {
            key(font, style, left, row, wide, size, "SHIFT", lit[SNEAK], surfaces);
        }
    }

    /** The per-key settings, read once per pass instead of once per key. */
    private static final class KeyStyle {
        private static final KeyStyle SHARED = new KeyStyle();
        int keyAlpha;
        int pressedFill;
        int unpressedFill;
        boolean background;
        boolean border;
        int borderColour;
        int borderWidth;
        int pressedText;
        int unpressedText;
        int radius;
        boolean shadow;

        static KeyStyle read(FathomModule module, int alpha) {
            KeyStyle style = SHARED;
            style.keyAlpha = alpha * (int) Widgets.optionNumber("keystrokes", "key.opacity", 88) / 100;
            style.pressedFill = Widgets.optionColour("keystrokes", "key.pressed.fill", 0x3A3A3A);
            style.unpressedFill = Widgets.optionColour("keystrokes", "key.unpressed.fill", 0x1A1A1A);
            style.background = Widgets.option("keystrokes", "key.background", true);
            style.border = Widgets.option("keystrokes", "key.border", true);
            style.borderColour = Widgets.optionColour("keystrokes", "key.border.colour", 0x5C5C5C);
            style.borderWidth = (int) Widgets.optionNumber("keystrokes", "key.border.width", 1);
            style.pressedText = Widgets.optionColour("keystrokes", "key.pressed.text", 0xF2F2F2);
            style.unpressedText = Widgets.optionColour("keystrokes", "key.unpressed.text", 0xCCCCCC);
            style.radius = Math.min(HudStyle.radius(module), keySize() / 2);
            style.shadow = HudStyle.textShadow(module);
            return style;
        }
    }

    /**
     * {@code KeyBinding.isPressed()} rather than {@code wasPressed()}.
     *
     * <p>{@code wasPressed} consumes a queued press and returns true once, which is
     * how vanilla triggers an action. Calling it from a renderer would eat the jump
     * the player was trying to make.
     */
    private static boolean pressed(KeyBinding binding) {
        return binding != null && binding.isPressed();
    }

    private static void key(HudFont font, KeyStyle style, int x, int y, int width, int height,
            String label, float amount, boolean surfaces) {
        int keyAlpha = style.keyAlpha;
        if (surfaces) {
            int fill = mix(style.unpressedFill, style.pressedFill, amount);
            if (style.background) {
                Plate.draw(x, y, width, height, (keyAlpha << 24) | (fill & 0x00FFFFFF), style.radius, false);
            }
            if (style.border) {
                Plate.border(x, y, width, height, style.radius, style.borderWidth,
                        (keyAlpha << 24) | (style.borderColour & 0x00FFFFFF));
            }
            return;
        }
        int text = mix(style.unpressedText, style.pressedText, amount);
        int colour = (keyAlpha << 24) | (text & 0x00FFFFFF);
        float labelWidth = font.width(label);
        float textX = Math.round(x + (width - labelWidth) / 2f);
        // Centred on the capitals rather than on the line box, so a label sits
        // in the middle of its key in both typefaces.
        float textY = Math.round(font.centredTop(y, height));
        font.draw(label, textX, textY, colour, style.shadow);
    }

    /** Blend two RGB colours, for the press fade. */
    private static int mix(int from, int to, float amount) {
        if (amount <= 0f) {
            return from;
        }
        if (amount >= 1f) {
            return to;
        }
        int r = (int) (((from >> 16) & 0xFF) + (((to >> 16) & 0xFF) - ((from >> 16) & 0xFF)) * amount);
        int g = (int) (((from >> 8) & 0xFF) + (((to >> 8) & 0xFF) - ((from >> 8) & 0xFF)) * amount);
        int b = (int) ((from & 0xFF) + ((to & 0xFF) - (from & 0xFF)) * amount);
        return (r << 16) | (g << 8) | b;
    }
}
