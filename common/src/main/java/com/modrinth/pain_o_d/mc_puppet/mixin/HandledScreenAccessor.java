package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.screen.ingame.HandledScreen;

/**
 * Where a container screen drew its panel. A slot knows its place within the
 * panel and the panel keeps its own place to itself, so without this nothing
 * outside can say where on the screen a slot is, or whether a widget a mod
 * added sits on the panel or beside it.
 */
@Mixin(HandledScreen.class)
public interface HandledScreenAccessor {

    @Accessor("x")
    int mc_puppet$x();

    @Accessor("y")
    int mc_puppet$y();

    @Accessor("backgroundWidth")
    int mc_puppet$backgroundWidth();

    @Accessor("backgroundHeight")
    int mc_puppet$backgroundHeight();
}
