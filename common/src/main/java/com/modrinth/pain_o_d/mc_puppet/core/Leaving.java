package com.modrinth.pain_o_d.mc_puppet.core;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Sees a stopped dedicated server's process out, in a development environment.
 *
 * <p>A server that has stopped has saved its worlds and closed its ports, and
 * the process is expected to end with it. In a development environment it may
 * not: the tooling that transforms classes as they load keeps thread pools
 * that are not daemons, and on some versions of the game nothing ends the
 * process after them. What is left holds the remapped mod jars and the log
 * open, so the next launch from that directory fails - on Windows with "Failed
 * to remap mods", which reads like a broken mod. A tool that promises a game
 * can be stopped properly, with nothing left behind, has to mean the process.
 *
 * <p>So the process is given a little while to go by itself, and is then
 * asked to, the ordinary way, with its shutdown hooks; and if a hook hangs,
 * told to. The watcher is a daemon: a process that does end by itself takes
 * the watcher with it, and nothing here is ever seen.
 */
public final class Leaving {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc_puppet");

    /** Long enough for a process that is going to end by itself to have done so. */
    public static final long GRACE_MS = 10_000;

    private Leaving() {
    }

    /** Ends this process if it is still here after the grace. */
    public static void seeTheProcessOut() {
        after(GRACE_MS, () -> {
            LOGGER.warn("The server stopped {} seconds ago and its process has not ended: something in this "
                    + "development environment keeps threads that are not daemons. Ending it, so that the next "
                    + "launch does not find its files held.", GRACE_MS / 1000);
            // Should a shutdown hook hang, the ordinary way out never returns.
            after(GRACE_MS, () -> Runtime.getRuntime().halt(0));
            System.exit(0);
        });
    }

    /** Runs {@code then} after {@code graceMs} on a daemon thread, which dies with the process if that comes first. */
    static Thread after(long graceMs, Runnable then) {
        Thread watcher = new Thread(() -> {
            try {
                Thread.sleep(graceMs);
            } catch (InterruptedException cancelled) {
                return;
            }
            then.run();
        }, "mc_puppet-leaving");
        watcher.setDaemon(true);
        watcher.start();
        return watcher;
    }
}
