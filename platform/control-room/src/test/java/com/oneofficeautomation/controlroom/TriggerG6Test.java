package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.runs.TriggerKinds;
import com.oneofficeautomation.controlroom.runs.Webhooks;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G6 trigger rows: new kinds and settings, email filters, Microsoft clouds, and webhook checks end to end. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class TriggerG6Test {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    Db db; ControlRoom cr; ControlRoomServer server; ControlRoom.User admin;
    String base, deviceId;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-trigger-g6");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t0 = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        String token = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        admin = cr.session(token);
        @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"on_hook\",\"name\":\"On hook\",\"description\":\"Reacts to webhooks\","
                + "\"variables\":[{\"name\":\"payload\",\"type\":\"Any\",\"direction\":\"input\"},{\"name\":\"order_id\",\"type\":\"String\",\"direction\":\"input\"}],"
                + "\"steps\":[]}");
        cr.checkin(admin, "Ops/On hook.flow.json", flow, "first", true, null);
        Map<String, Object> dev = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        deviceId = (String) dev.get("device_id");
        cr.createLocker(admin, "hooks", "Webhook secrets", "user:admin", null);
        cr.setCredential(admin, "hooks", "shop", "signing", "s3cret", null);
        cr.setCredential(admin, "hooks", "shop", "key", "k-123", null);
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll void stop() { if (server != null) server.stop(); }

    Map<String, Object> body(String kind, Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("name", kind + " g6", "bot_path", "Ops/On hook.flow.json", "device_id", deviceId, "kind", kind));
        for (int i = 0; i < kv.length; i += 2) m.put((String) kv[i], kv[i + 1]);
        return m;
    }

    static String bad(Runnable r) { return assertThrows(OoaException.class, r::run).getMessage(); }

    @Test void newKindsAndSettingsAreValidated() {
        for (String k : List.of("file_renamed", "folder_renamed")) {
            assertEquals(TriggerKinds.Where.RUNNER, TriggerKinds.kind(k).where());
            assertTrue(TriggerKinds.FOLDER_KINDS.contains(k));
        }
        assertTrue(TriggerKinds.kind("service_paused").windowsOnly());
        assertEquals(Map.of("fire_existing", true), TriggerKinds.validate("file", Map.of("folder", "C:/in", "fire_existing", true)));
        assertEquals(Map.of(), TriggerKinds.validate("file_deleted", Map.of("folder", "C:/in", "fire_existing", true)), "only arrive and created fire for what is there");
        assertEquals(Map.of("title", "Invoice", "case_sensitive", true), TriggerKinds.validate("window_opened", Map.of("title", "Invoice", "case_sensitive", true)));
        assertEquals(Map.of("title", "Invoice"), TriggerKinds.validate("window_opened", Map.of("title", "Invoice")));
        assertEquals(Map.of("process", "notepad.exe", "events", "both"), TriggerKinds.validate("process_started", Map.of("process", "notepad.exe", "events", "both")));
        assertTrue(bad(() -> TriggerKinds.validate("process_started", Map.of("process", "x.exe", "events", "all"))).contains("events"));
        assertEquals(Map.of("service", "Spooler"), TriggerKinds.validate("service_paused", Map.of("service", "Spooler")));
    }

    @Test void emailFiltersAndMicrosoftClouds() {
        Map<String, Object> imap = TriggerKinds.validate("email", Map.of("host", "imap.acme.test", "username", "ap", "password_ref", "a/b/c",
                "from_contains", "billing@; invoices@ ;", "subject_contains", "Invoice", "has_attachment", true));
        assertEquals(List.of("billing@", "invoices@"), imap.get("from_any"));
        assertEquals("Invoice", imap.get("subject_contains"));
        assertEquals(true, imap.get("has_attachment"));
        Map<String, Object> msg = new HashMap<>(Map.of("from", "Invoices@acme.test", "subject", "Invoice 7", "has_attachments", true));
        assertTrue(MailSource.matches(msg, imap));
        msg.put("from", "boss@acme.test");
        assertFalse(MailSource.matches(msg, imap), "no sender in the list matches");
        msg.put("from", "billing@acme.test"); msg.put("has_attachments", false);
        assertFalse(MailSource.matches(msg, imap), "only emails with attachments");
        assertTrue(ImapMailSource.hasAttachments("multipart/mixed; boundary=x", "--x\r\nContent-Disposition: attachment; filename=a.pdf".getBytes()));
        assertFalse(ImapMailSource.hasAttachments("text/plain", "hello".getBytes()));

        Map<String, Object> gov = TriggerKinds.validate("outlook", Map.of("tenant_id", "t", "client_id", "c", "client_secret_ref", "a/b/c", "mailbox", "ap@acme.us",
                "azure_cloud", "us_government", "has_attachment", true, "from_contains", "a;b"));
        assertEquals("https://graph.microsoft.us/v1.0", gov.get("graph_url"));
        assertEquals("https://login.microsoftonline.us", gov.get("login_url"));
        assertEquals("https://graph.microsoft.us/.default", gov.get("graph_scope"));
        assertEquals("a;b", gov.get("from_contains"), "lists are for IMAP only");
        assertEquals(true, gov.get("has_attachment"));
        Map<String, Object> china = TriggerKinds.validate("outlook", Map.of("token_ref", "a/b/c", "azure_cloud", "china"));
        assertEquals("https://microsoftgraph.chinacloudapi.cn/v1.0", china.get("graph_url"));
        assertFalse(TriggerKinds.validate("outlook", Map.of("token_ref", "a/b/c", "azure_cloud", "global")).containsKey("graph_url"));
        assertTrue(bad(() -> TriggerKinds.validate("outlook", Map.of("token_ref", "a/b/c", "azure_cloud", "mars"))).contains("azure_cloud"));
        assertTrue(bad(() -> TriggerKinds.validate("outlook", Map.of("ms_signin", "ms0123456789abcdef01234567", "azure_cloud", "china"))).contains("global cloud"));
    }

    @Test void webhookSettingsAndHelpers() {
        Map<String, Object> c = TriggerKinds.validate("webhook", Map.of("required_header", "X-Api-Key", "required_header_value_ref", "hooks/shop/key",
                "signature_scheme", "sha256", "signature_header", "X-Signature", "signature_secret_ref", "hooks/shop/signing", "challenge_field", "challenge",
                "input_map", Map.of("order_id", "$.order.id")));
        assertEquals("hooks/shop/signing", c.get("signature_secret_ref"));
        assertEquals(Map.of("order_id", "$.order.id"), c.get("input_map"));
        assertTrue(bad(() -> TriggerKinds.validate("webhook", Map.of("signature_scheme", "md5"))).contains("sha256"));
        assertTrue(bad(() -> TriggerKinds.validate("webhook", Map.of("signature_scheme", "sha1", "signature_header", "X-S", "signature_secret_ref", "plain-secret"))).contains("vault"));
        assertTrue(bad(() -> TriggerKinds.validate("webhook", Map.of("input_map", Map.of("payload", "$.a")))).contains("input name"));
        assertEquals(Map.of(), TriggerKinds.validate("webhook", Map.of()));

        assertEquals(Map.of("a", "1", "b", "x y"), Webhooks.payload("a=1&b=x+y".getBytes(), "application/x-www-form-urlencoded"));
        assertEquals("<a>1</a>", Webhooks.payload("<a>1</a>".getBytes(), "application/xml"));
        assertEquals("plain words", Webhooks.payload("plain words".getBytes(), null));
        assertEquals(1, ((Number) ((Map<?, ?>) Webhooks.payload("{\"k\":1}".getBytes(), null)).get("k")).intValue());
        assertThrows(OoaException.class, () -> Webhooks.payload("{bad".getBytes(), "application/json"));

        byte[] body = "{\"x\":1}".getBytes();
        String hex = hmac("HmacSHA256", "s3cret", body), b64 = Base64.getEncoder().encodeToString(HexFormat.of().parseHex(hex));
        assertTrue(Webhooks.signatureOk("sha256", "s3cret".getBytes(), body, "sha256=" + hex));
        assertTrue(Webhooks.signatureOk("sha256", "s3cret".getBytes(), body, hex.toUpperCase()));
        assertTrue(Webhooks.signatureOk("sha256", "s3cret".getBytes(), body, b64));
        assertTrue(Webhooks.signatureOk("sha1", "s3cret".getBytes(), body, "sha1=" + hmac("HmacSHA1", "s3cret", body)));
        assertFalse(Webhooks.signatureOk("sha256", "other".getBytes(), body, hex));
        assertFalse(Webhooks.signatureOk("sha256", "s3cret".getBytes(), "{\"x\":2}".getBytes(), hex));
    }

    static String hmac(String alg, String secret, byte[] body) {
        try {
            Mac m = Mac.getInstance(alg);
            m.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), alg));
            return HexFormat.of().formatHex(m.doFinal(body));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    HttpResponse<String> post(String tid, String body, String contentType, Map<String, String> headers) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/hooks/" + tid)).POST(HttpRequest.BodyPublishers.ofString(body));
        if (contentType != null) b.header("Content-Type", contentType);
        headers.forEach(b::header);
        return HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> runInputs(String runId) {
        Map<String, Object> run = db.one("SELECT inputs FROM runs WHERE id = ?", runId);
        assertNotNull(run);
        return (Map<String, Object>) JsonLite.parse(String.valueOf(run.get("inputs")));
    }

    @Test void signedWebhookWithChallengeHeaderAndMapping() throws Exception {
        Map<String, Object> made = cr.createTrigger(admin, body("webhook", "required_header", "X-Api-Key", "required_header_value_ref", "hooks/shop/key",
                "signature_scheme", "sha256", "signature_header", "X-Signature", "signature_secret_ref", "hooks/shop/signing", "challenge_field", "challenge",
                "input_map", Map.of("order_id", "$.order.id")), null);
        String tid = (String) made.get("id"), secret = (String) made.get("secret");

        HttpResponse<String> ch = post(tid, "{\"challenge\":\"abc123\"}", "application/json", Map.of());
        assertEquals(200, ch.statusCode());
        assertEquals("abc123", ((Map<?, ?>) JsonLite.parse(ch.body())).get("challenge"));

        String order = "{\"order\":{\"id\":\"SO-9\"}}";
        String sig = "sha256=" + hmac("HmacSHA256", "s3cret", order.getBytes(StandardCharsets.UTF_8));
        assertEquals(403, post(tid, order, "application/json", Map.of("X-Api-Key", "k-123")).statusCode(), "no signature");
        assertEquals(403, post(tid, order, "application/json", Map.of("X-Api-Key", "k-123", "X-Signature", "sha256=00")).statusCode(), "wrong signature");
        assertEquals(403, post(tid, order, "application/json", Map.of("X-Signature", sig)).statusCode(), "no required header");
        assertEquals(403, post(tid, order, "application/json", Map.of("X-Api-Key", "wrong", "X-Signature", sig)).statusCode(), "wrong header value");
        assertEquals(403, post(tid, order, "application/json", Map.of("X-Api-Key", "k-123", "X-Signature", sig, "X-OOA-Secret", "nope")).statusCode(), "a wrong secret is refused");
        HttpResponse<String> ok = post(tid, order, "application/json", Map.of("X-Api-Key", "k-123", "X-Signature", sig));
        assertEquals(202, ok.statusCode(), ok.body());
        assertEquals(202, post(tid, order, "application/json", Map.of("X-Api-Key", "k-123", "X-Signature", sig, "X-OOA-Secret", secret)).statusCode());
        Object runId = ((Map<?, ?>) JsonLite.parse(ok.body())).get("run_id");
        Map<String, Object> in = runInputs(String.valueOf(runId));
        assertEquals("SO-9", in.get("order_id"), String.valueOf(in));
        assertEquals(Map.of("order", Map.of("id", "SO-9")), in.get("payload"));
    }

    @Test void plainWebhookStillNeedsItsSecretAndTakesTextBodies() throws Exception {
        Map<String, Object> made = cr.createTrigger(admin, body("webhook"), null);
        String tid = (String) made.get("id"), secret = (String) made.get("secret");
        assertEquals(403, post(tid, "{}", "application/json", Map.of()).statusCode());
        assertEquals(202, post(tid, "<order id=\"1\"/>", "text/xml", Map.of("X-OOA-Secret", secret)).statusCode());
        assertEquals(202, post(tid, "a=1&b=2", "application/x-www-form-urlencoded", Map.of("X-OOA-Secret", secret)).statusCode());
        assertEquals(400, post(tid, "{bad", "application/json", Map.of("X-OOA-Secret", secret)).statusCode());
        assertEquals(403, post(tid, "{\"challenge\":\"x\"}", "application/json", Map.of()).statusCode(), "no challenge reply unless it is set up");
    }
}
