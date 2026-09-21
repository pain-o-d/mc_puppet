package com.modrinth.pain_o_d.mc_puppet.compat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Consumer;
import java.util.function.Function;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.TitleScreen;
import net.minecraft.client.gui.screen.multiplayer.ConnectScreen;
import net.minecraft.client.network.ServerAddress;
import net.minecraft.client.network.ServerInfo;
import net.minecraft.client.sound.SoundInstance;
import net.minecraft.registry.DynamicRegistryManager;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardDisplaySlot;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.world.dimension.DimensionOptionsRegistryHolder;
import net.minecraft.world.gen.GeneratorOptions;
import net.minecraft.world.level.LevelInfo;

/**
 * What the 1.21.1 client does its own way. <b>This file is the 1.21.1 one</b>;
 * see {@link Compat}. Nothing on a dedicated server may load this class.
 */
public final class ClientCompat {

    private ClientCompat() {
    }

    /** Works out again what the crosshair is on, after the head has been turned by hand. */
    public static void updateCrosshair(MinecraftClient client) {
        client.gameRenderer.updateCrosshairTarget(1f);
    }

    public static void openWorld(MinecraftClient client, String name) {
        client.createIntegratedServerLoader().start(name, () -> client.setScreen(new TitleScreen()));
    }

    public static void createWorld(MinecraftClient client, String name, LevelInfo level, GeneratorOptions generator,
                                   Function<DynamicRegistryManager, DimensionOptionsRegistryHolder> dimensions) {
        client.createIntegratedServerLoader().createAndStart(name, level, generator, dimensions, client.currentScreen);
    }

    /** Straight to the connecting screen, as the server list's Join button goes; back from a refusal is the title. */
    public static void joinServer(MinecraftClient client, ServerAddress address, String asTyped) {
        ConnectScreen.connect(new TitleScreen(), client, address,
                new ServerInfo("MC Puppet", asTyped, ServerInfo.ServerType.OTHER), false, null);
    }

    /** Every sound the client plays, as it plays it. */
    public static void onSound(MinecraftClient client, Consumer<SoundInstance> heard) {
        client.getSoundManager().registerListener((sound, set, range) -> heard.accept(sound));
    }

    public static ScoreboardObjective sidebarOf(Scoreboard scoreboard) {
        return scoreboard.getObjectiveForSlot(ScoreboardDisplaySlot.SIDEBAR);
    }

    /** The sidebar's lines, highest first: what each says, and its number. */
    public static List<Map.Entry<String, Integer>> linesOf(Scoreboard scoreboard, ScoreboardObjective objective) {
        List<Map.Entry<String, Integer>> lines = new ArrayList<>();
        scoreboard.getScoreboardEntries(objective).stream().filter(entry -> !entry.hidden())
                .sorted((a, b) -> Integer.compare(b.value(), a.value())).limit(15)
                .forEach(entry -> lines.add(Map.entry(entry.name().getString(), entry.value())));
        return lines;
    }
}
