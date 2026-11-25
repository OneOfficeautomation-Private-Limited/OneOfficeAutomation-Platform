package com.oneofficeautomation.modules.chat;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.ActionCatalog;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.*;
import java.util.function.Function;

/**
 * Whether a OneChat model server behind a Model Connection was built for this release (S6, 25 Nov 2026; contract
 * section 16): for each OpenAI-compatible connection whose model is one of OneChat's (ooa-assistant,
 * ooa-flow-assistant...), it asks the server's version route and compares the action catalog and flow rules the
 * model was built from with this release's. Information for administrators only: Studio keeps using the connection,
 * and a server that does not answer only makes this check say so.
 */
public final class OneChatModelCheck {
    static final String CONTRACT = "studio-assistant/v1";
    /** The environment (tests replace it): a connection's api_key_env is read from it. */
    public static Function<String, String> env = System::getenv;

    private final ModuleContext ctx;
    private final HttpClient http = HttpClient.newBuilder().version(HttpClient.Version.HTTP_1_1).connectTimeout(Duration.ofSeconds(5)).build();
    private volatile Map<String, Object> ours;

    OneChatModelCheck(ModuleContext ctx) { this.ctx = ctx; }

    /** Whether a connection's model is one of OneChat's model names. */
    static boolean isOneChat(Map<String, Object> c) {
        String model = String.valueOf(c.getOrDefault("model", ""));
        return "openai_compatible".equals(c.get("provider")) && (model.equals("ooa-assistant") || model.startsWith("ooa-flow-assistant"));
    }

    /** This release's catalog checksum and size, and the flow rules' checksums (as written, and with "\n" line ends). */
    Map<String, Object> ours() {
        Map<String, Object> o = ours;
        if (o != null) return o;
        Map<String, Object> m = new LinkedHashMap<>(ActionCatalog.header(ctx.toolkitManifests()));
        try (InputStream in = OneChatModelCheck.class.getResourceAsStream("/flow.v1.json")) {
            if (in != null) {
                byte[] b = in.readAllBytes();
                m.put("schema_sha256", ActionCatalog.sha256(b));
                m.put("schema_sha256_lf", ActionCatalog.sha256(new String(b, StandardCharsets.UTF_8).replace("\r\n", "\n").getBytes(StandardCharsets.UTF_8)));
            }
        } catch (IOException ignored) { /* no flow rules on the class path: the rules are not compared */ }
        ours = m;
        return m;
    }

    /** {catalog: this release's header, connections: [one result per OneChat connection]}. */
    @SuppressWarnings("unchecked")
    Map<String, Object> check(User u) {
        Map<String, Object> all = ctx.modelConnectionsFor(ctx.rolesOf(u.username(), u.role()));
        List<Map<String, Object>> out = new ArrayList<>();
        for (Map<String, Object> c : (List<Map<String, Object>>) all.getOrDefault("connections", List.of()))
            if (isOneChat(c)) out.add(one(c));
        return Map.of("catalog", ours(), "connections", out);
    }

    /** One connection: {connection, status, message, version?, model_step?, catalog_actions?}. */
    Map<String, Object> one(Map<String, Object> c) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("connection", c.get("name"));
        String base = String.valueOf(c.getOrDefault("base_url", "")).replaceAll("/+$", "").replaceAll("/v1$", "");
        Map<?, ?> v;
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/api/assistant/v1/admin/version")).timeout(Duration.ofSeconds(5)).GET();
            String key = key(c);
            if (!key.isEmpty()) b.header("Authorization", "Bearer " + key);
            HttpResponse<String> resp = http.send(b.build(), HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
            v = resp.statusCode() == 200 && JsonLite.parse(resp.body()) instanceof Map<?, ?> m ? m : null;
            if (v == null) return status(r, "unreachable", "The OneChat model server at " + base + " answered HTTP " + resp.statusCode() + " to its version route.");
        } catch (IOException | RuntimeException e) {
            return status(r, "unreachable", "The OneChat model server at " + base + " is not answering.");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return status(r, "unreachable", "Interrupted.");
        }
        r.put("version", v.get("version"));
        r.put("model_step", v.get("model_step"));
        r.put("catalog_actions", v.get("catalog_actions"));
        if (!CONTRACT.equals(String.valueOf(v.get("contract"))))
            return status(r, "wrong_contract", "It speaks " + v.get("contract") + ", not " + CONTRACT + ".");
        Map<String, Object> o = ours();
        List<String> differs = new ArrayList<>();
        Object theirCatalog = v.get("platform_catalog_sha256");
        boolean catalogKnown = theirCatalog != null;
        if (catalogKnown && !theirCatalog.equals(o.get("sha256"))) differs.add("action catalog");
        Object theirSchema = v.get("schema_sha256");
        if (theirSchema != null && o.get("schema_sha256") != null && !theirSchema.equals(o.get("schema_sha256")) && !theirSchema.equals(o.get("schema_sha256_lf")))
            differs.add("flow rules");
        if (!differs.isEmpty())
            return status(r, "built_for_another_release", "Its model was built from another release's " + String.join(" and ", differs)
                    + ": it may suggest commands this release does not have, or miss new ones (Studio still checks every step). Rebuild it from this release's catalog (ooa catalog).");
        if (!catalogKnown)
            return status(r, "catalog_unknown", "It does not say which action catalog it was built from (platform_catalog_sha256). It knows "
                    + v.get("catalog_actions") + " entries; this release has " + o.get("entries") + ".");
        return status(r, "compatible", "OneChat " + v.get("version") + " was built from this release's action catalog and flow rules.");
    }

    private static Map<String, Object> status(Map<String, Object> r, String status, String message) {
        r.put("status", status);
        r.put("message", message);
        return r;
    }

    /** The connection's key: from the vault, or the environment variable it names; empty when it has none. */
    private String key(Map<String, Object> c) {
        if (c.get("api_key") instanceof Map<?, ?> k && k.get("$credential") instanceof String ref) return ctx.systemCredential(ref);
        if (c.get("api_key_env") instanceof String name && !name.isBlank()) return Objects.requireNonNullElse(env.apply(name), "");
        return "";
    }

    // ---- what people did with a suggestion, for OneChat's training (S6, contract section 16) ----

    /** OneChat's modes (contract section 16): Auto is the plain model name, the others add -fast, -smart or -ultra. */
    static final List<String> MODES = List.of("auto", "fast", "smart", "ultra");

    /** The model name for a mode: ooa-flow-assistant (Auto), or with -fast, -smart or -ultra. */
    static String withMode(String mode) {
        String m = mode == null || mode.isBlank() ? "auto" : mode.trim().toLowerCase(Locale.ROOT);
        if (!MODES.contains(m)) throw new OoaException("CR.BAD_INPUT", "How hard it works is auto, fast, smart or ultra", "Pick Auto, Fast, Smart or Ultra.");
        return m.equals("auto") ? "ooa-flow-assistant" : "ooa-flow-assistant-" + m;
    }

    /** A connection the person may use, by name (blank: their default), or null. */
    @SuppressWarnings("unchecked")
    Map<String, Object> connection(User u, String name) {
        Map<String, Object> all = ctx.modelConnectionsFor(ctx.rolesOf(u.username(), u.role()));
        String n = name == null || name.isBlank() ? String.valueOf(all.get("default")) : name;
        return ((List<Map<String, Object>>) all.getOrDefault("connections", List.of())).stream().filter(x -> n.equals(x.get("name"))).findFirst().orElse(null);
    }

    static void define(ModuleContext ctx) {
        // S6 (design A, 25 Nov 2026): the mode Studio's dialog starts on, and the connection tried when the OneChat model fails
        ctx.defineSetting("studio_onechat_mode", "auto", (v, saas) -> withMode(v));
        ctx.defineSetting("studio_fallback_connection", "", (v, saas) -> AssistantSettings.checkConnections(ctx, v, false));
        ctx.defineSetting("studio_training_feedback", 0, (v, saas) -> {
            if (!v.trim().equals("0") && !v.trim().equals("1")) throw new OoaException("CR.BAD_INPUT", "Setting 'studio_training_feedback' must be 0 or 1");
        });
    }

    /**
     * Sends what the person did with Studio's suggestion (verdict inserted or discarded) to the OneChat model server
     * that made it, only when Settings allow it (studio_training_feedback = 1, off by default) and only when the
     * Studio assistant's connection is a OneChat model. Never fails the caller: {sent, reason?}.
     */
    @SuppressWarnings("unchecked")
    Map<String, Object> feedback(User u, String connection, Map<String, Object> body) {
        if (ctx.intSetting("studio_training_feedback") != 1) return Map.of("sent", false, "reason", "off");
        String verdict = String.valueOf(body.get("verdict"));
        if (!verdict.equals("inserted") && !verdict.equals("discarded")) throw new OoaException("CR.BAD_INPUT", "verdict is inserted or discarded", "Send inserted or discarded.");
        String request = body.get("request") == null ? "" : String.valueOf(body.get("request")).trim();
        if (request.isEmpty() || request.length() > 5000) throw new OoaException("CR.BAD_INPUT", "request is 1 to 5,000 characters", "Send the request as typed.");
        Map<String, Object> all = ctx.modelConnectionsFor(ctx.rolesOf(u.username(), u.role()));
        List<Map<String, Object>> list = (List<Map<String, Object>>) all.getOrDefault("connections", List.of());
        String name = connection == null || connection.isBlank() ? String.valueOf(all.get("default")) : connection;
        Map<String, Object> c = list.stream().filter(x -> name.equals(x.get("name"))).findFirst().orElse(null);
        if (c == null || !isOneChat(c)) return Map.of("sent", false, "reason", "not_onechat");
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("request", request);
        out.put("verdict", verdict);
        Map<String, Object> flow = new LinkedHashMap<>();
        flow.put("variables", body.get("variables") instanceof List<?> l ? l : List.of());
        flow.put("steps", body.get("steps") instanceof List<?> l ? l : List.of());
        out.put("flow", flow);
        out.put("note", "from One CommandCenter");
        String base = String.valueOf(c.getOrDefault("base_url", "")).replaceAll("/+$", "");
        try {
            HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(base + "/feedback")).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(out), StandardCharsets.UTF_8));
            String key = key(c);
            if (!key.isEmpty()) b.header("Authorization", "Bearer " + key);
            int status = http.send(b.build(), HttpResponse.BodyHandlers.discarding()).statusCode();
            return status / 100 == 2 ? Map.of("sent", true) : Map.of("sent", false, "reason", "HTTP " + status);
        } catch (IOException | RuntimeException e) {
            return Map.of("sent", false, "reason", "not answering");
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Map.of("sent", false, "reason", "interrupted");
        }
    }
}
