package net.fathom.optimizations.fabric.hub;

import net.fathom.optimizations.core.hud.CrosshairShape;
import net.fathom.optimizations.core.module.CrosshairPresets;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.ui.layout.Rect;
import net.fathom.optimizations.core.ui.render.Renderer;
import net.fathom.optimizations.core.ui.render.Type;
import net.fathom.optimizations.fabric.ui.Skin;
import net.fathom.optimizations.fabric.ui.Controls;

/**
 * A live picture of what a module's settings currently produce.
 *
 * <h2>Why a module may have one and why almost none do</h2>
 *
 * <p>Most settings describe themselves: a toggle called "Seconds" adds seconds.
 * A crosshair does not — length, thickness, gap, dot and outline are five
 * numbers whose product you cannot picture, and the only way to choose them by
 * hand today is to close the menu, look, and open it again. So the crosshair
 * gets a preview and nothing else does, and the preview is a drawing, not a
 * picture: it asks {@link CrosshairShape} for the same rectangles the HUD draws,
 * so it cannot show something the game will not.
 *
 * <p>The configuration page asks {@link #of} for a preview and draws whatever it gets. A
 * module without one simply has more room for its settings.
 */
interface ModulePreview {

    /** How tall the preview area is, in logical units. */
    float height();

    void draw(Renderer ui, FathomModule module, Rect area);

    /** The preview for a module, or null when it does not have one. */
    static ModulePreview of(FathomModule module) {
        if (module == null) {
            return null;
        }
        if ("crosshair".equals(module.id())) {
            return CROSSHAIR;
        }
        if ("keystrokes".equals(module.id())) {
            return KEYSTROKES;
        }
        if ("armour".equals(module.id())) {
            return ARMOUR;
        }
        return "potions".equals(module.id()) ? POTIONS : null;
    }

    /**
     * HUD pixels to preview units: two, about the widget at GUI scale 2 on a
     * 1080p screen, rounded so every HUD pixel covers whole device pixels and
     * the game's sprites and font stay sharp.
     */
    static float hudScale(Renderer ui) {
        return NativeArt.crisp(ui, 2f);
    }

    /** Text as the HUD draws it -- Minecraft's font -- or the interface font when that one is unavailable. */
    static void label(Renderer ui, String text, float x, float top, float scale, int argb, boolean shadow) {
        if (NativeArt.fontAvailable()) {
            NativeArt.text(ui, text, x, top, scale, argb, shadow);
        } else {
            ui.text(text, x, top + ui.ascent(Type.SMALL), Type.SMALL, argb);
        }
    }

    static float labelWidth(Renderer ui, String text, float scale) {
        return NativeArt.fontAvailable() ? NativeArt.width(text, scale) : ui.measure(text, Type.SMALL);
    }

    /** One line of HUD text: the font's eight-pixel glyphs and the pixel under them. */
    static float labelHeight(Renderer ui, float scale) {
        return NativeArt.fontAvailable() ? 8f * scale : ui.lineHeight(Type.SMALL);
    }

    /** The module's plate behind a list, as the HUD draws it, when it has a material. */
    static void plate(Renderer ui, FathomModule module, float x, float y, float w, float h, float scale) {
        if (!HudStyle.background(module)) {
            return;
        }
        int fill = Skin.argb(HudStyle.backgroundAlpha(module) / 255f, HudStyle.backgroundColour(module));
        ui.rounded(x, y, x + w, y + h, HudStyle.radius(module) * scale, fill);
        if (HudStyle.border(module)) {
            ui.rim(x, y, x + w, y + h, HudStyle.radius(module) * scale,
                    Skin.argb(1f, HudStyle.borderColour(module)));
        }
    }

    /** The sunken well every preview stands in. */
    static void well(Renderer ui, Rect area) {
        ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_MEDIUM, Skin.RECESSED);
        ui.rim(area.x, area.y, area.right(), area.bottom(), Skin.R_MEDIUM, Skin.EDGE_RECESSED);
    }

    /**
     * Armour HUD with sample pieces -- a healthy helmet, a worn chestplate,
     * nearly broken leggings, boots and a held sword -- drawn with the game's
     * own item sprites and font, and laid out by the module's settings: what
     * is shown, the durability style, labels, vertical or horizontal, the
     * order, spacing, the hotbar's durability bar, and the amber and red
     * thresholds.
     */
    ModulePreview ARMOUR = new ModulePreview() {
        private final String[] slots = {"Helmet", "Chestplate", "Leggings", "Boots", "Held"};
        private final String[] names = {"Diamond Helmet", "Iron Chestplate", "Iron Leggings", "Diamond Boots",
            "Diamond Sword"};
        private final int[] left = {330, 96, 30, 410, 1200};
        private final int[] max = {363, 240, 225, 429, 1561};

        private net.minecraft.item.Item item(int index) {
            switch (index) {
                case 0:
                    return net.minecraft.item.Items.DIAMOND_HELMET;
                case 1:
                    return net.minecraft.item.Items.IRON_CHESTPLATE;
                case 2:
                    return net.minecraft.item.Items.IRON_LEGGINGS;
                case 3:
                    return net.minecraft.item.Items.DIAMOND_BOOTS;
                default:
                    return net.minecraft.item.Items.DIAMOND_SWORD;
            }
        }

        @Override
        public float height() {
            return 150f;
        }

        @Override
        public void draw(Renderer ui, FathomModule module, Rect area) {
            well(ui, area);
            float s = hudScale(ui);
            String display = module.choice("display", "Icons and values");
            boolean icons = !"Values only".equals(display);
            boolean values = !"Icons only".equals(display);
            String style = module.choice("style", "Percent");
            String labelStyle = module.choice("label", "None");
            boolean held = module.toggle("held", true);
            boolean horizontal = "Horizontal".equals(module.choice("layout", "Vertical"));
            boolean reverse = module.toggle("reverse", false);
            boolean bar = module.toggle("bar", false);
            boolean condition = module.toggle("condition", true);
            boolean shadow = HudStyle.textShadow(module);
            int warn = (int) Math.round(module.number("warn", 50));
            int danger = (int) Math.round(module.number("danger", 20));
            float size = Math.round(module.number("icon.size", 16)) * s;
            float gap = Math.round(module.number("spacing", 1)) * s;
            float pad = HudStyle.padding(module) * s;
            int count = held ? 5 : 4;
            int text = Skin.argb(1f, HudStyle.textColour(module));
            float textH = labelHeight(ui, s);

            String[] lines = new String[count];
            float widest = 0f;
            for (int i = 0; i < count; i++) {
                int index = reverse && i < 4 ? 3 - i : i;
                lines[i] = values ? line(index, style, labelStyle) : "";
                widest = Math.max(widest, labelWidth(ui, lines[i], s));
            }
            float rowH = Math.max(icons ? size : 0f, textH);
            float cell = horizontal ? Math.max(size, widest) : 0f;
            float contentW = horizontal ? count * cell + (count - 1) * gap
                    : (icons ? size + (values ? 2f * s : 0f) : 0f) + widest;
            float contentH = horizontal ? (icons ? size + s : 0f) + (values ? textH : 0f)
                    : count * rowH + (count - 1) * gap;
            float w = contentW + pad * 2f;
            float h = contentH + pad * 2f;
            float x0 = Math.round(area.centreX() - w / 2f);
            float y0 = Math.round(area.centreY() - h / 2f);
            plate(ui, module, x0, y0, w, h, s);

            for (int i = 0; i < count; i++) {
                int index = reverse && i < 4 ? 3 - i : i;
                int percent = left[index] * 100 / max[index];
                int colour = text;
                if (condition && percent <= danger) {
                    colour = Skin.argb(1f, net.fathom.optimizations.generated.GraphiteTokens.COLOR_STATUS_ERROR);
                } else if (condition && percent <= warn) {
                    colour = Skin.argb(1f, net.fathom.optimizations.generated.GraphiteTokens.COLOR_STATUS_WARN);
                }
                float ix = horizontal ? x0 + pad + i * (cell + gap) + (cell - size) / 2f : x0 + pad;
                float iy = horizontal ? y0 + pad : y0 + pad + i * (rowH + gap) + (rowH - size) / 2f;
                if (icons) {
                    NativeArt.item(ui, item(index), ix, iy, size, 1f);
                    if (bar) {
                        // The hotbar's bar: 13 of the item's 16 pixels, two high, two up from its foot.
                        float unit = size / 16f;
                        float bx = ix + 2f * unit;
                        float by = iy + 13f * unit;
                        ui.fill(bx, by, bx + 13f * unit, by + 2f * unit, 0xFF000000);
                        ui.fill(bx, by, bx + 13f * unit * percent / 100f, by + unit,
                                Skin.argb(1f, barColour(percent)));
                    }
                }
                if (values) {
                    float tw = labelWidth(ui, lines[i], s);
                    float tx = horizontal ? x0 + pad + i * (cell + gap) + (cell - tw) / 2f
                            : x0 + pad + (icons ? size + 2f * s : 0f);
                    float ty = horizontal ? y0 + pad + (icons ? size + s : 0f)
                            : y0 + pad + i * (rowH + gap) + (rowH - textH) / 2f;
                    label(ui, lines[i], tx, ty, s, colour, shadow);
                }
            }
            ui.text("Sample items", area.x + Skin.S3, area.bottom() - Skin.S3, Type.CAPTION, Skin.TEXT_DISABLED);
        }

        private String line(int index, String style, String labelStyle) {
            String value;
            if ("Remaining".equals(style)) {
                value = String.valueOf(left[index]);
            } else if ("Remaining / max".equals(style)) {
                value = left[index] + "/" + max[index];
            } else {
                value = (left[index] * 100 / max[index]) + "%";
            }
            if ("Slot".equals(labelStyle)) {
                return slots[index] + " " + value;
            }
            if ("Item name".equals(labelStyle)) {
                return names[index] + " " + value;
            }
            return value;
        }

        /** The hotbar's green to red, as vanilla colours a durability bar. */
        private int barColour(int percent) {
            float f = Math.max(0f, Math.min(1f, percent / 100f));
            int red = (int) (255 * (1f - f));
            int green = (int) (255 * f);
            return (red << 16) | (green << 8);
        }
    };

    /**
     * Potion HUD with sample effects -- Speed II, Regeneration II, and a
     * Strength about to run out -- with the game's own effect icons and font,
     * laid out by the module's settings: icons, names, levels, time left,
     * compact lines, layout, order, spacing, the effect colour and the ending
     * warning.
     */
    ModulePreview POTIONS = new ModulePreview() {
        private final String[] names = {"Speed", "Regeneration", "Strength"};
        private final String[] levels = {"II", "II", "I"};
        private final int[] seconds = {90, 45, 8};

        private net.minecraft.entity.effect.StatusEffect effect(int index) {
            return index == 0 ? net.minecraft.entity.effect.StatusEffect.SPEED
                    : index == 1 ? net.minecraft.entity.effect.StatusEffect.REGENERATION
                    : net.minecraft.entity.effect.StatusEffect.STRENGTH;
        }

        @Override
        public float height() {
            return 150f;
        }

        @Override
        public void draw(Renderer ui, FathomModule module, Rect area) {
            well(ui, area);
            float s = hudScale(ui);
            boolean icons = module.toggle("icons", true);
            boolean showNames = module.toggle("names", true);
            boolean level = module.toggle("level", true);
            boolean duration = module.toggle("duration", true);
            boolean compact = module.toggle("compact", false);
            boolean horizontal = "Horizontal".equals(module.choice("layout", "Vertical"));
            String sort = module.choice("sort", "Applied");
            boolean tint = module.toggle("effect-colour", false);
            boolean ending = module.toggle("ending", true);
            boolean shadow = HudStyle.textShadow(module);
            float size = Math.round(module.number("icon.size", 18)) * s;
            float gap = Math.round(module.number("spacing", 2)) * s;
            float pad = HudStyle.padding(module) * s;
            int text = Skin.argb(1f, HudStyle.textColour(module));
            int[] order = "Time left".equals(sort) ? new int[] {2, 1, 0}
                    : "Name".equals(sort) ? new int[] {1, 0, 2} : new int[] {0, 1, 2};
            float lineH = labelHeight(ui, s) + s;

            String[] first = new String[3];
            String[] second = new String[3];
            float widest = 0f;
            for (int i = 0; i < 3; i++) {
                int e = order[i];
                String name = showNames ? names[e] + (level ? " " + levels[e] : "") : "";
                String time = duration ? clock(seconds[e]) : "";
                if (compact || name.isEmpty() || time.isEmpty()) {
                    first[i] = (name + (name.isEmpty() || time.isEmpty() ? "" : " ") + time).trim();
                    second[i] = "";
                } else {
                    first[i] = name;
                    second[i] = time;
                }
                widest = Math.max(widest, Math.max(labelWidth(ui, first[i], s), labelWidth(ui, second[i], s)));
            }
            boolean twoLines = !compact && showNames && duration;
            float textH = lineH * (twoLines ? 2f : 1f) - s;
            float entryH = Math.max(icons ? size : 0f, textH);
            float entryW = (icons ? size + 3f * s : 0f) + widest;
            float contentW = horizontal ? 3 * entryW + 2 * gap : entryW;
            float contentH = horizontal ? entryH : 3 * entryH + 2 * gap;
            float w = contentW + pad * 2f;
            float h = contentH + pad * 2f;
            float x0 = Math.round(area.centreX() - w / 2f);
            float y0 = Math.round(area.centreY() - h / 2f);
            plate(ui, module, x0, y0, w, h, s);

            for (int i = 0; i < 3; i++) {
                int e = order[i];
                float ex = horizontal ? x0 + pad + i * (entryW + gap) : x0 + pad;
                float ey = horizontal ? y0 + pad : y0 + pad + i * (entryH + gap);
                if (icons) {
                    NativeArt.effect(ui, effect(e), ex, ey + (entryH - size) / 2f, size, 1f);
                }
                float tx = ex + (icons ? size + 3f * s : 0f);
                float ty = ey + (entryH - textH) / 2f;
                int nameColour = tint ? Skin.argb(1f, effect(e).getColor()) : text;
                int timeColour = ending && seconds[e] <= 10
                        ? Skin.argb(1f, net.fathom.optimizations.generated.GraphiteTokens.COLOR_STATUS_ERROR) : text;
                if (second[i].isEmpty()) {
                    label(ui, first[i], tx, ty, s, showNames ? nameColour : timeColour, shadow);
                } else {
                    label(ui, first[i], tx, ty, s, nameColour, shadow);
                    label(ui, second[i], tx, ty + lineH, s, timeColour, shadow);
                }
            }
            ui.text("Sample effects", area.x + Skin.S3, area.bottom() - Skin.S3, Type.CAPTION, Skin.TEXT_DISABLED);
        }

        private String clock(int total) {
            return (total / 60) + ":" + (total % 60 < 10 ? "0" : "") + (total % 60);
        }
    };

    /**
     * Keystrokes: the widget's own layout (rows, key size and gap, the mouse
     * row with clicks per second), its key fills, text colours, opacity,
     * border and radius, and the module's plate, with the labels in the
     * game's own font. W is shown pressed so both states are on screen.
     */
    ModulePreview KEYSTROKES = new ModulePreview() {

        @Override
        public float height() {
            return 150f;
        }

        @Override
        public void draw(Renderer ui, FathomModule module, Rect area) {
            well(ui, area);

            boolean wasd = module.toggle("wasd", true);
            boolean mouse = module.toggle("mouse", true);
            boolean space = module.toggle("space", true);
            boolean sneak = module.toggle("sneak", false);
            boolean cps = mouse && module.toggle("cps-on-keys", false);
            int rows = (wasd ? 2 : 0) + (mouse ? 1 : 0) + (space ? 1 : 0) + (sneak ? 1 : 0);
            if (rows == 0) {
                ui.text("No keys are switched on", area.x + Skin.S3, Controls.baseline(ui, area, Type.SMALL),
                        Type.SMALL, Skin.TEXT_MUTED);
                return;
            }
            float key = Math.round(module.number("key.size", 18));
            float gap = Math.round(module.number("key.gap", 3));
            float pad = HudStyle.padding(module);
            float extra = cps ? 9f : 0f;
            float wide = key * 3f + gap * 2f;
            float tall = rows * (key + gap) - gap + extra;
            float plateW = wide + pad * 2f;
            float plateH = tall + pad * 2f;

            // As large as fits, up to the HUD at GUI scale two, on whole device pixels.
            float roomH = area.height - Skin.S4 * 2f;
            float roomW = area.width - Skin.S4 * 2f;
            float fit = Math.min(roomH / plateH, roomW / plateW);
            float s = Math.min(hudScale(ui), Math.max(1f, (float) Math.floor(fit * ui.scale())) / ui.scale());
            float x0 = Math.round(area.centreX() - plateW * s / 2f);
            float y0 = Math.round(area.centreY() - plateH * s / 2f);
            float radius = Math.min(HudStyle.radius(module), key / 2f) * s;

            plate(ui, module, x0, y0, plateW * s, plateH * s, s);

            float alpha = (float) module.number("key.opacity", 88) / 100f;
            boolean fills = module.toggle("key.background", true);
            int pressedFill = Skin.argb(alpha, module.colour("key.pressed.fill", 0x3A3A3A));
            int unpressedFill = Skin.argb(alpha, module.colour("key.unpressed.fill", 0x1A1A1A));
            int pressedText = Skin.argb(1f, module.colour("key.pressed.text", 0xF2F2F2));
            int unpressedText = Skin.argb(1f, module.colour("key.unpressed.text", 0xCCCCCC));
            int border = module.toggle("key.border", true)
                    ? Skin.argb(alpha, module.colour("key.border.colour", 0x5C5C5C)) : 0;

            float leftX = x0 + pad * s;
            float y = y0 + pad * s;
            float k = key * s;
            float g = gap * s;
            KEYS.style(ui, s, radius, fills, pressedFill, unpressedFill, pressedText, unpressedText, border,
                    HudStyle.textShadow(module));
            if (wasd) {
                KEYS.key(leftX + k + g, y, k, k, keyName(0, "W"), true);
                y += k + g;
                KEYS.key(leftX, y, k, k, keyName(1, "A"), false);
                KEYS.key(leftX + k + g, y, k, k, keyName(2, "S"), false);
                KEYS.key(leftX + (k + g) * 2f, y, k, k, keyName(3, "D"), false);
                y += k + g;
            }
            if (mouse) {
                float half = Math.round((wide - gap) / 2f) * s;
                float h = (key + extra) * s;
                KEYS.key(leftX, y, half, h, cps ? "LMB 0" : "LMB", false);
                KEYS.key(leftX + half + g, y, wide * s - half - g, h, cps ? "RMB 0" : "RMB", false);
                y += h + g;
            }
            if (space) {
                KEYS.key(leftX, y, wide * s, k, "SPACE", false);
                y += k + g;
            }
            if (sneak) {
                KEYS.key(leftX, y, wide * s, k, "SHIFT", false);
            }
            ui.text("W shown pressed", area.x + Skin.S3, area.bottom() - Skin.S3, Type.CAPTION, Skin.TEXT_DISABLED);
        }

        /** The bound key's name, as the widget labels it; the default when unbound or a mouse button. */
        private String keyName(int which, String fallback) {
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client == null || client.options == null) {
                return fallback;
            }
            net.minecraft.client.option.KeyBinding binding = which == 0 ? client.options.forwardKey
                    : which == 1 ? client.options.leftKey : which == 2 ? client.options.backKey
                    : client.options.rightKey;
            int code = binding.getCode();
            if (code <= 0) {
                return fallback;
            }
            String name = org.lwjgl.input.Keyboard.getKeyName(code);
            return name == null || name.isEmpty() ? fallback : name;
        }
    };

    /** One preview key at a time, with the style set once per frame. Render thread only. */
    final class Keys {
        private Renderer ui;
        private float scale;
        private float radius;
        private boolean fills;
        private int pressedFill;
        private int unpressedFill;
        private int pressedText;
        private int unpressedText;
        private int border;
        private boolean shadow;

        void style(Renderer ui, float scale, float radius, boolean fills, int pressedFill, int unpressedFill,
                int pressedText, int unpressedText, int border, boolean shadow) {
            this.ui = ui;
            this.scale = scale;
            this.radius = radius;
            this.fills = fills;
            this.pressedFill = pressedFill;
            this.unpressedFill = unpressedFill;
            this.pressedText = pressedText;
            this.unpressedText = unpressedText;
            this.border = border;
            this.shadow = shadow;
        }

        void key(float x, float y, float w, float h, String label, boolean pressed) {
            if (fills) {
                ui.rounded(x, y, x + w, y + h, radius, pressed ? pressedFill : unpressedFill);
            }
            if (border != 0) {
                ui.rim(x, y, x + w, y + h, radius, border);
            }
            float width = labelWidth(ui, label, scale);
            float tx = Math.round((x + (w - width) / 2f) * ui.scale()) / ui.scale();
            float ty = Math.round((y + (h - labelHeight(ui, scale)) / 2f) * ui.scale()) / ui.scale();
            ModulePreview.label(ui, label, tx, ty, scale, pressed ? pressedText : unpressedText, shadow);
        }
    }

    /** One instance, like {@link #SHAPE}. */
    Keys KEYS = new Keys();

    ModulePreview CROSSHAIR = new ModulePreview() {

        @Override
        public float height() {
            return 96f;
        }

        @Override
        public void draw(Renderer ui, FathomModule module, Rect area) {
            ui.rounded(area.x, area.y, area.right(), area.bottom(), Skin.R_MEDIUM, Skin.RECESSED);
            ui.rim(area.x, area.y, area.right(), area.bottom(), Skin.R_MEDIUM, Skin.EDGE_RECESSED);

            String chosen = module.choice(CrosshairPresets.STYLE, "Cross");
            if ("Vanilla".equals(chosen)) {
                ui.text("Minecraft's own crosshair", area.x + Skin.S3,
                        Controls.baseline(ui, area, Type.SMALL), Type.SMALL, Skin.TEXT_MUTED);
                return;
            }

            CrosshairShape shape = SHAPE;
            shape.build(style(chosen),
                    (int) Math.round(module.number(CrosshairPresets.LENGTH, 4)),
                    (int) Math.round(module.number(CrosshairPresets.THICKNESS, 1)),
                    (int) Math.round(module.number(CrosshairPresets.GAP, 2)),
                    module.toggle(CrosshairPresets.DOT, false),
                    (int) Math.round(module.number(CrosshairPresets.DOT_SIZE, 1)),
                    module.toggle(CrosshairPresets.OUTLINE, true)
                            ? (int) Math.round(module.number(CrosshairPresets.OUTLINE_WIDTH, 1))
                            : 0);

            // Whole-number magnification, so the preview is the crosshair's own
            // pixels enlarged rather than a resampled blur of them.
            float room = Math.min(area.width - Skin.S5 * 2f, area.height - Skin.S5 * 2f);
            int scale = Math.max(1, Math.min(6, (int) (room / Math.max(1, shape.size()))));
            float size = shape.size() * scale;
            float left = area.centreX() - size / 2f;
            float top = area.centreY() - size / 2f;

            int alpha = (int) Math.round(module.number("opacity", 100)) * 255 / 100;
            int colour = Skin.argb(alpha / 255f, module.colour("colour", 0xFFFFFF));
            int outline = Skin.argb(alpha / 255f, module.colour("outline.colour", 0x000000));
            paint(ui, shape.outline(), shape.outlineCount(), left, top, scale, outline);
            paint(ui, shape.fill(), shape.fillCount(), left, top, scale, colour);

            ui.text(shape.size() + " x " + shape.size() + " px", area.x + Skin.S3,
                    area.bottom() - Skin.S3, Type.CAPTION, Skin.TEXT_DISABLED);
        }

        private void paint(Renderer ui, int[] rects, int count, float left, float top, int scale, int argb) {
            for (int index = 0; index < count; index += 4) {
                ui.fill(left + rects[index] * scale, top + rects[index + 1] * scale,
                        left + rects[index + 2] * scale, top + rects[index + 3] * scale, argb);
            }
        }

        private CrosshairShape.Style style(String chosen) {
            return CrosshairShape.Style.named(chosen);
        }
    };

    /** One instance: the detail page draws one preview at a time, on one thread. */
    CrosshairShape SHAPE = new CrosshairShape();
}
