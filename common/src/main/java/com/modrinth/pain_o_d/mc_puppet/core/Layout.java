package com.modrinth.pain_o_d.mc_puppet.core;

import java.util.ArrayList;
import java.util.List;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;

/**
 * What is wrong with where things were drawn, as arithmetic.
 *
 * <p>The defects a person finds by looking at a screenshot are mostly three:
 * text that runs off the screen, text drawn over other text, and a label too
 * wide for the button it is on. Given what was drawn and where, none of them
 * needs eyes. Plain Java, tested without a game.
 */
public final class Layout {

    private Layout() {
    }

    /** Something drawn: what it says, and the box it fills, in scaled GUI pixels. */
    public record Box(String kind, String label, double x, double y, double w, double h) {

        double right() {
            return x + w;
        }

        double bottom() {
            return y + h;
        }

        /**
         * The same words within two pixels are one piece of text drawn more
         * than once: a shadow, or an outline, which the experience level gets
         * by being drawn four times around itself and once on top.
         */
        boolean sameAs(Box other) {
            return label.equals(other.label) && Math.abs(x - other.x) <= 2 && Math.abs(y - other.y) <= 2;
        }
    }

    /**
     * A glyph's box is taller than its ink: nine pixels for seven of letter.
     * Lines set a pixel apart touch without being on top of each other.
     */
    private static final double OVERLAP_MIN = 2.5;

    /**
     * @param texts   every string drawn in the frame
     * @param widgets every visible widget, its label as the text
     * @param labelWidths how wide each widget's label is drawn, by the widget's index in {@code widgets}
     */
    public static JsonArray issues(List<Box> texts, List<Box> widgets, List<Double> labelWidths,
                                   double screenWidth, double screenHeight) {
        JsonArray found = new JsonArray();
        for (Box text : texts) {
            if (text.label().isBlank()) {
                continue;
            }
            if (text.x() < -0.5 || text.y() < -0.5 || text.right() > screenWidth + 0.5
                    || text.bottom() > screenHeight + 0.5) {
                found.add(issue("off_screen", "\"" + text.label() + "\" runs off the screen", text, null));
            }
        }
        List<Box> distinct = new ArrayList<>();
        for (Box text : texts) {
            // Drawn twice in one place is a shadow, an outline or a redraw, not a collision.
            if (!text.label().isBlank() && distinct.stream().noneMatch(text::sameAs)) {
                distinct.add(text);
            }
        }
        for (int first = 0; first < distinct.size(); first++) {
            for (int second = first + 1; second < distinct.size(); second++) {
                Box a = distinct.get(first);
                Box b = distinct.get(second);
                double across = Math.min(a.right(), b.right()) - Math.max(a.x(), b.x());
                double down = Math.min(a.bottom(), b.bottom()) - Math.max(a.y(), b.y());
                if (across >= OVERLAP_MIN && down >= OVERLAP_MIN) {
                    found.add(issue("overlap", "\"" + a.label() + "\" and \"" + b.label() + "\" are drawn over "
                            + "each other", a, b));
                }
            }
        }
        for (int index = 0; index < widgets.size() && index < labelWidths.size(); index++) {
            Box widget = widgets.get(index);
            if (!widget.label().isBlank() && labelWidths.get(index) > widget.w()) {
                found.add(issue("label_too_wide", "\"" + widget.label() + "\" is " + Math.round(labelWidths.get(index))
                        + " wide on a widget of " + Math.round(widget.w()), widget, null));
            }
        }
        return found;
    }

    private static JsonObject issue(String kind, String says, Box first, Box second) {
        JsonObject json = new JsonObject();
        json.addProperty("issue", kind);
        json.addProperty("says", says);
        json.add("at", box(first));
        if (second != null) {
            json.add("and", box(second));
        }
        return json;
    }

    private static JsonObject box(Box box) {
        JsonObject json = new JsonObject();
        json.addProperty("x", round(box.x()));
        json.addProperty("y", round(box.y()));
        json.addProperty("w", round(box.w()));
        json.addProperty("h", round(box.h()));
        return json;
    }

    /** One decimal: positions are whole unless something scaled them. */
    public static double round(double value) {
        return Math.round(value * 10) / 10.0;
    }
}
