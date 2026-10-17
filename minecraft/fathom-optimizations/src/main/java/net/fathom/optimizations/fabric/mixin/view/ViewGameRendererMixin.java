package net.fathom.optimizations.fabric.mixin.view;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import net.minecraft.client.render.GameRenderer;

import net.fathom.optimizations.core.bench.view.ViewProbe;
import net.fathom.optimizations.core.bench.view.ViewSection;

/** View benchmark only: the frame's render, its world pass, weather and the hand. */
@Mixin(GameRenderer.class)
public abstract class ViewGameRendererMixin {

    @Inject(method = "render(FJ)V", at = @At("HEAD"), require = 0)
    private void fathom$viewRenderStart(float tickDelta, long limit, CallbackInfo info) {
        ViewProbe.enter(ViewSection.RENDER);
    }

    @Inject(method = "render(FJ)V", at = @At("RETURN"), require = 0)
    private void fathom$viewRenderEnd(float tickDelta, long limit, CallbackInfo info) {
        ViewProbe.exit(ViewSection.RENDER);
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("HEAD"), require = 0)
    private void fathom$viewWorldStart(float tickDelta, long limit, CallbackInfo info) {
        ViewProbe.enter(ViewSection.WORLD);
    }

    @Inject(method = "renderWorld(FJ)V", at = @At("RETURN"), require = 0)
    private void fathom$viewWorldEnd(float tickDelta, long limit, CallbackInfo info) {
        ViewProbe.exit(ViewSection.WORLD);
    }

    @Inject(method = "renderWeather(F)V", at = @At("HEAD"), require = 0)
    private void fathom$viewWeatherStart(float tickDelta, CallbackInfo info) {
        ViewProbe.enter(ViewSection.WEATHER);
    }

    @Inject(method = "renderWeather(F)V", at = @At("RETURN"), require = 0)
    private void fathom$viewWeatherEnd(float tickDelta, CallbackInfo info) {
        ViewProbe.exit(ViewSection.WEATHER);
    }

    @Inject(method = "renderHand(FI)V", at = @At("HEAD"), require = 0)
    private void fathom$viewHandStart(float tickDelta, int anaglyph, CallbackInfo info) {
        ViewProbe.enter(ViewSection.HAND);
    }

    @Inject(method = "renderHand(FI)V", at = @At("RETURN"), require = 0)
    private void fathom$viewHandEnd(float tickDelta, int anaglyph, CallbackInfo info) {
        ViewProbe.exit(ViewSection.HAND);
    }

    @Inject(method = "tick()V", at = @At("HEAD"), require = 0)
    private void fathom$viewTickStart(CallbackInfo info) {
        ViewProbe.enter(ViewSection.TICK_RENDERERS);
    }

    @Inject(method = "tick()V", at = @At("RETURN"), require = 0)
    private void fathom$viewTickEnd(CallbackInfo info) {
        ViewProbe.exit(ViewSection.TICK_RENDERERS);
    }
}
