package com.modrinth.pain_o_d.mc_puppet.mixin.compat;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.text.Text;

/**
 * What the disconnect screen says, for {@code client.disconnected} and {@code client.connect_failed}.
 * <b>This file is the 1.20.1 one</b>: there the screen holds the {@code Text} itself; on 1.21.1 it
 * holds a {@code DisconnectionInfo}. Its twin, with the same name, is in the root build. Only
 * {@code ClientCompat.disconnectReason} uses it.
 */
@Mixin(DisconnectedScreen.class)
public interface DisconnectedScreenAccessor {

    @Accessor("reason")
    Text mc_puppet$reason();
}
