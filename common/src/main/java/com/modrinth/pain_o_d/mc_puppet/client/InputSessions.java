package com.modrinth.pain_o_d.mc_puppet.client;

import com.modrinth.pain_o_d.mc_puppet.core.InputSession;
import com.modrinth.pain_o_d.mc_puppet.core.Ops;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;

/** Game-thread adapter for the single client's owned temporal input. */
final class InputSessions {
    private static final InputSession OWNER = new InputSession();
    private InputSessions() { }

    static InputSession.Lease begin(MinecraftClient client, String what, boolean worldInput,
                                    boolean focus, Runnable cleanup) throws Ops.Refused {
        Object player = client.player, world = client.world, handler = client.getNetworkHandler();
        Object screen = client.currentScreen, manager = client.interactionManager;
        Object focusOwner = new Object();
        boolean[] focused = {false};
        InputSession.Lease lease;
        try {
            lease = OWNER.begin(what, () -> {
                if (PuppetClient.elsewhere(client)) return "the client left this machine; temporal input was released";
                if (client.player != player || client.world != world || client.getNetworkHandler() != handler
                        || client.interactionManager != manager) return "the player, world or connection changed during input";
                if (client.currentScreen != screen || worldInput && client.currentScreen != null)
                    return "the screen changed during input; stop or close_screen before trying again";
                if (focused[0] && (!client.isWindowFocused() || worldInput && !client.mouse.isCursorLocked()))
                    return "scoped input focus hooks stopped working; input was released";
                return null;
            }, task -> { if (client.isOnThread()) task.run(); else client.execute(task); }, () -> {
                try { cleanup.run(); } finally { VirtualFocus.end(focusOwner); }
            });
        } catch (IllegalStateException refused) { throw new Ops.Refused(refused.getMessage()); }
        try {
            if (focus) {
                VirtualFocus.begin(focusOwner);
                focused[0] = true;
                if (!client.isWindowFocused() || worldInput && !client.mouse.isCursorLocked())
                    throw new IllegalStateException("scoped input focus hooks are unavailable; no held input was sent");
            }
            return lease;
        } catch (RuntimeException failure) { lease.revoke(failure.getMessage()); throw failure; }
    }

    static void requireIdle() throws Ops.Refused {
        try { OWNER.requireIdle(); } catch (IllegalStateException refused) { throw new Ops.Refused(refused.getMessage()); }
    }

    static void tick() { OWNER.tick(); }

    static void revoke(String why) { OWNER.revoke(why); }

    static void releaseAll(MinecraftClient client, String why) {
        OWNER.revoke(why);
        KeyBinding.unpressAll();
        VirtualKeys.releaseAll();
        if (client.interactionManager != null) client.interactionManager.cancelBlockBreaking();
    }
}
