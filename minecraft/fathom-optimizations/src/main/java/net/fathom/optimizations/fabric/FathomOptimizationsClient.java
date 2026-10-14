package net.fathom.optimizations.fabric;

import java.util.ArrayList;
import java.util.List;

import net.fabricmc.api.ClientModInitializer;
import net.fabricmc.loader.api.FabricLoader;
import net.minecraft.client.MinecraftClient;
import net.minecraft.resource.ReloadableResourceManager;
import net.minecraft.resource.ResourceManager;
import net.minecraft.resource.ResourceReloadListener;

import net.fathom.optimizations.core.config.LauncherConfig;
import net.fathom.optimizations.core.guard.Guard;
import net.fathom.optimizations.core.registry.Conflict;
import net.fathom.optimizations.core.registry.Optimization;
import net.fathom.optimizations.core.registry.OptimizationRegistry;
import net.fathom.optimizations.core.registry.SwitchBoard;
import net.fathom.optimizations.core.module.FathomApi;
import net.fathom.optimizations.core.module.FathomModule;
import net.fathom.optimizations.core.module.ModuleRegistry;
import net.fathom.optimizations.core.hud.Placement;
import net.fathom.optimizations.core.ui.VideoOptionDomain;
import net.fathom.optimizations.fabric.bridge.LauncherBridge;
import net.fathom.optimizations.fabric.hud.FathomHud;
import net.fathom.optimizations.fabric.hud.HudWidget;

/**
 * The mod's entry point.
 *
 * <p>This class is deliberately thin. All it does is:
 *
 * <ol>
 *   <li>build the registry, then ask {@link LauncherConfig} what the launcher chose;
 *   <li>look at which other mods are present and switch off anything that conflicts;
 *   <li>hand the result to {@link Runtime} so the Mixins can reach it.
 * </ol>
 *
 * <p>The order matters. The launcher's choices are applied first as
 * {@link net.fathom.optimizations.core.registry.SwitchBoard.Reason#USER}, and conflict
 * detection runs after and may override them — because a user who installs OptiFine has
 * made a later and more specific decision than the one recorded in a settings file.
 *
 * <p>It performs no optimization of its own; the Mixins do that, and only where an entry
 * is switched on.
 */
public final class FathomOptimizationsClient implements ClientModInitializer {

    /**
     * Mod ids we check for, and the human name the registry's conflicts use.
     *
     * <p>Detection is by mod id through Fabric's own loader, not by scanning the mods
     * directory or reading jars. The launcher's mod engine already knows what is
     * installed and why; asking the loader is the same question with a reliable answer.
     */
    private static final String[][] CONFLICT_MOD_IDS = {
        {"optifine", "OptiFine"},
        {"optifabric", "OptiFine"},
        {"patcher", "Patcher"},
        {"polypatcher", "PolyPatcher"},
    };

    @Override
    public void onInitializeClient() {
        // First, before anything this class does can change what is being
        // reported. The point of the block is to describe the process as the
        // loader handed it over, not as Fathom left it.
        net.fathom.optimizations.fabric.diag.RuntimeTrace.print();
        repairLegacyFovCorruption();
        installRendererReloadHook();
        OptimizationRegistry registry = OptimizationRegistry.standard();

        // What the launcher's Performance page wrote, if anything. An absent or
        // unreadable file yields registry defaults rather than an error: the mod has to
        // load when it is installed by hand, without a launcher anywhere.
        LauncherConfig configuration =
                LauncherConfig.fromGameDirectory(
                        FabricLoader.getInstance().getGameDir().toFile());
        SwitchBoard switches = configuration.apply(registry);
        for (String complaint : configuration.complaints()) {
            net.fathom.optimizations.core.module.ModuleLog.say("settings file: " + complaint);
        }

        for (String[] standdown : configuration.standdowns()) {
            Optimization stood = registry.find(standdown[0]);
            net.fathom.optimizations.core.module.ModuleLog.say(
                    "Fathom " + (stood == null ? standdown[0] : stood.name())
                            + " was disabled for compatibility. " + standdown[1]);
        }

        List<String> present = detectConflictingSoftware();
        applyConflicts(registry, switches, present);

        Guard guard =
                new Guard(
                        switches,
                        new Guard.Listener() {
                            @Override
                            public void tripped(Guard.Record record) {
                                // Loudly. A safeguard firing means we were wrong about an
                                // equivalence, which is a bug to fix rather than a
                                // condition to absorb quietly.
                                // Both: standard error so it lands in a crash
                                // report, and the module log so Diagnostics can
                                // show it to somebody who will never open one.
                                String said = "Optimization " + record.id()
                                        + " was switched off after "
                                        + record.failures()
                                        + " failures. First failure: "
                                        + record.firstFailure();
                                System.err.println("[Fathom] " + said);
                                net.fathom.optimizations.core.module.ModuleLog.say(said);
                            }
                        });

        Runtime.install(registry, switches, guard);

        java.io.File gameDirectory = FabricLoader.getInstance().getGameDir().toFile();

        // The launcher bridge first, because the profile name
        // come from it and the HUD wants both. It never throws: no handshake
        // file is the ordinary case for a game started any other way.
        LauncherBridge bridge = new LauncherBridge(gameDirectory);
        Runtime.installBridge(bridge);

        // The feature registry, open for registration until the hub first
        // opens. Third-party features land in this same map through
        // `FathomApi`, which is the API our own features already use.
        final ModuleRegistry features = ModuleRegistry.standard();
        Runtime.installFeatures(features);

        // The HUD comes after the switch board, because a widget can ask whether an
        // optimization is on, and before anything renders. Its own failures are its
        // own: a layout file that cannot be read costs the default layout, not the
        // module.
        final FathomHud hud = new FathomHud(gameDirectory, features);
        for (String complaint : hud.complaints()) {
            net.fathom.optimizations.core.module.ModuleLog.say("settings: " + complaint);
        }
        Runtime.installHud(hud);

        // The live Fathom Profile, applied before anything reads a module's state,
        // including the loop just below that lets a feature drive the optimization
        // it owns. The old properties files are migrated here, once, with a backup.
        hud.attachProfiles(net.fathom.optimizations.fabric.profile.ProfileRuntime.start(
                gameDirectory, bridge.session().root(), hud, switches, registry));

        // Features that own an optimization drive it, so a feature switched off
        // in a stored config does not leave its optimization running. Done once,
        // here, rather than by the hub -- which may never be opened.
        for (FathomModule feature : features.all()) {
            for (String id : feature.optimizations()) {
                switches.setByUser(
                        net.fathom.optimizations.core.registry.OptimizationId.of(id), feature.enabled());
            }
        }

        // The public API. Everything a third-party feature can do goes through
        // here, and there is no second path our own features take.
        FathomApi.install(new FathomApi.Host() {
            @Override
            public FathomApi.Result register(FathomModule feature, Object widgetRenderer, Placement placement) {
                if (features.sealed()) {
                    return FathomApi.Result.TOO_LATE;
                }
                if (widgetRenderer != null && !(widgetRenderer instanceof HudWidget)) {
                    return FathomApi.Result.BAD_WIDGET;
                }
                if (widgetRenderer != null) {
                    HudWidget widget = (HudWidget) widgetRenderer;
                    // Checked before anything is registered, so a refusal leaves nothing half-added.
                    if (!feature.widgets().contains(widget.id()) || hud.registry().contains(widget.id())
                            || hud.renderer(widget.id()) != null) {
                        return FathomApi.Result.BAD_WIDGET;
                    }
                }
                if (!features.register(feature)) {
                    return FathomApi.Result.DUPLICATE;
                }
                if (widgetRenderer != null && !hud.addRenderer((HudWidget) widgetRenderer, feature, placement)) {
                    return FathomApi.Result.BAD_WIDGET;
                }
                // The live profile was applied before this mod registered; give
                // the module, and its widget's place, what that profile says.
                if (hud.profiles() != null) {
                    hud.profiles().adopt(feature);
                }
                return FathomApi.Result.ACCEPTED;
            }

            @Override
            public ModuleRegistry registry() {
                return features;
            }
        });

        // This is the launcher's positive proof that the exact jar it placed
        // was loaded and that Fathom completed client initialization. It is
        // deliberately after the registries, HUD and public API above.
        ClientMarker.write(gameDirectory, features.size());
        // The launcher's live view of the session, when a launcher started it.
        net.fathom.optimizations.fabric.diag.SessionPulse.start(gameDirectory, features);

        net.fathom.optimizations.core.module.ModuleLog.say(
                "" + registry.size() + " optimizations registered, "
                        + switches.enabled().size() + " enabled, "
                        + features.size() + " features, "
                        + hud.registry().size() + " HUD widgets"
                        + (configuration.isPresent() ? " (launcher settings applied)." : "."));
        net.fathom.optimizations.core.module.ModuleLog.say(
                "" + hud.sessionName()
                        + ", launcher bridge " + bridge.state().name().toLowerCase(java.util.Locale.ROOT)
                        + ". Press " + hud.hubKey().keyName() + " in game to open Fathom Hub.");

        // Last, so the probe's totals do not include this method's own work.
        net.fathom.optimizations.fabric.diag.FramePerf.start();
    }

    private static boolean reloadHookInstalled;

    /**
     * Install the reload hook once the game has a resource manager. Called at
     * initialisation and then once a frame until it has taken: the client
     * entrypoint can run before Minecraft creates its resource manager, and a
     * hook that quietly declined then was never installed at all -- so a
     * resource pack change left every widget's cached text width, and the
     * renderer's textures, from the pack before.
     */
    public static void ensureReloadHook() {
        if (!reloadHookInstalled) {
            installRendererReloadHook();
        }
    }

    /** Recreate renderer-owned textures after a resource-pack reload. */
    private static void installRendererReloadHook() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (reloadHookInstalled || client == null
                || !(client.getResourceManager() instanceof ReloadableResourceManager)) {
            return;
        }
        reloadHookInstalled = true;
        System.out.println("[Fathom] resource reload hook installed");
        ((ReloadableResourceManager) client.getResourceManager()).registerListener(new ResourceReloadListener() {
            @Override
            public void reload(ResourceManager manager) {
                net.fathom.optimizations.fabric.gfx.Gfx.dispose();
                net.fathom.optimizations.fabric.hud.TextWidget.fontsReloaded();
                net.fathom.optimizations.fabric.menu.FathomMenuScreen.disposeResources();
            }
        });
    }

    /**
     * An earlier Quick-page slider treated 1.8.9's degree-valued field as a
     * modern 0..1 fraction. Repair only values vanilla itself can never have
     * written; every valid user choice remains byte-for-byte unchanged.
     */
    public static void repairLegacyFovCorruption() {
        MinecraftClient client = MinecraftClient.getInstance();
        if (client == null || client.options == null) {
            return;
        }
        float before = client.options.fov;
        float after = VideoOptionDomain.fov(before);
        if (Float.floatToIntBits(before) == Float.floatToIntBits(after)) {
            return;
        }
        client.options.fov = after;
        client.options.save();
        net.fathom.optimizations.core.module.ModuleLog.say(
                "repaired invalid legacy FOV " + before + " to normal (70 degrees)");
    }

    /** Which conflicting mods Fabric can see. */
    private static List<String> detectConflictingSoftware() {
        List<String> present = new ArrayList<String>();
        for (String[] pair : CONFLICT_MOD_IDS) {
            if (FabricLoader.getInstance().isModLoaded(pair[0]) && !present.contains(pair[1])) {
                present.add(pair[1]);
            }
        }
        return present;
    }

    /**
     * Switch off anything that conflicts with software the user has installed.
     *
     * <p>{@code mod-policies.md} §8: "we must detect OptiFine at load and disable our
     * conflicting patches by default rather than fighting it". The user chose to install
     * the other thing, and it is not our place to overrule that.
     */
    private static void applyConflicts(
            OptimizationRegistry registry, SwitchBoard switches, List<String> present) {
        if (present.isEmpty()) {
            return;
        }
        for (Optimization entry : registry.all()) {
            for (Conflict conflict : entry.conflicts()) {
                if (!present.contains(conflict.with())) {
                    continue;
                }
                if (conflict.resolution() == Conflict.Resolution.DISABLE_OURS) {
                    switches.disableForConflict(entry.id(), conflict.detail());
                }
            }
        }
        net.fathom.optimizations.core.module.ModuleLog.say(
                "Detected " + present + "; conflicting optimizations are off.");
    }
}
