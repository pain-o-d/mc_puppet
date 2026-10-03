package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.*;
import java.util.ArrayDeque;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionException;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.Test;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;

class InputSessionTest {
    @Test void queuedPressBeforePreScopeRefusalCannotFireInTheNewScope() {
        InputSession owner = new InputSession(); AtomicReference<String> scope = new AtomicReference<>();
        AtomicInteger use = new AtomicInteger(1), shared = new AtomicInteger(1), unrelated = new AtomicInteger(1);
        var lease = owner.begin("world hold or mouse button", scope::get, Runnable::run,
                () -> { use.set(0); shared.set(0); });
        var result = lease.track(new CompletableFuture<JsonElement>());
        scope.set("screen/world/Reach changed before vanilla consumed the initial press"); owner.tick();
        assertTrue(result.isCompletedExceptionally()); assertEquals(0, use.get()); assertEquals(0, shared.get());
        assertEquals(1, unrelated.get()); assertThrows(IllegalStateException.class, lease::check);
    }

    @Test void cancelledWaiterCannotReassertKeys() {
        InputSession owner = new InputSession();
        Waiter waiter = new Waiter();
        AtomicInteger presses = new AtomicInteger(), releases = new AtomicInteger();
        var lease = owner.begin("hold", () -> null, Runnable::run, releases::incrementAndGet);
        CompletableFuture<JsonElement> result = lease.track(waiter.until("hold", 5000, () -> {
            lease.check(); presses.incrementAndGet(); return null;
        }));
        assertEquals(1, presses.get());
        result.cancel(false);
        waiter.tick(); waiter.tick();
        assertEquals(1, presses.get()); assertEquals(1, releases.get());
        assertEquals(0, waiter.waiting()); owner.requireIdle();
    }

    @Test void stopAndReleaseRevokeBeforePendingTick() {
        for (String reason : new String[] {"input was stopped", "input was released"}) {
            InputSession owner = new InputSession(); Waiter waiter = new Waiter();
            AtomicInteger presses = new AtomicInteger(), releases = new AtomicInteger();
            var lease = owner.begin("hold", () -> null, Runnable::run, releases::incrementAndGet);
            var result = lease.track(waiter.until("hold", 5000, () -> {
                lease.check(); presses.incrementAndGet(); return null;
            }));
            owner.revoke(reason); waiter.tick();
            assertTrue(result.isCompletedExceptionally());
            assertEquals(1, presses.get()); assertEquals(1, releases.get());
            owner.requireIdle();
        }
    }

    @Test void timeoutCleansAndCannotReassertLater() {
        InputSession owner = new InputSession(); Waiter waiter = new Waiter();
        AtomicInteger presses = new AtomicInteger(), releases = new AtomicInteger();
        var lease = owner.begin("hold", () -> null, Runnable::run, releases::incrementAndGet);
        var result = lease.track(waiter.until("hold", 0, () -> {
            lease.check(); presses.incrementAndGet(); return null;
        }));
        waiter.tick(); int atTimeout = presses.get(); waiter.tick();
        assertTrue(result.isCompletedExceptionally());
        assertEquals(atTimeout, presses.get()); assertEquals(1, releases.get()); owner.requireIdle();
    }

    @Test void everyScopeTransitionRevokesBeforeEffect() {
        for (String changed : new String[] {"world", "player", "handler", "screen", "Reach", "focus"}) {
            InputSession owner = new InputSession(); AtomicReference<String> refusal = new AtomicReference<>();
            AtomicInteger effect = new AtomicInteger(), releases = new AtomicInteger();
            var lease = owner.begin("input", refusal::get, Runnable::run, releases::incrementAndGet);
            var source = new CompletableFuture<JsonElement>(); var result = lease.track(source);
            refusal.set(changed); owner.tick();
            assertThrows(IllegalStateException.class, () -> { lease.check(); effect.incrementAndGet(); });
            assertTrue(result.isCompletedExceptionally()); assertEquals(0, effect.get());
            assertEquals(1, releases.get()); owner.requireIdle();
        }
    }

    @Test void overlappingOwnerCannotAcquireOrEndAnotherFocus() {
        InputSession owner = new InputSession(); AtomicReference<Object> focus = new AtomicReference<>();
        Object first = new Object(), second = new Object();
        var lease = owner.begin("first", () -> null, Runnable::run, () -> focus.compareAndSet(first, null));
        focus.set(first);
        assertThrows(IllegalStateException.class, () -> owner.begin("overlap", () -> null, Runnable::run, () -> { }));
        lease.revoke("stop");
        var next = owner.begin("second", () -> null, Runnable::run, () -> focus.compareAndSet(second, null));
        focus.set(second); lease.revoke("stale old cleanup");
        assertSame(second, focus.get()); next.revoke("done"); assertNull(focus.get());
    }

    @Test void offThreadCancellationQueuesCleanupAndKeepsOwnerUntilItRuns() throws Exception {
        InputSession owner = new InputSession(); ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        AtomicInteger releases = new AtomicInteger();
        var lease = owner.begin("hold", () -> null, tasks::add, releases::incrementAndGet);
        var reply = lease.track(new CompletableFuture<JsonElement>());
        Thread cancelling = new Thread(() -> reply.cancel(false)); cancelling.start(); cancelling.join();
        assertEquals(0, releases.get()); assertEquals(1, tasks.size());
        assertThrows(IllegalStateException.class, owner::requireIdle);
        assertThrows(IllegalStateException.class, lease::check);
        tasks.remove().run(); assertEquals(1, releases.get()); owner.requireIdle();
    }

    @Test void setupFailureBeforeFutureStillCleansExactlyOnce() {
        InputSession owner = new InputSession(); AtomicInteger releases = new AtomicInteger();
        var lease = owner.begin("setup", () -> null, Runnable::run, releases::incrementAndGet);
        lease.revoke("setup failed before waiter"); lease.revoke("repeat");
        assertEquals(1, releases.get()); owner.requireIdle();
    }

    @Test void admissionRefusalDoesNotClaimInput() {
        InputSession owner = new InputSession(); AtomicInteger releases = new AtomicInteger();
        assertThrows(IllegalStateException.class, () -> owner.begin("invalid", () -> "not local",
                Runnable::run, releases::incrementAndGet));
        assertEquals(0, releases.get()); owner.requireIdle();
    }

    @Test void revokeBeforeTrackStillWaitsForQueuedGameThreadCleanup() {
        InputSession owner = new InputSession(); ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        AtomicInteger releases = new AtomicInteger();
        var lease = owner.begin("setup", () -> null, tasks::add, releases::incrementAndGet);
        lease.revoke("setup was revoked before the waiter attached");
        var reply = lease.track(new CompletableFuture<JsonElement>());
        assertFalse(reply.isDone()); assertEquals(0, releases.get()); assertEquals(1, tasks.size());
        assertThrows(IllegalStateException.class, owner::requireIdle);
        tasks.remove().run();
        assertTrue(reply.isCompletedExceptionally()); assertEquals(1, releases.get()); owner.requireIdle();
    }

    @Test void successfulResultWaitsForGameThreadCleanup() {
        InputSession owner = new InputSession(); ArrayDeque<Runnable> tasks = new ArrayDeque<>();
        AtomicInteger releases = new AtomicInteger();
        var lease = owner.begin("gesture", () -> null, tasks::add, releases::incrementAndGet);
        var source = new CompletableFuture<JsonElement>(); var reply = lease.track(source);
        source.complete(JsonNull.INSTANCE);
        assertFalse(reply.isDone()); assertThrows(IllegalStateException.class, lease::check);
        tasks.remove().run(); assertSame(JsonNull.INSTANCE, reply.join());
        assertEquals(1, releases.get()); owner.requireIdle();
    }

    @Test void conditionFailureAndCleanupFailureAreBothReported() {
        InputSession owner = new InputSession(); RuntimeException initial = new IllegalStateException("action");
        var lease = owner.begin("gesture", () -> null, Runnable::run,
                () -> { throw new IllegalStateException("cleanup"); });
        var source = new CompletableFuture<JsonElement>(); var reply = lease.track(source);
        source.completeExceptionally(initial);
        var reported = assertThrows(CompletionException.class, reply::join);
        assertSame(initial, reported.getCause()); assertEquals("cleanup", initial.getSuppressed()[0].getMessage());
        owner.requireIdle();
    }
}
