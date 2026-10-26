package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Two-step sign-in for accounts with a local password: a 6-digit code from an authenticator app (TOTP, RFC 6238:
 * HMAC-SHA1, 30-second steps, one step of clock drift either way), or a one-time recovery code. The secret is kept
 * encrypted with the vault key; recovery codes only as SHA-256 hashes. A code is accepted once (the last used step is
 * remembered). Setting {@code mfa_required}: 0 optional, 1 required for administrators, 2 required for everyone who
 * signs in with a password. People who sign in through single sign-on are left to the identity provider; API keys are
 * not affected.
 */
public final class Mfa {
    private final ControlRoom cr;
    private final Db db;
    /** Sign-ins waiting for their code: the token is handed out after the password and lives 5 minutes. */
    private final Map<String, Challenge> challenges = new ConcurrentHashMap<>();
    record Challenge(String userId, Instant expires, int[] attempts) {}
    /** The time the codes are checked against (tests move it). */
    java.time.Clock clock = java.time.Clock.systemUTC();

    static final String ISSUER = "ONEOFFICEAUTOMATION";
    static final int RECOVERY_CODES = 10;

    Mfa(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    // ---------------- policy ----------------

    /** Whether this person must use two-step sign-in, from the mfa_required setting. */
    boolean requiredFor(User u) {
        if (u.sso()) return false;
        int level = cr.platform.intSetting("mfa_required");
        return level >= 2 || (level == 1 && (u.role().equals("admin") || cr.permissionsOf(u).contains("settings.manage")));
    }

    /** True when the setting requires two-step sign-in for this person and they have not set it up yet. */
    boolean setupNeeded(User u) { return !u.mfaEnabled() && requiredFor(u); }

    // ---------------- sign-in ----------------

    /** After a correct password: a challenge token when the account uses two-step sign-in, else null. */
    String challenge(Map<String, Object> user) {
        if (!Boolean.TRUE.equals(user.get("mfa_enabled"))) return null;
        challenges.values().removeIf(c -> c.expires().isBefore(clock.instant()));
        String t = Security.token(24);
        challenges.put(t, new Challenge((String) user.get("id"), clock.instant().plus(Duration.ofMinutes(5)), new int[1]));
        return t;
    }

    /** Checks the code for a challenge; returns the user's row. Five wrong codes end the challenge and count as a failed sign-in. */
    Map<String, Object> complete(String challengeToken, String code, String ip) {
        Challenge c = challengeToken == null ? null : challenges.get(challengeToken);
        if (c == null || c.expires().isBefore(clock.instant())) {
            if (challengeToken != null) challenges.remove(challengeToken);
            throw new OoaException("CR.MFA_EXPIRED", "The sign-in took too long", "Sign in again with your password.");
        }
        Map<String, Object> r = db.one("SELECT * FROM users WHERE id = ?", c.userId());
        if (r == null || !verify(r, code)) {
            String name = r == null ? "?" : (String) r.get("username");
            cr.audit.record(name, "auth.mfa", "user", name, null, "failed", ip);
            if (++c.attempts()[0] >= 5) {
                challenges.remove(challengeToken);
                if (r != null) cr.countFailedSignIn(r, ip);
                throw new OoaException("CR.MFA_EXPIRED", "Too many wrong codes", "Sign in again with your password.");
            }
            throw new OoaException("CR.MFA_WRONG_CODE", "That code is not right", "Enter the 6-digit code your authenticator app shows now, or a recovery code.");
        }
        challenges.remove(challengeToken);
        return r;
    }

    /** True for the current code (not used before) or an unused recovery code, which is then spent. */
    boolean verify(Map<String, Object> r, String code) {
        if (code == null) return false;
        String c = code.replaceAll("[\\s-]", "").toUpperCase(Locale.ROOT);
        if (c.matches("\\d{6}") && r.get("mfa_secret") != null) {
            byte[] secret = base32Decode(cr.vault.decrypt((String) r.get("mfa_secret"), "mfa:" + r.get("id")));
            long now = clock.instant().getEpochSecond() / 30;
            long last = r.get("mfa_last_step") instanceof Number n ? n.longValue() : -1;
            for (long step = now - 1; step <= now + 1; step++) {
                if (step > last && constantTimeEquals(totp(secret, step), c)) {
                    db.update("UPDATE users SET mfa_last_step = ? WHERE id = ?", step, r.get("id"));
                    return true;
                }
            }
            return false;
        }
        if (c.matches("[A-Z0-9]{10}") && r.get("mfa_recovery") instanceof String rec && !rec.isEmpty()) {
            List<String> left = new ArrayList<>(List.of(rec.split(",")));
            if (left.remove(Security.sha256(c))) {
                db.update("UPDATE users SET mfa_recovery = ? WHERE id = ?", String.join(",", left), r.get("id"));
                cr.audit.record((String) r.get("username"), "auth.mfa_recovery_code", "user", (String) r.get("username"), Map.of("left", left.size()), "ok", null);
                return true;
            }
        }
        return false;
    }

    private static boolean constantTimeEquals(String a, String b) { return java.security.MessageDigest.isEqual(a.getBytes(StandardCharsets.US_ASCII), b.getBytes(StandardCharsets.US_ASCII)); }

    // ---------------- set up, turn off, reset ----------------

    /** Starts setting up: a new secret (not yet in force) and the otpauth address for the authenticator app. */
    public Map<String, Object> setup(User u, String ip) {
        if (u.sso()) throw new OoaException("CR.BAD_INPUT", "You sign in through single sign-on; your identity provider handles two-step sign-in");
        Map<String, Object> r = db.one("SELECT mfa_enabled FROM users WHERE id = ?", u.id());
        if (Boolean.TRUE.equals(r.get("mfa_enabled"))) throw new OoaException("CR.CONFLICT", "Two-step sign-in is already on", "Turn it off first to move it to another device.");
        byte[] secret = new byte[20];
        Security.RANDOM.nextBytes(secret);
        String b32 = base32Encode(secret);
        db.update("UPDATE users SET mfa_secret = ?, mfa_last_step = NULL WHERE id = ?", cr.vault.encrypt(b32, "mfa:" + u.id()), u.id());
        String label = URLEncoder.encode(ISSUER + ":" + u.username(), StandardCharsets.UTF_8).replace("+", "%20");
        String uri = "otpauth://totp/" + label + "?secret=" + b32 + "&issuer=" + ISSUER + "&algorithm=SHA1&digits=6&period=30";
        cr.audit.record(u.username(), "user.mfa_setup", "user", u.username(), null, "ok", ip);
        return Map.of("secret", b32.replaceAll("(.{4})(?!$)", "$1 "), "otpauth_uri", uri);
    }

    /** Turns two-step sign-in on once the person enters a code from the new secret; returns the recovery codes (shown once). */
    public Map<String, Object> enable(User u, String code, String ip) {
        Map<String, Object> r = db.one("SELECT * FROM users WHERE id = ?", u.id());
        if (Boolean.TRUE.equals(r.get("mfa_enabled"))) throw new OoaException("CR.CONFLICT", "Two-step sign-in is already on");
        if (r.get("mfa_secret") == null) throw new OoaException("CR.BAD_INPUT", "Start the set-up first");
        if (code == null || !code.replaceAll("\\s", "").matches("\\d{6}") || !verify(r, code))
            throw new OoaException("CR.MFA_WRONG_CODE", "That code is not right", "Enter the 6-digit code the authenticator app shows for ONEOFFICEAUTOMATION.");
        List<String> codes = new ArrayList<>(), hashes = new ArrayList<>();
        String alphabet = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
        for (int i = 0; i < RECOVERY_CODES; i++) {
            StringBuilder s = new StringBuilder();
            for (int k = 0; k < 10; k++) s.append(alphabet.charAt(Security.RANDOM.nextInt(alphabet.length())));
            codes.add(s.substring(0, 5) + "-" + s.substring(5));
            hashes.add(Security.sha256(s.toString()));
        }
        db.update("UPDATE users SET mfa_enabled = TRUE, mfa_recovery = ? WHERE id = ?", String.join(",", hashes), u.id());
        cr.audit.record(u.username(), "user.mfa_enable", "user", u.username(), null, "ok", ip);
        return Map.of("enabled", true, "recovery_codes", codes);
    }

    /** Turns it off with the password and a current code; refused while the setting requires it for this person. */
    public void disable(User u, String password, String code, String ip) {
        if (requiredFor(u)) throw new OoaException("CR.FORBIDDEN", "Two-step sign-in is required for your account", "An administrator can reset it if you lost your device.");
        Map<String, Object> r = db.one("SELECT * FROM users WHERE id = ?", u.id());
        if (!Security.verifyPassword(password == null ? new char[0] : password.toCharArray(), (String) r.get("password_hash")) || !verify(r, code)) {
            cr.audit.record(u.username(), "user.mfa_disable", "user", u.username(), null, "failed", ip);
            throw new OoaException("CR.LOGIN_FAILED", "The password or the code is wrong");
        }
        clear(u.id());
        cr.audit.record(u.username(), "user.mfa_disable", "user", u.username(), null, "ok", ip);
    }

    /** An administrator resets someone's two-step sign-in (a lost phone); they set it up again at their next sign-in. */
    public void reset(User by, String userIdOrName, String ip) {
        if (!cr.can(by, "users.manage")) throw cr.denied("reset two-step sign-in");
        Map<String, Object> r = db.one("SELECT id, username FROM users WHERE id = ? OR username = ?", userIdOrName, userIdOrName);
        if (r == null) throw ControlRoom.notFound("User " + userIdOrName);
        clear((String) r.get("id"));
        db.update("DELETE FROM sessions WHERE user_id = ?", r.get("id"));
        cr.audit.record(by.username(), "user.mfa_reset", "user", (String) r.get("username"), null, "ok", ip);
    }

    void clear(String userId) {
        db.update("UPDATE users SET mfa_enabled = FALSE, mfa_secret = NULL, mfa_recovery = NULL, mfa_last_step = NULL WHERE id = ?", userId);
    }

    /** For the person's own menu: on or off, required or not, recovery codes left. */
    public Map<String, Object> status(User u) {
        Map<String, Object> r = db.one("SELECT mfa_enabled, mfa_recovery FROM users WHERE id = ?", u.id());
        boolean on = Boolean.TRUE.equals(r.get("mfa_enabled"));
        int left = on && r.get("mfa_recovery") instanceof String s && !s.isEmpty() ? s.split(",").length : 0;
        return Map.of("enabled", on, "required", requiredFor(u), "sso", u.sso(), "recovery_codes_left", left);
    }

    // ---------------- TOTP and Base32 ----------------

    static String totp(byte[] secret, long step) {
        try {
            Mac mac = Mac.getInstance("HmacSHA1");
            mac.init(new SecretKeySpec(secret, "HmacSHA1"));
            byte[] h = mac.doFinal(java.nio.ByteBuffer.allocate(8).putLong(step).array());
            int o = h[h.length - 1] & 0x0f;
            int bin = ((h[o] & 0x7f) << 24) | ((h[o + 1] & 0xff) << 16) | ((h[o + 2] & 0xff) << 8) | (h[o + 3] & 0xff);
            return String.format("%06d", bin % 1_000_000);
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static final String B32 = "ABCDEFGHIJKLMNOPQRSTUVWXYZ234567";

    static String base32Encode(byte[] data) {
        StringBuilder s = new StringBuilder();
        int buffer = 0, bits = 0;
        for (byte b : data) {
            buffer = (buffer << 8) | (b & 0xff); bits += 8;
            while (bits >= 5) { s.append(B32.charAt((buffer >> (bits - 5)) & 31)); bits -= 5; }
        }
        if (bits > 0) s.append(B32.charAt((buffer << (5 - bits)) & 31));
        return s.toString();
    }

    static byte[] base32Decode(String s) {
        String t = s.replaceAll("[\\s=]", "").toUpperCase(Locale.ROOT);
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        int buffer = 0, bits = 0;
        for (char ch : t.toCharArray()) {
            int v = B32.indexOf(ch);
            if (v < 0) throw new IllegalArgumentException("Not Base32");
            buffer = (buffer << 5) | v; bits += 5;
            if (bits >= 8) { out.write((buffer >> (bits - 8)) & 0xff); bits -= 8; }
        }
        return out.toByteArray();
    }
}
