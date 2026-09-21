package com.modrinth.pain_o_d.mc_puppet.client;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import com.modrinth.pain_o_d.mc_puppet.core.Args;
import com.modrinth.pain_o_d.mc_puppet.core.GameJson;
import com.modrinth.pain_o_d.mc_puppet.core.Layout;
import com.modrinth.pain_o_d.mc_puppet.core.Ops;
import com.modrinth.pain_o_d.mc_puppet.core.Waiter;
import com.modrinth.pain_o_d.mc_puppet.mixin.MinecraftClientInvoker;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.network.ClientPlayerEntity;
import net.minecraft.client.option.GameOptions;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;
import net.minecraft.command.argument.EntityAnchorArgumentType;
import net.minecraft.entity.Entity;
import net.minecraft.registry.Registries;
import net.minecraft.util.hit.BlockHitResult;
import net.minecraft.util.hit.EntityHitResult;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.MathHelper;
import net.minecraft.util.math.Vec3d;

/**
 * The character, moved as a player moves it.
 *
 * <p>A test that teleports and then asserts has tested the teleport. What a
 * mod does to movement, reach, attack or mining is in the path between a key
 * and its effect, so these hold the game's own key bindings down and let the
 * game do the rest: walking is the forward key, an attack is the game's own
 * attack on whatever the crosshair is on, breaking a block is the attack
 * held on it for as long as it takes with the tool in hand.
 *
 * <p>Nothing here finds a path. {@code move_to} faces a place and walks,
 * jumping when it runs into something; enough for a flat test world and
 * honest about the rest.
 */
final class Body {

    private Body() {
    }

    static void register(Ops ops, MinecraftClient client, Waiter waiter) {

        ops.now("look", "{yaw, pitch} | {at: {x,y,z}} | {entity: uuid} | {type, radius?: 16}",
                "Turns the head: to angles, at a point, or at an entity. Returns what the crosshair is then on.",
                args -> {
                    look(client, args);
                    com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.updateCrosshair(client);
                    return Sight.hit(client, client.crosshairTarget);
                });

        ops.add("hold", "{keys: [forward|back|left|right|jump|sneak|sprint|attack|use|<binding name>], "
                        + "ticks: n}",
                "Holds key bindings down for so many ticks, then lets go, and returns where the player ended. "
                        + "Walking, sneaking, drawing a bow, eating. Any binding from \"bindings\" by name, mods' "
                        + "too. Needs no screen open.",
                args -> hold(client, waiter, args));

        ops.now("tap", "{key: drop|swap_hands|inventory|pick_item|<binding name>}",
                "Presses a key binding once, whatever key it is bound to: drop, swap hands, a mod's own key.",
                args -> {
                    KeyBinding binding = binding(client, Args.string(args, "key"));
                    if (binding.isUnbound()) {
                        throw new Ops.Refused(binding.getTranslationKey() + " is not bound to any key");
                    }
                    // Counted as a press by every binding on that key, as a real press is.
                    KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey()));
                    return JsonNull.INSTANCE;
                });

        ops.add("move_to", "{x, z, y?, within?: 0.6, sprint?: false, timeout_ms?: 15000}",
                "Walks to a place: faces it, holds forward, jumps when blocked. No path finding. Returns where "
                        + "the player ended and how far off.",
                args -> moveTo(client, waiter, args));

        ops.now("attack", "{entity?: uuid, type?, radius?: 6}",
                "A left click in the world. Given an entity, looks at it first. Goes through the game's own "
                        + "attack, so reach, cooldown and what mods do to either apply. Returns what was hit.",
                args -> {
                    requireNoScreen(client);
                    if (args.has("entity") || args.has("type")) {
                        look(client, args);
                    }
                    com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.updateCrosshair(client);
                    JsonElement target = Sight.hit(client, client.crosshairTarget);
                    MinecraftClientInvoker game = (MinecraftClientInvoker) client;
                    // Not holding the button clears the pause the game puts on attacks after a screen closes.
                    game.mc_puppet$handleBlockBreaking(false);
                    JsonObject json = new JsonObject();
                    json.addProperty("swung", true);
                    json.addProperty("started_breaking", game.mc_puppet$doAttack());
                    json.add("target", target);
                    return json;
                });

        ops.add("break_block", "{x, y, z, timeout_ms?: 30000}",
                "Looks at a block and holds attack on it until it breaks, as long as that takes with what is in "
                        + "hand. Refuses when the block is out of reach or something is in the way.",
                args -> breakBlock(client, waiter, args));

        ops.now("stop", "{}", "Lets go of every key binding and stops breaking.", args -> {
            KeyBinding.unpressAll();
            VirtualKeys.releaseAll();
            if (client.interactionManager != null) {
                client.interactionManager.cancelBlockBreaking();
            }
            return JsonNull.INSTANCE;
        });
    }

    // ---- looking ---------------------------------------------------------------------

    private static void look(MinecraftClient client, JsonObject args) throws Ops.Refused {
        ClientPlayerEntity player = Sight.requirePlayer(client);
        if (args.has("yaw") || args.has("pitch")) {
            player.setYaw((float) Args.decimal(args, "yaw", player.getYaw()));
            player.setPitch(MathHelper.clamp((float) Args.decimal(args, "pitch", player.getPitch()), -90, 90));
            return;
        }
        if (args.has("at") && args.get("at").isJsonObject()) {
            JsonObject at = args.getAsJsonObject("at");
            player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES,
                    new Vec3d(Args.decimal(at, "x"), Args.decimal(at, "y"), Args.decimal(at, "z")));
            return;
        }
        Entity entity = entity(client, args);
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, entity.getBoundingBox().getCenter());
    }

    private static Entity entity(MinecraftClient client, JsonObject args) throws Ops.Refused {
        ClientPlayerEntity player = Sight.requirePlayer(client);
        String uuid = Args.string(args, "entity", null);
        String type = Args.string(args, "type", null);
        if (uuid == null && type == null) {
            throw new Ops.Refused("look takes {yaw, pitch}, {at: {x,y,z}}, {entity: uuid} or {type}");
        }
        double radius = Args.decimal(args, "radius", 16);
        Entity nearest = null;
        for (Entity each : Sight.requireWorld(client).getEntities()) {
            if (each == player || !each.isAlive() || each.distanceTo(player) > radius) {
                continue;
            }
            boolean wanted = uuid != null ? each.getUuid().equals(parse(uuid))
                    : Registries.ENTITY_TYPE.getId(each.getType()).toString().equals(type);
            if (wanted && (nearest == null || each.distanceTo(player) < nearest.distanceTo(player))) {
                nearest = each;
            }
        }
        if (nearest == null) {
            throw new Ops.Refused("the client sees no living " + (uuid != null ? "entity " + uuid : type)
                    + " within " + radius);
        }
        return nearest;
    }

    private static UUID parse(String uuid) throws Ops.Refused {
        try {
            return UUID.fromString(uuid);
        } catch (IllegalArgumentException malformed) {
            throw new Ops.Refused("\"" + uuid + "\" is not a uuid");
        }
    }

    // ---- keys ------------------------------------------------------------------------

    static KeyBinding binding(MinecraftClient client, String name) throws Ops.Refused {
        GameOptions options = client.options;
        switch (name.toLowerCase(Locale.ROOT)) {
            case "forward": return options.forwardKey;
            case "back": return options.backKey;
            case "left": return options.leftKey;
            case "right": return options.rightKey;
            case "jump": return options.jumpKey;
            case "sneak": return options.sneakKey;
            case "sprint": return options.sprintKey;
            case "attack": return options.attackKey;
            case "use": return options.useKey;
            case "drop": return options.dropKey;
            case "swap_hands": return options.swapHandsKey;
            case "inventory": return options.inventoryKey;
            case "pick_item": return options.pickItemKey;
            default:
                break;
        }
        for (KeyBinding each : options.allKeys) {
            if (each.getTranslationKey().equalsIgnoreCase(name)) {
                return each;
            }
        }
        throw new Ops.Refused("no key binding is called \"" + name + "\"; \"bindings\" lists them");
    }

    private static CompletableFuture<JsonElement> hold(MinecraftClient client, Waiter waiter, JsonObject args)
            throws Ops.Refused {
        Sight.requirePlayer(client);
        requireNoScreen(client);
        if (!args.has("keys") || !args.get("keys").isJsonArray()) {
            throw new Ops.Refused("hold takes \"keys\": an array of binding names");
        }
        List<KeyBinding> held = new ArrayList<>();
        for (JsonElement each : args.getAsJsonArray("keys")) {
            held.add(binding(client, each.getAsString()));
        }
        int ticks = Math.max(1, Math.min(20 * 60, Args.integer(args, "ticks")));
        int[] left = {ticks};
        // The first press is a press: what waits for a key to go down, rather than be down, hears it.
        for (KeyBinding binding : held) {
            if (!binding.isUnbound()) {
                KeyBinding.onKeyPressed(InputUtil.fromTranslationKey(binding.getBoundKeyTranslationKey()));
            }
        }
        return waiter.until("the keys to have been held", ticks * 50L + 5000, () -> {
            if (client.player == null) {
                held.forEach(binding -> binding.setPressed(false));
                throw new IllegalStateException("the world went away while keys were held");
            }
            if (left[0]-- > 0) {
                // Every tick: opening a screen or losing focus lets go of everything.
                held.forEach(binding -> binding.setPressed(true));
                return null;
            }
            held.forEach(binding -> binding.setPressed(false));
            return where(client.player);
        });
    }

    private static JsonObject where(ClientPlayerEntity player) {
        JsonObject json = new JsonObject();
        json.add("pos", GameJson.pos(player.getPos()));
        json.addProperty("yaw", Layout.round(player.getYaw()));
        json.addProperty("pitch", Layout.round(player.getPitch()));
        json.addProperty("on_ground", player.isOnGround());
        return json;
    }

    // ---- walking ---------------------------------------------------------------------

    private static CompletableFuture<JsonElement> moveTo(MinecraftClient client, Waiter waiter, JsonObject args)
            throws Ops.Refused {
        Sight.requirePlayer(client);
        requireNoScreen(client);
        double x = Args.decimal(args, "x");
        double z = Args.decimal(args, "z");
        double within = Math.max(0.2, Args.decimal(args, "within", 0.6));
        boolean sprint = Args.flag(args, "sprint", false);
        GameOptions options = client.options;
        Runnable letGo = () -> {
            options.forwardKey.setPressed(false);
            options.jumpKey.setPressed(false);
            options.sprintKey.setPressed(false);
        };
        CompletableFuture<JsonElement> walked = waiter.until(
                () -> "the player to reach " + x + ", " + z + "; it is at "
                        + (client.player == null ? "nowhere" : GameJson.pos(client.player.getPos())),
                Args.timeout(args, 15000), () -> {
                    ClientPlayerEntity player = client.player;
                    if (player == null) {
                        throw new IllegalStateException("the world went away on the way");
                    }
                    double dx = x - player.getX();
                    double dz = z - player.getZ();
                    double off = Math.sqrt(dx * dx + dz * dz);
                    if (off <= within) {
                        letGo.run();
                        JsonObject json = where(player);
                        json.addProperty("off_by", Layout.round(off));
                        return json;
                    }
                    player.setYaw((float) (MathHelper.atan2(dz, dx) * 180 / Math.PI) - 90);
                    player.setPitch(0);
                    options.forwardKey.setPressed(true);
                    options.sprintKey.setPressed(sprint);
                    options.jumpKey.setPressed(player.horizontalCollision && player.isOnGround()
                            || player.isTouchingWater());
                    return null;
                });
        walked.whenComplete((ignored, failure) -> letGo.run());
        return walked;
    }

    // ---- breaking --------------------------------------------------------------------

    private static CompletableFuture<JsonElement> breakBlock(MinecraftClient client, Waiter waiter,
                                                             JsonObject args) throws Ops.Refused {
        ClientPlayerEntity player = Sight.requirePlayer(client);
        requireNoScreen(client);
        BlockPos pos = new BlockPos(Args.integer(args, "x"), Args.integer(args, "y"), Args.integer(args, "z"));
        if (Sight.requireWorld(client).getBlockState(pos).isAir()) {
            throw new Ops.Refused("there is no block at " + pos.toShortString());
        }
        String was = Registries.BLOCK.getId(client.world.getBlockState(pos).getBlock()).toString();
        player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(pos));
        com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.updateCrosshair(client);
        if (!(client.crosshairTarget instanceof BlockHitResult aimed) || !aimed.getBlockPos().equals(pos)) {
            String instead = client.crosshairTarget instanceof BlockHitResult other
                    ? "the block at " + other.getBlockPos().toShortString()
                    : client.crosshairTarget instanceof EntityHitResult ? "an entity" : "nothing within reach";
            throw new Ops.Refused("looking at " + pos.toShortString() + " the crosshair is on " + instead);
        }
        MinecraftClientInvoker game = (MinecraftClientInvoker) client;
        game.mc_puppet$handleBlockBreaking(false);
        int[] ticks = {0};
        CompletableFuture<JsonElement> broken = waiter.until(() -> was + " at " + pos.toShortString()
                + " to break", Args.timeout(args, 30000), () -> {
                    if (client.player == null || client.world == null) {
                        throw new IllegalStateException("the world went away while breaking");
                    }
                    if (!client.world.getBlockState(pos).isOf(Registries.BLOCK.get(
                            net.minecraft.util.Identifier.tryParse(was)))) {
                        JsonObject json = new JsonObject();
                        json.addProperty("broke", was);
                        json.addProperty("ticks", ticks[0]);
                        return json;
                    }
                    ticks[0]++;
                    client.player.lookAt(EntityAnchorArgumentType.EntityAnchor.EYES, Vec3d.ofCenter(pos));
                    com.modrinth.pain_o_d.mc_puppet.compat.ClientCompat.updateCrosshair(client);
                    game.mc_puppet$handleBlockBreaking(true);
                    return null;
                });
        broken.whenComplete((ignored, failure) -> {
            if (client.player != null) {
                game.mc_puppet$handleBlockBreaking(false);
            }
        });
        return broken;
    }

    private static void requireNoScreen(MinecraftClient client) throws Ops.Refused {
        if (client.currentScreen != null) {
            throw new Ops.Refused("a screen is open (" + client.currentScreen.getClass().getSimpleName()
                    + "); the character does not move behind one. close_screen first");
        }
    }
}
