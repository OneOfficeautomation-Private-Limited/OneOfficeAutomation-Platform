package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.JsonLite;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.concurrent.CopyOnWriteArrayList;

import static org.junit.jupiter.api.Assertions.*;

/** Server-side trigger pollers against fake HTTP servers (universal poller, ServiceNow, Graph) and a fake IMAP server. */
class TriggerPollersTest {
    HttpServer http;
    final List<String> requests = new CopyOnWriteArrayList<>();

    String serve(String path, java.util.function.Function<Map<String, String>, Object> handler) throws IOException {
        if (http == null) {
            http = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            http.start();
        }
        http.createContext(path, ex -> {
            Map<String, String> q = new HashMap<>();
            String raw = ex.getRequestURI().getRawQuery();
            if (raw != null) for (String kv : raw.split("&")) { int i = kv.indexOf('='); q.put(URLDecoder.decode(kv.substring(0, i), StandardCharsets.UTF_8), URLDecoder.decode(kv.substring(i + 1), StandardCharsets.UTF_8)); }
            q.put("_auth", String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            q.put("_body", new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI());
            Object r = handler.apply(q);
            int status = r instanceof Integer s ? s : 200;
            byte[] b = (r instanceof Integer ? "{\"error\":{\"message\":\"nope\"}}" : JsonLite.write(r)).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        return "http://127.0.0.1:" + http.getAddress().getPort();
    }

    @AfterEach void stop() { if (http != null) http.stop(0); }

    // ---------- universal poller ----------
    @Test void universalPollerFiresOncePerNewItem() throws Exception {
        List<Object> orders = new CopyOnWriteArrayList<>(List.of(Map.of("order", Map.of("no", "A1"), "total", 10), Map.of("order", Map.of("no", "A2"), "total", 20)));
        String base = serve("/orders", q -> Map.of("data", Map.of("items", orders)));
        Map<String, Object> cfg = Map.of("url", base + "/orders", "items_path", "$.data.items[*]", "id_field", "order.no", "auth_ref", "api/orders/token", "headers", Map.of("X-Tenant", "t1"));
        List<String> refs = new ArrayList<>();
        java.util.function.Function<String, String> vault = ref -> { refs.add(ref); return "tok-123"; };

        TriggerPollers.Poll first = TriggerPollers.pollHttp(cfg, null, List.of(), false, vault);
        assertEquals(List.of(), first.payloads(), "items present at the first check are only remembered");
        assertEquals(2, first.seen().size());
        assertEquals(List.of("api/orders/token"), refs);

        orders.add(Map.of("order", Map.of("no", "A3"), "total", 30));
        TriggerPollers.Poll second = TriggerPollers.pollHttp(cfg, first.cursor(), first.seen(), false, vault);
        assertEquals(1, second.payloads().size());
        assertEquals("A3", second.payloads().get(0).get("item_id"));
        assertEquals(30, ((Number) second.payloads().get(0).get("total")).intValue());
        TriggerPollers.Poll third = TriggerPollers.pollHttp(cfg, second.cursor(), second.seen(), false, vault);
        assertEquals(List.of(), third.payloads(), "never twice");

        TriggerPollers.Poll all = TriggerPollers.pollHttp(cfg, null, List.of(), true, vault);
        assertEquals(3, all.payloads().size(), "fire_existing starts the agent for what is there now");

        IOException noId = assertThrows(IOException.class, () -> TriggerPollers.pollHttp(Map.of("url", base + "/orders", "items_path", "$.data.items", "id_field", "missing"), "x", List.of(), false, vault));
        assertTrue(noId.getMessage().contains("missing"));
        String failing = serve("/broken", q -> 503);
        IOException e = assertThrows(IOException.class, () -> TriggerPollers.pollHttp(Map.of("url", failing + "/broken", "id_field", "id"), null, List.of(), false, vault));
        assertTrue(e.getMessage().contains("503") && e.getMessage().contains("nope"), e.getMessage());
    }

    @Test void jsonPathSubset() {
        Object doc = JsonLite.parse("{\"a\":{\"list\":[{\"id\":1},{\"id\":2}],\"m\":{\"x\":1,\"y\":2}},\"value\":[3,4]}");
        assertEquals(2, JsonPath.compile("$.a.list[*]").items(doc).size());
        assertEquals(2, JsonPath.compile("a.list").items(doc).size());
        assertEquals(2, JsonPath.compile("$['a']['m'].*").items(doc).size());
        assertEquals(java.math.BigDecimal.valueOf(2), JsonPath.compile("$.a.list[-1].id").first(doc));
        assertEquals(2, JsonPath.compile("value").items(doc).size());
        assertTrue(JsonPath.compile("$.nothing").items(doc).isEmpty());
        assertThrows(RuntimeException.class, () -> JsonPath.compile("$.a[oops"));
    }

    // ---------- ServiceNow ----------
    @Test void serviceNowPollsNewAndUpdatedRecordsInOrder() throws Exception {
        List<Map<String, Object>> table = new CopyOnWriteArrayList<>();
        table.add(rec("aaa1", "2026-10-20 08:00:00", "2026-10-20 08:00:00", "INC1"));
        String base = serve("/api/now/table/incident", q -> {
            String query = q.get("sysparm_query");
            assertTrue(query.startsWith("active=true^"), query);
            List<Map<String, Object>> rows = new ArrayList<>(table);
            rows.sort(Comparator.comparing((Map<String, Object> r) -> String.valueOf(r.get("sys_updated_on"))).thenComparing(r -> String.valueOf(r.get("sys_id"))));
            if (query.endsWith("ORDERBYDESCsys_updated_on")) { Collections.reverse(rows); return Map.of("result", rows.subList(0, Math.min(1, rows.size()))); }
            String since = query.substring(query.indexOf("sys_updated_on>=") + 16, query.indexOf("^ORDERBY"));
            rows.removeIf(r -> String.valueOf(r.get("sys_updated_on")).compareTo(since) < 0);
            return Map.of("result", rows);
        });
        Map<String, Object> cfg = Map.of("instance_url", base, "table", "incident", "query", "active=true", "username", "svc", "password_ref", "snow/api/password");
        TriggerPollers.Poll p0 = TriggerPollers.pollServiceNow(cfg, null, false, ref -> "pw");
        assertEquals("2026-10-20 08:00:00|aaa1", p0.cursor());
        assertTrue(p0.payloads().isEmpty());

        table.add(rec("bbb2", "2026-10-20 09:00:00", "2026-10-20 09:00:00", "INC2"));
        table.add(rec("ccc3", "2026-10-20 09:00:00", "2026-10-20 09:00:00", "INC3"));   // same second
        table.set(0, rec("aaa1", "2026-10-20 08:00:00", "2026-10-20 09:30:00", "INC1"));
        TriggerPollers.Poll p1 = TriggerPollers.pollServiceNow(cfg, p0.cursor(), false, ref -> "pw");
        assertEquals(List.of("INC2/new", "INC3/new", "INC1/updated"), p1.payloads().stream().map(r -> r.get("number") + "/" + r.get("change")).toList());
        assertEquals("2026-10-20 09:30:00|aaa1", p1.cursor());
        assertTrue(requests.stream().anyMatch(r -> r.contains("sysparm_display_value=false")));
        assertTrue(TriggerPollers.pollServiceNow(cfg, p1.cursor(), false, ref -> "pw").payloads().isEmpty(), "each change fires once");

        Map<String, Object> tokenCfg = new HashMap<>(cfg);
        tokenCfg.remove("username"); tokenCfg.remove("password_ref"); tokenCfg.put("token_ref", "snow/oauth/token");
        assertEquals(3, TriggerPollers.pollServiceNow(tokenCfg, null, true, ref -> "t").payloads().size(), "fire_existing with a token");
    }

    static Map<String, Object> rec(String id, String created, String updated, String number) {
        return Map.of("sys_id", id, "sys_created_on", created, "sys_updated_on", updated, "number", number, "short_description", "Printer " + number);
    }

    // ---------- mail ----------
    @Test void mailPollerAppliesFiltersAndKeepsTheCursor() throws Exception {
        List<Map<String, Object>> inbox = new ArrayList<>();
        MailSource fake = (cursor, fireExisting) -> {
            int from = cursor == null ? inbox.size() : Integer.parseInt(cursor);
            if (cursor == null && !fireExisting) return new MailSource.Batch(String.valueOf(inbox.size()), List.of());
            if (cursor == null) from = 0;
            return new MailSource.Batch(String.valueOf(inbox.size()), new ArrayList<>(inbox.subList(from, inbox.size())));
        };
        Map<String, Object> cfg = Map.of("from_contains", "billing@", "subject_contains", "invoice");
        inbox.add(Map.of("from", "billing@acme.com", "subject", "Old invoice"));
        TriggerPollers.Poll p0 = TriggerPollers.pollMail(fake, cfg, null, false);
        assertTrue(p0.payloads().isEmpty());
        inbox.add(Map.of("from", "Billing@Acme.com", "subject", "INVOICE 7"));
        inbox.add(Map.of("from", "news@acme.com", "subject", "Invoice tips"));
        inbox.add(Map.of("from", "billing@acme.com", "subject", "Statement"));
        TriggerPollers.Poll p1 = TriggerPollers.pollMail(fake, cfg, p0.cursor(), false);
        assertEquals(List.of("INVOICE 7"), p1.payloads().stream().map(m -> m.get("subject")).toList());
        assertEquals("4", p1.cursor());
    }

    @Test void graphMailUsesReceivedTimeAndIdHashes() throws Exception {
        List<Map<String, Object>> msgs = new CopyOnWriteArrayList<>();
        msgs.add(graph("m1", "2026-10-20T08:00:00Z", "Old"));
        String base = serve("/v1.0/users/ap@contoso.com/mailFolders/inbox/messages", q -> {
            assertEquals("Bearer graph-token", q.get("_auth"));
            List<Map<String, Object>> rows = new ArrayList<>(msgs);
            rows.sort(Comparator.comparing(m -> String.valueOf(m.get("receivedDateTime"))));
            if (q.containsKey("$top") && q.get("$top").equals("1")) { Collections.reverse(rows); return Map.of("value", rows.subList(0, 1)); }
            String since = q.get("$filter").substring("receivedDateTime ge ".length());
            rows.removeIf(m -> String.valueOf(m.get("receivedDateTime")).compareTo(since) < 0);
            return Map.of("value", rows);
        });
        GraphMailSource src = new GraphMailSource(base + "/v1.0", "ap@contoso.com", "inbox", "graph-token");
        MailSource.Batch b0 = src.fetch(null, false);
        assertTrue(b0.cursor().startsWith("2026-10-20T08:00:00Z|"));
        msgs.add(graph("m2", "2026-10-20T09:00:00Z", "Invoice A"));
        msgs.add(graph("m3", "2026-10-20T09:00:00Z", "Invoice B"));
        MailSource.Batch b1 = src.fetch(b0.cursor(), false);
        assertEquals(List.of("Invoice A", "Invoice B"), b1.messages().stream().map(m -> m.get("subject")).toList());
        assertEquals("Ann <ann@x.com>", b1.messages().get(0).get("from"));
        assertTrue(b1.cursor().length() <= 64);
        msgs.add(graph("m4", "2026-10-20T09:00:00Z", "Invoice C"));   // arrives in the same second after the poll
        assertEquals(List.of("Invoice C"), src.fetch(b1.cursor(), false).messages().stream().map(m -> m.get("subject")).toList());

        String login = serve("/tenant-1/oauth2/v2.0/token", q -> {
            assertTrue(q.get("_body").contains("grant_type=client_credentials") && q.get("_body").contains("client_secret=s3"));
            return Map.of("access_token", "app-token");
        });
        assertEquals("app-token", GraphMailSource.clientCredentialsToken(login, "tenant-1", "client", "s3"));
    }

    static Map<String, Object> graph(String id, String at, String subject) {
        return Map.of("id", id, "receivedDateTime", at, "subject", subject, "bodyPreview", "Please pay", "hasAttachments", true,
                "from", Map.of("emailAddress", Map.of("name", "Ann", "address", "ann@x.com")), "toRecipients", List.of(Map.of("emailAddress", Map.of("address", "ap@contoso.com"))));
    }

    // ---------- IMAP client against a scripted fake server ----------
    @Test void imapClientReadsNewMessages() throws Exception {
        List<String> seen = new CopyOnWriteArrayList<>();
        try (ServerSocket ss = new ServerSocket(0)) {
            Thread server = new Thread(() -> {
                for (int conn = 0; conn < 2; conn++) {
                    try (Socket s = ss.accept()) {
                        BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                        OutputStream out = s.getOutputStream();
                        write(out, "* OK fake IMAP ready\r\n");
                        for (String line; (line = in.readLine()) != null; ) {
                            seen.add(line);
                            String tag = line.substring(0, line.indexOf(' ')), cmd = line.substring(line.indexOf(' ') + 1);
                            if (cmd.startsWith("LOGIN")) write(out, tag + " OK logged in\r\n");
                            else if (cmd.startsWith("SELECT")) write(out, "* 3 EXISTS\r\n* OK [UIDVALIDITY 77] ok\r\n* OK [UIDNEXT 12] ok\r\n" + tag + " OK [READ-WRITE] done\r\n");
                            else if (cmd.startsWith("UID SEARCH")) write(out, "* SEARCH 11 12 13\r\n" + tag + " OK done\r\n");
                            else if (cmd.startsWith("UID FETCH")) {
                                String uid = cmd.split(" ")[2];
                                String header = "From: =?UTF-8?B?QW5uIE3DvGxsZXI=?= <ann@x.com>\r\nSubject: Invoice " + uid + "\r\n =?ISO-8859-1?Q?f=FCr_you?=\r\nDate: Tue, 20 Oct 2026 09:00:00 +0000\r\n"
                                        + "Message-ID: <" + uid + "@x>\r\nContent-Type: multipart/alternative; boundary=\"b1\"\r\n\r\n";
                                String text = "--b1\r\nContent-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\nTotal: 10=E2=82=AC due\r\n--b1\r\nContent-Type: text/html\r\n\r\n<p>x</p>\r\n--b1--\r\n";
                                byte[] hb = header.getBytes(StandardCharsets.UTF_8), tb = text.getBytes(StandardCharsets.UTF_8);
                                write(out, "* 1 FETCH (UID " + uid + " BODY[HEADER.FIELDS (FROM TO SUBJECT)] {" + hb.length + "}\r\n" + header + " BODY[TEXT]<0> {" + tb.length + "}\r\n" + text + ")\r\n" + tag + " OK done\r\n");
                            } else if (cmd.startsWith("LOGOUT")) { write(out, "* BYE\r\n" + tag + " OK bye\r\n"); break; }
                            else write(out, tag + " BAD unknown\r\n");
                        }
                    } catch (IOException ignored) { }
                }
            });
            server.setDaemon(true);
            server.start();
            ImapMailSource src = new ImapMailSource("127.0.0.1", ss.getLocalPort(), false, "ann", "p\"w", "INBOX", 5000);
            MailSource.Batch base = src.fetch(null, false);
            assertEquals("77:11", base.cursor(), "the first look remembers UIDNEXT - 1");
            MailSource.Batch b = src.fetch("77:11", false);
            assertEquals("77:13", b.cursor());
            assertEquals(2, b.messages().size(), "uid 11 was already seen");
            Map<String, Object> m = b.messages().get(0);
            assertEquals("Ann Müller <ann@x.com>", m.get("from"));
            assertEquals("Invoice 12 für you", m.get("subject"));
            assertEquals("Total: 10€ due", m.get("body_preview"));
            assertEquals("<12@x>", m.get("message_id"));
            assertTrue(seen.contains("A1 LOGIN \"ann\" \"p\\\"w\""), String.valueOf(seen));
        }
    }

    static void write(OutputStream out, String s) throws IOException { out.write(s.getBytes(StandardCharsets.UTF_8)); out.flush(); }
}
