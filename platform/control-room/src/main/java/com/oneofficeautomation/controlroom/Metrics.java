package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;

import java.lang.management.GarbageCollectorMXBean;
import java.lang.management.ManagementFactory;
import java.lang.management.MemoryUsage;
import java.util.*;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicLongArray;

import static com.oneofficeautomation.controlroom.ControlRoom.now;

/**
 * GET /metrics (gap G38): One CommandCenter's numbers in the Prometheus text format, for Prometheus, Grafana Agent or
 * the OpenTelemetry Collector's Prometheus receiver. Every series has a node label (and tenant in the SaaS service).
 * <ul>
 *   <li>Counted on this node since it started: runs finished by status, run durations (a histogram), events delivered,
 *       failed and dropped.</li>
 *   <li>Read from the database, so the same on every node (use max by the other labels across nodes): runs queued and
 *       running, work items waiting by queue and status, runners online and busy, open alerts, licence seats.</li>
 *   <li>This Java process: heap and non-heap memory, threads, garbage collection, uptime.</li>
 * </ul>
 * A scrape reads the database at most once every 5 seconds (the text is kept), with a few counting queries on indexed
 * columns. Access: the metrics token (made in Administration > Event subscriptions, kept only as a hash) as the bearer,
 * or a signed-in person with the metrics.view permission.
 */
public final class Metrics {
    static final double[] BUCKETS = { 1, 5, 15, 30, 60, 300, 900, 1800, 3600 };
    static final String TOKEN_SETTING = "metrics_token_hash";
    private final ControlRoom cr;
    private final Map<String, AtomicLong> finished = new java.util.concurrent.ConcurrentHashMap<>();
    private final AtomicLongArray buckets = new AtomicLongArray(BUCKETS.length + 1);
    private final AtomicLong durationMs = new AtomicLong(), durationCount = new AtomicLong();
    private volatile String cached;
    private volatile long cachedAt;
    private volatile String tokenHash;
    private volatile long tokenAt;

    Metrics(ControlRoom cr) { this.cr = cr; }

    /** A run ended on this node; durationMs is null when it never started (refused, lost). */
    void runFinished(String status, Long durationMs) {
        finished.computeIfAbsent(status, k -> new AtomicLong()).incrementAndGet();
        if (durationMs == null) return;
        double s = durationMs / 1000.0;
        int i = 0;
        while (i < BUCKETS.length && s > BUCKETS[i]) i++;
        buckets.incrementAndGet(i);
        this.durationMs.addAndGet(durationMs);
        durationCount.incrementAndGet();
    }

    /** The audit entries of runs that end without finish() (refused before the start, lost runners). */
    void onAudit(Map<String, Object> e) {
        if ("run.lost".equals(e.get("action"))) runFinished("failed", null);
        else if ("run.finish".equals(e.get("action")) && "failed".equals(e.get("result"))) runFinished("failed", null);   // refused at the start (licence, toolkits)
    }

    // ===================== token =====================

    boolean tokenSet() { return hash() != null; }

    private String hash() {
        if (System.currentTimeMillis() - tokenAt > 5000) {
            Map<String, Object> r = cr.db.one("SELECT json_value FROM settings WHERE name = ?", TOKEN_SETTING);
            tokenHash = r == null ? null : String.valueOf(com.oneofficeautomation.sdk.JsonLite.parse((String) r.get("json_value")));
            tokenAt = System.currentTimeMillis();
        }
        return tokenHash;
    }

    /** True when the bearer is the metrics token. */
    boolean tokenMatches(String bearer) {
        String h = hash();
        return h != null && bearer != null && bearer.startsWith("ooam_") && Security.constantEquals(h, Security.sha256(bearer));
    }

    /** Makes a new metrics token (the old one stops working); it is shown this once and kept only as a hash. */
    public Map<String, Object> makeToken(User by, String ip) {
        cr.require(by, "events.manage", "make the metrics token");
        String token = "ooam_" + Security.token(32);
        String json = com.oneofficeautomation.sdk.JsonLite.write(Security.sha256(token));
        cr.db.tx(() -> {
            if (cr.db.update("UPDATE settings SET json_value = ?, updated = ?, updated_by = ? WHERE name = ?", json, now(), by.username(), TOKEN_SETTING) == 0)
                cr.db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", TOKEN_SETTING, json, now(), by.username());
            cr.audit.record(by.username(), "metrics.token_create", "settings", TOKEN_SETTING, null, "ok", ip);
            return null;
        });
        tokenAt = 0;
        return Map.of("token", token);
    }

    public void deleteToken(User by, String ip) {
        cr.require(by, "events.manage", "remove the metrics token");
        if (cr.db.update("DELETE FROM settings WHERE name = ?", TOKEN_SETTING) == 0) throw new OoaException("CR.NOT_FOUND", "No metrics token is set", "Make a metrics token in Administration > Settings > Monitoring.");
        cr.audit.record(by.username(), "metrics.token_delete", "settings", TOKEN_SETTING, null, "ok", ip);
        tokenAt = 0;
    }

    // ===================== the text =====================

    /** The Prometheus text, made at most every 5 seconds. */
    public String text() {
        String c = cached;
        if (c != null && System.currentTimeMillis() - cachedAt < 5000) return c;
        synchronized (this) {
            if (cached != null && System.currentTimeMillis() - cachedAt < 5000) return cached;
            cached = build();
            cachedAt = System.currentTimeMillis();
            return cached;
        }
    }

    String build() {
        Out o = new Out(cr.cluster.nodeId, cr.tenant);
        o.help("ooa_info", "gauge", "One CommandCenter node; 1 on the node leading background work in leader");
        o.add("ooa_info", Map.of("leader", String.valueOf(cr.cluster.isLeader()), "schema", String.valueOf(Db.LATEST)), 1);

        o.help("ooa_runs_finished_total", "counter", "Runs that ended on this node since it started, by status");
        for (String s : List.of("success", "failed", "stopped")) finished.computeIfAbsent(s, k -> new AtomicLong());
        new TreeMap<>(finished).forEach((s, n) -> o.add("ooa_runs_finished_total", Map.of("status", s), n.get()));

        o.help("ooa_run_duration_seconds", "histogram", "How long runs that ended on this node took");
        long cum = 0;
        for (int i = 0; i < BUCKETS.length; i++) { cum += buckets.get(i); o.add("ooa_run_duration_seconds_bucket", Map.of("le", num(BUCKETS[i])), cum); }
        cum += buckets.get(BUCKETS.length);
        o.add("ooa_run_duration_seconds_bucket", Map.of("le", "+Inf"), cum);
        o.add("ooa_run_duration_seconds_sum", Map.of(), durationMs.get() / 1000.0);
        o.add("ooa_run_duration_seconds_count", Map.of(), durationCount.get());

        Db db = cr.db;
        o.help("ooa_up", "gauge", "1 when this node could read the database for this scrape");
        try {
            o.help("ooa_runs", "gauge", "Runs waiting or running now (all nodes)");
            Map<String, Long> active = new TreeMap<>(Map.of("queued", 0L, "running", 0L));
            for (Map<String, Object> r : db.query("SELECT status, COUNT(*) AS n FROM runs WHERE status IN ('queued', 'running') GROUP BY status"))
                active.put(String.valueOf(r.get("status")), ((Number) r.get("n")).longValue());
            active.forEach((s, n) -> o.add("ooa_runs", Map.of("status", s), n));

            o.help("ooa_queue_items", "gauge", "Work items not finished, by queue and status (all nodes)");
            for (Map<String, Object> r : db.query("SELECT q.name, i.status, COUNT(*) AS n FROM queue_items i JOIN queues q ON q.id = i.queue_id "
                    + "WHERE i.status IN ('new', 'retry', 'deferred', 'in_progress', 'review') GROUP BY q.name, i.status"))
                o.add("ooa_queue_items", Map.of("queue", String.valueOf(r.get("name")), "status", String.valueOf(r.get("status"))), ((Number) r.get("n")).longValue());

            o.help("ooa_runners", "gauge", "Runners: enabled, online (reported in the last minute) and busy (running an agent)");
            java.time.Instant since = now().minus(ControlRoom.ONLINE);
            o.add("ooa_runners", Map.of("state", "enabled"), cr.count("SELECT COUNT(*) AS n FROM devices WHERE enabled = TRUE"));
            o.add("ooa_runners", Map.of("state", "online"), cr.count("SELECT COUNT(*) AS n FROM devices WHERE enabled = TRUE AND last_seen > ?", since));
            o.add("ooa_runners", Map.of("state", "busy"), cr.count("SELECT COUNT(DISTINCT device_id) AS n FROM runs WHERE status = 'running'"));

            o.help("ooa_alerts_open", "gauge", "Alerts not cleared or acknowledged, by severity");
            Map<String, Long> alerts = new TreeMap<>(Map.of("warning", 0L, "critical", 0L));
            for (Map<String, Object> r : db.query("SELECT severity, COUNT(*) AS n FROM alerts WHERE status = 'open' GROUP BY severity"))
                alerts.put(String.valueOf(r.get("severity")), ((Number) r.get("n")).longValue());
            alerts.forEach((s, n) -> o.add("ooa_alerts_open", Map.of("severity", s), n));

            o.help("ooa_license_seats_used", "gauge", "Licence seats given out, by type");
            for (String t : License.TYPES) o.add("ooa_license_seats_used", Map.of("type", t), cr.licensing.used(t));
            o.help("ooa_license_seats_total", "gauge", "Licence seats there are, by type (-1: no limit)");
            for (String t : License.TYPES) o.add("ooa_license_seats_total", Map.of("type", t), cr.licensing.total(t));
            o.add("ooa_up", Map.of("part", "database"), 1);
        } catch (RuntimeException e) {
            o.add("ooa_up", Map.of("part", "database"), 0);
        }

        EventSubscriptions ev = cr.events;
        o.help("ooa_events_total", "counter", "Events to subscriptions from this node since it started: delivered, failed (after the last attempt), dropped (queue full)");
        o.add("ooa_events_total", Map.of("outcome", "delivered"), ev.delivered.get());
        o.add("ooa_events_total", Map.of("outcome", "failed"), ev.failed.get());
        o.add("ooa_events_total", Map.of("outcome", "dropped"), ev.dropped.get());
        o.help("ooa_events_pending", "gauge", "Events waiting to be sent from this node");
        o.add("ooa_events_pending", Map.of(), ev.pending());

        var mem = ManagementFactory.getMemoryMXBean();
        MemoryUsage heap = mem.getHeapMemoryUsage(), non = mem.getNonHeapMemoryUsage();
        o.help("ooa_jvm_memory_used_bytes", "gauge", "Java memory in use");
        o.add("ooa_jvm_memory_used_bytes", Map.of("area", "heap"), heap.getUsed());
        o.add("ooa_jvm_memory_used_bytes", Map.of("area", "nonheap"), non.getUsed());
        o.help("ooa_jvm_memory_max_bytes", "gauge", "The most Java heap memory the process may use");
        o.add("ooa_jvm_memory_max_bytes", Map.of("area", "heap"), heap.getMax());
        o.help("ooa_jvm_threads", "gauge", "Live Java threads (virtual threads are not counted)");
        o.add("ooa_jvm_threads", Map.of(), ManagementFactory.getThreadMXBean().getThreadCount());
        o.help("ooa_jvm_gc_seconds_total", "counter", "Time spent in garbage collection, by collector");
        for (GarbageCollectorMXBean gc : ManagementFactory.getGarbageCollectorMXBeans())
            if (gc.getCollectionTime() >= 0) o.add("ooa_jvm_gc_seconds_total", Map.of("gc", gc.getName()), gc.getCollectionTime() / 1000.0);
        o.help("ooa_process_uptime_seconds", "gauge", "Seconds since this process started");
        o.add("ooa_process_uptime_seconds", Map.of(), ManagementFactory.getRuntimeMXBean().getUptime() / 1000.0);
        o.help("ooa_process_cpu_count", "gauge", "Processors Java may use");
        o.add("ooa_process_cpu_count", Map.of(), Runtime.getRuntime().availableProcessors());
        return o.sb.toString();
    }

    static String num(double d) { return d == Math.rint(d) && !Double.isInfinite(d) ? String.valueOf((long) d) : String.valueOf(d); }

    /** Lines of the text format; each series carries the node (and tenant) label. */
    static final class Out {
        final StringBuilder sb = new StringBuilder();
        private final String node, tenant;
        private final Set<String> helped = new HashSet<>();
        Out(String node, String tenant) { this.node = node; this.tenant = tenant; }

        void help(String name, String type, String help) {
            if (!helped.add(name)) return;
            sb.append("# HELP ").append(name).append(' ').append(help.replace("\\", "\\\\").replace("\n", "\\n")).append('\n');
            sb.append("# TYPE ").append(name).append(' ').append(type).append('\n');
        }

        void add(String name, Map<String, String> labels, double value) {
            sb.append(name).append("{node=\"").append(esc(node)).append('"');
            if (tenant != null) sb.append(",tenant=\"").append(esc(tenant)).append('"');
            new TreeMap<>(labels).forEach((k, v) -> sb.append(',').append(k).append("=\"").append(esc(v)).append('"'));
            sb.append("} ").append(num(value)).append('\n');
        }

        static String esc(String v) { return v.replace("\\", "\\\\").replace("\"", "\\\"").replace("\n", "\\n"); }
    }
}
