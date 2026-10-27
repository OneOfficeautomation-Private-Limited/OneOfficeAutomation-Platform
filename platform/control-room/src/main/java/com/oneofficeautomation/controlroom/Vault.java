package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.PosixFilePermissions;
import java.util.Base64;

/**
 * Credential encryption with AES-256-GCM. The master key comes from OOA_MASTER_KEY (Base64, 32 bytes) or a key file
 * created on first start in the data folder. Each value is bound to its locker/credential/attribute name (GCM
 * associated data), so an encrypted value cannot be moved to another credential.
 */
public final class Vault {
    private volatile SecretKeySpec key;
    public final String keySource;
    /** The key file, or null when the key comes from OOA_MASTER_KEY. */
    final Path keyFile;

    private Vault(SecretKeySpec key, String keySource, Path keyFile) { this.key = key; this.keySource = keySource; this.keyFile = keyFile; }

    /** A vault with a given key: a SaaS tenant's own key, kept encrypted in the region's tenant catalog. */
    public static Vault ofKey(byte[] raw, String source) {
        if (raw.length != 32) throw new OoaException("CR.VAULT_KEY", "The vault master key must be 32 bytes");
        return new Vault(new SecretKeySpec(raw.clone(), "AES"), source, null);
    }

    /** A vault with a new random key (for rotating the master key). */
    static Vault fresh() {
        byte[] k = new byte[32];
        Security.RANDOM.nextBytes(k);
        return new Vault(new SecretKeySpec(k, "AES"), "new key", null);
    }

    String keyBase64() { return Base64.getEncoder().encodeToString(key.getEncoded()); }

    /** A short fingerprint of the key (SHA-256 of the key, first 12 hex digits), to tell keys apart without showing them. */
    String fingerprint() { return Security.sha256(keyBase64()).substring(0, 12); }

    /** Switches to another vault's key (after every value was re-encrypted with it). */
    synchronized void adopt(Vault next) { this.key = next.key; }

    public Vault(Path dataDir) {
        String env = System.getenv("OOA_MASTER_KEY"), external = KeySources.env.apply("OOA_MASTER_KEY_SOURCE");
        byte[] raw;
        if (env != null && !env.isBlank()) { raw = Base64.getDecoder().decode(env.trim()); keySource = "environment variable OOA_MASTER_KEY"; keyFile = null; }
        else if (external != null && !external.isBlank()) {
            // kept in a key store outside the Command Center (Azure Key Vault, AWS, Google) or typed in at start (S7)
            raw = KeySources.fetch(external);
            keySource = KeySources.describe(external);
            keyFile = null;
        }
        else {
            Path f = dataDir.resolve("master.key");
            keyFile = f;
            try {
                if (!Files.exists(f)) {
                    byte[] k = new byte[32];
                    Security.RANDOM.nextBytes(k);
                    Files.writeString(f, Base64.getEncoder().encodeToString(k));
                    try { Files.setPosixFilePermissions(f, PosixFilePermissions.fromString("rw-------")); } catch (UnsupportedOperationException ignored) { }
                }
                raw = Base64.getDecoder().decode(Files.readString(f).trim());
            } catch (IOException e) { throw new OoaException("CR.VAULT_KEY", "Cannot read or create the vault key: " + e.getMessage()); }
            keySource = "key file " + f + " (set OOA_MASTER_KEY in production and keep the key outside the data folder)";
        }
        if (raw.length != 32) throw new OoaException("CR.VAULT_KEY", "The vault master key must be 32 bytes (Base64)");
        key = new SecretKeySpec(raw, "AES");
    }

    public synchronized String encrypt(String plain, String boundTo) {
        try {
            byte[] iv = new byte[12];
            Security.RANDOM.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key, new GCMParameterSpec(128, iv));
            c.updateAAD(boundTo.getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(plain.getBytes(StandardCharsets.UTF_8));
            return "v1:" + Base64.getEncoder().encodeToString(iv) + ":" + Base64.getEncoder().encodeToString(ct);
        } catch (Exception e) { throw new OoaException("CR.VAULT_ERROR", "Encryption failed"); }
    }

    public synchronized String decrypt(String stored, String boundTo) {
        try {
            String[] p = stored.split(":");
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key, new GCMParameterSpec(128, Base64.getDecoder().decode(p[1])));
            c.updateAAD(boundTo.getBytes(StandardCharsets.UTF_8));
            return new String(c.doFinal(Base64.getDecoder().decode(p[2])), StandardCharsets.UTF_8);
        } catch (Exception e) {
            throw new OoaException("CR.VAULT_TAMPERED", "A stored credential could not be decrypted (wrong master key or modified data)", "Check OOA_MASTER_KEY is the key the vault was created with.");
        }
    }

}
