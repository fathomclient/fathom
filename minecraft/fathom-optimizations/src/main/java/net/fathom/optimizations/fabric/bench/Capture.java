package net.fathom.optimizations.fabric.bench;

import java.io.File;
import java.util.List;

import org.lwjgl.opengl.Display;

import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.GameOptions;

import net.fathom.optimizations.core.bench.Configuration;
import net.fathom.optimizations.core.bench.Environment;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.registry.Optimization;
import net.fathom.optimizations.fabric.Runtime;

/**
 * What the client was actually doing when a benchmark ran.
 *
 * <h2>Why this is read from the game and not from a form</h2>
 *
 * <p>The failure this prevents is not choosing the wrong settings, it is not
 * <em>noticing</em> that something changed them: a resource pack that survived a
 * profile switch, VSync that came back after a driver update, a window that was
 * resized between two runs. Asking the client what it is doing at the moment of
 * the run catches all three. Asking a human to confirm catches none.
 *
 * <h2>Which configuration this is</h2>
 *
 * <p>Inferred, not asked, for the same reason. The client can see whether any
 * module is on, whether any optional optimization is on, and what preset the
 * launcher wrote — which is exactly the three facts that separate C, D, E and F.
 * It cannot see A or B, because Fathom is not in them, and {@link #configuration}
 * says so rather than guessing.
 */
public final class Capture {

    private Capture() {}

    /** Everything two runs must share before they may be compared. */
    public static Environment environment(MinecraftClient client) {
        Environment out = new Environment();
        GameOptions options = client == null ? null : client.options;

        out.set("resolution", client == null ? "" : client.width + "x" + client.height);
        out.set("display", options != null && options.fullscreen ? "fullscreen" : "windowed");

        if (options != null) {
            out.set("renderDistance", options.viewDistance);
            out.set("graphics", options.fancyGraphics ? "fancy" : "fast");
            // Vanilla's `ao` is 0 off, 1 minimum, 2 maximum. Written as the
            // number rather than a word, because "on" would lose the difference
            // between the two levels and they do not cost the same.
            out.set("smoothLighting", "level " + options.ao);
            out.set("particles", particles(options.particle));
            out.set("vbo", options.vbo);
            out.set("vsync", options.vsync);
            out.set("fpsLimit", options.maxFramerate);
            out.set("guiScale", options.guiScale);
            out.set("mipmaps", options.mipmapLevels);
            out.set("entityShadows", options.entityShadows);
            out.set("fov", String.valueOf(Math.round(options.fov)));
            out.set("resourcePacks", packs(options.resourcePacks));
        }

        out.set("java", System.getProperty("java.version", ""));
        out.set("javaVendor", System.getProperty("java.vendor", ""));
        out.set("heapMaxMb", (int) (java.lang.Runtime.getRuntime().maxMemory() / (1024L * 1024L)));
        out.set("jvmArguments", jvmArguments());
        out.set("os", System.getProperty("os.name", "") + " " + System.getProperty("os.arch", ""));
        out.set("cpuCores", java.lang.Runtime.getRuntime().availableProcessors());
        out.set("minecraft", "1.8.9");
        out.set("loader", loader());
        out.set("fathomVersion", fathomVersion());
        return out;
    }

    /**
     * Which comparison configuration this client is in.
     *
     * <p>C, D, E or F — the four that contain Fathom. A and B cannot be produced
     * here at all: there is no Fathom in them, so nothing is running to answer.
     */
    public static String configuration() {
        ModuleRegistry modules = Runtime.features();
        boolean anyModule = false;
        if (modules != null) {
            for (FathomModule module : modules.all()) {
                if (module.enabled() && module.hasEffect()) {
                    anyModule = true;
                    break;
                }
            }
        }

        boolean anyOptimization = false;
        if (Runtime.switches() != null && Runtime.registry() != null) {
            for (Optimization entry : Runtime.registry().all()) {
                if (entry.runsInGame() && Runtime.switches().isEnabled(entry.id())) {
                    anyOptimization = true;
                    break;
                }
            }
        }

        // The preset comes from the launcher's session descriptor, which is the
        // only place the game learns it. A dev run has no descriptor, so this is
        // empty and the inference below falls through to what is switched on --
        // which is the honest answer when nothing told us.
        String preset = Runtime.hud() == null
                ? ""
                : Runtime.hud().launcherSession().preset();
        if ("competitive".equals(preset)) {
            return "f-competitive";
        }
        if ("balanced".equals(preset)) {
            return "e-balanced";
        }
        // No preset to go on: the two remaining configurations differ by whether
        // anything is switched on at all.
        if (anyOptimization || anyModule) {
            return "c-core";
        }
        return "d-performance-off";
    }

    /**
     * Whether the window had focus for the whole run.
     *
     * <p>The check that stops a benchmark session producing six identical
     * numbers. With the unfocused frame cap on, a client that lost focus reports
     * the cap rather than the machine — and driving the game with synthetic
     * input loses focus constantly, which is how this was found.
     *
     * <p>Sampled rather than asked once at the end: focus can be lost and
     * regained inside a sixty-second run, and both ends of it would look fine.
     */
    public static final class Focus {
        private boolean everLost;

        public void sample() {
            if (!Display.isCreated() || !Display.isActive()) {
                everLost = true;
            }
        }

        public boolean held() {
            return !everLost;
        }

        public void reset() {
            everLost = false;
        }
    }

    /** Where a run's file goes. */
    public static File directory(File gameDirectory) {
        return net.fathom.optimizations.core.bench.Run.dayDirectory(gameDirectory);
    }

    /** The setup line for a configuration, for the note the screen shows. */
    public static String setupFor(String configurationId) {
        Configuration configuration = Configuration.byId(configurationId);
        return configuration == null ? "" : configuration.setup();
    }

    // -----------------------------------------------------------------------

    private static String particles(int level) {
        switch (level) {
            case 0:
                return "all";
            case 1:
                return "decreased";
            case 2:
                return "minimal";
            default:
                return String.valueOf(level);
        }
    }

    private static String packs(List<String> packs) {
        if (packs == null || packs.isEmpty()) {
            return "none";
        }
        StringBuilder out = new StringBuilder();
        for (String pack : packs) {
            if (out.length() > 0) {
                out.append(", ");
            }
            out.append(pack);
        }
        return out.toString();
    }

    /**
     * The JVM arguments that change how the game performs.
     *
     * <p>Filtered, not the whole command line: a launcher passes a token and a
     * session id among them, and a benchmark file is something people paste into
     * bug reports. Only flags starting with {@code -X}, {@code -XX} and
     * {@code -D} survive, and even then anything naming a token, a key or a
     * secret is dropped.
     */
    private static String jvmArguments() {
        StringBuilder out = new StringBuilder();
        try {
            List<String> arguments =
                    java.lang.management.ManagementFactory.getRuntimeMXBean().getInputArguments();
            for (String argument : arguments) {
                if (!argument.startsWith("-X") && !argument.startsWith("-D")) {
                    continue;
                }
                String lower = argument.toLowerCase(java.util.Locale.ROOT);
                if (lower.contains("token") || lower.contains("secret") || lower.contains("key")
                        || lower.contains("password") || lower.contains("session")) {
                    continue;
                }
                if (out.length() > 0) {
                    out.append(' ');
                }
                out.append(argument);
            }
        } catch (RuntimeException | LinkageError unavailable) {
            // No management bean on this JVM. An empty string is honest; the
            // report will say the field was not recorded.
            return "";
        }
        return out.length() == 0 ? "(none)" : out.toString();
    }

    private static String loader() {
        try {
            return "Legacy Fabric "
                    + FabricLoader.getInstance()
                            .getModContainer("fabricloader")
                            .get()
                            .getMetadata()
                            .getVersion()
                            .getFriendlyString();
        } catch (RuntimeException unavailable) {
            return "";
        }
    }

    private static String fathomVersion() {
        try {
            return FabricLoader.getInstance()
                    .getModContainer("fathom-optimizations")
                    .get()
                    .getMetadata()
                    .getVersion()
                    .getFriendlyString();
        } catch (RuntimeException unavailable) {
            return "";
        }
    }
}
