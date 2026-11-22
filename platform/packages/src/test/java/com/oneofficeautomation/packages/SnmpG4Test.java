package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G4 (AA parity, 22 Nov 2026), SNMP: values as hex or text, a log file, walking past the subtree, and byte value types. */
class SnmpG4Test {
    @SuppressWarnings("unchecked")
    @Test void hexTextLogScopeAndByteTypes() throws Exception {
        Path w = Files.createTempDirectory("ooa-g4snmp");
        ActionContext c = PackagesTestSupport.ctx(w, Map.of());
        try (SystemGapsTest.FakeAgent agent = new SystemGapsTest.FakeAgent()) {
            NetworkPackage n = new NetworkPackage();
            Map<String, Object> hex = (Map<String, Object>) n.snmpGet(c, "127.0.0.1", "1.3.6.1.2.1.1.5.0", "public", agent.port(), 2000, 0, "v2c",
                    null, null, null, null, null, null, "hex", "snmp.log").get("values");
            assertEquals("73:77:69:74:63:68:2d:30:31", hex.get("1.3.6.1.2.1.1.5.0"), "switch-01 as hex bytes");
            assertTrue(Files.readString(w.resolve("snmp.log")).contains("GET 127.0.0.1"));

            Map<String, Object> within = n.snmpWalk(c, "127.0.0.1", "1.3.6.1.2.1.2.2", "public", agent.port(), 100, 2000, 0, "v2c",
                    null, null, null, null, null, null, "getnext", 10, "within", "auto", null);
            assertEquals(new BigDecimal(3), within.get("row_count"));
            Map<String, Object> all = n.snmpWalk(c, "127.0.0.1", "1.3.6.1.2.1.2.2", "public", agent.port(), 100, 2000, 0, "v2c",
                    null, null, null, null, null, null, "getnext", 10, "all", "auto", "snmp.log");
            assertEquals(new BigDecimal(4), all.get("row_count"), "on past the subtree to the end of the tree");
            assertTrue(Files.readString(w.resolve("snmp.log")).contains("1.3.6.1.2.1.4.1.0"));

            assertArrayEquals(new byte[]{10, 27, (byte) 255}, NetworkPackage.hexBytes("0a:1b:ff"));
            assertArrayEquals(new byte[]{10, 27, (byte) 255}, NetworkPackage.hexBytes("0x0A1BFF"));
            assertArrayEquals(new byte[]{10, 27, (byte) 255}, NetworkPackage.decimalBytes("10.27.255"));
            assertEquals("SNMP.BAD_VALUE", assertThrows(ActionException.class, () -> NetworkPackage.decimalBytes("10.300")).code());
            assertEquals(NetworkPackage.Snmp.OCTETS, NetworkPackage.Snmp.typeCode("hex_string"));
            assertEquals(NetworkPackage.Snmp.NULL, NetworkPackage.Snmp.typeCode("null"));
            n.snmpSet(c, "127.0.0.1", "1.3.6.1.2.1.1.6.0", "0a:1b", "hex_string", "private", agent.port(), 2000, 0, "v2c", null, null, null, null, null, null, "snmp.log");
            assertTrue(Files.readString(w.resolve("snmp.log")).contains("SET 127.0.0.1 1.3.6.1.2.1.1.6.0 (hex_string) = 0a:1b"));
        }
    }
}
