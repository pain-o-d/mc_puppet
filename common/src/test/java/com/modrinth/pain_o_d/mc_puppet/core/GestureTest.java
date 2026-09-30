package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

/** Where a dragged cursor goes, and how far a pixel turns the head. */
class GestureTest {

    @Test
    @DisplayName("at the default sensitivity a pixel turns the head 0.15 degrees")
    void defaultSensitivity() {
        // 0.5 * 0.6 + 0.2 = 0.5; 0.5³ × 8 = 1; × 0.15.
        assertEquals(0.15, Gesture.degreesPerPixel(0.5), 1e-9);
        assertEquals(0.6144, Gesture.degreesPerPixel(1), 1e-9);
        assertEquals(0.0096, Gesture.degreesPerPixel(0), 1e-9);
    }

    @Test
    @DisplayName("one step a leg is the old drag: a point a tick, ending on the last")
    void oneStep() {
        List<double[]> points = Gesture.legs(new double[] {0, 0},
                List.of(new double[] {10, 0}, new double[] {10, 20}), 1);
        assertEquals(2, points.size());
        assertArrayEquals(new double[] {10, 0}, points.get(0), 1e-9);
        assertArrayEquals(new double[] {10, 20}, points.get(1), 1e-9);
    }

    @Test
    @DisplayName("steps cut every leg evenly, and none is empty")
    void manySteps() {
        List<double[]> points = Gesture.legs(new double[] {0, 0},
                List.of(new double[] {8, 0}, new double[] {8, -4}), 4);
        assertEquals(8, points.size());
        assertArrayEquals(new double[] {2, 0}, points.get(0), 1e-9);
        assertArrayEquals(new double[] {8, 0}, points.get(3), 1e-9);
        assertArrayEquals(new double[] {8, -1}, points.get(4), 1e-9);
        assertArrayEquals(new double[] {8, -4}, points.get(7), 1e-9);
        assertEquals(1, Gesture.legs(new double[] {0, 0}, List.of(new double[] {1, 1}), 0).size());
    }
}
