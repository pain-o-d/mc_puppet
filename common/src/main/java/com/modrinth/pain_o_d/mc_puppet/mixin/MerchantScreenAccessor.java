package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screen.ingame.MerchantScreen;

/** Which offer a trading screen has selected, to read it and to set it as a click on the list does. */
@Mixin(MerchantScreen.class)
public interface MerchantScreenAccessor {

    @Accessor("selectedIndex")
    int mc_puppet$selectedIndex();

    @Accessor("selectedIndex")
    void mc_puppet$setSelectedIndex(int index);
}
