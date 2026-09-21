package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonPrimitive;

@DisplayName("a client's bridge reaches this machine, and no further")
class ReachTest {

    private static JsonObject json(String text) {
        return Protocol.GSON.fromJson(text, JsonObject.class);
    }

    @Test
    @DisplayName("a world of one's own and a server on localhost are this machine; anything else is not")
    void thisMachine() throws Exception {
        assertTrue(Reach.isThisMachine(true, null), "the game's own connection to the server inside it");
        assertTrue(Reach.isThisMachine(false, new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 25565)));
        assertTrue(Reach.isThisMachine(false, new InetSocketAddress(InetAddress.getByName("::1"), 25565)));

        assertFalse(Reach.isThisMachine(false, new InetSocketAddress(InetAddress.getByName("192.168.1.5"), 25565)),
                "the same computer by its network address cannot be told from the one next door");
        assertFalse(Reach.isThisMachine(false, new InetSocketAddress(InetAddress.getByName("203.0.113.7"), 25565)));
        assertFalse(Reach.isThisMachine(false, InetSocketAddress.createUnresolved("play.example.org", 25565)),
                "an address that was never resolved is not taken on trust");
        assertFalse(Reach.isThisMachine(false, null));
    }

    @Test
    @DisplayName("elsewhere, only what tells, lets go and leaves still answers")
    void whatAnswersElsewhere() {
        for (String op : new String[] {"info", "help", "stop", "release_keys", "leave_world", "quit"}) {
            assertNull(Reach.refusalElsewhere(op), op);
        }
        for (String op : new String[] {"click_at", "click_widget", "key", "type", "move_to", "attack", "break_block",
                "use_entity", "command", "say", "screen", "entities", "player", "screenshot", "frame", "blocks",
                "some_mod:its_own_operation"}) {
            assertNotNull(Reach.refusalElsewhere(op), op + " would be a bot's or a radar's");
        }
    }

    @Test
    @DisplayName("the gate is asked for every operation, a batch's steps and what a wait_until polls among them")
    void theGateIsAskedEverywhere() throws Exception {
        AtomicBoolean elsewhere = new AtomicBoolean();
        AtomicInteger clicks = new AtomicInteger();
        Waiter waiter = new Waiter();
        Ops ops = new Ops("client", Runnable::run, waiter);
        ops.now("info", "{}", "Says where.", args -> new JsonPrimitive("here"));
        ops.now("click_at", "{}", "Clicks.", args -> new JsonPrimitive(clicks.incrementAndGet()));
        ops.gate(op -> elsewhere.get() ? Reach.refusalElsewhere(op) : null);

        ops.run("click_at", new JsonObject()).get();
        assertEquals(1, clicks.get(), "at home it clicks");

        elsewhere.set(true);
        ExecutionException refused = assertThrows(ExecutionException.class,
                () -> ops.run("click_at", new JsonObject()).get());
        assertTrue(refused.getCause().getMessage().contains("not on this machine"), refused.getCause().getMessage());
        assertEquals("here", ops.run("info", new JsonObject()).get().getAsString());

        JsonObject batch = ops.run("batch", json("{\"steps\":[{\"op\":\"info\"},{\"op\":\"click_at\"}]}"))
                .get().getAsJsonObject();
        assertFalse(batch.get("ok").getAsBoolean(), "a batch is no way round");
        assertThrows(ExecutionException.class, () -> ops.run("wait_until",
                json("{\"op\":\"click_at\",\"exists\":true,\"timeout_ms\":1000}")).get(), "nor is waiting");
        assertEquals(1, clicks.get(), "and nothing was clicked by any of them");
    }
}
