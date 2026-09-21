package com.modrinth.pain_o_d.mc_puppet.core;

import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.SocketAddress;
import java.net.UnknownHostException;
import java.util.Locale;
import java.util.Set;

/**
 * How far a client's bridge reaches: this machine, and no further.
 *
 * <p>The bridge lets a program press a player's keys. In a world of one's own,
 * or on a server started on the same machine to test against, that is a test.
 * On somebody else's server it is a bot: it walks, clicks and fights for a
 * player who is not at the keyboard, and reads out what is around them, on a
 * server whose owner never agreed to it. Nothing about testing a mod needs
 * that, so there the bridge does nothing of the kind, whoever asks.
 *
 * <p>"This machine" is a connection that never left it: the game's own
 * in-memory one to the server inside a single-player client, or a socket to a
 * loopback address. A server on the same computer reached by its network
 * address, {@code 192.168.1.5} and not {@code localhost}, counts as elsewhere:
 * there is no telling it from the one next door, and {@code localhost} is one
 * word to type.
 *
 * <p>What is left is what ends a session and what lets go: nothing that acts
 * in the world or reports on it.
 */
public final class Reach {

    private Reach() {
    }

    /** What still answers on somebody else's server. */
    public static final Set<String> ALLOWED_ELSEWHERE = Set.of(
            // What is this, and where
            "info", "help",
            // Letting go of whatever a test was holding when the player went there
            "release_keys", "stop",
            // Leaving
            "leave_world", "quit", "wait",
            // These only run other operations, each of which is asked about in its turn
            "batch", "wait_until");

    /**
     * Whether a connection stayed on this machine.
     *
     * @param inMemory the game's own connection to a server inside the same process
     * @param address  where the socket goes, or {@code null} if that cannot be told
     */
    public static boolean isThisMachine(boolean inMemory, SocketAddress address) {
        if (inMemory) {
            return true;
        }
        // Unresolved has no address to ask, and is not taken on trust.
        return address instanceof InetSocketAddress socket && socket.getAddress() != null
                && socket.getAddress().isLoopbackAddress();
    }

    /**
     * Whether a host, as somebody typed it, names this machine: {@code localhost}, or a loopback
     * address written out. No other name is looked up. That would be a question to DNS from the
     * game's thread, and what a name answers today it need not answer tomorrow; the connection
     * that results is judged by {@link #isThisMachine} whatever was typed.
     */
    public static boolean namesThisMachine(String host) {
        if (host == null) {
            return false;
        }
        String name = host.trim().toLowerCase(Locale.ROOT);
        if (name.startsWith("[") && name.endsWith("]")) {
            name = name.substring(1, name.length() - 1);
        }
        if (name.equals("localhost")) {
            return true;
        }
        // Only what is already an address: digits and dots, or anything with a colon, which
        // Java reads as an IPv6 literal or refuses, and never looks up.
        if (!name.matches("[0-9.]+") && name.indexOf(':') < 0) {
            return false;
        }
        try {
            return InetAddress.getByName(name).isLoopbackAddress();
        } catch (UnknownHostException | SecurityException notAnAddress) {
            return false;
        }
    }

    /** Why a client is not sent to {@code host}, or {@code null} if it is on this machine. */
    public static String refusalToJoin(String host) {
        if (namesThisMachine(host)) {
            return null;
        }
        return "join_server goes to a server on this machine: localhost or 127.0.0.1, with its port. \"" + host
                + "\" is somewhere else, where MC Puppet would neither drive nor read the game. For a test server "
                + "on another machine of yours, bring its port here first (ssh -L 25565:localhost:25565 that-machine) "
                + "and join localhost:25565.";
    }

    /** Why {@code op} is refused on a server that is elsewhere, or {@code null} if it is not. */
    public static String refusalElsewhere(String op) {
        if (ALLOWED_ELSEWHERE.contains(op)) {
            return null;
        }
        return "this client is on a server that is not on this machine, and MC Puppet does not drive or read a game "
                + "there: that would be a bot on somebody else's server. Test in a single-player world or against a "
                + "server on localhost. (\"" + op + "\" refused; info, help, stop, release_keys, leave_world and quit "
                + "still answer.)";
    }
}
