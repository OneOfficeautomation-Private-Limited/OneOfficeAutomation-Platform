package com.oneofficeautomation.modules.docauto;

import com.oneofficeautomation.controlroom.ControlRoom.User;
import com.oneofficeautomation.controlroom.module.ModuleContext;
import com.oneofficeautomation.sdk.JsonLite;
import com.oneofficeautomation.sdk.OoaException;

import java.time.Instant;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * I-4 (28 Nov 2026, plan DA-2 items 1-2): DocFlow's AI engines take their key from a Model connection, so a key is kept
 * once, in the vault. An administrator chooses the connection for each engine (setting docflow_engine_connections, a
 * JSON object engine id to connection name); DocFlow asks for it at POST /api/docflow-callback/credentials with its
 * service token and gets the connection's key, model and address, never written to disk there. Only a connection
 * chosen for an engine is handed out, and only when its provider fits the engine. A key typed on DocFlow's Engines page
 * is used when no connection is chosen.
 */
final class EngineConnections {
    static final String SETTING = "docflow_engine_connections";

    /** The Model connection providers each DocFlow engine can use. */
    static final Map<String, List<String>> FITS = new LinkedHashMap<>();
    static {
        FITS.put("claude", List.of("anthropic"));
        FITS.put("gemini", List.of("google_vertex"));
        FITS.put("bedrock", List.of("aws_bedrock"));
        FITS.put("azure-openai", List.of("azure_openai"));
        FITS.put("llm-cloud", List.of("openai", "openai_compatible"));
        FITS.put("llm-local", List.of("openai_compatible"));
    }

    private static final Pattern BEDROCK_REGION = Pattern.compile("bedrock-runtime(?:-fips)?\\.([a-z0-9-]+)\\.amazonaws");

    private final ModuleContext ctx;

    EngineConnections(ModuleContext ctx) { this.ctx = ctx; }

    /** Checks the setting as Settings saves it: a JSON object of known engines and existing, fitting connections. */
    static void check(ModuleContext ctx, String v) {
        if (v == null || v.isBlank()) return;
        Object parsed;
        try { parsed = JsonLite.parse(v); } catch (RuntimeException e) { parsed = null; }
        if (!(parsed instanceof Map<?, ?> m)) throw new OoaException("CR.BAD_INPUT", "The engines' model connections are a JSON object: engine to connection name");
        for (Map.Entry<?, ?> e : m.entrySet()) fitting(ctx, String.valueOf(e.getKey()), String.valueOf(e.getValue()));
    }

    /** The connection's row when it exists and its provider fits the engine; refuses otherwise. */
    private static Map<String, Object> fitting(ModuleContext ctx, String engine, String name) {
        List<String> fits = FITS.get(engine);
        if (fits == null) throw new OoaException("CR.BAD_INPUT", "'" + engine + "' does not take a Model connection", "Model connections serve the AI engines: " + String.join(", ", FITS.keySet()) + ".");
        Map<String, Object> c = ctx.db().one("SELECT name, provider, model, base_url, key_ref FROM model_connections WHERE name = ?", name);
        if (c == null) throw new OoaException("CR.BAD_INPUT", "There is no Model connection named '" + name + "'", "Choose one of Manage > Model connections, or add it there first.");
        if (!fits.contains(String.valueOf(c.get("provider"))))
            throw new OoaException("CR.BAD_INPUT", "'" + name + "' is a " + c.get("provider") + " connection; " + engine + " takes " + String.join(" or ", fits),
                    "Choose a " + String.join(" or ", fits) + " connection for this engine.");
        return c;
    }

    /** The choices (engine to connection), from the database itself so a change shows at once. */
    Map<String, String> choices() {
        Map<String, Object> r = ctx.db().one("SELECT json_value FROM settings WHERE name = ?", SETTING);
        Map<String, String> out = new LinkedHashMap<>();
        if (r == null) return out;
        try {
            Object v = JsonLite.parse(String.valueOf(r.get("json_value")));
            Object m = v instanceof String s && !s.isBlank() ? JsonLite.parse(s) : v;
            if (m instanceof Map<?, ?> map) map.forEach((k, n) -> { if (FITS.containsKey(String.valueOf(k)) && n != null && !String.valueOf(n).isBlank()) out.put(String.valueOf(k), String.valueOf(n)); });
        } catch (RuntimeException ignored) { }
        return out;
    }

    /** For the Engines page: each engine's connection, the connections that may be chosen, and which providers fit which engine. */
    Map<String, Object> list(User u) {
        ctx.require(u, "docs.view", "see Document Automation");
        List<Map<String, Object>> connections = new ArrayList<>();
        for (Map<String, Object> c : ctx.db().query("SELECT name, provider, model FROM model_connections ORDER BY name"))
            if (FITS.values().stream().anyMatch(l -> l.contains(String.valueOf(c.get("provider"))))) connections.add(c);
        return Map.of("choices", choices(), "connections", connections, "fits", FITS);
    }

    /** Chooses (or with an empty name clears) the connection an engine uses. */
    void save(User u, Map<String, Object> b, String ip) {
        ctx.require(u, "connections.manage", "choose the Model connection of a Document Automation engine");
        String engine = String.valueOf(b.getOrDefault("engine", "")).trim();
        String name = b.get("connection") == null ? "" : String.valueOf(b.get("connection")).trim();
        if (!FITS.containsKey(engine)) throw new OoaException("CR.BAD_INPUT", "'" + engine + "' does not take a Model connection", "Model connections serve the AI engines: " + String.join(", ", FITS.keySet()) + ".");
        if (!name.isEmpty()) fitting(ctx, engine, name);
        Map<String, String> all = new LinkedHashMap<>(choices());
        if (name.isEmpty()) all.remove(engine); else all.put(engine, name);
        String json = JsonLite.write(JsonLite.write(all));   // the setting's value is a string holding the JSON object
        Instant now = Instant.now();
        if (ctx.db().update("UPDATE settings SET json_value = ?, updated = ?, updated_by = ? WHERE name = ?", json, now, u.username(), SETTING) == 0)
            ctx.db().update("INSERT INTO settings (name, json_value, updated, updated_by) VALUES (?, ?, ?, ?)", SETTING, json, now, u.username());
        ctx.audit(u.username(), "docflow.engine_connection", "engine", engine, Map.of("connection", name.isEmpty() ? "(typed key)" : name), "ok", ip);
    }

    /**
     * DocFlow asks for its engines' keys (the service token is checked by the caller): for each engine asked about
     * that has a connection chosen, the connection's provider, model, address and key from the vault. An engine
     * without one is left out (DocFlow then uses a typed key). Each key handed out is recorded as a vault use.
     */
    Map<String, Object> credentials(Map<String, Object> b, String ip) {
        List<String> asked = new ArrayList<>();
        if (b.get("engines") instanceof List<?> l) for (Object o : l) asked.add(String.valueOf(o));
        else if (b.get("engine") != null) asked.add(String.valueOf(b.get("engine")));
        Map<String, String> chosen = choices();
        Map<String, Object> out = new LinkedHashMap<>();
        Map<String, Object> refused = new LinkedHashMap<>();
        for (String engine : asked) {
            String name = chosen.get(engine);
            if (name == null) continue;
            try {
                Map<String, Object> c = fitting(ctx, engine, name);
                out.put(engine, answer(engine, c));
                ctx.audit("docflow", "vault.use", "credential", String.valueOf(c.get("key_ref")),
                        Map.of("for", "Document Automation engine " + engine, "connection", name), "ok", ip);
            } catch (OoaException e) {
                refused.put(engine, e.getMessage());
            }
        }
        return Map.of("engines", out, "refused", refused);
    }

    private Map<String, Object> answer(String engine, Map<String, Object> c) {
        String provider = String.valueOf(c.get("provider"));
        String ref = c.get("key_ref") == null ? "" : String.valueOf(c.get("key_ref")).trim();
        String key = ref.isEmpty() ? "" : ctx.systemCredential(ref);
        key = key == null ? "" : key.strip();
        String base = c.get("base_url") == null ? "" : String.valueOf(c.get("base_url")).trim();
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("connection", c.get("name"));
        a.put("provider", provider);
        a.put("model", c.get("model") == null ? "" : c.get("model"));
        a.put("baseUrl", base);
        if (provider.equals("aws_bedrock")) {
            // stored as bots keep it: ACCESS_KEY_ID:SECRET in one credential
            int i = key.indexOf(':');
            if (i <= 0) throw new OoaException("CR.BAD_INPUT", "The Bedrock connection's key is not ACCESS_KEY_ID:SECRET", "Store the AWS key as ACCESS_KEY_ID:SECRET in the connection's credential.");
            a.put("accessKeyId", key.substring(0, i).strip());
            a.put("secretAccessKey", key.substring(i + 1).strip());
            Matcher m = BEDROCK_REGION.matcher(base);
            if (m.find()) a.put("region", m.group(1));
        } else {
            if (provider.equals("google_vertex") && key.startsWith("{"))
                throw new OoaException("CR.BAD_INPUT", "The Gemini engine takes a Vertex AI or Google AI Studio API key, not a service-account JSON key",
                        "Give the connection an API key, or type the key on the Engines page.");
            a.put("apiKey", key);
        }
        return a;
    }
}
