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

    /** Byte offset of the source address inside an IPv6 header. */
    const val SOURCE_OFFSET = 8

    /** Byte offset of the destination address inside an IPv6 header. */
    const val DESTINATION_OFFSET = 24

    /**
     * Offsets into the caller's buffer, so the relay can parse without copying.
     *
     * The addresses stay as offsets rather than arrays: the relay copies them out
     * exactly once, when a flow is first opened, instead of twice per packet.
     */
    class UdpView {
        var sourcePort: Int = 0
        var destinationPort: Int = 0
        var payloadOffset: Int = 0
        var payloadLength: Int = 0
    }

    /** @return false when this is not a well-formed IPv6 UDP datagram. */
    fun parseUdpInto(packet: ByteArray, length: Int, into: UdpView): Boolean {
        if (nextHeader(packet, length) != NEXT_HEADER_UDP) return false
        if (length < HEADER_BYTES + UDP_HEADER_BYTES) return false

        val udpLength = Ipv4.readShort(packet, HEADER_BYTES + 4)
        if (udpLength < UDP_HEADER_BYTES) return false

        val available = length - HEADER_BYTES - UDP_HEADER_BYTES
        val payloadLength = minOf(udpLength - UDP_HEADER_BYTES, available)
        if (payloadLength < 0) return false

        into.sourcePort = Ipv4.readShort(packet, HEADER_BYTES)
        into.destinationPort = Ipv4.readShort(packet, HEADER_BYTES + 2)
        into.payloadOffset = HEADER_BYTES + UDP_HEADER_BYTES
        into.payloadLength = payloadLength
        return true
    }

    fun parseUdp(packet: ByteArray, length: Int): UdpDatagram? {
        val view = UdpView()
        if (!parseUdpInto(packet, length, view)) return null

        return UdpDatagram(
            sourceAddress = packet.copyOfRange(SOURCE_OFFSET, SOURCE_OFFSET + ADDRESS_BYTES),
            destinationAddress = packet.copyOfRange(
                DESTINATION_OFFSET,
                DESTINATION_OFFSET + ADDRESS_BYTES,
            ),
            sourcePort = view.sourcePort,
            destinationPort = view.destinationPort,
            payload = packet.copyOfRange(
                view.payloadOffset,
                view.payloadOffset + view.payloadLength,
            ),
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

        val packet = ByteArray(HEADER_BYTES + UDP_HEADER_BYTES + payload.size)
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
     * Writes the packet into a caller-owned buffer so the relay can reuse one
     * output buffer for every reply.
     *
     * Every header byte is written explicitly, the checksum field included: [out]
     * is reused and still holds the previous packet on entry, and a stale
     * checksum field would be folded into the new one.
     *
     * @return the total packet length written at offset 0.
     */
    fun buildUdpInto(
        out: ByteArray,
        sourceAddress: ByteArray,
        destinationAddress: ByteArray,
        sourcePort: Int,
        destinationPort: Int,
        payload: ByteArray,
        payloadOffset: Int,
        payloadLength: Int,
    ): Int {
        require(sourceAddress.size == ADDRESS_BYTES && destinationAddress.size == ADDRESS_BYTES) {
            "IPv6 addresses must be 16 bytes"
        }

        val udpLength = UDP_HEADER_BYTES + payloadLength
        val totalLength = HEADER_BYTES + udpLength
        require(out.size >= totalLength) { "buffer too small for $totalLength bytes" }

        out[0] = 0x60                          // version 6, no traffic class
        out[1] = 0                             // traffic class / flow label
        out[2] = 0
        out[3] = 0
        writeShort(out, 4, udpLength)          // payload length
        out[6] = NEXT_HEADER_UDP.toByte()
        out[7] = 64                            // hop limit
        sourceAddress.copyInto(out, SOURCE_OFFSET)
        destinationAddress.copyInto(out, DESTINATION_OFFSET)

        writeShort(out, HEADER_BYTES, sourcePort)
        writeShort(out, HEADER_BYTES + 2, destinationPort)
        writeShort(out, HEADER_BYTES + 4, udpLength)
        writeShort(out, HEADER_BYTES + 6, 0)   // zeroed before it is computed
        payload.copyInto(
            out,
            HEADER_BYTES + UDP_HEADER_BYTES,
            payloadOffset,
            payloadOffset + payloadLength,
        )

        // Mandatory over IPv6. A zero checksum here is invalid and the packet
        // would be discarded by the stack.
        writeShort(out, HEADER_BYTES + 6, udpChecksum(out, udpLength))

        return totalLength
    }

    /**
     * Checksum over the IPv6 pseudo-header (source, destination, upper-layer
     * length, next header) followed by the UDP header and payload.
     */
    fun udpChecksum(packet: ByteArray, udpLength: Int): Int =
        udpChecksum(packet, HEADER_BYTES, udpLength, SOURCE_OFFSET, DESTINATION_OFFSET)

    /**
     * The same checksum, folded in place.
     *
     * The pseudo-header is summed field by field straight out of [packet] rather
     * than being materialised: the copying version allocated two scratch arrays
     * and a third full copy of the payload for every single reply packet.
     */
    fun udpChecksum(
        packet: ByteArray,
        udpOffset: Int,
        udpLength: Int,
        sourceOffset: Int,
        destinationOffset: Int,
    ): Int {
        var sum = 0L

        sum += sumWords(packet, sourceOffset, ADDRESS_BYTES)
        sum += sumWords(packet, destinationOffset, ADDRESS_BYTES)

        // Upper-layer packet length as a 32-bit field, then three zero bytes and
        // the next-header value - i.e. the words 0x0000 and 0x0011.
        sum += ((udpLength ushr 16) and 0xFFFF).toLong()
        sum += (udpLength and 0xFFFF).toLong()
        sum += NEXT_HEADER_UDP.toLong()

        sum += sumWords(packet, udpOffset, udpLength)

        while (sum shr 16 != 0L) {
            sum = (sum and 0xFFFF) + (sum shr 16)
        }
        val result = (sum.inv() and 0xFFFF).toInt()
        // RFC 768: a computed zero is transmitted as all ones, because zero means
        // "no checksum" - which is not permitted over IPv6.
        return if (result == 0) 0xFFFF else result
    }

    /** Unfolded one's-complement word sum over a range, with odd-length padding. */
    private fun sumWords(data: ByteArray, offset: Int, length: Int): Long {
        var sum = 0L
        var index = offset
        val end = offset + length

        while (index + 1 < end) {
            sum += Ipv4.readShort(data, index).toLong()
            index += 2
        }
        if (index < end) {
            sum += ((data[index].toInt() and 0xFF) shl 8).toLong()
        }
        return sum
    }

    private fun writeShort(data: ByteArray, offset: Int, value: Int) {
        data[offset] = ((value shr 8) and 0xFF).toByte()
        data[offset + 1] = (value and 0xFF).toByte()
    }
}
