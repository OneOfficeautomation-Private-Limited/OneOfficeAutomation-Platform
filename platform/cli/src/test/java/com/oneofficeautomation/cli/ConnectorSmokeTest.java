package com.oneofficeautomation.cli;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.*;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every command of the cloud and enterprise connectors runs once against a local catch-all server that answers each
 * request with 200 and an empty JSON object. Inputs get typed sample values; address inputs (base_url, instance_url,
 * site_url ...) point at the local server and credentials come from a test vault. A command passes when it either
 * finishes or stops with one of its own error codes; it fails when the empty answer makes it crash (ACTION.UNEXPECTED),
 * which is what a changed or unexpected reply from a real service would do. Results go to
 * build/connector-smoke-results.json for the progress table (docs/research/command-comparison.md).
 */
class ConnectorSmokeTest {
    static final List<String> CONNECTORS = List.of("ooa.microsoft365", "ooa.google", "ooa.jira", "ooa.servicenow", "ooa.salesforce", "ooa.slack",
            "ooa.workday", "ooa.asana", "ooa.azure_devops", "ooa.box", "ooa.docusign", "ooa.github", "ooa.okta", "ooa.zendesk", "ooa.databricks",
            "ooa.snowflake", "ooa.kafka", "ooa.active_directory", "ooa.sap", "ooa.terminal", "ooa.genai", "ooa.ai_agent", "ooa.ai_skills",
            "ooa.web_agent", "ooa.connector", "ooa.excel_desktop", "ooa.pgp", "ooa.file_transfer", "ooa.desktop_ui", "ooa.ocr");
    static final Set<String> ADDRESS = Set.of("base_url", "organization_url", "workspace_url", "site_url", "org_url", "instance_url", "account_url",
            "endpoint_override", "login_url", "upload_url");
    static final List<String> TOKEN_ORDER = List.of("access_token", "api_token", "bot_token", "token", "personal_access_token", "api_key", "oauth_token", "password", "credential");

    @TempDir static Path tmp;
    static HttpServer server;
    static final List<String> requests = Collections.synchronizedList(new ArrayList<>());
    static final List<Map<String, Object>> RESULTS = Collections.synchronizedList(new ArrayList<>());

    @BeforeAll
    static void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath());
            ex.getRequestBody().readAllBytes();
            byte[] body = "{}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length);
            ex.getResponseBody().write(body);
            ex.close();
        });
        server.start();
        Files.writeString(tmp.resolve("in.txt"), "sample");
    }

    @AfterAll
    static void stop() throws Exception {
        if (server != null) server.stop(0);
        RESULTS.sort(Comparator.comparing(m -> String.valueOf(m.get("command"))));
        Files.createDirectories(Path.of("build"));
        Files.writeString(Path.of("build/connector-smoke-results.json"), JsonLite.write(new ArrayList<>(RESULTS)), StandardCharsets.UTF_8);
    }

    @TestFactory
    Stream<DynamicTest> everyConnectorCommand() {
        PackageRegistry reg = FirstPartyPackages.registry();
        List<DynamicTest> tests = new ArrayList<>();
        Runner runner = Runner.builder(reg).workDir(tmp).service("vault", new CredentialVault.InMemory().put("test", "api", "token", "sample-token")).build();
        for (String pid : CONNECTORS) {
            PackageRegistry.LoadedPackage pkg;
            try { pkg = reg.resolve(pid, null); } catch (RuntimeException e) { continue; }   // optional module not installed
            for (ActionSpec spec : pkg.info().actions().values())
                tests.add(DynamicTest.dynamicTest(pid + "/" + spec.id(), () -> smoke(runner, pid, spec)));
        }
        return tests.stream();
    }

    static void smoke(Runner runner, String pid, ActionSpec spec) {
        Map<String, Object> args = new LinkedHashMap<>();
        String base = "http://127.0.0.1:" + server.getAddress().getPort();
        boolean credentialGiven = false;
        List<ActionSpec.InputSpec> ordered = new ArrayList<>(spec.inputs());
        ordered.sort(Comparator.comparingInt(i -> TOKEN_ORDER.contains(i.name()) ? TOKEN_ORDER.indexOf(i.name()) : 99));
        for (ActionSpec.InputSpec in : ordered) {
            if (ADDRESS.contains(in.name())) { args.put(in.name(), base); continue; }
            if (in.type() == OoaType.CREDENTIAL) {
                if (!credentialGiven && (in.required() || TOKEN_ORDER.contains(in.name()))) { args.put(in.name(), "$tok"); credentialGiven = true; }
                continue;
            }
            if (!in.required() || in.defaultValue() != null) continue;
            args.put(in.name(), sample(in));
        }
        if (spec.input("host").isPresent() && !args.containsKey("host")) args.put("host", "127.0.0.1");
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("kind", "TaskBot"); flow.put("id", "smoke"); flow.put("name", "smoke");
        flow.put("variables", List.of(Map.of("name", "tok", "type", "Credential", "direction", "input")));
        flow.put("steps", List.of(new LinkedHashMap<>(Map.of("id", "s", "action", pid + "/" + spec.id(), "args", args, "timeoutSeconds", BigDecimal.valueOf(20)))));
        int before = requests.size();
        RunResult r = runner.run(flow, Map.of("tok", Map.of("$credential", "test/api/token")), new Runner.RunOptions().maxRunSeconds(30));
        String request = requests.size() > before ? requests.get(before) : "";
        String code = r.ok() ? "" : String.valueOf(r.error().get("code"));
        String verdict = r.ok() || !Set.of("ACTION.UNEXPECTED").contains(code) ? "pass" : "fail";
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("command", pid + "/" + spec.id()); res.put("request", request); res.put("status", r.status());
        res.put("error", code); res.put("message", r.ok() ? "" : String.valueOf(r.error().get("message")));
        res.put("verdict", verdict);
        RESULTS.add(res);
        assertEquals("pass", verdict, pid + "/" + spec.id() + " crashed on an empty reply: " + res.get("message") + " (request " + request + ")");
    }

    static Object sample(ActionSpec.InputSpec in) {
        if (!in.enumValues().isEmpty()) return in.enumValues().get(0);
        return switch (in.type()) {
            case NUMBER -> BigDecimal.ONE;
            case BOOLEAN -> false;
            case LIST -> List.of("x");
            case DICTIONARY, RECORD -> Map.of("x", "y");
            case TABLE -> Map.of("columns", List.of("x"), "rows", List.of(List.of("y")));
            case DATETIME -> "2026-01-01T00:00:00";
            case FILE -> tmp.resolve("in.txt").toString().replace('\\', '/');
            default -> in.name().contains("email") ? "a@b.test" : in.name().equals("host") ? "127.0.0.1" : "x";
        };
    }
}
