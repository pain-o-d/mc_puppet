package com.modrinth.pain_o_d.mc_puppet.core;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.nio.file.StandardOpenOption;
import java.time.Instant;

import com.google.gson.JsonObject;

/**
 * Everything the bridge was asked to do, in a file.
 *
 * <p>The thing driving the game may be an agent nobody is watching. What it
 * did should be readable afterwards by the person whose game it was: one line
 * a request, with when, what, how it ended and how long it took. The token
 * is never written, and long arguments are cut.
 *
 * <p>The one being written about is the one supplying the words, so none of
 * them is trusted to be a line. An operation's name is cut short and loses
 * its control characters, since a line break inside it wrote whole lines of
 * its own choosing. What runs inside a {@code batch} or a {@code wait_until}
 * has a line of its own, since a step behind three hundred characters of
 * padding was otherwise never named at all. A long string is cut where it
 * is, and says by how much, so that padding in one argument cannot push the
 * next one out of sight. And the file is turned over while it is being
 * written, not only when the game starts. All found by the review before the
 * first release.
 *
 * <p>Writing must never be the reason an operation fails, so every failure
 * here is swallowed after the first is reported.
 */
public final class Audit {

    static final long ROTATE_AT_BYTES = 4L << 20;
    /** A whole line's arguments; each string among them is cut at {@link #MAX_STRING} first. */
    static final int MAX_ARGS = 2000;
    static final int MAX_STRING = 200;
    static final int MAX_OP = 80;

    private final Path file;
    private final Path previous;
    private long size;
    private boolean complained;

    public Audit(Path directory, String side) {
        this.file = directory.resolve("audit-" + side + ".log");
        this.previous = directory.resolve("audit-" + side + ".1.log");
        try {
            this.size = Files.exists(file) ? Files.size(file) : 0;
        } catch (IOException unknown) {
            this.size = 0;
        }
        turnOverIfFull();
    }

    private void turnOverIfFull() {
        if (size <= ROTATE_AT_BYTES) {
            return;
        }
        try {
            Files.move(file, previous, StandardCopyOption.REPLACE_EXISTING);
            size = 0;
        } catch (IOException unimportant) {
            // An old log that could not be moved is appended to instead.
        }
    }

    public Path file() {
        return file;
    }

    public void wrote(String op, JsonObject args, Throwable failure, long millis) {
        write(plain(op, MAX_OP), args, failure, millis);
    }

    /** A step of a batch, or what a wait_until polls: named under what it ran inside. */
    public void wroteInner(String within, String op, JsonObject args, Throwable failure, long millis) {
        write("  " + within + "> " + plain(op, MAX_OP), args, failure, millis);
    }

    private synchronized void write(String what, JsonObject args, Throwable failure, long millis) {
        String given = args == null ? "{}" : cut(args).toString();
        if (given.length() > MAX_ARGS) {
            given = given.substring(0, MAX_ARGS) + "…(+" + (given.length() - MAX_ARGS) + " characters)";
        }
        String line = Instant.now() + " " + what + " " + given + " -> "
                + (failure == null ? "ok" : "refused: " + plain(Ops.messageOf(failure), MAX_ARGS))
                + " (" + millis + "ms)\n";
        try {
            turnOverIfFull();
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
            size += line.getBytes(StandardCharsets.UTF_8).length;
        } catch (IOException | RuntimeException failed) {
            if (!complained) {
                complained = true;
                org.slf4j.LoggerFactory.getLogger("mc_puppet").warn("MC Puppet cannot write its audit log {}: {}",
                        file, failed.toString());
            }
        }
    }

    /** Words from outside as part of one line: no control characters, and no longer than {@code most}. */
    static String plain(String words, int most) {
        if (words == null) {
            return "null";
        }
        StringBuilder out = new StringBuilder(Math.min(words.length(), most));
        for (int index = 0; index < words.length() && out.length() < most; index++) {
            char letter = words.charAt(index);
            out.append(Character.isISOControl(letter) || letter == '\u2028' || letter == '\u2029' ? ' ' : letter);
        }
        return words.length() > most ? out + "…(+" + (words.length() - most) + ")" : out.toString();
    }

    /** The same arguments with every long string cut where it is, so that one cannot hide the next. */
    static com.google.gson.JsonElement cut(com.google.gson.JsonElement given) {
        if (given.isJsonObject()) {
            JsonObject out = new JsonObject();
            for (var entry : given.getAsJsonObject().entrySet()) {
                out.add(plain(entry.getKey(), MAX_STRING), cut(entry.getValue()));
            }
            return out;
        }
        if (given.isJsonArray()) {
            com.google.gson.JsonArray out = new com.google.gson.JsonArray();
            for (var each : given.getAsJsonArray()) {
                out.add(cut(each));
            }
            return out;
        }
        if (given.isJsonPrimitive() && given.getAsJsonPrimitive().isString()
                && given.getAsString().length() > MAX_STRING) {
            return new com.google.gson.JsonPrimitive(plain(given.getAsString(), MAX_STRING));
        }
        return given;
    }
}
