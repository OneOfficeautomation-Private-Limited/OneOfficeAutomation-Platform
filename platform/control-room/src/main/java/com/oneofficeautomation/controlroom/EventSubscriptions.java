package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.InetAddress;
import java.net.URI;
import java.net.UnknownHostException;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicLong;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Event subscriptions (gap G38; UiPath's webhooks, a SIEM or log platform fed with run logs): One CommandCenter posts
 * its events, as they happen, to the addresses administrators subscribe (Administration > Event subscriptions).
 * <p>
 * Each event is one JSON POST with an event id (for de-duplication), its type, time and node, and data. With the json
 * format the body is signed: X-OOA-Signature is "sha256=" and the HMAC-SHA256, in hex, of the X-OOA-Timestamp value, a
 * dot and the body, keyed with the subscription's secret. With the splunk format the body is in the shape Splunk's HTTP
 * Event Collector takes and the secret is the collector token (Authorization: Splunk). Delivery runs on its own threads
 * behind a bounded queue, so it never holds up a run or a request: an event that does not fit is dropped and counted;
 * a failed delivery is tried again after 2 s, 10 s, 30 s and 2 min (HTTP 408, 429, 5xx or no answer; other answers are
 * final). Each event is delivered by the node where it happened, so with several nodes it is sent once.
 * <p>
 * Addresses are https:// unless Settings events_allow_http is on, and must be public internet addresses unless Settings
 * events_allow_private is on (a SaaS tenant's are always checked by NetGuard). The address is checked again before every
 * attempt, as its name may resolve differently later. Redirects are not followed.
 */
public final class EventSubscriptions {
    /** Every event type, in the order the console lists them. */
    public static final List<String> TYPES = List.of(
        "run.queued", "run.started", "run.finished", "run.failed", "run.paused", "run.resumed", "run.log",
        "queue.item_added", "queue.item_started", "queue.item_finished", "queue.item_abandoned",
        "runner.online", "runner.offline", "alert.raised", "audit.entry");
    static final List<String> FORMATS = List.of("json", "splunk");
    static final int MAX_PENDING = 2000, INBOX = 5000;

    private final ControlRoom cr;
    private final Db db;
    /** The waits before the second, third... attempt (tests shorten them). */
    volatile long[] backoffMs = { 2_000, 10_000, 30_000, 120_000 };
    private final ArrayBlockingQueue<Event> inbox = new ArrayBlockingQueue<>(INBOX);
    private final ScheduledThreadPoolExecutor senders;
    private final AtomicInteger pending = new AtomicInteger();
    final AtomicLong delivered = new AtomicLong(), failed = new AtomicLong(), dropped = new AtomicLong();
    private final Map<String, Stats> stats = new ConcurrentHashMap<>();
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    private volatile List<Sub> subs;
    private volatile long subsAt;
    private volatile Thread dispatcher;
    /** The runners seen online at the leader's last look (null until its first look, which only takes note). */
    private Set<String> online;

    record Event(String id, String type, String time, Map<String, Object> data) { }
    record Sub(String id, String name, String url, List<String> events, String format, String secret, List<String> filters) { }
    static final class Stats { final AtomicLong delivered = new AtomicLong(), failed = new AtomicLong(), dropped = new AtomicLong(); volatile long written; volatile boolean failing; }

    EventSubscriptions(ControlRoom cr) {
        this.cr = cr;
        this.db = cr.db;
        senders = new ScheduledThreadPoolExecutor(4, r -> { Thread t = new Thread(r, "ooa-events-send"); t.setDaemon(true); return t; });
        senders.setRemoveOnCancelPolicy(true);
    }

    // ===================== events in =====================

    /** True when an enabled subscription takes this type (cheap: a copy of the subscriptions kept for 5 seconds). */
    public boolean wanted(String type) {
        for (Sub s : subscriptions()) if (matches(s.events(), type)) return true;
        return false;
    }

    /** Queues an event for the subscriptions that take it; never fails and never waits. */
    public void emit(String type, Map<String, Object> data) {
        try {
            if (!wanted(type)) return;
            Event e = new Event(id("evt"), type, now().toString(), new LinkedHashMap<>(data));
            if (!inbox.offer(e)) { dropped.incrementAndGet(); return; }
            startDispatcher();
        } catch (RuntimeException ignored) { /* events never fail the work they describe */ }
    }

    /**
     * An audit entry was written: audit.entry, and the run events the audit trail already records (queued, finished,
     * failed, paused, resumed). Called inside the audit's transaction, so it only queues.
     */
    void onAudit(Map<String, Object> entry) {
        String action = String.valueOf(entry.get("action")), id = entry.get("object_id") == null ? null : String.valueOf(entry.get("object_id"));
        Map<String, Object> a = new LinkedHashMap<>(entry);
        if ("run".equals(entry.get("object_type")) && id != null) a.put("run_id", id);
        if ("bot".equals(entry.get("object_type")) && id != null) a.put("bot_path", id);
        emit("audit.entry", a);
        if (!"run".equals(entry.get("object_type")) || id == null) return;
        switch (action) {
            case "run.start" -> emit("run.queued", Map.of("run_id", id, "requested_by", String.valueOf(entry.get("actor"))));
            case "run.pause" -> emit("run.paused", Map.of("run_id", id, "by", String.valueOf(entry.get("actor"))));
            case "run.resume" -> emit("run.resumed", Map.of("run_id", id, "by", String.valueOf(entry.get("actor"))));
            case "run.finish", "run.lost" -> {
                String status = action.equals("run.lost") ? "failed" : statusOf(entry.get("details"));
                Map<String, Object> d = new LinkedHashMap<>(Map.of("run_id", id, "status", status));
                if (action.equals("run.lost")) d.put("error_code", "AGENT.LOST");
                emit("run.finished", d);
                if (status.equals("failed")) emit("run.failed", d);
            }
            default -> { }
        }
    }

    private static String statusOf(Object details) {
        try { if (JsonLite.parse(String.valueOf(details)) instanceof Map<?, ?> m && m.get("status") != null) return String.valueOf(m.get("status")); }
        catch (RuntimeException ignored) { }
        return "failed";
    }

    /**
     * The leader's look at the runners (every clock tick): runner.offline for those that stopped reporting. The first
     * look only takes note, so a node that just became leader does not announce every runner.
     */
    synchronized void checkRunners() {
        Set<String> now = new HashSet<>();
        Map<String, String> names = new HashMap<>();
        for (Map<String, Object> d : db.query("SELECT id, name FROM devices WHERE enabled = TRUE AND last_seen > ?", ControlRoom.now().minus(ControlRoom.ONLINE))) {
            now.add((String) d.get("id")); names.put((String) d.get("id"), (String) d.get("name"));
        }
        Set<String> before = online;
        online = now;
        if (before == null) return;
        for (String id : before) if (!now.contains(id)) {
            Map<String, Object> d = db.one("SELECT name, last_seen FROM devices WHERE id = ?", id);
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("runner_id", id); m.put("name", d == null ? null : d.get("name")); m.put("last_seen", d == null || d.get("last_seen") == null ? null : String.valueOf(d.get("last_seen")));
            emit("runner.offline", m);
        }
    }

    private void startDispatcher() {
        if (dispatcher != null) return;
        synchronized (this) {
            if (dispatcher != null) return;
            Thread t = new Thread(this::dispatchLoop, "ooa-events" + (cr.tenant == null ? "" : "-" + cr.tenant));
            t.setDaemon(true);
            t.start();
            dispatcher = t;
        }
    }

    private void dispatchLoop() {
        while (true) {
            Event e;
            try { e = inbox.take(); } catch (InterruptedException x) { return; }
            try { dispatch(e); } catch (RuntimeException ignored) { /* one bad event never stops the others */ }
        }
    }

    /** Adds what the filters and the receiver need (the agent's path of a run or work item), then hands it to each subscription. */
    void dispatch(Event e) {
        enrich(e.data());
        Object bot = e.data().get("bot_path");
        for (Sub s : subscriptions()) {
            if (!matches(s.events(), e.type()) || !passes(s.filters(), bot == null ? null : String.valueOf(bot))) continue;
            Stats st = stats.computeIfAbsent(s.id(), k -> new Stats());
            if (pending.incrementAndGet() > MAX_PENDING) { pending.decrementAndGet(); dropped.incrementAndGet(); st.dropped.incrementAndGet(); continue; }
            senders.execute(() -> attempt(s.id(), e, 1));
        }
    }

    private void enrich(Map<String, Object> d) {
        if (d.get("run_id") != null && d.get("bot_path") == null) {
            Map<String, Object> r = db.one("SELECT bot_path, device_id, requested_by, source, status, duration_ms FROM runs WHERE id = ?", String.valueOf(d.get("run_id")));
            if (r != null) {
                d.put("bot_path", r.get("bot_path"));
                d.putIfAbsent("runner_id", r.get("device_id"));
                d.putIfAbsent("requested_by", r.get("requested_by"));
                d.putIfAbsent("source", r.get("source"));
                if (r.get("duration_ms") != null) d.putIfAbsent("duration_ms", ((Number) r.get("duration_ms")).longValue());
            }
        }
        if (d.get("queue_id") != null && d.get("queue") == null) {
            Map<String, Object> q = db.one("SELECT name, bot_path FROM queues WHERE id = ?", String.valueOf(d.get("queue_id")));
            if (q != null) { d.put("queue", q.get("name")); d.putIfAbsent("bot_path", q.get("bot_path")); }
        }
    }

    static boolean matches(List<String> events, String type) {
        for (String p : events) if (p.equals(type) || p.equals("*") || (p.endsWith("*") && type.startsWith(p.substring(0, p.length() - 1)))) return true;
        return false;
    }

    /** Folder or agent path prefixes; an event that names no agent (a runner, most audit entries) always passes. */
    static boolean passes(List<String> filters, String bot) {
        if (filters.isEmpty() || bot == null) return true;
        String b = bot.toLowerCase(Locale.ROOT);
        for (String f : filters) if (b.startsWith(f.toLowerCase(Locale.ROOT))) return true;
        return false;
    }

    // ===================== delivery =====================

    private Sub current(String subId) {
        for (Sub s : subscriptions()) if (s.id().equals(subId)) return s;
        return null;
    }

    /** One attempt; the subscription is read again, so a disabled or deleted one gets no more attempts. */
    private void attempt(String subId, Event e, int n) {
        Sub s = current(subId);
        if (s == null) { pending.decrementAndGet(); return; }
        Stats st = stats.computeIfAbsent(subId, k -> new Stats());
        Outcome o = send(s, e);
        if (o.ok()) {
            pending.decrementAndGet();
            delivered.incrementAndGet(); st.delivered.incrementAndGet();
            record(subId, st, true, String.valueOf(o.status()), null);
            return;
        }
        if (o.retry() && n <= backoffMs.length) {
            senders.schedule(() -> attempt(subId, e, n + 1), backoffMs[n - 1], TimeUnit.MILLISECONDS);
            return;
        }
        pending.decrementAndGet();
        failed.incrementAndGet(); st.failed.incrementAndGet();
        record(subId, st, false, o.status() == 0 ? "error" : String.valueOf(o.status()), o.error() + (n > 1 ? " (after " + n + " attempts)" : ""));
    }

    record Outcome(boolean ok, int status, boolean retry, String error) { }

    /** Sends one event once and says how it went. */
    Outcome send(Sub s, Event e) {
        try { checkAddress(s.url()); }
        catch (OoaException x) { return new Outcome(false, 0, false, x.getMessage()); }
        Map<String, Object> envelope = new LinkedHashMap<>();
        envelope.put("id", e.id()); envelope.put("type", e.type()); envelope.put("time", e.time()); envelope.put("node", cr.cluster.nodeId);
        if (cr.tenant != null) envelope.put("tenant", cr.tenant);
        envelope.put("data", e.data());
        String ts = String.valueOf(Instant.now().getEpochSecond());
        HttpRequest.Builder req = HttpRequest.newBuilder(URI.create(s.url())).timeout(Duration.ofSeconds(15))
            .header("Content-Type", "application/json").header("User-Agent", "OOA-Events/1")
            .header("X-OOA-Event", e.type()).header("X-OOA-Event-Id", e.id()).header("X-OOA-Timestamp", ts);
        String body;
        if ("splunk".equals(s.format())) {
            body = splunk(envelope, e, Instant.parse(e.time()));
            if (s.secret() != null) req.header("Authorization", "Splunk " + s.secret());
        } else {
            body = JsonLite.write(envelope);
            if (s.secret() != null) req.header("X-OOA-Signature", "sha256=" + sign(s.secret(), ts, body));
        }
        try {
            HttpResponse<Void> res = http.send(req.POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.discarding());
            int c = res.statusCode();
            if (c / 100 == 2) return new Outcome(true, c, false, null);
            return new Outcome(false, c, c == 408 || c == 429 || c >= 500, "The receiver answered HTTP " + c);
        } catch (InterruptedException x) {
            Thread.currentThread().interrupt();
            return new Outcome(false, 0, false, "Stopped");
        } catch (Exception x) {
            return new Outcome(false, 0, true, "No answer: " + (x.getMessage() == null ? x.getClass().getSimpleName() : x.getMessage()));
        }
    }

    /** Splunk's collector takes several events in one body: a run's log lines each become one event. */
    @SuppressWarnings("unchecked")
    private String splunk(Map<String, Object> envelope, Event e, Instant time) {
        StringBuilder sb = new StringBuilder();
        if (e.type().equals("run.log") && e.data().get("lines") instanceof List<?> lines) {
            for (Object line : lines) {
                Map<String, Object> one = new LinkedHashMap<>(envelope);
                Map<String, Object> d = new LinkedHashMap<>(e.data());
                d.remove("lines");
                d.put("line", line);
                one.put("data", d);
                sb.append(hec(one, time));
            }
            if (sb.length() > 0) return sb.toString();
        }
        return hec(envelope, time);
    }

    private String hec(Map<String, Object> event, Instant time) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("time", time.toEpochMilli() / 1000.0);
        m.put("host", cr.cluster.nodeId);
        m.put("source", "ooa-command-center");
        m.put("sourcetype", "ooa:event");
        m.put("event", event);
        return JsonLite.write(m);
    }

    /** The signature a receiver checks: HMAC-SHA256 of "timestamp.body" with the secret, in lower-case hex. */
    public static String sign(String secret, String timestamp, String body) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return HexFormat.of().formatHex(mac.doFinal((timestamp + "." + body).getBytes(StandardCharsets.UTF_8)));
        } catch (Exception x) { throw new IllegalStateException(x); }
    }

    /** Writes how delivery goes: every failure, and a success when it ends failures or 5 seconds after the last write. */
    private void record(String subId, Stats st, boolean ok, String status, String error) {
        long t = System.currentTimeMillis();
        try {
            if (ok) {
                if (!st.failing && t - st.written < 5000) return;
                db.update("UPDATE event_subscriptions SET last_status = ?, last_error = NULL, last_delivery = ?, failures = 0 WHERE id = ?", status, now(), subId);
            } else {
                String err = error == null ? null : error.length() > 990 ? error.substring(0, 990) : error;
                db.update("UPDATE event_subscriptions SET last_status = ?, last_error = ?, last_delivery = ?, failures = failures + 1 WHERE id = ?", status, err, now(), subId);
            }
            st.failing = !ok;
            st.written = t;
        } catch (RuntimeException ignored) { /* the counters in memory still say it */ }
    }

    /** Events waiting to be sent (queued or between attempts), for the metrics. */
    int pending() { return pending.get() + inbox.size(); }

    // ===================== addresses =====================

    /** The outbound rules: https unless Settings allows http; public addresses unless Settings allows private ones. */
    void checkAddress(String url) {
        String u = url == null ? "" : url.trim();
        boolean allowHttp = cr.platform.intSetting("events_allow_http") == 1;
        if (!u.matches("(?i)https://[^\\s/?#]+[^\\s]*") && !(allowHttp && u.matches("(?i)http://[^\\s/?#]+[^\\s]*")))
            throw new OoaException("CR.BAD_INPUT", allowHttp ? "The address starts with https:// or http://" : "The address starts with https://",
                "Plain http:// is allowed only when Settings (E-mail, proxy and SIEM > Event subscriptions) allows it (test systems)");
        if (u.length() > 1000) throw new OoaException("CR.BAD_INPUT", "The address is longer than 1000 characters");
        if (cr.tenant != null) { NetGuard.check(cr, u, "The event receiver"); return; }
        if (cr.platform.intSetting("events_allow_private") == 1) return;
        String host = NetGuard.hostOf(u);
        if (host.isEmpty() || host.equals("localhost") || host.endsWith(".localhost") || host.endsWith(".internal")) throw blocked(host);
        InetAddress[] all;
        try { all = InetAddress.getAllByName(host); }
        catch (UnknownHostException x) { throw new OoaException("CR.BAD_INPUT", "The name " + host + " is not known"); }
        for (InetAddress a : all) if (!NetGuard.isPublic(a)) throw blocked(host);
    }

    private static OoaException blocked(String host) {
        return new OoaException("EVENTS.ADDRESS_BLOCKED", "The event receiver at " + (host.isEmpty() ? "(no address)" : host) + " is not a public internet address",
            "Events go to public addresses only. To send them inside your own network, switch on Settings > E-mail, proxy and SIEM > Allow receivers in private networks.");
    }

    // ===================== subscriptions (Administration > Event subscriptions) =====================

    /** The enabled subscriptions, with their secrets decrypted, kept for 5 seconds (a change on this node clears the copy). */
    List<Sub> subscriptions() {
        List<Sub> c = subs;
        if (c != null && System.currentTimeMillis() - subsAt < 5000) return c;
        List<Sub> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT id, name, url, events, format, secret, filters FROM event_subscriptions WHERE enabled = TRUE")) {
            String id = (String) r.get("id"), secret = null;
            try { if (r.get("secret") instanceof String s && !s.isEmpty()) secret = cr.vault.decrypt(s, "event-subscription:" + id); }
            catch (RuntimeException e) { continue; }   // a secret this vault cannot open: nothing is sent unsigned
            out.add(new Sub(id, (String) r.get("name"), (String) r.get("url"), csv(r.get("events")), (String) r.get("format"), secret, csv(r.get("filters"))));
        }
        subs = List.copyOf(out);
        subsAt = System.currentTimeMillis();
        return subs;
    }

    private void changed() { subs = null; }

    public Map<String, Object> list(User by) {
        cr.require(by, "events.manage", "manage event subscriptions");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> r : db.query("SELECT * FROM event_subscriptions ORDER BY name")) out.add(view(r));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("subscriptions", out);
        m.put("types", TYPES);
        m.put("allow_http", cr.platform.intSetting("events_allow_http") == 1);
        m.put("allow_private", cr.platform.intSetting("events_allow_private") == 1);
        m.put("metrics_token", cr.metrics.tokenSet());
        m.put("node", cr.cluster.nodeId);
        return m;
    }

    private Map<String, Object> view(Map<String, Object> r) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (String k : List.of("id", "name", "url", "format", "enabled", "last_status", "last_error", "last_delivery", "failures", "created", "created_by")) m.put(k, r.get(k));
        m.put("events", csv(r.get("events")));
        m.put("filters", csv(r.get("filters")));
        m.put("has_secret", r.get("secret") != null);
        Stats st = stats.get((String) r.get("id"));
        m.put("this_node", Map.of("delivered", st == null ? 0 : st.delivered.get(), "failed", st == null ? 0 : st.failed.get(), "dropped", st == null ? 0 : st.dropped.get()));
        return m;
    }

    /**
     * Creates (id null) or changes a subscription: name, url, events, format (json, splunk), filters, enabled, secret.
     * A new json subscription without a secret gets one made here; it is in the answer this once ("secret"). With
     * rotate_secret true a new one is made the same way. A splunk subscription needs the collector token as its secret.
     */
    public Map<String, Object> save(User by, String id, Map<String, Object> b, String ip) {
        cr.require(by, "events.manage", "manage event subscriptions");
        Map<String, Object> old = id == null ? null : db.one("SELECT * FROM event_subscriptions WHERE id = ?", id);
        if (id != null && old == null) throw notFound("Event subscription");
        String name = text(b, old, "name", 120);
        if (name.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Give the subscription a name");
        String url = text(b, old, "url", 1000);
        checkAddress(url);
        List<String> events = b.containsKey("events") ? list(b.get("events")) : csv(old.get("events"));
        if (events.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Choose at least one event", "Event types: " + String.join(", ", TYPES));
        for (String e : events) if (!e.equals("*") && TYPES.stream().noneMatch(t -> matches(List.of(e), t)))
            throw new OoaException("CR.BAD_INPUT", "Unknown event type '" + e + "'", "Event types: " + String.join(", ", TYPES) + " (a type may end in *, for example queue.item_*)");
        String format = text(b, old, "format", 20);
        if (format.isEmpty()) format = "json";
        if (!FORMATS.contains(format)) throw new OoaException("CR.BAD_INPUT", "The format is json or splunk");
        List<String> filters = b.containsKey("filters") ? list(b.get("filters")) : old == null ? List.of() : csv(old.get("filters"));
        for (String f : filters) if (!f.matches("[A-Za-z0-9 _.()/-]{1,200}")) throw new OoaException("CR.BAD_INPUT", "Not a folder or agent path: " + f, "Example: Finance/ or Finance/Invoices.flow.json");
        boolean enabled = b.containsKey("enabled") ? !Boolean.FALSE.equals(b.get("enabled")) : old == null || Boolean.TRUE.equals(old.get("enabled"));
        String given = b.get("secret") instanceof String s && !s.isBlank() ? s.trim() : null;
        if (given != null && (given.length() < 16 || given.length() > 500)) throw new OoaException("CR.BAD_INPUT", "The secret is 16 to 500 characters");
        boolean rotate = Boolean.TRUE.equals(b.get("rotate_secret"));
        boolean hadSecret = old != null && old.get("secret") != null;
        if (format.equals("splunk") && given == null && !hadSecret) throw new OoaException("CR.BAD_INPUT", "A Splunk subscription needs the HTTP Event Collector token as its secret");
        if (format.equals("splunk") && rotate) throw new OoaException("CR.BAD_INPUT", "Splunk makes the collector token; paste the new one as the secret");
        String made = given == null && (rotate || (old == null && format.equals("json"))) ? "whsec_" + Security.token(32) : null;
        String sid = id == null ? id("evsub") : id;
        String secret = given != null ? given : made;
        String stored = secret == null ? null : cr.vault.encrypt(secret, "event-subscription:" + sid);
        String fmt = format;
        db.tx(() -> {
            if (old == null) db.update("INSERT INTO event_subscriptions (id, name, url, events, format, secret, enabled, filters, failures, created, created_by) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                sid, name, url, String.join(",", events), fmt, stored, enabled, filters.isEmpty() ? null : String.join(",", filters), 0, now(), by.username());
            else {
                db.update("UPDATE event_subscriptions SET name = ?, url = ?, events = ?, format = ?, enabled = ?, filters = ? WHERE id = ?",
                    name, url, String.join(",", events), fmt, enabled, filters.isEmpty() ? null : String.join(",", filters), sid);
                if (stored != null) db.update("UPDATE event_subscriptions SET secret = ? WHERE id = ?", stored, sid);
                if (enabled && !Boolean.TRUE.equals(old.get("enabled"))) db.update("UPDATE event_subscriptions SET failures = 0 WHERE id = ?", sid);
            }
            Map<String, Object> details = new LinkedHashMap<>();
            details.put("name", name); details.put("url", url); details.put("events", events); details.put("format", fmt); details.put("filters", filters); details.put("enabled", enabled);
            if (stored != null) details.put("secret", old == null ? "set" : "changed");   // never the secret itself
            cr.audit.record(by.username(), old == null ? "events.subscription_create" : "events.subscription_update", "event_subscription", sid, details, "ok", ip);
            return null;
        });
        changed();
        Map<String, Object> out = view(db.one("SELECT * FROM event_subscriptions WHERE id = ?", sid));
        if (made != null) out.put("secret", made);   // shown this once
        return out;
    }

    public void setEnabled(User by, String id, boolean enabled, String ip) {
        cr.require(by, "events.manage", "manage event subscriptions");
        int n = enabled ? db.update("UPDATE event_subscriptions SET enabled = TRUE, failures = 0 WHERE id = ?", id)
            : db.update("UPDATE event_subscriptions SET enabled = FALSE WHERE id = ?", id);
        if (n == 0) throw notFound("Event subscription");
        cr.audit.record(by.username(), enabled ? "events.subscription_enable" : "events.subscription_disable", "event_subscription", id, null, "ok", ip);
        changed();
    }

    public void delete(User by, String id, String ip) {
        cr.require(by, "events.manage", "manage event subscriptions");
        Map<String, Object> r = db.one("SELECT name FROM event_subscriptions WHERE id = ?", id);
        if (r == null) throw notFound("Event subscription");
        db.update("DELETE FROM event_subscriptions WHERE id = ?", id);
        cr.audit.record(by.username(), "events.subscription_delete", "event_subscription", id, Map.of("name", r.get("name")), "ok", ip);
        stats.remove(id);
        changed();
    }

    /** Sends a test event (type "test") to the subscription now, once, and says what the receiver answered. Works while it is disabled too. */
    public Map<String, Object> test(User by, String id, String ip) {
        cr.require(by, "events.manage", "manage event subscriptions");
        Map<String, Object> r = db.one("SELECT id, name, url, events, format, secret, filters FROM event_subscriptions WHERE id = ?", id);
        if (r == null) throw notFound("Event subscription");
        String secret = r.get("secret") instanceof String s && !s.isEmpty() ? cr.vault.decrypt(s, "event-subscription:" + id) : null;
        Sub sub = new Sub(id, (String) r.get("name"), (String) r.get("url"), csv(r.get("events")), (String) r.get("format"), secret, csv(r.get("filters")));
        Event e = new Event(id("evt"), "test", now().toString(), new LinkedHashMap<>(Map.of("message", "Test event from One CommandCenter", "by", by.username(), "subscription", String.valueOf(r.get("name")))));
        Outcome o = send(sub, e);
        record(id, stats.computeIfAbsent(id, k -> new Stats()), o.ok(), o.ok() || o.status() != 0 ? String.valueOf(o.status()) : "error", o.error());
        cr.audit.record(by.username(), "events.test", "event_subscription", id, o.ok() ? Map.of("status", o.status()) : Map.of("error", String.valueOf(o.error())), o.ok() ? "ok" : "failed", ip);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("ok", o.ok()); out.put("event_id", e.id());
        if (o.status() != 0) out.put("status", o.status());
        if (!o.ok()) out.put("error", o.error());
        return out;
    }

    private static String text(Map<String, Object> b, Map<String, Object> old, String k, int max) {
        Object v = b.containsKey(k) ? b.get(k) : old == null ? null : old.get(k);
        String s = v == null ? "" : String.valueOf(v).trim();
        if (s.length() > max) throw new OoaException("CR.BAD_INPUT", "The " + k + " is longer than " + max + " characters");
        return s;
    }

    private static List<String> list(Object v) {
        List<String> out = new ArrayList<>();
        if (v instanceof List<?> l) { for (Object o : l) if (o != null && !String.valueOf(o).isBlank() && !out.contains(String.valueOf(o).trim())) out.add(String.valueOf(o).trim()); }
        else if (v != null) for (String s : String.valueOf(v).split(",")) if (!s.isBlank() && !out.contains(s.trim())) out.add(s.trim());
        return out;
    }

    public static List<String> csv(Object v) { return v == null ? List.of() : list(String.valueOf(v)); }
}
