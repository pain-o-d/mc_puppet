package com.curseforge.pain_o_d.mc_puppet.mixin;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.gen.Invoker;

import net.minecraft.client.Mouse;

/**
 * Where a real mouse enters the game.
 *
 * <p>GLFW calls these three. Everything downstream of them is what a player's
 * click goes through: the loaders' screen events, which a mod may be
 * listening to instead of overriding a method; the key bindings, when no
 * screen is open; drag and hover. Calling a screen's {@code mouseClicked}
 * directly skips all of it, and a test of a mod that hooks an event passed
 * without the mod having been asked anything.
 */
@Mixin(Mouse.class)
public interface MouseInvoker {

    @Invoker("onCursorPos")
    void mc_puppet$onCursorPos(long window, double x, double y);

    @Invoker("onMouseButton")
    void mc_puppet$onMouseButton(long window, int button, int action, int mods);

    @Invoker("onMouseScroll")
    void mc_puppet$onMouseScroll(long window, double horizontal, double vertical);
}
