package com.modrinth.pain_o_d.mc_puppet.compat;

import java.util.function.BiConsumer;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.village.TradeOffer;
import net.minecraft.world.World;

/**
 * What Minecraft 1.21.1 does its own way. <b>This file is the 1.21.1 one</b>;
 * {@code mc1.20.1/common/…/compat/Compat.java} is the same class for 1.20.1,
 * and each build sees only its own. See {@code docs/MULTIVERSION.md}.
 *
 * <p>Everything here is a question the rest of the mod asks the game and the
 * two versions answer differently. Shared code never asks which version it is
 * on; it asks here. Safe on a dedicated server: nothing of the client's.
 */
public final class Compat {

    private Compat() {
    }

    /** The version this build is for, as {@code info} says it. */
    public static final String MINECRAFT = "1.21.1";

    /** The loader this build is for when it is not Fabric. */
    public static final String OTHER_LOADER = "neoforge";

    /** What a stack carries beyond being its item, as text, or {@code null}: components here, NBT before 1.20.5. */
    public static String dataOf(ItemStack stack) {
        return stack.getComponentChanges().isEmpty() ? null : stack.getComponentChanges().toString();
    }

    /** What an offer asks for first, as the screen shows it: with any discount and demand in it. */
    public static ItemStack firstBuy(TradeOffer offer) {
        return offer.getDisplayedFirstBuyItem();
    }

    public static ItemStack secondBuy(TradeOffer offer) {
        return offer.getDisplayedSecondBuyItem();
    }

    public static double msPerTick(MinecraftServer server) {
        return server.getAverageNanosPerTick() / 1_000_000.0;
    }

    /** A source that reports how a command ended: whether it succeeded, and with what number. */
    public static ServerCommandSource reportingTo(ServerCommandSource source, BiConsumer<Boolean, Integer> ended) {
        return source.withReturnValueConsumer(ended::accept);
    }

    public static String nbtOf(BlockEntity blockEntity, World world) {
        return blockEntity.createNbtWithIdentifyingData(world.getRegistryManager()).toString();
    }

    public static String idOf(StatusEffectInstance effect) {
        return effect.getEffectType().getKey().map(key -> key.getValue().toString()).orElse("unregistered");
    }
}
