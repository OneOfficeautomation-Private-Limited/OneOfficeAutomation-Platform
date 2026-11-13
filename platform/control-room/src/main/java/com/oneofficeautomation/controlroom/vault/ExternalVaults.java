package com.oneofficeautomation.controlroom.vault;

import com.oneofficeautomation.controlroom.NetGuard;
import com.oneofficeautomation.controlroom.ControlRoom;
import com.oneofficeautomation.controlroom.Vault;
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
 * Providers: hashicorp (KV v2), azure_keyvault, aws_secrets_manager, cyberark_ccp, and since 14 Nov 2026 (round 4, A3, as
 * AA's list) delinea (Secret Server), beyondtrust (Password Safe) and arcon (ARCON PAM). The secret that lets One
 * CommandCenter sign in to the external vault (token, client secret, AWS secret key, password, API key) is stored
 * encrypted in our own vault.
 * <ul>
 * <li>delinea: an OAuth password sign-in at url/oauth2/token as {@code username}; a credential name is the secret's
 *     name (or #id), its attribute the field's slug (password, username, ...).</li>
 * <li>beyondtrust: SignAppin with the API key as {@code username}; a credential name is SYSTEM/ACCOUNT, checked out for
 *     a few minutes, read and checked in again; its attribute is password.</li>
 * <li>arcon: an OAuth resource-owner sign-in at {@code token_url} (client {@code client_id}; the secret is the
 *     password, or {"client_secret": "...", "password": "..."}), then url (ARCON's GetTargetDevicePassKey) with
 *     ServerIp/TargetType/UserName/OpenForHours[/HostName]; a credential name is SERVER/TARGETTYPE/USER[/HOST].</li>
 * </ul>
 */
public final class ExternalVaults {
    public static final List<String> PROVIDERS = List.of("hashicorp", "azure_keyvault", "aws_secrets_manager", "cyberark_ccp", "delinea", "beyondtrust", "arcon");

    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
    private final ControlRoom cr;

    public ExternalVaults(ControlRoom cr) { this.cr = cr; }
    /** Azure access tokens, cached until shortly before they expire. */
    private final Map<String, Object[]> tokens = new ConcurrentHashMap<>();

    /** Checks a link configuration before it is saved; returns it with only the known keys. */
    public static Map<String, Object> validate(Map<String, Object> cfg) {
        String p = String.valueOf(cfg.get("provider"));
        if (!PROVIDERS.contains(p)) throw new OoaException("CR.BAD_INPUT", "External vault provider must be one of " + PROVIDERS);
        Map<String, Object> out = new java.util.LinkedHashMap<>();
        out.put("provider", p);
        for (String k : List.of("url", "mount", "namespace", "tenant_id", "client_id", "region", "access_key_id", "app_id", "safe", "folder", "token_url", "endpoint", "username", "open_hours")) {
            Object v = cfg.get(k);
            if (v instanceof String s && !s.isBlank()) out.put(k, s.trim());
        }
        java.util.function.Consumer<String> need = (k) -> { if (!out.containsKey(k)) throw new OoaException("CR.BAD_INPUT", "The " + p + " link needs " + k); };
        switch (p) {
            case "hashicorp" -> need.accept("url");
            case "azure_keyvault" -> { need.accept("url"); need.accept("tenant_id"); need.accept("client_id"); }
            case "aws_secrets_manager" -> { need.accept("region"); need.accept("access_key_id"); }
            case "cyberark_ccp" -> { need.accept("url"); need.accept("app_id"); need.accept("safe"); }
            case "delinea", "beyondtrust" -> { need.accept("url"); need.accept("username"); }
            case "arcon" -> { need.accept("url"); need.accept("token_url"); need.accept("username"); }
            default -> { }
        }
        if (out.get("token_url") instanceof String t && !t.matches("^https?://\\S+$")) throw new OoaException("CR.BAD_INPUT", "The token URL must start with http:// or https://");
        if (out.get("open_hours") instanceof String h && !h.matches("[1-9][0-9]?")) throw new OoaException("CR.BAD_INPUT", "Open for hours is a whole number from 1 to 99");
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
                case "delinea" -> delinea(cfg, authSecret, name, attribute);
                case "beyondtrust" -> beyondtrust(cfg, authSecret, name, attribute);
                case "arcon" -> arcon(cfg, authSecret, name, attribute);
                default -> throw new OoaException("VAULT.EXTERNAL", "Unknown external vault provider " + p, "Use one of the providers the link offers: HashiCorp Vault, Azure Key Vault, AWS Secrets Manager, CyberArk, Delinea, BeyondTrust or ARCON.");
            };
        } catch (OoaException e) {
            throw e;
        } catch (java.io.IOException e) {
            throw new OoaException("VAULT.EXTERNAL_UNREACHABLE", "The external vault did not answer: " + e.getMessage(), "Check the vault URL and network access from One CommandCenter.", true, null);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new OoaException("VAULT.EXTERNAL_UNREACHABLE", "Interrupted while reading the external vault", "Try again; if it keeps happening, an administrator can look in the server log.");
        } catch (Exception e) {
            throw new OoaException("VAULT.EXTERNAL", "Reading the external vault failed: " + e.getMessage(), "Check the external vault's address and that the server can reach it; the message has the vault's answer.");
        }
    }

    private static String trimSlash(Object u) { String s = String.valueOf(u); return s.endsWith("/") ? s.substring(0, s.length() - 1) : s; }
    private static String enc(String s) { return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20"); }

    private Map<String, Object> send(HttpRequest req, String what) throws Exception {
        NetGuard.check(cr, req.uri().toString(), "The external vault");
        HttpResponse<String> r = http.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) throw new OoaException("VAULT.NOT_FOUND", what + " was not found in the external vault", "Check the secret's name and field in the external vault, and the attribute in the credential's link.");
        if (r.statusCode() == 401 || r.statusCode() == 403) throw new OoaException("VAULT.EXTERNAL_DENIED", "The external vault refused access to " + what + " (HTTP " + r.statusCode() + ")",
                "Check the sign-in secret and the vault's access policy for One CommandCenter.");
        if (r.statusCode() >= 300) throw new OoaException("VAULT.EXTERNAL", "The external vault answered HTTP " + r.statusCode() + " for " + what, "Check the link's address and secret, and that its identity may read this secret in the external vault.", r.statusCode() == 429 || r.statusCode() >= 500, null);
        Object o = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body());
        @SuppressWarnings("unchecked") Map<String, Object> m = o instanceof Map<?, ?> ? (Map<String, Object>) o : Map.of("value", o);
        return m;
    }

    /** A field from a value that may itself be a JSON object (AWS and Azure secrets often hold several fields). */
    private static String field(Object value, String attribute, String what) {
        if (value == null) throw new OoaException("VAULT.NOT_FOUND", what + " has no value", "Check the secret's name and field in the external vault, and the attribute in the credential's link.");
        String s = String.valueOf(value);
        if (attribute == null || attribute.isBlank() || attribute.equals("value")) return s;
        Object parsed;
        try { parsed = JsonLite.parse(s); } catch (RuntimeException e) { parsed = null; }
        if (parsed instanceof Map<?, ?> m) {
            if (!m.containsKey(attribute)) throw new OoaException("VAULT.NOT_FOUND", what + " has no field '" + attribute + "'", "Check the secret's name and field in the external vault, and the attribute in the credential's link.");
            return String.valueOf(m.get(attribute));
        }
        if (attribute.equals("password") || attribute.equals("secret")) return s;
        throw new OoaException("VAULT.NOT_FOUND", what + " is a single value; use the attribute 'value'", "Set the credential's attribute to value.");
    }

    private String hashicorp(Map<String, Object> cfg, String token, String name, String attribute) throws Exception {
        if (token == null || token.isBlank()) throw new OoaException("VAULT.EXTERNAL_DENIED", "No token is stored for this HashiCorp Vault link", "Store the missing secret for this link in Manage > Credentials > External vaults.");
        String mount = String.valueOf(cfg.getOrDefault("mount", "secret"));
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(trimSlash(cfg.get("url")) + "/v1/" + mount + "/data/" + name.replace(' ', '_')))
                .timeout(Duration.ofSeconds(20)).header("X-Vault-Token", token).GET();
        if (cfg.get("namespace") instanceof String ns) b.header("X-Vault-Namespace", ns);
        Map<String, Object> r = send(b.build(), "secret '" + name + "'");
        Object data = r.get("data") instanceof Map<?, ?> d ? ((Map<?, ?>) d).get("data") : null;
        if (!(data instanceof Map<?, ?> fields)) throw new OoaException("VAULT.NOT_FOUND", "Secret '" + name + "' has no data", "Check the secret's name and field in the external vault, and the attribute in the credential's link.");
        if (!fields.containsKey(attribute)) throw new OoaException("VAULT.NOT_FOUND", "Secret '" + name + "' has no field '" + attribute + "'", "Check the secret's name and field in the external vault, and the attribute in the credential's link.");
        return String.valueOf(fields.get(attribute));
    }

    private String azureToken(Map<String, Object> cfg, String clientSecret) throws Exception {
        String key = cfg.get("tenant_id") + "|" + cfg.get("client_id");
        Object[] t = tokens.get(key);
        if (t != null && System.currentTimeMillis() < (Long) t[1]) return (String) t[0];
        if (clientSecret == null || clientSecret.isBlank()) throw new OoaException("VAULT.EXTERNAL_DENIED", "No client secret is stored for this Azure Key Vault link", "Store the missing secret for this link in Manage > Credentials > External vaults.");
        String url = cfg.get("token_url") instanceof String u ? u : "https://login.microsoftonline.com/" + enc(String.valueOf(cfg.get("tenant_id"))) + "/oauth2/v2.0/token";
        String form = "grant_type=client_credentials&client_id=" + enc(String.valueOf(cfg.get("client_id"))) + "&client_secret=" + enc(clientSecret) + "&scope=" + enc("https://vault.azure.net/.default");
        Map<String, Object> r = send(HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(20)).header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form)).build(), "the Azure sign-in");
        String access = (String) r.get("access_token");
        if (access == null) throw new OoaException("VAULT.EXTERNAL_DENIED", "Azure did not return an access token", "Check the Azure app's tenant, client id and secret in the link.");
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
        throw new OoaException("VAULT.NOT_FOUND", "Account '" + name + "' has no property '" + attribute + "'", "Check the secret's name and field in the external vault, and the attribute in the credential's link.");
    }

    private static String form(Map<String, String> m) {
        StringBuilder b = new StringBuilder();
        m.forEach((k, v) -> { if (v != null) b.append(b.length() == 0 ? "" : "&").append(enc(k)).append('=').append(enc(v)); });
        return b.toString();
    }

    private static OoaException missing(String what) {
        return new OoaException("VAULT.EXTERNAL_DENIED", "No " + what + " is stored for this link", "Store the missing secret for this link in Manage > Credentials > External vaults.");
    }

    /** Delinea Secret Server: an OAuth password sign-in, then the secret (by #id or by name) and its field by slug. */
    private String delinea(Map<String, Object> cfg, String password, String name, String attribute) throws Exception {
        if (password == null || password.isBlank()) throw missing("password");
        String base = trimSlash(cfg.get("url"));
        Map<String, Object> t = send(HttpRequest.newBuilder(URI.create(base + "/oauth2/token")).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded")
                .POST(HttpRequest.BodyPublishers.ofString(form(Map.of("grant_type", "password", "username", String.valueOf(cfg.get("username")), "password", password)))).build(), "the sign-in");
        String token = String.valueOf(t.get("access_token"));
        String id;
        if (name.startsWith("#")) id = name.substring(1);
        else {
            Map<String, Object> found = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/secrets?filter.searchText=" + enc(name) + "&take=10")).timeout(Duration.ofSeconds(20))
                    .header("Authorization", "Bearer " + token).GET().build(), "secret '" + name + "'");
            Object rec = found.get("records") instanceof List<?> l ? l.stream().filter((r) -> r instanceof Map<?, ?> m && name.equals(m.get("name"))).findFirst().orElse(null) : null;
            if (!(rec instanceof Map<?, ?> m)) throw new OoaException("VAULT.NOT_FOUND", "Secret '" + name + "' was not found in Delinea Secret Server", "Check the secret's exact name, or give its id as #123.");
            id = String.valueOf(m.get("id"));
        }
        String slug = attribute == null || attribute.isBlank() || attribute.equals("value") ? "password" : attribute;
        Map<String, Object> v = send(HttpRequest.newBuilder(URI.create(base + "/api/v1/secrets/" + enc(id) + "/fields/" + enc(slug))).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + token).GET().build(), "field '" + slug + "' of secret '" + name + "'");
        return String.valueOf(v.get("value"));
    }

    /** BeyondTrust Password Safe: SignAppin, find SYSTEM/ACCOUNT, check it out for a few minutes, read, check in, sign out. */
    private String beyondtrust(Map<String, Object> cfg, String apiKey, String name, String attribute) throws Exception {
        if (apiKey == null || apiKey.isBlank()) throw missing("API key");
        int slash = name.indexOf('/');
        if (slash <= 0 || slash == name.length() - 1) throw new OoaException("VAULT.NOT_FOUND", "A BeyondTrust credential is named SYSTEM/ACCOUNT, not '" + name + "'", "Rename the credential as the managed system and account, for example sql01/sa.");
        String base = trimSlash(cfg.get("url")) + "/BeyondTrust/api/public/v3";
        HttpClient session = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).cookieHandler(new java.net.CookieManager()).build();
        String auth = "PS-Auth key=" + apiKey + "; runas=" + cfg.get("username") + ";";
        java.util.function.BiFunction<String, String, HttpRequest.Builder> req = (path, method) -> HttpRequest.newBuilder(URI.create(base + path))
                .timeout(Duration.ofSeconds(20)).header("Authorization", auth).header("Content-Type", "application/json");
        sendWith(session, req.apply("/Auth/SignAppin", "POST").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), "the sign-in");
        try {
            Map<String, Object> acc = sendWith(session, req.apply("/ManagedAccounts?systemName=" + enc(name.substring(0, slash)) + "&accountName=" + enc(name.substring(slash + 1)), "GET").GET().build(), "account '" + name + "'");
            Map<String, Object> r = sendWith(session, req.apply("/Requests", "POST").POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(Map.of(
                    "SystemID", acc.get("SystemId"), "AccountID", acc.get("AccountId"), "DurationMinutes", 5, "Reason", "ONEOFFICEAUTOMATION run")))).build(), "a request for '" + name + "'");
            String requestId = String.valueOf(r.getOrDefault("value", r.get("RequestID")));
            try {
                Map<String, Object> c = sendWith(session, req.apply("/Credentials/" + enc(requestId), "GET").GET().build(), "the password of '" + name + "'");
                return String.valueOf(c.get("value"));
            } finally {
                try { sendWith(session, req.apply("/Requests/" + enc(requestId) + "/Checkin", "PUT").PUT(HttpRequest.BodyPublishers.ofString("{}")).build(), "the check-in"); } catch (RuntimeException ignored) { /* the request expires by itself */ }
            }
        } finally {
            try { sendWith(session, req.apply("/Auth/Signout", "POST").POST(HttpRequest.BodyPublishers.ofString("{}")).build(), "the sign-out"); } catch (RuntimeException ignored) { /* the session expires by itself */ }
        }
    }

    /** ARCON PAM: an OAuth resource-owner sign-in, then GetTargetDevicePassKey for SERVER/TARGETTYPE/USER[/HOST]. */
    private String arcon(Map<String, Object> cfg, String secret, String name, String attribute) throws Exception {
        if (secret == null || secret.isBlank()) throw missing("password");
        String clientSecret = null, password = secret;
        if (secret.trim().startsWith("{") && JsonLite.parse(secret) instanceof Map<?, ?> m) { clientSecret = (String) m.get("client_secret"); password = String.valueOf(m.get("password")); }
        String[] p = name.split("/");
        if (p.length < 3 || p.length > 4) throw new OoaException("VAULT.NOT_FOUND", "An ARCON credential is named SERVER/TARGETTYPE/USER[/HOST], not '" + name + "'", "Rename the credential, for example 10.0.0.5/Windows/svc_robot.");
        Map<String, String> f = new java.util.LinkedHashMap<>();
        f.put("grant_type", "password"); f.put("username", String.valueOf(cfg.get("username"))); f.put("password", password);
        if (cfg.get("client_id") instanceof String id) f.put("client_id", id);
        if (clientSecret != null) f.put("client_secret", clientSecret);
        Map<String, Object> t = send(HttpRequest.newBuilder(URI.create(String.valueOf(cfg.get("token_url")))).timeout(Duration.ofSeconds(20))
                .header("Content-Type", "application/x-www-form-urlencoded").POST(HttpRequest.BodyPublishers.ofString(form(f))).build(), "the sign-in");
        Map<String, Object> body = new java.util.LinkedHashMap<>();
        body.put("ServerIp", p[0]); body.put("TargetType", p[1]); body.put("UserName", p[2]);
        body.put("OpenForHours", cfg.getOrDefault("open_hours", "1"));
        if (p.length == 4) body.put("HostName", p[3]);
        Map<String, Object> r = send(HttpRequest.newBuilder(URI.create(String.valueOf(cfg.get("url")))).timeout(Duration.ofSeconds(20))
                .header("Authorization", "Bearer " + t.get("access_token")).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(JsonLite.write(List.of(body)))).build(), "credential '" + name + "'");
        Object result = r.get("Result") instanceof List<?> l && !l.isEmpty() ? l.get(0) : r.get("value") instanceof List<?> l2 && !l2.isEmpty() ? l2.get(0) : r;
        if (result instanceof Map<?, ?> m) {
            String want = attribute == null || attribute.isBlank() || attribute.equals("value") ? "password" : attribute;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (k.equalsIgnoreCase(want) || (want.equals("password") && (k.equalsIgnoreCase("Password") || k.equalsIgnoreCase("PassKey") || k.equalsIgnoreCase("TargetPassword")))) return String.valueOf(e.getValue());
            }
        }
        throw new OoaException("VAULT.NOT_FOUND", "ARCON's answer for '" + name + "' has no '" + attribute + "'", "Check the server, target type and user name in ARCON, and the attribute (password) in the credential's link.");
    }

    private Map<String, Object> sendWith(HttpClient client, HttpRequest req, String what) throws Exception {
        NetGuard.check(cr, req.uri().toString(), "The external vault");
        HttpResponse<String> r = client.send(req, HttpResponse.BodyHandlers.ofString());
        if (r.statusCode() == 404) throw new OoaException("VAULT.NOT_FOUND", what + " was not found in the external vault", "Check the secret's name and field in the external vault, and the attribute in the credential's link.");
        if (r.statusCode() == 401 || r.statusCode() == 403) throw new OoaException("VAULT.EXTERNAL_DENIED", "The external vault refused access to " + what + " (HTTP " + r.statusCode() + ")", "Check the sign-in secret and the vault's access policy for One CommandCenter.");
        if (r.statusCode() >= 300) throw new OoaException("VAULT.EXTERNAL", "The external vault answered HTTP " + r.statusCode() + " for " + what, "Check the link's address and secret, and that its identity may read this secret in the external vault.");
        Object o = r.body().isBlank() ? Map.of() : JsonLite.parse(r.body());
        @SuppressWarnings("unchecked") Map<String, Object> m = o instanceof Map<?, ?> ? (Map<String, Object>) o : java.util.Collections.singletonMap("value", o);
        return m;
    }

    private String aws(Map<String, Object> cfg, String secretKey, String name, String attribute) throws Exception {
        if (secretKey == null || secretKey.isBlank()) throw new OoaException("VAULT.EXTERNAL_DENIED", "No secret access key is stored for this AWS Secrets Manager link", "Store the missing secret for this link in Manage > Credentials > External vaults.");
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
