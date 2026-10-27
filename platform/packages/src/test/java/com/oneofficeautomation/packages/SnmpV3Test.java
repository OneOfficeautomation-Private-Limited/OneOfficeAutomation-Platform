package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.NetworkPackage.Snmp;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** X8: SNMP v3 (user-based security): the RFC 3414 key vectors, and get, walk and set against a stand-in v3 device. */
class SnmpV3Test {

    static byte[] hex(String h) { return HexFormat.of().parseHex(h.replace(" ", "")); }

    @Test void keysMatchTheRfcVectors() {
        byte[] engine = hex("000000000000000000000002");
        byte[] pw = "maplesyrup".getBytes(StandardCharsets.UTF_8);
        assertArrayEquals(hex("526f5eed9fcce26f8964c2930787d82b"), SnmpV3.localizedKey("md5", pw, engine), "RFC 3414 A.3.1");
        assertArrayEquals(hex("6695febc9288e36282235fc7151f128497b38f3f"), SnmpV3.localizedKey("sha", pw, engine), "RFC 3414 A.3.2");
        assertEquals("SNMP.BAD_SECURITY", assertThrows(ActionException.class, () -> new SnmpV3.Security("u", "sha", "short".getBytes(), "none", null, "")).code());
        assertEquals("SNMP.BAD_SECURITY", assertThrows(ActionException.class, () -> new SnmpV3.Security("u", "none", null, "aes128", "privpass1".getBytes(), "")).code());
    }

    /** A stand-in SNMP v3 device: one user, a few objects, and the reports real devices send. */
    static final class Device implements AutoCloseable {
        final DatagramSocket socket;
        final byte[] engine = hex("80001f888059dc486145a26322");
        final String user, auth, priv;
        final byte[] authKey, privKey;
        final Map<String, String> objects = new TreeMap<>(Map.of("1.3.6.1.2.1.1.5.0", "router-1", "1.3.6.1.2.1.2.2.1.2.1", "eth0", "1.3.6.1.2.1.2.2.1.2.2", "eth1"));
        final Thread thread;

        Device(String user, String auth, String authPw, String priv, String privPw) throws Exception {
            this.user = user; this.auth = auth; this.priv = priv;
            authKey = SnmpV3.localizedKey(auth, authPw.getBytes(), engine);
            privKey = priv.equals("none") ? null : SnmpV3.localizedKey(auth, privPw.getBytes(), engine);
            socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
            thread = new Thread(this::serve, "snmpv3-device");
            thread.setDaemon(true);
            thread.start();
        }
        int port() { return socket.getLocalPort(); }

        void reply(DatagramPacket p, SnmpV3.Msg m) throws Exception {
            byte[] out = SnmpV3.encode(m, auth, authKey, priv, privKey);
            socket.send(new DatagramPacket(out, out.length, p.getSocketAddress()));
        }

        SnmpV3.Msg report(int msgId, String counter) {
            return new SnmpV3.Msg(msgId, 0, engine, 5, 100, "", new byte[0], "", SnmpV3.REPORT, 0, 0, 0,
                List.of(new Snmp.VarBind(counter, Snmp.COUNTER32, BigDecimal.ONE)));
        }

        void serve() {
            byte[] buf = new byte[65535];
            while (!socket.isClosed()) {
                try {
                    DatagramPacket p = new DatagramPacket(buf, buf.length);
                    socket.receive(p);
                    int msgId = SnmpV3.msgIdOf(p.getData(), p.getLength());
                    SnmpV3.Msg in;
                    try { in = SnmpV3.decode(p.getData(), p.getLength(), auth, priv, (e, u) -> new byte[][]{authKey, privKey}); }
                    catch (ActionException e) { reply(p, report(msgId, "1.3.6.1.6.3.15.1.1.5.0")); continue; }
                    if (in.engineId().length == 0) { reply(p, report(msgId, "1.3.6.1.6.3.15.1.1.4.0")); continue; }
                    if (!in.user().equals(user)) { reply(p, report(msgId, "1.3.6.1.6.3.15.1.1.3.0")); continue; }
                    List<Snmp.VarBind> out = new ArrayList<>();
                    for (Snmp.VarBind vb : in.vars()) {
                        if (in.pduType() == Snmp.SET) { objects.put(vb.oid(), String.valueOf(vb.value())); out.add(vb); }
                        else if (in.pduType() == Snmp.GETNEXT) {
                            String next = objects.keySet().stream().filter(k -> compare(k, vb.oid()) > 0).findFirst().orElse(null);
                            out.add(next == null ? new Snmp.VarBind(vb.oid(), Snmp.END_OF_MIB, null) : new Snmp.VarBind(next, Snmp.OCTETS, objects.get(next)));
                        } else out.add(objects.containsKey(vb.oid()) ? new Snmp.VarBind(vb.oid(), Snmp.OCTETS, objects.get(vb.oid())) : new Snmp.VarBind(vb.oid(), Snmp.NO_SUCH_OBJECT, null));
                    }
                    byte[] salt = new byte[8];
                    new Random().nextBytes(salt);
                    reply(p, new SnmpV3.Msg(in.msgId(), in.flags() & 3, engine, 5, 101, user, salt, "", Snmp.RESPONSE, in.requestId(), 0, 0, out));
                } catch (Exception e) { if (socket.isClosed()) return; }
            }
        }

        static int compare(String a, String b) {
            long[] x = Snmp.parseOid(a), y = Snmp.parseOid(b);
            for (int i = 0; i < Math.min(x.length, y.length); i++) if (x[i] != y[i]) return Long.compare(x[i], y[i]);
            return Integer.compare(x.length, y.length);
        }

        @Override public void close() { socket.close(); }
    }

    @Test @SuppressWarnings("unchecked")
    void getWalkAndSetWithSignInAndPrivacy() throws Exception {
        ActionContext c = AiGapsTest.ctx(PackagesTest.tmp(), new ArrayList<>());
        NetworkPackage n = new NetworkPackage();
        for (String[] combo : new String[][]{{"sha", "aes128"}, {"md5", "des"}, {"sha256", "aes128"}, {"sha512", "none"}}) {
            SnmpV3.forgetEngines();
            try (Device d = new Device("ops", combo[0], "auth-pass-1", combo[1], "priv-pass-1")) {
                SecureString authPw = new SecureString("auth-pass-1", "net/snmp/auth"), privPw = combo[1].equals("none") ? null : new SecureString("priv-pass-1", "net/snmp/priv");
                Map<String, Object> got = (Map<String, Object>) n.snmpGet(c, "127.0.0.1", "1.3.6.1.2.1.1.5.0", null, d.port(), 2000, 0, "v3", "ops", combo[0], authPw, combo[1], privPw, null).get("values");
                assertEquals("router-1", got.get("1.3.6.1.2.1.1.5.0"), String.join("/", combo));
                Map<String, Object> walk = n.snmpWalk(c, "127.0.0.1", "1.3.6.1.2.1.2.2", null, d.port(), 100, 2000, 0, "v3", "ops", combo[0], authPw, combo[1], privPw, null);
                assertEquals(0, BigDecimal.valueOf(2).compareTo((BigDecimal) walk.get("row_count")), String.join("/", combo));
                n.snmpSet(c, "127.0.0.1", "1.3.6.1.2.1.1.6.0", "Room B", "string", null, d.port(), 2000, 0, "v3", "ops", combo[0], authPw, combo[1], privPw, null);
                assertEquals("Room B", d.objects.get("1.3.6.1.2.1.1.6.0"));
            }
        }
        SnmpV3.forgetEngines();
        try (Device d = new Device("ops", "sha", "auth-pass-1", "aes128", "priv-pass-1")) {
            SecureString wrong = new SecureString("wrong-pass-9", "x");
            assertEquals("SNMP.WRONG_DIGEST", assertThrows(ActionException.class, () -> n.snmpGet(c, "127.0.0.1", "1.3.6.1.2.1.1.5.0", null, d.port(), 2000, 0, "v3", "ops", "sha", wrong, "aes128", wrong, null)).code());
            assertEquals("SNMP.UNKNOWN_USER", assertThrows(ActionException.class, () -> n.snmpGet(c, "127.0.0.1", "1.3.6.1.2.1.1.5.0", null, d.port(), 2000, 0, "v3", "nobody", "sha",
                new SecureString("auth-pass-1", "a"), "aes128", new SecureString("priv-pass-1", "p"), null)).code());
        }
    }
}
