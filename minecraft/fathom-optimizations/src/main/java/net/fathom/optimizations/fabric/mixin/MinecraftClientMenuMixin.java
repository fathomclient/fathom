package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.ModifyVariable;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;

import net.fathom.optimizations.fabric.menu.FathomMenuScreen;
import net.fathom.optimizations.fabric.menu.MenuTakeover;

/**
 * Fathom's main menu stands in for vanilla's.
 *
 * <h2>Why the argument and not the method body</h2>
 *
 * <p>{@code setScreen} is called from a dozen places — start-up, disconnecting,
 * leaving a world, a failed join — and every one of them constructs the screen
 * itself. Injecting at {@code HEAD} and calling {@code setScreen} again from
 * inside {@code setScreen} is a recursion waiting to be triggered by whichever
 * of those paths nobody thought about.
 *
 * <p>Replacing the argument has none of that. Vanilla's own method runs exactly
 * once, with a different screen than it was handed, and every caller keeps
 * working without knowing.
 *
 * <h2>The null case, which is the one that actually bit</h2>
 *
 * <p>{@code setScreen(null)} does not mean "no screen" when there is no world.
 * Vanilla substitutes a title screen for it, in the method body, at bytecode
 * offset 25 — <em>after</em> {@code HEAD}. So closing any screen from the main
 * menu produced vanilla's title screen and this Mixin never saw it: the
 * argument it was handed was null, and by the time a {@code TitleScreen}
 * existed the injection point was long past.
 *
 * <p>Observed, not reasoned about: pressing the Fathom button opened the Hub,
 * something closed it a frame later, and the game landed on a grey vanilla menu
 * with a different splash. Replacing the null here pre-empts the substitution —
 * vanilla's {@code if (screen == null && world == null)} is simply no longer
 * true — which is one line and covers every caller rather than the one that was
 * caught.
 *
 * <h2>Why the check is on the exact class</h2>
 *
 * <p>{@code getClass() == TitleScreen.class}, not {@code instanceof}. Another
 * mod may subclass the title screen to add its own button; swapping that for
 * ours would delete their work and look like a crash to them. Ours is not a
 * {@code TitleScreen} subclass at all, so it cannot match itself either.
 *
 * <h2>Why it is switchable</h2>
 *
 * <p>A client that replaces the first screen of the game has to be able to stop
 * doing that. The module owns the behaviour, the module can be switched off, and
 * off means vanilla's screen — unchanged, because nothing was patched into it.
 *
 * <h2>When this is too early</h2>
 *
 * <p>The first title screen can arrive before Fathom's runtime is installed
 * (seen with OptiFine through OptiFabric in a packaged game). This Mixin then
 * has nothing to ask and leaves vanilla's screen; {@link MenuTakeover} replaces
 * it on the first frame after Fathom is ready.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMenuMixin {

    @ModifyVariable(method = "setScreen(Lnet/minecraft/client/gui/screen/Screen;)V", at = @At("HEAD"), argsOnly = true)
    private Screen fathom$ownMenu(Screen screen) {
        net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.OPTIFINE_OVERLAP_HOOK);
        if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowCoreHooks()
                || !net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowOptifineOverlapHooks()) {
            return screen;
        }
        // The saved main-menu toggle, and visual QA asking for the menu.
        if (!MenuTakeover.owned()) {
            if (!MenuTakeover.ready() && FathomMenuScreen.shouldReplace(screen)) {
                // Not an error: MenuTakeover replaces it on the first frame
                // after Fathom finishes loading. Logged so a log reader sees why.
                net.fathom.optimizations.core.module.ModuleLog.say(
                        "main menu: title screen shown before Fathom finished loading; replacing it next frame.");
            }
            return screen;
        }
        // Vanilla is about to turn this null into a title screen, because there
        // is no world to go back to. Ours instead.
        if (screen == null) {
            MinecraftClient client = (MinecraftClient) (Object) this;
            return client.world == null ? new FathomMenuScreen() : screen;
        }
        return FathomMenuScreen.shouldReplace(screen) ? new FathomMenuScreen() : screen;
    }
}
