package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.modrinth.pain_o_d.mc_puppet.core.EventLog;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;

/** The action bar and titles, written down as they are set: each is gone in seconds. */
@Mixin(InGameHud.class)
public abstract class InGameHudMixin {

    @Inject(method = "setOverlayMessage", at = @At("HEAD"), require = 0)
    private void mc_puppet$actionBar(Text message, boolean tinted, CallbackInfo info) {
        if (message != null) {
            EventLog.CLIENT.add("actionbar", message.getString());
        }
    }

    @Inject(method = "setTitle", at = @At("HEAD"), require = 0)
    private void mc_puppet$title(Text title, CallbackInfo info) {
        if (title != null) {
            EventLog.CLIENT.add("title", title.getString());
        }
    }

    @Inject(method = "setSubtitle", at = @At("HEAD"), require = 0)
    private void mc_puppet$subtitle(Text subtitle, CallbackInfo info) {
        if (subtitle != null) {
            EventLog.CLIENT.add("subtitle", subtitle.getString());
        }
    }
}
