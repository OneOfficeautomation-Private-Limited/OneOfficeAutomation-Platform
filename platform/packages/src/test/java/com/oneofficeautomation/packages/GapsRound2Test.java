package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;

import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** Second round of Automation Anywhere gap items (calendar, logging, capture, services, JSON schema and more). */
class GapsRound2Test {
    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }
    @SuppressWarnings("unchecked") static Map<String, Object> m(Object o) { return (Map<String, Object>) o; }

    // ------------------------------------------------------------------ logging and images

    @Test void logEveryVariableOfAType() throws Exception {
        Path w = tmp();
        String vars = "[{\"name\":\"invoice\",\"type\":\"String\",\"default\":\"INV-7\"},{\"name\":\"customer\",\"type\":\"String\",\"default\":\"Acme\"},"
                + "{\"name\":\"total\",\"type\":\"Number\",\"default\":12.5},{\"name\":\"pw\",\"type\":\"Credential\"},{\"name\":\"n\",\"type\":\"Number\",\"direction\":\"output\"}]";
        var r = CommandTestFixesTest.flow(w, vars, "[{\"id\":\"l1\",\"action\":\"ooa.log/log_variables\",\"args\":{\"path\":\"s.log\",\"timestamp\":false,\"all_of_type\":\"String\"},\"out\":{\"lines\":\"n\"}},"
                + "{\"id\":\"l2\",\"action\":\"ooa.log/log_variables\",\"args\":{\"path\":\"all.log\",\"timestamp\":false,\"variables\":{\"note\":\"first\"},\"all_of_type\":\"all\"}}]");
        assertEquals(List.of("invoice=INV-7", "customer=Acme"), Files.readAllLines(w.resolve("s.log")), String.valueOf(r.error()));
        List<String> all = Files.readAllLines(w.resolve("all.log"));
        assertEquals("note=first", all.get(0));
        assertTrue(all.contains("total=12.5"), all.toString());
        assertTrue(all.stream().anyMatch(l -> l.startsWith("n=")), all.toString());
        var bad = CommandTestFixesTest.flow(w, vars, "[{\"id\":\"l\",\"action\":\"ooa.log/log_variables\",\"args\":{\"path\":\"x.log\",\"all_of_type\":\"Gizmo\"}}]");
        assertTrue(List.of("LOG.BAD_TYPE", "FLOW.INVALID").contains(CommandTestFixesTest.code(bad)), String.valueOf(bad.error()));
        var none = CommandTestFixesTest.flow(w, vars, "[{\"id\":\"l\",\"action\":\"ooa.log/log_variables\",\"args\":{\"path\":\"x.log\"}}]");
        assertTrue(List.of("LOG.NOTHING_TO_LOG", "FLOW.INVALID", "VALIDATION.FAILED").contains(CommandTestFixesTest.code(none)) || !none.ok(), String.valueOf(none.error()));
    }

    @Test void jpegQualityChangesTheFileSize() throws Exception {
        java.awt.image.BufferedImage img = new java.awt.image.BufferedImage(400, 300, java.awt.image.BufferedImage.TYPE_INT_ARGB);
        Random rnd = new Random(7);
        for (int y = 0; y < 300; y++) for (int x = 0; x < 400; x++) img.setRGB(x, y, 0xff000000 | rnd.nextInt(0xffffff));
        Path w = tmp();
        ImageFiles.write(img, "jpg", w.resolve("hi.jpg").toFile(), 95, null);
        ImageFiles.write(img, "jpg", w.resolve("lo.jpg").toFile(), 10, null);
        assertTrue(Files.size(w.resolve("lo.jpg")) < Files.size(w.resolve("hi.jpg")) / 2);
        assertNotNull(javax.imageio.ImageIO.read(w.resolve("lo.jpg").toFile()));
        assertEquals("IMAGE.BAD_QUALITY", code(() -> ImageFiles.write(img, "jpg", w.resolve("x.jpg").toFile(), 101, null)));
        ImageFiles.write(img, "tiff", w.resolve("g4.tif").toFile(), null, "ccitt_g4");
        assertEquals(1, javax.imageio.ImageIO.read(w.resolve("g4.tif").toFile()).getColorModel().getPixelSize());
    }

    // ------------------------------------------------------------------ JSON schema

    @SuppressWarnings("unchecked")
    @Test void jsonSchemaWithFileAndUrlReferencesAndFormats() throws Exception {
        Path w = tmp();
        Files.createDirectories(w.resolve("schemas/common"));
        Files.writeString(w.resolve("schemas/common/address.json"), """
                {"$defs": {"address": {"type": "object", "required": ["city"], "properties": {"city": {"type": "string", "minLength": 2}, "zip": {"$ref": "#/$defs/zip"}}},
                           "zip": {"type": "string", "pattern": "^[0-9]{5}$"}}}""");
        com.sun.net.httpserver.HttpServer http = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        http.createContext("/schemas/money.json", ex -> {
            byte[] b = "{\"$id\":\"MONEY\",\"$anchor\":\"money\",\"type\":\"object\",\"required\":[\"amount\",\"currency\"],\"properties\":{\"amount\":{\"type\":\"number\",\"minimum\":0},\"currency\":{\"enum\":[\"EUR\",\"USD\"]}}}".getBytes();
            ex.sendResponseHeaders(200, b.length); ex.getResponseBody().write(b); ex.close();
        });
        http.createContext("/schemas/missing.json", ex -> { ex.sendResponseHeaders(404, -1); ex.close(); });
        http.start();
        try {
            String url = "http://127.0.0.1:" + http.getAddress().getPort() + "/schemas/";
            Files.writeString(w.resolve("schemas/order.json"), """
                    {"$schema": "https://json-schema.org/draft/2020-12/schema", "type": "object",
                     "required": ["id", "ship_to", "total", "email", "placed"],
                     "properties": {
                       "id": {"type": "string", "format": "uuid"},
                       "email": {"type": "string", "format": "email"},
                       "placed": {"type": "string", "format": "date-time"},
                       "site": {"type": "string", "format": "hostname"},
                       "ip": {"format": "ipv4"},
                       "ship_to": {"$ref": "common/address.json#/$defs/address"},
                       "total": {"$ref": "%smoney.json"},
                       "lines": {"type": "array", "prefixItems": [{"const": "header"}], "items": {"type": "object"}, "contains": {"required": ["sku"]}},
                       "tags": {"type": "object", "propertyNames": {"pattern": "^[a-z_]+$"}, "patternProperties": {"^x_": {"type": "number"}}, "additionalProperties": {"type": "string"}},
                       "card": {"type": "string"}, "cvv": {"type": "string"},
                       "kind": {"enum": ["person", "company"]}, "vat": {"type": "string"}
                     },
                     "dependentRequired": {"card": ["cvv"]},
                     "if": {"properties": {"kind": {"const": "company"}}, "required": ["kind"]}, "then": {"required": ["vat"]}}
                    """.formatted(url));
            JsonPackage j = new JsonPackage();
            ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
            String good = """
                    {"id": "3f1c2a9e-1b2c-4d5e-8f90-123456789abc", "email": "ap@acme.example", "placed": "2026-11-02T10:15:00Z", "site": "erp.acme.example",
                     "ip": "10.0.0.5", "ship_to": {"city": "Berlin", "zip": "10115"}, "total": {"amount": 12.5, "currency": "EUR"},
                     "lines": ["header", {"sku": "A1"}], "tags": {"x_rank": 1, "note": "vip"}, "kind": "company", "vat": "DE123"}""";
            Map<String, Object> ok = j.validate(ctx, good, null, null, "schemas/order.json", true, true);
            assertEquals(true, ok.get("valid"), String.valueOf(ok.get("errors")));
            String bad = """
                    {"id": "not-a-uuid", "email": "ap@@acme", "placed": "2026-13-02T10:15:00Z", "site": "-bad-.example", "ip": "10.0.0.256",
                     "ship_to": {"city": "B", "zip": "1011"}, "total": {"amount": -1, "currency": "GBP"},
                     "lines": [{"sku": "A1"}], "tags": {"Note": "x", "x_rank": "high"}, "card": "4111", "kind": "company"}""";
            List<Object> errors = (List<Object>) j.validate(ctx, bad, null, null, "schemas/order.json", true, true).get("errors");
            String all = String.join("\n", errors.stream().map(String::valueOf).toList());
            for (String expect : List.of("$.id: \"not-a-uuid\" is not a valid uuid", "$.email:", "$.placed:", "$.site:", "$.ip:", "$.ship_to.city: shorter than 2",
                    "$.ship_to.zip: does not match pattern", "$.total.amount: less than 0", "$.total.currency:", "$.lines[0]: must be \"header\"",
                    "property name 'Note' is not allowed", "$.tags.x_rank: expected number", "property 'cvv' is required when 'card' is present", "missing required property 'vat'"))
                assertTrue(all.contains(expect), "missing: " + expect + "\n" + all);
            // formats can be switched off; then only the structural rules apply
            List<Object> noFormats = (List<Object>) j.validate(ctx, bad, null, null, "schemas/order.json", false, true).get("errors");
            assertTrue(noFormats.stream().noneMatch(e -> e.toString().contains("is not a valid")), noFormats.toString());
            // remote references can be switched off
            assertEquals("JSON.BAD_SCHEMA", code(() -> j.validate(ctx, good, null, null, "schemas/order.json", true, false)));
            // a broken URL or file is a typed error
            Files.writeString(w.resolve("schemas/broken.json"), "{\"$ref\":\"" + url + "missing.json\"}");
            assertEquals("JSON.REF_UNREACHABLE", code(() -> j.validate(ctx, "{}", null, null, "schemas/broken.json", true, true)));
            Files.writeString(w.resolve("schemas/nofile.json"), "{\"$ref\":\"common/none.json\"}");
            assertEquals("JSON.REF_NOT_FOUND", code(() -> j.validate(ctx, "{}", null, null, "schemas/nofile.json", true, true)));
            // an inline schema cannot point at a relative file, but can use its own anchors and $defs; loops are caught
            assertEquals("JSON.BAD_SCHEMA", code(() -> j.validate(ctx, "{}", null, "{\"$ref\":\"other.json\"}", null, true, true)));
            assertEquals(false, j.validate(ctx, "{\"a\":\"x\"}", null, "{\"properties\":{\"a\":{\"$ref\":\"#num\"}},\"$defs\":{\"n\":{\"$anchor\":\"num\",\"type\":\"number\"}}}", null, true, true).get("valid"));
            assertEquals("JSON.BAD_SCHEMA", code(() -> j.validate(ctx, "1", null, "{\"$defs\":{\"a\":{\"$ref\":\"#/$defs/b\"},\"b\":{\"$ref\":\"#/$defs/a\"}},\"$ref\":\"#/$defs/a\"}", null, true, true)));
            // draft-07 tuples and dependencies
            List<Object> d7 = JsonSchemaLite.validate(JsonLite.parse("{\"items\":[{\"type\":\"string\"}],\"additionalItems\":false,\"dependencies\":{}}"), List.of("a", "b"));
            assertEquals(List.of("$: no more than 1 items are allowed"), d7);
        } finally { http.stop(0); }
        // format details
        for (String okDate : List.of("2026-02-28T23:59:59.5+05:30", "2026-11-02t10:00:00z")) assertTrue(JsonSchemaLite.Formats.valid("date-time", okDate), okDate);
        for (String badDate : List.of("2026-02-30T10:00:00Z", "2026-11-02 10:00:00Z", "2026-11-02T25:00:00Z")) assertFalse(JsonSchemaLite.Formats.valid("date-time", badDate), badDate);
        assertTrue(JsonSchemaLite.Formats.valid("duration", "P1DT2H"));
        assertFalse(JsonSchemaLite.Formats.valid("duration", "PT"));
        assertTrue(JsonSchemaLite.Formats.valid("ipv6", "2001:db8::1"));
        assertFalse(JsonSchemaLite.Formats.valid("ipv6", "2001:db8::g1"));
        assertFalse(JsonSchemaLite.Formats.valid("ipv6", "12345::1"));
        assertTrue(JsonSchemaLite.Formats.valid("uri", "https://acme.example/a?b=1"));
        assertFalse(JsonSchemaLite.Formats.valid("uri", "/relative/path"));
        assertTrue(JsonSchemaLite.Formats.valid("json-pointer", "/a~1b/0"));
        assertFalse(JsonSchemaLite.Formats.valid("regex", "(unclosed"));
        assertTrue(JsonSchemaLite.Formats.valid("made-up-format", "anything"), "unknown formats pass");
    }

    // ------------------------------------------------------------------ AI agent: forms as tools and the toxicity guardrail

    @Test void toxicityRatings() {
        assertEquals("none", Toxicity.rate("Please send the invoice for order 4711 by Friday.", null).level());
        assertEquals("low", Toxicity.rate("This damn printer is crap again", null).level());
        assertEquals("medium", Toxicity.rate("You are such an idiot, fix it", null).level());
        assertEquals("high", Toxicity.rate("I will kill you if this happens again", null).level());
        assertEquals("high", Toxicity.rate("k.y.s", null).level(), "spaced letters are undone");
        assertEquals("medium", Toxicity.rate("y0u are a m0r0n", null).level(), "l33t spelling is undone");
        Toxicity.Rating custom = Toxicity.rate("Our price beats Globex easily", List.of("globex"));
        assertEquals("high", custom.level());
        assertEquals(List.of("blocked_term"), custom.categories());
        assertFalse(Toxicity.rate("Assessment of the classic glass bass", null).atLeast("low"), "no matches inside longer words");
        assertTrue(Toxicity.rate("shut up", null).atLeast("medium"));
        assertEquals("AGENT.BAD_OPTION", code(() -> Toxicity.setting("loud", null, null, "AGENT")));
    }

    @SuppressWarnings("unchecked")
    @Test void agentAsksAPersonWithAFormAndGuardsToxicity() throws Exception {
        Path w = tmp();
        Files.createDirectories(w.resolve("Forms"));
        Files.writeString(w.resolve("Forms/approve.form.json"), "{\"id\":\"approve_refund\",\"name\":\"Approve refund\",\"description\":\"Ask a supervisor to approve a refund.\","
                + "\"fields\":[{\"id\":\"amount\",\"type\":\"number\",\"label\":\"Amount\"},{\"id\":\"decision\",\"type\":\"dropdown\",\"label\":\"Decision\",\"options\":[\"approve\",\"reject\"]},"
                + "{\"id\":\"t\",\"type\":\"heading\",\"label\":\"Refund\"}]}");
        List<Map<String, Object>> asked = new ArrayList<>();
        AiAgentPackage.FormAsker before = AiAgentPackage.formAsker;
        AiAgentPackage.formAsker = (ctx, form, values, timeout) -> {
            asked.add(Map.of("form", form.name(), "values", values, "timeout", timeout));
            return Map.of("values", Map.of("amount", new BigDecimal("40"), "decision", "approve"), "button", "submit", "submitted", true);
        };
        SecureString key = new SecureString("sk-test", "k");
        try (ConnectorsTest.Fake f = new ConnectorsTest.Fake()) {
            int[] turn = {0};
            List<String> bodies = new ArrayList<>();
            f.on("POST /v1/chat/completions", c -> {
                bodies.add(c.text());
                turn[0]++;
                if (turn[0] == 1) return ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":null,\"tool_calls\":[{\"id\":\"c1\",\"type\":\"function\",\"function\":{\"name\":\"ask_approve_refund\",\"arguments\":\"{\\\"amount\\\":40}\"}}]}}]}");
                return ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":\"The supervisor approved the refund of 40.\"}}]}");
            });
            ActionContext ctx = PackagesTestSupport.ctx(w, Map.of("repository", (com.oneofficeautomation.runner.AutomationRepository) (id, v) -> { throw new com.oneofficeautomation.sdk.OoaException("REPO.NOT_FOUND", id); },
                    "bot_invoker", (com.oneofficeautomation.runner.BotInvoker) (id, in) -> null));
            AiAgentPackage a = new AiAgentPackage();
            Map<String, Object> r = a.run(ctx, "Customer asks for a 40 EUR refund on order 9", "Ask a supervisor first.", null, null, "openai", null, "m", key, f.url() + "/v1",
                    4, 60000, false, null, null, null, null, null, false, 0, 0, List.of("Forms/approve.form.json"), 120, "block", "medium", null);
            assertEquals("completed", r.get("status"), String.valueOf(r));
            assertEquals("The supervisor approved the refund of 40.", r.get("answer"));
            assertEquals(1, asked.size());
            assertEquals(Map.of("amount", new BigDecimal(40)), asked.get(0).get("values"), "the model's values are filled in first");
            assertEquals(120.0, asked.get(0).get("timeout"));
            assertTrue(bodies.get(0).contains("ask_approve_refund") && bodies.get(0).contains("Ask a supervisor to approve a refund."), "the form is offered as a tool");
            assertTrue(bodies.get(1).contains("\\\"decision\\\":\\\"approve\\\"") || bodies.get(1).contains("approve"), "the person's answer goes back to the model");
            assertTrue(String.valueOf(r.get("trace")).contains("submitted"));

            // a toxic goal is not acted on; nothing reaches the model
            int calls = f.calls.size();
            Map<String, Object> b = a.run(ctx, "Tell that idiot customer you will kill him", "x", null, null, "openai", null, "m", key, f.url() + "/v1",
                    4, 60000, false, null, null, null, null, null, false, 0, 0, List.of("Forms/approve.form.json"), 120, "block", "medium", null);
            assertEquals("blocked", b.get("status"));
            assertEquals(calls, f.calls.size());
            // a toxic answer stops the agent (block) or is only recorded (log)
            f.on("POST /v1/chat/completions", c -> ConnectorsTest.ok("{\"choices\":[{\"message\":{\"content\":\"Tell him to shut up, he is a moron.\"}}]}"));
            Map<String, Object> c1 = a.run(ctx, "Reply to the complaint", "x", null, null, "openai", null, "m", key, f.url() + "/v1",
                    4, 60000, false, null, null, null, null, null, false, 0, 0, List.of("Forms/approve.form.json"), 120, "block", "medium", null);
            assertEquals("blocked", c1.get("status"));
            Map<String, Object> c2 = a.run(ctx, "Reply to the complaint", "x", null, null, "openai", null, "m", key, f.url() + "/v1",
                    4, 60000, false, null, null, null, null, null, false, 0, 0, List.of("Forms/approve.form.json"), 120, "log", "medium", null);
            assertEquals("completed", c2.get("status"));
            assertTrue(String.valueOf(c2.get("trace")).contains("toxicity in the model's answer"));
            assertEquals("AGENT.FORM_NOT_FOUND", code(() -> a.run(ctx, "g", "x", null, null, "openai", null, "m", key, f.url() + "/v1",
                    4, 60000, false, null, null, null, null, null, false, 0, 0, List.of("Forms/none.form.json"), 120, "off", "medium", null)));
            assertEquals("AGENT.NO_TOOLS", code(() -> a.run(ctx, "g", "x", null, null, "openai", null, "m", key, f.url() + "/v1",
                    4, 60000, false, null, null, null, null, null, false, 0, 0, null, 120, "off", "medium", null)));
        } finally { AiAgentPackage.formAsker = before; }
        // nobody at the runner: the form is reported as unavailable
        List<Object> trace = new ArrayList<>();
        String unavailable = AiAgentPackage.askForm(PackagesTestSupport.ctx(w, Map.of()), AiAgentPackage.formTool(PackagesTestSupport.ctx(w, Map.of()), "Forms/approve.form.json"), Map.of(), 10, trace);
        if (java.awt.GraphicsEnvironment.isHeadless()) assertTrue(unavailable.contains("unavailable"), unavailable);
    }

    // ------------------------------------------------------------------ Active Directory path builder

    @Test void activeDirectoryPathsToDistinguishedNames() {
        ActiveDirectoryPackage ad = new ActiveDirectoryPackage();
        ActionContext ctx = PackagesTestSupport.ctx(Path.of("."), Map.of());
        assertEquals(Map.of("dn", "OU=EMEA,OU=Sales,OU=Corp,DC=corp,DC=example,DC=com", "parent_dn", "OU=Sales,OU=Corp,DC=corp,DC=example,DC=com"),
                ad.buildDnAction(ctx, "Corp/Sales/EMEA", "corp.example.com", null, null, null));
        assertEquals("CN=Lee\\, Ann,OU=Staff,DC=corp,DC=example,DC=com", ad.buildDnAction(ctx, "corp.example.com/Staff", null, "Lee, Ann", "CN", "auto").get("dn"), "the domain may start the path; commas are escaped");
        assertEquals("CN=PC-0042,CN=Computers,DC=corp,DC=local", ad.buildDnAction(ctx, "Computers", "corp.local", "PC-0042", null, null).get("dn"), "built-in containers are CN=");
        assertEquals("OU=Computers,DC=corp,DC=local", ad.buildDnAction(ctx, "Computers", "corp.local", null, null, "ou").get("dn"));
        assertEquals("DC=corp,DC=local", ad.buildDnAction(ctx, "", "corp.local", null, null, null).get("dn"));
        assertEquals("AD.BAD_DN", code(() -> ad.buildDnAction(ctx, "Corp/Sales", null, null, null, null)));
        assertEquals("AD.BAD_DN", code(() -> ad.buildDnAction(ctx, "Corp", "not a domain", null, null, null)));
        assertEquals("AD.BAD_OPTION", code(() -> ad.buildDnAction(ctx, "Corp", "corp.local", "x", "DC", null)));
    }

    // ------------------------------------------------------------------ OCR engines

    @SuppressWarnings("unchecked")
    @Test void ocrEngineStatus() throws Exception {
        OcrPackage ocr = new OcrPackage();
        ActionContext ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        Map<String, Object> s = ocr.engineStatus(ctx);
        String engine = (String) s.get("engine");
        assertTrue(List.of("tesseract", "windows", "none").contains(engine), engine);
        if (engine.equals("tesseract")) assertTrue(((List<Object>) s.get("tesseract_languages")).contains("eng"), s.toString());
        if (!ProcessRunner.WINDOWS) {
            assertEquals(List.of(), s.get("windows_languages"));
            assertEquals("SYSTEM.WINDOWS_ONLY", code(() -> ocr.addWindowsLanguage(ctx, "de-DE")));
        }
        assertTrue(OcrPackage.WINDOWS_ADD_LANGUAGE.contains("Language.OCR~~~"));
    }

    // ------------------------------------------------------------------ media

    @Test void playMediaFiles() throws Exception {
        SoundPackage s = new SoundPackage();
        Path w = tmp();
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        assertEquals("FILE.NOT_FOUND", code(() -> s.playMedia(ctx, "none.mp3", false, true, 100, 1, 0, false, 800, 480)));
        Files.writeString(w.resolve("notes.txt"), "x");
        assertEquals("SOUND.UNSUPPORTED", code(() -> s.playMedia(ctx, "notes.txt", false, true, 100, 1, 0, false, 800, 480)));
        Files.write(w.resolve("chime.mp3"), new byte[]{'I', 'D', '3', 3, 0, 0, 0, 0, 0, 0}); // only the name and the volume are checked before playing
        assertEquals("SOUND.BAD_VOLUME", code(() -> s.playMedia(ctx, "chime.mp3", false, true, 150, 1, 0, false, 800, 480)));
        // results of the Windows player script
        assertEquals(new BigDecimal("1.567"), SoundPackage.mediaResult(new ProcessRunner.Result(0, "noise\n__OOA_MEDIA__ 1.5673 1.9\n", "", false), true));
        assertEquals("SOUND.CANNOT_PLAY", code(() -> SoundPackage.mediaResult(new ProcessRunner.Result(2, "__OOA_MEDIA_FAIL__ Exception from HRESULT: 0xC00D11B1", "", false), true)));
        assertEquals("SOUND.CANNOT_PLAY", code(() -> SoundPackage.mediaResult(new ProcessRunner.Result(0, "", "", false), true)));
        assertTrue(SoundPackage.WINDOWS_PLAYER.contains("System.Windows.Media.MediaPlayer") && SoundPackage.WINDOWS_PLAYER.contains("MediaElement"));
        // elsewhere a command-line player is used: audio without a window, video with one
        if (SoundPackage.onPath("ffplay")) {
            assertTrue(SoundPackage.mediaCommand(w.resolve("chime.mp3"), false).contains("-nodisp"));
            assertFalse(SoundPackage.mediaCommand(w.resolve("clip.mp4"), true).contains("-nodisp"));
        }
        assertEquals(new BigDecimal(0), s.stopMedia(ctx).get("stopped"));
    }

    // ------------------------------------------------------------------ Microsoft 365 files

    @Test void oneDriveLargeFileThroughAResumableUploadSession() throws Exception {
        Microsoft365Package pkg = new Microsoft365Package();
        SecureString tok = new SecureString("tok-123", "t");
        Microsoft365Package.chunkRetryBaseMs = 5;
        Path w = tmp();
        byte[] data = new byte[5_000_000];
        new Random(3).nextBytes(data);
        Files.write(w.resolve("big.bin"), data);
        Files.write(w.resolve("small.txt"), "hello".getBytes());
        try (Microsoft365GapsTest.Fake f = new Microsoft365GapsTest.Fake()) {
            String b = f.base();
            java.io.ByteArrayOutputStream got = new java.io.ByteArrayOutputStream();
            int[] failOnce = {1};
            String upload = f.url() + "/upload/sess1";
            f.at("POST", "/v1.0/me/drive/root:/Archive/big.bin:/createUploadSession", c -> Microsoft365GapsTest.ok("{\"uploadUrl\":\"" + upload + "\"}"))
             .at("PUT", "/upload/sess1", c -> {
                 String range = c.header("Content-Range");
                 long start = Long.parseLong(range.replaceAll("bytes (\\d+)-.*", "$1"));
                 if (start > 0 && failOnce[0]-- > 0) return new Object[]{503, "{\"error\":{\"message\":\"busy\"}}"};
                 synchronized (got) { assertEquals(got.size(), start, "chunks arrive in order"); got.write(c.body(), 0, c.body().length); }
                 if (got.size() == 5_000_000) return new Object[]{201, "{\"id\":\"ITEM9\",\"webUrl\":\"https://od/big.bin\"}"};
                 return new Object[]{202, "{\"nextExpectedRanges\":[\"" + got.size() + "-\"]}"};
             })
             .at("GET", "/upload/sess1", c -> Microsoft365GapsTest.ok("{\"nextExpectedRanges\":[\"" + got.size() + "-4999999\"]}"))
             .at("DELETE", "/upload/sess1", c -> Microsoft365GapsTest.none())
             .at("PUT", "/v1.0/me/drive/root:/Archive/small.txt:/content", c -> Microsoft365GapsTest.ok("{\"id\":\"S1\",\"webUrl\":\"https://od/small.txt\"}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(w);
            Map<String, Object> r = pkg.upload(ctx, tok, "big.bin", "Archive/big.bin", "fail", "me/drive", b, 2);
            assertEquals("ITEM9", r.get("item_id"));
            assertEquals(new BigDecimal(5_000_000), r.get("size"));
            assertArrayEquals(data, got.toByteArray(), "the file arrives intact after a failed chunk");
            Map<String, Object> created = f.last("POST", "/v1.0/me/drive/root:/Archive/big.bin:/createUploadSession").json();
            assertEquals("fail", m(created.get("item")).get("@microsoft.graph.conflictBehavior"));
            List<Microsoft365GapsTest.Call> puts = f.all("PUT", "/upload/sess1");
            assertEquals(4, puts.size(), "3 pieces of 1,966,080 bytes or less, one sent twice");
            assertEquals("bytes 0-1966079/5000000", puts.get(0).header("Content-Range"));
            assertNull(puts.get(0).header("Authorization"), "the pre-authorised upload URL gets no token");
            assertTrue(f.all("DELETE", "/upload/sess1").isEmpty(), "a finished session is not cancelled");
            // small files still go in one request
            assertEquals("S1", pkg.upload(ctx, tok, "small.txt", "Archive/small.txt", null, "me/drive", b, 10).get("item_id"));
            // a session the service keeps failing is cancelled
            got.reset();
            f.at("PUT", "/upload/sess1", c -> new Object[]{500, "{\"error\":{\"message\":\"down\"}}"});
            assertEquals("M365.SERVER_ERROR", code(() -> pkg.upload(ctx, tok, "big.bin", "Archive/big.bin", "replace", "me/drive", b, 60)));
            assertEquals(1, f.all("DELETE", "/upload/sess1").size());
        }
        assertEquals(327_680, Microsoft365Package.chunkBytes(0.1));
        assertEquals(10 * 1024 * 1024 / 327_680 * 327_680, Microsoft365Package.chunkBytes(10));
        assertEquals("M365.BAD_OPTION", code(() -> Microsoft365Package.chunkBytes(61)));
    }

    @Test void teamsMessagesWithMentions() throws Exception {
        Microsoft365Package pkg = new Microsoft365Package();
        SecureString tok = new SecureString("tok-123", "t");
        try (Microsoft365GapsTest.Fake f = new Microsoft365GapsTest.Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/users/ann%40contoso.com", c -> Microsoft365GapsTest.ok("{\"id\":\"U-ANN\",\"displayName\":\"Ann Lee\"}"))
             .at("GET", "/v1.0/users/U-BOB", c -> Microsoft365GapsTest.ok("{\"id\":\"U-BOB\",\"displayName\":\"Bob <Ops>\"}"))
             .at("GET", "/v1.0/users/ghost%40contoso.com", c -> new Object[]{404, "{\"error\":{\"message\":\"Resource not found\"}}"})
             .at("GET", "/v1.0/teams/T1/channels/C1", c -> Microsoft365GapsTest.ok("{\"id\":\"C1\",\"displayName\":\"Alerts\"}"))
             .at("POST", "/v1.0/teams/T1/channels/C1/messages", c -> new Object[]{201, "{\"id\":\"MSG1\"}"})
             .at("POST", "/v1.0/chats/CH1/messages", c -> new Object[]{201, "{\"id\":\"M9\"}"});
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            pkg.teamsMessage(ctx, tok, "T1", "C1", "Invoice run failed for <ACME> & co.\nPlease check, @[ann@contoso.com].", false, null, "high", null, b, null, null,
                    List.of("ann@contoso.com", "U-BOB", "channel"));
            Map<String, Object> msg = f.last("POST", "/v1.0/teams/T1/channels/C1/messages").json();
            Map<String, Object> body = m(msg.get("body"));
            assertEquals("html", body.get("contentType"));
            assertEquals("<at id=\"1\">Bob &lt;Ops&gt;</at> <at id=\"2\">Alerts</at> Invoice run failed for &lt;ACME&gt; &amp; co.<br>Please check, <at id=\"0\">Ann Lee</at>.", body.get("content"));
            List<Object> mentions = (List<Object>) msg.get("mentions");
            assertEquals(3, mentions.size());
            Map<String, Object> ann = m(mentions.get(0));
            assertEquals(BigDecimal.ZERO, ann.get("id"));
            assertEquals("Ann Lee", ann.get("mentionText"));
            assertEquals(Map.of("id", "U-ANN", "displayName", "Ann Lee", "userIdentityType", "aadUser"), m(ann.get("mentioned")).get("user"));
            assertEquals("channel", m(m(m(mentions.get(2)).get("mentioned")).get("conversation")).get("conversationIdentityType"));
            assertEquals("high", msg.get("importance"));

            pkg.sendChatMessage(ctx, tok, "CH1", "<p>Done</p>", true, "normal", b, null, List.of("ann@contoso.com"));
            assertEquals("<at id=\"0\">Ann Lee</at> <p>Done</p>", m(f.last("POST", "/v1.0/chats/CH1/messages").json().get("body")).get("content"));
            assertEquals("M365.BAD_INPUT", code(() -> pkg.sendChatMessage(ctx, tok, "CH1", "hi", false, "normal", b, null, List.of("channel"))));
            assertEquals("M365.USER_NOT_FOUND", code(() -> pkg.sendChatMessage(ctx, tok, "CH1", "hi", false, "normal", b, null, List.of("ghost@contoso.com"))));
            assertEquals("M365.BAD_INPUT", code(() -> pkg.sendChatMessage(ctx, tok, "CH1", "hi @[bob@contoso.com]", false, "normal", b, null, List.of("ann@contoso.com"))));
            // without mentions nothing changes
            pkg.sendChatMessage(ctx, tok, "CH1", "plain", false, "normal", b);
            assertEquals(Map.of("contentType", "text", "content", "plain"), f.last("POST", "/v1.0/chats/CH1/messages").json().get("body"));
        }
    }

    @SuppressWarnings("unchecked")
    @Test void excelOnlineCopyWorksheetWithFormats() throws Exception {
        Microsoft365Package pkg = new Microsoft365Package();
        SecureString tok = new SecureString("tok-123", "t");
        String src = "/me/drive/root:/Fin/book.xlsx:/workbook/worksheets('Data')", dst = "/me/drive/root:/Fin/archive.xlsx:/workbook/worksheets('Data%202026')";
        Map<String, String> answers = new LinkedHashMap<>();
        answers.put(src + "/range(address='A:A')/format?$select=columnWidth", "{\"columnWidth\":120}");
        answers.put(src + "/range(address='B:B')/format?$select=columnWidth", "{\"columnWidth\":48}");
        answers.put(src + "/range(address='1:1')/format?$select=rowHeight", "{\"rowHeight\":30}");
        answers.put(src + "/range(address='2:2')/format?$select=rowHeight", "{\"rowHeight\":15}");
        answers.put(src + "/range(address='A1:B1')/format/font", "{\"name\":\"Calibri\",\"size\":11,\"color\":\"#FFFFFF\",\"bold\":true,\"italic\":false,\"underline\":\"None\"}");
        answers.put(src + "/range(address='A1:B1')/format/fill", "{\"color\":\"#1F4E79\"}");
        answers.put(src + "/range(address='A1:B1')/format?$select=horizontalAlignment,verticalAlignment,wrapText", "{\"horizontalAlignment\":\"Center\",\"verticalAlignment\":\"Bottom\",\"wrapText\":false}");
        answers.put(src + "/range(address='A2:B2')/format/font", "{\"name\":\"Calibri\",\"size\":11,\"color\":\"#000000\",\"bold\":false,\"italic\":null,\"underline\":\"None\"}");
        answers.put(src + "/range(address='A2:B2')/format/fill", "{\"color\":null}");
        answers.put(src + "/range(address='A2:B2')/format?$select=horizontalAlignment,verticalAlignment,wrapText", "{\"horizontalAlignment\":\"General\",\"verticalAlignment\":\"Bottom\",\"wrapText\":false}");
        answers.put(src + "/range(address='A2')/format/font", "{\"name\":\"Calibri\",\"size\":11,\"color\":\"#000000\",\"bold\":false,\"italic\":false,\"underline\":\"None\"}");
        answers.put(src + "/range(address='B2')/format/font", "{\"name\":\"Calibri\",\"size\":11,\"color\":\"#000000\",\"bold\":false,\"italic\":true,\"underline\":\"None\"}");
        answers.put(src + "/range(address='A2')/format/fill", "{\"color\":\"#FFFFFF\"}");
        answers.put(src + "/range(address='B2')/format/fill", "{\"color\":\"#FFFF00\"}");
        List<Map<String, Object>> patches = Collections.synchronizedList(new ArrayList<>());
        try (Microsoft365GapsTest.Fake f = new Microsoft365GapsTest.Fake()) {
            String b = f.base(), wb2 = "/v1.0/me/drive/root:/Fin/archive.xlsx:/workbook";
            f.at("GET", "/v1.0/me/drive/root:/Fin/book.xlsx:/workbook/worksheets('Data')/usedRange", c -> Microsoft365GapsTest.ok("{\"address\":\"Data!A1:B2\",\"rowCount\":2,"
                    + "\"formulas\":[[\"Item\",\"Total\"],[\"Pens\",\"=3*4\"]],\"numberFormat\":[[\"General\",\"General\"],[\"General\",\"0.00\"]]}"))
             .at("POST", wb2 + "/worksheets/add", c -> new Object[]{201, "{\"id\":\"s9\",\"name\":\"Data 2026\"}"})
             .at("PATCH", wb2 + "/worksheets('Data%202026')/range(address='A1:B2')", c -> Microsoft365GapsTest.ok("{}"))
             .at("POST", "/v1.0/$batch", c -> {
                 List<Object> reqs = (List<Object>) c.json().get("requests");
                 assertTrue(reqs.size() <= 20, "at most 20 requests per batch");
                 List<Object> resps = new ArrayList<>();
                 for (Object o : reqs) {
                     Map<String, Object> r = (Map<String, Object>) o;
                     String url = String.valueOf(r.get("url"));
                     if ("PATCH".equals(r.get("method"))) { assertTrue(url.startsWith(dst), url); patches.add(Map.of("url", url.substring(dst.length()), "body", r.get("body"))); resps.add(Map.of("id", r.get("id"), "status", 200, "body", Map.of())); }
                     else if (answers.containsKey(url)) resps.add(Map.of("id", r.get("id"), "status", 200, "body", JsonLite.parse(answers.get(url))));
                     else resps.add(Map.of("id", r.get("id"), "status", 404, "body", Map.of("error", Map.of("message", "no " + url))));
                 }
                 return Microsoft365GapsTest.ok(JsonLite.write(Map.of("responses", resps)));
             });
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            Map<String, Object> r = pkg.excelCopySheet(ctx, tok, "Fin/book.xlsx", "Data", "Fin/archive.xlsx", "Data 2026", "me/drive", b, "all");
            assertEquals("A1:B2", r.get("address"));
            Map<String, Object> byUrl = new LinkedHashMap<>();
            for (Map<String, Object> p : patches) byUrl.put(String.valueOf(p.get("url")), p.get("body"));
            assertEquals(Map.of("columnWidth", new BigDecimal(120)), byUrl.get("/range(address='A:A')/format"));
            assertEquals(Map.of("rowHeight", new BigDecimal(30)), byUrl.get("/range(address='1:1')/format"));
            assertEquals(Map.of("color", "#FFFFFF", "bold", true), byUrl.get("/range(address='A1:B1')/format/font"));
            assertEquals(Map.of("color", "#1F4E79"), byUrl.get("/range(address='A1:B1')/format/fill"));
            assertEquals(Map.of("horizontalAlignment", "Center"), byUrl.get("/range(address='A1:B1')/format"));
            assertEquals(Map.of("italic", true), byUrl.get("/range(address='B2')/format/font"), "a row with different fonts is copied cell by cell");
            assertEquals(Map.of("color", "#FFFF00"), byUrl.get("/range(address='B2')/format/fill"));
            assertFalse(byUrl.containsKey("/range(address='A2')/format/font"), "default formats are not written");
            assertFalse(byUrl.containsKey("/range(address='A2:B2')/format"), "default alignment is not written");

            // sizes only
            patches.clear();
            pkg.excelCopySheet(ctx, tok, "Fin/book.xlsx", "Data", "Fin/archive.xlsx", "Data 2026", "me/drive", b, "sizes");
            assertTrue(patches.stream().allMatch(p -> String.valueOf(p.get("url")).matches("/range\\(address='[A-Z0-9]+:[A-Z0-9]+'\\)/format")), patches.toString());
            int n = f.all("POST", "/v1.0/\\$batch").size();
            pkg.excelCopySheet(ctx, tok, "Fin/book.xlsx", "Data", "Fin/archive.xlsx", "Data 2026", "me/drive", b, "none");
            assertEquals(n, f.all("POST", "/v1.0/\\$batch").size(), "none copies cells only");
            assertEquals("M365.BAD_OPTION", code(() -> pkg.excelCopySheet(ctx, tok, "Fin/book.xlsx", "Data", "Fin/archive.xlsx", "X", "me/drive", b, "colours")));
        }
    }

    // ------------------------------------------------------------------ VT terminals

    /** An SSH-like screen whose text changes as the test says; the cursor follows the last line. */
    static final class FakeVt extends TerminalPackage.Session {
        volatile List<String> lines = List.of("", "");
        final List<String> sent = new ArrayList<>();
        @Override boolean isVt() { return true; }
        @Override synchronized List<String> cmd(String action) {
            if (action.equals("Ascii()")) return lines;
            if (action.equals("Query(Cursor)")) { int r = lines.size() - 1; return List.of(r + " " + lines.get(r).length()); }
            sent.add(action);
            return List.of();
        }
    }

    @Test void vtKeysEscapeSequencesAndPromptWait() throws Exception {
        assertEquals("Seq(1b5b32347e)", TerminalPackage.keyAction("SEQ:\\e[24~", true));
        assertEquals("\033[24~", VtSshSession.keys(TerminalPackage.keyAction("SEQ:^[[24~", true)));
        assertEquals("\033OP", VtSshSession.keys(TerminalPackage.keyAction("seq:\\x1bOP", true)));
        assertEquals("\003", TerminalPackage.escapeSequence("^C"));
        assertEquals("\033x", VtSshSession.keys(TerminalPackage.keyAction("Alt+x", true)));
        assertEquals("PF(12)", TerminalPackage.keyAction("F12", true));
        assertEquals("\033[24~", VtSshSession.keys("PF(12)"));
        assertEquals("\033[5~", VtSshSession.keys(TerminalPackage.keyAction("PageUp", true)));
        assertEquals("TERMINAL.BAD_KEY", code(() -> TerminalPackage.keyAction("SEQ:\\q", true)));
        assertEquals("TERMINAL.BAD_KEY", code(() -> TerminalPackage.keyAction("SEQ:\\x1", true)));
        assertEquals("TERMINAL.BAD_KEY", code(() -> TerminalPackage.keyAction("SEQ:\\e[24~", false)), "3270 hosts have no escape sequences");

        TerminalPackage term = new TerminalPackage();
        Map<String, Object> sessions = new HashMap<>();
        FakeVt vt = new FakeVt();
        sessions.put("terminal", vt);
        ActionContext ctx = new ActionContext("r", "s", "t", tmp(), sessions, new HashMap<>(), new java.util.concurrent.atomic.AtomicBoolean(), List::of, (l, m) -> { });
        // a mapped escape sequence is sent by Press key
        term.setKeyMapping(ctx, Map.of("Help", "SEQ:\\eOP"), "terminal");
        term.sendKey(ctx, "Help", "terminal", false, 5, 1, 0, null);
        assertEquals("Seq(1b4f50)", vt.sent.get(vt.sent.size() - 1));
        // output still arriving, then a prompt
        vt.lines = List.of("$ ls", "a.txt b.txt");
        Thread later = new Thread(() -> { try { Thread.sleep(600); vt.lines = List.of("$ ls", "a.txt b.txt", "ops@srv:~$ "); } catch (InterruptedException ignored) { } });
        later.start();
        long t0 = System.nanoTime();
        Map<String, Object> r = term.waitForPrompt(ctx, "terminal", null, 5, 200, true);
        assertEquals(true, r.get("found"));
        assertEquals("ops@srv:~$", r.get("prompt_line"));
        assertTrue((System.nanoTime() - t0) / 1_000_000 >= 600);
        later.join();
        assertEquals(false, term.waitForPrompt(ctx, "terminal", "SQL>\\s*$", 1, 100, false).get("found"));
        assertEquals("TERMINAL.NO_PROMPT", code(() -> term.waitForPrompt(ctx, "terminal", "SQL>\\s*$", 1, 100, true)));
        assertEquals("TERMINAL.BAD_PATTERN", code(() -> term.waitForPrompt(ctx, "terminal", "([", 1, 100, true)));
    }

    // ------------------------------------------------------------------ services on another computer

    @Test void servicesOnAnotherComputer() {
        var local = ServicesPackage.target(null, null, null);
        assertFalse(local.remote());
        assertSame(ServicesPackage.Target.LOCAL, ServicesPackage.target("localhost", "", null));
        assertEquals(List.of("sc.exe", "query", "Spooler"), ServicesPackage.scCommand(local, "query", "Spooler"));
        var remote = ServicesPackage.target("\\\\FILESRV01", null, null);
        assertEquals(List.of("sc.exe", "\\\\FILESRV01", "stop", "Spooler"), ServicesPackage.scCommand(remote, "stop", "Spooler"));
        assertEquals("SERVICES.BAD_COMPUTER", code(() -> ServicesPackage.target("FILE SRV; calc", null, null)));
        assertEquals("SERVICES.NO_SECRET", code(() -> ServicesPackage.target("FILESRV01", "CORP\\ops", null)));
        assertEquals("SERVICES.NO_SECRET", code(() -> ServicesPackage.target("FILESRV01", "CORP\\ops", "typed-password")));
        assertEquals("SERVICES.BAD_INPUT", code(() -> ServicesPackage.target("", "CORP\\ops", new SecureString("p", "v"))));
        assertEquals("SERVICES.BAD_NAME", code(() -> ServicesPackage.scCommand(remote, "query", "Spooler & calc")));
        // sc.exe results are typed, naming the computer
        var withUser = ServicesPackage.target("FILESRV01", "CORP\\ops", new SecureString("p", "v"));
        assertEquals("CORP\\ops", withUser.user());
        assertEquals("SERVICES.UNREACHABLE", ServicesPackage.scError(remote, "query", "Spooler", new ProcessRunner.Result(1722, "[SC] OpenSCManager FAILED 1722:\n\nThe RPC server is unavailable.", "", false)).code());
        ActionException denied = ServicesPackage.scError(remote, "stop", "Spooler", new ProcessRunner.Result(5, "[SC] OpenService FAILED 5:\n\nAccess is denied.", "", false));
        assertEquals("SERVICES.ACCESS_DENIED", denied.code());
        assertTrue(denied.getMessage().contains("on FILESRV01"), denied.getMessage());
        assertEquals("SERVICES.NOT_FOUND", ServicesPackage.scError(local, "query", "Nope", new ProcessRunner.Result(1060, "[SC] EnumQueryServicesStatus:OpenService FAILED 1060:", "", false)).code());
        assertEquals("SERVICES.DISABLED", ServicesPackage.scError(local, "start", "X", new ProcessRunner.Result(1058, "[SC] StartService FAILED 1058:", "", false)).code());
        assertNull(ServicesPackage.scError(local, "start", "X", new ProcessRunner.Result(1056, "[SC] StartService FAILED 1056:", "", false)), "already running is fine");
        assertNull(ServicesPackage.scError(local, "query", "X", new ProcessRunner.Result(0, "STATE : 4 RUNNING", "", false)));
        assertTrue(ServicesPackage.AS_USER.contains("\\\\$computer\\IPC$"), "signs in to \\\\computer\\IPC$");
    }

    // ------------------------------------------------------------------ calendars

    @Test void googleEventEndsInAnotherTimeZone() throws Exception {
        try (ConnectorsTest.Fake f = new ConnectorsTest.Fake()) {
            f.on("POST /calendar/v3/calendars/primary/events", c -> ConnectorsTest.ok("{\"id\":\"E9\",\"htmlLink\":\"https://cal/E9\"}"))
             .on("PATCH /calendar/v3/calendars/primary/events/E9", c -> ConnectorsTest.ok("{\"htmlLink\":\"https://cal/E9\"}"));
            Path w = tmp();
            GoogleOptionsTest.g(f, w, "calendar_create_event", Map.of("subject", "Flight LHR-JFK", "start", "2026-11-20T09:00:00", "end", "2026-11-20T12:30:00",
                    "time_zone", "Europe/London", "end_time_zone", "America/New_York"), "event_id");
            Map<String, Object> body = f.last("POST", "/calendar/v3/calendars/primary/events").json();
            assertEquals(Map.of("dateTime", "2026-11-20T09:00:00", "timeZone", "Europe/London"), body.get("start"));
            assertEquals(Map.of("dateTime", "2026-11-20T12:30:00", "timeZone", "America/New_York"), body.get("end"));
            // 12:30 in New York is after 09:00 in London; 03:30 in New York is not
            Map<String, Object> e = GoogleGapsTest.err(f, "calendar_create_event", Map.of("subject", "x", "start", "2026-11-20T09:00:00", "end", "2026-11-20T03:30:00",
                    "time_zone", "Europe/London", "end_time_zone", "America/New_York"));
            assertEquals("GOOGLE.BAD_INPUT", e.get("code"));
            assertEquals("GOOGLE.BAD_INPUT", GoogleGapsTest.err(f, "calendar_create_event", Map.of("subject", "x", "start", "2026-11-20T09:00:00", "end", "2026-11-20T10:00:00",
                    "end_time_zone", "Mars/Olympus")).get("code"));

            GoogleOptionsTest.g(f, w, "calendar_update_event", Map.of("event_id", "E9", "start", "2026-11-20T10:00:00", "end", "2026-11-20T13:00:00",
                    "time_zone", "Europe/London", "end_time_zone", "America/New_York"), "link");
            Map<String, Object> patch = f.last("PATCH", "/calendar/v3/calendars/primary/events/E9").json();
            assertEquals("America/New_York", m(patch.get("end")).get("timeZone"));
            assertEquals("Europe/London", m(patch.get("start")).get("timeZone"));
        }
    }

    @Test void outlookSeriesMovedToTheSecondTuesday() throws Exception {
        Microsoft365Package pkg = new Microsoft365Package();
        SecureString tok = new SecureString("tok-123", "t");
        try (Microsoft365GapsTest.Fake f = new Microsoft365GapsTest.Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/me/events/E1", c -> Microsoft365GapsTest.ok("{\"type\":\"seriesMaster\",\"start\":{\"dateTime\":\"2026-11-10T09:00:00.0000000\",\"timeZone\":\"UTC\"},"
                    + "\"end\":{\"dateTime\":\"2026-11-10T10:00:00.0000000\",\"timeZone\":\"UTC\"}}"))
             .at("PATCH", "/v1.0/me/events/E1", c -> Microsoft365GapsTest.ok("{\"webLink\":\"https://outlook/E1\"}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            pkg.updateEvent(ctx, tok, "E1", null, null, null, "UTC", null, null, false, null, null, null, null, null, "me", b,
                    null, "monthly", 1, null, 12, List.of("Tuesday"), "second");
            Map<String, Object> rec = m(f.last("PATCH", "/v1.0/me/events/E1").json().get("recurrence"));
            Map<String, Object> pattern = m(rec.get("pattern"));
            assertEquals("relativeMonthly", pattern.get("type"));
            assertEquals(List.of("tuesday"), pattern.get("daysOfWeek"));
            assertEquals("second", pattern.get("index"));
            assertEquals(new BigDecimal(12), m(rec.get("range")).get("numberOfOccurrences"));
            pkg.updateEvent(ctx, tok, "E1", null, null, null, "UTC", null, null, false, null, null, null, null, null, "me", b,
                    null, "yearly", 1, null, null, null, "last");
            Map<String, Object> yearly = m(m(f.last("PATCH", "/v1.0/me/events/E1").json().get("recurrence")).get("pattern"));
            assertEquals("relativeYearly", yearly.get("type"));
            assertEquals(List.of("tuesday"), yearly.get("daysOfWeek"), "the start's weekday");
            assertEquals(new BigDecimal(11), yearly.get("month"));
            assertEquals("M365.BAD_OPTION", code(() -> pkg.updateEvent(ctx, tok, "E1", "x", null, null, "UTC", null, null, false, null, null, null, null, null, "me", b,
                    null, null, 1, null, null, List.of("monday"), null)));
            assertEquals("M365.BAD_OPTION", code(() -> pkg.updateEvent(ctx, tok, "E1", null, null, null, "UTC", null, null, false, null, null, null, null, null, "me", b,
                    null, "weekly", 1, null, null, List.of("someday"), null)));
        }
    }
}
