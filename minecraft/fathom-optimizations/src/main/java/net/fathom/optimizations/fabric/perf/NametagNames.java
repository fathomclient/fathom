package net.fathom.optimizations.fabric.perf;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerEntity;
import net.minecraft.entity.vehicle.AbstractMinecartEntity;
import net.minecraft.scoreboard.AbstractTeam;
import net.minecraft.scoreboard.Team;
import net.minecraft.text.Text;

import net.fathom.optimizations.fabric.Runtime;

/**
 * The label string a nametag shows, built once per change instead of once per frame
 * -- {@code render.entity.nametag-name-cache}.
 *
 * <h2>What vanilla does every frame, for every label</h2>
 *
 * <p>{@code renderName} asks {@code entity.getName().asFormattedString()}. For a player
 * {@code getName()} builds a {@code LiteralText} with a click event ({@code "/msg " +
 * name}), an insertion, and a hover event whose text is an NBT compound serialised to
 * a string: {@code UUID.toString()}, a {@code HashMap}, and one Java 8
 * {@code String.replace} per value, each of which compiles a regular expression. The
 * label uses none of it. {@code asFormattedString()} then walks the component and
 * appends its style codes (none) and a reset. What is drawn is
 * {@code prefix + name + suffix + "§r"} for a player, {@code name + "§r"} for any
 * other vanilla entity.
 *
 * <h2>What this does</h2>
 *
 * <p>Calls the original {@code getName()} and {@code asFormattedString()} exactly as
 * vanilla does, keeps both results on the entity, and hands them back while every
 * input of the string is unchanged: the name, and for a player its team and that
 * team's prefix and suffix. Anything else -- a different team, a renamed hologram, a
 * prefix the server changed -- calls the original again in that frame. The string is
 * never composed here, so it cannot disagree with vanilla.
 *
 * <p>Only for classes whose {@code getName()} depends on nothing else: vanilla's own
 * players and entities. Villagers and minecarts override it with their own rules, and
 * entities from mods could do anything; both always take the original path.
 */
public final class NametagNames {

    private NametagNames() {}

    /** What an entity keeps between frames. Implemented on {@code Entity} by a Mixin. */
    public interface Holder {
        NametagNames.Entry fathom$nameEntry();

        void fathom$setNameEntry(NametagNames.Entry entry);
    }

    public static final class Entry {
        Text text;
        String formatted;
        String key;
        AbstractTeam team;
        String prefix;
        String suffix;
    }

    private static final int NONE = 0;
    private static final int ENTITY = 1;
    private static final int PLAYER = 2;

    private static final ClassValue<Integer> KINDS = new ClassValue<Integer>() {
        @Override
        protected Integer computeValue(Class<?> type) {
            return Integer.valueOf(classify(type));
        }
    };

    static int classify(Class<?> type) {
        // Vanilla's own classes only. In a production launch they are
        // net.minecraft.class_NNNN; in development net.minecraft.entity.*.
        if (!type.getName().startsWith("net.minecraft.")) {
            return NONE;
        }
        if (PlayerEntity.class.isAssignableFrom(type)) {
            return PLAYER;
        }
        if (VillagerEntity.class.isAssignableFrom(type) || AbstractMinecartEntity.class.isAssignableFrom(type)) {
            return NONE;
        }
        return Entity.class.isAssignableFrom(type) ? ENTITY : NONE;
    }

    /** The Text the last {@link #name} returned, and its formatted string. Client thread only. */
    private static Text lastText;
    private static String lastFormatted;

    /** Statistics for the benchmark: names served from the entity, and rebuilt. */
    public static long hits;
    public static long builds;

    /**
     * {@code -Dfathom.verify.caches=true}: every served string is checked against a
     * fresh run of the original two calls. Diagnostic only; costs what the cache saves.
     */
    public static final boolean VERIFY = Boolean.getBoolean("fathom.verify.caches");
    public static long verified;
    public static long mismatches;
    public static String firstMismatch;

    /** Replaces {@code entity.getName()} in {@code renderName}. */
    public static Text name(Entity entity) {
        int kind = Runtime.nametagNameCache ? KINDS.get(entity.getClass()).intValue() : NONE;
        if (kind == NONE || !(entity instanceof Holder)) {
            lastText = null;
            return entity.getName();
        }
        String key = entity.getTranslationKey();
        AbstractTeam team = null;
        String prefix = null;
        String suffix = null;
        if (kind == PLAYER) {
            team = ((PlayerEntity) entity).getScoreboardTeam();
            if (team != null) {
                if (team.getClass() != Team.class) {
                    // A team type this does not know the decoration rules of.
                    lastText = null;
                    return entity.getName();
                }
                prefix = ((Team) team).getPrefix();
                suffix = ((Team) team).getSuffix();
            }
        }
        Holder holder = (Holder) entity;
        Entry entry = holder.fathom$nameEntry();
        if (entry != null && entry.team == team && equal(entry.key, key) && equal(entry.prefix, prefix)
                && equal(entry.suffix, suffix)) {
            hits++;
            if (VERIFY) {
                String fresh = entity.getName().asFormattedString();
                verified++;
                if (!fresh.equals(entry.formatted)) {
                    mismatches++;
                    if (firstMismatch == null) {
                        firstMismatch = "'" + entry.formatted + "' served, '" + fresh + "' expected";
                    }
                }
            }
        } else {
            if (entry == null) {
                entry = new Entry();
                holder.fathom$setNameEntry(entry);
            }
            entry.text = entity.getName();
            entry.formatted = entry.text.asFormattedString();
            entry.key = key;
            entry.team = team;
            entry.prefix = prefix;
            entry.suffix = suffix;
            builds++;
        }
        lastText = entry.text;
        lastFormatted = entry.formatted;
        return entry.text;
    }

    /** Replaces {@code text.asFormattedString()} right after {@link #name}. */
    public static String formatted(Text text) {
        if (text != null && text == lastText) {
            return lastFormatted;
        }
        return text.asFormattedString();
    }

    private static boolean equal(String a, String b) {
        return a == b || (a != null && a.equals(b));
    }
}
