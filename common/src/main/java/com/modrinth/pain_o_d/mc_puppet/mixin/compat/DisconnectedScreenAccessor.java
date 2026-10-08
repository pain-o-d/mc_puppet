package com.modrinth.pain_o_d.mc_puppet.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.network.DisconnectionInfo;

/**
 * What the disconnect screen says, for {@code client.disconnected} and {@code client.connect_failed}.
 * <b>This file is the 1.21.1 one</b>: there the screen holds a {@code DisconnectionInfo}; on 1.20.1 it
 * holds the {@code Text} itself. Its twin, with the same name, is in {@code mc1.20.1}. Only
 * {@code ClientCompat.disconnectReason} uses it.
 */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {

    @Accessor("info")
    DisconnectionInfo mc_puppet$info();
}
