package net.fathom.optimizations.fabric.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.InGameHud;

import net.fathom.optimizations.fabric.Runtime;
import net.fathom.optimizations.fabric.hud.FathomHud;

/**
 * Draws Fathom's HUD.
 *
 * <h2>Where in vanilla's HUD it goes</h2>
 *
 * <p>After the hotbar, the bars, the debug screen and the scoreboard, and
 * before the chat and the player list. Vanilla's own HUD has to be underneath
 * the widgets — a hotbar drawn over a widget that overlaps it is the sort of
 * thing that only shows up in the one place a widget happens to sit — but the
 * chat and the player list are things a player asks for, and have to be on
 * top. The widgets used to be drawn at the very end, so holding Tab in a
 * minigame showed the clock, the ping and the potions over the names it was
 * held to read.
 *
 * <p>The chat is drawn inside a {@code translate(0, height - 48)}; the injection
 * point is the chat call itself, so the pass undoes that translation for its
 * own drawing and puts back the state vanilla had set up for the chat.
 *
 * <h2>Why the editor is checked here</h2>
 *
 * <p>The editor draws the widgets itself, over the world, so it can show
 * placeholders and hit boxes. This injector skips its own pass while the
 * editor is current — otherwise every widget would be drawn twice.
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
        net.fathom.optimizations.fabric.hud.ScoreboardWidget.frame();
        if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.ACTIVE) {
            fathom$wholeHud = net.fathom.optimizations.fabric.diag.ModuleSelfTest.wholeHudMark();
        }
        if (net.fathom.optimizations.fabric.gfx.WorldBackdrop.pending()) {
            net.fathom.optimizations.fabric.gfx.WorldBackdrop.captureIfRequested(MinecraftClient.getInstance());
        }
    }

    @Inject(method = "render(F)V",
            at = @At(value = "INVOKE", target = "Lnet/minecraft/client/gui/hud/ChatHud;render(I)V"))
    private void fathom$drawHud(float tickDelta, CallbackInfo info) {
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            net.fathom.optimizations.core.bench.view.ViewProbe.enter(net.fathom.optimizations.core.bench.view.ViewSection.FATHOM_HUD);
        }
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        boolean undone = false;
        // QA only: what this pass leaves changed, and what it allocates.
        java.util.Map<String, String> glBefore = null;
        java.util.List<String> desyncBefore = null;
        long allocation = -1L;
        if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.ACTIVE) {
            if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.glChecking()) {
                glBefore = net.fathom.optimizations.fabric.diag.GlLeakCheck.snapshot();
                desyncBefore = net.fathom.optimizations.fabric.diag.GlLeakCheck.desync();
            }
            allocation = net.fathom.optimizations.fabric.diag.ModuleSelfTest.allocationMark();
        }
        try {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.MIXIN_CALLBACK);
            if (!fathom$hudPassAllowed()) return;
            FathomHud hud = Runtime.hud();
            MinecraftClient client = MinecraftClient.getInstance();
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.count(
                    net.fathom.optimizations.core.bench.auto.RenderCounters.Counter.HUD_PASS);
            // Vanilla has translated down to the chat's baseline; the widgets
            // are placed from the screen's top-left.
            int height = net.fathom.optimizations.fabric.gfx.MinecraftGuiSize.get(client).height();
            GlStateManager.pushMatrix();
            undone = true;
            GlStateManager.translate(0f, -(height - 48), 0f);
            hud.render(client, false);
        } finally {
            if (undone) {
                GlStateManager.popMatrix();
                // What vanilla set for the chat, just before this call.
                GlStateManager.enableBlend();
                GlStateManager.blendFuncSeparate(770, 771, 1, 0);
                GlStateManager.disableAlphaTest();
                GlStateManager.color(1f, 1f, 1f, 1f);
            }
            if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.ACTIVE) {
                net.fathom.optimizations.fabric.diag.ModuleSelfTest.allocationDone(allocation);
                if (glBefore != null) {
                    net.fathom.optimizations.fabric.diag.ModuleSelfTest.glChecked("hud", glBefore, desyncBefore);
                }
            }
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, probe);
            if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
                net.fathom.optimizations.core.bench.view.ViewProbe.exit(net.fathom.optimizations.core.bench.view.ViewSection.FATHOM_HUD);
            }
        }
    }

    /** QA only: the thread's allocation when this frame's HUD began. */
    @org.spongepowered.asm.mixin.Unique
    private long fathom$wholeHud = -1L;

    @Inject(method = "render(F)V", at = @At("RETURN"))
    private void fathom$afterHud(float tickDelta, CallbackInfo info) {
        if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.ACTIVE) {
            net.fathom.optimizations.fabric.diag.ModuleSelfTest.wholeHudDone(fathom$wholeHud);
            fathom$wholeHud = -1L;
        }
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            if (!fathom$hudPassAllowed()) return;
            MinecraftClient client = MinecraftClient.getInstance();
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
                // InGameHud.render returns right after this: the vanilla HUD ends here too.
                net.fathom.optimizations.core.bench.view.ViewProbe.exit(net.fathom.optimizations.core.bench.view.ViewSection.HUD);
            }
        }
    }

    /** Whether Fathom's HUD is drawn this frame at all. */
    private static boolean fathom$hudPassAllowed() {
        if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()) return false;
        if (Runtime.hud() == null) return false;
        MinecraftClient client = MinecraftClient.getInstance();
        // F1 hides the HUD. A HUD Fathom kept drawing through F1 would be a
        // HUD nobody could take a screenshot without.
        if (client == null || !MinecraftClient.isHudEnabled()) return false;
        if (client.currentScreen instanceof net.fathom.optimizations.fabric.hud.HudEditorScreen) return false;
        // Nor under the Hub: it is transparent now, and widgets drawn under
        // its header and rail read as clutter on top of it. A module's own
        // page previews its widget.
        if (client.currentScreen instanceof net.fathom.optimizations.fabric.hub.FathomHubScreen) return false;
        // The benchmark's minimal pass switches the whole HUD off here rather
        // than switching eleven widgets off individually: the question it
        // answers is what the pass costs, including the frame it opens.
        return net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowHudPass();
    }
}
