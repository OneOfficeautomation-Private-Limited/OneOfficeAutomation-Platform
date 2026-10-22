package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;
import java.util.function.Function;

/**
 * Server-side triggers: the Command Center polls mailboxes (IMAP, Microsoft 365 Outlook), ServiceNow tables and any
 * HTTP endpoint on each trigger's interval, and starts the trigger's agent once per new message, record or item.
 * Progress is kept in the trigger row: last_hash is the cursor (last UID, received time or update time), and the
 * universal poller keeps short hashes of the item ids it has seen in its config under "_seen".
 */
public final class TriggerPollers {
    static final int MAX_EVENTS = 50, MAX_SEEN = 300;
    private final ControlRoom cr;
    private final Map<String, Long> nextDue = new ConcurrentHashMap<>();
    private final Set<String> busy = ConcurrentHashMap.newKeySet();
    private final ExecutorService pool = Executors.newFixedThreadPool(4, r -> { Thread t = new Thread(r, "ooa-cr-poller"); t.setDaemon(true); return t; });
    /** Opens the mailbox of an email or outlook trigger; replaced in tests. */
    Function<Map<String, Object>, MailSource> mailSources = this::openMail;

    /** The trigger, its config and a way to read the vault references it names. */
    private record Ctx(Map<String, Object> trigger, Map<String, Object> config) {}

    TriggerPollers(ControlRoom cr) { this.cr = cr; }

    /** One poll result: the new cursor, the payloads to fire for, and (universal poller) the ids seen. */
    record Poll(String cursor, List<Map<String, Object>> payloads, List<String> seen) {}

    /** Called by the server clock: starts the polls that are due, at most one at a time per trigger. */
    public void tick() {
        long now = System.currentTimeMillis();
        for (Map<String, Object> t : cr.db.query("SELECT id, kind, config FROM triggers WHERE enabled = TRUE AND kind IN ('email', 'outlook', 'servicenow', 'http_poll')")) {
            String id = (String) t.get("id");
            if (nextDue.getOrDefault(id, 0L) > now || busy.contains(id)) continue;
            int every = ((Number) config(t).getOrDefault("interval_seconds", TriggerKinds.DEFAULT_INTERVAL)).intValue();
            nextDue.put(id, now + every * 1000L);
            busy.add(id);
            pool.submit(() -> { try { pollNow(id); } catch (RuntimeException ignored) { } finally { busy.remove(id); } });
        }
    }

    /** Forget the schedule of a trigger, so it is polled at the next tick (after resume or a change). */
    void reset(String id) { nextDue.remove(id); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> config(Map<String, Object> t) {
        Object c = t.get("config");
        if (c == null || String.valueOf(c).isBlank()) return new LinkedHashMap<>();
        try { return new LinkedHashMap<>((Map<String, Object>) JsonLite.parse(String.valueOf(c))); } catch (RuntimeException e) { return new LinkedHashMap<>(); }
    }

    /**
     * Polls one trigger now and starts its agent once per new item. Returns how many runs started and the error, if
     * any; the error is also kept in last_error, and the cursor in last_hash.
     */
    @SuppressWarnings("unchecked")
    public Map<String, Object> pollNow(String id) {
        Map<String, Object> t = cr.db.one("SELECT * FROM triggers WHERE id = ?", id);
        if (t == null) throw ControlRoom.notFound("Trigger");
        String kind = (String) t.get("kind");
        Map<String, Object> cfg = config(t);
        Ctx c = new Ctx(t, cfg);
        String cursor = (String) t.get("last_hash");
        boolean fireExisting = Boolean.TRUE.equals(cfg.get("fire_existing"));
        Poll p;
        try {
            p = switch (kind) {
                case "email", "outlook" -> pollMail(mailSources.apply(withSecrets(c)), cfg, cursor, fireExisting);
                case "servicenow" -> pollServiceNow(cfg, cursor, fireExisting, ref -> secret(c, ref));
                case "http_poll" -> pollHttp(cfg, cursor, (List<String>) cfg.getOrDefault("_seen", List.of()), fireExisting, ref -> secret(c, ref));
                default -> throw new OoaException("CR.BAD_TRIGGER", "Trigger " + id + " is not polled by the Command Center");
            };
        } catch (IOException | RuntimeException e) {
            String msg = e instanceof IOException ? e.getMessage() : e instanceof OoaException ? e.getMessage() : e.toString();
            cr.db.update("UPDATE triggers SET last_error = ? WHERE id = ?", clip(msg), id);
            return Map.of("fired", 0, "error", String.valueOf(msg));
        }
        String input = switch (kind) { case "servicenow" -> "trigger_record"; case "http_poll" -> "trigger_item"; default -> "trigger_email"; };
        int fired = 0;
        String error = null;
        for (Map<String, Object> payload : p.payloads()) {
            Map<String, Object> ev = new LinkedHashMap<>(payload);
            ev.put("kind", kind);
            ev.put("trigger_id", id);
            ev.put("trigger_name", t.get("name"));
            ev.put("time", Instant.now().toString());
            try { cr.fireTrigger(t, Map.of(input, payload, "trigger_event", ev), "trigger:" + id, null); fired++; }
            catch (OoaException e) { error = "Could not start the agent: " + e.getMessage(); }
        }
        if (p.seen() != null) { cfg.put("_seen", p.seen()); cr.db.update("UPDATE triggers SET config = ? WHERE id = ?", JsonLite.write(cfg), id); }
        cr.db.update("UPDATE triggers SET last_hash = ?, last_error = ? WHERE id = ?", clip64(p.cursor()), error == null ? null : clip(error), id);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("fired", fired);
        out.put("error", error);
        return out;
    }

    private static String clip(String s) { return s == null ? null : s.length() > 1900 ? s.substring(0, 1900) : s; }
    private static String clip64(String s) { return s == null ? null : s.length() > 64 ? s.substring(0, 64) : s; }

    // ---------- vault ----------
    /** A vault secret for a server-side trigger: the trigger's owner must be a consumer of the vault folder, as for runs. */
    String secret(Ctx c, String ref) {
        Map<String, Object> t = c.trigger();
        String actor = "trigger:" + t.get("id") + " for " + t.get("created_by");
        String[] p = ref == null ? new String[0] : ref.split("/");
        Map<String, Object> l = p.length == 3 ? cr.db.one("SELECT * FROM lockers WHERE name = ?", p[0]) : null;
        Map<String, Object> u = cr.db.one("SELECT role FROM users WHERE username = ? AND enabled = TRUE", t.get("created_by"));
        if (l == null || u == null || !cr.consumes((String) t.get("created_by"), (String) u.get("role"), l)) {
            cr.audit.record(actor, "credential.read", "credential", ref, Map.of("trigger", t.get("id")), "denied", null);
            throw new OoaException("VAULT.DENIED", "'" + t.get("created_by") + "' may not use vault folder '" + (p.length > 0 ? p[0] : ref) + "'",
                    "Ask the vault folder owner to add the trigger's owner as a consumer.");
        }
        Map<String, Object> row = cr.db.one("SELECT ciphertext FROM credentials WHERE locker = ? AND name = ? AND attribute = ?", p[0], p[1], p[2]);
        if (row == null) { cr.audit.record(actor, "credential.read", "credential", ref, Map.of("trigger", t.get("id")), "not_found", null); throw new OoaException("VAULT.NOT_FOUND", "Credential '" + ref + "' not found"); }
        cr.audit.record(actor, "credential.read", "credential", ref, Map.of("trigger", t.get("id")), "ok", null);
        return cr.vault.decrypt((String) row.get("ciphertext"), ref);
    }

    /** The trigger's config with its vault references resolved, for opening a mailbox. */
    private Map<String, Object> withSecrets(Ctx c) {
        Map<String, Object> m = new LinkedHashMap<>(c.config());
        m.put("kind", c.trigger().get("kind"));
        for (String k : List.of("password_ref", "token_ref", "client_secret_ref"))
            if (m.get(k) instanceof String ref) m.put(k.replace("_ref", ""), secret(c, ref));
        return m;
    }

    private MailSource openMail(Map<String, Object> m) {
        if ("email".equals(m.get("kind")))
            return new ImapMailSource((String) m.get("host"), ((Number) m.get("port")).intValue(), !Boolean.FALSE.equals(m.get("ssl")), (String) m.get("username"),
                    (String) m.get("password"), (String) m.getOrDefault("mail_folder", "INBOX"), 30_000);
        String token = (String) m.get("token");
        if (token == null) {
            try { token = GraphMailSource.clientCredentialsToken((String) m.get("login_url"), (String) m.get("tenant_id"), (String) m.get("client_id"), (String) m.get("client_secret")); }
            catch (IOException e) { throw new OoaException("CR.TRIGGER_SIGN_IN", "Microsoft sign-in failed: " + e.getMessage()); }
        }
        return new GraphMailSource((String) m.get("graph_url"), (String) m.get("mailbox"), (String) m.get("mail_folder"), token);
    }

    // ---------- pollers (static, tested with fakes) ----------
    static Poll pollMail(MailSource src, Map<String, Object> cfg, String cursor, boolean fireExisting) throws IOException {
        MailSource.Batch b = src.fetch(cursor, fireExisting);
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> m : b.messages()) if (MailSource.matches(m, cfg) && out.size() < MAX_EVENTS) out.add(m);
        return new Poll(b.cursor(), out, null);
    }

    /**
     * New or updated ServiceNow records matching the encoded query, oldest change first. The cursor is
     * "sys_updated_on|sys_id" of the last record handled (UTC, as the Table API returns it).
     */
    @SuppressWarnings("unchecked")
    static Poll pollServiceNow(Map<String, Object> cfg, String cursor, boolean fireExisting, Function<String, String> secrets) throws IOException {
        String base = String.valueOf(cfg.get("instance_url")).replaceAll("/+$", "") + "/api/now/table/" + cfg.get("table");
        Map<String, String> headers = new LinkedHashMap<>();
        if (cfg.get("token_ref") instanceof String tr) headers.put("Authorization", "Bearer " + secrets.apply(tr));
        else headers.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((cfg.get("username") + ":" + secrets.apply((String) cfg.get("password_ref"))).getBytes(StandardCharsets.UTF_8)));
        String q = cfg.get("query") instanceof String s && !s.isBlank() ? s + "^" : "";
        String common = "&sysparm_display_value=false&sysparm_exclude_reference_link=true";
        if (cursor == null || !cursor.contains("|")) {
            if (fireExisting) cursor = "1970-01-01 00:00:00|";
            else {
                Object r = PollHttp.getJson(base + "?sysparm_query=" + PollHttp.enc(q + "ORDERBYDESCsys_updated_on") + "&sysparm_limit=1&sysparm_fields=sys_id,sys_updated_on" + common, headers);
                List<Object> rows = r instanceof Map<?, ?> m && m.get("result") instanceof List<?> l ? (List<Object>) l : List.of();
                if (rows.isEmpty()) return new Poll("1970-01-01 00:00:00|", List.of(), null);
                Map<String, Object> top = (Map<String, Object>) rows.get(0);
                return new Poll(top.get("sys_updated_on") + "|" + top.get("sys_id"), List.of(), null);
            }
        }
        String ts = cursor.substring(0, cursor.indexOf('|')), lastId = cursor.substring(cursor.indexOf('|') + 1);
        Object r = PollHttp.getJson(base + "?sysparm_query=" + PollHttp.enc(q + "sys_updated_on>=" + ts + "^ORDERBYsys_updated_on^ORDERBYsys_id")
                + "&sysparm_limit=" + (MAX_EVENTS * 2) + common, headers);
        List<Object> rows = r instanceof Map<?, ?> m && m.get("result") instanceof List<?> l ? (List<Object>) l : List.of();
        List<Map<String, Object>> out = new ArrayList<>();
        String newCursor = cursor;
        for (Object o : rows) {
            Map<String, Object> rec = new LinkedHashMap<>((Map<String, Object>) o);
            String u = String.valueOf(rec.get("sys_updated_on")), sid = String.valueOf(rec.get("sys_id"));
            if (u.compareTo(ts) < 0 || (u.equals(ts) && sid.compareTo(lastId) <= 0)) continue;
            if (out.size() >= MAX_EVENTS) break;
            rec.put("change", String.valueOf(rec.get("sys_created_on")).equals(u) ? "new" : "updated");
            rec.put("table", cfg.get("table"));
            out.add(rec);
            newCursor = u + "|" + sid;
        }
        return new Poll(newCursor, out, null);
    }

    /** Universal poller: calls the URL, takes the items at items_path and fires once per item whose id_field is new. */
    static Poll pollHttp(Map<String, Object> cfg, String cursor, List<String> seen, boolean fireExisting, Function<String, String> secrets) throws IOException {
        Map<String, String> headers = new LinkedHashMap<>();
        if (cfg.get("headers") instanceof Map<?, ?> h) h.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
        if (cfg.get("auth_ref") instanceof String ar) headers.put("Authorization", "Bearer " + secrets.apply(ar));
        Object json = PollHttp.getJson(String.valueOf(cfg.get("url")), headers);
        List<Object> items = JsonPath.compile(String.valueOf(cfg.getOrDefault("items_path", "$"))).items(json);
        JsonPath idPath = JsonPath.compile(String.valueOf(cfg.get("id_field")));
        boolean baseline = cursor == null && !fireExisting;
        LinkedHashSet<String> known = new LinkedHashSet<>(seen == null ? List.of() : seen);
        List<Map<String, Object>> out = new ArrayList<>();
        String last = cursor;
        int missingIds = 0;
        for (Object item : items) {
            Object idv = idPath.first(item);
            if (idv == null) { missingIds++; continue; }
            String h = hash(String.valueOf(idv));
            last = h;
            if (!known.add(h) || baseline) continue;
            if (out.size() >= MAX_EVENTS) { known.remove(h); continue; }   // the rest fire at the next poll
            Map<String, Object> payload = new LinkedHashMap<>();
            if (item instanceof Map<?, ?> m) m.forEach((k, v) -> payload.put(String.valueOf(k), v)); else payload.put("value", item);
            payload.put("item_id", String.valueOf(idv));
            out.add(payload);
        }
        if (!items.isEmpty() && missingIds == items.size()) throw new IOException("No item has the id field '" + cfg.get("id_field") + "'");
        List<String> keep = new ArrayList<>(known);
        if (keep.size() > MAX_SEEN) keep = keep.subList(keep.size() - MAX_SEEN, keep.size());
        return new Poll(last == null ? "none" : last, out, keep);
    }

    static String hash(String id) {
        try { return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(id.getBytes(StandardCharsets.UTF_8)), 0, 8); }
        catch (NoSuchAlgorithmException e) { throw new IllegalStateException(e); }
    }

    void stop() { pool.shutdownNow(); }
}
