package com.modrinth.pain_o_d.mc_puppet.core;

import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.temporal.ChronoUnit;
import java.util.ArrayDeque;
import java.util.Deque;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Set;
import java.util.function.Consumer;
import java.util.function.Supplier;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

/**
 * What the game, the server and the launcher did, as one numbered record that
 * a program can wait on instead of sleeping and reading a log.
 *
 * <p>One event is one JSON object on one line:
 * {@code {"seq":12,"ts":"2026-10-07T17:08:12.431Z","side":"server","name":"server.crash",
 * "level":"error","data":{...}}}. {@code side} is {@code client}, {@code server}
 * or {@code launcher}; {@code level} is {@code info}, {@code warn} or
 * {@code error}; {@code data} is small (ids, reasons, paths), never a log, and a
 * long text in it is cut.
 *
 * <p>This is the bus only: it knows nothing of the game. Hooks call
 * {@link #emit(String, String, Level, JsonObject)}; what is let through is
 * decided by {@link Settings} (the {@code events} block of
 * {@code config/mc_puppet.json}); where it goes is a file
 * ({@code <run dir>/mc_puppet/events.jsonl}, emptied when the run starts, so a
 * watcher reads only this run), the standard output, and a ring of the latest
 * kept for the {@code lifecycle} operation. There is no network sink and there
 * must not be one: the bridge is loopback-only, and so is everything it says.
 *
 * <p>Events exist only where the bridge is on ({@link #start}); otherwise the
 * installed bus is {@link #OFF} and {@code emit} does nothing. This is not a
 * second way past consent.
 *
 * <p>Not {@link EventLog}: that is chat and toasts, a different volume for
 * different consumers.
 */
public final class Events {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc_puppet");

    /** How many are kept for the operation; the file keeps all. */
    public static final int KEPT = 1000;
    /** Longest text left in a string of {@code data}. */
    public static final int MAX_TEXT = 300;

    /** The file sink stops after this many bytes: a long process must not fill a disk. */
    public static final long MAX_FILE_BYTES = 16L * 1024 * 1024;

    private static final java.util.Set<String> SECRETS = java.util.concurrent.ConcurrentHashMap.newKeySet();

    private static final java.util.regex.Pattern KEY_VALUE = java.util.regex.Pattern.compile(
            "(?i)([\\w.-]*(?:password|passwd|token|secret|key)[\\w.-]*)(\\s*[=:]\\s*)(\"[^\"]*\"|'[^']*'|[^\\s,;&\"']+)");

    /** Registers a value (the bridge token) that must never appear in an event. */
    public static void addSecret(String secret) {
        if (secret != null && secret.length() >= 8) {
            SECRETS.add(secret);
        }
    }

    /** Hides registered secrets and {@code password=…}, {@code token: …}, {@code secret=…}, {@code key=…} values. */
    static String redact(String text) {
        for (String secret : SECRETS) {
            text = text.replace(secret, "[redacted]");
        }
        return KEY_VALUE.matcher(text).replaceAll(m -> java.util.regex.Matcher.quoteReplacement(
                m.group(1) + m.group(2) + "[redacted]"));
    }

    public enum Level {
        INFO, WARN, ERROR;

        public String text() {
            return name().toLowerCase(Locale.ROOT);
        }

        /** @return the level, or {@code null} if the text is none of them */
        public static Level parse(String text) {
            if (text != null) {
                for (Level level : values()) {
                    if (level.text().equals(text.trim().toLowerCase(Locale.ROOT))) {
                        return level;
                    }
                }
            }
            return null;
        }
    }

    /** Names let through by default whatever their level, besides every warn and error. */
    public static final Set<String> DEFAULT_NAMES = Set.of(
            "server.ready", "client.ready", "client.connected", "client.connect_failed", "client.disconnected");

    private static final Set<String> SIDES = Set.of("client", "server", "launcher");

    /**
     * The {@code events} block.
     *
     * @param enabled  names to record, {@code "*"} for all; {@code null} for the default rule (every
     *                 warn and error, and {@link #DEFAULT_NAMES})
     * @param file     write {@code events.jsonl}
     * @param stdout   print each line to the standard output
     * @param minLevel nothing below this is recorded, whatever {@code enabled} says
     */
    public record Settings(Set<String> enabled, boolean file, boolean stdout, Level minLevel) {

        public static Settings defaults() {
            return new Settings(null, true, false, Level.INFO);
        }

        public boolean allows(String name, Level level) {
            if (level.compareTo(minLevel) < 0) {
                return false;
            }
            if (enabled == null) {
                return level.compareTo(Level.WARN) >= 0 || DEFAULT_NAMES.contains(name);
            }
            return enabled.contains("*") || enabled.contains(name);
        }

        /**
         * Reads the block. Anything wrong with it (not an object, a key of the wrong type, an unknown
         * sink or level) gives the defaults whole and one warning, never half a setting.
         *
         * @param block the value of {@code "events"}, or {@code null} if the key is absent
         * @param warn  told once, in words, when the block is bad
         */
        public static Settings parse(JsonElement block, Consumer<String> warn) {
            if (block == null || block.isJsonNull()) {
                return defaults();
            }
            try {
                if (!block.isJsonObject()) {
                    throw new IllegalArgumentException("it is not an object");
                }
                JsonObject read = block.getAsJsonObject();
                Set<String> enabled = null;
                boolean file = true;
                boolean stdout = false;
                Level min = Level.INFO;
                if (read.has("enabled")) {
                    enabled = strings(read.get("enabled"), "enabled");
                }
                if (read.has("sinks")) {
                    file = false;
                    for (String sink : strings(read.get("sinks"), "sinks")) {
                        switch (sink) {
                            case "file" -> file = true;
                            case "stdout" -> stdout = true;
                            default -> throw new IllegalArgumentException(
                                    "\"" + sink + "\" is not a sink; the sinks are \"file\" and \"stdout\"");
                        }
                    }
                }
                if (read.has("min_level")) {
                    JsonElement text = read.get("min_level");
                    min = text.isJsonPrimitive() && text.getAsJsonPrimitive().isString()
                            ? Level.parse(text.getAsString()) : null;
                    if (min == null) {
                        throw new IllegalArgumentException("min_level is \"info\", \"warn\" or \"error\"");
                    }
                }
                return new Settings(enabled, file, stdout, min);
            } catch (IllegalArgumentException | IllegalStateException | ClassCastException bad) {
                warn.accept("the \"events\" block of config/mc_puppet.json is ignored (" + bad.getMessage()
                        + "); the defaults apply");
                return defaults();
            }
        }

        private static Set<String> strings(JsonElement element, String key) {
            if (!element.isJsonArray()) {
                throw new IllegalArgumentException(key + " is not an array");
            }
            Set<String> found = new LinkedHashSet<>();
            for (JsonElement each : (JsonArray) element) {
                if (!each.isJsonPrimitive() || !each.getAsJsonPrimitive().isString()) {
                    throw new IllegalArgumentException(key + " holds something that is not a text");
                }
                found.add(each.getAsString());
            }
            return found;
        }
    }

    /** Nothing is recorded. What is installed until {@link #start} says the bridge is on. */
    public static final Events OFF = new Events();

    private static volatile Events current = OFF;

    private static final DateTimeFormatter STAMP =
            DateTimeFormatter.ofPattern("yyyy-MM-dd'T'HH:mm:ss.SSS'Z'").withZone(ZoneOffset.UTC);

    private final boolean active;
    private final Settings settings;
    private final Path file;
    private final Supplier<Instant> clock;
    private final PrintStream out;
    private final Deque<JsonObject> kept = new ArrayDeque<>();
    private long sequence;
    private boolean fileFailed;
    /** Bytes written to the file so far; at {@link #fileLimit} the file sink stops, with one warning. */
    private long fileBytes;
    long fileLimit = MAX_FILE_BYTES;

    private Events() {
        this.active = false;
        this.settings = Settings.defaults();
        this.file = null;
        this.clock = Instant::now;
        this.out = null;
    }

    /**
     * A live bus. The file, if the settings ask for one, is emptied now: a run starts here.
     *
     * @param file where the lines go; may be {@code null} for none
     */
    public Events(Settings settings, Path file) {
        this(settings, file, Instant::now, System.out, false);
    }

    /**
     * As above; with {@code keepLauncher} the launcher's {@code process.started} line, already in the
     * file because "puppet launch" wrote it before the game's JVM began, survives and the numbering
     * continues after it. Otherwise {@code wait --event process.started} would block forever: this
     * constructor is what empties the file.
     */
    public Events(Settings settings, Path file, boolean keepLauncher) {
        this(settings, file, Instant::now, System.out, keepLauncher);
    }

    Events(Settings settings, Path file, Supplier<Instant> clock, PrintStream out) {
        this(settings, file, clock, out, false);
    }

    Events(Settings settings, Path file, Supplier<Instant> clock, PrintStream out, boolean keepLauncher) {
        this.active = true;
        this.settings = settings;
        this.file = settings.file() ? file : null;
        this.clock = clock;
        this.out = out;
        if (this.file != null) {
            try {
                Files.createDirectories(this.file.toAbsolutePath().getParent());
                StringBuilder kept = new StringBuilder();
                if (keepLauncher && Files.isRegularFile(this.file)) {
                    for (String line : Files.readAllLines(this.file, StandardCharsets.UTF_8)) {
                        try {
                            JsonObject each = JsonParser.parseString(line).getAsJsonObject();
                            if ("launcher".equals(each.get("side").getAsString())
                                    && "process.started".equals(each.get("name").getAsString())) {
                                kept.append(line).append(System.lineSeparator());
                                sequence = Math.max(sequence, each.get("seq").getAsLong());
                            }
                        } catch (RuntimeException foreign) {
                            // not one of ours: dropped with the rest
                        }
                    }
                }
                Files.writeString(this.file, kept.toString(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.TRUNCATE_EXISTING, StandardOpenOption.WRITE);
                fileBytes = kept.length();
            } catch (IOException cannot) {
                fileFailed = true;
                LOGGER.warn("MC Puppet cannot write {}; events are kept in memory only: {}", this.file,
                        cannot.toString());
            }
        }
    }

    /**
     * Installs the bus for this process: a live one if the bridge is on, otherwise {@link #OFF}.
     *
     * @param runDir the game directory; the file is {@code <runDir>/mc_puppet/events.jsonl}
     */
    public static Events start(PuppetConfig config, Path runDir) {
        Events bus = config.enabled()
                ? new Events(config.events(), runDir.resolve("mc_puppet").resolve("events.jsonl"),
                        Boolean.getBoolean("mc_puppet.launched"))
                : OFF;
        install(bus);
        return bus;
    }

    public static void install(Events bus) {
        current = bus == null ? OFF : bus;
    }

    public static Events current() {
        return current;
    }

    /** {@link #emit} on the installed bus: what a hook calls. */
    public static JsonObject record(String side, String name, Level level, JsonObject data) {
        return current.emit(side, name, level, data);
    }

    /**
     * Records an event if the settings let its name and level through. Never throws: a hook must
     * not be able to break the game by reporting on it.
     *
     * @param data small facts, or {@code null}; its top-level texts are cut at {@link #MAX_TEXT}
     * @return the event as written, or {@code null} if it was not recorded
     */
    public JsonObject emit(String side, String name, Level level, JsonObject data) {
        if (!active || level == null || name == null || name.isBlank() || !SIDES.contains(side)) {
            return null;
        }
        try {
            if (!settings.allows(name, level)) {
                return null;
            }
            JsonObject small = new JsonObject();
            if (data != null) {
                data.entrySet().forEach(entry -> small.add(entry.getKey(), cut(entry.getValue())));
            }
            JsonObject event;
            String line;
            synchronized (this) {
                event = new JsonObject();
                event.addProperty("seq", ++sequence);
                event.addProperty("ts", STAMP.format(clock.get().truncatedTo(ChronoUnit.MILLIS)));
                event.addProperty("side", side);
                event.addProperty("name", name);
                event.addProperty("level", level.text());
                event.add("data", small);
                line = Protocol.GSON.toJson(event);
                kept.addLast(event);
                while (kept.size() > KEPT) {
                    kept.removeFirst();
                }
                write(line);
            }
            return event;
        } catch (RuntimeException broken) {
            LOGGER.warn("MC Puppet could not record event {}: {}", name, broken.toString());
            return null;
        }
    }

    private void write(String line) {
        if (file != null && !fileFailed) {
            long size = line.length() + System.lineSeparator().length();
            if (fileBytes + size > fileLimit) {
                fileFailed = true;
                LOGGER.warn("MC Puppet stopped writing {}: it reached {} bytes; events are kept in memory only",
                        file, fileLimit);
            }
        }
        if (file != null && !fileFailed) {
            fileBytes += line.length() + System.lineSeparator().length();
            try {
                Files.writeString(file, line + System.lineSeparator(), StandardCharsets.UTF_8,
                        StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            } catch (IOException cannot) {
                fileFailed = true;
                LOGGER.warn("MC Puppet cannot write {}; events are kept in memory only: {}", file,
                        cannot.toString());
            }
        }
        if (settings.stdout() && out != null) {
            out.println(line);
        }
    }

    private static JsonElement cut(JsonElement value) {
        if (value.isJsonPrimitive() && value.getAsJsonPrimitive().isString()) {
            String text = redact(value.getAsString());
            if (text.length() > MAX_TEXT) {
                return new com.google.gson.JsonPrimitive(text.substring(0, MAX_TEXT) + "…");
            }
            return new com.google.gson.JsonPrimitive(text);
        }
        return value;
    }

    public synchronized long sequence() {
        return sequence;
    }

    /**
     * The events after {@code since}, as the file has them.
     *
     * @param names comma-separated event names, or {@code null} for all
     * @param limit at most this many; the latest, when there are more
     */
    public synchronized JsonObject read(long since, String names, int limit) {
        Set<String> among = null;
        if (names != null && !names.isBlank()) {
            among = new LinkedHashSet<>();
            for (String name : names.split(",")) {
                if (!name.isBlank()) {
                    among.add(name.trim());
                }
            }
        }
        JsonArray found = new JsonArray();
        for (JsonObject event : kept) {
            if (event.get("seq").getAsLong() > since
                    && (among == null || among.contains(event.get("name").getAsString()))) {
                found.add(event.deepCopy());
            }
        }
        while (found.size() > Math.max(1, limit)) {
            found.remove(0);
        }
        JsonObject answer = new JsonObject();
        answer.addProperty("sequence", sequence);
        answer.addProperty("recording", active);
        answer.add("events", found);
        return answer;
    }

    /** The {@code lifecycle} operation, registered by {@link Ops} on every side. */
    static JsonElement operation(JsonObject args) throws Ops.Refused {
        return current.read(Args.number(args, "since", 0), Args.string(args, "names", null),
                Args.integer(args, "limit", 50));
    }
}
