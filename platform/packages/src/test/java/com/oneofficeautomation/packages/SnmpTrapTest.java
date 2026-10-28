package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.NetworkPackage.Snmp;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** SNMP traps in each version, caught by a UDP receiver on this computer. */
class SnmpTrapTest {
    static ActionContext ctx() throws Exception {
        return new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-trap"), new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    static byte[] receive(DatagramSocket s) throws Exception {
        byte[] buf = new byte[65535];
        DatagramPacket p = new DatagramPacket(buf, buf.length);
        s.receive(p);
        return Arrays.copyOf(p.getData(), p.getLength());
    }

    @Test void v1TrapCarriesEnterpriseAndTrapTypes() throws Exception {
        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            rx.setSoTimeout(3000);
            new NetworkPackage().snmpSendTrap(ctx(), "127.0.0.1", "1.3.6.1.4.1.99999", new LinkedHashMap<>(Map.of("1.3.6.1.4.1.99999.1.1", "Disk full")), "traps", rx.getLocalPort(),
                "v1", "enterprise_specific", 7, null, null, null, null, null, null, null);
            byte[] b = receive(rx);
            Snmp.Reader top = new Snmp.Reader(b, 0, b.length);
            Snmp.Reader msg = top.inner(top.next(Snmp.SEQUENCE));
            assertArrayEquals(new byte[]{0}, msg.bytes(msg.next(Snmp.INTEGER)), "version 1 is coded as 0");
            assertEquals("traps", new String(msg.bytes(msg.next(Snmp.OCTETS)), StandardCharsets.UTF_8));
            Snmp.Reader pdu = msg.inner(msg.next(Snmp.TRAP_V1));
            assertEquals("1.3.6.1.4.1.99999", Snmp.decodeOid(pdu.bytes(pdu.next(Snmp.OID))));
            assertEquals(4, pdu.bytes(pdu.next(Snmp.IPADDR)).length);
            assertArrayEquals(new byte[]{6}, pdu.bytes(pdu.next(Snmp.INTEGER)), "enterprise_specific");
            assertArrayEquals(new byte[]{7}, pdu.bytes(pdu.next(Snmp.INTEGER)));
            pdu.next(Snmp.TIMETICKS);
            Snmp.Reader vbs = pdu.inner(pdu.next(Snmp.SEQUENCE));
            Snmp.Reader vb = vbs.inner(vbs.next(Snmp.SEQUENCE));
            assertEquals("1.3.6.1.4.1.99999.1.1", Snmp.decodeOid(vb.bytes(vb.next(Snmp.OID))));
            assertEquals("Disk full", new String(vb.bytes(vb.next(Snmp.OCTETS)), StandardCharsets.UTF_8));
        }
        assertEquals("SNMP.BAD_TRAP_TYPE", assertThrows(ActionException.class, () -> new NetworkPackage().snmpSendTrap(ctx(), "127.0.0.1", "1.3.6.1.4.1.99999", null, null, 162,
            "v1", "nope", 0, null, null, null, null, null, null, null)).code());
    }

    @SuppressWarnings("unchecked")
    @Test void v3TrapIsSignedAndEncryptedFromThisEngine() throws Exception {
        SecureString auth = new SecureString("auth-pass-1", "vault:t"), priv = new SecureString("priv-pass-1", "vault:t");
        try (DatagramSocket rx = new DatagramSocket(0, InetAddress.getLoopbackAddress())) {
            rx.setSoTimeout(3000);
            Map<String, Object> r = new NetworkPackage().snmpSendTrap(ctx(), "127.0.0.1", "1.3.6.1.4.1.99999.0.2", new LinkedHashMap<>(Map.of("1.3.6.1.4.1.99999.1.2", BigDecimal.valueOf(42))),
                null, rx.getLocalPort(), "v3", null, 0, "monitor", "sha256", auth, "aes128", priv, "", "80000000040102030405");
            assertEquals("80000000040102030405", r.get("engine_id"));
            byte[] b = receive(rx);
            byte[] engine = HexFormat.of().parseHex("80000000040102030405");
            // the receiver knows the user with this engine ID, so it can check the signature and decrypt
            SnmpV3.Msg m = SnmpV3.decode(b, b.length, "sha256", "aes128", (e, u) -> new byte[][]{
                SnmpV3.localizedKey("sha256", "auth-pass-1".getBytes(), e), SnmpV3.localizedKey("sha256", "priv-pass-1".getBytes(), e)});
            assertArrayEquals(engine, m.engineId());
            assertEquals("monitor", m.user());
            assertEquals(Snmp.TRAP, m.pduType());
            assertEquals(SnmpV3.FLAG_AUTH | SnmpV3.FLAG_PRIV, m.flags());
            assertEquals("1.3.6.1.4.1.99999.0.2", m.vars().get(1).value());
            assertEquals(0, new BigDecimal("42").compareTo((BigDecimal) m.vars().get(2).value()));
            // a receiver with another password refuses it
            assertEquals("SNMP.WRONG_DIGEST", assertThrows(ActionException.class, () -> SnmpV3.decode(b, b.length, "sha256", "aes128", (e, u) -> new byte[][]{
                SnmpV3.localizedKey("sha256", "other-pass".getBytes(), e), SnmpV3.localizedKey("sha256", "priv-pass-1".getBytes(), e)})).code());
        }
        byte[] own = SnmpV3.localEngineId();
        assertEquals((byte) 0x80, own[0]);
        assertTrue(own.length >= 6 && own.length <= 32);
        assertEquals("SNMP.BAD_ENGINE_ID", assertThrows(ActionException.class, () -> SnmpV3.engineId("12")).code());
    }
}
