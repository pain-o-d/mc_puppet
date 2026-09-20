package com.modrinth.pain_o_d.mc_puppet.mixin;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import org.spongepowered.asm.mixin.Mixin;
import org.spongepowered.asm.mixin.injection.At;
import org.spongepowered.asm.mixin.injection.Inject;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfo;
import org.spongepowered.asm.mixin.injection.callback.CallbackInfoReturnable;

import com.modrinth.pain_o_d.mc_puppet.client.FrameCapture;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.client.gui.tooltip.TooltipPositioner;
import net.minecraft.entity.LivingEntity;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.text.Text;
import net.minecraft.util.Identifier;
import net.minecraft.world.World;

/**
 * Writes down what a frame draws, while {@link FrameCapture} is asked to.
 *
 * <p>Each hook is on the method the public variants all end in, so a string
 * is written down once however it was drawn. Every one is optional: a loader
 * that patched one of these methods out of reach loses that kind of record
 * and nothing else, and the game starts either way.
 */
@Mixin(DrawContext.class)
public abstract class DrawContextMixin {

    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Ljava/lang/String;IIIZ)I",
            at = @At("HEAD"), require = 0)
    private void mc_puppet$string(TextRenderer renderer, String text, int x, int y, int colour, boolean shadow,
                                  CallbackInfoReturnable<Integer> info) {
        if (FrameCapture.active()) {
            FrameCapture.text((DrawContext) (Object) this, renderer, text, x, y, colour);
        }
    }

    @Inject(method = "drawText(Lnet/minecraft/client/font/TextRenderer;Lnet/minecraft/text/OrderedText;IIIZ)I",
            at = @At("HEAD"), require = 0)
    private void mc_puppet$ordered(TextRenderer renderer, OrderedText text, int x, int y, int colour,
                                   boolean shadow, CallbackInfoReturnable<Integer> info) {
        if (FrameCapture.active()) {
            FrameCapture.text((DrawContext) (Object) this, renderer, text, x, y, colour);
        }
    }

    @Inject(method = "drawItem(Lnet/minecraft/entity/LivingEntity;Lnet/minecraft/world/World;"
            + "Lnet/minecraft/item/ItemStack;IIII)V", at = @At("HEAD"), require = 0)
    private void mc_puppet$item(LivingEntity entity, World world, ItemStack stack, int x, int y, int seed, int z,
                                CallbackInfo info) {
        if (FrameCapture.active()) {
            FrameCapture.item((DrawContext) (Object) this, stack, x, y);
        }
    }

    @Inject(method = "drawGuiTexture(Lnet/minecraft/util/Identifier;IIIII)V", at = @At("HEAD"), require = 0)
    private void mc_puppet$sprite(Identifier texture, int x, int y, int z, int width, int height,
                                  CallbackInfo info) {
        if (FrameCapture.active()) {
            FrameCapture.sprite((DrawContext) (Object) this, texture, x, y, width, height);
        }
    }

    @Inject(method = "drawGuiTexture(Lnet/minecraft/util/Identifier;IIIIIIIIII)V", at = @At("HEAD"), require = 0)
    private void mc_puppet$spritePart(Identifier texture, int textureWidth, int textureHeight, int u, int v, int x,
                                      int y, int z, int width, int height, CallbackInfo info) {
        if (FrameCapture.active()) {
            FrameCapture.sprite((DrawContext) (Object) this, texture, x, y, width, height);
        }
    }

    @Inject(method = "drawTexture(Lnet/minecraft/util/Identifier;IIIIIIIFFII)V", at = @At("HEAD"), require = 0)
    private void mc_puppet$texture(Identifier texture, int x1, int x2, int y1, int y2, int z, int regionWidth,
                                   int regionHeight, float u, float v, int textureWidth, int textureHeight,
                                   CallbackInfo info) {
        if (FrameCapture.active()) {
            FrameCapture.sprite((DrawContext) (Object) this, texture, x1, y1, x2 - x1, y2 - y1);
        }
    }

    // TooltipData moved packages between versions; the handler only needs its Optional, which erases to
    // the same descriptor in both, so it is left unnamed and this mixin is shared.
    //
    // A tooltip's lines are drawn by its components, past drawText, so they are
    // taken where they are handed over.

    @Inject(method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;Ljava/util/Optional;II)V",
            at = @At("HEAD"), require = 0)
    private void mc_puppet$tooltip(TextRenderer renderer, List<Text> lines, Optional<?> data, int x,
                                   int y, CallbackInfo info) {
        if (FrameCapture.active()) {
            List<String> said = new ArrayList<>();
            lines.forEach(line -> said.add(line.getString()));
            FrameCapture.tooltip(said, x, y);
        }
    }

    @Inject(method = "drawOrderedTooltip", at = @At("HEAD"), require = 0)
    private void mc_puppet$orderedTooltip(TextRenderer renderer, List<? extends OrderedText> lines, int x, int y,
                                          CallbackInfo info) {
        if (FrameCapture.active()) {
            List<String> said = new ArrayList<>();
            lines.forEach(line -> said.add(FrameCapture.plain(line)));
            FrameCapture.tooltip(said, x, y);
        }
    }

    @Inject(method = "drawTooltip(Lnet/minecraft/client/font/TextRenderer;Ljava/util/List;"
            + "Lnet/minecraft/client/gui/tooltip/TooltipPositioner;II)V", at = @At("HEAD"), require = 0)
    private void mc_puppet$placedTooltip(TextRenderer renderer, List<OrderedText> lines,
                                         TooltipPositioner positioner, int x, int y, CallbackInfo info) {
        if (FrameCapture.active()) {
            List<String> said = new ArrayList<>();
            lines.forEach(line -> said.add(FrameCapture.plain(line)));
            FrameCapture.tooltip(said, x, y);
        }
    }
}
