package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.modrinth.pain_o_d.mc_puppet.client.FrameCapture;

import net.minecraft.client.render.GameRenderer;

/**
 * Where a frame begins and ends, so that a capture is of a whole one.
 *
 * <p>{@code render} takes a tick counter in 1.21 and a float and a long before it. A handler
 * that asks for none of the target's arguments fits both, so this mixin is shared.
 */
@Mixin(GameRenderer.class)
public abstract class GameRendererMixin {

    @Inject(method = "render", at = @At("HEAD"), require = 0)
    private void mc_puppet$frameBegins(CallbackInfo info) {
        FrameCapture.frameBegins();
    }

    @Inject(method = "render", at = @At("RETURN"), require = 0)
    private void mc_puppet$frameEnds(CallbackInfo info) {
        FrameCapture.frameEnds();
    }
}
