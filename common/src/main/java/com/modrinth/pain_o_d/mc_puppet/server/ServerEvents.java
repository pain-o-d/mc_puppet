package com.modrinth.pain_o_d.mc_puppet.server;

import com.google.gson.JsonObject;
import com.modrinth.pain_o_d.mc_puppet.core.Events;

import dev.architectury.event.events.common.LifecycleEvent;
import dev.architectury.event.events.common.PlayerEvent;
import net.minecraft.server.network.ServerPlayerEntity;

/**
 * The server's life on the events bus: starting, ready, stopping, stopped, and who comes and goes.
 * Crashes are {@code CrashEvents}, from the mixin on the report. Safe to load on a dedicated server.
 */
public final class ServerEvents {

    private ServerEvents() {
    }

    public static void init() {
        LifecycleEvent.SERVER_STARTING.register(server -> say("server.starting", null));
        LifecycleEvent.SERVER_STARTED.register(server -> say("server.ready", null));
        LifecycleEvent.SERVER_STOPPING.register(server -> say("server.stopping", null));
        LifecycleEvent.SERVER_STOPPED.register(server -> say("server.stopped", null));
        PlayerEvent.PLAYER_JOIN.register(player -> say("player.joined", who(player)));
        PlayerEvent.PLAYER_QUIT.register(player -> say("player.left", who(player)));
    }

    private static JsonObject who(ServerPlayerEntity player) {
        JsonObject data = new JsonObject();
        data.addProperty("name", player.getName().getString());
        data.addProperty("uuid", player.getUuidAsString());
        return data;
    }

    private static void say(String name, JsonObject data) {
        Events.record("server", name, Events.Level.INFO, data);
    }
}
