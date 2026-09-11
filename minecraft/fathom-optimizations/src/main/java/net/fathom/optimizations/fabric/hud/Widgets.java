package net.fathom.optimizations.fabric.hud;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.TimeZone;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.DrawableHelper;
import net.minecraft.client.network.PlayerListEntry;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.entity.player.ClientPlayerEntity;
import net.minecraft.entity.effect.StatusEffect;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.biome.Biome;

import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.CrosshairPolicy;
import net.fathom.optimizations.core.module.HudStyle;

/**
 * The widgets themselves.
 *
 * <p>Nested classes in one file rather than eleven files, because each is a dozen lines and
 * the interesting thing about the set is that it is a set. Anything with real logic —
 * click counting, snapping, layout — is in {@code core} where it has tests; what is left
 * here is "read a value off the client and format it", which is exactly the code that
 * cannot be tested without a running game and should therefore be as thin as possible.
 *
 * <h2>Every setting here is read</h2>
 *
 * <p>That is the rule this file was rewritten to. A module declares a setting
 * only when something below consults it, and {@code ModuleTests} refuses a
 * {@code WORKING} module with no effect at all. The eight settings every HUD
 * module shares are read once, in {@link TextWidget}, rather than nine times
 * here.
 *
 * <h2>What none of these do</h2>
 *
 * <p>Read anything the player's own client does not already have on screen or in memory.
 * There is no packet inspection, no world scanning and no server query anywhere in this
 * file. The ping readout takes the number the tab list would show; the armour readout
 * takes the durability the inventory screen would show. That is the line between a HUD and
 * something a server is entitled to object to, and it is drawn here rather than argued
 * about later.
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
        out.add(new Keystrokes(clicks));
        out.add(new SprintSneak());
        out.add(new Ping());
        out.add(new Armour());
        out.add(new Potions());
        out.add(new PackDisplay());
        out.add(new Crosshair());
        return out;
    }

    /**
     * Advance anything that must move once per frame.
     *
     * <p>Called from {@link FathomHud#render}, which is the only place that
     * knows what a frame is. A widget cannot do this for itself: a widget's
     * {@code lines} is asked four times a frame — to measure, to size, to test
     * visibility and to draw — so smoothing done there would advance four times
     * as fast as the frame rate and mean nothing.
     */
    static void frame() {
        FPS.sample();
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
        return net.fathom.optimizations.fabric.Runtime.features().find(moduleId);
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
     * watching for a trend rather than a spike.
     */
    private static final class FpsMeter {
        private static final double LIGHT = 0.10;
        private static final double SMOOTH = 0.02;

        private long lastNanos;
        private double instant;
        private double light;
        private double smooth;

        void sample() {
            long now = System.nanoTime();
            if (lastNanos == 0L) {
                lastNanos = now;
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
                return;
            }
            instant = 1_000_000_000.0 / elapsed;
            light = light == 0 ? instant : light + (instant - light) * LIGHT;
            smooth = smooth == 0 ? instant : smooth + (instant - smooth) * SMOOTH;
        }

        int read(String smoothing) {
            if ("Off".equals(smoothing)) {
                return (int) Math.round(instant);
            }
            return (int) Math.round("Smooth".equals(smoothing) ? smooth : light);
        }
    }

    private static final FpsMeter FPS = new FpsMeter();

    /** Frames per second, smoothed as much as the settings ask for. */
    public static final class Fps extends TextWidget {
        private long nextTextRefreshNs;
        private String cachedSmoothing = "";
        private String cachedFormat = "";
        private List<String> cachedLines = Collections.singletonList("0 FPS");

        public Fps() {
            super("fps", "000 FPS");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            long now = System.nanoTime();
            String smoothing = optionChoice("fps", "smoothing", "Light");
            String format = optionChoice("fps", "format", "120 FPS");
            if (now < nextTextRefreshNs
                    && smoothing.equals(cachedSmoothing)
                    && format.equals(cachedFormat)) {
                return cachedLines;
            }
            int fps = FPS.read(smoothing);
            String text;
            if ("120".equals(format)) {
                text = String.valueOf(fps);
            } else if ("FPS: 120".equals(format)) {
                text = "FPS: " + fps;
            } else {
                text = fps + " FPS";
            }
            cachedLines = Collections.singletonList(text);
            cachedSmoothing = smoothing;
            cachedFormat = format;
            nextTextRefreshNs = now + refreshNanos(optionChoice("fps", "refresh", "1 second"));
            return cachedLines;
        }

        private static long refreshNanos(String choice) {
            if ("250 ms".equals(choice)) return 250_000_000L;
            if ("500 ms".equals(choice)) return 500_000_000L;
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

        public Coordinates() {
            super("coordinates", "-0000 / -00 / -0000");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<String>(3);

            int places = (int) optionNumber("coordinates", "precision", 0);
            boolean labels = option("coordinates", "labels", false);
            StringBuilder position = new StringBuilder(24);
            if (option("coordinates", "axis.x", true)) {
                append(position, labels ? "X " : "", player.x, places);
            }
            if (option("coordinates", "axis.y", true)) {
                append(position, labels ? "Y " : "", player.y, places);
            }
            if (option("coordinates", "axis.z", true)) {
                append(position, labels ? "Z " : "", player.z, places);
            }
            if (position.length() > 0) {
                out.add(position.toString());
            }

            if (option("coordinates", "direction", true)) {
                out.add(facing(player.yaw));
            }
            if (option("coordinates", "biome", false)) {
                String biome = biome(client);
                if (biome != null) {
                    out.add(biome);
                }
            }
            return out;
        }

        private static void append(StringBuilder into, String label, double value, int places) {
            if (into.length() > 0) {
                into.append(" / ");
            }
            into.append(label);
            if (places <= 0) {
                // Floor, not round: the block you are standing in is the one
                // below you, and rounding puts you in the next one halfway
                // across it.
                into.append((int) Math.floor(value));
            } else {
                into.append(String.format(Locale.ROOT, "%." + places + "f", Double.valueOf(value)));
            }
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

        /**
         * Vanilla's own convention: yaw 0 is south, and it grows clockwise. The +180 and
         * the wrap are what turn an unbounded float into one of eight names.
         */
        private static String facing(float yaw) {
            int octant = (int) Math.floor(((yaw % 360f) + 360f) % 360f / 45f + 0.5f) % 8;
            return COMPASS[octant];
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.player != null && !lines(client).isEmpty();
        }
    }

    // -----------------------------------------------------------------------
    // Status
    // -----------------------------------------------------------------------

    /** The real-world time. */
    public static final class Clock extends TextWidget {
        // Rebuilt only when the pattern or the zone changes: SimpleDateFormat is
        // not cheap and this is asked at frame rate. Not shared between
        // instances, because it is also not thread-safe -- and the client thread
        // is the only one here.
        private SimpleDateFormat format;
        private String pattern = "";
        private String zone = "";

        public Clock() {
            super("clock", "00:00");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            boolean twelve = "12 hour".equals(optionChoice("clock", "format", "24 hour"));
            boolean seconds = option("clock", "seconds", false);
            String wanted = (twelve ? "h:mm" : "HH:mm") + (seconds ? ":ss" : "") + (twelve ? " a" : "");
            String wantedZone = optionChoice("clock", "timezone", "System");

            if (format == null || !wanted.equals(pattern) || !wantedZone.equals(zone)) {
                format = new SimpleDateFormat(wanted, Locale.ROOT);
                format.setTimeZone("UTC".equals(wantedZone)
                        ? TimeZone.getTimeZone("UTC")
                        : TimeZone.getDefault());
                pattern = wanted;
                zone = wantedZone;
            }
            return Collections.singletonList(format.format(new Date()));
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return true;
        }
    }

    /** Latency, as the tab list reports it. */
    public static final class Ping extends TextWidget {
        public Ping() {
            super("ping", "000 ms");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            Integer latency = latency(client);
            if (latency == null) {
                return Collections.emptyList();
            }
            String format = optionChoice("ping", "format", "120 ms");
            if ("120".equals(format)) {
                return Collections.singletonList(String.valueOf(latency));
            }
            if ("Ping: 120 ms".equals(format)) {
                return Collections.singletonList("Ping: " + latency + " ms");
            }
            return Collections.singletonList(latency + " ms");
        }

        /**
         * Green, amber, red.
         *
         * <p>The thresholds are the ones the vanilla tab list already draws its
         * signal bars at, so the colour agrees with the icon a player is used to
         * rather than introducing a second opinion.
         */
        @Override
        protected int colourFor(int index, String line, int textColour) {
            if (!option("ping", "colour-by-quality", true)) {
                return textColour;
            }
            Integer latency = latency(MinecraftClient.getInstance());
            if (latency == null) {
                return textColour;
            }
            int value = latency.intValue();
            return value < 100 ? 0x5FD37A : (value <= 300 ? 0xE0B341 : 0xE0505C);
        }

        private static Integer latency(MinecraftClient client) {
            if (client == null || client.player == null || client.getNetworkHandler() == null) {
                return null;
            }
            PlayerListEntry entry = client.getNetworkHandler().getPlayerListEntry(client.player.getUuid());
            return entry == null ? null : Integer.valueOf(entry.getLatency());
        }

        @Override
        public boolean visible(MinecraftClient client) {
            // Singleplayer has a network handler and a latency of zero, which is
            // true and useless. Nothing to say is better than "0 ms", and the
            // module declares MULTIPLAYER so its row says so too.
            return !client.isInSingleplayer() && latency(client) != null;
        }
    }

    /** Durability left on what you are wearing. */
    public static final class Armour extends TextWidget {
        private static final String[] NAMES = {"Boots", "Legs", "Chest", "Helmet"};

        public Armour() {
            super("armour", "Helmet 100%");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return Collections.emptyList();
            }
            boolean names = option("armour", "names", true);
            boolean remaining = "Remaining".equals(optionChoice("armour", "style", "Percent"));
            List<String> out = new ArrayList<String>(5);

            if (option("armour", "held", true)) {
                ItemStack held = player.inventory.getMainHandStack();
                if (held != null) {
                    out.add(line(names ? "Held" : "", held, remaining));
                }
            }
            ItemStack[] worn = player.inventory.armor;
            // Drawn top-down the way it is worn, and the array is stored feet-first.
            for (int slot = worn.length - 1; slot >= 0; slot--) {
                ItemStack piece = worn[slot];
                if (piece == null) {
                    continue;
                }
                out.add(line(names ? NAMES[slot] : "", piece, remaining));
            }
            return out;
        }

        private static String line(String name, ItemStack stack, boolean remaining) {
            String value = remaining
                    ? String.valueOf(Math.max(0, stack.getMaxDamage() - stack.getDamage()))
                    : percentLeft(stack) + "%";
            return name.isEmpty() ? value : name + " " + value;
        }

        private static int percentLeft(ItemStack stack) {
            int max = stack.getMaxDamage();
            if (max <= 0) {
                return 100;
            }
            return Math.max(0, Math.round((max - stack.getDamage()) * 100f / max));
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.player != null && !lines(client).isEmpty();
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
            String value;
            if ("Left".equals(mode)) {
                value = String.valueOf(clicks.left());
            } else if ("Right".equals(mode)) {
                value = String.valueOf(clicks.right());
            } else {
                value = clicks.left() + " | " + clicks.right();
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

    /** Whether you are sprinting or sneaking. */
    public static final class SprintSneak extends TextWidget {
        public SprintSneak() {
            super("toggle-sprint", "Sprinting");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return Collections.emptyList();
            }
            List<String> out = new ArrayList<String>(2);
            if (player.isSprinting() && option("toggle-sprint", "sprint", true)) {
                out.add("Sprinting");
            }
            if (player.isSneaking() && option("toggle-sprint", "sneak", true)) {
                out.add("Sneaking");
            }
            return out;
        }
    }

    /** Active effects and how long each has left. */
    public static final class Potions extends TextWidget {
        public Potions() {
            super("potions", "Speed II  1:30");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            ClientPlayerEntity player = client.player;
            if (player == null) {
                return Collections.emptyList();
            }
            boolean level = option("potions", "level", true);
            boolean duration = option("potions", "duration", true);
            List<String> out = new ArrayList<String>();
            for (StatusEffectInstance effect : player.getStatusEffectInstances()) {
                StatusEffect type = StatusEffect.STATUS_EFFECTS[effect.getEffectId()];
                if (type == null) {
                    continue;
                }
                StringBuilder line = new StringBuilder(
                        net.minecraft.client.resource.language.I18n.translate(type.getTranslationKey()));
                if (level && effect.getAmplifier() > 0) {
                    line.append(' ').append(roman(effect.getAmplifier() + 1));
                }
                if (duration) {
                    line.append("  ").append(duration(effect.getDuration()));
                }
                out.add(line.toString());
            }
            return out;
        }

        /**
         * Effect levels are Roman numerals in Minecraft, and only ever small.
         *
         * <p>Four entries rather than a general algorithm: vanilla caps
         * amplifiers well below anything that needs one, and a general converter
         * would be twenty lines to render a number nobody will see.
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

        private static String duration(int ticks) {
            int seconds = ticks / 20;
            return seconds / 60 + ":" + (seconds % 60 < 10 ? "0" : "") + seconds % 60;
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.player != null && !client.player.getStatusEffectInstances().isEmpty();
        }
    }

    /** Which resource pack is on. */
    public static final class PackDisplay extends TextWidget {
        public PackDisplay() {
            super("pack-display", "Default");
        }

        @Override
        protected List<String> lines(MinecraftClient client) {
            if (client.options == null) {
                return Collections.emptyList();
            }
            String name = "Default";
            List<String> packs = client.options.resourcePacks;
            if (packs != null && !packs.isEmpty()) {
                // The last one wins in Minecraft's stack, so the last one is the
                // one somebody would name if you asked what pack they were using.
                name = packs.get(packs.size() - 1).replace(".zip", "");
            }
            return Collections.singletonList(
                    "Pack: Name".equals(optionChoice("pack-display", "format", "Name only"))
                            ? "Pack: " + name
                            : name);
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.options != null;
        }
    }

    /**
     * A crosshair you choose.
     *
     * <p>Vanilla's is hidden by {@code InGameHudCrosshairMixin} when the
     * "Hide vanilla crosshair" setting is on, which is the module's declared
     * {@code crosshair} behaviour. With it off both are drawn, which is a
     * legitimate thing to want while comparing them and a mess otherwise.
     */
    public static final class Crosshair implements HudWidget {
        @Override
        public String id() {
            return "crosshair";
        }

        @Override
        public String placeholder() {
            return "+";
        }

        private static int size() {
            return (int) optionNumber("crosshair", "size", 9);
        }

        @Override
        public int width(MinecraftClient client, HudFont font) {
            return size();
        }

        @Override
        public int height(MinecraftClient client, HudFont font) {
            return size();
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return CrosshairPolicy.drawsCustom(module("crosshair"));
        }

        @Override
        public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
            if (!CrosshairPolicy.drawsCustom(module("crosshair"))) {
                return;
            }
            int extent = size();
            int gap = (int) optionNumber("crosshair", "gap", 2);
            int colour = (alpha << 24) | optionColour("crosshair", "colour", 0xFFFFFF);
            boolean outline = option("crosshair", "outline", true);
            String shape = optionChoice("crosshair", "shape", "Cross");
            int centreX = x + extent / 2;
            int centreY = y + extent / 2;

            if (outline) {
                // A dark pass one pixel larger, so the shape reads against snow
                // and against a dark cave without the user changing anything.
                paint(shape, centreX, centreY, extent, gap, ((alpha * 2 / 3) << 24), true);
            }
            paint(shape, centreX, centreY, extent, gap, colour, false);
        }

        private static void paint(String shape, int cx, int cy, int extent, int gap, int colour, boolean grow) {
            int arm = extent / 2;
            int pad = grow ? 1 : 0;

            if ("Dot".equals(shape)) {
                fill(cx - 1 - pad, cy - 1 - pad, cx + 1 + pad, cy + 1 + pad, colour);
                return;
            }
            if ("Circle".equals(shape)) {
                for (int angle = 0; angle < 360; angle += 8) {
                    int px = cx + (int) Math.round(Math.cos(Math.toRadians(angle)) * arm);
                    int py = cy + (int) Math.round(Math.sin(Math.toRadians(angle)) * arm);
                    fill(px - pad, py - pad, px + 1 + pad, py + 1 + pad, colour);
                }
                return;
            }

            // Cross and Tee share their arms; a Tee simply has no top one.
            if (!"Tee".equals(shape)) {
                fill(cx - pad, cy - arm - pad, cx + 1 + pad, cy - gap + pad, colour);
            }
            fill(cx - pad, cy + gap - pad, cx + 1 + pad, cy + arm + 1 + pad, colour);
            fill(cx - arm - pad, cy - pad, cx - gap + pad, cy + 1 + pad, colour);
            fill(cx + gap - pad, cy - pad, cx + arm + 1 + pad, cy + 1 + pad, colour);
        }

        private static void fill(int left, int top, int right, int bottom, int colour) {
            DrawableHelper.fill(left, top, right, bottom, colour);
        }
    }

    /**
     * Movement keys, mouse buttons, space and sneak, lit while held.
     *
     * <p>The one widget that is not text on a plate, so it implements
     * {@link HudWidget} directly rather than extending {@link TextWidget}. It
     * still reads the common style settings — background colour and opacity,
     * padding, radius, shadow — through {@link HudStyle}, because a key that
     * ignored them would be the one widget whose settings did nothing.
     *
     * <p>The keys read are the player's own bindings, not the physical W A S D, so
     * somebody on a Dvorak or ESDF layout sees their own keys light up.
     */
    public static final class Keystrokes implements HudWidget {
        private static final int KEY = 16;
        private static final int GAP = 2;
        private static final int WIDE = KEY * 3 + GAP * 2;

        private final ClickTracker clicks;

        public Keystrokes(ClickTracker clicks) {
            this.clicks = clicks;
        }

        @Override
        public String id() {
            return "keystrokes";
        }

        @Override
        public String placeholder() {
            return "WASD";
        }

        private static int pad() {
            return HudStyle.padding(module("keystrokes"));
        }

        @Override
        public int width(MinecraftClient client, HudFont font) {
            return WIDE + pad() * 2;
        }

        @Override
        public int height(MinecraftClient client, HudFont font) {
            int rows = 0;
            if (option("keystrokes", "wasd", true)) {
                rows += 2;
            }
            if (option("keystrokes", "mouse", true)) {
                rows++;
            }
            if (option("keystrokes", "space", true)) {
                rows++;
            }
            if (option("keystrokes", "sneak", false)) {
                rows++;
            }
            if (rows == 0) {
                return 0;
            }
            return rows * (KEY + GAP) - GAP + pad() * 2;
        }

        @Override
        public boolean visible(MinecraftClient client) {
            return client.options != null && height(client, null) > 0;
        }

        @Override
        public void draw(MinecraftClient client, HudFont font, int x, int y, int alpha) {
            if (client.options == null) {
                return;
            }
            FathomModule module = module("keystrokes");
            int pad = HudStyle.padding(module);
            if (HudStyle.background(module)) {
                Plate.draw(x, y, width(client, font), height(client, font),
                        ((HudStyle.backgroundAlpha(module) * alpha / 255) << 24)
                                | (HudStyle.backgroundColour(module) & 0x00FFFFFF),
                        HudStyle.radius(module), HudStyle.dropShadow(module));
            }

            int left = x + pad;
            int row = y + pad;

            if (option("keystrokes", "wasd", true)) {
                key(font, left + KEY + GAP, row, KEY, "W", pressed(client.options.forwardKey), alpha);
                row += KEY + GAP;
                key(font, left, row, KEY, "A", pressed(client.options.leftKey), alpha);
                key(font, left + KEY + GAP, row, KEY, "S", pressed(client.options.backKey), alpha);
                key(font, left + (KEY + GAP) * 2, row, KEY, "D", pressed(client.options.rightKey), alpha);
                row += KEY + GAP;
            }
            if (option("keystrokes", "mouse", true)) {
                boolean numbers = option("keystrokes", "cps-on-keys", false);
                int half = (WIDE - GAP) / 2;
                wide(font, left, row, half, numbers ? String.valueOf(clicks.left()) : "LMB",
                        clicks.leftDown(), alpha);
                wide(font, left + half + GAP, row, WIDE - half - GAP,
                        numbers ? String.valueOf(clicks.right()) : "RMB", clicks.rightDown(), alpha);
                row += KEY + GAP;
            }
            if (option("keystrokes", "space", true)) {
                wide(font, left, row, WIDE, "SPACE", pressed(client.options.jumpKey), alpha);
                row += KEY + GAP;
            }
            if (option("keystrokes", "sneak", false)) {
                wide(font, left, row, WIDE, "SHIFT", pressed(client.options.sneakKey), alpha);
            }
        }

        /**
         * {@code KeyBinding.isPressed()} rather than {@code wasPressed()}.
         *
         * <p>{@code wasPressed} consumes a queued press and returns true once, which is
         * how vanilla triggers an action. Calling it from a renderer would eat the jump
         * the player was trying to make.
         */
        private static boolean pressed(KeyBinding binding) {
            return binding != null && binding.isPressed();
        }

        private static void key(
                HudFont font, int x, int y, int size, String label, boolean down, int alpha) {
            wide(font, x, y, size, label, down, alpha);
        }

        private static void wide(
                HudFont font, int x, int y, int width, String label, boolean down, int alpha) {
            FathomModule module = module("keystrokes");
            int keyAlpha = alpha * (int) optionNumber("keystrokes", "key.opacity", 80) / 100;
            int fill = down
                    ? optionColour("keystrokes", "key.pressed.fill", 0x373B44)
                    : optionColour("keystrokes", "key.unpressed.fill", 0x111318);
            if (option("keystrokes", "key.background", true)) {
                Plate.draw(x, y, width, KEY, (keyAlpha << 24) | (fill & 0x00FFFFFF),
                        Math.min(HudStyle.radius(module), KEY / 2), false);
            }
            if (option("keystrokes", "key.border", true)) {
                int border = (keyAlpha << 24)
                        | (optionColour("keystrokes", "key.border.colour", 0x5C6573) & 0x00FFFFFF);
                border(x, y, width, KEY, border);
            }

            int text = down
                    ? optionColour("keystrokes", "key.pressed.text", 0xFFFFFF)
                    : optionColour("keystrokes", "key.unpressed.text", 0xE7EAF0);
            int colour = (keyAlpha << 24) | (text & 0x00FFFFFF);
            float labelWidth = font.width(label);
            float textX = x + (width - labelWidth) / 2f;
            float textY = y + (KEY - font.lineHeight()) / 2f;
            font.draw(label, textX, textY, colour, HudStyle.textShadow(module));
        }

        private static void border(int x, int y, int width, int height, int colour) {
            DrawableHelper.fill(x, y, x + width, y + 1, colour);
            DrawableHelper.fill(x, y + height - 1, x + width, y + height, colour);
            DrawableHelper.fill(x, y + 1, x + 1, y + height - 1, colour);
            DrawableHelper.fill(x + width - 1, y + 1, x + width, y + height - 1, colour);
        }
    }
}
