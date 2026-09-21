package com.modrinth.pain_o_d.mc_puppet.core;

import java.util.Iterator;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Supplier;

import com.google.gson.JsonElement;

/**
 * Waits for something to become true, from the game's own tick.
 *
 * <p>A test is mostly waiting: for a world to load, a screen to open, a line
 * to arrive. Waiting over the socket means asking again and again, each time
 * a round trip and a frame; and sleeping means guessing. Here a condition is
 * looked at once a tick, on the thread that owns what it looks at, and the
 * answer goes out the tick it turns true.
 */
public final class Waiter {

    private static final class Pending {
        final Supplier<JsonElement> condition;
        final CompletableFuture<JsonElement> answer;
        final Supplier<String> what;
        long ticksLeft;

        Pending(Supplier<JsonElement> condition, CompletableFuture<JsonElement> answer, Supplier<String> what,
                long ticksLeft) {
            this.condition = condition;
            this.answer = answer;
            this.what = what;
            this.ticksLeft = ticksLeft;
        }
    }

    /** Twenty ticks a second, the game's own. */
    public static final long TICKS_PER_SECOND = 20;

    public static final long DEFAULT_TIMEOUT_MS = 30_000;

    /** Nobody should be able to park a future in the game for an hour. */
    public static final long MAX_TIMEOUT_MS = 10 * 60_000;

    private final List<Pending> pending = new CopyOnWriteArrayList<>();

    /**
     * Completes with what {@code condition} returns the first time that is
     * not {@code null}, or fails after {@code timeoutMs}.
     *
     * @param what said in the failure, so a timed-out test reads as one
     */
    public CompletableFuture<JsonElement> until(String what, long timeoutMs, Supplier<JsonElement> condition) {
        return until(() -> what, timeoutMs, condition);
    }

    /**
     * As {@link #until(String, long, Supplier)}, with what it was waiting for
     * asked at the moment it gives up: "the offers to change; last seen …" is
     * worth more to a failed test than "the offers to change".
     */
    public CompletableFuture<JsonElement> until(Supplier<String> what, long timeoutMs,
                                                Supplier<JsonElement> condition) {
        CompletableFuture<JsonElement> answer = new CompletableFuture<>();
        JsonElement already;
        try {
            already = condition.get();
        } catch (RuntimeException failure) {
            answer.completeExceptionally(failure);
            return answer;
        }
        if (already != null) {
            answer.complete(already);
            return answer;
        }
        long bounded = Math.max(0, Math.min(MAX_TIMEOUT_MS, timeoutMs));
        pending.add(new Pending(condition, answer, what, Math.max(1, bounded * TICKS_PER_SECOND / 1000)));
        return answer;
    }

    /** Once a tick, on the game thread. */
    public void tick() {
        if (pending.isEmpty()) {
            return;
        }
        for (Iterator<Pending> each = pending.iterator(); each.hasNext();) {
            Pending one = each.next();
            JsonElement result;
            try {
                result = one.condition.get();
            } catch (RuntimeException failure) {
                pending.remove(one);
                one.answer.completeExceptionally(failure);
                continue;
            }
            if (result != null) {
                pending.remove(one);
                one.answer.complete(result);
            } else if (--one.ticksLeft <= 0) {
                pending.remove(one);
                one.answer.completeExceptionally(new Ops.Refused("timed out waiting for " + one.what.get()));
            }
        }
    }

    /** Fails everything still waiting, as the side it waits on goes away. */
    public void abandon(String why) {
        for (Pending one : pending) {
            one.answer.completeExceptionally(new Ops.Refused(why));
        }
        pending.clear();
    }

    public int waiting() {
        return pending.size();
    }
}
