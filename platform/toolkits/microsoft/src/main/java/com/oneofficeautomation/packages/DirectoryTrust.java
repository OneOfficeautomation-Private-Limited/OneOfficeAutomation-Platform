package com.oneofficeautomation.packages;

import javax.net.SocketFactory;
import javax.net.ssl.*;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.Socket;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyStore;
import java.security.cert.*;
import java.util.*;

/**
 * K6 (command review, batch 10): the certificates a directory connection (LDAPS or StartTLS) trusts. Domain controllers
 * usually have certificates from the company's own CA, which Java's own list does not know; AA tells builders to put it
 * in the Windows certificate store. So a connection trusts Java's list, the Windows "Trusted Root" store on Windows,
 * and the CA certificates of an optional file (PEM or DER).
 *
 * <p>JNDI makes its sockets with a factory class it names ({@code java.naming.ldap.factory.socket}) and calls
 * {@link #getDefault()} on; the CA file of the connection being made is passed in {@link #CA_FILE} on that thread.
 */
public final class DirectoryTrust extends SSLSocketFactory {
    /** The CA file for the connection this thread is opening (null: none). */
    static final ThreadLocal<Path> CA_FILE = new ThreadLocal<>();
    /** Whether the Windows store is read (tests turn it off to show that a CA file is needed). */
    static volatile boolean useWindowsStore = true;

    private final SSLSocketFactory f;

    private DirectoryTrust(SSLSocketFactory f) { this.f = f; }

    /** Called by JNDI for each new connection. */
    public static SocketFactory getDefault() {
        try { return new DirectoryTrust(context(CA_FILE.get()).getSocketFactory()); }
        catch (Exception e) { throw new IllegalStateException("Cannot set up the trusted certificates: " + e.getMessage(), e); }
    }

    /** A TLS context that trusts Java's list, the Windows store (on Windows) and the certificates in the file. */
    static SSLContext context(Path caFile) throws Exception {
        List<X509TrustManager> managers = new ArrayList<>();
        managers.add(manager(null));
        if (useWindowsStore && System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows")) {
            try { KeyStore w = KeyStore.getInstance("Windows-ROOT"); w.load(null, null); managers.add(manager(w)); }
            catch (Exception ignored) { /* no Windows store here: Java's list and the file */ }
        }
        if (caFile != null) {
            KeyStore ks = KeyStore.getInstance(KeyStore.getDefaultType());
            ks.load(null, null);
            int n = 0;
            try (InputStream in = Files.newInputStream(caFile)) {
                for (Certificate c : CertificateFactory.getInstance("X.509").generateCertificates(in)) ks.setCertificateEntry("ca" + n++, c);
            }
            if (n == 0) throw new IOException("No certificate in " + caFile);
            managers.add(manager(ks));
        }
        SSLContext ctx = SSLContext.getInstance("TLS");
        ctx.init(null, new TrustManager[]{new AnyOf(managers)}, null);
        return ctx;
    }

    private static X509TrustManager manager(KeyStore ks) throws Exception {
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(ks);
        for (TrustManager t : tmf.getTrustManagers()) if (t instanceof X509TrustManager x) return x;
        throw new IllegalStateException("No X.509 trust manager");
    }

    /** Trusts a server when any of its managers does. */
    private record AnyOf(List<X509TrustManager> managers) implements X509TrustManager {
        public void checkClientTrusted(X509Certificate[] chain, String auth) throws CertificateException { managers.get(0).checkClientTrusted(chain, auth); }
        public void checkServerTrusted(X509Certificate[] chain, String auth) throws CertificateException {
            CertificateException last = null;
            for (X509TrustManager m : managers) {
                try { m.checkServerTrusted(chain, auth); return; } catch (CertificateException e) { last = e; }
            }
            throw last == null ? new CertificateException("Not trusted") : last;
        }
        public X509Certificate[] getAcceptedIssuers() {
            List<X509Certificate> all = new ArrayList<>();
            for (X509TrustManager m : managers) all.addAll(Arrays.asList(m.getAcceptedIssuers()));
            return all.toArray(new X509Certificate[0]);
        }
    }

    @Override public String[] getDefaultCipherSuites() { return f.getDefaultCipherSuites(); }
    @Override public String[] getSupportedCipherSuites() { return f.getSupportedCipherSuites(); }
    @Override public Socket createSocket() throws IOException { return f.createSocket(); }
    @Override public Socket createSocket(Socket s, String host, int port, boolean autoClose) throws IOException { return f.createSocket(s, host, port, autoClose); }
    @Override public Socket createSocket(String host, int port) throws IOException { return f.createSocket(host, port); }
    @Override public Socket createSocket(String host, int port, InetAddress local, int localPort) throws IOException { return f.createSocket(host, port, local, localPort); }
    @Override public Socket createSocket(InetAddress host, int port) throws IOException { return f.createSocket(host, port); }
    @Override public Socket createSocket(InetAddress host, int port, InetAddress local, int localPort) throws IOException { return f.createSocket(host, port, local, localPort); }
}
