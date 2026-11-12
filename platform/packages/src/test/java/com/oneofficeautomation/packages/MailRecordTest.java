package com.oneofficeautomation.packages;

import org.junit.jupiter.api.Test;

import java.time.LocalDateTime;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A2: one email record for every mail service, added next to each service's own keys. */
class MailRecordTest {
    @Test void microsoft365HtmlBodyGivesTextAndHtml() {
        Map<String, Object> e = new LinkedHashMap<>(Map.of("id", "AAMk", "body", "<p>Please pay&nbsp;<b>42</b></p><p>Thanks</p>", "body_type", "html",
                "internet_message_id", "<42@vendor.test>", "has_attachments", true, "unread", false, "from", "billing@vendor.test", "subject", "Invoice 42",
                "received", "2026-11-03T09:15:00Z"));
        Map<String, Object> r = MailRecord.withCommonKeys(e);
        assertEquals("Please pay 42\nThanks", r.get("text"));
        assertEquals(e.get("body"), r.get("html"));
        assertEquals("<42@vendor.test>", r.get("message_id"));
        assertEquals(true, r.get("has_attachments"), "a key the service gave is kept as it was");
        assertEquals("<p>Please pay&nbsp;<b>42</b></p><p>Thanks</p>", r.get("body"), "old keys stay for agents written before");
        assertTrue(r.keySet().containsAll(MailRecord.KEYS), r.keySet().toString());
    }

    @Test void gmailDateHeaderAndLabels() {
        Map<String, Object> r = MailRecord.withCommonKeys(new LinkedHashMap<>(Map.of("id", "18c", "date", "Tue, 3 Nov 2026 10:15:00 +0100 (CET)",
                "labels", List.of("INBOX", "UNREAD"), "attachments", List.of("inv.pdf"), "text", "hi", "html", "")));
        assertEquals(LocalDateTime.of(2026, 11, 3, 9, 15), r.get("received"));
        assertEquals(true, r.get("unread"));
        assertEquals(true, r.get("has_attachments"));
        assertNull(MailRecord.headerDate("not a date"));
    }

    @Test void outlookDesktopNames() {
        Map<String, Object> r = MailRecord.withCommonKeys(new LinkedHashMap<>(Map.of("id", "00AB", "body", "Hello", "html_body", "<p>Hello</p>",
                "attachment_names", List.of("a.xlsx"), "unread", true)));
        assertEquals("Hello", r.get("text"));
        assertEquals("<p>Hello</p>", r.get("html"));
        assertEquals(List.of("a.xlsx"), r.get("attachments"));
        assertEquals(List.of(), r.get("to"));
        assertEquals("", r.get("message_id"));
    }
}
