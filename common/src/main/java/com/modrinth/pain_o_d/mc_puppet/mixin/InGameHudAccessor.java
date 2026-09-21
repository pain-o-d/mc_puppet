package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.hud.InGameHud;
import net.minecraft.text.Text;

/** What the HUD is showing now, which it keeps to itself. */
@Mixin(InGameHud.class)
public interface InGameHudAccessor {

    @Accessor("overlayMessage")
    Text mc_puppet$overlayMessage();

    @Accessor("overlayRemaining")
    int mc_puppet$overlayRemaining();

    @Accessor("title")
    Text mc_puppet$title();

    @Accessor("subtitle")
    Text mc_puppet$subtitle();

    @Accessor("titleRemainTicks")
    int mc_puppet$titleRemainTicks();
}
