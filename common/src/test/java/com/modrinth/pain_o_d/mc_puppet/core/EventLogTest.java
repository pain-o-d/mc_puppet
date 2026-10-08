package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

@DisplayName("what happened to the player is kept, numbered, and read back by number")
class EventLogTest {

    private static JsonArray events(EventLog log, long since, String kinds, String contains, int limit) {
        return log.read(since, kinds, contains, limit).getAsJsonObject().getAsJsonArray("events");
    }

    private static JsonObject at(JsonArray events, int index) {
        return events.get(index).getAsJsonObject();
    }

    @Test
    void eachEventGetsTheNextNumberStartingAtOne() {
        EventLog log = new EventLog(10);
        assertEquals(0, log.sequence());
        log.add("chat", "a");
        log.add("toast", "b");
        assertEquals(2, log.sequence());
        JsonArray all = events(log, 0, null, null, 10);
        assertEquals(2, all.size());
        assertEquals(1, at(all, 0).get("seq").getAsLong());
        assertEquals("chat", at(all, 0).get("kind").getAsString());
        assertEquals(2, at(all, 1).get("seq").getAsLong());
        assertEquals(2, log.read(0, null, null, 10).getAsJsonObject().get("sequence").getAsLong());
    }

    @Test
    void readingSinceANumberLeavesOutWhatWasSeen() {
        EventLog log = new EventLog(10);
        log.add("chat", "a");
        log.add("chat", "b");
        log.add("chat", "c");
        JsonArray later = events(log, 1, null, null, 10);
        assertEquals(2, later.size());
        assertEquals("b", at(later, 0).get("text").getAsString());
        assertEquals(0, events(log, 3, null, null, 10).size());
        assertEquals(0, events(log, 99, null, null, 10).size());
    }

    @Test
    void theOldestAreDroppedPastTheCapacityAndNumbersKeepCounting() {
        EventLog log = new EventLog(3);
        for (int i = 1; i <= 5; i++) {
            log.add("chat", "m" + i);
        }
        assertEquals(5, log.sequence(), "numbers are never reused");
        JsonArray kept = events(log, 0, null, null, 10);
        assertEquals(3, kept.size());
        assertEquals(3, at(kept, 0).get("seq").getAsLong());
        assertEquals("m3", at(kept, 0).get("text").getAsString());
        assertEquals("m5", at(kept, 2).get("text").getAsString());
    }

    @Test
    void aLimitKeepsTheLatestAndAtLeastOne() {
        EventLog log = new EventLog(10);
        for (int i = 1; i <= 5; i++) {
            log.add("chat", "m" + i);
        }
        JsonArray two = events(log, 0, null, null, 2);
        assertEquals(2, two.size());
        assertEquals("m4", at(two, 0).get("text").getAsString());
        assertEquals("m5", at(two, 1).get("text").getAsString());
        assertEquals(1, events(log, 0, null, null, 0).size(), "a limit of zero still answers one");
        assertEquals(1, events(log, 0, null, null, -4).size());
        assertEquals("m5", at(events(log, 0, null, null, 0), 0).get("text").getAsString());
    }

    @Test
    void kindsFilterByWholeNameIgnoringCaseAndSpaces() {
        EventLog log = new EventLog(10);
        log.add("chat", "a");
        log.add("actionbar", "b");
        log.add("title", "c");
        log.add("subtitle", "d");
        assertEquals(2, events(log, 0, "chat, title", null, 10).size());
        assertEquals(1, events(log, 0, "CHAT", null, 10).size());
        assertEquals(1, events(log, 0, "title", null, 10).size(), "title is not subtitle");
        assertEquals(0, events(log, 0, "toast", null, 10).size());
    }

    @Test
    void textFiltersByPartIgnoringCase() {
        EventLog log = new EventLog(10);
        log.add("chat", "Welcome Back");
        log.add("chat", "something else");
        assertEquals(1, events(log, 0, null, "welcome", 10).size());
        assertEquals(1, events(log, 0, null, "BACK", 10).size());
        assertEquals(2, events(log, 0, null, "", 10).size(), "the empty string is in everything");
        assertEquals(0, events(log, 0, null, "nowhere", 10).size());
    }

    @Test
    void aNullTextIsKeptAsEmptyAndExtraFieldsRideAlong() {
        EventLog log = new EventLog(10);
        log.add("chat", null);
        JsonObject more = new JsonObject();
        more.addProperty("sender", "Puppet");
        log.add("chat", "hello", more);
        JsonArray all = events(log, 0, null, null, 10);
        assertEquals("", at(all, 0).get("text").getAsString());
        assertFalse(at(all, 0).has("sender"));
        assertEquals("Puppet", at(all, 1).get("sender").getAsString());
    }

    @Test
    void aCapacityOfZeroKeepsNothingButStillCounts() {
        EventLog log = new EventLog(0);
        log.add("chat", "a");
        assertEquals(1, log.sequence());
        assertEquals(0, events(log, 0, null, null, 10).size());
    }

    @Test
    void concurrentAppendsAreAllNumberedOnceEach() throws Exception {
        int threads = 8;
        int each = 500;
        EventLog log = new EventLog(threads * each);
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch go = new CountDownLatch(1);
        List<Future<?>> done = new ArrayList<>();
        for (int t = 0; t < threads; t++) {
            int id = t;
            done.add(pool.submit(() -> {
                go.await();
                for (int i = 0; i < each; i++) {
                    log.add("chat", id + ":" + i);
                    log.read(0, null, null, 1);
                }
                return null;
            }));
        }
        go.countDown();
        for (Future<?> f : done) {
            f.get(30, TimeUnit.SECONDS);
        }
        pool.shutdown();
        assertEquals(threads * each, log.sequence());
        JsonArray all = events(log, 0, null, null, threads * each);
        assertEquals(threads * each, all.size());
        Set<Long> seen = new HashSet<>();
        long previous = 0;
        for (int i = 0; i < all.size(); i++) {
            long seq = at(all, i).get("seq").getAsLong();
            assertTrue(seq > previous, "in order");
            previous = seq;
            seen.add(seq);
        }
        assertEquals(threads * each, seen.size(), "no number twice");
    }
}
