package net.fathom.optimizations.fabric.hud;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.fabric.input.MovementToggles;

/**
 * The text widgets.
 *
 * <p>The ones whose whole job is "read a value off the client and format it" are
 * nested here, because the interesting thing about them is that they are a set
 * and each is a dozen lines. The four that draw something other than text —
 * Keystrokes, the crosshair, the armour list, the potion list, the ping bars —
 * have files of their own, because a keyboard and an item renderer are not a
 * string.
 *
 * <h2>Every setting here is read</h2>
 *
 * <p>That is the rule this file is written to. A module declares a setting only
 * when something consults it, and {@code ModuleTests} refuses a {@code WORKING}
 * module with no effect at all. The settings every HUD module shares are read
 * once, in {@link TextWidget}, rather than once per widget.
 *
 * <h2>What none of these do</h2>
 *
 * <p>Read anything the player's own client does not already have on screen or in
 * memory. There is no packet inspection, no world scanning and no server query
 * anywhere in this file. The ping readout takes the number the tab list would
 * show; the armour readout takes the durability the inventory screen would show.
 * That is the line between a HUD and something a server is entitled to object
 * to, and it is drawn here rather than argued about later.
 */
public final class Widgets {

    private Widgets() {}

    /** Every widget, in registry order. */
    public static List<HudWidget> all(ClickTracker clicks) {
        List<HudWidget> out = new ArrayList<HudWidget>();
        out.add(new Fps());
        out.add(new Coordinates());
        out.add(new Clock());
        out.add(new Cps(clicks));
        out.add(new KeystrokesWidget(clicks));
        out.add(new SprintSneak());
        out.add(new PingWidget());
        out.add(new ArmourWidget());
        out.add(new PotionsWidget());
        out.add(new PackDisplayWidget());
        out.add(new CrosshairWidget());
        return out;
    }

    /**
     * Advance anything that must move once per frame.
     *
     * <p>Called from {@link FathomHud#render}, which is the only place that
     * knows what a frame is. A widget cannot do this for itself: a widget's
     * lines are asked four times a frame — to measure, to size, to test
     * visibility and to draw — so smoothing done there would advance four times
     * as fast as the frame rate and mean nothing.
     */
    static void frame() {
        FPS.sample();
        frameNumber++;
    }

    private static long frameNumber;

    /** Which HUD frame this is, for per-frame memoisation. */
    static long frameNumber() {
        return frameNumber;
    }

    /**
     * A module's setting, read while drawing.
     *
     * <p>Widgets look their own settings up rather than being handed them,
     * because a widget is drawn sixty times a second and the alternative is
     * pushing a settings object through every render signature. The lookup is
     * two map reads against a registry that is sealed before anything renders.
     *
     * <p>Every one of these falls back rather than failing: a widget whose
     * module has been unregistered draws with its defaults instead of throwing
     * inside a render loop.
     */
    static FathomModule module(String moduleId) {
        return net.fathom.optimizations.fabric.Runtime.features() == null
                ? null
                : net.fathom.optimizations.fabric.Runtime.features().find(moduleId);
    }

    static boolean option(String moduleId, String settingId, boolean fallback) {
        FathomModule module = module(moduleId);
        return module == null ? fallback : module.toggle(settingId, fallback);
    }

    static String optionChoice(String moduleId, String settingId, String fallback) {
        FathomModule module = module(moduleId);
        return module == null ? fallback : module.choice(settingId, fallback);
    }

    static double optionNumber(String moduleId, String settingId, double fallback) {
        FathomModule module = module(moduleId);
        return module == null ? fallback : module.number(settingId, fallback);
    }

    static int optionColour(String moduleId, String settingId, int fallback) {
        FathomModule module = module(moduleId);
        return module == null ? fallback : module.colour(settingId, fallback);
    }

    static String optionText(String moduleId, String settingId, String fallback) {
        FathomModule module = module(moduleId);
        return module == null ? fallback : module.text(settingId, fallback);
    }

    // -----------------------------------------------------------------------
    // Performance
    // -----------------------------------------------------------------------

    /**
     * The frame-rate reading behind the FPS widget.
     *
     * <p>Its own class because smoothing is state, and state that advances once
     * per frame cannot live in a method the renderer calls four times.
     *
     * <p>An exponential moving average rather than a window of frame times: it
     * is one multiply and one add per frame with no allocation, and the
     * difference between the two is invisible in a number rounded to whole
     * frames. The two coefficients are chosen by what they feel like — Light
     * settles in about a fifth of a second, which removes the flicker without
     * lagging a real drop; Smooth takes about a second and a half, for somebody
     * watching for a trend rather than a spike. Average is neither: it is the
     * honest count of frames drawn since the reading last changed, which is what
     * somebody comparing settings wants.
     */
    private static final class FpsMeter {
        private static final double LIGHT = 0.10;
        private static final double SMOOTH = 0.02;

        private long lastNanos;
        private double instant;
        private double light;
        private double smooth;
        private int framesSinceRead;
        private long readNanos;

        void sample() {
            long now = System.nanoTime();
            if (lastNanos == 0L) {
                lastNanos = now;
                readNanos = now;
                return;
            }
            long elapsed = now - lastNanos;
            lastNanos = now;
            if (elapsed <= 0L) {
                return;
            }
            // A stall -- a world load, a resource reload -- is not a frame rate.
            // Feeding it in would peg the average at one for the next second.
            if (elapsed > 500_000_000L) {
                readNanos = now;
                framesSinceRead = 0;
                return;
            }
            instant = 1_000_000_000.0 / elapsed;
            light = light == 0 ? instant : light + (instant - light) * LIGHT;
            smooth = smooth == 0 ? instant : smooth + (instant - smooth) * SMOOTH;
            framesSinceRead++;
        }

        int read(String reading) {
            if ("Raw".equals(reading)) {
                return (int) Math.round(instant);
            }
            if ("Average".equals(reading)) {
                return average();
            }
            return (int) Math.round("Smooth".equals(reading) ? smooth : light);
        }

        /** Frames drawn since this was last asked, over the time that took. */
        private int average() {
            long now = System.nanoTime();
            long elapsed = now - readNanos;
            int frames = framesSinceRead;
            readNanos = now;
            framesSinceRead = 0;
            if (elapsed <= 0L || frames <= 0) {
                return (int) Math.round(light);
            }
            return (int) Math.round(frames * 1_000_000_000.0 / elapsed);
        }
    }

    private static final FpsMeter FPS = new FpsMeter();

    /** Frames per second, as raw, smoothed or averaged as the settings ask. */
    public static final class Fps extends TextWidget {
        private long nextTextRefreshNs;
        private int settings = -1;
        private List<String> cachedLines = Collections.singletonList("0 FPS");

        public Fps() {
            super("fps", "000 FPS");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            long now = System.nanoTime();
            int revision = net.fathom.optimizations.core.module.Setting.revision();
            if (now < nextTextRefreshNs && revision == settings) {
                return cachedLines;
            }
            settings = revision;
            int fps = FPS.read(optionChoice("fps", "smoothing", "Light"));
            cachedLines = Collections.singletonList(
                    text(fps, optionChoice("fps", "format", "120 FPS")));
            nextTextRefreshNs = now + refreshNanos(optionChoice("fps", "refresh", "1 second"));
            return cachedLines;
        }

        private static String text(int fps, String format) {
            if ("120".equals(format)) {
                return String.valueOf(fps);
            }
            if ("FPS: 120".equals(format)) {
                return "FPS: " + fps;
            }
            if ("Custom".equals(format)) {
                return optionText("fps", "prefix", "") + fps + optionText("fps", "suffix", " fps");
            }
            return fps + " FPS";
        }

        private static long refreshNanos(String choice) {
            if ("100 ms".equals(choice)) {
                return 100_000_000L;
            }
            if ("250 ms".equals(choice)) {
                return 250_000_000L;
            }
            if ("500 ms".equals(choice)) {
                return 500_000_000L;
            }
            return 1_000_000_000L;
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // World
    // -----------------------------------------------------------------------

    /** Where you are, which way you are facing, and what you are standing in. */
    public static final class Coordinates extends TextWidget {
        private static final String[] COMPASS = {"South", "South West", "West", "North West",
                "North", "North East", "East", "South East"};
        private static final String[] SHORT = {"S", "SW", "W", "NW", "N", "NE", "E", "SE"};
        private static final String[] AXIS = {"+Z", "-X +Z", "-X", "-X -Z", "-Z", "+X -Z", "+X", "+X +Z"};

        private final List<String> out = new ArrayList<String>(4);
        private final StringBuilder line = new StringBuilder(48);

        public Coordinates() {
            super("coordinates", "-0000 / -00 / -0000");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return Collections.emptyList();
            }
            out.clear();
            String layout = optionChoice("coordinates", "layout", "Single line");
            boolean compact = "Compact".equals(layout);
            boolean perLine = "Lines".equals(layout);
            String format = optionChoice("coordinates", "format", "123 / 64 / -200");
            boolean labels = format.startsWith("X:");
            String separator = compact ? " " : (format.contains("/") ? " / " : (labels ? " " : ", "));
            int places = (int) optionNumber("coordinates", "precision", 0);

            line.setLength(0);
            if (option("coordinates", "axis.x", true)) {
                axis(perLine, labels ? "X: " : "", player.x, places, separator);
            }
            if (option("coordinates", "axis.y", true)) {
                axis(perLine, labels ? "Y: " : "", player.y, places, separator);
            }
            if (option("coordinates", "axis.z", true)) {
                axis(perLine, labels ? "Z: " : "", player.z, places, separator);
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }

            if (option("coordinates", "direction", true)) {
                extra(facing(player.yaw, optionChoice("coordinates", "direction.style", "North")), compact);
            }
            if (option("coordinates", "chunk", false)) {
                int chunkX = ((int) Math.floor(player.x)) >> 4;
                int chunkZ = ((int) Math.floor(player.z)) >> 4;
                extra((compact ? "C " : "Chunk ") + chunkX + ", " + chunkZ, compact);
            }
            if (option("coordinates", "dimension", false)) {
                extra(dimension(client), compact);
            }
            if (option("coordinates", "biome", false)) {
                String biome = biome(client);
                if (biome != null) {
                    extra(biome, compact);
                }
            }
            if (line.length() > 0) {
                out.add(line.toString());
                line.setLength(0);
            }
            return out;
        }

        /** One coordinate, either on its own line or appended to the current one. */
        private void axis(boolean perLine, String label, double value, int places, String separator) {
            if (perLine) {
                out.add(label + number(value, places));
                return;
            }
            if (line.length() > 0) {
                line.append(separator);
            }
            line.append(label).append(number(value, places));
        }

        /** Direction, chunk, dimension and biome: their own lines, or appended when compact. */
        private void extra(String text, boolean compact) {
            if (text == null || text.isEmpty()) {
                return;
            }
            if (!compact) {
                out.add(text);
                return;
            }
            if (line.length() > 0) {
                line.append("  ");
            }
            line.append(text);
        }

        private static String number(double value, int places) {
            if (places <= 0) {
                // Floor, not round: the block you are standing in is the one
                // below you, and rounding puts you in the next one halfway
                // across it.
                return String.valueOf((int) Math.floor(value));
            }
            return String.format(Locale.ROOT, "%." + places + "f", Double.valueOf(value));
        }

        /**
         * The biome under the player, from the client's own world.
         *
         * <p>Null when the chunk is not loaded, which happens for a frame or two
         * after a teleport. A missing line reads better than "Unknown".
         */
        private static String biome(MinecraftClient client) {
            if (client.world == null || client.player == null) {
                return null;
            }
            try {
                Biome biome = client.world.getBiome(new BlockPos(client.player));
                return biome == null ? null : biome.name;
            } catch (RuntimeException notLoaded) {
                return null;
            }
        }

        /** Whatever the world calls itself: Overworld, Nether, The End, or a mod's own. */
        private static String dimension(MinecraftClient client) {
            if (client.world == null || client.world.dimension == null) {
                return "";
            }
            try {
                return String.valueOf(client.world.dimension.getName());
            } catch (RuntimeException unnamed) {
                return "";
            }
        }

        /**
         * Vanilla's own convention: yaw 0 is south, and it grows clockwise. The +180 and
         * the wrap are what turn an unbounded float into one of eight names.
         */
        private static String facing(float yaw, String style) {
            int octant = (int) Math.floor(((yaw % 360f) + 360f) % 360f / 45f + 0.5f) % 8;
            if ("N".equals(style)) {
                return SHORT[octant];
            }
            if ("+Z".equals(style)) {
                return AXIS[octant];
            }
            if ("North (+Z)".equals(style)) {
                return COMPASS[octant] + " (" + AXIS[octant] + ")";
            }
            return COMPASS[octant];
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.player != null && !currentLines(client).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Status
    // -----------------------------------------------------------------------

    /** The real-world time, and the date if it is asked for. */
    public static final class Clock extends TextWidget {
        // Rebuilt only when a setting changes: SimpleDateFormat is not cheap and
        // this is asked at frame rate. Not shared between instances, because it
        // is also not thread-safe -- and the client thread is the only one here.
        private SimpleDateFormat format;
        private int settings = -1;
        /** The whole second the text was formatted for; it cannot change within one. */
        private long formattedSecond = Long.MIN_VALUE;
        private List<String> formatted = Collections.emptyList();

        public Clock() {
            super("clock", "00:00");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            int revision = net.fathom.optimizations.core.module.Setting.revision();
            if (format == null || revision != settings) {
                settings = revision;
                format = build();
                formattedSecond = Long.MIN_VALUE;
            }
            // The shown text only changes on a second boundary, so format at
            // most once a second instead of on every frame.
            long second = Math.floorDiv(System.currentTimeMillis(), 1000L);
            if (second != formattedSecond) {
                formatted = Collections.singletonList(format.format(new Date(second * 1000L)));
                formattedSecond = second;
            }
            return formatted;
        }

        /** The pattern the settings describe, or the custom one if it can be used. */
        private SimpleDateFormat build() {
            String chosen = optionChoice("clock", "format", "24 hour");
            String pattern;
            if ("Custom".equals(chosen)) {
                pattern = custom();
            } else {
                boolean twelve = "12 hour".equals(chosen);
                pattern = (twelve ? "h:mm" : "HH:mm")
                        + (option("clock", "seconds", false) ? ":ss" : "")
                        + (twelve ? " a" : "");
                String date = optionChoice("clock", "date", "Off");
                if (!"Off".equals(date)) {
                    pattern = datePattern(date) + "  " + pattern;
                }
            }
            SimpleDateFormat built = new SimpleDateFormat(pattern, Locale.ROOT);
            built.setTimeZone("UTC".equals(optionChoice("clock", "timezone", "System"))
                    ? TimeZone.getTimeZone("UTC")
                    : TimeZone.getDefault());
            return built;
        }

        /**
         * The custom pattern, if Java can use it.
         *
         * <p>A pattern is typed by hand into a text field, so most of them are
         * half-typed most of the time. An unusable one falls back to the plain
         * clock rather than throwing out of a render call.
         */
        private String custom() {
            String pattern = optionText("clock", "pattern", "EEE HH:mm");
            try {
                new SimpleDateFormat(pattern, Locale.ROOT).format(new Date());
                return pattern;
            } catch (RuntimeException unusable) {
                return "HH:mm";
            }
        }

        private static String datePattern(String choice) {
            if ("Mon 14 Sep".equals(choice)) {
                return "EEE d MMM";
            }
            if ("2026-09-14".equals(choice)) {
                return "yyyy-MM-dd";
            }
            if ("14/09/2026".equals(choice)) {
                return "dd/MM/yyyy";
            }
            if ("09/14/2026".equals(choice)) {
                return "MM/dd/yyyy";
            }
            return "d MMM";
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return true;
        }
    }

    // -----------------------------------------------------------------------
    // Input
    // -----------------------------------------------------------------------

    /** Clicks per second, over the window the module asks for. */
    public static final class Cps extends TextWidget {
        private final ClickTracker clicks;

        public Cps(ClickTracker clicks) {
            super("cps", "00 | 00");
            this.clicks = clicks;
        }

        /** How far back the tracker should count, in seconds. */
        static int window() {
            return (int) optionNumber("cps", "window", 1);
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            String mode = optionChoice("cps", "mode", "Both");
            boolean letters = option("cps", "letters", false);
            String value;
            if ("Left".equals(mode)) {
                value = (letters ? "L " : "") + clicks.left();
            } else if ("Right".equals(mode)) {
                value = (letters ? "R " : "") + clicks.right();
            } else if ("Combined".equals(mode)) {
                value = String.valueOf(clicks.left() + clicks.right());
            } else {
                value = (letters ? "L " : "") + clicks.left()
                        + " | " + (letters ? "R " : "") + clicks.right();
            }
            if (option("cps", "label", false)) {
                value = value + " CPS";
            }
            return Collections.singletonList(value);
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return true;
        }
    }

    /**
     * Whether you are sprinting or sneaking, and whether a toggle is holding it.
     *
     * <p>The state comes from the player, not from the toggle: what is drawn is
     * what the game is doing. The toggle only decides which of the two labels to
     * use, so a latched sprint that vanilla has refused — no food, an obstacle,
     * an item being eaten — reads as not sprinting, which is the truth.
     */
    public static final class SprintSneak extends TextWidget {
        private final List<String> out = new ArrayList<String>(2);
        private final List<Boolean> latched = new ArrayList<Boolean>(2);

        public SprintSneak() {
            super("toggle-sprint", "Sprinting");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            out.clear();
            latched.clear();
            if (player == null || !option("toggle-sprint", "hud", true)) {
                return out;
            }
            if (option("toggle-sprint", "sprint", true)) {
                if (player.isSprinting()) {
                    boolean toggled = MovementToggles.sprintLatched();
                    add(optionText("toggle-sprint",
                            toggled ? "label.sprint.toggled" : "label.sprinting",
                            toggled ? "Sprinting (Toggled)" : "Sprinting"), toggled);
                } else {
                    add(optionText("toggle-sprint", "label.walking", ""), false);
                }
            }
            if (option("toggle-sprint", "sneak", true) && player.isSneaking()) {
                boolean toggled = MovementToggles.sneakLatched();
                add(optionText("toggle-sprint",
                        toggled ? "label.sneak.toggled" : "label.sneaking",
                        toggled ? "Sneaking (Toggled)" : "Sneaking"), toggled);
            }
            return out;
        }

        /** An empty label is how a state is hidden, so nothing is drawn for it. */
        private void add(String text, boolean toggled) {
            if (text == null || text.isEmpty()) {
                return;
            }
            out.add(text);
            latched.add(Boolean.valueOf(toggled));
        }

        @Override
        protected int colourFor(int index, String line, int textColour) {
            if (index >= latched.size()) {
                return textColour;
            }
            if (latched.get(index).booleanValue()) {
                return optionColour("toggle-sprint", "toggled.colour", 0xFFFFFF);
            }
            // Walking is the one state that is not "something is happening", so
            // it takes the quieter colour rather than the module's text colour.
            String walking = optionText("toggle-sprint", "label.walking", "");
            if (!walking.isEmpty() && walking.equals(line)) {
                return optionColour("toggle-sprint", "walking.colour", 0xAAAAAA);
            }
            return textColour;
        }
    }
}
