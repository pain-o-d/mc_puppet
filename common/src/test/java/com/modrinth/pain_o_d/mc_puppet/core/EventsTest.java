package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

import com.modrinth.pain_o_d.mc_puppet.core.Events.Level;
import com.modrinth.pain_o_d.mc_puppet.core.Events.Settings;

/** Task 1A: the event bus, its settings, its file and its operation. No game. */
class EventsTest {

    @TempDir
    Path dir;

    @AfterEach
    void uninstall() {
        Events.install(null);
    }

    private static Settings parse(String json, List<String> warnings) {
        return Settings.parse(json == null ? null : JsonParser.parseString(json), warnings::add);
    }

    private Events bus(Settings settings) {
        return new Events(settings, dir.resolve("mc_puppet").resolve("events.jsonl"));
    }

    private List<String> lines() throws Exception {
        return Files.readAllLines(dir.resolve("mc_puppet").resolve("events.jsonl"), StandardCharsets.UTF_8);
    }

    @Test
    void defaultsLetThroughWarnErrorAndTheNamedInfo() {
        Settings defaults = Settings.defaults();
        assertTrue(defaults.file());
        assertFalse(defaults.stdout());
        assertEquals(Level.INFO, defaults.minLevel());
        assertTrue(defaults.allows("server.crash", Level.ERROR));
        assertTrue(defaults.allows("anything.at.all", Level.WARN));
        for (String name : Set.of("server.ready", "client.ready", "client.connected", "client.connect_failed",
                "client.disconnected")) {
            assertTrue(defaults.allows(name, Level.INFO), name);
        }
        assertFalse(defaults.allows("player.joined", Level.INFO));
        assertFalse(defaults.allows("server.starting", Level.INFO));
    }

    @Test
    void anAbsentBlockIsTheDefaultsWithoutAWarning() {
        List<String> warnings = new ArrayList<>();
        assertEquals(Settings.defaults(), parse(null, warnings));
        assertEquals(Settings.defaults(), parse("{}", warnings));
        assertTrue(warnings.isEmpty());
    }

    @Test
    void enabledReplacesTheDefaultRuleAndStarMeansAll() {
        Settings named = parse("{\"enabled\":[\"player.joined\"],\"sinks\":[\"file\"],\"min_level\":\"info\"}",
                new ArrayList<>());
        assertTrue(named.allows("player.joined", Level.INFO));
        assertFalse(named.allows("server.crash", Level.ERROR), "a list is the whole list");
        Settings all = parse("{\"enabled\":[\"*\"]}", new ArrayList<>());
        assertTrue(all.allows("server.starting", Level.INFO));
        assertTrue(all.allows("whatever", Level.ERROR));
    }

    @Test
    void minLevelFiltersWhateverIsEnabled() {
        Settings warnUp = parse("{\"enabled\":[\"*\"],\"min_level\":\"warn\"}", new ArrayList<>());
        assertFalse(warnUp.allows("server.ready", Level.INFO));
        assertTrue(warnUp.allows("server.crash", Level.WARN));
        Settings errorsOnly = parse("{\"min_level\":\"ERROR\"}", new ArrayList<>());
        assertFalse(errorsOnly.allows("client.disconnected", Level.WARN));
        assertTrue(errorsOnly.allows("client.crash", Level.ERROR));
    }

    @Test
    void sinksAreChosenAndNothingElseIsASink() {
        List<String> warnings = new ArrayList<>();
        Settings both = parse("{\"sinks\":[\"file\",\"stdout\"]}", warnings);
        assertTrue(both.file() && both.stdout());
        Settings none = parse("{\"sinks\":[]}", warnings);
        assertFalse(none.file() || none.stdout());
        assertTrue(warnings.isEmpty());
        assertEquals(Settings.defaults(), parse("{\"sinks\":[\"webhook\"]}", warnings));
        assertEquals(1, warnings.size(), "there is no network sink");
    }

    @Test
    void aBadBlockKeepsTheDefaultsAndWarnsOnce() {
        for (String bad : new String[] {"\"nope\"", "[1]", "{\"enabled\":\"server.crash\"}", "{\"enabled\":[1]}",
                "{\"min_level\":\"loud\"}", "{\"min_level\":3}", "{\"sinks\":\"file\"}",
                "{\"enabled\":[\"*\"],\"min_level\":\"loud\"}"}) {
            List<String> warnings = new ArrayList<>();
            assertEquals(Settings.defaults(), parse(bad, warnings), bad);
            assertEquals(1, warnings.size(), bad);
            assertTrue(warnings.get(0).contains("events"), warnings.get(0));
        }
    }

    @Test
    void theConfigFileCarriesTheBlock(@TempDir Path config) throws Exception {
        Files.writeString(config.resolve("mc_puppet.json"),
                "{\"enabled\":true,\"events\":{\"enabled\":[\"server.crash\"],\"sinks\":[\"stdout\"],"
                        + "\"min_level\":\"warn\"}}");
        PuppetConfig loaded = PuppetConfig.load(config);
        assertEquals(new Settings(Set.of("server.crash"), false, true, Level.WARN), loaded.events());

        Path other = Files.createDirectory(config.resolve("none"));
        Files.writeString(other.resolve("mc_puppet.json"), "{\"enabled\":true,\"events\":7}");
        assertEquals(Settings.defaults(), PuppetConfig.load(other).events());

        assertEquals(Settings.defaults(), new PuppetConfig(true, 25580, 25581).events());
    }

    @Test
    void anEventHasOneShapeAndASequenceThatOnlyGrows() throws Exception {
        Events bus = new Events(new Settings(Set.of("*"), true, false, Level.INFO),
                dir.resolve("mc_puppet").resolve("events.jsonl"), () -> Instant.parse("2026-10-07T17:08:12.431Z"),
                System.out);
        JsonObject data = new JsonObject();
        data.addProperty("report", "crash-reports/x.txt");
        JsonObject first = bus.emit("server", "server.crash", Level.ERROR, data);
        JsonObject second = bus.emit("client", "client.ready", Level.INFO, null);
        assertEquals(1, first.get("seq").getAsLong());
        assertEquals(2, second.get("seq").getAsLong());
        assertEquals("2026-10-07T17:08:12.431Z", first.get("ts").getAsString());
        assertEquals("server", first.get("side").getAsString());
        assertEquals("server.crash", first.get("name").getAsString());
        assertEquals("error", first.get("level").getAsString());
        assertEquals("crash-reports/x.txt", first.getAsJsonObject("data").get("report").getAsString());
        assertTrue(second.getAsJsonObject("data").size() == 0);

        List<String> written = lines();
        assertEquals(2, written.size());
        assertEquals(first, JsonParser.parseString(written.get(0)), "the file line is the event");
        assertEquals(List.of("seq", "ts", "side", "name", "level", "data"),
                List.copyOf(JsonParser.parseString(written.get(0)).getAsJsonObject().keySet()));
        assertEquals(2, bus.sequence());
    }

    @Test
    void whatIsFilteredOutTakesNoNumberAndLeavesNoLine() throws Exception {
        Events bus = bus(Settings.defaults());
        assertNull(bus.emit("server", "player.joined", Level.INFO, null));
        assertNotNull(bus.emit("server", "server.ready", Level.INFO, null));
        assertNull(bus.emit("server", "server.starting", Level.INFO, null));
        assertEquals(2, bus.emit("server", "mod.load_failed", Level.WARN, null).get("seq").getAsLong());
        assertEquals(2, lines().size());
    }

    @Test
    void anUnknownSideOrLevelOrNameIsNotRecordedAndNeverThrows() {
        Events bus = bus(new Settings(Set.of("*"), true, false, Level.INFO));
        assertNull(bus.emit("moon", "server.ready", Level.INFO, null));
        assertNull(bus.emit("server", "server.ready", null, null));
        assertNull(bus.emit("server", null, Level.INFO, null));
        assertNull(bus.emit("server", " ", Level.INFO, null));
        assertEquals(0, bus.sequence());
    }

    @Test
    void theFileIsEmptiedWhenARunStartsAndAppendedDuringIt() throws Exception {
        Settings all = new Settings(Set.of("*"), true, false, Level.INFO);
        Events earlier = bus(all);
        earlier.emit("server", "server.starting", Level.INFO, null);
        earlier.emit("server", "server.ready", Level.INFO, null);
        assertEquals(2, lines().size());

        Events next = bus(all);
        assertEquals(0, Files.size(dir.resolve("mc_puppet").resolve("events.jsonl")), "a watcher reads this run only");
        next.emit("server", "server.starting", Level.INFO, null);
        next.emit("server", "server.stopping", Level.INFO, null);
        List<String> now = lines();
        assertEquals(2, now.size());
        assertEquals(1, JsonParser.parseString(now.get(0)).getAsJsonObject().get("seq").getAsLong());
        assertEquals("server.stopping", JsonParser.parseString(now.get(1)).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void noFileSinkNoFile() {
        Events bus = new Events(new Settings(Set.of("*"), false, false, Level.INFO),
                dir.resolve("mc_puppet").resolve("events.jsonl"));
        bus.emit("server", "server.ready", Level.INFO, null);
        assertFalse(Files.exists(dir.resolve("mc_puppet")));
        assertEquals(1, bus.sequence());
    }

    @Test
    void anUnwritableFileCostsTheSinkNotTheGame() throws Exception {
        Path blocker = Files.writeString(dir.resolve("blocker"), "a file where a directory should be");
        Events bus = new Events(new Settings(Set.of("*"), true, false, Level.INFO),
                blocker.resolve("events.jsonl"));
        assertNotNull(bus.emit("server", "server.ready", Level.INFO, null));
        assertEquals(1, bus.read(0, null, 10).getAsJsonArray("events").size());
    }

    @Test
    void stdoutIsASinkWhenAsked() {
        ByteArrayOutputStream captured = new ByteArrayOutputStream();
        Events bus = new Events(new Settings(Set.of("*"), false, true, Level.INFO), null, Instant::now,
                new PrintStream(captured, true, StandardCharsets.UTF_8));
        bus.emit("launcher", "process.started", Level.INFO, null);
        String line = captured.toString(StandardCharsets.UTF_8).strip();
        assertEquals("process.started", JsonParser.parseString(line).getAsJsonObject().get("name").getAsString());
    }

    @Test
    void aLongTextIsCutAndALineStaysOneLine() throws Exception {
        Events bus = bus(new Settings(Set.of("*"), true, false, Level.INFO));
        JsonObject data = new JsonObject();
        data.addProperty("cause", ("line one\nline two ").repeat(100));
        JsonObject written = bus.emit("server", "server.crash", Level.ERROR, data);
        String cause = written.getAsJsonObject("data").get("cause").getAsString();
        assertTrue(cause.length() <= Events.MAX_TEXT + 1, "was " + cause.length());
        assertEquals(1, lines().size());
    }

    @Test
    void queriesTakeSinceNamesAndLimitAndKeepTheLatest() {
        Events bus = new Events(new Settings(Set.of("*"), false, false, Level.INFO), null);
        for (int i = 0; i < 6; i++) {
            bus.emit("client", i % 2 == 0 ? "client.connecting" : "client.connected", Level.INFO, null);
        }
        JsonObject all = bus.read(0, null, 50);
        assertEquals(6, all.get("sequence").getAsLong());
        assertTrue(all.get("recording").getAsBoolean());
        assertEquals(6, all.getAsJsonArray("events").size());

        JsonArray since = bus.read(4, null, 50).getAsJsonArray("events");
        assertEquals(2, since.size());
        assertEquals(5, since.get(0).getAsJsonObject().get("seq").getAsLong());

        JsonArray named = bus.read(0, "client.connected, nothing.such", 50).getAsJsonArray("events");
        assertEquals(3, named.size());

        JsonArray latest = bus.read(0, null, 2).getAsJsonArray("events");
        assertEquals(2, latest.size());
        assertEquals(5, latest.get(0).getAsJsonObject().get("seq").getAsLong());
        assertEquals(6, latest.get(1).getAsJsonObject().get("seq").getAsLong());
    }

    @Test
    void theRingKeepsTheLatestAndTheSequenceKeepsCounting() {
        Events bus = new Events(new Settings(Set.of("*"), false, false, Level.INFO), null);
        for (int i = 0; i < Events.KEPT + 5; i++) {
            bus.emit("server", "server.ready", Level.INFO, null);
        }
        JsonArray every = bus.read(0, null, Events.KEPT + 100).getAsJsonArray("events");
        assertEquals(Events.KEPT, every.size());
        assertEquals(6, every.get(0).getAsJsonObject().get("seq").getAsLong());
        assertEquals(Events.KEPT + 5, bus.sequence());
    }

    @Test
    void theBridgeBeingOffMeansNoEventsAtAll() throws Exception {
        PuppetConfig off = new PuppetConfig(false, 25580, 25581, true);
        assertEquals(Events.OFF, Events.start(off, dir));
        assertNull(Events.record("server", "server.crash", Level.ERROR, null));
        assertFalse(Files.exists(dir.resolve("mc_puppet")), "nothing is written in a shipped config");
        JsonObject answer = Events.OFF.read(0, null, 10);
        assertFalse(answer.get("recording").getAsBoolean());
        assertEquals(0, answer.getAsJsonArray("events").size());
    }

    @Test
    void theBridgeBeingOnStartsARunInTheRunDirectory() throws Exception {
        PuppetConfig on = new PuppetConfig(true, 25580, 25581);
        Events bus = Events.start(on, dir);
        assertEquals(bus, Events.current());
        assertNotNull(Events.record("server", "server.crash", Level.ERROR, null));
        assertNull(Events.record("server", "player.joined", Level.INFO, null));
        assertEquals(1, lines().size());
    }

    @Test
    void theLifecycleOperationAnswersOnEverySideAndAddsNoProtocolVersion() throws Exception {
        Events.install(new Events(new Settings(Set.of("*"), false, false, Level.INFO), null));
        Events.record("client", "client.connect_failed", Level.ERROR, null);
        Events.record("client", "client.ready", Level.INFO, null);
        Ops ops = new Ops("test", Runnable::run);
        JsonObject args = JsonParser.parseString("{\"since\":0,\"names\":\"client.connect_failed\",\"limit\":5}")
                .getAsJsonObject();
        JsonObject answer = ops.run("lifecycle", args).get().getAsJsonObject();
        assertEquals(2, answer.get("sequence").getAsLong());
        assertEquals(1, answer.getAsJsonArray("events").size());
        assertEquals(1, Protocol.VERSION, "a new operation does not raise the protocol version");
    }

    @Test
    void secretsAreHiddenInData() {
        Events.addSecret("0123456789abcdef");
        Events bus = new Events(new Settings(Set.of("*"), false, false, Level.INFO), null);
        JsonObject data = new JsonObject();
        data.addProperty("a", "connect with 0123456789abcdef now");
        data.addProperty("b", "password=hunter2 and Token: abc123 ok");
        data.addProperty("c", "plain text");
        JsonObject event = bus.emit("server", "x.y", Level.INFO, data);
        JsonObject out = event.getAsJsonObject("data");
        assertEquals("connect with [redacted] now", out.get("a").getAsString());
        assertEquals("password=[redacted] and Token: [redacted] ok", out.get("b").getAsString());
        assertEquals("plain text", out.get("c").getAsString());
    }

    @Test
    void theFileStopsAtItsLimitAndTheRingKeepsGoing(@TempDir Path dir) throws Exception {
        Path file = dir.resolve("events.jsonl");
        Events bus = new Events(new Settings(Set.of("*"), true, false, Level.INFO), file);
        bus.fileLimit = 400;
        for (int i = 0; i < 20; i++) {
            bus.emit("server", "x.y", Level.INFO, null);
        }
        assertTrue(Files.size(file) <= 400, "the file stays under its limit");
        assertTrue(Files.size(file) > 0);
        assertEquals(20, bus.read(0, null, 100).getAsJsonArray("events").size());
    }
}
