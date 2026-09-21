package com.modrinth.pain_o_d.mc_puppet;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.modrinth.pain_o_d.mc_puppet.core.Bridge;
import com.modrinth.pain_o_d.mc_puppet.core.Ops;
import com.modrinth.pain_o_d.mc_puppet.core.PuppetConfig;
import com.modrinth.pain_o_d.mc_puppet.core.Waiter;
import com.modrinth.pain_o_d.mc_puppet.server.ServerOps;

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

    /**
     * Whether this is a mod developer's run. {@code -Dmc_puppet.pretend_production=true} says it
     * is not, which is how the rules for everybody else are tried out from a development
     * environment. It can only make things stricter; nothing says the opposite.
     */
    public static boolean development() {
        return Platform.isDevelopmentEnvironment() && !Boolean.getBoolean("mc_puppet.pretend_production")
                && !LOOKS_LIKE_A_REAL_GAME;
    }

    /**
     * What the loader says is not all that is asked, because on Fabric it says what it is told:
     * a development environment there is the system property {@code fabric.development}, and a
     * shipped {@code -Dfabric.development=true} would have let a player's game skip consent
     * altogether. Found by the review before the first release.
     *
     * <p>So the game is looked at as well. A real Fabric game runs in intermediary names, where
     * an identifier is {@code class_2960} and has been since 1.14; a development environment
     * runs in a project's own mappings and has no such class. Forge and NeoForge decide from
     * how the game was launched, which no property changes, and their production games are in
     * the same names a developer may use, so there is nothing of the kind to look for there.
     */
    private static final boolean LOOKS_LIKE_A_REAL_GAME = Platform.isFabric() && has("net.minecraft.class_2960");

    private static boolean has(String className) {
        try {
            Class.forName(className, false, McPuppet.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    public static void init() {
        PuppetConfig config = PuppetConfig.load(Platform.getConfigFolder(), development(),
                Platform.getGameFolder(), com.modrinth.pain_o_d.mc_puppet.core.Consent.home());
        if (config.refusedForWantOfConsent()) {
            // Said loudly, because the usual way to get here is a modpack that shipped its
            // author's config, and the player it reached should be able to find out.
            LOGGER.warn("MC Puppet was switched on by a config file or a launch option, and has stayed OFF: "
                    + "this is not a development environment, and nobody at this machine has allowed this game "
                    + "to be driven. If you are testing a mod here, run: mc-puppet allow \"{}\". If you are "
                    + "not, nothing needs doing; you may remove the mod. ({})", Platform.getGameFolder(),
                    com.modrinth.pain_o_d.mc_puppet.core.Consent.refusal(
                            com.modrinth.pain_o_d.mc_puppet.core.Consent.home(), Platform.getGameFolder()));
            return;
        }
        if (!config.enabled()) {
            LOGGER.info("MC Puppet is installed and off. Switch it on in config/mc_puppet.json "
                    + "or with -Dmc_puppet.enabled=true.");
            return;
        }

        LifecycleEvent.SERVER_STARTED.register(server -> {
            try {
                Ops ops = ServerOps.create(server, SERVER_WAITER);
                com.modrinth.pain_o_d.mc_puppet.api.PuppetApi.attach(
                        com.modrinth.pain_o_d.mc_puppet.api.PuppetApi.Side.SERVER, ops);
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
        LifecycleEvent.SERVER_STOPPED.register(server -> {
            // A developer's dedicated server only: a client goes on living after the server inside
            // it stops, and anybody else's process is theirs to end.
            if (server.isDedicated() && development()) {
                com.modrinth.pain_o_d.mc_puppet.core.Leaving.seeTheProcessOut();
            }
        });
        TickEvent.SERVER_POST.register(server -> SERVER_WAITER.tick());

        if (Platform.getEnvironment() == Env.CLIENT) {
            // Inside the branch, so that a dedicated server never runs the
            // line that would load a class mentioning the client.
            com.modrinth.pain_o_d.mc_puppet.client.PuppetClient.init(config);
        }
    }
}
