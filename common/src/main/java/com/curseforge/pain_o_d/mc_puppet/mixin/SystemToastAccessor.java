package com.curseforge.pain_o_d.mc_puppet.mixin;

import java.util.List;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Accessor;

import net.minecraft.client.toast.SystemToast;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;

@Mixin(SystemToast.class)
public interface SystemToastAccessor {

    @Accessor("title")
    Text mc_puppet$title();

    @Accessor("lines")
    List<OrderedText> mc_puppet$lines();
}
