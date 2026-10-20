package com.oneofficeautomation.controlroom;

import java.io.IOException;
import java.util.*;

/**
 * A mailbox folder polled by an email trigger (IMAP or Microsoft Graph). The cursor is the trigger's last_hash:
 * it remembers the newest message seen, so each message fires once.
 */
interface MailSource {
    /**
     * Messages newer than the cursor, oldest first, with the cursor to store next. With a null cursor the source
     * only marks the current newest message as seen (no messages), unless fireExisting asks for the recent ones too.
     * Each message has: id, from, to, subject, date, message_id, body_preview, has_attachments, folder.
     */
    Batch fetch(String cursor, boolean fireExisting) throws IOException;

    record Batch(String cursor, List<Map<String, Object>> messages) {}

    /** True when the message passes the trigger's from and subject filters (case-insensitive "contains"). */
    static boolean matches(Map<String, Object> msg, Map<String, Object> config) {
        return contains(msg.get("from"), config.get("from_contains")) && contains(msg.get("subject"), config.get("subject_contains"));
    }

    private static boolean contains(Object value, Object filter) {
        if (filter == null || String.valueOf(filter).isBlank()) return true;
        return value != null && String.valueOf(value).toLowerCase(Locale.ROOT).contains(String.valueOf(filter).toLowerCase(Locale.ROOT));
    }
}
