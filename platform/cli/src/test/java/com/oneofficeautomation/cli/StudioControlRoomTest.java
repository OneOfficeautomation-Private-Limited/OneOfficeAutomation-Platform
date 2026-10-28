package com.oneofficeautomation.cli;

import com.oneofficeautomation.controlroom.*;
import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.net.ServerSocket;
import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Studio's private workspace working with the Control Room public repository: connect, check in, check out, locks, undo. */
class StudioControlRoomTest {
    static final HttpClient HTTP = HttpClient.newHttpClient();

    @SuppressWarnings("unchecked")
    static Map<String, Object> call(String base, String method, String path, Object body, String bearer) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + path)).method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body)))
                .header("Content-Type", "application/json");
        if (bearer != null) b.header("Authorization", "Bearer " + bearer);
        HttpResponse<String> r = HTTP.send(b.build(), HttpResponse.BodyHandlers.ofString());
        Map<String, Object> m = new LinkedHashMap<>(r.body().isBlank() ? Map.of() : (Map<String, Object>) JsonLite.parse(r.body()));
        m.put("_status", r.statusCode());
        return m;
    }

    static int freePort() throws Exception { try (ServerSocket s = new ServerSocket(0)) { return s.getLocalPort(); } }

    @SuppressWarnings("unchecked")
    @Test void studioChecksInAndOutThroughControlRoom() throws Exception {
        Path data = Files.createTempDirectory("ooa-cr2"), wsA = Files.createTempDirectory("ooa-wsA"), wsB = Files.createTempDirectory("ooa-wsB");
        Db db = Db.embedded(data);
        ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        String pw = cr.bootstrap();
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        String crUrl = "http://127.0.0.1:" + server.port();
        int pa = freePort(), pb = freePort();
        new StudioServer(wsA, pa, Path.of("/nonexistent")).start();
        new StudioServer(wsB, pb, Path.of("/nonexistent")).start();
        String sa = "http://127.0.0.1:" + pa, sb = "http://127.0.0.1:" + pb;
        try {
            String admin = (String) call(crUrl, "POST", "/api/auth/login", Map.of("username", "admin", "password", pw), null).get("token");
            assertEquals("STUDIO.CR_PASSWORD_CHANGE", ((Map<String, Object>) call(sa, "POST", "/api/cr/connect", Map.of("url", crUrl, "username", "admin", "password", pw), null).get("error")).get("code"),
                    "Studio asks for the first-time password change in Control Room");
            call(crUrl, "POST", "/api/auth/password", Map.of("current", pw, "new", "Admin-Passw0rd-2026"), admin);
            for (String u : List.of("alice", "bob")) {
                String tmp = (String) call(crUrl, "POST", "/api/users", Map.of("username", u, "role", "bot_creator"), admin).get("temporary_password");
                String t = (String) call(crUrl, "POST", "/api/auth/login", Map.of("username", u, "password", tmp), null).get("token");
                call(crUrl, "POST", "/api/auth/password", Map.of("current", tmp, "new", u + "-Passw0rd-99"), t);
            }
            assertEquals(true, call(sa, "POST", "/api/cr/connect", Map.of("url", crUrl, "username", "alice", "password", "alice-Passw0rd-99"), null).get("connected"));
            assertEquals(true, call(sb, "POST", "/api/cr/connect", Map.of("url", crUrl, "username", "bob", "password", "bob-Passw0rd-99"), null).get("connected"));

            Files.createDirectories(wsA.resolve("My Bots"));
            String bot = "{\"kind\":\"TaskBot\",\"id\":\"invoice_bot\",\"name\":\"Invoice bot\",\"description\":\"Processes invoices\",\"variables\":[],\"steps\":[{\"id\":\"s1\",\"action\":\"core/log\",\"args\":{\"message\":\"v1\"}}]}";
            Files.writeString(wsA.resolve("My Bots/Invoice bot.flow.json"), bot);
            Map<String, Object> in1 = call(sa, "POST", "/api/cr/checkin", Map.of("local", "My Bots/Invoice bot.flow.json", "comment", "first version"), null);
            assertEquals(1, ((Number) in1.get("version")).intValue(), String.valueOf(in1));
            List<Object> bots = (List<Object>) call(sb, "GET", "/api/cr/repo", null, null).get("bots");
            assertEquals("My Bots/Invoice bot.flow.json", ((Map<String, Object>) bots.get(0)).get("path"));

            Map<String, Object> co = call(sb, "POST", "/api/cr/checkout", Map.of("path", "My Bots/Invoice bot.flow.json"), null);
            assertEquals("Control Room/My Bots/Invoice bot.flow.json", co.get("local"));
            assertTrue(Files.exists(wsB.resolve("Control Room/My Bots/Invoice bot.flow.json")), "checked-out copy lands in Bob's private workspace");
            Map<String, Object> blocked = call(sa, "POST", "/api/cr/checkout", Map.of("path", "My Bots/Invoice bot.flow.json"), null);
            assertEquals(409, blocked.get("_status"));
            assertTrue(String.valueOf(blocked.get("error")).contains("bob"), "Alice is told who has it");
            assertEquals("CR.NOT_CHECKED_OUT", ((Map<String, Object>) call(sa, "POST", "/api/cr/checkin", Map.of("local", "My Bots/Invoice bot.flow.json", "comment", "x"), null).get("error")).get("code"));

            Path bobCopy = wsB.resolve("Control Room/My Bots/Invoice bot.flow.json");
            Files.writeString(bobCopy, Files.readString(bobCopy).replace("\"v1\"", "\"v2 by bob\""));
            Map<String, Object> in2 = call(sb, "POST", "/api/cr/checkin", Map.of("local", "Control Room/My Bots/Invoice bot.flow.json", "comment", "bob's change", "production", false), null);
            assertEquals(2, ((Number) in2.get("version")).intValue(), "the private copy goes back to the same repository path");
            assertEquals("My Bots/Invoice bot.flow.json", in2.get("path"));
            assertEquals(Boolean.FALSE, in2.get("production"));
            Map<String, Object> listed = (Map<String, Object>) ((List<Object>) call(sb, "GET", "/api/cr/repo", null, null).get("bots")).get(0);
            assertEquals(1, ((Number) listed.get("production_version")).intValue(), "checked in without 'live': version 1 stays the live one");
            // a new agent's first version becomes live even without the tick, or nothing could run it
            Files.writeString(wsA.resolve("My Bots/Fresh.flow.json"), bot.replace("invoice_bot", "fresh_bot").replace("Invoice bot", "Fresh"));
            Map<String, Object> first = call(sa, "POST", "/api/cr/checkin", Map.of("local", "My Bots/Fresh.flow.json", "comment", "new", "production", false), null);
            assertEquals(Boolean.TRUE, first.get("production"), String.valueOf(first));

            assertEquals(200, call(sa, "POST", "/api/cr/checkout", Map.of("path", "My Bots/Invoice bot.flow.json"), null).get("_status"), "check-in released Bob's lock");
            assertTrue(Files.readString(wsA.resolve("Control Room/My Bots/Invoice bot.flow.json")).contains("v2 by bob"), "Alice gets Bob's latest version");
            assertEquals(200, call(sa, "POST", "/api/cr/undo-checkout", Map.of("path", "My Bots/Invoice bot.flow.json"), null).get("_status"));
            assertEquals(2, ((List<Object>) call(sa, "GET", "/api/cr/versions?path=My%20Bots/Invoice%20bot.flow.json", null, null).get("versions")).size());

            Files.writeString(wsA.resolve("My Bots/Broken.flow.json"), "{\"kind\":\"TaskBot\",\"id\":\"broken_bot\",\"name\":\"b\",\"variables\":[],\"steps\":[{\"id\":\"s1\",\"action\":\"core/log\",\"args\":{\"message\":\"$nope\"}}]}");
            Map<String, Object> bad = call(sa, "POST", "/api/cr/checkin", Map.of("local", "My Bots/Broken.flow.json"), null);
            assertEquals("CR.INVALID_BOT", ((Map<String, Object>) bad.get("error")).get("code"));
            assertTrue(String.valueOf(bad.get("error")).contains("not declared"), "the problem list explains why");
        } finally { server.stop(); db.close(); }
    }
}
