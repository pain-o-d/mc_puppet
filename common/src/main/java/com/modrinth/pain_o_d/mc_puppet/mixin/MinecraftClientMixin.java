package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.modrinth.pain_o_d.mc_puppet.client.VirtualFocus;

import net.minecraft.client.MinecraftClient;

/**
 * The window has focus while a test drags the mouse. See {@link VirtualFocus}.
 * Optional, so a game where it cannot apply still starts.
 */
@Mixin(MinecraftClient.class)
public abstract class MinecraftClientMixin {

    @Inject(method = "isWindowFocused", at = @At("HEAD"), cancellable = true, require = 0)
    private void mc_puppet$focusedForATest(CallbackInfoReturnable<Boolean> info) {
        if (VirtualFocus.on()) {
            info.setReturnValue(true);
        }
    }
}
