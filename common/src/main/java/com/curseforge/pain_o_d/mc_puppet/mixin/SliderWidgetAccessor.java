package com.curseforge.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.gui.widget.SliderWidget;

@Mixin(SliderWidget.class)
public interface SliderWidgetAccessor {

    /** Where the slider stands, from 0 to 1. */
    @Accessor("value")
    double mc_puppet$value();
}
