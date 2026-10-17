package net.fathom.optimizations.core.opt.entity;

import java.util.Arrays;
import java.util.Collections;
import java.util.HashSet;
import java.util.LinkedHashSet;
import java.util.Set;

/**
 * Whether an entity may be skipped, given a conservative reading of what the player needs
 * to see.
 *
 * <h2>What vanilla does</h2>
 *
 * <p>1.8.9 culls entities by view frustum and render distance and by nothing else. An
 * armour stand sealed inside a wall a hundred blocks away, facing the camera, goes through
 * the full render path — model setup, texture bind, matrix push, immediate-mode geometry.
 * On a lobby holding hundreds of them this is a large fraction of the frame spent drawing
 * things no pixel of which reaches the screen.
 *
 * <h2>What this class is, and what it is not</h2>
 *
 * <p>It is the <strong>refusal half</strong> of entity culling: the rules that say an
 * entity must be drawn regardless of what any occlusion test concluded. It does not
 * perform an occlusion test, does not know how one would be implemented, and cannot on its
 * own make the game faster.
 *
 * <p>That split is on purpose. The dangerous part of entity culling is not the geometry,
 * it is the exception list, and the exception list is the part that can be written down,
 * argued about and tested without a GPU. Shipping it first means the occlusion test, when
 * it is written, arrives with its safety rails already checked.
 *
 * <h2>The rules, and why each is here</h2>
 *
 * <p>Every one of these corresponds to a way naive culling is known to break. They are
 * ordered cheapest first, and the method returns at the first that fires.
 *
 * <ol>
 *   <li><strong>The occlusion result is stale or absent.</strong> Not knowing is not a
 *       reason to hide something. This is the rule that makes every other failure mode
 *       degrade into "we drew too much" rather than "the player could not see an enemy".
 *   <li><strong>The test did not say it was occluded.</strong> Stated explicitly rather
 *       than left as a fall-through, because it is the whole contract.
 *   <li><strong>The viewer, and anything they are riding or being ridden by.</strong>
 *   <li><strong>Players.</strong> Always. Team colours, nametags, and the simple fact that
 *       a player behind a wall is about to not be behind a wall. Hiding a player for a
 *       frame in a PvP client is a correctness failure that would be reported as cheating
 *       by one side and as a bug by the other.
 *   <li><strong>Anything with a visible nametag.</strong> Nametags draw through walls in
 *       vanilla; the occlusion test is about the body, and the tag is the part that
 *       carries information. On servers that use armour stands as holograms this rule is
 *       the difference between an optimization and a broken lobby.
 *   <li><strong>Bosses and world-effect sources.</strong> The ender dragon and the wither
 *       have render logic reaching well past their hitboxes.
 *   <li><strong>Anything whose rendering reaches beyond its own bounds.</strong> Beacon
 *       beams, guardian lasers, fishing lines, end-portal effects.
 *   <li><strong>Anything drawing its own effects</strong> — on fire, in a portal.
 *   <li><strong>Anything large.</strong> A big bounding box is more likely to be partly
 *       visible than a test on a single representative point can establish.
 *   <li><strong>Anything close.</strong> Near the camera the cost of being wrong is
 *       highest and the saving is lowest.
 * </ol>
 *
 * <h2>Hooks</h2>
 *
 * <p>Other software gets a vote, and the vote is one-directional: a {@link Hook} may
 * insist an entity is rendered and may never ask for one to be hidden. A shader pack
 * rendering shadows needs geometry that is not in the camera's view at all, and OptiFine
 * and user mods have their own reasons we cannot anticipate. Letting them add exceptions
 * is safe; letting them remove ours is not. A hook that throws is treated as a vote to
 * render.
 */
public final class EntityVisibilityPolicy {

    /** The outcome, with the reason attached. */
    public static final class Verdict {
        private final boolean mustRender;
        private final String reason;

        Verdict(boolean mustRender, String reason) {
            this.mustRender = mustRender;
            this.reason = reason;
        }

        /** True if the entity has to be drawn. */
        public boolean mustRender() {
            return mustRender;
        }

        /** True only if every rule agreed it is safe to skip. */
        public boolean mayCull() {
            return !mustRender;
        }

        /** Why. Never empty, including when the answer is "may cull". */
        public String reason() {
            return reason;
        }

        @Override
        public String toString() {
            return (mustRender ? "render: " : "cull: ") + reason;
        }
    }

    /** Another mod's chance to insist. */
    public interface Hook {
        /**
         * Return true to force this entity to be rendered.
         *
         * <p>There is no way to force the opposite. That asymmetry is the safety property.
         */
        boolean mustRender(EntityFacts entity);

        /** For logs and for the conflict report. */
        String name();
    }

    /**
     * Entities that are never culled whatever else is true.
     *
     * <p>Sourced from the exception lists the established culling mods publish. Those
     * lists are the research finding: naive occlusion culling breaks specific things, and
     * which things is a fact about Minecraft rather than anyone's implementation.
     */
    private static final Set<String> NEVER_CULL_TYPES = Collections.unmodifiableSet(
            new HashSet<String>(Arrays.asList(
                    "minecraft:ender_dragon",
                    "minecraft:wither",
                    "minecraft:beacon",
                    "minecraft:end_gateway",
                    "minecraft:end_portal",
                    "minecraft:guardian",
                    "minecraft:elder_guardian",
                    "minecraft:fishing_bobber")));

    /**
     * Below this distance, in blocks, nothing is culled.
     *
     * <p>Sixteen — one chunk. Close entities are cheap in aggregate because there are few
     * of them, and they are exactly the ones a player is about to interact with.
     */
    public static final double MINIMUM_CULL_DISTANCE_BLOCKS = 16.0;

    /**
     * Above this size, in blocks, nothing is culled.
     *
     * <p>Four. Larger than any common mob and smaller than anything with a genuinely
     * unusual render, so it catches the awkward cases without exempting half the lobby.
     */
    public static final double MAXIMUM_CULL_SIZE_BLOCKS = 4.0;

    private final Set<Hook> hooks = new LinkedHashSet<Hook>();

    private long rendered;
    private long culled;
    private long hookOverrides;
    private long hookFailures;

    /** Register another mod's veto. Registering the same hook twice does nothing. */
    public void addHook(Hook hook) {
        if (hook == null) {
            throw new IllegalArgumentException("hook");
        }
        hooks.add(hook);
    }

    public void removeHook(Hook hook) {
        hooks.remove(hook);
    }

    public int hookCount() {
        return hooks.size();
    }

    /** Decide about one entity. */
    public Verdict decide(EntityFacts entity) {
        if (entity == null) {
            throw new IllegalArgumentException("entity");
        }
        Verdict verdict = evaluate(entity);
        if (verdict.mustRender()) {
            rendered++;
        } else {
            culled++;
        }
        return verdict;
    }

    private Verdict evaluate(EntityFacts entity) {
        if (!entity.occlusionResultIsFresh()) {
            return render("no current occlusion result; not knowing is not a reason to hide"
                    + " something");
        }
        if (!entity.occludedByOpaqueGeometry()) {
            return render("not occluded");
        }
        if (entity.isViewer() || entity.isAttachedToViewer()) {
            return render("the viewer, or attached to them");
        }
        if (entity.isPlayer()) {
            return render("a player; hiding one for a frame is a correctness failure in a PvP"
                    + " client whatever it saves");
        }
        if (entity.hasVisibleNameTag()) {
            return render("has a visible nametag, which vanilla draws through terrain");
        }
        if (NEVER_CULL_TYPES.contains(entity.typeId())) {
            return render("type " + entity.typeId() + " is on the never-cull list");
        }
        if (entity.rendersBeyondItsBounds()) {
            return render("renders beyond its own bounds; culling it would delete the beam or"
                    + " line, not just the entity");
        }
        if (entity.hasVisualEffects()) {
            return render("drawing its own effects");
        }
        if (entity.sizeBlocks() > MAXIMUM_CULL_SIZE_BLOCKS) {
            return render("larger than " + MAXIMUM_CULL_SIZE_BLOCKS + " blocks; a single-point"
                    + " occlusion test does not speak for a box that size");
        }
        if (entity.distanceBlocks() < MINIMUM_CULL_DISTANCE_BLOCKS) {
            return render("within " + MINIMUM_CULL_DISTANCE_BLOCKS + " blocks");
        }
        for (Hook hook : hooks) {
            boolean insists;
            try {
                insists = hook.mustRender(entity);
            } catch (Throwable failure) {
                // A hook that throws is a hook we cannot understand, and the safe reading
                // of an answer we did not get is "render it".
                hookFailures++;
                return render("hook " + hook.name() + " failed (" + failure + "); treating that"
                        + " as a vote to render");
            }
            if (insists) {
                hookOverrides++;
                return render("hook " + hook.name() + " requires it");
            }
        }
        return new Verdict(false, "occluded, distant, small, unnamed, and nothing objected");
    }

    private static Verdict render(String reason) {
        return new Verdict(true, reason);
    }

    public long renderedCount() {
        return rendered;
    }

    public long culledCount() {
        return culled;
    }

    public long hookOverrideCount() {
        return hookOverrides;
    }

    public long hookFailureCount() {
        return hookFailures;
    }

    /**
     * Fraction of decisions that resulted in a skip, or {@code NaN} before any.
     *
     * <p>The number a benchmark reports alongside frame time. A culling rate that moves
     * without a frame time moving means the work being skipped was not the expensive part.
     */
    public double cullRate() {
        long total = rendered + culled;
        return total == 0L ? Double.NaN : (double) culled / (double) total;
    }

    public void resetCounters() {
        rendered = 0L;
        culled = 0L;
        hookOverrides = 0L;
        hookFailures = 0L;
    }
}
