package com.orbis.app.vpn;

import java.util.Arrays;

/**
 * Just enough IPv4/UDP to relay QUIC through the tunnel.
 *
 * <p>Deliberately pure: header layout and checksum arithmetic are exactly the
 * kind of thing that is painful to debug on a device, so they live in
 * {@code src/test}.
 */
public final class Ipv4 {

    public static final int PROTOCOL_TCP = 6;
    public static final int PROTOCOL_UDP = 17;

    private static final int MIN_HEADER_BYTES = 20;
    private static final int UDP_HEADER_BYTES = 8;

    private Ipv4() {
        // No instances: header arithmetic only.
    }

    public static final class Header {
        private final int protocol;
        private final int sourceAddress;
        private final int destinationAddress;
        private final int headerBytes;
        private final int totalLength;

        public Header(
                int protocol,
                int sourceAddress,
                int destinationAddress,
                int headerBytes,
                int totalLength) {
            this.protocol = protocol;
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.headerBytes = headerBytes;
            this.totalLength = totalLength;
        }

        public int getProtocol() {
            return protocol;
        }

        public int getSourceAddress() {
            return sourceAddress;
        }

        public int getDestinationAddress() {
            return destinationAddress;
        }

        public int getHeaderBytes() {
            return headerBytes;
        }

        public int getTotalLength() {
            return totalLength;
        }
    }

    public static final class UdpDatagram {
        private final int sourceAddress;
        private final int destinationAddress;
        private final int sourcePort;
        private final int destinationPort;
        private final byte[] payload;

        public UdpDatagram(
                int sourceAddress,
                int destinationAddress,
                int sourcePort,
                int destinationPort,
                byte[] payload) {
            this.sourceAddress = sourceAddress;
            this.destinationAddress = destinationAddress;
            this.sourcePort = sourcePort;
            this.destinationPort = destinationPort;
            this.payload = payload;
        }

        public int getSourceAddress() {
            return sourceAddress;
        }

        public int getDestinationAddress() {
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

        // Comparing a byte[] by reference makes tests lie. Compare contents.
        @Override
        public boolean equals(Object other) {
            if (this == other) {
                return true;
            }
            if (!(other instanceof UdpDatagram)) {
                return false;
            }
            UdpDatagram that = (UdpDatagram) other;
            return sourceAddress == that.sourceAddress
                    && destinationAddress == that.destinationAddress
                    && sourcePort == that.sourcePort
                    && destinationPort == that.destinationPort
                    && Arrays.equals(payload, that.payload);
        }

        @Override
        public int hashCode() {
            int result = sourceAddress;
            result = 31 * result + destinationAddress;
            result = 31 * result + sourcePort;
            result = 31 * result + destinationPort;
            result = 31 * result + Arrays.hashCode(payload);
            return result;
        }
    }

    /**
     * A parsed datagram expressed as offsets into the caller's buffer.
     *
     * <p>The relay keeps one of these per thread and refills it, so parsing a
     * packet costs nothing. {@link UdpDatagram} copies the payload out instead,
     * which at video bitrates was the single largest source of garbage in the
     * app - a ~1.4 KB array per packet, thousands of packets a second.
     */
    public static final class UdpView {
        public int sourceAddress = 0;
        public int destinationAddress = 0;
        public int sourcePort = 0;
        public int destinationPort = 0;
        public int payloadOffset = 0;
        public int payloadLength = 0;
    }

    /** @return null when the length does not hold a well-formed IPv4 header. */
    public static Header parseHeader(byte[] packet, int length) {
        if (length < MIN_HEADER_BYTES) {
            return null;
        }

        int versionAndIhl = packet[0] & 0xFF;
        if ((versionAndIhl >> 4) != 4) {
            return null;
        }

        int headerBytes = (versionAndIhl & 0x0F) * 4;
        if (headerBytes < MIN_HEADER_BYTES || headerBytes > length) {
            return null;
        }

        int totalLength = readShort(packet, 2);
        if (totalLength > length) {
            return null;
        }

        return new Header(
                packet[9] & 0xFF,
                readInt(packet, 12),
                readInt(packet, 16),
                headerBytes,
                totalLength);
    }

    /**
     * Fills the view with offsets into the packet rather than copying anything.
     *
     * @return false when this is not a well-formed IPv4 UDP datagram.
     */
    public static boolean parseUdpInto(byte[] packet, int length, UdpView into) {
        Header header = parseHeader(packet, length);
        if (header == null) {
            return false;
        }
        if (header.getProtocol() != PROTOCOL_UDP) {
            return false;
        }
        if (header.getTotalLength() < header.getHeaderBytes() + UDP_HEADER_BYTES) {
            return false;
        }

        int udpStart = header.getHeaderBytes();
        int udpLength = readShort(packet, udpStart + 4);
        if (udpLength < UDP_HEADER_BYTES) {
            return false;
        }

        int payloadLength =
                Math.min(udpLength, header.getTotalLength() - udpStart) - UDP_HEADER_BYTES;
        if (payloadLength < 0) {
            return false;
        }

        into.sourceAddress = header.getSourceAddress();
        into.destinationAddress = header.getDestinationAddress();
        into.sourcePort = readShort(packet, udpStart);
        into.destinationPort = readShort(packet, udpStart + 2);
        into.payloadOffset = udpStart + UDP_HEADER_BYTES;
        into.payloadLength = payloadLength;
        return true;
    }

    public static UdpDatagram parseUdp(byte[] packet, int length) {
        UdpView view = new UdpView();
        if (!parseUdpInto(packet, length, view)) {
            return null;
        }

        return new UdpDatagram(
                view.sourceAddress,
                view.destinationAddress,
                view.sourcePort,
                view.destinationPort,
                Arrays.copyOfRange(
                        packet, view.payloadOffset, view.payloadOffset + view.payloadLength));
    }

    /**
     * Builds a complete IPv4+UDP packet to write back into the tunnel, with the
     * addresses and ports already swapped relative to the outbound datagram.
     */
    public static byte[] buildUdp(
            int sourceAddress,
            int destinationAddress,
            int sourcePort,
            int destinationPort,
            byte[] payload) {
        byte[] packet = new byte[MIN_HEADER_BYTES + UDP_HEADER_BYTES + payload.length];
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
     * Writes the same packet into a caller-owned buffer, so the relay can reuse
     * one output buffer for every reply instead of allocating per packet.
     *
     * <p>Every header byte is written explicitly, including the zeroed ones: the
     * buffer is reused and therefore holds the previous packet's bytes on entry.
     *
     * @return the total packet length written at offset 0.
     */
    public static int buildUdpInto(
            byte[] out,
            int sourceAddress,
            int destinationAddress,
            int sourcePort,
            int destinationPort,
            byte[] payload,
            int payloadOffset,
            int payloadLength) {

        int totalLength = MIN_HEADER_BYTES + UDP_HEADER_BYTES + payloadLength;
        if (out.length < totalLength) {
            throw new IllegalArgumentException("buffer too small for " + totalLength + " bytes");
        }

        out[0] = 0x45; // IPv4, 5-word header
        out[1] = 0; // DSCP / ECN
        writeShort(out, 2, totalLength);
        writeShort(out, 4, 0); // identification
        writeShort(out, 6, 0); // flags / fragment offset
        out[8] = 64; // TTL
        out[9] = (byte) PROTOCOL_UDP;
        writeShort(out, 10, 0); // checksum, zeroed before computing
        writeInt(out, 12, sourceAddress);
        writeInt(out, 16, destinationAddress);
        writeShort(out, 10, checksum(out, 0, MIN_HEADER_BYTES));

        int udpStart = MIN_HEADER_BYTES;
        writeShort(out, udpStart, sourcePort);
        writeShort(out, udpStart + 2, destinationPort);
        writeShort(out, udpStart + 4, UDP_HEADER_BYTES + payloadLength);
        // UDP checksum is optional over IPv4; 0 means "not computed". Leaving it
        // zero keeps this simple and is accepted by the stack.
        writeShort(out, udpStart + 6, 0);
        System.arraycopy(
                payload, payloadOffset, out, udpStart + UDP_HEADER_BYTES, payloadLength);

        return totalLength;
    }

    /** Standard one's-complement internet checksum (RFC 1071). */
    public static int checksum(byte[] data, int offset, int length) {
        long sum = 0L;
        int index = offset;
        int end = offset + length;

        while (index + 1 < end) {
            sum += readShort(data, index);
            index += 2;
        }
        if (index < end) {
            sum += ((long) (data[index] & 0xFF)) << 8;
        }
        while ((sum >> 16) != 0L) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }
        return (int) (~sum & 0xFFFF);
    }

    public static byte[] toBytes(int address) {
        return new byte[] {
            (byte) ((address >> 24) & 0xFF),
            (byte) ((address >> 16) & 0xFF),
            (byte) ((address >> 8) & 0xFF),
            (byte) (address & 0xFF)
        };
    }

    public static int readShort(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 8) | (data[offset + 1] & 0xFF);
    }

    public static int readInt(byte[] data, int offset) {
        return ((data[offset] & 0xFF) << 24)
                | ((data[offset + 1] & 0xFF) << 16)
                | ((data[offset + 2] & 0xFF) << 8)
                | (data[offset + 3] & 0xFF);
    }

    private static void writeShort(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 8) & 0xFF);
        data[offset + 1] = (byte) (value & 0xFF);
    }

    private static void writeInt(byte[] data, int offset, int value) {
        data[offset] = (byte) ((value >> 24) & 0xFF);
        data[offset + 1] = (byte) ((value >> 16) & 0xFF);
        data[offset + 2] = (byte) ((value >> 8) & 0xFF);
        data[offset + 3] = (byte) (value & 0xFF);
    }
}
