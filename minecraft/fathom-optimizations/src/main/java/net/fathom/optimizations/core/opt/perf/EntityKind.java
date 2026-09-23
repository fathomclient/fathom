package net.fathom.optimizations.core.opt.perf;

/**
 * The groups entity render distances are set by.
 *
 * <p>Deliberately not one universal distance. What a player needs to see at 60
 * blocks is another player or an arrow in flight, not a sheep; the table in
 * {@link EntityDistances} gives the PvP-relevant groups the generous numbers.
 *
 * <p>{@link #NEVER} is for things whose rendering is gameplay-critical or
 * reaches far beyond their own bounds: bosses (whose health bar and beams come
 * from the entity), lightning, and ender crystals. No preset limits them.
 */
public enum EntityKind {
    PLAYER("Players"),
    HOSTILE("Hostile mobs"),
    PASSIVE("Passive mobs"),
    ITEM("Dropped items"),
    ARMOR_STAND("Armor stands"),
    PROJECTILE("Projectiles, TNT and falling blocks"),
    MISC("Other entities"),
    NEVER("Never limited");

    public static final int COUNT = values().length;

    private final String label;

    EntityKind(String label) {
        this.label = label;
    }

    public String label() {
        return label;
    }
}
