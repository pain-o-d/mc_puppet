package com.modrinth.pain_o_d.mc_puppet.core;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Supplier;

/** One temporal input owner. Revocation precedes cleanup and every later side effect. */
public final class InputSession {
    private volatile Lease active;

    public Lease begin(String what, Supplier<String> refusal, Executor gameThread, Runnable cleanup) {
        if (active != null) throw new IllegalStateException("input is already owned by " + active.what
                + "; stop or release_keys first");
        String refused = refusal.get();
        if (refused != null) throw new IllegalStateException(refused);
        Lease lease = new Lease(what, refusal, gameThread, cleanup);
        active = lease;
        return lease;
    }

    public void requireIdle() {
        if (active != null) throw new IllegalStateException("input is already owned by " + active.what
                + "; stop or release_keys first");
    }

    public void revoke(String why) {
        Lease lease = active;
        if (lease != null) lease.revoke(why);
    }

    /** Called before vanilla input runs; guards inside pending callbacks remain mandatory. */
    public void tick() {
        Lease lease = active;
        if (lease != null) {
            try { lease.check(); } catch (RuntimeException refused) { lease.revoke(refused.getMessage()); }
        }
    }

    public final class Lease {
        private final String what;
        private final Supplier<String> refusal;
        private final Executor gameThread;
        private final Runnable cleanup;
        private final AtomicBoolean live = new AtomicBoolean(true);
        private final AtomicBoolean cleaned = new AtomicBoolean();
        private final CompletableFuture<Throwable> cleanupFinished = new CompletableFuture<>();
        private volatile CompletableFuture<?> pending;
        private volatile String revoked;

        private Lease(String what, Supplier<String> refusal, Executor gameThread, Runnable cleanup) {
            this.what = what;
            this.refusal = refusal;
            this.gameThread = gameThread;
            this.cleanup = cleanup;
        }

        public void check() {
            if (!live.get()) throw new IllegalStateException(revoked == null ? "input session ended" : revoked);
            String why;
            try { why = refusal.get(); }
            catch (RuntimeException failure) { revoke(failure.getMessage()); throw failure; }
            if (why != null) { revoke(why); throw new IllegalStateException(why); }
        }

        public void revoke(String why) {
            revoked = why;
            live.set(false);
            CompletableFuture<?> answer = pending;
            if (answer != null) answer.completeExceptionally(new IllegalStateException(why));
            else finish(null, ignored -> { });
        }

        /** Cleanup belongs to the game thread even if cancellation came from a socket thread. */
        public <T> CompletableFuture<T> track(CompletableFuture<T> future) {
            if (pending != null) throw new IllegalStateException("an input session has one pending operation");
            pending = future;
            CompletableFuture<T> reply = new CompletableFuture<>();
            future.whenComplete((value, failure) -> finish(failure, error -> {
                if (error == null) reply.complete(value); else reply.completeExceptionally(error);
            }));
            reply.whenComplete((value, failure) -> {
                if (reply.isCancelled()) revoke("input operation was cancelled");
            });
            if (!live.get() && !future.isDone()) future.completeExceptionally(
                    new IllegalStateException(revoked == null ? "input session ended" : revoked));
            return reply;
        }

        private void finish(Throwable failure, java.util.function.Consumer<Throwable> completion) {
            live.set(false);
            if (cleaned.compareAndSet(false, true)) gameThread.execute(() -> {
                Throwable error = null;
                try { cleanup.run(); }
                catch (RuntimeException cleaning) { error = cleaning; }
                finally { if (active == this) active = null; }
                cleanupFinished.complete(error);
            });
            // A revoke before track may have queued cleanup already; every reply waits for it.
            cleanupFinished.thenAccept(cleaning -> {
                Throwable error = failure;
                if (cleaning != null) {
                    if (error == null) error = cleaning;
                    else if (error != cleaning) error.addSuppressed(cleaning);
                }
                completion.accept(error);
            });
        }
    }
}
