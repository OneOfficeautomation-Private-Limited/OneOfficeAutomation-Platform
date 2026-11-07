package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.insights.Analytics;
import com.oneofficeautomation.modules.insights.Dashboards;

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

    @Test @SuppressWarnings("unchecked") void aDashboardEmailedOnASchedule() throws Exception {
        String dash = (String) cr.modules.service(Dashboards.class).save(admin, null, Map.of("name", "Ops", "widgets", List.of(
                Map.of("title", "Runs", "type", "number", "source", "runs", "metric", "runs", "days", 1),
                Map.of("title", "Runs per agent", "type", "bars", "source", "runs", "metric", "runs", "group", "agent", "days", 1))), null).get("id");
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("recipients", ""), Map.of("recipients", "role:*"), Map.of("recipients", "not an address"),
                Map.of("recipients", "a@example.com", "every", "hourly"), Map.of("recipients", "a@example.com", "at_time", "8am"),
                Map.of("recipients", "a@example.com", "every", "week", "weekday", "FUN")))
            assertTrue(Set.of("CR.BAD_INPUT", "MAIL.BAD_ADDRESS").contains(code(() -> cr.modules.service(Dashboards.class).mailSave(admin, dash, bad, null))), String.valueOf(bad));
        ControlRoom.User viewer = RepositoryLifecycleTest.person(cr, admin, "vic", "viewer");
        assertEquals("CR.FORBIDDEN", code(() -> cr.modules.service(Dashboards.class).mailSave(viewer, dash, Map.of("recipients", "a@example.com"), null)));

        try (LicenseMailAndUsageTest.FakeSmtp smtp = new LicenseMailAndUsageTest.FakeSmtp()) {
            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "127.0.0.1", "mail_smtp_port", smtp.port(), "mail_smtp_security", "none", "mail_from", "ooa@example.com",
                "public_url", "https://ooa.example.com"), null);
            db.update("UPDATE users SET email = ? WHERE username = ?", "asha@example.com", "admin");
            String id = (String) cr.modules.service(Dashboards.class).mailSave(admin, dash, Map.of("recipients", "user:admin, cfo@example.com", "every", "day", "at_time", "08:00", "time_zone", "UTC"), null).get("id");
            assertEquals(1, cr.modules.service(Dashboards.class).mailList(admin, dash).size());
            // due now: sent once, then moved to its next time
            db.update("UPDATE dashboard_mail SET next_send = ? WHERE id = ?", Instant.now().minusSeconds(60), id);
            cr.modules.service(Dashboards.class).mailIfDue();
            assertEquals(1, smtp.messages.size());
            assertEquals(Set.of("asha@example.com", "cfo@example.com"), new HashSet<>(smtp.rcpts));
            assertEquals("Dashboard: Ops", LicenseMailAndUsageTest.subject(smtp.messages.get(0)));
            // two parts: the figures as text, and the same as a PDF to keep or forward
            String raw = smtp.messages.get(0);
            assertTrue(raw.contains("Content-Type: multipart/mixed"), raw.substring(0, Math.min(400, raw.length())));
            assertTrue(raw.contains("Content-Type: application/pdf; name=\"Ops.pdf\""));
            String[] parts = raw.split("\n\n");
            String body = new String(Base64.getMimeDecoder().decode(parts[2].substring(0, parts[2].indexOf("--")).trim()), java.nio.charset.StandardCharsets.UTF_8);
            String pdf = new String(Base64.getMimeDecoder().decode(parts[3].substring(0, parts[3].indexOf("--")).trim()), java.nio.charset.StandardCharsets.ISO_8859_1);
            assertTrue(pdf.startsWith("%PDF-1.4") && pdf.contains("(Runs: 5)") && pdf.trim().endsWith("%%EOF"), pdf.substring(0, Math.min(300, pdf.length())));
            assertTrue(body.contains("Runs: 5"), body);   // the figures its maker (an administrator) sees
            assertTrue(body.contains("  Ops/Report: 4"), body);
            assertTrue(body.contains("https://ooa.example.com/next/#/business?tab=dashboards&dashboard=" + dash), body);
            Map<String, Object> s = db.one("SELECT next_send, last_sent, last_error FROM dashboard_mail WHERE id = ?", id);
            assertTrue(Instant.parse(String.valueOf(s.get("next_send"))).isAfter(Instant.now()));
            assertNotNull(s.get("last_sent"));
            cr.modules.service(Dashboards.class).mailIfDue();
            assertEquals(1, smtp.messages.size(), "not due again");
            // send now; stop: only its maker or an administrator
            assertEquals(2, cr.modules.service(Dashboards.class).mailNow(admin, id, null).get("sent_to"));
            assertEquals("CR.FORBIDDEN", code(() -> cr.modules.service(Dashboards.class).mailDelete(viewer, id, null)));
            cr.modules.service(Dashboards.class).mailDelete(admin, id, null);
            assertTrue(cr.modules.service(Dashboards.class).mailList(admin, dash).isEmpty());
        } finally {
            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "", "mail_from", ""), null);
        }
    }

    @Test @SuppressWarnings("unchecked") void theDataApiInPages() {
        // runs: oldest first, two at a time, a cursor to the next page; same-time rows are not skipped
        Map<String, Object> p1 = cr.modules.service(Analytics.class).dataRuns(admin, Map.of("limit", "2"));
        List<Map<String, Object>> items = (List<Map<String, Object>>) p1.get("items");
        assertEquals(List.of("run_0", "run_1"), items.stream().map(i -> i.get("id")).toList());
        assertEquals("0", String.valueOf(((Map<String, Object>) items.get(0).get("outputs")).get("n")));
        assertEquals("schedule", items.get(0).get("source"));
        List<Object> all = new ArrayList<>();
        String next = null;
        do {
            Map<String, String> q = new HashMap<>(Map.of("limit", "2"));
            if (next != null) q.put("after", next);
            Map<String, Object> p = cr.modules.service(Analytics.class).dataRuns(admin, q);
            ((List<Map<String, Object>>) p.get("items")).forEach(i -> all.add(i.get("id")));
            next = (String) p.get("next");
        } while (next != null);
        assertEquals(List.of("run_0", "run_1", "run_2", "run_3", "run_4"), all);
        assertEquals("CR.BAD_INPUT", code(() -> cr.modules.service(Analytics.class).dataRuns(admin, Map.of("after", "rubbish"))));

        // audit: by sequence number; needs the Audit Trail's permission
        Map<String, Object> a = cr.modules.service(Analytics.class).dataAudit(admin, Map.of("limit", "3"));
        assertEquals(3, ((List<?>) a.get("items")).size());
        long last = ((Number) ((List<Map<String, Object>>) a.get("items")).get(2).get("seq")).longValue();
        assertEquals(String.valueOf(last), a.get("next"));
        ControlRoom.User viewer = RepositoryLifecycleTest.person(cr, admin, "val", "viewer");
        assertEquals("CR.FORBIDDEN", code(() -> cr.modules.service(Analytics.class).dataAudit(viewer, Map.of())));
        assertEquals(List.of(), ((Map<String, Object>) cr.modules.service(Analytics.class).dataKpis(admin, Map.of())).get("items"));
    }
}
