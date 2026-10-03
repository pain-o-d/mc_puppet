package com.modrinth.pain_o_d.mc_puppet.client;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.function.Supplier;

import com.modrinth.pain_o_d.mc_puppet.core.Args;
import com.modrinth.pain_o_d.mc_puppet.core.Ops;
import com.modrinth.pain_o_d.mc_puppet.core.Waiter;
import com.modrinth.pain_o_d.mc_puppet.mixin.KeyboardInvoker;
import com.modrinth.pain_o_d.mc_puppet.mixin.MouseInvoker;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.client.MinecraftClient;
import net.minecraft.client.option.KeyBinding;
import net.minecraft.client.util.InputUtil;

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
        List<Integer> requested = modifiers(args);
        try {
            for (int code : requested) if (!VirtualKeys.isHeld(code)) {
                VirtualKeys.hold(code);
                held.add(code);
            }
            return action.get();
        } finally {
            held.forEach(VirtualKeys::release);
        }
    }

    static List<Integer> modifiers(JsonObject args) throws Ops.Refused {
        List<Integer> requested = new ArrayList<>();
        if (args.has("modifiers") && args.get("modifiers").isJsonArray()) {
            for (JsonElement each : args.getAsJsonArray("modifiers")) {
                requested.add(VirtualKeys.code(each.getAsString()));
            }
        }
        return requested;
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

    /** Capture all configured bindings a real press on this physical key can enqueue. */
    static List<KeyBinding> bindingsOn(MinecraftClient client, InputUtil.Key key) {
        List<KeyBinding> found = new ArrayList<>();
        for (KeyBinding binding : client.options.allKeys) {
            if (binding.getBoundKeyTranslationKey().equals(key.getTranslationKey())) found.add(binding);
        }
        return found;
    }

    static void releaseBindings(List<KeyBinding> bindings) {
        for (KeyBinding binding : bindings) {
            binding.setPressed(false);
            // setPressed(false) alone leaves a queued attack/use/mod press for the next scope.
            while (binding.wasPressed()) { }
        }
    }

    /** Moves the cursor by so many window pixels from where the game last heard it was. */
    static void moveBy(MinecraftClient client, double dx, double dy) {
        ((MouseInvoker) client.mouse).mc_puppet$onCursorPos(client.getWindow().getHandle(),
                client.mouse.getX() + dx, client.mouse.getY() + dy);
    }

    /**
     * Whether the movement heard has been handed on. The game hands it on once
     * a frame (1.21.1: a screen's drag and the turn of the head both; 1.20.1:
     * the turn), so a button let go before then is let go where the cursor was.
     */
    static boolean settled(MinecraftClient client) {
        MouseInvoker mouse = (MouseInvoker) client.mouse;
        return mouse.mc_puppet$cursorDeltaX() == 0 && mouse.mc_puppet$cursorDeltaY() == 0;
    }

    /**
     * A gesture with a button held: {@code steps} one a tick and never two
     * in one frame, the first of which presses; then, once a frame has taken the last movement and
     * {@code after} more ticks have gone, {@code last}, which lets go. The
     * window counts as focused throughout ({@link VirtualFocus}); if it ends
     * any other way, {@code letGo} runs on the game thread.
     */
    static CompletableFuture<JsonElement> gesture(MinecraftClient client, Waiter waiter, String what,
                                                  List<Runnable> steps, int after, Runnable last,
                                                  Supplier<JsonElement> answer, Runnable letGo) throws Ops.Refused {
        return gesture(client, waiter, what, steps, after, last, answer, letGo, List.of());
    }

    static CompletableFuture<JsonElement> gesture(MinecraftClient client, Waiter waiter, String what,
                                                  List<Runnable> steps, int after, Runnable last,
                                                  Supplier<JsonElement> answer, Runnable letGo,
                                                  List<KeyBinding> queuedBindings) throws Ops.Refused {
        int[] next = {0};
        int[] waited = {0};
        boolean[] done = {false};
        var session = InputSessions.begin(client, what, client.currentScreen == null, true,
                () -> {
                    try { if (!done[0]) letGo.run(); }
                    finally { releaseBindings(queuedBindings); }
                });
        CompletableFuture<JsonElement> gesture = waiter.until(
                () -> what + (next[0] >= steps.size() && !settled(client)
                        ? " (no frame took the last movement: is the window minimised?)" : ""),
                Math.max(Waiter.DEFAULT_TIMEOUT_MS, (steps.size() + after) * 200L), () -> {
                    session.check();
                    if (next[0] < steps.size()) {
                        // Two ticks can run with no frame between them, and a frame is what hands a
                        // move on: a step waits for the last to be taken, or two moves arrive as one.
                        if (next[0] == 0 || settled(client)) {
                            steps.get(next[0]++).run();
                        }
                        return null;
                    }
                    if (!settled(client) || waited[0]++ < after) {
                        return null;
                    }
                    last.run();
                    done[0] = true;
                    return answer.get();
                });
        return session.track(gesture);
    }
}
