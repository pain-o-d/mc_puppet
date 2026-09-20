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
 * <p>Writing must never be the reason an operation fails, so every failure
 * here is swallowed after the first is reported.
 */
public final class Audit {

    static final long ROTATE_AT_BYTES = 4L << 20;
    static final int MAX_ARGS = 300;

    private final Path file;
    private boolean complained;

    public Audit(Path directory, String side) {
        this.file = directory.resolve("audit-" + side + ".log");
        try {
            if (Files.exists(file) && Files.size(file) > ROTATE_AT_BYTES) {
                Files.move(file, directory.resolve("audit-" + side + ".1.log"),
                        StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException unimportant) {
            // An old log that could not be moved is appended to instead.
        }
    }

    public Path file() {
        return file;
    }

    public synchronized void wrote(String op, JsonObject args, Throwable failure, long millis) {
        String given = args == null ? "{}" : args.toString();
        if (given.length() > MAX_ARGS) {
            given = given.substring(0, MAX_ARGS) + "…";
        }
        String line = Instant.now() + " " + op + " " + given + " -> "
                + (failure == null ? "ok" : "refused: " + Ops.messageOf(failure).replace('\n', ' '))
                + " (" + millis + "ms)\n";
        try {
            Files.writeString(file, line, StandardCharsets.UTF_8, StandardOpenOption.CREATE,
                    StandardOpenOption.APPEND);
        } catch (IOException | RuntimeException failed) {
            if (!complained) {
                complained = true;
                org.slf4j.LoggerFactory.getLogger("mc_puppet").warn("MC Puppet cannot write its audit log {}: {}",
                        file, failed.toString());
            }
        }
    }
}
