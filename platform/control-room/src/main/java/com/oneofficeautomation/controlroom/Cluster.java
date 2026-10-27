package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

/**
 * Several Command Center nodes on one database (I5). Every node serves the console, API and runners; one node, the
 * leader, runs the background work that must happen once (schedules, triggers, work-queue dispatch, workflows,
 * housekeeping, backups, audit checkpoints). The leader holds a lease row in the database and renews it every
 * {@link #RENEW_SECONDS}; when it stops renewing, another node takes over after {@link #LEASE_SECONDS}. Sign-ins in
 * progress (two-step codes, SAML and OpenID Connect) are kept in the database too ({@link #put}/{@link #take}), so a
 * load balancer may send each request to any node. Each node is listed with its last heartbeat.
 */
public final class Cluster {
    static final int LEASE_SECONDS = 30, RENEW_SECONDS = 10;
    private final Db db;
    public final String nodeId;
    private volatile boolean leader;
    /** The time leases are judged against (tests move it). */
    java.time.Clock clock = java.time.Clock.systemUTC();

    Cluster(Db db) {
        this.db = db;
        String host;
        try { host = java.net.InetAddress.getLocalHost().getHostName(); } catch (Exception e) { host = "node"; }
        this.nodeId = host + "-" + Security.token(6);
    }

    private Instant now() { return clock.instant(); }

    /** Renews or takes the lease, and records this node; returns whether this node now leads. */
    public synchronized boolean tick() {
        Instant now = now(), until = now.plusSeconds(LEASE_SECONDS);
        try {
            if (db.update("UPDATE cluster_nodes SET last_seen = ? WHERE node_id = ?", now, nodeId) == 0)
                db.update("INSERT INTO cluster_nodes (node_id, started, last_seen) VALUES (?,?,?)", nodeId, now, now);
            int got = db.update("UPDATE cluster_lease SET holder = ?, expires = ? WHERE name = 'leader' AND (holder = ? OR expires < ?)", nodeId, until, nodeId, now);
            if (got == 0 && db.one("SELECT name FROM cluster_lease WHERE name = 'leader'") == null) {
                try { db.update("INSERT INTO cluster_lease (name, holder, expires) VALUES ('leader', ?, ?)", nodeId, until); got = 1; }
                catch (OoaException raced) { got = 0; }   // another node made the row at the same moment
            }
            boolean was = leader;
            leader = got == 1;
            if (leader && !was) db.update("UPDATE cluster_nodes SET leader_since = ? WHERE node_id = ?", now, nodeId);
            // nodes gone for a day are forgotten
            db.update("DELETE FROM cluster_nodes WHERE last_seen < ?", now.minus(Duration.ofDays(1)));
        } catch (RuntimeException e) { leader = false; }   // without the database nobody leads from here
        return leader;
    }

    public boolean isLeader() { return leader; }

    /** Gives the lease up (a clean stop), so another node leads at once. */
    public synchronized void release() {
        try { db.update("UPDATE cluster_lease SET expires = ? WHERE name = 'leader' AND holder = ?", Instant.parse("2000-01-01T00:00:00Z"), nodeId); } catch (RuntimeException ignored) { }
        leader = false;
    }

    /** The nodes seen in the last minute and which one leads, for the console. */
    public List<Map<String, Object>> nodes() {
        Map<String, Object> lease = db.one("SELECT holder, expires FROM cluster_lease WHERE name = 'leader'");
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> n : db.query("SELECT node_id, started, last_seen FROM cluster_nodes WHERE last_seen > ? ORDER BY started", now().minusSeconds(60))) {
            Map<String, Object> m = new LinkedHashMap<>(n);
            m.put("leader", lease != null && n.get("node_id").equals(lease.get("holder")) && Instant.parse(String.valueOf(lease.get("expires"))).isAfter(now()));
            m.put("this_node", n.get("node_id").equals(nodeId));
            out.add(m);
        }
        return out;
    }

    // ---------- short-lived state shared by the nodes (sign-ins in progress) ----------

    /** Keeps a value for a while under kind and token; false when that token is already there (used once). */
    public boolean put(String kind, String token, String data, Duration ttl) {
        db.update("DELETE FROM auth_pending WHERE expires < ?", now());
        try { db.update("INSERT INTO auth_pending (token, kind, data, expires) VALUES (?,?,?,?)", kind + ":" + token, kind, data == null ? "" : data, now().plus(ttl)); return true; }
        catch (OoaException duplicate) { return false; }
    }

    /** Takes a value out (it can be taken once); null when missing or expired. */
    public String take(String kind, String token) {
        if (token == null) return null;
        Map<String, Object> r = db.one("SELECT data, expires FROM auth_pending WHERE token = ?", kind + ":" + token);
        if (r == null || db.update("DELETE FROM auth_pending WHERE token = ?", kind + ":" + token) != 1) return null;
        return Instant.parse(String.valueOf(r.get("expires"))).isBefore(now()) ? null : (String) r.get("data");
    }

    /** Reads a value without taking it; null when missing or expired. */
    public String peek(String kind, String token) {
        if (token == null) return null;
        Map<String, Object> r = db.one("SELECT data, expires FROM auth_pending WHERE token = ?", kind + ":" + token);
        return r == null || Instant.parse(String.valueOf(r.get("expires"))).isBefore(now()) ? null : (String) r.get("data");
    }

    /** Changes a value that is still there (keeps its expiry). */
    public void update(String kind, String token, String data) { db.update("UPDATE auth_pending SET data = ? WHERE token = ?", data, kind + ":" + token); }
}
