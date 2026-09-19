package com.curseforge.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.curseforge.pain_o_d.mc_puppet.client.FrameCapture;

import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.screen.Screen;

/**
 * Where in a frame the screen is drawn: after the HUD, which it covers, and
 * before toasts, which cover it. What overlaps what only means something
 * within one of those.
 */
@Mixin(Screen.class)
public abstract class ScreenMixin {

    @Inject(method = "renderWithTooltip", at = @At("HEAD"), require = 0)
    private void mc_puppet$screenBegins(DrawContext context, int mouseX, int mouseY, float delta,
                                        CallbackInfo info) {
        if (FrameCapture.active()) {
            FrameCapture.screenBegins();
        }
    }

    @Inject(method = "renderWithTooltip", at = @At("RETURN"), require = 0)
    private void mc_puppet$screenEnds(DrawContext context, int mouseX, int mouseY, float delta,
                                      CallbackInfo info) {
        if (FrameCapture.active()) {
            FrameCapture.screenEnds();
        }
    }
}
