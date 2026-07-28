package com.orbis.app.vpn

/**
 * Just enough IPv4/UDP to relay QUIC through the tunnel.
 *
 * Deliberately pure: header layout and checksum arithmetic are exactly the kind
 * of thing that is painful to debug on a device, so they live in `src/test`.
 */
object Ipv4 {

    const val PROTOCOL_TCP = 6
    const val PROTOCOL_UDP = 17

    private const val MIN_HEADER_BYTES = 20
    private const val UDP_HEADER_BYTES = 8

    data class Header(
        val protocol: Int,
        val sourceAddress: Int,
        val destinationAddress: Int,
        val headerBytes: Int,
        val totalLength: Int,
    )

    data class UdpDatagram(
        val sourceAddress: Int,
        val destinationAddress: Int,
        val sourcePort: Int,
        val destinationPort: Int,
        val payload: ByteArray,
    ) {
        // Data class equality on a ByteArray compares references, which makes
        // tests lie. Compare contents instead.
        override fun equals(other: Any?): Boolean {
            if (this === other) return true
            if (other !is UdpDatagram) return false
            return sourceAddress == other.sourceAddress &&
                destinationAddress == other.destinationAddress &&
                sourcePort == other.sourcePort &&
                destinationPort == other.destinationPort &&
                payload.contentEquals(other.payload)
        }

        override fun hashCode(): Int {
            var result = sourceAddress
            result = 31 * result + destinationAddress
            result = 31 * result + sourcePort
            result = 31 * result + destinationPort
            result = 31 * result + payload.contentHashCode()
            return result
        }
    }

    /** @return null when [length] does not hold a well-formed IPv4 header. */
    fun parseHeader(packet: ByteArray, length: Int): Header? {
        if (length < MIN_HEADER_BYTES) return null

        val versionAndIhl = packet[0].toInt() and 0xFF
        if ((versionAndIhl shr 4) != 4) return null

        val headerBytes = (versionAndIhl and 0x0F) * 4
        if (headerBytes < MIN_HEADER_BYTES || headerBytes > length) return null

        val totalLength = readShort(packet, 2)
        if (totalLength > length) return null

        return Header(
            protocol = packet[9].toInt() and 0xFF,
            sourceAddress = readInt(packet, 12),
            destinationAddress = readInt(packet, 16),
            headerBytes = headerBytes,
            totalLength = totalLength,
        )
    }

    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        val header = parseHeader(packet, length) ?: return null
        if (header.protocol != PROTOCOL_UDP) return null
        if (header.totalLength < header.headerBytes + UDP_HEADER_BYTES) return null

        val udpStart = header.headerBytes
        val udpLength = readShort(packet, udpStart + 4)
        if (udpLength < UDP_HEADER_BYTES) return null

        val payloadLength = minOf(udpLength, header.totalLength - udpStart) - UDP_HEADER_BYTES
        if (payloadLength < 0) return null

        val payloadStart = udpStart + UDP_HEADER_BYTES
        return UdpDatagram(
            sourceAddress = header.sourceAddress,
            destinationAddress = header.destinationAddress,
            sourcePort = readShort(packet, udpStart),
            destinationPort = readShort(packet, udpStart + 2),
            payload = packet.copyOfRange(payloadStart, payloadStart + payloadLength),
        )
    }

    /**
     * Builds a complete IPv4+UDP packet to write back into the tunnel, with the
     * addresses and ports already swapped relative to the outbound datagram.
     */
    fun buildUdp(
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
    ): ByteArray {
        val totalLength = MIN_HEADER_BYTES + UDP_HEADER_BYTES + payload.size
        val packet = ByteArray(totalLength)

        packet[0] = 0x45                       // IPv4, 5-word header
        writeShort(packet, 2, totalLength)
        packet[8] = 64                         // TTL
        packet[9] = PROTOCOL_UDP.toByte()
        writeInt(packet, 12, sourceAddress)
        writeInt(packet, 16, destinationAddress)
        writeShort(packet, 10, checksum(packet, 0, MIN_HEADER_BYTES))

        val udpStart = MIN_HEADER_BYTES
        writeShort(packet, udpStart, sourcePort)
        writeShort(packet, udpStart + 2, destinationPort)
        writeShort(packet, udpStart + 4, UDP_HEADER_BYTES + payload.size)
        payload.copyInto(packet, udpStart + UDP_HEADER_BYTES)
        // UDP checksum is optional over IPv4; 0 means "not computed". Leaving it
        // zero keeps this simple and is accepted by the stack.

        return packet
    }

    /** Standard one's-complement internet checksum (RFC 1071). */
    fun checksum(data: ByteArray, offset: Int, length: Int): Int {
        var sum = 0L
        var index = offset
        val end = offset + length

        while (index + 1 < end) {
            sum += readShort(data, index).toLong()
            index += 2
        }
        if (index < end) {
            sum += ((data[index].toInt() and 0xFF) shl 8).toLong()
        }
        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        return (sum.inv() and 0xFFFF).toInt()
    }

    fun toBytes(address: Int): ByteArray = byteArrayOf(
        ((address shr 24) and 0xFF).toByte(),
        ((address shr 16) and 0xFF).toByte(),
        ((address shr 8) and 0xFF).toByte(),
        (address and 0xFF).toByte(),
    )

    fun readShort(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 8) or (data[offset + 1].toInt() and 0xFF)

    fun readInt(data: ByteArray, offset: Int): Int =
        ((data[offset].toInt() and 0xFF) shl 24) or
            ((data[offset + 1].toInt() and 0xFF) shl 16) or
            ((data[offset + 2].toInt() and 0xFF) shl 8) or
            (data[offset + 3].toInt() and 0xFF)

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
