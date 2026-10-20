package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.CredentialVault;
import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.*;

/** Custom connectors: importers for OpenAPI 3, Swagger 2 and Postman, and calling operations against a local fake API. */
class CustomConnectorTest {

    // ---------- sample specs (written for these tests) ----------

    static final String OPENAPI = """
        {"openapi": "3.0.3",
         "info": {"title": "Orders Hub", "version": "2.1", "description": "Sample order service"},
         "servers": [{"url": "{scheme}://orders.example.test/{ver}", "variables": {"scheme": {"default": "https"}, "ver": {"default": "v2"}}}],
         "security": [{"keyAuth": []}],
         "components": {
           "securitySchemes": {
             "keyAuth": {"type": "apiKey", "in": "header", "name": "X-Api-Key"},
             "basicAuth": {"type": "http", "scheme": "basic"},
             "tokenAuth": {"type": "http", "scheme": "bearer", "bearerFormat": "JWT"},
             "machine": {"type": "oauth2", "flows": {"clientCredentials": {"tokenUrl": "https://login.example.test/token", "scopes": {"orders.read": "read", "orders.write": "write"}}}},
             "gateway": {"type": "apiKey", "in": "header", "name": "Authorization", "x-amazon-apigateway-authtype": "awsSigv4"}
           },
           "parameters": {"OrderId": {"name": "orderId", "in": "path", "required": true, "schema": {"type": "string"}}},
           "requestBodies": {"OrderBody": {"required": true, "content": {"application/json": {"schema": {"$ref": "#/components/schemas/Order"}}}}},
           "schemas": {
             "Order": {"type": "object", "required": ["customer"], "properties": {"customer": {"type": "string"}, "lines": {"type": "array", "items": {"$ref": "#/components/schemas/Line"}}, "parent": {"$ref": "#/components/schemas/Order"}}},
             "Line": {"type": "object", "properties": {"sku": {"type": "string"}, "qty": {"type": "integer"}}}
           }
         },
         "paths": {
           "/orders": {
             "get": {"operationId": "listOrders", "summary": "List orders", "tags": ["orders"],
                     "parameters": [{"name": "status", "in": "query", "schema": {"type": "string", "enum": ["open", "closed"], "default": "open"}},
                                    {"name": "limit", "in": "query", "required": true, "schema": {"type": "integer", "default": 20}}],
                     "responses": {"200": {"description": "ok", "content": {"application/json": {"schema": {"type": "array", "items": {"$ref": "#/components/schemas/Order"}}}}}}},
             "post": {"operationId": "createOrder", "requestBody": {"$ref": "#/components/requestBodies/OrderBody"},
                      "responses": {"201": {"description": "created"}}}
           },
           "/orders/{orderId}": {
             "parameters": [{"$ref": "#/components/parameters/OrderId"}],
             "get": {"operationId": "getOrder", "security": [{"machine": ["orders.read"]}, {"tokenAuth": []}], "responses": {"200": {"description": "ok"}}},
             "delete": {"security": [], "responses": {"204": {"description": "gone"}}}
           },
           "/orders/{orderId}/invoice": {
             "parameters": [{"$ref": "#/components/parameters/OrderId"}],
             "get": {"operationId": "downloadInvoice", "responses": {"200": {"description": "pdf", "content": {"application/pdf": {"schema": {"type": "string", "format": "binary"}}}}}}
           },
           "/attachments": {
             "post": {"operationId": "uploadAttachment",
                      "requestBody": {"content": {"multipart/form-data": {"schema": {"type": "object", "properties": {"note": {"type": "string"}, "file": {"type": "string", "format": "binary"}}}}}},
                      "responses": {"200": {"description": "ok"}}}
           }
         }}
        """;

    static final String SWAGGER = """
        {"swagger": "2.0", "info": {"title": "Legacy Stock", "version": "1"},
         "host": "stock.example.test", "basePath": "/api", "schemes": ["http", "https"],
         "consumes": ["application/json"], "produces": ["application/json"],
         "securityDefinitions": {"key": {"type": "apiKey", "in": "query", "name": "api_key"},
                                 "app": {"type": "oauth2", "flow": "application", "tokenUrl": "https://auth.example.test/oauth/token", "scopes": {"stock": "all"}},
                                 "basic": {"type": "basic"}},
         "security": [{"key": []}],
         "parameters": {"Sku": {"name": "sku", "in": "path", "required": true, "type": "string"}},
         "definitions": {"Item": {"type": "object", "properties": {"sku": {"type": "string"}, "count": {"type": "integer"}}}},
         "paths": {
           "/items/{sku}": {
             "get": {"operationId": "getItem", "parameters": [{"$ref": "#/parameters/Sku"}, {"name": "tags", "in": "query", "type": "array", "items": {"type": "string"}, "collectionFormat": "csv"}],
                     "responses": {"200": {"description": "ok", "schema": {"$ref": "#/definitions/Item"}}}},
             "put": {"operationId": "putItem", "parameters": [{"$ref": "#/parameters/Sku"}, {"name": "item", "in": "body", "required": true, "schema": {"$ref": "#/definitions/Item"}}],
                     "responses": {"200": {"description": "ok"}}}
           },
           "/items/{sku}/photo": {
             "post": {"operationId": "uploadPhoto", "consumes": ["multipart/form-data"],
                      "parameters": [{"$ref": "#/parameters/Sku"}, {"name": "photo", "in": "formData", "type": "file", "required": true}, {"name": "caption", "in": "formData", "type": "string"}],
                      "responses": {"200": {"description": "ok"}}}
           }
         }}
        """;

    static final String POSTMAN = """
        {"info": {"_postman_id": "1", "name": "Help Desk API", "schema": "https://schema.getpostman.com/json/collection/v2.1.0/collection.json"},
         "variable": [{"key": "baseUrl", "value": "https://desk.example.test/api/v1"}],
         "auth": {"type": "apikey", "apikey": [{"key": "key", "value": "X-Desk-Key"}, {"key": "value", "value": "{{deskKey}}"}, {"key": "in", "value": "header"}]},
         "item": [
           {"name": "Tickets", "item": [
             {"name": "Get ticket", "request": {"method": "GET",
               "url": {"raw": "{{baseUrl}}/tickets/:ticketId?expand=comments", "host": ["{{baseUrl}}"], "path": ["tickets", ":ticketId"],
                       "query": [{"key": "expand", "value": "comments"}], "variable": [{"key": "ticketId", "value": ""}]},
               "header": [{"key": "X-Trace", "value": "{{trace}}"}]}},
             {"name": "Create ticket", "request": {"method": "POST", "url": "{{baseUrl}}/tickets",
               "header": [{"key": "Content-Type", "value": "application/json"}],
               "body": {"mode": "raw", "raw": "{\\"title\\": \\"Printer\\", \\"priority\\": 2}", "options": {"raw": {"language": "json"}}}},
              "response": [{"name": "Created", "code": 201, "header": [{"key": "Content-Type", "value": "application/json"}], "body": "{\\"id\\": 7}"}]}
           ]},
           {"name": "Health", "request": {"method": "GET", "url": "https://status.example.test/ping", "auth": {"type": "noauth"}}},
           {"name": "Upload", "request": {"method": "POST", "url": "{{baseUrl}}/files",
             "auth": {"type": "oauth2", "oauth2": [{"key": "grant_type", "value": "client_credentials"}, {"key": "accessTokenUrl", "value": "https://desk.example.test/oauth/token"}, {"key": "scope", "value": "files"}]},
             "body": {"mode": "formdata", "formdata": [{"key": "file", "type": "file", "src": "a.txt"}, {"key": "folder", "value": "inbox", "type": "text"}]}}}
         ]}
        """;

    // ---------- importers ----------

    @Test void openApi3ResolvesRefsServersSecurityAndBodies() {
        Map<String, Object> d = ConnectorImporter.importSpec(OPENAPI, null, null);
        assertEquals(ConnectorImporter.FORMAT, d.get("format"));
        assertEquals("orders_hub", d.get("name"));
        assertEquals("https://orders.example.test/v2", d.get("server"), "server variables use their defaults");
        assertEquals(List.of("keyAuth"), d.get("security"));
        Map<String, Map<String, Object>> auth = byId(d.get("auth"));
        assertEquals("api_key", auth.get("keyAuth").get("type"));
        assertEquals("X-Api-Key", auth.get("keyAuth").get("name"));
        assertEquals("basic", auth.get("basicAuth").get("type"));
        assertEquals("bearer", auth.get("tokenAuth").get("type"));
        assertEquals("oauth2_client_credentials", auth.get("machine").get("type"));
        assertEquals("https://login.example.test/token", auth.get("machine").get("tokenUrl"));
        assertEquals(List.of("orders.read", "orders.write"), auth.get("machine").get("scopes"));
        assertEquals("aws_sigv4", auth.get("gateway").get("type"));

        Map<String, Map<String, Object>> ops = byId(d.get("operations"));
        assertEquals(Set.of("listOrders", "createOrder", "getOrder", "delete_orders_orderId", "downloadInvoice", "uploadAttachment"), ops.keySet());
        Map<String, Object> list = ops.get("listOrders");
        assertEquals("List orders", list.get("name"));
        Map<String, Map<String, Object>> lp = byName(list.get("parameters"));
        assertEquals("query", lp.get("status").get("in"));
        assertEquals(false, lp.get("status").get("required"));
        assertEquals("open", lp.get("status").get("default"));
        assertEquals(List.of("open", "closed"), lp.get("status").get("enum"));
        assertEquals("integer", lp.get("limit").get("type"));
        assertEquals(0, new BigDecimal(20).compareTo((BigDecimal) lp.get("limit").get("default")));

        Map<String, Object> get = ops.get("getOrder");
        assertEquals("path", byName(get.get("parameters")).get("orderId").get("in"), "path-level $ref parameter is inherited");
        assertEquals(List.of("machine", "tokenAuth"), get.get("security"));
        assertEquals(List.of(), ops.get("delete_orders_orderId").get("security"), "security: [] means no auth");
        assertEquals("DELETE", ops.get("delete_orders_orderId").get("method"));

        Map<String, Object> body = map(ops.get("createOrder").get("body"));
        assertEquals("application/json", body.get("contentType"));
        assertEquals(true, body.get("required"));
        Map<String, Object> schema = map(body.get("schema"));
        assertEquals(List.of("customer"), schema.get("required"));
        Map<String, Object> props = map(schema.get("properties"));
        assertEquals("integer", map(map(map(map(props.get("lines")).get("items")).get("properties")).get("qty")).get("type"), "nested $ref resolved");
        assertEquals(true, map(props.get("parent")).get("x-recursive"), "a schema that contains itself is cut");

        Map<String, Object> up = map(ops.get("uploadAttachment").get("body"));
        assertEquals("multipart/form-data", up.get("contentType"));
        assertEquals(List.of("file"), up.get("fileFields"));
        assertEquals(true, map(ops.get("downloadInvoice").get("result")).get("binary"));
        assertEquals("201", map(ops.get("createOrder").get("result")).get("status"));
    }

    @Test void swagger2BuildsServerBodyAndFormData() {
        Map<String, Object> d = ConnectorImporter.importSpec(SWAGGER, "stock", null);
        assertEquals("stock", d.get("name"));
        assertEquals("https://stock.example.test/api", d.get("server"), "https is preferred");
        assertEquals(2, ((List<?>) d.get("servers")).size());
        Map<String, Map<String, Object>> auth = byId(d.get("auth"));
        assertEquals("query", auth.get("key").get("in"));
        assertEquals("api_key", auth.get("key").get("name"));
        assertEquals("oauth2_client_credentials", auth.get("app").get("type"));
        assertEquals("basic", auth.get("basic").get("type"));
        Map<String, Map<String, Object>> ops = byId(d.get("operations"));
        Map<String, Map<String, Object>> gp = byName(ops.get("getItem").get("parameters"));
        assertEquals(true, gp.get("sku").get("required"));
        assertEquals("array", gp.get("tags").get("type"));
        assertEquals("csv", gp.get("tags").get("collectionFormat"));
        Map<String, Object> put = map(ops.get("putItem").get("body"));
        assertEquals("application/json", put.get("contentType"));
        assertEquals("object", map(put.get("schema")).get("type"));
        assertNotNull(map(map(put.get("schema")).get("properties")).get("count"));
        assertEquals(1, ((List<?>) ops.get("putItem").get("parameters")).size(), "body parameter is not a URL parameter");
        Map<String, Object> photo = map(ops.get("uploadPhoto").get("body"));
        assertEquals("multipart/form-data", photo.get("contentType"));
        assertEquals(List.of("photo"), photo.get("fileFields"));
        assertEquals(List.of("photo"), map(photo.get("schema")).get("required"));
        assertEquals("object", map(map(((List<?>) ops.get("getItem").get("responses")).get(0)).get("schema")).get("type"), "response $ref resolved");
    }

    @Test void postmanCollectionBecomesOperations() {
        Map<String, Object> d = ConnectorImporter.importSpec(POSTMAN, null, null);
        assertEquals("help_desk_api", d.get("name"));
        assertEquals("https://desk.example.test", d.get("server"), "the most used origin becomes the server");
        Map<String, Map<String, Object>> ops = byId(d.get("operations"));
        assertEquals(Set.of("getTicket", "createTicket", "health", "upload"), ops.keySet());
        Map<String, Object> get = ops.get("getTicket");
        assertEquals("/api/v1/tickets/{ticketId}", get.get("path"));
        assertEquals(List.of("Tickets"), get.get("tags"));
        Map<String, Map<String, Object>> gp = byName(get.get("parameters"));
        assertEquals("path", gp.get("ticketId").get("in"));
        assertEquals(true, gp.get("ticketId").get("required"));
        assertEquals("comments", gp.get("expand").get("default"));
        assertEquals("header", gp.get("X-Trace").get("in"));
        assertNull(gp.get("X-Trace").get("default"), "{{variables}} are not used as defaults");

        Map<String, Map<String, Object>> auth = byId(d.get("auth"));
        String keyId = String.valueOf(((List<?>) d.get("security")).get(0));
        assertEquals("api_key", auth.get(keyId).get("type"));
        assertEquals("X-Desk-Key", auth.get(keyId).get("name"));
        assertEquals(List.of(), ops.get("health").get("security"), "noauth on a request");
        String oauthId = String.valueOf(((List<?>) ops.get("upload").get("security")).get(0));
        assertEquals("oauth2_client_credentials", auth.get(oauthId).get("type"));
        assertEquals(List.of("files"), auth.get(oauthId).get("scopes"));

        Map<String, Object> create = map(ops.get("createTicket").get("body"));
        assertEquals("application/json", create.get("contentType"));
        assertEquals("Printer", map(create.get("example")).get("title"));
        assertEquals("number", map(map(map(create.get("schema")).get("properties")).get("priority")).get("type"));
        assertEquals("201", map(ops.get("createTicket").get("result")).get("status"));
        Map<String, Object> up = map(ops.get("upload").get("body"));
        assertEquals("multipart/form-data", up.get("contentType"));
        assertEquals(List.of("file"), up.get("fileFields"));
    }

    @Test void importErrorsAreClear() {
        ActionException yaml = assertThrows(ActionException.class, () -> ConnectorImporter.importSpec("openapi: 3.0.0\ninfo:\n  title: x\n", null, null));
        assertTrue(Set.of("CONNECTOR.YAML_UNSUPPORTED", "CONNECTOR.NO_OPERATIONS").contains(yaml.code()), yaml.code());
        assertEquals("CONNECTOR.UNKNOWN_FORMAT", assertThrows(ActionException.class, () -> ConnectorImporter.importSpec("{\"hello\": 1}", null, null)).code());
        assertEquals("CONNECTOR.UNSUPPORTED_VERSION", assertThrows(ActionException.class, () -> ConnectorImporter.importSpec("{\"swagger\": \"1.2\"}", null, null)).code());
        assertEquals("CONNECTOR.BAD_REF", assertThrows(ActionException.class, () -> ConnectorImporter.importSpec(
                "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"t\"},\"paths\":{\"/a\":{\"get\":{\"parameters\":[{\"$ref\":\"#/components/parameters/Nope\"}],\"responses\":{}}}}}", null, null)).code());
        assertEquals("CONNECTOR.BAD_NAME", assertThrows(ActionException.class, () -> ConnectorImporter.importSpec(OPENAPI, "../evil", null)).code());
        assertEquals("CONNECTOR.BAD_JSON", assertThrows(ActionException.class, () -> ConnectorImporter.importSpec("{\"openapi\": ", null, null)).code());
    }

    @Test void storeRoundTripListAndDelete() throws Exception {
        Path w = Files.createTempDirectory("ooa-conn");
        Map<String, Object> d = ConnectorImporter.importSpec(SWAGGER, "stock", null);
        assertEquals("connectors/stock.connector.json", ConnectorStore.save(w, d, false));
        assertEquals("CONNECTOR.EXISTS", assertThrows(ActionException.class, () -> ConnectorStore.save(w, d, false)).code());
        Map<String, Object> back = ConnectorStore.load(w, "stock");
        assertEquals(d.get("operations"), back.get("operations"));
        List<Map<String, Object>> all = ConnectorStore.list(w);
        assertEquals(1, all.size());
        assertEquals("Legacy Stock", all.get(0).get("title"));
        assertTrue(ConnectorStore.delete(w, "stock"));
        assertEquals("CONNECTOR.NOT_FOUND", assertThrows(ActionException.class, () -> ConnectorStore.load(w, "stock")).code());
    }

    // ---------- runtime against a fake API ----------

    record Req(String method, String path, String rawQuery, Map<String, String> headers, byte[] body) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
        String query() { return rawQuery == null ? "" : URLDecoder.decode(rawQuery, StandardCharsets.UTF_8); }
        String header(String n) { return headers.get(n.toLowerCase(Locale.ROOT)); }
    }

    static final class Api implements AutoCloseable {
        final HttpServer server;
        final List<Req> calls = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Function<Req, Object[]>> routes = new LinkedHashMap<>();
        Api() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        Api on(String key, Function<Req, Object[]> h) { routes.put(key, h); return this; }
        void handle(HttpExchange ex) throws java.io.IOException {
            Map<String, String> hs = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> hs.put(k.toLowerCase(Locale.ROOT), String.join(",", v)));
            Req r = new Req(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(), hs, ex.getRequestBody().readAllBytes());
            calls.add(r);
            Object[] out = {404, "{\"message\":\"no route " + r.method() + " " + r.path() + "\"}", "application/json"};
            for (var e : routes.entrySet()) {
                String[] k = e.getKey().split(" ", 2);
                if (k[0].equals(r.method()) && r.path().matches(k[1])) { out = e.getValue().apply(r); break; }
            }
            byte[] b = out[1] instanceof byte[] bytes ? bytes : String.valueOf(out[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().set("Content-Type", out.length > 2 ? String.valueOf(out[2]) : "application/json");
            ex.sendResponseHeaders((Integer) out[0], b.length == 0 ? -1 : b.length);
            if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        }
        long count(String method, String pathRegex) { synchronized (calls) { return calls.stream().filter(c -> c.method().equals(method) && c.path().matches(pathRegex)).count(); } }
        Req last(String method, String pathRegex) {
            synchronized (calls) { for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).method().equals(method) && calls.get(i).path().matches(pathRegex)) return calls.get(i); }
            return null;
        }
        @Override public void close() { server.stop(0); }
    }

    static final ConnectorPackage P = new ConnectorPackage();
    Path work;
    ActionContext ctx;

    @BeforeEach void setUp() throws Exception {
        work = Files.createTempDirectory("ooa-conn");
        ctx = PackagesTestSupport.ctx(work, Map.of());
        ConnectorAuth.TOKENS.clear();
    }

    static SecureString secret(String s) { return new SecureString(s, "test/ref"); }

    Map<String, Object> call(String op, Map<String, Object> params, Object body, Object cred, Map<String, Object> auth, String outFile) {
        return P.callOperation(ctx, "orders", op, params, body, cred, auth, outFile, null, null, 30, true);
    }

    void importOrders(String base) {
        P.importConnector(ctx, null, OPENAPI, "orders", base, true);
    }

    @Test void callsWithApiKeyHeaderEncodingDefaultsAndJsonBody() throws Exception {
        try (Api api = new Api()) {
            api.on("GET /v2/orders", r -> new Object[]{200, "[{\"customer\":\"Acme\"}]"})
               .on("POST /v2/orders", r -> new Object[]{201, "{\"id\":\"o-1\"}"})
               .on("GET /v2/orders/.*", r -> new Object[]{200, "{\"id\":\"x\"}"});
            importOrders(api.url() + "/v2");
            assertTrue(Files.isRegularFile(work.resolve("connectors/orders.connector.json")));

            Map<String, Object> o = call("listOrders", Map.of("status", "closed & paid"), null, secret("k-123"), null, null);
            eq(200, o.get("status"));
            assertEquals("Acme", map(((List<?>) o.get("body")).get(0)).get("customer"));
            Req get = api.last("GET", "/v2/orders");
            assertEquals("k-123", get.header("X-Api-Key"));
            assertEquals("status=closed%20%26%20paid&limit=20", get.rawQuery(), "values are URL-encoded; the required default is filled in");

            Map<String, Object> c = call("createOrder", null, Map.of("customer", "Acme", "lines", List.of(Map.of("sku", "A1", "qty", BigDecimal.ONE))), secret("k-123"), null, null);
            eq(201, c.get("status"));
            assertEquals("o-1", map(c.get("body")).get("id"));
            Req post = api.last("POST", "/v2/orders");
            assertEquals("application/json", post.header("Content-Type"));
            assertEquals("Acme", map(JsonLite.parse(post.text())).get("customer"));

            call("getOrder", Map.of("orderId", "a/b c?"), null, secret("tok-1"), Map.of("scheme", "tokenAuth"), null);
            Req g = api.last("GET", "/v2/orders/.*");
            assertEquals("/v2/orders/a%2Fb%20c%3F", g.path(), "path values are encoded as one segment");
            assertEquals("Bearer tok-1", g.header("Authorization"));
        }
    }

    @Test void validatesParametersAndBody() throws Exception {
        try (Api api = new Api()) {
            importOrders(api.url() + "/v2");
            ActionException e = assertThrows(ActionException.class, () -> call("getOrder", Map.of(), null, secret("t"), Map.of("scheme", "tokenAuth"), null));
            assertEquals("CONNECTOR.MISSING_PARAMETER", e.code());
            assertTrue(e.getMessage().contains("orderId"));
            assertEquals("CONNECTOR.UNKNOWN_PARAMETER", assertThrows(ActionException.class, () -> call("listOrders", Map.of("colour", "red"), null, secret("k"), null, null)).code());
            assertEquals("CONNECTOR.MISSING_BODY", assertThrows(ActionException.class, () -> call("createOrder", null, null, secret("k"), null, null)).code());
            assertEquals("CONNECTOR.MISSING_BODY_FIELD", assertThrows(ActionException.class, () -> call("createOrder", null, Map.of("lines", List.of()), secret("k"), null, null)).code());
            assertEquals("CONNECTOR.UNKNOWN_OPERATION", assertThrows(ActionException.class, () -> call("nope", null, null, null, null, null)).code());
            assertEquals("CONNECTOR.NO_CREDENTIAL", assertThrows(ActionException.class, () -> call("listOrders", null, null, null, null, null)).code());
            assertEquals("CONNECTOR.NOT_FOUND", assertThrows(ActionException.class,
                    () -> P.callOperation(ctx, "missing", "x", null, null, null, null, null, null, null, 30, true)).code());
            assertEquals(0, api.calls.size(), "nothing is sent when validation fails");
        }
    }

    @Test void httpErrorsMapToCodesAndRetryFlags() throws Exception {
        try (Api api = new Api()) {
            api.on("GET /v2/orders/missing", r -> new Object[]{404, "{\"message\":\"Order not found\"}"})
               .on("GET /v2/orders/busy", r -> new Object[]{429, "{\"message\":\"slow down\"}"})
               .on("GET /v2/orders/down", r -> new Object[]{503, "unavailable", "text/plain"})
               .on("GET /v2/orders/soft", r -> new Object[]{409, "{\"message\":\"conflict\"}"});
            importOrders(api.url() + "/v2");
            Map<String, Object> bearer = Map.of("scheme", "tokenAuth");
            ActionException nf = assertThrows(ActionException.class, () -> call("getOrder", Map.of("orderId", "missing"), null, secret("t"), bearer, null));
            assertEquals("CONNECTOR.HTTP_404", nf.code());
            assertFalse(nf.retryable());
            assertTrue(nf.getMessage().contains("Order not found"), nf.getMessage());
            ActionException busy = assertThrows(ActionException.class, () -> call("getOrder", Map.of("orderId", "busy"), null, secret("t"), bearer, null));
            assertEquals("CONNECTOR.HTTP_429", busy.code());
            assertTrue(busy.retryable());
            ActionException down = assertThrows(ActionException.class, () -> call("getOrder", Map.of("orderId", "down"), null, secret("t"), bearer, null));
            assertEquals("CONNECTOR.HTTP_503", down.code());
            assertTrue(down.retryable());
            Map<String, Object> soft = P.callOperation(ctx, "orders", "getOrder", Map.of("orderId", "soft"), null, secret("t"), bearer, null, null, null, 30, false);
            eq(409, soft.get("status"));
            assertEquals("conflict", map(soft.get("body")).get("message"), "fail_on_error=false returns the response");
        }
    }

    @Test void streamsBinaryDownloadAndUploadsMultipartFile() throws Exception {
        try (Api api = new Api()) {
            byte[] pdf = "%PDF-1.7 fake invoice".getBytes(StandardCharsets.UTF_8);
            api.on("GET /v2/orders/o1/invoice", r -> new Object[]{200, pdf, "application/pdf"})
               .on("POST /v2/attachments", r -> new Object[]{200, "{\"ok\":true}"});
            importOrders(api.url() + "/v2");
            Map<String, Object> d = call("downloadInvoice", Map.of("orderId", "o1"), null, secret("k"), null, "out/inv.pdf");
            assertArrayEquals(pdf, Files.readAllBytes(work.resolve("out/inv.pdf")));
            assertEquals(work.resolve("out/inv.pdf").toString(), d.get("file"));
            assertNull(d.get("body"));

            Files.writeString(work.resolve("note.txt"), "hello file");
            call("uploadAttachment", null, Map.of("note", "see attached", "file", "note.txt"), secret("k"), null, null);
            Req up = api.last("POST", "/v2/attachments");
            assertTrue(up.header("Content-Type").startsWith("multipart/form-data; boundary="));
            assertTrue(up.text().contains("name=\"file\"; filename=\"note.txt\""), up.text());
            assertTrue(up.text().contains("hello file") && up.text().contains("see attached"));
        }
    }

    @Test void oauth2ClientCredentialsCachesRenewsAndRetriesOn401() throws Exception {
        try (Api api = new Api()) {
            AtomicInteger issued = new AtomicInteger();
            AtomicInteger expiresIn = new AtomicInteger(3600);
            Set<String> revoked = Collections.synchronizedSet(new HashSet<>());
            api.on("POST /token", r -> new Object[]{200, "{\"access_token\":\"at-" + issued.incrementAndGet() + "\",\"token_type\":\"bearer\",\"expires_in\":" + expiresIn.get() + "}"})
               .on("GET /v2/orders/.*", r -> revoked.contains(r.header("Authorization")) ? new Object[]{401, "{\"message\":\"expired\"}"} : new Object[]{200, "{\"id\":\"o\"}"});
            importOrders(api.url() + "/v2");
            // Point the scheme's token URL at the fake server
            Map<String, Object> def = ConnectorStore.load(work, "orders");
            for (Object a : (List<?>) def.get("auth")) if ("machine".equals(map(a).get("id"))) map(a).put("tokenUrl", api.url() + "/token");
            ConnectorStore.save(work, def, true);
            Map<String, Object> auth = Map.of("client_id", "app-1");

            call("getOrder", Map.of("orderId", "1"), null, secret("s3cret"), auth, null);
            call("getOrder", Map.of("orderId", "2"), null, secret("s3cret"), auth, null);
            assertEquals(1, issued.get(), "the token is cached between calls");
            Req tok = api.last("POST", "/token");
            assertEquals("Basic " + Base64.getEncoder().encodeToString("app-1:s3cret".getBytes(StandardCharsets.UTF_8)), tok.header("Authorization"));
            assertTrue(tok.query().isEmpty());
            String form = URLDecoder.decode(tok.text(), StandardCharsets.UTF_8);
            assertTrue(form.contains("grant_type=client_credentials") && form.contains("scope=orders.read orders.write"), form);
            assertEquals("Bearer at-1", api.last("GET", "/v2/orders/2").header("Authorization"));

            // The API rejects the cached token: renew once and retry
            revoked.add("Bearer at-1");
            Map<String, Object> o = call("getOrder", Map.of("orderId", "3"), null, secret("s3cret"), auth, null);
            eq(200, o.get("status"));
            assertEquals(2, issued.get());
            assertEquals("Bearer at-2", api.last("GET", "/v2/orders/3").header("Authorization"));

            // A short-lived token is renewed before it runs out
            ConnectorAuth.TOKENS.clear();
            expiresIn.set(30);
            call("getOrder", Map.of("orderId", "4"), null, secret("s3cret"), auth, null);
            call("getOrder", Map.of("orderId", "5"), null, secret("s3cret"), auth, null);
            assertEquals(4, issued.get(), "a token within the renewal margin is not reused");

            // client secret posted in the body, settings given as a JSON credential
            ConnectorAuth.TOKENS.clear();
            expiresIn.set(3600);
            call("getOrder", Map.of("orderId", "6"), null, secret("{\"client_id\":\"app-2\",\"client_secret\":\"other\",\"client_auth\":\"body\"}"), Map.of(), null);
            Req tok2 = api.last("POST", "/token");
            assertNull(tok2.header("Authorization"));
            assertTrue(tok2.text().contains("client_id=app-2") && tok2.text().contains("client_secret=other"));
        }
    }

    @Test void oauth2UsesRefreshTokenWhenIssued() throws Exception {
        try (Api api = new Api()) {
            AtomicInteger issued = new AtomicInteger();
            api.on("POST /token", r -> new Object[]{200, "{\"access_token\":\"at-" + issued.incrementAndGet() + "\",\"refresh_token\":\"rt-1\",\"expires_in\":10}"})
               .on("GET /v2/orders/.*", r -> new Object[]{200, "{}"});
            importOrders(api.url() + "/v2");
            Map<String, Object> def = ConnectorStore.load(work, "orders");
            for (Object a : (List<?>) def.get("auth")) if ("machine".equals(map(a).get("id"))) map(a).put("tokenUrl", api.url() + "/token");
            ConnectorStore.save(work, def, true);
            call("getOrder", Map.of("orderId", "1"), null, secret("s"), Map.of("client_id", "c", "scope", "orders.read"), null);
            call("getOrder", Map.of("orderId", "2"), null, secret("s"), Map.of("client_id", "c", "scope", "orders.read"), null);
            String form = URLDecoder.decode(api.last("POST", "/token").text(), StandardCharsets.UTF_8);
            assertTrue(form.contains("grant_type=refresh_token") && form.contains("refresh_token=rt-1") && form.contains("scope=orders.read"), form);
            assertEquals("Bearer at-2", api.last("GET", "/v2/orders/2").header("Authorization"));
        }
    }

    @Test void oauth2TokenFailureIsReported() throws Exception {
        try (Api api = new Api()) {
            api.on("POST /token", r -> new Object[]{401, "{\"error\":\"invalid_client\",\"error_description\":\"bad secret\"}"});
            importOrders(api.url() + "/v2");
            Map<String, Object> def = ConnectorStore.load(work, "orders");
            for (Object a : (List<?>) def.get("auth")) if ("machine".equals(map(a).get("id"))) map(a).put("tokenUrl", api.url() + "/token");
            ConnectorStore.save(work, def, true);
            ActionException e = assertThrows(ActionException.class, () -> call("getOrder", Map.of("orderId", "1"), null, secret("x"), Map.of("client_id", "c"), null));
            assertEquals("CONNECTOR.OAUTH_FAILED", e.code());
            assertTrue(e.getMessage().contains("bad secret"));
        }
    }

    @Test void apiKeyInQueryAndBasicAuth() throws Exception {
        try (Api api = new Api()) {
            api.on("GET /api/items/.*", r -> new Object[]{200, "{\"sku\":\"A\",\"count\":3}"})
               .on("PUT /api/items/.*", r -> new Object[]{200, "{}"});
            P.importConnector(ctx, null, SWAGGER, "stock", api.url() + "/api", true);
            Map<String, Object> o = P.callOperation(ctx, "stock", "getItem", Map.of("sku", "A", "tags", List.of("x", "y z")), null, secret("qk&1"), null, null, null, null, 30, true);
            eq(3, map(o.get("body")).get("count"));
            Req r = api.last("GET", "/api/items/A");
            assertEquals("tags=x%2Cy%20z&api_key=qk%261", r.rawQuery(), "csv array and API key in the query, both encoded");
            assertNull(r.header("Authorization"));

            P.callOperation(ctx, "stock", "putItem", Map.of("sku", "B"), Map.of("sku", "B", "count", BigDecimal.TEN), secret("pw:1"),
                    Map.of("scheme", "basic", "username", "ann"), null, null, null, 30, true);
            Req put = api.last("PUT", "/api/items/B");
            assertEquals("Basic " + Base64.getEncoder().encodeToString("ann:pw:1".getBytes(StandardCharsets.UTF_8)), put.header("Authorization"));
            assertEquals("", put.rawQuery() == null ? "" : put.rawQuery(), "no API key when another scheme is chosen");
        }
    }

    @Test void awsSigV4MatchesReferenceSignature() {
        // Reference request "get-vanilla" from the public AWS Signature Version 4 test suite.
        Map<String, String> h = new LinkedHashMap<>();
        ConnectorAuth.signV4("GET", "https://example.amazonaws.com/", h, new byte[0], "AKIDEXAMPLE", "wJalrXUtnFEMI/K7MDENG+bPxRfiCYEXAMPLEKEY", null,
                "us-east-1", "service", ZonedDateTime.of(2015, 8, 30, 12, 36, 0, 0, ZoneOffset.UTC));
        assertEquals("20150830T123600Z", h.get("X-Amz-Date"));
        assertEquals("AWS4-HMAC-SHA256 Credential=AKIDEXAMPLE/20150830/us-east-1/service/aws4_request, SignedHeaders=host;x-amz-date, "
                + "Signature=5fa00fa31553b73ebf1942676e86291e8372ff2a2260956d9b8aae1d763fbf31", h.get("Authorization"));
    }

    @Test void awsSigV4IsAppliedToCalls() throws Exception {
        try (Api api = new Api()) {
            api.on("GET /v2/orders/.*", r -> new Object[]{200, "{}"});
            importOrders(api.url() + "/v2");
            call("getOrder", Map.of("orderId", "9"), null, secret("SECRETKEY"),
                    Map.of("scheme", "gateway", "access_key_id", "AKIDTEST", "region", "eu-west-1", "session_token", "sess"), null);
            Req r = api.last("GET", "/v2/orders/9");
            assertTrue(r.header("Authorization").startsWith("AWS4-HMAC-SHA256 Credential=AKIDTEST/"), r.header("Authorization"));
            assertTrue(r.header("Authorization").contains("/eu-west-1/execute-api/aws4_request"));
            assertTrue(r.header("Authorization").contains("SignedHeaders=host;x-amz-date;x-amz-security-token"), r.header("Authorization"));
            assertEquals("sess", r.header("X-Amz-Security-Token"));
        }
    }

    @Test void listAndDescribeOperations() {
        importOrders("https://orders.example.test/v2");
        Map<String, Object> l = P.listOperations(ctx, "orders", null);
        eq(6, l.get("count"));
        Map<String, Object> s = P.listOperations(ctx, "orders", "invoice");
        eq(1, s.get("count"));
        Map<String, Object> d = P.describeOperation(ctx, "orders", "listOrders");
        assertEquals("GET", map(d.get("operation")).get("method"));
        assertEquals("open", map(d.get("example_parameters")).get("status"));
        assertEquals("api_key", map(((List<?>) d.get("auth")).get(0)).get("type"));
        assertEquals(1, ((List<?>) P.listConnectors(ctx).get("connectors")).size());
    }

    @Test void agentImportsAndCallsThroughTheRunner() throws Exception {
        try (Api api = new Api()) {
            api.on("GET /v2/orders", r -> new Object[]{200, "[{\"customer\":\"Acme\"},{\"customer\":\"Globex\"}]"});
            Path w = Files.createTempDirectory("ooa-conn-run");
            Files.writeString(w.resolve("orders.json"), OPENAPI);
            @SuppressWarnings("unchecked") Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("{\"kind\":\"TaskBot\",\"id\":\"t\",\"name\":\"t\",\"variables\":["
                    + "{\"name\":\"key\",\"type\":\"Credential\",\"direction\":\"input\",\"required\":true},"
                    + "{\"name\":\"n\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"st\",\"type\":\"Number\",\"direction\":\"output\"},{\"name\":\"rows\",\"type\":\"Any\",\"direction\":\"output\"}],"
                    + "\"steps\":[{\"id\":\"i\",\"action\":\"ooa.connector/import_connector\",\"args\":{\"spec_file\":\"orders.json\",\"name\":\"orders\",\"server\":\"" + api.url() + "/v2\"},\"out\":{\"operations\":\"n\"}},"
                    + "{\"id\":\"c\",\"action\":\"ooa.connector/call_operation\",\"args\":{\"connector\":\"orders\",\"operation\":\"listOrders\",\"parameters\":{\"limit\":5},\"credential\":\"$key\"},\"out\":{\"status\":\"st\",\"body\":\"rows\"}}]}");
            RunResult r = Runner.builder(PackagesTest.REG).workDir(w).service("vault", new CredentialVault.InMemory().put("test", "api", "token", "tok-123"))
                    .build().run(flow, Map.of("key", Map.of("$credential", "test/api/token")));
            assertTrue(r.ok(), "run failed: " + r.error());
            eq(6, r.outputs().get("n"));
            eq(200, r.outputs().get("st"));
            assertEquals(2, ((List<?>) r.outputs().get("rows")).size());
            assertEquals("tok-123", api.last("GET", "/v2/orders").header("X-Api-Key"));
            assertEquals("limit=5", api.last("GET", "/v2/orders").rawQuery(), "optional parameters are not sent unless given");
        }
    }

    // ---------- helpers ----------

    static void eq(long expected, Object actual) { assertEquals(0, BigDecimal.valueOf(expected).compareTo((BigDecimal) actual), "expected " + expected + " but was " + actual); }

    @SuppressWarnings("unchecked")
    static Map<String, Object> map(Object o) { return o instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of(); }

    static Map<String, Map<String, Object>> byId(Object list) { return index(list, "id"); }
    static Map<String, Map<String, Object>> byName(Object list) { return index(list, "name"); }
    static Map<String, Map<String, Object>> index(Object list, String key) {
        Map<String, Map<String, Object>> out = new LinkedHashMap<>();
        for (Object o : (List<?>) list) out.put(String.valueOf(map(o).get(key)), map(o));
        return out;
    }
}
