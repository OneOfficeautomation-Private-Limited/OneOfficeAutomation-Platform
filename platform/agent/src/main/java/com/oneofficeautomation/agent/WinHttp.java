package com.oneofficeautomation.agent;

import com.oneofficeautomation.sdk.OoaException;
import com.sun.jna.Memory;
import com.sun.jna.Native;
import com.sun.jna.Pointer;
import com.sun.jna.WString;
import com.sun.jna.ptr.IntByReference;
import com.sun.jna.win32.StdCallLibrary;
import com.sun.jna.win32.W32APIOptions;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.net.URI;
import java.util.Map;

/**
 * The runner's requests through Windows' own web stack (WinHTTP), for proxies that accept only Windows sign-in (NTLM or
 * Kerberos/Negotiate): 14 Nov 2026, round 4 item B2. Windows signs in to the proxy as the runner's Windows user, as
 * Edge and Windows Update do; no password is stored. Proxy setting {@code windows} uses this computer's proxy settings
 * (including automatic configuration and WPAD); {@code windows:http://host:port} names one. Certificates are Windows'
 * own (the company CA from Group Policy); a self-signed One CommandCenter certificate must be in the Windows store.
 */
final class WinHttp {
    private WinHttp() {}

    interface Lib extends StdCallLibrary {
        Lib I = Network.WINDOWS ? Native.load("winhttp", Lib.class, W32APIOptions.UNICODE_OPTIONS) : null;
        Pointer WinHttpOpen(WString agent, int accessType, WString proxy, WString bypass, int flags);
        Pointer WinHttpConnect(Pointer session, WString server, short port, int reserved);
        Pointer WinHttpOpenRequest(Pointer connect, WString verb, WString objectName, WString version, WString referrer, Pointer acceptTypes, int flags);
        boolean WinHttpSetTimeouts(Pointer h, int resolve, int connect, int send, int receive);
        boolean WinHttpSetOption(Pointer h, int option, IntByReference buffer, int length);
        boolean WinHttpSendRequest(Pointer request, WString headers, int headersLength, Pointer optional, int optionalLength, int totalLength, Pointer context);
        boolean WinHttpReceiveResponse(Pointer request, Pointer reserved);
        boolean WinHttpQueryHeaders(Pointer request, int infoLevel, WString name, IntByReference buffer, IntByReference bufferLength, IntByReference index);
        boolean WinHttpQueryDataAvailable(Pointer request, IntByReference available);
        boolean WinHttpReadData(Pointer request, Pointer buffer, int toRead, IntByReference read);
        boolean WinHttpQueryAuthSchemes(Pointer request, IntByReference supported, IntByReference first, IntByReference target);
        boolean WinHttpSetCredentials(Pointer request, int target, int scheme, WString user, WString password, Pointer params);
        boolean WinHttpCloseHandle(Pointer handle);
    }

    static final int ACCESS_AUTOMATIC = 4, ACCESS_DEFAULT = 0, ACCESS_NAMED = 3, FLAG_SECURE = 0x00800000;
    static final int QUERY_STATUS_CODE = 19 | 0x20000000, OPTION_AUTOLOGON_POLICY = 77, AUTOLOGON_LOW = 0;
    static final int AUTH_BASIC = 0x1, AUTH_NTLM = 0x2, AUTH_NEGOTIATE = 0x10, TARGET_SERVER = 0, TARGET_PROXY = 1;

    record Response(int status, byte[] body) {}

    /** The proxy part of a setting {@code windows} or {@code windows:http://host:port}; null for this computer's settings. */
    static String namedProxy(String setting) {
        return setting != null && setting.startsWith("windows:") ? setting.substring("windows:".length()).replaceFirst("^https?://", "").replaceAll("/+$", "") : null;
    }

    /** One request; a 407 (or 401) from a proxy asking for Windows sign-in is answered with the runner user's own credentials. */
    static Response send(String proxySetting, String method, String url, Map<String, String> headers, byte[] body, int timeoutMs) throws IOException {
        if (Lib.I == null) throw new OoaException("AGENT.BAD_PROXY", "The proxy setting windows works on Windows runners only", "Use system, none or http://host:port on this computer.");
        URI u = URI.create(url);
        boolean https = "https".equalsIgnoreCase(u.getScheme());
        int port = u.getPort() > 0 ? u.getPort() : https ? 443 : 80;
        String named = namedProxy(proxySetting);
        Pointer session = Lib.I.WinHttpOpen(new WString("ONEOFFICEAUTOMATION-Runner"), named != null ? ACCESS_NAMED : ACCESS_AUTOMATIC,
                named != null ? new WString(named) : null, named != null ? new WString("<local>") : null, 0);
        if (session == null && named == null) session = Lib.I.WinHttpOpen(new WString("ONEOFFICEAUTOMATION-Runner"), ACCESS_DEFAULT, null, null, 0);   // before Windows 8.1
        if (session == null) throw failed("open a WinHTTP session");
        Pointer connect = null, request = null;
        try {
            Lib.I.WinHttpSetTimeouts(session, timeoutMs, timeoutMs, timeoutMs, timeoutMs);
            connect = Lib.I.WinHttpConnect(session, new WString(u.getHost()), (short) port, 0);
            if (connect == null) throw failed("connect to " + u.getHost());
            String path = (u.getRawPath() == null || u.getRawPath().isEmpty() ? "/" : u.getRawPath()) + (u.getRawQuery() == null ? "" : "?" + u.getRawQuery());
            request = Lib.I.WinHttpOpenRequest(connect, new WString(method), new WString(path), null, null, null, https ? FLAG_SECURE : 0);
            if (request == null) throw failed("open the request");
            // send the signed-in user's credentials to the proxy (and to intranet servers) without asking
            Lib.I.WinHttpSetOption(request, OPTION_AUTOLOGON_POLICY, new IntByReference(AUTOLOGON_LOW), 4);
            StringBuilder h = new StringBuilder();
            headers.forEach((k, v) -> h.append(k).append(": ").append(v).append("\r\n"));
            Memory data = body == null || body.length == 0 ? null : new Memory(body.length);
            if (data != null) data.write(0, body, 0, body.length);
            int len = body == null ? 0 : body.length;
            for (int attempt = 0; ; attempt++) {
                if (!Lib.I.WinHttpSendRequest(request, h.length() == 0 ? null : new WString(h.toString()), h.length() == 0 ? 0 : -1, data, len, len, null))
                    throw failed("send the request to " + u.getHost());
                if (!Lib.I.WinHttpReceiveResponse(request, null)) throw failed("receive the answer from " + u.getHost());
                IntByReference status = new IntByReference(), size = new IntByReference(4);
                Lib.I.WinHttpQueryHeaders(request, QUERY_STATUS_CODE, null, status, size, null);
                int code = status.getValue();
                if ((code == 407 || code == 401) && attempt < 3 && signIn(request, code == 407 ? TARGET_PROXY : TARGET_SERVER)) continue;
                return new Response(code, read(request));
            }
        } finally {
            if (request != null) Lib.I.WinHttpCloseHandle(request);
            if (connect != null) Lib.I.WinHttpCloseHandle(connect);
            Lib.I.WinHttpCloseHandle(session);
        }
    }

    /** Picks Negotiate (Kerberos), else NTLM, with the runner user's own Windows credentials (no name or password given). */
    private static boolean signIn(Pointer request, int target) {
        IntByReference supported = new IntByReference(), first = new IntByReference(), tgt = new IntByReference();
        if (!Lib.I.WinHttpQueryAuthSchemes(request, supported, first, tgt)) return false;
        int s = supported.getValue();
        int scheme = (s & AUTH_NEGOTIATE) != 0 ? AUTH_NEGOTIATE : (s & AUTH_NTLM) != 0 ? AUTH_NTLM : 0;
        if (scheme == 0) throw new OoaException("AGENT.PROXY_AUTH", "The proxy asks for a sign-in Windows cannot give for you (only Basic)", "Set proxy_user and proxy_password with the proxy http://host:port instead of windows.");
        return Lib.I.WinHttpSetCredentials(request, target, scheme, null, null, null);
    }

    private static byte[] read(Pointer request) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        IntByReference available = new IntByReference(), got = new IntByReference();
        while (Lib.I.WinHttpQueryDataAvailable(request, available) && available.getValue() > 0) {
            Memory buf = new Memory(available.getValue());
            if (!Lib.I.WinHttpReadData(request, buf, available.getValue(), got)) throw failed("read the answer");
            out.write(buf.getByteArray(0, got.getValue()));
        }
        return out.toByteArray();
    }

    private static IOException failed(String what) { return new IOException("WinHTTP could not " + what + " (Windows error " + Native.getLastError() + ")"); }
}
