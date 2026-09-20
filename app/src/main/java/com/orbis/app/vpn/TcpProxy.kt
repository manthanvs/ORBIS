package com.orbis.app.vpn

import com.orbis.app.throttle.TokenBucket
import java.io.IOException
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.atomic.AtomicLong
import kotlin.concurrent.thread

/**
 * Carries the routed app's TCP, which the packet relay can only drop.
 *
 * The relay forwards UDP and drops TCP, so an app that falls back to TCP was
 * being starved rather than slowed - measured on CPH2585, Morphe's Shorts froze
 * on the first frame while ORBIS dropped 73 TCP packets against 135 KB of UDP.
 *
 * Rather than write a userspace TCP stack to re-implement what the kernel
 * already does, the tunnel advertises this proxy with `Builder.setHttpProxy`.
 * The app's own networking then connects here, on loopback, so its TCP never
 * enters the tunnel and never meets the drop. Each connection is terminated
 * locally and re-opened outward through a protected socket - so this is a relay
 * in the same sense the UDP path is, one layer up.
 *
 * The squeeze is applied by **pacing**, not dropping: the download direction is
 * read only as fast as the ceiling allows, and TCP's own flow control pushes the
 * slowdown back to the sender. No retransmits, no sequence numbers, no loss.
 *
 * Deliberately free of Android types, so the whole thing is testable over
 * loopback in `src/test`. [protect] is `VpnService.protect` in production, and
 * keeps the outward socket out of the tunnel it would otherwise loop back into.
 */
class TcpProxy(
    private val protect: (Socket) -> Unit = {},
    /** Download ceiling in bytes/second right now; 0 means unlimited. */
    private val ceilingBytesPerSecond: () -> Long = { 0L },
    /** Bytes a connection may carry before the ceiling applies to it. */
    private val bulkBytes: Long = 64L * 1024L,
) {
    private val server = ServerSocket(0, BACKLOG, InetAddress.getByName(LOOPBACK))

    /** One allowance for the whole app, so its streams share the ceiling. */
    private val bucket = TokenBucket()
    private val bucketLock = Any()

    private val bytesIn = AtomicLong(0)
    private val paused = AtomicLong(0)

    @Volatile
    private var running = true

    val port: Int get() = server.localPort

    /** Download bytes carried, and how many chunks the squeeze held back. */
    fun bytesCarried(): Long = bytesIn.get()
    fun chunksPaced(): Long = paused.get()

    fun start() {
        thread(name = "orbis-proxy", isDaemon = true) {
            while (running) {
                val client = try {
                    server.accept()
                } catch (_: IOException) {
                    break
                }
                // ponytail: thread per connection. A feed opens tens, not
                // thousands; swap for NIO only if that stops being true.
                thread(name = "orbis-proxy-conn", isDaemon = true) { serve(client) }
            }
        }
    }

    fun stop() {
        running = false
        runCatching { server.close() }
    }

    private fun serve(client: Socket) {
        var upstream: Socket? = null
        try {
            client.tcpNoDelay = true
            val request = readRequestLine(client) ?: return
            val target = parseConnect(request) ?: run {
                // ponytail: CONNECT only. Everything these apps do is HTTPS; a
                // plain-HTTP request would need the whole header rewritten.
                client.getOutputStream().write(NOT_IMPLEMENTED.toByteArray())
                return
            }

            upstream = Socket().apply {
                tcpNoDelay = true
                protect(this)
                connect(InetSocketAddress(target.first, target.second), CONNECT_TIMEOUT_MILLIS)
            }
            client.getOutputStream().write(ESTABLISHED.toByteArray())

            val up = thread(name = "orbis-proxy-up", isDaemon = true) {
                copy(client, upstream, paced = false)
            }
            copy(upstream, client, paced = true)
            up.join(JOIN_MILLIS)
        } catch (_: Throwable) {
            // A dead connection is the app's problem to retry, not ours to log.
        } finally {
            runCatching { client.close() }
            runCatching { upstream?.close() }
        }
    }

    /**
     * Pumps one direction. When [paced], a chunk waits for its allowance before
     * being handed on, which is what makes the squeeze felt without dropping
     * anything: the reader stops reading, the window closes, the sender slows.
     */
    private fun copy(from: Socket, to: Socket, paced: Boolean) {
        // Small chunks on purpose: a chunk larger than the bucket's burst could
        // never be afforded at a trickle rate, and would wedge.
        val buffer = ByteArray(CHUNK_BYTES)
        val input = from.getInputStream()
        val output = to.getOutputStream()
        var carried = 0L

        while (running) {
            val read = try {
                input.read(buffer)
            } catch (_: IOException) {
                break
            }
            if (read < 0) break
            if (read == 0) continue

            if (paced) {
                carried += read
                bytesIn.addAndGet(read.toLong())
                // A new connection runs free until it is plainly a stream: the
                // handshake and small requests must never be held up.
                if (carried > bulkBytes) await(read)
            }

            try {
                output.write(buffer, 0, read)
                output.flush()
            } catch (_: IOException) {
                break
            }
        }
        runCatching { to.shutdownOutput() }
    }

    /** Blocks until [bytes] is affordable at the current ceiling. */
    private fun await(bytes: Int) {
        var waited = 0L
        while (running) {
            val ceiling = ceilingBytesPerSecond()
            val allowed = synchronized(bucketLock) {
                bucket.tryTake(bytes, ceiling, System.nanoTime())
            }
            if (allowed) return

            paused.incrementAndGet()
            if (waited >= MAX_WAIT_MILLIS) return
            waited += WAIT_STEP_MILLIS
            try {
                Thread.sleep(WAIT_STEP_MILLIS)
            } catch (_: InterruptedException) {
                return
            }
        }
    }

    private fun readRequestLine(client: Socket): String? {
        val input = client.getInputStream()
        val request = readLine(input) ?: return null
        // Drain the remaining CONNECT headers - they say nothing we need - and
        // stop at the blank line that ends them, not at a byte count.
        while (true) {
            val header = readLine(input) ?: break
            if (header.isEmpty()) break
        }
        return request
    }

    /** One CRLF-terminated line, or null at end of stream. */
    private fun readLine(input: InputStream): String? {
        val line = StringBuilder()
        while (line.length < MAX_REQUEST_LINE) {
            val c = input.read()
            if (c < 0) return if (line.isEmpty()) null else line.toString()
            if (c == LF) break
            if (c != CR) line.append(c.toChar())
        }
        return line.toString()
    }

    companion object {
        private const val LOOPBACK = "127.0.0.1"
        private const val BACKLOG = 32
        private const val CHUNK_BYTES = 1_400
        private const val CONNECT_TIMEOUT_MILLIS = 10_000
        private const val JOIN_MILLIS = 1_000L
        private const val WAIT_STEP_MILLIS = 20L

        /** Never wedge a connection for longer than one squeeze. */
        private const val MAX_WAIT_MILLIS = 6_000L
        private const val MAX_REQUEST_LINE = 2_048
        private const val CR = 13
        private const val LF = 10

        private const val ESTABLISHED = "HTTP/1.1 200 Connection established\r\n\r\n"
        private const val NOT_IMPLEMENTED = "HTTP/1.1 501 Not Implemented\r\n\r\n"

        /** `CONNECT host:443 HTTP/1.1` -> host to port. Null for anything else. */
        fun parseConnect(requestLine: String): Pair<String, Int>? {
            val parts = requestLine.trim().split(' ')
            if (parts.size < 2 || !parts[0].equals("CONNECT", ignoreCase = true)) return null
            val authority = parts[1]
            val colon = authority.lastIndexOf(':')
            if (colon <= 0 || colon == authority.length - 1) return null
            val port = authority.substring(colon + 1).toIntOrNull() ?: return null
            if (port !in 1..65_535) return null
            return authority.substring(0, colon).trim('[', ']') to port
        }
    }
}
