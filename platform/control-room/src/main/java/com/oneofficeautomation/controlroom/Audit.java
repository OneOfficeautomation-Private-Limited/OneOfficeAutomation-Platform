package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;

import java.time.Instant;
import java.util.*;

/**
 * Append-only audit log with a hash chain: each record stores the hash of the previous one, so any change, deletion
 * or reordering of past records is detected by verify().
 */
public final class Audit {
    private final Db db;
    /** Told about every entry after it is written (the syslog forwarder); never allowed to fail the action. */
    private volatile java.util.function.Consumer<Map<String, Object>> listener;
    public Audit(Db db) { this.db = db; }

    void onRecord(java.util.function.Consumer<Map<String, Object>> l) { this.listener = l; }

    public synchronized void record(String actor, String action, String objectType, String objectId, Object details, String result, String ip) {
        db.tx(() -> {
            Map<String, Object> last = db.one("SELECT seq, hash FROM audit ORDER BY seq DESC LIMIT 1");
            long seq = last == null ? 1 : ((Number) last.get("seq")).longValue() + 1;
            String prev = last == null ? "0".repeat(64) : (String) last.get("hash");
            String ts = Instant.now().truncatedTo(java.time.temporal.ChronoUnit.MILLIS).toString();
            String det = details == null ? "" : details instanceof String s ? s : JsonLite.write(details);
            if (det.length() > 3900) det = det.substring(0, 3900) + "...";
            String hash = Security.sha256(prev + "|" + seq + "|" + ts + "|" + actor + "|" + action + "|" + objectType + "|" + objectId + "|" + det + "|" + result);
            db.update("INSERT INTO audit (seq, ts, actor, action, object_type, object_id, details, result, ip, prev_hash, hash) VALUES (?,?,?,?,?,?,?,?,?,?,?)",
                    seq, Instant.parse(ts), actor, action, objectType, objectId, det, result, ip, prev, hash);
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

    /** Re-computes the whole chain. Returns {valid, records, broken_at}. */
    public Map<String, Object> verify() {
        String prev = "0".repeat(64);
        long expectedSeq = 1, count = 0;
        for (Map<String, Object> r : db.query("SELECT * FROM audit ORDER BY seq")) {
            long seq = ((Number) r.get("seq")).longValue();
            String ts = Instant.parse(String.valueOf(r.get("ts"))).toString();
            String hash = Security.sha256(prev + "|" + seq + "|" + ts + "|" + r.get("actor") + "|" + r.get("action") + "|" + r.get("object_type") + "|" + r.get("object_id") + "|"
                    + (r.get("details") == null ? "" : r.get("details")) + "|" + r.get("result"));
            if (seq != expectedSeq || !prev.equals(r.get("prev_hash")) || !hash.equals(r.get("hash")))
                return Map.of("valid", false, "records", count, "broken_at", seq);
            prev = hash; expectedSeq++; count++;
        }
        return Map.of("valid", true, "records", count);
    }
}
