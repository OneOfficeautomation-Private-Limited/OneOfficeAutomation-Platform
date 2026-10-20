package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Cloud connectors against a local server that imitates each service's REST API. */
class ConnectorsTest {
    record Call(String method, String path, String query, String auth, String contentType, byte[] body, Map<String, List<String>> headers) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
        String header(String name) {
            for (var e : headers.entrySet()) if (e.getKey().equalsIgnoreCase(name) && !e.getValue().isEmpty()) return e.getValue().get(0);
            return null;
        }
        @SuppressWarnings("unchecked") Map<String, Object> json() { return (Map<String, Object>) JsonLite.parse(text()); }
    }

    /** Fake service: routes "METHOD /path" to a handler returning {status, body} or {status, body, response headers}. */
    static final class Fake implements AutoCloseable {
        final HttpServer server;
        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Function<Call, Object[]>> routes = new LinkedHashMap<>();
        Fake() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        Fake on(String key, Function<Call, Object[]> h) { routes.put(key, h); return this; }
        void handle(HttpExchange ex) throws java.io.IOException {
            Call c = new Call(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(),
                    ex.getRequestHeaders().getFirst("Authorization"), ex.getRequestHeaders().getFirst("Content-Type"), ex.getRequestBody().readAllBytes(),
                    new LinkedHashMap<>(ex.getRequestHeaders()));
            calls.add(c);
            Object[] r = new Object[]{404, "{\"error\":{\"message\":\"no route " + c.method() + " " + c.path() + "\"}}"};
            for (var e : routes.entrySet()) {
                String[] k = e.getKey().split(" ", 2);
                if (k[0].equals(c.method()) && c.path().matches(k[1])) { r = e.getValue().apply(c); break; }
            }
            if (r.length > 2 && r[2] instanceof Map<?, ?> hs) hs.forEach((k, v) -> ex.getResponseHeaders().add(String.valueOf(k), String.valueOf(v)));
            byte[] b = r[1] instanceof byte[] bytes ? bytes : String.valueOf(r[1]).getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders((Integer) r[0], b.length == 0 ? -1 : b.length);
            if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        }
        Call last(String method, String pathRegex) {
            synchronized (calls) { for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).method().equals(method) && calls.get(i).path().matches(pathRegex)) return calls.get(i); }
            return null;
        }
        @Override public void close() { server.stop(0); }
    }

    static Object[] ok(String json) { return new Object[]{200, json}; }
    static String q(Call c) { return c.query() == null ? "" : URLDecoder.decode(c.query(), StandardCharsets.UTF_8); }
    static final String TOKEN_VAR = "{\"name\":\"tok\",\"type\":\"Credential\",\"direction\":\"input\",\"required\":true}";
    static final Map<String, Object> TOKEN = Map.of("tok", Map.of("$credential", "test/api/token"));

    @Test void microsoft365MailPagingAttachmentsCalendarAndFiles() throws Exception {
        try (Fake f = new Fake()) {
            String base = f.url() + "/v1.0";
            f.on("POST /v1.0/me/sendMail", c -> new Object[]{202, ""})
             .on("GET /v1.0/me/mailFolders/inbox/messages", c -> c.query().contains("skiptoken")
                    ? ok("{\"value\":[{\"id\":\"m3\",\"subject\":\"Invoice 3\",\"from\":{\"emailAddress\":{\"address\":\"ap@acme.com\"}},\"isRead\":true}]}")
                    : ok("{\"value\":[{\"id\":\"m1\",\"subject\":\"Invoice 1\",\"from\":{\"emailAddress\":{\"address\":\"ap@acme.com\"}},\"isRead\":false,\"hasAttachments\":true},"
                       + "{\"id\":\"m2\",\"subject\":\"Lunch\",\"from\":{\"emailAddress\":{\"address\":\"bob@corp.com\"}},\"isRead\":false}],"
                       + "\"@odata.nextLink\":\"" + base + "/me/mailFolders/inbox/messages?$skiptoken=2\"}"))
             .on("GET /v1.0/me/messages/m1/attachments", c -> ok("{\"value\":[{\"name\":\"../../evil/INV-1.pdf\",\"contentBytes\":\""
                    + Base64.getEncoder().encodeToString("%PDF-1.4".getBytes()) + "\"},{\"name\":\"logo.png\",\"contentBytes\":\"AAAA\"}]}"))
             .on("POST /v1.0/me/events", c -> ok("{\"id\":\"ev1\",\"webLink\":\"https://outlook/ev1\"}"))
             .on("PUT /v1.0/me/drive/root:/Reports/Q4%202026/sum%20mary.txt:/content", c -> ok("{\"id\":\"item1\",\"webUrl\":\"https://od/item1\"}"));
            Path w = tmp();
            Files.writeString(w.resolve("summary.txt"), "report");
            Map<String, Object> o = run(w, "[" + TOKEN_VAR + ",{\"name\":\"t\",\"type\":\"Table\",\"direction\":\"output\"},{\"name\":\"files\",\"type\":\"List\",\"direction\":\"output\"},"
                    + "{\"name\":\"ev\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"item\",\"type\":\"String\",\"direction\":\"output\"}]",
                    "[{\"id\":\"s\",\"action\":\"ooa.microsoft365/send_mail\",\"args\":{\"access_token\":\"$tok\",\"base_url\":\"" + base + "\",\"to\":\"a@x.com; b@x.com\",\"subject\":\"Hi\",\"body\":\"<b>x</b>\",\"html\":true,\"attachments\":[\"summary.txt\"]}},"
                    + "{\"id\":\"l\",\"action\":\"ooa.microsoft365/list_mail\",\"args\":{\"access_token\":\"$tok\",\"base_url\":\"" + base + "\",\"from_contains\":\"acme\"},\"out\":{\"table\":\"t\"}},"
                    + "{\"id\":\"a\",\"action\":\"ooa.microsoft365/save_mail_attachments\",\"args\":{\"access_token\":\"$tok\",\"base_url\":\"" + base + "\",\"id\":\"m1\",\"target_folder\":\"in\",\"name_pattern\":\"*.pdf\"},\"out\":{\"files\":\"files\"}},"
                    + "{\"id\":\"e\",\"action\":\"ooa.microsoft365/create_event\",\"args\":{\"access_token\":\"$tok\",\"base_url\":\"" + base + "\",\"subject\":\"Review\",\"start\":\"2026-10-20T10:00:00\",\"end\":\"2026-10-20T10:30:00\",\"attendees\":\"x@y.com\"},\"out\":{\"event_id\":\"ev\"}},"
                    + "{\"id\":\"u\",\"action\":\"ooa.microsoft365/upload_file\",\"args\":{\"access_token\":\"$tok\",\"base_url\":\"" + base + "\",\"local_path\":\"summary.txt\",\"remote_path\":\"Reports/Q4 2026/sum mary.txt\"},\"out\":{\"item_id\":\"item\"}}]", TOKEN);
            Call send = f.last("POST", "/v1.0/me/sendMail");
            assertEquals("Bearer tok-123", send.auth());
            @SuppressWarnings("unchecked") Map<String, Object> msg = (Map<String, Object>) ((Map<String, Object>) JsonLite.parse(send.text())).get("message");
            assertEquals(2, ((List<?>) msg.get("toRecipients")).size());
            assertEquals("HTML", ((Map<?, ?>) msg.get("body")).get("contentType"));
            assertEquals("summary.txt", ((Map<?, ?>) ((List<?>) msg.get("attachments")).get(0)).get("name"));
            @SuppressWarnings("unchecked") List<List<Object>> rows = (List<List<Object>>) ((Map<String, Object>) o.get("t")).get("rows");
            assertEquals(List.of("m1", "m3"), rows.stream().map(r -> r.get(0)).toList(), "filters sender and follows nextLink paging");
            List<?> files = (List<?>) o.get("files");
            assertEquals(1, files.size());
            assertTrue(Files.exists(w.resolve("in/INV-1.pdf")), "attachment name reduced to a safe file name");
            assertFalse(Files.exists(w.resolve("evil")));
            assertEquals("ev1", o.get("ev"));
            assertEquals("item1", o.get("item"));
        }
    }

    @Test void m365ErrorsCarryServiceMessageAndRetryFlag() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /v1.0/me/messages/x", c -> new Object[]{401, "{\"error\":{\"code\":\"InvalidAuthenticationToken\",\"message\":\"Access token has expired.\"}}"})
             .on("GET /v1.0/me/messages/y", c -> new Object[]{503, "{\"error\":{\"message\":\"Service unavailable\"}}"});
            RunResult r = runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[{\"id\":\"g\",\"action\":\"ooa.microsoft365/get_mail\",\"args\":{\"access_token\":\"$tok\",\"base_url\":\"" + f.url() + "/v1.0\",\"id\":\"x\"}}]");
            assertEquals("M365.AUTH_FAILED", r.error().get("code"));
            assertTrue(String.valueOf(r.error().get("message")).contains("Access token has expired"));
            RunResult r2 = runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[{\"id\":\"g\",\"action\":\"ooa.microsoft365/get_mail\",\"args\":{\"access_token\":\"$tok\",\"base_url\":\"" + f.url() + "/v1.0\",\"id\":\"y\"}}]");
            assertEquals("M365.SERVER_ERROR", r2.error().get("code"));
            assertEquals(true, r2.error().get("retryable"));
        }
    }

    static RunResult runRawWithToken(Path w, String vars, String steps) {
        @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":" + vars + ",\"steps\":" + steps + "}");
        return com.oneofficeautomation.runner.Runner.builder(REG).workDir(w)
                .service("vault", new com.oneofficeautomation.runner.CredentialVault.InMemory().put("test", "api", "token", "tok-123")).build().run(flow, TOKEN);
    }

    @Test void googleSheetsAndDriveMultipartUpload() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /v4/spreadsheets/S1/values/.*", c -> ok("{\"values\":[[\"Name\",\"Amount\"],[\"Acme\",120.5],[\"Globex\"]]}"))
             .on("POST /v4/spreadsheets/S1/values/.*", c -> ok("{\"updates\":{\"updatedRange\":\"Sheet1!A4:B4\"}}"))
             .on("POST /upload/drive/v3/files", c -> ok("{\"id\":\"F9\"}"));
            Path w = tmp();
            Files.writeString(w.resolve("r.csv"), "a,b");
            Map<String, Object> o = run(w, "[" + TOKEN_VAR + ",{\"name\":\"t\",\"type\":\"Table\",\"direction\":\"output\"},{\"name\":\"rng\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"fid\",\"type\":\"String\",\"direction\":\"output\"}]",
                    "[{\"id\":\"r\",\"action\":\"ooa.google/sheets_read\",\"args\":{\"access_token\":\"$tok\",\"endpoint_override\":\"" + f.url() + "\",\"spreadsheet_id\":\"S1\",\"range\":\"Sheet1!A1:B10\"},\"out\":{\"table\":\"t\"}},"
                    + "{\"id\":\"a\",\"action\":\"ooa.google/sheets_append\",\"args\":{\"access_token\":\"$tok\",\"endpoint_override\":\"" + f.url() + "\",\"spreadsheet_id\":\"S1\",\"range\":\"Sheet1!A1\",\"table\":\"$t\"},\"out\":{\"updated_range\":\"rng\"}},"
                    + "{\"id\":\"u\",\"action\":\"ooa.google/drive_upload\",\"args\":{\"access_token\":\"$tok\",\"endpoint_override\":\"" + f.url() + "\",\"local_path\":\"r.csv\",\"folder_id\":\"D1\"},\"out\":{\"file_id\":\"fid\"}}]", TOKEN);
            @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) o.get("t");
            assertEquals(List.of("Name", "Amount"), t.get("columns"));
            assertEquals(2, ((List<?>) t.get("rows")).size());
            assertEquals("Sheet1!A4:B4", o.get("rng"));
            assertEquals("F9", o.get("fid"));
            Call up = f.last("POST", "/upload/drive/v3/files");
            assertTrue(up.contentType().startsWith("multipart/related; boundary="));
            assertTrue(up.text().contains("\"parents\":[\"D1\"]") && up.text().contains("a,b"));
            assertTrue(q(up).contains("uploadType=multipart"));
        }
    }

    @Test void salesforceQueryFollowsPagesAndFlattensRelationships() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /services/data/v61.0/query", c -> ok("{\"done\":false,\"nextRecordsUrl\":\"/services/data/v61.0/query/01g-2000\",\"records\":["
                    + "{\"attributes\":{\"type\":\"Account\"},\"Id\":\"001A\",\"Name\":\"Acme\",\"Owner\":{\"attributes\":{},\"Name\":\"Ann\"}}]}"))
             .on("GET /services/data/v61.0/query/01g-2000", c -> ok("{\"done\":true,\"records\":[{\"attributes\":{},\"Id\":\"001B\",\"Name\":\"Globex\",\"Owner\":{\"Name\":\"Bob\"}}]}"))
             .on("POST /services/data/v61.0/sobjects/Case", c -> new Object[]{201, "{\"id\":\"500X000000000001\",\"success\":true}"})
             .on("PATCH /services/data/v61.0/sobjects/Case/500X000000000001", c -> new Object[]{204, ""});
            var pkg = new SalesforcePackage();
            var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
            var tok = new com.oneofficeautomation.sdk.SecureString("tok-123", "t");
            Map<String, Object> r = pkg.query(ctx, f.url(), tok, "SELECT Id, Name, Owner.Name FROM Account", 100, "v61.0");
            assertEquals(new BigDecimal(2), r.get("count"), "both result pages read");
            @SuppressWarnings("unchecked") Map<String, Object> t = (Map<String, Object>) r.get("table");
            assertEquals(List.of("Id", "Name", "Owner.Name"), t.get("columns"));
            assertEquals("Bearer tok-123", f.last("GET", "/services/data/v61.0/query").auth());
            assertEquals("500X000000000001", pkg.create(ctx, f.url(), tok, "Case", Map.of("Subject", "Refund"), "v61.0").get("id"));
            pkg.update(ctx, f.url(), tok, "Case", "500X000000000001", Map.of("Status", "Closed"), "v61.0");
            assertTrue(f.last("PATCH", ".*").text().contains("\"Status\":\"Closed\""));
            assertEquals("SALESFORCE.BAD_OBJECT", assertThrows(com.oneofficeautomation.sdk.ActionException.class,
                    () -> pkg.delete(ctx, f.url(), tok, "Case/../../x", "500X000000000001", "v61.0")).code());
            RunResult viaBot = runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[{\"id\":\"q\",\"action\":\"ooa.salesforce/query\",\"args\":{\"instance_url\":\"" + f.url() + "\",\"access_token\":\"$tok\",\"soql\":\"SELECT Id FROM Account\"}}]");
            assertEquals("FLOW.INVALID", viaBot.error().get("code"), "bots must use https:// instance URLs");
        }
    }

    @Test void serviceNowCreateCommentAndAttach() throws Exception {
        try (Fake f = new Fake()) {
            String sys = "0123456789abcdef0123456789abcdef";
            f.on("POST /api/now/table/incident", c -> new Object[]{201, "{\"result\":{\"sys_id\":\"" + sys + "\",\"number\":\"INC0010001\"}}"})
             .on("PATCH /api/now/table/incident/" + sys, c -> ok("{\"result\":{}}"))
             .on("POST /api/now/attachment/file", c -> new Object[]{201, "{\"result\":{\"sys_id\":\"att1\"}}"})
             .on("GET /api/now/table/incident", c -> ok("{\"result\":[{\"number\":\"INC1\",\"state\":\"New\"},{\"number\":\"INC2\",\"state\":\"New\"}]}"));
            Path w = tmp();
            Files.writeString(w.resolve("log.txt"), "details");
            String inst = f.url();
            // ServiceNow requires https in production; the test server is plain http, so call the package directly with the pattern bypassed
            var pkg = new ServiceNowPackage();
            var ctx = PackagesTestSupport.ctx(w, Map.of("tok", "x"));
            com.oneofficeautomation.sdk.SecureString pw = new com.oneofficeautomation.sdk.SecureString("pw", "t");
            Map<String, Object> created = pkg.create(ctx, inst, "incident", Map.of("short_description", "Printer down"), "bot", pw, null);
            assertEquals("INC0010001", created.get("number"));
            assertTrue(f.last("POST", "/api/now/table/incident").auth().startsWith("Basic "));
            pkg.comment(ctx, inst, "incident", sys, "Restarted spooler", true, "bot", pw, null);
            assertTrue(f.last("PATCH", "/api/now/table/incident/" + sys).text().contains("\"work_notes\":\"Restarted spooler\""));
            assertEquals("att1", pkg.attach(ctx, inst, "incident", sys, "log.txt", "bot", pw, null).get("attachment_id"));
            assertTrue(q(f.last("POST", "/api/now/attachment/file")).contains("file_name=log.txt"));
            Map<String, Object> q = pkg.query(ctx, inst, "incident", "active=true", null, 10, "bot", pw, null);
            assertEquals(new BigDecimal(2), q.get("count"));
            var bad = assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> pkg.get(ctx, inst, "incident; drop", sys, "bot", pw, null));
            assertEquals("SERVICENOW.BAD_TABLE", bad.code());
        }
    }

    @Test void jiraCreateSearchTransitionAndAttach() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /rest/api/2/issue", c -> new Object[]{201, "{\"id\":\"10001\",\"key\":\"OPS-7\"}"})
             .on("POST /rest/api/2/search", c -> ok("{\"total\":1,\"issues\":[{\"key\":\"OPS-7\",\"fields\":{\"summary\":\"Disk full\",\"status\":{\"name\":\"To Do\"}}}]}"))
             .on("GET /rest/api/2/issue/OPS-7/transitions", c -> ok("{\"transitions\":[{\"id\":\"21\",\"name\":\"Start\",\"to\":{\"name\":\"In Progress\"}},{\"id\":\"31\",\"name\":\"Done\",\"to\":{\"name\":\"Done\"}}]}"))
             .on("POST /rest/api/2/issue/OPS-7/transitions", c -> new Object[]{204, ""})
             .on("POST /rest/api/2/issue/OPS-7/attachments", c -> ok("[{\"id\":\"a1\"}]"));
            Path w = tmp();
            Files.writeString(w.resolve("trace.log"), "stack");
            Map<String, Object> o = run(w, "[" + TOKEN_VAR + ",{\"name\":\"k\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"t\",\"type\":\"Table\",\"direction\":\"output\"}]",
                    ("[{\"id\":\"c\",\"action\":\"ooa.jira/create_issue\",\"args\":{\"site_url\":\"URL\",\"project\":\"OPS\",\"summary\":\"Disk full\",\"email\":\"bot@x.com\",\"api_token\":\"$tok\",\"extra_fields\":{\"labels\":[\"bot\"]}},\"out\":{\"key\":\"k\"}},"
                    + "{\"id\":\"s\",\"action\":\"ooa.jira/search\",\"args\":{\"site_url\":\"URL\",\"jql\":\"project = OPS\",\"email\":\"bot@x.com\",\"api_token\":\"$tok\"},\"out\":{\"table\":\"t\"}},"
                    + "{\"id\":\"tr\",\"action\":\"ooa.jira/transition\",\"args\":{\"site_url\":\"URL\",\"key\":\"$k\",\"transition\":\"in progress\",\"email\":\"bot@x.com\",\"api_token\":\"$tok\"}},"
                    + "{\"id\":\"a\",\"action\":\"ooa.jira/attach_file\",\"args\":{\"site_url\":\"URL\",\"key\":\"$k\",\"path\":\"trace.log\",\"email\":\"bot@x.com\",\"api_token\":\"$tok\"}}]").replace("URL", f.url()), TOKEN);
            assertEquals("OPS-7", o.get("k"));
            assertEquals("Basic " + Base64.getEncoder().encodeToString("bot@x.com:tok-123".getBytes()), f.last("POST", "/rest/api/2/issue").auth());
            assertTrue(f.last("POST", "/rest/api/2/issue").text().contains("\"labels\":[\"bot\"]"));
            assertTrue(f.last("POST", "/rest/api/2/issue/OPS-7/transitions").text().contains("\"id\":\"21\""), "matched by target status name");
            Call att = f.last("POST", "/rest/api/2/issue/OPS-7/attachments");
            assertTrue(att.contentType().startsWith("multipart/form-data") && att.text().contains("filename=\"trace.log\""));
            RunResult bad = runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", ("[{\"id\":\"x\",\"action\":\"ooa.jira/transition\",\"args\":{\"site_url\":\"URL\",\"key\":\"OPS-7\",\"transition\":\"Reopen\",\"email\":\"b\",\"api_token\":\"$tok\"}}]").replace("URL", f.url()));
            assertEquals("JIRA.NO_TRANSITION", bad.error().get("code"));
            assertTrue(String.valueOf(bad.error().get("suggestedFix")).contains("Start"));
        }
    }

    @Test void slackOkFalseBecomesTypedErrorAndWorkdayPaging() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /api/chat.postMessage", c -> c.text().contains("C404") ? ok("{\"ok\":false,\"error\":\"channel_not_found\"}") : ok("{\"ok\":true,\"ts\":\"1700.01\"}"))
             .on("GET /ccx/api/v1/acme/workers", c -> q(c).contains("offset=0") ? ok("{\"total\":3,\"data\":[{\"id\":\"w1\",\"descriptor\":\"Ann\"},{\"id\":\"w2\",\"descriptor\":\"Bob\"}]}")
                    : ok("{\"total\":3,\"data\":[{\"id\":\"w3\",\"descriptor\":\"Cy\"}]}"));
            Map<String, Object> o = run(tmp(), "[" + TOKEN_VAR + ",{\"name\":\"ts\",\"type\":\"String\",\"direction\":\"output\"}]",
                    "[{\"id\":\"p\",\"action\":\"ooa.slack/post_message\",\"args\":{\"bot_token\":\"$tok\",\"channel\":\"C1\",\"text\":\"Done\",\"base_url\":\"" + f.url() + "/api\"},\"out\":{\"ts\":\"ts\"}}]", TOKEN);
            assertEquals("1700.01", o.get("ts"));
            RunResult bad = runRawWithToken(tmp(), "[" + TOKEN_VAR + "]", "[{\"id\":\"p\",\"action\":\"ooa.slack/post_message\",\"args\":{\"bot_token\":\"$tok\",\"channel\":\"C404\",\"text\":\"x\",\"base_url\":\"" + f.url() + "/api\"}}]");
            assertEquals("SLACK.CHANNEL_NOT_FOUND", bad.error().get("code"));
            var pkg = new WorkdayPackage();
            Map<String, Object> w = pkg.searchWorkers(PackagesTestSupport.ctx(tmp(), Map.of()), f.url().replace("http://", "http://"), "acme",
                    new com.oneofficeautomation.sdk.SecureString("t", "t"), null, 10);
            assertEquals(new BigDecimal(3), w.get("count"));
        }
    }

    @Test void aesRoundTripWrongKeyAndTamperDetection() throws Exception {
        char[] k = "correct horse".toCharArray();
        byte[] enc = CryptoPackage.aes(true, "salary: 5000".getBytes(), k);
        assertEquals("salary: 5000", new String(CryptoPackage.aes(false, enc, k)));
        assertEquals("CRYPTO.WRONG_KEY", assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> CryptoPackage.aes(false, enc, "wrong".toCharArray())).code());
        enc[enc.length - 1] ^= 1;
        assertEquals("CRYPTO.WRONG_KEY", assertThrows(com.oneofficeautomation.sdk.ActionException.class, () -> CryptoPackage.aes(false, enc, k)).code());
        assertFalse(Arrays.equals(CryptoPackage.aes(true, new byte[]{1}, k), CryptoPackage.aes(true, new byte[]{1}, k)), "random salt and IV each time");
    }

    @Test void powerActionsNeedConfirmationAndGlobalValues() throws Exception {
        RunResult r = runRaw(tmp(), "[]", "[{\"id\":\"s\",\"action\":\"ooa.system/shut_down\",\"args\":{\"confirm\":false}}]");
        assertEquals("SYSTEM.CONFIRM_REQUIRED", r.error().get("code"));
        Path w = tmp();
        Files.writeString(w.resolve("global-values.json"), "{\"reports_folder\":\"D:/Reports\",\"threshold\":1000}");
        Map<String, Object> o = run(w, "[{\"name\":\"f\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"t\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"d\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"info\",\"type\":\"Dictionary\",\"direction\":\"output\"}]",
                "[{\"id\":\"1\",\"action\":\"ooa.global_values/get\",\"args\":{\"name\":\"reports_folder\"},\"out\":{\"value\":\"f\"}},"
                + "{\"id\":\"2\",\"action\":\"ooa.global_values/get\",\"args\":{\"name\":\"threshold\"},\"out\":{\"value\":\"t\"}},"
                + "{\"id\":\"3\",\"action\":\"ooa.global_values/get\",\"args\":{\"name\":\"missing\",\"default\":\"x\"},\"out\":{\"value\":\"d\"}},"
                + "{\"id\":\"4\",\"action\":\"ooa.run_context/get\",\"out\":{\"info\":\"info\"}}]", Map.of());
        assertEquals("D:/Reports", o.get("f"));
        eqNum("1000", o.get("t"));
        assertEquals("x", o.get("d"));
        assertTrue(String.valueOf(((Map<?, ?>) o.get("info")).get("run_id")).startsWith("run_"));
        RunResult miss = runRaw(w, "[]", "[{\"id\":\"m\",\"action\":\"ooa.global_values/get\",\"args\":{\"name\":\"nope\"}}]");
        assertEquals("GLOBALS.NOT_FOUND", miss.error().get("code"));
    }
}
