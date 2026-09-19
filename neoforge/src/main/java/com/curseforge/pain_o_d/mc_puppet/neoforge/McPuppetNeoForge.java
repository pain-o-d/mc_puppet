package com.curseforge.pain_o_d.mc_puppet.neoforge;

import com.curseforge.pain_o_d.mc_puppet.McPuppet;

import net.neoforged.fml.common.Mod;

/** NeoForge entry point. Everything is in common; the client half starts itself from there. */
@Mod(McPuppet.MOD_ID)
public final class McPuppetNeoForge {

    public McPuppetNeoForge() {
        McPuppet.init();
    }
}
