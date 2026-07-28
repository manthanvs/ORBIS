package com.orbis.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class Ipv6Test {

    private val source = ByteArray(16) { it.toByte() }
    private val destination = ByteArray(16) { (0xF0 or (it and 0x0F)).toByte() }

    @Test
    fun `round-trips a udp datagram`() {
        val payload = byteArrayOf(9, 8, 7, 6)
        val packet = Ipv6.buildUdp(source, destination, 51234, 443, payload)

        val parsed = Ipv6.parseUdp(packet, packet.size)!!

        assertArrayEquals(source, parsed.sourceAddress)
        assertArrayEquals(destination, parsed.destinationAddress)
        assertEquals(51234, parsed.sourcePort)
        assertEquals(443, parsed.destinationPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `identifies ipv6 and its next header`() {
        val packet = Ipv6.buildUdp(source, destination, 1, 2, ByteArray(4))

        assertTrue(Ipv6.isIpv6(packet, packet.size))
        assertEquals(Ipv6.NEXT_HEADER_UDP, Ipv6.nextHeader(packet, packet.size))
    }

    @Test
    fun `udp checksum is never zero`() {
        // Zero means "no checksum" which is illegal over IPv6, so a computed zero
        // must be transmitted as 0xFFFF.
        val packet = Ipv6.buildUdp(source, destination, 1, 2, ByteArray(0))

        assertNotEquals(0, Ipv4.readShort(packet, Ipv6.HEADER_BYTES + 6))
    }

    @Test
    fun `checksum covers the payload`() {
        val a = Ipv6.buildUdp(source, destination, 1, 2, byteArrayOf(1, 2, 3, 4))
        val b = Ipv6.buildUdp(source, destination, 1, 2, byteArrayOf(1, 2, 3, 5))

        assertNotEquals(
            Ipv4.readShort(a, Ipv6.HEADER_BYTES + 6),
            Ipv4.readShort(b, Ipv6.HEADER_BYTES + 6),
        )
    }

    @Test
    fun `checksum covers the addresses via the pseudo-header`() {
        val other = ByteArray(16) { 0x11 }
        val a = Ipv6.buildUdp(source, destination, 1, 2, byteArrayOf(7))
        val b = Ipv6.buildUdp(source, other, 1, 2, byteArrayOf(7))

        assertNotEquals(
            Ipv4.readShort(a, Ipv6.HEADER_BYTES + 6),
            Ipv4.readShort(b, Ipv6.HEADER_BYTES + 6),
        )
    }

    @Test
    fun `payload length field matches the udp length`() {
        val packet = Ipv6.buildUdp(source, destination, 1, 2, ByteArray(100))

        assertEquals(108, Ipv4.readShort(packet, 4)) // 8 UDP header + 100 payload
        assertEquals(Ipv6.HEADER_BYTES + 108, packet.size)
    }

    @Test
    fun `ports above 32767 survive the round trip`() {
        val packet = Ipv6.buildUdp(source, destination, 65535, 60000, byteArrayOf(1))

        val parsed = Ipv6.parseUdp(packet, packet.size)!!

        assertEquals(65535, parsed.sourcePort)
        assertEquals(60000, parsed.destinationPort)
    }

    @Test
    fun `rejects an ipv4 packet`() {
        val ipv4 = Ipv4.buildUdp(0x0A000001, 0x0A000002, 1, 2, ByteArray(4))

        assertNull(Ipv6.nextHeader(ipv4, ipv4.size))
        assertNull(Ipv6.parseUdp(ipv4, ipv4.size))
    }

    @Test
    fun `rejects a truncated packet`() {
        assertNull(Ipv6.parseUdp(ByteArray(20).also { it[0] = 0x60 }, 20))
    }

    @Test
    fun `parseUdp refuses a tcp next header`() {
        val packet = Ipv6.buildUdp(source, destination, 1, 2, ByteArray(4))
        packet[6] = Ipv6.NEXT_HEADER_TCP.toByte()

        assertNull(Ipv6.parseUdp(packet, packet.size))
        assertEquals(Ipv6.NEXT_HEADER_TCP, Ipv6.nextHeader(packet, packet.size))
    }
}
