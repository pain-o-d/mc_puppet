package com.curseforge.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonArray;

/** The defects a person finds in a screenshot, found by arithmetic. */
class LayoutTest {

    private static Layout.Box text(String label, double x, double y, double w) {
        return new Layout.Box("text", label, x, y, w, 9);
    }

    private static JsonArray issues(List<Layout.Box> texts) {
        return Layout.issues(texts, List.of(), List.of(), 427, 240);
    }

    @Test
    @DisplayName("a tidy screen has nothing wrong with it")
    void tidy() {
        assertEquals(0, issues(List.of(text("Trades", 10, 6, 30), text("Farmer", 120, 6, 32),
                text("Inventory", 120, 80, 45))).size());
    }

    @Test
    @DisplayName("lines a pixel apart touch without colliding; lines on top of each other collide")
    void overlap() {
        assertEquals(0, issues(List.of(text("one", 10, 10, 40), text("two", 10, 18, 40))).size());
        JsonArray found = issues(List.of(text("Price: 47", 10, 10, 50), text("Stock: 3", 40, 12, 40)));
        assertEquals(1, found.size());
        assertEquals("overlap", found.get(0).getAsJsonObject().get("issue").getAsString());
    }

    @Test
    @DisplayName("the same words in the same place are a shadow, not a collision")
    void shadow() {
        assertEquals(0, issues(List.of(text("Done", 10, 10, 24), text("Done", 10, 10, 24))).size());
    }

    @Test
    @DisplayName("an outline is the same words five times a pixel apart, and is one piece of text")
    void outline() {
        assertEquals(0, issues(List.of(text("4", 211, 205, 6), text("4", 209, 205, 6), text("4", 210, 206, 6),
                text("4", 210, 204, 6), text("4", 210, 205, 6))).size());
        // The same words further apart are two labels, and can collide like any others.
        assertEquals(1, issues(List.of(text("Price", 10, 10, 30), text("Price", 14, 12, 30))).size());
    }

    @Test
    @DisplayName("text past any edge of the screen is reported once")
    void offScreen() {
        JsonArray found = issues(List.of(text("a long label", 400, 10, 60), text("fine", 5, 5, 20),
                text("above", 5, -4, 20)));
        assertEquals(2, found.size());
        assertTrue(found.get(0).getAsJsonObject().get("says").getAsString().contains("a long label"));
    }

    @Test
    @DisplayName("a label wider than its widget is the clipped button of the first real screenshot")
    void labelTooWide() {
        List<Layout.Box> widgets = List.of(new Layout.Box("button", "Pay in: Saro's coins", 300, 4, 76, 16),
                new Layout.Box("button", "Done", 10, 200, 100, 20));
        JsonArray found = Layout.issues(List.of(), widgets, List.of(104.0, 24.0), 427, 240);
        assertEquals(1, found.size());
        assertEquals("label_too_wide", found.get(0).getAsJsonObject().get("issue").getAsString());
    }

    @Test
    @DisplayName("blank strings are never an issue: mods draw them as spacers")
    void blanks() {
        assertEquals(0, issues(List.of(text(" ", 500, 10, 4), text("", 10, 10, 0), text("x", 10, 10, 6))).size());
    }
}
