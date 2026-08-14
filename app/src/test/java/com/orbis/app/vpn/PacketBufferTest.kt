package com.orbis.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Covers the allocation-free paths the relay actually runs on.
 *
 * `buildUdp`/`parseUdp` copy, and are what the other packet tests exercise. The
 * relay uses neither: it parses into a reused view and builds into a reused
 * output buffer, with the reply payload already sitting in its final position.
 * Two things go wrong quietly there and produce packets the stack silently
 * discards - a header byte left over from the previous packet, and a stale
 * checksum field folded into the new checksum - so the buffer is deliberately
 * dirtied before every build below.
 */
class PacketBufferTest {

    private val v6Source = ByteArray(16) { it.toByte() }
    private val v6Destination = ByteArray(16) { (0xF0 or (it and 0x0F)).toByte() }

    private fun dirtyBuffer(): ByteArray = ByteArray(1500) { 0xA5.toByte() }

    // ------------------------------------------------------------------ IPv4

    @Test
    fun `ipv4 build into a dirty buffer matches a fresh build`() {
        val payload = byteArrayOf(9, 8, 7, 6, 5)
        val expected = Ipv4.buildUdp(0x0A000001, 0x0A000002, 51234, 443, payload)

        val out = dirtyBuffer()
        val total = Ipv4.buildUdpInto(
            out = out,
            sourceAddress = 0x0A000001,
            destinationAddress = 0x0A000002,
            sourcePort = 51234,
            destinationPort = 443,
            payload = payload,
            payloadOffset = 0,
            payloadLength = payload.size,
        )

        assertEquals(expected.size, total)
        assertArrayEquals(expected, out.copyOfRange(0, total))
    }

    @Test
    fun `ipv4 builds correctly when the payload is already in place`() {
        // The reply path: the payload is read straight off the socket into the
        // offset it will occupy, and the headers are written in front of it.
        val payload = byteArrayOf(1, 2, 3, 4, 5, 6, 7)
        val expected = Ipv4.buildUdp(0x0A000001, 0x0A000002, 443, 51234, payload)

        val out = dirtyBuffer()
        val payloadOffset = 28 // 20-byte IPv4 header + 8-byte UDP header
        payload.copyInto(out, payloadOffset)

        val total = Ipv4.buildUdpInto(
            out = out,
            sourceAddress = 0x0A000001,
            destinationAddress = 0x0A000002,
            sourcePort = 443,
            destinationPort = 51234,
            payload = out,
            payloadOffset = payloadOffset,
            payloadLength = payload.size,
        )

        assertArrayEquals(expected, out.copyOfRange(0, total))
    }

    @Test
    fun `ipv4 reusing one buffer for successive packets leaves nothing behind`() {
        val out = dirtyBuffer()

        val long = ByteArray(200) { it.toByte() }
        Ipv4.buildUdpInto(out, 1, 2, 3, 4, long, 0, long.size)

        // A shorter packet must not inherit the tail of the longer one.
        val short = byteArrayOf(42)
        val total = Ipv4.buildUdpInto(out, 1, 2, 3, 4, short, 0, short.size)

        assertArrayEquals(Ipv4.buildUdp(1, 2, 3, 4, short), out.copyOfRange(0, total))
    }

    @Test
    fun `ipv4 parseUdpInto agrees with parseUdp`() {
        val payload = byteArrayOf(4, 5, 6)
        val packet = Ipv4.buildUdp(0x0A000001, 0x0A000002, 1234, 443, payload)
        val copied = Ipv4.parseUdp(packet, packet.size)!!

        val view = Ipv4.UdpView()
        assertTrue(Ipv4.parseUdpInto(packet, packet.size, view))

        assertEquals(copied.sourceAddress, view.sourceAddress)
        assertEquals(copied.destinationAddress, view.destinationAddress)
        assertEquals(copied.sourcePort, view.sourcePort)
        assertEquals(copied.destinationPort, view.destinationPort)
        assertArrayEquals(
            copied.payload,
            packet.copyOfRange(view.payloadOffset, view.payloadOffset + view.payloadLength),
        )
    }

    @Test
    fun `ipv4 parseUdpInto rejects tcp and leaves the view alone`() {
        val packet = Ipv4.buildUdp(1, 2, 3, 4, byteArrayOf(1))
        packet[9] = Ipv4.PROTOCOL_TCP.toByte()

        val view = Ipv4.UdpView()
        assertFalse(Ipv4.parseUdpInto(packet, packet.size, view))
        assertEquals(0, view.payloadLength)
    }

    // ------------------------------------------------------------------ IPv6

    @Test
    fun `ipv6 build into a dirty buffer matches a fresh build`() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val expected = Ipv6.buildUdp(v6Source, v6Destination, 51234, 443, payload)

        val out = dirtyBuffer()
        val total = Ipv6.buildUdpInto(
            out = out,
            sourceAddress = v6Source,
            destinationAddress = v6Destination,
            sourcePort = 51234,
            destinationPort = 443,
            payload = payload,
            payloadOffset = 0,
            payloadLength = payload.size,
        )

        assertEquals(expected.size, total)
        assertArrayEquals(expected, out.copyOfRange(0, total))
    }

    @Test
    fun `ipv6 builds correctly when the payload is already in place`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val expected = Ipv6.buildUdp(v6Source, v6Destination, 443, 51234, payload)

        val out = dirtyBuffer()
        val payloadOffset = Ipv6.HEADER_BYTES + 8
        payload.copyInto(out, payloadOffset)

        val total = Ipv6.buildUdpInto(
            out = out,
            sourceAddress = v6Source,
            destinationAddress = v6Destination,
            sourcePort = 443,
            destinationPort = 51234,
            payload = out,
            payloadOffset = payloadOffset,
            payloadLength = payload.size,
        )

        assertArrayEquals(expected, out.copyOfRange(0, total))
    }

    @Test
    fun `ipv6 checksum does not fold in the previous packet's checksum`() {
        // The checksum field has to be zeroed before it is computed. On a reused
        // buffer it still holds the last packet's value, and folding that in
        // yields a packet the stack drops without a word.
        val payload = byteArrayOf(1, 2, 3, 4)
        val out = dirtyBuffer()

        Ipv6.buildUdpInto(out, v6Source, v6Destination, 1, 2, payload, 0, payload.size)
        val first = Ipv4.readShort(out, Ipv6.HEADER_BYTES + 6)

        Ipv6.buildUdpInto(out, v6Source, v6Destination, 1, 2, payload, 0, payload.size)
        val second = Ipv4.readShort(out, Ipv6.HEADER_BYTES + 6)

        assertEquals(first, second)
    }

    @Test
    fun `ipv6 incremental checksum matches a full round trip`() {
        // The pseudo-header used to be materialised into scratch arrays. This is
        // the same sum folded in place, so it must still validate: summing a
        // packet including its own checksum yields zero.
        val payload = ByteArray(37) { (it * 7).toByte() }
        val packet = Ipv6.buildUdp(v6Source, v6Destination, 51234, 443, payload)
        val udpLength = 8 + payload.size

        var sum = 0L
        // Pseudo-header, then the UDP segment with its checksum in place.
        for (offset in intArrayOf(Ipv6.SOURCE_OFFSET, Ipv6.DESTINATION_OFFSET)) {
            var index = offset
            while (index < offset + Ipv6.ADDRESS_BYTES) {
                sum += Ipv4.readShort(packet, index)
                index += 2
            }
        }
        sum += udpLength.toLong()
        sum += Ipv6.NEXT_HEADER_UDP.toLong()
        val udpEnd = Ipv6.HEADER_BYTES + udpLength
        var index = Ipv6.HEADER_BYTES
        while (index + 1 < udpEnd) {
            sum += Ipv4.readShort(packet, index)
            index += 2
        }
        // The payload is an odd length on purpose: RFC 1071 pads the final byte
        // into the high half of a word, and dropping it is exactly the mistake
        // that yields a checksum the stack rejects.
        if (index < udpEnd) {
            sum += ((packet[index].toInt() and 0xFF) shl 8).toLong()
        }
        while (sum shr 16 != 0L) sum = (sum and 0xFFFF) + (sum shr 16)

        assertEquals(0xFFFF, sum.toInt())
    }

    @Test
    fun `ipv6 parseUdpInto agrees with parseUdp`() {
        val payload = byteArrayOf(7, 7, 7)
        val packet = Ipv6.buildUdp(v6Source, v6Destination, 1234, 443, payload)
        val copied = Ipv6.parseUdp(packet, packet.size)!!

        val view = Ipv6.UdpView()
        assertTrue(Ipv6.parseUdpInto(packet, packet.size, view))

        assertEquals(copied.sourcePort, view.sourcePort)
        assertEquals(copied.destinationPort, view.destinationPort)
        assertArrayEquals(
            copied.payload,
            packet.copyOfRange(view.payloadOffset, view.payloadOffset + view.payloadLength),
        )
        // The addresses stay as offsets; the relay copies them once per flow.
        assertArrayEquals(
            copied.destinationAddress,
            packet.copyOfRange(
                Ipv6.DESTINATION_OFFSET,
                Ipv6.DESTINATION_OFFSET + Ipv6.ADDRESS_BYTES,
            ),
        )
    }

    @Test
    fun `build rejects a buffer that cannot hold the packet`() {
        val tooSmall = ByteArray(24)
        val payload = ByteArray(64)

        val threw = runCatching {
            Ipv4.buildUdpInto(tooSmall, 1, 2, 3, 4, payload, 0, payload.size)
        }.isFailure

        assertTrue("a short buffer must fail loudly, not truncate the packet", threw)
    }
}
