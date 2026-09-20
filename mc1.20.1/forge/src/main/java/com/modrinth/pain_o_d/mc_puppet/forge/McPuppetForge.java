package com.modrinth.pain_o_d.mc_puppet.forge;

import com.modrinth.pain_o_d.mc_puppet.McPuppet;

import dev.architectury.platform.forge.EventBuses;

import net.minecraftforge.fml.common.Mod;
import net.minecraftforge.fml.javafmlmod.FMLJavaModLoadingContext;

/** Forge entry point for 1.20.1. Everything is in common; the client half starts itself from there. */
@Mod(McPuppet.MOD_ID)
public final class McPuppetForge {

    public McPuppetForge() {
        // Architectury's events on Forge are raised through the mod's own event bus, and must be told which.
        EventBuses.registerModEventBus(McPuppet.MOD_ID, FMLJavaModLoadingContext.get().getModEventBus());
        McPuppet.init();
    }
}
