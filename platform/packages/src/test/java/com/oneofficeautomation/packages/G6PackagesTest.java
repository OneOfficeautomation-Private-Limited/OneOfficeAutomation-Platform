package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.Signature;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** G6 rows: documents update_result, download and process_version; Google service account keys; each_item by status and in batches. */
class G6PackagesTest {

    // ---------- Document Automation ----------

    final DocumentAutomationPackage docs = new DocumentAutomationPackage();

    @Test void updateResultSendsTheCorrectionsAsADictionaryOrJson(@TempDir Path dir) {
        List<Map<String, Object>> asked = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> service = req -> { asked.add(req); return Map.of("status", "validated", "fields", Map.of("total", "110.00")); };
        ActionContext ctx = DocumentAutomationPackageTest.ctx(dir, Map.of("docflow", service));
        Map<String, Object> out = docs.updateResult(ctx, " d1 ", Map.of("total", "110.00"));
        assertEquals("validated", out.get("status"));
        assertEquals(Map.of("op", "update", "document_id", "d1", "fields", Map.of("total", "110.00")), asked.get(0));
        docs.updateResult(ctx, "d1", "{\"total\": \"99\"}");
        assertEquals(Map.of("total", "99"), asked.get(1).get("fields"));
        for (Object bad : new Object[]{"not json", "[1]", Map.of(), null})
            assertEquals("DOCS.BAD_FIELDS", assertThrows(ActionException.class, () -> docs.updateResult(ctx, "d1", bad)).code());
        assertEquals("DOCS.NO_DOCUMENT", assertThrows(ActionException.class, () -> docs.updateResult(ctx, " ", Map.of("a", 1))).code());
    }

    @Test void downloadSavesTheFileInTheFolderOrTheRunsTemporaryFolder(@TempDir Path dir) throws Exception {
        List<Map<String, Object>> asked = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> service = req -> {
            asked.add(req);
            return Map.of("file_name", "INV-1.csv", "data", Base64.getEncoder().encodeToString("field,value\ntotal,115\n".getBytes(StandardCharsets.UTF_8)), "deleted", false,
                    "delete_note", "kept");
        };
        ActionContext ctx = DocumentAutomationPackageTest.ctx(dir, Map.of("docflow", service));
        Map<String, Object> out = docs.download(ctx, "d1", "csv", "out", true);
        Path saved = Path.of(String.valueOf(out.get("path")));
        assertEquals(dir.resolve("out").resolve("INV-1.csv").toAbsolutePath().normalize(), saved.toAbsolutePath().normalize());
        assertEquals("field,value\ntotal,115\n", Files.readString(saved));
        assertEquals(false, out.get("deleted"));
        assertEquals(Map.of("op", "download", "document_id", "d1", "kind", "csv", "delete_after", true), asked.get(0));
        Map<String, Object> temp = docs.download(ctx, "d1", null, "", false);
        assertTrue(String.valueOf(temp.get("path")).contains(".ooa-temp"), "an empty folder saves in the run's temporary folder");
        assertEquals("DOCS.BAD_KIND", assertThrows(ActionException.class, () -> docs.download(ctx, "d1", "xlsx", null, false)).code());
    }

    @Test void sendPassesTheProcessVersionOnlyWhenGiven(@TempDir Path dir) throws Exception {
        Files.writeString(dir.resolve("a.pdf"), "%PDF");
        List<Map<String, Object>> asked = new ArrayList<>();
        Function<Map<String, Object>, Map<String, Object>> service = req -> { asked.add(req); return Map.of("document_id", "d1", "status", "processing"); };
        ActionContext ctx = DocumentAutomationPackageTest.ctx(dir, Map.of("docflow", service));
        docs.send(ctx, "AP", "a.pdf", false, 3);
        docs.send(ctx, "AP", "a.pdf", false);
        assertEquals(3, asked.get(0).get("process_version"));
        assertFalse(asked.get(1).containsKey("process_version"));
    }

    // ---------- Google service account ----------

    final GoogleAiPackage google = new GoogleAiPackage();

    static String keyJson(KeyPair kp, String tokenUri) {
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        return JsonLite.write(Map.of("type", "service_account", "client_email", "bot@acme.iam.gserviceaccount.com", "private_key_id", "k1", "private_key", pem, "token_uri", tokenUri));
    }

    @Test void serviceAccountKeySignsInAndKeepsTheTokenUntilItExpires(@TempDir Path dir) throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        KeyPair kp = g.generateKeyPair();
        AtomicInteger tokens = new AtomicInteger();
        try (Fake f = new Fake()) {
            f.on("POST /token", c -> new Object[]{200, JsonLite.write(Map.of("access_token", "ya29.sa" + tokens.incrementAndGet(), "expires_in", 3600, "token_type", "Bearer")),
                            Map.of("Content-Type", "application/json")})
             .on("POST /v1/projects/.*", c -> new Object[]{200, JsonLite.write(Map.of("document", Map.of("text", "Hi", "pages", List.of()))), Map.of("Content-Type", "application/json")});
            Files.writeString(dir.resolve("key.json"), keyJson(kp, f.url() + "/token"));
            Files.writeString(dir.resolve("a.pdf"), "%PDF");
            ActionContext ctx = RestToolkitTestSupport.ctx(dir);
            google.connect(ctx, null, "service_account_json", "key.json", "acme-ai", "us", f.url(), "google_ai");
            google.documentaiProcess(ctx, "google_ai", "p1", "a.pdf", null, null, true);
            google.documentaiProcess(ctx, "google_ai", "p1", "a.pdf", "other-proj", null, null, true);
            assertEquals(1, tokens.get(), "the token is kept until it is about to expire");
            Call doc = f.last("POST", "/v1/projects/other-proj/.*");
            assertNotNull(doc, "the step's project wins over Connect's");
            assertEquals("Bearer ya29.sa1", doc.auth());

            Call tok = f.last("POST", "/token");
            String form = tok.text();
            assertTrue(form.startsWith("grant_type=urn%3Aietf%3Aparams%3Aoauth%3Agrant-type%3Ajwt-bearer&assertion="));
            String jwt = form.substring(form.indexOf("assertion=") + 10);
            String[] parts = jwt.split("\\.");
            assertEquals(3, parts.length);
            Map<?, ?> header = (Map<?, ?>) JsonLite.parse(new String(Base64.getUrlDecoder().decode(parts[0]), StandardCharsets.UTF_8));
            Map<?, ?> claims = (Map<?, ?>) JsonLite.parse(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8));
            assertEquals("RS256", header.get("alg"));
            assertEquals("k1", header.get("kid"));
            assertEquals("bot@acme.iam.gserviceaccount.com", claims.get("iss"));
            assertEquals("https://www.googleapis.com/auth/cloud-platform", claims.get("scope"));
            assertEquals(f.url() + "/token", claims.get("aud"));
            Signature v = Signature.getInstance("SHA256withRSA");
            v.initVerify(kp.getPublic());
            v.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
            assertTrue(v.verify(Base64.getUrlDecoder().decode(parts[2])), "signed with the key's private key");

            // the same key from the vault; an expired token is fetched again
            google.connect(ctx, new SecureString(keyJson(kp, f.url() + "/token"), "g/sa/key"), "service_account_json", null, "acme-ai", "us", f.url(), "vaulted");
            GoogleAiPackage.GoogleAiSession s = ctx.sessionOrOnly("vaulted", "google_ai", GoogleAiPackage.GoogleAiSession.class);
            long[] now = {1_000_000};
            s.account.clock = () -> now[0];
            assertEquals("ya29.sa2", s.account.token(ctx));
            now[0] += 3000;
            assertEquals("ya29.sa2", s.account.token(ctx));
            now[0] += 600;
            assertEquals("ya29.sa3", s.account.token(ctx));
        }
    }

    @Test void badServiceAccountKeysAreRefused(@TempDir Path dir) {
        ActionContext ctx = RestToolkitTestSupport.ctx(dir);
        assertEquals("GOOGLE_AI.NO_CREDENTIAL", assertThrows(ActionException.class, () -> google.connect(ctx, null, "service_account_json", null, null, null, null, "x")).code());
        assertEquals("GOOGLE_AI.BAD_KEY_FILE", assertThrows(ActionException.class,
                () -> google.connect(ctx, new SecureString("{\"type\":\"service_account\"}", "a/b/c"), "service_account_json", null, null, null, null, "x")).code());
        assertEquals("GOOGLE_AI.BAD_KEY_FILE", assertThrows(ActionException.class,
                () -> google.connect(ctx, new SecureString("{\"client_email\":\"a@b\",\"private_key\":\"nope\"}", "a/b/c"), "service_account_json", null, null, null, null, "x")).code());
        assertEquals("GOOGLE_AI.BAD_CREDENTIAL_TYPE", assertThrows(ActionException.class, () -> google.connect(ctx, null, "password", null, null, null, null, "x")).code());
    }

    // ---------- Work queues: each_item ----------

    final WorkloadPackage work = new WorkloadPackage();

    static Map<String, Object> item(String id, String status, String errorKind, String deferUntil) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("id", id, "status", status, "attempts", BigDecimal.ONE, "data", Map.of("n", id)));
        if (errorKind != null) m.put("error_kind", errorKind);
        if (deferUntil != null) m.put("defer_until", deferUntil);
        return m;
    }

    List<Object> ids(Iterable<Object> it) {
        List<Object> out = new ArrayList<>();
        for (Object o : it) out.add(((Map<?, ?>) o).get("item_id"));
        return out;
    }

    @Test void eachItemWorksTheChosenStatusInBatches(@TempDir Path dir) {
        ActionContext ctx = RestToolkitTestSupport.ctx(dir);
        WorkloadPackage.update(ctx, "q", true, items -> {
            items.add(item("n1", "new", null, null));
            items.add(item("h1", "deferred", null, "9999-12-31T23:59:59"));
            items.add(item("d1", "failed", "business", null));
            items.add(item("f1", "failed", "system", null));
            items.add(item("n2", "new", null, null));
            items.add(item("p1", "deferred", null, "2000-01-01T00:00:00"));
            return null;
        });
        assertEquals(List.of("h1", "p1"), ids(work.eachItem(ctx, "q", 1000, "on_hold", 100)));
        assertEquals(List.of("d1"), ids(work.eachItem(ctx, "q", 1000, "data_error", 100)));
        assertEquals(List.of("f1"), ids(work.eachItem(ctx, "q", 1000, "failed", 100)));
        assertEquals(List.of("n1"), ids(work.eachItem(ctx, "q", 1, "new", 100)), "max_items still caps the loop");
        assertEquals(List.of("n2"), ids(work.eachItem(ctx, "q", 1000)));
        assertEquals(List.of(), ids(work.eachItem(ctx, "q", 1000, "new", 5)));
        Map<?, ?> st = WorkloadPackage.update(ctx, "q", false, items -> {
            Map<String, Object> m = new HashMap<>();
            for (Map<String, Object> i : items) m.put(String.valueOf(i.get("id")), i.get("status"));
            return m;
        });
        assertTrue(st.values().stream().allMatch("in_progress"::equals), "every taken item is in progress: " + st);
        assertEquals("QUEUE.BAD_STATUS", assertThrows(ActionException.class, () -> work.eachItem(ctx, "q", 10, "done", 1)).code());
    }

    @Test void aBatchTakesSeveralItemsAtOneLook(@TempDir Path dir) {
        ActionContext ctx = RestToolkitTestSupport.ctx(dir);
        WorkloadPackage.update(ctx, "b", true, items -> { for (int i = 0; i < 5; i++) items.add(item("i" + i, "new", null, null)); return null; });
        Iterator<Object> it = work.eachItem(ctx, "b", 1000, "new", 3).iterator();
        assertTrue(it.hasNext());
        long inProgress = WorkloadPackage.update(ctx, "b", false, items -> items.stream().filter(i -> "in_progress".equals(i.get("status"))).count());
        assertEquals(3, inProgress, "the first look took three");
        List<Object> all = new ArrayList<>();
        while (it.hasNext()) all.add(((Map<?, ?>) it.next()).get("item_id"));
        assertEquals(List.of("i0", "i1", "i2", "i3", "i4"), all);
    }
}
