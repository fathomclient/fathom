package net.fathom.optimizations.core.opt.entity;

/**
 * Whether a box is hidden from an eye by opaque full blocks --
 * {@code render.entity.occlusion-culling}.
 *
 * <h2>The test</h2>
 *
 * <p>Each face of the box that faces the eye is a rectangle. A rectangle is
 * hidden when the sight lines to its four corners all meet opaque blocks and
 * the box of blocks spanning those four is solid: the points whose line from
 * the eye passes through a convex solid form a convex set, so a rectangle
 * whose corners are in it is wholly in it. A rectangle that does not settle
 * that way is split in four, down to {@link #MAX_DEPTH} halvings, and one
 * that is still unsettled there counts as visible.
 *
 * <h2>Exact in the direction that matters</h2>
 *
 * <p>Every "hidden" is proved, never sampled: no line from the eye reaches
 * the box without passing through an opaque block. What the test can get
 * wrong is the other way -- giving up on a box that was hidden, behind
 * jagged terrain -- and that only costs drawing it. The box handed in is the
 * entity's bounds grown by {@link #padSide}, {@link #padUp} and
 * {@link #PAD_DOWN}, which reach past every limb, held item, piece of armour
 * and shadow, so a hidden box means a hidden entity.
 *
 * <h2>Cost</h2>
 *
 * <p>Behind a wall, four lines a face: the wall blocks all four corners with
 * a few blocks that span a solid box. Each line is first tried against the
 * blocks recent lines were stopped by, then walked block by block from both
 * ends at once. A visible entity usually stops at its first clear corner.
 *
 * <p>Pure: the world is an {@link Opacity}, so the geometry is tested without
 * a game. Not thread-safe; one instance per render thread.
 */
public final class OcclusionTest {

    /** Whether the block at a position hides what is behind it. */
    public interface Opacity {
        boolean occludes(int x, int y, int z);
    }

    /** Below the hitbox: the shadow is drawn on the blocks under the entity. */
    public static final double PAD_DOWN = 1.0;

    /** Points are pulled this far towards the eye, off block boundaries. */
    private static final double NUDGE = 1.0E-4;

    /**
     * Beside the hitbox, for a hitbox {@code width} wide: arms, held items, a
     * horse's head. A zombie's outstretched arm and sword reach 1.25 blocks
     * from its centre; this gives it 1.25.
     */
    public static double padSide(double width) {
        return 0.8 + 0.25 * width;
    }

    /** Above the hitbox, for one {@code height} tall: heads, helmets, a slime's stretch. */
    public static double padUp(double height) {
        return 0.25 + 0.15 * height;
    }

    private final Walker forward = new Walker();
    private final Walker backward = new Walker();

    // Blocks this frame's walks found opaque, most recent first by slot.
    // From one eye, the lines to one entity cross a wall through a handful
    // of blocks, and its neighbours' lines through the same ones; a line is
    // first tried against these, which is a few multiplications each, and
    // walked only if none of them stops it. Exact: each is a block that is
    // opaque this frame, and a line that passes through one is blocked.
    private static final int BLOCKERS = 16;
    private final int[] blockerX = new int[BLOCKERS];
    private final int[] blockerY = new int[BLOCKERS];
    private final int[] blockerZ = new int[BLOCKERS];
    private int blockerCount;
    private int blockerNext;

    /** The block that stopped the last line {@link #clear} found blocked. */
    private int hitX;
    private int hitY;
    private int hitZ;

    private long lines;
    private long blocks;

    /**
     * True only if the box is hidden from the eye. An eye inside the box, or
     * inside an opaque block, is never hidden from anything: the answer
     * there is "draw it".
     */
    public boolean hidden(double eyeX, double eyeY, double eyeZ,
            double minX, double minY, double minZ, double maxX, double maxY, double maxZ,
            Opacity opacity) {
        if (eyeX >= minX && eyeX <= maxX && eyeY >= minY && eyeY <= maxY && eyeZ >= minZ && eyeZ <= maxZ) {
            return false;
        }
        if (opacity.occludes(floorOf(eyeX), floorOf(eyeY), floorOf(eyeZ))) {
            return false;
        }
        this.eyeX = eyeX;
        this.eyeY = eyeY;
        this.eyeZ = eyeZ;
        this.opacity = opacity;
        try {
            // The faces the eye can see: on each axis, at most one. Together
            // they cover everything of the box the eye could see.
            if (eyeX < minX && !face(0, minX, minY, maxY, minZ, maxZ)) {
                return false;
            }
            if (eyeX > maxX && !face(0, maxX, minY, maxY, minZ, maxZ)) {
                return false;
            }
            if (eyeY < minY && !face(1, minY, minX, maxX, minZ, maxZ)) {
                return false;
            }
            if (eyeY > maxY && !face(1, maxY, minX, maxX, minZ, maxZ)) {
                return false;
            }
            if (eyeZ < minZ && !face(2, minZ, minX, maxX, minY, maxY)) {
                return false;
            }
            if (eyeZ > maxZ && !face(2, maxZ, minX, maxX, minY, maxY)) {
                return false;
            }
            return true;
        } finally {
            this.opacity = null;
        }
    }

    // -- one face -----------------------------------------------------------
    //
    // A face is a rectangle; it is hidden if every rectangle it is split into
    // is. A rectangle is hidden when the lines to its four corners are all
    // blocked and the box spanning the four blocking blocks is solid: the
    // points of the face whose line from the eye passes through a convex
    // solid form a convex set, and a convex set holding a rectangle's corners
    // holds the rectangle. Exact, not sampled. A rectangle that does not
    // settle is split in four, down to MAX_DEPTH, and one that still has not
    // settled there is counted as visible: a test that runs out of patience
    // draws the entity.

    /** Halvings of a face, at most: 2^3 = 8 cells along each side, so giving up costs 81 lines at most. */
    static final int MAX_DEPTH = 3;
    private static final int GRID = 1 << MAX_DEPTH;
    private static final int POINTS = GRID + 1;
    /** The biggest box of blocks checked for solidity in one go. */
    private static final int MAX_SPAN_BLOCKS = 64;

    private double eyeX;
    private double eyeY;
    private double eyeZ;
    private Opacity opacity;

    private int axis;
    private double plane;
    private double minA;
    private double spanA;
    private double minB;
    private double spanB;

    // Per grid point of the current face: the face it was cast for (so
    // nothing is cleared between faces; negative when the line was clear),
    // and what blocked it.
    private final int[] pointStamp = new int[POINTS * POINTS];
    private final int[] pointX = new int[POINTS * POINTS];
    private final int[] pointY = new int[POINTS * POINTS];
    private final int[] pointZ = new int[POINTS * POINTS];
    /** The face a point's blocker was last made the one nearest the eye for. */
    private final int[] pointNear = new int[POINTS * POINTS];
    private int faceStamp;

    private boolean face(int axis, double plane, double minA, double maxA, double minB, double maxB) {
        this.axis = axis;
        this.plane = plane;
        this.minA = minA;
        this.spanA = maxA - minA;
        this.minB = minB;
        this.spanB = maxB - minB;
        if (++faceStamp == Integer.MAX_VALUE) {
            faceStamp = 1;
            java.util.Arrays.fill(pointStamp, 0);
            java.util.Arrays.fill(pointNear, 0);
        }
        return cell(0, 0, GRID, GRID);
    }

    private boolean cell(int a0, int b0, int a1, int b1) {
        if (!blocked(a0, b0) || !blocked(a1, b0) || !blocked(a0, b1) || !blocked(a1, b1)) {
            return false;
        }
        if (solidSpan(a0, b0, a1, b1)) {
            return true;
        }
        // A line can be stopped near either end, and the walk reports
        // whichever end it met first: the ground under the entity for one
        // corner, the wall for the next, and no solid box spans those. The
        // block nearest the eye on each line is the one that matters for
        // walls; find those and try again before splitting.
        if (nearest(a0, b0) | nearest(a1, b0) | nearest(a0, b1) | nearest(a1, b1)
                && solidSpan(a0, b0, a1, b1)) {
            return true;
        }
        if (a1 - a0 <= 1) {
            return false;
        }
        int am = (a0 + a1) >>> 1;
        int bm = (b0 + b1) >>> 1;
        return cell(a0, b0, am, bm) && cell(am, b0, a1, bm) && cell(a0, bm, am, b1) && cell(am, bm, a1, b1);
    }

    /** Whether the line to grid point (a, b) is blocked; remembers by what. */
    private boolean blocked(int a, int b) {
        int index = a * POINTS + b;
        int stamp = pointStamp[index];
        if (stamp == faceStamp) {
            return true;
        }
        if (stamp == -faceStamp) {
            return false;
        }
        double u = minA + spanA * a / GRID;
        double v = minB + spanB * b / GRID;
        double x;
        double y;
        double z;
        if (axis == 0) {
            x = plane;
            y = u;
            z = v;
        } else if (axis == 1) {
            x = u;
            y = plane;
            z = v;
        } else {
            x = u;
            y = v;
            z = plane;
        }
        if (clear(eyeX, eyeY, eyeZ, x, y, z, opacity)) {
            pointStamp[index] = -faceStamp;
            return false;
        }
        pointStamp[index] = faceStamp;
        pointX[index] = hitX;
        pointY[index] = hitY;
        pointZ[index] = hitZ;
        return true;
    }

    /**
     * Replace a grid point's blocker with the first opaque block on its line
     * walking from the eye. True if that changed it.
     */
    private boolean nearest(int a, int b) {
        int index = a * POINTS + b;
        if (pointNear[index] == faceStamp) {
            return false;
        }
        pointNear[index] = faceStamp;
        double u = minA + spanA * a / GRID;
        double v = minB + spanB * b / GRID;
        double x = axis == 0 ? plane : u;
        double y = axis == 1 ? plane : axis == 0 ? u : v;
        double z = axis == 2 ? plane : v;
        forward.start(eyeX, eyeY, eyeZ, x, y, z);
        int total = 1 + Math.abs(floorOf(x) - floorOf(eyeX)) + Math.abs(floorOf(y) - floorOf(eyeY))
                + Math.abs(floorOf(z) - floorOf(eyeZ));
        for (int seen = 0; seen < total; seen++) {
            blocks++;
            if (opacity.occludes(forward.x, forward.y, forward.z)) {
                boolean changed = pointX[index] != forward.x || pointY[index] != forward.y
                        || pointZ[index] != forward.z;
                pointX[index] = forward.x;
                pointY[index] = forward.y;
                pointZ[index] = forward.z;
                return changed;
            }
            forward.step();
        }
        return false;
    }

    /**
     * Whether every block in the box spanning the four corners' blockers is
     * opaque. The box must also lie wholly ahead of the eye along the face's
     * axis, or the projection argument above does not hold.
     */
    private boolean solidSpan(int a0, int b0, int a1, int b1) {
        int i0 = a0 * POINTS + b0;
        int i1 = a1 * POINTS + b0;
        int i2 = a0 * POINTS + b1;
        int i3 = a1 * POINTS + b1;
        int loX = Math.min(Math.min(pointX[i0], pointX[i1]), Math.min(pointX[i2], pointX[i3]));
        int hiX = Math.max(Math.max(pointX[i0], pointX[i1]), Math.max(pointX[i2], pointX[i3]));
        int loY = Math.min(Math.min(pointY[i0], pointY[i1]), Math.min(pointY[i2], pointY[i3]));
        int hiY = Math.max(Math.max(pointY[i0], pointY[i1]), Math.max(pointY[i2], pointY[i3]));
        int loZ = Math.min(Math.min(pointZ[i0], pointZ[i1]), Math.min(pointZ[i2], pointZ[i3]));
        int hiZ = Math.max(Math.max(pointZ[i0], pointZ[i1]), Math.max(pointZ[i2], pointZ[i3]));
        int volume = (hiX - loX + 1) * (hiY - loY + 1) * (hiZ - loZ + 1);
        if (volume > MAX_SPAN_BLOCKS) {
            return false;
        }
        if (!ahead(loX, hiX, loY, hiY, loZ, hiZ)) {
            return false;
        }
        for (int x = loX; x <= hiX; x++) {
            for (int y = loY; y <= hiY; y++) {
                for (int z = loZ; z <= hiZ; z++) {
                    if (!opacity.occludes(x, y, z)) {
                        return false;
                    }
                }
            }
        }
        return true;
    }

    /** Whether blocks lo..hi lie strictly beyond the eye, towards the face, on the face's axis. */
    private boolean ahead(int loX, int hiX, int loY, int hiY, int loZ, int hiZ) {
        if (axis == 0) {
            return plane > eyeX ? loX > eyeX : hiX + 1 < eyeX;
        }
        if (axis == 1) {
            return plane > eyeY ? loY > eyeY : hiY + 1 < eyeY;
        }
        return plane > eyeZ ? loZ > eyeZ : hiZ + 1 < eyeZ;
    }

    /** Sight lines walked since the last {@link #resetCounters}. */
    public long lines() {
        return lines;
    }

    /** Blocks looked at since the last {@link #resetCounters}. */
    public long blocks() {
        return blocks;
    }

    public void resetCounters() {
        lines = 0L;
        blocks = 0L;
    }

    /**
     * Forget the blocks remembered as opaque. Call whenever the world the
     * {@link Opacity} describes may have changed -- at least once a frame.
     */
    public void resetBlockers() {
        blockerCount = 0;
        blockerNext = 0;
    }

    /**
     * Whether the segment from the eye to a point crosses no opaque block.
     *
     * <p>The blocks a segment passes through are the same set walked from
     * either end, so the two walkers take turns and the line is clear once
     * they have looked at every block between them.
     */
    public boolean clear(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
            Opacity opacity) {
        lines++;
        // Pull the far end towards the eye, so a point lying exactly on a
        // block face belongs to the block on the eye's side of it.
        double dx = fromX - toX;
        double dy = fromY - toY;
        double dz = fromZ - toZ;
        double length = Math.sqrt(dx * dx + dy * dy + dz * dz);
        if (length > NUDGE) {
            double scale = NUDGE / length;
            toX += dx * scale;
            toY += dy * scale;
            toZ += dz * scale;
        }
        for (int i = 0; i < blockerCount; i++) {
            if (crosses(fromX, fromY, fromZ, toX, toY, toZ, blockerX[i], blockerY[i], blockerZ[i])) {
                hitX = blockerX[i];
                hitY = blockerY[i];
                hitZ = blockerZ[i];
                return false;
            }
        }
        int total = 1 + Math.abs(floorOf(toX) - floorOf(fromX)) + Math.abs(floorOf(toY) - floorOf(fromY))
                + Math.abs(floorOf(toZ) - floorOf(fromZ));
        forward.start(fromX, fromY, fromZ, toX, toY, toZ);
        backward.start(toX, toY, toZ, fromX, fromY, fromZ);
        int seen = 0;
        while (true) {
            blocks++;
            if (opacity.occludes(forward.x, forward.y, forward.z)) {
                remember(forward.x, forward.y, forward.z);
                return false;
            }
            if (++seen >= total) {
                return true;
            }
            forward.step();
            blocks++;
            if (opacity.occludes(backward.x, backward.y, backward.z)) {
                remember(backward.x, backward.y, backward.z);
                return false;
            }
            if (++seen >= total) {
                return true;
            }
            backward.step();
        }
    }

    private void remember(int x, int y, int z) {
        hitX = x;
        hitY = y;
        hitZ = z;
        blockerX[blockerNext] = x;
        blockerY[blockerNext] = y;
        blockerZ[blockerNext] = z;
        blockerNext = (blockerNext + 1) % BLOCKERS;
        if (blockerCount < BLOCKERS) {
            blockerCount++;
        }
    }

    /**
     * Whether the segment passes through the inside of the block at
     * {@code (x, y, z)}. Strictly inside: a line that only grazes an edge or
     * a face is not counted as blocked by it.
     */
    static boolean crosses(double fromX, double fromY, double fromZ, double toX, double toY, double toZ,
            int x, int y, int z) {
        // Slab test, one axis at a time, narrowing [enter, leave] along the
        // segment; no arrays, it runs per line.
        double enter = 0.0;
        double leave = 1.0;
        double d = toX - fromX;
        double lo = x - fromX;
        if (d == 0.0) {
            if (lo >= 0.0 || lo + 1.0 <= 0.0) {
                return false;
            }
        } else {
            double t0 = lo / d;
            double t1 = (lo + 1.0) / d;
            enter = Math.max(enter, Math.min(t0, t1));
            leave = Math.min(leave, Math.max(t0, t1));
            if (leave - enter <= 1.0E-9) {
                return false;
            }
        }
        d = toY - fromY;
        lo = y - fromY;
        if (d == 0.0) {
            if (lo >= 0.0 || lo + 1.0 <= 0.0) {
                return false;
            }
        } else {
            double t0 = lo / d;
            double t1 = (lo + 1.0) / d;
            enter = Math.max(enter, Math.min(t0, t1));
            leave = Math.min(leave, Math.max(t0, t1));
            if (leave - enter <= 1.0E-9) {
                return false;
            }
        }
        d = toZ - fromZ;
        lo = z - fromZ;
        if (d == 0.0) {
            return lo < 0.0 && lo + 1.0 > 0.0;
        }
        double t0 = lo / d;
        double t1 = (lo + 1.0) / d;
        enter = Math.max(enter, Math.min(t0, t1));
        leave = Math.min(leave, Math.max(t0, t1));
        return leave - enter > 1.0E-9;
    }

    public static int floorOf(double value) {
        int truncated = (int) value;
        return value < truncated ? truncated - 1 : truncated;
    }

    /** One end of a block walk (Amanatides and Woo). */
    private static final class Walker {
        int x;
        int y;
        int z;
        private int stepX;
        private int stepY;
        private int stepZ;
        private double nextX;
        private double nextY;
        private double nextZ;
        private double deltaX;
        private double deltaY;
        private double deltaZ;

        void start(double fromX, double fromY, double fromZ, double toX, double toY, double toZ) {
            x = floorOf(fromX);
            y = floorOf(fromY);
            z = floorOf(fromZ);
            double dx = toX - fromX;
            double dy = toY - fromY;
            double dz = toZ - fromZ;
            stepX = dx > 0 ? 1 : dx < 0 ? -1 : 0;
            stepY = dy > 0 ? 1 : dy < 0 ? -1 : 0;
            stepZ = dz > 0 ? 1 : dz < 0 ? -1 : 0;
            deltaX = stepX == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dx);
            deltaY = stepY == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dy);
            deltaZ = stepZ == 0 ? Double.POSITIVE_INFINITY : Math.abs(1.0 / dz);
            nextX = stepX == 0 ? Double.POSITIVE_INFINITY
                    : (stepX > 0 ? x + 1 - fromX : fromX - x) * deltaX;
            nextY = stepY == 0 ? Double.POSITIVE_INFINITY
                    : (stepY > 0 ? y + 1 - fromY : fromY - y) * deltaY;
            nextZ = stepZ == 0 ? Double.POSITIVE_INFINITY
                    : (stepZ > 0 ? z + 1 - fromZ : fromZ - z) * deltaZ;
        }

        void step() {
            if (nextX <= nextY && nextX <= nextZ) {
                x += stepX;
                nextX += deltaX;
            } else if (nextY <= nextZ) {
                y += stepY;
                nextY += deltaY;
            } else {
                z += stepZ;
                nextZ += deltaZ;
            }
        }
    }
}
