package com.modrinth.pain_o_d.mc_puppet.core;

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
 *
 * <p>A review before the first release found three ways a pack could still
 * have answered for the player, and each is refused here by name:
 * <ul>
 * <li>an entry that is not an absolute path. {@code "."} is wherever the game
 *     was started, which is the game directory in every launcher, so it named
 *     every victim's instance without knowing any of them;</li>
 * <li>a consent file that is itself inside the game directory, which happens
 *     when the home directory <em>is</em> the game directory - a server in a
 *     container whose home is its root, a pack unpacked into {@code ~} - and
 *     then the file is one a pack ships;</li>
 * <li>a home that is not an absolute path, which is what a shipped
 *     {@code -Duser.home=config/x} makes of it.</li>
 * </ul>
 * None of this stands against a pack that can set JVM arguments or carries a
 * mod of its own: that is code running as the player already, and it needs no
 * bridge. What is kept out is files - a config folder, a home folder - arriving
 * with a download.
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
        return refusal(home, gameDir) == null;
    }

    /** Why consent is not given, for the log, or {@code null} if it is. */
    public static String refusal(Path home, Path gameDir) {
        if (!home.isAbsolute()) {
            return "the home directory is given as \"" + home + "\", which is not an absolute path";
        }
        Path file = fileIn(home);
        String game = key(gameDir);
        String consent = key(file);
        if (consent.equals(game) || consent.startsWith(game.endsWith("/") ? game : game + "/")) {
            return file + " is inside the game directory, where anything downloaded can put it";
        }
        if (!Files.isRegularFile(file)) {
            return "there is no " + file;
        }
        return named(file, game) ? null : file + " does not name this game directory";
    }

    private static boolean named(Path file, String wanted) {
        try {
            JsonObject read = Protocol.GSON.fromJson(Files.readString(file, StandardCharsets.UTF_8),
                    JsonObject.class);
            if (read == null || !read.has("allowed") || !read.get("allowed").isJsonArray()) {
                return false;
            }
            JsonArray allowed = read.getAsJsonArray("allowed");
            for (JsonElement each : allowed) {
                if (!each.isJsonPrimitive()) {
                    continue;
                }
                Path entry = Path.of(each.getAsString());
                // Absolute or nothing: "." would be every game that was ever started from its own folder.
                if (entry.isAbsolute() && key(entry).equals(wanted)) {
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
