package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.modrinth.pain_o_d.mc_puppet.core.CrashEvents;

import net.minecraft.util.crash.CrashReport;

/**
 * The game wrote a crash report: said on the events bus, on both sides, from the one place both
 * write through. Takes no arguments of its own, so it fits whatever overloads a version has.
 * Optional, so a game where it cannot apply still starts.
 */
@Mixin(CrashReport.class)
public abstract class CrashReportMixin {

    @Inject(method = "writeToFile", at = @At("RETURN"), require = 0)
    private void mc_puppet$saved(CallbackInfoReturnable<Boolean> info) {
        try {
            CrashReport report = (CrashReport) (Object) this;
            CrashEvents.saved(info.getReturnValueZ() ? String.valueOf(report.getFile()) : null, report.getCause());
        } catch (RuntimeException | LinkageError ignored) {
            // A report about a crash must not become a second one.
        }
    }
}
