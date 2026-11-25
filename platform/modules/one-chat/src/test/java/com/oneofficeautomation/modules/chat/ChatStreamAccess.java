package com.oneofficeautomation.modules.chat;

import java.time.Instant;

/** Lets tests in other packages reach ChatStream's package-private helpers (S5c). */
public final class ChatStreamAccess {
    private ChatStreamAccess() {}

    public static Instant start(String lastEventId, Instant now) { return ChatStream.start(lastEventId, now); }
}
