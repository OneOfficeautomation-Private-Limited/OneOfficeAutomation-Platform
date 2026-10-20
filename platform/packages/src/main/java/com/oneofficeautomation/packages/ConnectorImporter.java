package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.JsonLite;

import java.math.BigDecimal;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.*;

/**
 * Turns an API description into a custom connector definition ({@code ooa.connector/v1}). Reads OpenAPI 3.x, Swagger 2.0
 * (JSON, or YAML when SnakeYAML is on the classpath) and Postman collections v2.1. Local {@code $ref}s are resolved in place;
 * a reference that points back into itself is cut and marked {@code "x-recursive": true}.
 *
 * <pre>
 * {"format": "ooa.connector/v1", "name": "petstore", "title": "...", "description": "...", "version": "1.0",
 *  "source": {"type": "openapi|swagger|postman", "version": "3.0.3", "importedAt": "..."},
 *  "servers": [{"url": "https://api.example.com/v1", "description": "..."}], "server": "https://api.example.com/v1",
 *  "auth": [{"id": "key", "type": "api_key", "in": "header|query|cookie", "name": "X-API-Key"},
 *           {"id": "basic", "type": "basic"}, {"id": "token", "type": "bearer"},
 *           {"id": "oauth", "type": "oauth2_client_credentials", "tokenUrl": "...", "scopes": [], "clientAuth": "basic|body"},
 *           {"id": "aws", "type": "aws_sigv4", "region": "", "service": ""}],
 *  "security": ["key"],
 *  "operations": [{"id": "getPet", "name": "Get pet", "description": "...", "method": "GET", "path": "/pets/{petId}", "tags": [],
 *     "security": ["key"] (absent = connector default, [] = no auth),
 *     "parameters": [{"name": "petId", "in": "path|query|header|cookie", "type": "integer", "required": true, "default": null, ...}],
 *     "body": {"required": true, "contentType": "application/json", "contentTypes": [...], "schema": {...}, "fileFields": [], "example": ...},
 *     "responses": [{"status": "200", "description": "...", "contentType": "application/json", "schema": {...}, "binary": false}],
 *     "result": {"status": "200", "contentType": "application/json", "binary": false, "extract": null}}]}
 * </pre>
 */
public final class ConnectorImporter {
    private ConnectorImporter() {}

    public static final String FORMAT = "ooa.connector/v1";
    static final List<String> METHODS = List.of("get", "put", "post", "delete", "options", "head", "patch", "trace");
    private static final int MAX_REF_DEPTH = 32;

    /**
     * Imports a spec.
     * @param text   file content: OpenAPI/Swagger JSON or YAML, or a Postman collection (JSON)
     * @param name   connector name, or null/blank to derive it from the spec title
     * @param server preferred server URL, or null to use the first one in the spec
     */
    public static Map<String, Object> importSpec(String text, String name, String server) {
        if (text == null || text.isBlank()) throw new ActionException("CONNECTOR.EMPTY_SPEC", "The API description is empty");
        Object doc = parse(text);
        if (!(doc instanceof Map<?, ?> m)) throw new ActionException("CONNECTOR.UNKNOWN_FORMAT", "The file is not an OpenAPI, Swagger or Postman document",
                "Use an OpenAPI 3.x or Swagger 2.0 document, or a Postman v2.1 collection export.");
        @SuppressWarnings("unchecked") Map<String, Object> root = (Map<String, Object>) m;
        Map<String, Object> def;
        if (root.get("openapi") != null && String.valueOf(root.get("openapi")).startsWith("3")) def = new OpenApi(root, false).build();
        else if (root.get("swagger") != null && String.valueOf(root.get("swagger")).startsWith("2")) def = new OpenApi(root, true).build();
        else if (root.get("item") instanceof List<?> && root.get("info") instanceof Map<?, ?>) def = new Postman(root).build();
        else if (root.get("openapi") != null || root.get("swagger") != null)
            throw new ActionException("CONNECTOR.UNSUPPORTED_VERSION", "Unsupported spec version " + Objects.requireNonNullElse(root.get("openapi"), root.get("swagger")),
                    "Use OpenAPI 3.x or Swagger 2.0.");
        else throw new ActionException("CONNECTOR.UNKNOWN_FORMAT", "The file is not an OpenAPI, Swagger or Postman document",
                    "Use an OpenAPI 3.x or Swagger 2.0 document, or a Postman v2.1 collection export.");
        String n = name == null || name.isBlank() ? slug(String.valueOf(def.getOrDefault("title", "connector"))) : name.trim();
        def.put("name", ConnectorStore.checkName(n));
        if (server != null && !server.isBlank()) def.put("server", server.trim());
        Map<String, Object> ordered = new LinkedHashMap<>();
        ordered.put("format", FORMAT);
        ordered.put("name", def.get("name"));
        ordered.putAll(def);
        if (((List<?>) ordered.get("operations")).isEmpty())
            throw new ActionException("CONNECTOR.NO_OPERATIONS", "The API description has no operations", "Check that the file lists paths or requests.");
        return ordered;
    }

    // ---------- parsing ----------

    static Object parse(String text) {
        String t = text.strip();
        if (t.startsWith("﻿")) t = t.substring(1);
        if (t.startsWith("{") || t.startsWith("[")) {
            try { return JsonLite.parse(t); }
            catch (IllegalArgumentException e) { throw new ActionException("CONNECTOR.BAD_JSON", "The file is not valid JSON: " + e.getMessage()); }
        }
        return parseYaml(t);
    }

    /**
     * YAML through SnakeYAML when it is on the classpath (it ships with the Studio and agent runtime through the JSON Schema
     * validator). Looked up by reflection so this module keeps no YAML dependency. Uses the safe constructor: plain data only.
     */
    static Object parseYaml(String text) {
        Class<?> yamlCls;
        try { yamlCls = Class.forName("org.yaml.snakeyaml.Yaml"); }
        catch (ClassNotFoundException | LinkageError e) {
            throw new ActionException("CONNECTOR.YAML_UNSUPPORTED", "YAML files cannot be read here because no YAML parser is installed",
                    "Convert the spec to JSON (most API tools can export JSON), or import it from One Agent Studio, which can read YAML.");
        }
        Object loaded;
        try {
            Class<?> optsCls = Class.forName("org.yaml.snakeyaml.LoaderOptions");
            Object opts = optsCls.getConstructor().newInstance();
            try { optsCls.getMethod("setCodePointLimit", int.class).invoke(opts, 20 * 1024 * 1024); } catch (NoSuchMethodException ignored) { }
            Class<?> ctorCls = Class.forName("org.yaml.snakeyaml.constructor.SafeConstructor");
            Object ctor = ctorCls.getConstructor(optsCls).newInstance(opts);
            Class<?> baseCtor = Class.forName("org.yaml.snakeyaml.constructor.BaseConstructor");
            Object yaml = yamlCls.getConstructor(baseCtor).newInstance(ctor);
            loaded = yamlCls.getMethod("load", String.class).invoke(yaml, text);
        } catch (java.lang.reflect.InvocationTargetException e) {
            throw new ActionException("CONNECTOR.BAD_YAML", "The file is not valid YAML: " + firstLine(e.getCause()));
        } catch (ReflectiveOperationException | LinkageError e) {
            throw new ActionException("CONNECTOR.YAML_UNSUPPORTED", "The installed YAML parser is not compatible: " + e,
                    "Convert the spec to JSON and import it again.");
        }
        return plain(loaded);
    }

    private static String firstLine(Throwable t) {
        String m = t == null ? "" : String.valueOf(t.getMessage());
        int i = m.indexOf('\n');
        return i > 0 ? m.substring(0, i) : m;
    }

    /** YAML values to the same shapes JsonLite produces: ordered string-keyed maps, lists, BigDecimal numbers, strings. */
    private static Object plain(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> out.put(String.valueOf(k), plain(x)));
            return out;
        }
        if (v instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object x : l) out.add(plain(x)); return out; }
        if (v instanceof Number n) return n instanceof Double d && (d.isNaN() || d.isInfinite()) ? String.valueOf(d) : new BigDecimal(n.toString());
        if (v instanceof Date d) return d.toInstant().toString();
        if (v instanceof byte[] b) return Base64.getEncoder().encodeToString(b);
        if (v instanceof Set<?> s) return plain(new ArrayList<>(s));
        return v;
    }

    // ---------- shared helpers ----------

    static String slug(String s) {
        String x = s.trim().toLowerCase(Locale.ROOT).replaceAll("[^a-z0-9]+", "_").replaceAll("^_+|_+$", "");
        if (x.isEmpty()) x = "connector";
        return x.length() > 60 ? x.substring(0, 60) : x;
    }

    /** "listPetsByOwner" or "list_pets" to "List pets by owner". */
    static String humanize(String id) {
        String s = id.replaceAll("([a-z0-9])([A-Z])", "$1 $2").replace('_', ' ').replace('-', ' ').replaceAll("\\s+", " ").trim().toLowerCase(Locale.ROOT);
        return s.isEmpty() ? id : Character.toUpperCase(s.charAt(0)) + s.substring(1);
    }

    /** An operation id from method and path, e.g. GET /pets/{id} to get_pets_id. */
    static String opId(String method, String path) {
        String p = path.replaceAll("[{}:]", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
        return method.toLowerCase(Locale.ROOT) + (p.isEmpty() ? "_root" : "_" + p);
    }

    static String unique(String id, Set<String> used) {
        String base = id.replaceAll("[^A-Za-z0-9_.-]", "_");
        if (base.isEmpty()) base = "operation";
        String out = base;
        for (int i = 2; !used.add(out); i++) out = base + "_" + i;
        return out;
    }

    static boolean isBinaryType(String contentType) {
        if (contentType == null) return false;
        String c = contentType.toLowerCase(Locale.ROOT);
        return c.startsWith("application/octet-stream") || c.startsWith("image/") || c.startsWith("audio/") || c.startsWith("video/")
                || c.equals("application/pdf") || c.equals("application/zip") || c.contains("spreadsheetml") || c.contains("wordprocessingml")
                || c.equals("application/vnd.ms-excel") || c.equals("application/msword");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>(); }
    @SuppressWarnings("unchecked")
    private static List<Object> list(Object o) { return o instanceof List<?> l ? (List<Object>) l : new ArrayList<>(); }
    private static String str(Object o) { return o == null ? null : o instanceof BigDecimal d ? d.toPlainString() : String.valueOf(o); }
    private static void putIf(Map<String, Object> m, String k, Object v) {
        if (v != null && !(v instanceof String s && s.isEmpty()) && !(v instanceof Collection<?> c && c.isEmpty())) m.put(k, v);
    }

    // ---------- OpenAPI 3.x and Swagger 2.0 ----------

    private static final class OpenApi {
        final Map<String, Object> root;
        final boolean swagger;
        final Map<String, Map<String, Object>> schemes = new LinkedHashMap<>();

        OpenApi(Map<String, Object> root, boolean swagger) { this.root = root; this.swagger = swagger; }

        Map<String, Object> build() {
            Map<String, Object> info = map(root.get("info"));
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("title", Objects.requireNonNullElse(str(info.get("title")), "API"));
            putIf(def, "description", str(info.get("description")));
            putIf(def, "version", str(info.get("version")));
            def.put("source", new LinkedHashMap<>(Map.of("type", swagger ? "swagger" : "openapi",
                    "version", str(root.get(swagger ? "swagger" : "openapi")), "importedAt", Instant.now().toString())));
            List<Object> servers = servers();
            def.put("servers", servers);
            def.put("server", servers.isEmpty() ? "" : map(servers.get(0)).get("url"));
            List<Object> auth = auth();
            def.put("auth", auth);
            def.put("security", security(root.get("security")));
            def.put("operations", operations());
            return def;
        }

        List<Object> servers() {
            List<Object> out = new ArrayList<>();
            if (swagger) {
                String host = str(root.get("host"));
                String basePath = Objects.requireNonNullElse(str(root.get("basePath")), "");
                List<Object> schemesList = list(root.get("schemes"));
                if (host == null) { if (!basePath.isEmpty()) out.add(new LinkedHashMap<>(Map.of("url", basePath))); return out; }
                if (schemesList.isEmpty()) schemesList = List.of("https");
                for (Object s : schemesList) out.add(new LinkedHashMap<>(Map.of("url", s + "://" + host + basePath)));
                out.sort(Comparator.comparing(o -> String.valueOf(map(o).get("url")).startsWith("https") ? 0 : 1));
                return out;
            }
            for (Object o : list(root.get("servers"))) {
                Map<String, Object> s = map(o);
                String url = str(s.get("url"));
                if (url == null) continue;
                for (var v : map(s.get("variables")).entrySet()) {
                    Object d = map(v.getValue()).get("default");
                    if (d != null) url = url.replace("{" + v.getKey() + "}", str(d));
                }
                if (url.endsWith("/") && url.length() > 1) url = url.substring(0, url.length() - 1);
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("url", url);
                putIf(e, "description", str(s.get("description")));
                out.add(e);
            }
            return out;
        }

        List<Object> auth() {
            Map<String, Object> defs = swagger ? map(root.get("securityDefinitions")) : map(map(root.get("components")).get("securitySchemes"));
            List<Object> out = new ArrayList<>();
            for (var e : defs.entrySet()) {
                Map<String, Object> s = map(resolve(e.getValue()));
                Map<String, Object> a = new LinkedHashMap<>();
                a.put("id", e.getKey());
                String type = Objects.requireNonNullElse(str(s.get("type")), "");
                String scheme = Objects.requireNonNullElse(str(s.get("scheme")), "").toLowerCase(Locale.ROOT);
                Object awsType = s.get("x-amazon-apigateway-authtype");
                if (awsType != null && String.valueOf(awsType).toLowerCase(Locale.ROOT).contains("sigv4")) {
                    a.put("type", "aws_sigv4"); a.put("region", ""); a.put("service", "execute-api");
                } else if (type.equals("apiKey")) {
                    a.put("type", "api_key"); a.put("in", Objects.requireNonNullElse(str(s.get("in")), "header")); a.put("name", str(s.get("name")));
                } else if (type.equals("basic") || (type.equals("http") && scheme.equals("basic"))) {
                    a.put("type", "basic");
                } else if (type.equals("http") && scheme.equals("bearer")) {
                    a.put("type", "bearer"); putIf(a, "format", str(s.get("bearerFormat")));
                } else if (type.equals("http") && (scheme.equals("aws4-hmac-sha256") || scheme.equals("sigv4"))) {
                    a.put("type", "aws_sigv4"); a.put("region", ""); a.put("service", "");
                } else if (type.equals("oauth2")) {
                    String tokenUrl = null; Object scopes = null;
                    if (swagger && "application".equals(s.get("flow"))) { tokenUrl = str(s.get("tokenUrl")); scopes = s.get("scopes"); }
                    else if (!swagger && map(s.get("flows")).get("clientCredentials") instanceof Map<?, ?> cc) { tokenUrl = str(cc.get("tokenUrl")); scopes = cc.get("scopes"); }
                    if (tokenUrl != null) {
                        a.put("type", "oauth2_client_credentials"); a.put("tokenUrl", tokenUrl);
                        a.put("scopes", new ArrayList<>(map(scopes).keySet())); a.put("clientAuth", "basic");
                    } else { a.put("type", "bearer"); a.put("note", "OAuth 2.0 flow other than client credentials: supply an access token"); }
                } else if (type.equals("openIdConnect")) {
                    a.put("type", "bearer"); a.put("note", "OpenID Connect: supply an access token");
                } else {
                    a.put("type", "unsupported"); a.put("note", "Scheme type '" + type + (scheme.isEmpty() ? "" : "/" + scheme) + "' is not supported");
                }
                putIf(a, "description", str(s.get("description")));
                schemes.put(e.getKey(), a);
                out.add(a);
            }
            return out;
        }

        /** Security requirements to a list of scheme ids (any one of them works); null when not stated. */
        List<Object> security(Object sec) {
            if (!(sec instanceof List<?> l)) return null;
            List<Object> ids = new ArrayList<>();
            for (Object req : l) {
                Map<String, Object> r = map(req);
                if (r.isEmpty()) continue;  // {} means anonymous access is allowed
                String first = r.keySet().iterator().next();
                if (!ids.contains(first)) ids.add(first);
            }
            return ids;
        }

        List<Object> operations() {
            List<Object> out = new ArrayList<>();
            Set<String> used = new HashSet<>();
            for (var pe : map(root.get("paths")).entrySet()) {
                String path = pe.getKey();
                Map<String, Object> item = map(resolve(pe.getValue()));
                List<Object> shared = list(item.get("parameters"));
                for (String m : METHODS) {
                    if (!(item.get(m) instanceof Map<?, ?>)) continue;
                    Map<String, Object> op = map(item.get(m));
                    Map<String, Object> o = new LinkedHashMap<>();
                    String id = str(op.get("operationId"));
                    o.put("id", unique(id == null || id.isBlank() ? opId(m, path) : id, used));
                    String summary = str(op.get("summary"));
                    o.put("name", summary != null && !summary.isBlank() ? summary.strip() : humanize(id != null ? id : m + " " + path));
                    putIf(o, "description", str(op.get("description")));
                    o.put("method", m.toUpperCase(Locale.ROOT));
                    o.put("path", path);
                    putIf(o, "tags", list(op.get("tags")));
                    if (Boolean.TRUE.equals(op.get("deprecated"))) o.put("deprecated", true);
                    List<Object> sec = security(op.get("security"));
                    if (sec != null) o.put("security", sec);
                    Map<String, Map<String, Object>> params = new LinkedHashMap<>();
                    List<Object> formFields = new ArrayList<>();
                    Map<String, Object> swaggerBody = null;
                    List<Object> all = new ArrayList<>(shared);
                    all.addAll(list(op.get("parameters")));
                    for (Object po : all) {
                        Map<String, Object> p = map(resolve(po));
                        String in = str(p.get("in"));
                        if (in == null) continue;
                        if (swagger && in.equals("body")) { swaggerBody = p; continue; }
                        if (swagger && in.equals("formData")) { formFields.add(p); continue; }
                        params.put(in + ":" + p.get("name"), param(p));  // operation level overrides path level
                    }
                    o.put("parameters", new ArrayList<>(params.values()));
                    Map<String, Object> body = swagger ? swaggerBody(op, swaggerBody, formFields) : body(op.get("requestBody"));
                    if (body != null) o.put("body", body);
                    responses(op, o);
                    out.add(o);
                }
            }
            return out;
        }

        Map<String, Object> param(Map<String, Object> p) {
            Map<String, Object> schema = swagger ? p : map(p.get("schema"));
            Map<String, Object> r = new LinkedHashMap<>();
            r.put("name", str(p.get("name")));
            r.put("in", str(p.get("in")));
            r.put("type", typeOf(schema));
            r.put("required", Boolean.TRUE.equals(p.get("required")) || "path".equals(p.get("in")));
            r.put("default", schema.get("default"));
            putIf(r, "format", str(schema.get("format")));
            putIf(r, "enum", list(schema.get("enum")));
            if (schema.get("items") instanceof Map<?, ?> items) putIf(r, "items", typeOf(map(items)));
            putIf(r, "description", str(p.get("description")));
            Object ex = p.get("example") != null ? p.get("example") : schema.get("example");
            if (ex != null) r.put("example", ex);
            if (p.get("style") != null) r.put("style", str(p.get("style")));
            if (p.get("explode") instanceof Boolean b) r.put("explode", b);
            if (swagger && p.get("collectionFormat") != null) r.put("collectionFormat", str(p.get("collectionFormat")));
            return r;
        }

        Map<String, Object> body(Object rbo) {
            if (rbo == null) return null;
            Map<String, Object> rb = map(resolve(rbo));
            Map<String, Object> content = map(rb.get("content"));
            if (content.isEmpty()) return null;
            List<String> types = new ArrayList<>(content.keySet());
            String chosen = types.stream().filter(t -> t.contains("json")).findFirst()
                    .or(() -> types.stream().filter(t -> t.startsWith("multipart/form-data")).findFirst())
                    .or(() -> types.stream().filter(t -> t.startsWith("application/x-www-form-urlencoded")).findFirst())
                    .orElse(types.get(0));
            Map<String, Object> media = map(content.get(chosen));
            Map<String, Object> schema = map(media.get("schema"));
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("required", Boolean.TRUE.equals(rb.get("required")));
            b.put("contentType", chosen);
            if (types.size() > 1) b.put("contentTypes", types);
            b.put("schema", schema);
            List<Object> files = new ArrayList<>();
            for (var pe : map(schema.get("properties")).entrySet()) {
                Map<String, Object> ps = map(pe.getValue());
                if (isFileSchema(ps) || (ps.get("items") instanceof Map<?, ?> it && isFileSchema(map(it)))) files.add(pe.getKey());
            }
            if (chosen.startsWith("multipart/") || !files.isEmpty()) putIf(b, "fileFields", files);
            if (isFileSchema(schema) || isBinaryType(chosen)) b.put("binary", true);
            Object ex = media.get("example") != null ? media.get("example") : schema.get("example");
            if (ex == null && media.get("examples") instanceof Map<?, ?> exs && !exs.isEmpty()) ex = map(exs.values().iterator().next()).get("value");
            if (ex != null) b.put("example", ex);
            putIf(b, "description", str(rb.get("description")));
            return b;
        }

        static boolean isFileSchema(Map<String, Object> s) {
            return "file".equals(s.get("type")) || ("string".equals(s.get("type")) && ("binary".equals(s.get("format")) || "base64".equals(s.get("format"))))
                    || s.get("contentMediaType") != null && "string".equals(s.get("type"));
        }

        Map<String, Object> swaggerBody(Map<String, Object> op, Map<String, Object> bodyParam, List<Object> formFields) {
            List<Object> consumes = list(op.get("consumes") != null ? op.get("consumes") : root.get("consumes"));
            if (bodyParam != null) {
                Map<String, Object> b = new LinkedHashMap<>();
                b.put("required", Boolean.TRUE.equals(bodyParam.get("required")));
                String ct = consumes.stream().map(String::valueOf).filter(t -> t.contains("json")).findFirst()
                        .orElse(consumes.isEmpty() ? "application/json" : String.valueOf(consumes.get(0)));
                b.put("contentType", ct);
                b.put("schema", map(bodyParam.get("schema")));
                putIf(b, "description", str(bodyParam.get("description")));
                return b;
            }
            if (formFields.isEmpty()) return null;
            Map<String, Object> props = new LinkedHashMap<>();
            List<Object> required = new ArrayList<>(), files = new ArrayList<>();
            for (Object f : formFields) {
                Map<String, Object> p = map(f);
                String n = str(p.get("name"));
                Map<String, Object> s = new LinkedHashMap<>();
                s.put("type", Objects.requireNonNullElse(str(p.get("type")), "string"));
                putIf(s, "description", str(p.get("description")));
                if (p.get("default") != null) s.put("default", p.get("default"));
                props.put(n, s);
                if (Boolean.TRUE.equals(p.get("required"))) required.add(n);
                if ("file".equals(p.get("type"))) files.add(n);
            }
            boolean multipart = !files.isEmpty() || consumes.stream().anyMatch(c -> String.valueOf(c).startsWith("multipart/"));
            Map<String, Object> schema = new LinkedHashMap<>();
            schema.put("type", "object");
            schema.put("properties", props);
            if (!required.isEmpty()) schema.put("required", required);
            Map<String, Object> b = new LinkedHashMap<>();
            b.put("required", !required.isEmpty());
            b.put("contentType", multipart ? "multipart/form-data" : "application/x-www-form-urlencoded");
            b.put("schema", schema);
            putIf(b, "fileFields", files);
            return b;
        }

        void responses(Map<String, Object> op, Map<String, Object> o) {
            List<Object> out = new ArrayList<>();
            Map<String, Object> result = null;
            List<Object> produces = list(op.get("produces") != null ? op.get("produces") : root.get("produces"));
            for (var re : map(op.get("responses")).entrySet()) {
                Map<String, Object> r = map(resolve(re.getValue()));
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("status", re.getKey());
                putIf(x, "description", str(r.get("description")));
                String ct = null; Map<String, Object> schema = null;
                if (swagger) {
                    schema = r.get("schema") instanceof Map<?, ?> ? map(r.get("schema")) : null;
                    if (schema != null) ct = produces.stream().map(String::valueOf).filter(t -> t.contains("json")).findFirst()
                            .orElse(produces.isEmpty() ? "application/json" : String.valueOf(produces.get(0)));
                } else {
                    Map<String, Object> content = map(r.get("content"));
                    if (!content.isEmpty()) {
                        ct = content.keySet().stream().filter(t -> t.contains("json")).findFirst().orElse(content.keySet().iterator().next());
                        schema = map(map(content.get(ct)).get("schema"));
                    }
                }
                putIf(x, "contentType", ct);
                if (schema != null && !schema.isEmpty()) x.put("schema", schema);
                boolean binary = isBinaryType(ct) || (schema != null && isFileSchema(schema));
                x.put("binary", binary);
                out.add(x);
                if (result == null && re.getKey().startsWith("2")) {
                    result = new LinkedHashMap<>();
                    result.put("status", re.getKey());
                    result.put("contentType", ct);
                    result.put("binary", binary);
                    result.put("extract", null);
                }
            }
            o.put("responses", out);
            if (result == null) { result = new LinkedHashMap<>(); result.put("status", "200"); result.put("contentType", null); result.put("binary", false); result.put("extract", null); }
            o.put("result", result);
        }

        static String typeOf(Map<String, Object> schema) {
            Object t = schema.get("type");
            if (t instanceof List<?> l) t = l.stream().map(x -> (Object) x).filter(x -> !"null".equals(x)).findFirst().orElse("string");
            if (t == null) t = schema.get("properties") != null ? "object" : schema.get("items") != null ? "array" : "string";
            return String.valueOf(t);
        }

        Object resolve(Object node) { return resolve(node, new ArrayDeque<>()); }

        /** Replaces local {"$ref": "#/..."} nodes with their targets, recursively. Sibling keys of a $ref are kept. */
        Object resolve(Object node, Deque<String> stack) {
            if (node instanceof Map<?, ?> m) {
                if (m.get("$ref") instanceof String ref) {
                    if (!ref.startsWith("#")) {
                        Map<String, Object> ext = new LinkedHashMap<>(map(m));
                        ext.put("x-unresolved", "External references are not followed");
                        return ext;
                    }
                    if (stack.contains(ref) || stack.size() >= MAX_REF_DEPTH) {
                        Map<String, Object> cut = new LinkedHashMap<>();
                        cut.put("$ref", ref); cut.put("x-recursive", true);
                        return cut;
                    }
                    Object target = pointer(root, ref);
                    if (target == null) throw new ActionException("CONNECTOR.BAD_REF", "Reference " + ref + " does not point to anything in the document",
                            "Fix the reference, or bundle external files into one document before importing.");
                    stack.push(ref);
                    Object r = resolve(target, stack);
                    stack.pop();
                    if (m.size() > 1 && r instanceof Map<?, ?> rm) {
                        Map<String, Object> merged = new LinkedHashMap<>(map(rm));
                        for (var e : map(m).entrySet()) if (!e.getKey().equals("$ref")) merged.put(e.getKey(), resolve(e.getValue(), stack));
                        return merged;
                    }
                    return r;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (var e : map(m).entrySet()) out.put(e.getKey(), resolve(e.getValue(), stack));
                return out;
            }
            if (node instanceof List<?> l) { List<Object> out = new ArrayList<>(); for (Object x : l) out.add(resolve(x, stack)); return out; }
            return node;
        }

        static Object pointer(Object doc, String ref) {
            String p = ref.substring(1);
            if (p.isEmpty()) return doc;
            Object cur = doc;
            for (String raw : p.substring(1).split("/", -1)) {
                String key = URLDecoder.decode(raw.replace("+", "%2B"), StandardCharsets.UTF_8).replace("~1", "/").replace("~0", "~");
                if (cur instanceof Map<?, ?> m) cur = m.get(key);
                else if (cur instanceof List<?> l) { try { cur = l.get(Integer.parseInt(key)); } catch (RuntimeException e) { return null; } }
                else return null;
                if (cur == null) return null;
            }
            return cur;
        }
    }

    // ---------- Postman collection v2.1 ----------

    private static final class Postman {
        final Map<String, Object> root;
        final Map<String, String> vars = new LinkedHashMap<>();
        final List<Object> auth = new ArrayList<>();
        final Map<String, String> authIds = new LinkedHashMap<>();  // canonical JSON of scheme -> id
        final Map<String, Integer> origins = new LinkedHashMap<>();
        final Set<String> used = new HashSet<>();

        Postman(Map<String, Object> root) { this.root = root; }

        Map<String, Object> build() {
            Map<String, Object> info = map(root.get("info"));
            String schema = Objects.requireNonNullElse(str(info.get("schema")), "");
            for (Object v : list(root.get("variable"))) {
                Map<String, Object> m = map(v);
                if (m.get("key") != null) vars.put(str(m.get("key")), Objects.requireNonNullElse(str(m.get("value")), ""));
            }
            Map<String, Object> def = new LinkedHashMap<>();
            def.put("title", Objects.requireNonNullElse(str(info.get("name")), "Postman collection"));
            Object desc = info.get("description");
            putIf(def, "description", desc instanceof Map<?, ?> dm ? str(dm.get("content")) : str(desc));
            putIf(def, "version", info.get("version") instanceof Map<?, ?> vm ? str(vm.get("major")) + "." + str(vm.get("minor")) + "." + str(vm.get("patch")) : str(info.get("version")));
            def.put("source", new LinkedHashMap<>(Map.of("type", "postman", "version", schema.contains("v2.0") ? "2.0" : "2.1", "importedAt", Instant.now().toString())));
            String collectionAuth = authId(root.get("auth"));
            List<Object> ops = new ArrayList<>();
            walk(list(root.get("item")), collectionAuth, new ArrayList<>(), ops);
            List<Object> servers = new ArrayList<>();
            origins.entrySet().stream().sorted((a, b) -> b.getValue() - a.getValue())
                    .forEach(e -> servers.add(new LinkedHashMap<>(Map.of("url", e.getKey()))));
            def.put("servers", servers);
            def.put("server", servers.isEmpty() ? "" : map(servers.get(0)).get("url"));
            def.put("auth", auth);
            def.put("security", collectionAuth == null ? null : collectionAuth.isEmpty() ? List.of() : List.of(collectionAuth));
            def.put("operations", ops);
            return def;
        }

        void walk(List<Object> items, String inheritedAuth, List<String> folders, List<Object> out) {
            for (Object io : items) {
                Map<String, Object> it = map(io);
                String a = it.containsKey("auth") ? authId(it.get("auth")) : inheritedAuth;
                if (it.get("item") instanceof List<?> sub) {
                    List<String> f = new ArrayList<>(folders);
                    f.add(Objects.requireNonNullElse(str(it.get("name")), "folder"));
                    walk(list(sub), a, f, out);
                } else if (it.get("request") != null) {
                    Object req = it.get("request");
                    Map<String, Object> r = req instanceof String s ? new LinkedHashMap<>(Map.of("url", s, "method", "GET")) : map(req);
                    String ra = r.containsKey("auth") ? authId(r.get("auth")) : a;
                    out.add(operation(it, r, ra, folders));
                }
            }
        }

        /** Registers a Postman auth block as a connector scheme; returns its id, "" for no auth, null for none stated. */
        String authId(Object authObj) {
            if (!(authObj instanceof Map<?, ?> am)) return null;
            String type = str(am.get("type"));
            if (type == null || type.equals("noauth")) return "";
            Map<String, String> kv = new LinkedHashMap<>();
            Object params = am.get(type);
            if (params instanceof List<?> l) for (Object p : l) { Map<String, Object> pm = map(p); kv.put(str(pm.get("key")), Objects.requireNonNullElse(str(pm.get("value")), "")); }
            else for (var e : map(params).entrySet()) kv.put(e.getKey(), Objects.requireNonNullElse(str(e.getValue()), ""));
            Map<String, Object> s = new LinkedHashMap<>();
            switch (type) {
                case "apikey" -> { s.put("type", "api_key"); s.put("in", kv.getOrDefault("in", "header")); s.put("name", kv.getOrDefault("key", "X-API-Key")); }
                case "basic" -> s.put("type", "basic");
                case "bearer" -> s.put("type", "bearer");
                case "oauth2" -> {
                    String grant = kv.getOrDefault("grant_type", "");
                    if (grant.equals("client_credentials") && !kv.getOrDefault("accessTokenUrl", "").isEmpty()) {
                        s.put("type", "oauth2_client_credentials"); s.put("tokenUrl", subst(kv.get("accessTokenUrl")));
                        String scope = kv.getOrDefault("scope", "").trim();
                        s.put("scopes", scope.isEmpty() ? List.of() : List.of(scope.split("\\s+")));
                        s.put("clientAuth", "body".equals(kv.get("client_authentication")) ? "body" : "basic");
                    } else { s.put("type", "bearer"); s.put("note", "OAuth 2.0 flow other than client credentials: supply an access token"); }
                }
                case "awsv4" -> { s.put("type", "aws_sigv4"); s.put("region", subst(kv.getOrDefault("region", ""))); s.put("service", subst(kv.getOrDefault("service", ""))); }
                default -> { s.put("type", "unsupported"); s.put("note", "Postman auth type '" + type + "' is not supported"); }
            }
            String key = JsonLite.canonical(s);
            String id = authIds.get(key);
            if (id != null) return id;
            id = String.valueOf(s.get("type")).replace("_client_credentials", "");
            for (int i = 2; authIds.containsValue(id); i++) id = s.get("type") + "_" + i;
            authIds.put(key, id);
            Map<String, Object> withId = new LinkedHashMap<>();
            withId.put("id", id);
            withId.putAll(s);
            auth.add(withId);
            return id;
        }

        String subst(String s) {
            if (s == null) return null;
            String out = s;
            for (var e : vars.entrySet()) out = out.replace("{{" + e.getKey() + "}}", e.getValue());
            return out;
        }

        Map<String, Object> operation(Map<String, Object> item, Map<String, Object> r, String authId, List<String> folders) {
            Map<String, Object> o = new LinkedHashMap<>();
            String name = Objects.requireNonNullElse(str(item.get("name")), "request");
            String method = Objects.requireNonNullElse(str(r.get("method")), "GET").toUpperCase(Locale.ROOT);
            Object desc = r.get("description") != null ? r.get("description") : item.get("description");
            o.put("id", unique(camel(name), used));
            o.put("name", name);
            putIf(o, "description", desc instanceof Map<?, ?> dm ? str(dm.get("content")) : str(desc));
            o.put("method", method);
            List<Object> params = new ArrayList<>();
            String raw;
            Map<String, Object> url = r.get("url") instanceof Map<?, ?> um ? map(um) : null;
            raw = url != null ? Objects.requireNonNullElse(str(url.get("raw")), rebuild(url)) : Objects.requireNonNullElse(str(r.get("url")), "/");
            int q = raw.indexOf('?');
            String noQuery = q >= 0 ? raw.substring(0, q) : raw;
            String[] split = splitOrigin(noQuery);
            String origin = split[0], path = split[1];
            if (!origin.isEmpty()) origins.merge(origin, 1, Integer::sum);
            // :name and leftover {{name}} segments become path parameters
            StringBuilder p = new StringBuilder();
            Map<String, String> pathDefaults = new LinkedHashMap<>();
            if (url != null) for (Object v : list(url.get("variable"))) { Map<String, Object> vm = map(v); pathDefaults.put(str(vm.get("key")), str(vm.get("value"))); }
            for (String seg : path.split("/")) {
                if (seg.isEmpty()) continue;
                String s = seg;
                if (s.startsWith(":")) s = "{" + s.substring(1) + "}";
                s = s.replaceAll("\\{\\{([^}]+)}}", "{$1}");
                p.append('/').append(s);
                java.util.regex.Matcher m = java.util.regex.Pattern.compile("\\{([^}]+)}").matcher(s);
                while (m.find()) {
                    Map<String, Object> pp = new LinkedHashMap<>();
                    pp.put("name", m.group(1)); pp.put("in", "path"); pp.put("type", "string"); pp.put("required", true);
                    String d = pathDefaults.get(m.group(1));
                    pp.put("default", d == null || d.isEmpty() || d.contains("{{") ? null : d);
                    params.add(pp);
                }
            }
            o.put("path", p.length() == 0 ? "/" : p.toString());
            o.put("tags", folders.isEmpty() ? List.of() : List.of(String.join(" / ", folders)));
            if (authId != null) o.put("security", authId.isEmpty() ? List.of() : List.of(authId));
            List<Object> queryItems = url != null ? list(url.get("query")) : parseQuery(q >= 0 ? raw.substring(q + 1) : "");
            for (Object qo : queryItems) {
                Map<String, Object> qm = map(qo);
                if (qm.get("key") == null) continue;
                String v = subst(str(qm.get("value")));
                Map<String, Object> pp = new LinkedHashMap<>();
                pp.put("name", str(qm.get("key"))); pp.put("in", "query"); pp.put("type", "string");
                pp.put("required", !Boolean.TRUE.equals(qm.get("disabled")));
                pp.put("default", v == null || v.isEmpty() || v.contains("{{") ? null : v);
                putIf(pp, "description", qm.get("description") instanceof Map<?, ?> dm ? str(dm.get("content")) : str(qm.get("description")));
                params.add(pp);
            }
            String contentTypeHeader = null;
            for (Object ho : list(r.get("header"))) {
                Map<String, Object> hm = map(ho);
                String k = str(hm.get("key"));
                if (k == null || Boolean.TRUE.equals(hm.get("disabled")) || k.equalsIgnoreCase("Authorization")) continue;
                String v = subst(str(hm.get("value")));
                if (k.equalsIgnoreCase("Content-Type")) { contentTypeHeader = v; continue; }
                Map<String, Object> pp = new LinkedHashMap<>();
                pp.put("name", k); pp.put("in", "header"); pp.put("type", "string");
                boolean fixed = v != null && !v.isEmpty() && !v.contains("{{");
                pp.put("required", !fixed);
                pp.put("default", fixed ? v : null);
                params.add(pp);
            }
            o.put("parameters", params);
            Map<String, Object> body = body(map(r.get("body")), contentTypeHeader);
            if (body != null) o.put("body", body);
            Map<String, Object> result = new LinkedHashMap<>();
            result.put("status", "200"); result.put("contentType", null); result.put("binary", false); result.put("extract", null);
            List<Object> responses = new ArrayList<>();
            for (Object ro : list(item.get("response"))) {
                Map<String, Object> rm = map(ro);
                Map<String, Object> x = new LinkedHashMap<>();
                x.put("status", rm.get("code") != null ? str(rm.get("code")) : "200");
                putIf(x, "description", str(rm.get("name")));
                String ct = null;
                for (Object ho : list(rm.get("header"))) if ("content-type".equalsIgnoreCase(str(map(ho).get("key")))) ct = str(map(ho).get("value"));
                putIf(x, "contentType", ct);
                x.put("binary", isBinaryType(ct));
                if (rm.get("body") instanceof String b && !b.isBlank()) { try { x.put("example", JsonLite.parse(b)); } catch (IllegalArgumentException e) { x.put("example", b.length() > 2000 ? b.substring(0, 2000) : b); } }
                responses.add(x);
                if (String.valueOf(x.get("status")).startsWith("2") && result.get("contentType") == null) {
                    result.put("status", x.get("status")); result.put("contentType", ct); result.put("binary", x.get("binary"));
                }
            }
            o.put("responses", responses);
            o.put("result", result);
            return o;
        }

        Map<String, Object> body(Map<String, Object> b, String contentType) {
            String mode = str(b.get("mode"));
            if (mode == null || Boolean.TRUE.equals(b.get("disabled"))) return null;
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("required", true);
            switch (mode) {
                case "raw" -> {
                    String raw = Objects.requireNonNullElse(str(b.get("raw")), "");
                    String lang = str(map(map(b.get("options")).get("raw")).get("language"));
                    String ct = contentType != null ? contentType : "json".equals(lang) ? "application/json" : "xml".equals(lang) ? "application/xml" : "text/plain";
                    Object example = null;
                    if (ct.contains("json")) { try { example = JsonLite.parse(raw); } catch (IllegalArgumentException e) { example = raw; } }
                    else example = raw;
                    out.put("contentType", ct);
                    out.put("schema", ct.contains("json") ? inferSchema(example) : Map.of("type", "string"));
                    if (!raw.isBlank()) out.put("example", example);
                }
                case "urlencoded", "formdata" -> {
                    Map<String, Object> props = new LinkedHashMap<>();
                    List<Object> files = new ArrayList<>();
                    Map<String, Object> example = new LinkedHashMap<>();
                    for (Object fo : list(b.get(mode))) {
                        Map<String, Object> f = map(fo);
                        String k = str(f.get("key"));
                        if (k == null || Boolean.TRUE.equals(f.get("disabled"))) continue;
                        boolean file = "file".equals(f.get("type"));
                        props.put(k, file ? new LinkedHashMap<>(Map.of("type", "string", "format", "binary")) : new LinkedHashMap<>(Map.of("type", "string")));
                        if (file) files.add(k);
                        else if (f.get("value") != null) example.put(k, subst(str(f.get("value"))));
                    }
                    out.put("contentType", mode.equals("formdata") ? "multipart/form-data" : "application/x-www-form-urlencoded");
                    out.put("schema", new LinkedHashMap<>(Map.of("type", "object", "properties", props)));
                    putIf(out, "fileFields", files);
                    if (!example.isEmpty()) out.put("example", example);
                }
                case "file" -> {
                    out.put("contentType", contentType != null ? contentType : "application/octet-stream");
                    out.put("schema", new LinkedHashMap<>(Map.of("type", "string", "format", "binary")));
                    out.put("binary", true);
                }
                case "graphql" -> {
                    Map<String, Object> g = map(b.get("graphql"));
                    out.put("contentType", "application/json");
                    out.put("schema", new LinkedHashMap<>(Map.of("type", "object", "properties", Map.of("query", Map.of("type", "string"), "variables", Map.of("type", "object")))));
                    Map<String, Object> ex = new LinkedHashMap<>();
                    ex.put("query", str(g.get("query")));
                    Object vars = g.get("variables");
                    if (vars instanceof String vs && !vs.isBlank()) { try { ex.put("variables", JsonLite.parse(vs)); } catch (IllegalArgumentException ignored) { } }
                    out.put("example", ex);
                }
                default -> { return null; }
            }
            return out;
        }

        static Map<String, Object> inferSchema(Object v) {
            Map<String, Object> s = new LinkedHashMap<>();
            if (v instanceof Map<?, ?> m) {
                s.put("type", "object");
                Map<String, Object> props = new LinkedHashMap<>();
                m.forEach((k, x) -> props.put(String.valueOf(k), inferSchema(x)));
                s.put("properties", props);
            } else if (v instanceof List<?> l) {
                s.put("type", "array");
                if (!l.isEmpty()) s.put("items", inferSchema(l.get(0)));
            } else s.put("type", v instanceof BigDecimal ? "number" : v instanceof Boolean ? "boolean" : "string");
            return s;
        }

        String rebuild(Map<String, Object> url) {
            StringBuilder sb = new StringBuilder();
            if (url.get("protocol") != null) sb.append(str(url.get("protocol"))).append("://");
            Object host = url.get("host");
            sb.append(host instanceof List<?> hl ? String.join(".", hl.stream().map(String::valueOf).toList()) : Objects.requireNonNullElse(str(host), ""));
            if (url.get("port") != null) sb.append(':').append(str(url.get("port")));
            Object path = url.get("path");
            if (path instanceof List<?> pl) for (Object s : pl) sb.append('/').append(s instanceof Map<?, ?> sm ? str(sm.get("value")) : String.valueOf(s));
            else if (path != null) sb.append('/').append(str(path).replaceAll("^/", ""));
            return sb.toString();
        }

        /** Splits a raw Postman URL into origin (with collection variables filled in) and path. */
        String[] splitOrigin(String raw) {
            String s = raw.trim();
            // A leading {{var}} is the base URL (often "{{baseUrl}}/users")
            java.util.regex.Matcher lead = java.util.regex.Pattern.compile("^\\{\\{([^}]+)}}").matcher(s);
            if (lead.find()) {
                String v = vars.get(lead.group(1));
                String rest = s.substring(lead.end());
                if (v != null && !v.contains("{{")) {
                    String base = v.endsWith("/") ? v.substring(0, v.length() - 1) : v;
                    if (!base.contains("://")) { String[] inner = splitOrigin(base + rest); return inner; }
                    String[] parts = splitOrigin(base);
                    return new String[]{parts[0], parts[1] + rest};
                }
                return new String[]{"", rest.isEmpty() ? "/" : rest};
            }
            s = subst(s);
            if (!s.contains("://")) {
                int slash = s.indexOf('/');
                if (slash > 0 && s.substring(0, slash).contains(".")) s = "https://" + s;
                else if (slash < 0 && s.contains(".")) s = "https://" + s;
                else return new String[]{"", s.startsWith("/") ? s : "/" + s};
            }
            int start = s.indexOf("://") + 3;
            int slash = s.indexOf('/', start);
            return slash < 0 ? new String[]{s, "/"} : new String[]{s.substring(0, slash), s.substring(slash)};
        }

        static List<Object> parseQuery(String q) {
            List<Object> out = new ArrayList<>();
            if (q.isEmpty()) return out;
            for (String kv : q.split("&")) {
                int i = kv.indexOf('=');
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("key", i < 0 ? kv : kv.substring(0, i));
                m.put("value", i < 0 ? "" : kv.substring(i + 1));
                out.add(m);
            }
            return out;
        }

        static String camel(String name) {
            String[] w = name.trim().replaceAll("[^A-Za-z0-9]+", " ").trim().split(" ");
            StringBuilder sb = new StringBuilder();
            for (String x : w) {
                if (x.isEmpty()) continue;
                sb.append(sb.length() == 0 ? x.substring(0, 1).toLowerCase(Locale.ROOT) : x.substring(0, 1).toUpperCase(Locale.ROOT)).append(x.substring(1));
            }
            return sb.length() == 0 ? "request" : sb.toString();
        }
    }

    /** Host of a URL for messages; the URL itself when it cannot be parsed. */
    static String host(String url) { try { return Objects.requireNonNullElse(URI.create(url).getHost(), url); } catch (Exception e) { return url; } }
}
