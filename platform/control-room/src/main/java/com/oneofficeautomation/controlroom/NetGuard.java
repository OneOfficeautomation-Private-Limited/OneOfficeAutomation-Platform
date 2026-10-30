package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.net.Inet4Address;
import java.net.Inet6Address;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.util.Locale;

/**
 * Addresses a SaaS tenant's Command Center may connect to (C3): when it is a tenant, the mail server, syslog
 * collector, external vault, identity provider, trigger sources, webhooks, Git remote and promotion target a tenant
 * sets must be on the public internet. Loopback, private, link-local (cloud metadata), carrier-grade NAT, unique-local
 * and multicast addresses are refused, so a tenant cannot reach the region's own network through the server. On an
 * on-premises install (no tenant) everything is allowed, as before.
 * <p>
 * The name is resolved here and again when connecting, so the region's network must also block those ranges for the
 * Command Center's outgoing traffic (docs/infrastructure/09-saas-and-multi-region-operations.md); this check gives the tenant a clear message first.
 */
final class NetGuard {
    private NetGuard() { }

    static void check(ControlRoom cr, String target, String what) {
        if (cr == null || cr.tenant == null) return;
        String host = hostOf(target);
        if (host.isEmpty() || host.equals("metadata.google.internal") || host.endsWith(".internal") || host.equals("localhost") || host.endsWith(".localhost"))
            throw blocked(what, host);
        InetAddress[] all;
        try { all = InetAddress.getAllByName(host); }
        catch (UnknownHostException e) { throw new OoaException("SAAS.UNKNOWN_HOST", what + ": the name " + host + " is not known"); }
        for (InetAddress a : all) if (!isPublic(a)) throw blocked(what, host);
    }

    /** The host of a URL (https://h:1/x), of host:port, or a bare host name. */
    static String hostOf(String target) {
        String t = target == null ? "" : target.trim();
        if (t.contains("://")) {
            try { String h = URI.create(t).getHost(); return h == null ? "" : strip(h); }
            catch (IllegalArgumentException e) { return ""; }
        }
        if (t.startsWith("[")) return strip(t.substring(0, Math.max(1, t.indexOf(']') + 1)));
        int at = t.lastIndexOf('@');
        if (at >= 0) t = t.substring(at + 1);                                // user@host:path (Git over SSH)
        int colon = t.indexOf(':');
        return strip(colon >= 0 && t.indexOf(':', colon + 1) < 0 ? t.substring(0, colon) : t);
    }

    private static String strip(String h) {
        String s = h.replace("[", "").replace("]", "").toLowerCase(Locale.ROOT);
        return s.endsWith(".") ? s.substring(0, s.length() - 1) : s;
    }

    static boolean isPublic(InetAddress a) {
        if (a.isAnyLocalAddress() || a.isLoopbackAddress() || a.isLinkLocalAddress() || a.isSiteLocalAddress() || a.isMulticastAddress()) return false;
        byte[] b = a.getAddress();
        if (a instanceof Inet4Address) {
            int x = b[0] & 0xff, y = b[1] & 0xff;
            if (x == 0 || x >= 240) return false;                            // "this network", reserved and broadcast
            if (x == 100 && y >= 64 && y <= 127) return false;               // carrier-grade NAT 100.64.0.0/10
            if (x == 192 && y == 0 && (b[2] & 0xff) == 0) return false;      // IETF protocol assignments
            if (x == 198 && (y == 18 || y == 19)) return false;              // benchmarking
        } else if (a instanceof Inet6Address) {
            if ((b[0] & 0xfe) == 0xfc) return false;                         // unique local fc00::/7
            if (b[0] == 0x20 && b[1] == 0x01 && b[2] == 0x0d && (b[3] & 0xff) == 0xb8) return false;   // documentation
            boolean mapped = true;                                           // ::ffff:a.b.c.d: judge the IPv4 address
            for (int i = 0; i < 10; i++) mapped &= b[i] == 0;
            if (mapped && (b[10] & 0xff) == 0xff && (b[11] & 0xff) == 0xff) {
                try { return isPublic(InetAddress.getByAddress(new byte[] { b[12], b[13], b[14], b[15] })); }
                catch (UnknownHostException e) { return false; }
            }
        }
        return true;
    }

    private static OoaException blocked(String what, String host) {
        return new OoaException("SAAS.ADDRESS_BLOCKED", what + " at " + (host.isEmpty() ? "(no address)" : host) + " is not on the public internet",
            "The SaaS Command Center connects only to public addresses. For systems in your own network, use a runner there.");
    }
}
