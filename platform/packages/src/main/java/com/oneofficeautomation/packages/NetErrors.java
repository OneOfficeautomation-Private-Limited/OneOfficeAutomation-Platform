package com.oneofficeautomation.packages;

/**
 * Plain-language reasons for network failures. Java's own messages were shown as they are, e.g. "null" for a refused
 * HTTP connection or "java.net.ConnectException: Connection refused: getsockopt" for SFTP.
 */
public final class NetErrors {
    private NetErrors() {}

    /** Why a connection to {@code target} failed, without Java class names; never "null". */
    public static String reason(Throwable e, String target) {
        Throwable root = e;
        for (int i = 0; i < 10 && root.getCause() != null && root.getCause() != root; i++) root = root.getCause();
        String at = target == null || target.isBlank() ? "" : " " + target;
        if (has(e, java.net.ConnectException.class)) return "the connection to" + at + " was refused (is the server running and the port open?)";
        if (has(e, java.net.UnknownHostException.class)) return "the host name" + at + " is unknown (check the address and DNS)";
        if (has(e, java.net.http.HttpConnectTimeoutException.class) || has(e, java.net.SocketTimeoutException.class))
            return "no answer from" + at + " in time (check the address, firewall and proxy)";
        if (has(e, java.net.NoRouteToHostException.class)) return "there is no network route to" + at;
        if (has(e, javax.net.ssl.SSLHandshakeException.class)) return "the secure (TLS) connection to" + at + " failed: " + clean(root.getMessage())
                + " (check the certificate or the insecure TLS option)";
        if (has(e, javax.net.ssl.SSLException.class)) return "the secure (TLS) connection to" + at + " failed: " + clean(root.getMessage());
        String m = clean(e.getMessage());
        if (m.isEmpty()) m = clean(root.getMessage());
        if (m.isEmpty()) m = root.getClass().getSimpleName().replaceAll("([a-z])([A-Z])", "$1 $2").toLowerCase(java.util.Locale.ROOT);
        return m;
    }

    private static boolean has(Throwable e, Class<? extends Throwable> type) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) if (type.isInstance(t)) return true;
        return false;
    }

    /** A message without "java.net.ConnectException:"-style prefixes and socket-call suffixes. */
    public static String clean(String m) {
        if (m == null || m.equals("null")) return "";
        return m.replaceAll("(?:[a-z_][\\w]*\\.)+[A-Z]\\w*(?:Exception|Error):\\s*", "").replaceAll(":\\s*(getsockopt|connect)$", "").strip();
    }
}
