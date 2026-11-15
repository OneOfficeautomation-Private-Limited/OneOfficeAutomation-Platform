package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.mail.MailRules;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Mail rules (MA-2): one reader per mailbox, rules in order, every match, forwarded copies once, Not matched, Try the rule. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)   // one mailbox for all: its read position moves on from test to test
class MailRouterTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    Db db; ControlRoom cr; ControlRoomServer server; HttpServer ms; String base, token, account;
    final List<Map<String, Object>> inbox = new CopyOnWriteArrayList<>();
    final List<String> delivered = new CopyOnWriteArrayList<>();

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-mail-router");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t0 = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        token = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        ControlRoom.User admin = cr.session(token);
        // a stand-in for Document Automation's processes: records what each target receives
        cr.mailRouter.target("box", new ModuleContext.MailTarget() {
            public String check(ControlRoom.User u, String target) {
                if (target.equals("forbidden")) throw new OoaException("CR.FORBIDDEN", "You may not change this process");
                return "Box " + target;
            }
            public void deliver(ModuleContext.MailDelivery d) {
                delivered.add(d.target() + ":" + d.email().get("subject") + ":" + d.attachments().stream().map(a -> a.name()).toList() + ":" + d.asUser());
            }
        });
        ms = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        String claims = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"tid\":\"t-1\",\"preferred_username\":\"admin@contoso.com\"}".getBytes(StandardCharsets.UTF_8));
        ms.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath(), q = ex.getRequestURI().getRawQuery() == null ? "" : URLDecoder.decode(ex.getRequestURI().getRawQuery(), StandardCharsets.UTF_8);
            ex.getRequestBody().readAllBytes();
            String out;
            if (path.endsWith("/devicecode")) out = "{\"device_code\":\"D\",\"user_code\":\"CODE-1\",\"expires_in\":900,\"interval\":1}";
            else if (path.endsWith("/token")) out = "{\"access_token\":\"acc\",\"refresh_token\":\"ref\",\"expires_in\":3600,\"id_token\":\"x." + claims + ".y\"}";
            else if (path.endsWith("/attachments")) {
                String id = path.split("/")[path.split("/").length - 2];
                Map<String, Object> m = inbox.stream().filter(x -> x.get("id").equals(id)).findFirst().orElse(Map.of());
                List<Object> files = new ArrayList<>();
                for (Object f : (List<?>) m.getOrDefault("files", List.of()))
                    files.add(Map.of("@odata.type", "#microsoft.graph.fileAttachment", "name", f, "contentType", String.valueOf(f).endsWith(".pdf") ? "application/pdf" : "application/octet-stream", "contentBytes", "JVBERi0xLjQ="));
                out = JsonLite.write(Map.of("value", files));
            } else if (path.contains("/mailFolders/")) {
                List<Map<String, Object>> sorted = new ArrayList<>(inbox);
                sorted.sort(Comparator.comparing(x -> String.valueOf(x.get("receivedDateTime"))));
                if (q.contains("$top=1&")) out = JsonLite.write(Map.of("value", sorted.isEmpty() ? List.of() : List.of(graph(sorted.get(sorted.size() - 1)))));
                else {
                    String ts = q.replaceAll(".*receivedDateTime ge ([^&]+).*", "$1");
                    out = JsonLite.write(Map.of("value", sorted.stream().filter(x -> String.valueOf(x.get("receivedDateTime")).compareTo(ts) >= 0).map(MailRouterTest::graph).toList()));
                }
            } else out = "{}";
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        ms.start();
        String url = "http://127.0.0.1:" + ms.getAddress().getPort();
        System.setProperty("ooa.microsoft.login.url", url);
        System.setProperty("ooa.microsoft.graph.url", url + "/v1.0");
        cr.platform.updateSettings(admin, Map.of("microsoft_app_id", "11111111-2222-3333-4444-555555555555"), null);
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
        String sid = (String) call("POST", "/api/mail-accounts/ms-signin", Map.of(), token).get("id");
        db.update("UPDATE ms_signins SET next_poll = ? WHERE id = ?", java.time.Instant.now().minusSeconds(5), sid);
        assertEquals("signed_in", call("GET", "/api/mail-accounts/ms-signin/" + sid, null, token).get("status"));
        account = (String) call("POST", "/api/mail-accounts", Map.of("name", "AP invoices mailbox", "provider", "microsoft365", "address", "invoices@contoso.com",
                "ms_signin", sid, "mailbox", "invoices@contoso.com"), token).get("id");
    }

    @AfterAll void stop() {
        System.clearProperty("ooa.microsoft.login.url");
        System.clearProperty("ooa.microsoft.graph.url");
        server.stop(); ms.stop(0); db.close();
    }

    static Map<String, Object> graph(Map<String, Object> m) {
        return Map.of("id", m.get("id"), "subject", m.get("subject"), "from", Map.of("emailAddress", Map.of("name", "Sender", "address", m.get("from"))),
                "toRecipients", List.of(Map.of("emailAddress", Map.of("address", "invoices@contoso.com"))), "receivedDateTime", m.get("receivedDateTime"),
                "bodyPreview", m.getOrDefault("body", ""), "hasAttachments", !((List<?>) m.getOrDefault("files", List.of())).isEmpty(), "internetMessageId", m.getOrDefault("mid", "<" + m.get("id") + "@x>"));
    }

    void mail(String id, String time, String from, String subject, List<String> files, String mid) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("id", id, "receivedDateTime", "2026-11-15T" + time + ":00Z", "from", from, "subject", subject, "files", files));
        if (mid != null) m.put("mid", mid);
        inbox.add(m);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, String path, Object body, String as) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + as).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body))).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> m = r.body().isBlank() ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(r.body()));
        m.put("_status", r.statusCode());
        m.put("_body", r.body());
        return m;
    }

    Map<String, Object> rule(String name, Map<String, Object> conditions, String target) throws Exception {
        Map<String, Object> r = call("POST", "/api/mail-accounts/" + account + "/rules", Map.of("name", name, "conditions", conditions, "target_kind", "box", "target", target), token);
        assertEquals(200, r.get("_status"), String.valueOf(r.get("_body")));
        return r;
    }

    static Map<String, Object> when(Object... fieldOpValue) {
        List<Map<String, Object>> w = new ArrayList<>();
        for (int i = 0; i < fieldOpValue.length; i += 3) w.add(Map.of("field", fieldOpValue[i], "op", fieldOpValue[i + 1], "value", fieldOpValue[i + 2]));
        return Map.of("match", "all", "when", w);
    }

    @Test @Order(1) @SuppressWarnings("unchecked")
    void eachEmailGoesWhereItsRulesSayOnce() throws Exception {
        Map<String, Object> invoices = rule("Supplier invoices", Map.of("match", "all",
                "when", List.of(Map.of("field", "from", "op", "domain_in", "value", List.of("@acme.com", "@globex.com")), Map.of("field", "attachment_type", "op", "is_one_of", "value", List.of("pdf", "xml"))),
                "except", List.of(Map.of("field", "from", "op", "contains", "value", "noreply@"))), "ap");
        Map<String, Object> everything = rule("Everything else", Map.of("match", "all"), "inbox");
        Map<String, Object> credits = rule("Credit notes", when("subject", "contains", "credit note"), "cn");
        assertEquals("from @acme.com or @globex.com · has a PDF or XML file · except not from noreply@".replace("except not from", "except from"), String.valueOf(invoices.get("says")).replace("except not from", "except from"));
        List<Map<String, Object>> rules = (List<Map<String, Object>>) call("GET", "/api/mail-accounts/" + account + "/rules", null, token).get("rules");
        assertEquals(List.of("Supplier invoices", "Credit notes", "Everything else"), rules.stream().map(r -> r.get("name")).toList(), "the rule that takes every email stays last");

        mail("m0", "08:00", "old@acme.com", "Old invoice", List.of("old.pdf"), null);
        assertEquals(0, ((Number) call("POST", "/api/mail-accounts/" + account + "/read", null, token).get("read")).intValue(), "the first look only marks where the mailbox is");
        mail("m1", "09:00", "billing@acme.com", "Invoice 1", List.of("INV-1.pdf"), "<inv-1@acme.com>");
        mail("m2", "09:01", "noreply@acme.com", "Payment advice", List.of("advice.pdf"), null);
        mail("m3", "09:02", "x@y.com", "Credit note 5", List.of(), null);
        mail("m4", "09:03", "billing@acme.com", "FW: Invoice 1", List.of("INV-1.pdf"), "<INV-1@acme.com>");   // the same email again
        Map<String, Object> read = call("POST", "/api/mail-accounts/" + account + "/read", null, token);
        assertEquals(3, ((Number) read.get("read")).intValue(), String.valueOf(read));
        assertEquals(List.of("ap:Invoice 1:[INV-1.pdf]:admin", "inbox:Payment advice:[advice.pdf]:admin", "cn:Credit note 5:[]:admin"), delivered);
        assertEquals(0, ((Number) call("POST", "/api/mail-accounts/" + account + "/read", null, token).get("read")).intValue(), "nothing twice");

        // every matching rule: one email can go to several places
        call("PUT", "/api/mail-accounts/" + account, Map.of("rule_mode", "every"), token);
        delivered.clear();
        mail("m5", "10:00", "billing@globex.com", "Credit note for invoice 7", List.of("CN-7.pdf"), null);
        call("POST", "/api/mail-accounts/" + account + "/read", null, token);
        assertEquals(Set.of("ap:Credit note for invoice 7:[CN-7.pdf]:admin", "cn:Credit note for invoice 7:[CN-7.pdf]:admin", "inbox:Credit note for invoice 7:[CN-7.pdf]:admin"), new HashSet<>(delivered));
        call("PUT", "/api/mail-accounts/" + account, Map.of("rule_mode", "first"), token);

        // without a rule for everything else, emails wait in Not matched; they can be sent on by hand, or ignored
        assertEquals(200, call("DELETE", "/api/mail-rules/" + everything.get("id"), null, token).get("_status"));
        delivered.clear();
        mail("m6", "11:00", "sales@hooli.com", "Quotation Q-2041", List.of("Q-2041.pdf"), null);
        mail("m7", "11:01", "mike@acme.local", "Out of office", List.of(), null);
        call("POST", "/api/mail-accounts/" + account + "/read", null, token);
        assertEquals(List.of(), delivered);
        List<Map<String, Object>> waiting = (List<Map<String, Object>>) call("GET", "/api/mail-accounts/" + account + "/unmatched", null, token).get("emails");
        assertEquals(List.of("Out of office", "Quotation Q-2041"), waiting.stream().map(w -> w.get("subject")).toList());
        assertEquals(200, call("POST", "/api/mail-unmatched/" + waiting.get(1).get("id") + "/send", Map.of("rule", invoices.get("id")), token).get("_status"));
        assertEquals(List.of("ap:Quotation Q-2041:[Q-2041.pdf]:admin"), delivered);
        assertEquals(200, call("POST", "/api/mail-unmatched/" + waiting.get(0).get("id") + "/ignore", null, token).get("_status"));
        assertEquals(List.of(), call("GET", "/api/mail-accounts/" + account + "/unmatched", null, token).get("emails"));

        // the order rules are checked in
        List<String> ids = List.of(String.valueOf(credits.get("id")), String.valueOf(invoices.get("id")));
        assertEquals(200, call("PUT", "/api/mail-accounts/" + account + "/rules/order", Map.of("ids", ids), token).get("_status"));
        assertEquals(List.of("Credit notes", "Supplier invoices"), ((List<Map<String, Object>>) call("GET", "/api/mail-accounts/" + account + "/rules", null, token).get("rules")).stream().map(r -> r.get("name")).toList());
        assertEquals(400, call("PUT", "/api/mail-accounts/" + account + "/rules/order", Map.of("ids", List.of(ids.get(0))), token).get("_status"), "every rule once");

        // Try the rule: on the latest emails, reading nothing into any process
        delivered.clear();
        Map<String, Object> fresh = call("POST", "/api/mail-accounts/" + account + "/rules/try", Map.of("conditions", when("subject", "contains", "invoice")), token);
        assertEquals(List.of(), fresh.get("matched"), "a new rule goes last: the existing rules take these first");
        assertEquals(4, ((Number) fresh.get("taken_by_earlier")).intValue(), String.valueOf(fresh.get("_body")));
        Map<String, Object> tried = call("POST", "/api/mail-accounts/" + account + "/rules/try", Map.of("conditions", when("subject", "contains", "invoice"), "rule", invoices.get("id")), token);
        assertEquals(List.of("Old invoice", "Invoice 1", "FW: Invoice 1"), ((List<Map<String, Object>>) tried.get("matched")).stream().map(m -> m.get("subject")).toList(), String.valueOf(tried.get("_body")));
        assertEquals(1, ((Number) tried.get("taken_by_earlier")).intValue(), "Credit note for invoice 7 goes to Credit notes, which is checked first");
        assertEquals(List.of(), delivered);
    }

    @Test @Order(2) @SuppressWarnings("unchecked")
    void aNewEmailTriggerOnAMailAccountStartsItsAgent() throws Exception {
        ControlRoom.User admin = cr.session(token);
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"on_mail\",\"name\":\"On mail\",\"description\":\"Reacts to emails\","
                + "\"variables\":[{\"name\":\"trigger_email\",\"type\":\"Dictionary\",\"direction\":\"input\"},{\"name\":\"note\",\"type\":\"String\",\"direction\":\"output\"}],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"note\",\"value\":\"ok\"}}]}");
        cr.checkin(admin, "Ops/On mail.flow.json", flow, "first", true, null);
        String device = String.valueOf(cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-9", "pc-9", "Windows 11", "0.2.0", null).get("device_id"));
        Map<String, Object> made = call("POST", "/api/triggers", Map.of("name", "Statements in", "kind", "mail", "bot_path", "Ops/On mail.flow.json", "device_id", device,
                "mail_account", "AP invoices mailbox", "conditions", when("subject", "contains", "statement")), token);
        assertEquals(200, made.get("_status"), String.valueOf(made.get("_body")));
        String tid = String.valueOf(made.get("id"));
        List<Map<String, Object>> rules = (List<Map<String, Object>>) call("GET", "/api/mail-rules?target_kind=trigger&target=" + tid, null, token).get("rules");
        assertEquals(1, rules.size());
        assertEquals("Statements in", rules.get(0).get("target_name"));
        assertEquals("subject has statement", rules.get(0).get("says"));
        assertEquals("AP invoices mailbox", rules.get(0).get("account_name"));
        assertEquals(400, call("POST", "/api/triggers", Map.of("name", "No account", "kind", "mail", "bot_path", "Ops/On mail.flow.json", "device_id", device), token).get("_status"));
        assertEquals(404, call("POST", "/api/triggers", Map.of("name", "Unknown", "kind", "mail", "bot_path", "Ops/On mail.flow.json", "device_id", device, "mail_account", "Nope"), token).get("_status"));

        call("POST", "/api/mail-accounts/" + account + "/read", null, token);                  // where the mailbox is now
        mail("s1", "23:00", "bank@hdfc.example", "Your statement for October", List.of("statement.pdf"), null);
        Map<String, Object> checked = call("POST", "/api/triggers/" + tid + "/poll", null, token);   // Check now reads the account
        assertEquals(200, checked.get("_status"), String.valueOf(checked.get("_body")));
        List<Map<String, Object>> runs = db.query("SELECT inputs, source FROM runs WHERE bot_path = ?", "Ops/On mail.flow.json");
        assertEquals(1, runs.size(), String.valueOf(checked));
        assertTrue(String.valueOf(runs.get(0).get("inputs")).contains("Your statement for October"));
        assertTrue(String.valueOf(runs.get(0).get("source")).startsWith("mail-rule:"), String.valueOf(runs.get(0).get("source")));

        assertEquals(200, call("DELETE", "/api/triggers/" + tid, null, token).get("_status"));
        assertEquals(List.of(), call("GET", "/api/mail-rules?target_kind=trigger&target=" + tid, null, token).get("rules"), "its rule goes with it");
    }

    @Test @Order(3) void rulesAreCheckedWhenSaved() throws Exception {
        assertEquals(400, call("POST", "/api/mail-accounts/" + account + "/rules", Map.of("name", "Bad", "conditions", when("colour", "is", "red"), "target_kind", "box", "target", "x"), token).get("_status"));
        assertEquals(400, call("POST", "/api/mail-accounts/" + account + "/rules", Map.of("name", "Bad", "conditions", when("subject", "regex", "(unclosed"), "target_kind", "box", "target", "x"), token).get("_status"));
        assertEquals(400, call("POST", "/api/mail-accounts/" + account + "/rules", Map.of("name", "Bad", "conditions", when("subject", "contains", "x"), "target_kind", "nowhere", "target", "x"), token).get("_status"));
        assertEquals(403, call("POST", "/api/mail-accounts/" + account + "/rules", Map.of("name", "Bad", "conditions", when("subject", "contains", "x"), "target_kind", "box", "target", "forbidden"), token).get("_status"),
                "the target decides who may send emails to it");
        // someone who may not use the account cannot add rules to it
        ControlRoom.User admin = cr.session(token);
        String temp = String.valueOf(cr.createUser(admin, "rita", "rita", "bot_creator", null).get("temporary_password"));
        String t0 = (String) cr.login("rita", temp, null).get("token");
        cr.changePassword(cr.session(t0), temp, "Rita-Passw0rd-2026", t0, null);
        String rita = (String) cr.login("rita", "Rita-Passw0rd-2026", null).get("token");
        assertEquals(403, call("POST", "/api/mail-accounts/" + account + "/rules", Map.of("name", "Mine", "conditions", when("subject", "contains", "x"), "target_kind", "box", "target", "r"), rita).get("_status"));
        assertEquals(403, call("GET", "/api/mail-accounts/" + account + "/rules", null, rita).get("_status"));
    }

    @Test void conditionsReadAsPeopleSayThem() {
        Map<String, Object> c = MailRules.check(Map.of("match", "any", "when", List.of(Map.of("field", "to", "op", "contains", "value", "invoices+ap@"), Map.of("field", "attachment_name", "op", "matches", "value", "INV-*.pdf"))));
        assertEquals("to invoices+ap@ or a file named INV-*.pdf", MailRules.describe(c));
        assertTrue(MailRules.matches(c, Map.of("to", "Invoices <invoices+ap@acme.com>", "has_attachments", false), List::of));
        assertTrue(MailRules.matches(c, Map.of("to", "x@y.com", "has_attachments", true), () -> List.of(new MailSource.Attachment("inv-77.PDF", "application/pdf", new byte[0]))));
        assertFalse(MailRules.matches(c, Map.of("to", "x@y.com", "has_attachments", true), () -> List.of(new MailSource.Attachment("photo.jpg", "image/jpeg", new byte[0]))));
        assertEquals("Every email", MailRules.describe(MailRules.check(null)));
        assertTrue(MailRules.matches(MailRules.check(Map.of("when", List.of(Map.of("field", "received_after", "op", "after", "value", "2026-11-01")))),
                Map.of("date", "Tue, 10 Nov 2026 09:00:00 +0000"), List::of));
    }
}
