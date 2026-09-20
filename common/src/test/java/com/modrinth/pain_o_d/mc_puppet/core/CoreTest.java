package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * Covers the part of the bridge that needs no game: the wire, the token, the
 * batch, the waiting. It is also the part a stranger talks to.
 */
class CoreTest {

    /** A game thread that is just the caller's: operations run where they are asked. */
    private static Ops ops() {
        Ops ops = new Ops("test", Runnable::run);
        ops.now("echo", "{value}", "Returns its argument.", args -> args.get("value"));
        ops.now("fail", "{}", "Always refuses.", args -> {
            throw new Ops.Refused("no");
        });
        ops.now("crash", "{}", "Always throws.", args -> {
            throw new IllegalStateException("boom");
        });
        return ops;
    }

    private static JsonObject json(String text) {
        return Protocol.GSON.fromJson(text, JsonObject.class);
    }

    @Nested
    @DisplayName("the wire")
    class Wire {

        @Test
        @DisplayName("a request is an object naming an op; args are optional")
        void parses() throws Protocol.Malformed {
            Protocol.Request request = Protocol.parse("{\"id\":7,\"token\":\"t\",\"op\":\"screen\"}");
            assertEquals("screen", request.op());
            assertEquals("t", request.token());
            assertEquals(0, request.args().size());
            assertEquals(7, request.id().getAsInt());
        }

        @Test
        @DisplayName("anything else is said to be malformed, in words")
        void malformed() {
            assertThrows(Protocol.Malformed.class, () -> Protocol.parse("not json"));
            assertThrows(Protocol.Malformed.class, () -> Protocol.parse("[1,2]"));
            assertThrows(Protocol.Malformed.class, () -> Protocol.parse("{\"args\":{}}"));
            assertThrows(Protocol.Malformed.class, () -> Protocol.parse("{\"op\":\"x\",\"args\":[]}"));
        }

        @Test
        @DisplayName("a response stays on its line whatever is in it")
        void oneLine() {
            String response = Protocol.ok(new JsonPrimitive(1), new JsonPrimitive("two\nlines   and more"));
            assertFalse(response.contains("\n"));
            assertEquals("two\nlines   and more", json(response).get("result").getAsString());
        }
    }

    @Nested
    @DisplayName("the token")
    class Token {

        @Test
        void matchesOnlyItself() {
            assertTrue(Protocol.tokenMatches("abc", "abc"));
            assertFalse(Protocol.tokenMatches("abc", "abd"));
            assertFalse(Protocol.tokenMatches("abc", "ab"));
            assertFalse(Protocol.tokenMatches("abc", ""));
            assertFalse(Protocol.tokenMatches("abc", null));
        }

        @Test
        @DisplayName("no token set means nobody gets in, not everybody")
        void emptyExpected() {
            assertFalse(Protocol.tokenMatches("", ""));
            assertFalse(Protocol.tokenMatches(null, null));
        }
    }

    @Nested
    @DisplayName("operations")
    class Operations {

        @Test
        void unknownIsRefusedInWords() {
            CompletableFuture<JsonElement> answer = ops().run("nope", new JsonObject());
            assertTrue(answer.isCompletedExceptionally());
            assertTrue(Ops.messageOf(assertThrows(Exception.class, answer::get)).contains("no such operation"));
        }

        @Test
        @DisplayName("a crash in an operation is an answer, not an exception in the game")
        void crashIsContained() {
            CompletableFuture<JsonElement> answer = ops().run("crash", new JsonObject());
            assertEquals("IllegalStateException: boom", Ops.messageOf(assertThrows(Exception.class, answer::get)));
        }

        @Test
        void helpListsEverything() throws Exception {
            JsonObject help = ops().run("help", new JsonObject()).get().getAsJsonObject();
            assertEquals("test", help.get("side").getAsString());
            assertEquals(6, help.getAsJsonArray("ops").size());
        }
    }

    @Nested
    @DisplayName("a batch is one round trip")
    class Batch {

        private JsonObject run(String steps, boolean stopOnError) throws Exception {
            JsonObject args = json("{\"steps\":" + steps + ",\"stop_on_error\":" + stopOnError + "}");
            return ops().run("batch", args).get(5, TimeUnit.SECONDS).getAsJsonObject();
        }

        @Test
        void runsInOrderAndKeepsEveryResult() throws Exception {
            JsonObject done = run("[{\"op\":\"echo\",\"args\":{\"value\":1}},{\"op\":\"echo\",\"args\":{\"value\":2}}]", true);
            assertTrue(done.get("ok").getAsBoolean());
            assertEquals(2, done.get("completed").getAsInt());
            assertEquals(2, done.getAsJsonArray("results").get(1).getAsJsonObject().get("result").getAsInt());
        }

        @Test
        @DisplayName("stops at the first failure, and says how far it got")
        void stops() throws Exception {
            JsonObject done = run("[{\"op\":\"echo\",\"args\":{\"value\":1}},{\"op\":\"fail\"},{\"op\":\"echo\"}]", true);
            assertFalse(done.get("ok").getAsBoolean());
            assertEquals(2, done.get("completed").getAsInt());
            assertEquals(3, done.get("of").getAsInt());
            assertEquals("no", done.getAsJsonArray("results").get(1).getAsJsonObject().get("error").getAsString());
        }

        @Test
        @DisplayName("or carries on, when told to")
        void carriesOn() throws Exception {
            JsonObject done = run("[{\"op\":\"fail\"},{\"op\":\"echo\",\"args\":{\"value\":3}}]", false);
            assertFalse(done.get("ok").getAsBoolean());
            assertEquals(2, done.get("completed").getAsInt());
        }

        @Test
        void doesNotNest() {
            CompletableFuture<JsonElement> answer = ops().run("batch",
                    json("{\"steps\":[{\"op\":\"batch\",\"args\":{\"steps\":[]}}]}"));
            assertTrue(Ops.messageOf(assertThrows(Exception.class, answer::get)).contains("does not nest"));
        }
    }

    @Nested
    @DisplayName("waiting happens on the tick")
    class Waiting {

        @Test
        @DisplayName("already true answers at once")
        void atOnce() {
            Waiter waiter = new Waiter();
            assertTrue(waiter.until("it", 1_000, () -> new JsonPrimitive(1)).isDone());
            assertEquals(0, waiter.waiting());
        }

        @Test
        @DisplayName("turns true on the tick it becomes so")
        void onTheTick() throws Exception {
            Waiter waiter = new Waiter();
            boolean[] ready = {false};
            CompletableFuture<JsonElement> answer = waiter.until("it", 10_000,
                    () -> ready[0] ? new JsonPrimitive("yes") : null);
            waiter.tick();
            assertFalse(answer.isDone());
            ready[0] = true;
            waiter.tick();
            assertEquals("yes", answer.get().getAsString());
            assertEquals(0, waiter.waiting());
        }

        @Test
        @DisplayName("times out in ticks, and says what it was waiting for")
        void timesOut() {
            Waiter waiter = new Waiter();
            CompletableFuture<JsonElement> answer = waiter.until("a screen", 100, () -> null);
            for (int tick = 0; tick < 3; tick++) {
                waiter.tick();
            }
            assertTrue(Ops.messageOf(assertThrows(Exception.class, answer::get)).contains("a screen"));
        }

        @Test
        @DisplayName("everything still waiting is told when its side goes away")
        void abandoned() {
            Waiter waiter = new Waiter();
            CompletableFuture<JsonElement> answer = waiter.until("it", 10_000, () -> null);
            waiter.abandon("the server is stopping");
            assertTrue(Ops.messageOf(assertThrows(Exception.class, answer::get)).contains("stopping"));
        }
    }

    @Nested
    @DisplayName("the socket")
    class TheSocket {

        private static String ask(int port, String line) throws Exception {
            try (Socket socket = new Socket(InetAddress.getLoopbackAddress(), port)) {
                socket.setSoTimeout(5_000);
                Writer out = new OutputStreamWriter(socket.getOutputStream(), StandardCharsets.UTF_8);
                out.write(line + "\n");
                out.flush();
                return new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8))
                        .readLine();
            }
        }

        @Test
        @DisplayName("says where it is in a file, answers the right token, and ends on a wrong one")
        void endToEnd(@TempDir Path gameDir) throws Exception {
            try (Bridge bridge = Bridge.open("test", ops(), 25_590, gameDir)) {
                JsonObject endpoint = json(Files.readString(gameDir.resolve("mc_puppet/endpoint-test.json")));
                assertEquals(bridge.port(), endpoint.get("port").getAsInt());
                assertEquals("127.0.0.1", endpoint.get("host").getAsString());
                String token = endpoint.get("token").getAsString();
                assertEquals(64, token.length());

                JsonObject good = json(ask(bridge.port(),
                        "{\"id\":1,\"token\":\"" + token + "\",\"op\":\"echo\",\"args\":{\"value\":42}}"));
                assertTrue(good.get("ok").getAsBoolean());
                assertEquals(42, good.get("result").getAsInt());

                JsonObject bad = json(ask(bridge.port(), "{\"id\":2,\"token\":\"wrong\",\"op\":\"echo\"}"));
                assertFalse(bad.get("ok").getAsBoolean());
                assertTrue(bad.get("error").getAsString().contains("token"));

                JsonObject none = json(ask(bridge.port(), "{\"id\":3,\"op\":\"echo\"}"));
                assertFalse(none.get("ok").getAsBoolean());
            }
            assertFalse(Files.exists(gameDir.resolve("mc_puppet/endpoint-test.json")),
                    "a closed bridge leaves no endpoint file behind");
        }

        @Test
        @DisplayName("two games on one machine: the second takes the next port")
        void portInUse(@TempDir Path first, @TempDir Path second) throws Exception {
            try (Bridge one = Bridge.open("test", ops(), 25_595, first);
                    Bridge two = Bridge.open("test", ops(), 25_595, second)) {
                assertTrue(two.port() != one.port());
            }
        }
    }

    @Test
    @DisplayName("the bridge listens on 127.0.0.1 itself, whichever loopback the JVM prefers")
    void listensWhereTheFileSays(@TempDir Path gameDir) throws Exception {
        Bridge bridge = Bridge.open("test", ops(), 0, gameDir);
        try {
            JsonObject endpoint = json(Files.readString(gameDir.resolve("mc_puppet").resolve("endpoint-test.json")));
            assertEquals("127.0.0.1", endpoint.get("host").getAsString());
            // Dialled as the tools dial it: by that address, not by "localhost", which a JVM
            // started preferring IPv6 resolves to ::1. Forge starts the game that way, and the
            // bridge listened there while the file said here.
            try (Socket socket = new Socket(InetAddress.getByAddress(new byte[] {127, 0, 0, 1}),
                    endpoint.get("port").getAsInt())) {
                assertTrue(socket.isConnected());
            }
        } finally {
            bridge.close();
        }
    }
}
