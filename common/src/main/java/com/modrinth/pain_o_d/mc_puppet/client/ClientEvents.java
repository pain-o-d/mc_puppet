package com.modrinth.pain_o_d.mc_puppet.client;

import com.google.gson.JsonObject;
import com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat;
import com.modrinth.pain_o_d.mc_puppet.core.Events;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.DisconnectedScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.network.ServerInfo;

/**
 * The client's connection on the events bus, watched from the tick rather than hooked in the
 * game's networking: what the person at the screen could see is what is said. A connecting
 * screen is {@code client.connecting}; a world with a player in it is {@code client.connected};
 * the disconnect screen is {@code client.connect_failed} if the world was never reached and
 * {@code client.disconnected} if it was, with the text the screen shows as {@code reason}.
 * Crashes are {@code CrashEvents}. Nothing on a dedicated server may load this class.
 */
public final class ClientEvents {

    /** Ticks a lost world waits for a disconnect screen before it is called a plain leaving. */
    static final int GRACE = 10;

    private static boolean connecting;
    private static boolean connected;
    private static boolean readySaid;
    private static String address;
    private static Screen reported;
    private static int waited;

    private ClientEvents() {
    }

    /** Every client tick. Never throws: reporting must not break the game. */
    public static void tick(MinecraftClient client) {
        try {
            step(client);
        } catch (RuntimeException | LinkageError failure) {
            // A watcher that fails is a watcher that is off; the game goes on.
        }
    }

    private static void step(MinecraftClient client) {
        Screen screen = client.currentScreen;
        boolean inWorld = client.world != null && client.player != null;
        if (!readySaid && (inWorld || screen instanceof TitleScreen)) {
            // The main menu, or a world, is there: the game is usable.
            readySaid = true;
            Events.record("client", "client.ready", Events.Level.INFO, null);
        }
        ServerInfo entry = client.getCurrentServerEntry();
        if (entry != null && entry.address != null && !entry.address.isEmpty()) {
            address = entry.address;
        }

        if (ClientCompat.isConnecting(screen) && !connecting && !connected) {
            connecting = true;
            waited = 0;
            Events.record("client", "client.connecting", Events.Level.INFO, withAddress(null));
        }
        if (inWorld && !connected) {
            connected = true;
            connecting = false;
            waited = 0;
            Events.record("client", "client.connected", Events.Level.INFO, withAddress(null));
        }
        if (screen instanceof DisconnectedScreen disconnected && screen != reported) {
            reported = screen;
            String reason = ClientCompat.disconnectReason(disconnected);
            lost(reason);
            return;
        }
        if (connected && !inWorld) {
            // The world went, and no disconnect screen came with it: a quit to the title, most likely.
            if (++waited > GRACE) {
                lost("left");
            }
        } else if (connecting && !connected && !ClientCompat.isConnecting(screen) && backedOut(screen)) {
            // The connecting screen went and neither a world nor a refusal followed, and the player is
            // back at the title or the server list: cancelled. Any other screen (a resource pack
            // prompt, say) is still part of the login.
            if (++waited > GRACE) {
                connecting = false;
                waited = 0;
                JsonObject data = new JsonObject();
                data.addProperty("reason", "cancelled");
                Events.record("client", "client.connect_failed", Events.Level.WARN, withAddress(data));
                address = null;
            }
        } else {
            waited = 0;
        }
    }

    private static boolean backedOut(Screen screen) {
        return screen == null || screen instanceof TitleScreen
                || screen instanceof net.minecraft.client.gui.screen.multiplayer.MultiplayerScreen;
    }

    private static void lost(String reason) {
        JsonObject data = new JsonObject();
        data.addProperty("reason", reason);
        if (connected) {
            Events.record("client", "client.disconnected", Events.Level.INFO, withAddress(data));
        } else {
            Events.record("client", "client.connect_failed", Events.Level.WARN, withAddress(data));
        }
        connected = false;
        connecting = false;
        waited = 0;
        address = null;
    }

    private static JsonObject withAddress(JsonObject data) {
        JsonObject out = data == null ? new JsonObject() : data;
        if (address != null) {
            out.addProperty("address", address);
        }
        return out;
    }
}
