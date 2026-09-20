package com.curseforge.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * A switch is enough for a developer's run and not for anybody else's: a
 * modpack ships its config folder, and cannot ship a user's home directory.
 */
class ConsentTest {

    private static Path gameWith(Path root, String config) throws Exception {
        Path game = Files.createDirectories(root.resolve("instance"));
        Path configDir = Files.createDirectories(game.resolve("config"));
        Files.writeString(configDir.resolve("mc_puppet.json"), config);
        return game;
    }

    private static void allow(Path home, Path... games) throws Exception {
        StringBuilder list = new StringBuilder();
        for (Path game : games) {
            list.append(list.isEmpty() ? "" : ",").append('"').append(game.toString().replace("\\", "\\\\"))
                    .append('"');
        }
        Files.createDirectories(Consent.fileIn(home).getParent());
        Files.writeString(Consent.fileIn(home), "{\"allowed\":[" + list + "]}");
    }

    @Test
    @DisplayName("the modpack that shipped its author's config: switched on, and stays off")
    void aShippedConfigDoesNotOpenTheBridge(@TempDir Path root) throws Exception {
        Path game = gameWith(root, "{\"enabled\": true, \"i_really_mean_it\": true}");
        Path home = Files.createDirectories(root.resolve("home"));

        PuppetConfig config = PuppetConfig.load(game.resolve("config"), false, game, home);

        assertFalse(config.enabled());
        assertTrue(config.refusedForWantOfConsent(), "and it says why, so that the log can");
    }

    @Test
    @DisplayName("a developer's run needs the switch and nothing else")
    void development(@TempDir Path root) throws Exception {
        Path game = gameWith(root, "{\"enabled\": true}");
        PuppetConfig config = PuppetConfig.load(game.resolve("config"), true, game, root.resolve("home"));
        assertTrue(config.enabled());
        assertFalse(config.refusedForWantOfConsent());
    }

    @Test
    @DisplayName("allowed from the home directory, this instance opens and the one beside it does not")
    void oneDirectoryAtATime(@TempDir Path root) throws Exception {
        Path game = gameWith(root, "{\"enabled\": true}");
        Path other = gameWith(root.resolve("elsewhere"), "{\"enabled\": true}");
        Path home = Files.createDirectories(root.resolve("home"));
        allow(home, game);

        assertTrue(PuppetConfig.load(game.resolve("config"), false, game, home).enabled());
        assertFalse(PuppetConfig.load(other.resolve("config"), false, other, home).enabled());
    }

    @Test
    @DisplayName("consent without the switch opens nothing: both are needed")
    void consentIsNotASwitch(@TempDir Path root) throws Exception {
        Path game = gameWith(root, "{\"enabled\": false}");
        Path home = Files.createDirectories(root.resolve("home"));
        allow(home, game);
        PuppetConfig config = PuppetConfig.load(game.resolve("config"), false, game, home);
        assertFalse(config.enabled());
        assertFalse(config.refusedForWantOfConsent(), "nobody asked, so nobody was refused");
    }

    @Test
    @DisplayName("the same directory written another way is the same directory")
    void paths(@TempDir Path root) throws Exception {
        Path game = gameWith(root, "{\"enabled\": true}");
        Path home = Files.createDirectories(root.resolve("home"));
        allow(home, game.resolve("..").resolve("instance"));
        assertTrue(Consent.given(home, game));
        assertEquals(Consent.key(game), Consent.key(game.resolve("config").resolve("..")));
    }

    @Test
    @DisplayName("a consent file that cannot be read allows nothing")
    void unreadable(@TempDir Path root) throws Exception {
        Path game = gameWith(root, "{\"enabled\": true}");
        Path home = Files.createDirectories(root.resolve("home"));
        Files.createDirectories(Consent.fileIn(home).getParent());
        Files.writeString(Consent.fileIn(home), "{\"allowed\": \"everything\"}");
        assertFalse(Consent.given(home, game));
        Files.writeString(Consent.fileIn(home), "not json at all");
        assertFalse(Consent.given(home, game));
    }
}
