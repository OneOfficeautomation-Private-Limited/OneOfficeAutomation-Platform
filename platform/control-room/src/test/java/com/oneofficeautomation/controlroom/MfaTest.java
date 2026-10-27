package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.packages.FirstPartyPackages;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;
import org.junit.jupiter.api.*;

import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Two-step sign-in: TOTP codes, recovery codes, the required setting, resets and the HTTP flow. */
@TestInstance(TestInstance.Lifecycle.PER_CLASS)
class MfaTest {
    Db db; ControlRoom cr; ControlRoom.User admin;
    Instant now = Instant.parse("2026-11-02T10:00:00Z");

    @BeforeAll void start() throws Exception {
        Path data = Files.createTempDirectory("ooa-mfa");
        db = Db.embedded(data);
        cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
        admin = RepositoryLifecycleTest.boot(cr);
        cr.mfa.clock = Clock.fixed(now, ZoneOffset.UTC);
    }
    @AfterAll void stop() { cr.triggerPollers.stop(); db.close(); }

    void at(Instant t) { now = t; cr.mfa.clock = Clock.fixed(t, ZoneOffset.UTC); }
    String code(String secret) { return Mfa.totp(Mfa.base32Decode(secret), now.getEpochSecond() / 30); }
    static String code(OoaException e) { return e.code(); }

    @Test void totpMatchesRfc6238() {
        byte[] key = "12345678901234567890".getBytes(StandardCharsets.US_ASCII);
        assertEquals("287082", Mfa.totp(key, 59 / 30));               // RFC 6238 appendix B, T = 59 (94287082)
        assertEquals("081804", Mfa.totp(key, 1111111109L / 30));      // 07081804
        assertArrayEquals(key, Mfa.base32Decode(Mfa.base32Encode(key)));
        assertEquals("GEZDGNBVGY3TQOJQGEZDGNBVGY3TQOJQ", Mfa.base32Encode(key));
    }

    @Test void setUpSignInWithACodeAndRecoveryCodes() {
        ControlRoom.User dana = person("dana");
        Map<String, Object> s = cr.mfa.setup(dana, null);
        String secret = ((String) s.get("secret")).replace(" ", "");
        assertTrue(((String) s.get("otpauth_uri")).startsWith("otpauth://totp/ONEOFFICEAUTOMATION%3Adana?secret=" + secret));
        assertEquals("CR.MFA_WRONG_CODE", assertThrows(OoaException.class, () -> cr.mfa.enable(dana, "000000", null)).code());
        Map<String, Object> on = cr.mfa.enable(dana, code(secret), null);
        @SuppressWarnings("unchecked") List<String> recovery = (List<String>) on.get("recovery_codes");
        assertEquals(10, recovery.size());
        assertTrue(recovery.get(0).matches("[A-Z0-9]{5}-[A-Z0-9]{5}"));
        assertEquals("CR.CONFLICT", assertThrows(OoaException.class, () -> cr.mfa.setup(dana, null)).code());

        // the password alone gives a challenge, not a session
        at(now.plusSeconds(60));
        Map<String, Object> first = cr.login("dana", "Dana-Passw0rd-2026", null);
        assertEquals(true, first.get("mfa_required"));
        assertNull(first.get("token"));
        String challenge = (String) first.get("mfa_token");
        assertEquals("CR.MFA_WRONG_CODE", assertThrows(OoaException.class, () -> cr.loginMfa(challenge, "123456", null)).code());
        String c = code(secret);
        Map<String, Object> ok = cr.loginMfa(challenge, c, null);
        assertNotNull(ok.get("token"));
        assertEquals("CR.MFA_EXPIRED", assertThrows(OoaException.class, () -> cr.loginMfa(challenge, c, null)).code(), "a challenge is used once");

        // the same code cannot be used twice; a code sent with the password signs in at once (Studio)
        String again = (String) cr.login("dana", "Dana-Passw0rd-2026", null).get("mfa_token");
        assertEquals("CR.MFA_WRONG_CODE", assertThrows(OoaException.class, () -> cr.loginMfa(again, c, null)).code(), "no replay");
        at(now.plusSeconds(30));
        assertNotNull(cr.login("dana", "Dana-Passw0rd-2026", code(secret), null).get("token"));

        // a recovery code works once
        String rc = recovery.get(3);
        assertNotNull(cr.loginMfa((String) cr.login("dana", "Dana-Passw0rd-2026", null).get("mfa_token"), rc.toLowerCase(Locale.ROOT), null).get("token"));
        String t2 = (String) cr.login("dana", "Dana-Passw0rd-2026", null).get("mfa_token");
        assertEquals("CR.MFA_WRONG_CODE", assertThrows(OoaException.class, () -> cr.loginMfa(t2, rc, null)).code());
        ControlRoom.User viaSecond = cr.session((String) cr.login("dana", "Dana-Passw0rd-2026", recovery.get(4), null).get("token"));
        assertEquals(8, cr.mfa.status(viaSecond).get("recovery_codes_left"), "two of ten recovery codes used");

        // turning it off needs the password and a code
        at(now.plusSeconds(30));
        ControlRoom.User danaNow = cr.session((String) cr.login("dana", "Dana-Passw0rd-2026", code(secret), null).get("token"));
        at(now.plusSeconds(30));
        assertEquals("CR.LOGIN_FAILED", assertThrows(OoaException.class, () -> cr.mfa.disable(danaNow, "wrong", code(secret), null)).code());
        cr.mfa.disable(danaNow, "Dana-Passw0rd-2026", code(secret), null);
        assertNotNull(cr.login("dana", "Dana-Passw0rd-2026", null).get("token"), "off again: the password is enough");
    }

    @Test void wrongCodesCountTowardsTheLockout() {
        ControlRoom.User erin = person("erin");
        String secret = ((String) cr.mfa.setup(erin, null).get("secret")).replace(" ", "");
        cr.mfa.enable(erin, code(secret), null);
        String t = (String) cr.login("erin", "Erin-Passw0rd-2026", null).get("mfa_token");
        for (int i = 0; i < 4; i++) assertEquals("CR.MFA_WRONG_CODE", assertThrows(OoaException.class, () -> cr.loginMfa(t, "111111", null)).code());
        assertEquals("CR.MFA_EXPIRED", assertThrows(OoaException.class, () -> cr.loginMfa(t, "111111", null)).code(), "the fifth wrong code ends the challenge");
        assertEquals(1, ((Number) db.one("SELECT failed FROM users WHERE username = 'erin'").get("failed")).intValue(), "and counts as a failed sign-in");
        // the administrator resets it (a lost phone); local recovery clears it too
        cr.mfa.reset(admin, "erin", null);
        assertNotNull(cr.login("erin", "Erin-Passw0rd-2026", null).get("token"));
        assertEquals("CR.FORBIDDEN", assertThrows(OoaException.class, () -> cr.mfa.reset(person("fred"), "erin", null)).code());
        String s2 = ((String) cr.mfa.setup(erin, null).get("secret")).replace(" ", "");
        cr.mfa.enable(erin, code(s2), null);
        cr.recoverUser("erin");
        assertEquals(false, db.one("SELECT mfa_enabled FROM users WHERE username = 'erin'").get("mfa_enabled"));
    }

    @Test void theRequiredSettingAndTheHttpFlow() throws Exception {
        ControlRoom.User gina = person("gina");
        assertFalse(cr.mfa.setupNeeded(gina));
        cr.platform.updateSettings(admin, Map.of("mfa_required", 1), null);
        assertFalse(cr.mfa.setupNeeded(gina), "level 1 is for administrators");
        assertTrue(cr.mfa.setupNeeded(admin));
        cr.platform.updateSettings(admin, Map.of("mfa_required", 2), null);
        assertTrue(cr.mfa.setupNeeded(gina));
        assertFalse(cr.mfa.setupNeeded(new ControlRoom.User("x", "x", "viewer", "x", false, false, true)), "single sign-on users are left to the identity provider");
        assertEquals("CR.BAD_INPUT", assertThrows(OoaException.class, () -> cr.platform.updateSettings(admin, Map.of("mfa_required", 3), null)).code());

        ControlRoomServer server = new ControlRoomServer(cr, "127.0.0.1", 0);
        server.start();
        try {
            String base = "http://127.0.0.1:" + server.port() + "/api/";
            HttpClient http = HttpClient.newHttpClient();
            String token = (String) post(http, base + "auth/login", Map.of("username", "gina", "password", "Gina-Passw0rd-2026"), null).get("token");
            HttpResponse<String> blocked = http.send(HttpRequest.newBuilder(URI.create(base + "dashboard")).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(403, blocked.statusCode());
            assertTrue(blocked.body().contains("CR.MFA_SETUP_REQUIRED"));
            assertEquals(true, get(http, base + "auth/me", token).get("mfa_setup_required"));
            String secret = ((String) post(http, base + "auth/mfa/setup", Map.of(), token).get("secret")).replace(" ", "");
            post(http, base + "auth/mfa/enable", Map.of("code", code(secret)), token);
            assertEquals(200, http.send(HttpRequest.newBuilder(URI.create(base + "dashboard")).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString()).statusCode());

            at(now.plusSeconds(30));
            Map<String, Object> step1 = post(http, base + "auth/login", Map.of("username", "gina", "password", "Gina-Passw0rd-2026"), null);
            assertEquals(true, step1.get("mfa_required"));
            HttpResponse<String> wrong = http.send(HttpRequest.newBuilder(URI.create(base + "auth/mfa")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(Map.of("mfa_token", step1.get("mfa_token"), "code", "000000")))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, wrong.statusCode());
            HttpResponse<String> right = http.send(HttpRequest.newBuilder(URI.create(base + "auth/mfa")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(Map.of("mfa_token", step1.get("mfa_token"), "code", code(secret))))).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, right.statusCode());
            assertTrue(right.headers().firstValue("Set-Cookie").orElse("").startsWith(ControlRoomServer.COOKIE + "="));
        } finally {
            server.stop();
            cr.platform.updateSettings(admin, Map.of("mfa_required", 0), null);
        }
    }

    ControlRoom.User person(String name) {
        String pw = Character.toUpperCase(name.charAt(0)) + name.substring(1) + "-Passw0rd-2026";
        Map<String, Object> created = cr.createUser(admin, name, name, "bot_creator", null);
        String temp = (String) created.get("temporary_password");
        String t = (String) cr.login(name, temp, null).get("token");
        cr.changePassword(cr.session(t), temp, pw, t, null);
        return cr.session((String) cr.login(name, pw, null).get("token"));
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> post(HttpClient http, String url, Object body, String token) throws Exception {
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(body)));
        if (token != null) b.header("Authorization", "Bearer " + token);
        HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
        assertEquals(200, r.statusCode(), r.body());
        return (Map<String, Object>) JsonLite.parse(r.body());
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> get(HttpClient http, String url, String token) throws Exception {
        HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(url)).header("Authorization", "Bearer " + token).build(), HttpResponse.BodyHandlers.ofString());
        return (Map<String, Object>) JsonLite.parse(r.body());
    }
}
