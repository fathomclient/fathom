package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.hud.FathomHud;

/**
 * Draws Fathom's HUD.
 *
 * <h2>Why {@code RETURN} and not {@code HEAD}</h2>
 *
 * <p>Vanilla's HUD has to be underneath. Drawing first would put the hotbar and the health
 * bar on top of anything overlapping them, which is both wrong and the sort of thing that
 * only shows up in the one place a widget happens to overlap.
 *
 * <h2>Why the editor is checked here</h2>
 *
 * <p>{@code InGameHud.render} is not called while a screen is open, so a HUD editor that
 * relied on this hook would have nothing to arrange. The editor therefore calls
 * {@link FathomHud#render} itself, and this injector skips its own call when the editor is
 * the current screen — otherwise every widget would be drawn twice, and the second copy
 * would be at a different scale.
 */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    /**
     * The world is drawn and no HUD is: the one moment a menu opening over the
     * world can capture it clean. One boolean read unless a menu asked.
     */
    @Inject(method = "render(F)V", at = @At("HEAD"))
    private void fathom$captureBackdrop(float tickDelta, CallbackInfo info) {
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            net.fathom.optimizations.core.bench.view.ViewProbe.enter(net.fathom.optimizations.core.bench.view.ViewSection.HUD);
        }
        if (net.fathom.optimizations.fabric.gfx.WorldBackdrop.pending()) {
            net.fathom.optimizations.fabric.gfx.WorldBackdrop.captureIfRequested(MinecraftClient.getInstance());
        }
    }

    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void fathom$drawHud(float tickDelta, CallbackInfo info) {
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            net.fathom.optimizations.core.bench.view.ViewProbe.enter(net.fathom.optimizations.core.bench.view.ViewSection.FATHOM_HUD);
        }
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
            if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()) return;
            FathomHud hud = Runtime.hud();
            if (hud == null) return;
            MinecraftClient client = MinecraftClient.getInstance();
            if (client == null || !MinecraftClient.isHudEnabled()) return;
            // F1 hides the HUD. A HUD Fathom kept drawing through F1 would be a
            // HUD nobody could take a screenshot without.
            if (client.currentScreen instanceof net.fathom.optimizations.fabric.hud.HudEditorScreen) return;
            // Nor under the Hub: it is transparent now, and widgets drawn under
            // its header and rail read as clutter on top of it. A module's own
            // page previews its widget.
            if (client.currentScreen instanceof net.fathom.optimizations.fabric.hub.FathomHubScreen) return;
        // The benchmark's minimal pass switches the whole HUD off here rather
        // than switching eleven widgets off individually: the question it
        // answers is what the pass costs, including the frame it opens.
            if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowHudPass()) return;
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.HUD_PASS);
            hud.render(client, false);
            if (net.fathom.optimizations.fabric.perf.PerfLayer.stats) {
                net.fathom.optimizations.fabric.perf.PerfOverlay.draw(client,
                        net.fathom.optimizations.fabric.gfx.MinecraftGuiSize.get(client).height());
            }
            net.fathom.optimizations.fabric.hud.HudCapture.frame(client);
        } finally {
            // A HUD-cache sample takes its immediate checkpoint inside
            // HudLayerCache. This is the third checkpoint: after the rest of
            // FathomHud and the surrounding InGameHud method have completed.
            net.fathom.optimizations.fabric.gfx.GlStateAudit.finish(
                    net.fathom.optimizations.fabric.gfx.GlStateAudit.HUD_CACHE);
            net.fathom.optimizations.fabric.gfx.GlStateAudit.finish(
                    net.fathom.optimizations.fabric.gfx.GlStateAudit.HUD_STEADY);
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, probe);
            if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
                net.fathom.optimizations.core.bench.view.ViewProbe.exit(net.fathom.optimizations.core.bench.view.ViewSection.FATHOM_HUD);
                // InGameHud.render returns right after this: the vanilla HUD ends here too.
                net.fathom.optimizations.core.bench.view.ViewProbe.exit(net.fathom.optimizations.core.bench.view.ViewSection.HUD);
            }
        }
    }
}
