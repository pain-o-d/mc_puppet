package com.modrinth.pain_o_d.mc_puppet.core;

import com.google.gson.JsonObject;

/**
 * Turns "the game wrote a crash report" into a {@code server.crash} or {@code client.crash} event.
 * Plain Java, no game: the mixin on {@code CrashReport} only hands over a file name and a cause, so
 * the report is announced whether or not the server's own shutdown went on to succeed.
 */
public final class CrashEvents {

    /** Told once per file: a game that writes the report through two overloads must not say it twice. */
    private static String last;

    private CrashEvents() {
    }

    /**
     * @param file  where the report was written (any separator), or {@code null} if it was not
     * @param cause what crashed, or {@code null}
     * @return the event recorded, or {@code null} if this was not a client or server report, was
     *         told already, or the bus is off
     */
    public static synchronized JsonObject saved(String file, Throwable cause) {
        String name = file == null ? "" : file.substring(Math.max(file.lastIndexOf('/'), file.lastIndexOf((char) 92)) + 1);
        String side;
        if (name.endsWith("-server.txt")) {
            side = "server";
        } else if (name.endsWith("-client.txt")) {
            side = "client";
        } else {
            return null;
        }
        if (name.equals(last)) {
            return null;
        }
        last = name;
        JsonObject data = new JsonObject();
        data.addProperty("report", "crash-reports/" + name);
        if (cause != null) {
            String text = String.valueOf(cause.getMessage());
            int line = text.indexOf('\n');
            data.addProperty("cause", cause.getClass().getSimpleName() + ": " + (line < 0 ? text : text.substring(0, line)));
        }
        return Events.record(side, side + ".crash", Events.Level.ERROR, data);
    }
}
