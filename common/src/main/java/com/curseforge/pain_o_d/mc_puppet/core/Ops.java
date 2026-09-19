package com.curseforge.pain_o_d.mc_puppet.core;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Executor;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonNull;
import com.google.gson.JsonObject;

/**
 * The operations one side of the game answers to.
 *
 * <p>An operation runs on the game thread and returns a future. Most are done
 * by the time they return. The ones that wait — for a screen, for a line of
 * chat, for so many ticks — hand back a future that {@link Waiter} completes
 * from the tick, so nothing ever blocks the game and nothing polls over the
 * socket.
 *
 * <p>{@code help} and {@code batch} are here rather than with the game's own
 * operations, because they are about operations. {@code batch} is what keeps
 * a test fast: a scenario of thirty steps is one line out and one line back,
 * not thirty round trips each waiting a frame.
 */
public final class Ops {

    /** One operation. Called on the game thread. */
    @FunctionalInterface
    public interface Op {
        CompletableFuture<JsonElement> run(JsonObject args) throws Exception;
    }

    /** An operation that is finished when it returns, which is most of them. */
    @FunctionalInterface
    public interface Now {
        JsonElement run(JsonObject args) throws Exception;
    }

    /** An operation refused for a reason the caller should read. */
    public static final class Refused extends Exception {
        private static final long serialVersionUID = 1L;

        public Refused(String message) {
            super(message);
        }
    }

    private record Entry(Op op, String args, String does) {
    }

    private final String side;
    private final Executor gameThread;
    private final Map<String, Entry> entries = new LinkedHashMap<>();

    /**
     * @param side       "client" or "server", for {@code help}
     * @param gameThread runs a task on the thread the game's state belongs to
     */
    public Ops(String side, Executor gameThread) {
        this.side = side;
        this.gameThread = gameThread;
        now("help", "{}", "Every operation this side answers to, with its arguments.", args -> help());
        add("batch", "{steps: [{op, args}], stop_on_error?: true}",
                "Runs steps in order in one round trip. Returns {results: [{ok, result|error}], completed}.",
                this::batch);
    }

    public void add(String name, String args, String does, Op op) {
        entries.put(name, new Entry(op, args, does));
    }

    public void now(String name, String args, String does, Now op) {
        add(name, args, does, given -> CompletableFuture.completedFuture(op.run(given)));
    }

    /**
     * Runs one operation. Safe from any thread: the work is moved to the game
     * thread, and a failure of any kind comes back as a failed future rather
     * than as an exception in somebody else's thread.
     */
    public CompletableFuture<JsonElement> run(String name, JsonObject args) {
        Entry entry = entries.get(name);
        if (entry == null) {
            return CompletableFuture.failedFuture(
                    new Refused("no such operation on the " + side + ": " + name + " (try \"help\")"));
        }
        CompletableFuture<JsonElement> answer = new CompletableFuture<>();
        try {
            gameThread.execute(() -> {
                try {
                    entry.op().run(args).whenComplete((result, failure) -> {
                        if (failure != null) {
                            answer.completeExceptionally(failure);
                        } else {
                            answer.complete(result == null ? JsonNull.INSTANCE : result);
                        }
                    });
                } catch (Throwable failure) {
                    answer.completeExceptionally(failure);
                }
            });
        } catch (RuntimeException rejected) {
            answer.completeExceptionally(new Refused("the game is not taking work: " + rejected.getMessage()));
        }
        return answer;
    }

    private JsonElement help() {
        JsonObject catalogue = new JsonObject();
        catalogue.addProperty("side", side);
        JsonArray list = new JsonArray();
        for (Map.Entry<String, Entry> each : entries.entrySet()) {
            JsonObject one = new JsonObject();
            one.addProperty("op", each.getKey());
            one.addProperty("args", each.getValue().args());
            one.addProperty("does", each.getValue().does());
            list.add(one);
        }
        catalogue.add("ops", list);
        return catalogue;
    }

    private CompletableFuture<JsonElement> batch(JsonObject args) throws Refused {
        if (!args.has("steps") || !args.get("steps").isJsonArray()) {
            throw new Refused("batch takes \"steps\": an array of {op, args}");
        }
        List<JsonObject> steps = new ArrayList<>();
        for (JsonElement step : args.getAsJsonArray("steps")) {
            if (!step.isJsonObject() || !step.getAsJsonObject().has("op")) {
                throw new Refused("every step of a batch is an object naming an \"op\"");
            }
            if ("batch".equals(step.getAsJsonObject().get("op").getAsString())) {
                throw new Refused("a batch does not nest");
            }
            steps.add(step.getAsJsonObject());
        }
        boolean stopOnError = !args.has("stop_on_error") || args.get("stop_on_error").getAsBoolean();
        CompletableFuture<JsonElement> done = new CompletableFuture<>();
        runStep(steps, 0, stopOnError, new JsonArray(), done);
        return done;
    }

    /** One step, then the next from its completion: steps may wait, and the game must not. */
    private void runStep(List<JsonObject> steps, int index, boolean stopOnError, JsonArray results,
                         CompletableFuture<JsonElement> done) {
        if (index >= steps.size()) {
            done.complete(finished(results, steps.size()));
            return;
        }
        JsonObject step = steps.get(index);
        JsonObject stepArgs = step.has("args") && step.get("args").isJsonObject()
                ? step.getAsJsonObject("args") : new JsonObject();
        run(step.get("op").getAsString(), stepArgs).whenComplete((result, failure) -> {
            JsonObject outcome = new JsonObject();
            outcome.addProperty("op", step.get("op").getAsString());
            outcome.addProperty("ok", failure == null);
            if (failure == null) {
                outcome.add("result", result);
            } else {
                outcome.addProperty("error", messageOf(failure));
            }
            results.add(outcome);
            if (failure != null && stopOnError) {
                done.complete(finished(results, steps.size()));
            } else {
                runStep(steps, index + 1, stopOnError, results, done);
            }
        });
    }

    private static JsonElement finished(JsonArray results, int asked) {
        JsonObject summary = new JsonObject();
        boolean allOk = results.size() == asked;
        for (JsonElement result : results) {
            allOk &= result.getAsJsonObject().get("ok").getAsBoolean();
        }
        summary.addProperty("ok", allOk);
        summary.addProperty("completed", results.size());
        summary.addProperty("of", asked);
        summary.add("results", results);
        return summary;
    }

    /** What went wrong, in words meant for whoever sent the request. */
    public static String messageOf(Throwable failure) {
        Throwable cause = failure;
        while (cause.getCause() != null && (cause instanceof java.util.concurrent.CompletionException
                || cause instanceof java.util.concurrent.ExecutionException)) {
            cause = cause.getCause();
        }
        if (cause instanceof Refused) {
            return cause.getMessage();
        }
        String message = cause.getMessage();
        return cause.getClass().getSimpleName() + (message == null ? "" : ": " + message);
    }
}
