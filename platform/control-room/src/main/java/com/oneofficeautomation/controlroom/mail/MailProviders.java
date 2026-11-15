package com.oneofficeautomation.controlroom.mail;

import com.oneofficeautomation.sdk.JsonLite;

import javax.naming.directory.Attribute;
import javax.naming.directory.Attributes;
import javax.naming.directory.InitialDirContext;
import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.function.Function;

/**
 * Mail vendors (Mail accounts, MA-1): Microsoft 365, Outlook.com, Exchange, Gmail and Google Workspace, Zoho, Yahoo,
 * AOL, iCloud, GMX, Fastmail, Rediffmail and any other IMAP server. They come from the catalogue
 * {@code mail/providers.json}, so a new vendor is a new entry, not new code: its servers (by region), how it signs in,
 * where to make an app password, and how to recognise it from an address (its domains, or for a company domain the
 * mail servers its MX record names).
 */
public final class MailProviders {
    private final List<Map<String, Object>> providers;
    private final Function<String, List<String>> mxLookup;

    /** The catalogue shipped with One CommandCenter, and the real DNS for MX records. */
    public static MailProviders standard() { return new MailProviders(load(), MailProviders::mx); }

    public MailProviders(List<Map<String, Object>> providers, Function<String, List<String>> mxLookup) {
        this.providers = List.copyOf(providers);
        this.mxLookup = mxLookup;
    }

    @SuppressWarnings("unchecked")
    static List<Map<String, Object>> load() {
        try (InputStream in = MailProviders.class.getResourceAsStream("providers.json")) {
            if (in == null) throw new IllegalStateException("mail/providers.json is missing");
            Map<String, Object> m = (Map<String, Object>) JsonLite.parse(new String(in.readAllBytes(), StandardCharsets.UTF_8));
            return (List<Map<String, Object>>) m.get("providers");
        } catch (IOException e) { throw new IllegalStateException("mail/providers.json cannot be read", e); }
    }

    /** Every vendor, in the catalogue's order. */
    public List<Map<String, Object>> list() { return providers; }

    /** A vendor by id, or null. */
    public Map<String, Object> get(String id) {
        for (Map<String, Object> p : providers) if (p.get("id").equals(id)) return p;
        return null;
    }

    /**
     * The vendor of an address: by its domain first, then for a company domain by where its MX record points (a
     * Google Workspace, Microsoft 365 or Zoho domain). Returns provider (id or null), how (domain, mx or none), the
     * region when the vendor has several, and the mail server found.
     */
    public Map<String, Object> detect(String address) {
        Map<String, Object> out = new LinkedHashMap<>();
        String a = address == null ? "" : address.trim().toLowerCase(Locale.ROOT);
        int at = a.lastIndexOf('@');
        String domain = at >= 0 ? a.substring(at + 1) : a;
        out.put("domain", domain);
        if (domain.isEmpty() || !domain.matches("[a-z0-9.-]+\\.[a-z]{2,}")) return none(out);
        for (Map<String, Object> p : providers)
            if (strings(p.get("domains")).contains(domain)) {
                out.put("provider", p.get("id"));
                out.put("how", "domain");
                Map<String, Object> region = regionFor(p, domain, null);
                if (region != null) out.put("region", region.get("id"));
                return out;
            }
        List<String> mx;
        try { mx = mxLookup.apply(domain); } catch (RuntimeException e) { mx = List.of(); }
        for (String host : mx) {
            String h = host.toLowerCase(Locale.ROOT).replaceAll("\\.$", "");
            for (Map<String, Object> p : providers)
                for (String suffix : strings(p.get("mx")))
                    if (h.equals(suffix) || h.endsWith("." + suffix)) {
                        out.put("provider", p.get("id"));
                        out.put("how", "mx");
                        out.put("mx", h);
                        Map<String, Object> region = regionFor(p, null, h);
                        if (region != null) out.put("region", region.get("id"));
                        return out;
                    }
        }
        if (!mx.isEmpty()) out.put("mx", mx.get(0).replaceAll("\\.$", ""));
        return none(out);
    }

    private static Map<String, Object> none(Map<String, Object> out) {
        out.put("provider", null);
        out.put("how", "none");
        return out;
    }

    /** The vendor's region for a domain (gmx.de: net) or a mail server (mx.zoho.in: in); null when it has no regions. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> regionFor(Map<String, Object> p, String domain, String mxHost) {
        if (!(p.get("regions") instanceof List<?> rs) || rs.isEmpty()) return null;
        for (Object o : rs) {
            Map<String, Object> r = (Map<String, Object>) o;
            String key = String.valueOf(r.get("mx"));
            if (mxHost != null && (mxHost.equals(key) || mxHost.endsWith("." + key))) return r;
            if (domain != null && (domain.equals(key) || String.valueOf(r.get("name")).contains(domain))) return r;
        }
        return (Map<String, Object>) rs.get(0);
    }

    /** The IMAP server for a vendor and region (the region's own server when it has one). */
    @SuppressWarnings("unchecked")
    public String imapHost(Map<String, Object> p, String region) {
        if (region != null && p.get("regions") instanceof List<?> rs)
            for (Object o : rs) if (region.equals(((Map<String, Object>) o).get("id"))) return String.valueOf(((Map<String, Object>) o).get("host"));
        return p.get("imap") instanceof Map<?, ?> imap ? String.valueOf(imap.get("host")) : "";
    }

    static List<String> strings(Object o) {
        List<String> out = new ArrayList<>();
        if (o instanceof List<?> l) for (Object x : l) out.add(String.valueOf(x).toLowerCase(Locale.ROOT));
        return out;
    }

    /** A domain's mail servers from DNS, best first; empty when DNS cannot tell (a few seconds at most). */
    static List<String> mx(String domain) {
        Hashtable<String, String> env = new Hashtable<>();
        env.put("java.naming.factory.initial", "com.sun.jndi.dns.DnsContextFactory");
        env.put("com.sun.jndi.dns.timeout.initial", "2000");
        env.put("com.sun.jndi.dns.timeout.retries", "1");
        try {
            Attributes attrs = new InitialDirContext(env).getAttributes(domain, new String[]{"MX"});
            Attribute mx = attrs.get("MX");
            if (mx == null) return List.of();
            List<String[]> rows = new ArrayList<>();
            for (int i = 0; i < mx.size(); i++) {
                String[] parts = String.valueOf(mx.get(i)).trim().split("\\s+");
                if (parts.length == 2) rows.add(parts);
            }
            rows.sort(Comparator.comparingInt(r -> { try { return Integer.parseInt(r[0]); } catch (NumberFormatException e) { return 999; } }));
            List<String> out = new ArrayList<>();
            for (String[] r : rows) out.add(r[1]);
            return out;
        } catch (Exception e) {
            return List.of();
        }
    }
}
