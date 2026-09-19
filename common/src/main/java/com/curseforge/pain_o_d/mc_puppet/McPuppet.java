package com.curseforge.pain_o_d.mc_puppet;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.curseforge.pain_o_d.mc_puppet.core.Bridge;
import com.curseforge.pain_o_d.mc_puppet.core.Ops;
import com.curseforge.pain_o_d.mc_puppet.core.PuppetConfig;
import com.curseforge.pain_o_d.mc_puppet.core.Waiter;
import com.curseforge.pain_o_d.mc_puppet.server.ServerOps;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.TickEvent;
import dev.architectury.platform.Platform;
import dev.architectury.utils.Env;

/**
 * MC Puppet: lets a program on the same machine see and drive a running game,
 * so that a mod can be tested without a person at the keyboard.
 *
 * <p>Two bridges, one for each side of the game. The server's comes up with a
 * server — a dedicated one, or the one inside a single-player client — and
 * goes with it. The client's comes up with the client. They are separate
 * because they are separate games: a test of a trading screen wants to click
 * in the client and then ask the server what the villager now holds.
 *
 * <p>Off unless switched on. See {@link PuppetConfig} and {@link Bridge}.
 */
public final class McPuppet {

    public static final String MOD_ID = "mc_puppet";

    private static final Logger LOGGER = LoggerFactory.getLogger(MOD_ID);

    private static volatile Bridge serverBridge;
    private static final Waiter SERVER_WAITER = new Waiter();

    private McPuppet() {
    }

    public static void init() {
        PuppetConfig config = PuppetConfig.load(Platform.getConfigFolder());
        if (!config.enabled()) {
            LOGGER.info("MC Puppet is installed and off. Switch it on in config/mc_puppet.json "
                    + "or with -Dmc_puppet.enabled=true.");
            return;
        }

        LifecycleEvent.SERVER_STARTED.register(server -> {
            try {
                Ops ops = ServerOps.create(server, SERVER_WAITER);
                serverBridge = Bridge.open("server", ops, config.serverPort(), Platform.getGameFolder());
            } catch (IOException | RuntimeException failure) {
                LOGGER.error("MC Puppet could not open its server bridge", failure);
            }
        });
        LifecycleEvent.SERVER_STOPPING.register(server -> {
            SERVER_WAITER.abandon("the server is stopping");
            Bridge open = serverBridge;
            serverBridge = null;
            if (open != null) {
                open.close();
            }
        });
        TickEvent.SERVER_POST.register(server -> SERVER_WAITER.tick());

        if (Platform.getEnvironment() == Env.CLIENT) {
            // Inside the branch, so that a dedicated server never runs the
            // line that would load a class mentioning the client.
            com.curseforge.pain_o_d.mc_puppet.client.PuppetClient.init(config);
        }
    }
}
