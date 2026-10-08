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

    /**
     * A game started with {@code -Dmc_puppet.pretend_production=true} has no bridge, so nothing
     * can ask it to quit; say so in the window title (Task 11). Injected into the getter rather
     * than set once on the window, because the game rewrites the title when a world is joined or
     * left. Reads the existing property and nothing else; it only adds words.
     */
    @Inject(method = "getWindowTitle", at = @At("RETURN"), cancellable = true, require = 0)
    private void mc_puppet$saySoInTheTitle(CallbackInfoReturnable<String> info) {
        if (Boolean.getBoolean("mc_puppet.pretend_production")) {
            info.setReturnValue(info.getReturnValue() + " [MC Puppet: pretend_production, no bridge, close by hand]");
        }
    }
}
