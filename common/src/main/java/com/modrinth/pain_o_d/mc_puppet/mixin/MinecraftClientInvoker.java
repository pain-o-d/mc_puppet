package com.modrinth.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.MinecraftClient;

/**
 * What the attack and use keys do, as the game does it.
 *
 * <p>Breaking a block is asked for every tick the attack key is held, but
 * only while the mouse is grabbed, which it is not when the window is behind
 * another. These are the methods the key handlers call; called directly they
 * do the same work against the same crosshair target without that condition.
 */
@Mixin(MinecraftClient.class)
public interface MinecraftClientInvoker {

    @Invoker("doAttack")
    boolean mc_puppet$doAttack();

    @Invoker("doItemUse")
    void mc_puppet$doItemUse();

    @Invoker("handleBlockBreaking")
    void mc_puppet$handleBlockBreaking(boolean breaking);
}
