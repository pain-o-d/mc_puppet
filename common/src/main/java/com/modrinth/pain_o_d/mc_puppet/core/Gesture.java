package com.modrinth.pain_o_d.mc_puppet.core;

import java.util.ArrayList;
import java.util.List;

/**
 * The arithmetic of a mouse moved with a button held, without a game: where
 * the cursor is on each tick of a drag, and how many pixels a turn of the head
 * is.
 */
public final class Gesture {

    private Gesture() {
    }

    /**
     * Degrees of turn a pixel of mouse movement makes at a sensitivity (the
     * option's 0 to 1): the game's cube of {@code s * 0.6 + 0.2}, times 8,
     * then the 0.15 a player's head turns by. The cinematic camera and a
     * spyglass make it something else.
     */
    public static double degreesPerPixel(double sensitivity) {
        double s = sensitivity * 0.6 + 0.2;
        return s * s * s * 8 * 0.15;
    }

    /**
     * The points the cursor passes, one a tick, going from {@code from}
     * through each of {@code path}, every leg cut into {@code steps} even
     * moves; the last is the last of the path.
     */
    public static List<double[]> legs(double[] from, List<double[]> path, int steps) {
        int each = Math.max(1, steps);
        List<double[]> points = new ArrayList<>();
        double[] was = from;
        for (double[] point : path) {
            for (int i = 1; i <= each; i++) {
                double share = (double) i / each;
                points.add(new double[] {was[0] + (point[0] - was[0]) * share,
                        was[1] + (point[1] - was[1]) * share});
            }
            was = point;
        }
        return points;
    }
}
