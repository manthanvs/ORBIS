package com.orbis.app.vpn;

import java.util.Arrays;

/**
 * IPv6 equivalent of {@link Ipv4}, needed because this is not an edge case: the
 * test device has IPv6 on both WiFi and LTE, and the target apps use it heavily.
 *
 * <p>Ignoring it is not neutral - a tunnel that captures IPv6 and cannot relay
 * it blackholes most of the traffic it was meant to merely slow down.
 *
 * <p>Two differences from IPv4 matter here:
 *
 * <ul>
 *   <li>the header is a fixed 40 bytes with no checksum of its own
 *   <li>the UDP checksum is <b>mandatory</b>, and must cover a pseudo-header
 * </ul>
 */
public final class Ipv6 {

    public static final int NEXT_HEADER_TCP = 6;
    public static final int NEXT_HEADER_UDP = 17;

    public static final int HEADER_BYTES = 40;
    public static final int ADDRESS_BYTES = 16;
    private static final int UDP_HEADER_BYTES = 8;

    /** Byte offset of the source address inside an IPv6 header. */
    public static final int SOURCE_OFFSET = 8;

    /** Byte offset of the destination address inside an IPv6 header. */
    public static final int DESTINATION_OFFSET = 24;

    private Ipv6() {
        // No instances: header arithmetic only.
    }

    public static final class UdpDatagram {
        private final byte[] sourceAddress;
        private final byte[] destinationAddress;
        private final int sourcePort;
        private final int destinationPort;
        private final byte[] payload;

        public UdpDatagram(
                byte[] sourceAddress,
                byte[] destinationAddress,
                int sourcePort,
                int destinationPort,
                byte[] payload) {
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.payload = payload;
        }

        public byte[] getSourceAddress() {
            return sourceAddress;
        }

        public byte[] getDestinationAddress() {
            return destinationAddress;
        }

        public int getSourcePort() {
            return sourcePort;
        }

        public int getDestinationPort() {
            return destinationPort;
        }

        public byte[] getPayload() {
            return payload;
        }

        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof UdpDatagram)) {
                return false;
            }
            UdpDatagram that = (UdpDatagram) other;
            return sourcePort == that.sourcePort
                    && destinationPort == that.destinationPort
                    && Arrays.equals(sourceAddress, that.sourceAddress)
                    && Arrays.equals(destinationAddress, that.destinationAddress)
                    && Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            int result = Arrays.hashCode(sourceAddress);
            result = 31 * result + Arrays.hashCode(destinationAddress);
            result = 31 * result + sourcePort;
            result = 31 * result + destinationPort;
            result = 31 * result + Arrays.hashCode(payload);
            return result;
        }
    }

    /**
     * Offsets into the caller's buffer, so the relay can parse without copying.
     *
     * <p>The addresses stay as offsets rather than arrays: the relay copies them
     * out exactly once, when a flow is first opened, instead of twice per packet.
     */
    public static final class UdpView {
        public int sourcePort = 0;
        public int destinationPort = 0;
        public int payloadOffset = 0;
        public int payloadLength = 0;
    }

    public static boolean isIpv6(byte[] packet, int length) {
        return length >= HEADER_BYTES && ((packet[0] & 0xF0) >> 4) == 6;
    }

    /** @return the next-header (protocol) value, or null if this is not IPv6. */
    public static Integer nextHeader(byte[] packet, int length) {
        if (!isIpv6(packet, length)) {
            return null;
        }
        return packet[6] & 0xFF;
    }

    /** @return false when this is not a well-formed IPv6 UDP datagram. */
    public static boolean parseUdpInto(byte[] packet, int length, UdpView into) {
        Integer next = nextHeader(packet, length);
        if (next == null || next != NEXT_HEADER_UDP) {
            return false;
        }
        if (length < HEADER_BYTES + UDP_HEADER_BYTES) {
            return false;
        }

        int udpLength = Ipv4.readShort(packet, HEADER_BYTES + 4);
        if (udpLength < UDP_HEADER_BYTES) {
            return false;
        }

        int available = length - HEADER_BYTES - UDP_HEADER_BYTES;
        int payloadLength = Math.min(udpLength - UDP_HEADER_BYTES, available);
        if (payloadLength < 0) {
            return false;
        }

        into.sourcePort = Ipv4.readShort(packet, HEADER_BYTES);
        into.destinationPort = Ipv4.readShort(packet, HEADER_BYTES + 2);
        into.payloadOffset = HEADER_BYTES + UDP_HEADER_BYTES;
        into.payloadLength = payloadLength;
        return true;
    }

    public static UdpDatagram parseUdp(byte[] packet, int length) {
        UdpView view = new UdpView();
        if (!parseUdpInto(packet, length, view)) {
            return null;
        }

        return new UdpDatagram(
                Arrays.copyOfRange(packet, SOURCE_OFFSET, SOURCE_OFFSET + ADDRESS_BYTES),
                Arrays.copyOfRange(
                        packet, DESTINATION_OFFSET, DESTINATION_OFFSET + ADDRESS_BYTES),
                view.sourcePort,
                view.destinationPort,
                Arrays.copyOfRange(
                        packet, view.payloadOffset, view.payloadOffset + view.payloadLength));
    }

    public static byte[] buildUdp(
            byte[] sourceAddress,
            byte[] destinationAddress,
            int sourcePort,
            int destinationPort,
            byte[] payload) {

        requireAddresses(sourceAddress, destinationAddress);

        byte[] packet = new byte[HEADER_BYTES + UDP_HEADER_BYTES + payload.length];
        buildUdpInto(
                packet,
                sourceAddress,
                destinationAddress,
                sourcePort,
                destinationPort,
                payload,
                0,
                payload.length);
        return packet;
    }

    /**
     * Writes the packet into a caller-owned buffer so the relay can reuse one
     * output buffer for every reply.
     *
     * <p>Every header byte is written explicitly, the checksum field included:
     * the buffer is reused and still holds the previous packet on entry, and a
     * stale checksum field would be folded into the new one.
     *
     * @return the total packet length written at offset 0.
     */
    public static int buildUdpInto(
            byte[] out,
            byte[] sourceAddress,
            byte[] destinationAddress,
            int sourcePort,
            int destinationPort,
            byte[] payload,
            int payloadOffset,
            int payloadLength) {

        requireAddresses(sourceAddress, destinationAddress);

        int udpLength = UDP_HEADER_BYTES + payloadLength;
        int totalLength = HEADER_BYTES + udpLength;
        if (out.length < totalLength) {
            throw new IllegalArgumentException("buffer too small for " + totalLength + " bytes");
        }

        out[0] = 0x60; // version 6, no traffic class
        out[1] = 0; // traffic class / flow label
        out[2] = 0;
        out[3] = 0;
        writeShort(out, 4, udpLength); // payload length
        out[6] = (byte) NEXT_HEADER_UDP;
        out[7] = 64; // hop limit
        System.arraycopy(sourceAddress, 0, out, SOURCE_OFFSET, ADDRESS_BYTES);
        System.arraycopy(destinationAddress, 0, out, DESTINATION_OFFSET, ADDRESS_BYTES);

        writeShort(out, HEADER_BYTES, sourcePort);
        writeShort(out, HEADER_BYTES + 2, destinationPort);
        writeShort(out, HEADER_BYTES + 4, udpLength);
        writeShort(out, HEADER_BYTES + 6, 0); // zeroed before it is computed
        System.arraycopy(
                payload, payloadOffset, out, HEADER_BYTES + UDP_HEADER_BYTES, payloadLength);

        // Mandatory over IPv6. A zero checksum here is invalid and the packet
        // would be discarded by the stack.
        writeShort(out, HEADER_BYTES + 6, udpChecksum(out, udpLength));

        return totalLength;
    }

    /**
     * Checksum over the IPv6 pseudo-header (source, destination, upper-layer
     * length, next header) followed by the UDP header and payload.
     */
    public static int udpChecksum(byte[] packet, int udpLength) {
        return udpChecksum(packet, HEADER_BYTES, udpLength, SOURCE_OFFSET, DESTINATION_OFFSET);
    }

    /**
     * The same checksum, folded in place.
     *
     * <p>The pseudo-header is summed field by field straight out of the packet
     * rather than being materialised: the copying version allocated two scratch
     * arrays and a third full copy of the payload for every single reply packet.
     */
    public static int udpChecksum(
            byte[] packet, int udpOffset, int udpLength, int sourceOffset, int destinationOffset) {

        long sum = 0L;

        sum += sumWords(packet, sourceOffset, ADDRESS_BYTES);
        sum += sumWords(packet, destinationOffset, ADDRESS_BYTES);

        // Upper-layer packet length as a 32-bit field, then three zero bytes and
        // the next-header value - i.e. the words 0x0000 and 0x0011.
        sum += (udpLength >>> 16) & 0xFFFF;
        sum += udpLength & 0xFFFF;
        sum += NEXT_HEADER_UDP;

        sum += sumWords(packet, udpOffset, udpLength);

        while ((sum >> 16) != 0L) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        int result = (int) (~sum & 0xFFFF);
        // RFC 768: a computed zero is transmitted as all ones, because zero means
        // "no checksum" - which is not permitted over IPv6.
        return result == 0 ? 0xFFFF : result;
    }

    /** Unfolded one's-complement word sum over a range, with odd-length padding. */
    private static long sumWords(byte[] data, int offset, int length) {
        long sum = 0L;
        int index = offset;
        int end = offset + length;

        while (index + 1 < end) {
            sum += Ipv4.readShort(data, index);
            index += 2;
        }
        if (index < end) {
            sum += ((long) (data[index] & 0xFF)) << 8;
        }
        return sum;
    }

    private static void requireAddresses(byte[] sourceAddress, byte[] destinationAddress) {
        if (sourceAddress.length != ADDRESS_BYTES || destinationAddress.length != ADDRESS_BYTES) {
            throw new IllegalArgumentException("IPv6 addresses must be 16 bytes");
        }
    }

    private static void writeShort(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }
}
