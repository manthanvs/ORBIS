package com.orbis.app.vpn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertFalse;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

import java.util.Arrays;

/**
 * Covers the allocation-free paths the relay actually runs on.
 *
 * <p>{@code buildUdp}/{@code parseUdp} copy, and are what the other packet tests
 * exercise. The relay uses neither: it parses into a reused view and builds into
 * a reused output buffer, with the reply payload already sitting in its final
 * position. Two things go wrong quietly there and produce packets the stack
 * silently discards - a header byte left over from the previous packet, and a
 * stale checksum field folded into the new checksum - so the buffer is
 * deliberately dirtied before every build below.
 */
public class PacketBufferTest {

    private final byte[] v6Source = v6Address(false);
    private final byte[] v6Destination = v6Address(true);

    private static byte[] v6Address(boolean high) {
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (high ? (0xF0 | (i & 0x0F)) : i);
        }
        return bytes;
    }

    private static byte[] dirtyBuffer() {
        byte[] buffer = new byte[1500];
        Arrays.fill(buffer, (byte) 0xA5);
        return buffer;
    }

    private static byte[] range(byte[] source, int from, int to) {
        return Arrays.copyOfRange(source, from, to);
    }

    // ------------------------------------------------------------------ IPv4

    @Test
    public void ipv4BuildIntoADirtyBufferMatchesAFreshBuild() {
        byte[] payload = {9, 8, 7, 6, 5};
        byte[] expected = Ipv4.buildUdp(0x0A000001, 0x0A000002, 51234, 443, payload);

        byte[] out = dirtyBuffer();
        int total =
                Ipv4.buildUdpInto(
                        out,
                        0x0A000001,
                        0x0A000002,
                        51234,
                        443,
                        payload,
                        0,
                        payload.length);

        assertEquals(expected.length, total);
        assertArrayEquals(expected, range(out, 0, total));
    }

    @Test
    public void ipv4BuildsCorrectlyWhenThePayloadIsAlreadyInPlace() {
        // The reply path: the payload is read straight off the socket into the
        // offset it will occupy, and the headers are written in front of it.
        byte[] payload = {1, 2, 3, 4, 5, 6, 7};
        byte[] expected = Ipv4.buildUdp(0x0A000001, 0x0A000002, 443, 51234, payload);

        byte[] out = dirtyBuffer();
        int payloadOffset = 28; // 20-byte IPv4 header + 8-byte UDP header
        System.arraycopy(payload, 0, out, payloadOffset, payload.length);

        int total =
                Ipv4.buildUdpInto(
                        out,
                        0x0A000001,
                        0x0A000002,
                        443,
                        51234,
                        out,
                        payloadOffset,
                        payload.length);

        assertArrayEquals(expected, range(out, 0, total));
    }

    @Test
    public void ipv4ReusingOneBufferForSuccessivePacketsLeavesNothingBehind() {
        byte[] out = dirtyBuffer();

        byte[] longPayload = new byte[200];
        for (int i = 0; i < longPayload.length; i++) {
            longPayload[i] = (byte) i;
        }
        Ipv4.buildUdpInto(out, 1, 2, 3, 4, longPayload, 0, longPayload.length);

        // A shorter packet must not inherit the tail of the longer one.
        byte[] shortPayload = {42};
        int total = Ipv4.buildUdpInto(out, 1, 2, 3, 4, shortPayload, 0, shortPayload.length);

        assertArrayEquals(Ipv4.buildUdp(1, 2, 3, 4, shortPayload), range(out, 0, total));
    }

    @Test
    public void ipv4ParseUdpIntoAgreesWithParseUdp() {
        byte[] payload = {4, 5, 6};
        byte[] packet = Ipv4.buildUdp(0x0A000001, 0x0A000002, 1234, 443, payload);
        Ipv4.UdpDatagram copied = Ipv4.parseUdp(packet, packet.length);

        Ipv4.UdpView view = new Ipv4.UdpView();
        assertTrue(Ipv4.parseUdpInto(packet, packet.length, view));

        assertEquals(copied.getSourceAddress(), view.sourceAddress);
        assertEquals(copied.getDestinationAddress(), view.destinationAddress);
        assertEquals(copied.getSourcePort(), view.sourcePort);
        assertEquals(copied.getDestinationPort(), view.destinationPort);
        assertArrayEquals(
                copied.getPayload(),
                range(packet, view.payloadOffset, view.payloadOffset + view.payloadLength));
    }

    @Test
    public void ipv4ParseUdpIntoRejectsTcpAndLeavesTheViewAlone() {
        byte[] packet = Ipv4.buildUdp(1, 2, 3, 4, new byte[] {1});
        packet[9] = (byte) Ipv4.PROTOCOL_TCP;

        Ipv4.UdpView view = new Ipv4.UdpView();
        assertFalse(Ipv4.parseUdpInto(packet, packet.length, view));
        assertEquals(0, view.payloadLength);
    }

    // ------------------------------------------------------------------ IPv6

    @Test
    public void ipv6BuildIntoADirtyBufferMatchesAFreshBuild() {
        byte[] payload = {9, 8, 7, 6};
        byte[] expected = Ipv6.buildUdp(v6Source, v6Destination, 51234, 443, payload);

        byte[] out = dirtyBuffer();
        int total =
                Ipv6.buildUdpInto(
                        out, v6Source, v6Destination, 51234, 443, payload, 0, payload.length);

        assertEquals(expected.length, total);
        assertArrayEquals(expected, range(out, 0, total));
    }

    @Test
    public void ipv6BuildsCorrectlyWhenThePayloadIsAlreadyInPlace() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] expected = Ipv6.buildUdp(v6Source, v6Destination, 443, 51234, payload);

        byte[] out = dirtyBuffer();
        int payloadOffset = Ipv6.HEADER_BYTES + 8;
        System.arraycopy(payload, 0, out, payloadOffset, payload.length);

        int total =
                Ipv6.buildUdpInto(
                        out,
                        v6Source,
                        v6Destination,
                        443,
                        51234,
                        out,
                        payloadOffset,
                        payload.length);

        assertArrayEquals(expected, range(out, 0, total));
    }

    @Test
    public void ipv6ChecksumDoesNotFoldInThePreviousPacketChecksum() {
        // The checksum field has to be zeroed before it is computed. On a reused
        // buffer it still holds the last packet's value, and folding that in
        // yields a packet the stack drops without a word.
        byte[] payload = {1, 2, 3, 4};
        byte[] out = dirtyBuffer();

        Ipv6.buildUdpInto(out, v6Source, v6Destination, 1, 2, payload, 0, payload.length);
        int first = Ipv4.readShort(out, Ipv6.HEADER_BYTES + 6);

        Ipv6.buildUdpInto(out, v6Source, v6Destination, 1, 2, payload, 0, payload.length);
        int second = Ipv4.readShort(out, Ipv6.HEADER_BYTES + 6);

        assertEquals(first, second);
    }

    @Test
    public void ipv6IncrementalChecksumMatchesAFullRoundTrip() {
        // The pseudo-header used to be materialised into scratch arrays. This is
        // the same sum folded in place, so it must still validate: summing a
        // packet including its own checksum yields zero.
        byte[] payload = new byte[37];
        for (int i = 0; i < payload.length; i++) {
            payload[i] = (byte) (i * 7);
        }
        byte[] packet = Ipv6.buildUdp(v6Source, v6Destination, 51234, 443, payload);
        int udpLength = 8 + payload.length;

        long sum = 0L;
        // Pseudo-header, then the UDP segment with its checksum in place.
        for (int offset : new int[] {Ipv6.SOURCE_OFFSET, Ipv6.DESTINATION_OFFSET}) {
            for (int index = offset; index < offset + Ipv6.ADDRESS_BYTES; index += 2) {
                sum += Ipv4.readShort(packet, index);
            }
        }
        sum += udpLength;
        sum += Ipv6.NEXT_HEADER_UDP;
        int udpEnd = Ipv6.HEADER_BYTES + udpLength;
        int index = Ipv6.HEADER_BYTES;
        while (index + 1 < udpEnd) {
            sum += Ipv4.readShort(packet, index);
            index += 2;
        }
        // The payload is an odd length on purpose: RFC 1071 pads the final byte
        // into the high half of a word, and dropping it is exactly the mistake
        // that yields a checksum the stack rejects.
        if (index < udpEnd) {
            sum += (packet[index] & 0xFF) << 8;
        }
        while ((sum >> 16) != 0L) {
            sum = (sum & 0xFFFF) + (sum >> 16);
        }

        assertEquals(0xFFFF, (int) sum);
    }

    @Test
    public void ipv6ParseUdpIntoAgreesWithParseUdp() {
        byte[] payload = {7, 7, 7};
        byte[] packet = Ipv6.buildUdp(v6Source, v6Destination, 1234, 443, payload);
        Ipv6.UdpDatagram copied = Ipv6.parseUdp(packet, packet.length);

        Ipv6.UdpView view = new Ipv6.UdpView();
        assertTrue(Ipv6.parseUdpInto(packet, packet.length, view));

        assertEquals(copied.getSourcePort(), view.sourcePort);
        assertEquals(copied.getDestinationPort(), view.destinationPort);
        assertArrayEquals(
                copied.getPayload(),
                range(packet, view.payloadOffset, view.payloadOffset + view.payloadLength));
        // The addresses stay as offsets; the relay copies them once per flow.
        assertArrayEquals(
                copied.getDestinationAddress(),
                range(
                        packet,
                        Ipv6.DESTINATION_OFFSET,
                        Ipv6.DESTINATION_OFFSET + Ipv6.ADDRESS_BYTES));
    }

    @Test
    public void buildRejectsABufferThatCannotHoldThePacket() {
        byte[] tooSmall = new byte[24];
        byte[] payload = new byte[64];

        boolean threw;
        try {
            Ipv4.buildUdpInto(tooSmall, 1, 2, 3, 4, payload, 0, payload.length);
            threw = false;
        } catch (RuntimeException refused) {
            threw = true;
        }

        assertTrue("a short buffer must fail loudly, not truncate the packet", threw);
    }
}
