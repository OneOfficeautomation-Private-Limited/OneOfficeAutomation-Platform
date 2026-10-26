package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.io.OutputStreamWriter;
import java.io.Writer;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.function.Supplier;
import java.util.zip.GZIPOutputStream;

/**
 * Append-only audit log with a hash chain: each record stores the hash of the previous one, so any change, deletion
 * or reordering of past records is detected by verify().
 * <p>Tamper evidence beyond the database: entries are chained with HMAC-SHA256 under a key kept encrypted with the
 * vault key (alg "h1"; older entries keep plain SHA-256), so someone with database access alone cannot rebuild a valid
 * chain. Every hour a checkpoint (the last sequence number and hash) is written to {@code audit-checkpoints.log} in the
 * data folder and, as an audit entry, to the SIEM when syslog forwarding is on; verify() compares the chain with those
 * checkpoints, which also catches a chain rebuilt by someone who has the key.
 * <p>Retention ({@code audit_retention_days}): older entries are first written to {@code audit-archive/*.jsonl.gz}, then
 * removed; an "audit.purged" entry records where the chain was cut, so the rest still verifies.
 */
public final class Audit {
    private final Db db;
    /** Told about every entry after it is written (the syslog forwarder); never allowed to fail the action. */
    private volatile java.util.function.Consumer<Map<String, Object>> listener;
    /** The chain key (raw bytes), or null to chain with plain SHA-256 (tests, older callers). */
    private volatile Supplier<byte[]> key;
    /** Where checkpoints and archives go; null keeps them in the database only. */
    private volatile Path dataDir;

    public Audit(Db db) { this.db = db; }

    void onRecord(java.util.function.Consumer<Map<String, Object>> l) { this.listener = l; }

    /** Turns on the keyed chain and the files beside the database (the Command Center does this at start). */
    void protect(Supplier<byte[]> key, Path dataDir) { this.key = key; this.dataDir = dataDir; }

    static final String ZERO = "0".repeat(64);

    private static String line(String prev, long seq, String ts, Object actor, Object action, Object objectType, Object objectId, Object det, Object result) {
        return prev + "|" + seq + "|" + ts + "|" + actor + "|" + action + "|" + objectType + "|" + objectId + "|" + (det == null ? "" : det) + "|" + result;
    }

    static String hmac(byte[] key, String s) {
        try {
            Mac m = Mac.getInstance("HmacSHA256");
            m.init(new SecretKeySpec(key, "HmacSHA256"));
            return HexFormat.of().formatHex(m.doFinal(s.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    public synchronized void record(String actor, String action, String objectType, String objectId, Object details, String result, String ip) {
        db.tx(() -> {
            Map<String, Object> last = db.one("SELECT seq, hash FROM audit ORDER BY seq DESC LIMIT 1");
            long seq = last == null ? 1 : ((Number) last.get("seq")).longValue() + 1;
            String prev = last == null ? ZERO : (String) last.get("hash");
            String ts = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
            String det = details == null ? "" : details instanceof String s ? s : JsonLite.write(details);
            if (det.length() > 3900) det = det.substring(0, 3900) + "...";
            String text = line(prev, seq, ts, actor, action, objectType, objectId, det, result);
            Supplier<byte[]> k = key;
            byte[] kb = k == null ? null : k.get();
            String hash = kb == null ? Security.sha256(text) : hmac(kb, text);
            db.update("INSERT INTO audit (seq, ts, actor, action, object_type, object_id, details, result, ip, prev_hash, hash, alg) VALUES (?,?,?,?,?,?,?,?,?,?,?,?)",
                    seq, Instant.parse(ts), actor, action, objectType, objectId, det, result, ip, prev, hash, kb == null ? null : "h1");
            var l = listener;
            if (l != null) {
                Map<String, Object> e = new HashMap<>();
                e.put("seq", seq); e.put("ts", ts); e.put("actor", actor); e.put("action", action); e.put("object_type", objectType);
                e.put("object_id", objectId); e.put("details", det); e.put("result", result); e.put("ip", ip);
                try { l.accept(e); } catch (RuntimeException ignored) { /* forwarding never fails the action */ }
            }
            return null;
        });
    }

    /**
     * Re-computes the chain from its first kept entry and compares it with the checkpoints. Returns {valid, records,
     * keyed, checkpoints, archived_through?, broken_at?, reason?}.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> verify() {
        List<Map<String, Object>> rows = db.query("SELECT * FROM audit ORDER BY seq");
        Map<String, Object> out = new LinkedHashMap<>();
        if (rows.isEmpty()) return Map.of("valid", true, "records", 0);
        long first = ((Number) rows.get(0).get("seq")).longValue();
        String prev = ZERO;
        if (first > 1) {
            // the start was archived: an audit.purged entry must vouch for exactly where the chain was cut
            String start = (String) rows.get(0).get("prev_hash");
            boolean vouched = false;
            for (Map<String, Object> r : rows) {
                if (!"audit.purged".equals(r.get("action"))) continue;
                try {
                    Map<String, Object> d = (Map<String, Object>) JsonLite.parse((String) r.get("details"));
                    if (((Number) d.get("through_seq")).longValue() == first - 1 && start.equals(d.get("through_hash"))) vouched = true;
                } catch (RuntimeException ignored) { }
            }
            if (!vouched) return Map.of("valid", false, "records", 0, "broken_at", first, "reason", "entries before " + first + " are missing and no archive record vouches for them");
            prev = start;
            out.put("archived_through", first - 1);
        }
        Supplier<byte[]> k = key;
        byte[] kb = k == null ? null : k.get();
        long expected = first, count = 0;
        boolean keyed = false;
        Map<Long, String> hashes = new HashMap<>();
        for (Map<String, Object> r : rows) {
            long seq = ((Number) r.get("seq")).longValue();
            String ts = Instant.parse(String.valueOf(r.get("ts"))).toString();
            String text = line(prev, seq, ts, r.get("actor"), r.get("action"), r.get("object_type"), r.get("object_id"), r.get("details"), r.get("result"));
            String hash;
            if ("h1".equals(r.get("alg"))) {
                if (kb == null) return Map.of("valid", false, "records", count, "broken_at", seq, "reason", "the audit key is not available");
                hash = hmac(kb, text);
                keyed = true;
            } else {
                // once the chain is keyed, an unkeyed entry after it means someone rewrote it without the key
                if (keyed) return Map.of("valid", false, "records", count, "broken_at", seq, "reason", "an entry was rewritten without the audit key");
                hash = Security.sha256(text);
            }
            if (seq != expected || !prev.equals(r.get("prev_hash")) || !hash.equals(r.get("hash")))
                return Map.of("valid", false, "records", count, "broken_at", seq);
            hashes.put(seq, hash);
            prev = hash; expected++; count++;
        }
        // checkpoints kept outside the database must match what the database holds now
        int checked = 0;
        Path cp = dataDir == null ? null : dataDir.resolve("audit-checkpoints.log");
        if (cp != null && Files.exists(cp)) {
            try {
                for (String l : Files.readAllLines(cp, StandardCharsets.UTF_8)) {
                    String[] p = l.trim().split("\\s+");
                    if (p.length < 3) continue;
                    long seq = Long.parseLong(p[1]);
                    if (seq < first) continue;   // archived since
                    String h = hashes.get(seq);
                    if (h == null || !h.equals(p[2])) return Map.of("valid", false, "records", count, "broken_at", seq, "reason", "the database does not match the checkpoint of " + p[0]);
                    checked++;
                }
            } catch (Exception e) { return Map.of("valid", false, "records", count, "reason", "the checkpoint file cannot be read: " + e.getMessage()); }
        }
        out.put("valid", true); out.put("records", count); out.put("keyed", keyed); out.put("checkpoints", checked);
        return out;
    }

    /** Hourly: the last sequence number and hash, to a file beside the database and (as an entry) to the SIEM. */
    synchronized void checkpointIfDue() {
        Path dir = dataDir;
        if (dir == null) return;
        Map<String, Object> last = db.one("SELECT seq, hash, ts, action FROM audit ORDER BY seq DESC LIMIT 1");
        if (last == null || "audit.checkpoint".equals(last.get("action"))) return;   // nothing new since the last one
        Map<String, Object> lastCp = db.one("SELECT ts FROM audit WHERE action = 'audit.checkpoint' ORDER BY seq DESC LIMIT 1");
        if (lastCp != null && Instant.parse(String.valueOf(lastCp.get("ts"))).isAfter(Instant.now().minus(Duration.ofHours(1)))) return;
        long seq = ((Number) last.get("seq")).longValue();
        String hash = (String) last.get("hash");
        try {
            Files.writeString(dir.resolve("audit-checkpoints.log"), Instant.now() + " " + seq + " " + hash + System.lineSeparator(), StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (Exception e) { return; }
        record("system", "audit.checkpoint", "audit", String.valueOf(seq), Map.of("upto_seq", seq, "upto_hash", hash), "ok", null);
    }

    /**
     * Retention: writes entries older than {@code days} to audit-archive/audit-through-SEQ.jsonl.gz, then removes them and
     * records where the chain was cut. Returns the number removed. Keeps the newest checkpoint period at least.
     */
    synchronized int purgeOlderThan(int days) {
        Path dir = dataDir;
        if (days <= 0 || dir == null) return 0;
        Instant cutoff = Instant.now().minus(Duration.ofDays(days));
        Map<String, Object> lastOld = db.one("SELECT seq, hash FROM audit WHERE ts < ? ORDER BY seq DESC LIMIT 1", cutoff);
        if (lastOld == null) return 0;
        long through = ((Number) lastOld.get("seq")).longValue();
        if (db.one("SELECT seq FROM audit WHERE seq > ?", through) == null) return 0;   // never remove the newest entry
        List<Map<String, Object>> old = db.query("SELECT * FROM audit WHERE seq <= ? ORDER BY seq", through);
        Path archive = dir.resolve("audit-archive").resolve("audit-through-" + through + ".jsonl.gz");
        try {
            Files.createDirectories(archive.getParent());
            try (Writer w = new OutputStreamWriter(new GZIPOutputStream(Files.newOutputStream(archive)), StandardCharsets.UTF_8)) {
                for (Map<String, Object> r : old) {
                    Map<String, Object> m = new LinkedHashMap<>(r);
                    m.replaceAll((k, v) -> v instanceof java.util.Date || v instanceof java.time.temporal.Temporal ? String.valueOf(v) : v);
                    w.write(JsonLite.write(m)); w.write('\n');
                }
            }
        } catch (Exception e) { return 0; }   // without the archive nothing is removed
        db.update("DELETE FROM audit WHERE seq <= ?", through);
        record("system", "audit.purged", "audit", archive.getFileName().toString(),
            Map.of("through_seq", through, "through_hash", lastOld.get("hash"), "entries", old.size(), "days", days, "archive", archive.toString()), "ok", null);
        return old.size();
    }
}
