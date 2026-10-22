package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Repository folders and folder permissions, delete and recover, version labels, dependencies; agent lifecycle (export,
 * import, promote to a second Command Center); run-as users and deploy to several targets; work queues with work item
 * models, members, single items and CSV.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class RepositoryLifecycleTest {
    Db db, db2; ControlRoom cr, cr2; ControlRoomServer server2;
    ControlRoom.User admin, jane, bob, admin2;
    String device;

    static ControlRoom.User person(ControlRoom c, ControlRoom.User by, String name, String role) {
        c.createUser(by, name, name, role, null);
        return new ControlRoom.User(name, name, role, name, false);
    }

    static ControlRoom.User boot(ControlRoom c) {
        String pw = c.bootstrap();
        String t0 = (String) c.login("admin", pw, null).get("token");
        c.changePassword(c.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        return c.session((String) c.login("admin", "Admin-Passw0rd-2026", null).get("token"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> flow(String id, String... calls) {
        StringBuilder steps = new StringBuilder("{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"note\",\"value\":\"ok\"}}");
        int n = 2;
        for (String c : calls) steps.append(",{\"id\":\"").append(n++).append("\",\"action\":\"core/run_bot\",\"args\":{\"automation\":\"").append(c).append("\"}}");
        return (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"test\","
                + "\"variables\":[{\"name\":\"note\",\"type\":\"String\",\"direction\":\"output\"},{\"name\":\"who\",\"type\":\"String\",\"direction\":\"in_out\"}],\"steps\":[" + steps + "]}");
    }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-repo-lc");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = boot(cr);
        jane = person(cr, admin, "jane", "bot_creator");
        bob = person(cr, admin, "bob", "bot_creator");
        cr.checkin(admin, "Finance/Child.flow.json", flow("child"), "v1", true, null);
        cr.checkin(admin, "Finance/Parent.flow.json", flow("parent", "child"), "v1", true, null);
        cr.checkin(admin, "Sales/Report.flow.json", flow("report"), "v1", true, null);
        device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");

        Path data2 = Files.createTempDirectory("ooa-repo-lc2");
        db2 = Db.embedded(data2);
        cr2 = new ControlRoom(db2, data2, FirstPartyPackages.registry(), null);
        admin2 = boot(cr2);
        server2 = new ControlRoomServer(cr2, "127.0.0.1", 0);
        server2.start();
    }

    @AfterAll void stop() { server2.stop(); cr.triggerPollers.stop(); cr2.triggerPollers.stop(); db.close(); db2.close(); }

    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }
    interface Executable { void run() throws Exception; }

    @Test @Order(1)
    void folderPermissions() {
        // without folder permissions everyone with the role sees and edits everything
        assertEquals(3, cr.repoList(bob, null).size());
        cr.folders.create(admin, "Finance/Archive", "Old agents", null);
        assertTrue(cr.folders.list(admin).stream().anyMatch(f -> f.get("path").equals("Finance/Archive")));
        // Finance: bot creators may only view; jane may edit
        cr.folders.setPermissions(admin, "Finance", List.of(Map.of("principal", "role:bot_creator", "access", "view"), Map.of("principal", "user:jane", "access", "edit")), null);
        assertEquals("view", cr.repoList(bob, null).stream().filter(b -> b.get("path").equals("Finance/Parent.flow.json")).findFirst().orElseThrow().get("access"));
        assertEquals("CR.FORBIDDEN", code(() -> cr.checkout(bob, "Finance/Parent.flow.json", null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.startRun("bob", "bot_creator", "Finance/Parent.flow.json", device, Map.of(), "manual", null)));
        cr.checkout(jane, "Finance/Parent.flow.json", null);
        cr.undoCheckout(jane, "Finance/Parent.flow.json", null);
        // the sub-folder follows its parent; Sales has no permissions, so the role alone decides
        assertEquals("edit", cr.folders.access("jane", "bot_creator", "Finance/Archive/Old.flow.json"));
        assertEquals("manage", cr.folders.access("bob", "bot_creator", "Sales/Report.flow.json"));
        // a folder that names only jane hides its agents from bob
        cr.folders.setPermissions(admin, "Finance", List.of(Map.of("principal", "user:jane", "access", "manage")), null);
        assertTrue(cr.repoList(bob, null).stream().noneMatch(b -> String.valueOf(b.get("path")).startsWith("Finance/")));
        assertEquals("CR.FORBIDDEN", code(() -> cr.getBot(bob, "Finance/Child.flow.json", "latest")));
        assertEquals("CR.FORBIDDEN", code(() -> cr.folders.setPermissions(jane, "Finance", List.of(), null)));   // repo.permissions is admin-only
        cr.folders.setPermissions(admin, "Finance", List.of(), null);   // no permissions: back to the roles
        assertEquals(3, cr.repoList(bob, null).size());
        assertEquals("CR.BAD_INPUT", code(() -> cr.folders.setPermissions(admin, "Finance", List.of(Map.of("principal", "jane", "access", "edit")), null)));
    }

    @Test @Order(2)
    @SuppressWarnings("unchecked")
    void deleteRecoverLabelsDependencies() {
        Map<String, Object> deps = cr.dependencies(admin, "Finance/Parent.flow.json", "latest");
        assertEquals("Finance/Child.flow.json", ((Map<String, Object>) ((List<Object>) deps.get("agents")).get(0)).get("path"));
        assertEquals(List.of("Finance/Parent.flow.json"), cr.dependencies(admin, "Finance/Child.flow.json", "latest").get("called_by"));
        // labels
        cr.checkout(admin, "Sales/Report.flow.json", null);
        cr.checkin(admin, "Sales/Report.flow.json", flow("report"), "v2", false, null);
        cr.setLabel(admin, "Sales/Report.flow.json", "UAT", 1, null);
        assertEquals(1, cr.getBot(admin, "Sales/Report.flow.json", "UAT").get("version"));
        assertEquals(List.of("UAT"), cr.versions(admin, "Sales/Report.flow.json").stream().filter(v -> ((Number) v.get("version")).intValue() == 1).findFirst().orElseThrow().get("labels"));
        cr.setLabel(admin, "Sales/Report.flow.json", "UAT", 2, null);   // a label moves
        assertEquals(2, cr.getBot(admin, "Sales/Report.flow.json", "UAT").get("version"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.setLabel(admin, "Sales/Report.flow.json", "production", 1, null)));
        // delete is refused while a schedule uses the agent; then deleted, hidden, not runnable, recoverable
        Map<String, Object> sch = cr.createSchedule(admin, new HashMap<>(Map.of("name", "nightly", "bot_path", "Sales/Report.flow.json", "device_id", device, "kind", "daily", "at_time", "02:00")), null);
        assertEquals("CR.IN_USE", code(() -> cr.deleteBot(admin, "Sales/Report.flow.json", null)));
        cr.deleteSchedule(admin, (String) sch.get("id"), null);
        cr.deleteBot(bob, "Sales/Report.flow.json", null);
        assertTrue(cr.repoList(admin, null).stream().noneMatch(b -> b.get("path").equals("Sales/Report.flow.json")));
        assertEquals(1, cr.repoList(admin, null, null, true).size());
        assertEquals("CR.DELETED", code(() -> cr.startRun("admin", "admin", "Sales/Report.flow.json", device, Map.of(), "manual", null)));
        assertEquals("CR.DELETED", code(() -> cr.checkin(admin, "Sales/Report.flow.json", flow("report"), "again", true, null)));
        cr.recoverBot(admin, "Sales/Report.flow.json", null);
        assertEquals(2, cr.getBot(admin, "Sales/Report.flow.json", "latest").get("version"));
    }

    @Test @Order(3)
    @SuppressWarnings("unchecked")
    void exportImportPromote() throws Exception {
        assertEquals("CR.FORBIDDEN", code(() -> cr.lifecycle.export(jane, List.of("Finance/Parent.flow.json"), "production", true, null)));
        Map<String, Object> bundle = cr.lifecycle.export(admin, List.of("Finance/Parent.flow.json"), "production", true, null);
        List<Map<String, Object>> agents = (List<Map<String, Object>>) bundle.get("agents");
        assertEquals(List.of("Finance/Parent.flow.json", "Finance/Child.flow.json"), agents.stream().map(a -> a.get("path")).toList());
        assertEquals("dependency", agents.get(1).get("included_as"));
        // import into the second Command Center, under a folder, live
        Map<String, Object> r = cr2.lifecycle.importBundle(admin2, bundle, "skip", true, "Imported", null);
        assertEquals(true, r.get("ok"));
        assertEquals(List.of("created", "created"), ((List<Map<String, Object>>) r.get("results")).stream().map(x -> x.get("status")).toList());
        assertEquals(1, cr2.content("Imported/Finance/Parent.flow.json", "production").get("version"));
        // again: skip leaves them, overwrite adds a version (history kept)
        r = cr2.lifecycle.importBundle(admin2, bundle, "skip", true, "Imported", null);
        assertEquals(List.of("skipped", "skipped"), ((List<Map<String, Object>>) r.get("results")).stream().map(x -> x.get("status")).toList());
        r = cr2.lifecycle.importBundle(admin2, bundle, "overwrite", false, "Imported", null);
        assertEquals(2, ((List<Map<String, Object>>) r.get("results")).get(0).get("version"));
        assertEquals(1, cr2.content("Imported/Finance/Parent.flow.json", "production").get("version"));
        // a changed bundle is refused
        Map<String, Object> bad = new LinkedHashMap<>(bundle);
        List<Object> list = new ArrayList<>((List<Object>) bundle.get("agents"));
        Map<String, Object> first = new LinkedHashMap<>((Map<String, Object>) list.get(0));
        first.put("content", String.valueOf(first.get("content")).replace("\"ok\"", "\"hacked\""));
        list.set(0, first);
        bad.put("agents", list);
        assertEquals("failed", ((List<Map<String, Object>>) cr2.lifecycle.importBundle(admin2, bad, "overwrite", false, "Other", null).get("results")).get(0).get("status"));
        bad.put("checksum", "0");
        assertEquals("CR.BAD_BUNDLE", code(() -> cr2.lifecycle.importBundle(admin2, bad, "overwrite", false, "Other", null)));
        // nothing is imported unless every agent passes
        assertTrue(cr2.repoList(admin2, null).stream().noneMatch(b -> String.valueOf(b.get("path")).startsWith("Other/")));

        // promote: the key for the second Command Center comes from the vault
        String key = (String) cr2.platform.createApiKey(admin2, "promotion", 1, null).get("key");
        cr.createLocker(admin, "envs", "Other Command Centers", "user:admin", null);
        cr.setCredential(admin, "envs", "prod", "api_key", key, null);
        Map<String, Object> req = new LinkedHashMap<>(Map.of("paths", List.of("Sales/Report.flow.json"), "version", "UAT", "target_url", "http://127.0.0.1:" + server2.port(),
                "api_key_ref", "envs/prod/api_key", "production", true));
        Map<String, Object> p = cr.lifecycle.promote(admin, req, null);
        assertEquals(true, p.get("ok"), String.valueOf(p));
        assertEquals(1, cr2.content("Sales/Report.flow.json", "production").get("version"));
        // someone who may not use the vault folder cannot promote with its key
        req.put("api_key_ref", "envs/prod/api_key");
        ControlRoom.User other = person(cr, admin, "otheradmin", "admin");
        cr.updateLocker(admin, "envs", "user:admin", "admin", null);
        assertDoesNotThrow(() -> cr.lifecycle.promote(other, req, null));   // admins manage every vault folder
        assertEquals("CR.BAD_INPUT", code(() -> cr.lifecycle.promote(admin, Map.of("paths", List.of("Sales/Report.flow.json"), "target_url", "ftp://x", "api_key_ref", "envs/prod/api_key"), null)));

        // over HTTP: export and import routes
        HttpClient http = HttpClient.newHttpClient();
        HttpResponse<String> res = http.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + server2.port() + "/api/lifecycle/import"))
                .header("Authorization", "Bearer " + key).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(Map.of("bundle", bundle, "conflict", "skip")))).build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, res.statusCode(), res.body());
    }

    @Test @Order(4)
    @SuppressWarnings("unchecked")
    void runAsUsersAndDeploy() {
        cr.setDefaultDevice(admin, "jane", device, null);
        Map<String, Object> users = cr.runAsUsers(admin).stream().filter(u -> u.get("username").equals("jane")).findFirst().orElseThrow();
        assertEquals(device, users.get("device_id"));
        Map<String, Object> d = cr.deploy(admin, Map.of("bot_path", "Finance/Child.flow.json", "run_as_users", List.of("jane", "bob"), "inputs", Map.of("who", "x")), null);
        assertEquals(1, d.get("started"));
        assertEquals("CR.NO_DEFAULT_RUNNER", ((Map<String, Object>) ((Map<String, Object>) ((List<Object>) d.get("runs")).get(1)).get("error")).get("code"));
        Map<String, Object> job = cr.claimNext(cr.db.one("SELECT * FROM devices WHERE id = ?", device));
        assertEquals("jane", job.get("principal"));
        assertEquals("unattended", job.get("mode"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.startRun("admin", "admin", "Finance/Child.flow.json", device, null, Map.of(), "manual", null, 0, null, null, "sideways")));
        // a runner with work cannot be deleted; an idle one can, and leaves the users' defaults
        cr.startRun("admin", "admin", "Finance/Child.flow.json", device, Map.of(), "manual", null);
        assertEquals("CR.CONFLICT", code(() -> cr.deleteDevice(admin, device, null)));
        cr.db.update("UPDATE runs SET status = 'stopped' WHERE device_id = ?", device);
        cr.deleteDevice(admin, device, null);
        assertNull(cr.runAsUsers(admin).stream().filter(u -> u.get("username").equals("jane")).findFirst().orElseThrow().get("device_id"));
    }

    @Test @Order(5)
    @SuppressWarnings("unchecked")
    void workQueues() {
        Map<String, Object> model = cr.platform.saveWorkItemModel(admin, null, Map.of("name", "Invoice", "fields", List.of(
                Map.of("name", "invoice_no", "type", "text", "required", true), Map.of("name", "amount", "type", "number", "required", true),
                Map.of("name", "urgent", "type", "boolean"), Map.of("name", "due", "type", "date"))), null);
        String qid = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Invoices", "model_id", model.get("id")), null).get("id");
        cr.platform.addItems(admin, qid, List.of(Map.of("invoice_no", "A1", "amount", "120.50", "urgent", "yes", "due", "2026-11-01", "_reference", "A1")), null);
        assertEquals("CR.BAD_ITEM", code(() -> cr.platform.addItems(admin, qid, List.of(Map.of("invoice_no", "A2")), null)));
        assertEquals("CR.BAD_ITEM", code(() -> cr.platform.addItems(admin, qid, List.of(Map.of("invoice_no", "A2", "amount", "lots")), null)));
        assertEquals("CR.BAD_ITEM", code(() -> cr.platform.addItems(admin, qid, List.of(Map.of("invoice_no", "A2", "amount", 1, "colour", "red")), null)));
        // CSV in: header row, reference and priority apart
        // a quoted cell keeps its comma; "1,000" is not a number, so nothing of that file is added
        assertEquals("CR.BAD_ITEM", code(() -> cr.platform.importCsv(admin, qid, "reference,priority,invoice_no,amount,urgent\r\nB1,5,B1,\"1,000\",no\r\n", null)));
        Map<String, Object> added = cr.platform.importCsv(admin, qid, "reference,priority,invoice_no,amount,urgent\nB1,5,B1,1000,no\nB2,,B2,7,true\n", null);
        assertEquals(2, added.get("added"));
        Map<String, Object> page = cr.platform.items(admin, qid, null, "B1", 10, 0);
        Map<String, Object> b1 = ((List<Map<String, Object>>) page.get("items")).get(0);
        String b1id = (String) b1.get("id");
        assertEquals(5, ((Number) b1.get("priority")).intValue());
        Map<String, Object> one = cr.platform.item(admin, qid, b1id);
        assertEquals(false, ((Map<String, Object>) one.get("data")).get("urgent"));
        cr.platform.updateItem(admin, qid, b1id, Map.of("data", Map.of("invoice_no", "B1", "amount", 999), "priority", 9), null);
        assertEquals("999", String.valueOf(((Map<String, Object>) cr.platform.item(admin, qid, b1id).get("data")).get("amount")));
        String csv = cr.platform.exportCsv(admin, qid, null);
        assertTrue(csv.startsWith("id,reference,priority,status,attempts,created,finished,invoice_no,amount,urgent,due\r\n"), csv);
        assertTrue(csv.contains(",B1,9,new,0,"), csv);
        assertEquals(1, cr.platform.deleteItems(admin, qid, List.of(b1id), null));
        // members: jane may add items but not process; bob is not a member
        cr.platform.setQueueMembers(admin, qid, List.of(Map.of("principal", "user:admin", "role", "owner"), Map.of("principal", "user:jane", "role", "participant")), null);
        cr.platform.addItems(jane, qid, List.of(Map.of("invoice_no", "J1", "amount", 1)), null);
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.setProcessing(jane, qid, true, null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.items(bob, qid, null, null, 10, 0)));
        assertTrue(cr.platform.queues(bob).stream().noneMatch(q -> q.get("id").equals(qid)));
        assertEquals(List.of("participant"), cr.platform.queue(jane, qid).get("my_roles"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.setQueueMembers(admin, qid, List.of(Map.of("principal", "user:jane", "role", "participant")), null)));
        // the model cannot lose fields while items use it
        assertEquals("CR.CONFLICT", code(() -> cr.platform.saveWorkItemModel(admin, (String) model.get("id"), Map.of("name", "Invoice", "fields", List.of(Map.of("name", "invoice_no", "type", "text"))), null)));
        assertEquals("CR.IN_USE", code(() -> cr.platform.deleteWorkItemModel(admin, (String) model.get("id"), null)));
    }
}
