package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpExchange;

import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * GET /api/chat/stream (S5c, 25 Nov 2026; contract section 6): server-sent events for the One Chat page, so it hears
 * at once what One CommandCenter adds for the person instead of asking every few seconds:
 * <ul>
 *   <li>{@code run_ended} {run_id, status, source}: a run they started from One Chat (either engine) finished;</li>
 *   <li>{@code approval_decided} {task_id, outcome}: an approver decided their high-impact request;</li>
 *   <li>{@code new_task} {task_id, title}: a task arrived in their Task inbox.</li>
 * </ul>
 * Each connection reads the database every {@link #EVERY} for the window since its last look, so it works the same on
 * every node of a cluster. Event ids are "t" + the end of the window in epoch milliseconds: a dropped stream that
 * reconnects with Last-Event-ID (or ?last=) picks up from there (at most {@link #REPLAY} back). A comment line every
 * 15 seconds keeps proxies from closing it; after {@link #LIFETIME} the server ends it and the page reconnects. Where a
 * proxy blocks streams, the page still checks every 30 seconds.
 */
final class ChatStream {
    static final Duration EVERY = Duration.ofSeconds(2), LIFETIME = Duration.ofMinutes(10), REPLAY = Duration.ofMinutes(15);

    private final ModuleContext ctx;
    private final OneChatCallbacks callbacks;

    ChatStream(ModuleContext ctx, OneChatCallbacks callbacks) { this.ctx = ctx; this.callbacks = callbacks; }

    /** Where a stream starts: the Last-Event-ID (or ?last=) when it is recent, else now. */
    static Instant start(String lastEventId, Instant now) {
        if (lastEventId == null || !lastEventId.matches("t\\d{1,15}")) return now;
        Instant t = Instant.ofEpochMilli(Long.parseLong(lastEventId.substring(1)));
        return t.isAfter(now) ? now : t.isBefore(now.minus(REPLAY)) ? now.minus(REPLAY) : t;
    }

    /** Holds the connection open and writes the person's events until LIFETIME, or until the page goes. */
    void serve(HttpExchange ex, User u) throws IOException {
        String last = ex.getRequestHeaders().getFirst("Last-Event-ID");
        if (last == null) last = ctx.query(ex).get("last");
        Instant since = start(last, Instant.now()), ends = Instant.now().plus(LIFETIME);
        ex.getResponseHeaders().set("Content-Type", "text/event-stream; charset=utf-8");
        ex.getResponseHeaders().set("Cache-Control", "no-cache");
        ex.getResponseHeaders().set("X-Accel-Buffering", "no");
        ex.sendResponseHeaders(200, 0);
        try (OutputStream out = ex.getResponseBody()) {
            write(out, "retry: 5000\n: connected\n\n");
            Instant lastPing = Instant.now();
            while (Instant.now().isBefore(ends)) {
                Instant to = Instant.now();
                List<Map<String, Object>> events = events(u, since, to);
                String id = "t" + to.toEpochMilli();
                for (Map<String, Object> e : events)
                    write(out, "id: " + id + "\nevent: " + e.get("type") + "\ndata: " + JsonLite.write(e.get("data")) + "\n\n");
                if (events.isEmpty() && Duration.between(lastPing, to).getSeconds() >= 15) { write(out, ": ping " + id + "\n\n"); lastPing = to; }
                since = to;
                try { Thread.sleep(EVERY.toMillis()); } catch (InterruptedException e) { Thread.currentThread().interrupt(); return; }
            }
        } catch (IOException gone) { /* the page closed the stream */ }
    }

    private static void write(OutputStream out, String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    /** The person's events in (since, to]. */
    List<Map<String, Object>> events(User u, Instant since, Instant to) {
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : ctx.db().query("SELECT id, status, source FROM runs WHERE requested_by = ? AND source IN ('chat', 'onechat') AND finished > ? AND finished <= ? ORDER BY finished",
                u.username(), since, to))
            out.add(Map.of("type", "run_ended", "data", Map.of("run_id", r.get("id"), "status", r.get("status"), "source", r.get("source"))));
        for (Map<String, Object> t : ctx.db().query("SELECT id, stage, data, outcome FROM human_tasks WHERE stage = ? AND completed > ? AND completed <= ? ORDER BY completed",
                Chat.APPROVAL_STAGE, since, to))
            if (u.username().equals(Chat.requester(t)))
                out.add(Map.of("type", "approval_decided", "data", Map.of("task_id", t.get("id"), "outcome", String.valueOf(t.get("outcome")))));
        for (Map<String, Object> t : callbacks.tasks(u)) {
            Instant created = OneChatService.instant(t.get("created"), Instant.EPOCH);
            if (created.isAfter(since) && !created.isAfter(to))
                out.add(Map.of("type", "new_task", "data", Map.of("task_id", t.get("task_id"), "title", String.valueOf(t.get("title")))));
        }
        return out;
    }
}
