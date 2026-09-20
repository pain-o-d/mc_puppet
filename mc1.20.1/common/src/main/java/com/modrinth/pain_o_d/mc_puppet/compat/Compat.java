package com.modrinth.pain_o_d.mc_puppet.compat;

import java.util.function.BiConsumer;

import net.minecraft.block.entity.BlockEntity;
import net.minecraft.entity.effect.StatusEffectInstance;
import net.minecraft.item.ItemStack;
import net.minecraft.registry.Registries;
import net.minecraft.server.MinecraftServer;
import net.minecraft.server.command.ServerCommandSource;
import net.minecraft.util.Identifier;
import net.minecraft.village.TradeOffer;
import net.minecraft.world.World;

/**
 * What Minecraft 1.20.1 does its own way. <b>This file is the 1.20.1 one</b>;
 * {@code common/…/compat/Compat.java} at the repository's root is the same
 * class for 1.21.1, and each build sees only its own. See
 * {@code docs/MULTIVERSION.md}.
 *
 * <p>Keep the two in step: a method added to one is added to the other, with
 * the same name and the same meaning, or the build that lacks it stops.
 */
public final class Compat {

    private Compat() {
    }

    public static final String MINECRAFT = "1.20.1";

    /** The loader this build is for when it is not Fabric: NeoForge begins at 1.20.2. */
    public static final String OTHER_LOADER = "forge";

    /** Before 1.20.5 a stack carries NBT, not components. */
    public static String dataOf(ItemStack stack) {
        return stack.hasNbt() ? String.valueOf(stack.getNbt()) : null;
    }

    public static ItemStack firstBuy(TradeOffer offer) {
        return offer.getAdjustedFirstBuyItem();
    }

    /** The second item is never discounted, in either version; here it has no "displayed" of its own. */
    public static ItemStack secondBuy(TradeOffer offer) {
        return offer.getSecondBuyItem();
    }

    public static double msPerTick(MinecraftServer server) {
        return server.getTickTime();
    }

    public static ServerCommandSource reportingTo(ServerCommandSource source, BiConsumer<Boolean, Integer> ended) {
        return source.withConsumer((context, success, result) -> ended.accept(success, result));
    }

    public static String nbtOf(BlockEntity blockEntity, World world) {
        return blockEntity.createNbtWithIdentifyingData().toString();
    }

    public static String idOf(StatusEffectInstance effect) {
        Identifier id = Registries.STATUS_EFFECT.getId(effect.getEffectType());
        return id == null ? "unregistered" : id.toString();
    }
}
