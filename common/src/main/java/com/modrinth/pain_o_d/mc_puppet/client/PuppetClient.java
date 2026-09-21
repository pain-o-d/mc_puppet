package com.modrinth.pain_o_d.mc_puppet.client;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.modrinth.pain_o_d.mc_puppet.api.PuppetApi;
import com.modrinth.pain_o_d.mc_puppet.core.Bridge;
import com.modrinth.pain_o_d.mc_puppet.core.EventLog;
import com.modrinth.pain_o_d.mc_puppet.core.Ops;
import com.modrinth.pain_o_d.mc_puppet.core.PuppetConfig;
import com.modrinth.pain_o_d.mc_puppet.core.Waiter;

import com.google.gson.JsonObject;

import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.events.client.ClientChatEvent;
import dev.architectury.event.events.client.ClientLifecycleEvent;
import dev.architectury.event.events.client.ClientSystemMessageEvent;
import dev.architectury.event.events.client.ClientTickEvent;
import dev.architectury.platform.Platform;

/**
 * The client's bridge. Nothing on a dedicated server may load this class.
 *
 * <p>Opened when the game can be used, which is later than when it has
 * started: after "started" the client is still loading its resources behind
 * the splash, and work handed to it then runs from inside that loading. A
 * world opened from there never finishes opening - the first launch driven
 * by a program rather than a person did exactly that, the same second the
 * bridge appeared, and hung. So the bridge opens on the first tick with no
 * loading overlay up, and a test that waits for the bridge has waited for
 * the game.
 */
public final class PuppetClient {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc_puppet");

    private static volatile Bridge bridge;

    /** Tried once and failed: said once, not once a tick. */
    private static boolean gaveUp;

    private PuppetClient() {
    }

    public static void init(PuppetConfig config) {
        Waiter waiter = new Waiter();
        ChatLog chat = new ChatLog();

        ClientSystemMessageEvent.RECEIVED.register(message -> {
            EventLog.CLIENT.add("system", message.getString());
            chat.add(true, message.getString());
            return CompoundEventResult.pass();
        });
        ClientChatEvent.RECEIVED.register((type, message) -> {
            EventLog.CLIENT.add("chat", message.getString());
            chat.add(false, message.getString());
            return CompoundEventResult.pass();
        });
        ClientTickEvent.CLIENT_POST.register(client -> waiter.tick());

        // Outside a development environment the person playing may not be the person who
        // switched this on, and a line in a log is not somewhere a player looks. Said in
        // chat on joining a world, every time, for as long as the bridge is open.
        dev.architectury.event.events.client.ClientPlayerEvent.CLIENT_PLAYER_JOIN.register(player -> {
            if (bridge != null && !com.modrinth.pain_o_d.mc_puppet.McPuppet.development()) {
                player.sendMessage(net.minecraft.text.Text.literal("[MC Puppet] This game can be controlled by "
                        + "programs on this computer: a mod testing tool is switched on. If you did not do that, "
                        + "remove the mc_puppet mod or set \"enabled\": false in config/mc_puppet.json.")
                        .formatted(net.minecraft.util.Formatting.GOLD), false);
            }
        });
        Recorder.init();

        // A test that was holding a key when the player went to somebody else's server lets go of it
        // there: the bridge refuses new input on such a server, and must not go on giving old input.
        ClientTickEvent.CLIENT_POST.register(client -> {
            boolean now = elsewhere(client);
            if (now && !wasElsewhere) {
                net.minecraft.client.option.KeyBinding.unpressAll();
                VirtualKeys.releaseAll();
                LOGGER.warn("MC Puppet: this client has joined a server that is not on this machine. The bridge "
                        + "neither drives nor reads the game there; it answers again in a world of your own.");
            }
            wasElsewhere = now;
        });

        ClientTickEvent.CLIENT_POST.register(client -> {
            if (bridge != null || gaveUp || client.getOverlay() != null) {
                return;
            }
            try {
                // A test runs behind other windows. Vanilla opens the pause menu
                // when its window loses focus, and every step in the world then
                // meets a screen nobody opened. Not written to options.txt.
                client.options.pauseOnLostFocus = false;
                // A sound is often all a mod does to say that something worked.
                com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.onSound(client, sound -> {
                    JsonObject more = new JsonObject();
                    more.addProperty("category", sound.getCategory().getName());
                    more.addProperty("x", Math.round(sound.getX() * 10) / 10.0);
                    more.addProperty("y", Math.round(sound.getY() * 10) / 10.0);
                    more.addProperty("z", Math.round(sound.getZ() * 10) / 10.0);
                    EventLog.CLIENT.add("sound", sound.getId().toString(), more);
                });
                Ops ops = ClientOps.create(client, waiter, chat);
                // This machine and no further: on somebody else's server a bridge is a bot. See Reach.
                ops.gate(op -> elsewhere(client) ? com.modrinth.pain_o_d.mc_puppet.core.Reach.refusalElsewhere(op) : null);
                PuppetApi.attach(PuppetApi.Side.CLIENT, ops);
                bridge = Bridge.open("client", ops, config.clientPort(), Platform.getGameFolder());
            } catch (IOException | RuntimeException failure) {
                gaveUp = true;
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

    private static volatile boolean wasElsewhere;

    /** Whether the world this client is in is served from another machine. No world is nowhere else. */
    static boolean elsewhere(net.minecraft.client.MinecraftClient client) {
        net.minecraft.client.network.ClientPlayNetworkHandler handler = client.getNetworkHandler();
        if (client.world == null || handler == null) {
            return false;
        }
        net.minecraft.network.ClientConnection connection = handler.getConnection();
        return !com.modrinth.pain_o_d.mc_puppet.core.Reach.isThisMachine(connection.isLocal(), connection.getAddress());
    }
}
