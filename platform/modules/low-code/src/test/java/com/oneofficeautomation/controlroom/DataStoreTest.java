package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.lowcode.AppEmbeds;
import com.oneofficeautomation.modules.lowcode.Apps;
import com.oneofficeautomation.modules.lowcode.DataStore;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G66: entities with checked fields and records, storage buckets, folders, and what runs and people may do. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class DataStoreTest {
    Db db; ControlRoom cr; ControlRoom.User admin, vic, cara;
    Map<String, Object> dev;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-data");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.createUser(admin, "vic", "Vic Viewer", "viewer", null);
        cr.createUser(admin, "cara", "Cara Creator", "bot_creator", null);
        vic = new ControlRoom.User("vic", "vic", "viewer", "Vic Viewer", false);
        cara = new ControlRoom.User("cara", "cara", "bot_creator", "Cara Creator", false);
        cr.checkin(admin, "Finance/Post.flow.json", LicensingAndChecksTest.agent("post"), "v1", true, null);
        cr.checkin(admin, "HR/Hire.flow.json", LicensingAndChecksTest.agent("hire"), "v1", true, null);
        Map<String, Object> r = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "Data-PC", "data-pc", "Windows 11", "0.2.0", null);
        dev = cr.device((String) r.get("device_id"), (String) r.get("secret"));
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    String running(String path) {
        String id = (String) cr.startRun("admin", "admin", path, (String) dev.get("id"), Map.of(), "manual", null).get("run_id");
        assertNotNull(cr.claimNext(dev));
        return id;
    }

    Map<String, Object> ask(String run, Object... kv) {
        Map<String, Object> req = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) req.put((String) kv[i], kv[i + 1]);
        return cr.modules.service(DataStore.class).forRun(dev, Map.of("run_id", run, "request", req));
    }

    static Map<String, Object> field(String name, String type, boolean required, boolean unique, String... choices) {
        Map<String, Object> f = new LinkedHashMap<>(Map.of("name", name, "type", type, "required", required, "unique", unique));
        if (choices.length > 0) f.put("choices", List.of(choices));
        return f;
    }

    @Test @SuppressWarnings("unchecked")
    void recordsAreCheckedQueriedAndKeptForTheirFolder() {
        Map<String, Object> e = cr.modules.service(DataStore.class).saveEntity(admin, null, Map.of("name", "Suppliers", "folder", "Finance", "fields", List.of(
            field("name", "text", true, true), field("country", "choice", false, false, "IN", "US", "DE"), field("limit", "number", false, false),
            field("active", "boolean", false, false), field("since", "date", false, false))), "127.0.0.1");
        assertEquals(0L, e.get("records"));
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.modules.service(DataStore.class).saveEntity(cara, null, Map.of("name", "X", "fields", List.of(field("a", "text", false, false))), null)).code());
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.modules.service(DataStore.class).saveEntity(admin, null, Map.of("name", "Bad", "fields", List.of(field("1x", "text", false, false))), null)).code());

        String run = running("Finance/Post.flow.json");
        Map<String, Object> acme = ask(run, "op", "create", "entity", "suppliers", "data", Map.of("name", "ACME", "country", "IN", "limit", "5000", "active", "yes", "since", "2024-03-01"));
        Map<String, Object> d = (Map<String, Object>) acme.get("data");
        assertEquals(new BigDecimal("5000"), new BigDecimal(d.get("limit").toString()));
        assertEquals(true, d.get("active"));
        assertTrue(String.valueOf(acme.get("created_by")).startsWith("agent:Finance/Post.flow.json for admin"));
        ask(run, "op", "create", "entity", "Suppliers", "data", Map.of("name", "Globex", "country", "US", "limit", 900));
        ask(run, "op", "create", "entity", "Suppliers", "data", Map.of("name", "Initech", "country", "DE", "limit", 12000, "active", false));
        assertEquals("DATA.DUPLICATE", assertThrows(OoaException.class, () -> ask(run, "op", "create", "entity", "Suppliers", "data", Map.of("name", "acme"))).code());
        assertEquals("DATA.REQUIRED", assertThrows(OoaException.class, () -> ask(run, "op", "create", "entity", "Suppliers", "data", Map.of("country", "IN"))).code());
        assertEquals("DATA.BAD_VALUE", assertThrows(OoaException.class, () -> ask(run, "op", "create", "entity", "Suppliers", "data", Map.of("name", "Z", "country", "FR"))).code());
        assertEquals("DATA.NO_FIELD", assertThrows(OoaException.class, () -> ask(run, "op", "create", "entity", "Suppliers", "data", Map.of("name", "Z", "colour", "red"))).code());

        Map<String, Object> q = ask(run, "op", "query", "entity", "Suppliers", "filters", List.of(Map.of("field", "limit", "op", ">", "value", "1000")), "sort", "limit", "descending", true);
        List<Map<String, Object>> recs = (List<Map<String, Object>>) q.get("records");
        assertEquals(List.of("Initech", "ACME"), recs.stream().map(r -> ((Map<String, Object>) r.get("data")).get("name")).toList(), "numbers compare by value, not as text");
        assertEquals(1, ((List<?>) ask(run, "op", "query", "entity", "Suppliers", "filters", List.of(Map.of("field", "name", "op", "starts_with", "value", "glo"))).get("records")).size());
        assertEquals(1, ((List<?>) ask(run, "op", "query", "entity", "Suppliers", "filters", List.of(Map.of("field", "active", "op", "empty"))).get("records")).size(),
            "only Globex has no active value");

        String id = (String) acme.get("id");
        Map<String, Object> changed = ask(run, "op", "update", "entity", "Suppliers", "id", id, "data", Map.of("limit", 6000, "since", ""));
        assertEquals("ACME", ((Map<String, Object>) changed.get("data")).get("name"), "an update keeps the fields not given");
        assertFalse(((Map<String, Object>) changed.get("data")).containsKey("since"), "an empty value clears the field");
        assertEquals(true, ask(run, "op", "delete", "entity", "Suppliers", "id", id).get("deleted"));
        assertEquals(false, ask(run, "op", "delete", "entity", "Suppliers", "id", id).get("deleted"));

        // an agent in another folder cannot use it
        String other = running("HR/Hire.flow.json");
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> ask(other, "op", "query", "entity", "Suppliers")).code());

        // people: viewers see, creators change records, the console's records page searches
        assertEquals(2L, ((Map<String, Object>) cr.modules.service(DataStore.class).entities(vic).get(0)).get("records"));
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.modules.service(DataStore.class).saveRecord(vic, "Suppliers", null, Map.of("data", Map.of("name", "V")), null)).code());
        Map<String, Object> made = cr.modules.service(DataStore.class).saveRecord(cara, "Suppliers", null, Map.of("data", Map.of("name", "Umbrella", "country", "DE")), "127.0.0.1");
        assertEquals(1, cr.modules.service(DataStore.class).records(vic, (String) e.get("id"), Map.of("q", "umbrel")).get("total"));
        cr.modules.service(DataStore.class).deleteRecord(cara, "Suppliers", (String) made.get("id"), null);
        assertEquals(1L, ((Number) db.one("SELECT COUNT(*) AS n FROM audit WHERE action = 'data.record_delete'").get("n")).longValue());

        cr.modules.service(DataStore.class).deleteEntity(admin, (String) e.get("id"), null);
        assertEquals(0L, ((Number) db.one("SELECT COUNT(*) AS n FROM entity_records").get("n")).longValue());
    }

    @Test @SuppressWarnings("unchecked")
    void bucketsKeepFilesThatRunsReadAndWrite() {
        Map<String, Object> b = cr.modules.service(DataStore.class).saveBucket(admin, null, Map.of("name", "Invoices", "folder", "", "max_mb", 1), null);
        String run = running("HR/Hire.flow.json");
        String content = Base64.getEncoder().encodeToString("hello".getBytes(StandardCharsets.UTF_8));
        assertEquals(5, ask(run, "op", "upload", "bucket", "invoices", "path", "2026/acme.pdf", "content", content).get("bytes"));
        assertEquals("DATA.EXISTS", assertThrows(OoaException.class, () -> ask(run, "op", "upload", "bucket", "Invoices", "path", "2026/acme.pdf", "content", content, "overwrite", false)).code());
        assertEquals("hello", new String(Base64.getDecoder().decode((String) ask(run, "op", "download", "bucket", "Invoices", "path", "2026/acme.pdf").get("content")), StandardCharsets.UTF_8));
        List<Map<String, Object>> files = (List<Map<String, Object>>) ask(run, "op", "list_files", "bucket", "Invoices", "prefix", "2026/").get("files");
        assertEquals("2026/acme.pdf", files.get(0).get("path"));
        assertEquals("DATA.BAD_PATH", assertThrows(OoaException.class, () -> ask(run, "op", "download", "bucket", "Invoices", "path", "../../secret.txt")).code());
        String big = Base64.getEncoder().encodeToString(new byte[1024 * 1024 + 1]);
        assertEquals("DATA.TOO_LARGE", assertThrows(OoaException.class, () -> ask(run, "op", "upload", "bucket", "Invoices", "path", "big.bin", "content", big)).code());
        assertEquals(1L, ((Number) cr.modules.service(DataStore.class).buckets(vic).get(0).get("files")).longValue());
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.modules.service(DataStore.class).upload(vic, "Invoices", "a.txt", new byte[1], null)).code());
        cr.modules.service(DataStore.class).upload(cara, "Invoices", "notes/a.txt", "x".getBytes(StandardCharsets.UTF_8), null);
        assertArrayEquals("x".getBytes(StandardCharsets.UTF_8), cr.modules.service(DataStore.class).download(vic, "Invoices", "notes/a.txt"));
        assertEquals(true, ask(run, "op", "delete_file", "bucket", "Invoices", "path", "2026/acme.pdf").get("deleted"));
        cr.modules.service(DataStore.class).deleteBucket(admin, (String) b.get("id"), null);
        assertEquals("CR.NOT_FOUND", assertThrows(OoaException.class, () -> ask(run, "op", "list_files", "bucket", "Invoices")).code());
    }
}
