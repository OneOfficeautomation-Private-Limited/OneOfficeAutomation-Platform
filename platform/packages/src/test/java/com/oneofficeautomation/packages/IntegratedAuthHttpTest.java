package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Windows sign-in for REST (gap: NTLM / Kerberos / logged-in Windows user). A small server speaks NTLM: it answers the
 * negotiate message with a challenge and checks the NTLMv2 proof in the authenticate message against the password, so
 * the test proves the right password was used without a domain.
 */
class IntegratedAuthHttpTest {
    static final byte[] CHALLENGE = {1, 2, 3, 4, 5, 6, 7, 8};

    static ActionContext ctx() throws Exception {
        return new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-ntlm"), new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> { });
    }

    @Test void ntlmWithUserAndPasswordSignsIn() throws Exception {
        HttpServer srv = ntlmServer("S3cret-pw!");
        try {
            String url = "http://127.0.0.1:" + srv.getAddress().getPort() + "/whoami";
            RestPackage rest = new RestPackage();
            Map<String, Object> r = rest.request(ctx(), url, "POST", Map.of("q", "1"), Map.of("X-Trace", "t1"), Map.of("a", 1), null, null, "TEST\\alice",
                    new SecureString("S3cret-pw!", "test/ntlm/password"), null, 30, true, null, false, null, null, null, null, null, false, true, null, null, "user_password");
            assertEquals(new BigDecimal(200), r.get("status"));
            assertEquals("signed in as TEST\\alice; body {\"a\":1}; q=1; trace t1", r.get("body"));

            // a wrong password is refused by the server: HTTP 401 becomes REST.HTTP_ERROR
            ActionException e = assertThrows(ActionException.class, () -> rest.request(ctx(), url, "GET", null, null, null, null, null, "TEST\\alice",
                    new SecureString("wrong", "test/ntlm/password"), null, 30, true, null, false, null, null, null, null, null, false, true, null, null, "user_password"));
            assertEquals("REST.HTTP_ERROR", e.code());

            // a plain-text password is not used
            assertEquals("REST.NO_SECRET", assertThrows(ActionException.class, () -> rest.request(ctx(), url, "GET", null, null, null, null, null, "TEST\\alice",
                    "S3cret-pw!", null, 30, true, null, false, null, null, null, null, null, false, true, null, null, "user_password")).code());
            assertEquals("REST.NO_USER", assertThrows(ActionException.class, () -> rest.request(ctx(), url, "GET", null, null, null, null, null, null,
                    new SecureString("x", "test/ntlm/password"), null, 30, true, null, false, null, null, null, null, null, false, true, null, null, "user_password")).code());
            assertEquals("REST.BAD_AUTH", assertThrows(ActionException.class, () -> rest.request(ctx(), url, "GET", null, null, null, null, null, null,
                    null, null, 30, true, null, false, null, null, null, null, null, false, true, null, null, "kerberos")).code());

            // download with Windows sign-in
            Map<String, Object> d = rest.downloadFile(ctx(), url, "dl/out.txt", null, null, "TEST\\alice", new SecureString("S3cret-pw!", "test/ntlm/password"), null, true, 30, false,
                    null, null, null, null, null, "user_password");
            assertEquals("signed in as TEST\\alice; body ; q=; trace ", Files.readString(java.nio.file.Path.of((String) d.get("path"))));
        } finally { srv.stop(0); }
        if (!com.oneofficeautomation.packages.ProcessRunner.WINDOWS)
            assertEquals("REST.AUTH_UNSUPPORTED", assertThrows(ActionException.class, () -> new RestPackage().request(ctx(), "http://127.0.0.1:1/", "GET", null, null,
                    null, null, null, null, null, null, 5, true, null, false, null, null, null, null, null, false, true, null, null, "current_user")).code());
    }

    // ------------------------------------------------------------------ an NTLM server (NTLMv2 check)

    static HttpServer ntlmServer(String password) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            byte[] in = ex.getRequestBody().readAllBytes();
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            int status; byte[] out = new byte[0];
            if (auth == null || !auth.startsWith("NTLM ")) {
                ex.getResponseHeaders().add("WWW-Authenticate", "NTLM");
                status = 401;
            } else {
                byte[] msg = Base64.getDecoder().decode(auth.substring(5).strip());
                int type = le32(msg, 8);
                if (type == 1) {
                    ex.getResponseHeaders().add("WWW-Authenticate", "NTLM " + Base64.getEncoder().encodeToString(type2()));
                    status = 401;
                } else {
                    String user = field(msg, 36), domain = field(msg, 28);
                    byte[] nt = buf(msg, 20);
                    if (verify(nt, user, domain, password)) {
                        String q = Optional.ofNullable(ex.getRequestURI().getQuery()).orElse("");
                        out = ("signed in as " + domain + "\\" + user + "; body " + new String(in, StandardCharsets.UTF_8) + "; " + (q.isEmpty() ? "q=" : q)
                                + "; trace " + Optional.ofNullable(ex.getRequestHeaders().getFirst("X-Trace")).orElse("")).getBytes(StandardCharsets.UTF_8);
                        status = 200;
                    } else { ex.getResponseHeaders().add("WWW-Authenticate", "NTLM"); status = 401; }
                }
            }
            ex.sendResponseHeaders(status, out.length == 0 ? -1 : out.length);
            if (out.length > 0) ex.getResponseBody().write(out);
            ex.close();
        });
        s.start();
        return s;
    }

    static byte[] type2() {
        byte[] info = avPairs();
        int flags = 0x00000001 | 0x00000004 | 0x00000200 | 0x00008000 | 0x00010000 | 0x00080000 | 0x00800000 | 0x20000000 | 0x80000000;
        byte[] target = "TEST".getBytes(StandardCharsets.UTF_16LE);
        ByteBuffer b = ByteBuffer.allocate(48 + target.length + info.length).order(ByteOrder.LITTLE_ENDIAN);
        b.put("NTLMSSP\0".getBytes(StandardCharsets.US_ASCII)).putInt(2);
        b.putShort((short) target.length).putShort((short) target.length).putInt(48);
        b.putInt(flags).put(CHALLENGE).putLong(0);
        b.putShort((short) info.length).putShort((short) info.length).putInt(48 + target.length);
        b.put(target).put(info);
        return b.array();
    }

    static byte[] avPairs() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        av(o, 2, "TEST"); av(o, 1, "SERVER"); av(o, 0, "");
        return o.toByteArray();
    }

    static void av(ByteArrayOutputStream o, int id, String v) {
        byte[] d = v.getBytes(StandardCharsets.UTF_16LE);
        o.write(id & 0xff); o.write(id >> 8); o.write(d.length & 0xff); o.write(d.length >> 8); o.writeBytes(d);
    }

    static boolean verify(byte[] nt, String user, String domain, String password) {
        if (nt.length < 24) return false;
        try {
            byte[] ntowf = hmac(md4(password.getBytes(StandardCharsets.UTF_16LE)), (user.toUpperCase(Locale.ROOT) + domain).getBytes(StandardCharsets.UTF_16LE));
            byte[] blob = Arrays.copyOfRange(nt, 16, nt.length);
            byte[] data = new byte[8 + blob.length];
            System.arraycopy(CHALLENGE, 0, data, 0, 8); System.arraycopy(blob, 0, data, 8, blob.length);
            return Arrays.equals(Arrays.copyOf(hmac(ntowf, data), 16), Arrays.copyOf(nt, 16));
        } catch (Exception e) { return false; }
    }

    static byte[] hmac(byte[] key, byte[] data) throws Exception {
        Mac m = Mac.getInstance("HmacMD5"); m.init(new SecretKeySpec(key, "HmacMD5")); return m.doFinal(data);
    }

    static int le32(byte[] b, int at) { return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8 | (b[at + 2] & 0xff) << 16 | (b[at + 3] & 0xff) << 24; }
    static int le16(byte[] b, int at) { return (b[at] & 0xff) | (b[at + 1] & 0xff) << 8; }
    static byte[] buf(byte[] m, int at) { int len = le16(m, at), off = le32(m, at + 4); return Arrays.copyOfRange(m, off, off + len); }
    static String field(byte[] m, int at) { return new String(buf(m, at), StandardCharsets.UTF_16LE); }

    /** MD4 (RFC 1320); Java does not offer it as a MessageDigest. */
    static byte[] md4(byte[] msg) {
        int[] s = {0x67452301, 0xefcdab89, 0x98badcfe, 0x10325476};
        long bits = (long) msg.length * 8;
        int padLen = ((56 - (msg.length + 1) % 64) + 64) % 64;
        byte[] m = Arrays.copyOf(msg, msg.length + 1 + padLen + 8);
        m[msg.length] = (byte) 0x80;
        for (int i = 0; i < 8; i++) m[m.length - 8 + i] = (byte) (bits >>> (8 * i));
        for (int off = 0; off < m.length; off += 64) {
            int[] x = new int[16];
            for (int i = 0; i < 16; i++) x[i] = le32(m, off + 4 * i);
            int a = s[0], b = s[1], c = s[2], d = s[3];
            int[] r1 = {3, 7, 11, 19};
            for (int i = 0; i < 16; i++) { int t = a + ((b & c) | (~b & d)) + x[i]; a = d; d = c; c = b; b = Integer.rotateLeft(t, r1[i % 4]); }
            int[] r2 = {3, 5, 9, 13}, k2 = {0, 4, 8, 12, 1, 5, 9, 13, 2, 6, 10, 14, 3, 7, 11, 15};
            for (int i = 0; i < 16; i++) { int t = a + ((b & c) | (b & d) | (c & d)) + x[k2[i]] + 0x5a827999; a = d; d = c; c = b; b = Integer.rotateLeft(t, r2[i % 4]); }
            int[] r3 = {3, 9, 11, 15}, k3 = {0, 8, 4, 12, 2, 10, 6, 14, 1, 9, 5, 13, 3, 11, 7, 15};
            for (int i = 0; i < 16; i++) { int t = a + (b ^ c ^ d) + x[k3[i]] + 0x6ed9eba1; a = d; d = c; c = b; b = Integer.rotateLeft(t, r3[i % 4]); }
            s[0] += a; s[1] += b; s[2] += c; s[3] += d;
        }
        ByteBuffer out = ByteBuffer.allocate(16).order(ByteOrder.LITTLE_ENDIAN);
        for (int v : s) out.putInt(v);
        return out.array();
    }

    @Test void md4KnownAnswers() {
        assertEquals("31d6cfe0d16ae931b73c59d7e0c089c0", HexFormat.of().formatHex(md4(new byte[0])));
        assertEquals("a448017aaf21d8525fc10ae87aa6729d", HexFormat.of().formatHex(md4("abc".getBytes(StandardCharsets.US_ASCII))));
    }
}
