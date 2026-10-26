package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.apache.poi.xssf.usermodel.XSSFWorkbook;
import org.junit.jupiter.api.*;

import java.io.ByteArrayOutputStream;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Folder rename, work items from Excel, queue definitions, toolkit version ranges, run retention, licence and variable profile. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class PendingItemsTest {
    Db db; ControlRoom cr; ControlRoom.User admin, jane;
    String device;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(String s) { return (Map<String, Object>) JsonLite.parse(s); }

    static Map<String, Object> agent(String id, String action) {
        return json("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[{\"name\":\"total\",\"type\":\"Number\",\"direction\":\"output\"}],"
                + "\"steps\":[{\"id\":\"1\",\"action\":\"" + action + "\",\"args\":" + (action.equals("core/set") ? "{\"variable\":\"total\",\"value\":1}" : "{\"url\":\"https://x.test\",\"method\":\"GET\"}") + "}]}");
    }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-pending");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_creator");
        cr.checkin(admin, "Finance/Pay/Run pay.flow.json", agent("run_pay", "core/set"), "v1", true, null);
        cr.checkin(admin, "Finance/Rest call.flow.json", agent("rest_call", "ooa.rest/request"), "v1", true, null);
        device = (String) cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null).get("device_id");
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @Test @Order(1)
    void renameFolder() {
        cr.setLabel(admin, "Finance/Pay/Run pay.flow.json", "UAT", 1, null);
        Map<String, Object> sch = cr.createSchedule(admin, new HashMap<>(Map.of("name", "pay", "bot_path", "Finance/Pay/Run pay.flow.json", "device_id", device, "kind", "daily", "at_time", "01:00")), null);
        String run = (String) cr.startRun("admin", "admin", "Finance/Pay/Run pay.flow.json", device, Map.of(), "manual", null).get("run_id");
        cr.folders.setPermissions(admin, "Finance/Pay", List.of(Map.of("principal", "user:jane", "access", "edit"), Map.of("principal", "user:admin", "access", "manage")), null);
        // a workflow naming an agent of the folder by path blocks the move
        cr.checkin(admin, "Proc/Pay.process.json", json("{\"name\":\"Pay\",\"stages\":[{\"id\":\"a\",\"type\":\"bot\",\"name\":\"Pay\",\"bot\":\"Finance/Pay/Run pay.flow.json\"}]}"), "v1", true, null);
        assertEquals("CR.IN_USE", code(() -> cr.folders.rename(admin, "Finance", "Accounts", null)));
        cr.deleteBot(admin, "Proc/Pay.process.json", null);
        db.update("DELETE FROM bots WHERE path = 'Proc/Pay.process.json'");   // gone for good, so it no longer names the folder
        Map<String, Object> r = cr.folders.rename(admin, "Finance", "Accounts", null);
        assertEquals(2, r.get("agents"));
        assertEquals(1, cr.getBot(admin, "Accounts/Pay/Run pay.flow.json", "UAT").get("version"));
        assertEquals("Accounts/Pay/Run pay.flow.json", cr.schedule(admin, (String) sch.get("id")).get("bot_path"));
        assertEquals("Accounts/Pay/Run pay.flow.json", db.one("SELECT bot_path FROM runs WHERE id = ?", run).get("bot_path"));
        assertEquals("edit", cr.folders.access("jane", "bot_creator", "Accounts/Pay/Run pay.flow.json"));
        assertEquals("CR.NOT_FOUND", code(() -> cr.getBot(admin, "Finance/Rest call.flow.json", "latest")));
        // into an existing folder, or inside itself, is refused
        cr.folders.create(admin, "Other", null, null);
        assertEquals("CR.CONFLICT", code(() -> cr.folders.rename(admin, "Accounts", "Other", null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.folders.rename(admin, "Accounts", "Accounts/Sub", null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.folders.rename(jane, "Accounts/Pay", "Accounts/Payroll", null)));   // jane can edit, not manage
        db.update("UPDATE runs SET status = 'stopped' WHERE id = ?", run);
    }

    @Test @Order(2)
    @SuppressWarnings("unchecked")
    void excelAndQueueDefinitions() throws Exception {
        Map<String, Object> model = cr.platform.saveWorkItemModel(admin, null, Map.of("name", "Invoice", "fields", List.of(
                Map.of("name", "invoice_no", "type", "text", "required", true), Map.of("name", "amount", "type", "number", "required", true), Map.of("name", "due", "type", "date"))), null);
        String qid = (String) cr.platform.saveQueue(admin, null, Map.of("name", "Invoices", "model_id", model.get("id")), null).get("id");
        byte[] xlsx;
        try (XSSFWorkbook wb = new XSSFWorkbook(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
            var sh = wb.createSheet("Items");
            var head = sh.createRow(0);
            String[] cols = {"reference", "priority", "invoice_no", "amount", "due"};
            for (int i = 0; i < cols.length; i++) head.createCell(i).setCellValue(cols[i]);
            var date = wb.createCellStyle();
            date.setDataFormat(wb.getCreationHelper().createDataFormat().getFormat("dd/mm/yyyy"));
            for (int r = 1; r <= 3; r++) {
                var row = sh.createRow(r);
                row.createCell(0).setCellValue("INV-" + r);
                row.createCell(1).setCellValue(r == 3 ? 9 : 0);
                row.createCell(2).setCellValue("INV-" + r);
                row.createCell(3).setCellValue(100.5 * r);
                var d = row.createCell(4); d.setCellValue(java.time.LocalDate.of(2026, 11, r)); d.setCellStyle(date);
            }
            wb.write(out);
            xlsx = out.toByteArray();
        }
        assertEquals(3, cr.platform.importXlsx(admin, qid, Base64.getEncoder().encodeToString(xlsx), "Items", null).get("added"));
        Map<String, Object> it = ((List<Map<String, Object>>) cr.platform.items(admin, qid, null, "INV-3", 10, 0).get("items")).get(0);
        Map<String, Object> data = (Map<String, Object>) JsonLite.parse((String) it.get("data"));
        assertEquals("2026-11-03", data.get("due"));
        assertEquals("301.5", String.valueOf(data.get("amount")));
        assertEquals(9, ((Number) it.get("priority")).intValue());
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.importXlsx(admin, qid, Base64.getEncoder().encodeToString("not excel".getBytes()), null, null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.importXlsx(admin, qid, Base64.getEncoder().encodeToString(xlsx), "Nope", null)));
        // definition out and back in under another name: the model is reused, the items come along, missing things are noted
        cr.platform.setQueueMembers(admin, qid, List.of(Map.of("principal", "user:admin", "role", "owner"), Map.of("principal", "user:jane", "role", "participant")), null);
        Map<String, Object> def = cr.platform.exportQueue(admin, qid, true);
        assertEquals(3, ((List<Object>) def.get("items")).size());
        Map<String, Object> def2 = new LinkedHashMap<>(def);
        def2.put("members", List.of(Map.of("principal", "user:admin", "role", "owner"), Map.of("principal", "user:ghost", "role", "consumer")));
        Map<String, Object> imp = cr.platform.importQueue(admin, def2, "Invoices (copy)", null);
        assertEquals(3, imp.get("items"));
        assertTrue(((List<String>) imp.get("notes")).stream().anyMatch(n -> n.contains("user:ghost")));
        assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM work_item_models"));
        assertEquals(model.get("id"), cr.platform.queue(admin, (String) imp.get("id")).get("model_id"));
        assertEquals("CR.CONFLICT", code(() -> cr.platform.importQueue(admin, def, null, null)));
    }

    @Test @Order(3)
    @SuppressWarnings("unchecked")
    void toolkitVersions() {
        Map<String, Object> v = cr.platform.toolkitVersions(admin, "ooa.rest");
        String installed = String.valueOf(v.get("installed"));
        Map<String, Object> a = ((List<Map<String, Object>>) v.get("agents")).get(0);
        assertEquals("Accounts/Rest call.flow.json", a.get("path"));
        assertNull(a.get("required"));
        String range = "^" + installed.split("\\.")[0] + ".0.0";
        Map<String, Object> up = cr.platform.updateToolkitRange(admin, "ooa.rest", range, null, null);
        assertEquals("updated", ((List<Map<String, Object>>) up.get("results")).get(0).get("status"));
        Map<String, Object> flow = (Map<String, Object>) cr.content("Accounts/Rest call.flow.json", "latest").get("flow");
        assertEquals(range, ((Map<String, Object>) ((Map<String, Object>) flow.get("requires")).get("packages")).get("ooa.rest"));
        assertEquals(1, cr.content("Accounts/Rest call.flow.json", "production").get("version"));   // not made live
        assertEquals("unchanged", ((List<Map<String, Object>>) cr.platform.updateToolkitRange(admin, "ooa.rest", range, null, null).get("results")).get(0).get("status"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.platform.updateToolkitRange(admin, "ooa.rest", "^999.0.0", null, null)));
        assertEquals(true, ((List<Map<String, Object>>) cr.platform.toolkitVersions(admin, "ooa.rest").get("agents")).get(0).get("satisfied"));
    }

    @Test @Order(4)
    @SuppressWarnings("unchecked")
    void retentionLicenceAndVariables() {
        Instant old = Instant.now().minusSeconds(40L * 86400), recent = Instant.now().minusSeconds(3600);
        int n = 0;
        for (Instant t : List.of(old, old, recent)) {
            n++;
            db.update("INSERT INTO runs (id, bot_path, version, device_id, status, inputs, requested_by, source, created, finished, cancel_requested, pause_requested, outputs) VALUES (?,?,?,?,?,?,?,?,?,?,?,?,?)",
                    "run_r" + n, "Accounts/Pay/Run pay.flow.json", 1, device, "success", "{}", "admin", "manual", t, t, false, false, JsonLite.write(Map.of("total", 10 * n, "state", n == 3 ? "late" : "ok")));
            db.update("INSERT INTO run_events (run_id, seq, event) VALUES (?,?,?)", "run_r" + n, 1, "{}");
        }
        Map<String, Object> prof = cr.analytics.variables(admin, "Accounts/Pay/Run pay.flow.json", Map.of("days", "90"));
        Map<String, Object> total = ((List<Map<String, Object>>) prof.get("variables")).stream().filter(x -> x.get("variable").equals("total")).findFirst().orElseThrow();
        assertEquals(10.0, total.get("min"));
        assertEquals(30.0, total.get("max"));
        Map<String, Object> state = ((List<Map<String, Object>>) prof.get("variables")).stream().filter(x -> x.get("variable").equals("state")).findFirst().orElseThrow();
        assertEquals("ok", ((List<Map<String, Object>>) state.get("common")).get(0).get("value"));
        // logs older than 7 days go, then runs older than 30 days
        Map<String, Object> p1 = cr.platform.purgeNow(admin, Map.of("log_days", 7), null);
        assertEquals(2L, ((Number) p1.get("log_lines_removed")).longValue());
        assertEquals(0L, ((Number) p1.get("runs_removed")).longValue());
        cr.platform.updateSettings(admin, Map.of("run_retention_days", 30), null);
        Map<String, Object> p2 = cr.platform.purge(30, 0, "system", null);
        assertEquals(2L, ((Number) p2.get("runs_removed")).longValue());
        assertNotNull(db.one("SELECT id FROM runs WHERE id = 'run_r3'"));
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.purgeNow(jane, Map.of("log_days", 1), null)));
        Map<String, Object> lic = cr.platform.license(admin);
        assertEquals("Unlicensed (no license installed)", lic.get("edition"));   // no license file: nothing is limited
        assertTrue(((Number) lic.get("agents")).intValue() >= 2);
    }
}
