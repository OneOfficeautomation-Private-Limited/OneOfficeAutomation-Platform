package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.net.InetAddress;
import java.util.ArrayList;
import java.util.List;

/**
 * The addresses allowed to use the Command Center API (Settings: ip_allow_list): single addresses or CIDR ranges,
 * IPv4 or IPv6, separated by commas, spaces or new lines. An empty list allows everyone; this computer is always allowed.
 */
final class IpAllowList {
    private record Range(byte[] net, int bits) { }

    private final List<Range> ranges;

    private IpAllowList(List<Range> ranges) { this.ranges = ranges; }

    /** Parses and checks the list; a wrong entry is refused with its text. At most 200 entries. */
    static IpAllowList parse(String text) {
        List<Range> out = new ArrayList<>();
        for (String raw : String.valueOf(text == null ? "" : text).split("[,\\s]+")) {
            String e = raw.trim();
            if (e.isEmpty()) continue;
            String[] p = e.split("/", 2);
            if (!p[0].matches("[0-9A-Fa-f:.]+")) throw bad(e);
            byte[] a;
            try { a = InetAddress.getByName(p[0]).getAddress(); } catch (Exception x) { throw bad(e); }
            int bits = a.length * 8;
            if (p.length == 2) {
                try { bits = Integer.parseInt(p[1]); } catch (NumberFormatException x) { throw bad(e); }
                if (bits < 0 || bits > a.length * 8) throw bad(e);
            }
            out.add(new Range(a, bits));
        }
        if (out.size() > 200) throw new OoaException("CR.BAD_INPUT", "The IP allow list holds at most 200 entries");
        return new IpAllowList(out);
    }

    private static OoaException bad(String e) {
        return new OoaException("CR.BAD_INPUT", "'" + e + "' is not an IP address or CIDR range (for example 10.0.0.0/8 or 203.0.113.7)");
    }

    boolean isEmpty() { return ranges.isEmpty(); }

    boolean allows(String address) {
        if (ranges.isEmpty()) return true;
        byte[] a;
        try { a = InetAddress.getByName(address).getAddress(); } catch (Exception e) { return false; }
        if (a.length == 4 || a.length == 16) for (Range r : ranges) if (matches(r, a)) return true;
        return false;
    }

    private static boolean matches(Range r, byte[] a) {
        if (r.net().length != a.length) return false;
        int full = r.bits() / 8, rest = r.bits() % 8;
        for (int i = 0; i < full; i++) if (r.net()[i] != a[i]) return false;
        if (rest == 0) return true;
        int mask = 0xFF << (8 - rest) & 0xFF;
        return (r.net()[full] & mask) == (a[full] & mask);
    }
}
