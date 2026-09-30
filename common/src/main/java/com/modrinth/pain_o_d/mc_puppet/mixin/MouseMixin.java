package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Final;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.Shadow;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.modrinth.pain_o_d.mc_puppet.client.VirtualFocus;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.Mouse;

/**
 * The cursor is grabbed, with no screen open, while a test drags the mouse,
 * so that its movement turns the head (and whatever a mod hooks there hears
 * it). The real cursor is not: grabbing it is what a window that does not
 * have focus must not do. See {@link VirtualFocus}. Optional, as the others.
 */
@Mixin(Mouse.class)
public abstract class MouseMixin {

    @Shadow
    @Final
    private MinecraftClient client;

    @Inject(method = "isCursorLocked", at = @At("HEAD"), cancellable = true, require = 0)
    private void mc_puppet$grabbedForATest(CallbackInfoReturnable<Boolean> info) {
        if (VirtualFocus.on() && client.currentScreen == null) {
            info.setReturnValue(true);
        }
    }

    @Inject(method = "lockCursor", at = @At("HEAD"), cancellable = true, require = 0)
    private void mc_puppet$notTheRealCursor(CallbackInfo info) {
        if (VirtualFocus.on() && !((MinecraftClientInvoker) client).mc_puppet$windowFocused()) {
            info.cancel();
        }
    }
}
