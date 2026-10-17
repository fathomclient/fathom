package net.fathom.optimizations.fabric.hud;

import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.resource.language.I18n;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.util.Identifier;

import net.fathom.optimizations.core.hud.EffectTime;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.HudStyle;
import net.fathom.optimizations.core.module.Setting;

/**
 * Active effects, their level and how long each has left.
 *
 * <h2>Icons come from Minecraft's own sheet</h2>
 *
 * <p>The same {@code inventory.png} strip the effects screen uses, at the index
 * the effect itself reports, so a resource pack that restyles potion icons
 * restyles these too and an effect added by a mod draws its own icon without
 * this class knowing about it.
 *
 * <h2>Text that cannot overlap</h2>
 *
 * <p>Every entry is measured before anything is drawn: the icon, the name and
 * the time it has left. The widest of them sets the plate, so a row of "Speed
 * II" beside "8:20" never runs into a row of "Fire Resistance" beside "0:07",
 * and the list is exactly as wide as it needs to be. Compact puts the time on
 * the same line as the name instead, which is two-thirds of the height for a
 * player who mostly wants to know what is on.
 *
 * <h2>Rebuilt when it changes, not when it is drawn</h2>
 *
 * <p>The names change when the effects do and the times change once a second,
 * so both are rebuilt then rather than sixty times a second — a translated name
 * and a formatted duration per effect per frame was the most this widget
 * allocated. The icons are drawn as one run of quads from Minecraft's own
 * sheet: one bind and one draw call for however many effects are on.
 */
final class PotionsWidget implements HudWidget {

    /** Each line measured once while it is unchanged, not on every size asked for. */
    private final MeasuredWidths widths = new MeasuredWidths(32);

    private static final Identifier SHEET = new Identifier("textures/gui/container/inventory.png");
    private static final int ICON = 18;
    private static final int COMPACT_ICON = 10;
    /** The last ten seconds, where a duration is worth noticing. */
    private static final int ENDING_TICKS = 200;

    private final List<StatusEffectInstance> effects = new ArrayList<StatusEffectInstance>();
    private final List<String> names = new ArrayList<String>();
    private final List<String> times = new ArrayList<String>();
    private long collectedFrame = -1L;

    /** What the strings were built from: the effects, their levels and the second shown. */
    private int builtEffects = Integer.MIN_VALUE;
    private int builtSeconds = Integer.MIN_VALUE;
    private int builtSettings = -1;

    private static final Comparator<StatusEffectInstance> BY_TIME = new Comparator<StatusEffectInstance>() {
        @Override
        public int compare(StatusEffectInstance first, StatusEffectInstance second) {
            return Integer.compare(EffectTime.sortKey(first.getDuration(), first.isPermanent()),
                    EffectTime.sortKey(second.getDuration(), second.isPermanent()));
        }
    };

    @Override
    public String id() {
        return "potions";
    }

    @Override
    public String placeholder() {
        return "Speed II  1:30";
    }

    private static FathomModule module() {
        return Widgets.module("potions");
    }

    private void collect(MinecraftClient client) {
        long frame = Widgets.frameNumber();
        if (frame == collectedFrame) {
            return;
        }
        collectedFrame = frame;
        effects.clear();
        names.clear();
        times.clear();
        if (client.player == null) {
            builtEffects = Integer.MIN_VALUE;
            return;
        }
        for (StatusEffectInstance effect : client.player.getStatusEffectInstances()) {
            if (effect != null && type(effect) != null) {
                effects.add(effect);
            }
        }
        String order = Widgets.optionChoice("potions", "sort", "Applied");
        if ("Time left".equals(order)) {
            Collections.sort(effects, BY_TIME);
        }
        if (!changed()) {
            return;
        }
        boolean level = Widgets.option("potions", "level", true);
        for (int index = 0; index < effects.size(); index++) {
            names.add(name(effects.get(index), level));
            StatusEffectInstance effect = effects.get(index);
            times.add(EffectTime.format(effect.getDuration(), effect.isPermanent()));
        }
        if ("Name".equals(order)) {
            sortByName();
        }
        builtSettings = Setting.revision();
        buildLines();
        kept.clear();
        kept.addAll(names);
        keptTimes.clear();
        keptTimes.addAll(times);
    }

    /**
     * Whether the text has to be built again.
     *
     * <p>Which effects, at which levels, showing which second. Everything else
     * about a frame leaves the strings correct, and the common case — nothing
     * changed since the last frame — keeps them and allocates nothing.
     */
    private boolean changed() {
        int signature = 1;
        int seconds = 0;
        for (int index = 0; index < effects.size(); index++) {
            StatusEffectInstance effect = effects.get(index);
            signature = signature * 31 + effect.getEffectId() * 8 + effect.getAmplifier();
            seconds = seconds * 31 + effect.getDuration() / 20;
        }
        if (signature == builtEffects && seconds == builtSeconds
                && builtSettings == Setting.revision()
                && kept.size() == effects.size()) {
            // Restore the strings the last build produced; only the list of
            // effect instances was rebuilt above.
            names.addAll(kept);
            times.addAll(keptTimes);
            return false;
        }
        builtEffects = signature;
        builtSeconds = seconds;
        kept.clear();
        keptTimes.clear();
        return true;
    }

    /** The last build, kept so an unchanged frame can reuse it. */
    private final List<String> kept = new ArrayList<String>();
    private final List<String> keptTimes = new ArrayList<String>();

    /** Sort the three lists together, by name. A list of five, so a simple pass. */
    private void sortByName() {
        for (int outer = 1; outer < names.size(); outer++) {
            for (int index = outer; index > 0 && names.get(index - 1).compareTo(names.get(index)) > 0; index--) {
                Collections.swap(names, index - 1, index);
                Collections.swap(times, index - 1, index);
                Collections.swap(effects, index - 1, index);
            }
        }
    }

    private static StatusEffect type(StatusEffectInstance effect) {
        int id = effect.getEffectId();
        if (id < 0 || id >= StatusEffect.STATUS_EFFECTS.length) {
            return null;
        }
        return StatusEffect.STATUS_EFFECTS[id];
    }

    private static String name(StatusEffectInstance effect, boolean level) {
        StatusEffect type = type(effect);
        StringBuilder out = new StringBuilder(I18n.translate(type.getTranslationKey()));
        if (level && effect.getAmplifier() > 0) {
            out.append(' ').append(roman(effect.getAmplifier() + 1));
        }
        return out.toString();
    }

    /**
     * Effect levels are Roman numerals in Minecraft, and only ever small.
     *
     * <p>Four entries rather than a general algorithm: vanilla caps amplifiers
     * well below anything that needs one, and a general converter would be
     * twenty lines to render a number nobody will see.
     */
    private static String roman(int level) {
        switch (level) {
            case 1:
                return "I";
            case 2:
                return "II";
            case 3:
                return "III";
            case 4:
                return "IV";
            default:
                return String.valueOf(level);
        }
    }

    private static boolean icons() {
        return Widgets.option("potions", "icons", true);
    }

    private static boolean showNames() {
        return Widgets.option("potions", "names", true);
    }

    private static boolean showTimes() {
        return Widgets.option("potions", "duration", true);
    }

    private static boolean compact() {
        return Widgets.option("potions", "compact", false);
    }

    private static boolean horizontal() {
        return "Horizontal".equals(Widgets.optionChoice("potions", "layout", "Vertical"));
    }

    private static int spacing() {
        return (int) Math.round(Widgets.optionNumber("potions", "spacing", 2));
    }

    /** The chosen icon size; compact lines take it in proportion to their smaller height. */
    private static int iconSize() {
        int chosen = (int) Math.round(Widgets.optionNumber("potions", "icon.size", ICON));
        return compact() ? Math.max(6, Math.round(chosen * COMPACT_ICON / (float) ICON)) : chosen;
    }

    /** The width of one entry, icon and text together. */
    private int entryWidth(HudFont font, int index) {
        int width = icons() ? iconSize() : 0;
        int text = 0;
        if (compact()) {
            text = (int) Math.ceil(widths.of(font, line(index)));
        } else {
            if (showNames()) {
                text = (int) Math.ceil(widths.of(font, names.get(index)));
            }
            if (showTimes()) {
                text = Math.max(text, (int) Math.ceil(widths.of(font, times.get(index))));
            }
        }
        if (text > 0) {
            width += (icons() ? 3 : 0) + text;
        }
        return width;
    }

    /** One compact line: name and time, whichever of them are on. Built with the rest of the text. */
    private String line(int index) {
        return index < lines.size() ? lines.get(index) : "";
    }

    private final List<String> lines = new ArrayList<String>();

    private void buildLines() {
        lines.clear();
        boolean showNames = showNames();
        boolean showTimes = showTimes();
        for (int index = 0; index < names.size(); index++) {
            if (showNames && showTimes) {
                lines.add(names.get(index) + "  " + times.get(index));
            } else if (showNames) {
                lines.add(names.get(index));
            } else {
                lines.add(showTimes ? times.get(index) : "");
            }
        }
    }

    private int entryHeight(HudFont font) {
        int text;
        if (compact()) {
            text = (int) Math.ceil(font.lineHeight());
        } else {
            int lines = (showNames() ? 1 : 0) + (showTimes() ? 1 : 0);
            text = (int) Math.ceil(lines * font.lineHeight() + Math.max(0, lines - 1));
        }
        return icons() ? Math.max(iconSize(), text) : text;
    }

    @Override
    public int width(MinecraftClient client, HudFont font) {
        collect(client);
        int pad = HudStyle.padding(module());
        if (effects.isEmpty()) {
            return pad * 2;
        }
        int gap = spacing();
        if (horizontal()) {
            int width = 0;
            for (int index = 0; index < effects.size(); index++) {
                width += entryWidth(font, index) + gap;
            }
            return width - gap + pad * 2;
        }
        int widest = 0;
        for (int index = 0; index < effects.size(); index++) {
            widest = Math.max(widest, entryWidth(font, index));
        }
        return widest + pad * 2;
    }

    @Override
    public int height(MinecraftClient client, HudFont font) {
        collect(client);
        int pad = HudStyle.padding(module());
        if (effects.isEmpty()) {
            return pad * 2;
        }
        if (horizontal()) {
            return entryHeight(font) + pad * 2;
        }
        return effects.size() * (entryHeight(font) + spacing()) - spacing() + pad * 2;
    }

    @Override
    public boolean visible(MinecraftClient client) {
        collect(client);
        return client.player != null && !effects.isEmpty();
    }

    @Override
    public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
        collect(client);
        if (effects.isEmpty()) {
            return;
        }
        FathomModule module = module();
        int pad = HudStyle.padding(module);
        TextWidget.backdrop(module, x, y, width(client, font), height(client, font), alpha);

        boolean icons = icons();
        boolean compact = compact();
        boolean shadow = HudStyle.textShadow(module);
        int size = iconSize();
        int gap = spacing();
        int rowHeight = entryHeight(font);
        int left = x + pad;
        int top = y + pad;

        if (icons) {
            // One bind, one draw, however many effects are on.
            HudGl.spritesBegin(client, SHEET, alpha);
            try {
                int cursor = left;
                int row = top;
                for (int index = 0; index < effects.size(); index++) {
                    StatusEffect type = type(effects.get(index));
                    if (type != null && type.hasIcon()) {
                        int level = type.getIconLevel();
                        HudGl.spriteQuad(cursor, row + (rowHeight - size) / 2f, size, size,
                                level % 8 * 18, 198 + level / 8 * 18, 18, 18);
                    }
                    if (horizontal()) {
                        cursor += entryWidth(font, index) + gap;
                    } else {
                        row += rowHeight + gap;
                    }
                }
            } finally {
                HudGl.spritesEnd();
            }
        }

        for (int index = 0; index < effects.size(); index++) {
            int entryLeft = left;
            int entryTop = top;
            if (horizontal()) {
                left += entryWidth(font, index) + gap;
            } else {
                top += rowHeight + gap;
            }
            int textLeft = entryLeft + (icons ? size + 3 : 0);
            if (compact) {
                String text = line(index);
                if (!text.isEmpty()) {
                    font.draw(text, textLeft, Math.round(entryTop + (rowHeight - font.lineHeight()) / 2f),
                            nameColour(module, index, alpha), shadow);
                }
                continue;
            }
            float textY = entryTop;
            if (showNames()) {
                font.draw(names.get(index), textLeft, textY, nameColour(module, index, alpha), shadow);
                textY += font.lineHeight() + 1;
            }
            if (showTimes()) {
                font.draw(times.get(index), textLeft, textY, timeColour(module, index, alpha), shadow);
            }
        }
    }

    /** The declared text colour, or the effect's own when that is switched on. */
    private int nameColour(FathomModule module, int index, int alpha) {
        int rgb = HudStyle.textColour(module);
        if (Widgets.option("potions", "effect-colour", false)) {
            StatusEffect type = type(effects.get(index));
            if (type != null) {
                rgb = type.getColor();
            }
        }
        return (alpha << 24) | (rgb & 0x00FFFFFF);
    }

    /** Dimmer than the name, and red once the effect is nearly gone. */
    private int timeColour(FathomModule module, int index, int alpha) {
        StatusEffectInstance effect = effects.get(index);
        if (Widgets.option("potions", "ending", true)
                && !EffectTime.permanent(effect.getDuration(), effect.isPermanent())
                && effect.getDuration() <= ENDING_TICKS) {
            return (alpha << 24) | (net.fathom.optimizations.generated.GraphiteTokens.COLOR_STATUS_ERROR & 0x00FFFFFF);
        }
        int rgb = HudStyle.textColour(module);
        // Three quarters of the text colour, so the time reads as secondary
        // without needing a colour setting of its own.
        int r = ((rgb >> 16) & 0xFF) * 3 / 4;
        int g = ((rgb >> 8) & 0xFF) * 3 / 4;
        int b = (rgb & 0xFF) * 3 / 4;
        return (alpha << 24) | (r << 16) | (g << 8) | b;
    }
}
