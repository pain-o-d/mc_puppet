package com.modrinth.pain_o_d.mc_puppet.mixin;

import net.minecraft.client.network.ClientPlayerInteractionManager;
import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

/** The vanilla cached-slot synchronization seam, shared by both supported versions. */
@Mixin(ClientPlayerInteractionManager.class)
public interface ClientPlayerInteractionManagerInvoker {
    @Invoker("syncSelectedSlot")
    void mc_puppet$syncSelectedSlot();
}
