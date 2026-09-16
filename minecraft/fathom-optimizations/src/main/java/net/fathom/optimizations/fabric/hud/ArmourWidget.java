package net.fathom.optimizations.fabric.hud;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.item.ItemStack;
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

    private static final String[] SLOT_NAMES = {"Boots", "Legs", "Chest", "Helmet"};
    private static final int ICON = 16;

    /** Up to five entries: the held item and four armour slots. */
    private final ItemStack[] stacks = new ItemStack[5];
    private final String[] labels = new String[5];
    private final String[] values = new String[5];
    private final int[] percents = new int[5];
    /** Which slot each entry came from: -1 held, 0 boots to 3 helmet. */
    private final int[] slots = new int[5];
    private int count;
    private long collectedFrame = -1L;

    /** What the strings were built from, so they are not rebuilt for nothing. */
    private final Object[] builtStacks = new Object[5];
    private final int[] builtDamage = new int[5];
    private final int[] builtCount = new int[5];
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
        if (Widgets.option("armour", "held", true) && player.inventory.getMainHandStack() != null) {
            slots[count] = -1;
            stacks[count++] = player.inventory.getMainHandStack();
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
            values[index] = value(stack, valueStyle);
            builtStacks[index] = stack;
            builtDamage[index] = stack.getDamage();
            builtCount[index] = stack.count;
        }
        builtEntries = count;
        builtSettings = Setting.revision();
    }

    /** What to call the slot an entry came from, for the Slot label style. */
    private static String slotName(int slot) {
        return slot < 0 || slot >= SLOT_NAMES.length ? "Held" : SLOT_NAMES[slot];
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
        return value == null || value.isEmpty() ? label : label + " " + value;
    }

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
            int height = icons() ? ICON : 0;
            if (text()) {
                height += (icons() ? 1 : 0) + (int) Math.ceil(font.lineHeight());
            }
            return height + pad * 2;
        }
        return count * (rowHeight(font) + gap) - gap + pad * 2;
    }

    private int rowHeight(HudFont font) {
        int textHeight = text() ? (int) Math.ceil(font.lineHeight()) : 0;
        return icons() ? Math.max(ICON, textHeight) : textHeight;
    }

    private int rowWidth(HudFont font, int index) {
        int width = icons() ? ICON : 0;
        if (text()) {
            String line = text(index);
            if (!line.isEmpty()) {
                width += (icons() ? 2 : 0) + (int) Math.ceil(font.width(line));
            }
        }
        return width;
    }

    private int cellWidth(HudFont font, int index) {
        int width = icons() ? ICON : 0;
        if (text()) {
            width = Math.max(width, (int) Math.ceil(font.width(text(index))));
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
        boolean bar = Widgets.option("armour", "bar", false);
        boolean shadow = HudStyle.textShadow(module);
        boolean horizontal = horizontal();
        int gap = spacing();
        int left = x + pad;
        int top = y + pad;
        int rowHeight = horizontal ? 0 : rowHeight(font);

        if (icons) {
            HudGl.itemsBegin();
            try {
                int cursor = left;
                for (int index = 0; index < count; index++) {
                    if (horizontal) {
                        int cell = cellWidth(font, index);
                        HudGl.itemQuad(client, stacks[index], cursor + (cell - ICON) / 2, top, bar);
                        cursor += cell + gap;
                    } else {
                        HudGl.itemQuad(client, stacks[index], left,
                                top + index * (rowHeight + gap) + (rowHeight - ICON) / 2, bar);
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
                float textX = cursor + (cell - font.width(line)) / 2f;
                font.draw(line, Math.round(textX), top + (icons ? ICON + 1 : 0),
                        colour(module, index, alpha), shadow);
                cursor += cell + gap;
                continue;
            }
            int rowTop = top + index * (rowHeight + gap);
            float textX = left + (icons ? ICON + 2 : 0);
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
                rgb = 0xE0505C;
            } else if (percent <= warn) {
                rgb = 0xE0B341;
            }
        }
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }
}
