package com.modrinth.pain_o_d.mc_puppet.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;

/**
 * What the 1.20.1 client does its own way. <b>This file is the 1.20.1 one</b>;
 * see {@link Compat}. Nothing on a dedicated server may load this class.
 */
public final class ClientCompat {

    private ClientCompat() {
    }

    /** The sidebar's slot. A number here; an enum from 1.20.2. */
    private static final int SIDEBAR = 1;

    public static void updateCrosshair(MinecraftClient client) {
        client.gameRenderer.updateTargetedEntity(1f);
    }

    public static void openWorld(MinecraftClient client, String name) {
        client.createIntegratedServerLoader().start(client.currentScreen, name);
    }

    public static void createWorld(MinecraftClient client, String name, LevelInfo level, GeneratorOptions generator,
                                   Function<DynamicRegistryManager, DimensionOptionsRegistryHolder> dimensions) {
        client.createIntegratedServerLoader().createAndStart(name, level, generator, dimensions);
    }

    public static void onSound(MinecraftClient client, Consumer<SoundInstance> heard) {
        client.getSoundManager().registerListener((sound, set) -> heard.accept(sound));
    }

    public static ScoreboardObjective sidebarOf(Scoreboard scoreboard) {
        return scoreboard.getObjectiveForSlot(SIDEBAR);
    }

    public static List<Map.Entry<String, Integer>> linesOf(Scoreboard scoreboard, ScoreboardObjective objective) {
        List<Map.Entry<String, Integer>> lines = new ArrayList<>();
        scoreboard.getAllPlayerScores(objective).stream()
                .sorted((a, b) -> Integer.compare(b.getScore(), a.getScore())).limit(15)
                .forEach(score -> lines.add(Map.entry(score.getPlayerName(), score.getScore())));
        return lines;
    }
}
