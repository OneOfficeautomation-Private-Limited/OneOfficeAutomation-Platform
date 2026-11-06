package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Operational Insights step 3 (1 Nov 2026): dashboards e-mailed on a schedule, and the analytics data API (pages of
 * runs, KPI values and audit entries for Power BI, Tableau or a data warehouse).
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class InsightsExportTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (OoaException e) { return e.code(); } }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-insights-export");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        Instant t = Instant.now().minus(Duration.ofHours(2));
        for (int i = 0; i < 5; i++)
            db.update("INSERT INTO runs (id, bot_path, version, device_id, status, requested_by, source, created, outputs, cancel_requested, pause_requested) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                "run_" + i, i == 4 ? "Secret/Hidden.flow.json" : "Ops/Report.flow.json", 1, "dev_x", "success", "admin", "schedule:s1", t.plusSeconds(i == 3 ? 2 : i), "{\"n\":" + i + "}", false, false);
    }

    @AfterAll void stop() { db.close(); }

    @Test void nextSendTimes() {
        Instant monNoon = ZonedDateTime.of(2026, 11, 2, 12, 0, 0, 0, ZoneId.of("UTC")).toInstant();   // a Monday
        assertEquals("2026-11-03T08:00:00Z", Dashboards.nextSend(Map.of("every", "day", "at_time", "08:00", "time_zone", "UTC"), monNoon).toString());
        assertEquals("2026-11-02T13:00:00Z", Dashboards.nextSend(Map.of("every", "day", "at_time", "13:00", "time_zone", "UTC"), monNoon).toString());
        assertEquals("2026-11-09T08:00:00Z", Dashboards.nextSend(Map.of("every", "week", "weekday", "MON", "at_time", "08:00", "time_zone", "UTC"), monNoon).toString());
        assertEquals("2026-11-04T02:30:00Z", Dashboards.nextSend(Map.of("every", "week", "weekday", "WED", "at_time", "08:00", "time_zone", "Asia/Kolkata"), monNoon).toString());
        assertEquals("2026-12-01T08:00:00Z", Dashboards.nextSend(Map.of("every", "month", "at_time", "08:00", "time_zone", "UTC"), monNoon).toString());
    }

    /** The decoded part of a message with this content type (text/plain, text/html, application/pdf). */
    static String part(String raw, String type, java.nio.charset.Charset cs) {
        int i = raw.indexOf("Content-Type: " + type);
        assertTrue(i >= 0, "no " + type + " part");
        int from = raw.indexOf("\n\n", i) + 2, to = raw.indexOf("\n--", from);
        return new String(Base64.getMimeDecoder().decode(raw.substring(from, to).trim()), cs);
    }

    void mailSetUp(LicenseMailAndUsageTest.FakeSmtp smtp, boolean outside) {
        cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "127.0.0.1", "mail_smtp_port", smtp.port(), "mail_smtp_security", "none", "mail_from", "ooa@example.com",
            "public_url", "https://ooa.example.com", "dashboard_mail_outside", outside ? 1 : 0), null);
    }

    @Test @SuppressWarnings("unchecked") void aDashboardEmailedOnASchedule() throws Exception {
        String dash = (String) cr.dashboards.save(admin, null, Map.of("name", "Ops", "widgets", List.of(
                Map.of("title", "Runs", "type", "number", "source", "runs", "metric", "runs", "days", 1),
                Map.of("title", "Runs per agent", "type", "bars", "source", "runs", "metric", "runs", "group", "agent", "days", 1))), null).get("id");
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("recipients", ""), Map.of("recipients", "role:*"), Map.of("recipients", "not an address"),
                Map.of("recipients", "user:admin", "every", "hourly"), Map.of("recipients", "user:admin", "at_time", "8am"),
                Map.of("recipients", "user:admin", "every", "week", "weekday", "FUN")))
            assertTrue(Set.of("CR.BAD_INPUT", "MAIL.BAD_ADDRESS").contains(code(() -> cr.dashboards.mailSave(admin, dash, bad, null))), String.valueOf(bad));
        ControlRoom.User viewer = RepositoryLifecycleTest.person(cr, admin, "vic", "viewer");
        assertEquals("CR.FORBIDDEN", code(() -> cr.dashboards.mailSave(viewer, dash, Map.of("recipients", "user:admin"), null)));

        try (LicenseMailAndUsageTest.FakeSmtp smtp = new LicenseMailAndUsageTest.FakeSmtp()) {
            mailSetUp(smtp, true);
            db.update("UPDATE users SET email = ? WHERE username = ?", "asha@example.com", "admin");
            String id = (String) cr.dashboards.mailSave(admin, dash, Map.of("recipients", "user:admin, cfo@example.com", "every", "day", "at_time", "08:00", "time_zone", "UTC"), null).get("id");
            assertEquals(1, cr.dashboards.mailList(admin, dash).size());
            // due now: sent once (both see the same figures, so one message), then moved to its next time
            db.update("UPDATE dashboard_mail SET next_send = ? WHERE id = ?", Instant.now().minusSeconds(60), id);
            cr.dashboards.mailIfDue();
            assertEquals(1, smtp.messages.size());
            assertEquals(Set.of("asha@example.com", "cfo@example.com"), new HashSet<>(smtp.rcpts));
            assertEquals("Dashboard: Ops", LicenseMailAndUsageTest.subject(smtp.messages.get(0)));
            // the figures as text and as HTML (mail programs show one), and the same as a PDF to keep or forward
            String raw = smtp.messages.get(0);
            assertTrue(raw.contains("Content-Type: multipart/mixed") && raw.contains("Content-Type: multipart/alternative"), raw.substring(0, Math.min(400, raw.length())));
            assertTrue(raw.contains("Content-Type: application/pdf; name=\"Ops.pdf\""));
            String body = part(raw, "text/plain", java.nio.charset.StandardCharsets.UTF_8);
            String html = part(raw, "text/html", java.nio.charset.StandardCharsets.UTF_8);
            String pdf = part(raw, "application/pdf", java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(pdf.startsWith("%PDF-1.4") && pdf.contains("(Runs: 5)") && pdf.trim().endsWith("%%EOF"), pdf.substring(0, Math.min(300, pdf.length())));
            assertTrue(body.contains("Runs: 5"), body);   // the figures its maker (an administrator) sees
            assertTrue(body.contains("  Ops/Report: 4"), body);
            assertTrue(body.contains("https://ooa.example.com/next/#/business?tab=dashboards&dashboard=" + dash), body);
            assertTrue(html.contains("<h1 style=\"font-size:18px;margin:0 0 2px\">Ops</h1>"), html);
            assertTrue(html.contains(">5</p>"), html);   // the number widget
            assertTrue(html.contains("<table") && html.contains(">Agent</th>") && html.contains(">Runs</th>"), html);   // the bars widget as a table
            assertTrue(html.matches("(?s).*>Ops/Report</td><td[^>]*>4</td>.*"), html);
            assertTrue(html.contains("href=\"https://ooa.example.com/next/#/business?tab=dashboards&amp;dashboard=" + dash + "\""), html);
            Map<String, Object> s = db.one("SELECT next_send, last_sent, last_error FROM dashboard_mail WHERE id = ?", id);
            assertTrue(Instant.parse(String.valueOf(s.get("next_send"))).isAfter(Instant.now()));
            assertNotNull(s.get("last_sent"));
            cr.dashboards.mailIfDue();
            assertEquals(1, smtp.messages.size(), "not due again");
            // send now; stop: only the owner or an administrator
            assertEquals(2, cr.dashboards.mailNow(admin, id, null).get("sent_to"));
            assertEquals("CR.FORBIDDEN", code(() -> cr.dashboards.mailNow(viewer, id, null)));
            assertEquals("CR.FORBIDDEN", code(() -> cr.dashboards.mailDelete(viewer, id, null)));
            cr.dashboards.mailDelete(admin, id, null);
            assertTrue(cr.dashboards.mailList(admin, dash).isEmpty());
            assertTrue(db.query("SELECT seq FROM audit WHERE action = 'dashboard.mailed' AND object_id = ?", dash).size() >= 2);
        } finally {
            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "", "mail_from", "", "dashboard_mail_outside", 0), null);
        }
    }

    /** Recipients are people the dashboard is shared with, checked when it is saved and again when it is sent. */
    @Test void recipientsAreTheDashboardsViewers() throws Exception {
        String dash = (String) cr.dashboards.save(admin, null, Map.of("name", "Finance", "shared_with", List.of("role:bot_runner"), "widgets", List.of(
                Map.of("title", "Runs", "type", "number", "source", "runs", "metric", "runs", "days", 1))), null).get("id");
        ControlRoom.User runner = RepositoryLifecycleTest.person(cr, admin, "rita", "bot_runner");
        RepositoryLifecycleTest.person(cr, admin, "otto", "viewer");
        db.update("UPDATE users SET email = ? WHERE username = ?", "rita@example.com", "rita");
        db.update("UPDATE users SET email = ? WHERE username = ?", "otto@example.com", "otto");
        db.update("UPDATE users SET email = ? WHERE username = ?", "asha@example.com", "admin");
        try (LicenseMailAndUsageTest.FakeSmtp smtp = new LicenseMailAndUsageTest.FakeSmtp()) {
            mailSetUp(smtp, false);
            // a person it is shared with may see it, but only its owner (or an administrator) sets up its e-mails
            assertEquals("CR.FORBIDDEN", code(() -> cr.dashboards.mailSave(runner, dash, Map.of("recipients", "user:rita"), null)));
            // not shared with otto, nor with the viewer role; outside addresses are off; a person's own address counts as them
            for (String bad : List.of("user:otto", "role:viewer", "team:nobody", "cfo@example.com", "otto@example.com"))
                assertEquals("CR.BAD_INPUT", code(() -> cr.dashboards.mailSave(admin, dash, Map.of("recipients", bad), null)), bad);
            for (String ok : List.of("user:rita", "role:bot_runner", "rita@example.com", "user:admin"))
                assertEquals("ok", code(() -> cr.dashboards.mailSave(admin, dash, Map.of("recipients", ok), null)), ok);
            cr.platform.updateSettings(admin, Map.of("dashboard_mail_outside", 1), null);
            String id = (String) cr.dashboards.mailSave(admin, dash, Map.of("recipients", "role:bot_runner, user:admin, cfo@example.com", "every", "day"), null).get("id");
            Map<String, Object> r = cr.dashboards.mailNow(admin, id, null);
            assertEquals(3, r.get("sent_to"));
            assertEquals(0, r.get("left_out"));
            // sent again after the dashboard stops being shared and outside addresses are switched off: only its owner gets it
            cr.dashboards.save(admin, dash, Map.of("name", "Finance", "shared_with", List.of(), "widgets", List.of(
                Map.of("title", "Runs", "type", "number", "source", "runs", "metric", "runs", "days", 1))), null);
            cr.platform.updateSettings(admin, Map.of("dashboard_mail_outside", 0), null);
            smtp.rcpts.clear();
            r = cr.dashboards.mailNow(admin, id, null);
            assertEquals(1, r.get("sent_to"));
            assertEquals(2, r.get("left_out"));
            assertEquals(List.of("asha@example.com"), smtp.rcpts);
            // nobody left who may see it: the e-mail fails, and the scheduled run records why
            String only = (String) db.one("SELECT id FROM dashboard_mail WHERE dashboard_id = ? AND recipients = 'user:rita'", dash).get("id");
            assertEquals("MAIL.NO_RECIPIENT", code(() -> cr.dashboards.mailNow(admin, only, null)));
            db.update("UPDATE dashboard_mail SET next_send = ? WHERE dashboard_id = ?", Instant.now().plusSeconds(3600), dash);
            db.update("UPDATE dashboard_mail SET next_send = ? WHERE id = ?", Instant.now().minusSeconds(60), only);
            cr.dashboards.mailIfDue();
            assertTrue(String.valueOf(db.one("SELECT last_error FROM dashboard_mail WHERE id = ?", only).get("last_error")).contains("None of the recipients"));
        } finally {
            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "", "mail_from", "", "dashboard_mail_outside", 0), null);
        }
    }

    @Test @SuppressWarnings("unchecked") void theDataApiInPages() {
        // runs: oldest first, two at a time, a cursor to the next page; same-time rows are not skipped
        Map<String, Object> p1 = cr.analytics.dataRuns(admin, Map.of("limit", "2"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) p1.get("items");
        assertEquals(List.of("run_0", "run_1"), items.stream().map(i -> i.get("id")).toList());
        assertEquals("0", String.valueOf(((Map<String, Object>) items.get(0).get("outputs")).get("n")));
        assertEquals("schedule", items.get(0).get("source"));
        List<Object> all = new ArrayList<>();
        String next = null;
        do {
            Map<String, String> q = new HashMap<>(Map.of("limit", "2"));
            if (next != null) q.put("after", next);
            Map<String, Object> p = cr.analytics.dataRuns(admin, q);
            ((List<Map<String, Object>>) p.get("items")).forEach(i -> all.add(i.get("id")));
            next = (String) p.get("next");
        } while (next != null);
        assertEquals(List.of("run_0", "run_1", "run_2", "run_3", "run_4"), all);
        assertEquals("CR.BAD_INPUT", code(() -> cr.analytics.dataRuns(admin, Map.of("after", "rubbish"))));

        // audit: by sequence number; needs the Audit Trail's permission
        Map<String, Object> a = cr.analytics.dataAudit(admin, Map.of("limit", "3"));
        assertEquals(3, ((List<?>) a.get("items")).size());
        long last = ((Number) ((List<Map<String, Object>>) a.get("items")).get(2).get("seq")).longValue();
        assertEquals(String.valueOf(last), a.get("next"));
        ControlRoom.User viewer = RepositoryLifecycleTest.person(cr, admin, "val", "viewer");
        assertEquals("CR.FORBIDDEN", code(() -> cr.analytics.dataAudit(viewer, Map.of())));
        assertEquals(List.of(), ((Map<String, Object>) cr.analytics.dataKpis(admin, Map.of())).get("items"));
    }
}
