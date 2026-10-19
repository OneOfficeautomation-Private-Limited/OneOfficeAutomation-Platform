package com.oneofficeautomation.sdk;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.security.*;
import java.util.Base64;
import java.util.HexFormat;
import java.util.Map;

/**
 * Package signatures (FR-PKG-003). The signed digest covers the canonical manifest and the package
 * class bytes, so changing either the declared contract or the code invalidates the signature.
 * Ed25519 (JDK built-in) here; release builds add Sigstore cosign + jarsigner on the JAR.
 */
public final class PackageSigning {
    private PackageSigning() {}

    public record PublisherKey(String publisher, PrivateKey privateKey, PublicKey publicKey) {
        public static PublisherKey generate(String publisher) {
            try {
                KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
                return new PublisherKey(publisher, kp.getPrivate(), kp.getPublic());
            } catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
        }
    }

    public record Signature(String publisher, String digest, String signature) {
        public Map<String, Object> toMap() { return Map.of("publisher", publisher, "digest", digest, "signature", signature); }
    }

    public static String digest(Class<?> cls) {
        try {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            md.update(JsonLite.canonical(PackageIntrospector.manifest(cls)).getBytes(StandardCharsets.UTF_8));
            String res = "/" + cls.getName().replace('.', '/') + ".class";
            try (InputStream in = cls.getResourceAsStream(res)) {
                if (in != null) md.update(MessageDigest.getInstance("SHA-256").digest(in.readAllBytes()));
            }
            return HexFormat.of().formatHex(md.digest());
        } catch (NoSuchAlgorithmException | IOException e) { throw new IllegalStateException(e); }
    }

    public static Signature sign(Class<?> cls, PublisherKey key) {
        try {
            String d = digest(cls);
            java.security.Signature s = java.security.Signature.getInstance("Ed25519");
            s.initSign(key.privateKey());
            s.update(d.getBytes(StandardCharsets.UTF_8));
            return new Signature(key.publisher(), d, Base64.getEncoder().encodeToString(s.sign()));
        } catch (GeneralSecurityException e) { throw new IllegalStateException(e); }
    }

    public static boolean verify(String digest, String signatureB64, PublicKey key) {
        try {
            java.security.Signature s = java.security.Signature.getInstance("Ed25519");
            s.initVerify(key);
            s.update(digest.getBytes(StandardCharsets.UTF_8));
            return s.verify(Base64.getDecoder().decode(signatureB64));
        } catch (GeneralSecurityException | IllegalArgumentException e) { return false; }
    }
}
