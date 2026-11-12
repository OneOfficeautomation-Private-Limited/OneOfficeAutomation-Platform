package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.ByteArrayOutputStream;
import java.math.BigInteger;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.security.interfaces.RSAPrivateCrtKey;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.RSAPublicKeySpec;
import java.util.*;

/**
 * K1 (command review, batch 10): signs the JSON Web Tokens that the renewing sign-ins need (RS256): a Google service
 * account's or DocuSign's JWT grant, an Okta service app's client assertion, a GitHub App's token request, and
 * Snowflake's key-pair token. The private key comes from the vault as PEM (PKCS#8 "PRIVATE KEY", or PKCS#1 "RSA PRIVATE
 * KEY" as GitHub gives it) or as a Google service-account JSON file.
 */
public final class JwtSigner {
    private JwtSigner() {}

    /** A key read from the vault, with the service account's email when it came from Google's JSON file. */
    public record Key(PrivateKey privateKey, String clientEmail) {}

    public static Key key(String text) {
        String t = text == null ? "" : text.strip();
        String email = null;
        if (t.startsWith("{")) {
            Object j;
            try { j = JsonLite.parse(t); } catch (RuntimeException e) { throw bad("the key file is not valid JSON"); }
            if (!(j instanceof Map<?, ?> m) || !(m.get("private_key") instanceof String pk)) throw bad("the JSON has no private_key (use a Google service-account key file)");
            email = m.get("client_email") instanceof String e ? e : null;
            t = pk.strip();
        }
        boolean pkcs1 = t.contains("BEGIN RSA PRIVATE KEY");
        if (!t.contains("PRIVATE KEY")) throw bad("the key is not a PEM private key");
        if (t.contains("ENCRYPTED")) throw bad("the private key is encrypted; store it without a passphrase in the vault");
        String b64 = t.replaceAll("-----[A-Z ]+-----", "").replaceAll("\\s", "");
        byte[] der;
        try { der = Base64.getDecoder().decode(b64); } catch (IllegalArgumentException e) { throw bad("the private key is not valid Base64"); }
        if (pkcs1) der = pkcs8(der);
        try { return new Key(KeyFactory.getInstance("RSA").generatePrivate(new PKCS8EncodedKeySpec(der)), email); }
        catch (Exception e) { throw bad("the private key cannot be read (" + e.getMessage() + "); an RSA key is needed"); }
    }

    /** header.claims.signature with RS256. */
    public static String sign(Map<String, Object> claims, PrivateKey key, String keyId) {
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("alg", "RS256");
        header.put("typ", "JWT");
        if (keyId != null && !keyId.isBlank()) header.put("kid", keyId);
        Base64.Encoder b64 = Base64.getUrlEncoder().withoutPadding();
        String body = b64.encodeToString(JsonLite.write(header).getBytes(StandardCharsets.UTF_8)) + "." + b64.encodeToString(JsonLite.write(claims).getBytes(StandardCharsets.UTF_8));
        try {
            Signature s = Signature.getInstance("SHA256withRSA");
            s.initSign(key);
            s.update(body.getBytes(StandardCharsets.US_ASCII));
            return body + "." + b64.encodeToString(s.sign());
        } catch (GeneralSecurityException e) { throw new OoaException("CONNECTION.SIGN_IN_FAILED", "Cannot sign the sign-in request: " + e.getMessage()); }
    }

    /** SHA256:base64 of the public key (DER), as Snowflake names a key pair; computed from the private key. */
    public static String publicKeyFingerprint(PrivateKey key) {
        if (!(key instanceof RSAPrivateCrtKey k)) throw bad("the key does not hold its public part");
        try {
            PublicKey pub = KeyFactory.getInstance("RSA").generatePublic(new RSAPublicKeySpec(k.getModulus(), k.getPublicExponent()));
            return "SHA256:" + Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-256").digest(pub.getEncoded()));
        } catch (GeneralSecurityException e) { throw bad("the public key cannot be worked out: " + e.getMessage()); }
    }

    /** A PKCS#1 RSA key wrapped as PKCS#8 (SEQUENCE { 0, { rsaEncryption, NULL }, OCTET STRING key }). */
    static byte[] pkcs8(byte[] pkcs1) {
        byte[] algorithm = {0x30, 0x0d, 0x06, 0x09, 0x2a, (byte) 0x86, 0x48, (byte) 0x86, (byte) 0xf7, 0x0d, 0x01, 0x01, 0x01, 0x05, 0x00};
        ByteArrayOutputStream inner = new ByteArrayOutputStream();
        inner.writeBytes(new byte[]{0x02, 0x01, 0x00});
        inner.writeBytes(algorithm);
        inner.write(0x04);
        inner.writeBytes(length(pkcs1.length));
        inner.writeBytes(pkcs1);
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        out.write(0x30);
        out.writeBytes(length(inner.size()));
        out.writeBytes(inner.toByteArray());
        return out.toByteArray();
    }

    private static byte[] length(int n) {
        if (n < 0x80) return new byte[]{(byte) n};
        byte[] b = BigInteger.valueOf(n).toByteArray();
        int start = b[0] == 0 ? 1 : 0;
        byte[] out = new byte[b.length - start + 1];
        out[0] = (byte) (0x80 | (b.length - start));
        System.arraycopy(b, start, out, 1, b.length - start);
        return out;
    }

    private static OoaException bad(String what) {
        return new OoaException("CONNECTION.SIGN_IN_INCOMPLETE", "The sign-in's private key: " + what, "Put the key (PEM, or Google's JSON key file) in the vault and pick it in the connection.");
    }
}
