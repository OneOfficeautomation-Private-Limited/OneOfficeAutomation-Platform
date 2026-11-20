package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.JsonLite;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.nio.charset.StandardCharsets;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.*;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

/**
 * The person token (S5, 20 Nov 2026; docs/integration/one-chat-contract.md, section 2): One CommandCenter names the
 * person who is speaking on every request it passes to the OneChat service, so the service can act only for that
 * person, and only for a few minutes. Format v1.&lt;payload&gt;.&lt;signature&gt;: the payload is JSON in base64url
 * {sub, role, tenant, area, session, ip, iat, exp, jti}; the signature is HMAC-SHA256 with a key only One CommandCenter
 * holds (in the module's table onechat_keys, encrypted with the vault key). The service reads the payload but cannot
 * make or change a token; One CommandCenter checks the signature, the time and the tenant on every callback, and then
 * the person's permissions as they are now.
 */
public final class PersonTokens {
    /** How long a token is good for. */
    static final int LIFE_SECONDS = 300;
    private static final String KEY_ID = "person-token", BOUND = "onechat:person-token-key";
    private static final Base64.Encoder B64 = Base64.getUrlEncoder().withoutPadding();
    private static final Base64.Decoder D64 = Base64.getUrlDecoder();

    /** A checked token: who, for which area and chat, from where, when it was made, and its id. */
    public record Person(User user, String area, String session, String ip, long issued, String id) {}

    private final ModuleContext ctx;
    private volatile byte[] key;

    public PersonTokens(ModuleContext ctx) { this.ctx = ctx; }

    /** A token for this person, area (chat or studio) and chat, good for five minutes. */
    public String issue(User u, String area, String session, String ip) { return issue(u, area, session, ip, Instant.now()); }

    /** As above, made at a given time (tests make expired tokens with it). */
    public String issue(User u, String area, String session, String ip, Instant at) {
        long now = at.getEpochSecond();
        Map<String, Object> p = new LinkedHashMap<>();
        p.put("sub", u.username());
        p.put("role", u.role());
        p.put("tenant", tenant());
        p.put("area", area);
        if (session != null) p.put("session", session);
        if (ip != null) p.put("ip", ip);
        p.put("iat", now);
        p.put("exp", now + LIFE_SECONDS);
        p.put("jti", id("ptk"));
        String body = "v1." + B64.encodeToString(JsonLite.write(p).getBytes(StandardCharsets.UTF_8));
        return body + "." + B64.encodeToString(sign(body));
    }

    /**
     * The person a token names, or null when it is not good: not signed by this One CommandCenter, expired, for
     * another tenant, or for someone who no longer has an active account.
     */
    @SuppressWarnings("unchecked")
    public Person check(String token, String tenantHeader) {
        if (token == null) return null;
        String[] parts = token.trim().split("\\.");
        if (parts.length != 3 || !parts[0].equals("v1")) return null;
        String body = parts[0] + "." + parts[1];
        byte[] given;
        try { given = D64.decode(parts[2]); } catch (IllegalArgumentException e) { return null; }
        if (!java.security.MessageDigest.isEqual(given, sign(body))) return null;
        Map<String, Object> p;
        try { p = (Map<String, Object>) JsonLite.parse(new String(D64.decode(parts[1]), StandardCharsets.UTF_8)); }
        catch (RuntimeException e) { return null; }
        long exp = p.get("exp") instanceof Number n ? n.longValue() : 0, iat = p.get("iat") instanceof Number n ? n.longValue() : 0;
        if (exp < Instant.now().getEpochSecond()) return null;
        String tenant = String.valueOf(p.get("tenant"));
        if (!tenant.equals(tenant()) || (tenantHeader != null && !tenantHeader.equals(tenant))) return null;
        User u = ctx.activeUser(String.valueOf(p.get("sub")));
        if (u == null) return null;
        return new Person(u, String.valueOf(p.get("area")), p.get("session") == null ? null : String.valueOf(p.get("session")),
                p.get("ip") == null ? null : String.valueOf(p.get("ip")), iat, String.valueOf(p.get("jti")));
    }

    /** The tenant a token and the service's requests name: the tenant's id in SaaS, default on premises. */
    public String tenant() { return ctx.tenant() == null ? "default" : ctx.tenant(); }

    private byte[] sign(String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key(), "HmacSHA256"));
            return mac.doFinal(body.getBytes(StandardCharsets.UTF_8));
        } catch (java.security.GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    /** The signing key: read once, made on first use (32 random bytes), kept encrypted with the vault key. */
    private byte[] key() {
        byte[] k = key;
        if (k != null) return k;
        synchronized (this) {
            if (key != null) return key;
            Map<String, Object> r = ctx.db().one("SELECT secret FROM onechat_keys WHERE id = ?", KEY_ID);
            if (r == null) {
                byte[] fresh = new byte[32];
                new SecureRandom().nextBytes(fresh);
                try { ctx.db().update("INSERT INTO onechat_keys (id, secret, created) VALUES (?,?,?)", KEY_ID, ctx.vault().encrypt(Base64.getEncoder().encodeToString(fresh), BOUND), now()); }
                catch (RuntimeException e) { /* another node made it first: read theirs */ }
                r = ctx.db().one("SELECT secret FROM onechat_keys WHERE id = ?", KEY_ID);
            }
            key = Base64.getDecoder().decode(ctx.vault().decrypt(String.valueOf(r.get("secret")), BOUND));
            return key;
        }
    }
}
