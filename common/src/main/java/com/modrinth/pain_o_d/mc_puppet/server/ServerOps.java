package com.modrinth.pain_o_d.mc_puppet.server;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.modrinth.pain_o_d.mc_puppet.core.Args;
import com.modrinth.pain_o_d.mc_puppet.core.GameJson;
import com.modrinth.pain_o_d.mc_puppet.core.Ops;
import com.modrinth.pain_o_d.mc_puppet.core.Waiter;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

import dev.architectury.platform.Platform;

import net.minecraft.block.BlockState;
import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.Entity;
import net.minecraft.predicate.NbtPredicate;
import net.minecraft.registry.Registries;
import net.minecraft.registry.RegistryKey;
import net.minecraft.registry.RegistryKeys;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.CommandOutput;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.server.network.ServerPlayerEntity;
import net.minecraft.server.world.ServerWorld;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Box;
import net.minecraft.util.math.Vec2f;
import net.minecraft.util.math.Vec3d;

/**
 * What the server side answers to.
 *
 * <p>RCON already runs a command. It says nothing about what the command did
 * beyond the text it printed, needs a password in a properties file, and
 * cannot be asked what is in a chest. A test wants state: who is online, what
 * they hold, what a villager is offering, what a block is. Commands do the
 * writing here as well — they are the game's own, checked, versioned way to
 * change a world — and these operations do the reading.
 *
 * <p>Runs with operator level four. Whoever can read the token file could
 * already edit the world save.
 */
public final class ServerOps {

    private ServerOps() {
    }

    public static Ops create(MinecraftServer server, Waiter waiter) {
        Ops ops = new Ops("server", server::execute, waiter);

        ops.now("info", "{}", "Version, mods, players, worlds, tick time.", args -> info(server));

        ops.now("command", "{command, as?: player name}",
                "Runs a command at operator level 4 and returns {output: [lines], success, result}. "
                        + "\"as\" runs it as that player, at their position.",
                args -> command(server, args));

        ops.now("players", "{}", "Online players: name, uuid, position, dimension, game mode, health.",
                args -> players(server));

        ops.now("inventory", "{player}", "A player's non-empty inventory slots.",
                args -> GameJson.inventory(player(server, args).getInventory()));

        ops.now("count", "{player, item}", "How many of one item a player holds.",
                args -> new JsonPrimitive(GameJson.countOf(player(server, args).getInventory(),
                        Args.string(args, "item"))));

        ops.now("entities", "{dimension?, type?, near?: {x,y,z,radius}, offers?: false, limit?: 50}",
                "Entities, nearest first when \"near\" is given. \"offers\" adds a merchant's trades.",
                args -> entities(server, args));

        ops.now("entity", "{uuid, nbt?: false, offers?: true}", "One entity; \"nbt\" adds its saved data as text.",
                args -> entity(server, args));

        ops.now("block", "{x, y, z, dimension?, nbt?: true}", "A block's state, and its block entity's data.",
                args -> block(server, args));

        ops.add("wait", "{ticks?: n, players?: n, timeout_ms?}",
                "Waits for so many server ticks, or until so many players are online.",
                args -> {
                    if (args.has("players")) {
                        int wanted = Args.integer(args, "players");
                        return waiter.until(wanted + " player(s) online", Args.timeout(args),
                                () -> server.getCurrentPlayerCount() >= wanted
                                        ? new JsonPrimitive(server.getCurrentPlayerCount()) : null);
                    }
                    int ticks = Args.integer(args, "ticks", 1);
                    int until = server.getTicks() + Math.max(1, ticks);
                    return waiter.until(ticks + " tick(s)", Math.max(Args.timeout(args), ticks * 100L),
                            () -> server.getTicks() >= until ? new JsonPrimitive(server.getTicks()) : null);
                });
        return ops;
    }

    private static JsonElement info(MinecraftServer server) {
        JsonObject info = new JsonObject();
        info.addProperty("side", "server");
        info.addProperty("protocol", com.modrinth.pain_o_d.mc_puppet.core.Protocol.VERSION);
        info.addProperty("mod_version", dev.architectury.platform.Platform.getMod("mc_puppet").getVersion());
        info.addProperty("development", com.modrinth.pain_o_d.mc_puppet.McPuppet.development());
        info.addProperty("minecraft", server.getVersion());
        // As the client's info has it: a scenario that asks only the server still learns where it ran.
        info.addProperty("loader", Platform.isFabric() ? "fabric"
                : com.modrinth.pain_o_d.mc_puppet.compat.Compat.OTHER_LOADER);
        info.addProperty("dedicated", server.isDedicated());
        // The save's folder, which is what open_world takes: a scenario that leaves a world can come back to it.
        java.nio.file.Path save = server.getSavePath(net.minecraft.util.WorldSavePath.ROOT).toAbsolutePath().normalize();
        info.addProperty("world_name", save.getFileName() == null ? null : save.getFileName().toString());
        info.addProperty("ticks", server.getTicks());
        info.addProperty("ms_per_tick", Math.round(com.modrinth.pain_o_d.mc_puppet.compat.Compat.msPerTick(server) * 100) / 100.0);
        info.addProperty("players", server.getCurrentPlayerCount());
        JsonArray worlds = new JsonArray();
        for (ServerWorld world : server.getWorlds()) {
            worlds.add(world.getRegistryKey().getValue().toString());
        }
        info.add("worlds", worlds);
        JsonArray mods = new JsonArray();
        for (var mod : Platform.getMods()) {
            mods.add(mod.getModId() + " " + mod.getVersion());
        }
        info.add("mods", mods);
        return info;
    }

    /** Collects what a command says, which is otherwise sent to whoever ran it and lost. */
    private static final class Capture implements CommandOutput {
        final List<String> lines = new ArrayList<>();

        @Override
        public void sendMessage(Text message) {
            lines.add(message.getString());
        }

        @Override
        public boolean shouldReceiveFeedback() {
            return true;
        }

        @Override
        public boolean shouldTrackOutput() {
            return true;
        }

        @Override
        public boolean shouldBroadcastConsoleToOps() {
            return false;
        }
    }

    private static JsonElement command(MinecraftServer server, JsonObject args) throws Ops.Refused {
        String command = Args.string(args, "command");
        Capture capture = new Capture();
        ServerWorld world = server.getOverworld();
        ServerCommandSource source = new ServerCommandSource(capture, Vec3d.ofBottomCenter(world.getSpawnPos()),
                Vec2f.ZERO, world, 4, "MCPuppet", Text.literal("MCPuppet"), server, null);
        if (args.has("as")) {
            ServerPlayerEntity player = server.getPlayerManager().getPlayer(Args.string(args, "as"));
            if (player == null) {
                throw new Ops.Refused("no such player online: " + Args.string(args, "as"));
            }
            source = player.getCommandSource().withOutput(capture).withLevel(4);
        }
        int[] result = {0};
        boolean[] success = {false};
        source = com.modrinth.pain_o_d.mc_puppet.compat.Compat.reportingTo(source, (successful, value) -> {
            success[0] = successful;
            result[0] = value;
        });
        server.getCommandManager().executeWithPrefix(source, command);

        JsonObject answer = new JsonObject();
        answer.addProperty("success", success[0]);
        answer.addProperty("result", result[0]);
        JsonArray output = new JsonArray();
        capture.lines.forEach(output::add);
        answer.add("output", output);
        return answer;
    }

    private static JsonElement players(MinecraftServer server) {
        JsonArray list = new JsonArray();
        for (ServerPlayerEntity player : server.getPlayerManager().getPlayerList()) {
            JsonObject one = new JsonObject();
            one.addProperty("name", player.getGameProfile().getName());
            one.addProperty("uuid", player.getUuidAsString());
            one.add("pos", GameJson.pos(player.getPos()));
            one.addProperty("dimension", player.getWorld().getRegistryKey().getValue().toString());
            one.addProperty("game_mode", player.interactionManager.getGameMode().getName());
            one.addProperty("health", player.getHealth());
            list.add(one);
        }
        return list;
    }

    private static ServerPlayerEntity player(MinecraftServer server, JsonObject args) throws Ops.Refused {
        String name = Args.string(args, "player");
        ServerPlayerEntity player = server.getPlayerManager().getPlayer(name);
        if (player == null) {
            try {
                player = server.getPlayerManager().getPlayer(UUID.fromString(name));
            } catch (IllegalArgumentException notAUuid) {
                // A name that matched nobody; said below.
            }
        }
        if (player == null) {
            throw new Ops.Refused("no such player online: " + name);
        }
        return player;
    }

    private static ServerWorld world(MinecraftServer server, JsonObject args) throws Ops.Refused {
        if (!args.has("dimension")) {
            return server.getOverworld();
        }
        Identifier id = Identifier.tryParse(Args.string(args, "dimension"));
        ServerWorld world = id == null ? null : server.getWorld(RegistryKey.of(RegistryKeys.WORLD, id));
        if (world == null) {
            throw new Ops.Refused("no such dimension: " + Args.string(args, "dimension"));
        }
        return world;
    }

    private static JsonElement entities(MinecraftServer server, JsonObject args) throws Ops.Refused {
        ServerWorld world = world(server, args);
        String type = Args.string(args, "type", null);
        boolean withOffers = Args.flag(args, "offers", false);
        int limit = Math.max(1, Math.min(500, Args.integer(args, "limit", 50)));

        List<Entity> found = new ArrayList<>();
        Vec3d centre = null;
        if (args.has("near") && args.get("near").isJsonObject()) {
            JsonObject near = args.getAsJsonObject("near");
            centre = new Vec3d(Args.decimal(near, "x"), Args.decimal(near, "y"), Args.decimal(near, "z"));
            double radius = near.has("radius") ? Args.decimal(near, "radius") : 16;
            found.addAll(world.getOtherEntities(null, Box.of(centre, radius * 2, radius * 2, radius * 2)));
        } else {
            world.iterateEntities().forEach(found::add);
        }
        // The living. A mob killed this tick is still in the world for a second.
        found.removeIf(entity -> !entity.isAlive());
        if (type != null) {
            found.removeIf(entity -> !Registries.ENTITY_TYPE.getId(entity.getType()).toString().equals(type));
        }
        if (centre != null) {
            Vec3d from = centre;
            found.sort((a, b) -> Double.compare(a.squaredDistanceTo(from), b.squaredDistanceTo(from)));
        }
        JsonArray list = new JsonArray();
        for (Entity entity : found) {
            if (list.size() >= limit) {
                break;
            }
            list.add(GameJson.entity(entity, withOffers));
        }
        JsonObject answer = new JsonObject();
        answer.addProperty("found", found.size());
        answer.add("entities", list);
        return answer;
    }

    private static JsonElement entity(MinecraftServer server, JsonObject args) throws Ops.Refused {
        UUID uuid;
        try {
            uuid = UUID.fromString(Args.string(args, "uuid"));
        } catch (IllegalArgumentException notAUuid) {
            throw new Ops.Refused("\"uuid\" is not a uuid");
        }
        for (ServerWorld world : server.getWorlds()) {
            Entity entity = world.getEntity(uuid);
            if (entity != null) {
                JsonObject json = GameJson.entity(entity, Args.flag(args, "offers", true));
                json.addProperty("dimension", world.getRegistryKey().getValue().toString());
                if (Args.flag(args, "nbt", false)) {
                    json.addProperty("nbt", GameJson.cut(NbtPredicate.entityToNbt(entity).toString()));
                }
                return json;
            }
        }
        throw new Ops.Refused("no loaded entity has that uuid");
    }

    private static JsonElement block(MinecraftServer server, JsonObject args) throws Ops.Refused {
        ServerWorld world = world(server, args);
        BlockPos pos = new BlockPos(Args.integer(args, "x"), Args.integer(args, "y"), Args.integer(args, "z"));
        if (!world.isChunkLoaded(pos)) {
            throw new Ops.Refused("that chunk is not loaded");
        }
        BlockState state = world.getBlockState(pos);
        JsonObject json = new JsonObject();
        json.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
        json.addProperty("state", state.toString());
        BlockEntity blockEntity = world.getBlockEntity(pos);
        if (blockEntity != null && Args.flag(args, "nbt", true)) {
            json.addProperty("nbt", GameJson.cut(
                    com.modrinth.pain_o_d.mc_puppet.compat.Compat.nbtOf(blockEntity, world)));
        }
        return json;
    }
}
