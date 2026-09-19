package com.curseforge.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutionException;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonElement;
import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

/**
 * The path and expectation language as the game speaks it, and waiting on it.
 * The scenario runner speaks the same language in JavaScript; the cases here
 * mirror scenario.test.js so that the two cannot drift apart unnoticed.
 */
class ExpectTest {

    private static JsonObject json(String text) {
        return Protocol.GSON.fromJson(text, JsonObject.class);
    }

    private static final String SCREEN = "{\"title\":\"Librarian\",\"widgets\":["
            + "{\"text\":\"Done\",\"x\":10},{\"text\":\"Pay in: Coins\",\"x\":200}],"
            + "\"slots\":[{\"index\":0,\"stack\":null},{\"index\":1,\"stack\":{\"item\":\"minecraft:emerald\","
            + "\"count\":5}}],\"tags\":[\"a\",\"b\"]}";

    @Nested
    @DisplayName("a path")
    class Paths {

        @Test
        @DisplayName("walks keys and indexes, from either end")
        void keysAndIndexes() throws Ops.Refused {
            JsonObject screen = json(SCREEN);
            assertEquals("Librarian", Expect.at(screen, "title").getAsString());
            assertEquals("Done", Expect.at(screen, "widgets[0].text").getAsString());
            assertEquals(200, Expect.at(screen, "widgets[-1].x").getAsInt());
            assertEquals(5, Expect.at(screen, "slots[1].stack.count").getAsInt());
        }

        @Test
        @DisplayName("finds an element by what it says, exactly or in part, ignoring case")
        void selectors() throws Ops.Refused {
            JsonObject screen = json(SCREEN);
            assertEquals(10, Expect.at(screen, "widgets[text=done].x").getAsInt());
            assertEquals(200, Expect.at(screen, "widgets[text~=pay in].x").getAsInt());
            assertEquals("minecraft:emerald", Expect.at(screen, "slots[index=1].stack.item").getAsString());
            assertEquals(1, Expect.at(screen, "slots[stack.item~=emerald].index").getAsInt());
        }

        @Test
        @DisplayName("counts with #")
        void counts() throws Ops.Refused {
            assertEquals(2, Expect.at(json(SCREEN), "widgets#").getAsInt());
        }

        @Test
        @DisplayName("leads nowhere quietly: a missing thing is null, not an error")
        void nowhere() throws Ops.Refused {
            JsonObject screen = json(SCREEN);
            assertNull(Expect.at(screen, "nothing.here"));
            assertNull(Expect.at(screen, "widgets[9].text"));
            assertNull(Expect.at(screen, "widgets[text=absent].x"));
            assertNull(Expect.at(screen, "slots[0].stack.count"));
        }

        @Test
        @DisplayName("is refused in words when it is not a path")
        void malformed() {
            assertThrows(Ops.Refused.class, () -> Expect.at(json(SCREEN), "widgets[0"));
            assertThrows(Ops.Refused.class, () -> Expect.at(json(SCREEN), "widgets[what]"));
        }
    }

    @Nested
    @DisplayName("an expectation")
    class Expectations {

        private String check(String expectation) throws Ops.Refused {
            return Expect.check(json(expectation), json(SCREEN));
        }

        @Test
        @DisplayName("passes as null and fails as a sentence")
        void passesAndFails() throws Ops.Refused {
            assertNull(check("{\"path\":\"title\",\"equals\":\"Librarian\"}"));
            String problem = check("{\"path\":\"title\",\"equals\":\"Farmer\"}");
            assertNotNull(problem);
            assertTrue(problem.contains("Librarian") && problem.contains("Farmer"), problem);
        }

        @Test
        @DisplayName("takes 5 and 5.0 for the same number")
        void numbers() throws Ops.Refused {
            assertNull(check("{\"path\":\"slots[1].stack.count\",\"equals\":5.0}"));
            assertNotNull(check("{\"path\":\"slots[1].stack.count\",\"not\":5.0}"));
        }

        @Test
        @DisplayName("compares, contains and matches")
        void theRest() throws Ops.Refused {
            assertNull(check("{\"path\":\"widgets[1].x\",\"gt\":100,\"lte\":200}"));
            assertNotNull(check("{\"path\":\"widgets[1].x\",\"lt\":200}"));
            assertNull(check("{\"path\":\"title\",\"contains\":\"LIBR\"}"));
            assertNull(check("{\"path\":\"tags\",\"contains\":\"b\"}"));
            assertNotNull(check("{\"path\":\"tags\",\"contains\":\"c\"}"));
            assertNull(check("{\"path\":\"widgets[1].text\",\"matches\":\"^pay in: \\\\w+$\"}"));
            assertThrows(Ops.Refused.class, () -> check("{\"path\":\"title\",\"matches\":\"(\"}"));
        }

        @Test
        @DisplayName("knows an empty slot from a missing one: null does not exist")
        void exists() throws Ops.Refused {
            assertNull(check("{\"path\":\"slots[0].stack\",\"exists\":false}"));
            assertNull(check("{\"path\":\"slots[1].stack\",\"exists\":true}"));
            assertNotNull(check("{\"path\":\"slots[0].stack\",\"exists\":true}"));
        }

        @Test
        @DisplayName("a number that is missing fails a comparison rather than passing it")
        void missingNumber() throws Ops.Refused {
            assertNotNull(check("{\"path\":\"slots[0].stack.count\",\"lt\":10}"));
        }
    }

    @Nested
    @DisplayName("wait_until")
    class WaitUntil {

        private final Waiter waiter = new Waiter();
        private final int[] counter = {0};

        private Ops ops() {
            Ops ops = new Ops("test", Runnable::run, waiter);
            ops.now("count", "{}", "Counts how often it was asked.", args -> {
                JsonObject json = new JsonObject();
                json.addProperty("asked", ++counter[0]);
                return json;
            });
            ops.now("shy", "{}", "Refuses twice, then answers.", args -> {
                if (++counter[0] < 3) {
                    throw new Ops.Refused("no screen is open");
                }
                return new JsonPrimitive("open");
            });
            ops.add("slow", "{}", "Never answers at once.", args -> new CompletableFuture<>());
            return ops;
        }

        @Test
        @DisplayName("asks once a tick and answers the tick the expectation holds, with the part asked for")
        void waits() throws Exception {
            CompletableFuture<JsonElement> answer = ops().run("wait_until",
                    json("{\"op\":\"count\",\"path\":\"asked\",\"gte\":3}"));
            assertFalse(answer.isDone());
            waiter.tick();
            assertFalse(answer.isDone());
            waiter.tick();
            assertTrue(answer.isDone());
            assertEquals(3, answer.get().getAsInt());
            assertEquals(0, waiter.waiting());
        }

        @Test
        @DisplayName("answers at once when the expectation already holds")
        void already() throws Exception {
            CompletableFuture<JsonElement> answer = ops().run("wait_until",
                    json("{\"op\":\"count\",\"path\":\"asked\",\"equals\":1}"));
            assertTrue(answer.isDone());
            assertEquals(1, answer.get().getAsInt());
        }

        @Test
        @DisplayName("takes a refusal for not yet: a screen that is not open is one to wait for")
        void refusalIsNotYet() throws Exception {
            CompletableFuture<JsonElement> answer = ops().run("wait_until",
                    json("{\"op\":\"shy\",\"equals\":\"open\"}"));
            waiter.tick();
            waiter.tick();
            assertEquals("open", answer.get().getAsString());
        }

        @Test
        @DisplayName("times out saying what it last saw")
        void timesOut() {
            CompletableFuture<JsonElement> answer = ops().run("wait_until",
                    json("{\"op\":\"count\",\"path\":\"asked\",\"lt\":0,\"timeout_ms\":100}"));
            for (int tick = 0; tick < 5; tick++) {
                waiter.tick();
            }
            ExecutionException failure = assertThrows(ExecutionException.class, answer::get);
            String message = Ops.messageOf(failure);
            assertTrue(message.contains("timed out") && message.contains("\"asked\" is"), message);
        }

        @Test
        @DisplayName("refuses what cannot be waited on: nothing expected, an unknown op, an op that waits itself")
        void refuses() {
            assertThrows(ExecutionException.class,
                    () -> ops().run("wait_until", json("{\"op\":\"count\"}")).get());
            assertThrows(ExecutionException.class,
                    () -> ops().run("wait_until", json("{\"op\":\"absent\",\"exists\":true}")).get());
            assertThrows(ExecutionException.class,
                    () -> ops().run("wait_until", json("{\"op\":\"batch\",\"exists\":true}")).get());
        }

        @Test
        @DisplayName("a malformed path ends the wait at once instead of timing out")
        void badPath() {
            CompletableFuture<JsonElement> answer = ops().run("wait_until",
                    json("{\"op\":\"count\",\"path\":\"asked[\",\"exists\":true}"));
            assertTrue(answer.isCompletedExceptionally());
        }
    }
}
