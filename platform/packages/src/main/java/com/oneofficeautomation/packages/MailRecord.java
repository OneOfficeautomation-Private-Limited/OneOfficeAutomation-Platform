package com.oneofficeautomation.packages;

import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * One email record for every mail service (A2, command review batch 7). Read email of Email (IMAP), Exchange, Gmail,
 * Microsoft 365 and Outlook desktop each give their own keys; these common keys are added next to them, so a child
 * agent or a later step that handles "an email" works whichever service it came from, and agents written before keep
 * the keys they use. Existing keys are never changed.
 *
 * <p>The common keys: id, from, to, cc (lists of addresses), subject, received (date and time, UTC), text, html,
 * attachments (file names), has_attachments, unread, message_id.
 */
public final class MailRecord {
    private MailRecord() {}

    public static final List<String> KEYS = List.of("id", "from", "to", "cc", "subject", "received", "text", "html", "attachments", "has_attachments", "unread", "message_id");

    /** The record with the common keys added from what the service gave (body and body_type, html_body, date, labels...). */
    public static Map<String, Object> withCommonKeys(Map<String, Object> e) {
        Map<String, Object> out = new LinkedHashMap<>(e);
        String type = Objects.toString(e.get("body_type"), "").toLowerCase(Locale.ROOT);
        Object body = e.get("body");
        if (!out.containsKey("html")) out.put("html", type.equals("html") ? body : e.containsKey("html_body") ? e.get("html_body") : "");
        if (!out.containsKey("text"))
            out.put("text", type.equals("html") ? htmlToText(Objects.toString(body, "")) : body != null ? body : "");
        if (!out.containsKey("received")) out.put("received", e.get("date") == null ? null : headerDate(Objects.toString(e.get("date"))));
        if (!out.containsKey("message_id")) out.put("message_id", Objects.toString(e.get("internet_message_id"), ""));
        if (!out.containsKey("attachments")) out.put("attachments", e.get("attachment_names") instanceof List<?> l ? new ArrayList<Object>(l) : new ArrayList<>());
        if (!out.containsKey("has_attachments")) out.put("has_attachments", out.get("attachments") instanceof List<?> l && !l.isEmpty());
        if (!out.containsKey("unread") && e.get("labels") instanceof List<?> labels) out.put("unread", labels.contains("UNREAD"));
        if (!out.containsKey("to")) out.put("to", new ArrayList<>());
        if (!out.containsKey("cc")) out.put("cc", new ArrayList<>());
        return out;
    }

    /** A Date header (RFC 5322, e.g. "Tue, 3 Nov 2026 10:15:00 +0100") as UTC; null when it cannot be read. */
    public static LocalDateTime headerDate(String header) {
        if (header == null || header.isBlank()) return null;
        String h = header.strip().replaceAll("\\s*\\([^)]*\\)\\s*$", "");   // a trailing comment such as (UTC)
        try { return ZonedDateTime.parse(h, DateTimeFormatter.RFC_1123_DATE_TIME).withZoneSameInstant(ZoneOffset.UTC).toLocalDateTime(); }
        catch (RuntimeException e) { return null; }
    }

    /** Plain text of an HTML body: line breaks for block ends, tags dropped, the common entities decoded. */
    static String htmlToText(String html) {
        String t = html.replaceAll("(?is)<(script|style)[^>]*>.*?</\\1>", "")
                .replaceAll("(?i)<br\\s*/?>", "\n").replaceAll("(?i)</(p|div|tr|li|h[1-6])>", "\n")
                .replaceAll("(?s)<[^>]+>", "")
                .replace("&nbsp;", " ").replace("&lt;", "<").replace("&gt;", ">").replace("&quot;", "\"").replace("&#39;", "'").replace("&amp;", "&");
        return t.replaceAll("[ \\t]+\n", "\n").replaceAll("\n{3,}", "\n\n").strip();
    }
}
