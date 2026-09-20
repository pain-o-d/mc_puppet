package com.modrinth.pain_o_d.mc_puppet.fabric;

import com.modrinth.pain_o_d.mc_puppet.McPuppet;

import net.fabricmc.api.ModInitializer;

/** Fabric entry point for 1.20.1. Everything is in common; the client half starts itself from there. */
public final class McPuppetFabric implements ModInitializer {

    @Override
    public void onInitialize() {
        McPuppet.init();
    }
}
