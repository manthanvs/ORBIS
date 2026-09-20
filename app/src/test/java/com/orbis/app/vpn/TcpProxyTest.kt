package com.orbis.app.vpn

import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import kotlin.concurrent.thread

/**
 * Runs the proxy for real over loopback, because the thing worth proving is that
 * a tunnelled connection carries bytes and that the squeeze actually slows it.
 */
class TcpProxyTest {

    private val proxies = mutableListOf<TcpProxy>()
    private val servers = mutableListOf<ServerSocket>()

    @After
    fun tearDown() {
        proxies.forEach { it.stop() }
        servers.forEach { runCatching { it.close() } }
    }

    /** An origin that sends [bytes] of payload to whoever connects. */
    private fun origin(bytes: Int): ServerSocket {
        val server = ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"))
        servers += server
        thread(isDaemon = true) {
            while (true) {
                val socket = try { server.accept() } catch (_: Throwable) { return@thread }
                thread(isDaemon = true) {
                    runCatching {
                        socket.getOutputStream().write(ByteArray(bytes) { 7 })
                        socket.getOutputStream().flush()
                        socket.shutdownOutput()
                    }
                }
            }
        }
        return server
    }

    private fun proxy(ceiling: Long, bulkBytes: Long = 64L * 1024L): TcpProxy =
        TcpProxy(ceilingBytesPerSecond = { ceiling }, bulkBytes = bulkBytes)
            .also { proxies += it; it.start() }

    /** Connects through [proxy] to [origin] and reads until close; returns bytes read. */
    private fun download(proxy: TcpProxy, origin: ServerSocket, limitMillis: Long): Int {
        Socket("127.0.0.1", proxy.port).use { client ->
            client.soTimeout = limitMillis.toInt() + 2_000
            client.getOutputStream().write(
                "CONNECT 127.0.0.1:${origin.localPort} HTTP/1.1\r\n\r\n".toByteArray()
            )
            val input = client.getInputStream()

            val header = StringBuilder()
            while (!header.endsWith("\r\n\r\n")) {
                val c = input.read()
                if (c < 0) break
                header.append(c.toChar())
            }
            assertTrue(header.toString(), header.startsWith("HTTP/1.1 200"))

            val deadline = System.currentTimeMillis() + limitMillis
            val buffer = ByteArray(8 * 1024)
            var total = 0
            while (System.currentTimeMillis() < deadline) {
                val read = try { input.read(buffer) } catch (_: Throwable) { break }
                if (read < 0) break
                total += read
            }
            return total
        }
    }

    @Test
    fun `an unlimited tunnel carries the whole payload`() {
        val payload = 200_000
        val bytes = download(proxy(ceiling = 0L), origin(payload), limitMillis = 4_000)

        assertEquals(payload, bytes)
    }

    @Test
    fun `a squeezed tunnel is held near its ceiling`() {
        // 16 KB/s is the squeeze rate. Over a second, a 1 MB payload must not
        // arrive - this is the whole point of the proxy existing.
        val bytes = download(proxy(ceiling = 16_000L, bulkBytes = 0L), origin(1_000_000), 1_000)

        assertTrue("carried $bytes in 1s at a 16 KB/s ceiling", bytes < 80_000)
    }

    @Test
    fun `a small request is never held up`() {
        // Under the bulk threshold: handshakes and API calls must not be paced,
        // or an app decides the network is broken and stops using it.
        val payload = 20_000
        val started = System.currentTimeMillis()
        val bytes = download(proxy(ceiling = 16_000L), origin(payload), limitMillis = 3_000)

        assertEquals(payload, bytes)
        assertTrue(System.currentTimeMillis() - started < 2_000)
    }

    @Test
    fun `the squeeze lifts and the stream recovers`() {
        var ceiling = 16_000L
        val proxy = TcpProxy(ceilingBytesPerSecond = { ceiling }, bulkBytes = 0L)
            .also { proxies += it; it.start() }
        val server = origin(400_000)

        thread(isDaemon = true) {
            Thread.sleep(600)
            ceiling = 0L
        }
        val bytes = download(proxy, server, limitMillis = 3_000)

        assertEquals(400_000, bytes)
    }

    @Test
    fun `connect lines parse, and anything else is refused`() {
        assertEquals("i.instagram.com" to 443, TcpProxy.parseConnect("CONNECT i.instagram.com:443 HTTP/1.1"))
        assertEquals("::1" to 443, TcpProxy.parseConnect("CONNECT [::1]:443 HTTP/1.1"))
        assertNull(TcpProxy.parseConnect("GET http://example.com/ HTTP/1.1"))
        assertNull(TcpProxy.parseConnect("CONNECT example.com HTTP/1.1"))
        assertNull(TcpProxy.parseConnect("CONNECT example.com:0 HTTP/1.1"))
        assertNull(TcpProxy.parseConnect(""))
    }
}
