package net.fathom.optimizations.fabric.hud;

import com.mojang.blaze3d.platform.GlStateManager;

import net.minecraft.client.MinecraftClient;

import net.fathom.optimizations.fabric.Runtime;

/**
 * Fathom's HUD pass, called from wherever the HUD is drawn.
 *
 * <p>On Fabric that is vanilla's {@code InGameHud.render}; under Forge the HUD
 * is {@code GuiIngameForge}'s own method, which never calls vanilla's, so the
 * same four moments are hooked there instead. Both sets of Mixins call these
 * methods and nothing else, so the two loaders draw one HUD.
 */
public final class HudHooks {

    /** QA only: the thread's allocation when this frame's HUD began. */
    private static long wholeHud = -1L;

    private HudHooks() {}

    /** The HUD method has started: nothing of the HUD is drawn yet. */
    public static void begin() {
        if (net.fathom.optimizations.core.bench.view.ViewProbe.REQUESTED) {
            net.fathom.optimizations.core.bench.view.ViewProbe.enter(net.fathom.optimizations.core.bench.view.ViewSection.HUD);
        }
        net.fathom.optimizations.fabric.hud.ScoreboardWidget.frame();
        if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.ACTIVE) {
            wholeHud = net.fathom.optimizations.fabric.diag.ModuleSelfTest.wholeHudMark();
        }
        if (net.fathom.optimizations.fabric.gfx.WorldBackdrop.pending()) {
            net.fathom.optimizations.fabric.gfx.WorldBackdrop.captureIfRequested(MinecraftClient.getInstance());
        }
    }

    /**
     * Just before the chat, inside the chat's {@code translate(0, height - 48)}:
     * Fathom's widgets go over vanilla's HUD and under the chat and player list.
     */
    public static void widgets() {
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
            if (!passAllowed()) return;
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

    /** Just after the crosshair would have been decided: Fathom's crosshair, where vanilla's goes. */
    public static void crosshair() {
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            if (!net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowRenderHooks()
                    || !net.fathom.optimizations.fabric.bench.auto.BenchmarkState.allowHudPass()) return;
            net.fathom.optimizations.fabric.hud.FathomHud hud = Runtime.hud();
            if (hud == null) return;
            net.minecraft.client.MinecraftClient client = net.minecraft.client.MinecraftClient.getInstance();
            if (client == null || !net.minecraft.client.MinecraftClient.isHudEnabled()) return;
            if (client.currentScreen instanceof net.fathom.optimizations.fabric.hud.HudEditorScreen
                    || client.currentScreen instanceof net.fathom.optimizations.fabric.hub.FathomHubScreen) return;
            java.util.Map<String, String> glBefore = null;
            java.util.List<String> desyncBefore = null;
            if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.glChecking()) {
                glBefore = net.fathom.optimizations.fabric.diag.GlLeakCheck.snapshot();
                desyncBefore = net.fathom.optimizations.fabric.diag.GlLeakCheck.desync();
            }
            boolean drew = hud.renderCrosshair(client);
            if (drew) {
                // Put back what the rest of vanilla's HUD expects: its icon
                // sheet bound, blending on, an untinted colour.
                com.mojang.blaze3d.platform.GlStateManager.enableTexture();
                com.mojang.blaze3d.platform.GlStateManager.color(1f, 1f, 1f, 1f);
                client.getTextureManager().bindTexture(net.minecraft.client.gui.DrawableHelper.GUI_ICONS_TEXTURE);
                com.mojang.blaze3d.platform.GlStateManager.enableBlend();
            }
            if (glBefore != null && drew) {
                net.fathom.optimizations.fabric.diag.ModuleSelfTest.glChecked("crosshair", glBefore, desyncBefore);
            }
        } finally {
            net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStop(
                    net.fathom.optimizations.core.instrument.Probe.Section.MIXIN_CALLBACKS, probe);
        }
    }

    /** The HUD method is returning. */
    public static void end() {
        if (net.fathom.optimizations.fabric.diag.ModuleSelfTest.ACTIVE) {
            net.fathom.optimizations.fabric.diag.ModuleSelfTest.wholeHudDone(wholeHud);
            wholeHud = -1L;
        }
        long probe = net.fathom.optimizations.fabric.bench.auto.BenchmarkState.profileStart();
        try {
            if (!passAllowed()) return;
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
    public static boolean passAllowed() {
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
