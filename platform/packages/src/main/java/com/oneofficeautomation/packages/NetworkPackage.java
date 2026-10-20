package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;

@OoaPackage(id = "ooa.network", name = "Network", version = "1.0.0", description = "Check whether hosts and ports are reachable, and read or change devices over SNMP v2c.")
public final class NetworkPackage {

    @Action(id = "ping", name = "Ping", category = "Network", idempotent = true, capabilities = "network", description = "Check whether a host answers, and how fast.")
    @Output(name = "reachable", type = OoaType.BOOLEAN, description = "True when the host answered.")
    @Output(name = "response_ms", type = OoaType.NUMBER, description = "Round-trip time in milliseconds.")
    public Map<String, Object> ping(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Host name or IP address.") String host,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "Timeout in milliseconds.") int timeout) {
        long t0 = System.nanoTime();
        boolean ok;
        try { ok = InetAddress.getByName(host).isReachable(timeout); }
        catch (UnknownHostException e) { throw new ActionException("NETWORK.UNKNOWN_HOST", "Unknown host '" + host + "'", "Check the host name and DNS."); }
        catch (IOException e) { ok = false; }
        return Out.of("reachable", ok, "response_ms", BigDecimal.valueOf((System.nanoTime() - t0) / 1_000_000));
    }

    @Action(id = "check_port", name = "Check port", category = "Network", idempotent = true, capabilities = "network",
            description = "Check whether a TCP port accepts connections, e.g. a database on port 1433.")
    @Output(name = "open", type = OoaType.BOOLEAN, description = "True when the port accepted a connection.")
    public Map<String, Object> checkPort(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Host name or IP address.") String host,
            @Input(name = "port", type = OoaType.NUMBER, description = "TCP port.") int port,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "Timeout in milliseconds.") int timeout) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, port), timeout); return Map.of("open", true); }
        catch (IOException e) { return Map.of("open", false); }
    }

    @Condition(id = "host_reachable", name = "Host is reachable", description = "True when the host answers a ping within the timeout.")
    public boolean reachable(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Host name or IP address.") String host,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "Timeout in milliseconds.") int timeout) {
        try { return InetAddress.getByName(host).isReachable(timeout); } catch (IOException e) { return false; }
    }

    @Condition(id = "port_open", name = "Port is open", description = "True when a TCP port accepts connections.")
    public boolean portOpen(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Host name or IP address.") String host,
            @Input(name = "port", type = OoaType.NUMBER, description = "TCP port.") int port,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = "Timeout in milliseconds.") int timeout) {
        try (Socket s = new Socket()) { s.connect(new InetSocketAddress(host, port), timeout); return true; } catch (IOException e) { return false; }
    }

    // ---------- SNMP v2c ----------

    /** Minimal SNMP v2c message codec (BER) and UDP client: GET, GETNEXT, SET and SNMPv2-Trap. No external library. */
    static final class Snmp {
        private Snmp() {}
        static final int GET = 0xA0, GETNEXT = 0xA1, RESPONSE = 0xA2, SET = 0xA3, TRAP = 0xA7;
        static final int INTEGER = 0x02, OCTETS = 0x04, NULL = 0x05, OID = 0x06, SEQUENCE = 0x30,
                IPADDR = 0x40, COUNTER32 = 0x41, GAUGE32 = 0x42, TIMETICKS = 0x43, OPAQUE = 0x44, COUNTER64 = 0x46,
                NO_SUCH_OBJECT = 0x80, NO_SUCH_INSTANCE = 0x81, END_OF_MIB = 0x82;
        static final int VERSION_2C = 1;
        static final String SYS_UPTIME = "1.3.6.1.2.1.1.3.0", TRAP_OID = "1.3.6.1.6.3.1.1.4.1.0";
        static final String[] ERRORS = {"noError", "tooBig", "noSuchName", "badValue", "readOnly", "genErr", "noAccess", "wrongType", "wrongLength",
                "wrongEncoding", "wrongValue", "noCreation", "inconsistentValue", "resourceUnavailable", "commitFailed", "undoFailed",
                "authorizationError", "notWritable", "inconsistentName"};

        /** value: BigDecimal for numbers, String for text, OIDs and IP addresses, null for NULL and the exception types. */
        record VarBind(String oid, int type, Object value) {}
        record Message(int version, String community, int pduType, int requestId, int errorStatus, int errorIndex, List<VarBind> vars) {}

        // ----- encoding -----
        static byte[] length(int n) {
            if (n < 0x80) return new byte[]{(byte) n};
            if (n < 0x100) return new byte[]{(byte) 0x81, (byte) n};
            if (n < 0x10000) return new byte[]{(byte) 0x82, (byte) (n >> 8), (byte) n};
            return new byte[]{(byte) 0x83, (byte) (n >> 16), (byte) (n >> 8), (byte) n};
        }

        static byte[] tlv(int tag, byte[]... parts) {
            java.io.ByteArrayOutputStream body = new java.io.ByteArrayOutputStream();
            for (byte[] p : parts) body.writeBytes(p);
            byte[] c = body.toByteArray();
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            out.write(tag);
            out.writeBytes(length(c.length));
            out.writeBytes(c);
            return out.toByteArray();
        }

        static long[] parseOid(String oid) {
            String o = oid == null ? "" : oid.strip();
            if (o.startsWith(".")) o = o.substring(1);
            if (!o.matches("\\d+(\\.\\d+)+")) throw new ActionException("SNMP.BAD_OID", "Invalid OID '" + oid + "'", "Use numbers separated by dots, e.g. 1.3.6.1.2.1.1.1.0.");
            String[] p = o.split("\\.");
            long[] arcs = new long[p.length];
            for (int i = 0; i < p.length; i++) {
                try { arcs[i] = Long.parseLong(p[i]); } catch (NumberFormatException e) { arcs[i] = -1; }
                if (arcs[i] < 0 || arcs[i] > 0xFFFFFFFFL) throw new ActionException("SNMP.BAD_OID", "OID part " + p[i] + " is out of range in '" + oid + "'", "Each part must be between 0 and 4294967295.");
            }
            if (arcs[0] > 2 || (arcs[0] < 2 && arcs[1] >= 40)) throw new ActionException("SNMP.BAD_OID", "Invalid OID '" + oid + "'", "OIDs start with 0, 1 or 2, e.g. 1.3.6.1...");
            return arcs;
        }

        static byte[] oid(String dotted) {
            long[] a = parseOid(dotted);
            java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
            base128(out, a[0] * 40 + a[1]);
            for (int i = 2; i < a.length; i++) base128(out, a[i]);
            return tlv(OID, out.toByteArray());
        }

        private static void base128(java.io.ByteArrayOutputStream out, long v) {
            int groups = 1;
            for (long t = v >>> 7; t != 0; t >>>= 7) groups++;
            for (int g = groups - 1; g >= 0; g--) out.write((int) ((v >>> (7 * g)) & 0x7F) | (g > 0 ? 0x80 : 0));
        }

        static byte[] integer(long v) { return tlv(INTEGER, BigInteger.valueOf(v).toByteArray()); }

        static byte[] value(int type, Object v) {
            return switch (type) {
                case INTEGER -> tlv(INTEGER, new BigDecimal(String.valueOf(v).strip()).toBigIntegerExact().toByteArray());
                case OCTETS -> tlv(OCTETS, v instanceof byte[] b ? b : String.valueOf(v).getBytes(StandardCharsets.UTF_8));
                case NULL, NO_SUCH_OBJECT, NO_SUCH_INSTANCE, END_OF_MIB -> tlv(type, new byte[0]);
                case OID -> oid(String.valueOf(v));
                case IPADDR -> {
                    String ip = String.valueOf(v).strip();
                    if (!ip.matches("\\d{1,3}(\\.\\d{1,3}){3}")) throw new ActionException("SNMP.BAD_VALUE", "Invalid IPv4 address '" + ip + "'", "Use the form 192.168.1.10.");
                    byte[] b = new byte[4];
                    String[] p = ip.split("\\.");
                    for (int i = 0; i < 4; i++) { int n = Integer.parseInt(p[i]); if (n > 255) throw new ActionException("SNMP.BAD_VALUE", "Invalid IPv4 address '" + ip + "'"); b[i] = (byte) n; }
                    yield tlv(IPADDR, b);
                }
                case COUNTER32, GAUGE32, TIMETICKS, COUNTER64 -> {
                    BigInteger n = new BigDecimal(String.valueOf(v).strip()).toBigIntegerExact();
                    BigInteger max = type == COUNTER64 ? BigInteger.ONE.shiftLeft(64) : BigInteger.ONE.shiftLeft(32);
                    if (n.signum() < 0 || n.compareTo(max) >= 0) throw new ActionException("SNMP.BAD_VALUE", "Value " + n + " is out of range for " + typeName(type));
                    yield tlv(type, n.toByteArray());
                }
                default -> throw new ActionException("SNMP.BAD_TYPE", "Unsupported value type " + typeName(type));
            };
        }

        static byte[] encode(Message m) {
            java.io.ByteArrayOutputStream vbs = new java.io.ByteArrayOutputStream();
            for (VarBind vb : m.vars()) vbs.writeBytes(tlv(SEQUENCE, oid(vb.oid()), value(vb.type(), vb.value())));
            byte[] pdu = tlv(m.pduType(), integer(m.requestId()), integer(m.errorStatus()), integer(m.errorIndex()), tlv(SEQUENCE, vbs.toByteArray()));
            return tlv(SEQUENCE, integer(m.version()), tlv(OCTETS, m.community().getBytes(StandardCharsets.UTF_8)), pdu);
        }

        // ----- decoding -----
        static final class Reader {
            final byte[] b; int pos; final int end;
            Reader(byte[] b, int pos, int end) { this.b = b; this.pos = pos; this.end = end; }
            boolean more() { return pos < end; }
            int tag() { need(1); return b[pos++] & 0xFF; }
            int len() {
                need(1);
                int first = b[pos++] & 0xFF;
                if (first < 0x80) return first;
                int n = first & 0x7F;
                if (n == 0 || n > 3) throw bad("unsupported length form");
                need(n);
                int v = 0;
                for (int i = 0; i < n; i++) v = (v << 8) | (b[pos++] & 0xFF);
                return v;
            }
            /** Reads one TLV of the expected tag (or any tag when expected is -1) and returns {tag, start, end}. */
            int[] next(int expected) {
                int t = tag(), l = len();
                need(l);
                if (expected >= 0 && t != expected) throw bad("expected tag 0x" + Integer.toHexString(expected) + " but found 0x" + Integer.toHexString(t));
                int[] r = {t, pos, pos + l};
                pos += l;
                return r;
            }
            Reader inner(int[] t) { return new Reader(b, t[1], t[2]); }
            byte[] bytes(int[] t) { return Arrays.copyOfRange(b, t[1], t[2]); }
            void need(int n) { if (pos + n > end) throw bad("message is truncated"); }
        }

        static ActionException bad(String why) { return new ActionException("SNMP.BAD_RESPONSE", "Cannot read the SNMP message: " + why, "Check the device speaks SNMP v2c on this port."); }

        static Message decode(byte[] data, int length) {
            Reader top = new Reader(data, 0, length);
            Reader msg = top.inner(top.next(SEQUENCE));
            int version = new BigInteger(msg.bytes(msg.next(INTEGER))).intValue();
            String community = new String(msg.bytes(msg.next(OCTETS)), StandardCharsets.UTF_8);
            int[] pduT = msg.next(-1);
            Reader pdu = msg.inner(pduT);
            int reqId = new BigInteger(pdu.bytes(pdu.next(INTEGER))).intValue();
            int err = new BigInteger(pdu.bytes(pdu.next(INTEGER))).intValue();
            int idx = new BigInteger(pdu.bytes(pdu.next(INTEGER))).intValue();
            Reader list = pdu.inner(pdu.next(SEQUENCE));
            List<VarBind> vars = new ArrayList<>();
            while (list.more()) {
                Reader vb = list.inner(list.next(SEQUENCE));
                String oid = decodeOid(vb.bytes(vb.next(OID)));
                int[] v = vb.next(-1);
                vars.add(new VarBind(oid, v[0], readable(v[0], vb.bytes(v))));
            }
            return new Message(version, community, pduT[0], reqId, err, idx, vars);
        }

        static String decodeOid(byte[] c) {
            if (c.length == 0) throw bad("empty OID");
            List<Long> arcs = new ArrayList<>();
            long v = 0;
            for (byte x : c) {
                v = (v << 7) | (x & 0x7F);
                if ((x & 0x80) == 0) { arcs.add(v); v = 0; }
            }
            long first = arcs.get(0);
            StringBuilder sb = new StringBuilder();
            sb.append(first < 80 ? first / 40 : 2).append('.').append(first < 80 ? first % 40 : first - 80);
            for (int i = 1; i < arcs.size(); i++) sb.append('.').append(arcs.get(i));
            return sb.toString();
        }

        static Object readable(int type, byte[] c) {
            return switch (type) {
                case INTEGER -> c.length == 0 ? BigDecimal.ZERO : new BigDecimal(new BigInteger(c));
                case COUNTER32, GAUGE32, TIMETICKS, COUNTER64 -> c.length == 0 ? BigDecimal.ZERO : new BigDecimal(new BigInteger(1, c));
                case OCTETS, OPAQUE -> text(c);
                case OID -> decodeOid(c);
                case IPADDR -> c.length == 4 ? (c[0] & 0xFF) + "." + (c[1] & 0xFF) + "." + (c[2] & 0xFF) + "." + (c[3] & 0xFF) : hex(c);
                default -> null;
            };
        }

        static String text(byte[] c) {
            String s = new String(c, StandardCharsets.UTF_8);
            boolean printable = !s.contains("�") && s.chars().allMatch(ch -> ch >= 0x20 || ch == '\t' || ch == '\r' || ch == '\n');
            return printable ? s : hex(c);
        }

        static String hex(byte[] c) {
            StringJoiner j = new StringJoiner(":");
            for (byte x : c) j.add(String.format("%02x", x & 0xFF));
            return j.toString();
        }

        static String typeName(int t) {
            return switch (t) {
                case INTEGER -> "integer"; case OCTETS -> "string"; case NULL -> "null"; case OID -> "oid"; case IPADDR -> "ip_address";
                case COUNTER32 -> "counter32"; case GAUGE32 -> "gauge32"; case TIMETICKS -> "timeticks"; case OPAQUE -> "opaque"; case COUNTER64 -> "counter64";
                case NO_SUCH_OBJECT -> "no_such_object"; case NO_SUCH_INSTANCE -> "no_such_instance"; case END_OF_MIB -> "end_of_mib_view";
                default -> "0x" + Integer.toHexString(t);
            };
        }

        static int typeCode(String name) {
            return switch (name == null ? "string" : name.toLowerCase(Locale.ROOT)) {
                case "integer" -> INTEGER; case "string" -> OCTETS; case "oid" -> OID; case "ip_address" -> IPADDR;
                case "counter32" -> COUNTER32; case "gauge32" -> GAUGE32; case "timeticks" -> TIMETICKS; case "counter64" -> COUNTER64;
                default -> throw new ActionException("SNMP.BAD_TYPE", "Unknown value type '" + name + "'", "Use integer, string, oid, ip_address, counter32, gauge32, timeticks or counter64.");
            };
        }

        private static final java.security.SecureRandom IDS = new java.security.SecureRandom();

        /** Sends one request and waits for the matching response, retrying on timeout. */
        static Message request(ActionContext ctx, String host, int port, String community, int pduType, List<VarBind> vars, int timeoutMs, int retries) {
            int reqId = IDS.nextInt(Integer.MAX_VALUE);
            byte[] out = encode(new Message(VERSION_2C, community, pduType, reqId, 0, 0, vars));
            InetAddress addr;
            try { addr = InetAddress.getByName(host); }
            catch (UnknownHostException e) { throw new ActionException("SNMP.UNKNOWN_HOST", "Unknown host '" + host + "'", "Check the host name and DNS."); }
            try (DatagramSocket s = new DatagramSocket()) {
                s.setSoTimeout(Math.max(100, timeoutMs));
                byte[] buf = new byte[65535];
                for (int attempt = 0; attempt <= Math.max(0, retries); attempt++) {
                    if (ctx != null) ctx.checkCancelled();
                    s.send(new DatagramPacket(out, out.length, addr, port));
                    long deadline = System.currentTimeMillis() + Math.max(100, timeoutMs);
                    while (System.currentTimeMillis() < deadline) {
                        DatagramPacket in = new DatagramPacket(buf, buf.length);
                        try { s.receive(in); } catch (SocketTimeoutException e) { break; }
                        Message m;
                        try { m = decode(in.getData(), in.getLength()); } catch (ActionException e) { continue; }
                        if (m.pduType() == RESPONSE && m.requestId() == reqId) {
                            if (m.errorStatus() != 0) {
                                String name = m.errorStatus() < ERRORS.length ? ERRORS[m.errorStatus()] : "error " + m.errorStatus();
                                String where = m.errorIndex() > 0 && m.errorIndex() <= vars.size() ? " for " + vars.get(m.errorIndex() - 1).oid() : "";
                                throw new ActionException("SNMP.ERROR", "The SNMP agent answered " + name + where,
                                        m.errorStatus() == 4 || m.errorStatus() == 6 || m.errorStatus() == 17 || m.errorStatus() == 16
                                                ? "Use a community with write access, and check the object is writable." : "Check the OID, value and type.",
                                        false, Map.of("error_status", BigDecimal.valueOf(m.errorStatus()), "error_index", BigDecimal.valueOf(m.errorIndex())));
                            }
                            return m;
                        }
                    }
                }
            } catch (IOException e) {
                throw new ActionException("SNMP.UNREACHABLE", "Cannot send to " + host + ":" + port + ": " + e.getMessage(), "Check the network and firewall.", true);
            }
            throw new ActionException("SNMP.TIMEOUT", "No SNMP answer from " + host + ":" + port + " after " + (Math.max(0, retries) + 1) + " attempt(s)",
                    "Check the host, the port (usually 161) and the community; devices silently ignore a wrong community.", true);
        }

        static void sendTrap(String host, int port, String community, long uptimeTicks, String trapOid, List<VarBind> extra) {
            List<VarBind> vars = new ArrayList<>();
            vars.add(new VarBind(SYS_UPTIME, TIMETICKS, BigDecimal.valueOf(uptimeTicks)));
            vars.add(new VarBind(TRAP_OID, OID, trapOid));
            vars.addAll(extra);
            byte[] out = encode(new Message(VERSION_2C, community, TRAP, IDS.nextInt(Integer.MAX_VALUE), 0, 0, vars));
            try (DatagramSocket s = new DatagramSocket()) { s.send(new DatagramPacket(out, out.length, InetAddress.getByName(host), port)); }
            catch (UnknownHostException e) { throw new ActionException("SNMP.UNKNOWN_HOST", "Unknown host '" + host + "'", "Check the host name and DNS."); }
            catch (IOException e) { throw new ActionException("SNMP.UNREACHABLE", "Cannot send the trap to " + host + ":" + port + ": " + e.getMessage(), "Check the network and firewall.", true); }
        }
    }

    private static String community(Object c) {
        if (c == null) return "public";
        if (c instanceof SecureString s) return s.reveal();
        String t = com.oneofficeautomation.runner.Values.text(c);
        return t.isEmpty() ? "public" : t;
    }

    private static Map<String, Object> row(Snmp.VarBind vb) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("oid", vb.oid()); m.put("type", Snmp.typeName(vb.type())); m.put("value", vb.value());
        return m;
    }

    private static final String D_HOST = "Device host name or IP address.";
    private static final String D_COMMUNITY = "SNMP v2c community (text or a Credential); public when empty.";
    private static final String D_TIMEOUT = "How long to wait for each answer, in milliseconds.";
    private static final String D_RETRIES = "Extra attempts after a timeout.";

    @Action(id = "snmp_get", name = "SNMP get", category = "SNMP", idempotent = true, timeoutSeconds = 120, capabilities = "network",
            description = "Read one or more values from a device with SNMP v2c.")
    @Output(name = "values", type = OoaType.DICTIONARY, description = "OID to value; empty for objects the device does not have.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: oid, type, value.")
    public Map<String, Object> snmpGet(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = D_HOST) String host,
            @Input(name = "oids", example = "1.3.6.1.2.1.1.5.0", description = "One OID, several separated by commas, or a List of OIDs.") Object oids,
            @Input(name = "community", required = false, defaultValue = "\"public\"", description = D_COMMUNITY) Object community,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "161", description = "UDP port of the SNMP agent.") int port,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = D_TIMEOUT) int timeout,
            @Input(name = "retries", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_RETRIES) int retries) {
        List<String> list = HttpJson.addressList(oids);
        if (list.isEmpty()) throw new ActionException("SNMP.BAD_OID", "Give at least one OID", "For example 1.3.6.1.2.1.1.5.0 (device name).");
        List<Snmp.VarBind> vars = new ArrayList<>();
        for (String o : list) { Snmp.parseOid(o); vars.add(new Snmp.VarBind(o.startsWith(".") ? o.substring(1) : o, Snmp.NULL, null)); }
        Snmp.Message m = Snmp.request(ctx, host, port, community(community), Snmp.GET, vars, timeout, retries);
        Map<String, Object> values = new LinkedHashMap<>();
        List<Object> rows = new ArrayList<>();
        for (Snmp.VarBind vb : m.vars()) { values.put(vb.oid(), vb.value()); rows.add(new ArrayList<>(row(vb).values())); }
        return Out.of("values", values, "table", com.oneofficeautomation.runner.Values.table(List.of("oid", "type", "value"), rows));
    }

    @Action(id = "snmp_get_next", name = "SNMP get next", category = "SNMP", idempotent = true, timeoutSeconds = 120, capabilities = "network",
            description = "Read the object that follows an OID in the device's tree (SNMP v2c GETNEXT).")
    @Output(name = "oid", type = OoaType.STRING, description = "OID of the next object.")
    @Output(name = "type", type = OoaType.STRING, description = "Value type, e.g. integer, string, end_of_mib_view.")
    @Output(name = "value", description = "Value of the next object.")
    public Map<String, Object> snmpGetNext(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = D_HOST) String host,
            @Input(name = "oid", type = OoaType.STRING, description = "Starting OID.") String oid,
            @Input(name = "community", required = false, defaultValue = "\"public\"", description = D_COMMUNITY) Object community,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "161", description = "UDP port of the SNMP agent.") int port,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = D_TIMEOUT) int timeout,
            @Input(name = "retries", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_RETRIES) int retries) {
        Snmp.parseOid(oid);
        Snmp.Message m = Snmp.request(ctx, host, port, community(community), Snmp.GETNEXT, List.of(new Snmp.VarBind(oid, Snmp.NULL, null)), timeout, retries);
        if (m.vars().isEmpty()) throw Snmp.bad("the answer has no values");
        return row(m.vars().get(0));
    }

    @Action(id = "snmp_walk", name = "SNMP walk", category = "SNMP", idempotent = true, timeoutSeconds = 600, capabilities = "network",
            description = "Read every object under an OID, e.g. a whole interface table, with repeated GETNEXT requests.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: oid, type, value.")
    @Output(name = "row_count", type = OoaType.NUMBER, description = "Number of objects read.")
    public Map<String, Object> snmpWalk(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = D_HOST) String host,
            @Input(name = "oid", type = OoaType.STRING, example = "1.3.6.1.2.1.2.2", description = "Root OID of the subtree to read.") String oid,
            @Input(name = "community", required = false, defaultValue = "\"public\"", description = D_COMMUNITY) Object community,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "161", description = "UDP port of the SNMP agent.") int port,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "10000", description = "Safety limit on objects read.") int maxRows,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = D_TIMEOUT) int timeout,
            @Input(name = "retries", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_RETRIES) int retries) {
        Snmp.parseOid(oid);
        String root = oid.strip().startsWith(".") ? oid.strip().substring(1) : oid.strip();
        String c = community(community);
        List<Object> rows = new ArrayList<>();
        String current = root;
        while (rows.size() < Math.max(1, maxRows)) {
            ctx.checkCancelled();
            Snmp.Message m = Snmp.request(ctx, host, port, c, Snmp.GETNEXT, List.of(new Snmp.VarBind(current, Snmp.NULL, null)), timeout, retries);
            if (m.vars().isEmpty()) break;
            Snmp.VarBind vb = m.vars().get(0);
            if (vb.type() == Snmp.END_OF_MIB || !(vb.oid().equals(root) || vb.oid().startsWith(root + ".")) || vb.oid().equals(current)) break;
            rows.add(new ArrayList<>(row(vb).values()));
            current = vb.oid();
        }
        return Out.of("table", com.oneofficeautomation.runner.Values.table(List.of("oid", "type", "value"), rows), "row_count", BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "snmp_set", name = "SNMP set", category = "SNMP", timeoutSeconds = 120, capabilities = "network",
            description = "Change one value on a device with SNMP v2c; the community must have write access.")
    @Output(name = "value", description = "Value the device reports after the change.")
    public Map<String, Object> snmpSet(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = D_HOST) String host,
            @Input(name = "oid", type = OoaType.STRING, example = "1.3.6.1.2.1.1.6.0", description = "OID of the object to change.") String oid,
            @Input(name = "value", description = "New value.") Object value,
            @Input(name = "value_type", type = OoaType.STRING, required = false, defaultValue = "\"string\"",
                    enumValues = {"string", "integer", "oid", "ip_address", "counter32", "gauge32", "timeticks", "counter64"}, description = "SNMP type of the new value.") String type,
            @Input(name = "community", required = false, defaultValue = "\"private\"", description = "SNMP v2c community with write access (text or a Credential).") Object community,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "161", description = "UDP port of the SNMP agent.") int port,
            @Input(name = "timeout_ms", type = OoaType.NUMBER, required = false, defaultValue = "3000", description = D_TIMEOUT) int timeout,
            @Input(name = "retries", type = OoaType.NUMBER, required = false, defaultValue = "1", description = D_RETRIES) int retries) {
        Snmp.parseOid(oid);
        int t = Snmp.typeCode(type);
        Object v = value instanceof SecureString s ? s.reveal() : com.oneofficeautomation.runner.Values.text(value);
        Snmp.value(t, v);  // validates before anything is sent
        Snmp.Message m = Snmp.request(ctx, host, port, community(community), Snmp.SET, List.of(new Snmp.VarBind(oid, t, v)), timeout, retries);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("value", m.vars().isEmpty() ? null : m.vars().get(0).value());
        return out;
    }

    @Action(id = "snmp_send_trap", name = "SNMP send trap", category = "SNMP", timeoutSeconds = 60, capabilities = "network",
            description = "Send an SNMP v2c notification (trap) to a monitoring system.")
    public Map<String, Object> snmpSendTrap(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Monitoring system host name or IP address.") String host,
            @Input(name = "trap_oid", type = OoaType.STRING, example = "1.3.6.1.4.1.99999.0.1", description = "OID that identifies the kind of notification.") String trapOid,
            @Input(name = "variables", type = OoaType.DICTIONARY, required = false, description = "Extra values to include: OID to value (numbers are sent as integers, everything else as text).") Map<String, Object> variables,
            @Input(name = "community", required = false, defaultValue = "\"public\"", description = D_COMMUNITY) Object community,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "162", description = "UDP port of the trap receiver.") int port) {
        Snmp.parseOid(trapOid);
        List<Snmp.VarBind> extra = new ArrayList<>();
        if (variables != null) for (var e : variables.entrySet()) {
            Snmp.parseOid(e.getKey());
            Object v = e.getValue();
            boolean whole = v instanceof BigDecimal d && d.stripTrailingZeros().scale() <= 0 && d.abs().compareTo(BigDecimal.valueOf(Integer.MAX_VALUE)) <= 0;
            extra.add(whole ? new Snmp.VarBind(e.getKey(), Snmp.INTEGER, v) : new Snmp.VarBind(e.getKey(), Snmp.OCTETS, v instanceof SecureString ? SecureString.MASK : com.oneofficeautomation.runner.Values.text(v)));
        }
        long uptime = java.lang.management.ManagementFactory.getRuntimeMXBean().getUptime() / 10;  // hundredths of a second
        Snmp.sendTrap(host, port, community(community), uptime & 0xFFFFFFFFL, trapOid.strip().startsWith(".") ? trapOid.strip().substring(1) : trapOid.strip(), extra);
        return Map.of();
    }

}
