package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.net.URI;
import java.net.http.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.time.ZonedDateTime;
import java.util.*;

/**
 * Custom connectors: APIs imported from an OpenAPI, Swagger or Postman file (see {@link ConnectorImporter}) and called by
 * operation id with typed parameters, authentication and response handling. Definitions live in the workspace under
 * connectors/&lt;name&gt;.connector.json.
 */
@OoaPackage(id = "ooa.connector", name = "Custom Connectors", version = "1.0.0",
        description = "Call any REST API imported from an OpenAPI, Swagger or Postman file as named operations with typed inputs and authentication.")
public final class ConnectorPackage {
    private static final String D_CONNECTOR = "Connector name (the file connectors/<name>.connector.json in the workspace).";
    private static final String D_OPERATION = "Operation id, as shown by List operations.";

    // ---------- import and browse ----------

    @Action(id = "import_connector", name = "Import connector", category = "Connectors", capabilities = "filesystem",
            description = "Create or replace a custom connector from an OpenAPI 3.x or Swagger 2.0 document (JSON, or YAML where a YAML parser is installed) or a Postman v2.1 collection.",
            panel = "(API description: A file = spec_file / Pasted text = spec_text), name | server, overwrite")
    @Output(name = "connector", type = OoaType.STRING, description = "Name of the saved connector.")
    @Output(name = "operations", type = OoaType.NUMBER, description = "Number of operations imported.")
    @Output(name = "path", type = OoaType.STRING, description = "Workspace path of the connector definition.")
    public Map<String, Object> importConnector(ActionContext ctx,
            @Input(name = "spec_file", type = OoaType.STRING, required = false, description = "Path of the API description file.") String specFile,
            @Input(name = "spec_text", type = OoaType.STRING, required = false, description = "The API description itself, when there is no file.") String specText,
            @Input(name = "name", type = OoaType.STRING, required = false, description = "Connector name; derived from the API title when empty.") String name,
            @Input(name = "server", type = OoaType.STRING, required = false, description = "Base URL to use instead of the first server in the file.") String server,
            @Input(name = "overwrite", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Replace a connector with the same name.") boolean overwrite) {
        String text = specText;
        if (specFile != null && !specFile.isBlank()) {
            Path p = ctx.resolvePath(specFile);
            try { text = Files.readString(p, StandardCharsets.UTF_8); }
            catch (IOException e) { throw new ActionException("CONNECTOR.SPEC_NOT_FOUND", "Cannot read " + specFile + ": " + e.getMessage(), "Check the file path."); }
        }
        if (text == null || text.isBlank()) throw new ActionException("CONNECTOR.EMPTY_SPEC", "Give spec_file or spec_text");
        Map<String, Object> def = ConnectorImporter.importSpec(text, name, server);
        String path = ConnectorStore.save(ctx.workDir(), def, overwrite);
        int n = ((List<?>) def.get("operations")).size();
        ctx.info("Imported connector '" + def.get("name") + "' with " + n + " operation(s)");
        return Out.of("connector", def.get("name"), "operations", BigDecimal.valueOf(n), "path", path);
    }

    @Action(id = "list_connectors", name = "List connectors", category = "Connectors", idempotent = true,
            description = "List the custom connectors in the workspace with their title, server and number of operations.")
    @Output(name = "connectors", type = OoaType.LIST, description = "One dictionary per connector: name, title, server, operations, source.")
    public Map<String, Object> listConnectors(ActionContext ctx) {
        return Out.of("connectors", new ArrayList<Object>(ConnectorStore.list(ctx.workDir())));
    }

    @Action(id = "list_operations", name = "List operations", category = "Connectors", idempotent = true,
            description = "List the operations of a custom connector: id, name, method, path and description.",
            panel = "connector, search")
    @Output(name = "operations", type = OoaType.LIST, description = "One dictionary per operation: id, name, method, path, description, tags.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of operations listed.")
    public Map<String, Object> listOperations(ActionContext ctx,
            @Input(name = "connector", type = OoaType.STRING, description = D_CONNECTOR) String connector,
            @Input(name = "search", type = OoaType.STRING, required = false, description = "Only operations whose id, name, path or tag contains this text.") String search) {
        Map<String, Object> def = ConnectorStore.load(ctx.workDir(), connector);
        String s = search == null ? "" : search.toLowerCase(Locale.ROOT).trim();
        List<Object> out = new ArrayList<>();
        for (Object o : ops(def)) {
            Map<String, Object> op = map(o);
            String hay = (op.get("id") + " " + op.get("name") + " " + op.get("path") + " " + op.get("tags")).toLowerCase(Locale.ROOT);
            if (!s.isEmpty() && !hay.contains(s)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            for (String k : List.of("id", "name", "method", "path", "description", "tags")) row.put(k, op.get(k));
            out.add(row);
        }
        return Out.of("operations", out, "count", BigDecimal.valueOf(out.size()));
    }

    @Action(id = "describe_operation", name = "Describe operation", category = "Connectors", idempotent = true,
            description = "Show one operation of a custom connector: parameters, request body, responses and authentication.",
            panel = "connector, operation")
    @Output(name = "operation", type = OoaType.DICTIONARY, description = "The full operation definition.")
    @Output(name = "example_parameters", type = OoaType.DICTIONARY, description = "Parameter names with their default or example value, ready to edit.")
    @Output(name = "auth", type = OoaType.LIST, description = "Authentication schemes this operation accepts.")
    public Map<String, Object> describeOperation(ActionContext ctx,
            @Input(name = "connector", type = OoaType.STRING, description = D_CONNECTOR) String connector,
            @Input(name = "operation", type = OoaType.STRING, description = D_OPERATION) String operation) {
        Map<String, Object> def = ConnectorStore.load(ctx.workDir(), connector);
        Map<String, Object> op = op(def, connector, operation);
        Map<String, Object> ex = new LinkedHashMap<>();
        for (Object po : list(op.get("parameters"))) {
            Map<String, Object> p = map(po);
            ex.put(String.valueOf(p.get("name")), p.get("default") != null ? p.get("default") : p.get("example") != null ? p.get("example") : "");
        }
        List<Object> auth = new ArrayList<>();
        for (Object id : securityOf(def, op)) { Map<String, Object> s = scheme(def, String.valueOf(id)); if (s != null) auth.add(s); }
        return Out.of("operation", op, "example_parameters", ex, "auth", auth);
    }

    // ---------- call ----------

    @Action(id = "call_operation", name = "Call operation", category = "Connectors", timeoutSeconds = 300, capabilities = "network",
            description = "Call an operation of a custom connector. Checks required parameters, encodes path and query values, signs the request and returns status, headers and the parsed body, or saves a file.",
            panel = "connector, operation, parameters, body, credential, auth | output_file, base_url, headers, timeout_seconds, fail_on_error")
    @Output(name = "status", type = OoaType.NUMBER, description = "HTTP status code.")
    @Output(name = "headers", type = OoaType.DICTIONARY, description = "Response headers.")
    @Output(name = "body", description = "Response body: parsed JSON, or text; empty when saved to a file.")
    @Output(name = "file", type = OoaType.STRING, description = "Path of the saved response when output_file is set.")
    public Map<String, Object> callOperation(ActionContext ctx,
            @Input(name = "connector", type = OoaType.STRING, description = D_CONNECTOR) String connector,
            @Input(name = "operation", type = OoaType.STRING, description = D_OPERATION) String operation,
            @Input(name = "parameters", type = OoaType.DICTIONARY, required = false, description = "Path, query, header and cookie parameters by name.") Map<String, Object> parameters,
            @Input(name = "body", required = false, description = "Request body: a dictionary or list for JSON, fields for forms (file fields take a file path), or a file path for binary uploads.") Object body,
            @Input(name = "credential", type = OoaType.CREDENTIAL, required = false, description = "Secret for the API: API key, password, bearer token, OAuth client secret or AWS secret key. May also be a JSON object with the fields of 'auth'.") Object credential,
            @Input(name = "auth", type = OoaType.DICTIONARY, required = false, description = "Non-secret auth settings: scheme, username, client_id, scope, access_key_id, session_token, region, service.") Map<String, Object> auth,
            @Input(name = "output_file", type = OoaType.STRING, required = false, description = "Save the response body to this file (streamed; for downloads and binary responses).") String outputFile,
            @Input(name = "base_url", type = OoaType.STRING, required = false, description = "Server to call instead of the connector's server.") String baseUrl,
            @Input(name = "headers", type = OoaType.DICTIONARY, required = false, description = "Extra request headers.") Map<String, Object> headers,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "Response timeout.") int timeout,
            @Input(name = "fail_on_error", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Raise CONNECTOR.HTTP_<status> on 4xx and 5xx responses.") boolean failOnError) {
        Map<String, Object> def = ConnectorStore.load(ctx.workDir(), connector);
        Map<String, Object> op = op(def, connector, operation);
        String opId = String.valueOf(op.get("id"));
        String method = String.valueOf(op.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);

        // 1. Parameters: check names and required values, fill defaults
        Map<String, Object> given = parameters == null ? new LinkedHashMap<>() : new LinkedHashMap<>(parameters);
        List<Map<String, Object>> declared = new ArrayList<>();
        for (Object po : list(op.get("parameters"))) declared.add(map(po));
        Map<String, Object> path = new LinkedHashMap<>(), query = new LinkedHashMap<>(), hdr = new LinkedHashMap<>(), cookie = new LinkedHashMap<>();
        List<String> missing = new ArrayList<>();
        Set<String> consumed = new HashSet<>();
        for (Map<String, Object> p : declared) {
            String n = String.valueOf(p.get("name")), in = String.valueOf(p.get("in"));
            String qualified = in + ":" + n;
            Object v = given.containsKey(qualified) ? given.get(qualified) : given.get(n);
            if (given.containsKey(qualified)) consumed.add(qualified); else if (given.containsKey(n)) consumed.add(n);
            if (v == null || (v instanceof String s && s.isEmpty() && Boolean.TRUE.equals(p.get("required")))) v = Boolean.TRUE.equals(p.get("required")) ? p.get("default") : null;
            if (v == null) { if (Boolean.TRUE.equals(p.get("required"))) missing.add(n + " (" + in + ")"); continue; }
            switch (in) {
                case "path" -> path.put(n, v);
                case "header" -> hdr.put(n, v);
                case "cookie" -> cookie.put(n, v);
                default -> { }
            }
            if (in.equals("query")) query.put(n, new Object[]{v, p});
        }
        if (!missing.isEmpty()) throw new ActionException("CONNECTOR.MISSING_PARAMETER", opId + " needs " + String.join(", ", missing),
                "Add them to parameters, e.g. {\"" + missing.get(0).replaceAll(" \\(.*", "") + "\": ...}.");
        for (String k : given.keySet()) if (!consumed.contains(k)) {
            List<String> names = declared.stream().map(p -> String.valueOf(p.get("name"))).toList();
            throw new ActionException("CONNECTOR.UNKNOWN_PARAMETER", opId + " has no parameter '" + k + "'",
                    names.isEmpty() ? "This operation takes no parameters; put request data in body." : "Use one of: " + String.join(", ", names) + ". Request data goes in body.");
        }

        // 2. URL
        String server = baseUrl != null && !baseUrl.isBlank() ? baseUrl.trim() : String.valueOf(Objects.requireNonNullElse(def.get("server"), ""));
        if (!server.matches("(?i)^https?://.+")) throw new ActionException("CONNECTOR.NO_SERVER", "Connector '" + connector + "' has no absolute server URL" + (server.isEmpty() ? "" : " (" + server + ")"),
                "Set base_url on this step, or edit \"server\" in the connector definition.");
        if (server.endsWith("/")) server = server.substring(0, server.length() - 1);
        String p = String.valueOf(op.getOrDefault("path", "/"));
        for (var e : path.entrySet()) p = p.replace("{" + e.getKey() + "}", HttpJson.enc(joinList(e.getValue(), ",")));
        if (p.matches(".*\\{[^}/]+}.*")) throw new ActionException("CONNECTOR.MISSING_PARAMETER", opId + " path still has a placeholder: " + p, "Give every path parameter.");
        StringJoiner qs = new StringJoiner("&");
        for (var e : query.entrySet()) {
            Object[] pair = (Object[]) e.getValue();
            Map<String, Object> pd = map(pair[1]);
            Object v = pair[0];
            if (v instanceof List<?> l) {
                String fmt = String.valueOf(pd.getOrDefault("collectionFormat", Boolean.FALSE.equals(pd.get("explode")) ? "csv" : "multi"));
                String sep = switch (fmt) { case "csv" -> ","; case "ssv" -> " "; case "tsv" -> "\t"; case "pipes" -> "|"; default -> null; };
                if (sep == null) for (Object x : l) qs.add(HttpJson.enc(e.getKey()) + "=" + HttpJson.enc(text(x)));
                else qs.add(HttpJson.enc(e.getKey()) + "=" + HttpJson.enc(joinList(l, sep)));
            } else qs.add(HttpJson.enc(e.getKey()) + "=" + HttpJson.enc(text(v)));
        }

        // 3. Auth settings
        Map<String, Object> settings = new LinkedHashMap<>();
        if (auth != null) settings.putAll(auth);
        String secret = null;
        if (credential instanceof SecureString s) {
            String v = s.reveal();
            if (v.trim().startsWith("{")) {
                try { if (JsonLite.parse(v) instanceof Map<?, ?> m) m.forEach((k, x) -> settings.put(String.valueOf(k), x)); else secret = v; }
                catch (IllegalArgumentException e) { secret = v; }
            } else secret = v;
        } else if (credential != null) throw new ActionException("CONNECTOR.CREDENTIAL_REQUIRED", "credential must be a Credential from the vault",
                "Map credential to a Credential variable; secrets are never typed into steps.");
        Map<String, Object> schemeDef = chooseScheme(def, op, settings);

        // 4. Body
        Map<String, Object> bodyDef = op.get("body") instanceof Map<?, ?> bd ? map(bd) : null;
        byte[] payload = null;
        String contentType = null;
        if (bodyDef != null) {
            if (body == null && Boolean.TRUE.equals(bodyDef.get("required")))
                throw new ActionException("CONNECTOR.MISSING_BODY", opId + " needs a request body (" + bodyDef.get("contentType") + ")", "Set body on this step.");
            if (body != null) {
                Object[] built = buildBody(ctx, opId, bodyDef, body);
                payload = (byte[]) built[0]; contentType = (String) built[1];
            }
        } else if (body != null) {
            Object[] built = buildBody(ctx, opId, Map.of("contentType", "application/json"), body);
            payload = (byte[]) built[0]; contentType = (String) built[1];
        }

        Path out = outputFile == null || outputFile.isBlank() ? null : ctx.resolvePath(outputFile);
        if (out != null) {
            try { if (out.getParent() != null) Files.createDirectories(out.getParent()); }
            catch (IOException e) { throw new ActionException("CONNECTOR.IO", "Cannot create folder for " + outputFile + ": " + e.getMessage()); }
        }
        String url = server + p + (qs.length() == 0 ? "" : "?" + qs);
        Map<String, Object> result = map(op.get("result"));

        // 5. Send, renewing an OAuth token once when the API answers 401
        HttpResponse<Object> resp = null;
        for (int attempt = 0; attempt < 2; attempt++) {
            Map<String, String> h = new LinkedHashMap<>();
            String accept = result.get("contentType") instanceof String ct && !ct.isBlank() ? ct + ", */*;q=0.8" : "application/json, */*;q=0.8";
            h.put("Accept", accept);
            for (var e : hdr.entrySet()) h.put(e.getKey(), text(e.getValue()));
            if (headers != null) for (var e : headers.entrySet()) h.put(e.getKey(), text(e.getValue()));
            if (contentType != null) h.put("Content-Type", contentType);
            StringJoiner ck = new StringJoiner("; ");
            for (var e : cookie.entrySet()) ck.add(e.getKey() + "=" + HttpJson.enc(text(e.getValue())));
            String finalUrl = applyAuth(ctx, schemeDef, settings, secret, method, url, h, ck, payload, attempt > 0);
            if (ck.length() > 0) h.put("Cookie", ck.toString());
            resp = send(ctx, method, finalUrl, h, payload, timeout, out);
            if (resp.statusCode() == 401 && attempt == 0 && schemeDef != null && "oauth2_client_credentials".equals(schemeDef.get("type"))) {
                ctx.info("HTTP 401: renewing the OAuth token and trying once more");
                continue;
            }
            break;
        }

        // 6. Response
        int status = resp.statusCode();
        Map<String, Object> rh = new LinkedHashMap<>();
        resp.headers().map().forEach((k, v) -> { if (!k.startsWith(":")) rh.put(k, String.join(", ", v)); });
        String respType = resp.headers().firstValue("Content-Type").orElse("");
        Object parsed = null;
        String savedFile = null;
        if (resp.body() instanceof Path saved) savedFile = saved.toString();
        else {
            byte[] bytes = (byte[]) resp.body();
            boolean binary = ConnectorImporter.isBinaryType(respType) || (Boolean.TRUE.equals(result.get("binary")) && !respType.contains("json") && !respType.startsWith("text/"));
            if (status >= 400 || !binary) {
                String text = new String(bytes, StandardCharsets.UTF_8);
                parsed = text;
                if (!text.isBlank() && (respType.contains("json") || text.strip().startsWith("{") || text.strip().startsWith("["))) {
                    try { parsed = JsonLite.parse(text); } catch (IllegalArgumentException ignored) { }
                }
                if (status >= 400 && failOnError) throw httpError(opId, status, text, parsed, rh);
            } else if (bytes.length > 0) ctx.warn(opId + " returned " + bytes.length + " bytes of " + respType + "; set output_file to save them");
            if (status < 400 && result.get("extract") instanceof String ptr && !ptr.isBlank() && parsed != null) parsed = pointer(parsed, ptr);
        }
        ctx.info(opId + " -> HTTP " + status + (savedFile != null ? ", saved " + savedFile : ""));
        return Out.of("status", BigDecimal.valueOf(status), "headers", rh, "body", parsed, "file", savedFile);
    }

    // ---------- helpers ----------

    private static HttpResponse<Object> send(ActionContext ctx, String method, String url, Map<String, String> h, byte[] payload, int timeout, Path out) {
        HttpRequest.Builder b;
        try {
            b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(timeout <= 0 ? 60 : timeout))
                    .method(method, payload == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(payload));
            h.forEach(b::header);
        } catch (IllegalArgumentException e) {
            throw new ActionException("CONNECTOR.BAD_REQUEST", "Cannot build the request: " + e.getMessage(), "Check base_url, header names and header values.");
        }
        int q = url.indexOf('?');
        ctx.info(method + " " + (q < 0 ? url : url.substring(0, q) + "?…"));
        HttpResponse.BodyHandler<Object> handler = info -> info.statusCode() < 400 && out != null
                ? HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofFile(out), x -> (Object) x)
                : HttpResponse.BodySubscribers.mapping(HttpResponse.BodySubscribers.ofByteArray(), x -> (Object) x);
        try { return HttpJson.CLIENT.send(b.build(), handler); }
        catch (HttpTimeoutException e) { throw new ActionException("CONNECTOR.TIMEOUT", "No response within " + timeout + " s from " + HttpJson.host(url), "Try again later or raise timeout_seconds.", true); }
        catch (IOException e) { throw new ActionException("CONNECTOR.UNREACHABLE", "Cannot reach " + HttpJson.host(url) + ": " + e.getMessage(), "Check the server URL, proxy and network access.", true); }
        catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
    }

    static ActionException httpError(String opId, int status, String text, Object parsed, Map<String, Object> headers) {
        Map<String, List<String>> hm = new LinkedHashMap<>();
        headers.forEach((k, v) -> hm.put(k, List.of(String.valueOf(v))));
        ActionException base = HttpJson.error("CONNECTOR", new HttpJson.Response(status, text, parsed, new byte[0], hm));
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("status", BigDecimal.valueOf(status));
        details.put("operation", opId);
        details.put("body", text.length() > 1000 ? text.substring(0, 1000) : text);
        Object retryAfter = headers.entrySet().stream().filter(e -> e.getKey().equalsIgnoreCase("retry-after")).map(Map.Entry::getValue).findFirst().orElse(null);
        if (retryAfter != null) details.put("retry_after", retryAfter);
        return new ActionException("CONNECTOR.HTTP_" + status, opId + ": " + base.getMessage(), base.suggestedFix(), status == 429 || status >= 500, details);
    }

    /** The auth scheme for this call: auth.scheme when given, else the operation's (or connector's) first scheme; null for none. */
    static Map<String, Object> chooseScheme(Map<String, Object> def, Map<String, Object> op, Map<String, Object> settings) {
        Object wanted = settings.get("scheme");
        if (wanted != null && !String.valueOf(wanted).isBlank()) {
            if ("none".equals(wanted)) return null;
            Map<String, Object> s = scheme(def, String.valueOf(wanted));
            if (s == null) throw new ActionException("CONNECTOR.UNKNOWN_SCHEME", "The connector has no auth scheme '" + wanted + "'",
                    "Use one of: " + list(def.get("auth")).stream().map(x -> String.valueOf(map(x).get("id"))).toList());
            return s;
        }
        List<Object> sec = securityOf(def, op);
        if (sec.isEmpty()) return null;
        Map<String, Object> s = scheme(def, String.valueOf(sec.get(0)));
        if (s == null) throw new ActionException("CONNECTOR.UNKNOWN_SCHEME", "Operation refers to an unknown auth scheme '" + sec.get(0) + "'");
        return s;
    }

    static List<Object> securityOf(Map<String, Object> def, Map<String, Object> op) {
        if (op.get("security") instanceof List<?> l) return list(l);
        if (def.get("security") instanceof List<?> l) return list(l);
        return List.of();
    }

    static Map<String, Object> scheme(Map<String, Object> def, String id) {
        for (Object o : list(def.get("auth"))) if (id.equals(String.valueOf(map(o).get("id")))) return map(o);
        return null;
    }

    /** Adds credentials to headers, cookies or the URL; returns the URL to call. */
    private static String applyAuth(ActionContext ctx, Map<String, Object> s, Map<String, Object> settings, String secret, String method, String url,
                                    Map<String, String> h, StringJoiner cookies, byte[] payload, boolean renew) {
        if (s == null) return url;
        String type = String.valueOf(s.get("type"));
        switch (type) {
            case "api_key" -> {
                String key = need(firstNonNull(secret, setting(settings, "api_key")), "an API key");
                String name = String.valueOf(Objects.requireNonNullElse(setting(settings, "name"), s.get("name")));
                String in = String.valueOf(s.getOrDefault("in", "header"));
                if (in.equals("query")) return url + (url.contains("?") ? "&" : "?") + HttpJson.enc(name) + "=" + HttpJson.enc(key);
                if (in.equals("cookie")) cookies.add(name + "=" + HttpJson.enc(key));
                else h.put(name, key);
            }
            case "basic" -> {
                String user = need(setting(settings, "username"), "auth.username");
                String pw = need(firstNonNull(secret, setting(settings, "password")), "a password");
                h.put("Authorization", "Basic " + Base64.getEncoder().encodeToString((user + ":" + pw).getBytes(StandardCharsets.UTF_8)));
            }
            case "bearer" -> h.put("Authorization", "Bearer " + need(firstNonNull(secret, setting(settings, "token")), "a bearer token"));
            case "oauth2_client_credentials" -> {
                String tokenUrl = String.valueOf(Objects.requireNonNullElse(setting(settings, "token_url"), s.get("tokenUrl")));
                String clientId = need(setting(settings, "client_id"), "auth.client_id");
                String clientSecret = need(firstNonNull(secret, setting(settings, "client_secret")), "a client secret");
                String scope = setting(settings, "scope");
                if (scope == null) scope = String.join(" ", list(s.get("scopes")).stream().map(String::valueOf).toList());
                String clientAuth = String.valueOf(Objects.requireNonNullElse(setting(settings, "client_auth"), s.getOrDefault("clientAuth", "basic")));
                ConnectorAuth.Token t = ConnectorAuth.oauthToken(ctx, tokenUrl, clientId, clientSecret, scope, clientAuth, renew);
                h.put("Authorization", ("bearer".equalsIgnoreCase(t.type()) ? "Bearer" : t.type()) + " " + t.accessToken());
            }
            case "aws_sigv4" -> {
                String access = need(setting(settings, "access_key_id"), "auth.access_key_id");
                String secretKey = need(firstNonNull(secret, setting(settings, "secret_access_key")), "an AWS secret access key");
                String region = firstNonNull(setting(settings, "region"), blankToNull(s.get("region")));
                String service = firstNonNull(setting(settings, "service"), blankToNull(s.get("service")));
                if (region == null || service == null) throw new ActionException("CONNECTOR.AWS_SETTINGS", "AWS signing needs a region and a service",
                        "Set auth.region and auth.service (for example us-east-1 and execute-api).");
                ConnectorAuth.signV4(method, url, h, payload, access, secretKey, setting(settings, "session_token"), region, service, ZonedDateTime.now());
            }
            default -> throw new ActionException("CONNECTOR.UNSUPPORTED_AUTH", "Auth scheme '" + s.get("id") + "' (" + type + ") is not supported",
                    s.get("note") != null ? String.valueOf(s.get("note")) : "Choose another scheme with auth.scheme, or add headers yourself.");
        }
        return url;
    }

    private static String setting(Map<String, Object> settings, String key) {
        Object v = settings.get(key);
        if (v == null) return null;
        String s = v instanceof SecureString ss ? ss.reveal() : Values.text(v);
        return s.isEmpty() ? null : s;
    }

    private static String blankToNull(Object o) { return o == null || String.valueOf(o).isBlank() ? null : String.valueOf(o); }
    private static String firstNonNull(String a, String b) { return a != null ? a : b; }
    private static String need(String v, String what) {
        if (v == null || v.isEmpty()) throw new ActionException("CONNECTOR.NO_CREDENTIAL", "This operation needs " + what,
                "Map credential to a vault Credential and set the non-secret fields in auth.");
        return v;
    }

    /** Returns {bytes, content type}. */
    private static Object[] buildBody(ActionContext ctx, String opId, Map<String, Object> bodyDef, Object body) {
        String ct = String.valueOf(bodyDef.getOrDefault("contentType", "application/json"));
        String lower = ct.toLowerCase(Locale.ROOT);
        Map<String, Object> schema = map(bodyDef.get("schema"));
        if (body instanceof Map<?, ?> bm && schema.get("required") instanceof List<?> req) {
            List<String> miss = new ArrayList<>();
            for (Object r : req) if (!bm.containsKey(String.valueOf(r))) miss.add(String.valueOf(r));
            if (!miss.isEmpty()) throw new ActionException("CONNECTOR.MISSING_BODY_FIELD", opId + " body needs " + String.join(", ", miss), "Add them to body.");
        }
        if (lower.startsWith("multipart/")) {
            Set<String> files = new HashSet<>();
            for (Object f : list(bodyDef.get("fileFields"))) files.add(String.valueOf(f));
            List<Object[]> parts = new ArrayList<>();
            for (var e : map(body instanceof Map<?, ?> ? body : Map.of()).entrySet()) {
                List<?> values = e.getValue() instanceof List<?> l && files.contains(e.getKey()) ? l : List.of(e.getValue() == null ? "" : e.getValue());
                for (Object v : values) {
                    String disp = "Content-Disposition: form-data; name=\"" + e.getKey().replace("\"", "%22") + "\"";
                    if (files.contains(e.getKey())) {
                        Path f = ctx.resolvePath(text(v));
                        byte[] data;
                        try { data = Files.readAllBytes(f); }
                        catch (IOException ex) { throw new ActionException("CONNECTOR.FILE_NOT_FOUND", "Cannot read file for '" + e.getKey() + "': " + v, "Give the path of an existing file."); }
                        parts.add(new Object[]{disp + "; filename=\"" + HttpJson.safeName(f.getFileName().toString()).replace("\"", "%22") + "\"\r\nContent-Type: " + guessType(f), data});
                    } else {
                        boolean structured = v instanceof Map || v instanceof List;
                        parts.add(new Object[]{disp + (structured ? "\r\nContent-Type: application/json" : ""), text(v).getBytes(StandardCharsets.UTF_8)});
                    }
                }
            }
            if (!(body instanceof Map<?, ?>)) throw new ActionException("CONNECTOR.BAD_BODY", opId + " takes form fields: give body as a dictionary");
            Object[] mp = HttpJson.multipart("form-data", parts);
            return new Object[]{mp[0], mp[1]};
        }
        if (lower.startsWith("application/x-www-form-urlencoded")) {
            if (!(body instanceof Map<?, ?> bm)) return new Object[]{text(body).getBytes(StandardCharsets.UTF_8), ct};
            StringJoiner j = new StringJoiner("&");
            bm.forEach((k, v) -> { if (v != null) j.add(HttpJson.enc(String.valueOf(k)) + "=" + HttpJson.enc(text(v))); });
            return new Object[]{j.toString().getBytes(StandardCharsets.UTF_8), ct};
        }
        if (lower.contains("json")) {
            if (body instanceof String s) return new Object[]{s.getBytes(StandardCharsets.UTF_8), ct};
            return new Object[]{JsonLite.write(Values.toJsonable(reveal(body))).getBytes(StandardCharsets.UTF_8), ct};
        }
        if (Boolean.TRUE.equals(bodyDef.get("binary")) || ConnectorImporter.isBinaryType(lower)) {
            Path f = ctx.resolvePath(text(body));
            try { return new Object[]{Files.readAllBytes(f), ct}; }
            catch (IOException ex) { throw new ActionException("CONNECTOR.FILE_NOT_FOUND", opId + " uploads a file; cannot read " + body, "Give the path of an existing file as body."); }
        }
        return new Object[]{text(body).getBytes(StandardCharsets.UTF_8), ct};
    }

    private static String guessType(Path f) {
        try { String t = Files.probeContentType(f); if (t != null) return t; } catch (IOException ignored) { }
        return "application/octet-stream";
    }

    /** Text for URLs, headers and form fields; Credential values are revealed only here, on their way to the API. */
    private static String text(Object v) {
        if (v instanceof SecureString s) return s.reveal();
        if (v instanceof Boolean b) return b.toString();
        return Values.text(reveal(v));
    }

    private static String joinList(Object v, String sep) {
        if (v instanceof List<?> l) { StringJoiner j = new StringJoiner(sep); for (Object x : l) j.add(text(x)); return j.toString(); }
        return text(v);
    }

    private static Object reveal(Object v) {
        if (v instanceof SecureString s) return s.reveal();
        if (v instanceof Map<?, ?> m) { Map<String, Object> out = new LinkedHashMap<>(); m.forEach((k, x) -> out.put(String.valueOf(k), reveal(x))); return out; }
        if (v instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object x : l) out.add(reveal(x)); return out; }
        return v;
    }

    static Object pointer(Object doc, String ptr) {
        Object cur = doc;
        for (String raw : ptr.replaceFirst("^/", "").split("/")) {
            if (raw.isEmpty()) continue;
            String k = raw.replace("~1", "/").replace("~0", "~");
            if (cur instanceof Map<?, ?> m) cur = m.get(k);
            else if (cur instanceof List<?> l) { try { cur = l.get(Integer.parseInt(k)); } catch (RuntimeException e) { return null; } }
            else return null;
        }
        return cur;
    }

    static List<Object> ops(Map<String, Object> def) { return list(def.get("operations")); }

    static Map<String, Object> op(Map<String, Object> def, String connector, String id) {
        for (Object o : ops(def)) if (String.valueOf(map(o).get("id")).equals(id)) return map(o);
        List<String> ids = ops(def).stream().map(o -> String.valueOf(map(o).get("id"))).toList();
        throw new ActionException("CONNECTOR.UNKNOWN_OPERATION", "Connector '" + connector + "' has no operation '" + id + "'",
                ids.size() <= 15 ? "Use one of: " + String.join(", ", ids) : "Use List operations to see the " + ids.size() + " operation ids.");
    }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked")
    static List<Object> list(Object o) { return o instanceof List<?> l ? (List<Object>) l : new ArrayList<>(); }
}
