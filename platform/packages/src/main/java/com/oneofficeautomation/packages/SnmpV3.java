package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.NetworkPackage.Snmp;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;

import javax.crypto.Cipher;
import javax.crypto.Mac;
import javax.crypto.spec.IvParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.*;
import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * SNMP v3 with the user-based security model (RFC 3414, 3826, 7860): engine discovery, sign-in with HMAC-MD5,
 * HMAC-SHA, HMAC-SHA-256 or HMAC-SHA-512, and privacy with DES or AES-128. The same codec serves both ends, so the
 * tests can stand in for a device. No external library.
 */
final class SnmpV3 {
    private SnmpV3() {}

    static final int VERSION_3 = 3, REPORT = 0xA8;
    static final int FLAG_AUTH = 1, FLAG_PRIV = 2, FLAG_REPORTABLE = 4;
    private static final SecureRandom RANDOM = new SecureRandom();

    /** The user and protocols; passwords are bytes so they can come from the vault. */
    record Security(String user, String auth, byte[] authPassword, String priv, byte[] privPassword, String context) {
        Security {
            auth = auth == null || auth.isBlank() ? "none" : auth.strip().toLowerCase(Locale.ROOT);
            priv = priv == null || priv.isBlank() ? "none" : priv.strip().toLowerCase(Locale.ROOT);
            if (!Set.of("none", "md5", "sha", "sha256", "sha512").contains(auth)) throw new ActionException("SNMP.BAD_SECURITY", "Unknown sign-in protocol '" + auth + "'", "Use none, md5, sha, sha256 or sha512.");
            if (!Set.of("none", "des", "aes128").contains(priv)) throw new ActionException("SNMP.BAD_SECURITY", "Unknown privacy protocol '" + priv + "'", "Use none, des or aes128.");
            if (user == null || user.isBlank()) throw new ActionException("SNMP.BAD_SECURITY", "SNMP v3 needs the security user name");
            if (!auth.equals("none") && (authPassword == null || authPassword.length < 8)) throw new ActionException("SNMP.BAD_SECURITY", "The sign-in password needs at least 8 characters");
            if (!priv.equals("none")) {
                if (auth.equals("none")) throw new ActionException("SNMP.BAD_SECURITY", "Privacy needs a sign-in protocol too", "Choose md5, sha, sha256 or sha512 as well.");
                if (privPassword == null || privPassword.length < 8) throw new ActionException("SNMP.BAD_SECURITY", "The privacy password needs at least 8 characters");
            }
            if (context == null) context = "";
        }
        int flags() { return (auth.equals("none") ? 0 : FLAG_AUTH) | (priv.equals("none") ? 0 : FLAG_PRIV); }
    }

    /** One v3 message, either end. */
    record Msg(int msgId, int flags, byte[] engineId, int boots, int time, String user, byte[] privParams, String context,
               int pduType, int requestId, int errorStatus, int errorIndex, List<Snmp.VarBind> vars) {}

    // ---------- keys (RFC 3414 A.2, RFC 7860) ----------

    static String hashName(String auth) {
        return switch (auth) { case "md5" -> "MD5"; case "sha" -> "SHA-1"; case "sha256" -> "SHA-256"; case "sha512" -> "SHA-512"; default -> throw new IllegalArgumentException(auth); };
    }

    static int macLength(String auth) { return switch (auth) { case "md5", "sha" -> 12; case "sha256" -> 24; case "sha512" -> 48; default -> 0; }; }

    /** The password stretched to a key (one megabyte of the repeated password, hashed), then localised to the engine. */
    static byte[] localizedKey(String auth, byte[] password, byte[] engineId) {
        try {
            MessageDigest md = MessageDigest.getInstance(hashName(auth));
            byte[] buf = new byte[64];
            int idx = 0;
            for (int count = 0; count < 1_048_576; count += 64) {
                for (int i = 0; i < 64; i++) buf[i] = password[idx++ % password.length];
                md.update(buf);
            }
            byte[] ku = md.digest();
            md.update(ku); md.update(engineId); md.update(ku);
            return md.digest();
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static byte[] hmac(String auth, byte[] key, byte[] msg) {
        try {
            String alg = switch (auth) { case "md5" -> "HmacMD5"; case "sha" -> "HmacSHA1"; case "sha256" -> "HmacSHA256"; default -> "HmacSHA512"; };
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(key, alg));
            return Arrays.copyOf(m.doFinal(msg), macLength(auth));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    static byte[] crypt(boolean encrypt, String priv, byte[] key, int boots, int time, byte[] salt, byte[] data) {
        try {
            if (priv.equals("des")) {
                byte[] iv = new byte[8];
                for (int i = 0; i < 8; i++) iv[i] = (byte) (key[8 + i] ^ salt[i]);
                Cipher c = Cipher.getInstance("DES/CBC/NoPadding");
                c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOf(key, 8), "DES"), new IvParameterSpec(iv));
                if (!encrypt && data.length % 8 != 0) throw new ActionException("SNMP.DECRYPTION", "The encrypted answer has a bad length");
                return c.doFinal(encrypt ? Arrays.copyOf(data, (data.length + 7) / 8 * 8) : data);
            }
            byte[] iv = ByteBuffer.allocate(16).putInt(boots).putInt(time).put(salt).array();
            Cipher c = Cipher.getInstance("AES/CFB/NoPadding");
            c.init(encrypt ? Cipher.ENCRYPT_MODE : Cipher.DECRYPT_MODE, new SecretKeySpec(Arrays.copyOf(key, 16), "AES"), new IvParameterSpec(iv));
            return c.doFinal(data);
        } catch (GeneralSecurityException e) { throw new ActionException("SNMP.DECRYPTION", "Cannot decrypt the SNMP message: " + e.getMessage(), "Check the privacy protocol and password."); }
    }

    // ---------- the message ----------

    static byte[] pdu(int type, int reqId, int err, int idx, List<Snmp.VarBind> vars) {
        java.io.ByteArrayOutputStream vbs = new java.io.ByteArrayOutputStream();
        for (Snmp.VarBind vb : vars) vbs.writeBytes(Snmp.tlv(Snmp.SEQUENCE, Snmp.oid(vb.oid()), Snmp.value(vb.type(), vb.value())));
        return Snmp.tlv(type, Snmp.integer(reqId), Snmp.integer(err), Snmp.integer(idx), Snmp.tlv(Snmp.SEQUENCE, vbs.toByteArray()));
    }

    /** Encodes and, as the flags say, encrypts and signs. authKey and privKey are the localised keys (null without). */
    static byte[] encode(Msg m, String auth, byte[] authKey, String priv, byte[] privKey) {
        byte[] scoped = Snmp.tlv(Snmp.SEQUENCE, Snmp.tlv(Snmp.OCTETS, m.engineId()), Snmp.tlv(Snmp.OCTETS, m.context().getBytes(StandardCharsets.UTF_8)),
            pdu(m.pduType(), m.requestId(), m.errorStatus(), m.errorIndex(), m.vars()));
        boolean signed = (m.flags() & FLAG_AUTH) != 0, secret = (m.flags() & FLAG_PRIV) != 0;
        byte[] privParams = secret ? m.privParams() : new byte[0];
        byte[] data = secret ? Snmp.tlv(Snmp.OCTETS, crypt(true, priv, privKey, m.boots(), m.time(), privParams, scoped)) : scoped;
        byte[] zeros = new byte[signed ? macLength(auth) : 0];
        byte[] sec = Snmp.tlv(Snmp.SEQUENCE, Snmp.tlv(Snmp.OCTETS, m.engineId()), Snmp.integer(m.boots()), Snmp.integer(m.time()),
            Snmp.tlv(Snmp.OCTETS, m.user().getBytes(StandardCharsets.UTF_8)), Snmp.tlv(Snmp.OCTETS, zeros), Snmp.tlv(Snmp.OCTETS, privParams));
        byte[] whole = Snmp.tlv(Snmp.SEQUENCE, Snmp.integer(VERSION_3),
            Snmp.tlv(Snmp.SEQUENCE, Snmp.integer(m.msgId()), Snmp.integer(65507), Snmp.tlv(Snmp.OCTETS, new byte[]{(byte) m.flags()}), Snmp.integer(3)),
            Snmp.tlv(Snmp.OCTETS, sec), data);
        if (signed) {
            byte[] mac = hmac(auth, authKey, whole);
            int at = indexOf(whole, zeros, sec);
            System.arraycopy(mac, 0, whole, at, mac.length);
        }
        return whole;
    }

    /** Where the zeroed MAC field sits: inside the security parameters, which appear once in the message. */
    private static int indexOf(byte[] whole, byte[] zeros, byte[] sec) {
        int secAt = -1;
        outer:
        for (int i = 0; i + sec.length <= whole.length; i++) {
            for (int j = 0; j < sec.length; j++) if (whole[i + j] != sec[j]) continue outer;
            secAt = i; break;
        }
        Snmp.Reader r = new Snmp.Reader(whole, secAt, secAt + sec.length);
        Snmp.Reader s = r.inner(r.next(Snmp.SEQUENCE));
        s.next(Snmp.OCTETS); s.next(Snmp.INTEGER); s.next(Snmp.INTEGER); s.next(Snmp.OCTETS);
        return s.next(Snmp.OCTETS)[1];
    }

    /** What the decoder needs to check and open a message: the keys for the engine and user it names. */
    interface Keys { byte[][] forEngine(byte[] engineId, String user); }

    static Msg decode(byte[] buf, int length, String auth, String priv, Keys keys) {
        byte[] data = Arrays.copyOf(buf, length);
        Snmp.Reader top = new Snmp.Reader(data, 0, data.length);
        Snmp.Reader msg = top.inner(top.next(Snmp.SEQUENCE));
        int version = new BigInteger(msg.bytes(msg.next(Snmp.INTEGER))).intValue();
        if (version != VERSION_3) throw Snmp.bad("not an SNMP v3 message");
        Snmp.Reader g = msg.inner(msg.next(Snmp.SEQUENCE));
        int msgId = new BigInteger(g.bytes(g.next(Snmp.INTEGER))).intValue();
        g.next(Snmp.INTEGER);
        byte[] fl = g.bytes(g.next(Snmp.OCTETS));
        int flags = fl.length == 0 ? 0 : fl[0] & 0xFF;
        Snmp.Reader secR = msg.inner(msg.next(Snmp.OCTETS));
        Snmp.Reader s = secR.inner(secR.next(Snmp.SEQUENCE));
        byte[] engine = s.bytes(s.next(Snmp.OCTETS));
        int boots = new BigInteger(1, s.bytes(s.next(Snmp.INTEGER))).intValue(), time = new BigInteger(1, s.bytes(s.next(Snmp.INTEGER))).intValue();
        String user = new String(s.bytes(s.next(Snmp.OCTETS)), StandardCharsets.UTF_8);
        int[] macT = s.next(Snmp.OCTETS);
        byte[] privParams = s.bytes(s.next(Snmp.OCTETS));
        byte[][] k = (flags & (FLAG_AUTH | FLAG_PRIV)) != 0 ? keys.forEngine(engine, user) : new byte[2][];
        if ((flags & FLAG_AUTH) != 0) {
            byte[] got = Arrays.copyOfRange(data, macT[1], macT[2]);
            byte[] zeroed = data.clone();
            Arrays.fill(zeroed, macT[1], macT[2], (byte) 0);
            if (k[0] == null || !MessageDigest.isEqual(got, hmac(auth, k[0], zeroed)))
                throw new ActionException("SNMP.WRONG_DIGEST", "The SNMP message's signature does not match", "Check the sign-in protocol and password.");
        }
        Snmp.Reader scoped;
        if ((flags & FLAG_PRIV) != 0) {
            byte[] plain = crypt(false, priv, k[1], boots, time, privParams, msg.bytes(msg.next(Snmp.OCTETS)));
            Snmp.Reader p = new Snmp.Reader(plain, 0, plain.length);
            scoped = p.inner(p.next(Snmp.SEQUENCE));
        } else scoped = msg.inner(msg.next(Snmp.SEQUENCE));
        scoped.next(Snmp.OCTETS);
        String context = new String(scoped.bytes(scoped.next(Snmp.OCTETS)), StandardCharsets.UTF_8);
        int[] pduT = scoped.next(-1);
        Snmp.Reader pdu = scoped.inner(pduT);
        int reqId = new BigInteger(pdu.bytes(pdu.next(Snmp.INTEGER))).intValue();
        int err = new BigInteger(pdu.bytes(pdu.next(Snmp.INTEGER))).intValue();
        int idx = new BigInteger(pdu.bytes(pdu.next(Snmp.INTEGER))).intValue();
        Snmp.Reader list = pdu.inner(pdu.next(Snmp.SEQUENCE));
        List<Snmp.VarBind> vars = new ArrayList<>();
        while (list.more()) {
            Snmp.Reader vb = list.inner(list.next(Snmp.SEQUENCE));
            String oid = Snmp.decodeOid(vb.bytes(vb.next(Snmp.OID)));
            int[] v = vb.next(-1);
            vars.add(new Snmp.VarBind(oid, v[0], Snmp.readable(v[0], vb.bytes(v))));
        }
        return new Msg(msgId, flags, engine, boots, time, user, privParams, context, pduT[0], reqId, err, idx, vars);
    }

    // ---------- the client ----------

    /** A device's engine as discovered: its id, boots and clock, and when the clock was read. */
    record Engine(byte[] id, int boots, int time, long at) {
        int now() { return (int) Math.min(Integer.MAX_VALUE, time + (System.currentTimeMillis() - at) / 1000); }
    }

    private static final Map<String, Engine> ENGINES = new ConcurrentHashMap<>();

    // ---------- notifications: this computer is the authoritative engine ----------

    /** This computer's engine ID (RFC 3411 text format): 80 00 00 00 04, then "OOA-" and the computer name, at most 32 bytes. */
    static byte[] localEngineId() {
        String name;
        try { name = InetAddress.getLocalHost().getHostName(); } catch (IOException e) { name = "runner"; }
        byte[] text = ("OOA-" + name).getBytes(StandardCharsets.UTF_8);
        byte[] id = new byte[Math.min(32, 5 + text.length)];
        id[0] = (byte) 0x80; id[4] = 4;
        System.arraycopy(text, 0, id, 5, id.length - 5);
        return id;
    }

    /** An engine ID typed as hex (5 to 32 bytes, spaces and colons allowed). */
    static byte[] engineId(String hex) {
        String h = hex.replaceAll("[\\s:]", "").replaceFirst("^(0x|0X)", "");
        try {
            byte[] id = HexFormat.of().parseHex(h);
            if (id.length >= 5 && id.length <= 32) return id;
        } catch (IllegalArgumentException ignored) { }
        throw new ActionException("SNMP.BAD_ENGINE_ID", "The engine ID must be 5 to 32 bytes of hex, such as 800000000403414243", "Leave it empty to use this computer's own.");
    }

    /** Engine boots: the notification receiver only needs it to stay the same or grow, so 1 with time since the start. */
    private static final long STARTED = System.currentTimeMillis();

    /** Sends an SNMPv2-Trap as a v3 message from the engine {@code engineId}, signed and encrypted with keys localised to it. */
    static void trap(String host, int port, Security sec, byte[] engineId, List<Snmp.VarBind> vars) {
        byte[] authKey = sec.auth().equals("none") ? null : localizedKey(sec.auth(), sec.authPassword(), engineId);
        byte[] privKey = sec.priv().equals("none") ? null : localizedKey(sec.auth(), sec.privPassword(), engineId);
        byte[] salt = new byte[8];
        RANDOM.nextBytes(salt);
        int time = (int) Math.min(Integer.MAX_VALUE, (System.currentTimeMillis() - STARTED) / 1000);
        Msg m = new Msg(RANDOM.nextInt(Integer.MAX_VALUE), sec.flags(), engineId, 1, time, sec.user(), salt, sec.context(),
            Snmp.TRAP, RANDOM.nextInt(Integer.MAX_VALUE), 0, 0, vars);
        Snmp.send(host, port, encode(m, sec.auth(), authKey, sec.priv(), privKey));
    }
    private static final String USM = "1.3.6.1.6.3.15.1.1.";

    static Snmp.Message request(ActionContext ctx, String host, int port, Security sec, int pduType, List<Snmp.VarBind> vars, int timeoutMs, int retries) {
        InetAddress addr;
        try { addr = InetAddress.getByName(host); }
        catch (UnknownHostException e) { throw new ActionException("SNMP.UNKNOWN_HOST", "Unknown host '" + host + "'", "Check the host name and DNS."); }
        String key = addr.getHostAddress() + ":" + port;
        try (DatagramSocket s = new DatagramSocket()) {
            s.setSoTimeout(Math.max(100, timeoutMs));
            Engine e = ENGINES.get(key);
            if (e == null) { e = discover(ctx, s, addr, port, timeoutMs, retries); ENGINES.put(key, e); }
            byte[] authKey = sec.auth().equals("none") ? null : localizedKey(sec.auth(), sec.authPassword(), e.id());
            byte[] privKey = sec.priv().equals("none") ? null : localizedKey(sec.auth(), sec.privPassword(), e.id());
            for (int round = 0; round < 2; round++) {
                int msgId = RANDOM.nextInt(Integer.MAX_VALUE), reqId = RANDOM.nextInt(Integer.MAX_VALUE);
                byte[] salt = new byte[8];
                RANDOM.nextBytes(salt);
                if (sec.priv().equals("des")) ByteBuffer.wrap(salt).putInt(e.boots());
                Engine en = e;
                byte[] out = encode(new Msg(msgId, sec.flags() | FLAG_REPORTABLE, e.id(), e.boots(), e.now(), sec.user(), salt, sec.context(), pduType, reqId, 0, 0, vars),
                    sec.auth(), authKey, sec.priv(), privKey);
                Msg m = exchange(ctx, s, addr, port, out, msgId, timeoutMs, retries, sec, (id, u) -> new byte[][]{authKey, privKey});
                if (m.pduType() == REPORT) {
                    String why = m.vars().isEmpty() ? "" : m.vars().get(0).oid();
                    if (why.startsWith(USM + "2") && round == 0) { e = new Engine(en.id(), m.boots(), m.time(), System.currentTimeMillis()); ENGINES.put(key, e); continue; }
                    throw report(why);
                }
                if (m.errorStatus() != 0) {
                    String name = m.errorStatus() < Snmp.ERRORS.length ? Snmp.ERRORS[m.errorStatus()] : "error " + m.errorStatus();
                    throw new ActionException("SNMP.ERROR", "The SNMP agent answered " + name, "Check the OID, value and type, and that the user may read or write it.",
                        false, Map.of("error_status", BigDecimal.valueOf(m.errorStatus()), "error_index", BigDecimal.valueOf(m.errorIndex())));
                }
                return new Snmp.Message(VERSION_3, "", m.pduType(), m.requestId(), m.errorStatus(), m.errorIndex(), m.vars());
            }
            throw new ActionException("SNMP.NOT_IN_TIME_WINDOW", "The device's clock and this runner's disagree", "Check the device's SNMP engine.", true);
        } catch (IOException e) {
            throw new ActionException("SNMP.UNREACHABLE", "Cannot send to " + host + ":" + port + ": " + e.getMessage(), "Check the network and firewall.", true);
        }
    }

    static ActionException report(String oid) {
        if (oid.startsWith(USM + "3")) return new ActionException("SNMP.UNKNOWN_USER", "The device does not know this SNMP user", "Check the security user name.");
        if (oid.startsWith(USM + "5")) return new ActionException("SNMP.WRONG_DIGEST", "The device refused the sign-in", "Check the sign-in protocol and password.");
        if (oid.startsWith(USM + "6")) return new ActionException("SNMP.DECRYPTION", "The device could not decrypt the request", "Check the privacy protocol and password.");
        if (oid.startsWith(USM + "1")) return new ActionException("SNMP.UNSUPPORTED_SECURITY", "The device does not allow this security level for the user", "Check which sign-in and privacy the user has on the device.");
        return new ActionException("SNMP.REPORT", "The device answered with report " + oid, "Check the SNMP v3 settings.");
    }

    /** Engine discovery: an empty request the device answers with a report naming its engine id, boots and time. */
    static Engine discover(ActionContext ctx, DatagramSocket s, InetAddress addr, int port, int timeoutMs, int retries) throws IOException {
        int msgId = RANDOM.nextInt(Integer.MAX_VALUE);
        byte[] out = encode(new Msg(msgId, FLAG_REPORTABLE, new byte[0], 0, 0, "", new byte[0], "", Snmp.GET, RANDOM.nextInt(Integer.MAX_VALUE), 0, 0, List.of()),
            "none", null, "none", null);
        Msg m = exchange(ctx, s, addr, port, out, msgId, timeoutMs, retries, null, (id, u) -> new byte[2][]);
        if (m.engineId().length == 0) throw Snmp.bad("the device named no SNMP engine");
        return new Engine(m.engineId(), m.boots(), m.time(), System.currentTimeMillis());
    }

    private static Msg exchange(ActionContext ctx, DatagramSocket s, InetAddress addr, int port, byte[] out, int msgId, int timeoutMs, int retries, Security sec, Keys keys) throws IOException {
        byte[] buf = new byte[65535];
        for (int attempt = 0; attempt <= Math.max(0, retries); attempt++) {
            if (ctx != null) ctx.checkCancelled();
            s.send(new DatagramPacket(out, out.length, addr, port));
            long deadline = System.currentTimeMillis() + Math.max(100, timeoutMs);
            while (System.currentTimeMillis() < deadline) {
                DatagramPacket in = new DatagramPacket(buf, buf.length);
                try { s.receive(in); } catch (SocketTimeoutException e) { break; }
                Msg m;
                try { m = decode(in.getData(), in.getLength(), sec == null ? "none" : sec.auth(), sec == null ? "none" : sec.priv(), keys); }
                catch (ActionException e) {
                    if (e.code().equals("SNMP.WRONG_DIGEST") || e.code().equals("SNMP.DECRYPTION")) throw e;
                    continue;
                }
                if (m.msgId() == msgId) return m;
            }
        }
        throw new ActionException("SNMP.TIMEOUT", "No SNMP v3 answer from " + addr.getHostAddress() + ":" + port + " after " + (Math.max(0, retries) + 1) + " attempt(s)",
            "Check the host, the port (usually 161) and that the device has SNMP v3 on.", true);
    }

    /** The message id, read without checking or opening the message (an agent answers a bad message with a report carrying it). */
    static int msgIdOf(byte[] buf, int length) {
        Snmp.Reader top = new Snmp.Reader(buf, 0, length);
        Snmp.Reader msg = top.inner(top.next(Snmp.SEQUENCE));
        msg.next(Snmp.INTEGER);
        Snmp.Reader g = msg.inner(msg.next(Snmp.SEQUENCE));
        return new BigInteger(g.bytes(g.next(Snmp.INTEGER))).intValue();
    }

    /** Forgets the discovered engines (tests, and after a device was replaced). */
    static void forgetEngines() { ENGINES.clear(); }
}
