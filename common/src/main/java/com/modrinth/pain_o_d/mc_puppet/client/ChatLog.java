package com.modrinth.pain_o_d.mc_puppet.client;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * The last messages the client was sent, numbered.
 *
 * <p>A command run as the player answers in chat, and chat scrolls away. A
 * test needs to ask what was said since it last looked, so every line gets a
 * sequence number and the asker names the one it is up to.
 */
public final class ChatLog {

    private static final int KEPT = 300;

    private record Line(long sequence, boolean system, String text) {
    }

    private final Deque<Line> lines = new ArrayDeque<>();
    private long sequence;

    public synchronized void add(boolean system, String text) {
        lines.addLast(new Line(++sequence, system, text));
        while (lines.size() > KEPT) {
            lines.removeFirst();
        }
    }

    /** The number of the last line received; "since" this means "from now on". */
    public synchronized long sequence() {
        return sequence;
    }

    public synchronized JsonElement read(long since, String contains, int limit) {
        JsonArray found = new JsonArray();
        String wanted = contains == null ? null : contains.toLowerCase(Locale.ROOT);
        for (Line line : lines) {
            if (line.sequence() > since
                    && (wanted == null || line.text().toLowerCase(Locale.ROOT).contains(wanted))) {
                found.add(json(line));
            }
        }
        while (found.size() > Math.max(1, limit)) {
            found.remove(0);
        }
        JsonObject answer = new JsonObject();
        answer.addProperty("sequence", sequence);
        answer.add("lines", found);
        return answer;
    }

    /** The first line after {@code since} containing the text, or {@code null}. */
    public synchronized JsonElement firstContaining(long since, String text) {
        String wanted = text.toLowerCase(Locale.ROOT);
        for (Line line : lines) {
            if (line.sequence() > since && line.text().toLowerCase(Locale.ROOT).contains(wanted)) {
                return json(line);
            }
        }
        return null;
    }

    private static JsonObject json(Line line) {
        JsonObject json = new JsonObject();
        json.addProperty("seq", line.sequence());
        json.addProperty("text", line.text());
        if (!line.system()) {
            json.addProperty("chat", true);
        }
        return json;
    }
}
