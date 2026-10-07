package com.modrinth.pain_o_d.mc_puppet.core;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.google.gson.JsonObject;
import com.google.gson.JsonParser;

@DisplayName("an operation's arguments are read, or refused in words")
class ArgsTest {

    private interface Call {
        void run() throws Ops.Refused;
    }

    private static JsonObject json(String text) {
        return JsonParser.parseString(text).getAsJsonObject();
    }

    private static String refusal(Call call) {
        return assertThrows(Ops.Refused.class, call::run).getMessage();
    }

    @Test
    void aStringIsReadOrItsAbsenceIsNamed() throws Exception {
        assertEquals("hi", Args.string(json("{\"a\":\"hi\"}"), "a"));
        assertEquals("5", Args.string(json("{\"a\":5}"), "a"), "a number is read as its text");
        assertEquals("\"a\" is required", refusal(() -> Args.string(json("{}"), "a")));
        assertEquals("\"a\" is required", refusal(() -> Args.string(json("{\"a\":{}}"), "a")), "an object is not a string");
        assertEquals("\"a\" is required", refusal(() -> Args.string(json("{\"a\":null}"), "a")));
    }

    @Test
    void aStringFallsBackWhenItIsMissingOrNotPrimitive() {
        assertEquals("x", Args.string(json("{}"), "a", "x"));
        assertEquals("x", Args.string(json("{\"a\":[1]}"), "a", "x"));
        assertEquals("x", Args.string(json("{\"a\":null}"), "a", "x"));
        assertEquals("y", Args.string(json("{\"a\":\"y\"}"), "a", "x"));
        assertNull(Args.string(json("{}"), "a", null));
    }

    @Test
    void aWholeNumberIsParsedOrRefused() throws Exception {
        assertEquals(7, Args.integer(json("{\"n\":7}"), "n"));
        assertEquals(-3, Args.integer(json("{\"n\":\"-3\"}"), "n"), "a numeral in a string is fine");
        assertEquals("\"n\" is a whole number", refusal(() -> Args.integer(json("{\"n\":2.5}"), "n")));
        assertEquals("\"n\" is a whole number", refusal(() -> Args.integer(json("{\"n\":\"abc\"}"), "n")));
        assertEquals("\"n\" is a whole number", refusal(() -> Args.integer(json("{\"n\":99999999999}"), "n")), "too big for an int");
        assertEquals("\"n\" is required", refusal(() -> Args.integer(json("{}"), "n")));
    }

    @Test
    void aWholeNumberWithAFallbackOnlyFallsBackWhenAbsent() throws Exception {
        assertEquals(9, Args.integer(json("{}"), "n", 9));
        assertEquals(4, Args.integer(json("{\"n\":4}"), "n", 9));
        assertEquals("\"n\" is a whole number", refusal(() -> Args.integer(json("{\"n\":\"x\"}"), "n", 9)), "a wrong value is not hidden");
    }

    @Test
    void aNumberTruncatesAndRefusesText() throws Exception {
        assertEquals(12L, Args.number(json("{\"n\":12.9}"), "n", 1L));
        assertEquals(-12L, Args.number(json("{\"n\":-12.9}"), "n", 1L), "toward zero");
        assertEquals(10_000_000_000L, Args.number(json("{\"n\":10000000000}"), "n", 1L), "past an int");
        assertEquals(1L, Args.number(json("{}"), "n", 1L));
        assertEquals("\"n\" is a number", refusal(() -> Args.number(json("{\"n\":\"soon\"}"), "n", 1L)));
    }

    @Test
    void aDecimalIsParsedOrRefused() throws Exception {
        assertEquals(2.5, Args.decimal(json("{\"d\":2.5}"), "d"));
        assertEquals(3.0, Args.decimal(json("{\"d\":\"3\"}"), "d"));
        assertEquals("\"d\" is a number", refusal(() -> Args.decimal(json("{\"d\":\"x\"}"), "d")));
        assertEquals("\"d\" is required", refusal(() -> Args.decimal(json("{}"), "d")));
        assertEquals(0.5, Args.decimal(json("{}"), "d", 0.5));
        assertEquals(0.5, Args.decimal(json("{\"d\":null}"), "d", 0.5), "null is as good as absent");
        assertEquals(1.5, Args.decimal(json("{\"d\":1.5}"), "d", 0.5));
        assertEquals("\"d\" is a number", refusal(() -> Args.decimal(json("{\"d\":\"x\"}"), "d", 0.5)));
    }

    @Test
    void aFlagIsReadOrFallsBack() {
        assertTrue(Args.flag(json("{\"f\":true}"), "f", false));
        assertFalse(Args.flag(json("{\"f\":false}"), "f", true));
        assertTrue(Args.flag(json("{}"), "f", true));
        assertFalse(Args.flag(json("{}"), "f", false));
        assertTrue(Args.flag(json("{\"f\":null}"), "f", true), "null falls back");
        assertTrue(Args.flag(json("{\"f\":\"true\"}"), "f", false), "the string \"true\" is read as true");
        assertFalse(Args.flag(json("{\"f\":\"yes\"}"), "f", true), "any other text is false, as Gson reads it");
    }

    @Test
    void theTimeoutIsTheCallersOrTheDefault() throws Exception {
        assertEquals(Waiter.DEFAULT_TIMEOUT_MS, Args.timeout(json("{}")));
        assertEquals(500L, Args.timeout(json("{\"timeout_ms\":500}")));
        assertEquals(77L, Args.timeout(json("{}"), 77L), "an operation's own idea");
        assertEquals(500L, Args.timeout(json("{\"timeout_ms\":500}"), 77L), "the caller wins");
        assertEquals("\"timeout_ms\" is a number", refusal(() -> Args.timeout(json("{\"timeout_ms\":\"soon\"}"))));
    }
}
