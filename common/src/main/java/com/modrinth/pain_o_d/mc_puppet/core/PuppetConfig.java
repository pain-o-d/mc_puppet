package com.modrinth.pain_o_d.mc_puppet.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * Whether the bridge is on, and where.
 *
 * <p>Off by default, and that is the point of having a setting at all: a mod
 * that lets another program drive the game must not do so because somebody
 * dropped it in a mods folder. It is switched on by
 * {@code config/mc_puppet.json} or, for a development run that should not
 * leave a file behind, by {@code -Dmc_puppet.enabled=true}. The property wins.
 *
 * <p>There is deliberately no setting for the address. It is the loopback,
 * always.
 */
public record PuppetConfig(boolean enabled, int clientPort, int serverPort, boolean refusedForWantOfConsent) {

    /** As a development environment reads it: a switch is all it takes. */
    public PuppetConfig(boolean enabled, int clientPort, int serverPort) {
        this(enabled, clientPort, serverPort, false);
    }

    /**
     * Reads the config, and outside a development environment asks for
     * consent as well. See {@link Consent} for why a switch is not enough
     * there.
     *
     * @param development whether this is a mod developer's run, from Gradle or an IDE
     * @param gameDir     the directory that would be driven
     * @param home        where consent is kept; the user's home directory
     */
    public static PuppetConfig load(Path configDir, boolean development, Path gameDir, Path home) {
        PuppetConfig asked = load(configDir);
        if (!asked.enabled() || development || Consent.given(home, gameDir)) {
            return asked;
        }
        return new PuppetConfig(false, asked.clientPort(), asked.serverPort(), true);
    }

    private static final Logger LOGGER = LoggerFactory.getLogger("mc_puppet");

    public static final int DEFAULT_CLIENT_PORT = 25580;
    public static final int DEFAULT_SERVER_PORT = 25581;

    public static PuppetConfig load(Path configDir) {
        boolean enabled = false;
        int clientPort = DEFAULT_CLIENT_PORT;
        int serverPort = DEFAULT_SERVER_PORT;

        Path file = configDir.resolve("mc_puppet.json");
        try {
            if (Files.exists(file)) {
                JsonObject read = Protocol.GSON.fromJson(
                        Files.readString(file, StandardCharsets.UTF_8), JsonObject.class);
                if (read != null) {
                    enabled = read.has("enabled") && read.get("enabled").getAsBoolean();
                    clientPort = portOf(read, "client_port", clientPort);
                    serverPort = portOf(read, "server_port", serverPort);
                }
            } else {
                Files.createDirectories(configDir);
                Files.writeString(file, """
                        {
                          "_": "MC Puppet lets a program on THIS machine drive the game, for testing mods. Leave it off unless you are doing that.",
                          "enabled": false,
                          "client_port": %d,
                          "server_port": %d
                        }
                        """.formatted(DEFAULT_CLIENT_PORT, DEFAULT_SERVER_PORT), StandardCharsets.UTF_8);
            }
        } catch (IOException | JsonParseException | IllegalStateException | ClassCastException unreadable) {
            LOGGER.warn("config/mc_puppet.json could not be read; MC Puppet stays off unless a property says otherwise",
                    unreadable);
        }

        String property = System.getProperty("mc_puppet.enabled");
        if (property != null) {
            enabled = Boolean.parseBoolean(property);
        }
        clientPort = Integer.getInteger("mc_puppet.client_port", clientPort);
        serverPort = Integer.getInteger("mc_puppet.server_port", serverPort);
        return new PuppetConfig(enabled, clientPort, serverPort);
    }

    private static int portOf(JsonObject read, String key, int fallback) {
        if (!read.has(key)) {
            return fallback;
        }
        int port = read.get(key).getAsInt();
        return port >= 1024 && port <= 65_000 ? port : fallback;
    }
}
