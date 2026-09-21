package com.modrinth.pain_o_d.mc_puppet.core;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

import net.minecraft.entity.Entity;
import net.minecraft.entity.passive.MerchantEntity;
import net.minecraft.entity.passive.VillagerEntity;
import net.minecraft.entity.player.PlayerInventory;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Vec3d;
import net.minecraft.village.TradeOffer;
import net.minecraft.village.TradeOfferList;

/**
 * The game's things as JSON, the same way on both sides.
 *
 * <p>Small on purpose. What comes back from the bridge is read by a test, or
 * by a model paying for every token, so an empty slot is left out rather than
 * written as a null and an item with nothing unusual about it is an id and a
 * count.
 */
public final class GameJson {

    private GameJson() {
    }

    /** Longest component or NBT text passed through; the rest is cut and marked. */
    public static final int MAX_TEXT = 600;

    public static JsonElement stack(ItemStack stack) {
        if (stack == null || stack.isEmpty()) {
            return JsonNull.INSTANCE;
        }
        JsonObject json = new JsonObject();
        json.addProperty("id", Registries.ITEM.getId(stack.getItem()).toString());
        json.addProperty("count", stack.getCount());
        String data = com.modrinth.pain_o_d.mc_puppet.compat.Compat.dataOf(stack);
        if (data != null) {
            json.addProperty("name", stack.getName().getString());
            // Called "components" in both versions, so that a scenario reads one name: it is NBT before 1.20.5.
            json.addProperty("components", cut(data));
        }
        if (stack.isDamaged()) {
            json.addProperty("damage", stack.getDamage());
            json.addProperty("max_damage", stack.getMaxDamage());
        }
        return json;
    }

    public static String cut(String text) {
        return text.length() <= MAX_TEXT ? text : text.substring(0, MAX_TEXT) + "…(+" + (text.length() - MAX_TEXT) + ")";
    }

    public static JsonObject pos(Vec3d pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", round(pos.x));
        json.addProperty("y", round(pos.y));
        json.addProperty("z", round(pos.z));
        return json;
    }

    public static JsonObject pos(BlockPos pos) {
        JsonObject json = new JsonObject();
        json.addProperty("x", pos.getX());
        json.addProperty("y", pos.getY());
        json.addProperty("z", pos.getZ());
        return json;
    }

    private static double round(double value) {
        return Math.round(value * 100.0) / 100.0;
    }

    /** Non-empty slots of a player's inventory, by slot number. */
    public static JsonArray inventory(PlayerInventory inventory) {
        JsonArray slots = new JsonArray();
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty()) {
                JsonObject one = stack(stack).getAsJsonObject();
                one.addProperty("slot", slot);
                slots.add(one);
            }
        }
        return slots;
    }

    /** How many of one item an inventory holds. What a test asks most. */
    public static int countOf(PlayerInventory inventory, String itemId) {
        int total = 0;
        for (int slot = 0; slot < inventory.size(); slot++) {
            ItemStack stack = inventory.getStack(slot);
            if (!stack.isEmpty() && Registries.ITEM.getId(stack.getItem()).toString().equals(itemId)) {
                total += stack.getCount();
            }
        }
        return total;
    }

    public static JsonArray offers(TradeOfferList offers) {
        JsonArray list = new JsonArray();
        int index = 0;
        for (TradeOffer offer : offers) {
            JsonObject one = new JsonObject();
            one.addProperty("index", index++);
            // The displayed stack: what the screen shows and the game checks,
            // with any demand bonus and discount already in it.
            one.add("buy", stack(com.modrinth.pain_o_d.mc_puppet.compat.Compat.firstBuy(offer)));
            ItemStack second = com.modrinth.pain_o_d.mc_puppet.compat.Compat.secondBuy(offer);
            if (!second.isEmpty()) {
                one.add("buy2", stack(second));
            }
            one.add("sell", stack(offer.getSellItem()));
            one.addProperty("uses", offer.getUses());
            one.addProperty("max_uses", offer.getMaxUses());
            if (offer.isDisabled()) {
                one.addProperty("disabled", true);
            }
            if (offer.getSpecialPrice() != 0) {
                one.addProperty("special_price", offer.getSpecialPrice());
            }
            if (offer.getDemandBonus() != 0) {
                one.addProperty("demand_bonus", offer.getDemandBonus());
            }
            list.add(one);
        }
        return list;
    }

    public static JsonObject entity(Entity entity, boolean withOffers) {
        JsonObject json = new JsonObject();
        json.addProperty("id", entity.getId());
        json.addProperty("uuid", entity.getUuidAsString());
        json.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
        json.add("pos", pos(entity.getPos()));
        // A player's name is who they are: with several on a server, a test has to say which it means.
        if (entity.hasCustomName() || entity instanceof net.minecraft.entity.player.PlayerEntity) {
            json.addProperty("name", entity.getName().getString());
        }
        json.addProperty("yaw", Math.round(entity.getYaw() * 10) / 10.0);
        json.addProperty("pitch", Math.round(entity.getPitch() * 10) / 10.0);
        json.add("velocity", pos(entity.getVelocity()));
        json.addProperty("on_ground", entity.isOnGround());
        if (entity.getVehicle() != null) {
            json.addProperty("vehicle", entity.getVehicle().getUuidAsString());
        }
        if (entity.hasPassengers()) {
            JsonArray riders = new JsonArray();
            entity.getPassengerList().forEach(rider -> riders.add(rider.getUuidAsString()));
            json.add("passengers", riders);
        }
        if (entity instanceof net.minecraft.entity.LivingEntity living) {
            json.addProperty("health", living.getHealth());
            json.addProperty("max_health", living.getMaxHealth());
            JsonObject equipment = new JsonObject();
            for (net.minecraft.entity.EquipmentSlot slot : net.minecraft.entity.EquipmentSlot.values()) {
                if (!living.getEquippedStack(slot).isEmpty()) {
                    equipment.add(slot.getName(), stack(living.getEquippedStack(slot)));
                }
            }
            if (equipment.size() > 0) {
                json.add("equipment", equipment);
            }
        }
        if (entity instanceof net.minecraft.entity.ItemEntity dropped) {
            json.add("stack", stack(dropped.getStack()));
        }
        if (entity instanceof VillagerEntity villager) {
            json.addProperty("profession",
                    Registries.VILLAGER_PROFESSION.getId(villager.getVillagerData().getProfession()).toString());
            json.addProperty("level", villager.getVillagerData().getLevel());
        }
        if (withOffers && entity instanceof MerchantEntity merchant) {
            json.add("offers", offers(merchant.getOffers()));
        }
        return json;
    }
}
