package com.modrinth.pain_o_d.mc_puppet.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;

import org.joml.Matrix4f;
import org.joml.Vector3f;

import com.modrinth.pain_o_d.mc_puppet.core.GameJson;
import com.modrinth.pain_o_d.mc_puppet.core.Layout;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import net.minecraft.client.font.TextRenderer;
import net.minecraft.client.gui.DrawContext;
import net.minecraft.item.ItemStack;
import net.minecraft.text.OrderedText;
import net.minecraft.util.Identifier;

/**
 * One frame, as what was drawn in it.
 *
 * <p>A screen draws most of what it shows without widgets: a trading screen's
 * prices, its heading, the bar of a villager's level. None of that is in the
 * widget tree, and until now only a screenshot held it. While a capture is
 * asked for, the drawing calls of one frame are written down with where they
 * landed on screen, the transform in force applied, since a tooltip or a
 * scaled heading is drawn at coordinates that mean nothing without it.
 *
 * <p>Nothing is recorded unless asked: the check on the drawing path is one
 * read of a boolean. All of it happens on the render thread.
 */
public final class FrameCapture {

    private FrameCapture() {
    }

    /** What one frame drew. */
    public static final class Frame {
        final List<Layout.Box> texts = new ArrayList<>();
        final List<Integer> colours = new ArrayList<>();
        final JsonArray items = new JsonArray();
        final JsonArray sprites = new JsonArray();
        final JsonArray tooltips = new JsonArray();

        /**
         * Which texts the open screen drew: from the first of these to before
         * the second. Before them is the HUD, which the screen covers; after
         * them toasts and the like, which cover the screen. -1 with no screen.
         */
        int screenFrom = -1;
        int screenTo = -1;

        String layerOf(int index) {
            if (screenFrom < 0 || index < screenFrom) {
                return "hud";
            }
            return screenTo >= 0 && index >= screenTo ? "overlay" : "screen";
        }

        /** What a test of layout is about: the screen's own texts when one is open, else all of them. */
        List<Layout.Box> textsToJudge() {
            if (screenFrom < 0) {
                return texts;
            }
            return texts.subList(screenFrom, screenTo < 0 ? texts.size() : screenTo);
        }
    }

    /** Asked for, not yet begun: begins with the next frame, so that it is a whole one. */
    private static CompletableFuture<Frame> wanted;
    private static Frame recording;
    private static boolean active;

    /** The next whole frame. Asked twice before one is drawn, both get the same. */
    public static CompletableFuture<Frame> next() {
        if (wanted == null) {
            wanted = new CompletableFuture<>();
        }
        return wanted;
    }

    // ---- called from the mixins -----------------------------------------------------

    public static void frameBegins() {
        if (wanted != null && recording == null) {
            recording = new Frame();
            active = true;
        }
    }

    public static void frameEnds() {
        if (recording == null) {
            return;
        }
        Frame done = recording;
        CompletableFuture<Frame> asked = wanted;
        recording = null;
        wanted = null;
        active = false;
        if (asked != null) {
            asked.complete(done);
        }
    }

    public static boolean active() {
        return active;
    }

    public static void screenBegins() {
        if (recording.screenFrom < 0) {
            recording.screenFrom = recording.texts.size();
        }
    }

    public static void screenEnds() {
        recording.screenTo = recording.texts.size();
    }

    public static void text(DrawContext context, TextRenderer renderer, String text, int x, int y, int colour) {
        if (text == null || text.isEmpty()) {
            return;
        }
        float[] at = place(context, x, y);
        recording.texts.add(new Layout.Box("text", text, at[0], at[1], renderer.getWidth(text) * at[2],
                renderer.fontHeight * at[3]));
        recording.colours.add(colour);
    }

    public static void text(DrawContext context, TextRenderer renderer, OrderedText text, int x, int y, int colour) {
        text(context, renderer, plain(text), x, y, colour);
    }

    public static void item(DrawContext context, ItemStack stack, int x, int y) {
        if (stack == null || stack.isEmpty()) {
            return;
        }
        float[] at = place(context, x, y);
        JsonObject json = GameJson.stack(stack).getAsJsonObject();
        json.addProperty("x", Layout.round(at[0]));
        json.addProperty("y", Layout.round(at[1]));
        json.addProperty("w", Layout.round(16 * at[2]));
        json.addProperty("h", Layout.round(16 * at[3]));
        recording.items.add(json);
    }

    public static void sprite(DrawContext context, Identifier texture, int x, int y, int width, int height) {
        float[] at = place(context, x, y);
        JsonObject json = new JsonObject();
        json.addProperty("texture", texture.toString());
        json.addProperty("x", Layout.round(at[0]));
        json.addProperty("y", Layout.round(at[1]));
        json.addProperty("w", Layout.round(width * at[2]));
        json.addProperty("h", Layout.round(height * at[3]));
        recording.sprites.add(json);
    }

    public static void tooltip(List<String> lines, int x, int y) {
        JsonObject json = new JsonObject();
        JsonArray said = new JsonArray();
        lines.forEach(said::add);
        json.add("lines", said);
        json.addProperty("x", x);
        json.addProperty("y", y);
        recording.tooltips.add(json);
    }

    public static String plain(OrderedText text) {
        StringBuilder plain = new StringBuilder();
        text.accept((index, style, codePoint) -> {
            plain.appendCodePoint(codePoint);
            return true;
        });
        return plain.toString();
    }

    /** Where a point lands on screen under the transform in force, and how much it is scaled. */
    private static float[] place(DrawContext context, int x, int y) {
        Matrix4f matrix = context.getMatrices().peek().getPositionMatrix();
        Vector3f origin = matrix.transformPosition(new Vector3f(x, y, 0));
        Vector3f unit = matrix.transformPosition(new Vector3f(x + 1, y + 1, 0));
        return new float[] {origin.x, origin.y, Math.abs(unit.x - origin.x), Math.abs(unit.y - origin.y)};
    }

    // ---- as an answer ----------------------------------------------------------------

    static JsonArray textsJson(Frame frame) {
        JsonArray texts = new JsonArray();
        for (int index = 0; index < frame.texts.size(); index++) {
            Layout.Box box = frame.texts.get(index);
            JsonObject json = new JsonObject();
            json.addProperty("text", box.label());
            json.addProperty("x", Layout.round(box.x()));
            json.addProperty("y", Layout.round(box.y()));
            json.addProperty("w", Layout.round(box.w()));
            json.addProperty("h", Layout.round(box.h()));
            json.addProperty("colour", String.format("#%06x", frame.colours.get(index) & 0xFFFFFF));
            json.addProperty("layer", frame.layerOf(index));
            texts.add(json);
        }
        return texts;
    }
}
