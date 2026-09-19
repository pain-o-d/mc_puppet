package com.curseforge.pain_o_d.mc_puppet.client;

import java.util.Locale;

import com.curseforge.pain_o_d.mc_puppet.mixin.HandledScreenAccessor;
import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

import dev.architectury.event.CompoundEventResult;
import dev.architectury.event.EventResult;
import dev.architectury.event.events.client.ClientGuiEvent;
import dev.architectury.event.events.client.ClientScreenInputEvent;
import dev.architectury.event.events.common.InteractionEvent;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.ingame.HandledScreen;
import net.minecraft.client.gui.widget.ClickableWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.registry.Registries;
import net.minecraft.screen.slot.Slot;

/**
 * Play through it once by hand; get the scenario.
 *
 * <p>Writing a scenario is mostly finding out what things are called: which
 * widget, which slot, what the screen that opened is. While recording, what
 * the player does is written down in those terms rather than as coordinates -
 * a click on a button is {@code click_widget} by its text, a click on a slot
 * is {@code click_at} by its number with the modifiers held, a screen that
 * opens is a wait for it by a name that survives a release build - so that
 * the result still works at another window size, and reads as what was meant.
 *
 * <p>It records screens, and using an entity or a block to get to one.
 * Walking about is not recorded: a scenario should say {@code move_to}.
 * What comes out has no expectations in it. Those are the test, and only its
 * author knows them; the steps are the part that is tedious.
 */
public final class Recorder {

    private Recorder() {
    }

    private static volatile boolean recording;
    private static JsonArray steps = new JsonArray();
    private static StringBuilder typed = new StringBuilder();
    private static String lastScreen = "";

    public static void init() {
        ClientScreenInputEvent.MOUSE_CLICKED_PRE.register((client, screen, x, y, button) -> {
            if (recording) {
                clicked(client, screen, x, y, button);
            }
            return EventResult.pass();
        });
        ClientScreenInputEvent.CHAR_TYPED_PRE.register((client, screen, character, modifiers) -> {
            if (recording) {
                typed.append(character);
            }
            return EventResult.pass();
        });
        ClientScreenInputEvent.KEY_PRESSED_PRE.register((client, screen, code, scan, modifiers) -> {
            if (recording) {
                pressed(code);
            }
            return EventResult.pass();
        });
        ClientGuiEvent.SET_SCREEN.register(screen -> {
            if (recording) {
                opened(screen);
            }
            return CompoundEventResult.pass();
        });
        InteractionEvent.INTERACT_ENTITY.register((player, entity, hand) -> {
            if (recording && player.getWorld().isClient) {
                JsonObject args = new JsonObject();
                args.addProperty("type", Registries.ENTITY_TYPE.getId(entity.getType()).toString());
                add("use_entity", args, null);
            }
            return EventResult.pass();
        });
        InteractionEvent.RIGHT_CLICK_BLOCK.register((player, hand, pos, side) -> {
            if (recording && player.getWorld().isClient && hand == net.minecraft.util.Hand.MAIN_HAND) {
                JsonObject args = new JsonObject();
                args.addProperty("x", pos.getX());
                args.addProperty("y", pos.getY());
                args.addProperty("z", pos.getZ());
                args.addProperty("side", side.asString());
                add("use_block", args, Registries.BLOCK.getId(player.getWorld().getBlockState(pos).getBlock())
                        .toString());
            }
            return EventResult.pass();
        });
    }

    static synchronized void start() {
        steps = new JsonArray();
        typed = new StringBuilder();
        Screen open = MinecraftClient.getInstance().currentScreen;
        lastScreen = open == null ? "" : nameOf(open);
        recording = true;
    }

    /** Stops, and returns what was recorded as a scenario. */
    static synchronized JsonObject stop(String name) {
        recording = false;
        flushTyped();
        JsonObject scenario = new JsonObject();
        scenario.addProperty("name", name);
        scenario.addProperty("about", "Recorded by hand. The steps are what was done; add \"expect\" to the ones "
                + "that matter, and \"setup\" and \"teardown\" for what the world must hold.");
        scenario.add("steps", steps);
        steps = new JsonArray();
        return scenario;
    }

    static boolean recording() {
        return recording;
    }

    static synchronized int count() {
        return steps.size();
    }

    // ---- what happened, in a scenario's words ----------------------------------------

    private static synchronized void clicked(MinecraftClient client, Screen screen, double x, double y,
                                             int button) {
        flushTyped();
        JsonObject args = new JsonObject();
        String note = null;
        ClickableWidget widget = widgetAt(screen, x, y);
        Slot slot = widget == null ? slotAt(screen, x, y) : null;
        if (widget != null && !(widget instanceof TextFieldWidget) && !widget.getMessage().getString().isBlank()) {
            args.addProperty("text", widget.getMessage().getString());
            finish("click_widget", args, button, null);
            return;
        }
        if (widget != null) {
            args.addProperty("widget", String.valueOf(ClientOps.widgetsOf(screen).indexOf(widget)));
            note = widget instanceof TextFieldWidget ? "a text field" : "a widget without words";
        } else if (slot != null) {
            args.addProperty("slot", slot.id);
            note = slot.hasStack() ? Registries.ITEM.getId(slot.getStack().getItem()).toString() : "an empty slot";
        } else {
            args.addProperty("x", Math.round(x));
            args.addProperty("y", Math.round(y));
            note = "neither a widget nor a slot: a place, which moves with the window's size";
        }
        finish("click_at", args, button, note);
    }

    private static void finish(String op, JsonObject args, int button, String note) {
        if (button != 0) {
            args.addProperty("button", button);
        }
        JsonArray modifiers = new JsonArray();
        if (Screen.hasShiftDown()) {
            modifiers.add("shift");
        }
        if (Screen.hasControlDown()) {
            modifiers.add("control");
        }
        if (Screen.hasAltDown()) {
            modifiers.add("alt");
        }
        if (!modifiers.isEmpty()) {
            args.add("modifiers", modifiers);
        }
        add(op, args, note);
    }

    private static synchronized void pressed(int code) {
        String name = switch (code) {
            case 256 -> "escape";
            case 257, 335 -> "enter";
            case 258 -> "tab";
            case 259 -> "backspace";
            case 261 -> "delete";
            case 262 -> "right";
            case 263 -> "left";
            case 264 -> "down";
            case 265 -> "up";
            default -> null;
        };
        if (name == null) {
            // A letter arrives again as a typed character, which is the one worth keeping.
            return;
        }
        if (name.equals("backspace") && typed.length() > 0) {
            typed.setLength(typed.length() - 1);
            return;
        }
        flushTyped();
        JsonObject args = new JsonObject();
        args.addProperty("key", name);
        add("key", args, null);
    }

    private static synchronized void opened(Screen screen) {
        String name = screen == null ? "" : nameOf(screen);
        if (name.equals(lastScreen)) {
            return;
        }
        boolean fromTheWorld = lastScreen.isEmpty();
        lastScreen = name;
        flushTyped();
        if (fromTheWorld && name.equals("minecraft:player_inventory")) {
            // Opened with a key, in the world, where no screen was there to hear it.
            JsonObject key = new JsonObject();
            key.addProperty("key", "inventory");
            add("tap", key, null);
        }
        JsonObject args = new JsonObject();
        if (screen == null) {
            args.addProperty("for", "no_screen");
        } else {
            args.addProperty("for", "screen");
            args.addProperty("value", name);
        }
        args.addProperty("timeout_ms", 10000);
        add("wait", args, null);
    }

    /** The name a wait should use: what is the same in a shipped jar first, a class name last. */
    private static String nameOf(Screen screen) {
        String handlerType = ClientOps.handlerTypeOf(screen);
        if (handlerType != null) {
            return handlerType;
        }
        String titleKey = ClientOps.titleKeyOf(screen);
        if (titleKey != null) {
            return titleKey.toLowerCase(Locale.ROOT);
        }
        return screen.getClass().getSimpleName().toLowerCase(Locale.ROOT);
    }

    private static void flushTyped() {
        if (typed.length() == 0) {
            return;
        }
        JsonObject args = new JsonObject();
        args.addProperty("text", typed.toString());
        typed = new StringBuilder();
        add("type", args, null);
    }

    private static void add(String op, JsonObject args, String note) {
        JsonObject step = new JsonObject();
        step.addProperty("op", op);
        step.add("args", args);
        if (note != null) {
            step.addProperty("note", note);
        }
        steps.add(step);
    }

    private static ClickableWidget widgetAt(Screen screen, double x, double y) {
        ClickableWidget found = null;
        for (ClickableWidget widget : ClientOps.widgetsOf(screen)) {
            if (widget.visible && x >= widget.getX() && x < widget.getX() + widget.getWidth()
                    && y >= widget.getY() && y < widget.getY() + widget.getHeight()) {
                // The last drawn is on top, and is the one a click reaches.
                found = widget;
            }
        }
        return found;
    }

    private static Slot slotAt(Screen screen, double x, double y) {
        if (!(screen instanceof HandledScreen<?> handled)) {
            return null;
        }
        HandledScreenAccessor bounds = (HandledScreenAccessor) handled;
        for (Slot slot : handled.getScreenHandler().slots) {
            double left = bounds.mc_puppet$x() + slot.x;
            double top = bounds.mc_puppet$y() + slot.y;
            if (slot.isEnabled() && x >= left - 1 && x < left + 17 && y >= top - 1 && y < top + 17) {
                return slot;
            }
        }
        return null;
    }
}
