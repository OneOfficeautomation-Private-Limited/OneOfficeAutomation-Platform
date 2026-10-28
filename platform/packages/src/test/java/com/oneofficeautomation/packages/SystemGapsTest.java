package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.net.DatagramPacket;
import java.net.DatagramSocket;
import java.net.InetAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Actions added to close the gaps in the system and integration toolkits. */
class SystemGapsTest {
    static ActionContext ctx(Path w) { return PackagesTestSupport.ctx(w, Map.of()); }

    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }

    @SuppressWarnings("unchecked")
    static List<List<Object>> rows(Object table) { return (List<List<Object>>) ((Map<String, Object>) table).get("rows"); }

    // ---------- File and Folder ----------

    @Test void pathPartsLinksAndShortcuts() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        FilePackage f = new FilePackage();
        Map<String, Object> n = f.getName(c, "reports/2026/q4.final.pdf");
        assertEquals("q4.final.pdf", n.get("name"));
        assertEquals("q4.final", n.get("name_without_extension"));
        assertEquals("pdf", n.get("extension"));
        assertEquals("", f.getName(c, "README").get("extension"));
        assertEquals(w.resolve("reports").toAbsolutePath().normalize().toString(), f.getParent(c, "reports/q4.pdf").get("folder"));

        Files.writeString(w.resolve("data.txt"), "hello");
        Files.createDirectories(w.resolve("box"));
        // symbolic links need Developer Mode or admin rights on Windows; either outcome must be clean
        try {
            String link = (String) f.createSymbolicLink(c, "data.txt", "links/data-link.txt", false).get("path");
            assertEquals("hello", Files.readString(Path.of(link)));
            assertEquals("FILE.EXISTS", code(() -> f.createSymbolicLink(c, "data.txt", "links/data-link.txt", false)));
            f.createSymbolicLink(c, "data.txt", "links/data-link.txt", true);
            String dirLink = (String) new FolderPackage().createSymbolicLink(c, "box", "links/box-link", false).get("path");
            assertTrue(Files.isDirectory(Path.of(dirLink)));
        } catch (ActionException e) {
            assertEquals("FILE.LINK_NOT_PERMITTED", e.code(), e.getMessage());
        }
        assertEquals("FILE.NOT_FOUND", code(() -> f.createSymbolicLink(c, "missing.txt", "x.lnk", false)));
        assertEquals("FOLDER.NOT_FOUND", code(() -> new FolderPackage().createSymbolicLink(c, "nope", "x", false)));

        if (ProcessRunner.WINDOWS) {
            String lnk = (String) f.createShortcut(c, "data.txt", "shortcuts/Data", "--flag \"two words\"", null, "test $(not run)", false).get("path");
            assertTrue(lnk.endsWith("Data.lnk"));
            assertTrue(Files.size(Path.of(lnk)) > 0);
            assertEquals("FILE.EXISTS", code(() -> f.createShortcut(c, "data.txt", "shortcuts/Data.lnk", null, null, null, false)));
            String folderLnk = (String) new FolderPackage().createShortcut(c, "box", "shortcuts/Box.lnk", null, false).get("path");
            assertTrue(Files.isRegularFile(Path.of(folderLnk)));
        } else {
            String desk = (String) f.createShortcut(c, "data.txt", "shortcuts/data.desktop", null, null, "Data", false).get("path");
            assertTrue(Files.readString(Path.of(desk)).contains("[Desktop Entry]"));
        }
        assertTrue(FilePackage.LNK_SCRIPT.contains("$env:OOA_LNK_TARGET"), "values travel in environment variables, never inside the script");
        assertEquals(w.resolve("a b").toString(), FilePackage.lnkEnv(w.resolve("x.lnk"), w.resolve("a b"), null, null, null).get("OOA_LNK_TARGET"));

        assertEquals("FILE.NO_FILES", code(() -> f.printFiles(c, List.of(), null, 1, null, "*", false, null)));
        assertEquals("FILE.NOT_FOUND", code(() -> f.printFiles(c, List.of("data.txt", "missing.pdf"), "No Such Printer", 1, null, "*", false, null)));
    }

    @Test void renameAndOpenFolder() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        FolderPackage fp = new FolderPackage();
        Files.createDirectories(w.resolve("in/2026"));
        Files.writeString(w.resolve("in/2026/a.txt"), "x");
        String moved = (String) fp.renameFolder(c, "in/2026", "Archive 2026").get("path");
        assertEquals("x", Files.readString(Path.of(moved, "a.txt")));
        assertFalse(Files.exists(w.resolve("in/2026")));
        Files.createDirectories(w.resolve("in/other"));
        assertEquals("FOLDER.EXISTS", code(() -> fp.renameFolder(c, "in/other", "Archive 2026")));
        assertEquals("FOLDER.BAD_NAME", code(() -> fp.renameFolder(c, "in/other", "../escape")));
        assertEquals("FOLDER.BAD_NAME", code(() -> fp.renameFolder(c, "in/other", "a:b")));
        assertEquals("FOLDER.NOT_FOUND", code(() -> fp.renameFolder(c, "in/none", "x")));
        assertEquals("FOLDER.NOT_FOUND", code(() -> fp.openFolder(c, "in/none")));
        List<String> cmd = FolderPackage.openCommand(w);
        assertEquals(ProcessRunner.WINDOWS ? "explorer.exe" : cmd.get(0), cmd.get(0));
        assertEquals(w.toString(), cmd.get(1));
    }

    // ---------- Printer, services, system ----------

    @Test void printerAndServiceGuards() throws Exception {
        ActionContext c = ctx(tmp());
        PrinterPackage p = new PrinterPackage();
        assertNotNull(p.getDefault(c).get("printer"));
        assertEquals("PRINTER.CONFIRM_REQUIRED", code(() -> p.removePrinter(c, "Any", false)));
        assertTrue(PrinterPackage.SET_DEFAULT_SCRIPT.contains("-eq $env:OOA_PRINTER"), "exact match, no wildcards");
        assertTrue(PrinterPackage.REMOVE_SCRIPT.contains("-eq $env:OOA_PRINTER"));
        assertEquals(List.of("sc.exe", "pause", "Spooler"), ServicesPackage.pauseCommand("Spooler", true));
        assertEquals(List.of("sc.exe", "continue", "Spooler"), ServicesPackage.pauseCommand("Spooler", false));
        assertEquals("SERVICES.BAD_NAME", code(() -> ServicesPackage.pauseCommand("x & del", true)));
        String missing = "OoaNoSuchPrinter" + UUID.randomUUID();
        if (ProcessRunner.WINDOWS) {
            // a printer that does not exist: nothing on the machine changes
            assertEquals("PRINTER.NOT_FOUND", code(() -> p.setDefault(c, missing)));
            assertEquals("PRINTER.NOT_FOUND", code(() -> p.removePrinter(c, missing, true)));
            assertEquals("PRINTER.BAD_NAME", code(() -> p.setDefault(c, "bad\nname")));
            assertEquals("SERVICES.NOT_FOUND", code(() -> new ServicesPackage().pause(c, "OoaNoSuchService123", 5, true)));
            assertEquals("SERVICES.NOT_FOUND", code(() -> new ServicesPackage().resume(c, "OoaNoSuchService123", 5, true)));
        } else {
            assertEquals("PRINTER.WINDOWS_ONLY", code(() -> p.setDefault(c, missing)));
            assertEquals("SYSTEM.WINDOWS_ONLY", code(() -> new ServicesPackage().pause(c, "cron", 5, true)));
        }
    }

    @Test void environmentVariables() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        SystemPackage s = new SystemPackage();
        s.setEnv(c, "OOA_GAP_TEST", "from-agent", "process");
        @SuppressWarnings("unchecked") Map<String, Object> vars = (Map<String, Object>) s.getEnvAll(c).get("variables");
        assertEquals("from-agent", vars.get("OOA_GAP_TEST"));
        assertTrue(vars.keySet().stream().noneMatch(SystemPackage::secretName));
        assertTrue(vars.keySet().stream().anyMatch(k -> k.equalsIgnoreCase("PATH")));
        List<String> echo = ProcessRunner.WINDOWS ? List.of("cmd.exe", "/c", "echo %OOA_GAP_TEST%") : List.of("sh", "-c", "echo $OOA_GAP_TEST");
        assertEquals("from-agent", ProcessRunner.run(c, echo, w, null, 30).stdout().strip(), "programs started later in the run see the variable");
        assertEquals("SYSTEM.SECRET_VARIABLE", code(() -> s.setEnv(c, "DB_PASSWORD", "x", "process")));
        assertEquals("SYSTEM.BAD_VARIABLE_NAME", code(() -> s.setEnv(c, "A=B", "x", "process")));
        assertEquals("SYSTEM.BAD_SCOPE", code(() -> s.setEnv(c, "OOA_X", "x", "machine")));
        assertTrue(SystemPackage.SET_USER_ENV_SCRIPT.contains("$env:OOA_ENV_VALUE"));
        assertFalse(ProcessRunner.run(ctx(w), echo, w, null, 30).stdout().contains("from-agent"), "another run does not see it");
    }

    // ---------- SNMP ----------

    @Test void snmpEncodingMatchesKnownBytes() {
        byte[] get = NetworkPackage.Snmp.encode(new NetworkPackage.Snmp.Message(1, "public", NetworkPackage.Snmp.GET, 1, 0, 0,
                List.of(new NetworkPackage.Snmp.VarBind("1.3.6.1.2.1.1.1.0", NetworkPackage.Snmp.NULL, null))));
        assertEquals("30:26:02:01:01:04:06:70:75:62:6c:69:63:a0:19:02:01:01:02:01:00:02:01:00:30:0e:30:0c:06:08:2b:06:01:02:01:01:01:00:05:00",
                NetworkPackage.Snmp.hex(get));
        var back = NetworkPackage.Snmp.decode(get, get.length);
        assertEquals("public", back.community());
        assertEquals("1.3.6.1.2.1.1.1.0", back.vars().get(0).oid());
        // large arcs, negative integers, unsigned counters and long lengths round-trip
        String text = "x".repeat(300);
        byte[] m = NetworkPackage.Snmp.encode(new NetworkPackage.Snmp.Message(1, "c", NetworkPackage.Snmp.RESPONSE, -5, 0, 0, List.of(
                new NetworkPackage.Snmp.VarBind("1.3.6.1.4.1.4294967295.2", NetworkPackage.Snmp.INTEGER, BigDecimal.valueOf(-129)),
                new NetworkPackage.Snmp.VarBind("1.3.6.1.2.1.2.2.1.10.1", NetworkPackage.Snmp.COUNTER32, BigDecimal.valueOf(4294967295L)),
                new NetworkPackage.Snmp.VarBind("1.3.6.1.2.1.1.5.0", NetworkPackage.Snmp.OCTETS, text),
                new NetworkPackage.Snmp.VarBind("1.3.6.1.2.1.4.20.1.1.1", NetworkPackage.Snmp.IPADDR, "10.0.0.255"))));
        var d = NetworkPackage.Snmp.decode(m, m.length);
        assertEquals(-5, d.requestId());
        assertEquals("1.3.6.1.4.1.4294967295.2", d.vars().get(0).oid());
        eqNum("-129", d.vars().get(0).value());
        eqNum("4294967295", d.vars().get(1).value());
        assertEquals(text, d.vars().get(2).value());
        assertEquals("10.0.0.255", d.vars().get(3).value());
        assertEquals("SNMP.BAD_OID", code(() -> NetworkPackage.Snmp.parseOid("1.3.x")));
        assertEquals("SNMP.BAD_OID", code(() -> NetworkPackage.Snmp.parseOid("3.1")));
        assertEquals("SNMP.BAD_RESPONSE", code(() -> NetworkPackage.Snmp.decode(Arrays.copyOf(get, 10), 10)));
    }

    /** Tiny SNMP v2c agent: answers GET, GETNEXT and SET from a sorted MIB for one community and records traps. */
    static final class FakeAgent implements AutoCloseable {
        final DatagramSocket socket = new DatagramSocket(0, InetAddress.getLoopbackAddress());
        final TreeMap<String, NetworkPackage.Snmp.VarBind> mib = new TreeMap<>(SystemGapsTest::compareOids);
        final List<NetworkPackage.Snmp.Message> traps = new CopyOnWriteArrayList<>();
        final AtomicBoolean running = new AtomicBoolean(true);
        final Thread thread;

        FakeAgent() throws Exception {
            put("1.3.6.1.2.1.1.1.0", NetworkPackage.Snmp.OCTETS, "OOA test switch");
            put("1.3.6.1.2.1.1.3.0", NetworkPackage.Snmp.TIMETICKS, BigDecimal.valueOf(123456));
            put("1.3.6.1.2.1.1.5.0", NetworkPackage.Snmp.OCTETS, "switch-01");
            put("1.3.6.1.2.1.2.2.1.2.1", NetworkPackage.Snmp.OCTETS, "eth0");
            put("1.3.6.1.2.1.2.2.1.2.2", NetworkPackage.Snmp.OCTETS, "eth1");
            put("1.3.6.1.2.1.2.2.1.10.1", NetworkPackage.Snmp.COUNTER32, BigDecimal.valueOf(4000000000L));
            put("1.3.6.1.2.1.4.1.0", NetworkPackage.Snmp.INTEGER, BigDecimal.valueOf(2));
            thread = new Thread(this::serve, "fake-snmp");
            thread.setDaemon(true);
            thread.start();
        }
        void put(String oid, int type, Object v) { mib.put(oid, new NetworkPackage.Snmp.VarBind(oid, type, v)); }
        int port() { return socket.getLocalPort(); }

        void serve() {
            byte[] buf = new byte[65535];
            while (running.get()) {
                try {
                    DatagramPacket in = new DatagramPacket(buf, buf.length);
                    socket.receive(in);
                    var req = NetworkPackage.Snmp.decode(in.getData(), in.getLength());
                    if (req.pduType() == NetworkPackage.Snmp.TRAP) { traps.add(req); continue; }
                    if (!req.community().equals(req.pduType() == NetworkPackage.Snmp.SET ? "private" : "public")) continue;  // wrong community: silence
                    List<NetworkPackage.Snmp.VarBind> out = new ArrayList<>();
                    int err = 0, idx = 0;
                    for (int i = 0; i < req.vars().size(); i++) {
                        var vb = req.vars().get(i);
                        if (req.pduType() == NetworkPackage.Snmp.GET) {
                            var hit = mib.get(vb.oid());
                            out.add(hit != null ? hit : new NetworkPackage.Snmp.VarBind(vb.oid(), NetworkPackage.Snmp.NO_SUCH_OBJECT, null));
                        } else if (req.pduType() == NetworkPackage.Snmp.GETNEXT) {
                            var next = mib.higherEntry(vb.oid());
                            out.add(next != null ? next.getValue() : new NetworkPackage.Snmp.VarBind(vb.oid(), NetworkPackage.Snmp.END_OF_MIB, null));
                        } else if (req.pduType() == NetworkPackage.Snmp.SET) {
                            if (vb.oid().equals("1.3.6.1.2.1.1.1.0")) { err = 17; idx = i + 1; out.add(vb); continue; }  // notWritable
                            mib.put(vb.oid(), vb);
                            out.add(vb);
                        }
                    }
                    byte[] resp = NetworkPackage.Snmp.encode(new NetworkPackage.Snmp.Message(1, req.community(), NetworkPackage.Snmp.RESPONSE, req.requestId(), err, idx,
                            err != 0 ? req.vars() : out));
                    socket.send(new DatagramPacket(resp, resp.length, in.getAddress(), in.getPort()));
                } catch (Exception e) {
                    if (!running.get()) return;
                }
            }
        }

        @Override public void close() { running.set(false); socket.close(); }
    }

    static int compareOids(String a, String b) {
        String[] x = a.split("\\."), y = b.split("\\.");
        for (int i = 0; i < Math.min(x.length, y.length); i++) {
            int c = Long.compare(Long.parseLong(x[i]), Long.parseLong(y[i]));
            if (c != 0) return c;
        }
        return Integer.compare(x.length, y.length);
    }

    @SuppressWarnings("unchecked")
    @Test void snmpAgainstFakeAgent() throws Exception {
        ActionContext c = ctx(tmp());
        NetworkPackage n = new NetworkPackage();
        try (FakeAgent agent = new FakeAgent()) {
            String host = "127.0.0.1";
            int port = agent.port();
            Map<String, Object> got = n.snmpGet(c, host, "1.3.6.1.2.1.1.5.0, 1.3.6.1.2.1.1.3.0, 1.3.6.1.2.1.99.0", "public", port, 2000, 0, "v2c");
            Map<String, Object> values = (Map<String, Object>) got.get("values");
            assertEquals("switch-01", values.get("1.3.6.1.2.1.1.5.0"));
            eqNum("123456", values.get("1.3.6.1.2.1.1.3.0"));
            assertNull(values.get("1.3.6.1.2.1.99.0"));
            assertEquals("no_such_object", rows(got.get("table")).get(2).get(1));

            Map<String, Object> next = n.snmpGetNext(c, host, "1.3.6.1.2.1.1.1.0", new SecureString("public", "test/snmp"), port, 2000, 0, "v2c");
            assertEquals("1.3.6.1.2.1.1.3.0", next.get("oid"));
            assertEquals("timeticks", next.get("type"));

            Map<String, Object> walk = n.snmpWalk(c, host, "1.3.6.1.2.1.2.2", "public", port, 100, 2000, 0, "v2c");
            eqNum("3", walk.get("row_count"));
            assertEquals("eth1", rows(walk.get("table")).get(1).get(2));
            eqNum("4000000000", rows(walk.get("table")).get(2).get(2));

            Map<String, Object> set = n.snmpSet(c, host, "1.3.6.1.2.1.1.6.0", "Server room B", "string", "private", port, 2000, 0, "v2c");
            assertEquals("Server room B", set.get("value"));
            assertEquals("Server room B", ((Map<String, Object>) n.snmpGet(c, host, List.of("1.3.6.1.2.1.1.6.0"), "public", port, 2000, 0, "v2c").get("values")).get("1.3.6.1.2.1.1.6.0"));
            n.snmpSet(c, host, "1.3.6.1.2.1.4.1.0", BigDecimal.ONE, "integer", "private", port, 2000, 0, "v2c");
            eqNum("1", ((Map<String, Object>) n.snmpGet(c, host, "1.3.6.1.2.1.4.1.0", "public", port, 2000, 0, "v2c").get("values")).get("1.3.6.1.2.1.4.1.0"));
            ActionException ro = assertThrows(ActionException.class, () -> n.snmpSet(c, host, "1.3.6.1.2.1.1.1.0", "x", "string", "private", port, 2000, 0, "v2c"));
            assertEquals("SNMP.ERROR", ro.code());
            assertTrue(ro.getMessage().contains("notWritable"), ro.getMessage());
            assertEquals("SNMP.BAD_VALUE", code(() -> n.snmpSet(c, host, "1.3.6.1.2.1.1.6.0", "300.1.1.1", "ip_address", "private", port, 2000, 0, "v2c")));
            assertEquals("SNMP.TIMEOUT", code(() -> n.snmpGet(c, host, "1.3.6.1.2.1.1.5.0", "wrong-community", port, 300, 1, "v2c")));

            n.snmpSendTrap(c, host, "1.3.6.1.4.1.99999.0.1", new LinkedHashMap<>(Map.of("1.3.6.1.4.1.99999.1.1", "Invoice batch failed", "1.3.6.1.4.1.99999.1.2", BigDecimal.valueOf(42))), "public", port);
            long end = System.currentTimeMillis() + 3000;
            while (agent.traps.isEmpty() && System.currentTimeMillis() < end) Thread.sleep(20);
            assertEquals(1, agent.traps.size());
            var trap = agent.traps.get(0);
            assertEquals(NetworkPackage.Snmp.SYS_UPTIME, trap.vars().get(0).oid());
            assertEquals("1.3.6.1.4.1.99999.0.1", trap.vars().get(1).value());
            Map<String, Object> extra = new HashMap<>();
            for (var vb : trap.vars().subList(2, trap.vars().size())) extra.put(vb.oid(), vb.value());
            assertEquals("Invoice batch failed", extra.get("1.3.6.1.4.1.99999.1.1"));
            eqNum("42", extra.get("1.3.6.1.4.1.99999.1.2"));
        }
    }

    // ---------- Log ----------

    @Test void logVariablesAndCommandCenter() throws Exception {
        Path w = tmp();
        List<String[]> lines = new CopyOnWriteArrayList<>();
        ActionContext c = new ActionContext("run", "s1", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(),
                () -> List.of("hunter2"), (l, m) -> lines.add(new String[]{l, m}));
        LogPackage log = new LogPackage();
        Map<String, Object> vars = new LinkedHashMap<>();
        vars.put("invoice", "INV-7");
        vars.put("total", new BigDecimal("129.50"));
        vars.put("items", List.of("a", "b"));
        vars.put("password", new SecureString("hunter2", "test/x"));
        vars.put("note", "line1\nline2 hunter2");
        eqNum("5", log.logVariables(c, "logs/state.log", vars, "After posting", true, false, "utf-8", null).get("lines"));
        String text = Files.readString(w.resolve("logs/state.log"));
        assertTrue(text.contains("After posting"));
        assertTrue(text.contains("invoice=INV-7"));
        assertTrue(text.contains("total=129.5"));
        assertTrue(text.contains("items=[\"a\",\"b\"]"));
        assertTrue(text.contains("password=****"));
        assertTrue(text.contains("note=line1\\nline2 ****"), text);
        assertFalse(text.contains("hunter2"));

        log.logToCommandCenter(c, "Posted batch with secret hunter2", "WARN", Map.of("count", BigDecimal.valueOf(3)));
        assertEquals("warning", lines.get(lines.size() - 1)[0]);
        assertEquals("Posted batch with secret **** {\"count\":3}", lines.get(lines.size() - 1)[1]);
        log.logToCommandCenter(c, "debug line", "DEBUG", null);
        assertEquals("debug", lines.get(lines.size() - 1)[0]);
        assertEquals("LOG.BAD_LEVEL", code(() -> log.logToCommandCenter(c, "x", "LOUD", null)));
        log.logToCommandCenter(c, "Checked 4 invoices", "INFO", Map.of("ok", true), " invoice-check ");
        assertEquals("info", lines.get(lines.size() - 1)[0]);
        assertEquals("[invoice-check] Checked 4 invoices {\"ok\":true}", lines.get(lines.size() - 1)[1]);
        log.logToCommandCenter(c, "no tag", "INFO", null, "  ");
        assertEquals("no tag", lines.get(lines.size() - 1)[1]);
    }

    // ---------- PGP ----------

    @Test void pgpCreateKeysThenEncryptAndDecrypt() throws Exception {
        if (!PgpPackage.usable() || !OcrPgpTest.has(PgpPackage.exe())) { System.out.println("    (gpg not installed; skipped)"); return; }
        Path w = tmp();
        ActionContext c = ctx(w);
        PgpPackage pgp = new PgpPackage();
        assertEquals("PGP.BAD_EMAIL", code(() -> pgp.createKeys(c, "Agent", "not-an-email", null, null, "ed25519", "1y", null, null, "keys")));
        assertEquals("PGP.BAD_NAME", code(() -> pgp.createKeys(c, "--batch", "a@b.test", null, null, "ed25519", "1y", null, null, "keys")));
        assertEquals("PGP.BAD_ALGORITHM", code(() -> pgp.createKeys(c, "Agent", "a@b.test", null, null, "dsa", "1y", null, null, "keys")));
        assertEquals("PGP.BAD_EXPIRY", code(() -> pgp.createKeys(c, "Agent", "a@b.test", null, null, "ed25519", "soon", null, null, "keys")));
        SecureString pass = new SecureString("agent-pass", "test/pgp/pass");
        try {
            Map<String, Object> k = pgp.createKeys(c, "Finance Agent", "finance-agent@ooa.test", pass, "test", "ed25519", "1d", "out/public.asc", "out/private.asc", "keys");
            assertTrue(String.valueOf(k.get("fingerprint")).matches("[0-9A-F]{40,64}"), String.valueOf(k.get("fingerprint")));
            assertEquals("Finance Agent (test) <finance-agent@ooa.test>", k.get("user_id"));
            assertTrue(Files.readString(w.resolve("out/public.asc")).contains("BEGIN PGP PUBLIC KEY BLOCK"));
            assertTrue(Files.readString(w.resolve("out/private.asc")).contains("BEGIN PGP PRIVATE KEY BLOCK"));
            Files.writeString(w.resolve("pay.csv"), "Ann,5000\n");
            pgp.encrypt(c, "pay.csv", "out/pay.csv.pgp", "finance-agent@ooa.test", false, "keys", null, true);
            pgp.decrypt(c, "out/pay.csv.pgp", "out/back.csv", pass, "keys", null, true);
            assertEquals("Ann,5000\n", Files.readString(w.resolve("out/back.csv")));
            // the exported public key imports into a separate keyring
            assertFalse(((List<?>) pgp.importKey(c, "out/public.asc", null, "partner").get("fingerprints")).isEmpty());
        } finally {
            for (String h : List.of("keys", "partner"))
                try { new ProcessBuilder("gpgconf", "--homedir", w.resolve(h).toString(), "--kill", "gpg-agent").start().waitFor(); } catch (Exception ignored) { }
        }
    }

    // ---------- Script ----------

    @Test void runPythonFunction() throws Exception {
        boolean py = OcrPgpTest.has(ProcessRunner.WINDOWS ? "python" : "python3");
        if (!py) { System.out.println("    (python not installed; skipped)"); return; }
        Path w = tmp();
        Files.writeString(w.resolve("calc.py"), """
                import json
                def total(items, tax=0):
                    print("computing", len(items))
                    s = sum(i["amount"] for i in items)
                    return {"total": round(s * (1 + tax), 2), "count": len(items)}
                class Tools:
                    @staticmethod
                    def shout(text):
                        return text.upper()
                def broken():
                    raise ValueError("no data")
                """);
        ActionContext c = ctx(w);
        ScriptPackage s = new ScriptPackage();
        Map<String, Object> r = s.runPythonFunction(c, "calc.py", "total",
                List.of(List.of(Map.of("amount", new BigDecimal("100")), Map.of("amount", new BigDecimal("50")))), Map.of("tax", new BigDecimal("0.1")), 60, true, null, null);
        @SuppressWarnings("unchecked") Map<String, Object> res = (Map<String, Object>) r.get("result");
        eqNum("165", res.get("total"));
        eqNum("2", res.get("count"));
        assertTrue(String.valueOf(r.get("output")).contains("computing 2"));
        assertEquals("HELLO", s.runPythonFunction(c, "calc.py", "Tools.shout", List.of("hello"), null, 60, true, null, null).get("result"));
        ActionException e = assertThrows(ActionException.class, () -> s.runPythonFunction(c, "calc.py", "broken", null, null, 60, true, null, null));
        assertEquals("SCRIPT.PYTHON_ERROR", e.code());
        assertTrue(e.getMessage().contains("ValueError: no data"));
        assertEquals("SCRIPT.PYTHON_ERROR", code(() -> s.runPythonFunction(c, "calc.py", "missing", null, null, 60, true, null, null)));
        assertEquals("SCRIPT.BAD_FUNCTION", code(() -> s.runPythonFunction(c, "calc.py", "__import__('os').system('x')", null, null, 60, true, null, null)));
        assertEquals("FILE.NOT_FOUND", code(() -> s.runPythonFunction(c, "none.py", "total", null, null, 60, true, null, null)));
    }

    // ---------- Database ----------

    @SuppressWarnings("unchecked")
    @Test void databaseBulkProcedureExportAndSchema() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        DatabasePackage db = new DatabasePackage();
        db.connect(c, "jdbc:h2:mem:gaps" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1", null, null, null, null, null, null, null, "db", null, 0, null, false, true);
        try {
            db.execute(c, "CREATE TABLE invoices(id VARCHAR(20) NOT NULL, vendor VARCHAR(100), amount DECIMAL(12,2))", null, "db", 0);
            db.execute(c, "CREATE VIEW big_invoices AS SELECT * FROM invoices WHERE amount > 1000", null, "db", 0);
            List<Object> data = new ArrayList<>();
            for (int i = 1; i <= 7; i++) data.add(new ArrayList<>(Arrays.asList("INV-" + i, i == 2 ? "Acme, \"Ltd\"" : "Vendor " + i, new BigDecimal(i * 500))));
            Map<String, Object> table = com.oneofficeautomation.runner.Values.table(List.of("Invoice", "Supplier", "Total"), data);
            eqNum("7", db.bulkInsert(c, "invoices", table, List.of("id", "vendor", "amount"), 3, "db").get("rows_inserted"));
            eqNum("7", rows(db.query(c, "SELECT COUNT(*) FROM invoices", null, 10, "db", 0).get("table")).get(0).get(0));
            assertEquals("DB.COLUMN_MISMATCH", code(() -> db.bulkInsert(c, "invoices", table, List.of("id"), 3, "db")));
            assertEquals("DB.BAD_NAME", code(() -> db.bulkInsert(c, "invoices; DROP TABLE invoices", table, null, 3, "db")));
            // a failing row rolls back the whole insert
            List<Object> bad = new ArrayList<>(List.of(new ArrayList<>(Arrays.asList("OK-1", "x", BigDecimal.ONE)), new ArrayList<>(Arrays.asList(null, "x", BigDecimal.ONE))));
            assertEquals("DB.SQL_ERROR", code(() -> db.bulkInsert(c, "invoices", com.oneofficeautomation.runner.Values.table(List.of("id", "vendor", "amount"), bad), null, 1, "db")));
            assertEquals(7, rows(db.query(c, "SELECT * FROM invoices", null, 100, "db", 0).get("table")).size());

            db.execute(c, "CREATE ALIAS OOA_MAX FOR \"java.lang.Math.max(int,int)\"", null, "db", 0);
            Map<String, Object> call = db.callProcedure(c, "OOA_MAX", List.of(new BigDecimal(3), new BigDecimal(9)), 100, "db", 0);
            eqNum("1", call.get("row_count"));
            eqNum("9", rows(call.get("table")).get(0).get(0));
            assertEquals("DB.BAD_NAME", code(() -> db.callProcedure(c, "x(1); --", null, 100, "db", 0)));

            Map<String, Object> exp = db.exportToFile(c, "SELECT id, vendor, amount FROM invoices WHERE amount >= ? ORDER BY id", "out/inv.csv",
                    List.of(new BigDecimal(1000)), ";", true, "utf-8", "db", 0, true);
            eqNum("6", exp.get("row_count"));
            List<String> csv = Files.readAllLines(w.resolve("out/inv.csv"));
            assertEquals("ID;VENDOR;AMOUNT", csv.get(0));
            assertEquals("INV-2;\"Acme, \"\"Ltd\"\"\";1000", csv.get(1));

            List<List<Object>> tables = rows(db.listTables(c, null, true, "db").get("table"));
            assertTrue(tables.stream().anyMatch(r -> "INVOICES".equals(r.get(1))));
            assertTrue(tables.stream().anyMatch(r -> "BIG_INVOICES".equals(r.get(1))));
            assertTrue(rows(db.listTables(c, null, false, "db").get("table")).stream().noneMatch(r -> "BIG_INVOICES".equals(r.get(1))));
            List<List<Object>> cols = rows(db.describeTable(c, "invoices", "db").get("table"));
            assertEquals(List.of("ID", "VENDOR", "AMOUNT"), cols.stream().map(r -> r.get(0)).toList());
            assertEquals(false, cols.get(0).get(3));
            eqNum("12", cols.get(2).get(2));
            assertEquals("DB.TABLE_NOT_FOUND", code(() -> db.describeTable(c, "nothing_here", "db")));
        } finally {
            db.disconnect(c, "db");
        }
    }

    // ---------- REST ----------

    @Test void restDownloadAndUploadFiles() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        RestPackage rest = new RestPackage();
        byte[] binary = new byte[70_000];
        new Random(7).nextBytes(binary);
        try (ConnectorsTest.Fake f = new ConnectorsTest.Fake()
                .on("GET /files/report.bin", call -> new Object[]{200, binary})
                .on("GET /files/missing", call -> new Object[]{404, "{\"message\":\"gone\"}"})
                .on("POST /upload", call -> new Object[]{201, "{\"id\":\"F-1\"}"})) {
            Map<String, Object> d = rest.downloadFile(c, f.url() + "/files/report.bin", "in/report.bin", Map.of("v", "1"), Map.of("X-Trace", "t1"),
                    null, null, new SecureString("tok-1", "test/api"), true, 30, false, null, null, null, null, null);
            assertArrayEquals(binary, Files.readAllBytes(w.resolve("in/report.bin")), "binary-safe");
            eqNum("70000", d.get("size_bytes"));
            eqNum("200", d.get("status"));
            assertEquals("Bearer tok-1", f.last("GET", "/files/report.bin").auth());
            assertEquals("v=1", f.last("GET", "/files/report.bin").query());
            Files.createDirectories(w.resolve("dl"));
            String intoFolder = (String) rest.downloadFile(c, f.url() + "/files/report.bin", "dl", null, null, null, null, null, true, 30, false, null, null, null, null, null).get("path");
            assertEquals(w.resolve("dl/report.bin").toString(), intoFolder);
            assertEquals("REST.FILE_EXISTS", code(() -> rest.downloadFile(c, f.url() + "/files/report.bin", "in/report.bin", null, null, null, null, null, false, 30, false, null, null, null, null, null)));
            ActionException e = assertThrows(ActionException.class, () -> rest.downloadFile(c, f.url() + "/files/missing", "in/missing.bin", null, null, null, null, null, true, 30, false, null, null, null, null, null));
            assertEquals("REST.HTTP_ERROR", e.code());
            assertFalse(Files.exists(w.resolve("in/missing.bin")));
            try (var s = Files.list(w.resolve("in"))) { assertTrue(s.noneMatch(p -> p.getFileName().toString().endsWith(".part")), "temporary files are removed"); }

            Files.writeString(w.resolve("invoice.pdf"), "%PDF-1.4 test");
            Map<String, Object> up = rest.uploadFile(c, f.url() + "/upload", "invoice.pdf", "document", Map.of("folder", "AP"), "POST", null, null,
                    "svc", new SecureString("pw", "test/basic"), null, 30, true, false, null, null, null, null, null);
            eqNum("201", up.get("status"));
            assertEquals("F-1", ((Map<?, ?>) up.get("json")).get("id"));
            ConnectorsTest.Call call = f.last("POST", "/upload");
            assertTrue(call.contentType().startsWith("multipart/form-data; boundary="));
            assertTrue(call.auth().startsWith("Basic "));
            String body = call.text();
            assertTrue(body.contains("name=\"document\"; filename=\"invoice.pdf\""));
            assertTrue(body.contains("%PDF-1.4 test"));
            assertTrue(body.contains("name=\"folder\"\r\n\r\nAP"));
            assertEquals("REST.FILE_NOT_FOUND", code(() -> rest.uploadFile(c, f.url() + "/upload", "none.pdf", null, null, null, null, null, null, null, null, 30, true, false, null, null, null, null, null)));
        }
        assertEquals("report.pdf", RestPackage.dispositionName("attachment; filename=\"report.pdf\""));
        assertEquals("r%C3%A9sum%C3%A9.pdf", RestPackage.dispositionName("attachment; filename=\"x.pdf\"; filename*=UTF-8''r%C3%A9sum%C3%A9.pdf"));
    }
}
