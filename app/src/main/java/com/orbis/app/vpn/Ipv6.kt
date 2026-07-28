package com.orbis.app.vpn

/**
 * IPv6 equivalent of [Ipv4], needed because this is not an edge case: the test
 * device has IPv6 on both WiFi and LTE, and Instagram and YouTube use it heavily.
 *
 * Ignoring it is not neutral - a tunnel that captures IPv6 and cannot relay it
 * blackholes most of the traffic it was meant to merely slow down.
 *
 * Two differences from IPv4 matter here:
 *  - the header is a fixed 40 bytes with no checksum of its own
 *  - the UDP checksum is **mandatory**, and must cover a pseudo-header
 */
object Ipv6 {

    const val NEXT_HEADER_TCP = 6
    const val NEXT_HEADER_UDP = 17

    const val HEADER_BYTES = 40
    const val ADDRESS_BYTES = 16
    private const val UDP_HEADER_BYTES = 8

    data class UdpDatagram(
        val sourceAddress: ByteArray,
        val destinationAddress: ByteArray,
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray,
    ) {
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UdpDatagram) return false
            return sourceAddress.contentEquals(other.sourceAddress) &&
                destinationAddress.contentEquals(other.destinationAddress) &&
                sourcePort == other.sourcePort &&
                destinationPort == other.destinationPort &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = sourceAddress.contentHashCode()
            result = 31 * result + destinationAddress.contentHashCode()
            result = 31 * result + sourcePort
            result = 31 * result + destinationPort
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    fun isIpv6(packet: ByteArray, length: Int): Boolean =
        length >= HEADER_BYTES && ((packet[0].toInt() and 0xF0) shr 4) == 6

    /** @return the next-header (protocol) value, or null if this is not IPv6. */
    fun nextHeader(packet: ByteArray, length: Int): Int? =
        if (!isIpv6(packet, length)) null else packet[6].toInt() and 0xFF

    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        if (nextHeader(packet, length) != NEXT_HEADER_UDP) return null
        if (length < HEADER_BYTES + UDP_HEADER_BYTES) return null

        val udpLength = Ipv4.readShort(packet, HEADER_BYTES + 4)
        if (udpLength < UDP_HEADER_BYTES) return null

        val available = length - HEADER_BYTES - UDP_HEADER_BYTES
        val payloadLength = minOf(udpLength - UDP_HEADER_BYTES, available)
        if (payloadLength < 0) return null

        val payloadStart = HEADER_BYTES + UDP_HEADER_BYTES
        return UdpDatagram(
            sourceAddress = packet.copyOfRange(8, 8 + ADDRESS_BYTES),
            destinationAddress = packet.copyOfRange(24, 24 + ADDRESS_BYTES),
            sourcePort = Ipv4.readShort(packet, HEADER_BYTES),
            destinationPort = Ipv4.readShort(packet, HEADER_BYTES + 2),
            payload = packet.copyOfRange(payloadStart, payloadStart + payloadLength),
        )
    }

    fun buildUdp(
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        require(sourceAddress.size == ADDRESS_BYTES && destinationAddress.size == ADDRESS_BYTES) {
            "IPv6 addresses must be 16 bytes"
        }

        val udpLength = UDP_HEADER_BYTES + payload.size
        val packet = ByteArray(HEADER_BYTES + udpLength)

        packet[0] = 0x60                       // version 6, no traffic class
        writeShort(packet, 4, udpLength)       // payload length
        packet[6] = NEXT_HEADER_UDP.toByte()
        packet[7] = 64                         // hop limit
        sourceAddress.copyInto(packet, 8)
        destinationAddress.copyInto(packet, 24)

        writeShort(packet, HEADER_BYTES, sourcePort)
        writeShort(packet, HEADER_BYTES + 2, destinationPort)
        writeShort(packet, HEADER_BYTES + 4, udpLength)
        payload.copyInto(packet, HEADER_BYTES + UDP_HEADER_BYTES)

        // Mandatory over IPv6. A zero checksum here is invalid and the packet
        // would be discarded by the stack.
        writeShort(packet, HEADER_BYTES + 6, udpChecksum(packet, udpLength))

        return packet
    }

    /**
     * Checksum over the IPv6 pseudo-header (source, destination, upper-layer
     * length, next header) followed by the UDP header and payload.
     */
    fun udpChecksum(packet: ByteArray, udpLength: Int): Int {
        val pseudo = ByteArray(2 * ADDRESS_BYTES + 8)
        packet.copyInto(pseudo, 0, 8, 8 + ADDRESS_BYTES)
        packet.copyInto(pseudo, ADDRESS_BYTES, 24, 24 + ADDRESS_BYTES)
        writeInt(pseudo, 2 * ADDRESS_BYTES, udpLength)
        pseudo[2 * ADDRESS_BYTES + 7] = NEXT_HEADER_UDP.toByte()

        val block = ByteArray(pseudo.size + udpLength)
        pseudo.copyInto(block, 0)
        packet.copyInto(block, pseudo.size, HEADER_BYTES, HEADER_BYTES + udpLength)

        val sum = Ipv4.checksum(block, 0, block.size)
        // RFC 768: a computed zero is transmitted as all ones, because zero means
        // "no checksum" - which is not permitted over IPv6.
        return if (sum == 0) 0xFFFF else sum
    }

    private fun writeShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }

    private fun writeInt(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 24) and 0xFF).toByte()
        data[offset + 1] = ((value shr 16) and 0xFF).toByte()
        data[offset + 2] = ((value shr 8) and 0xFF).toByte()
        data[offset + 3] = (value and 0xFF).toByte()
    }
}
