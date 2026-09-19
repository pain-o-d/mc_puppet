package com.curseforge.pain_o_d.mc_puppet.client;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.curseforge.pain_o_d.mc_puppet.core.Bridge;
import com.curseforge.pain_o_d.mc_puppet.core.Ops;
import com.curseforge.pain_o_d.mc_puppet.core.PuppetConfig;
import com.curseforge.pain_o_d.mc_puppet.core.Waiter;

import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.events.client.ClientChatEvent;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.client.ClientSystemMessageEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;

/**
 * The client's bridge. Nothing on a dedicated server may load this class.
 *
 * <p>Opened when the client has started rather than when the mod initialises:
 * until then there is no window, no render thread taking work and nothing to
 * look at, and a test that connected early would be told so for every
 * operation it tried.
 */
public final class PuppetClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc_puppet");

    private static volatile Bridge bridge;

    private PuppetClient() {
    }

    public static void init(PuppetConfig config) {
        Waiter waiter = new Waiter();
        ChatLog chat = new ChatLog();

        ClientSystemMessageEvent.RECEIVED.register(message -> {
            chat.add(true, message.getString());
            return CompoundEventResult.pass();
        });
        ClientChatEvent.RECEIVED.register((type, message) -> {
            chat.add(false, message.getString());
            return CompoundEventResult.pass();
        });
        ClientTickEvent.CLIENT_POST.register(client -> waiter.tick());

        ClientLifecycleEvent.CLIENT_STARTED.register(client -> {
            try {
                // A test runs behind other windows. Vanilla opens the pause menu
                // when its window loses focus, and every step in the world then
                // meets a screen nobody opened. Not written to options.txt.
                client.options.pauseOnLostFocus = false;
                Ops ops = ClientOps.create(client, waiter, chat);
                bridge = Bridge.open("client", ops, config.clientPort(), Platform.getGameFolder());
            } catch (IOException | RuntimeException failure) {
                LOGGER.error("MC Puppet could not open its client bridge", failure);
            }
        });
        ClientLifecycleEvent.CLIENT_STOPPING.register(client -> {
            waiter.abandon("the client is stopping");
            Bridge open = bridge;
            bridge = null;
            if (open != null) {
                open.close();
            }
        });
    }
}
