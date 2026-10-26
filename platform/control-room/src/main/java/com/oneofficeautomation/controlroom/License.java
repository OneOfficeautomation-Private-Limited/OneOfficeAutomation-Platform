package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.time.LocalDate;
import java.util.*;

/**
 * A ONEOFFICEAUTOMATION license file: the license terms as JSON (customer, dates, edition, seats per license type),
 * signed with the vendor's Ed25519 key. The file holds {"format":"ooa-license-1","payload":"<Base64 JSON>",
 * "signature":"<Base64>"}, so the signed bytes never change shape. The vendor's public key is built into the product;
 * the private key stays with the vendor and signs licenses with "ooa license issue".
 * {@code installId} ties the license to one Command Center (its install id, shown in Administration > Licenses); an
 * empty one lets the file work on any installation.
 */
public record License(String id, String customer, String edition, LocalDate issued, LocalDate expires, int graceDays, Map<String, Integer> seats, String notes, String installId) {

    public License { installId = installId == null ? "" : installId.trim(); }

    /** A license for any installation. */
    public License(String id, String customer, String edition, LocalDate issued, LocalDate expires, int graceDays, Map<String, Integer> seats, String notes) {
        this(id, customer, edition, issued, expires, graceDays, seats, notes, "");
    }

    /** Seat types: three per person, one per runner. */
    public static final List<String> USER_TYPES = List.of("developer", "citizen_developer", "attended_runner");
    public static final List<String> DEVICE_TYPES = List.of("unattended_runner");
    public static final List<String> TYPES = List.of("developer", "citizen_developer", "attended_runner", "unattended_runner");

    /** The vendor's public key (Ed25519, X.509, Base64) that every license must be signed with. */
    public static final String VENDOR_PUBLIC_KEY = "MCowBQYDK2VwAyEA5izC9CueL5Ke+I+88gmudgf4fs1wlxELhf9qRZ7J+uI=";

    static PublicKey vendorKey() { return publicKey(VENDOR_PUBLIC_KEY); }

    /** Reads and checks a license file against a public key: format, signature, then the terms. */
    public static License verify(String fileText, PublicKey key) {
        Map<String, Object> f;
        try { f = asMap(JsonLite.parse(fileText.trim())); } catch (RuntimeException e) { throw bad("The file is not a ONEOFFICEAUTOMATION license"); }
        if (!"ooa-license-1".equals(f.get("format")) || !(f.get("payload") instanceof String p) || !(f.get("signature") instanceof String sig)) throw bad("The file is not a ONEOFFICEAUTOMATION license");
        byte[] payload;
        try {
            payload = Base64.getDecoder().decode(p);
            Signature v = Signature.getInstance("Ed25519");
            v.initVerify(key);
            v.update(payload);
            if (!v.verify(Base64.getDecoder().decode(sig))) throw bad("The license signature is not valid: the file was changed or not issued by ONEOFFICEAUTOMATION");
        } catch (OoaException e) { throw e; }
        catch (Exception e) { throw bad("The license signature is not valid: the file was changed or not issued by ONEOFFICEAUTOMATION"); }
        return fromTerms(asMap(JsonLite.parse(new String(payload, StandardCharsets.UTF_8))));
    }

    static License fromTerms(Map<String, Object> t) {
        Map<String, Integer> seats = new LinkedHashMap<>();
        Map<String, Object> s = t.get("seats") instanceof Map<?, ?> m ? asMap(m) : Map.of();
        for (String type : TYPES) seats.put(type, s.get(type) instanceof Number n ? Math.max(0, n.intValue()) : 0);
        try {
            return new License(String.valueOf(t.get("id")), String.valueOf(t.get("customer")), String.valueOf(t.getOrDefault("edition", "Enterprise")),
                LocalDate.parse(String.valueOf(t.get("issued"))), LocalDate.parse(String.valueOf(t.get("expires"))),
                t.get("grace_days") instanceof Number g ? Math.max(0, Math.min(90, g.intValue())) : 14, seats, t.get("notes") == null ? "" : String.valueOf(t.get("notes")),
                t.get("install_id") == null ? "" : String.valueOf(t.get("install_id")));
        } catch (RuntimeException e) { throw bad("The license terms are incomplete (id, customer, issued and expires are needed)"); }
    }

    Map<String, Object> terms() {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id); t.put("customer", customer); t.put("edition", edition); t.put("issued", issued.toString()); t.put("expires", expires.toString());
        t.put("grace_days", graceDays); t.put("seats", new LinkedHashMap<>(seats));
        if (!notes.isEmpty()) t.put("notes", notes);
        if (!installId.isEmpty()) t.put("install_id", installId);
        return t;
    }

    /** Signs the terms with the vendor's private key and returns the license file text. */
    public String sign(PrivateKey key) {
        try {
            byte[] payload = JsonLite.write(terms()).getBytes(StandardCharsets.UTF_8);
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(payload);
            Map<String, Object> f = new LinkedHashMap<>();
            f.put("format", "ooa-license-1");
            f.put("payload", Base64.getEncoder().encodeToString(payload));
            f.put("signature", Base64.getEncoder().encodeToString(s.sign()));
            return JsonLite.write(f);
        } catch (GeneralSecurityException e) { throw new OoaException("LICENSE.SIGN_FAILED", "Signing failed: " + e.getMessage()); }
    }

    /** A new Ed25519 key pair: {private (PKCS#8, Base64), public (X.509, Base64)}. */
    public static Map<String, String> newKeyPair() {
        try {
            KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
            return Map.of("private", Base64.getEncoder().encodeToString(kp.getPrivate().getEncoded()), "public", Base64.getEncoder().encodeToString(kp.getPublic().getEncoded()));
        } catch (GeneralSecurityException e) { throw new OoaException("LICENSE.KEY_FAILED", "Key generation failed: " + e.getMessage()); }
    }

    public static PrivateKey privateKey(String base64) {
        try { return KeyFactory.getInstance("Ed25519").generatePrivate(new PKCS8EncodedKeySpec(Base64.getDecoder().decode(base64.trim()))); }
        catch (Exception e) { throw new OoaException("LICENSE.BAD_KEY", "Not an Ed25519 private key"); }
    }

    public static PublicKey publicKey(String base64) {
        try { return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(base64.trim()))); }
        catch (Exception e) { throw new OoaException("LICENSE.BAD_KEY", "Not an Ed25519 public key"); }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> asMap(Object o) { if (o instanceof Map<?, ?> m) return (Map<String, Object>) m; throw new IllegalArgumentException(); }

    private static OoaException bad(String msg) { return new OoaException("LICENSE.INVALID", msg, "Install the license file exactly as ONEOFFICEAUTOMATION sent it"); }
}
