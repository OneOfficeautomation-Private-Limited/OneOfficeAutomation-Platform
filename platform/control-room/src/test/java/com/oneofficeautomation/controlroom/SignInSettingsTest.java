package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Sign-in and security settings added on 1 Nov 2026 (Settings item 4, batch 1, as AA's, UiPath's and Blue Prism's):
 * lockout attempts and minutes, upper- and lower-case and symbol rules, a minimum time before a new password changes
 * again, the idle sign-out and expiry warning the console gets, the longest API key, and the sign-in notice.
 */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class SignInSettingsTest {
    Db db; ControlRoom cr; ControlRoom.User admin;

    static String code(Runnable r) { try { r.run(); return "ok"; } catch (OoaException e) { return e.code(); } }

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-signin-settings");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
    }

    @AfterAll void stop() { db.close(); }

    /** A new person with their own password (the temporary one changed), signed in. */
    ControlRoom.User person(String name, String password) {
        String temp = (String) cr.createUser(admin, name, name, "bot_creator", null).get("temporary_password");
        String t = (String) cr.login(name, temp, null).get("token");
        cr.changePassword(cr.session(t), temp, password, t, null);
        return cr.session((String) cr.login(name, password, null).get("token"));
    }

    @Test @SuppressWarnings("unchecked")
    void signInRules() throws Exception {
        // lockout: after 3 wrong passwords the account is locked, even for the right one
        cr.platform.updateSettings(admin, Map.of("lockout_attempts", 3, "lockout_minutes", 2), null);
        person("sam", "Sam-Passw0rd-2026");
        for (int i = 0; i < 3; i++) assertEquals("CR.LOGIN_FAILED", code(() -> cr.login("sam", "wrong-password-1", null)));
        assertEquals("CR.LOCKED_OUT", code(() -> cr.login("sam", "Sam-Passw0rd-2026", null)));

        // password rules: upper- and lower-case letters and a symbol; a new password kept at least a day
        cr.platform.updateSettings(admin, Map.of("password_needs_mixed_case", 1, "password_needs_symbol", 1), null);
        ControlRoom.User tia = person("tia", "Tia-Passw0rd-2026");
        String tok = (String) cr.login("tia", "Tia-Passw0rd-2026", null).get("token");
        assertTrue(assertThrows(OoaException.class, () -> cr.changePassword(tia, "Tia-Passw0rd-2026", "lowercase-only-2026", tok, null)).getMessage().contains("upper- and lower-case"));
        assertTrue(assertThrows(OoaException.class, () -> cr.changePassword(tia, "Tia-Passw0rd-2026", "MixedCaseOnly2026", tok, null)).getMessage().contains("symbol"));
        cr.platform.updateSettings(admin, Map.of("password_min_days", 1), null);
        assertTrue(assertThrows(OoaException.class, () -> cr.changePassword(tia, "Tia-Passw0rd-2026", "Tia-Newer-Pass-2026!", tok, null)).getMessage().contains("after 1 day"));

        // what the console gets: sign out after idle minutes; the password expiry warning within its days
        cr.platform.updateSettings(admin, Map.of("idle_minutes", 30, "password_max_days", 10, "password_expiry_warn_days", 15), null);
        Map<String, Object> me = cr.me(tia);
        assertEquals(30, ((Number) me.get("idle_minutes")).intValue());
        assertTrue(((Number) me.get("password_expires_in_days")).intValue() >= 9, String.valueOf(me));

        // the longest API key: one asked for with no end gets that lifetime
        cr.platform.updateSettings(admin, Map.of("api_key_max_days", 30), null);
        Map<String, Object> key = cr.platform.createApiKey(tia, "reports", 0, null);
        Instant expires = Instant.parse(String.valueOf(key.get("expires")));
        assertTrue(expires.isAfter(Instant.now().plus(Duration.ofDays(29))) && expires.isBefore(Instant.now().plus(Duration.ofDays(31))), String.valueOf(expires));

        // the sign-in notice: shown before signing in; a sign-in without accepting it is refused
        cr.platform.updateSettings(admin, Map.of("signin_notice", "Authorised use only.", "signin_notice_accept", 1), null);
        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.port() + "/api/";
            HttpClient http = HttpClient.newHttpClient();
            Map<String, Object> info = (Map<String, Object>) JsonLite.parse(http.send(HttpRequest.newBuilder(URI.create(base + "auth/sso")).build(), HttpResponse.BodyHandlers.ofString()).body());
            assertEquals(Map.of("text", "Authorised use only.", "accept", true), info.get("notice"));
            HttpResponse<String> refused = http.send(HttpRequest.newBuilder(URI.create(base + "auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(Map.of("username", "tia", "password", "Tia-Passw0rd-2026")))).build(), HttpResponse.BodyHandlers.ofString());
            assertTrue(refused.body().contains("CR.NOTICE_NOT_ACCEPTED"), refused.body());
            HttpResponse<String> in = http.send(HttpRequest.newBuilder(URI.create(base + "auth/login")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(Map.of("username", "tia", "password", "Tia-Passw0rd-2026", "accepted", true)))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, in.statusCode(), in.body());
        } finally { server.stop(); }
    }
}
