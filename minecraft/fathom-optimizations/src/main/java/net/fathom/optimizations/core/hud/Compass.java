package net.fathom.optimizations.core.hud;

/**
 * The Direction HUD's compass strip, laid out without Minecraft.
 *
 * <p>A strip a fixed number of degrees wide, centred on where the player is
 * facing, with a mark every fifteen degrees: the four cardinal letters, the
 * four between them when asked for, and short ticks for the rest.
 *
 * <h2>Headings</h2>
 *
 * <p>Minecraft's yaw is zero facing south and grows clockwise; a compass is
 * zero facing north. {@link #heading} converts, so 0 is north, 90 east, 180
 * south and 270 west — which is what the letters on the strip and the number
 * under it both say.
 */
public final class Compass {

    /** Degrees between marks. */
    public static final int STEP = 15;

    /** How many marks a strip can hold: every fifteen degrees of a full turn. */
    public static final int MAX_MARKS = 360 / STEP + 1;

    private static final String[] CARDINAL = {"N", "E", "S", "W"};
    private static final String[] BETWEEN = {"NE", "SE", "SW", "NW"};

    private Compass() {}

    /** Minecraft yaw to a compass heading in [0, 360). */
    public static double heading(float yaw) {
        double heading = (yaw + 180.0) % 360.0;
        return heading < 0 ? heading + 360.0 : heading;
    }

    /** The eight-way name of a heading. */
    public static String name(double heading) {
        int octant = (int) Math.floor(((heading % 360.0) + 360.0) % 360.0 / 45.0 + 0.5) % 8;
        return octant % 2 == 0 ? CARDINAL[octant / 2] : BETWEEN[octant / 2];
    }

    /**
     * Lay the marks out.
     *
     * @param heading where the player faces, from {@link #heading}
     * @param span how many degrees the strip shows, centre to both ends together
     * @param width the strip's width in pixels
     * @param between whether NE, SE, SW and NW are lettered
     * @param xs receives each mark's x, from the strip's left edge
     * @param labels receives each mark's letters, or null for a plain tick
     * @return how many marks were written
     */
    public static int layout(double heading, int span, int width, boolean between, float[] xs, String[] labels) {
        double perDegree = width / (double) Math.max(1, span);
        double half = span / 2.0;
        int count = 0;
        for (int mark = 0; mark < 360 && count < xs.length; mark += STEP) {
            double delta = ((mark - heading) % 360.0 + 540.0) % 360.0 - 180.0;
            if (delta < -half || delta > half) {
                continue;
            }
            xs[count] = (float) (width / 2.0 + delta * perDegree);
            if (mark % 90 == 0) {
                labels[count] = CARDINAL[mark / 90];
            } else if (between && mark % 45 == 0) {
                labels[count] = BETWEEN[mark / 90];
            } else {
                labels[count] = null;
            }
            count++;
        }
        return count;
    }
}
