package com.modrinth.pain_o_d.mc_puppet.core;

import java.util.ArrayDeque;
import java.util.Deque;
import java.util.Locale;

import com.google.gson.JsonArray;
import com.google.gson.JsonElement;
import com.google.gson.JsonObject;

/**
 * What happened to the player that was over before anyone could look.
 *
 * <p>A message on the action bar lasts three seconds. A toast slides away. A
 * sound is never on screen at all, and is often the only thing a mod does to
 * say that something worked. Each is written down with a number as it
 * happens, and a test asks what happened since the number it is up to.
 *
 * <p>Kinds: {@code chat}, {@code system}, {@code actionbar}, {@code title},
 * {@code subtitle}, {@code toast}, {@code sound}. Written from the render
 * thread and the sound engine, read from wherever; hence synchronized.
 */
public final class EventLog {

    /** The client's. The mixins that feed it have nowhere to be handed one. */
    public static final EventLog CLIENT = new EventLog(1000);

    private record Event(long sequence, String kind, String text, JsonObject more) {
    }

    private final int kept;
    private final Deque<Event> events = new ArrayDeque<>();
    private long sequence;

    public EventLog(int kept) {
        this.kept = kept;
    }

    public synchronized void add(String kind, String text, JsonObject more) {
        events.addLast(new Event(++sequence, kind, text == null ? "" : text, more));
        while (events.size() > kept) {
            events.removeFirst();
        }
    }

    public void add(String kind, String text) {
        add(kind, text, null);
    }

    public synchronized long sequence() {
        return sequence;
    }

    /**
     * @param kinds    comma-separated kinds, or {@code null} for all
     * @param contains part of the text, ignoring case, or {@code null}
     */
    public synchronized JsonElement read(long since, String kinds, String contains, int limit) {
        JsonArray found = new JsonArray();
        String wanted = contains == null ? null : contains.toLowerCase(Locale.ROOT);
        String among = kinds == null ? null : "," + kinds.toLowerCase(Locale.ROOT).replace(" ", "") + ",";
        for (Event event : events) {
            if (event.sequence() > since
                    && (among == null || among.contains("," + event.kind() + ","))
                    && (wanted == null || event.text().toLowerCase(Locale.ROOT).contains(wanted))) {
                JsonObject json = new JsonObject();
                json.addProperty("seq", event.sequence());
                json.addProperty("kind", event.kind());
                json.addProperty("text", event.text());
                if (event.more() != null) {
                    event.more().entrySet().forEach(entry -> json.add(entry.getKey(), entry.getValue()));
                }
                found.add(json);
            }
        }
        // The latest, when there are more than asked for: what just happened is what is being asked about.
        while (found.size() > Math.max(1, limit)) {
            found.remove(0);
        }
        JsonObject answer = new JsonObject();
        answer.addProperty("sequence", sequence);
        answer.add("events", found);
        return answer;
    }
}
