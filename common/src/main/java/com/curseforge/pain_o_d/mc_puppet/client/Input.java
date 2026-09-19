package com.curseforge.pain_o_d.mc_puppet.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.curseforge.pain_o_d.mc_puppet.core.Args;
import com.curseforge.pain_o_d.mc_puppet.core.Ops;
import com.curseforge.pain_o_d.mc_puppet.core.Waiter;
import com.curseforge.pain_o_d.mc_puppet.mixin.KeyboardInvoker;
import com.curseforge.pain_o_d.mc_puppet.mixin.MouseInvoker;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.MinecraftClient;

/**
 * A mouse and a keyboard nobody is holding.
 *
 * <p>Input enters the game where GLFW's callbacks do, so that everything
 * between a click and what it does is exercised: the loaders' screen events,
 * the key bindings, drag, hover. Positions are in scaled GUI pixels, the
 * space widgets and slots live in; the game is told window pixels, as a real
 * mouse would tell it.
 *
 * <p>The real mouse still works. If it moves over the window during a test it
 * moves the cursor, so a position is always set in the same task as the click
 * that depends on it.
 */
final class Input {

    private Input() {
    }

    private static final int PRESS = 1;
    private static final int RELEASE = 0;

    static void moveTo(MinecraftClient client, double guiX, double guiY) {
        var window = client.getWindow();
        double x = guiX * window.getWidth() / Math.max(1, window.getScaledWidth());
        double y = guiY * window.getHeight() / Math.max(1, window.getScaledHeight());
        ((MouseInvoker) client.mouse).mc_puppet$onCursorPos(window.getHandle(), x, y);
    }

    static void button(MinecraftClient client, int button, boolean down) {
        ((MouseInvoker) client.mouse).mc_puppet$onMouseButton(client.getWindow().getHandle(), button,
                down ? PRESS : RELEASE, VirtualKeys.modifierBits());
    }

    static void click(MinecraftClient client, double guiX, double guiY, int button) {
        moveTo(client, guiX, guiY);
        button(client, button, true);
        button(client, button, false);
    }

    static void scroll(MinecraftClient client, double guiX, double guiY, double amount) {
        moveTo(client, guiX, guiY);
        ((MouseInvoker) client.mouse).mc_puppet$onMouseScroll(client.getWindow().getHandle(), 0, amount);
    }

    static void key(MinecraftClient client, int code, boolean down) {
        if (down) {
            VirtualKeys.hold(code);
        } else {
            VirtualKeys.release(code);
        }
        ((KeyboardInvoker) client.keyboard).mc_puppet$onKey(client.getWindow().getHandle(), code, 0,
                down ? PRESS : RELEASE, VirtualKeys.modifierBits());
    }

    static void type(MinecraftClient client, String text) {
        text.codePoints().forEach(point -> ((KeyboardInvoker) client.keyboard)
                .mc_puppet$onChar(client.getWindow().getHandle(), point, VirtualKeys.modifierBits()));
    }

    /**
     * Holds the modifiers an operation names for as long as {@code action}
     * runs: {@code "modifiers": ["shift"]} makes a click a shift-click.
     */
    static <T> T withModifiers(JsonObject args, Supplier<T> action) throws Ops.Refused {
        List<Integer> held = new ArrayList<>();
        if (args.has("modifiers") && args.get("modifiers").isJsonArray()) {
            for (JsonElement each : args.getAsJsonArray("modifiers")) {
                int code = VirtualKeys.code(each.getAsString());
                if (!VirtualKeys.isHeld(code)) {
                    VirtualKeys.hold(code);
                    held.add(code);
                }
            }
        }
        try {
            return action.get();
        } finally {
            held.forEach(VirtualKeys::release);
        }
    }

    /**
     * Runs steps one a tick and answers after the last. A drag is three
     * things the game hears on different ticks: down, moved, up.
     */
    static CompletableFuture<JsonElement> overTicks(Waiter waiter, String what, List<Runnable> steps,
                                                    Supplier<JsonElement> answer) {
        int[] next = {0};
        return waiter.until(what, Math.max(Waiter.DEFAULT_TIMEOUT_MS, steps.size() * 200L), () -> {
            if (next[0] < steps.size()) {
                steps.get(next[0]++).run();
                return null;
            }
            return answer.get();
        });
    }

    static int buttonOf(JsonObject args) throws Ops.Refused {
        return Args.integer(args, "button", 0);
    }
}
