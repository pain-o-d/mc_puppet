package com.modrinth.pain_o_d.mc_puppet.api;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.modrinth.pain_o_d.mc_puppet.core.Ops;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * Operations of a mod's own.
 *
 * <p>A test of a mod wants the mod's state: what a price is and why, what a
 * machine holds, whose turn it is. Without this it gets there by running a
 * command and picking the answer out of chat, which tests the wording. A mod
 * registers an operation instead and answers with data:
 *
 * <pre>{@code
 * if (Platform.isModLoaded("mc_puppet")) {
 *     MyPuppetOps.register();   // a class of its own, so nothing loads without the mod
 * }
 *
 * PuppetApi.register(PuppetApi.Side.SERVER, "get_rich:price", "{item}",
 *         "What an item is worth and by which route.",
 *         args -> priceJson(args.get("item").getAsString()));
 * }</pre>
 *
 * <p>The handler runs on the game thread of its side, like the built-in
 * operations, and whatever it throws comes back to the test as a refusal in
 * words. Names carry the mod's id before a colon, so that they cannot collide
 * with each other or with anything added here later. Register at any time:
 * before the bridge opens or after.
 *
 * <p>Compile against MC Puppet without requiring it: {@code modCompileOnly},
 * an optional dependency in the mod's metadata, and the check above. With MC
 * Puppet absent or switched off, registering does nothing that costs anything.
 */
public final class PuppetApi {

    private PuppetApi() {
    }

    public enum Side {
        CLIENT, SERVER
    }

    /** Answers one request. Runs on the game thread. */
    @FunctionalInterface
    public interface Handler {
        JsonElement handle(JsonObject args) throws Exception;
    }

    private record Registration(Side side, String name, String args, String does, Handler handler) {
    }

    private static final List<Registration> REGISTERED = new CopyOnWriteArrayList<>();
    private static final List<Attached> ATTACHED = new CopyOnWriteArrayList<>();

    private record Attached(Side side, Ops ops) {
    }

    /**
     * @param name {@code modid:operation}
     * @param args what it takes, for {@code help}: {@code "{item, count?: 1}"}
     * @param does one sentence, for {@code help}
     * @throws IllegalArgumentException when the name has no mod id, or is taken
     */
    public static void register(Side side, String name, String args, String does, Handler handler) {
        if (name == null || !name.matches("[a-z0-9_.-]+:[a-z0-9_./-]+")) {
            throw new IllegalArgumentException("an operation a mod adds is named modid:operation, not \"" + name
                    + "\"");
        }
        for (Registration each : REGISTERED) {
            if (each.side() == side && each.name().equals(name)) {
                throw new IllegalArgumentException(name + " is already registered on the " + side);
            }
        }
        Registration registration = new Registration(side, name, args == null ? "{}" : args,
                does == null ? "" : does, handler);
        REGISTERED.add(registration);
        for (Attached attached : ATTACHED) {
            if (attached.side() == side) {
                add(attached.ops(), registration);
            }
        }
    }

    /** Every name registered for a side, in order. */
    public static List<String> registered(Side side) {
        List<String> names = new ArrayList<>();
        for (Registration each : REGISTERED) {
            if (each.side() == side) {
                names.add(each.name());
            }
        }
        return names;
    }

    /**
     * Not for mods: hands a side's operations over as its bridge opens, so
     * that what is registered, and what will be, is answered there.
     */
    public static void attach(Side side, Ops ops) {
        ATTACHED.removeIf(attached -> attached.side() == side);
        ATTACHED.add(new Attached(side, ops));
        for (Registration each : REGISTERED) {
            if (each.side() == side) {
                add(ops, each);
            }
        }
    }

    private static void add(Ops ops, Registration registration) {
        ops.now(registration.name(), registration.args(), registration.does(),
                args -> registration.handler().handle(args));
    }
}
