package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Duration;
import java.time.Instant;
import java.util.*;

import static com.oneofficeautomation.controlroom.ControlRoom.*;

/**
 * Looking after runners (Runners page; checked against AA Devices, UiPath Machines and Blue Prism Resources):
 * <ul>
 * <li>Maintenance: a runner takes no new work. {@code drain} lets its runs finish; {@code stop} also stops them
 * (after their current step). Runs already queued for it wait until maintenance ends; a pool sends new runs to its
 * other runners. The runner stays connected, unlike a disabled one.</li>
 * <li>Limits: runners report their CPU and memory use (an average over about 30 seconds) with each heartbeat. Above
 * its limit (its own, or Settings runner_cpu_max / runner_mem_max; 0: none) a runner takes no new work.</li>
 * <li>Logs and a screen picture on request: the next heartbeat asks the runner; it sends them back and the latest of
 * each is kept (runner_files). Screen pictures need Settings runner_screen_pictures and are audited.</li>
 * <li>A nickname and description.</li>
 * </ul>
 */
public final class RunnerCare {
    static final List<String> MAINTENANCE = List.of("drain", "stop");
    /** What a runner may send back: its log text, and a JPEG picture as Base64. */
    static final int MAX_LOG_CHARS = 512 * 1024, MAX_PICTURE_CHARS = 900_000;
    /** A request the runner has not answered within this time is shown as unanswered. */
    static final Duration ANSWER_WITHIN = Duration.ofMinutes(2);

    private final ControlRoom cr;
    private final Db db;

    RunnerCare(ControlRoom cr) { this.cr = cr; this.db = cr.db; }

    /**
     * Why the runner takes no new work now (maintenance, or above a limit), or null. {@code d} holds maintenance,
     * cpu_pct, mem_pct, cpu_max and mem_max.
     */
    String whyNoWork(Map<String, Object> d) {
        if (d.get("maintenance") != null) return "in maintenance";
        int cpuMax = limit(d.get("cpu_max"), "runner_cpu_max"), memMax = limit(d.get("mem_max"), "runner_mem_max");
        if (cpuMax > 0 && d.get("cpu_pct") instanceof Number c && c.intValue() > cpuMax) return "CPU use " + c.intValue() + "% is above its limit of " + cpuMax + "%";
        if (memMax > 0 && d.get("mem_pct") instanceof Number m && m.intValue() > memMax) return "memory use " + m.intValue() + "% is above its limit of " + memMax + "%";
        return null;
    }

    /** The runner's own limit, or the Command Center's when it has none. */
    int limit(Object own, String setting) { return own instanceof Number n ? n.intValue() : cr.platform.intSetting(setting); }

    /** Whether the runner takes new work now. */
    boolean takesWork(String deviceId) {
        Map<String, Object> d = db.one("SELECT maintenance, cpu_pct, mem_pct, cpu_max, mem_max FROM devices WHERE id = ?", deviceId);
        return d != null && whyNoWork(d) == null;
    }

    /** Starts (drain or stop) or ends (null) maintenance on runners. Stop also stops their running runs. */
    public Map<String, Object> setMaintenance(User by, List<String> ids, String mode, String ip) {
        cr.require(by, "devices.manage", "manage runners");
        if (ids == null || ids.isEmpty()) throw new OoaException("CR.BAD_INPUT", "Choose at least one runner");
        if (mode != null && !MAINTENANCE.contains(mode)) throw new OoaException("CR.BAD_INPUT", "Maintenance is drain (let runs finish) or stop (stop runs)");
        int stopped = db.tx(() -> {
            int n = 0;
            for (String id : ids) {
                if (db.one("SELECT id FROM devices WHERE id = ?", id) == null) throw notFound("Runner " + id);
                if (mode == null) db.update("UPDATE devices SET maintenance = NULL, maintenance_since = NULL, maintenance_by = NULL WHERE id = ?", id);
                else db.update("UPDATE devices SET maintenance = ?, maintenance_since = COALESCE(maintenance_since, ?), maintenance_by = ? WHERE id = ?", mode, now(), by.username(), id);
                if ("stop".equals(mode)) n += db.update("UPDATE runs SET cancel_requested = TRUE WHERE device_id = ? AND status = 'running'", id);
            }
            return n;
        });
        for (String id : ids) cr.wake(id, "stop".equals(mode));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("mode", mode == null ? "end" : mode);
        if (stopped > 0) details.put("runs_stopped", stopped);
        cr.audit.record(by.username(), mode == null ? "device.maintenance_end" : "device.maintenance", "device", String.join(",", ids), details, "ok", ip);
        return Map.of("updated", ids.size(), "runs_stopped", stopped);
    }

    /** Nickname and description (null: kept). */
    public void describe(User by, String id, String nickname, String description, String ip) {
        cr.require(by, "devices.manage", "manage runners");
        if (nickname != null && nickname.trim().length() > 120) throw new OoaException("CR.BAD_INPUT", "Nicknames have up to 120 characters");
        if (description != null && description.length() > 1000) throw new OoaException("CR.BAD_INPUT", "Descriptions have up to 1000 characters");
        if (db.one("SELECT id FROM devices WHERE id = ?", id) == null) throw notFound("Runner");
        Map<String, Object> changes = new LinkedHashMap<>();
        if (nickname != null) { db.update("UPDATE devices SET nickname = ? WHERE id = ?", nickname.isBlank() ? null : nickname.trim(), id); changes.put("nickname", nickname.trim()); }
        if (description != null) { db.update("UPDATE devices SET description = ? WHERE id = ?", description.isBlank() ? null : description, id); changes.put("description", "changed"); }
        cr.audit.record(by.username(), "device.describe", "device", id, changes, "ok", ip);
    }

    /** Asks the runner for its logs, or (when Settings allow it) a picture of its screen, with its next heartbeat. */
    public Map<String, Object> request(User by, String id, String kind, String ip) {
        cr.require(by, "devices.manage", "manage runners");
        if (!kind.equals("logs") && !kind.equals("screen")) throw notFound("Runner file " + kind);
        if (kind.equals("screen") && cr.platform.intSetting("runner_screen_pictures") != 1)
            throw new OoaException("CR.SCREEN_OFF", "Screen pictures of runners are off", "An administrator can allow them in Settings (Runners).");
        if (db.update("UPDATE devices SET " + (kind.equals("logs") ? "logs_requested" : "screen_requested") + " = ? WHERE id = ?", now(), id) == 0) throw notFound("Runner");
        db.update("UPDATE runner_files SET requested_by = ? WHERE device_id = ? AND kind = ?", by.username(), id, kind);
        pendingBy.put(id + "/" + kind, by.username());
        cr.wake(id, true);
        cr.audit.record(by.username(), kind.equals("logs") ? "device.logs_request" : "device.screen_request", "device", id, null, "ok", ip);
        return Map.of("requested", true);
    }

    /** Who asked for a file a runner has not sent yet (the runner's answer carries no user). */
    private final Map<String, String> pendingBy = new java.util.concurrent.ConcurrentHashMap<>();

    /** The latest logs or screen picture of a runner: {taken, requested_by, content or error, waiting (asked, not answered yet), unanswered}. */
    public Map<String, Object> file(User by, String id, String kind) {
        cr.require(by, "devices.manage", "manage runners");
        Map<String, Object> d = db.one("SELECT logs_requested, screen_requested FROM devices WHERE id = ?", id);
        if (d == null) throw notFound("Runner");
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> f = db.one("SELECT taken, requested_by, error, content FROM runner_files WHERE device_id = ? AND kind = ?", id, kind);
        if (f != null) out.putAll(f);
        Object asked = d.get(kind.equals("logs") ? "logs_requested" : "screen_requested");
        if (asked != null) {
            out.put("asked", asked);
            out.put(Instant.parse(asked.toString()).isBefore(now().minus(ANSWER_WITHIN)) ? "unanswered" : "waiting", true);
        }
        return out;
    }

    /** Reads the runner's CPU and memory use from a heartbeat, and adds what it was asked to send. */
    void heartbeat(Map<String, Object> d, Map<String, Object> info, Map<String, Object> out) {
        String id = (String) d.get("id");
        Integer cpu = percent(info.get("cpu")), mem = percent(info.get("mem"));
        if (cpu != null || mem != null) db.update("UPDATE devices SET cpu_pct = ?, mem_pct = ? WHERE id = ?", cpu, mem, id);
        if (d.get("logs_requested") != null) out.put("send_logs", true);
        if (d.get("screen_requested") != null && cr.platform.intSetting("runner_screen_pictures") == 1) out.put("send_screen", true);
        if (d.get("maintenance") != null) out.put("maintenance", d.get("maintenance"));
    }

    static Integer percent(Object v) { return v instanceof Number n && n.doubleValue() >= 0 && n.doubleValue() <= 100 ? (int) Math.round(n.doubleValue()) : null; }

    /** What the runner sends back: its logs ({text}) or a screen picture ({image: Base64 JPEG}); or {error} when it could not. */
    public void receive(Map<String, Object> d, String kind, Map<String, Object> b) {
        String id = (String) d.get("id");
        String column = kind.equals("logs") ? "logs_requested" : "screen_requested";
        Map<String, Object> asked = db.one("SELECT " + column + " AS asked FROM devices WHERE id = ?", id);
        if (asked == null || asked.get("asked") == null) throw new OoaException("CR.NOT_ASKED", "Nothing was asked of this runner");
        String error = b.get("error") instanceof String e && !e.isBlank() ? e.substring(0, Math.min(e.length(), 1000)) : null;
        String content = null;
        if (error == null && kind.equals("logs")) {
            content = String.valueOf(b.getOrDefault("text", ""));
            if (content.length() > MAX_LOG_CHARS) content = content.substring(content.length() - MAX_LOG_CHARS);
        } else if (error == null) {
            content = String.valueOf(b.getOrDefault("image", ""));
            if (content.length() > MAX_PICTURE_CHARS || !content.matches("[A-Za-z0-9+/=]+")) throw new OoaException("CR.BAD_INPUT", "The screen picture is not Base64 or is too large");
        }
        String who = pendingBy.remove(id + "/" + kind);
        String c = content;
        db.tx(() -> {
            db.update("DELETE FROM runner_files WHERE device_id = ? AND kind = ?", id, kind);
            db.update("INSERT INTO runner_files (device_id, kind, taken, requested_by, error, content) VALUES (?,?,?,?,?,?)", id, kind, now(), who, error, c);
            db.update("UPDATE devices SET " + column + " = NULL WHERE id = ?", id);
            return null;
        });
        cr.audit.record("agent:" + d.get("name"), kind.equals("logs") ? "device.logs" : "device.screen", "device", id, error == null ? null : Map.of("error", error), error == null ? "ok" : "failed", null);
    }
}
