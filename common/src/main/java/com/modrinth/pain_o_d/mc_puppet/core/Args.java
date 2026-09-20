package com.modrinth.pain_o_d.mc_puppet.core;

import com.google.gson.JsonObject;

/** Reads an operation's arguments, refusing in words when one is missing or the wrong shape. */
public final class Args {

    private Args() {
    }

    public static String string(JsonObject args, String key) throws Ops.Refused {
        if (!args.has(key) || !args.get(key).isJsonPrimitive()) {
            throw new Ops.Refused("\"" + key + "\" is required");
        }
        return args.get(key).getAsString();
    }

    public static String string(JsonObject args, String key, String fallback) {
        return args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsString() : fallback;
    }

    public static int integer(JsonObject args, String key) throws Ops.Refused {
        try {
            return Integer.parseInt(string(args, key));
        } catch (NumberFormatException notANumber) {
            throw new Ops.Refused("\"" + key + "\" is a whole number");
        }
    }

    public static int integer(JsonObject args, String key, int fallback) throws Ops.Refused {
        return args.has(key) ? integer(args, key) : fallback;
    }

    public static long number(JsonObject args, String key, long fallback) throws Ops.Refused {
        if (!args.has(key)) {
            return fallback;
        }
        try {
            return (long) Double.parseDouble(string(args, key));
        } catch (NumberFormatException notANumber) {
            throw new Ops.Refused("\"" + key + "\" is a number");
        }
    }

    public static double decimal(JsonObject args, String key) throws Ops.Refused {
        try {
            return Double.parseDouble(string(args, key));
        } catch (NumberFormatException notANumber) {
            throw new Ops.Refused("\"" + key + "\" is a number");
        }
    }

    public static boolean flag(JsonObject args, String key, boolean fallback) {
        return args.has(key) && args.get(key).isJsonPrimitive() ? args.get(key).getAsBoolean() : fallback;
    }

    public static double decimal(JsonObject args, String key, double fallback) throws Ops.Refused {
        return args.has(key) && !args.get(key).isJsonNull() ? decimal(args, key) : fallback;
    }

    /** {@code timeout_ms}, or an operation's own idea of how long it may take. */
    public static long timeout(JsonObject args, long fallback) throws Ops.Refused {
        return args.has("timeout_ms") ? timeout(args) : fallback;
    }

    public static long timeout(JsonObject args) throws Ops.Refused {
        return number(args, "timeout_ms", Waiter.DEFAULT_TIMEOUT_MS);
    }
}
