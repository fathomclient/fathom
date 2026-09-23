package net.fathom.optimizations.fabric.mixin;

import java.util.List;
import java.util.Set;

import org.objectweb.asm.tree.ClassNode;
import org.spongepowered.asm.mixin.extensibility.IMixinConfigPlugin;
import org.spongepowered.asm.mixin.extensibility.IMixinInfo;

import net.fabricmc.loader.api.FabricLoader;

/**
 * Decides which of Fathom's Mixins may apply in this installation.
 *
 * <h2>The problem this exists to solve</h2>
 *
 * <p>Three of Fathom's optimization Mixins redirect {@code Direction.values()}
 * inside methods that OptiFine rewrites wholesale. With OptiFine present, the
 * method the redirect was written against no longer exists, the injector finds
 * nothing, and because {@code fathom.mixins.json} sets
 * {@code injectors.defaultRequire} to 1 the game <em>crashes at class load</em>:
 *
 * <pre>
 * RuntimeException: Mixin transformation of net.minecraft.class_530 failed
 * Caused by: InjectionError: Critical injection failure: Redirector
 *   fathom$sharedDirections() in WorldRendererDirectionsMixin failed injection
 *   check, (0/1) succeeded. Scanned 0 target(s).
 * </pre>
 *
 * <p>That is a real crash, reproduced on Legacy Fabric 0.19.3 with Legacy
 * OptiFabric 2.3.6 and OptiFine 1.8.9 HD U M5.
 *
 * <h2>Why the runtime switch could not have prevented it</h2>
 *
 * <p>Because it is answering a different question at a later time. The
 * optimization catalogue has said since it was written that "Fathom's
 * conflicting patches are turned off when OptiFine is present rather than
 * fighting it", and {@code SwitchBoard} does exactly that — at runtime, by
 * making the redirected code path return the shared array or the fresh one.
 *
 * <p>But a Mixin is applied when the target class is <em>loaded</em>, long
 * before any switch is consulted, and an injector that finds no target fails
 * then. A switch cannot turn off an injection that has already failed to apply.
 * So the decision has to move to the only place that runs early enough, which is
 * here.
 *
 * <h2>Why this does not reintroduce silent failure</h2>
 *
 * <p>{@code defaultRequire} is deliberately 1, and
 * {@code MinecraftClientFrameMixin} documents at length what it cost to have it
 * at 0: a Mixin sat broken for a milestone and nothing said so. Nothing here
 * changes that. Every Mixin still has to apply, and a failure is still a crash —
 * <em>except</em> for the specific, named, individually justified set below,
 * skipped only when the thing they conflict with is actually installed.
 *
 * <p>A Mixin that is not on this list and conflicts with OptiFine anyway will
 * still crash the game, which is correct: that is a conflict nobody has
 * characterised yet, and finding out loudly is the point.
 */
public final class FathomMixinPlugin implements IMixinConfigPlugin {

    /**
     * Mixins that must not apply when OptiFine is being patched in.
     *
     * <p>Simple class names, matched against the tail of the Mixin class name.
     * Each entry is a method OptiFine replaces:
     *
     * <ul>
     *   <li>{@code WorldRendererDirectionsMixin} — redirects inside
     *       {@code WorldRenderer.setupTerrain}, which is the terrain visibility
     *       search OptiFine replaces to implement Render Regions. **Confirmed**
     *       crash; this is the one that was reproduced.
     *   <li>{@code ChunkVisibilityDirectionsMixin} — redirects inside
     *       {@code ChunkOcclusionDataBuilder.getOpenFaces}, the other half of
     *       the same visibility system, rewritten by the same OptiFine feature.
     *   <li>{@code ClientChunkProviderMixin} — caches chunk lookups.
     *       OptiFine has its own chunk-lookup caching, so this is both a
     *       likely structural conflict and redundant work if it applied.
     * </ul>
     *
     * <p>{@code WorldDirectionsMixin} is deliberately <em>not</em> here: it
     * targets {@code World}, which OptiFine does not rewrite for this purpose,
     * and skipping a Mixin that would have worked costs real performance. If it
     * turns out to conflict it will crash and be added, with a note saying so —
     * that is a better outcome than disabling it on a guess.
     */
    private static final String[] INCOMPATIBLE_WITH_OPTIFINE = {
        "WorldRendererDirectionsMixin",
        "ChunkVisibilityDirectionsMixin",
        "ClientChunkProviderMixin",
    };

    /**
     * Whether OptiFine is being patched into this game.
     *
     * <p>Asked of OptiFabric rather than of OptiFine, because OptiFine is not a
     * mod the loader knows about — it is a jar OptiFabric reads and patches in.
     * OptiFabric's own load guard refuses to start without one, so "OptiFabric
     * is loaded" and "OptiFine is present" are the same fact by the time any
     * class is transformed.
     *
     * <p>Resolved once and held: this is asked for every Mixin against every
     * target class, and the answer cannot change within a run.
     */
    private static final boolean OPTIFINE_PRESENT = detectOptiFine();

    private static boolean detectOptiFine() {
        try {
            return FabricLoader.getInstance().isModLoaded("optifabric");
        } catch (Throwable unavailable) {
            // Never let the decision itself be the thing that breaks loading.
            // Answering "no" applies every Mixin, which is the behaviour this
            // client had before OptiFine was supported at all.
            return false;
        }
    }

    /** Whether OptiFine is present, for the parts of the client that report it. */
    public static boolean optiFinePresent() {
        return OPTIFINE_PRESENT;
    }

    @Override
    public void onLoad(String mixinPackage) {
        if (OPTIFINE_PRESENT) {
            System.out.println(
                    "[Fathom] OptiFine detected through OptiFabric. "
                            + "Fathom's overlapping renderer patches will not be applied; "
                            + "OptiFine owns those paths.");
        }
    }

    @Override
    public String getRefMapperConfig() {
        return null;
    }

    @Override
    public boolean shouldApplyMixin(String targetClassName, String mixinClassName) {
        if (!OPTIFINE_PRESENT) {
            return true;
        }
        for (String incompatible : INCOMPATIBLE_WITH_OPTIFINE) {
            if (mixinClassName.endsWith(incompatible)) {
                System.out.println(
                        "[Fathom] Skipping " + incompatible + ": OptiFine rewrites its target.");
                return false;
            }
        }
        return true;
    }

    @Override
    public void acceptTargets(Set<String> myTargets, Set<String> otherTargets) {}

    @Override
    public List<String> getMixins() {
        return null;
    }

    @Override
    public void preApply(
            String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}

    @Override
    public void postApply(
            String targetClassName, ClassNode targetClass, String mixinClassName, IMixinInfo info) {}
}
