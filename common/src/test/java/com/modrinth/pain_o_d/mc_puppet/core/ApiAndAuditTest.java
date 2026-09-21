package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.modrinth.pain_o_d.mc_puppet.api.PuppetApi;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/** What a mod may add, and what is written down of what was asked. */
class ApiAndAuditTest {

    private static JsonObject json(String text) {
        return Protocol.GSON.fromJson(text, JsonObject.class);
    }

    @Test
    @DisplayName("a mod's operation answers whether it was registered before the bridge opened or after")
    void registersBeforeAndAfter() throws Exception {
        PuppetApi.register(PuppetApi.Side.SERVER, "test_mod:before", "{}", "Registered early.",
                args -> new JsonPrimitive("early"));
        Ops ops = new Ops("server", Runnable::run);
        PuppetApi.attach(PuppetApi.Side.SERVER, ops);
        PuppetApi.register(PuppetApi.Side.SERVER, "test_mod:after", "{n}", "Registered late.",
                args -> new JsonPrimitive(args.get("n").getAsInt() * 2));

        assertEquals("early", ops.run("test_mod:before", new JsonObject()).get().getAsString());
        assertEquals(42, ops.run("test_mod:after", json("{\"n\":21}")).get().getAsInt());
        assertTrue(ops.run("help", new JsonObject()).get().toString().contains("test_mod:after"));
        assertTrue(PuppetApi.registered(PuppetApi.Side.SERVER).containsAll(
                List.of("test_mod:before", "test_mod:after")));
    }

    @Test
    @DisplayName("an operation registered for one side is not answered on the other")
    void sidesAreSeparate() {
        PuppetApi.register(PuppetApi.Side.CLIENT, "test_mod:client_only", "{}", "", args -> null);
        Ops server = new Ops("server", Runnable::run);
        PuppetApi.attach(PuppetApi.Side.SERVER, server);
        assertTrue(server.run("test_mod:client_only", new JsonObject()).isCompletedExceptionally());
    }

    @Test
    @DisplayName("a name needs a mod id, and cannot be taken twice")
    void names() {
        assertThrows(IllegalArgumentException.class,
                () -> PuppetApi.register(PuppetApi.Side.CLIENT, "price", "{}", "", args -> null));
        assertThrows(IllegalArgumentException.class,
                () -> PuppetApi.register(PuppetApi.Side.CLIENT, "Test:Price", "{}", "", args -> null));
        PuppetApi.register(PuppetApi.Side.CLIENT, "test_mod:once", "{}", "", args -> null);
        assertThrows(IllegalArgumentException.class,
                () -> PuppetApi.register(PuppetApi.Side.CLIENT, "test_mod:once", "{}", "", args -> null));
    }

    @Test
    @DisplayName("what a mod's operation throws comes back as a refusal in words")
    void failures() {
        PuppetApi.register(PuppetApi.Side.SERVER, "test_mod:broken", "{}", "", args -> {
            throw new IllegalStateException("not priced yet");
        });
        Ops ops = new Ops("server", Runnable::run);
        PuppetApi.attach(PuppetApi.Side.SERVER, ops);
        Throwable failure = assertThrows(Exception.class, () -> ops.run("test_mod:broken", new JsonObject()).get());
        assertTrue(Ops.messageOf(failure).contains("not priced yet"));
    }

    @Test
    @DisplayName("the audit log has a line a request, says how each ended, and cuts what is long")
    void audit(@TempDir Path directory) throws Exception {
        Audit audit = new Audit(directory, "client");
        audit.wrote("screen", new JsonObject(), null, 3);
        audit.wrote("click_widget", json("{\"text\":\"" + "x".repeat(1000) + "\"}"), new Ops.Refused("no such\nwidget"),
                1);
        List<String> lines = Files.readAllLines(audit.file());
        assertEquals(2, lines.size());
        assertTrue(lines.get(0).contains(" screen {} -> ok (3ms)"), lines.get(0));
        assertTrue(lines.get(1).contains("refused: no such widget"), lines.get(1));
        assertTrue(lines.get(1).length() < Audit.MAX_ARGS + 120, "long arguments are cut");
        assertFalse(lines.get(1).contains("token"));
    }

    @Test
    @DisplayName("a log that has grown large is put aside when the next game starts")
    void rotates(@TempDir Path directory) throws Exception {
        Path file = directory.resolve("audit-server.log");
        Files.write(file, new byte[(int) Audit.ROTATE_AT_BYTES + 1]);
        Audit audit = new Audit(directory, "server");
        audit.wrote("info", new JsonObject(), null, 0);
        assertTrue(Files.exists(directory.resolve("audit-server.1.log")));
        assertEquals(1, Files.readAllLines(audit.file()).size());
    }

    @Test
    @DisplayName("whoever is written about cannot write the log: a line break in an operation's name is not a line")
    void aNameCannotForgeALine(@TempDir Path directory) throws Exception {
        Audit audit = new Audit(directory, "server");
        audit.wrote("help\n2026-01-01T00:00:00Z quit {} -> ok (0ms)\r\u2028also", new JsonObject(), null, 1);
        audit.wrote("x".repeat(5000), new JsonObject(), null, 1);

        List<String> lines = Files.readAllLines(audit.file());
        assertEquals(2, lines.size(), "two requests, two lines");
        assertTrue(lines.get(0).contains(" help "), lines.get(0));
        assertTrue(lines.get(1).length() < Audit.MAX_OP + 120, "a name is cut short");
    }

    @Test
    @DisplayName("padding in one argument cannot push the next out of sight")
    void paddingHidesNothing(@TempDir Path directory) throws Exception {
        Audit audit = new Audit(directory, "server");
        audit.wrote("command", json("{\"pad\":\"" + "x".repeat(50_000) + "\",\"command\":\"op somebody\"}"), null, 1);

        String line = Files.readAllLines(audit.file()).get(0);
        assertTrue(line.contains("op somebody"), line);
        assertTrue(line.contains("(+"), "and says how much was cut");
    }

    @Test
    @DisplayName("what runs inside a batch or a wait_until has a line of its own")
    void innerOperationsAreHeardOf(@TempDir Path directory) throws Exception {
        Audit audit = new Audit(directory, "server");
        Ops ops = new Ops("test", Runnable::run);
        ops.now("echo", "{value}", "Returns its argument.", args -> args.get("value"));
        ops.tellOfInner(audit::wroteInner);

        ops.run("batch", json("{\"steps\":[{\"op\":\"help\",\"args\":{\"pad\":\"" + "x".repeat(400)
                + "\"}},{\"op\":\"echo\",\"args\":{\"value\":\"hidden no longer\"}}]}")).get();
        ops.run("wait_until", json("{\"op\":\"echo\",\"args\":{\"value\":7},\"equals\":7,\"timeout_ms\":1000}"));

        List<String> lines = Files.readAllLines(audit.file());
        assertTrue(lines.stream().anyMatch(line -> line.contains("batch> echo") && line.contains("hidden no longer")),
                String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(line -> line.contains("batch> help")), String.join("\n", lines));
        assertTrue(lines.stream().anyMatch(line -> line.contains("wait_until> echo")), String.join("\n", lines));
    }

    @Test
    @DisplayName("the log is turned over while it is written, not only when the game starts")
    void turnsOverWhileRunning(@TempDir Path directory) throws Exception {
        Audit audit = new Audit(directory, "client");
        JsonObject big = json("{\"a\":\"" + "y".repeat(150) + "\",\"b\":\"" + "z".repeat(150) + "\"}");
        long lines = Audit.ROTATE_AT_BYTES / 300 + 2_000;
        for (long written = 0; written < lines; written++) {
            audit.wrote("click_at", big, null, 0);
        }
        assertTrue(Files.exists(directory.resolve("audit-client.1.log")), "the full one was set aside");
        assertTrue(Files.size(audit.file()) <= Audit.ROTATE_AT_BYTES + 1_000, "and the new one is not past the limit");
    }
}
