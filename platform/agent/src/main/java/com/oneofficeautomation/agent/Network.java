package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.OoaException;

import javax.net.ssl.*;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.KeyStore;
import java.security.cert.*;
import java.util.*;

/**
 * How the runner reaches the Command Center: the proxy and the certificates it trusts. Kept in the runner's config file.
 * <ul>
 * <li>{@code proxy}: {@code system} (default: the Windows proxy, including automatic configuration scripts and WPAD
 * through WinHTTP), {@code none}, or {@code http://host:port}. A Command Center on this computer is never reached
 * through a proxy.</li>
 * <li>{@code proxy_user} and {@code proxy_password}: for a proxy that asks for a user name and password (Basic). The
 * password is stored encrypted with Windows DPAPI for this computer ({@code dpapi:}), or read from an environment
 * variable ({@code env:NAME}).</li>
 * <li>Certificates: the JDK's authorities, on Windows also the Windows certificate store (so a company CA rolled out by
 * Group Policy is trusted), plus {@code ca}, a certificate file given with {@code --ca}.</li>
 * </ul>
 */
record Network(Path ca, String proxy, String proxyUser, String proxyPassword) {
    static final boolean WINDOWS = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");

    static {
        // the Windows proxy settings, and Basic authentication when tunnelling HTTPS through a proxy (off in the JDK by default)
        if (System.getProperty("java.net.useSystemProxies") == null) System.setProperty("java.net.useSystemProxies", "true");
        if (System.getProperty("jdk.http.auth.tunneling.disabledSchemes") == null) System.setProperty("jdk.http.auth.tunneling.disabledSchemes", "");
    }

    static final Network DEFAULT = new Network(null, "system", null, null);

    Network {
        proxy = proxy == null || proxy.isBlank() ? "system" : proxy.strip();
        if (!proxy.equals("system") && !proxy.equals("none") && !proxy.matches("https?://[A-Za-z0-9.\\-\\[\\]:]+:\\d{1,5}/?"))
            throw new OoaException("AGENT.BAD_PROXY", "The proxy is system, none or http://host:port, not " + proxy);
    }

    /** From the runner's config file. */
    static Network from(Map<String, Object> cfg) {
        return new Network(cfg.get("ca") == null ? null : Path.of((String) cfg.get("ca")), (String) cfg.get("proxy"), (String) cfg.get("proxy_user"), (String) cfg.get("proxy_password"));
    }

    /** Writes these settings into a config map (only what differs from the defaults). */
    void into(Map<String, Object> cfg) {
        cfg.remove("ca"); cfg.remove("proxy"); cfg.remove("proxy_user"); cfg.remove("proxy_password");
        if (ca != null) cfg.put("ca", ca.toString());
        if (!proxy.equals("system")) cfg.put("proxy", proxy);
        if (proxyUser != null && !proxyUser.isBlank()) { cfg.put("proxy_user", proxyUser); if (proxyPassword != null) cfg.put("proxy_password", proxyPassword); }
    }

    /** Sets the proxy, the proxy sign-in and the trusted certificates on a client for this Command Center address. */
    void apply(HttpClient.Builder b, String baseUrl) {
        b.sslContext(trust(ca));
        URI target = URI.create(baseUrl);
        boolean local = target.getHost() != null && (target.getHost().equals("localhost") || target.getHost().startsWith("127.") || target.getHost().equals("[::1]"));
        if (local || proxy.equals("none")) b.proxy(HttpClient.Builder.NO_PROXY);
        else if (proxy.equals("system")) b.proxy(ProxySelector.getDefault());
        else { URI p = URI.create(proxy); b.proxy(ProxySelector.of(new InetSocketAddress(p.getHost(), p.getPort()))); }
        if (proxyUser != null && !proxyUser.isBlank() && !local) {
            char[] pw = password(proxyPassword);
            b.authenticator(new Authenticator() {
                @Override protected PasswordAuthentication getPasswordAuthentication() {
                    return getRequestorType() == RequestorType.PROXY ? new PasswordAuthentication(proxyUser, pw) : null;
                }
            });
        }
    }

    /** The proxy password: decrypted (dpapi:) or from an environment variable (env:). */
    static char[] password(String stored) {
        if (stored == null) return new char[0];
        if (stored.startsWith("env:")) { String v = System.getenv(stored.substring(4)); if (v == null) throw new OoaException("AGENT.PROXY_PASSWORD", "The environment variable " + stored.substring(4) + " is not set"); return v.toCharArray(); }
        if (stored.startsWith("dpapi:")) {
            try { return new String(com.sun.jna.platform.win32.Crypt32Util.cryptUnprotectData(Base64.getDecoder().decode(stored.substring(6))), StandardCharsets.UTF_8).toCharArray(); }
            catch (Throwable e) { throw new OoaException("AGENT.PROXY_PASSWORD", "The proxy password cannot be read on this computer", "Set it again: ooa agent network --proxy-user USER --proxy-password-env VAR"); }
        }
        throw new OoaException("AGENT.PROXY_PASSWORD", "The proxy password is stored in an unknown form");
    }

    /** Encrypts a proxy password for this computer (Windows DPAPI, machine scope: a runner service and its installer both read it). */
    static String protect(String plain) {
        if (!WINDOWS) throw new OoaException("AGENT.PROXY_PASSWORD", "Stored proxy passwords need Windows", "Use --proxy-password-env with the variable set for the runner instead.");
        final int CRYPTPROTECT_LOCAL_MACHINE = 0x4, CRYPTPROTECT_UI_FORBIDDEN = 0x1;
        byte[] enc = com.sun.jna.platform.win32.Crypt32Util.cryptProtectData(plain.getBytes(StandardCharsets.UTF_8), null, CRYPTPROTECT_LOCAL_MACHINE | CRYPTPROTECT_UI_FORBIDDEN, "ONEOFFICEAUTOMATION proxy", null);
        return "dpapi:" + Base64.getEncoder().encodeToString(enc);
    }

    /** A TLS context trusting the JDK's authorities, the Windows certificate store and {@code ca}. */
    static SSLContext trust(Path ca) {
        try {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int n = 0;
            TrustManagerFactory sys = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            sys.init((KeyStore) null);
            for (TrustManager tm : sys.getTrustManagers())
                if (tm instanceof X509TrustManager x) for (X509Certificate c : x.getAcceptedIssuers()) ks.setCertificateEntry("jdk" + n++, c);
            if (WINDOWS) for (String store : List.of("Windows-ROOT", "Windows-ROOT-LOCALMACHINE")) {
                try {
                    KeyStore w = KeyStore.getInstance(store);
                    w.load(null, null);
                    for (String alias : Collections.list(w.aliases())) if (w.getCertificate(alias) != null) ks.setCertificateEntry("win" + n++, w.getCertificate(alias));
                } catch (Exception ignored) { /* this store type is not offered by this Java */ }
            }
            if (ca != null) try (var in = Files.newInputStream(ca)) {
                for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) ks.setCertificateEntry("ooa" + n++, c);
            }
            TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
            tmf.init(ks);
            SSLContext ctx = SSLContext.getInstance("TLS");
            ctx.init(null, tmf.getTrustManagers(), null);
            return ctx;
        } catch (OoaException e) { throw e; }
        catch (Exception e) {
            throw new OoaException("AGENT.CA", "Cannot use the certificate file " + ca + ": " + e.getMessage(), "Export it on the Command Center with: ooa tls export-cert --out ooa.cer");
        }
    }
}
