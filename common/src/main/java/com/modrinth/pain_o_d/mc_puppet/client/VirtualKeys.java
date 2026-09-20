package com.modrinth.pain_o_d.mc_puppet.client;

import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import com.modrinth.pain_o_d.mc_puppet.core.Ops;

/**
 * Keys a test is holding, and what keys are called.
 *
 * <p>Read from {@code InputUtilMixin} on whatever thread asks whether a key is
 * down, hence the concurrent set.
 */
public final class VirtualKeys {

    private VirtualKeys() {
    }

    private static final Set<Integer> HELD = ConcurrentHashMap.newKeySet();

    public static boolean isHeld(int code) {
        return !HELD.isEmpty() && HELD.contains(code);
    }

    public static void hold(int code) {
        HELD.add(code);
    }

    public static void release(int code) {
        HELD.remove(code);
    }

    public static void releaseAll() {
        HELD.clear();
    }

    public static final int SHIFT = 340;
    public static final int CONTROL = 341;
    public static final int ALT = 342;

    /** GLFW's modifier bits, as a click or a key press carries them. */
    public static int modifierBits() {
        return (isHeld(SHIFT) || isHeld(344) ? 1 : 0)
                | (isHeld(CONTROL) || isHeld(345) ? 2 : 0)
                | (isHeld(ALT) || isHeld(346) ? 4 : 0);
    }

    /** A key by name, letter, digit or GLFW code. */
    public static int code(String key) throws Ops.Refused {
        String name = key.toLowerCase(Locale.ROOT);
        switch (name) {
            case "escape": return 256;
            case "enter": return 257;
            case "tab": return 258;
            case "backspace": return 259;
            case "insert": return 260;
            case "delete": return 261;
            case "right": return 262;
            case "left": return 263;
            case "down": return 264;
            case "up": return 265;
            case "page_up": return 266;
            case "page_down": return 267;
            case "home": return 268;
            case "end": return 269;
            case "space": return 32;
            case "shift": return SHIFT;
            case "control": case "ctrl": return CONTROL;
            case "alt": return ALT;
            default:
                break;
        }
        if (name.matches("f([1-9]|1[0-9]|2[0-5])")) {
            return 289 + Integer.parseInt(name.substring(1));
        }
        if (name.length() == 1) {
            char only = name.charAt(0);
            if (only >= 'a' && only <= 'z') {
                return 'A' + (only - 'a');
            }
            if (only >= '0' && only <= '9') {
                return only;
            }
        }
        try {
            return Integer.parseInt(name);
        } catch (NumberFormatException unknown) {
            throw new Ops.Refused("unknown key \"" + key + "\"; a name (escape, enter, shift, f3…), a letter, "
                    + "a digit or a GLFW code");
        }
    }
}
