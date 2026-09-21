package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.modrinth.pain_o_d.mc_puppet.client.VirtualKeys;

import net.minecraft.client.util.InputUtil;

/**
 * Lets a key be held that nobody is holding.
 *
 * <p>Whether shift is down is not part of a click. The game asks the keyboard
 * when it needs to know, so a shift-click sent through the real input path
 * arrived as a plain click. While a test holds a key, the question is
 * answered yes. The set is empty unless a test is holding something, and
 * then this is one lookup; optional, so a game where it cannot apply still
 * starts.
 */
@Mixin(InputUtil.class)
public abstract class InputUtilMixin {

    @Inject(method = "isKeyPressed", at = @At("HEAD"), cancellable = true, require = 0)
    private static void mc_puppet$heldByATest(long handle, int code, CallbackInfoReturnable<Boolean> info) {
        if (VirtualKeys.isHeld(code)) {
            info.setReturnValue(true);
        }
    }
}
