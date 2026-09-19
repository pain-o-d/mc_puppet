package com.curseforge.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.curseforge.pain_o_d.mc_puppet.client.FrameCapture;

import net.minecraft.client.render.GameRenderer;
import net.minecraft.client.render.RenderTickCounter;

/** Where a frame begins and ends, so that a capture is of a whole one. */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void mc_puppet$frameBegins(RenderTickCounter counter, boolean tick, CallbackInfo info) {
        FrameCapture.frameBegins();
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void mc_puppet$frameEnds(RenderTickCounter counter, boolean tick, CallbackInfo info) {
        FrameCapture.frameEnds();
    }
}
