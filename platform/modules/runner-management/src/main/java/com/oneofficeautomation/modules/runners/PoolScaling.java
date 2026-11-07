package com.oneofficeautomation.modules.runners;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.Db;
import com.oneofficeautomation.controlroom.module.ModuleContext;

import static com.oneofficeautomation.controlroom.module.ModuleContext.*;

import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;

import static com.oneofficeautomation.controlroom.module.ModuleContext.now;

/**
 * Auto-scaling runner pools (gap G52): One CommandCenter works out how many runner machines a pool needs from its work
 * and asks the customer's cloud for that many. Machines that start register as runners of the pool (temporary runners,
 * cleaned up when they go offline); machines that are removed simply go offline.
 * <p>
 * Each pool's scaling is kept in the settings row pool_scaling:&lt;pool id&gt;: on/off, min and max machines, runs per
 * machine, minutes between changes, and where the size goes: a webhook (signed POST with the desired size, for any cloud
 * or tool), an AWS Auto Scaling group (SetDesiredCapacity) or an Azure virtual machine scale set (its capacity). Secrets
 * are vault references. The demand is the pool's queued and running runs, plus runs refused in the last minutes because
 * no runner of the pool was online, so a pool can scale up from zero.
 */
public final class PoolScaling {
    private final ModuleContext ctx;
    private final Db db;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).followRedirects(HttpClient.Redirect.NEVER).build();
    /** pool id -> when a run found no runner of it online (demand that has nowhere to go yet). */
    final Map<String, Deque<Instant>> refused = new ConcurrentHashMap<>();
    /** pool id -> the size last asked for and when. */
    public final Map<String, Object[]> last = new ConcurrentHashMap<>();
    public static final List<String> PROVIDERS = List.of("webhook", "aws_asg", "azure_vmss");

    public PoolScaling(ModuleContext ctx) { this.ctx = ctx; this.db = ctx.db(); }

    private static String key(String pool) { return "pool_scaling:" + pool; }

    @SuppressWarnings("unchecked")
    public Map<String, Object> config(String pool) {
        Map<String, Object> r = db.one("SELECT json_value FROM settings WHERE name = ?", key(pool));
        return r == null ? null : (Map<String, Object>) JsonLite.parse((String) r.get("json_value"));
    }

    /** The pool's scaling with what it would ask for now. */
    public Map<String, Object> get(User by, String pool) {
        ctx.require(by, "devices.manage", "see runner pool scaling");
        if (db.one("SELECT id FROM device_pools WHERE id = ?", pool) == null) throw notFound("Runner pool");
        Map<String, Object> c = config(pool);
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("scaling", c == null ? Map.of("enabled", false) : c);
        out.put("now", c == null ? demand(pool, Map.of("min", 0, "max", 0, "runs_per_runner", 1)) : demand(pool, c));
        return out;
    }

    /** Saves a pool's scaling after checking it. */
    public Map<String, Object> put(User by, String pool, Map<String, Object> in, String ip) {
        ctx.require(by, "devices.manage", "set runner pool scaling");
        if (db.one("SELECT id FROM device_pools WHERE id = ?", pool) == null) throw notFound("Runner pool");
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("enabled", Boolean.TRUE.equals(in.get("enabled")));
        int min = num(in.get("min"), 0, 0, 500, "min"), max = num(in.get("max"), 1, 0, 500, "max");
        if (max < min) throw new OoaException("CR.BAD_INPUT", "The most machines cannot be fewer than the fewest");
        c.put("min", min); c.put("max", max);
        c.put("runs_per_runner", num(in.get("runs_per_runner"), 1, 1, 100, "runs_per_runner"));
        c.put("cooldown_minutes", num(in.get("cooldown_minutes"), 5, 1, 1440, "cooldown_minutes"));
        String provider = String.valueOf(in.getOrDefault("provider", "webhook"));
        if (!PROVIDERS.contains(provider)) throw new OoaException("CR.BAD_INPUT", "The cloud is webhook, aws_asg or azure_vmss");
        c.put("provider", provider);
        switch (provider) {
            case "webhook" -> { c.put("url", https(in.get("url"), "The webhook")); c.put("secret_ref", ref(in.get("secret_ref"), false)); }
            case "aws_asg" -> {
                c.put("group", text(in.get("group"), "The Auto Scaling group"));
                c.put("region", text(in.get("region"), "The AWS region"));
                if (!String.valueOf(c.get("region")).matches("[a-z]{2}(-gov)?-[a-z]+-\\d")) throw new OoaException("CR.BAD_INPUT", "Not an AWS region: " + c.get("region"));
                c.put("access_key_ref", ref(in.get("access_key_ref"), true)); c.put("secret_key_ref", ref(in.get("secret_key_ref"), true));
            }
            default -> {
                for (String k : List.of("subscription", "resource_group", "scale_set", "tenant", "client_id")) c.put(k, text(in.get(k), k.replace('_', ' ')));
                c.put("client_secret_ref", ref(in.get("client_secret_ref"), true));
            }
        }
        String json = JsonLite.write(c);
        db.tx(() -> {
            db.update("DELETE FROM settings WHERE name = ?", key(pool));
            db.update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?,?,?,?)", key(pool), json, now(), by.username());
            return null;
        });
        ctx.audit(by.username(), "pool.scaling_set", "pool", pool, Map.of("enabled", c.get("enabled"), "min", min, "max", max, "provider", provider), "ok", ip);
        return get(by, pool);
    }

    /** What the pool needs: its runs waiting and running, runs refused for lack of a runner, and machines online. */
    public Map<String, Object> demand(String pool, Map<String, Object> c) {
        Map<String, Object> p = db.one("SELECT devices FROM device_pools WHERE id = ?", pool);
        List<String> devs = p == null ? List.of() : ModuleContext.csv(p.get("devices"));
        long queued = 0, running = 0, online = 0;
        Instant fresh = now().minus(ModuleContext.ONLINE);
        for (String d : devs) {
            queued += ctx.count("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND status = 'queued'", d);
            running += ctx.count("SELECT COUNT(*) AS n FROM runs WHERE device_id = ? AND status = 'running'", d);
            Map<String, Object> dev = db.one("SELECT last_seen, enabled FROM devices WHERE id = ?", d);
            if (dev != null && Boolean.TRUE.equals(dev.get("enabled")) && dev.get("last_seen") != null && !Instant.parse(String.valueOf(dev.get("last_seen"))).isBefore(fresh)) online++;
        }
        Deque<Instant> r = refused.getOrDefault(pool, new ArrayDeque<>());
        Instant window = now().minus(Duration.ofMinutes(10));
        long waiting;
        synchronized (r) { r.removeIf(t -> t.isBefore(window)); waiting = r.size(); }
        int per = num(c.get("runs_per_runner"), 1, 1, 100, "runs_per_runner");
        int min = num(c.get("min"), 0, 0, 500, "min"), max = num(c.get("max"), 1, 0, 500, "max");
        long need = (queued + running + waiting + per - 1) / per;
        int desired = (int) Math.max(min, Math.min(max, need));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("queued", queued); m.put("running", running); m.put("refused_recently", waiting); m.put("online", online); m.put("desired", desired);
        return m;
    }

    /** A run found no runner of the pool online: counted as demand for a few minutes. */
    public void noRunner(String pool) {
        if (config(pool) == null) return;
        Deque<Instant> d = refused.computeIfAbsent(pool, k -> new ArrayDeque<>());
        synchronized (d) { d.add(now()); while (d.size() > 1000) d.removeFirst(); }
    }

    /** Every minute on the leader: each pool with scaling on gets the size it needs, at most once per cooldown. */
    public void tick() {
        for (Map<String, Object> row : db.query("SELECT name, json_value FROM settings WHERE name LIKE 'pool_scaling:%'")) {
            String pool = String.valueOf(row.get("name")).substring("pool_scaling:".length());
            try {
                @SuppressWarnings("unchecked") Map<String, Object> c = (Map<String, Object>) JsonLite.parse((String) row.get("json_value"));
                if (!Boolean.TRUE.equals(c.get("enabled"))) continue;
                int desired = ((Number) demand(pool, c).get("desired")).intValue();
                Object[] was = last.get(pool);
                if (was != null && (int) was[0] == desired) continue;
                if (was != null && ((Instant) was[1]).plus(Duration.ofMinutes(num(c.get("cooldown_minutes"), 5, 1, 1440, "cooldown"))).isAfter(now())) continue;
                apply(pool, c, desired);
                last.put(pool, new Object[]{desired, now()});
                ctx.audit("system", "pool.scale", "pool", pool, Map.of("desired", desired, "provider", c.get("provider")), "ok", null);
            } catch (RuntimeException e) {
                ctx.audit("system", "pool.scale", "pool", pool, String.valueOf(e.getMessage()), "failed", null);
            }
        }
    }

    /** Asks the cloud for the size. */
    public void apply(String pool, Map<String, Object> c, int desired) {
        switch (String.valueOf(c.get("provider"))) {
            case "webhook" -> {
                String body = JsonLite.write(Map.of("pool", pool, "desired", desired, "at", now().toString()));
                HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(String.valueOf(c.get("url")))).timeout(Duration.ofSeconds(20))
                        .POST(HttpRequest.BodyPublishers.ofString(body)).header("Content-Type", "application/json");
                String ref = String.valueOf(c.getOrDefault("secret_ref", ""));
                if (!ref.isEmpty()) b.header("X-OOA-Signature", "sha256=" + hmacHex(ctx.systemCredential(ref), body));
                send(b.build(), "The scaling webhook");
            }
            case "aws_asg" -> {
                String region = String.valueOf(c.get("region"));
                URI uri = URI.create("https://autoscaling." + region + ".amazonaws.com/");
                String body = "Action=SetDesiredCapacity&Version=2011-01-01&AutoScalingGroupName=" + URLEncoder.encode(String.valueOf(c.get("group")), StandardCharsets.UTF_8)
                        + "&DesiredCapacity=" + desired + "&HonorCooldown=false";
                try {
                    Map<String, String> h = ModuleContext.awsSigV4("POST", uri, Map.of("content-type", "application/x-www-form-urlencoded; charset=utf-8"), body,
                            ctx.systemCredential(String.valueOf(c.get("access_key_ref"))), ctx.systemCredential(String.valueOf(c.get("secret_key_ref"))), region, "autoscaling",
                            ZonedDateTime.now(ZoneOffset.UTC));
                    HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body));
                    h.forEach((k, v) -> { if (!k.equals("host")) b.header(k, v); });
                    send(b.build(), "AWS Auto Scaling");
                } catch (OoaException e) { throw e; } catch (Exception e) { throw new OoaException("CR.SCALE_FAILED", "Signing for AWS failed: " + e.getMessage()); }
            }
            default -> {
                String token = azureToken(c);
                String url = "https://management.azure.com/subscriptions/" + seg(c.get("subscription")) + "/resourceGroups/" + seg(c.get("resource_group"))
                        + "/providers/Microsoft.Compute/virtualMachineScaleSets/" + seg(c.get("scale_set")) + "?api-version=2024-07-01";
                send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(30)).header("Authorization", "Bearer " + token).header("Content-Type", "application/json")
                        .method("PATCH", HttpRequest.BodyPublishers.ofString(JsonLite.write(Map.of("sku", Map.of("capacity", desired))))).build(), "Azure");
            }
        }
    }

    /** Endpoints for tests (a local stand-in of the cloud). */
    String azureLogin = "https://login.microsoftonline.com/";

    @SuppressWarnings("unchecked")
    private String azureToken(Map<String, Object> c) {
        String body = "grant_type=client_credentials&client_id=" + URLEncoder.encode(String.valueOf(c.get("client_id")), StandardCharsets.UTF_8)
                + "&client_secret=" + URLEncoder.encode(ctx.systemCredential(String.valueOf(c.get("client_secret_ref"))), StandardCharsets.UTF_8)
                + "&scope=" + URLEncoder.encode("https://management.azure.com/.default", StandardCharsets.UTF_8);
        String r = send(HttpRequest.newBuilder(URI.create(azureLogin + seg(c.get("tenant")) + "/oauth2/v2.0/token")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(body)).build(), "Azure sign-in");
        Object t = ((Map<String, Object>) JsonLite.parse(r)).get("access_token");
        if (!(t instanceof String s)) throw new OoaException("CR.SCALE_FAILED", "Azure gave no access token");
        return s;
    }

    private String send(HttpRequest req, String what) {
        if (ctx.tenant() != null) ctx.checkHost(req.uri().toString(), what);
        try {
            HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
            if (r.statusCode() / 100 != 2) throw new OoaException("CR.SCALE_FAILED", what + " answered HTTP " + r.statusCode() + ": " + r.body().substring(0, Math.min(300, r.body().length())));
            return r.body();
        } catch (java.io.IOException e) { throw new OoaException("CR.SCALE_FAILED", "Cannot reach " + what + ": " + e.getMessage()); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("CR.SCALE_FAILED", "Interrupted"); }
    }

    public static String hmacHex(String secret, String body) {
        try {
            javax.crypto.Mac m = javax.crypto.Mac.getInstance("HmacSHA256");
            m.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(m.doFinal(body.getBytes(StandardCharsets.UTF_8)));
        } catch (Exception e) { throw new IllegalStateException(e); }
    }

    private static String seg(Object v) { return URLEncoder.encode(String.valueOf(v), StandardCharsets.UTF_8).replace("+", "%20"); }

    private static int num(Object v, int dflt, int lo, int hi, String what) {
        int n = v instanceof Number x ? x.intValue() : v == null || String.valueOf(v).isBlank() ? dflt : parse(v, what);
        if (n < lo || n > hi) throw new OoaException("CR.BAD_INPUT", what.replace('_', ' ') + " is " + lo + " to " + hi);
        return n;
    }

    private static int parse(Object v, String what) {
        try { return Integer.parseInt(String.valueOf(v).strip()); } catch (NumberFormatException e) { throw new OoaException("CR.BAD_INPUT", what.replace('_', ' ') + " is a number"); }
    }

    private static String https(Object v, String what) {
        String s = v == null ? "" : String.valueOf(v).strip();
        // http only to this computer (a scaling script beside One CommandCenter, and tests)
        if (!s.matches("(?i)https://[^\\s]+") && !s.matches("(?i)http://(127\\.0\\.0\\.1|localhost)(:\\d+)?(/[^\\s]*)?"))
            throw new OoaException("CR.BAD_INPUT", what + " is an https:// address");
        return s;
    }

    private static String ref(Object v, boolean required) {
        String s = v == null ? "" : String.valueOf(v).strip();
        if (s.isEmpty() && !required) return "";
        if (s.split("/").length != 3) throw new OoaException("CR.BAD_INPUT", "Secrets are vault references: vault folder/credential/attribute");
        return s;
    }

    private static String text(Object v, String what) {
        String s = v == null ? "" : String.valueOf(v).strip();
        if (s.isEmpty() || s.length() > 200 || s.contains("/")) throw new OoaException("CR.BAD_INPUT", "Give " + what);
        return s;
    }
}
