package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.mail.MailAccounts;
import com.oneofficeautomation.controlroom.mail.MailProviders;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.*;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Mail accounts (MA-1): the vendor catalogue and detection, making accounts, who may use them, and reaching the mailbox. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MailAccountsTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();
    Db db; ControlRoom cr; ControlRoomServer server; String base, token;
    FakeImap imap;

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-mail-accounts");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        String t0 = (String) cr.login("admin", pw, null).get("token");
        cr.changePassword(cr.session(t0), pw, "Admin-Passw0rd-2026", t0, null);
        token = (String) cr.login("admin", "Admin-Passw0rd-2026", null).get("token");
        ControlRoom.User admin = cr.session(token);
        cr.createLocker(admin, "mail", "Mailboxes", "user:admin", null);
        cr.setCredential(admin, "mail", "scans", "password", "right-pw", null);
        cr.setCredential(admin, "mail", "old", "password", "wrong-pw", null);
        cr.createLocker(admin, "other", "Someone else's", "role:viewer", null);
        cr.setCredential(admin, "other", "box", "password", "x", null);
        cr.mailAccounts.providers = new MailProviders(standardList(), domain -> switch (domain) {
            case "acme-trading.com" -> List.of("aspmx.l.google.com.", "alt1.aspmx.l.google.com.");
            case "contoso.com" -> List.of("contoso-com.mail.protection.outlook.com.");
            case "kerala-traders.in" -> List.of("mx.zoho.in.");
            case "unknown.example" -> List.of("mail.unknown.example.");
            default -> List.of();
        });
        imap = new FakeImap("right-pw");
        server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        base = "http://127.0.0.1:" + server.port();
    }

    @AfterAll void stop() throws IOException { server.stop(); imap.close(); db.close(); }

    static List<Map<String, Object>> standardList() { return MailProviders.standard().list(); }

    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, String path, Object body, String as) throws Exception {
        HttpResponse<String> r = HTTP.send(HttpRequest.newBuilder(URI.create(base + path)).header("Authorization", "Bearer " + as).header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body))).build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> m = r.body().isBlank() ? new LinkedHashMap<>() : new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(r.body()));
        m.put("_status", r.statusCode());
        m.put("_body", r.body());
        return m;
    }

    String sessionFor(String name, String role) {
        ControlRoom.User admin = cr.session(token);
        String temp = String.valueOf(cr.createUser(admin, name, name, role, null).get("temporary_password"));
        String t0 = (String) cr.login(name, temp, null).get("token");
        cr.changePassword(cr.session(t0), temp, "Mail-Passw0rd-2026", t0, null);
        return (String) cr.login(name, "Mail-Passw0rd-2026", null).get("token");
    }

    @Test void theCatalogueIsWellFormed() {
        Set<String> ids = new HashSet<>();
        for (Map<String, Object> p : standardList()) {
            assertTrue(ids.add(String.valueOf(p.get("id"))), "ids are unique: " + p.get("id"));
            assertTrue(Set.of("graph", "imap").contains(p.get("kind")), String.valueOf(p));
            List<?> signIn = (List<?>) p.get("signIn");
            assertFalse(signIn.isEmpty(), String.valueOf(p.get("id")));
            assertTrue(MailAccounts.SIGN_INS.containsAll(signIn), String.valueOf(p.get("id")));
            if (p.get("kind").equals("imap")) assertNotNull(p.get("imap"), p.get("id") + " needs its IMAP server");
            assertFalse(String.valueOf(p.get("help")).isBlank(), p.get("id") + " says how to set it up");
        }
        assertTrue(ids.containsAll(Set.of("microsoft365", "outlook_com", "gmail", "zoho", "yahoo", "aol", "icloud", "gmx", "fastmail", "rediffmail", "exchange", "imap")));
    }

    @Test void theVendorIsFoundFromTheAddress() {
        MailProviders p = cr.mailAccounts.providers;
        assertEquals(Map.of("domain", "gmail.com", "provider", "gmail", "how", "domain"), p.detect("AP.Team@Gmail.com"));
        assertEquals("net", p.detect("buchhaltung@gmx.de").get("region"));
        assertEquals("outlook_com", p.detect("someone@hotmail.com").get("provider"));
        Map<String, Object> workspace = p.detect("invoices@acme-trading.com");
        assertEquals("gmail", workspace.get("provider"));
        assertEquals("mx", workspace.get("how"));
        assertEquals("aspmx.l.google.com", workspace.get("mx"));
        assertEquals("microsoft365", p.detect("ap@contoso.com").get("provider"));
        Map<String, Object> zoho = p.detect("accounts@kerala-traders.in");
        assertEquals("zoho", zoho.get("provider"));
        assertEquals("in", zoho.get("region"));
        assertEquals("imap.zoho.in", p.imapHost(p.get("zoho"), "in"));
        Map<String, Object> unknown = p.detect("ap@unknown.example");
        assertNull(unknown.get("provider"));
        assertEquals("mail.unknown.example", unknown.get("mx"));
        assertEquals("none", p.detect("not an address").get("how"));
    }

    @Test @SuppressWarnings("unchecked")
    void anImapAccountIsMadeOnceTestedAndLentToOthers() throws Exception {
        Map<String, Object> made = call("POST", "/api/mail-accounts", Map.of("name", "Scanner mailbox", "provider", "imap", "address", "scans@acme.local",
                "host", "127.0.0.1", "port", imap.port(), "ssl", false, "secret_ref", "mail/scans/password"), token);
        assertEquals(200, made.get("_status"), String.valueOf(made.get("_body")));
        String id = (String) made.get("id");
        assertEquals("new", made.get("status"));
        assertEquals("admin", made.get("owner"));
        assertEquals("scans@acme.local", ((Map<String, Object>) made.get("settings")).get("user"), "the user name is the address unless given");

        Map<String, Object> tested = call("POST", "/api/mail-accounts/" + id + "/test", null, token);
        assertEquals(true, tested.get("ok"), String.valueOf(tested));
        Map<String, Object> listed = call("GET", "/api/mail-accounts", null, token);
        assertFalse(String.valueOf(listed.get("_body")).contains("right-pw"), "the password never leaves the vault");
        Map<String, Object> row = ((List<Map<String, Object>>) listed.get("accounts")).stream().filter(x -> x.get("id").equals(id)).findFirst().orElseThrow();
        assertEquals("working", row.get("status"));
        assertEquals("Other mail server", row.get("provider_name"));
        assertTrue(((List<?>) listed.get("providers")).size() >= 12);
        assertTrue(imap.seen.contains("A1 LOGIN \"scans@acme.local\" \"right-pw\""), String.valueOf(imap.seen));
        assertTrue(JsonLite.write(call("GET", "/api/audit?q=mail-account", null, token)).contains("credential.read"), "each use of the secret is audited");

        // a wrong password says so
        assertEquals(200, call("PUT", "/api/mail-accounts/" + id, Map.of("secret_ref", "mail/old/password"), token).get("_status"));
        Map<String, Object> wrong = call("POST", "/api/mail-accounts/" + id + "/test", null, token);
        assertEquals("wrong_password", wrong.get("status"), String.valueOf(wrong));

        // lent to others: only the owner, administrators and the people, teams or roles in allowed may use it
        String bea = sessionFor("bea", "bot_creator");
        ControlRoom.User beaUser = cr.session(bea);
        assertFalse(cr.mailAccounts.canUse(beaUser, cr.mailAccounts.find(id)));
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.mailAccounts.requireUse(beaUser, "Scanner mailbox")).code());
        assertEquals(403, call("PUT", "/api/mail-accounts/" + id, Map.of("name", "Mine now"), bea).get("_status"), "only the owner or an administrator changes it");
        assertEquals(200, call("PUT", "/api/mail-accounts/" + id, Map.of("allowed", List.of("role:bot_creator")), token).get("_status"));
        assertTrue(cr.mailAccounts.canUse(beaUser, cr.mailAccounts.find(id)));
        assertEquals(id, cr.mailAccounts.requireUse(beaUser, "Scanner mailbox").get("id"));
        assertEquals(200, call("GET", "/api/mail-accounts", null, bea).get("_status"), "people who see Manage see the list");
        assertEquals(403, call("POST", "/api/mail-accounts", Map.of("name", "Bea's", "provider", "gmail", "address", "b@gmail.com", "secret_ref", "mail/scans/password"), bea).get("_status"),
                "making one needs globals.manage");

        // the person saving must be allowed to use the vault value they set
        Map<String, Object> denied = call("POST", "/api/mail-accounts", Map.of("name", "Not mine", "provider", "gmail", "address", "x@gmail.com", "secret_ref", "other/box/password"), token);
        assertEquals(403, denied.get("_status"), String.valueOf(denied.get("_body")));
        assertTrue(String.valueOf(denied.get("_body")).contains("may not use vault folder 'other'"));
        Map<String, Object> clash = call("POST", "/api/mail-accounts", Map.of("name", "Scanner mailbox", "provider", "gmail", "address", "x@gmail.com", "secret_ref", "mail/scans/password"), token);
        assertEquals(400, clash.get("_status"));
        Map<String, Object> gmail = call("POST", "/api/mail-accounts", Map.of("name", "Supplier Gmail", "provider", "gmail", "address", "suppliers@gmail.com", "secret_ref", "mail/scans/password"), token);
        Map<String, Object> s = (Map<String, Object>) gmail.get("settings");
        assertEquals(List.of("imap.gmail.com", 993, true, "app_password"), List.of(s.get("host"), ((Number) s.get("port")).intValue(), s.get("ssl"), s.get("sign_in")), "from the catalogue");
        assertEquals(400, call("POST", "/api/mail-accounts", Map.of("name", "Bad", "provider", "gmail", "address", "x@gmail.com", "sign_in", "microsoft", "ms_signin", "ms" + "0".repeat(24)), token).get("_status"),
                "Gmail does not sign in with Microsoft");
        assertEquals(400, call("POST", "/api/mail-accounts", Map.of("name", "Bad", "provider", "gmail", "address", "nope", "secret_ref", "mail/scans/password"), token).get("_status"));

        assertEquals(200, call("DELETE", "/api/mail-accounts/" + gmail.get("id"), null, token).get("_status"));
        assertNull(cr.mailAccounts.find("Supplier Gmail"));
    }

    @Test @SuppressWarnings("unchecked")
    void agentsUseImapAccountsAsTheEmailToolkitsConnectionsWhenLentToTheirUser() throws Exception {
        Map<String, Object> made = call("POST", "/api/mail-accounts", Map.of("name", "Agents' Zoho", "provider", "zoho", "address", "ap@kerala-traders.in",
                "region", "in", "secret_ref", "mail/scans/password"), token);
        assertEquals(200, made.get("_status"), String.valueOf(made.get("_body")));
        String id = String.valueOf(made.get("id"));
        Map<String, Object> conn = (Map<String, Object>) cr.mailAccounts.forJob().get("Agents' Zoho");
        assertEquals("ooa.email", conn.get("package"));
        Map<String, Object> values = (Map<String, Object>) conn.get("values");
        assertEquals(List.of("imap.zoho.in", 993, "ap@kerala-traders.in", "ssl", "imap"),
                List.of(values.get("imap_host"), ((Number) values.get("port")).intValue(), values.get("user"), values.get("security"), values.get("protocol")));
        assertEquals(Map.of("password", "mail:" + id), conn.get("credentials"), "the password is fetched for the run, never put in the job");
        assertEquals("right-pw", cr.mailAccounts.forRun("admin", id));
        sessionFor("otto", "bot_runner");
        assertEquals("VAULT.DENIED", assertThrows(OoaException.class, () -> cr.mailAccounts.forRun("otto", id)).code(), "only the people the account is lent to");
        call("PUT", "/api/mail-accounts/" + id, Map.of("allowed", List.of("user:otto")), token);
        assertEquals("right-pw", cr.mailAccounts.forRun("otto", id));
        assertTrue(JsonLite.write(call("GET", "/api/audit?q=mail-account:" + id, null, token)).contains("for otto"), "audited as the account, for the run's user");
        call("DELETE", "/api/mail-accounts/" + id, null, token);
    }

    @Test @SuppressWarnings("unchecked")
    void aPersonalMicrosoftAccountSignsInAndIsRead() throws Exception {
        ControlRoom.User admin = cr.session(token);
        List<String> seen = new CopyOnWriteArrayList<>();
        String claims = Base64.getUrlEncoder().withoutPadding().encodeToString("{\"tid\":\"9188040d-6c67-4c5b-b112-36a304b66dad\",\"preferred_username\":\"ap.person@outlook.com\"}".getBytes(StandardCharsets.UTF_8));
        HttpServer ms = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        ms.createContext("/", ex -> {
            String path = ex.getRequestURI().getPath(), body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(path + " " + body + " " + ex.getRequestHeaders().getFirst("Authorization"));
            String out = path.equals("/consumers/oauth2/v2.0/devicecode") ? "{\"device_code\":\"DEV-P\",\"user_code\":\"PERS-0001\",\"expires_in\":900,\"interval\":1}"
                    : path.equals("/consumers/oauth2/v2.0/token") && body.contains("device_code") ? "{\"access_token\":\"acc-p1\",\"refresh_token\":\"ref-p1\",\"expires_in\":3600,\"id_token\":\"x." + claims + ".y\"}"
                    : path.equals("/consumers/oauth2/v2.0/token") ? "{\"access_token\":\"acc-p2\",\"refresh_token\":\"ref-p2\",\"expires_in\":3600}"
                    : path.startsWith("/v1.0/me/") ? "{\"value\":[{\"id\":\"m1\",\"receivedDateTime\":\"2026-11-15T08:00:00Z\"}]}" : "{\"error\":\"unexpected\"}";
            byte[] b = out.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", "application/json");
            ex.sendResponseHeaders(out.contains("unexpected") ? 400 : 200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        ms.start();
        String url = "http://127.0.0.1:" + ms.getAddress().getPort();
        System.setProperty("ooa.microsoft.login.url", url);
        System.setProperty("ooa.microsoft.graph.url", url + "/v1.0");
        try {
            cr.platform.updateSettings(admin, Map.of("microsoft_app_id", "11111111-2222-3333-4444-555555555555"), null);
            Map<String, Object> started = call("POST", "/api/mail-accounts/ms-signin?audience=consumers", Map.of(), token);
            assertEquals("PERS-0001", started.get("userCode"), String.valueOf(started.get("_body")));
            String sid = (String) started.get("id");
            assertTrue(seen.get(0).contains("Mail.Read") && !seen.get(0).contains("Mail.Read.Shared"), "personal accounts have no shared mailboxes: " + seen.get(0));
            db.update("UPDATE ms_signins SET next_poll = ? WHERE id = ?", java.time.Instant.now().minusSeconds(5), sid);
            assertEquals("signed_in", call("GET", "/api/mail-accounts/ms-signin/" + sid, null, token).get("status"));
            assertEquals("consumers", db.one("SELECT audience FROM ms_signins WHERE id = ?", sid).get("audience"));
            assertEquals(400, call("POST", "/api/mail-accounts/ms-signin?audience=everyone", Map.of(), token).get("_status"));

            Map<String, Object> made = call("POST", "/api/mail-accounts", Map.of("name", "Personal box", "provider", "outlook_com", "address", "ap.person@outlook.com", "ms_signin", sid), token);
            assertEquals(200, made.get("_status"), String.valueOf(made.get("_body")));
            assertEquals("ap.person@outlook.com", made.get("signed_in_as"));
            assertEquals("microsoft", made.get("sign_in"));
            cr.microsoft = new MicrosoftSignIn(cr);                                   // no token in memory: the lasting sign-in is used
            Map<String, Object> tested = call("POST", "/api/mail-accounts/" + made.get("id") + "/test", null, token);
            assertEquals(true, tested.get("ok"), String.valueOf(tested));
            assertTrue(seen.stream().anyMatch(x -> x.startsWith("/consumers/oauth2/v2.0/token") && x.contains("refresh_token=ref-p1")), "renewed at the personal-accounts address: " + seen);
            assertTrue(seen.stream().anyMatch(x -> x.startsWith("/v1.0/me/mailFolders/inbox/messages") && x.endsWith("Bearer acc-p2")), String.valueOf(seen));
            call("DELETE", "/api/mail-accounts/" + made.get("id"), null, token);
        } finally {
            System.clearProperty("ooa.microsoft.login.url");
            System.clearProperty("ooa.microsoft.graph.url");
            cr.platform.updateSettings(admin, Map.of("microsoft_app_id", ""), null);
            ms.stop(0);
        }
    }

    /** A scripted IMAP server: LOGIN works with one password, SELECT reports UIDNEXT, nothing else is needed for a test. */
    static final class FakeImap implements Closeable {
        final ServerSocket ss;
        final List<String> seen = new CopyOnWriteArrayList<>();
        final String password;

        FakeImap(String password) throws IOException {
            this.password = password;
            this.ss = new ServerSocket(0);
            Thread t = new Thread(this::serve);
            t.setDaemon(true);
            t.start();
        }

        int port() { return ss.getLocalPort(); }

        private void serve() {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    OutputStream out = s.getOutputStream();
                    write(out, "* OK fake IMAP ready\r\n");
                    for (String line; (line = in.readLine()) != null; ) {
                        seen.add(line);
                        String tag = line.substring(0, line.indexOf(' ')), cmd = line.substring(line.indexOf(' ') + 1);
                        if (cmd.startsWith("LOGIN")) write(out, cmd.contains("\"" + password + "\"") ? tag + " OK logged in\r\n" : tag + " NO [AUTHENTICATIONFAILED] Invalid credentials\r\n");
                        else if (cmd.startsWith("SELECT")) write(out, "* 3 EXISTS\r\n* OK [UIDVALIDITY 77] ok\r\n* OK [UIDNEXT 12] ok\r\n" + tag + " OK [READ-WRITE] done\r\n");
                        else if (cmd.startsWith("UID SEARCH")) write(out, "* SEARCH\r\n" + tag + " OK done\r\n");
                        else if (cmd.startsWith("LOGOUT")) { write(out, "* BYE\r\n" + tag + " OK bye\r\n"); break; }
                        else write(out, tag + " BAD unknown\r\n");
                    }
                } catch (IOException ignored) { }
            }
        }

        static void write(OutputStream out, String s) throws IOException { out.write(s.getBytes(StandardCharsets.UTF_8)); out.flush(); }
        public void close() throws IOException { ss.close(); }
    }
}
