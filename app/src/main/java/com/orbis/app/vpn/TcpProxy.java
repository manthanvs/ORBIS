package com.orbis.app.vpn;

import com.orbis.app.throttle.TokenBucket;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Carries the routed app's TCP, which the packet relay can only drop.
 *
 * <p>The relay forwards UDP and drops TCP, so an app that falls back to TCP was
 * being starved rather than slowed - measured on CPH2585, one YouTube client's
 * Shorts froze on the first frame while ORBIS dropped 73 TCP packets against
 * 135 KB of UDP.
 *
 * <p>Rather than write a userspace TCP stack to re-implement what the kernel
 * already does, the tunnel advertises this proxy with {@code setHttpProxy}. The
 * app's own networking then connects here, on loopback, so its TCP never enters
 * the tunnel and never meets the drop. Each connection is terminated locally and
 * re-opened outward through a protected socket - so this is a relay in the same
 * sense the UDP path is, one layer up.
 *
 * <p>The squeeze is applied by <b>pacing</b>, not dropping: the download
 * direction is read only as fast as the ceiling allows, and TCP's own flow
 * control pushes the slowdown back to the sender. No retransmits, no sequence
 * numbers, no loss.
 *
 * <p>Deliberately free of Android types, so the whole thing is testable over
 * loopback in {@code src/test}. The guard is {@code VpnService.protect} in
 * production, and keeps the outward socket out of the tunnel it would otherwise
 * loop back into.
 */
public final class TcpProxy {

    /** Keeps a socket out of the tunnel. {@code VpnService::protect} in production. */
    public interface SocketGuard {
        void protect(Socket socket);
    }

    /** The download ceiling in bytes/second right now; 0 means unlimited. */
    public interface Ceiling {
        long bytesPerSecond();
    }

    /** Where a CONNECT line points. */
    public static final class Target {
        private final String host;
        private final int port;

        public Target(String host, int port) {
            this.host = host;
            this.port = port;
        }

        public String getHost() {
            return host;
        }

        public int getPort() {
            return port;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof Target)) {
                return false;
            }
            Target that = (Target) other;
            return port == that.port && Objects.equals(host, that.host);
        }

        @Override
        public int hashCode() {
            return Objects.hash(host, port);
        }

        @Override
        public String toString() {
            return host + ":" + port;
        }
    }

    private static final String LOOPBACK = "127.0.0.1";
    private static final int BACKLOG = 32;
    private static final int CHUNK_BYTES = 1_400;
    private static final int CONNECT_TIMEOUT_MILLIS = 10_000;
    private static final long JOIN_MILLIS = 1_000L;
    private static final long WAIT_STEP_MILLIS = 20L;

    /** Never wedge a connection for longer than one squeeze. */
    private static final long MAX_WAIT_MILLIS = 6_000L;
    private static final int MAX_REQUEST_LINE = 2_048;
    private static final int CR = 13;
    private static final int LF = 10;

    private static final String ESTABLISHED = "HTTP/1.1 200 Connection established\r\n\r\n";
    private static final String NOT_IMPLEMENTED = "HTTP/1.1 501 Not Implemented\r\n\r\n";

    private final SocketGuard protect;
    private final Ceiling ceiling;
    private final long bulkBytes;

    private final ServerSocket server;

    /** One allowance for the whole app, so its streams share the ceiling. */
    private final TokenBucket bucket = new TokenBucket();
    private final Object bucketLock = new Object();

    private final AtomicLong bytesIn = new AtomicLong(0);
    private final AtomicLong paused = new AtomicLong(0);

    private volatile boolean running = true;

    public TcpProxy(SocketGuard protect, Ceiling ceiling) throws IOException {
        this(protect, ceiling, 16L * 1024L);
    }

    /**
     * @param bulkBytes bytes a connection may carry before the ceiling applies to
     *     it. A new connection runs free until it is plainly a stream: the
     *     handshake and small requests must never be held up.
     */
    public TcpProxy(SocketGuard protect, Ceiling ceiling, long bulkBytes) throws IOException {
        this.protect = protect == null ? socket -> { } : protect;
        this.ceiling = ceiling == null ? () -> 0L : ceiling;
        this.bulkBytes = bulkBytes;
        this.server = new ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK));
    }

    public int getPort() {
        return server.getLocalPort();
    }

    /** Download bytes carried. */
    public long bytesCarried() {
        return bytesIn.get();
    }

    /** How many chunks the squeeze held back. */
    public long chunksPaced() {
        return paused.get();
    }

    public void start() {
        Thread acceptor = new Thread(() -> {
            while (running) {
                Socket client;
                try {
                    client = server.accept();
                } catch (IOException stopped) {
                    break;
                }
                // ponytail: thread per connection. A feed opens tens, not
                // thousands; swap for NIO only if that stops being true.
                Thread worker = new Thread(() -> serve(client), "orbis-proxy-conn");
                worker.setDaemon(true);
                worker.start();
            }
        }, "orbis-proxy");
        acceptor.setDaemon(true);
        acceptor.start();
    }

    public void stop() {
        running = false;
        try {
            server.close();
        } catch (IOException ignored) {
            // Already closed; nothing to undo.
        }
    }

    private void serve(Socket client) {
        Socket upstream = null;
        try {
            client.setTcpNoDelay(true);
            String request = readRequestLine(client);
            if (request == null) {
                return;
            }

            Target target = parseConnect(request);
            if (target == null) {
                // ponytail: CONNECT only. Everything these apps do is HTTPS; a
                // plain-HTTP request would need the whole header rewritten.
                client.getOutputStream().write(NOT_IMPLEMENTED.getBytes("US-ASCII"));
                return;
            }

            upstream = new Socket();
            upstream.setTcpNoDelay(true);
            protect.protect(upstream);
            upstream.connect(
                    new InetSocketAddress(target.getHost(), target.getPort()),
                    CONNECT_TIMEOUT_MILLIS);
            client.getOutputStream().write(ESTABLISHED.getBytes("US-ASCII"));

            final Socket outward = upstream;
            Thread up = new Thread(() -> copy(client, outward, false), "orbis-proxy-up");
            up.setDaemon(true);
            up.start();

            copy(upstream, client, true);
            up.join(JOIN_MILLIS);
        } catch (Throwable dead) {
            // A dead connection is the app's problem to retry, not ours to log.
        } finally {
            closeQuietly(client);
            closeQuietly(upstream);
        }
    }

    /**
     * Pumps one direction. When paced, a chunk waits for its allowance before
     * being handed on, which is what makes the squeeze felt without dropping
     * anything: the reader stops reading, the window closes, the sender slows.
     */
    private void copy(Socket from, Socket to, boolean paced) {
        // Small chunks on purpose: a chunk larger than the bucket's burst could
        // never be afforded at a trickle rate, and would wedge.
        byte[] buffer = new byte[CHUNK_BYTES];
        long carried = 0L;

        try {
            InputStream input = from.getInputStream();
            OutputStream output = to.getOutputStream();

            while (running) {
                int read;
                try {
                    read = input.read(buffer);
                } catch (IOException closed) {
                    break;
                }
                if (read < 0) {
                    break;
                }
                if (read == 0) {
                    continue;
                }

                if (paced) {
                    carried += read;
                    bytesIn.addAndGet(read);
                    // A new connection runs free until it is plainly a stream.
                    if (carried > bulkBytes) {
                        await(read);
                    }
                }

                try {
                    output.write(buffer, 0, read);
                    output.flush();
                } catch (IOException closed) {
                    break;
                }
            }
        } catch (IOException gone) {
            // The socket went away mid-pump; the finally below still tidies up.
        } finally {
            try {
                to.shutdownOutput();
            } catch (IOException ignored) {
                // Already shut, or never opened.
            }
        }
    }

    /** Blocks until this many bytes are affordable at the current ceiling. */
    private void await(int bytes) {
        long waited = 0L;
        while (running) {
            long allowance = ceiling.bytesPerSecond();
            boolean allowed;
            synchronized (bucketLock) {
                allowed = bucket.tryTake(bytes, allowance, System.nanoTime());
            }
            if (allowed) {
                return;
            }

            paused.incrementAndGet();
            if (waited >= MAX_WAIT_MILLIS) {
                return;
            }
            waited += WAIT_STEP_MILLIS;
            try {
                Thread.sleep(WAIT_STEP_MILLIS);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private String readRequestLine(Socket client) throws IOException {
        InputStream input = client.getInputStream();
        String request = readLine(input);
        if (request == null) {
            return null;
        }
        // Drain the remaining CONNECT headers - they say nothing we need - and
        // stop at the blank line that ends them, not at a byte count.
        while (true) {
            String header = readLine(input);
            if (header == null || header.isEmpty()) {
                break;
            }
        }
        return request;
    }

    /** One CRLF-terminated line, or null at end of stream. */
    private String readLine(InputStream input) throws IOException {
        StringBuilder line = new StringBuilder();
        while (line.length() < MAX_REQUEST_LINE) {
            int c = input.read();
            if (c < 0) {
                return line.length() == 0 ? null : line.toString();
            }
            if (c == LF) {
                break;
            }
            if (c != CR) {
                line.append((char) c);
            }
        }
        return line.toString();
    }

    private static void closeQuietly(Socket socket) {
        if (socket == null) {
            return;
        }
        try {
            socket.close();
        } catch (IOException ignored) {
            // Nothing useful to do about a socket that will not close.
        }
    }

    /** {@code CONNECT host:443 HTTP/1.1} -> host and port. Null for anything else. */
    public static Target parseConnect(String requestLine) {
        String[] parts = requestLine.trim().split(" ");
        if (parts.length < 2 || !parts[0].equalsIgnoreCase("CONNECT")) {
            return null;
        }

        String authority = parts[1];
        int colon = authority.lastIndexOf(':');
        if (colon <= 0 || colon == authority.length() - 1) {
            return null;
        }

        int port;
        try {
            port = Integer.parseInt(authority.substring(colon + 1));
        } catch (NumberFormatException notAPort) {
            return null;
        }
        if (port < 1 || port > 65_535) {
            return null;
        }

        String host = authority.substring(0, colon);
        // Strip the brackets an IPv6 authority carries.
        host = host.replace("[", "").replace("]", "");
        return new Target(host, port);
    }
}
