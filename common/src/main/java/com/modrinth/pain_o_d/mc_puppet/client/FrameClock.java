package com.modrinth.pain_o_d.mc_puppet.client;

import java.util.Arrays;

import com.google.gson.JsonObject;

/**
 * When each frame began, the last 8192 of them: what a watch says of the frame rate while it watched. One
 * {@code System.nanoTime()} a frame into an array; nothing else.
 */
public final class FrameClock {

    private static final long[] BEGAN = new long[8192];
    private static long frames;

    private FrameClock() {
    }

    /** From the frame hook. */
    static void frame() {
        BEGAN[(int) (frames & (BEGAN.length - 1))] = System.nanoTime();
        frames++;
    }

    public static long frames() {
        return frames;
    }

    /** Frames since {@code from} (a {@link #frames()} taken earlier): how many a second, and their times in ms. */
    public static JsonObject since(long from, long startedNanos) {
        JsonObject out = new JsonObject();
        long to = frames;
        long count = Math.min(to - from, BEGAN.length - 1);
        double seconds = (System.nanoTime() - startedNanos) / 1e9;
        out.addProperty("frames", to - from);
        out.addProperty("fps", Math.round((to - from) / Math.max(0.001, seconds) * 10) / 10.0);
        if (count >= 2) {
            double[] times = new double[(int) count - 1];
            for (int i = 0; i < times.length; i++) {
                long a = BEGAN[(int) ((to - count + i) & (BEGAN.length - 1))];
                long b = BEGAN[(int) ((to - count + i + 1) & (BEGAN.length - 1))];
                times[i] = (b - a) / 1e6;
            }
            double sum = 0;
            for (double t : times) {
                sum += t;
            }
            Arrays.sort(times);
            out.addProperty("frame_ms_mean", Math.round(sum / times.length * 100) / 100.0);
            out.addProperty("frame_ms_p95", Math.round(times[(int) Math.floor(0.95 * (times.length - 1))] * 100) / 100.0);
            out.addProperty("frame_ms_max", Math.round(times[times.length - 1] * 100) / 100.0);
            int stalls = 0;
            for (double t : times) {
                if (t > 50) {
                    stalls++;
                }
            }
            out.addProperty("stalls_over_50ms", stalls);
        }
        return out;
    }
}
