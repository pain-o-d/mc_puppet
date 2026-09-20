package com.curseforge.pain_o_d.mc_puppet.core;

import java.io.BufferedReader;
import java.io.BufferedWriter;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.SecureRandom;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.google.gson.JsonObject;

/**
 * The socket one side of the game listens on.
 *
 * <p><strong>This is remote control of a game, and is built to be refused.</strong>
 *
 * <ul>
 * <li>It binds the loopback address and nothing else. There is no setting
 *     that makes it listen to a network.</li>
 * <li>It is off unless switched on, by the config file or a system
 *     property.</li>
 * <li>Every request carries a token that is made afresh at each start and
 *     written to a file in the game directory. Reaching the socket is not
 *     enough; a caller has to be able to read the game's own files, which is
 *     somebody who could already do anything the game can.</li>
 * <li>A line that fails the token ends the connection.</li>
 * </ul>
 *
 * <p>Threads: one to accept, one for each connection, to read. Work is handed
 * to the game thread by {@link Ops}; answers are written from wherever they
 * complete, under the connection's lock.
 */
public final class Bridge implements AutoCloseable {

    private static final Logger LOGGER = LoggerFactory.getLogger("mc_puppet");

    /** Connections at once. A test harness needs one; a few is slack, not a service. */
    private static final int MAX_CONNECTIONS = 8;

    private final String side;
    private final Ops ops;
    private final String token;
    private final ServerSocket socket;
    private final Path endpointFile;
    private final Audit audit;
    private final Set<Socket> connections = ConcurrentHashMap.newKeySet();
    private final AtomicInteger threadNumber = new AtomicInteger();
    private volatile boolean closed;

    private Bridge(String side, Ops ops, String token, ServerSocket socket, Path endpointFile) {
        this.side = side;
        this.ops = ops;
        this.token = token;
        this.socket = socket;
        this.endpointFile = endpointFile;
        this.audit = new Audit(endpointFile.getParent(), side);
    }

    /**
     * Opens a bridge and says where it is.
     *
     * @param port    the port asked for; if it is taken the next free one is
     *                used, since two game instances on one machine is normal
     *                and the endpoint file says which port was had
     * @param gameDir where the endpoint file goes: {@code mc_puppet/endpoint-<side>.json}
     */
    public static Bridge open(String side, Ops ops, int port, Path gameDir) throws IOException {
        ServerSocket socket = bind(port);
        String token = newToken();
        Path directory = gameDir.resolve("mc_puppet");
        Files.createDirectories(directory);
        Path endpointFile = directory.resolve("endpoint-" + side + ".json");

        JsonObject endpoint = new JsonObject();
        endpoint.addProperty("side", side);
        endpoint.addProperty("host", "127.0.0.1");
        endpoint.addProperty("port", socket.getLocalPort());
        endpoint.addProperty("token", token);
        endpoint.addProperty("pid", ProcessHandle.current().pid());
        endpoint.addProperty("started", System.currentTimeMillis());
        endpoint.addProperty("protocol", Protocol.VERSION);
        Files.writeString(endpointFile, Protocol.GSON.toJson(endpoint), StandardCharsets.UTF_8);
        keepToTheOwner(endpointFile);

        Bridge bridge = new Bridge(side, ops, token, socket, endpointFile);
        Thread acceptor = new Thread(bridge::accept, "mc_puppet-" + side + "-accept");
        acceptor.setDaemon(true);
        acceptor.start();
        LOGGER.warn("MC Puppet is ON for the {}: listening on 127.0.0.1:{}. Anything that can read {} "
                + "can drive this game. Switch it off when not testing.", side, socket.getLocalPort(), endpointFile);
        return bridge;
    }

    /**
     * The token is what stands between this game and any program on the
     * machine, and it is in this file. Where the file system can say so, only
     * its owner may read it. Windows cannot be told this way; a profile
     * directory there is the owner's already.
     */
    private static void keepToTheOwner(Path file) {
        try {
            if (file.getFileSystem().supportedFileAttributeViews().contains("posix")) {
                Files.setPosixFilePermissions(file, java.nio.file.attribute.PosixFilePermissions.fromString("rw-------"));
            }
        } catch (IOException | UnsupportedOperationException | SecurityException unimportant) {
            // As readable as the directory it is in, which is what it was before this was tried.
        }
    }

    private static ServerSocket bind(int port) throws IOException {
        IOException last = null;
        for (int attempt = 0; attempt < 20; attempt++) {
            ServerSocket candidate = new ServerSocket();
            try {
                candidate.setReuseAddress(false);
                candidate.bind(new InetSocketAddress(InetAddress.getLoopbackAddress(), port + attempt), 16);
                return candidate;
            } catch (IOException taken) {
                candidate.close();
                last = taken;
            }
        }
        throw last;
    }

    private static String newToken() {
        byte[] bytes = new byte[32];
        new SecureRandom().nextBytes(bytes);
        StringBuilder hex = new StringBuilder(64);
        for (byte each : bytes) {
            hex.append(String.format("%02x", each));
        }
        return hex.toString();
    }

    public int port() {
        return socket.getLocalPort();
    }

    private void accept() {
        while (!closed) {
            Socket connection;
            try {
                connection = socket.accept();
            } catch (IOException ended) {
                if (!closed) {
                    LOGGER.warn("MC Puppet stopped accepting on the {}", side, ended);
                }
                return;
            }
            if (!connection.getInetAddress().isLoopbackAddress() || connections.size() >= MAX_CONNECTIONS) {
                // The first cannot happen on a loopback bind; checked anyway,
                // because this is the line that matters.
                closeQuietly(connection);
                continue;
            }
            connections.add(connection);
            Thread reader = new Thread(() -> serve(connection),
                    "mc_puppet-" + side + "-" + threadNumber.incrementAndGet());
            reader.setDaemon(true);
            reader.start();
        }
    }

    private void serve(Socket connection) {
        try (connection;
                BufferedReader in = new BufferedReader(
                        new InputStreamReader(connection.getInputStream(), StandardCharsets.UTF_8));
                BufferedWriter out = new BufferedWriter(
                        new OutputStreamWriter(connection.getOutputStream(), StandardCharsets.UTF_8))) {
            connection.setTcpNoDelay(true);
            String line;
            while (!closed && (line = readLine(in)) != null) {
                if (line.isBlank()) {
                    continue;
                }
                Protocol.Request request;
                try {
                    request = Protocol.parse(line);
                } catch (Protocol.Malformed malformed) {
                    write(out, Protocol.error(null, malformed.getMessage()));
                    continue;
                }
                if (!Protocol.tokenMatches(token, request.token())) {
                    write(out, Protocol.error(request.id(), "wrong or missing token; read it from "
                            + endpointFile.getFileName()));
                    return;
                }
                long asked = System.currentTimeMillis();
                ops.run(request.op(), request.args())
                        .orTimeout(Waiter.MAX_TIMEOUT_MS + 5_000, TimeUnit.MILLISECONDS)
                        .whenComplete((result, failure) -> {
                            audit.wrote(request.op(), request.args(), failure, System.currentTimeMillis() - asked);
                            try {
                                write(out, failure == null
                                        ? Protocol.ok(request.id(), result)
                                        : Protocol.error(request.id(), Ops.messageOf(failure)));
                            } catch (IOException gone) {
                                closeQuietly(connection);
                            }
                        });
            }
        } catch (IOException gone) {
            // A test harness that went away. Nothing to do and nothing to say.
        } finally {
            connections.remove(connection);
        }
    }

    /** A line, or {@code null} at the end; a line past the limit ends the connection. */
    private static String readLine(BufferedReader in) throws IOException {
        StringBuilder line = new StringBuilder();
        int read;
        while ((read = in.read()) >= 0) {
            if (read == '\n') {
                return line.toString();
            }
            if (read != '\r') {
                line.append((char) read);
            }
            if (line.length() > Protocol.MAX_LINE_BYTES) {
                throw new IOException("request line too long");
            }
        }
        return line.length() == 0 ? null : line.toString();
    }

    private static void write(BufferedWriter out, String line) throws IOException {
        synchronized (out) {
            out.write(line);
            out.write('\n');
            out.flush();
        }
    }

    @Override
    public void close() {
        closed = true;
        closeQuietly(socket);
        for (Socket connection : connections) {
            closeQuietly(connection);
        }
        try {
            Files.deleteIfExists(endpointFile);
        } catch (IOException leftBehind) {
            // A stale endpoint file names a dead pid and a token nothing
            // answers to. Harmless; the tooling checks the pid.
        }
        LOGGER.info("MC Puppet is off for the {}", side);
    }

    private static void closeQuietly(AutoCloseable closeable) {
        try {
            closeable.close();
        } catch (Exception ignored) {
            // Closing is best effort.
        }
    }
}
