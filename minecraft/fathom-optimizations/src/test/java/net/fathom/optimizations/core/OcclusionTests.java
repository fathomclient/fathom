package net.fathom.optimizations.core;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Random;
import java.util.Set;

import net.fathom.optimizations.core.opt.entity.OcclusionTest;

/** Entity occlusion's sight lines, against block worlds built here. */
public final class OcclusionTests {

    private static int passed;
    private static final List<String> failures = new ArrayList<String>();

    public static void main(String[] args) {
        openAirHidesNothing();
        aSolidWallHidesTheBox();
        aWindowInTheWallShowsTheBox();
        aHoleOffTheCentreLineStillShowsTheBox();
        anEyeInsideABlockOrTheBoxHidesNothing();
        aBoxSunkInTheGroundBehindAWallIsHidden();
        sightLinesAgreeWithAFineMarch();
        hiddenIsNeverWrongAboutAVisibleBox();
        aWallSettlesInAFewLines();
        paddingCoversLimbsAndShadows();

        System.out.println("OcclusionTests: " + passed + " passed, " + failures.size() + " failed");
        for (String failure : failures) {
            System.out.println("  FAILED: " + failure);
        }
        if (!failures.isEmpty()) {
            System.exit(1);
        }
    }

    /** A world of listed opaque blocks. */
    private static final class Blocks implements OcclusionTest.Opacity {
        final Set<Long> opaque = new HashSet<Long>();

        Blocks put(int x, int y, int z) {
            opaque.add(Long.valueOf(key(x, y, z)));
            return this;
        }

        Blocks remove(int x, int y, int z) {
            opaque.remove(Long.valueOf(key(x, y, z)));
            return this;
        }

        /** A wall across x and y at {@code z}, from {@code -half} to {@code half - 1}. */
        Blocks wallZ(int z, int half, int bottom, int top) {
            for (int x = -half; x < half; x++) {
                for (int y = bottom; y < top; y++) {
                    put(x, y, z);
                }
            }
            return this;
        }

        @Override
        public boolean occludes(int x, int y, int z) {
            return opaque.contains(Long.valueOf(key(x, y, z)));
        }

        private static long key(int x, int y, int z) {
            return ((long) (x & 0x1FFFFF) << 42) | ((long) (y & 0x1FFFFF) << 21) | (z & 0x1FFFFF);
        }
    }

    // An entity-sized box 20 blocks ahead of an eye at the origin, grown by
    // the padding the game uses for a zombie.
    private static final double EYE_X = 0.5;
    private static final double EYE_Y = 1.62;
    private static final double EYE_Z = 0.5;
    private static final double[] BOX = padded(0.5, 0.0, 20.5, 0.6, 1.95);

    private static double[] padded(double x, double y, double z, double width, double height) {
        double side = OcclusionTest.padSide(width);
        double half = width / 2;
        return new double[] {x - half - side, y - OcclusionTest.PAD_DOWN, z - half - side,
            x + half + side, y + height + OcclusionTest.padUp(height), z + half + side};
    }

    private static boolean hidden(Blocks world) {
        return new OcclusionTest().hidden(EYE_X, EYE_Y, EYE_Z,
                BOX[0], BOX[1], BOX[2], BOX[3], BOX[4], BOX[5], world);
    }

    static void openAirHidesNothing() {
        check(!hidden(new Blocks()), "nothing in the way, nothing hidden");
        pass("open air hides nothing");
    }

    static void aSolidWallHidesTheBox() {
        check(hidden(new Blocks().wallZ(10, 30, -10, 20)), "a wide, tall wall at 10 hides a box at 20");
        check(hidden(new Blocks().wallZ(17, 30, -10, 20)), "and so does one right in front of the box");
        check(!hidden(new Blocks().wallZ(10, 30, -10, 2)), "a wall below eye level does not");
        pass("a solid wall hides the box");
    }

    static void aWindowInTheWallShowsTheBox() {
        Blocks world = new Blocks().wallZ(10, 30, -10, 20);
        world.remove(0, 1, 10).remove(1, 1, 10).remove(0, 2, 10).remove(1, 2, 10);
        check(!hidden(world), "a 2x2 window on the line of sight shows the box");
        pass("a window in the wall shows the box");
    }

    static void aHoleOffTheCentreLineStillShowsTheBox() {
        // One missing block, beside and below the line to the centre but
        // inside the box's outline as seen from the eye.
        Blocks world = new Blocks().wallZ(10, 30, -10, 20);
        world.remove(1, 0, 10);
        check(!hidden(world), "a single hole off the centre line shows part of the box");
        // The same hole far outside the box's outline does not.
        Blocks away = new Blocks().wallZ(10, 30, -10, 20);
        away.remove(8, 8, 10);
        check(hidden(away), "a hole well outside the outline shows nothing of it");
        pass("a hole off the centre line still shows the box");
    }

    static void anEyeInsideABlockOrTheBoxHidesNothing() {
        Blocks world = new Blocks().wallZ(10, 30, -10, 20).put(0, 1, 0);
        check(!hidden(world), "an eye inside an opaque block is never used to hide anything");
        OcclusionTest test = new OcclusionTest();
        check(!test.hidden(0.5, 1.0, 20.5, BOX[0], BOX[1], BOX[2], BOX[3], BOX[4], BOX[5],
                new Blocks().wallZ(10, 30, -10, 20)), "an eye inside the box sees it");
        pass("an eye inside a block or the box hides nothing");
    }

    static void aBoxSunkInTheGroundBehindAWallIsHidden() {
        Blocks world = new Blocks().wallZ(10, 30, -10, 20);
        for (int x = -30; x < 30; x++) {
            for (int z = 0; z < 40; z++) {
                world.put(x, -1, z);
            }
        }
        check(hidden(world), "ground under the box does not open a line around the wall");
        pass("a box sunk in the ground behind a wall is hidden");
    }

    /**
     * The two-ended block walk against a brute-force march in tiny steps, on
     * random worlds and random lines. They must agree on every line.
     */
    static void sightLinesAgreeWithAFineMarch() {
        Random random = new Random(0x0CC1L);
        OcclusionTest test = new OcclusionTest();
        int disagreements = 0;
        int blockedLines = 0;
        for (int world = 0; world < 40; world++) {
            // One world at a time: what the test remembers as opaque is about
            // the world it was asked about.
            test.resetBlockers();
            final boolean[] grid = new boolean[16 * 16 * 16];
            for (int i = 0; i < grid.length; i++) {
                grid[i] = random.nextDouble() < 0.08;
            }
            OcclusionTest.Opacity opacity = new OcclusionTest.Opacity() {
                @Override
                public boolean occludes(int x, int y, int z) {
                    return x >= 0 && y >= 0 && z >= 0 && x < 16 && y < 16 && z < 16
                            && grid[(x * 16 + y) * 16 + z];
                }
            };
            for (int line = 0; line < 50; line++) {
                double[] a = {1 + random.nextDouble() * 14, 1 + random.nextDouble() * 14, 1 + random.nextDouble() * 14};
                double[] b = {1 + random.nextDouble() * 14, 1 + random.nextDouble() * 14, 1 + random.nextDouble() * 14};
                boolean walked = test.clear(a[0], a[1], a[2], b[0], b[1], b[2], opacity);
                boolean marched = march(a, b, opacity);
                if (!marched) {
                    blockedLines++;
                }
                if (walked != marched) {
                    disagreements++;
                }
            }
        }
        check(blockedLines > 200, "the random worlds block a fair share of lines (" + blockedLines + ")");
        check(disagreements == 0, disagreements + " of 2000 lines disagree with the fine march");
        pass("sight lines agree with a fine march");
    }

    /**
     * The direction that matters: on random worlds, whenever the test says a
     * box is hidden, a dense brute-force grid of lines to its faces must find
     * none clear. It may give up on hidden boxes; it may never hide a seen one.
     */
    static void hiddenIsNeverWrongAboutAVisibleBox() {
        Random random = new Random(0x51DEL);
        OcclusionTest test = new OcclusionTest();
        OcclusionTest brute = new OcclusionTest();
        int hiddenCount = 0;
        int wrong = 0;
        for (int trial = 0; trial < 600; trial++) {
            final boolean[] grid = new boolean[24 * 24 * 24];
            double density = 0.15 + random.nextDouble() * 0.35;
            for (int i = 0; i < grid.length; i++) {
                grid[i] = random.nextDouble() < density;
            }
            OcclusionTest.Opacity opacity = new OcclusionTest.Opacity() {
                @Override
                public boolean occludes(int x, int y, int z) {
                    return x >= 0 && y >= 0 && z >= 0 && x < 24 && y < 24 && z < 24
                            && grid[(x * 24 + y) * 24 + z];
                }
            };
            double eyeX = 2 + random.nextDouble() * 4;
            double eyeY = 2 + random.nextDouble() * 20;
            double eyeZ = 2 + random.nextDouble() * 20;
            grid[((int) eyeX * 24 + (int) eyeY) * 24 + (int) eyeZ] = false;
            double minX = 14 + random.nextDouble() * 4;
            double minY = 2 + random.nextDouble() * 16;
            double minZ = 2 + random.nextDouble() * 16;
            double maxX = minX + 0.5 + random.nextDouble() * 3;
            double maxY = minY + 0.5 + random.nextDouble() * 4;
            double maxZ = minZ + 0.5 + random.nextDouble() * 3;
            test.resetBlockers();
            if (!test.hidden(eyeX, eyeY, eyeZ, minX, minY, minZ, maxX, maxY, maxZ, opacity)) {
                continue;
            }
            hiddenCount++;
            brute.resetBlockers();
            int n = 40;
            boolean seen = false;
            for (int i = 0; i <= n && !seen; i++) {
                for (int j = 0; j <= n && !seen; j++) {
                    double y = minY + (maxY - minY) * i / n;
                    double z = minZ + (maxZ - minZ) * j / n;
                    double x = minX + (maxX - minX) * i / n;
                    double yy = minY + (maxY - minY) * j / n;
                    double zz = minZ + (maxZ - minZ) * j / n;
                    // The face towards the eye on x, and the y and z faces the eye can see.
                    seen = brute.clear(eyeX, eyeY, eyeZ, minX, y, z, opacity)
                            || (eyeY < minY && brute.clear(eyeX, eyeY, eyeZ, x, minY, zz, opacity))
                            || (eyeY > maxY && brute.clear(eyeX, eyeY, eyeZ, x, maxY, zz, opacity))
                            || (eyeZ < minZ && brute.clear(eyeX, eyeY, eyeZ, x, yy, minZ, opacity))
                            || (eyeZ > maxZ && brute.clear(eyeX, eyeY, eyeZ, x, yy, maxZ, opacity));
                }
            }
            if (seen) {
                wrong++;
            }
        }
        check(hiddenCount > 30, "the random worlds hide a fair number of boxes (" + hiddenCount + ")");
        check(wrong == 0, wrong + " boxes called hidden had a clear line to them");
        pass("hidden is never wrong about a visible box (" + hiddenCount + " hidden checked)");
    }

    static void aWallSettlesInAFewLines() {
        OcclusionTest test = new OcclusionTest();
        check(test.hidden(EYE_X, EYE_Y, EYE_Z, BOX[0], BOX[1], BOX[2], BOX[3], BOX[4], BOX[5],
                new Blocks().wallZ(10, 30, -10, 20)), "hidden behind the wall");
        check(test.lines() <= 8, "a plain wall settles in a few lines, took " + test.lines());
        pass("a wall settles in a few lines");
    }

    /** Every block the segment passes through, found by stepping 1/20000 of it at a time. */
    private static boolean march(double[] a, double[] b, OcclusionTest.Opacity opacity) {
        int steps = 20000;
        for (int i = 0; i <= steps; i++) {
            double t = (double) i / steps;
            double x = a[0] + (b[0] - a[0]) * t;
            double y = a[1] + (b[1] - a[1]) * t;
            double z = a[2] + (b[2] - a[2]) * t;
            if (opacity.occludes((int) Math.floor(x), (int) Math.floor(y), (int) Math.floor(z))) {
                return false;
            }
        }
        return true;
    }

    static void paddingCoversLimbsAndShadows() {
        // A zombie's arms reach about 0.75 blocks from its centre and a held
        // sword half a block more; its hitbox is 0.3 from centre to side.
        check(0.3 + OcclusionTest.padSide(0.6) >= 1.25, "side padding covers an outstretched arm and sword");
        check(OcclusionTest.PAD_DOWN >= 1.0, "padding below covers the shadow's blocks");
        check(OcclusionTest.padUp(1.95) >= 0.5, "padding above covers a helmet");
        pass("padding covers limbs and shadows");
    }

    private static void check(boolean condition, String message) {
        if (!condition) {
            failures.add(message);
            throw new AssertionError(message);
        }
    }

    private static void pass(String name) {
        passed++;
        System.out.println("  ok   " + name);
    }
}
