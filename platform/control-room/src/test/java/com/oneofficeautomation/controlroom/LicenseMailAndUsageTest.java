package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.PrivateKey;
import java.time.*;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Licence expiry e-mails (L3) through the Command Center's own SMTP sender, and the licence usage report (L2). */
class LicenseMailAndUsageTest {

    /** A tiny SMTP server: accepts every message and keeps the envelope and the text. */
    static final class FakeSmtp implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final List<String> messages = new CopyOnWriteArrayList<>();
        final List<String> rcpts = new CopyOnWriteArrayList<>();
        final Thread thread;

        FakeSmtp() throws IOException {
            thread = new Thread(() -> {
                while (!server.isClosed()) {
                    try (Socket s = server.accept()) { talk(s); } catch (IOException ignored) { }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        private void talk(Socket s) throws IOException {
            BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
            out.write("220 fake ESMTP\r\n"); out.flush();
            String l;
            while ((l = in.readLine()) != null) {
                String up = l.toUpperCase(Locale.ROOT);
                if (up.startsWith("EHLO")) out.write("250-fake\r\n250 8BITMIME\r\n");
                else if (up.startsWith("RCPT TO:")) { rcpts.add(l.substring(8).replaceAll("[<>]", "")); out.write("250 ok\r\n"); }
                else if (up.equals("DATA")) {
                    out.write("354 go\r\n"); out.flush();
                    StringBuilder m = new StringBuilder();
                    String d;
                    while ((d = in.readLine()) != null && !d.equals(".")) m.append(d).append('\n');
                    messages.add(m.toString());
                    out.write("250 queued\r\n");
                } else if (up.equals("QUIT")) { out.write("221 bye\r\n"); out.flush(); return; }
                else out.write("250 ok\r\n");
                out.flush();
            }
        }

        int port() { return server.getLocalPort(); }

        @Override public void close() throws IOException { server.close(); }
    }

    static String subject(String message) {
        for (String line : message.split("\n")) if (line.startsWith("Subject: ")) return line.substring(9);
        return "";
    }

    static String body(String message) {
        String b = message.substring(message.indexOf("\n\n") + 2).replace("\n", "");
        return new String(Base64.getDecoder().decode(b), StandardCharsets.UTF_8);
    }

    @Test void administratorsGetOneMailForEachStepBeforeTheLicenseEnds() throws Exception {
        Path data = Files.createTempDirectory("ooa-licmail");
        Db db = Db.embedded(data);
        try (FakeSmtp smtp = new FakeSmtp()) {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.licensing.vendorOverride = true;
            Map<String, String> kp = License.newKeyPair();
            PrivateKey priv = License.privateKey(kp.get("private"));
            cr.licensing.useKeyForTests(License.publicKey(kp.get("public")));
            cr.licensing.clock = Clock.fixed(Instant.parse("2027-11-01T08:00:00Z"), ZoneOffset.UTC);
            Map<String, Integer> seats = Map.of("developer", 2, "citizen_developer", 0, "attended_runner", 1, "unattended_runner", 1);
            cr.licensing.install(admin, new License("LIC-M", "Mail Customer", "Enterprise", LocalDate.of(2026, 1, 1), LocalDate.of(2027, 12, 31), 14, seats, "", "").sign(priv), null);

            // nothing is sent while e-mail is not set up
            cr.licensing.mailIfDue();
            assertTrue(smtp.messages.isEmpty());

            db.update("UPDATE users SET email = ? WHERE username = 'admin'", "admin@example.com");
            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "127.0.0.1", "mail_smtp_port", smtp.port(), "mail_smtp_security", "none",
                "mail_from", "ooa@example.com", "license_mail_to", "licences@example.com; admin@example.com"), null);

            // 60 days left: too early
            cr.licensing.mailIfDue();
            assertTrue(smtp.messages.isEmpty());

            // 30 days left: one mail to each administrator and the extra address (no address twice)
            cr.licensing.clock = Clock.fixed(Instant.parse("2027-12-01T08:00:00Z"), ZoneOffset.UTC);
            cr.licensing.mailIfDue();
            assertEquals(1, smtp.messages.size());
            assertEquals(List.of("admin@example.com", "licences@example.com"), smtp.rcpts);
            String m = smtp.messages.get(0);
            assertEquals("ONEOFFICEAUTOMATION license ends in 30 days", subject(m));
            assertTrue(body(m).contains("2027-12-31") && body(m).contains(cr.licensing.installId()), body(m));

            // the same step is not sent again, the next one (15) is sent once
            cr.licensing.mailIfDue();
            cr.licensing.clock = Clock.fixed(Instant.parse("2027-12-10T08:00:00Z"), ZoneOffset.UTC);
            cr.licensing.mailIfDue();
            assertEquals(1, smtp.messages.size(), "21 days left is still the 30-day step");
            cr.licensing.clock = Clock.fixed(Instant.parse("2027-12-16T08:00:00Z"), ZoneOffset.UTC);
            cr.licensing.mailIfDue();
            cr.licensing.mailIfDue();
            assertEquals(2, smtp.messages.size());
            assertEquals("ONEOFFICEAUTOMATION license ends in 15 days", subject(smtp.messages.get(1)));

            // on the last day, then once after it expired (grace)
            cr.licensing.clock = Clock.fixed(Instant.parse("2027-12-30T08:00:00Z"), ZoneOffset.UTC);
            cr.licensing.mailIfDue();
            assertEquals("ONEOFFICEAUTOMATION license ends in 1 day", subject(smtp.messages.get(2)));
            cr.licensing.clock = Clock.fixed(Instant.parse("2028-01-02T08:00:00Z"), ZoneOffset.UTC);
            cr.licensing.mailIfDue();
            cr.licensing.mailIfDue();
            assertEquals(4, smtp.messages.size());
            assertTrue(subject(smtp.messages.get(3)).contains("expired"), subject(smtp.messages.get(3)));
            assertTrue(body(smtp.messages.get(3)).contains("2028-01-14"), "the grace end is named");
            assertEquals(4, cr.count("SELECT COUNT(*) AS n FROM audit WHERE action = 'license.mail'"));

            // the test e-mail goes to the given address and is audited
            assertEquals(true, cr.mailer.test(admin, "check@example.com", null).get("sent"));
            assertTrue(smtp.rcpts.contains("check@example.com"));
        } finally { db.close(); }
    }

    @Test void mailSettingsAndAddressesAreChecked() throws Exception {
        Path data = Files.createTempDirectory("ooa-licmail2");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            for (Map<String, Object> bad : List.of(Map.<String, Object>of("mail_smtp_security", "tls13"), Map.<String, Object>of("mail_from", "not an address"),
                    Map.<String, Object>of("mail_smtp_password_ref", "plain-password"), Map.<String, Object>of("license_mail_to", "a@example.com, b@"),
                    Map.<String, Object>of("mail_smtp_port", 0), Map.<String, Object>of("mail_smtp_host", "smtp.example.com\r\nRCPT")))
                assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, bad, null), bad.toString());
            // header and command injection through addresses and subjects is refused or flattened
            assertThrows(OoaException.class, () -> Mailer.address("a@example.com>\r\nRCPT TO:<b@example.com"));
            assertEquals("a@example.com", Mailer.address("Someone <a@example.com>"));
            String msg = Mailer.message("a@example.com", List.of("b@example.com"), "Hello\r\nBcc: c@example.com", "line\r\n.\r\nQUIT");
            assertTrue(msg.contains("Subject: Hello Bcc: c@example.com\r\n"), msg);
            assertFalse(msg.contains("\r\n.\r\n"), "the body is Base64, so it can never end the DATA early");
            assertEquals("MAIL.NOT_SET_UP", assertThrows(OoaException.class, () -> cr.mailer.send(List.of("a@example.com"), "s", "b")).code());
        } finally { db.close(); }
    }

    @Test void theUsageReportShowsHoldersIdleSeatsAndTheTrend() throws Exception {
        Path data = Files.createTempDirectory("ooa-licuse");
        Db db = Db.embedded(data);
        try {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.licensing.vendorOverride = true;
            Map<String, String> kp = License.newKeyPair();
            cr.licensing.useKeyForTests(License.publicKey(kp.get("public")));
            cr.licensing.clock = Clock.fixed(Instant.now(), ZoneOffset.UTC);
            Map<String, Integer> seats = Map.of("developer", 3, "citizen_developer", 0, "attended_runner", 1, "unattended_runner", 2);
            cr.licensing.install(admin, new License("LIC-U", "Use Customer", "Enterprise", LocalDate.now().minusDays(10), LocalDate.now().plusDays(300), 14, seats, "", "")
                .sign(License.privateKey(kp.get("private"))), null);
            cr.createUser(admin, "dora", "Dora Dev", "bot_creator", null);
            db.update("UPDATE users SET last_login = ? WHERE username = 'admin'", Instant.now());
            cr.licensing.assign(admin, "developer", "admin", true, null);
            cr.licensing.assign(admin, "developer", "dora", true, null);

            Map<String, Object> u = cr.licensing.usage(admin, 30);
            @SuppressWarnings("unchecked") Map<String, Map<String, Object>> s = (Map<String, Map<String, Object>>) u.get("seats");
            assertEquals(3, s.get("developer").get("total"));
            assertEquals(2L, s.get("developer").get("used"));
            assertEquals(1L, s.get("developer").get("idle"), "dora never signed in or ran anything");
            @SuppressWarnings("unchecked") List<Map<String, Object>> holders = (List<Map<String, Object>>) u.get("holders");
            assertEquals(Set.of("admin", "dora"), new HashSet<>(holders.stream().map(h -> h.get("principal")).toList()));
            assertEquals("Dora Dev", holders.stream().filter(h -> "dora".equals(h.get("principal"))).findFirst().orElseThrow().get("name"));
            @SuppressWarnings("unchecked") List<Map<String, Object>> trend = (List<Map<String, Object>>) u.get("trend");
            assertEquals(4, trend.size(), "today's figure for each licence type");
            cr.licensing.usage(admin, 30);
            assertEquals(4, cr.count("SELECT COUNT(*) AS n FROM license_usage"), "one snapshot a day");

            String csv = cr.licensing.usageCsv(admin);
            assertTrue(csv.startsWith("license,holder_type,holder,name"), csv);
            assertTrue(csv.contains("Developer,user,dora,Dora Dev,"), csv);
            assertEquals("'=1+1", Licensing.csv("=1+1"), "no spreadsheet formulas");

            // people without users.manage or settings.manage may not see it
            ControlRoom.User dora = ControlRoom.user(db.one("SELECT * FROM users WHERE username = 'dora'"));
            assertThrows(OoaException.class, () -> cr.licensing.usage(dora, 30));
        } finally { db.close(); }
    }

    @Test @SuppressWarnings("unchecked")
    void workflowNotificationsEmailAddressesThroughTheMailServer() throws Exception {
        Path data = Files.createTempDirectory("ooa-wfmail");
        Db db = Db.embedded(data);
        try (FakeSmtp smtp = new FakeSmtp()) {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.checkin(admin, "Proc/Tell.process.json", (Map<String, Object>) com.oneofficeautomation.sdk.JsonLite.parse("{\"name\":\"Tell\",\"stages\":["
                + "{\"id\":\"n\",\"type\":\"notify\",\"name\":\"Tell\",\"to\":\"user:admin, buyer@example.com\",\"subject\":\"Order ${order} approved\",\"message\":\"Order ${order} is approved\"}]}"), "v1", true, null);

            // without e-mail set up, the inbox gets it and the address is reported as not delivered
            Map<String, Object> r1 = cr.workflows.start(admin, "Proc/Tell.process.json", Map.of("order", "PO-7"), null);
            String h1 = String.valueOf(cr.workflows.run(admin, (String) r1.get("id"), null).get("history"));
            assertTrue(h1.contains("not_delivered") && h1.contains("not set up"), h1);
            assertTrue(smtp.messages.isEmpty());

            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "127.0.0.1", "mail_smtp_port", smtp.port(), "mail_smtp_security", "none", "mail_from", "ooa@example.com"), null);
            Map<String, Object> r2 = cr.workflows.start(admin, "Proc/Tell.process.json", Map.of("order", "PO-8"), null);
            String h2 = String.valueOf(cr.workflows.run(admin, (String) r2.get("id"), null).get("history"));
            assertTrue(h2.contains("emailed"), h2);
            assertEquals(1, smtp.messages.size());
            assertEquals(List.of("buyer@example.com"), smtp.rcpts);
            assertEquals("Order PO-8 approved", subject(smtp.messages.get(0)));
            assertEquals("Order PO-8 is approved", body(smtp.messages.get(0)));
        } finally { db.close(); }
    }
}
