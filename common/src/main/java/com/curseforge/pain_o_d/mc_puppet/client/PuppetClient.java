package com.curseforge.pain_o_d.mc_puppet.client;

import java.io.IOException;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.curseforge.pain_o_d.mc_puppet.api.PuppetApi;
import com.curseforge.pain_o_d.mc_puppet.core.Bridge;
import com.curseforge.pain_o_d.mc_puppet.core.EventLog;
import com.curseforge.pain_o_d.mc_puppet.core.Ops;
import com.curseforge.pain_o_d.mc_puppet.core.PuppetConfig;
import com.curseforge.pain_o_d.mc_puppet.core.Waiter;

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
            if (bridge != null && !com.curseforge.pain_o_d.mc_puppet.McPuppet.development()) {
                player.sendMessage(net.minecraft.text.Text.literal("[MC Puppet] This game can be controlled by "
                        + "programs on this computer: a mod testing tool is switched on. If you did not do that, "
                        + "remove the mc_puppet mod or set \"enabled\": false in config/mc_puppet.json.")
                        .formatted(net.minecraft.util.Formatting.GOLD), false);
            }
        });
        Recorder.init();

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
                client.getSoundManager().registerListener((sound, set, range) -> {
                    JsonObject more = new JsonObject();
                    more.addProperty("category", sound.getCategory().getName());
                    more.addProperty("x", Math.round(sound.getX() * 10) / 10.0);
                    more.addProperty("y", Math.round(sound.getY() * 10) / 10.0);
                    more.addProperty("z", Math.round(sound.getZ() * 10) / 10.0);
                    EventLog.CLIENT.add("sound", sound.getId().toString(), more);
                });
                Ops ops = ClientOps.create(client, waiter, chat);
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
}
