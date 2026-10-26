package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import java.io.InputStream;
import java.io.Reader;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.util.*;

/**
 * HTTPS for the Command Center, from {@code <data>/config/ooa-tls.properties}:
 * <pre>
 * keystore=C:\certs\ooa.pfx      a PKCS#12 file (.pfx/.p12) with the server certificate and its private key
 * password=enc:...               written by "ooa tls set-password" (encrypted with the vault key), or env:VARIABLE
 * </pre>
 * When the file exists, the Command Center serves HTTPS on its port (TLS 1.2 and 1.3). "ooa tls self-signed" makes a
 * certificate for tests; production uses one from the company's certificate authority.
 */
public final class TlsConfig {
    public final Path file;
    private final Properties p = new Properties();

    private TlsConfig(Path file) { this.file = file; }

    public static Path defaultFile(Path dataDir) { return dataDir.resolve("config").resolve("ooa-tls.properties"); }

    public static TlsConfig load(Path file) {
        TlsConfig c = new TlsConfig(file);
        try (Reader r = Files.newBufferedReader(file, StandardCharsets.UTF_8)) { c.p.load(r); }
        catch (Exception e) { throw new OoaException("CR.TLS_CONFIG", "Cannot read " + file + ": " + e.getMessage()); }
        if (c.keystore().toString().isEmpty() || !Files.exists(c.keystore()))
            throw new OoaException("CR.TLS_CONFIG", "The certificate file " + c.keystore() + " named in " + file + " does not exist", "Run: ooa tls configure --pfx FILE");
        return c;
    }

    public Path keystore() { String k = p.getProperty("keystore", "").trim(); return Path.of(k); }

    /** The keystore password, decrypted (enc:) or from an environment variable (env:); never stored in clear text. */
    public String password(Vault vault) {
        String v = p.getProperty("password", "").trim();
        if (v.startsWith("enc:")) return vault.decrypt(v.substring(4), "ooa-tls:password");
        if (v.startsWith("env:")) {
            String e = System.getenv(v.substring(4));
            if (e == null) throw new OoaException("CR.TLS_CONFIG", "The environment variable " + v.substring(4) + " is not set");
            return e;
        }
        throw new OoaException("CR.TLS_CONFIG", "The certificate password in " + file + " is missing or in clear text", "Run: ooa tls set-password");
    }

    public static void storePassword(Path file, Vault vault, String plain) { DbConfig.setValue(file, "password", "enc:" + vault.encrypt(plain, "ooa-tls:password")); }

    public static void setKeystore(Path file, Path keystore) { DbConfig.setValue(file, "keystore", keystore.toAbsolutePath().toString()); }

    KeyStore keyStore(Vault vault) {
        try (InputStream in = Files.newInputStream(keystore())) {
            KeyStore ks = KeyStore.getInstance("PKCS12");
            ks.load(in, password(vault).toCharArray());
            return ks;
        } catch (OoaException e) { throw e; }
        catch (Exception e) { throw new OoaException("CR.TLS_CONFIG", "Cannot open the certificate " + keystore() + ": " + e.getMessage(), "Check the file and its password (ooa tls set-password)"); }
    }

    /** The TLS context the server uses: the certificate and key from the keystore. */
    public SSLContext sslContext(Vault vault) {
        try {
            KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
            kmf.init(keyStore(vault), password(vault).toCharArray());
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(kmf.getKeyManagers(), null, null);
            return ctx;
        } catch (OoaException e) { throw e; }
        catch (Exception e) { throw new OoaException("CR.TLS_CONFIG", "The certificate cannot be used for HTTPS: " + e.getMessage()); }
    }

    /** The server certificate (no private key) in PEM form, for runners ("ooa agent register --ca") and browsers to trust. */
    public String certificatePem(Vault vault) {
        try {
            KeyStore ks = keyStore(vault);
            for (String alias : Collections.list(ks.aliases())) {
                if (!ks.isKeyEntry(alias)) continue;
                String b64 = Base64.getMimeEncoder(64, "\n".getBytes(StandardCharsets.US_ASCII)).encodeToString(ks.getCertificate(alias).getEncoded());
                return "-----BEGIN CERTIFICATE-----\n" + b64 + "\n-----END CERTIFICATE-----\n";
            }
            throw new OoaException("CR.TLS_CONFIG", "The file " + keystore() + " holds no private key");
        } catch (OoaException e) { throw e; }
        catch (Exception e) { throw new OoaException("CR.TLS_CONFIG", "Cannot read the certificate: " + e.getMessage()); }
    }

    /** Subject, alternative names and validity of the server certificate (the first key entry). */
    public Map<String, Object> describe(Vault vault) {
        try {
            KeyStore ks = keyStore(vault);
            for (String alias : Collections.list(ks.aliases())) {
                if (!ks.isKeyEntry(alias)) continue;
                X509Certificate c = (X509Certificate) ks.getCertificate(alias);
                List<String> names = new ArrayList<>();
                if (c.getSubjectAlternativeNames() != null) for (List<?> n : c.getSubjectAlternativeNames()) names.add(String.valueOf(n.get(1)));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("subject", c.getSubjectX500Principal().getName());
                m.put("issuer", c.getIssuerX500Principal().getName());
                m.put("names", names);
                m.put("valid_from", c.getNotBefore().toInstant().toString());
                m.put("valid_until", c.getNotAfter().toInstant().toString());
                m.put("self_signed", c.getSubjectX500Principal().equals(c.getIssuerX500Principal()));
                return m;
            }
            throw new OoaException("CR.TLS_CONFIG", "The file " + keystore() + " holds no private key");
        } catch (OoaException e) { throw e; }
        catch (Exception e) { throw new OoaException("CR.TLS_CONFIG", "Cannot read the certificate: " + e.getMessage()); }
    }
}
