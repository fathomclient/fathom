package net.fathom.optimizations.fabric.opt;

import net.minecraft.util.math.Direction;

import net.fathom.optimizations.fabric.Runtime;

/**
 * One shared {@code Direction[]} for vanilla loops that only read it.
 *
 * <p>{@code Direction.values()} clones the enum's array on every call. Vanilla
 * calls it at the top of for-each loops in per-block light queries and in the
 * per-frame chunk visibility search; in a sampled benchmark run those clones were
 * 22% of everything the render thread allocated.
 *
 * <p>Only redirected at call sites whose bytecode was checked to store the array
 * in a local and read it ({@code arraylength}/{@code aaload}) — never written,
 * never passed on. The shared copy is never exposed anywhere else, so nothing can
 * modify it. Part of {@code cpu.allocation.hot-path-reduction}; with the switch
 * off the vanilla call runs.
 */
public final class DirectionArrays {

    private static final Direction[] ALL = Direction.values();

    private DirectionArrays() {}

    public static Direction[] values() {
        if (Runtime.hotPathAllocation) {
            net.fathom.optimizations.core.bench.view.ViewProbe.engaged(net.fathom.optimizations.core.bench.view.ViewCounter.ENGAGED_DIRECTIONS);
            return ALL;
        }
        return Direction.values();
    }
}
