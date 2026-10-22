package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;

import javax.net.ssl.*;
import java.io.InputStream;
import java.net.*;
import java.net.http.HttpClient;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.X509Certificate;
import java.time.Duration;

/**
 * Connection options shared by the REST and SOAP toolkits: proxy, client certificate (mutual TLS), accepting any server
 * certificate (test systems only), cookies and redirects. Without options the toolkit's shared client is used.
 */
record HttpOptions(boolean insecure, String proxy, String proxyUser, Object proxyPassword, String clientCertificate, Object clientCertificatePassword,
                   boolean cookies, boolean followRedirects) {

    static final String COOKIE_SESSION = "ooa.http.cookies";
    static final HttpOptions NONE = new HttpOptions(false, null, null, null, null, null, false, true);

    boolean isDefault() { return equals(NONE); }

    /** host:port of a proxy server; an optional http:// prefix is accepted. */
    static InetSocketAddress proxyAddress(String proxy) {
        String p = proxy.strip().replaceFirst("(?i)^https?://", "").replaceFirst("/+$", "");
        int colon = p.lastIndexOf(':');
        if (colon <= 0 || !p.substring(colon + 1).matches("\\d{1,5}"))
            throw new ActionException("HTTP.BAD_PROXY", "Invalid proxy '" + proxy + "'", "Use host:port, e.g. proxy.example.com:8080.");
        int port = Integer.parseInt(p.substring(colon + 1));
        if (port < 1 || port > 65535) throw new ActionException("HTTP.BAD_PROXY", "Invalid proxy port in '" + proxy + "'", "Use a port between 1 and 65535.");
        return InetSocketAddress.createUnresolved(p.substring(0, colon), port);
    }

    HttpClient client(ActionContext ctx, HttpClient shared) {
        if (isDefault()) return shared;
        HttpClient.Builder b = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(20))
                .followRedirects(followRedirects ? HttpClient.Redirect.NORMAL : HttpClient.Redirect.NEVER);
        if (proxy != null && !proxy.isBlank()) {
            b.proxy(ProxySelector.of(proxyAddress(proxy)));
            if (proxyUser != null && !proxyUser.isBlank()) {
                char[] pw = proxyPassword instanceof SecureString s ? s.reveal().toCharArray() : proxyPassword == null ? new char[0] : String.valueOf(proxyPassword).toCharArray();
                b.authenticator(new Authenticator() {
                    @Override protected PasswordAuthentication getPasswordAuthentication() {
                        return getRequestorType() == RequestorType.PROXY ? new PasswordAuthentication(proxyUser, pw) : null;
                    }
                });
            }
        }
        if (cookies) {
            Object jar = ctx.sessions().computeIfAbsent(COOKIE_SESSION, k -> new CookieManager(null, CookiePolicy.ACCEPT_ORIGINAL_SERVER));
            b.cookieHandler((CookieHandler) jar);
        }
        if (insecure || (clientCertificate != null && !clientCertificate.isBlank())) {
            if (insecure) ctx.warn("Server certificates are not checked for this request; use this only with test systems.");
            b.sslContext(sslContext(ctx));
        }
        return b.build();
    }

    private SSLContext sslContext(ActionContext ctx) {
        try {
            KeyManager[] keys = null;
            if (clientCertificate != null && !clientCertificate.isBlank()) {
                Path p = ctx.resolvePath(clientCertificate);
                if (!Files.isRegularFile(p)) throw new ActionException("HTTP.CERTIFICATE_NOT_FOUND", "Client certificate not found: " + p, "Give a .p12 or .pfx file.");
                char[] pw = clientCertificatePassword instanceof SecureString s ? s.reveal().toCharArray() : new char[0];
                KeyStore ks = KeyStore.getInstance("PKCS12");
                try (InputStream in = Files.newInputStream(p)) { ks.load(in, pw); }
                catch (java.io.IOException e) { throw new ActionException("HTTP.BAD_CERTIFICATE", "Cannot open client certificate " + p.getFileName() + ": " + e.getMessage(), "Check the file is PKCS#12 (.p12/.pfx) and the password is right."); }
                KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
                kmf.init(ks, pw);
                keys = kmf.getKeyManagers();
            }
            SSLContext c = SSLContext.getInstance("TLS");
            c.init(keys, insecure ? new TrustManager[]{TRUST_ALL} : null, null);
            return c;
        } catch (java.security.GeneralSecurityException e) {
            throw new ActionException("HTTP.TLS_SETUP_FAILED", "Cannot set up TLS: " + e.getMessage(), "Check the client certificate and its password.");
        }
    }

    /** Accepts every server certificate and host name; only used when the agent asks for it explicitly. */
    private static final X509ExtendedTrustManager TRUST_ALL = new X509ExtendedTrustManager() {
        public void checkClientTrusted(X509Certificate[] c, String a) { }
        public void checkServerTrusted(X509Certificate[] c, String a) { }
        public void checkClientTrusted(X509Certificate[] c, String a, Socket s) { }
        public void checkServerTrusted(X509Certificate[] c, String a, Socket s) { }
        public void checkClientTrusted(X509Certificate[] c, String a, SSLEngine e) { }
        public void checkServerTrusted(X509Certificate[] c, String a, SSLEngine e) { }
        public X509Certificate[] getAcceptedIssuers() { return new X509Certificate[0]; }
    };
}
