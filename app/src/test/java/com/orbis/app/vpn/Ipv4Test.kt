package com.orbis.app.vpn

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class Ipv4Test {

    private val source = ipv4(10, 111, 222, 2)
    private val destination = ipv4(142, 250, 195, 78)

    private fun ipv4(a: Int, b: Int, c: Int, d: Int): Int =
        (a shl 24) or (b shl 16) or (c shl 8) or d

    @Test
    fun `round-trips a udp datagram`() {
        val payload = byteArrayOf(1, 2, 3, 4, 5)
        val packet = Ipv4.buildUdp(source, destination, 51234, 443, payload)

        val parsed = Ipv4.parseUdp(packet, packet.size)!!

        assertEquals(source, parsed.sourceAddress)
        assertEquals(destination, parsed.destinationAddress)
        assertEquals(51234, parsed.sourcePort)
        assertEquals(443, parsed.destinationPort)
        assertArrayEquals(payload, parsed.payload)
    }

    @Test
    fun `built header reports udp and correct total length`() {
        val packet = Ipv4.buildUdp(source, destination, 1000, 443, ByteArray(100))

        val header = Ipv4.parseHeader(packet, packet.size)!!

        assertEquals(Ipv4.PROTOCOL_UDP, header.protocol)
        assertEquals(20, header.headerBytes)
        assertEquals(128, header.totalLength) // 20 IP + 8 UDP + 100 payload
        assertEquals(packet.size, header.totalLength)
    }

    @Test
    fun `header checksum verifies to zero`() {
        val packet = Ipv4.buildUdp(source, destination, 1000, 443, ByteArray(8))

        // Summing a valid header, checksum field included, must yield 0.
        assertEquals(0, Ipv4.checksum(packet, 0, 20))
    }

    @Test
    fun `handles an empty payload`() {
        val packet = Ipv4.buildUdp(source, destination, 1, 2, ByteArray(0))

        val parsed = Ipv4.parseUdp(packet, packet.size)!!

        assertEquals(0, parsed.payload.size)
    }

    @Test
    fun `ports above 32767 survive the round trip`() {
        // Ports are unsigned 16-bit; sign extension here would corrupt the flow key.
        val packet = Ipv4.buildUdp(source, destination, 65535, 60000, byteArrayOf(9))

        val parsed = Ipv4.parseUdp(packet, packet.size)!!

        assertEquals(65535, parsed.sourcePort)
        assertEquals(60000, parsed.destinationPort)
    }

    @Test
    fun `addresses with a high first octet are not sign extended`() {
        val high = ipv4(200, 100, 50, 25)
        val packet = Ipv4.buildUdp(high, high, 1, 1, ByteArray(0))

        assertEquals(high, Ipv4.parseUdp(packet, packet.size)!!.sourceAddress)
    }

    @Test
    fun `rejects a truncated packet`() {
        assertNull(Ipv4.parseHeader(ByteArray(10), 10))
    }

    @Test
    fun `rejects a non-ipv4 packet`() {
        val ipv6 = ByteArray(40).also { it[0] = 0x60 }

        assertNull(Ipv4.parseHeader(ipv6, ipv6.size))
    }

    @Test
    fun `rejects a header claiming more length than was read`() {
        val packet = Ipv4.buildUdp(source, destination, 1, 2, ByteArray(50))

        // totalLength says 78, but only 30 bytes are actually present.
        assertNull(Ipv4.parseHeader(packet, 30))
    }

    @Test
    fun `parseUdp refuses a tcp packet`() {
        val packet = Ipv4.buildUdp(source, destination, 1, 2, ByteArray(4))
        packet[9] = Ipv4.PROTOCOL_TCP.toByte()

        assertNull(Ipv4.parseUdp(packet, packet.size))
    }

    @Test
    fun `toInetAddress renders dotted quad correctly`() {
        assertEquals(
            "142.250.195.78",
            OrbisVpnService.toInetAddress(destination).hostAddress,
        )
    }
}
