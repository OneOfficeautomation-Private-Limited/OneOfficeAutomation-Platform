package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;
import com.oneofficeautomation.sdk.JsonLite;

import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Duration;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * Reads secrets from an external vault linked to a vault folder. The folder keeps its owners, consumers and audit trail;
 * only the values live outside. A credential "name/attribute" in a linked folder maps to secret "name", field "attribute".
 *
 * Providers: hashicorp (KV v2), azure_keyvault, aws_secrets_manager, cyberark_ccp. The secret that lets the Command Center
 * sign in to the external vault (token, client secret, AWS secret key) is stored encrypted in our own vault.
 */
public final class ExternalVaults {
    public static final List<String> PROVIDERS = List.of("hashicorp", "azure_keyvault", "aws_secrets_manager", "cyberark_ccp");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ControlRoom cr;

    ExternalVaults(ControlRoom cr) { this.cr = cr; }
    /** Azure access tokens, cached until shortly before they expire. */
    private final Map<String, Object[]> tokens = new ConcurrentHashMap<>();

    /** Checks a link configuration before it is saved; returns it with only the known keys. */
    public static Map<String, Object> validate(Map<String, Object> cfg) {
        String p = String.valueOf(cfg.get("provider"));
        if (!PROVIDERS.contains(p)) throw new OoaException("CR.BAD_INPUT", "External vault provider must be one of " + PROVIDERS);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("provider", p);
        for (String k : List.of("url", "mount", "namespace", "tenant_id", "client_id", "region", "access_key_id", "app_id", "safe", "folder", "token_url", "endpoint")) {
            Object v = cfg.get(k);
            if (v instanceof String s && !s.isBlank()) out.put(k, s.trim());
        }
        java.util.function.Consumer<String> need = (k) -> { if (!out.containsKey(k)) throw new OoaException("CR.BAD_INPUT", "The " + p + " link needs " + k); };
        switch (p) {
            case "hashicorp" -> need.accept("url");
            case "azure_keyvault" -> { need.accept("url"); need.accept("tenant_id"); need.accept("client_id"); }
            case "aws_secrets_manager" -> { need.accept("region"); need.accept("access_key_id"); }
            case "cyberark_ccp" -> { need.accept("url"); need.accept("app_id"); need.accept("safe"); }
            default -> { }
        }
        if (out.get("url") instanceof String u && !u.matches("^https?://\\S+$")) throw new OoaException("CR.BAD_INPUT", "The vault URL must start with http:// or https://");
        return out;
    }

    /** Reads one value. authSecret is the decrypted sign-in secret for the external vault (may be null for CyberArk). */
    public String read(Map<String, Object> cfg, String authSecret, String name, String attribute) {
        String p = String.valueOf(cfg.get("provider"));
        try {
            return switch (p) {
                case "hashicorp" -> hashicorp(cfg, authSecret, name, attribute);
                case "azure_keyvault" -> azure(cfg, authSecret, name, attribute);
                case "aws_secrets_manager" -> aws(cfg, authSecret, name, attribute);
                case "cyberark_ccp" -> cyberark(cfg, authSecret, name, attribute);
                default -> throw new OoaException("VAULT.EXTERNAL", "Unknown external vault provider " + p);
            };
        } catch (OoaException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new OoaException("VAULT.EXTERNAL_UNREACHABLE", "The external vault did not answer: " + e.getMessage(), "Check the vault URL and network access from the Command Center.", true, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OoaException("VAULT.EXTERNAL_UNREACHABLE", "Interrupted while reading the external vault");
        } catch (Exception e) {
            throw new OoaException("VAULT.EXTERNAL", "Reading the external vault failed: " + e.getMessage());
        }
    }

    private static String trimSlash(Object u) { String s = String.valueOf(u); return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    private Map<String, Object> send(HttpRequest req, String what) throws Exception {
        NetGuard.check(cr, req.uri().toString(), "The external vault");
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) throw new OoaException("VAULT.NOT_FOUND", what + " was not found in the external vault");
        if (r.statusCode() == 401 || r.statusCode() == 403) throw new OoaException("VAULT.EXTERNAL_DENIED", "The external vault refused access to " + what + " (HTTP " + r.statusCode() + ")",
                "Check the sign-in secret and the vault's access policy for the Command Center.");
        if (r.statusCode() >= 300) throw new OoaException("VAULT.EXTERNAL", "The external vault answered HTTP " + r.statusCode() + " for " + what, null, r.statusCode() == 429 || r.statusCode() >= 500, null);
        Object o = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body());
        @SuppressWarnings("unchecked") Map<String, Object> m = o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of("value", o);
        return m;
    }

    /** A field from a value that may itself be a JSON object (AWS and Azure secrets often hold several fields). */
    private static String field(Object value, String attribute, String what) {
        if (value == null) throw new OoaException("VAULT.NOT_FOUND", what + " has no value");
        String s = String.valueOf(value);
        if (attribute == null || attribute.isBlank() || attribute.equals("value")) return s;
        Object parsed;
        try { parsed = JsonLite.parse(s); } catch (RuntimeException e) { parsed = null; }
        if (parsed instanceof Map<?, ?> m) {
            if (!m.containsKey(attribute)) throw new OoaException("VAULT.NOT_FOUND", what + " has no field '" + attribute + "'");
            return String.valueOf(m.get(attribute));
        }
        if (attribute.equals("password") || attribute.equals("secret")) return s;
        throw new OoaException("VAULT.NOT_FOUND", what + " is a single value; use the attribute 'value'");
    }

    private String hashicorp(Map<String, Object> cfg, String token, String name, String attribute) throws Exception {
        if (token == null || token.isBlank()) throw new OoaException("VAULT.EXTERNAL_DENIED", "No token is stored for this HashiCorp Vault link");
        String mount = String.valueOf(cfg.getOrDefault("mount", "secret"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(trimSlash(cfg.get("url")) + "/v1/" + mount + "/data/" + name.replace(' ', '_')))
                .timeout(Duration.ofSeconds(20)).header("X-Vault-Token", token).GET();
        if (cfg.get("namespace") instanceof String ns) b.header("X-Vault-Namespace", ns);
        Map<String, Object> r = send(b.build(), "secret '" + name + "'");
        Object data = r.get("data") instanceof Map<?, ?> d ? ((Map<?, ?>) d).get("data") : null;
        if (!(data instanceof Map<?, ?> fields)) throw new OoaException("VAULT.NOT_FOUND", "Secret '" + name + "' has no data");
        if (!fields.containsKey(attribute)) throw new OoaException("VAULT.NOT_FOUND", "Secret '" + name + "' has no field '" + attribute + "'");
        return String.valueOf(fields.get(attribute));
    }

    private String azureToken(Map<String, Object> cfg, String clientSecret) throws Exception {
        String key = cfg.get("tenant_id") + "|" + cfg.get("client_id");
        Object[] t = tokens.get(key);
        if (t != null && System.currentTimeMillis() < (Long) t[1]) return (String) t[0];
        if (clientSecret == null || clientSecret.isBlank()) throw new OoaException("VAULT.EXTERNAL_DENIED", "No client secret is stored for this Azure Key Vault link");
        String url = cfg.get("token_url") instanceof String u ? u : "https://login.microsoftonline.com/" + enc(String.valueOf(cfg.get("tenant_id"))) + "/oauth2/v2.0/token";
        String form = "grant_type=client_credentials&client_id=" + enc(String.valueOf(cfg.get("client_id"))) + "&client_secret=" + enc(clientSecret) + "&scope=" + enc("https://vault.azure.net/.default");
        Map<String, Object> r = send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), "the Azure sign-in");
        String access = (String) r.get("access_token");
        if (access == null) throw new OoaException("VAULT.EXTERNAL_DENIED", "Azure did not return an access token");
        long life = r.get("expires_in") instanceof Number n ? n.longValue() : Long.parseLong(String.valueOf(r.getOrDefault("expires_in", "3600")));
        tokens.put(key, new Object[]{access, System.currentTimeMillis() + Math.max(60, life - 120) * 1000});
        return access;
    }

    private String azure(Map<String, Object> cfg, String clientSecret, String name, String attribute) throws Exception {
        String token = azureToken(cfg, clientSecret);
        Map<String, Object> r = send(HttpRequest.newBuilder(URI.create(trimSlash(cfg.get("url")) + "/secrets/" + enc(name) + "?api-version=7.4"))
                .timeout(Duration.ofSeconds(20)).header("Authorization", "Bearer " + token).GET().build(), "secret '" + name + "'");
        return field(r.get("value"), attribute, "Secret '" + name + "'");
    }

    private String cyberark(Map<String, Object> cfg, String password, String name, String attribute) throws Exception {
        String q = "AppID=" + enc(String.valueOf(cfg.get("app_id"))) + "&Safe=" + enc(String.valueOf(cfg.get("safe"))) + "&Object=" + enc(name)
                + (cfg.get("folder") instanceof String f ? "&Folder=" + enc(f) : "");
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(trimSlash(cfg.get("url")) + "/AIMWebService/api/Accounts?" + q)).timeout(Duration.ofSeconds(20)).GET();
        // The Central Credential Provider normally trusts the caller by certificate, address or OS user; a basic password is optional.
        if (password != null && !password.isBlank()) b.header("Authorization", "Basic " + java.util.Base64.getEncoder().encodeToString(password.getBytes(StandardCharsets.UTF_8)));
        Map<String, Object> r = send(b.build(), "account '" + name + "'");
        String key = attribute == null || attribute.isBlank() || attribute.equals("password") || attribute.equals("value") ? "Content" : attribute;
        for (Map.Entry<String, Object> e : r.entrySet()) if (e.getKey().equalsIgnoreCase(key)) return String.valueOf(e.getValue());
        throw new OoaException("VAULT.NOT_FOUND", "Account '" + name + "' has no property '" + attribute + "'");
    }

    private String aws(Map<String, Object> cfg, String secretKey, String name, String attribute) throws Exception {
        if (secretKey == null || secretKey.isBlank()) throw new OoaException("VAULT.EXTERNAL_DENIED", "No secret access key is stored for this AWS Secrets Manager link");
        String region = String.valueOf(cfg.get("region"));
        String endpoint = cfg.get("endpoint") instanceof String e ? trimSlash(e) : "https://secretsmanager." + region + ".amazonaws.com";
        String body = JsonLite.write(Map.of("SecretId", name));
        URI uri = URI.create(endpoint + "/");
        Map<String, String> h = SigV4.sign("POST", uri, Map.of("content-type", "application/x-amz-json-1.1", "x-amz-target", "secretsmanager.GetSecretValue"),
                body, String.valueOf(cfg.get("access_key_id")), secretKey, region, "secretsmanager", ZonedDateTime.now(ZoneOffset.UTC));
        HttpRequest.Builder b = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(20)).POST(HttpRequest.BodyPublishers.ofString(body));
        h.forEach((k, v) -> { if (!k.equals("host")) b.header(k, v); });
        Map<String, Object> r = send(b.build(), "secret '" + name + "'");
        return field(r.get("SecretString"), attribute, "Secret '" + name + "'");
    }

    /** AWS Signature Version 4 for a single request (headers are lower-case). */
    public static final class SigV4 {
        private SigV4() { }
        public static Map<String, String> sign(String method, URI uri, Map<String, String> headers, String body, String accessKey, String secretKey,
                                        String region, String service, ZonedDateTime now) throws Exception {
            String amzDate = now.format(DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'"));
            String day = amzDate.substring(0, 8);
            java.util.TreeMap<String, String> all = new java.util.TreeMap<>();
            headers.forEach((k, v) -> all.put(k.toLowerCase(java.util.Locale.ROOT), v.trim()));
            all.put("host", uri.getHost() + (uri.getPort() > 0 ? ":" + uri.getPort() : ""));
            all.put("x-amz-date", amzDate);
            String payloadHash = hex(sha256(body.getBytes(StandardCharsets.UTF_8)));
            StringBuilder canonHeaders = new StringBuilder();
            all.forEach((k, v) -> canonHeaders.append(k).append(':').append(v).append('\n'));
            String signed = String.join(";", all.keySet());
            String path = uri.getRawPath() == null || uri.getRawPath().isEmpty() ? "/" : uri.getRawPath();
            String query = uri.getRawQuery() == null ? "" : canonicalQuery(uri.getRawQuery());
            String canonical = method + "\n" + path + "\n" + query + "\n" + canonHeaders + "\n" + signed + "\n" + payloadHash;
            String scope = day + "/" + region + "/" + service + "/aws4_request";
            String toSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonical.getBytes(StandardCharsets.UTF_8)));
            byte[] k = hmac(("AWS4" + secretKey).getBytes(StandardCharsets.UTF_8), day);
            k = hmac(k, region); k = hmac(k, service); k = hmac(k, "aws4_request");
            String signature = hex(hmac(k, toSign));
            Map<String, String> out = new java.util.LinkedHashMap<>(all);
            out.put("authorization", "AWS4-HMAC-SHA256 Credential=" + accessKey + "/" + scope + ", SignedHeaders=" + signed + ", Signature=" + signature);
            return out;
        }

        static String canonicalQuery(String raw) {
            return java.util.Arrays.stream(raw.split("&")).filter(s -> !s.isEmpty()).map(s -> s.contains("=") ? s : s + "=").sorted().reduce((a, b) -> a + "&" + b).orElse("");
        }
        static byte[] sha256(byte[] b) throws Exception { return MessageDigest.getInstance("SHA-256").digest(b); }
        static byte[] hmac(byte[] key, String data) throws Exception {
            Mac m = Mac.getInstance("HmacSHA256"); m.init(new SecretKeySpec(key, "HmacSHA256")); return m.doFinal(data.getBytes(StandardCharsets.UTF_8));
        }
        static String hex(byte[] b) { return HexFormat.of().formatHex(b); }
    }
}
