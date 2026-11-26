package com.oneofficeautomation.controlroom.runs;

import com.oneofficeautomation.controlroom.JsonPath;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.util.*;
import java.util.function.Function;
import java.util.regex.Pattern;

/**
 * What a webhook trigger does with a call before its agent starts (G6): reads the body as JSON, a form, or plain text
 * (XML included); answers a sender's address check by echoing its challenge value; checks a required header and an HMAC
 * signature of the body; and picks payload values out for agent inputs of their own. The settings come from
 * {@link TriggerKinds#validate}; secrets are read from the vault through the function given, never stored here.
 */
public final class Webhooks {
    private Webhooks() {}

    /** The answer to send: 200 with the challenge for an address check, else 202 with the run that was started. */
    public record Reply(int status, Map<String, Object> body) {}

    private static final Pattern HEX = Pattern.compile("^[0-9a-fA-F]+$");
    static final int MAX_CHALLENGE = 512;

    /**
     * The payload: JSON for a JSON content type (or no content type when the body is JSON), the fields of a form
     * (application/x-www-form-urlencoded), else the body as text. An empty body is an empty object.
     */
    public static Object payload(byte[] raw, String contentType) {
        String text = new String(raw == null ? new byte[0] : raw, StandardCharsets.UTF_8);
        if (text.isBlank()) return new LinkedHashMap<String, Object>();
        String ct = contentType == null ? "" : contentType.toLowerCase(Locale.ROOT).split(";")[0].strip();
        if (ct.equals("application/x-www-form-urlencoded")) {
            Map<String, Object> form = new LinkedHashMap<>();
            for (String kv : text.split("&")) {
                if (kv.isEmpty()) continue;
                int i = kv.indexOf('=');
                String k = URLDecoder.decode(i < 0 ? kv : kv.substring(0, i), StandardCharsets.UTF_8), v = i < 0 ? "" : URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8);
                form.put(k, v);
            }
            return form;
        }
        boolean json = ct.equals("application/json") || ct.endsWith("+json");
        if (json || ct.isEmpty()) {
            try { return JsonLite.parse(text); }
            catch (IllegalArgumentException e) {
                if (json) throw new OoaException("CR.BAD_JSON", "Request body is not valid JSON", "Send the request body as JSON (Content-Type: application/json), or name its real content type.");
            }
        }
        return text;
    }

    /** The challenge value to echo, when the trigger answers address checks and the payload carries one; else null. */
    public static String challenge(Map<String, Object> cfg, Object payload) {
        if (!(cfg.get("challenge_field") instanceof String field) || !(payload instanceof Map<?, ?> m)) return null;
        Object v = m.get(field);
        if (!(v instanceof String s) || s.isEmpty() || s.length() > MAX_CHALLENGE) return null;
        return s;
    }

    /** True when the trigger checks a signature instead of (or as well as) the X-OOA-Secret header. */
    public static boolean signed(Map<String, Object> cfg) { return cfg.get("signature_scheme") instanceof String; }

    /**
     * Checks the required header and the signature; refuses with CR.FORBIDDEN, saying which check failed but never a
     * secret. header looks a request header up by name (any case); secrets reads a vault reference.
     */
    public static void check(Map<String, Object> cfg, Function<String, String> header, byte[] raw, Function<String, String> secrets) {
        if (cfg.get("required_header") instanceof String name) {
            String got = header.apply(name);
            if (got == null || got.isEmpty()) throw denied("The request has no " + name + " header");
            if (cfg.get("required_header_value_ref") instanceof String ref && !constantEquals(got, secrets.apply(ref)))
                throw denied("The " + name + " header has the wrong value");
        }
        if (signed(cfg)) {
            String name = String.valueOf(cfg.get("signature_header"));
            String got = header.apply(name);
            if (got == null || got.isBlank()) throw denied("The request has no " + name + " signature header");
            String secret = secrets.apply(String.valueOf(cfg.get("signature_secret_ref")));
            if (!signatureOk(String.valueOf(cfg.get("signature_scheme")), secret.getBytes(StandardCharsets.UTF_8), raw == null ? new byte[0] : raw, got))
                throw denied("The signature in " + name + " does not match the body");
        }
    }

    /**
     * True when given is the HMAC (sha256 or sha1) of the body with the secret, compared in constant time. given may be
     * hex or base64, and may start with its scheme's name and = (as sha256=...).
     */
    public static boolean signatureOk(String scheme, byte[] secret, byte[] body, String given) {
        String alg = scheme.equals("sha1") ? "HmacSHA1" : "HmacSHA256";
        byte[] want;
        try {
            Mac mac = Mac.getInstance(alg);
            mac.init(new SecretKeySpec(secret, alg));
            want = mac.doFinal(body);
        } catch (GeneralSecurityException e) { return false; }
        String g = given.strip();
        int eq = g.indexOf('=');
        if (eq > 0 && eq < 12 && g.substring(0, eq).matches("[A-Za-z0-9]+")) g = g.substring(eq + 1);
        byte[] got = null;
        if (g.length() == want.length * 2 && HEX.matcher(g).matches()) got = HexFormat.of().parseHex(g.toLowerCase(Locale.ROOT));
        else try { got = Base64.getDecoder().decode(g); } catch (IllegalArgumentException ignored) { }
        return got != null && MessageDigest.isEqual(want, got);
    }

    /** The agent inputs the trigger maps from the payload (input name to the value at its path); missing values are left out. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> inputs(Map<String, Object> cfg, Object payload) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (!(cfg.get("input_map") instanceof Map<?, ?> m) || !(payload instanceof Map || payload instanceof List)) return out;
        ((Map<String, Object>) m).forEach((name, path) -> {
            Object v = JsonPath.compile(String.valueOf(path)).first(payload);
            if (v != null) out.put(name, v);
        });
        return out;
    }

    private static boolean constantEquals(String a, String b) {
        return b != null && MessageDigest.isEqual(a.getBytes(StandardCharsets.UTF_8), b.getBytes(StandardCharsets.UTF_8));
    }

    private static OoaException denied(String why) {
        return new OoaException("CR.FORBIDDEN", why, "Check the webhook's settings in Run > Triggers against what the sender sends.");
    }
}
