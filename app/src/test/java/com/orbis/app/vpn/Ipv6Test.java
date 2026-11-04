package com.orbis.app.vpn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNotEquals;
import static org.junit.Assert.assertNull;
import static org.junit.Assert.assertTrue;

import org.junit.Test;

public class Ipv6Test {

    private final byte[] source = address(false);
    private final byte[] destination = address(true);

    private static byte[] address(boolean high) {
        byte[] bytes = new byte[16];
        for (int i = 0; i < bytes.length; i++) {
            bytes[i] = (byte) (high ? (0xF0 | (i & 0x0F)) : i);
        }
        return bytes;
    }

    private static int checksumOf(byte[] packet) {
        return Ipv4.readShort(packet, Ipv6.HEADER_BYTES + 6);
    }

    @Test
    public void roundTripsAUdpDatagram() {
        byte[] payload = {9, 8, 7, 6};
        byte[] packet = Ipv6.buildUdp(source, destination, 51234, 443, payload);

        Ipv6.UdpDatagram parsed = Ipv6.parseUdp(packet, packet.length);

        assertArrayEquals(source, parsed.getSourceAddress());
        assertArrayEquals(destination, parsed.getDestinationAddress());
        assertEquals(51234, parsed.getSourcePort());
        assertEquals(443, parsed.getDestinationPort());
        assertArrayEquals(payload, parsed.getPayload());
    }

    @Test
    public void identifiesIpv6AndItsNextHeader() {
        byte[] packet = Ipv6.buildUdp(source, destination, 1, 2, new byte[4]);

        assertTrue(Ipv6.isIpv6(packet, packet.length));
        assertEquals(
                Integer.valueOf(Ipv6.NEXT_HEADER_UDP), Ipv6.nextHeader(packet, packet.length));
    }

    @Test
    public void udpChecksumIsNeverZero() {
        // Zero means "no checksum" which is illegal over IPv6, so a computed zero
        // must be transmitted as 0xFFFF.
        byte[] packet = Ipv6.buildUdp(source, destination, 1, 2, new byte[0]);

        assertNotEquals(0, checksumOf(packet));
    }

    @Test
    public void checksumCoversThePayload() {
        byte[] a = Ipv6.buildUdp(source, destination, 1, 2, new byte[] {1, 2, 3, 4});
        byte[] b = Ipv6.buildUdp(source, destination, 1, 2, new byte[] {1, 2, 3, 5});

        assertNotEquals(checksumOf(a), checksumOf(b));
    }

    @Test
    public void checksumCoversTheAddressesViaThePseudoHeader() {
        byte[] other = new byte[16];
        java.util.Arrays.fill(other, (byte) 0x11);
        byte[] a = Ipv6.buildUdp(source, destination, 1, 2, new byte[] {7});
        byte[] b = Ipv6.buildUdp(source, other, 1, 2, new byte[] {7});

        assertNotEquals(checksumOf(a), checksumOf(b));
    }

    @Test
    public void payloadLengthFieldMatchesTheUdpLength() {
        byte[] packet = Ipv6.buildUdp(source, destination, 1, 2, new byte[100]);

        assertEquals(108, Ipv4.readShort(packet, 4)); // 8 UDP header + 100 payload
        assertEquals(Ipv6.HEADER_BYTES + 108, packet.length);
    }

    @Test
    public void portsAbove32767SurviveTheRoundTrip() {
        byte[] packet = Ipv6.buildUdp(source, destination, 65535, 60000, new byte[] {1});

        Ipv6.UdpDatagram parsed = Ipv6.parseUdp(packet, packet.length);

        assertEquals(65535, parsed.getSourcePort());
        assertEquals(60000, parsed.getDestinationPort());
    }

    @Test
    public void rejectsAnIpv4Packet() {
        byte[] ipv4 = Ipv4.buildUdp(0x0A000001, 0x0A000002, 1, 2, new byte[4]);

        assertNull(Ipv6.nextHeader(ipv4, ipv4.length));
        assertNull(Ipv6.parseUdp(ipv4, ipv4.length));
    }

    @Test
    public void rejectsATruncatedPacket() {
        byte[] truncated = new byte[20];
        truncated[0] = 0x60;

        assertNull(Ipv6.parseUdp(truncated, 20));
    }

    @Test
    public void parseUdpRefusesATcpNextHeader() {
        byte[] packet = Ipv6.buildUdp(source, destination, 1, 2, new byte[4]);
        packet[6] = (byte) Ipv6.NEXT_HEADER_TCP;

        assertNull(Ipv6.parseUdp(packet, packet.length));
        assertEquals(
                Integer.valueOf(Ipv6.NEXT_HEADER_TCP), Ipv6.nextHeader(packet, packet.length));
    }
}
