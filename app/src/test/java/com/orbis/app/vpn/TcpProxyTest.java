package com.orbis.app.vpn;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.After;
import org.junit.Test;

import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Runs the proxy for real over loopback, because the thing worth proving is that
 * a tunnelled connection carries bytes and that the squeeze actually slows it.
 */
public class TcpProxyTest {

    private final List<TcpProxy> proxies = new ArrayList<>();
    private final List<ServerSocket> servers = new ArrayList<>();

    @After
    public void tearDown() {
        for (TcpProxy proxy : proxies) {
            proxy.stop();
        }
        for (ServerSocket server : servers) {
            try {
                server.close();
            } catch (IOException ignored) {
                // Already closed, which is the state we wanted.
            }
        }
    }

    private static Thread daemon(Runnable body) {
        Thread thread = new Thread(body);
        thread.setDaemon(true);
        thread.start();
        return thread;
    }

    /** An origin that sends {@code bytes} of payload to whoever connects. */
    private ServerSocket origin(int bytes) throws IOException {
        ServerSocket server = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
        servers.add(server);
        daemon(
                () -> {
                    while (true) {
                        Socket socket;
                        try {
                            socket = server.accept();
                        } catch (Throwable stopped) {
                            return;
                        }
                        daemon(
                                () -> {
                                    try {
                                        byte[] payload = new byte[bytes];
                                        Arrays.fill(payload, (byte) 7);
                                        OutputStream out = socket.getOutputStream();
                                        out.write(payload);
                                        out.flush();
                                        socket.shutdownOutput();
                                    } catch (Throwable ignored) {
                                        // The reader hung up; nothing to do.
                                    }
                                });
                    }
                });
        return server;
    }

    private TcpProxy proxy(long ceiling) throws IOException {
        return proxy(ceiling, 64L * 1024L);
    }

    private TcpProxy proxy(long ceiling, long bulkBytes) throws IOException {
        TcpProxy proxy = new TcpProxy(socket -> {}, () -> ceiling, bulkBytes);
        proxies.add(proxy);
        proxy.start();
        return proxy;
    }

    /** Connects through the proxy to the origin and reads until close. */
    private int download(TcpProxy proxy, ServerSocket origin, long limitMillis)
            throws IOException {
        try (Socket client = new Socket("127.0.0.1", proxy.getPort())) {
            client.setSoTimeout((int) limitMillis + 2_000);
            client.getOutputStream()
                    .write(
                            ("CONNECT 127.0.0.1:"
                                            + origin.getLocalPort()
                                            + " HTTP/1.1\r\n\r\n")
                                    .getBytes(StandardCharsets.US_ASCII));
            InputStream input = client.getInputStream();

            StringBuilder header = new StringBuilder();
            while (header.length() < 4
                    || !header.substring(header.length() - 4).equals("\r\n\r\n")) {
                int c = input.read();
                if (c < 0) {
                    break;
                }
                header.append((char) c);
            }
            assertTrue(header.toString(), header.toString().startsWith("HTTP/1.1 200"));

            long deadline = System.currentTimeMillis() + limitMillis;
            byte[] buffer = new byte[8 * 1024];
            int total = 0;
            while (System.currentTimeMillis() < deadline) {
                int read;
                try {
                    read = input.read(buffer);
                } catch (Throwable closed) {
                    break;
                }
                if (read < 0) {
                    break;
                }
                total += read;
            }
            return total;
        }
    }

    @Test
    public void anUnlimitedTunnelCarriesTheWholePayload() throws IOException {
        int payload = 200_000;
        int bytes = download(proxy(0L), origin(payload), 4_000);

        assertEquals(payload, bytes);
    }

    @Test
    public void aSqueezedTunnelIsHeldNearItsCeiling() throws IOException {
        // 16 KB/s is the squeeze rate. Over a second, a 1 MB payload must not
        // arrive - this is the whole point of the proxy existing.
        int bytes = download(proxy(16_000L, 0L), origin(1_000_000), 1_000);

        assertTrue("carried " + bytes + " in 1s at a 16 KB/s ceiling", bytes < 80_000);
    }

    @Test
    public void aSmallRequestIsNeverHeldUp() throws IOException {
        // Under the bulk threshold: handshakes and API calls must not be paced,
        // or an app decides the network is broken and stops using it.
        int payload = 20_000;
        long started = System.currentTimeMillis();
        int bytes = download(proxy(16_000L), origin(payload), 3_000);

        assertEquals(payload, bytes);
        assertTrue(System.currentTimeMillis() - started < 2_000);
    }

    @Test
    public void theSqueezeLiftsAndTheStreamRecovers() throws IOException {
        AtomicLong ceiling = new AtomicLong(16_000L);
        TcpProxy proxy = new TcpProxy(socket -> {}, ceiling::get, 0L);
        proxies.add(proxy);
        proxy.start();
        ServerSocket server = origin(400_000);

        daemon(
                () -> {
                    try {
                        Thread.sleep(600L);
                    } catch (InterruptedException ignored) {
                        return;
                    }
                    ceiling.set(0L);
                });
        int bytes = download(proxy, server, 3_000);

        assertEquals(400_000, bytes);
    }

    @Test
    public void connectLinesParseAndAnythingElseIsRefused() {
        assertEquals(
                new TcpProxy.Target("i.instagram.com", 443),
                TcpProxy.parseConnect("CONNECT i.instagram.com:443 HTTP/1.1"));
        assertEquals(
                new TcpProxy.Target("::1", 443),
                TcpProxy.parseConnect("CONNECT [::1]:443 HTTP/1.1"));
        assertNull(TcpProxy.parseConnect("GET http://example.com/ HTTP/1.1"));
        assertNull(TcpProxy.parseConnect("CONNECT example.com HTTP/1.1"));
        assertNull(TcpProxy.parseConnect("CONNECT example.com:0 HTTP/1.1"));
        assertNull(TcpProxy.parseConnect(""));
    }
}
