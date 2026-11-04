package com.orbis.app.vpn;

import static org.junit.Assert.assertArrayEquals;
import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertNull;

import org.junit.Test;

import java.net.InetAddress;
import java.net.UnknownHostException;

public class Ipv4Test {

    private final int source = ipv4(10, 111, 222, 2);
    private final int destination = ipv4(142, 250, 195, 78);

    private static int ipv4(int a, int b, int c, int d) {
        return (a << 24) | (b << 16) | (c << 8) | d;
    }

    @Test
    public void roundTripsAUdpDatagram() {
        byte[] payload = {1, 2, 3, 4, 5};
        byte[] packet = Ipv4.buildUdp(source, destination, 51234, 443, payload);

        Ipv4.UdpDatagram parsed = Ipv4.parseUdp(packet, packet.length);

        assertEquals(source, parsed.getSourceAddress());
        assertEquals(destination, parsed.getDestinationAddress());
        assertEquals(51234, parsed.getSourcePort());
        assertEquals(443, parsed.getDestinationPort());
        assertArrayEquals(payload, parsed.getPayload());
    }

    @Test
    public void builtHeaderReportsUdpAndCorrectTotalLength() {
        byte[] packet = Ipv4.buildUdp(source, destination, 1000, 443, new byte[100]);

        Ipv4.Header header = Ipv4.parseHeader(packet, packet.length);

        assertEquals(Ipv4.PROTOCOL_UDP, header.getProtocol());
        assertEquals(20, header.getHeaderBytes());
        assertEquals(128, header.getTotalLength()); // 20 IP + 8 UDP + 100 payload
        assertEquals(packet.length, header.getTotalLength());
    }

    @Test
    public void headerChecksumVerifiesToZero() {
        byte[] packet = Ipv4.buildUdp(source, destination, 1000, 443, new byte[8]);

        // Summing a valid header, checksum field included, must yield 0.
        assertEquals(0, Ipv4.checksum(packet, 0, 20));
    }

    @Test
    public void handlesAnEmptyPayload() {
        byte[] packet = Ipv4.buildUdp(source, destination, 1, 2, new byte[0]);

        assertEquals(0, Ipv4.parseUdp(packet, packet.length).getPayload().length);
    }

    @Test
    public void portsAbove32767SurviveTheRoundTrip() {
        // Ports are unsigned 16-bit; sign extension here would corrupt the flow key.
        byte[] packet = Ipv4.buildUdp(source, destination, 65535, 60000, new byte[] {9});

        Ipv4.UdpDatagram parsed = Ipv4.parseUdp(packet, packet.length);

        assertEquals(65535, parsed.getSourcePort());
        assertEquals(60000, parsed.getDestinationPort());
    }

    @Test
    public void addressesWithAHighFirstOctetAreNotSignExtended() {
        int high = ipv4(200, 100, 50, 25);
        byte[] packet = Ipv4.buildUdp(high, high, 1, 1, new byte[0]);

        assertEquals(high, Ipv4.parseUdp(packet, packet.length).getSourceAddress());
    }

    @Test
    public void rejectsATruncatedPacket() {
        assertNull(Ipv4.parseHeader(new byte[10], 10));
    }

    @Test
    public void rejectsANonIpv4Packet() {
        byte[] ipv6 = new byte[40];
        ipv6[0] = 0x60;

        assertNull(Ipv4.parseHeader(ipv6, ipv6.length));
    }

    @Test
    public void rejectsAHeaderClaimingMoreLengthThanWasRead() {
        byte[] packet = Ipv4.buildUdp(source, destination, 1, 2, new byte[50]);

        // totalLength says 78, but only 30 bytes are actually present.
        assertNull(Ipv4.parseHeader(packet, 30));
    }

    @Test
    public void parseUdpRefusesATcpPacket() {
        byte[] packet = Ipv4.buildUdp(source, destination, 1, 2, new byte[4]);
        packet[9] = (byte) Ipv4.PROTOCOL_TCP;

        assertNull(Ipv4.parseUdp(packet, packet.length));
    }

    @Test
    public void toBytesRendersDottedQuadCorrectly() throws UnknownHostException {
        // The relay turns a parsed address back into one the socket can connect
        // to; a sign-extended octet here would dial the wrong host.
        assertEquals(
                "142.250.195.78",
                InetAddress.getByAddress(Ipv4.toBytes(destination)).getHostAddress());
    }
}
