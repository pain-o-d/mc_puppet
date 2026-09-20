package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;

import com.modrinth.pain_o_d.mc_puppet.client.FrameCapture;
import com.modrinth.pain_o_d.mc_puppet.core.EventLog;
import com.google.gson.JsonObject;

import net.minecraft.client.toast.SystemToast;
import net.minecraft.client.toast.Toast;
import net.minecraft.client.toast.ToastManager;

/** A toast, written down as it is queued: it slides away in five seconds. */
@Mixin(ToastManager.class)
public abstract class ToastManagerMixin {

    @Inject(method = "add", at = @At("HEAD"), require = 0)
    private void mc_puppet$toast(Toast toast, CallbackInfo info) {
        JsonObject more = new JsonObject();
        more.addProperty("class", toast.getClass().getName());
        String text = "";
        if (toast instanceof SystemToast) {
            SystemToastAccessor system = (SystemToastAccessor) toast;
            StringBuilder said = new StringBuilder(system.mc_puppet$title().getString());
            system.mc_puppet$lines().forEach(line -> said.append('\n').append(FrameCapture.plain(line)));
            text = said.toString();
        }
        EventLog.CLIENT.add("toast", text, more);
    }
}
