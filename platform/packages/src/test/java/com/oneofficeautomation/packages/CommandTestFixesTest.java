package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.FlowValidator;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.Graphics2D;
import java.awt.image.BufferedImage;
import java.math.BigDecimal;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.PackagesTest.REG;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Regression tests for the findings of the 2 Nov 2026 command test round (all 1,124 commands): each test names the
 * finding it covers (F001 ...). Windows-only behaviour (PowerShell, Excel, JScript, Windows OCR) is checked on Windows runners.
 */
class CommandTestFixesTest {

    @SuppressWarnings("unchecked")
    static RunResult flow(Path work, String variables, String steps, CredentialVault vault, int maxSeconds) {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse(
                "{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":" + variables + ",\"steps\":" + steps + "}");
        Runner.Builder b = Runner.builder(REG).workDir(work);
        if (vault != null) b.service("vault", vault);
        return b.build().run(flow, Map.of(), new Runner.RunOptions().maxRunSeconds(maxSeconds));
    }

    static RunResult flow(Path work, String variables, String steps) { return flow(work, variables, steps, vault(), 60); }

    static CredentialVault vault() { return new CredentialVault.InMemory().put("qa", "zip", "pw", "S3cret!pw").put("qa", "h", "key", "Jefe"); }

    static String code(RunResult r) { return r.error() == null ? null : String.valueOf(r.error().get("code")); }

    @SuppressWarnings("unchecked")
    static List<String> problems(String variables, String steps) {
        Map<String, Object> f = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":" + variables + ",\"steps\":" + steps + "}");
        List<String> out = new ArrayList<>();
        for (Map<String, Object> p : new FlowValidator(REG, null).validate(f)) out.add(p.get("path") + ": " + p.get("message"));
        return out;
    }

    static final String OUT = "[{\"name\":\"r\",\"type\":\"Any\",\"direction\":\"output\"},{\"name\":\"r2\",\"type\":\"Any\",\"direction\":\"output\"}]";

    // ---- F001: a vault reference written in a step is resolved, never used as the secret text

    @Test void f001VaultReferenceInStepIsResolved() throws Exception {
        Path w = tmp();
        Files.writeString(w.resolve("a.txt"), "hello");
        String zip = "{\"id\":\"z\",\"action\":\"ooa.file/zip\",\"args\":{\"source\":\"a.txt\",\"target\":\"a.zip\",\"password\":{\"$credential\":\"qa/zip/pw\"},\"overwrite\":true}}";
        RunResult ok = flow(w, OUT, "[" + zip + ",{\"id\":\"u\",\"action\":\"ooa.file/unzip\",\"args\":{\"archive\":\"a.zip\",\"folder\":\"out\",\"password\":{\"$credential\":\"qa/zip/pw\"},\"overwrite\":true}}]");
        assertTrue(ok.ok(), String.valueOf(ok.error()));
        RunResult refText = flow(w, OUT, "[{\"id\":\"u\",\"action\":\"ooa.file/unzip\",\"args\":{\"archive\":\"a.zip\",\"folder\":\"out2\",\"password\":\"{$credential=qa/zip/pw}\",\"overwrite\":true}}]");
        assertEquals("ZIP.WRONG_PASSWORD", code(refText), "the reference text must not open the archive");
        RunResult missing = flow(w, OUT, "[{\"id\":\"z\",\"action\":\"ooa.file/zip\",\"args\":{\"source\":\"a.txt\",\"target\":\"b.zip\",\"password\":{\"$credential\":\"qa/zip/nope\"}}}]");
        assertEquals("VAULT.NOT_FOUND", code(missing));
        RunResult hmac = flow(w, OUT, "[{\"id\":\"h\",\"action\":\"ooa.crypto/hmac\",\"args\":{\"text\":\"what do ya want for nothing?\",\"key\":{\"$credential\":\"qa/h/key\"},\"algorithm\":\"SHA-256\"},\"out\":{\"signature\":\"r\"}}]");
        assertEquals("5bdcc146bf60754e6a042426089575c75a003f089d2739839dec58b964ec3843", hmac.outputs().get("r"), "RFC 4231 test case 2");
    }

    @Test void f001ReferencesOutsideCredentialFieldsAndBadShapesAreRefused() throws Exception {
        assertTrue(problems("[]", "[{\"id\":\"r\",\"action\":\"ooa.rest/request\",\"args\":{\"url\":\"http://x\",\"headers\":{\"A\":{\"$credential\":\"a/b/c\"}}}}]")
                .stream().anyMatch(p -> p.contains("not a Credential field")));
        assertTrue(problems("[]", "[{\"id\":\"z\",\"action\":\"ooa.file/zip\",\"args\":{\"source\":\"a\",\"target\":\"b\",\"password\":{\"$credential\":\"onlyone\"}}}]")
                .stream().anyMatch(p -> p.contains("locker/credential/attribute")));
        assertEquals(List.of(), problems("[]", "[{\"id\":\"z\",\"action\":\"ooa.file/zip\",\"args\":{\"source\":\"a\",\"target\":\"b\",\"password\":{\"$credential\":\"a/b/c\"}}}]"));
        Path w = tmp();
        RunResult plain = flow(w, OUT, "[{\"id\":\"r\",\"action\":\"ooa.rest/request\",\"args\":{\"url\":\"http://127.0.0.1:1/x\",\"bearer_token\":\"plain\"}}]");
        assertEquals("REST.NO_SECRET", code(plain), "a token that is not a Credential is refused, not skipped");
        RunResult setCred = flow(w, "[{\"name\":\"pw\",\"type\":\"Credential\"}]", "[{\"id\":\"s\",\"action\":\"core/set\",\"args\":{\"variable\":\"pw\",\"value\":{\"$credential\":\"qa/zip/pw\"}}}]");
        assertTrue(setCred.ok(), "a Credential variable can be set from a vault reference: " + setCred.error());
    }

    // ---- F008 / F009: conditions and loop types are checked at design time

    @Test void f008f009ConditionsAndLoopTypesAreValidated() {
        List<String> p = problems("[{\"name\":\"x\",\"type\":\"Any\"}]", "[{\"id\":\"i\",\"action\":\"core/if\",\"args\":{\"condition\":{\"all\":[{\"condition\":\"ooa.list/contains\",\"args\":{\"list\":\"notalist\",\"valu\":1}}]}},\"children\":[]}]");
        assertTrue(p.stream().anyMatch(s -> s.contains("Unknown input 'valu'")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("Missing required input 'value'")), p.toString());
        assertTrue(p.stream().anyMatch(s -> s.contains("expects List")), p.toString());
        List<String> it = problems("[{\"name\":\"r\",\"type\":\"Any\"}]", "[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"ooa.csv/rows\",\"iterator_args\":{\"pathx\":\"a\"},\"as\":\"r\"},\"children\":[]}]");
        assertTrue(it.stream().anyMatch(s -> s.contains("Unknown input 'pathx'")), it.toString());
        assertTrue(it.stream().anyMatch(s -> s.contains("Missing required input 'path'")), it.toString());
        assertTrue(problems("[{\"name\":\"r\",\"type\":\"Any\"}]", "[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"ooa.csv/nope\",\"as\":\"r\"},\"children\":[]}]")
                .stream().anyMatch(s -> s.contains("has no loop type 'nope'")));
        assertEquals(List.of(), problems("[{\"name\":\"x\",\"type\":\"Any\"}]", "[{\"id\":\"i\",\"action\":\"core/if\",\"args\":{\"condition\":{\"not\":{\"condition\":\"ooa.file/exists\",\"args\":{\"path\":\"$x\"}}}},\"children\":[]}]"));
    }

    // ---- F020: the run time limit stops a step that is still running

    @Test void f020RunLimitStopsARunningStep() throws Exception {
        String sleep = ProcessRunner.WINDOWS ? "{\"program\":\"ping.exe\",\"arguments\":[\"-n\",\"30\",\"127.0.0.1\"],\"timeout_seconds\":120}"
                : "{\"program\":\"sleep\",\"arguments\":[\"30\"],\"timeout_seconds\":120}";
        long t0 = System.nanoTime();
        RunResult r = flow(tmp(), "[]", "[{\"id\":\"p\",\"action\":\"ooa.application/run_program\",\"args\":" + sleep + "}]", null, 2);
        assertEquals("RUN.TIMEOUT", code(r));
        assertTrue((System.nanoTime() - t0) / 1e9 < 15, "the step must not run to its own 120 s timeout");
    }

    // ---- F010: cmd.exe / .bat arguments cannot run a second command

    @Test void f010ShellOperatorsInCmdArgumentsAreRefused() {
        assertEquals("APPLICATION.UNSAFE_ARGUMENT", assertThrows(com.oneofficeautomation.sdk.ActionException.class,
                () -> ApplicationPackage.checkShellArguments("cmd.exe", List.of("/c", "echo", "a", "&", "echo", "injected"), false)).code());
        assertEquals("APPLICATION.UNSAFE_ARGUMENT", assertThrows(com.oneofficeautomation.sdk.ActionException.class,
                () -> ApplicationPackage.checkShellArguments("C:\\jobs\\run.BAT", List.of("file|x"), false)).code());
        ApplicationPackage.checkShellArguments("cmd.exe", List.of("/c", "echo", "a", "&", "echo", "b"), true);
        ApplicationPackage.checkShellArguments("notepad.exe", List.of("a & b"), false);
        ApplicationPackage.checkShellArguments("cmd.exe", List.of("/c", "dir", "C:\\Program Files (x86)"), false);
    }

    // ---- F011, F035: text

    @Test void f011f035TrimAndNumbers() throws Exception {
        StringPackage s = new StringPackage();
        assertEquals("abc", s.trim(null, "\u00a0\tabc\u3000\u202f", "both", null).get("result"));
        assertEquals(0, new BigDecimal("1200.50").compareTo((BigDecimal) s.toNumber(null, "$ 1,200.50", "en-US", null, null, true).get("result")));
        assertEquals(0, new BigDecimal("-1234").compareTo((BigDecimal) s.toNumber(null, "(1,234.00)", "en-US", null, null, true).get("result")));
        assertEquals(0, new BigDecimal("-1234.5").compareTo((BigDecimal) s.toNumber(null, "1.234,50- EUR", "de-DE", null, null, true).get("result")));
        assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> s.toNumber(null, "12ABC", "en-US", null, null, true), "ABC is not a currency");
        assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> s.toNumber(null, "$5", "en-US", null, null, false));
    }

    // ---- F012, F026: fractions

    @Test void f012f026FractionsAreExactOrRefused() throws Exception {
        Path w = tmp();
        RunResult add = flow(w, OUT, "[{\"id\":\"a\",\"action\":\"ooa.datetime/add\",\"args\":{\"value\":\"2026-01-15T10:30:00\",\"amount\":1.5,\"unit\":\"days\"},\"out\":{\"result\":\"r\"}},"
                + "{\"id\":\"b\",\"action\":\"ooa.datetime/add\",\"args\":{\"value\":\"2026-01-15T10:30:00\",\"amount\":0.5,\"unit\":\"hours\"},\"out\":{\"result\":\"r2\"}}]");
        assertTrue(add.ok(), String.valueOf(add.error()));
        assertEquals("2026-01-16T22:30", String.valueOf(add.outputs().get("r")));
        assertEquals("2026-01-15T11:00", String.valueOf(add.outputs().get("r2")));
        assertEquals("DATETIME.BAD_AMOUNT", code(flow(w, OUT, "[{\"id\":\"a\",\"action\":\"ooa.datetime/add\",\"args\":{\"value\":\"2026-01-15T10:30:00\",\"amount\":1.5,\"unit\":\"months\"}}]")));
        assertEquals("ACTION.INPUT_INVALID", code(flow(w, OUT, "[{\"id\":\"g\",\"action\":\"ooa.list/get_item\",\"args\":{\"list\":[\"a\",\"b\"],\"position\":1.7},\"out\":{\"item\":\"r\"}}]")));
        assertEquals("b", flow(w, OUT, "[{\"id\":\"g\",\"action\":\"ooa.list/get_item\",\"args\":{\"list\":[\"a\",\"b\"],\"position\":1.0},\"out\":{\"item\":\"r\"}}]").outputs().get("r"));
    }

    // ---- F005, F031: image search

    @Test void f005f031ImageSearchIgnoresEmptyAreasAndKeepsAllOutputs() throws Exception {
        Path w = tmp();
        BufferedImage logo = new BufferedImage(120, 60, BufferedImage.TYPE_INT_RGB);
        Graphics2D g = logo.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 120, 60); g.setColor(Color.RED); g.drawRect(2, 2, 115, 55); g.setColor(Color.BLACK); g.drawString("QA LOGO 42", 20, 35); g.dispose();
        BufferedImage page = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        g = page.createGraphics();
        g.setColor(Color.WHITE); g.fillRect(0, 0, 600, 400); g.setColor(Color.DARK_GRAY);
        for (int i = 0; i < 8; i++) g.drawString("My Agents   Folder " + i + "   text text", 20, 30 + i * 45);
        g.dispose();
        ImageIO.write(logo, "png", w.resolve("logo.png").toFile());
        ImageIO.write(page, "png", w.resolve("without.png").toFile());
        BufferedImage with = new BufferedImage(600, 400, BufferedImage.TYPE_INT_RGB);
        g = with.createGraphics(); g.drawImage(page, 0, 0, null); g.drawImage(logo, 300, 200, null); g.dispose();
        ImageIO.write(with, "png", w.resolve("with.png").toFile());
        BufferedImage tiny = page.getSubimage(0, 0, 100, 50);
        ImageIO.write(tiny, "png", w.resolve("tiny.png").toFile());
        ImagePackage img = new ImagePackage();
        com.oneofficeautomation.sdk.ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        Map<String, Object> absent = img.findInImage(ctx, w.resolve("without.png").toString(), w.resolve("logo.png").toString(), 10);
        assertEquals(false, absent.get("found"), "a light page without the logo is no match: " + absent);
        Map<String, Object> present = img.findInImage(ctx, w.resolve("with.png").toString(), w.resolve("logo.png").toString(), 10);
        assertEquals(true, present.get("found"));
        assertEquals(0, BigDecimal.valueOf(300).compareTo((BigDecimal) present.get("left")));
        Map<String, Object> larger = img.findInImage(ctx, w.resolve("tiny.png").toString(), w.resolve("logo.png").toString(), 10);
        assertEquals(false, larger.get("found"));
        assertTrue(larger.containsKey("left") && larger.containsKey("top"), "every declared output is there");
    }

    // ---- F019, F042: connectors refuse web pages and wait for Retry-After

    @Test void f019f042HtmlAnswersFailAndRetryAfterIsHonoured() throws Exception {
        java.util.concurrent.atomic.AtomicInteger calls = new java.util.concurrent.atomic.AtomicInteger();
        com.sun.net.httpserver.HttpServer srv = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        srv.createContext("/page", ex -> { byte[] b = "<html><body>Please sign in</body></html>".getBytes(); ex.getResponseHeaders().set("Content-Type", "text/html"); ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close(); });
        srv.createContext("/busy", ex -> {
            int n = calls.incrementAndGet();
            byte[] b = (n == 1 ? "{\"error\":\"slow down\"}" : "{\"ok\":true}").getBytes();
            if (n == 1) ex.getResponseHeaders().set("Retry-After", "1");
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(n == 1 ? 429 : 200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        srv.start();
        try {
            String base = "http://127.0.0.1:" + srv.getAddress().getPort();
            com.oneofficeautomation.sdk.ActionContext ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            com.oneofficeautomation.sdk.ActionException e = assertThrows(com.oneofficeautomation.sdk.ActionException.class,
                    () -> HttpJson.req("QA", "GET", base + "/page").send(ctx));
            assertEquals("QA.BAD_RESPONSE", e.code());
            assertEquals(Boolean.TRUE, HttpJson.req("QA", "GET", base + "/busy").send(ctx).map().get("ok"));
            assertEquals(2, calls.get(), "sent again after Retry-After");
            assertEquals(-1, HttpJson.retryAfterMillis(null, 0), "no Retry-After: fail at once (RATE_LIMITED, retryable)");
            assertEquals(-1, HttpJson.retryAfterMillis("3600", 0), "longer than a minute: not waited for inside the step");
        } finally { srv.stop(0); }
    }

    // ---- F023, F040, F046: XML

    @Test void f023f040f046XmlNamespacesDoctypesAndErrors() throws Exception {
        XmlPackage x = new XmlPackage();
        String ns = "<root xmlns:a=\"urn:a\" xmlns=\"urn:d\"><a:note id=\"1\">Hi</a:note><plain>p</plain></root>";
        assertEquals("Hi", x.getValue(null, "//a:note", ns, null, null, "text").get("value"));
        assertEquals("p", x.getValue(null, "//d:plain", ns, null, null, "text", Map.of("d", "urn:d")).get("value"));
        assertEquals("p", x.getValue(null, "//plain", ns, null, null, "text").get("value"), "XPaths without prefixes still match by name");
        assertEquals("XML.UNKNOWN_PREFIX", assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> x.getValue(null, "//zz:note", ns, null, null, "text")).code());
        assertEquals("Acme", x.getValue(null, "/n/to", "<?xml version=\"1.0\"?><!DOCTYPE n [<!ENTITY co \"Acme\">]><n><to>&co;</to></n>", null, null, "text").get("value"));
        assertEquals("XML.EXTERNAL_ENTITY_NOT_ALLOWED", assertThrows(com.oneofficeautomation.sdk.ActionException.class,
                () -> x.getValue(null, "/x", "<!DOCTYPE x [<!ENTITY s SYSTEM \"file:///etc/passwd\">]><x>&s;</x>", null, null, "text")).code());
        String msg = assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> x.getValue(null, "//a[", "<a/>", null, null, "text")).getMessage();
        assertFalse(msg.contains("javax.") || msg.contains("Exception"), msg);
    }

    // ---- F025, F036: JSON paths

    @Test void f025f036JsonTextAndJsonPath() {
        JsonPackage j = new JsonPackage();
        assertEquals(new BigDecimal("2"), j.getNode(null, "{\"a\":{\"b\":2}}", "a.b", null, false).get("result"));
        Map<String, Object> doc = Map.of("order", Map.of("items", List.of(Map.of("sku", "A", "qty", new BigDecimal("1")), Map.of("sku", "B", "qty", new BigDecimal("3")))));
        assertEquals(new BigDecimal("1"), j.getNode(null, doc, "$.order.items[0].qty", null, false).get("result"));
        assertEquals(List.of("A", "B"), j.getNode(null, doc, "$..sku", null, false).get("result"));
        assertEquals(List.of("B"), j.getNode(null, doc, "$.order.items[?(@.qty > 1)].sku", null, false).get("result"));
        assertEquals("B", j.getNode(null, doc, "order.items[-1].sku", null, false).get("result"));
    }

    // ---- F033: read-only database sessions

    @Test void f033ReadOnlySessionsRefuseChanges() throws Exception {
        Path w = tmp();
        String url = "jdbc:h2:" + w.resolve("t").toString().replace('\\', '/');
        RunResult setup = flow(w, "[]", "[{\"id\":\"c\",\"action\":\"ooa.database/connect\",\"args\":{\"jdbc_url\":\"" + url + "\"}},"
                + "{\"id\":\"e\",\"action\":\"ooa.database/execute\",\"args\":{\"sql\":\"CREATE TABLE t(id INT); INSERT INTO t VALUES (1)\"}},{\"id\":\"d\",\"action\":\"ooa.database/disconnect\",\"args\":{}}]");
        assertTrue(setup.ok(), String.valueOf(setup.error()));
        String ro = "{\"id\":\"c\",\"action\":\"ooa.database/connect\",\"args\":{\"jdbc_url\":\"" + url + "\",\"read_only\":true}}";
        assertEquals("DB.READ_ONLY", code(flow(w, "[]", "[" + ro + ",{\"id\":\"e\",\"action\":\"ooa.database/execute\",\"args\":{\"sql\":\"DELETE FROM t\"}}]")));
        RunResult q = flow(w, OUT, "[" + ro + ",{\"id\":\"q\",\"action\":\"ooa.database/query\",\"args\":{\"sql\":\"SELECT id FROM t WHERE 'delete me' <> ''\"},\"out\":{\"row_count\":\"r\"}}]");
        assertTrue(q.ok(), "a SELECT with such words in quotes still reads: " + q.error());
    }

    // ---- F037, F039: Sum column and RSA rotation

    @Test void f037f039SumColumnAndKeyRotation() throws Exception {
        DataTablePackage t = new DataTablePackage();
        Map<String, Object> table = com.oneofficeautomation.runner.Values.table(List.of("Amount"), List.of(List.of("1,000.50"), List.of("x"), Arrays.asList((Object) null), List.of(new BigDecimal("2"))));
        com.oneofficeautomation.sdk.ActionException e = assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> t.sumColumn(null, table, "Amount", "error", "en-US"));
        assertTrue(e.getMessage().contains("Row 2"), e.getMessage());
        Map<String, Object> s = t.sumColumn(null, table, "Amount", "skip", "en-US");
        assertEquals(0, new BigDecimal("1002.50").compareTo((BigDecimal) s.get("total")));
        assertEquals(0, BigDecimal.ONE.compareTo((BigDecimal) s.get("skipped")));
        Path w = tmp();
        com.oneofficeautomation.sdk.ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        CryptoPackage c = new CryptoPackage();
        c.rsaGenerate(ctx, "keys", "error", 2048);
        Map<String, Object> rotated = c.rsaGenerate(ctx, "keys", "rotate", 2048);
        assertTrue(Files.exists(Path.of(String.valueOf(rotated.get("backup_private_key")))), "the old key is kept");
    }

    // ---- F050: capabilities

    @Test void f050FileInputsDeclareTheFilesystemCapability() {
        for (Class<?> cls : FirstPartyPackages.ALL) {
            var info = com.oneofficeautomation.sdk.PackageIntrospector.inspect(cls);
            List<com.oneofficeautomation.sdk.ActionSpec> all = new ArrayList<>(info.actions().values());
            all.addAll(info.conditions().values());
            all.addAll(info.iterators().values());
            for (var spec : all)
                if (spec.inputs().stream().anyMatch(i -> i.type() == com.oneofficeautomation.sdk.OoaType.FILE))
                    assertTrue(spec.capabilities().contains("filesystem"), info.meta().id() + "/" + spec.id() + " takes a file but lacks filesystem");
        }
    }
}
