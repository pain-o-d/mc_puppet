package com.curseforge.pain_o_d.mc_puppet.core;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParseException;

/**
 * The wire format: one JSON object a line, in both directions.
 *
 * <pre>
 * -&gt; {"id": 7, "token": "…", "op": "screen.state", "args": {…}}
 * &lt;- {"id": 7, "ok": true, "result": {…}}
 * &lt;- {"id": 7, "ok": false, "error": "no screen is open"}
 * </pre>
 *
 * <p>Lines rather than length prefixes because every language reads a line,
 * and a bridge meant for test tooling is only worth having if a ten-line
 * script can talk to it. A JSON document never contains a raw newline, so a
 * line is always a whole message.
 *
 * <p>Plain Java on purpose: this is the part that can be tested without a
 * game, and it is the part an attacker would talk to.
 */
public final class Protocol {

    private Protocol() {
    }

    /** No pretty printing: a response has to stay on its line. */
    /**
     * The version of what is said over the socket. The mod and the tools that
     * talk to it are installed separately and will not always be of an age;
     * each says which version it speaks, so that a mismatch is reported as
     * one, and not as an operation that mysteriously is not there. Raised
     * when an existing operation changes what it takes or answers, not when
     * one is added: {@code help} is how a tool learns what there is.
     */
    public static final int VERSION = 1;

    public static final Gson GSON = new GsonBuilder().disableHtmlEscaping().serializeNulls().create();

    /** Longest request line read. A batch of a few hundred steps fits with room to spare. */
    public static final int MAX_LINE_BYTES = 1 << 20;

    /** What one line asked for. */
    public record Request(JsonElement id, String token, String op, JsonObject args) {
    }

    /** A line that was not a request, with why, so the caller can be told. */
    public static final class Malformed extends Exception {
        private static final long serialVersionUID = 1L;

        public Malformed(String message) {
            super(message);
        }
    }

    public static Request parse(String line) throws Malformed {
        JsonObject object;
        try {
            JsonElement parsed = GSON.fromJson(line, JsonElement.class);
            if (parsed == null || !parsed.isJsonObject()) {
                throw new Malformed("a request is a JSON object");
            }
            object = parsed.getAsJsonObject();
        } catch (JsonParseException notJson) {
            throw new Malformed("not JSON: " + notJson.getMessage());
        }
        if (!object.has("op") || !object.get("op").isJsonPrimitive()) {
            throw new Malformed("a request names an \"op\"");
        }
        JsonObject args = new JsonObject();
        if (object.has("args")) {
            if (!object.get("args").isJsonObject()) {
                throw new Malformed("\"args\" is an object");
            }
            args = object.getAsJsonObject("args");
        }
        String token = object.has("token") && object.get("token").isJsonPrimitive()
                ? object.get("token").getAsString() : "";
        return new Request(object.get("id"), token, object.get("op").getAsString(), args);
    }

    public static String ok(JsonElement id, JsonElement result) {
        JsonObject response = new JsonObject();
        response.add("id", id);
        response.addProperty("ok", true);
        response.add("result", result);
        return GSON.toJson(response);
    }

    public static String error(JsonElement id, String message) {
        JsonObject response = new JsonObject();
        response.add("id", id);
        response.addProperty("ok", false);
        response.addProperty("error", message == null ? "unknown error" : message);
        return GSON.toJson(response);
    }

    /**
     * Whether a presented token is the expected one, in time that does not
     * depend on how much of it was right. Loopback only and a fresh token
     * every start make a timing attack far-fetched; this costs nothing.
     */
    public static boolean tokenMatches(String expected, String presented) {
        if (expected == null || expected.isEmpty() || presented == null) {
            return false;
        }
        return MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8),
                presented.getBytes(StandardCharsets.UTF_8));
    }
}
