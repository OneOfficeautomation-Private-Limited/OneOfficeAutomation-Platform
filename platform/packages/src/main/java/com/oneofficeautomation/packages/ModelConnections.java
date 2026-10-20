package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.sdk.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.function.Supplier;

/**
 * One Model Connections: named language-model settings (provider, model, endpoint, API key reference) that AI actions,
 * Prompt Skills and AI agents use by name. They come from the "model_connections" service (One Command Center hands
 * them to its runners) or from model-connections.json in the workspace:
 * {"default": "gpt", "connections": [{"name": "gpt", "provider": "openai", "model": "gpt-4o-mini",
 *   "api_key_env": "OPENAI_API_KEY"} or {"api_key": {"$credential": "ai/openai/key"}}]}. Keys themselves are never stored here.
 */
final class ModelConnections {
    private ModelConnections() {}

    static final String D_CONNECTION = "Name of a One Model Connection (provider, model and key managed in one place). Leave empty to set them on this action.";

    @SuppressWarnings("unchecked")
    static Map<String, Object> all(ActionContext ctx) {
        try {
            Supplier<Object> svc = ctx.service("model_connections", Supplier.class);
            if (svc != null && svc.get() instanceof Map<?, ?> m) return (Map<String, Object>) m;
        } catch (RuntimeException ignored) { /* no service: use the workspace file */ }
        Path p = ctx.workDir().resolve("model-connections.json");
        if (!Files.isRegularFile(p)) return Map.of();
        try { Object v = JsonLite.parse(Files.readString(p)); return v instanceof Map ? (Map<String, Object>) v : Map.of(); }
        catch (Exception e) { throw new ActionException("AI.BAD_CONNECTIONS", "model-connections.json is not valid JSON: " + e.getMessage(), "Fix the file, or edit connections in One Agent Studio."); }
    }

    /** The named connection, or the default one when the name is "default"; null when name is empty. */
    @SuppressWarnings("unchecked")
    static Map<String, Object> find(ActionContext ctx, String name) {
        if (name == null || name.isBlank()) return null;
        Map<String, Object> all = all(ctx);
        String wanted = "default".equals(name) && all.get("default") instanceof String d ? d : name;
        for (Object o : (List<Object>) all.getOrDefault("connections", List.of()))
            if (o instanceof Map<?, ?> c && wanted.equals(c.get("name"))) return (Map<String, Object>) c;
        throw new ActionException("AI.NO_CONNECTION", "There is no model connection named '" + name + "'", "Create it under One Model Connections, or pick another name.");
    }

    /** Settings for one call: the connection supplies provider, model, endpoint and key; values set on the action win. */
    static LlmClient.Config config(ActionContext ctx, String connection, String provider, String model, Object key, String base, double temp, int max, boolean mask) {
        return config(ctx, connection, provider, model, key, base, temp, max, mask, null, null);
    }

    /**
     * As above, plus the AWS settings used by aws_bedrock_sagemaker. A connection may carry them as "region" and
     * "aws_access_key_id" ({"$credential": ...}) or "aws_access_key_id_env".
     */
    static LlmClient.Config config(ActionContext ctx, String connection, String provider, String model, Object key, String base, double temp, int max, boolean mask,
                                   Object accessKeyId, String region) {
        Map<String, Object> c = find(ctx, connection);
        if (c != null) {
            provider = String.valueOf(c.getOrDefault("provider", provider));
            if (model == null || model.isBlank()) model = (String) c.get("model");
            if (base == null || base.isBlank()) base = (String) c.get("base_url");
            if (key == null) key = key(ctx, c, "api_key");
            if (accessKeyId == null) accessKeyId = key(ctx, c, "aws_access_key_id");
            if ((region == null || region.isBlank()) && c.get("region") instanceof String r) region = r;
        }
        if (model == null || model.isBlank()) throw new ActionException("AI.NO_MODEL", "No model chosen", "Pick a One Model Connection, or set provider and model on this action.");
        return GenAiPackage.cfg(provider, model, key, base, temp, max, mask, accessKeyId, region);
    }

    @SuppressWarnings("unchecked")
    private static Object key(ActionContext ctx, Map<String, Object> c, String field) {
        if (c.get(field + "_env") instanceof String env && !env.isBlank()) {
            String v = System.getenv(env);
            if (v == null || v.isBlank()) throw new ActionException("AI.NO_KEY", "The environment variable " + env + " is not set", "Set it on the runner, or point the connection at a vault credential.");
            return new SecureString(v, "env:" + env);
        }
        if (c.get(field) instanceof Map<?, ?> m && m.get("$credential") instanceof String ref) {
            CredentialVault vault = ctx.service("vault", CredentialVault.class);
            if (vault == null) throw new ActionException("AI.NO_VAULT", "No vault is available for the connection's API key");
            return vault.getSecret(ref, null);
        }
        return null;
    }
}
