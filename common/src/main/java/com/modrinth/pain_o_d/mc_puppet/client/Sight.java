package com.modrinth.pain_o_d.mc_puppet.client;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

import com.modrinth.pain_o_d.mc_puppet.core.Args;
import com.modrinth.pain_o_d.mc_puppet.core.EventLog;
import com.modrinth.pain_o_d.mc_puppet.core.GameJson;
import com.modrinth.pain_o_d.mc_puppet.core.Layout;
import com.modrinth.pain_o_d.mc_puppet.core.Ops;
import com.modrinth.pain_o_d.mc_puppet.core.Waiter;
import com.modrinth.pain_o_d.mc_puppet.mixin.BossBarHudAccessor;
import com.modrinth.pain_o_d.mc_puppet.mixin.InGameHudAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.minecraft.block.BlockState;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.hud.ClientBossBar;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.world.ClientWorld;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.registry.Registries;
import net.minecraft.scoreboard.Scoreboard;
import net.minecraft.scoreboard.ScoreboardObjective;
import net.minecraft.text.Text;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.hit.HitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.world.LightType;

/**
 * Seeing without a screenshot.
 *
 * <p>The first operations could see widgets and slots, which is the part of a
 * screen that is data already. The rest a player sees was only in pixels:
 * what a screen draws by hand, what a tooltip says, the action bar, a toast, a
 * sound. And the world could only be asked of the server, so a client that
 * had fallen out of step with it could not be caught. These read all of that
 * as the client has it.
 */
final class Sight {

    private Sight() {
    }

    /** A box of blocks larger than this is a question for the server, or for several questions. */
    private static final int MAX_BLOCKS = 32768;

    static void register(Ops ops, MinecraftClient client, Waiter waiter) {

        ops.add("frame", "{texts?: true, items?: true, sprites?: false, tooltips?: true, issues?: true, "
                        + "contains?: text}",
                "What the next frame draws, as data: every string with where it landed and how wide, every "
                        + "item, tooltips, and on request every sprite. Sees what a screen draws without widgets "
                        + "- prices, headings, a HUD overlay. \"issues\" lists text off the screen, text drawn "
                        + "over other text, and labels wider than their widget - of the open screen when there is "
                        + "one (each text says its layer: hud, screen or overlay), else of the HUD. The window must not be minimised.",
                args -> frame(client, waiter, args));

        ops.add("tooltip", "{slot: n} | {widget: index|text} | {x, y}",
                "Hovers there and returns the tooltip the game then draws, as lines, with what mods add to it. "
                        + "{lines: []} when nothing is shown.",
                args -> {
                    double[] at = ClientOps.pointOf(client, args);
                    Input.moveTo(client, at[0], at[1]);
                    // The frame after the one in which the cursor arrived: a widget's tooltip waits for a delay
                    // that is zero by default, and a slot's is drawn from the focus the frame before found.
                    return FrameCapture.next().thenCompose(ignored -> withTimeout(waiter, FrameCapture.next()))
                            .thenApply(captured -> {
                                JsonObject json = new JsonObject();
                                json.add("lines", captured.tooltips.isEmpty() ? new JsonArray()
                                        : captured.tooltips.get(captured.tooltips.size() - 1).getAsJsonObject()
                                                .get("lines"));
                                json.addProperty("x", at[0]);
                                json.addProperty("y", at[1]);
                                return (JsonElement) json;
                            });
                });

        ops.now("hud", "{}",
                "What the HUD shows: action bar, title and subtitle, boss bars, the sidebar, status effects, "
                        + "health, food, armour, air, experience, the selected hotbar slot.",
                args -> hud(client));

        ops.now("events", "{since?: seq, kinds?: \"chat,system,actionbar,title,subtitle,toast,sound\", "
                        + "contains?: text, limit?: 50}",
                "What happened and was over before anyone could look, each numbered: messages, the action bar, "
                        + "titles, toasts and sounds (a sound's text is its id). Take \"sequence\" before doing "
                        + "something and ask \"since\" it after.",
                args -> EventLog.CLIENT.read(Args.number(args, "since", 0), Args.string(args, "kinds", null),
                        Args.string(args, "contains", null), Args.integer(args, "limit", 50)));

        ops.now("block", "{x, y, z}",
                "A block as the client believes it: state, light, biome. Ask the server the same to catch the "
                        + "two disagreeing.",
                args -> block(requireWorld(client), new BlockPos(Args.integer(args, "x"), Args.integer(args, "y"),
                        Args.integer(args, "z"))));

        ops.now("blocks", "{from: {x,y,z}, to: {x,y,z}, list?: false, skip_air?: true}",
                "A box of blocks as the client has it: how many of each, and with \"list\" every one with its "
                        + "place. At most " + MAX_BLOCKS + ".",
                args -> blocks(requireWorld(client), args));

        ops.now("target", "{}",
                "What the crosshair is on, within reach: a block with its side, an entity, or nothing.",
                args -> hit(client, client.crosshairTarget));

        ops.now("raycast", "{distance?: 64, fluids?: false}",
                "The first block along the line of sight, as far as asked.",
                args -> hit(client, requirePlayer(client).raycast(Args.decimal(args, "distance", 64), 1f,
                        Args.flag(args, "fluids", false))));

        ops.now("world", "{}", "Dimension, time, weather, difficulty, and how much of the world is loaded.",
                args -> world(client));

        ops.now("perf", "{}", "Frames a second, memory, entities and chunks in view, the integrated server's tick.",
                args -> perf(client));

        ops.now("bindings", "{contains?: text}",
                "Every key binding, the game's and mods': its name, category, the key it is bound to, whether it "
                        + "is down. The names are what hold and tap take.",
                args -> bindings(client, Args.string(args, "contains", null)));
    }

    /** A frame never comes while the window is minimised; a test should be told so, not left waiting. */
    private static <T> CompletableFuture<T> withTimeout(Waiter waiter, CompletableFuture<T> frame) {
        CompletableFuture<T> answer = new CompletableFuture<>();
        frame.whenComplete((value, failure) -> {
            if (failure != null) {
                answer.completeExceptionally(failure);
            } else {
                answer.complete(value);
            }
        });
        waiter.until("a frame to be drawn (is the window minimised?)", 5000,
                () -> answer.isDone() ? JsonNull.INSTANCE : null)
                .whenComplete((ignored, failure) -> {
                    if (failure != null) {
                        answer.completeExceptionally(failure);
                    }
                });
        return answer;
    }

    private static CompletableFuture<JsonElement> frame(MinecraftClient client, Waiter waiter, JsonObject args)
            throws Ops.Refused {
        String contains = Args.string(args, "contains", null);
        return withTimeout(waiter, FrameCapture.next()).thenApply(captured -> {
            JsonObject json = new JsonObject();
            json.addProperty("width", client.getWindow().getScaledWidth());
            json.addProperty("height", client.getWindow().getScaledHeight());
            json.addProperty("screen", client.currentScreen == null ? null
                    : client.currentScreen.getClass().getName());
            if (Args.flag(args, "texts", true)) {
                JsonArray texts = FrameCapture.textsJson(captured);
                if (contains != null) {
                    String wanted = contains.toLowerCase(Locale.ROOT);
                    JsonArray matching = new JsonArray();
                    for (JsonElement each : texts) {
                        if (each.getAsJsonObject().get("text").getAsString().toLowerCase(Locale.ROOT)
                                .contains(wanted)) {
                            matching.add(each);
                        }
                    }
                    texts = matching;
                }
                json.add("texts", texts);
            }
            if (Args.flag(args, "items", true)) {
                json.add("items", captured.items);
            }
            if (Args.flag(args, "sprites", false)) {
                json.add("sprites", captured.sprites);
            }
            if (Args.flag(args, "tooltips", true)) {
                json.add("tooltips", captured.tooltips);
            }
            if (Args.flag(args, "issues", true)) {
                json.add("issues", issues(client, captured));
            }
            return json;
        });
    }

    private static JsonArray issues(MinecraftClient client, FrameCapture.Frame captured) {
        List<Layout.Box> widgets = new ArrayList<>();
        List<Double> labelWidths = new ArrayList<>();
        Screen screen = client.currentScreen;
        if (screen != null) {
            for (ClickableWidget widget : ClientOps.widgetsOf(screen)) {
                String label = widget.getMessage().getString();
                // Only a label that was drawn. An icon button's message is for the narrator, and a text
                // field's likewise; neither is cut off by a widget it was never drawn on.
                boolean drawn = captured.texts.stream().anyMatch(text -> text.label().equals(label)
                        && text.y() >= widget.getY() - 1 && text.y() <= widget.getY() + widget.getHeight());
                if (!widget.visible || !drawn) {
                    continue;
                }
                widgets.add(new Layout.Box("widget", widget.getMessage().getString(), widget.getX(), widget.getY(),
                        widget.getWidth(), widget.getHeight()));
                labelWidths.add((double) client.textRenderer.getWidth(widget.getMessage()));
            }
        }
        // With a screen open the HUD is behind it, dimmed, and what it overlaps there is nobody's defect.
        return Layout.issues(captured.textsToJudge(), widgets, labelWidths, client.getWindow().getScaledWidth(),
                client.getWindow().getScaledHeight());
    }

    private static JsonElement hud(MinecraftClient client) throws Ops.Refused {
        ClientPlayerEntity player = requirePlayer(client);
        InGameHudAccessor hud = (InGameHudAccessor) client.inGameHud;
        JsonObject json = new JsonObject();
        json.addProperty("actionbar", hud.mc_puppet$overlayRemaining() > 0 ? said(hud.mc_puppet$overlayMessage())
                : null);
        boolean titled = hud.mc_puppet$titleRemainTicks() > 0;
        json.addProperty("title", titled ? said(hud.mc_puppet$title()) : null);
        json.addProperty("subtitle", titled ? said(hud.mc_puppet$subtitle()) : null);

        JsonArray bars = new JsonArray();
        for (ClientBossBar bar : ((BossBarHudAccessor) client.inGameHud.getBossBarHud()).mc_puppet$bossBars()
                .values()) {
            JsonObject one = new JsonObject();
            one.addProperty("name", bar.getName().getString());
            one.addProperty("percent", Layout.round(bar.getPercent() * 100));
            one.addProperty("colour", bar.getColor().getName());
            bars.add(one);
        }
        json.add("boss_bars", bars);

        Scoreboard scoreboard = player.getScoreboard();
        ScoreboardObjective sidebar = com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.sidebarOf(scoreboard);
        if (sidebar == null) {
            json.add("sidebar", JsonNull.INSTANCE);
        } else {
            JsonObject board = new JsonObject();
            board.addProperty("title", sidebar.getDisplayName().getString());
            JsonArray lines = new JsonArray();
            com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.linesOf(scoreboard, sidebar).forEach(entry -> {
                JsonObject line = new JsonObject();
                line.addProperty("name", entry.getKey());
                line.addProperty("value", entry.getValue());
                lines.add(line);
            });
            board.add("lines", lines);
            json.add("sidebar", board);
        }

        JsonArray effects = new JsonArray();
        for (StatusEffectInstance effect : player.getStatusEffects()) {
            JsonObject one = new JsonObject();
            one.addProperty("id", com.modrinth.pain_o_d.mc_puppet.compat.Compat.idOf(effect));
            one.addProperty("level", effect.getAmplifier() + 1);
            one.addProperty("ticks", effect.getDuration());
            effects.add(one);
        }
        json.add("effects", effects);

        json.addProperty("health", player.getHealth());
        json.addProperty("max_health", player.getMaxHealth());
        json.addProperty("food", player.getHungerManager().getFoodLevel());
        json.addProperty("armour", player.getArmor());
        json.addProperty("air", player.getAir());
        json.addProperty("max_air", player.getMaxAir());
        json.addProperty("xp_level", player.experienceLevel);
        json.addProperty("xp_progress", Layout.round(player.experienceProgress * 100));
        json.addProperty("hotbar", player.getInventory().selectedSlot);
        json.add("held", GameJson.stack(player.getMainHandStack()));
        json.add("offhand", GameJson.stack(player.getOffHandStack()));
        json.addProperty("hud_hidden", client.options.hudHidden);
        return json;
    }

    private static String said(Text text) {
        return text == null ? null : text.getString();
    }

    private static JsonObject block(ClientWorld world, BlockPos pos) {
        BlockState state = world.getBlockState(pos);
        JsonObject json = new JsonObject();
        json.addProperty("block", Registries.BLOCK.getId(state.getBlock()).toString());
        JsonObject properties = new JsonObject();
        state.getEntries().forEach((property, value) -> properties.addProperty(property.getName(),
                String.valueOf(value)));
        json.add("properties", properties);
        json.addProperty("air", state.isAir());
        json.addProperty("loaded", world.getChunkManager().isChunkLoaded(pos.getX() >> 4, pos.getZ() >> 4));
        json.addProperty("block_light", world.getLightLevel(LightType.BLOCK, pos));
        json.addProperty("sky_light", world.getLightLevel(LightType.SKY, pos));
        json.addProperty("biome", world.getBiome(pos).getKey().map(key -> key.getValue().toString())
                .orElse("unregistered"));
        json.addProperty("block_entity", world.getBlockEntity(pos) != null);
        return json;
    }

    private static JsonElement blocks(ClientWorld world, JsonObject args) throws Ops.Refused {
        BlockPos from = corner(args, "from");
        BlockPos to = corner(args, "to");
        BlockPos low = new BlockPos(Math.min(from.getX(), to.getX()), Math.min(from.getY(), to.getY()),
                Math.min(from.getZ(), to.getZ()));
        BlockPos high = new BlockPos(Math.max(from.getX(), to.getX()), Math.max(from.getY(), to.getY()),
                Math.max(from.getZ(), to.getZ()));
        long volume = (long) (high.getX() - low.getX() + 1) * (high.getY() - low.getY() + 1)
                * (high.getZ() - low.getZ() + 1);
        if (volume > MAX_BLOCKS) {
            throw new Ops.Refused("that is " + volume + " blocks; at most " + MAX_BLOCKS + " at a time");
        }
        boolean list = Args.flag(args, "list", false);
        boolean skipAir = Args.flag(args, "skip_air", true);
        Map<String, Integer> counts = new LinkedHashMap<>();
        JsonArray listed = new JsonArray();
        for (BlockPos pos : BlockPos.iterate(low, high)) {
            BlockState state = world.getBlockState(pos);
            if (skipAir && state.isAir()) {
                continue;
            }
            String id = Registries.BLOCK.getId(state.getBlock()).toString();
            counts.merge(id, 1, Integer::sum);
            if (list) {
                JsonObject one = GameJson.pos(pos);
                one.addProperty("block", id);
                listed.add(one);
            }
        }
        JsonObject json = new JsonObject();
        json.addProperty("volume", volume);
        JsonObject counted = new JsonObject();
        counts.forEach(counted::addProperty);
        json.add("counts", counted);
        if (list) {
            json.add("blocks", listed);
        }
        return json;
    }

    private static BlockPos corner(JsonObject args, String name) throws Ops.Refused {
        if (!args.has(name) || !args.get(name).isJsonObject()) {
            throw new Ops.Refused("\"" + name + "\" is {x, y, z}");
        }
        JsonObject corner = args.getAsJsonObject(name);
        return new BlockPos(Args.integer(corner, "x"), Args.integer(corner, "y"), Args.integer(corner, "z"));
    }

    static JsonElement hit(MinecraftClient client, HitResult hit) throws Ops.Refused {
        JsonObject json = new JsonObject();
        if (hit == null || hit.getType() == HitResult.Type.MISS) {
            json.addProperty("kind", "nothing");
            return json;
        }
        json.add("at", GameJson.pos(hit.getPos()));
        json.addProperty("distance", Layout.round(Math.sqrt(hit.squaredDistanceTo(requirePlayer(client)))));
        if (hit instanceof BlockHitResult onBlock) {
            json.addProperty("kind", "block");
            json.add("pos", GameJson.pos(onBlock.getBlockPos()));
            json.addProperty("side", onBlock.getSide().asString());
            json.addProperty("block", Registries.BLOCK.getId(requireWorld(client)
                    .getBlockState(onBlock.getBlockPos()).getBlock()).toString());
        } else if (hit instanceof EntityHitResult onEntity) {
            json.addProperty("kind", "entity");
            json.add("entity", GameJson.entity(onEntity.getEntity(), false));
        }
        return json;
    }

    private static JsonElement world(MinecraftClient client) throws Ops.Refused {
        ClientWorld world = requireWorld(client);
        JsonObject json = new JsonObject();
        json.addProperty("dimension", world.getRegistryKey().getValue().toString());
        json.addProperty("time", world.getTime());
        json.addProperty("day_time", world.getTimeOfDay() % 24000);
        json.addProperty("day", world.getTimeOfDay() / 24000);
        json.addProperty("raining", world.isRaining());
        json.addProperty("thundering", world.isThundering());
        json.addProperty("difficulty", world.getDifficulty().getName());
        json.addProperty("loaded_chunks", world.getChunkManager().getLoadedChunkCount());
        json.addProperty("entities", world.getRegularEntityCount());
        json.addProperty("view_distance", client.options.getViewDistance().getValue());
        return json;
    }

    private static JsonElement perf(MinecraftClient client) {
        JsonObject json = new JsonObject();
        json.addProperty("fps", client.getCurrentFps());
        Runtime runtime = Runtime.getRuntime();
        json.addProperty("memory_used_mb", (runtime.totalMemory() - runtime.freeMemory()) >> 20);
        json.addProperty("memory_max_mb", runtime.maxMemory() >> 20);
        if (client.world != null) {
            json.addProperty("entities", client.world.getRegularEntityCount());
            json.addProperty("chunks_drawn", client.worldRenderer.getCompletedChunkCount());
        }
        if (client.getServer() != null) {
            json.addProperty("server_ms_per_tick", Layout.round(com.modrinth.pain_o_d.mc_puppet.compat.Compat.msPerTick(client.getServer())));
        }
        return json;
    }

    private static JsonElement bindings(MinecraftClient client, String contains) {
        JsonArray found = new JsonArray();
        String wanted = contains == null ? null : contains.toLowerCase(Locale.ROOT);
        for (KeyBinding binding : client.options.allKeys) {
            String name = binding.getTranslationKey();
            if (wanted != null && !name.toLowerCase(Locale.ROOT).contains(wanted)
                    && !Text.translatable(name).getString().toLowerCase(Locale.ROOT).contains(wanted)) {
                continue;
            }
            JsonObject one = new JsonObject();
            one.addProperty("name", name);
            one.addProperty("says", Text.translatable(name).getString());
            one.addProperty("category", binding.getCategory());
            one.addProperty("key", binding.getBoundKeyTranslationKey());
            one.addProperty("unbound", binding.isUnbound());
            one.addProperty("pressed", binding.isPressed());
            found.add(one);
        }
        return found;
    }

    static ClientPlayerEntity requirePlayer(MinecraftClient client) throws Ops.Refused {
        if (client.player == null) {
            throw new Ops.Refused("no world is loaded");
        }
        return client.player;
    }

    static ClientWorld requireWorld(MinecraftClient client) throws Ops.Refused {
        if (client.world == null) {
            throw new Ops.Refused("no world is loaded");
        }
        return client.world;
    }
}
