package net.fathom.optimizations.core.opt.entity;

/**
 * Everything {@link EntityVisibilityPolicy} is allowed to know about an entity.
 *
 * <p>Deliberately narrow. A policy that could reach the live entity object would grow
 * special cases that only make sense in the game and could then never be tested outside
 * it. Everything here is a fact a Mixin can extract in a few field reads, and every one of
 * them exists because leaving it out would let us hide something a player needs to see.
 *
 * <p>Read once per decision. None of these may be expensive.
 */
public interface EntityFacts {

    /**
     * A stable identifier for the kind of entity — {@code "minecraft:armor_stand"}.
     *
     * <p>Used only for the boss and world-effect lists. Never for a "do not render X"
     * setting: that is a visual change and a different feature.
     */
    String typeId();

    /** Whether this is the player holding the mouse. */
    boolean isViewer();

    /** Whether the viewer is riding this, or it is riding the viewer. */
    boolean isAttachedToViewer();

    /** Whether this is any player, including other players. */
    boolean isPlayer();

    /**
     * Whether a nametag is currently drawn for this entity.
     *
     * <p>Nametags render through terrain in vanilla. Culling the entity would take the
     * nametag with it, which on a server where armour stands are holograms means deleting
     * text the player was reading.
     */
    boolean hasVisibleNameTag();

    /** Whether the entity is on fire, in a portal, or otherwise drawing its own effects. */
    boolean hasVisualEffects();

    /**
     * Whether rendering this entity has consequences beyond its own pixels.
     *
     * <p>Beacon beams, guardian lasers, end-portal frames and fishing lines are drawn
     * <em>by</em> an entity or tile entity but are visible far outside its bounding box.
     * Occlusion-testing the source and skipping the render deletes the beam.
     */
    boolean rendersBeyondItsBounds();

    /** Longest edge of the bounding box, in blocks. */
    double sizeBlocks();

    /** Distance from the camera, in blocks. */
    double distanceBlocks();

    /**
     * Whether the occlusion test result for this entity is current.
     *
     * <p>Occlusion data is computed asynchronously and is stale immediately after the
     * camera moves. False means "we do not know", and not knowing must never result in
     * hiding something.
     */
    boolean occlusionResultIsFresh();

    /** Whether the occlusion test believes this entity is fully behind opaque geometry. */
    boolean occludedByOpaqueGeometry();
}
