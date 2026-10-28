package com.oneofficeautomation.controlroom;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Settings: trusted_proxies. Behind a load balancer the client's own address counts for the allow list and limits. */
class ClientAddressTest {
    static final IpAllowList LB = IpAllowList.parse("10.0.5.10, 10.0.6.0/24");
    static final IpAllowList NONE = IpAllowList.parse("");

    @Test void aTrustedProxyNamesTheClient() {
        assertEquals("203.0.113.7", ControlRoomServer.clientIp("10.0.5.10", false, "203.0.113.7", LB));
        // two trusted hops: the last address that is not a trusted proxy is the client
        assertEquals("203.0.113.7", ControlRoomServer.clientIp("10.0.5.10", false, "198.51.100.1, 203.0.113.7, 10.0.6.4", LB));
    }

    @Test void othersCannotChooseTheirAddress() {
        assertEquals("192.0.2.50", ControlRoomServer.clientIp("192.0.2.50", false, "10.0.0.1", LB), "not a trusted proxy: the header is ignored");
        assertEquals("10.0.5.10", ControlRoomServer.clientIp("10.0.5.10", false, "10.0.0.1", NONE), "no trusted proxies: the connection's address");
        assertEquals("10.0.5.10", ControlRoomServer.clientIp("10.0.5.10", false, "not-an-address", LB), "a header that is not an address is not trusted");
        assertEquals("10.0.5.10", ControlRoomServer.clientIp("10.0.5.10", false, null, LB));
    }

    @Test void aProxyOnThisComputerCountsAsTrusted() {
        assertEquals("203.0.113.7", ControlRoomServer.clientIp("127.0.0.1", true, "203.0.113.7", NONE),
                "a reverse proxy on the same computer forwards remote clients; they are not this computer");
        assertEquals("127.0.0.1", ControlRoomServer.clientIp("127.0.0.1", true, null, NONE));
        assertTrue(ControlRoomServer.isLoopback("127.0.0.1"));
        assertFalse(ControlRoomServer.isLoopback("203.0.113.7"));
    }
}
