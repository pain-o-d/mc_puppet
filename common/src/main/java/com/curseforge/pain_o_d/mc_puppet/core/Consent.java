package com.curseforge.pain_o_d.mc_puppet.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * Whether the person at this machine has said this game may be driven.
 *
 * <p>The bridge is off unless switched on, by a config file or a launch
 * property. In a development environment that is enough: whoever runs a dev
 * client from Gradle is the person who set it up. Anywhere else it is not,
 * and the reason is modpacks. A pack ships its {@code config/} folder. A
 * developer who tests with MC Puppet and then exports the instance ships
 * {@code "enabled": true} to every player of it, and a second key in the same
 * file, however sternly named, ships right beside the first.
 *
 * <p>So outside development the switch needs consent kept where a pack cannot
 * put it: a file in the user's home directory, naming the game directory that
 * may be driven. Nothing a player downloads writes there. The command line
 * tool does, when asked: {@code mc-puppet allow <gameDir>}. One directory at a
 * time and no wildcard, so that allowing one instance is not allowing the
 * next pack that happens to carry the mod.
 */
public final class Consent {

    private Consent() {
    }

    /** {@code ~/.mc_puppet/allowed.json}: {@code {"allowed": ["C:/games/instance"]}}. */
    public static Path fileIn(Path home) {
        return home.resolve(".mc_puppet").resolve("allowed.json");
    }

    public static Path home() {
        return Path.of(System.getProperty("user.home", "."));
    }

    /** Whether {@code gameDir} is named in the consent file under {@code home}. Unreadable is no. */
    public static boolean given(Path home, Path gameDir) {
        Path file = fileIn(home);
        if (!Files.isRegularFile(file)) {
            return false;
        }
        try {
            JsonObject read = Protocol.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    JsonObject.class);
            if (read == null || !read.has("allowed") || !read.get("allowed").isJsonArray()) {
                return false;
            }
            String wanted = key(gameDir);
            JsonArray allowed = read.getAsJsonArray("allowed");
            for (JsonElement each : allowed) {
                if (each.isJsonPrimitive() && key(Path.of(each.getAsString())).equals(wanted)) {
                    return true;
                }
            }
            return false;
        } catch (IOException | JsonParseException | IllegalStateException | ClassCastException
                 | java.nio.file.InvalidPathException unreadable) {
            return false;
        }
    }

    /**
     * A directory as something to compare: the real one where it exists, so
     * that a link and what it points to are the same place, and without
     * regard to case, which costs nothing where case matters and is right
     * where it does not.
     */
    static String key(Path directory) {
        Path resolved;
        try {
            resolved = directory.toRealPath();
        } catch (IOException missing) {
            resolved = directory.toAbsolutePath().normalize();
        }
        return resolved.toString().replace('\\', '/').toLowerCase(Locale.ROOT);
    }
}
