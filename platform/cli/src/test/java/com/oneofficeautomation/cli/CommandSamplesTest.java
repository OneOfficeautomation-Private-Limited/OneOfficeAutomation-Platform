package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.DynamicTest;
import org.junit.jupiter.api.TestFactory;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sample tasks, one or more per command: a small agent runs through the real Runner and the variables are checked
 * afterwards (the "state before and after" of the command comparison in docs/research/command-comparison.md).
 *
 * <p>Runs in the cli module because its test classpath has every toolkit, including the optional Office, Email, PDF,
 * Browser, Transfer and Windows modules. Cases live in {@code src/test/resources/command-samples/*.json} as a list of
 * {@code {"command", "name"?, "vars"?: {name: value}, "files"?: {relative path: text}, "agents"?: {id: child flow}, "steps": [...],
 * "expect"?: {variable: value}, "expectFiles"?: {relative path: text | true | false}, "error"?: "CODE"}}.
 * Every variable named in vars or in a step's out map is declared (type Any). The text {@code {tmp}} anywhere in a
 * case is replaced by the case's temporary folder (with forward slashes). Results are written to
 * {@code build/command-samples-results.json} for the progress table. {@code {web}} is the case folder served over HTTP;
 * {@code "requires": "browser"} skips a case when no browser can start, {@code "requires": "desktop"} runs it only with
 * OOA_DESKTOP_SAMPLES=1 because it drives real windows on the screen.
 */
class CommandSamplesTest {
    @TempDir static Path tmpRoot;
    static final List<Map<String, Object>> RESULTS = Collections.synchronizedList(new ArrayList<>());
    /** Serves the case folders over HTTP, so {web} in a case is the address of its own folder. */
    static com.sun.net.httpserver.HttpServer web;
    static Boolean browserWorks;

    @org.junit.jupiter.api.AfterAll
    static void stopWeb() { if (web != null) web.stop(0); }

    static String webBase() throws Exception {
        if (web == null) {
            web = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            web.createContext("/", ex -> {
                Path f = tmpRoot.resolve(ex.getRequestURI().getPath().substring(1)).normalize();
                byte[] body = f.startsWith(tmpRoot) && Files.isRegularFile(f) ? Files.readAllBytes(f) : null;
                ex.getResponseHeaders().set("Content-Type", f.toString().endsWith(".html") ? "text/html; charset=utf-8" : "application/octet-stream");
                ex.sendResponseHeaders(body == null ? 404 : 200, body == null ? -1 : body.length);
                if (body != null) ex.getResponseBody().write(body);
                ex.close();
            });
            web.start();
        }
        return "http://127.0.0.1:" + web.getAddress().getPort();
    }

    static boolean onPath(String program) {
        try {
            Process p = new ProcessBuilder(program, "--version").redirectErrorStream(true).start();
            p.getInputStream().readAllBytes();
            return p.waitFor() == 0;
        } catch (Exception e) { return false; }
    }

    /** Whether a headless browser can start here (it cannot in some shells and CI images); browser cases are skipped otherwise. */
    static synchronized boolean browserWorks(Runner runner) {
        if (browserWorks == null) {
            RunResult r = runner.run(Map.of("kind", "TaskBot", "id", "probe", "name", "probe", "variables", List.of(), "steps", List.of(
                    Map.of("id", "o", "action", "ooa.browser/open", "args", Map.of("url", "about:blank", "headless", true, "engine", "playwright", "session", "probe")),
                    Map.of("id", "c", "action", "ooa.browser/close", "args", Map.of("session", "probe")))), Map.of());
            browserWorks = r.ok();
            if (!r.ok()) System.out.println("    browser samples skipped: " + r.error().get("message"));
        }
        return browserWorks;
    }

    @TestFactory
    Stream<DynamicTest> samples() throws Exception {
        Path dir = Path.of("src/test/resources/command-samples");
        List<DynamicTest> tests = new ArrayList<>();
        Runner runner = Runner.builder(FirstPartyPackages.registry()).workDir(tmpRoot).build();
        try (Stream<Path> files = Files.list(dir)) {
            for (Path f : files.filter(p -> p.toString().endsWith(".json")).sorted().toList()) {
                List<?> cases = (List<?>) JsonLite.parse(Files.readString(f, StandardCharsets.UTF_8));
                int n = 0;
                for (Object c : cases) {
                    @SuppressWarnings("unchecked") Map<String, Object> cs = (Map<String, Object>) c;
                    String label = cs.get("command") + (cs.containsKey("name") ? " - " + cs.get("name") : "") + " (" + f.getFileName() + "#" + (n++) + ")";
                    tests.add(DynamicTest.dynamicTest(label, () -> runCase(runner, cs, label)));
                }
            }
        }
        tests.add(DynamicTest.dynamicTest("write results", CommandSamplesTest::writeResults));
        return tests.stream();
    }

    @SuppressWarnings("unchecked")
    static void runCase(Runner runner, Map<String, Object> raw, String label) throws Exception {
        String requires = String.valueOf(raw.getOrDefault("requires", ""));
        String skip = requires.equals("desktop") && !"1".equals(System.getenv("OOA_DESKTOP_SAMPLES")) ? "desktop sample: set OOA_DESKTOP_SAMPLES=1 to run it (it opens windows on the screen)"
                : requires.equals("browser") && !browserWorks(runner) ? "no browser can start here"
                : requires.equals("node") && !onPath("node") ? "Node.js is not installed here" : null;
        if (skip != null) {
            RESULTS.add(new LinkedHashMap<>(Map.of("command", raw.get("command"), "name", raw.getOrDefault("name", ""), "check", "skipped", "detail", skip)));
            org.junit.jupiter.api.Assumptions.abort(skip);
        }
        Path tmp = Files.createTempDirectory(tmpRoot, "case");
        // requires "mail": a fresh in-memory SMTP/IMAP server per case, with one mailbox (MAIL_USER) and the test
        // password in an in-memory vault as test/mail/password
        com.icegreen.greenmail.util.GreenMail mail = null;
        if (requires.equals("mail")) {
            mail = new com.icegreen.greenmail.util.GreenMail(new com.icegreen.greenmail.util.ServerSetup[]{
                    new com.icegreen.greenmail.util.ServerSetup(0, "127.0.0.1", "smtp"), new com.icegreen.greenmail.util.ServerSetup(0, "127.0.0.1", "imap")});
            mail.start();
            mail.setUser(MAIL_USER, MAIL_USER, MAIL_PASSWORD);
        }
        // "mock": [{"method", "path" (prefix; * at the end), "status", "body"}] answers in order of the list; {mock} is its address
        com.sun.net.httpserver.HttpServer mockServer = null;
        List<String> sent = Collections.synchronizedList(new ArrayList<>());
        if (raw.get("mock") instanceof List<?> routes) {
            mockServer = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
            mockServer.createContext("/", ex -> {
                String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
                String path = ex.getRequestURI().getPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
                sent.add(ex.getRequestMethod() + " " + path + (body.isEmpty() ? "" : " " + body));
                Map<String, Object> hit = null;
                for (Object o : routes) {
                    Map<String, Object> rt = (Map<String, Object>) o;
                    String want = String.valueOf(rt.getOrDefault("path", "*"));
                    boolean pathOk = want.endsWith("*") ? ex.getRequestURI().getPath().startsWith(want.substring(0, want.length() - 1)) : ex.getRequestURI().getPath().equals(want);
                    if (pathOk && String.valueOf(rt.getOrDefault("method", ex.getRequestMethod())).equalsIgnoreCase(ex.getRequestMethod())) { hit = rt; break; }
                }
                Object b = hit == null ? Map.of("error", "no mock route") : hit.getOrDefault("body", Map.of());
                byte[] out = (b instanceof String str ? str : JsonLite.write(b)).getBytes(StandardCharsets.UTF_8);
                ex.getResponseHeaders().set("Content-Type", b instanceof String ? "text/plain" : "application/json");
                ex.sendResponseHeaders(hit == null ? 404 : ((Number) hit.getOrDefault("status", 200)).intValue(), out.length == 0 ? -1 : out.length);
                if (out.length > 0) ex.getResponseBody().write(out);
                ex.close();
            });
            mockServer.start();
        }
        String mockBase = mockServer == null ? "" : "http://127.0.0.1:" + mockServer.getAddress().getPort();
        // requires "ldap": an in-memory directory with DC=acme,DC=test, OU=Staff and the admin CN=admin,DC=acme,DC=test
        // (password test/mail/password); {ldap} is its address. Schema checks are off, so AD attributes are accepted.
        com.unboundid.ldap.listener.InMemoryDirectoryServer ldap = null;
        if (requires.equals("ldap")) {
            var cfg = new com.unboundid.ldap.listener.InMemoryDirectoryServerConfig("DC=acme,DC=test");
            cfg.setSchema(null);
            cfg.addAdditionalBindCredentials("CN=admin,DC=acme,DC=test", MAIL_PASSWORD);
            cfg.setListenerConfigs(com.unboundid.ldap.listener.InMemoryListenerConfig.createLDAPConfig("default", java.net.InetAddress.getLoopbackAddress(), 0, null));
            ldap = new com.unboundid.ldap.listener.InMemoryDirectoryServer(cfg);
            ldap.add("dn: DC=acme,DC=test", "objectClass: top", "objectClass: domain", "dc: acme");
            ldap.add("dn: OU=Staff,DC=acme,DC=test", "objectClass: top", "objectClass: organizationalUnit", "ou: Staff");
            ldap.startListening();
        }
        String ldapUrl = ldap == null ? "" : "ldap://127.0.0.1:" + ldap.getListenPort();
        final com.unboundid.ldap.listener.InMemoryDirectoryServer ldapServer = ldap;
        String json = JsonLite.write(raw).replace("{mock}", mockBase).replace("{ldap}", ldapUrl).replace("{tmp}", tmp.toString().replace('\\', '/'))
                .replace("{web}", JsonLite.write(raw).contains("{web}") ? webBase() + "/" + tmp.getFileName() : "")
                .replace("\"{smtp_port}\"", mail == null ? "0" : String.valueOf(mail.getSmtp().getPort()))
                .replace("\"{imap_port}\"", mail == null ? "0" : String.valueOf(mail.getImap().getPort()));
        try {
            runCase(runner, (Map<String, Object>) JsonLite.parse(json), label, tmp);
            // "expectRequests": ["METHOD /path text that must appear in the request line or body", ...] in order
            List<String> want = (List<String>) ((Map<String, Object>) JsonLite.parse(json)).getOrDefault("expectRequests", List.of());
            for (int i = 0; i < want.size(); i++) {
                String w = want.get(i);
                String[] parts = w.split(" ", 3);
                assertTrue(i < sent.size(), label + ": request " + (i + 1) + " (" + w + ") was not sent; sent " + sent);
                String got = sent.get(i);
                assertTrue(got.startsWith(parts[0] + " " + parts[1]) && (parts.length < 3 || got.contains(parts[2])), label + ": request " + (i + 1) + " expected '" + w + "' but was '" + got + "'");
            }
        } finally {
            if (mail != null) mail.stop();
            if (mockServer != null) mockServer.stop(0);
            if (ldapServer != null) ldapServer.shutDown(true);
        }
    }

    static final String MAIL_USER = "bot@sample.test", MAIL_PASSWORD = "sample-mail-pass";

    @SuppressWarnings("unchecked")
    static void runCase(Runner runner, Map<String, Object> cs, String label, Path tmp) throws Exception {
        for (Map.Entry<String, Object> e : ((Map<String, Object>) cs.getOrDefault("files", Map.of())).entrySet()) {
            Path p = tmp.resolve(e.getKey());
            Files.createDirectories(p.getParent());
            Files.writeString(p, String.valueOf(e.getValue()), StandardCharsets.UTF_8);
        }
        Map<String, Object> vars = (Map<String, Object>) cs.getOrDefault("vars", Map.of());
        List<Map<String, Object>> steps = (List<Map<String, Object>>) cs.get("steps");
        Set<String> names = new LinkedHashSet<>(vars.keySet());
        int[] id = {0};
        collect(steps, names, id);
        names.addAll(((Map<String, Object>) cs.getOrDefault("expect", Map.of())).keySet());
        List<Map<String, Object>> decl = new ArrayList<>();
        for (String name : names) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("name", name); v.put("type", "Any"); v.put("direction", "output");
            if (vars.containsKey(name)) v.put("default", vars.get(name));
            decl.add(v);
        }
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("kind", "TaskBot"); flow.put("id", "sample"); flow.put("name", label.length() > 190 ? label.substring(0, 190) : label);
        flow.put("variables", decl); flow.put("steps", steps);
        // "credentials": {variable: "locker/credential/attribute"} become Credential inputs read from a test vault
        Map<String, Object> inputs = new LinkedHashMap<>();
        for (Map.Entry<String, Object> e : ((Map<String, Object>) cs.getOrDefault("credentials", Map.of())).entrySet()) {
            decl.add(new LinkedHashMap<>(Map.of("name", e.getKey(), "type", "Credential", "direction", "input")));
            inputs.put(e.getKey(), Map.of("$credential", e.getValue()));
        }
        boolean agents = cs.get("agents") instanceof Map<?, ?>;
        if (agents || !inputs.isEmpty()) {
            // "secrets": {"locker/credential/attribute": test value} for credential inputs that refuse plain text
            var vault = new com.oneofficeautomation.runner.CredentialVault.InMemory().put("test", "mail", "password", MAIL_PASSWORD);
            for (Map.Entry<String, Object> e : ((Map<String, Object>) cs.getOrDefault("secrets", Map.of())).entrySet()) {
                String[] ref = e.getKey().split("/");
                vault.put(ref[0], ref[1], ref[2], String.valueOf(e.getValue()));
            }
            Runner.Builder b = Runner.builder(FirstPartyPackages.registry()).workDir(tmp).service("vault", vault);
            if (agents) b.service("repository", (com.oneofficeautomation.runner.AutomationRepository) (aid, version) -> (Map<String, Object>) ((Map<String, Object>) cs.get("agents")).get(aid));
            runner = b.build();
        }
        RunResult r = runner.run(flow, inputs);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("command", cs.get("command")); result.put("name", cs.getOrDefault("name", ""));
        Set<String> uses = new TreeSet<>();
        usedActions(steps, uses);
        result.put("uses", new ArrayList<>(uses));
        result.put("status", r.status());
        if (r.error() != null) result.put("error", r.error().get("code"));
        result.put("outputs", Values.toJsonable(r.outputs()));
        try {
            if (cs.containsKey("error")) {
                assertFalse(r.ok(), label + ": expected error " + cs.get("error"));
                assertEquals(cs.get("error"), r.error().get("code"), label + ": " + r.error().get("message"));
            } else {
                assertTrue(r.ok(), label + ": " + r.error());
                Object actual = Values.toJsonable(r.outputs());
                for (Map.Entry<String, Object> e : ((Map<String, Object>) cs.getOrDefault("expect", Map.of())).entrySet())
                    assertTrue(same(e.getValue(), ((Map<String, Object>) actual).get(e.getKey())),
                            label + ": $" + e.getKey() + " expected " + JsonLite.write(e.getValue()) + " but was " + JsonLite.write(((Map<String, Object>) actual).get(e.getKey())));
            }
            for (Map.Entry<String, Object> e : ((Map<String, Object>) cs.getOrDefault("expectFiles", Map.of())).entrySet()) {
                Path p = tmp.resolve(e.getKey());
                if (e.getValue() instanceof Boolean b) assertEquals(b, Files.exists(p), label + ": file " + e.getKey() + (b ? " missing" : " should not exist"));
                else assertEquals(String.valueOf(e.getValue()), Files.readString(p, StandardCharsets.UTF_8).replace("\r\n", "\n"), label + ": file " + e.getKey());
            }
            result.put("check", "pass");
        } catch (AssertionError e) {
            result.put("check", "fail"); result.put("detail", e.getMessage());
            throw e;
        } finally {
            RESULTS.add(result);
        }
    }

    @SuppressWarnings("unchecked")
    static void collect(List<Map<String, Object>> steps, Set<String> names, int[] id) {
        if (steps == null) return;
        for (Map<String, Object> s : steps) {
            s.putIfAbsent("id", "s" + (++id[0]));
            if (s.get("out") instanceof Map<?, ?> out) for (Object v : out.values()) names.add(String.valueOf(v));
            Map<String, Object> a = (Map<String, Object>) s.getOrDefault("args", Map.of());
            if ("core/set".equals(s.get("action")) && a.get("variable") instanceof String v) names.add(v);
            for (String k : List.of("as", "index")) if ("core/loop".equals(s.get("action")) && a.get(k) instanceof String v) names.add(v);
            for (String k : List.of("children", "else", "finally")) collect((List<Map<String, Object>>) s.get(k), names, id);
            for (String k : List.of("elseIf", "catch")) if (s.get(k) instanceof List<?> l) for (Object b : l) {
                Map<String, Object> bm = (Map<String, Object>) b;
                if (bm.get("as") instanceof String v) names.add(v);
                if (bm.get("message_to") instanceof String v) names.add(v);
                collect((List<Map<String, Object>>) bm.get("children"), names, id);
            }
        }
    }

    @SuppressWarnings("unchecked")
    static void usedActions(List<Map<String, Object>> steps, Set<String> uses) {
        if (steps == null) return;
        for (Map<String, Object> s : steps) {
            uses.add(String.valueOf(s.get("action")));
            Map<String, Object> a = (Map<String, Object>) s.getOrDefault("args", Map.of());
            if (a.get("iterator") instanceof String it && it.contains("/")) uses.add(it + " (iterator)");
            if (a.get("condition") instanceof Map<?, ?> c && c.get("condition") instanceof String cid) uses.add(cid + " (condition)");
            for (String k : List.of("children", "else", "finally")) usedActions((List<Map<String, Object>>) s.get(k), uses);
            for (String k : List.of("elseIf", "catch")) if (s.get(k) instanceof List<?> l)
                for (Object b : l) usedActions((List<Map<String, Object>>) ((Map<String, Object>) b).get("children"), uses);
        }
    }

    /** JSON-style equality: numbers by value, maps and lists element by element, a {"$contains": text} matcher. */
    static boolean same(Object want, Object got) {
        if (want instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("$contains")) return got != null && String.valueOf(got).contains(String.valueOf(m.get("$contains")));
        if (want instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("$type")) return got != null && typeOf(got).equals(m.get("$type"));
        if (want instanceof BigDecimal a && got instanceof Number b) return a.compareTo(new BigDecimal(b.toString())) == 0;
        if (want instanceof List<?> a && got instanceof List<?> b) {
            if (a.size() != b.size()) return false;
            for (int i = 0; i < a.size(); i++) if (!same(a.get(i), b.get(i))) return false;
            return true;
        }
        if (want instanceof Map<?, ?> a && got instanceof Map<?, ?> b) {
            if (!a.keySet().equals(b.keySet())) return false;
            for (Object k : a.keySet()) if (!same(a.get(k), b.get(k))) return false;
            return true;
        }
        return Objects.equals(want, got) || (want != null && got != null && !(got instanceof Map) && !(got instanceof List) && String.valueOf(want).equals(String.valueOf(got)));
    }

    static String typeOf(Object o) {
        return o instanceof Map ? "object" : o instanceof List ? "array" : o instanceof Number ? "number" : o instanceof Boolean ? "boolean" : "string";
    }

    static void writeResults() throws Exception {
        List<Map<String, Object>> sorted = new ArrayList<>(RESULTS);
        sorted.sort(Comparator.comparing(m -> String.valueOf(m.get("command"))));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/command-samples-results.json"), JsonLite.write(sorted), StandardCharsets.UTF_8);
    }
}
