package com.modrinth.pain_o_d.mc_puppet.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Supplier;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

import net.minecraft.entity.Entity;
import net.minecraft.entity.LivingEntity;
import net.minecraft.registry.Registries;
import net.minecraft.util.math.BlockPos;
import net.minecraft.util.math.Direction;
import net.minecraft.world.World;

/**
 * Watches entities every tick for a while and says how they moved: what a screenshot shows one frame of and a
 * test cannot assert. Entities that appear or vanish, and those that blink (live a few ticks); the largest step
 * a tick and the jumps (a step no walk makes - a teleport); the sharpest turn; those that move without their
 * legs moving (slide); those standing on nothing or inside a block; pairs closer than their width (through one
 * another). The worst of each, with where and when, so a failed test says what to look at.
 *
 * <p>Nothing is asked of the world but its entity list and the blocks at their feet: a box query would itself
 * be something a mod can answer (one that makes bodies for what is looked at would make them for the watch).
 */
public final class Watch {

    /** The longest watch: a minute. */
    public static final int MAX_TICKS = 1200;
    private static final int BLINK_TICKS = 5;
    private static final int WORST = 8;

    private static final class Track {
        double x;
        double y;
        double z;
        float yaw;
        int firstTick;
        int lastTick;
        boolean first = true;
        /** The last {@link #HISTORY} ticks: x, y, z, yaw, legs' speed. */
        final double[][] history = new double[HISTORY][];
        int written;

        void remember(Entity entity) {
            double legs = entity instanceof LivingEntity living ? living.limbAnimator.getSpeed() : 0;
            this.history[this.written % HISTORY] = new double[] {entity.getX(), entity.getY(), entity.getZ(), entity.getYaw(), legs};
            this.written++;
        }

        JsonArray recent() {
            JsonArray out = new JsonArray();
            for (int k = Math.max(0, this.written - HISTORY); k < this.written; k++) {
                double[] h = this.history[k % HISTORY];
                out.add(round(h[0]) + " " + round(h[1]) + " " + round(h[2]) + " yaw " + round(h[3]) + " legs " + round(h[4]));
            }
            return out;
        }
    }

    private static final int HISTORY = 10;

    private record Event(String kind, int entity, int tick, double value, double x, double y, double z, JsonArray history) {
    }

    private final Supplier<Iterable<Entity>> source;
    private final int ticks;
    private final double jump;
    private final double turn;
    private final Map<Integer, Track> tracks = new HashMap<>();
    private final List<Event> worst = new ArrayList<>();
    private final List<Double> steps = new ArrayList<>();
    private int tick;
    private int entitiesMin = Integer.MAX_VALUE;
    private int entitiesMax;
    private int appeared;
    private int disappeared;
    private int blinks;
    private int jumps;
    private int turns;
    private double stepMax;
    private double turnMax;
    private long moving;
    private long sliding;
    private long floating;
    private long buried;
    private long overlaps;
    private int overlapsMax;
    private boolean living;

    /**
     * @param source the entities to watch this tick, already filtered by the caller
     * @param args {@code ticks} (default 100), {@code jump} (blocks a tick counted a jump, default 1.0),
     *             {@code turn} (degrees a tick counted a sharp turn, default 45)
     */
    public Watch(Supplier<Iterable<Entity>> source, JsonObject args) throws Ops.Refused {
        this.source = source;
        this.ticks = Math.max(1, Math.min(MAX_TICKS, Args.integer(args, "ticks", 100)));
        this.jump = args.has("jump") ? Args.decimal(args, "jump") : 1.0;
        this.turn = args.has("turn") ? Args.decimal(args, "turn") : 45;
    }

    public int ticks() {
        return this.ticks;
    }

    /** Once a tick, from the waiter: null until the watch is over, then what it saw. */
    public JsonElement tick() {
        List<Entity> now = new ArrayList<>();
        for (Entity entity : this.source.get()) {
            if (entity.isAlive()) {
                now.add(entity);
            }
        }
        this.entitiesMin = Math.min(this.entitiesMin, now.size());
        this.entitiesMax = Math.max(this.entitiesMax, now.size());
        Map<Integer, Boolean> seen = new HashMap<>();
        for (Entity entity : now) {
            int id = entity.getId();
            seen.put(id, true);
            Track track = this.tracks.get(id);
            if (track == null) {
                track = new Track();
                track.firstTick = this.tick;
                this.tracks.put(id, track);
                if (this.tick > 0) {
                    this.appeared++;
                }
            }
            track.remember(entity);   // this tick's too, so an event's history ends where it happened
            if (!track.first) {
                double dx = entity.getX() - track.x;
                double dz = entity.getZ() - track.z;
                double step = Math.sqrt(dx * dx + dz * dz);
                this.steps.add(step);
                this.stepMax = Math.max(this.stepMax, step);
                if (step > this.jump) {
                    this.jumps++;
                    note("jump", entity, step);
                }
                double turned = Math.abs(((entity.getYaw() - track.yaw) % 360 + 540) % 360 - 180);
                this.turnMax = Math.max(this.turnMax, turned);
                if (turned > this.turn) {
                    this.turns++;
                    note("turn", entity, turned);
                }
                if (step > 0.03) {
                    this.moving++;
                    if (entity instanceof LivingEntity living) {
                        this.living = true;
                        if (living.limbAnimator.getSpeed() < 0.05f) {
                            this.sliding++;
                            note("slide", entity, step);
                        }
                    }
                }
            }
            standing(entity);
            track.x = entity.getX();
            track.y = entity.getY();
            track.z = entity.getZ();
            track.yaw = entity.getYaw();
            track.lastTick = this.tick;
            track.first = false;
        }
        for (Map.Entry<Integer, Track> entry : this.tracks.entrySet()) {
            Track track = entry.getValue();
            if (track.lastTick == this.tick - 1 && !seen.containsKey(entry.getKey())) {
                this.disappeared++;
                if (track.firstTick > 0 && track.lastTick - track.firstTick < BLINK_TICKS) {
                    this.blinks++;
                    this.worstAdd(new Event("blink", entry.getKey(), this.tick, track.lastTick - track.firstTick + 1,
                            track.x, track.y, track.z, track.recent()));
                }
            }
        }
        overlap(now);
        this.tick++;
        return this.tick >= this.ticks ? summary() : null;
    }

    /** On nothing, or inside a block: the block at the feet and the one under them. */
    private void standing(Entity entity) {
        World world = entity.getWorld();
        if (entity.hasVehicle()) {
            return;
        }
        BlockPos feet = entity.getBlockPos();
        var at = world.getBlockState(feet).getCollisionShape(world, feet);
        if (!at.isEmpty() && feet.getY() + at.getMax(Direction.Axis.Y) > entity.getY() + 0.1
                && feet.getY() + at.getMin(Direction.Axis.Y) < entity.getY() + 0.5) {
            this.buried++;
            note("buried", entity, 0);
            return;
        }
        // On nothing: an entity kept up by no gravity (a mod's placed body, a picture's) over air. A body with
        // gravity in the air is falling or jumping, and a flyer flies: neither is a defect.
        if (at.isEmpty() && entity.hasNoGravity() && !entity.isOnGround() && !entity.isTouchingWater()
                && !(entity instanceof net.minecraft.entity.Flutterer)
                && world.getBlockState(feet.down()).getCollisionShape(world, feet.down()).isEmpty()) {
            this.floating++;
            note("floating", entity, 0);
        }
    }

    /** Pairs closer than the narrower of the two, level with each other: one through the other. */
    private void overlap(List<Entity> now) {
        Map<Long, List<Entity>> cells = new HashMap<>();
        for (Entity entity : now) {
            long key = cell(Math.floor(entity.getX()), Math.floor(entity.getZ()));
            cells.computeIfAbsent(key, k -> new ArrayList<>()).add(entity);
        }
        int pairs = 0;
        for (Entity a : now) {
            long cx = (long) Math.floor(a.getX());
            long cz = (long) Math.floor(a.getZ());
            for (long dx = -1; dx <= 1; dx++) {
                for (long dz = -1; dz <= 1; dz++) {
                    List<Entity> cell = cells.get(cell(cx + dx, cz + dz));
                    if (cell == null) {
                        continue;
                    }
                    for (Entity b : cell) {
                        if (b.getId() <= a.getId() || Math.abs(a.getY() - b.getY()) > 1) {
                            continue;
                        }
                        double width = Math.min(a.getWidth(), b.getWidth());
                        double ex = a.getX() - b.getX();
                        double ez = a.getZ() - b.getZ();
                        if (ex * ex + ez * ez < width * width) {
                            pairs++;
                            if (this.worst.size() < WORST) {
                                note("overlap", a, Math.sqrt(ex * ex + ez * ez));
                            }
                        }
                    }
                }
            }
        }
        this.overlaps += pairs;
        this.overlapsMax = Math.max(this.overlapsMax, pairs);
    }

    private static long cell(double x, double z) {
        return ((long) x) << 32 ^ (((long) z) & 0xFFFFFFFFL);
    }

    private void note(String kind, Entity entity, double value) {
        Track track = this.tracks.get(entity.getId());
        worstAdd(new Event(kind, entity.getId(), this.tick, value, entity.getX(), entity.getY(), entity.getZ(),
                track == null ? new JsonArray() : track.recent()));
    }

    /** The worst eight, one of a kind first, then by value. */
    private void worstAdd(Event event) {
        for (int i = 0; i < this.worst.size(); i++) {
            Event other = this.worst.get(i);
            if (other.kind().equals(event.kind())) {
                if (event.value() > other.value()) {
                    this.worst.set(i, event);
                }
                return;
            }
        }
        if (this.worst.size() < WORST) {
            this.worst.add(event);
        }
    }

    private JsonObject summary() {
        JsonObject out = new JsonObject();
        out.addProperty("ticks", this.tick);
        out.addProperty("entities_min", this.entitiesMin == Integer.MAX_VALUE ? 0 : this.entitiesMin);
        out.addProperty("entities_max", this.entitiesMax);
        out.addProperty("appeared", this.appeared);
        out.addProperty("disappeared", this.disappeared);
        out.addProperty("blinks", this.blinks);
        out.addProperty("step_max", round(this.stepMax));
        out.addProperty("step_p95", round(quantile(this.steps, 0.95)));
        out.addProperty("jumps", this.jumps);
        out.addProperty("turn_max", round(this.turnMax));
        out.addProperty("turns", this.turns);
        out.addProperty("moving", this.moving);
        if (this.living) {
            out.addProperty("sliding", this.sliding);
            out.addProperty("sliding_share", this.moving == 0 ? 0 : round((double) this.sliding / this.moving));
        }
        out.addProperty("floating", this.floating);
        out.addProperty("buried", this.buried);
        out.addProperty("overlaps_mean", round((double) this.overlaps / Math.max(1, this.tick)));
        out.addProperty("overlaps_max", this.overlapsMax);
        JsonArray list = new JsonArray();
        for (Event event : this.worst) {
            JsonObject one = new JsonObject();
            one.addProperty("kind", event.kind());
            one.addProperty("entity", event.entity());
            one.addProperty("tick", event.tick());
            one.addProperty("value", round(event.value()));
            one.addProperty("at", round(event.x()) + " " + round(event.y()) + " " + round(event.z()));
            one.add("history", event.history());
            list.add(one);
        }
        out.add("worst", list);
        return out;
    }

    private static double quantile(List<Double> values, double q) {
        if (values.isEmpty()) {
            return 0;
        }
        List<Double> sorted = new ArrayList<>(values);
        sorted.sort(null);
        return sorted.get(Math.min(sorted.size() - 1, (int) Math.floor(q * sorted.size())));
    }

    private static double round(double value) {
        return Math.round(value * 1000) / 1000.0;
    }

    /**
     * Whether {@code entity} passes the watch's {@code ai} filter: absent, everything; false, only mobs whose
     * brain is off (a mod's placed bodies and pictures); true, only mobs that think for themselves.
     */
    public static boolean ofAi(Entity entity, JsonObject args) {
        if (!args.has("ai")) {
            return true;
        }
        boolean wanted = args.get("ai").getAsBoolean();
        return entity instanceof net.minecraft.entity.mob.MobEntity mob && mob.isAiDisabled() != wanted;
    }

    /** Whether {@code entity} is of the type an op was asked about, or any type when none was. */
    public static boolean ofType(Entity entity, String type) {
        return type == null || Registries.ENTITY_TYPE.getId(entity.getType()).toString().equals(type);
    }
}
