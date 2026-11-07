package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.orchestration.Workflows;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import static com.oneofficeautomation.controlroom.LicenseMailAndUsageTest.body;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G43: approving outside the Task inbox. Each assignee's e-mail carries their own signed Approve and Reject links; a
 * link opens a page that asks to confirm and then decides the task as that person; the Teams channel gets a card and
 * the phone a push with the same buttons. Everything is off until turned on.
 */
class ApprovalChannelsTest {
    record Hit(String path, Map<String, String> headers, String body) { }

    static HttpServer fake(List<Hit> hits) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            Map<String, String> h = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            hits.add(new Hit(ex.getRequestURI().getPath(), h, new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8)));
            ex.sendResponseHeaders(200, -1);
            ex.close();
        });
        s.start();
        return s;
    }

    static void waitFor(java.util.function.BooleanSupplier ok) throws InterruptedException {
        for (int i = 0; i < 100 && !ok.getAsBoolean(); i++) Thread.sleep(50);
    }

    @SuppressWarnings("unchecked")
    @Test void approveFromEmailTeamsAndPhone() throws Exception {
        Path data = Files.createTempDirectory("ooa-g43");
        Db db = Db.embedded(data);
        List<Hit> hits = new CopyOnWriteArrayList<>();
        HttpServer web = fake(hits);
        String base = "http://127.0.0.1:" + web.getAddress().getPort();
        try (LicenseMailAndUsageTest.FakeSmtp smtp = new LicenseMailAndUsageTest.FakeSmtp()) {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            ControlRoom.User jane = RepositoryLifecycleTest.person(cr, admin, "jane", "bot_creator");
            db.update("UPDATE users SET email = ? WHERE username = ?", "jane@example.com", "jane");
            cr.checkin(admin, "Proc/Buy.process.json", (Map<String, Object>) JsonLite.parse("{\"name\":\"Buy\",\"stages\":["
                    + "{\"id\":\"a\",\"type\":\"approval\",\"name\":\"Approve order\",\"assignee\":\"user:jane\"}]}"), "v1", true, null);

            // off: no links in the e-mail, no push, no card
            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "127.0.0.1", "mail_smtp_port", smtp.port(), "mail_smtp_security", "none",
                    "mail_from", "ooa@example.com", "notify_task_assigned", 1), null);
            cr.modules.service(Workflows.class).start(admin, "Proc/Buy.process.json", Map.of("order", "PO-1", "amount", 1200), null);
            waitFor(() -> smtp.messages.size() == 1);
            assertFalse(body(smtp.messages.get(0)).contains("/api/tasks/act"), "links are off by default");
            assertTrue(body(smtp.messages.get(0)).contains("order: PO-1"), body(smtp.messages.get(0)));

            // settings are checked; links need the public address
            assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("teams_approval_webhook_ref", "not-a-reference"), null));
            assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("push_server", "ftp://x"), null));
            assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("task_link_hours", 0), null));
            cr.createLocker(admin, "ops", "Operations", null, null);
            cr.setCredential(admin, "ops", "teams", "url", base + "/teams-hook", null);
            cr.platform.updateSettings(admin, Map.of("task_action_links", 1, "public_url", "https://ooa.example.com", "teams_approval_webhook_ref", "ops/teams/url",
                    "push_server", base), null);
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.taskChannels.teamsTest(admin)).code(),
                    "only an https:// webhook is used");

            // jane turns phone notifications on: a private topic; the test push reaches the server
            assertEquals(false, cr.taskChannels.pushStatus(jane).get("enabled"));
            Map<String, Object> push = cr.taskChannels.pushEnable(jane, null);
            String topic = (String) push.get("topic");
            assertTrue(topic.startsWith("ooa-") && topic.length() > 20, topic);
            cr.taskChannels.pushTest(jane);
            assertTrue(hits.stream().anyMatch(h -> h.path().equals("/" + topic)), String.valueOf(hits));
            assertFalse(JsonLite.write(db.query("SELECT json_value FROM settings WHERE name = 'push_topic:jane'")).contains(topic), "the topic is kept encrypted");
            hits.clear();

            // a new approval: e-mail with jane's own links; a push with Approve and Reject; nothing to Teams over http
            cr.modules.service(Workflows.class).start(admin, "Proc/Buy.process.json", Map.of("order", "PO-2", "amount", 900), null);
            waitFor(() -> smtp.messages.size() == 2 && hits.stream().anyMatch(h -> h.path().equals("/" + topic)));
            String mail = body(smtp.messages.get(1));
            Matcher yes = Pattern.compile("Approve: https://ooa\\.example\\.com/api/tasks/act\\?t=(\\S+)").matcher(mail);
            Matcher no = Pattern.compile("Reject: https://ooa\\.example\\.com/api/tasks/act\\?t=(\\S+)").matcher(mail);
            assertTrue(yes.find() && no.find(), mail);
            Hit p = hits.stream().filter(h -> h.path().equals("/" + topic)).findFirst().orElseThrow();
            assertTrue(p.headers().get("actions").contains("view, Approve, https://ooa.example.com/api/tasks/act?t="), p.headers().toString());
            assertEquals("A task for you", p.headers().get("title"));

            // the link page asks first; a changed token or another decision is refused
            String task = (String) db.one("SELECT id FROM human_tasks WHERE status = 'open' AND data LIKE '%PO-2%'").get("id");
            String page = cr.taskChannels.page(yes.group(1));
            assertTrue(page.contains("Nothing is decided until you press the button") && page.contains("PO-2") && page.contains("<button"), page);
            assertEquals("open", db.one("SELECT status FROM human_tasks WHERE id = ?", task).get("status"), "opening the link decides nothing");
            String forged = yes.group(1).substring(0, yes.group(1).length() - 3) + "AAA";
            assertTrue(cr.taskChannels.act(forged, null, null).contains("not valid"));
            String expired = cr.taskChannels.token(task, "jane", "approved", Instant.now().minus(Duration.ofMinutes(1)));
            assertTrue(cr.taskChannels.act(expired, null, null).contains("expired"));
            assertTrue(cr.taskChannels.act(cr.taskChannels.token(task, "admin2", "approved", Instant.now().plusSeconds(60)), null, null).contains("not active"));

            // confirming approves as jane, with the comment; the same link again changes nothing
            assertTrue(cr.taskChannels.act(yes.group(1), "OK by phone", "10.0.0.5").contains("you approved"));
            Map<String, Object> done = db.one("SELECT status, outcome, completed_by, result FROM human_tasks WHERE id = ?", task);
            assertEquals("completed", done.get("status"));
            assertEquals("approved", done.get("outcome"));
            assertEquals("jane", done.get("completed_by"));
            assertTrue(String.valueOf(done.get("result")).contains("OK by phone"));
            assertTrue(cr.taskChannels.page(no.group(1)).contains("already approved"));
            assertTrue(cr.taskChannels.act(no.group(1), null, null).contains("already"));
            assertEquals(1L, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'task.link_used'"));

            // Teams: a card with jane's buttons (single assignee), through an https webhook only
            Map<String, Object> card = ApprovalChannels.card("Approve order", true, null, Map.of("order", "PO-3", "_task", "x"), "https://a", "https://r", "jane", "https://ooa.example.com/next/#/tasks");
            String cj = JsonLite.write(card);
            assertTrue(cj.contains("application/vnd.microsoft.card.adaptive") && cj.contains("\"Approve\"") && cj.contains("PO-3") && !cj.contains("_task"), cj);
            Map<String, Object> roleCard = ApprovalChannels.card("Approve order", true, null, Map.of(), null, null, null, "https://ooa.example.com/next/#/tasks");
            assertFalse(JsonLite.write(roleCard).contains("\"Approve\""), "a task for a group gets only Open the task");

            // jane turns phone notifications off
            cr.taskChannels.pushDisable(jane, null);
            assertEquals(false, cr.taskChannels.pushStatus(jane).get("enabled"));
            assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.taskChannels.pushTest(jane)).code());
        } finally { web.stop(0); db.close(); }
    }
}
