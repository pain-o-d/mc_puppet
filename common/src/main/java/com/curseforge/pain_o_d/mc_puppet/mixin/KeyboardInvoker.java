package com.curseforge.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.Keyboard;

/** Where a real keyboard enters the game. See {@link MouseInvoker}. */
@Mixin(Keyboard.class)
public interface KeyboardInvoker {

    @Invoker("onKey")
    void mc_puppet$onKey(long window, int key, int scancode, int action, int modifiers);

    @Invoker("onChar")
    void mc_puppet$onChar(long window, int codePoint, int modifiers);
}
