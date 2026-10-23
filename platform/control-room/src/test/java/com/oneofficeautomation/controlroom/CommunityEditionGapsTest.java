package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The gaps found in the reference product's Community Edition: repository files, moving and copying agents, Prompt
 * Skills kept centrally, model connection roles and test, vault attribute flags and requests, several roles per
 * person, shared values people change for themselves, and runner settings.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class CommunityEditionGapsTest {
    Db db; ControlRoom cr; ControlRoom.User admin, jane, bob;
    String device;

    interface Executable { void run() throws Exception; }
    static String code(Executable e) { return assertThrows(OoaException.class, e::run).code(); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> json(String s) { return (Map<String, Object>) JsonLite.parse(s); }

    static Map<String, Object> agent(String id, String extra) {
        return json("{\"kind\":\"TaskBot\",\"id\":\"" + id + "\",\"name\":\"" + id + "\",\"description\":\"t\",\"variables\":[{\"name\":\"x\",\"type\":\"Number\"}]," + extra
                + "\"steps\":[{\"id\":\"1\",\"action\":\"core/set\",\"args\":{\"variable\":\"x\",\"value\":1}}]}");
    }

    ControlRoom.User real(String name) { return ControlRoom.user(db.one("SELECT * FROM users WHERE username = ?", name)); }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-ce-gaps");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        RepositoryLifecycleTest.person(cr, admin, "jane", "bot_creator");
        RepositoryLifecycleTest.person(cr, admin, "bob", "viewer");
        jane = real("jane"); bob = real("bob");
        Map<String, Object> reg = cr.registerDevice((String) cr.createRegistrationKey(admin, 1, null).get("key"), "PC-1", "pc-1", "Windows 11", "0.2.0", null);
        device = (String) reg.get("device_id");
    }

    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> claim() { return cr.claimNext(db.one("SELECT * FROM devices WHERE id = ?", device)); }

    void finishAll() { db.update("UPDATE runs SET status = 'completed', finished = CURRENT_TIMESTAMP WHERE status IN ('queued', 'running')"); }

    @Test @Order(1)
    @SuppressWarnings("unchecked")
    void repositoryFilesReachTheRunner() {
        byte[] xlsx = "PK fake workbook".getBytes(StandardCharsets.UTF_8);
        Map<String, Object> v1 = cr.repoFiles.put(jane, "Finance/Templates/invoice.xlsx", xlsx, "application/vnd.ms-excel", null);
        assertEquals(1, v1.get("version"));
        assertEquals(2, cr.repoFiles.put(jane, "Finance/Templates/invoice.xlsx", "PK v2".getBytes(StandardCharsets.UTF_8), null, null).get("version"));
        assertEquals("PK v2", new String((byte[]) cr.repoFiles.get(bob, "Finance/Templates/invoice.xlsx").get("content"), StandardCharsets.UTF_8));
        assertEquals(1, cr.repoFiles.list(bob, "Finance").size());
        assertTrue(cr.folders.list(admin).stream().anyMatch(f -> "Finance/Templates".equals(f.get("path"))), "a folder holding only files is listed");
        // agents are not files; paths stay inside the repository; viewers cannot add
        assertEquals("CR.BAD_PATH", code(() -> cr.repoFiles.put(jane, "Finance/x.flow.json", xlsx, null, null)));
        assertEquals("CR.BAD_PATH", code(() -> cr.repoFiles.put(jane, "../etc/passwd", xlsx, null, null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.repoFiles.put(bob, "Finance/b.txt", xlsx, null, null)));
        assertEquals("CR.TOO_LARGE", code(() -> cr.repoFiles.put(jane, "Finance/big.bin", new byte[(int) RepoFiles.MAX_BYTES + 1], null, null)));

        // an agent that requires the file gets it with the job
        cr.checkin(jane, "Finance/Fill invoice.flow.json", agent("fill_invoice", "\"requires\":{\"files\":[\"Finance/Templates/invoice.xlsx\"]},"), "v1", true, null);
        cr.startRun("jane", "bot_creator", "Finance/Fill invoice.flow.json", device, Map.of(), "manual", null);
        Map<String, Object> job = claim();
        Map<String, Object> files = (Map<String, Object>) job.get("files");
        assertEquals("PK v2", new String(Base64.getDecoder().decode((String) files.get("Finance/Templates/invoice.xlsx")), StandardCharsets.UTF_8));
        finishAll();

        // folder rename takes files along; a folder with files cannot be deleted
        cr.folders.rename(admin, "Finance/Templates", "Finance/Forms", null);
        assertEquals("Finance/Forms/invoice.xlsx", cr.repoFiles.list(admin, null).get(0).get("path"));
        assertEquals("CR.CONFLICT", code(() -> cr.folders.delete(admin, "Finance/Forms", null)));

        // the agent still names the old path: its run fails before it starts, with the reason
        String run = (String) cr.startRun("jane", "bot_creator", "Finance/Fill invoice.flow.json", device, Map.of(), "manual", null).get("run_id");
        assertNull(claim());
        Map<String, Object> failed = db.one("SELECT status, error FROM runs WHERE id = ?", run);
        assertEquals("failed", failed.get("status"));
        assertTrue(String.valueOf(failed.get("error")).contains("CR.NOT_FOUND"), String.valueOf(failed.get("error")));

        // files in folders a person cannot view are not theirs to use
        cr.folders.setPermissions(admin, "Finance/Forms", List.of(Map.of("principal", "user:admin", "access", "manage")), null);
        assertTrue(cr.repoFiles.list(bob, null).isEmpty());
        assertEquals("CR.FORBIDDEN", code(() -> cr.repoFiles.get(bob, "Finance/Forms/invoice.xlsx")));
        cr.folders.setPermissions(admin, "Finance/Forms", List.of(), null);
        cr.repoFiles.delete(jane, "Finance/Forms/invoice.xlsx", null);
        assertTrue(cr.repoFiles.list(admin, null).isEmpty());
    }

    @Test @Order(2)
    void moveAndCopyAgents() {
        cr.checkin(jane, "Sales/Quote.flow.json", agent("quote", ""), "v1", true, null);
        cr.setLabel(jane, "Sales/Quote.flow.json", "UAT", 1, null);
        Map<String, Object> sch = cr.createSchedule(admin, new HashMap<>(Map.of("name", "q", "bot_path", "Sales/Quote.flow.json", "device_id", device, "kind", "daily", "at_time", "02:00")), null);
        assertEquals("Archive/Quote.flow.json", cr.moveBot(jane, "Sales/Quote.flow.json", "Archive", null).get("path"));
        assertEquals(1, cr.getBot(admin, "Archive/Quote.flow.json", "UAT").get("version"));
        assertEquals("Archive/Quote.flow.json", cr.schedule(admin, (String) sch.get("id")).get("bot_path"));
        assertEquals("CR.NOT_FOUND", code(() -> cr.getBot(admin, "Sales/Quote.flow.json", "latest")));
        assertEquals("CR.FORBIDDEN", code(() -> cr.moveBot(bob, "Archive/Quote.flow.json", "Sales", null)));
        // a workflow that names it blocks the move
        cr.checkin(admin, "Proc/Q.process.json", json("{\"name\":\"Q\",\"stages\":[{\"id\":\"a\",\"type\":\"bot\",\"name\":\"Q\",\"bot\":\"Archive/Quote.flow.json\"}]}"), "v1", true, null);
        assertEquals("CR.IN_USE", code(() -> cr.moveBot(jane, "Archive/Quote.flow.json", "Sales", null)));

        Map<String, Object> copy = cr.copyBot(jane, "Archive/Quote.flow.json", null, null);
        assertEquals("Archive/Quote (copy).flow.json", copy.get("path"));
        Map<String, Object> flow = castMap(cr.getBot(jane, "Archive/Quote (copy).flow.json", "latest").get("flow"));
        assertEquals("quote_copy", flow.get("id"), "the copy gets its own agent id");
        assertEquals("quote_copy2", castMap(cr.getBot(jane, (String) cr.copyBot(jane, "Archive/Quote.flow.json", "Sales/Quote 2.flow.json", null).get("path"), "latest").get("flow")).get("id"));
        assertEquals("CR.CONFLICT", code(() -> cr.copyBot(jane, "Archive/Quote.flow.json", "Sales/Quote 2.flow.json", null)));
        assertEquals("CR.BAD_PATH", code(() -> cr.copyBot(jane, "Archive/Quote.flow.json", "Sales/Quote 3.process.json", null)));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> castMap(Object o) { return (Map<String, Object>) o; }

    @Test @Order(3)
    @SuppressWarnings("unchecked")
    void promptSkillsAreSharedVersionedAndSentWithRuns() {
        Map<String, Object> def = Map.of("prompt", "Summarise: ${text}", "output", "text", "temperature", 0.2);
        assertEquals(1, cr.promptSkills.save(jane, "summary", Map.of("description", "Short summary", "definition", def), null).get("version"));
        assertTrue(cr.promptSkills.list(bob).isEmpty(), "private until shared");
        assertEquals(2, cr.promptSkills.save(jane, "summary", Map.of("description", "Short summary", "definition", Map.of("prompt", "Summarise briefly: ${text}"), "shared", true, "comment", "shorter"), null).get("version"));
        assertEquals(1, cr.promptSkills.list(bob).size());
        Map<String, Object> s = cr.promptSkills.get(bob, "summary", 1);
        assertEquals("Summarise: ${text}", ((Map<String, Object>) s.get("definition")).get("prompt"));
        assertEquals(2, ((List<Object>) s.get("versions")).size());
        assertEquals("CR.FORBIDDEN", code(() -> cr.promptSkills.save(bob, "summary", Map.of("definition", def), null)));
        assertEquals("CR.BAD_INPUT", code(() -> cr.promptSkills.save(jane, "bad", Map.of("definition", Map.of("output", "text")), null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.promptSkills.save(jane, "tpl", Map.of("definition", def, "template", true), null)), "only administrators publish templates");
        assertEquals(3, cr.promptSkills.templates(jane).size(), "the built-in templates");
        assertEquals("route", cr.promptSkills.fromTemplate(jane, "template_route_request", "route", null).get("name"));

        // a run gets the skills its agent names
        cr.checkin(jane, "Sales/Summarise.flow.json", json("{\"kind\":\"TaskBot\",\"id\":\"summ\",\"name\":\"s\",\"variables\":[],\"steps\":[{\"id\":\"1\",\"action\":\"ooa.ai_skills/run\",\"args\":{\"skill\":\"summary\",\"values\":{}}}]}"), "v1", true, null);
        cr.startRun("bob", "bot_runner", "Sales/Summarise.flow.json", device, Map.of(), "manual", null);
        Map<String, Object> job = claim();
        Map<String, Object> skills = (Map<String, Object>) job.get("skills");
        assertEquals(Set.of("summary"), skills.keySet());
        assertEquals("Summarise briefly: ${text}", ((Map<String, Object>) skills.get("summary")).get("prompt"));
        finishAll();
        cr.promptSkills.delete(jane, "route", null);
        assertEquals("CR.NOT_FOUND", code(() -> cr.promptSkills.get(jane, "route", null)));
    }

    @Test @Order(4)
    @SuppressWarnings("unchecked")
    void modelConnectionsByRoleAndTest() throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> auth = new ArrayList<>();
        s.createContext("/", ex -> {
            auth.add(ex.getRequestHeaders().getFirst("Authorization"));
            byte[] b = "{\"choices\":[{\"message\":{\"content\":\"OK\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":1}}".getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, b.length);
            try (OutputStream os = ex.getResponseBody()) { os.write(b); }
        });
        s.start();
        try {
            cr.createLocker(admin, "ai", "keys", "role:admin", null);
            cr.setCredential(admin, "ai", "openai", "key", "sk-live-test", null);
            String url = "http://127.0.0.1:" + s.getAddress().getPort() + "/v1";
            cr.platform.saveModelConnection(admin, new HashMap<>(Map.of("name", "gpt", "provider", "openai", "model", "gpt-4o-mini", "base_url", url, "key_ref", "ai/openai/key",
                    "allowed_roles", List.of("bot_creator"), "model_type", "fine_tuned")), null);
            cr.platform.saveModelConnection(admin, new HashMap<>(Map.of("name", "open", "provider", "google_vertex", "model", "gemini-2.5-flash")), null);
            Map<String, Object> gpt = cr.platform.modelConnections(admin).stream().filter(c -> c.get("name").equals("gpt")).findFirst().orElseThrow();
            assertEquals(List.of("bot_creator"), gpt.get("allowed_roles"));
            assertEquals("fine_tuned", gpt.get("model_type"));
            assertEquals("CR.BAD_INPUT", code(() -> cr.platform.saveModelConnection(admin, new HashMap<>(Map.of("name", "x", "provider", "openai", "allowed_roles", List.of("nobody"))), null)));

            Map<String, Object> t = cr.platform.testModelConnection(admin, Map.of("name", "gpt", "provider", "openai", "model", "gpt-4o-mini", "base_url", url, "key_ref", "ai/openai/key"), null);
            assertEquals(true, t.get("ok"), String.valueOf(t));
            assertEquals("Bearer sk-live-test", auth.get(auth.size() - 1));
            assertEquals("CR.FORBIDDEN", code(() -> cr.platform.testModelConnection(jane, Map.of("provider", "openai"), null)));

            // a viewer's run does not receive the bot_creator-only connection
            List<Object> forViewer = (List<Object>) cr.platform.connectionsForJob(List.of("viewer")).get("connections");
            assertEquals(List.of("open"), forViewer.stream().map(c -> ((Map<String, Object>) c).get("name")).toList());
            assertEquals(2, ((List<Object>) cr.platform.connectionsForJob(List.of("viewer", "bot_creator")).get("connections")).size());
        } finally { s.stop(0); }
    }

    @Test @Order(5)
    @SuppressWarnings("unchecked")
    void vaultAttributeFlagsAndRequests() {
        cr.createLocker(admin, "portal", "Supplier portal", "role:viewer", null);
        cr.setCredential(admin, "portal", "login", "username", "svc-portal", null);
        cr.vaultAdmin.saveCredential(admin, "portal", "login", Map.of("user_attributes", List.of("password"), "attributes_info", Map.of(
                "username", Map.of("description", "Service account name", "masked", false),
                "password", Map.of("description", "Your own portal password", "password_only", true))), null);
        Map<String, Object> c = cr.vaultAdmin.credential(admin, "portal", "login");
        Map<String, Object> pw = ((List<Map<String, Object>>) c.get("attributes")).stream().filter(a -> a.get("attribute").equals("password")).findFirst().orElseThrow();
        assertEquals("Your own portal password", pw.get("description"));
        assertEquals(true, pw.get("password_only"));
        assertEquals(true, pw.get("masked"));
        assertEquals("svc-portal", cr.vaultAdmin.unmaskedValue(admin, "portal", "login", "username", null).get("value"));
        assertEquals("CR.FORBIDDEN", code(() -> cr.vaultAdmin.unmaskedValue(bob, "portal", "login", "username", null)));
        cr.setCredential(admin, "portal", "login", "token", "t0ken", null);
        assertEquals("CR.FORBIDDEN", code(() -> cr.vaultAdmin.unmaskedValue(admin, "portal", "login", "token", null)), "masked by default");

        // bob consumes the folder and has not filled in his password yet
        List<Map<String, Object>> req = cr.vaultAdmin.requests(bob);
        assertEquals(1, req.size());
        assertEquals("password", req.get(0).get("attribute"));
        assertEquals(true, req.get(0).get("password_only"));
        cr.vaultAdmin.setMyValue(bob, "portal", "login", "password", "bob-pw", null);
        assertTrue(cr.vaultAdmin.requests(bob).isEmpty());
        assertTrue(cr.vaultAdmin.passwordOnly("portal/login/password"));
        assertFalse(cr.vaultAdmin.passwordOnly("portal/login/username"));
    }

    @Test @Order(6)
    void severalRolesPerPerson() {
        assertEquals("CR.FORBIDDEN", code(() -> cr.platform.saveQueue(bob, null, Map.of("name", "Q"), null)));
        cr.updateUser(admin, bob.id(), Map.of("further_roles", List.of("bot_creator"), "first_name", "Bob", "last_name", "Stone", "description", "Accounts payable"), null);
        assertTrue(cr.can(bob, "queues.manage"), "the further role's permissions count");
        assertEquals(List.of("viewer", "bot_creator"), cr.rolesOf("bob", "viewer"));
        assertTrue(cr.teams.principalsOf("bob", "viewer").contains("role:bot_creator"));
        Map<String, Object> d = cr.userDetail(admin, "bob");
        assertEquals("Stone", d.get("last_name"));
        assertEquals(List.of("bot_creator"), d.get("further_roles"));
        assertEquals(1, ((Number) cr.usersPage(admin, Map.of("role", "bot_creator", "q", "stone")).get("total")).intValue());
        assertTrue(((List<?>) cr.me(bob).get("permissions")).contains("queues.manage"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.updateUser(admin, bob.id(), Map.of("further_roles", List.of("admin")), null)));
        cr.updateUser(admin, bob.id(), Map.of("further_roles", List.of()), null);
        assertFalse(cr.can(bob, "queues.manage"));
    }

    @Test @Order(7)
    @SuppressWarnings("unchecked")
    void sharedValuesPeopleChangeForThemselves() {
        cr.setGlobal(admin, "report_folder", "D:/Reports", true, null);
        cr.setGlobal(admin, "tax_rate", 0.2, null, null);
        assertEquals("CR.FORBIDDEN", code(() -> cr.setMyGlobal(bob, "tax_rate", 0.1, null)));
        cr.setMyGlobal(bob, "report_folder", "D:/Bob", null);
        Map<String, Object> g = cr.globals(bob).stream().filter(x -> x.get("name").equals("report_folder")).findFirst().orElseThrow();
        assertEquals("D:/Bob", g.get("my_value"));
        assertEquals("D:/Reports", g.get("value"));
        // bob's runs read his value; others read the shared one
        cr.checkin(jane, "Sales/Report.flow.json", agent("report", ""), "v1", true, null);
        cr.startRun("bob", "bot_runner", "Sales/Report.flow.json", device, Map.of(), "manual", null);
        assertEquals("D:/Bob", ((Map<String, Object>) claim().get("global_values")).get("report_folder"));
        finishAll();
        cr.startRun("jane", "bot_creator", "Sales/Report.flow.json", device, Map.of(), "manual", null);
        assertEquals("D:/Reports", ((Map<String, Object>) claim().get("global_values")).get("report_folder"));
        finishAll();
        // turning it off removes people's own values
        cr.setGlobal(admin, "report_folder", "D:/Reports", false, null);
        assertEquals(0, cr.count("SELECT COUNT(*) AS n FROM user_global_values"));
    }

    @Test @Order(8)
    void runnerSettings() {
        assertEquals("info", cr.heartbeat(db.one("SELECT * FROM devices WHERE id = ?", device), Map.of()).get("log_level"));
        cr.updateDevices(admin, List.of(device), Map.of("log_level", "debug"), null);
        assertEquals("debug", cr.heartbeat(db.one("SELECT * FROM devices WHERE id = ?", device), Map.of()).get("log_level"));
        assertEquals("CR.BAD_INPUT", code(() -> cr.updateDevices(admin, List.of(device), Map.of("log_level", "loud"), null)));
        assertEquals("CR.FORBIDDEN", code(() -> cr.updateDevices(jane, List.of(device), Map.of("log_level", "info"), null)));
        // runners nobody set are multi-user (as before); single-user: one person's default runner
        assertEquals("multi_user", cr.deviceDetail(admin, device).get("device_type"));
        cr.updateDevices(admin, List.of(device), Map.of("device_type", "single_user"), null);
        cr.setDefaultDevice(admin, jane.id(), device, null);
        assertEquals("CR.CONFLICT", code(() -> cr.setDefaultDevice(admin, bob.id(), device, null)));
        cr.updateDevices(admin, List.of(device), Map.of("device_type", "multi_user"), null);
        cr.setDefaultDevice(admin, bob.id(), device, null);
        assertEquals(List.of("bob", "jane"), cr.deviceDetail(admin, device).get("default_users"));
        assertEquals("multi_user", cr.deviceDetail(admin, device).get("device_type"));
        assertEquals("CR.CONFLICT", code(() -> cr.updateDevices(admin, List.of(device), Map.of("device_type", "single_user"), null)));
    }
}
