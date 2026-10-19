package net.fathom.optimizations.fabric;

import java.io.File;
import java.lang.reflect.Method;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * The mod loader underneath, whichever it is.
 *
 * <p>The same client classes run on Legacy Fabric and, remapped to Forge's
 * names by the {@code forgeJar} build, on Forge 1.8.9. Everything the client
 * asks a loader -- the game directory, whether another mod is present, version
 * strings for Diagnostics -- is asked here, so no other class names a loader.
 *
 * <p>Fabric is answered through its API; Forge through FML by reflection, so
 * this compiles without Forge and links without it on Fabric. Nothing here may
 * touch a Minecraft class: the Mixin plugin calls it before any are loaded.
 */
public final class Platform {

    private static final boolean FABRIC = present("net.fabricmc.loader.api.FabricLoader");

    private Platform() {}

    private static boolean present(String className) {
        try {
            Class.forName(className, false, Platform.class.getClassLoader());
            return true;
        } catch (Throwable absent) {
            return false;
        }
    }

    /** Whether this is Fabric. Otherwise it is Forge (LaunchWrapper and FML). */
    public static boolean fabric() {
        return FABRIC;
    }

    /** The game directory: the profile's {@code minecraft} folder. */
    public static File gameDir() {
        if (FABRIC) {
            return FabricSide.gameDir();
        }
        try {
            Object home = Class.forName("net.minecraft.launchwrapper.Launch").getField("minecraftHome").get(null);
            if (home instanceof File) {
                return (File) home;
            }
        } catch (Throwable ignored) {
            // Fall through to the working directory, which the launcher sets to the same place.
        }
        return new File(".").getAbsoluteFile();
    }

    /**
     * Whether a mod is loaded, by its Fabric id. On Forge the ids that name
     * OptiFine are answered by looking for OptiFine itself, since there is no
     * OptiFabric there and OptiFine is not an FML mod.
     */
    public static boolean isModLoaded(String id) {
        if (FABRIC) {
            return FabricSide.isModLoaded(id);
        }
        if ("optifabric".equals(id) || "optifine".equals(id)) {
            return optifineOnForge();
        }
        try {
            Object loaded = forgeLoader().getMethod("isModLoaded", String.class).invoke(null, id);
            return Boolean.TRUE.equals(loaded);
        } catch (Throwable unknown) {
            return false;
        }
    }

    private static boolean optifineOnForge() {
        ClassLoader loader = Platform.class.getClassLoader();
        return loader.getResource("optifine/OptiFineClassTransformer.class") != null
                || loader.getResource("optifine/OptiFineForgeTweaker.class") != null;
    }

    /** A mod's version, or null when it is not loaded or has none. */
    public static String modVersion(String id) {
        if (FABRIC) {
            return FabricSide.modVersion(id);
        }
        if ("fathom-optimizations".equals(id)) {
            Package own = Platform.class.getPackage();
            return own == null ? null : own.getImplementationVersion();
        }
        try {
            Object instance = forgeLoader().getMethod("instance").invoke(null);
            Map<?, ?> mods = (Map<?, ?>) instance.getClass().getMethod("getIndexedModList").invoke(instance);
            Object container = mods.get(id);
            return container == null ? null : (String) container.getClass().getMethod("getVersion").invoke(container);
        } catch (Throwable unknown) {
            return null;
        }
    }

    /** "Fabric 0.19.3" or "Forge 11.15.1.2318", for Diagnostics. */
    public static String loaderDescription() {
        if (FABRIC) {
            String version = FabricSide.modVersion("fabricloader");
            return "Fabric " + (version == null ? "?" : version);
        }
        try {
            Object version = Class.forName("net.minecraftforge.common.ForgeVersion").getMethod("getVersion").invoke(null);
            return "Forge " + version;
        } catch (Throwable unknown) {
            return "Forge";
        }
    }

    /** Every loaded mod as {id, name, version}, for Diagnostics. */
    public static List<String[]> mods() {
        if (FABRIC) {
            return FabricSide.mods();
        }
        List<String[]> out = new ArrayList<String[]>();
        try {
            Object instance = forgeLoader().getMethod("instance").invoke(null);
            List<?> mods = (List<?>) instance.getClass().getMethod("getActiveModList").invoke(instance);
            for (Object container : mods) {
                Class<?> type = container.getClass();
                out.add(new String[] {
                    String.valueOf(type.getMethod("getModId").invoke(container)),
                    String.valueOf(type.getMethod("getName").invoke(container)),
                    String.valueOf(type.getMethod("getVersion").invoke(container)),
                });
            }
        } catch (Throwable unknown) {
            return Collections.emptyList();
        }
        return out;
    }

    /** Whether this is a development run (Loom's runClient, or a deobfuscated Forge workspace). */
    public static boolean development() {
        if (FABRIC) {
            return FabricSide.development();
        }
        try {
            Map<?, ?> blackboard = (Map<?, ?>) Class.forName("net.minecraft.launchwrapper.Launch")
                    .getField("blackboard").get(null);
            return Boolean.TRUE.equals(blackboard.get("fml.deobfuscatedEnvironment"));
        } catch (Throwable unknown) {
            return false;
        }
    }

    /** The game's own launch arguments, or none where the loader does not keep them. */
    public static String[] launchArguments() {
        return FABRIC ? FabricSide.launchArguments() : new String[0];
    }

    private static Class<?> forgeLoader() throws ClassNotFoundException {
        return Class.forName("net.minecraftforge.fml.common.Loader");
    }

    /** Everything that names Fabric's API, so the classes above link without it. */
    private static final class FabricSide {

        static File gameDir() {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getGameDir().toFile();
        }

        static boolean isModLoaded(String id) {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isModLoaded(id);
        }

        static String modVersion(String id) {
            java.util.Optional<net.fabricmc.loader.api.ModContainer> found =
                    net.fabricmc.loader.api.FabricLoader.getInstance().getModContainer(id);
            return found.isPresent() ? found.get().getMetadata().getVersion().getFriendlyString() : null;
        }

        static List<String[]> mods() {
            List<String[]> out = new ArrayList<String[]>();
            for (net.fabricmc.loader.api.ModContainer container
                    : net.fabricmc.loader.api.FabricLoader.getInstance().getAllMods()) {
                out.add(new String[] {
                    container.getMetadata().getId(),
                    container.getMetadata().getName(),
                    container.getMetadata().getVersion().getFriendlyString(),
                });
            }
            return out;
        }

        static boolean development() {
            return net.fabricmc.loader.api.FabricLoader.getInstance().isDevelopmentEnvironment();
        }

        static String[] launchArguments() {
            return net.fabricmc.loader.api.FabricLoader.getInstance().getLaunchArguments(true);
        }
    }
}
