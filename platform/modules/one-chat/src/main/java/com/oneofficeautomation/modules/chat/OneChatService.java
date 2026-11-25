package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Function;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

/**
 * One CommandCenter's side of the OneChat service (S5, 20 Nov 2026; docs/integration/one-chat-contract.md): where it is
 * and how it is trusted (section 2), whether it is a version this release works with (section 8), passing the One
 * Chat page's and Studio's requests to it with the person token (sections 1 to 4), and telling it what happened
 * (section 6: run_ended, approval_decided, kept in onechat_events and retried for up to an hour). Chosen with Settings
 * > AI assistants > One Chat > Chat engine; the built-in engine stays the default.
 *
 * <p>OneChat is optional (S6, 25 Nov 2026; contract section 15). When the service is not set up, not answering, a
 * version this release does not work with, or failing with a server error, One Chat and Studio's assistant fall back
 * to the built-in engine and say so in a notice (Settings: onechat_fallback = builtin, the default; off shows the
 * error instead). After {@link #TRIP_AFTER} failures in a row the service is not asked for onechat_pause_seconds (a
 * circuit breaker), so people do not each wait for a service that is down.
 */
public final class OneChatService {
    /** The OneChat this release is built and tested with: contract, pinned, minimum, ref (onechat-compat.properties). */
    static final Properties COMPAT = new Properties();
    static {
        try (var in = OneChatService.class.getResourceAsStream("/onechat-compat.properties")) { if (in != null) COMPAT.load(in); }
        catch (IOException ignored) { /* none: every version is unknown */ }
    }
    /** The environment (tests replace it). */
    public static Function<String, String> env = System::getenv;
    /** How long to wait between tries of an event, by try; after an hour an event is dropped. */
    static final int[] BACKOFF = {10, 30, 60, 120, 300, 600, 900};

    private final ModuleContext ctx;
    private final PersonTokens tokens;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Map<String, Object> lastVersion;
    private volatile Instant lastVersionAt = Instant.EPOCH;
    /** Failures in a row before the service is paused (the circuit breaker opens). */
    static final int TRIP_AFTER = 3;
    /** The codes that mean "the service cannot answer now" (fall back), as opposed to a refusal of this request. */
    static final Set<String> UNAVAILABLE = Set.of("ONECHAT.NOT_AVAILABLE", "ONECHAT.UNREACHABLE", "ONECHAT.PAUSED", "ONECHAT.FAILED");
    private int failuresInRow;
    private volatile Instant pausedUntil = Instant.EPOCH;
    private volatile String lastFailure;
    private volatile Instant lastFailureAt;

    OneChatService(ModuleContext ctx, PersonTokens tokens) { this.ctx = ctx; this.tokens = tokens; }

    static void define(ModuleContext ctx) {
        ctx.defineSetting("chat_engine", "builtin", (v, saas) -> {
            if (!v.equals("builtin") && !v.equals("service")) throw new OoaException("CR.BAD_INPUT", "One Chat's engine is builtin or service");
        });
        ctx.defineSetting("onechat_url", "", (v, saas) -> {
            if (saas && !v.isEmpty()) throw new OoaException("SAAS.NOT_AVAILABLE", "Choosing the OneChat service is not available in the SaaS service", "The region's operator sets it.");
            if (!v.isEmpty() && !v.matches("(?i)https?://[^\\s/?#]+(/[^\\s?#]*)?")) throw new OoaException("CR.BAD_INPUT", "The OneChat service is a web address such as http://127.0.0.1:8086");
        });
        ctx.defineSetting("onechat_token_ref", "", (v, saas) -> {
            if (saas && !v.isEmpty()) throw new OoaException("SAAS.NOT_AVAILABLE", "Choosing the OneChat service is not available in the SaaS service", "The region's operator sets it.");
            if (!v.isEmpty() && v.split("/").length != 3) throw new OoaException("CR.BAD_INPUT", "The OneChat token is a vault reference: vault folder/credential/attribute");
        });
    }

    boolean chosen() { return "service".equals(String.valueOf(ctx.setting("chat_engine")).trim()); }

    static void defineFallback(ModuleContext ctx) {
        ctx.defineSetting("onechat_fallback", "builtin", (v, saas) -> {
            if (!v.equals("builtin") && !v.equals("off")) throw new OoaException("CR.BAD_INPUT", "When OneChat is not available is builtin or off");
        });
        ctx.defineSetting("onechat_pause_seconds", 300, (v, saas) -> {
            int n;
            try { n = Integer.parseInt(v.trim()); } catch (NumberFormatException e) { n = -1; }
            if (n < 30 || n > 3600) throw new OoaException("CR.BAD_INPUT", "Pause OneChat after failures is from 30 to 3600 seconds");
        });
    }

    /** Whether One Chat and Studio's assistant fall back to the built-in engine when the service cannot answer. */
    boolean fallsBack() { return !"off".equals(String.valueOf(ctx.setting("onechat_fallback")).trim()); }

    /** Whether the service is paused after failures in a row (the circuit breaker is open). */
    boolean paused() { return pausedUntil.isAfter(Instant.now()); }

    /** Whether this error means "the service cannot answer now" (fall back), not a refusal of the request itself. */
    static boolean unavailable(OoaException e) { return UNAVAILABLE.contains(e.code()); }

    /** The notice shown with an answer the built-in engine gave because the service could not. */
    String fallbackNotice() {
        return "The OneChat service is not available just now, so the built-in assistant answered. Chats and notes kept by OneChat are not used here.";
    }

    /** A failure of the service (not answering, timed out, server error): after TRIP_AFTER in a row it is paused. */
    synchronized void failed(String why) {
        lastFailure = why;
        lastFailureAt = Instant.now();
        if (++failuresInRow < TRIP_AFTER) return;
        failuresInRow = 0;
        pausedUntil = Instant.now().plusSeconds(Math.max(30, ctx.intSetting("onechat_pause_seconds")));
        recheck();
        ctx.audit("system", "onechat.paused", "service", baseUrl(), Map.of("until", pausedUntil.toString(), "error", why), "failed", null);
    }

    /** The service answered: the count of failures starts again. */
    synchronized void succeeded() { failuresInRow = 0; }

    /** Ends a pause at once (Settings > Check again, or the settings changed). */
    synchronized void resume() { failuresInRow = 0; pausedUntil = Instant.EPOCH; recheck(); }

    /** The breaker's state for Settings: paused_until, last_failure, last_failure_at, fallback. */
    Map<String, Object> health() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("fallback", fallsBack() ? "builtin" : "off");
        m.put("paused_until", paused() ? pausedUntil.toString() : null);
        m.put("last_failure", lastFailure);
        m.put("last_failure_at", lastFailureAt == null ? null : lastFailureAt.toString());
        return m;
    }

    /**
     * The service's address. On premises: Settings, then OOA_ONECHAT_URL (the platform's containers), then
     * config/ooa-onechat.properties (Windows setup), else http://127.0.0.1:8086. SaaS: OOA_ONECHAT_URL only.
     */
    String baseUrl() {
        String s = ctx.tenant() == null ? String.valueOf(ctx.setting("onechat_url")).trim() : "";
        if (s.isEmpty() || s.equals("null")) s = Objects.requireNonNullElse(env.apply("OOA_ONECHAT_URL"), "").trim();
        if (s.isEmpty() && ctx.tenant() == null) s = config().getProperty("url", "").trim();
        if (s.isEmpty()) s = "http://127.0.0.1:8086";
        return s.replaceAll("/+$", "");
    }

    /**
     * The service token. On premises: Settings (a vault reference), then OOA_ONECHAT_TOKEN, then the token Windows setup
     * kept (encrypted with the vault key) in config/ooa-onechat.properties. SaaS: OOA_ONECHAT_TOKEN only. Empty when none.
     */
    String token() {
        if (ctx.tenant() == null) {
            String ref = String.valueOf(ctx.setting("onechat_token_ref")).trim();
            if (!ref.isEmpty() && !ref.equals("null")) return ctx.systemCredential(ref);
        }
        String t = Objects.requireNonNullElse(env.apply("OOA_ONECHAT_TOKEN"), "").trim();
        if (!t.isEmpty() || ctx.tenant() != null) return t;
        String v = config().getProperty("token", "").trim();
        return v.startsWith("enc:") ? ctx.vault().decrypt(v.substring(4), "ooa-onechat:token") : v;
    }

    /** config/ooa-onechat.properties in the data folder, written by Windows setup (-OneChat); empty when there is none. */
    private Properties config() {
        Properties p = new Properties();
        java.nio.file.Path f = ctx.dataDir().resolve("config").resolve("ooa-onechat.properties");
        if (java.nio.file.Files.isRegularFile(f)) try (var in = java.nio.file.Files.newInputStream(f)) { p.load(in); } catch (IOException ignored) { /* unreadable: not set */ }
        return p;
    }

    /**
     * Whether the service answers with a version this release works with, asked at most once a minute: {contract,
     * tested, minimum, version, status (compatible, newer, older, wrong_contract, unreachable, not_set_up), compatible, message}.
     */
    Map<String, Object> version() {
        Map<String, Object> cached = lastVersion;
        if (cached != null && lastVersionAt.plusSeconds(60).isAfter(Instant.now())) return cached;
        Map<String, Object> out = new LinkedHashMap<>();
        String contract = COMPAT.getProperty("contract", "v1"), pinned = COMPAT.getProperty("pinned", "1.0.0"), minimum = COMPAT.getProperty("minimum", pinned);
        out.put("contract", contract);
        out.put("tested", pinned);
        out.put("minimum", minimum);
        String version = null, status;
        if (token().isEmpty()) status = "not_set_up";
        else {
            try {
                HttpResponse<String> r = http.send(HttpRequest.newBuilder(URI.create(baseUrl() + "/api/chat/v1/version")).timeout(Duration.ofSeconds(5))
                        .header("Authorization", "Bearer " + token()).GET().build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
                Object body = r.statusCode() == 200 ? JsonLite.parse(r.body()) : null;
                if (body instanceof Map<?, ?> m && m.get("version") != null) {
                    version = String.valueOf(m.get("version"));
                    status = !contract.equals(String.valueOf(m.get("contract"))) ? "wrong_contract" : compare(version, minimum, pinned);
                } else status = "unreachable";
            } catch (IOException | RuntimeException e) {
                status = "unreachable";
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                status = "unreachable";
            }
        }
        out.put("version", version);
        out.put("status", status);
        out.put("compatible", status.equals("compatible") || status.equals("newer"));
        out.put("message", switch (status) {
            case "compatible" -> "OneChat " + version + " works with this One CommandCenter.";
            case "newer" -> "OneChat " + version + " is newer than the " + pinned + " this release was tested with; it speaks the same contract.";
            case "older" -> "OneChat " + version + " is older than " + minimum + ", the oldest this release works with. Upgrade OneChat first.";
            case "wrong_contract" -> "OneChat " + version + " speaks another contract than " + contract + ". Install a OneChat that matches this release.";
            case "not_set_up" -> "The OneChat service has no token yet: set Service token (a vault reference), or OOA_ONECHAT_TOKEN.";
            default -> "The OneChat service at " + baseUrl() + " is not answering.";
        });
        lastVersion = out;
        lastVersionAt = Instant.now();
        return out;
    }

    /** Forgets the cached version (Settings changed). */
    void recheck() { lastVersion = null; }

    static String compare(String version, String minimum, String pinned) {
        if (cmp(version, minimum) < 0) return "older";
        return cmp(version, pinned) > 0 ? "newer" : "compatible";
    }

    static int cmp(String a, String b) {
        String[] x = a.split("[.-]"), y = b.split("[.-]");
        for (int i = 0; i < Math.max(x.length, y.length); i++) {
            int p = i < x.length ? num(x[i]) : 0, q = i < y.length ? num(y[i]) : 0;
            if (p != q) return Integer.compare(p, q);
        }
        return 0;
    }

    /** A time as the database layer gives it (ISO text), or the default when it is missing or unreadable. */
    static Instant instant(Object v, Instant dflt) {
        if (v instanceof Instant i) return i;
        try { return v == null ? dflt : Instant.parse(String.valueOf(v)); } catch (java.time.format.DateTimeParseException e) { return dflt; }
    }

    private static int num(String s) { try { return Integer.parseInt(s.replaceAll("\\D", "")); } catch (NumberFormatException e) { return 0; } }

    // ---- passing requests on ----

    /** One request to the service as this person: the service token, tenant, person token, area and language. */
    @SuppressWarnings("unchecked")
    Map<String, Object> call(String method, String path, Object body, User u, String area, String session, String lang, String ip, int timeoutSeconds) {
        if (paused())
            throw new OoaException("ONECHAT.PAUSED", "The OneChat service failed several times in a row; it is asked again after " + pausedUntil,
                    "An operator checks the OneChat service; Settings > AI assistants > One Chat > Check again asks it at once.");
        Map<String, Object> v = version();
        if (!Boolean.TRUE.equals(v.get("compatible")))
            throw new OoaException("ONECHAT.NOT_AVAILABLE", String.valueOf(v.get("message")), "An administrator checks Settings > AI assistants > One Chat > Engine, or chooses the built-in engine.");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(baseUrl() + "/api/chat/v1/" + path)).timeout(Duration.ofSeconds(Math.max(10, timeoutSeconds)))
                .header("Authorization", "Bearer " + token()).header("X-OOA-Tenant", tokens.tenant())
                .header("X-OOA-Person", tokens.issue(u, area, session, ip)).header("X-OOA-Area", area).header("X-OOA-Lang", lang == null || lang.isBlank() ? "en" : lang)
                .header("Content-Type", "application/json")
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(JsonLite.write(body), StandardCharsets.UTF_8));
        HttpResponse<String> r;
        try { r = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8)); }
        catch (IOException e) {
            recheck();
            failed(e instanceof java.net.http.HttpTimeoutException ? "timed out" : "not answering: " + e.getMessage());
            ctx.audit("system", "onechat.unreachable", "service", baseUrl(), Map.of("error", String.valueOf(e.getMessage())), "failed", null);
            throw new OoaException("ONECHAT.UNREACHABLE", "The OneChat service is not answering", "Try again in a moment; if it keeps happening, an operator checks the OneChat service.");
        } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("ONECHAT.UNREACHABLE", "Interrupted", "Try again."); }
        Object parsed;
        try { parsed = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body()); } catch (RuntimeException e) { parsed = Map.of(); }
        Map<String, Object> m = parsed instanceof Map<?, ?> pm ? (Map<String, Object>) pm : Map.of("items", parsed);
        if (r.statusCode() >= 500) {
            // a server error: the service cannot answer now (counted, and One Chat falls back)
            Map<String, Object> e = m.get("error") instanceof Map<?, ?> em ? (Map<String, Object>) em : Map.of();
            failed("HTTP " + r.statusCode());
            throw new OoaException("ONECHAT.FAILED", "The OneChat service failed: " + e.getOrDefault("message", "HTTP " + r.statusCode()), "Try again in a moment; if it keeps happening, an operator checks the OneChat service.");
        }
        succeeded();
        if (r.statusCode() >= 400) {
            Map<String, Object> e = m.get("error") instanceof Map<?, ?> em ? (Map<String, Object>) em : Map.of();
            throw new OoaException("ONECHAT.ERROR", "The OneChat service answered: " + e.getOrDefault("message", "HTTP " + r.statusCode()), (String) e.get("hint"));
        }
        return m;
    }

    /** A message from the One Chat page, answered by the service, in the page's own reply shape. */
    Map<String, Object> message(User u, String session, String text, String lang, String ip) {
        Map<String, Object> body = new LinkedHashMap<>();
        if (session != null && !session.isBlank()) body.put("session_id", session);
        body.put("message", text);
        try {
            Map<String, Object> r = call("POST", "messages", body, u, "chat", session, lang, ip, ctx.intSetting("chat_timeout_seconds") + 30);
            return toPage(r, session);
        } catch (OoaException e) {
            // the service cannot answer now: the caller lets the built-in engine answer (S6), unless fallback is off
            if (unavailable(e) && fallsBack()) throw e;
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("session_id", session);
            m.put("kind", "error");
            m.put("reply", e.getMessage() + (e.suggestedFix() == null ? "" : " " + e.suggestedFix()));
            return m;
        }
    }

    /** The service's reply as the One Chat page shows it: the assistant's texts, the last one's kind, and a confirmation's card. */
    @SuppressWarnings("unchecked")
    Map<String, Object> toPage(Map<String, Object> r, String session) {
        List<Map<String, Object>> said = new ArrayList<>();
        if (r.get("messages") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> mm && !"user".equals(mm.get("role"))) said.add((Map<String, Object>) mm);
        Map<String, Object> last = said.isEmpty() ? Map.of() : said.get(said.size() - 1);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("session_id", r.get("session_id") != null ? r.get("session_id") : session);
        out.put("kind", pageKind(String.valueOf(last.getOrDefault("kind", "answer"))));
        out.put("reply", String.join("\n\n", said.stream().map(x -> String.valueOf(x.getOrDefault("text", ""))).toList()));
        for (String k : List.of("run_id", "task_id", "rows", "columns", "page")) if (last.get(k) != null) out.put(k, last.get(k));
        if (last.get("confirmation_id") != null) {
            Map<String, Object> card = card(String.valueOf(last.get("confirmation_id")));
            if (card != null) out.put("confirmation", card);
        }
        return out;
    }

    private static String pageKind(String k) {
        return switch (k) {
            case "answer", "question", "confirm", "started", "done", "awaiting_approval", "refused", "not_understood", "cancelled", "error" -> k;
            case "approval" -> "awaiting_approval";
            default -> "answer";
        };
    }

    /** The page's card for a confirmation One CommandCenter made (onechat_confirmations): what it is about, risk, why, undo. */
    Map<String, Object> card(String id) {
        Map<String, Object> c = ctx.db().one("SELECT id, kind, name, risk, reason, undo, expires FROM onechat_confirmations WHERE id = ?", id);
        if (c == null) return null;
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", c.get("id"));
        m.put("expires", String.valueOf(c.get("expires")));
        m.put("risk", c.get("risk"));
        m.put("name", c.get("name"));
        m.put("kind", "tool".equals(c.get("kind")) ? "tool" : "agent");
        if (c.get("reason") != null) m.put("reason", c.get("reason"));
        if (c.get("undo") != null) m.put("undo", c.get("undo"));
        return m;
    }

    /** The person's chats on the service, in the page's shape. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> chats(User u) {
        Map<String, Object> r = call("GET", "sessions", null, u, "chat", null, null, null, 30);
        List<Map<String, Object>> out = new ArrayList<>();
        Object list = r.get("sessions") != null ? r.get("sessions") : r.get("items");
        if (list instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> s)
            out.add(Map.of("id", String.valueOf(s.get("id")), "title", String.valueOf(((Map<String, Object>) s).getOrDefault("title", "")),
                    "last_used", String.valueOf(((Map<String, Object>) s).getOrDefault("last_used", "")), "messages", ((Map<String, Object>) s).getOrDefault("messages", 0)));
        return out;
    }

    /** One of the person's chats on the service: its messages in the page's shape. */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> history(User u, String session) {
        Map<String, Object> r = call("GET", "sessions/" + java.net.URLEncoder.encode(session, StandardCharsets.UTF_8), null, u, "chat", session, null, null, 30);
        List<Map<String, Object>> out = new ArrayList<>();
        if (r.get("messages") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> mm) {
            Map<String, Object> x = new LinkedHashMap<>();
            x.put("role", "user".equals(mm.get("role")) ? "user" : "assistant");
            x.put("text", String.valueOf(((Map<String, Object>) mm).getOrDefault("text", "")));
            if (mm.get("kind") != null) x.put("kind", pageKind(String.valueOf(mm.get("kind"))));
            for (String k : List.of("run_id", "task_id")) if (mm.get(k) != null) x.put(k, mm.get(k));
            if (mm.get("confirmation_id") != null && card(String.valueOf(mm.get("confirmation_id"))) instanceof Map<String, Object> c) x.put("confirmation", c);
            out.add(x);
        }
        return out;
    }

    /** What the service remembers for the person, and forgetting it (My account > AI assistants). */
    @SuppressWarnings("unchecked")
    List<Map<String, Object>> notes(User u) {
        Map<String, Object> r = call("GET", "people/" + java.net.URLEncoder.encode(u.username(), StandardCharsets.UTF_8) + "/memory", null, u, "chat", null, null, null, 30);
        Object list = r.get("notes") != null ? r.get("notes") : r.get("items");
        return list instanceof List<?> l ? (List<Map<String, Object>>) l : List.of();
    }

    void forget(User u, String noteId) {
        call("DELETE", "people/" + java.net.URLEncoder.encode(u.username(), StandardCharsets.UTF_8) + "/memory" + (noteId == null ? "" : "/" + java.net.URLEncoder.encode(noteId, StandardCharsets.UTF_8)),
                null, u, "chat", null, null, null, 30);
    }

    /**
     * Studio's assistant through the service (the Studio area): Studio's request with its instructions as context; the
     * answer is the proposal the service suggests (as JSON text, which Studio checks as before) or its text.
     */
    @SuppressWarnings("unchecked")
    String studio(User u, Map<String, Object> context, String prompt, String ip) {
        String lang = Objects.requireNonNullElse(new AssistantSettings(ctx).language(u, "studio"), "en");
        Map<String, Object> r = call("POST", "messages", Map.of("message", prompt, "context", context), u, "studio", null, lang, ip,
                ctx.intSetting("studio_timeout_seconds") + 30);
        String text = "";
        if (r.get("messages") instanceof List<?> l) for (Object o : l) if (o instanceof Map<?, ?> mm && !"user".equals(mm.get("role"))) {
            if (mm.get("proposal") instanceof Map<?, ?> p) return JsonLite.write(p);
            text = String.valueOf(((Map<String, Object>) mm).getOrDefault("text", ""));
        }
        return text;
    }

    // ---- telling the service what happened (section 6) ----

    /** Keeps an event for the service, to be sent with a fresh person token. */
    void enqueue(String username, String session, Map<String, Object> event) {
        Map<String, Object> e = new LinkedHashMap<>(event);
        String id = id("evt");
        e.put("id", id);
        if (session != null) e.put("session_id", session);
        ctx.db().update("INSERT INTO onechat_events (id, username, session_id, payload, attempts, next_try, created) VALUES (?,?,?,?,?,?,?)",
                id, username, session, JsonLite.write(e), 0, now(), now());
    }

    /** Sends the events that are due (on One CommandCenter's clock, on the cluster's leader); retries, then drops after an hour. */
    @SuppressWarnings("unchecked")
    void deliverEvents() {
        Instant now = now();
        if (!chosen()) {
            // the built-in engine is chosen again: events nobody will collect go after an hour
            ctx.db().update("DELETE FROM onechat_events WHERE created < ?", now.minus(Duration.ofHours(1)));
            return;
        }
        for (Map<String, Object> e : ctx.db().query("SELECT id, username, session_id, payload, attempts, created FROM onechat_events WHERE next_try <= ? ORDER BY created LIMIT ?", now, 50)) {
            String id = String.valueOf(e.get("id"));
            Instant created = instant(e.get("created"), now);
            User u = ctx.activeUser(String.valueOf(e.get("username")));
            boolean sent = false;
            if (u != null) {
                try { call("POST", "events", JsonLite.parse(String.valueOf(e.get("payload"))), u, "chat", (String) e.get("session_id"), null, null, 30); sent = true; }
                catch (OoaException ignored) { /* tried again later */ }
            }
            if (sent || u == null) { ctx.db().update("DELETE FROM onechat_events WHERE id = ?", id); continue; }
            int attempts = ((Number) e.get("attempts")).intValue() + 1;
            if (created.isBefore(now.minus(Duration.ofHours(1)))) {
                ctx.db().update("DELETE FROM onechat_events WHERE id = ?", id);
                ctx.audit("system", "onechat.event_dropped", "event", id, Map.of("attempts", attempts), "failed", null);
            } else {
                ctx.db().update("UPDATE onechat_events SET attempts = ?, next_try = ? WHERE id = ?", attempts,
                        now.plusSeconds(BACKOFF[Math.min(attempts - 1, BACKOFF.length - 1)]), id);
            }
        }
    }
}
