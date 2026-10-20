package com.oneofficeautomation.controlroom;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.*;

/**
 * Microsoft 365 Outlook mail through Microsoft Graph. The cursor is "receivedDateTime|hash,hash": the newest
 * received time seen and short hashes of the message ids received at that same time (Graph ids are too long to keep).
 */
final class GraphMailSource implements MailSource {
    static final String GRAPH = "https://graph.microsoft.com/v1.0", LOGIN = "https://login.microsoftonline.com";
    private final String base, mailbox, folder, token;

    GraphMailSource(String graphUrl, String mailbox, String folder, String token) {
        this.base = graphUrl == null ? GRAPH : graphUrl.replaceAll("/+$", "");
        this.mailbox = mailbox; this.folder = folder == null ? "inbox" : folder; this.token = token;
    }

    /** An app-only token with the client credentials flow (needs Mail.Read application permission and a mailbox). */
    @SuppressWarnings("unchecked")
    static String clientCredentialsToken(String loginUrl, String tenant, String clientId, String secret) throws IOException {
        Object r = PollHttp.postForm((loginUrl == null ? LOGIN : loginUrl.replaceAll("/+$", "")) + "/" + PollHttp.enc(tenant) + "/oauth2/v2.0/token",
                Map.of("grant_type", "client_credentials", "client_id", clientId, "client_secret", secret, "scope", "https://graph.microsoft.com/.default"));
        Object t = r instanceof Map<?, ?> m ? m.get("access_token") : null;
        if (t == null) throw new IOException("Microsoft sign-in returned no access token");
        return String.valueOf(t);
    }

    private String messagesUrl() {
        return base + (mailbox == null || mailbox.isBlank() ? "/me" : "/users/" + PollHttp.enc(mailbox)) + "/mailFolders/" + PollHttp.enc(folder) + "/messages";
    }

    @SuppressWarnings("unchecked")
    private List<Object> get(String query) throws IOException {
        Object r = PollHttp.getJson(messagesUrl() + "?" + query, Map.of("Authorization", "Bearer " + token));
        return r instanceof Map<?, ?> m && m.get("value") instanceof List<?> l ? (List<Object>) l : List.of();
    }

    static String h8(String id) {
        try { byte[] d = MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)); return HexFormat.of().formatHex(d, 0, 4); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    private static final String SELECT = "$select=id,subject,from,toRecipients,receivedDateTime,bodyPreview,hasAttachments,internetMessageId,webLink";

    @SuppressWarnings("unchecked")
    @Override public Batch fetch(String cursor, boolean fireExisting) throws IOException {
        String ts;
        Set<String> seenAtTs = new HashSet<>();
        if (cursor == null || !cursor.contains("|")) {
            List<Object> newest = get("$top=1&$orderby=" + PollHttp.enc("receivedDateTime desc") + "&$select=id,receivedDateTime");
            if (newest.isEmpty()) return new Batch("1970-01-01T00:00:00Z|", List.of());
            Map<String, Object> n = (Map<String, Object>) newest.get(0);
            if (!fireExisting) return new Batch(n.get("receivedDateTime") + "|" + h8(String.valueOf(n.get("id"))), List.of());
            ts = "1970-01-01T00:00:00Z";
        } else {
            ts = cursor.substring(0, cursor.indexOf('|'));
            for (String h : cursor.substring(cursor.indexOf('|') + 1).split(",")) if (!h.isBlank()) seenAtTs.add(h);
        }
        List<Object> page = get("$filter=" + PollHttp.enc("receivedDateTime ge " + ts) + "&$orderby=" + PollHttp.enc("receivedDateTime asc") + "&$top=" + ImapMailSource.MAX_PER_POLL + "&" + SELECT);
        List<Map<String, Object>> out = new ArrayList<>();
        String newestTs = ts;
        Set<String> newestHashes = new LinkedHashSet<>(seenAtTs);
        for (Object o : page) {
            Map<String, Object> m = (Map<String, Object>) o;
            String rt = String.valueOf(m.get("receivedDateTime")), h = h8(String.valueOf(m.get("id")));
            if (rt.compareTo(ts) < 0 || (rt.equals(ts) && seenAtTs.contains(h))) continue;
            if (rt.compareTo(newestTs) > 0) { newestTs = rt; newestHashes.clear(); }
            newestHashes.add(h);
            out.add(message(m));
        }
        List<String> keep = new ArrayList<>(newestHashes);
        if (keep.size() > 4) keep = keep.subList(keep.size() - 4, keep.size());   // fits last_hash (64 characters)
        return new Batch(newestTs + "|" + String.join(",", keep), out);
    }

    @SuppressWarnings("unchecked")
    Map<String, Object> message(Map<String, Object> g) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", g.get("id"));
        m.put("from", address(g.get("from")));
        List<String> to = new ArrayList<>();
        if (g.get("toRecipients") instanceof List<?> l) for (Object r : l) to.add(address(r));
        m.put("to", String.join(", ", to));
        m.put("subject", String.valueOf(g.getOrDefault("subject", "")));
        m.put("date", g.get("receivedDateTime"));
        m.put("message_id", g.get("internetMessageId"));
        m.put("body_preview", String.valueOf(g.getOrDefault("bodyPreview", "")));
        m.put("has_attachments", Boolean.TRUE.equals(g.get("hasAttachments")));
        if (g.get("webLink") != null) m.put("web_link", g.get("webLink"));
        m.put("folder", folder);
        if (mailbox != null) m.put("mailbox", mailbox);
        return m;
    }

    private static String address(Object recipient) {
        if (recipient instanceof Map<?, ?> r && r.get("emailAddress") instanceof Map<?, ?> e) {
            Object name = e.get("name"), addr = e.get("address");
            return name != null && !String.valueOf(name).equals(String.valueOf(addr)) ? name + " <" + addr + ">" : String.valueOf(addr);
        }
        return "";
    }
}
