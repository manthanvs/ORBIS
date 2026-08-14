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

    /**
     * A parsed datagram expressed as offsets into the caller's buffer.
     *
     * The relay keeps one of these per thread and refills it, so parsing a packet
     * costs nothing. [UdpDatagram] copies the payload out instead, which at video
     * bitrates was the single largest source of garbage in the app - a ~1.4 KB
     * array per packet, thousands of packets a second.
     */
    class UdpView {
        var sourceAddress: Int = 0
        var destinationAddress: Int = 0
        var sourcePort: Int = 0
        var destinationPort: Int = 0
        var payloadOffset: Int = 0
        var payloadLength: Int = 0
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

    /**
     * Fills [into] with offsets into [packet] rather than copying anything.
     *
     * @return false when this is not a well-formed IPv4 UDP datagram.
     */
    fun parseUdpInto(packet: ByteArray, length: Int, into: UdpView): Boolean {
        val header = parseHeader(packet, length) ?: return false
        if (header.protocol != PROTOCOL_UDP) return false
        if (header.totalLength < header.headerBytes + UDP_HEADER_BYTES) return false

        val udpStart = header.headerBytes
        val udpLength = readShort(packet, udpStart + 4)
        if (udpLength < UDP_HEADER_BYTES) return false

        val payloadLength = minOf(udpLength, header.totalLength - udpStart) - UDP_HEADER_BYTES
        if (payloadLength < 0) return false

        into.sourceAddress = header.sourceAddress
        into.destinationAddress = header.destinationAddress
        into.sourcePort = readShort(packet, udpStart)
        into.destinationPort = readShort(packet, udpStart + 2)
        into.payloadOffset = udpStart + UDP_HEADER_BYTES
        into.payloadLength = payloadLength
        return true
    }

    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        val view = UdpView()
        if (!parseUdpInto(packet, length, view)) return null

        return UdpDatagram(
            sourceAddress = view.sourceAddress,
            destinationAddress = view.destinationAddress,
            sourcePort = view.sourcePort,
            destinationPort = view.destinationPort,
            payload = packet.copyOfRange(
                view.payloadOffset,
                view.payloadOffset + view.payloadLength,
            ),
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
        val packet = ByteArray(MIN_HEADER_BYTES + UDP_HEADER_BYTES + payload.size)
        buildUdpInto(
            out = packet,
            sourceAddress = sourceAddress,
            destinationAddress = destinationAddress,
            sourcePort = sourcePort,
            destinationPort = destinationPort,
            payload = payload,
            payloadOffset = 0,
            payloadLength = payload.size,
        )
        return packet
    }

    /**
     * Writes the same packet into a caller-owned buffer, so the relay can reuse
     * one output buffer for every reply instead of allocating per packet.
     *
     * Every header byte is written explicitly, including the zeroed ones: [out]
     * is reused and therefore holds the previous packet's bytes on entry.
     *
     * @return the total packet length written at offset 0.
     */
    fun buildUdpInto(
        out: ByteArray,
        sourceAddress: Int,
        destinationAddress: Int,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    ): Int {
        val totalLength = MIN_HEADER_BYTES + UDP_HEADER_BYTES + payloadLength
        require(out.size >= totalLength) { "buffer too small for $totalLength bytes" }

        out[0] = 0x45                          // IPv4, 5-word header
        out[1] = 0                             // DSCP / ECN
        writeShort(out, 2, totalLength)
        writeShort(out, 4, 0)                  // identification
        writeShort(out, 6, 0)                  // flags / fragment offset
        out[8] = 64                            // TTL
        out[9] = PROTOCOL_UDP.toByte()
        writeShort(out, 10, 0)                 // checksum, zeroed before computing
        writeInt(out, 12, sourceAddress)
        writeInt(out, 16, destinationAddress)
        writeShort(out, 10, checksum(out, 0, MIN_HEADER_BYTES))

        val udpStart = MIN_HEADER_BYTES
        writeShort(out, udpStart, sourcePort)
        writeShort(out, udpStart + 2, destinationPort)
        writeShort(out, udpStart + 4, UDP_HEADER_BYTES + payloadLength)
        // UDP checksum is optional over IPv4; 0 means "not computed". Leaving it
        // zero keeps this simple and is accepted by the stack.
        writeShort(out, udpStart + 6, 0)
        payload.copyInto(out, udpStart + UDP_HEADER_BYTES, payloadOffset, payloadOffset + payloadLength)

        return totalLength
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
