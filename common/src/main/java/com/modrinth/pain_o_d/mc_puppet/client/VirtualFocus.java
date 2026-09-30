package com.modrinth.pain_o_d.mc_puppet.client;

/**
 * A focused window, for as long as a test's mouse gesture lasts.
 *
 * <p>The game reads mouse movement only while its window has focus: a moved
 * cursor becomes a turn of the head, or a drag on a screen, only then, and a
 * mod that reads the turn (a lever pulled by dragging the mouse) hears
 * nothing from a window behind others. While a gesture runs, the game is told
 * its window is focused and, with no screen open, that the cursor is grabbed
 * ({@code MinecraftClientMixin}, {@code MouseMixin}); the real cursor is never
 * grabbed, since a window that does not have focus has no business taking the
 * mouse from whoever is using it. Off otherwise, and then one read of a
 * volatile.
 */
public final class VirtualFocus {

    private VirtualFocus() {
    }

    private static volatile boolean on;

    public static boolean on() {
        return on;
    }

    static void begin() {
        on = true;
    }

    static void end() {
        on = false;
    }
}
