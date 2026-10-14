package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.item.Items;
import net.minecraft.util.Formatting;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.module.Setting;

/**
 * What you are wearing and holding, with how much of it is left.
 *
 * <h2>Real items, not a picture of one</h2>
 *
 * <p>Each row draws the actual {@code ItemStack} through Minecraft's own item
 * renderer, so a resource pack's textures, a custom model and an enchantment
 * glint all appear exactly as they do in the inventory. Nothing here knows what
 * a diamond chestplate looks like, which is why nothing here can be wrong about
 * it. The whole row of items is drawn inside one set-up of the GUI item
 * lighting, the way the hotbar draws nine.
 *
 * <h2>Text is built when it changes, not when it is drawn</h2>
 *
 * <p>Durability text only changes when an item is damaged, swapped or a setting
 * is edited, and all three are rare next to sixty frames a second. The labels
 * and values are therefore rebuilt only when the items or the settings actually
 * differ from the last time; a frame where nothing changed allocates nothing.
 *
 * <h2>Layout is measured, not guessed</h2>
 *
 * <p>The same pass that draws the rows measures them, one row at a time, so the
 * plate is exactly as wide as the widest row at whatever font, scale and set of
 * switches are in force. Vertical stacks rows; horizontal puts the icons in a
 * line with the value under each.
 */
final class ArmourWidget implements HudWidget {

    /** Each line measured once while it is unchanged, not on every size asked for. */
    private final MeasuredWidths widths = new MeasuredWidths(16);

    private static final String[] SLOT_NAMES = {"Boots", "Legs", "Chest", "Helmet"};
    /** Item icons are drawn at 16 pixels and scaled to the chosen size. */
    private static final int ITEM = 16;

    private static int icon() {
        return (int) Math.round(Widgets.optionNumber("armour", "icon.size", ITEM));
    }

    /** Up to five entries: the held item and four armour slots. */
    private final ItemStack[] stacks = new ItemStack[6];
    private final String[] labels = new String[6];
    private final String[] values = new String[6];
    private final int[] percents = new int[6];
    /** Which slot each entry came from: -1 held, 0 boots to 3 helmet. */
    private final int[] slots = new int[6];
    private int count;
    private long collectedFrame = -1L;
    /** Where the widget is anchored, 0 left to 1 right: a right-hand readout mirrors. */
    private float alignment;
    /** Arrows carried, shown under a held bow. One stack object, its count updated. */
    private ItemStack arrows;
    private static final int ARROWS = -2;

    /** What the strings were built from, so they are not rebuilt for nothing. */
    private final Object[] builtStacks = new Object[6];
    private final int[] builtDamage = new int[6];
    private final int[] builtCount = new int[6];
    private int builtEntries = -1;
    private int builtSettings = -1;

    @Override
    public String id() {
        return "armour";
    }

    @Override
    public String placeholder() {
        return "Helmet 100%";
    }

    private static FathomModule module() {
        return Widgets.module("armour");
    }

    /** Gather the entries for this frame, once, and rebuild their text only if it changed. */
    private void collect(MinecraftClient client) {
        long frame = Widgets.frameNumber();
        if (frame == collectedFrame) {
            return;
        }
        collectedFrame = frame;
        count = 0;
        ClientPlayerEntity player = client.player;
        if (player == null || player.inventory == null) {
            builtEntries = -1;
            return;
        }
        boolean reverse = Widgets.option("armour", "reverse", false);
        ItemStack held = player.inventory.getMainHandStack();
        if (Widgets.option("armour", "held", true) && held != null) {
            slots[count] = -1;
            stacks[count++] = held;
            if (held.getItem() == Items.BOW && Widgets.option("armour", "arrows", true)) {
                int carried = arrowsCarried(player.inventory.main);
                if (carried > 0) {
                    if (arrows == null) {
                        arrows = new ItemStack(Items.ARROW);
                    }
                    arrows.count = carried;
                    slots[count] = ARROWS;
                    stacks[count++] = arrows;
                }
            }
        }
        ItemStack[] worn = player.inventory.armor;
        // The array is stored feet first; the default reading order is the way
        // it is worn, helmet at the top.
        for (int step = 0; step < worn.length; step++) {
            int slot = reverse ? step : worn.length - 1 - step;
            if (worn[slot] != null) {
                slots[count] = slot;
                stacks[count++] = worn[slot];
            }
        }
        if (!changed()) {
            return;
        }
        rebuild();
    }

    /** Whether anything the text depends on is different from last time. */
    private boolean changed() {
        if (builtEntries != count || builtSettings != Setting.revision()) {
            return true;
        }
        for (int index = 0; index < count; index++) {
            ItemStack stack = stacks[index];
            if (builtStacks[index] != stack
                    || builtDamage[index] != stack.getDamage()
                    || builtCount[index] != stack.count) {
                return true;
            }
        }
        return false;
    }

    private void rebuild() {
        String labelStyle = Widgets.optionChoice("armour", "label", "None");
        String valueStyle = Widgets.optionChoice("armour", "style", "Percent");
        for (int index = 0; index < count; index++) {
            ItemStack stack = stacks[index];
            labels[index] = label(stack, slotName(slots[index]), labelStyle);
            percents[index] = percentLeft(stack);
            values[index] = slots[index] == ARROWS ? "x" + stack.count : value(stack, valueStyle);
            builtStacks[index] = stack;
            builtDamage[index] = stack.getDamage();
            builtCount[index] = stack.count;
        }
        builtEntries = count;
        builtSettings = Setting.revision();
    }

    /** What to call the slot an entry came from, for the Slot label style. */
    private static String slotName(int slot) {
        if (slot == ARROWS) {
            return "Arrows";
        }
        return slot < 0 || slot >= SLOT_NAMES.length ? "Held" : SLOT_NAMES[slot];
    }

    private static int arrowsCarried(ItemStack[] main) {
        int total = 0;
        if (main == null) {
            return 0;
        }
        for (ItemStack stack : main) {
            if (stack != null && stack.getItem() == Items.ARROW) {
                total += stack.count;
            }
        }
        return total;
    }

    @Override
    public void align(float horizontal) {
        alignment = horizontal;
    }

    @Override
    public boolean animating(MinecraftClient client) {
        if (!icons()) {
            return false;
        }
        collect(client);
        for (int index = 0; index < count; index++) {
            if (stacks[index] != null && stacks[index].hasEnchantmentGlint()) {
                return true;
            }
        }
        return false;
    }

    private static String label(ItemStack stack, String slotName, String style) {
        if ("Slot".equals(style)) {
            return slotName;
        }
        if ("Item name".equals(style)) {
            // Stripped of colour codes: a pack or a server can name an item in
            // section-sign colours, and those would draw as boxes in Fathom's
            // typeface and as stray colour in Minecraft's.
            return Formatting.strip(String.valueOf(stack.getCustomName()));
        }
        return "";
    }

    private static String value(ItemStack stack, String style) {
        if (!stack.isDamageable()) {
            return stack.count > 1 ? "x" + stack.count : "";
        }
        int max = stack.getMaxDamage();
        int left = Math.max(0, max - stack.getDamage());
        if ("Remaining".equals(style)) {
            return String.valueOf(left);
        }
        if ("Remaining / max".equals(style)) {
            return left + " / " + max;
        }
        return percentLeft(stack) + "%";
    }

    private static int percentLeft(ItemStack stack) {
        if (stack == null || !stack.isDamageable()) {
            return 100;
        }
        int max = stack.getMaxDamage();
        if (max <= 0) {
            return 100;
        }
        return Math.max(0, Math.round((max - stack.getDamage()) * 100f / max));
    }

    /** The text beside or under an icon, which may be a label, a value or both. */
    private String text(int index) {
        String label = labels[index];
        String value = values[index];
        if (label == null || label.isEmpty()) {
            return value == null ? "" : value;
        }
        if (value == null || value.isEmpty()) {
            return label;
        }
        // Joined once per change, not per call: it is asked for to size the
        // box and again to draw, every frame, and its width is cached by the
        // string object.
        if (joinedLabels[index] != label || joinedValues[index] != value) {
            joinedLabels[index] = label;
            joinedValues[index] = value;
            joined[index] = label + " " + value;
        }
        return joined[index];
    }

    private final String[] joined = new String[6];
    private final String[] joinedLabels = new String[6];
    private final String[] joinedValues = new String[6];

    private static boolean icons() {
        return !"Values only".equals(Widgets.optionChoice("armour", "display", "Icons and values"));
    }

    private static boolean text() {
        return !"Icons only".equals(Widgets.optionChoice("armour", "display", "Icons and values"));
    }

    private static boolean horizontal() {
        return "Horizontal".equals(Widgets.optionChoice("armour", "layout", "Vertical"));
    }

    private static int spacing() {
        return (int) Math.round(Widgets.optionNumber("armour", "spacing", 1));
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        collect(client);
        int pad = HudStyle.padding(module());
        int gap = spacing();
        if (count == 0) {
            return pad * 2;
        }
        if (horizontal()) {
            int width = 0;
            for (int index = 0; index < count; index++) {
                width += cellWidth(font, index) + gap;
            }
            return width - gap + pad * 2;
        }
        int widest = 0;
        for (int index = 0; index < count; index++) {
            widest = Math.max(widest, rowWidth(font, index));
        }
        return widest + pad * 2;
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        collect(client);
        int pad = HudStyle.padding(module());
        int gap = spacing();
        if (count == 0) {
            return pad * 2;
        }
        if (horizontal()) {
            int height = icons() ? icon() : 0;
            if (text()) {
                height += (icons() ? 1 : 0) + (int) Math.ceil(font.lineHeight());
            }
            return height + pad * 2;
        }
        return count * (rowHeight(font) + gap) - gap + pad * 2;
    }

    private int rowHeight(HudFont font) {
        int textHeight = text() ? (int) Math.ceil(font.lineHeight()) : 0;
        return icons() ? Math.max(icon(), textHeight) : textHeight;
    }

    private int rowWidth(HudFont font, int index) {
        int width = icons() ? icon() : 0;
        if (text()) {
            String line = text(index);
            if (!line.isEmpty()) {
                width += (icons() ? 2 : 0) + (int) Math.ceil(widths.of(font, line));
            }
        }
        return width;
    }

    private int cellWidth(HudFont font, int index) {
        int width = icons() ? icon() : 0;
        if (text()) {
            width = Math.max(width, (int) Math.ceil(widths.of(font, text(index))));
        }
        return width;
    }

    @Override
    public boolean visible(MinecraftClient client) {
        collect(client);
        return client.player != null && count > 0;
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        collect(client);
        if (count == 0) {
            return;
        }
        FathomModule module = module();
        int pad = HudStyle.padding(module);
        TextWidget.backdrop(module, x, y, width(client, font), height(client, font), alpha);

        boolean icons = icons();
        boolean text = text();
        int size = icon();
        boolean bar = Widgets.option("armour", "bar", false);
        boolean shadow = HudStyle.textShadow(module);
        boolean horizontal = horizontal();
        int gap = spacing();
        int left = x + pad;
        int top = y + pad;
        int rowHeight = horizontal ? 0 : rowHeight(font);

        // Pinned to the right, a row reads from the edge inwards: icon at the
        // edge, the value to its left, rows right-aligned.
        boolean mirrored = !horizontal && alignment > 0.5f;
        int inner = width(client, font) - HudStyle.padding(module) * 2;
        if (icons) {
            HudGl.itemsBegin();
            try {
                int cursor = left;
                for (int index = 0; index < count; index++) {
                    // The arrow count is printed beside it; the stack's own
                    // number on the icon would say it twice.
                    boolean overlay = bar && slots[index] != ARROWS;
                    if (horizontal) {
                        int cell = cellWidth(font, index);
                        item(client, stacks[index], cursor + (cell - size) / 2, top, size, overlay);
                        cursor += cell + gap;
                    } else {
                        int iconX = mirrored ? left + inner - size : left;
                        item(client, stacks[index], iconX,
                                top + index * (rowHeight + gap) + (rowHeight - size) / 2, size, overlay);
                    }
                }
            } finally {
                HudGl.itemsEnd();
            }
        }
        if (!text) {
            return;
        }
        int cursor = left;
        for (int index = 0; index < count; index++) {
            String line = text(index);
            if (horizontal) {
                int cell = cellWidth(font, index);
                float textX = cursor + (cell - widths.of(font, line)) / 2f;
                font.draw(line, Math.round(textX), top + (icons ? size + 1 : 0),
                        colour(module, index, alpha), shadow);
                cursor += cell + gap;
                continue;
            }
            int rowTop = top + index * (rowHeight + gap);
            float textX = mirrored
                    ? left + inner - (icons ? size + 2 : 0) - widths.of(font, line)
                    : left + (icons ? size + 2 : 0);
            float textY = rowTop + (rowHeight - font.lineHeight()) / 2f;
            font.draw(line, Math.round(textX), Math.round(textY), colour(module, index, alpha), shadow);
        }
    }

    /** White, amber or red, by how much of the item is left. */
    private int colour(FathomModule module, int index, int alpha) {
        int rgb = HudStyle.textColour(module);
        if (Widgets.option("armour", "condition", true) && stacks[index] != null
                && stacks[index].isDamageable()) {
            int percent = percents[index];
            int danger = (int) Math.round(Widgets.optionNumber("armour", "danger", 20));
            int warn = (int) Math.round(Widgets.optionNumber("armour", "warn", 50));
            if (percent <= danger) {
                rgb = net.fathom.optimizations.generated.GraphiteTokens.COLOR_STATUS_ERROR & 0xFFFFFF;
            } else if (percent <= warn) {
                rgb = net.fathom.optimizations.generated.GraphiteTokens.COLOR_STATUS_WARN & 0xFFFFFF;
            }
        }
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    /** An item at {@code size} pixels: drawn at its native sixteen and scaled about its corner. */
    private static void item(MinecraftClient client, ItemStack stack, int x, int y, int size, boolean overlay) {
        if (size == ITEM) {
            HudGl.itemQuad(client, stack, x, y, overlay);
            return;
        }
        com.mojang.blaze3d.platform.GlStateManager.pushMatrix();
        try {
            com.mojang.blaze3d.platform.GlStateManager.translate(x, y, 0f);
            float scale = size / (float) ITEM;
            com.mojang.blaze3d.platform.GlStateManager.scale(scale, scale, 1f);
            HudGl.itemQuad(client, stack, 0, 0, overlay);
        } finally {
            com.mojang.blaze3d.platform.GlStateManager.popMatrix();
        }
    }
}
