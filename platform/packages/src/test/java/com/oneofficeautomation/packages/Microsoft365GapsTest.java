package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.RunResult;
import com.oneofficeautomation.sdk.*;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.Test;

import java.io.OutputStream;
import java.lang.reflect.Method;
import java.lang.reflect.Parameter;
import java.math.BigDecimal;
import java.net.InetSocketAddress;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.Function;
import java.util.regex.Pattern;

import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/** The extended Microsoft 365 toolkit (mail, files, Excel online, calendar, Teams, SharePoint lists) against a fake Graph server. */
class Microsoft365GapsTest {
    record Call(String method, String path, String query, Map<String, String> headers, byte[] body) {
        String text() { return new String(body, StandardCharsets.UTF_8); }
        String q() { return query == null ? "" : URLDecoder.decode(query, StandardCharsets.UTF_8); }
        @SuppressWarnings("unchecked") Map<String, Object> json() { return (Map<String, Object>) JsonLite.parse(text()); }
        String header(String n) { return headers.get(n.toLowerCase(Locale.ROOT)); }
    }

    /** Fake Graph: routes "METHOD path-regex" to {status, body[, response headers]} and records every call. */
    static final class Fake implements AutoCloseable {
        final HttpServer server;
        final List<Call> calls = Collections.synchronizedList(new ArrayList<>());
        final Map<String, Function<Call, Object[]>> routes = new LinkedHashMap<>();
        Fake() throws Exception {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/", this::handle);
            server.start();
        }
        String url() { return "http://127.0.0.1:" + server.getAddress().getPort(); }
        String base() { return url() + "/v1.0"; }
        /** Route on an exact raw path (no regex escaping needed). */
        Fake at(String method, String path, Function<Call, Object[]> h) { routes.put(method + " " + Pattern.quote(path), h); return this; }
        Fake rx(String method, String regex, Function<Call, Object[]> h) { routes.put(method + " " + regex, h); return this; }
        @SuppressWarnings("unchecked")
        void handle(HttpExchange ex) throws java.io.IOException {
            Map<String, String> h = new HashMap<>();
            ex.getRequestHeaders().forEach((k, v) -> h.put(k.toLowerCase(Locale.ROOT), v.get(0)));
            Call c = new Call(ex.getRequestMethod(), ex.getRequestURI().getRawPath(), ex.getRequestURI().getRawQuery(), h, ex.getRequestBody().readAllBytes());
            calls.add(c);
            Object[] r = new Object[]{404, "{\"error\":{\"message\":\"no route " + c.method() + " " + c.path() + "\"}}"};
            for (var e : routes.entrySet()) {
                String[] k = e.getKey().split(" ", 2);
                if (k[0].equals(c.method()) && c.path().matches(k[1])) { r = e.getValue().apply(c); break; }
            }
            if (r.length > 2) ((Map<String, String>) r[2]).forEach((k, v) -> ex.getResponseHeaders().add(k, v));
            byte[] b = r[1] instanceof byte[] bytes ? bytes : String.valueOf(r[1]).getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders((Integer) r[0], b.length == 0 ? -1 : b.length);
            if (b.length > 0) try (OutputStream os = ex.getResponseBody()) { os.write(b); }
            ex.close();
        }
        Call last(String method, String path) {
            synchronized (calls) { for (int i = calls.size() - 1; i >= 0; i--) if (calls.get(i).method().equals(method) && calls.get(i).path().equals(path)) return calls.get(i); }
            fail("no " + method + " " + path + " in " + calls.stream().map(c -> c.method() + " " + c.path()).toList());
            return null;
        }
        List<Call> all(String method, String pathRegex) {
            synchronized (calls) { return calls.stream().filter(c -> c.method().equals(method) && c.path().matches(pathRegex)).toList(); }
        }
        @Override public void close() { server.stop(0); }
    }

    static Object[] ok(String json) { return new Object[]{200, json}; }
    static Object[] none() { return new Object[]{204, ""}; }
    static Object[] accepted() { return new Object[]{202, ""}; }

    final Microsoft365Package pkg = new Microsoft365Package();
    final SecureString tok = new SecureString("tok-123", "t");

    static ActionContext ctx(Path w) { return PackagesTestSupport.ctx(w, Map.of()); }

    @SuppressWarnings("unchecked") static Map<String, Object> tbl(Object t) { return (Map<String, Object>) t; }
    @SuppressWarnings("unchecked") static List<List<Object>> rows(Object t) { return (List<List<Object>>) tbl(t).get("rows"); }

    // ------------------------------------------------------------------------------------ mail

    @Test void outlookMarkDeleteMoveForwardReplyFolderAndEml() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), me = "/v1.0/me";
            f.at("PATCH", me + "/messages/m1", c -> ok("{}")).at("PATCH", "/v1.0/users/ap%40acme.com/messages/m1", c -> ok("{}"))
             .rx("POST", "/v1\\.0/me/messages/[^/]+/move", c -> ok("{\"id\":\"moved\"}"))
             .rx("DELETE", "/v1\\.0/me/messages/[^/]+", c -> none())
             .at("GET", me + "/mailFolders/inbox/messages", c -> c.q().contains("skiptoken")
                    ? ok("{\"value\":[{\"id\":\"m3\",\"subject\":\"Invoice 3\",\"from\":{\"emailAddress\":{\"address\":\"ap@acme.com\"}}}]}")
                    : ok("{\"value\":[{\"id\":\"m1\",\"subject\":\"Invoice 1\",\"from\":{\"emailAddress\":{\"address\":\"AP@acme.com\"}}},"
                       + "{\"id\":\"m2\",\"subject\":\"Lunch\",\"from\":{\"emailAddress\":{\"address\":\"bob@corp.com\"}}}],"
                       + "\"@odata.nextLink\":\"" + b + "/me/mailFolders/inbox/messages?$skiptoken=2\"}"))
             .rx("POST", "/v1\\.0/me/messages/m1/(forward|reply|replyAll)", c -> accepted())
             .at("GET", me + "/mailFolders", c -> c.q().contains("'Invoices'") ? ok("{\"value\":[{\"id\":\"F-INV\",\"displayName\":\"Invoices\"}]}") : ok("{\"value\":[]}"))
             .at("GET", me + "/messages/m1/$value", c -> new Object[]{200, "From: ap@acme.com\r\nSubject: Invoice 1\r\n\r\nHello".getBytes()});
            Path w = tmp();
            ActionContext ctx = ctx(w);

            pkg.markMail(ctx, tok, "m1", "read", "me", b);
            assertEquals(Map.of("isRead", true), f.last("PATCH", me + "/messages/m1").json());
            assertEquals("Bearer tok-123", f.last("PATCH", me + "/messages/m1").header("Authorization"));
            pkg.markMail(ctx, tok, "m1", "flagged", "me", b);
            assertEquals("flagged", ((Map<?, ?>) f.last("PATCH", me + "/messages/m1").json().get("flag")).get("flagStatus"));
            assertEquals("M365.BAD_STATUS", assertThrows(ActionException.class, () -> pkg.markMail(ctx, tok, "m1", "starred", "me", b)).code());

            pkg.deleteMail(ctx, tok, "m1", false, "me", b);
            assertEquals("deleteditems", f.last("POST", me + "/messages/m1/move").json().get("destinationId"), "soft delete moves to Deleted Items");
            pkg.deleteMail(ctx, tok, "m2", true, "me", b);
            assertNotNull(f.last("DELETE", me + "/messages/m2"));

            f.calls.clear();
            Map<String, Object> del = pkg.deleteAllMail(ctx, tok, "inbox", false, "acme", null, true, 1000, false, null, null, "me", b);
            assertEquals(BigDecimal.valueOf(2), del.get("count"));
            assertEquals(List.of(me + "/messages/m1", me + "/messages/m3"), f.all("DELETE", ".*").stream().map(Call::path).toList(), "follows paging, filters sender");
            int firstDelete = f.calls.indexOf(f.all("DELETE", ".*").get(0));
            assertTrue(f.calls.subList(firstDelete, f.calls.size()).stream().noneMatch(c -> c.method().equals("GET")), "all pages read before deleting");

            f.calls.clear();
            Map<String, Object> mv = pkg.moveAllMail(ctx, tok, "archive", "inbox", false, null, "invoice", 1, false, null, null, "me", b);
            assertEquals(BigDecimal.ONE, mv.get("count"), "max_results caps the run");
            assertEquals("archive", f.last("POST", me + "/messages/m1/move").json().get("destinationId"));

            pkg.forwardMail(ctx, tok, "m1", "x@y.com; z@y.com", "FYI", null, null, null, null, null, "me", b);
            Map<String, Object> fw = f.last("POST", me + "/messages/m1/forward").json();
            assertEquals(2, ((List<?>) fw.get("toRecipients")).size());
            assertEquals("FYI", fw.get("comment"));
            assertEquals("M365.NO_RECIPIENTS", assertThrows(ActionException.class, () -> pkg.forwardMail(ctx, tok, "m1", "", "", null, null, null, null, null, "me", b)).code());

            pkg.replyMail(ctx, tok, "m1", "Thanks", false, null, null, null, null, null, false, "me", b);
            assertEquals("Thanks", f.last("POST", me + "/messages/m1/reply").json().get("comment"));
            pkg.replyAllMail(ctx, tok, "m1", "Thanks all", null, null, null, null, null, false, "me", b);
            assertEquals("Thanks all", f.last("POST", me + "/messages/m1/replyAll").json().get("comment"));

            Map<String, Object> ex = pkg.folderExists(ctx, tok, "Invoices", "me", b);
            assertEquals(true, ex.get("exists"));
            assertEquals("F-INV", ex.get("folder_id"));
            assertTrue(f.last("GET", me + "/mailFolders").q().contains("$filter=displayName eq 'Invoices'"));
            Map<String, Object> no = pkg.folderExists(ctx, tok, "O'Brien", "me", b);
            assertEquals(false, no.get("exists"));
            assertTrue(f.last("GET", me + "/mailFolders").q().contains("'O''Brien'"), "quotes escaped in the filter");

            Map<String, Object> eml = pkg.saveEml(ctx, tok, "m1", "mail/inv.eml", true, "me", b);
            assertTrue(Files.readString(Path.of((String) eml.get("path"))).endsWith("Hello"));

            pkg.markMail(ctx, tok, "m1", "unread", "ap@acme.com", b);
            assertEquals(Map.of("isRead", false), f.last("PATCH", "/v1.0/users/ap%40acme.com/messages/m1").json(), "shared mailbox addressing");
        }
    }

    // ------------------------------------------------------------------------------------ files

    @Test void filesCopyMoveRenameDeleteFolderSearchInfoPermissionsPdfAndVersions() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), d = "/v1.0/me/drive";
            AtomicInteger polls = new AtomicInteger();
            f.at("GET", d + "/root:/Archive", c -> ok("{\"id\":\"F1\",\"parentReference\":{\"driveId\":\"D1\"}}"))
             .at("GET", d + "/root", c -> ok("{\"id\":\"ROOT\",\"parentReference\":{\"driveId\":\"D1\"}}"))
             .at("POST", d + "/root:/Docs/a.docx:/copy", c -> new Object[]{202, "", Map.of("Location", f.url() + "/monitor/1")})
             .at("GET", "/monitor/1", c -> polls.incrementAndGet() == 1 ? ok("{\"status\":\"inProgress\"}") : ok("{\"status\":\"completed\",\"resourceId\":\"NEW1\"}"))
             .at("PATCH", d + "/items/I9", c -> ok("{\"id\":\"I9\",\"webUrl\":\"https://od/I9\"}"))
             .at("DELETE", d + "/root:/Old/x.txt", c -> none())
             .at("POST", d + "/root/children", c -> new Object[]{201, "{\"id\":\"NF\",\"webUrl\":\"https://od/NF\"}"})
             .at("POST", "/v1.0/sites/S1/drive/root:/Shared/Reports:/children", c -> new Object[]{201, "{\"id\":\"SF\"}"})
             .rx("GET", "/v1\\.0/me/drive/root/search\\(q=.*\\)", c -> c.q().contains("skip")
                    ? ok("{\"value\":[{\"id\":\"i3\",\"name\":\"inv 3.pdf\",\"size\":3}]}")
                    : ok("{\"value\":[{\"id\":\"i1\",\"name\":\"inv 1.pdf\",\"size\":1},{\"id\":\"i2\",\"name\":\"Invoices\",\"folder\":{}}],\"@odata.nextLink\":\"" + b + "/me/drive/root/search(q='inv')?$skip=2\"}"))
             .at("GET", d + "/items/I9", c -> ok("{\"id\":\"I9\",\"name\":\"a.docx\",\"size\":10,\"file\":{},\"lastModifiedBy\":{\"user\":{\"displayName\":\"Ann\"}},\"parentReference\":{\"path\":\"/drive/root:/Docs\"}}"))
             .at("GET", d + "/items/I9/permissions", c -> ok("{\"value\":[{\"id\":\"p1\",\"roles\":[\"write\"],\"grantedToV2\":{\"user\":{\"email\":\"ann@x.com\"}}},"
                    + "{\"id\":\"p2\",\"roles\":[\"read\"],\"link\":{\"type\":\"view\",\"scope\":\"organization\",\"webUrl\":\"https://share/1\"},\"grantedToIdentitiesV2\":[{\"user\":{\"email\":\"bob@x.com\"}}]}]}"))
             .at("GET", d + "/root:/Docs/a.docx:/content", c -> new Object[]{200, "%PDF-1.7".getBytes()})
             .at("GET", d + "/root:/Docs/a.docx:/versions", c -> ok("{\"value\":[{\"id\":\"3.0\"},{\"id\":\"2.0\"},{\"id\":\"1.0\"}]}"))
             .rx("POST", "/v1\\.0/me/drive/root:/Docs/a\\.docx:/versions/[^/]+/restoreVersion", c -> none())
             .at("GET", d + "/root:/Docs/new.txt:/versions", c -> ok("{\"value\":[{\"id\":\"1.0\"}]}"));
            Path w = tmp();
            ActionContext ctx = ctx(w);

            Map<String, Object> cp = pkg.copyItem(ctx, tok, "Docs/a.docx", "Archive", "a-copy.docx", null, 30, "fail", "me/drive", b);
            assertEquals("NEW1", cp.get("item_id"));
            assertEquals("completed", cp.get("status"));
            Map<String, Object> copyBody = f.last("POST", d + "/root:/Docs/a.docx:/copy").json();
            assertEquals(Map.of("driveId", "D1", "id", "F1"), copyBody.get("parentReference"));
            assertEquals("a-copy.docx", copyBody.get("name"));
            assertNull(f.last("GET", "/monitor/1").header("Authorization"), "the monitor URL never receives the token");
            Map<String, Object> cp0 = pkg.copyItem(ctx, tok, "Docs/a.docx", "", null, null, 0, "fail", "me/drive", b);
            assertEquals(f.url() + "/monitor/1", cp0.get("monitor_url"));
            assertEquals("inProgress", cp0.get("status"));
            assertEquals("ROOT", ((Map<?, ?>) f.last("POST", d + "/root:/Docs/a.docx:/copy").json().get("parentReference")).get("id"));

            Map<String, Object> mv = pkg.moveItem(ctx, tok, "id:I9", "Archive", "b.docx", "fail", "me/drive", b);
            assertEquals("https://od/I9", mv.get("web_url"));
            Map<String, Object> mvBody = f.last("PATCH", d + "/items/I9").json();
            assertEquals(Map.of("id", "F1"), mvBody.get("parentReference"));
            assertEquals("b.docx", mvBody.get("name"));
            pkg.renameItem(ctx, tok, "id:I9", "c.docx", "me/drive", b);
            assertEquals(Map.of("name", "c.docx"), f.last("PATCH", d + "/items/I9").json());

            pkg.deleteItem(ctx, tok, "Old/x.txt", false, "me/drive", b);
            assertNotNull(f.last("DELETE", d + "/root:/Old/x.txt"));
            assertEquals("M365.BAD_ITEM", assertThrows(ActionException.class, () -> pkg.deleteItem(ctx, tok, " ", false, "me/drive", b)).code());

            assertEquals("NF", pkg.createFolder(ctx, tok, "2026", "", "rename", "me/drive", b).get("item_id"));
            Map<String, Object> cf = f.last("POST", d + "/root/children").json();
            assertEquals("rename", cf.get("@microsoft.graph.conflictBehavior"));
            assertEquals(Map.of(), cf.get("folder"));
            assertEquals("SF", pkg.createFolder(ctx, tok, "Q4", "Shared/Reports", "fail", "sites/S1/drive", b).get("item_id"), "SharePoint library drive");

            Map<String, Object> found = pkg.findItems(ctx, tok, "inv", 10, "any", null, false, "me/drive", b);
            assertEquals(BigDecimal.valueOf(3), found.get("count"));
            assertEquals("folder", rows(found.get("table")).get(1).get(2));
            assertTrue(f.all("GET", ".*/search.*").get(0).path().endsWith("/root/search(q=%27inv%27)"));

            @SuppressWarnings("unchecked") Map<String, Object> info = (Map<String, Object>) pkg.itemInfo(ctx, tok, "id:I9", "me/drive", b).get("info");
            assertEquals("Ann", info.get("modified_by"));
            assertEquals("file", info.get("type"));
            assertEquals("/drive/root:/Docs", info.get("parent_path"));

            List<List<Object>> perms = rows(pkg.listPermissions(ctx, tok, "id:I9", "me/drive", b).get("table"));
            assertEquals(Arrays.asList("p1", "write", "ann@x.com", null, null, null), perms.get(0));
            assertEquals("https://share/1", perms.get(1).get(5));
            Map<String, Object> chk = pkg.checkPermissions(ctx, tok, "id:I9", "BOB@x.com", "me/drive", b);
            assertEquals(true, chk.get("has_access"));
            assertEquals(List.of("read"), chk.get("roles"));
            assertEquals(false, pkg.checkPermissions(ctx, tok, "id:I9", "eve@x.com", "me/drive", b).get("has_access"));

            Map<String, Object> pdf = pkg.exportPdf(ctx, tok, "Docs/a.docx", "out/a.pdf", "me/drive", b);
            assertEquals("%PDF-1.7", Files.readString(Path.of((String) pdf.get("path"))));
            assertEquals("format=pdf", f.last("GET", d + "/root:/Docs/a.docx:/content").q());

            assertEquals("2.0", pkg.restoreVersion(ctx, tok, "Docs/a.docx", "", "me/drive", b).get("restored_version"), "previous version by default");
            assertNotNull(f.last("POST", d + "/root:/Docs/a.docx:/versions/2.0/restoreVersion"));
            pkg.restoreVersion(ctx, tok, "Docs/a.docx", "1.0", "me/drive", b);
            assertNotNull(f.last("POST", d + "/root:/Docs/a.docx:/versions/1.0/restoreVersion"));
            assertEquals("M365.NO_OLDER_VERSION", assertThrows(ActionException.class, () -> pkg.restoreVersion(ctx, tok, "Docs/new.txt", "", "me/drive", b)).code());
        }
    }

    @Test void copyFailureBecomesTypedError() throws Exception {
        try (Fake f = new Fake()) {
            String d = "/v1.0/me/drive";
            f.at("GET", d + "/root", c -> ok("{\"id\":\"ROOT\"}"))
             .at("POST", d + "/items/X/copy", c -> new Object[]{202, "", Map.of("Location", f.url() + "/monitor/9")})
             .at("GET", "/monitor/9", c -> ok("{\"status\":\"failed\",\"error\":{\"message\":\"Name already exists\"}}"));
            ActionException e = assertThrows(ActionException.class, () -> pkg.copyItem(ctx(tmp()), tok, "id:X", "", null, null, 5, "fail", "me/drive", f.base()));
            assertEquals("M365.COPY_FAILED", e.code());
            assertTrue(e.getMessage().contains("Name already exists"));
        }
    }

    // ------------------------------------------------------------------------------------ Excel

    @Test void excelWorksheetsRangesCellsFormulasAndFormatting() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), wb = "/v1.0/me/drive/root:/Fin/book.xlsx:/workbook", sh = wb + "/worksheets('Data')";
            f.at("GET", wb + "/worksheets", c -> ok("{\"value\":[{\"id\":\"s2\",\"name\":\"B\",\"position\":1,\"visibility\":\"Visible\"},{\"id\":\"s1\",\"name\":\"Data\",\"position\":0,\"visibility\":\"Visible\"}]}"))
             .at("POST", wb + "/worksheets/add", c -> new Object[]{201, "{\"id\":\"s3\",\"name\":\"New\"}"})
             .at("DELETE", wb + "/worksheets('Old')", c -> none())
             .at("PATCH", sh, c -> ok("{}"))
             .at("GET", sh + "/range(address='A1:B2')", c -> ok("{\"values\":[[1,2],[3,4]]}"))
             .at("GET", "/v1.0/me/drive/items/ABC/workbook/worksheets('Data')/range(address='A1:B2')", c -> ok("{\"values\":[[\"x\"]]}"))
             .rx("PATCH", Pattern.quote(sh + "/range(address='") + "[A-Z0-9:]+'\\)", c -> ok("{\"values\":[[99]]}"))
             .at("GET", sh + "/range(address='C7')", c -> ok("{\"values\":[[42]],\"text\":[[\"42.00\"]],\"formulas\":[[\"=6*7\"]]}"))
             .at("POST", sh + "/range(address='A2:F100')/clear", c -> ok("{}"))
             .at("POST", sh + "/range(address='A5:F5')/delete", c -> none())
             .at("POST", sh + "/range(address='A5:F5')/insert", c -> ok("{\"address\":\"Data!A5:F5\"}"))
             .rx("POST", Pattern.quote(sh) + ".*/format/autofit(Columns|Rows)", c -> none())
             .rx("PATCH", Pattern.quote(sh + "/range(address='A1:B2')/format/") + "(fill|font)", c -> ok("{}"));
            ActionContext ctx = ctx(tmp());
            String book = "Fin/book.xlsx", dr = "me/drive";

            Map<String, Object> sheets = pkg.excelSheets(ctx, tok, book, dr, b);
            assertEquals(List.of("Data", "B"), sheets.get("sheets"), "tab order");
            assertEquals(Map.of("sheet_name", "New", "sheet_id", "s3"), pkg.excelAddSheet(ctx, tok, book, "New", dr, b));
            assertEquals(Map.of("name", "New"), f.last("POST", wb + "/worksheets/add").json());
            pkg.excelDeleteSheet(ctx, tok, book, "Old", dr, b);
            assertNotNull(f.last("DELETE", wb + "/worksheets('Old')"));
            pkg.excelRenameSheet(ctx, tok, book, "Data", "Data 2026", dr, b);
            assertEquals(Map.of("name", "Data 2026"), f.last("PATCH", sh).json());

            Map<String, Object> rg = pkg.excelGetRange(ctx, tok, book, "Data", "A1:B2", dr, b);
            assertEquals(BigDecimal.valueOf(2), rg.get("row_count"));
            assertEquals(BigDecimal.valueOf(2), rg.get("column_count"));
            assertEquals("x", ((List<?>) ((List<?>) pkg.excelGetRange(ctx, tok, "id:ABC", "Data", "A1:B2", dr, b).get("values")).get(0)).get(0), "workbook by item id");

            Map<String, Object> set = pkg.excelSetRange(ctx, tok, book, "Data", "B2", List.of(List.of("a", "b", "c"), List.of("d", "e")), dr, b);
            assertEquals("B2:D3", set.get("address"), "single cell grows to fit");
            assertTrue(f.last("PATCH", sh + "/range(address='B2:D3')").text().contains("\"values\":[[\"a\",\"b\",\"c\"],[\"d\",\"e\",\"\"]]"), "short rows padded");
            assertEquals("A1:C1", pkg.excelSetRange(ctx, tok, book, "Data", "A1", List.of("x", "y", "z"), dr, b).get("address"), "flat list is one row");
            assertEquals("M365.BAD_RANGE", assertThrows(ActionException.class, () -> pkg.excelSetRange(ctx, tok, book, "Data", "A1;DROP", List.of("x"), dr, b)).code());

            Map<String, Object> cell = pkg.excelGetCell(ctx, tok, book, "Data", "C7", dr, b);
            assertEquals(new BigDecimal("42"), new BigDecimal(String.valueOf(cell.get("value"))));
            assertEquals("42.00", cell.get("text"));
            pkg.excelSetCell(ctx, tok, book, "Data", "C7", "Paid", dr, b);
            assertEquals("{\"values\":[[\"Paid\"]]}", f.last("PATCH", sh + "/range(address='C7')").text());
            Map<String, Object> fm = pkg.excelSetFormula(ctx, tok, book, "Data", "D10", "SUM(D2:D9)", dr, b);
            assertEquals("{\"formulas\":[[\"=SUM(D2:D9)\"]]}", f.last("PATCH", sh + "/range(address='D10')").text());
            assertEquals("99", String.valueOf(fm.get("value")));
            assertEquals("=6*7", pkg.excelReadFormula(ctx, tok, book, "Data", "C7", dr, b).get("formula"));

            pkg.excelClear(ctx, tok, book, "Data", "A2:F100", "contents", dr, b);
            assertEquals(Map.of("applyTo", "Contents"), f.last("POST", sh + "/range(address='A2:F100')/clear").json());
            pkg.excelDeleteRange(ctx, tok, book, "Data", "A5:F5", "up", dr, b);
            assertEquals(Map.of("shift", "Up"), f.last("POST", sh + "/range(address='A5:F5')/delete").json());
            assertEquals("Data!A5:F5", pkg.excelInsertRange(ctx, tok, book, "Data", "A5:F5", "down", dr, b).get("address"));
            assertEquals(Map.of("shift", "Down"), f.last("POST", sh + "/range(address='A5:F5')/insert").json());
            assertEquals("M365.BAD_OPTION", assertThrows(ActionException.class, () -> pkg.excelDeleteRange(ctx, tok, book, "Data", "A5:F5", "down", dr, b)).code());
            pkg.excelAutofitColumns(ctx, tok, book, "Data", "A1:F1", dr, b);
            assertNotNull(f.last("POST", sh + "/range(address='A1:F1')/format/autofitColumns"));
            pkg.excelAutofitRows(ctx, tok, book, "Data", "A1:A50", dr, b);
            assertNotNull(f.last("POST", sh + "/range(address='A1:A50')/format/autofitRows"));

            pkg.excelFormat(ctx, tok, book, "Data", "A1:B2", "#FFF2CC", true, "#C00000", "0.00", null, null, null, null, null, null, null, null, null, null, null, null, null, dr, b);
            assertEquals(Map.of("color", "#FFF2CC"), f.last("PATCH", sh + "/range(address='A1:B2')/format/fill").json());
            assertEquals(Map.of("bold", true, "color", "#C00000"), f.last("PATCH", sh + "/range(address='A1:B2')/format/font").json());
            assertEquals(Map.of("numberFormat", List.of(List.of("0.00", "0.00"), List.of("0.00", "0.00"))), f.last("PATCH", sh + "/range(address='A1:B2')").json());
            assertEquals("M365.NOTHING_TO_DO", assertThrows(ActionException.class, () -> pkg.excelFormat(ctx, tok, book, "Data", "A1:B2", null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, null, dr, b)).code());
        }
    }

    @Test void excelUsedRangeRowsColumnsTablesAndDataTables() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), wb = "/v1.0/me/drive/root:/Fin/book.xlsx:/workbook", sh = wb + "/worksheets('Data')";
            f.at("GET", sh + "/usedRange(valuesOnly=true)", c -> ok("{\"address\":\"Data!B2:D10\",\"rowCount\":9,\"columnCount\":3}"))
             .at("GET", sh + "/range(address='A5:D5')", c -> ok("{\"values\":[[\"\",\"x\",1,2]]}"))
             .at("GET", sh + "/range(address='C1:C10')", c -> ok("{\"values\":[[\"h\"],[1],[2]]}"))
             .at("GET", wb + "/tables", c -> ok("{\"value\":[{\"id\":\"t1\",\"name\":\"Sales\",\"style\":\"TableStyleMedium2\",\"showHeaders\":true}]}"))
             .at("GET", sh + "/tables", c -> ok("{\"value\":[]}"))
             .at("POST", sh + "/tables/add", c -> new Object[]{201, "{\"id\":\"{T-1}\",\"name\":\"Table1\"}"})
             .at("PATCH", wb + "/tables/%7BT-1%7D", c -> ok("{}"))
             .at("POST", wb + "/tables/Sales/rows/add", c -> new Object[]{201, "{}"})
             .at("GET", wb + "/tables/Sales/headerRowRange", c -> ok("{\"values\":[[\"Name\",\"Amount\"]]}"))
             .at("GET", wb + "/tables/Sales/rows", c -> c.q().contains("skip")
                    ? ok("{\"value\":[{\"index\":1,\"values\":[[\"Globex\",7]]}]}")
                    : ok("{\"value\":[{\"index\":0,\"values\":[[\"Acme\",5]]}],\"@odata.nextLink\":\"" + b + "/me/drive/root:/Fin/book.xlsx:/workbook/tables/Sales/rows?$skip=1\"}"))
             .at("DELETE", wb + "/tables/Sales", c -> none())
             .at("PATCH", sh + "/range(address='C3:D5')", c -> ok("{}"))
             .at("PATCH", sh + "/range(address='A1:B2')", c -> ok("{}"));
            ActionContext ctx = ctx(tmp());
            String book = "Fin/book.xlsx", dr = "me/drive";

            Map<String, Object> used = pkg.excelUsedRange(ctx, tok, book, "Data", dr, b);
            assertEquals("B2:D10", used.get("address"));
            assertEquals(BigDecimal.valueOf(9), used.get("row_count"));
            assertEquals(BigDecimal.valueOf(10), used.get("last_row"));
            assertEquals("D", used.get("last_column"));
            assertTrue(f.last("GET", sh + "/usedRange(valuesOnly=true)").q().contains("$select=address"));

            assertEquals(4, ((List<?>) pkg.excelReadRow(ctx, tok, book, "Data", 5, dr, b).get("values")).size(), "row from A to the last used column");
            List<?> col = (List<?>) pkg.excelReadColumn(ctx, tok, book, "Data", "3", dr, b).get("values");
            assertEquals("h", col.get(0), "column number 3 is C, read to the last used row");

            assertEquals(List.of(List.of("t1", "Sales", "TableStyleMedium2", true)), rows(pkg.excelListTables(ctx, tok, book, "", dr, b).get("table")));
            assertTrue(rows(pkg.excelListTables(ctx, tok, book, "Data", dr, b).get("table")).isEmpty());

            Map<String, Object> ct = pkg.excelCreateTable(ctx, tok, book, "Data", "a1:b1", true, "Sales", dr, b);
            assertEquals("Sales", ct.get("table_name"));
            assertEquals(Map.of("address", "A1:B1", "hasHeaders", true), f.last("POST", sh + "/tables/add").json());
            assertEquals(Map.of("name", "Sales"), f.last("PATCH", wb + "/tables/%7BT-1%7D").json());

            Map<String, Object> dt = Map.of("columns", List.of("Name", "Amount"), "rows", List.of(List.of("Initech", "9"), List.of("Hooli")));
            assertEquals(BigDecimal.valueOf(2), pkg.excelAddTableRows(ctx, tok, book, "Sales", dt, dr, b).get("count"));
            assertEquals("{\"index\":null,\"values\":[[\"Initech\",\"9\"],[\"Hooli\",\"\"]]}", f.last("POST", wb + "/tables/Sales/rows/add").text());

            Map<String, Object> tr = pkg.excelGetTableRows(ctx, tok, book, "Sales", dr, b);
            assertEquals(List.of("Name", "Amount"), tbl(tr.get("table")).get("columns"));
            assertEquals(BigDecimal.valueOf(2), tr.get("count"), "follows nextLink");
            assertEquals("Globex", rows(tr.get("table")).get(1).get(0));

            pkg.excelDeleteTable(ctx, tok, book, "Sales", dr, b);
            assertNotNull(f.last("DELETE", wb + "/tables/Sales"));

            Map<String, Object> wdt = pkg.excelWriteDataTable(ctx, tok, book, "Data", Map.of("columns", List.of("Name", "Amount"), "rows", List.of(List.of("A", "1"), List.of("B", "2"))), "C3", true, dr, b);
            assertEquals("C3:D5", wdt.get("address"));
            assertEquals("{\"values\":[[\"Name\",\"Amount\"],[\"A\",\"1\"],[\"B\",\"2\"]]}", f.last("PATCH", sh + "/range(address='C3:D5')").text());
            assertEquals("A1:B2", pkg.excelWriteDataTable(ctx, tok, book, "Data", Map.of("columns", List.of("Name", "Amount"), "rows", List.of(List.of("A", "1"), List.of("B", "2"))), "A1", false, dr, b).get("address"), "without headers");
        }
    }

    // ------------------------------------------------------------------------------------ calendar

    @Test void calendarUpdateCancelDeleteRespondAttendeesAttachmentAndFreeSlots() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), ev = "/v1.0/me/events/E1";
            f.at("PATCH", ev, c -> ok("{\"id\":\"E1\",\"webLink\":\"https://outlook/E1\"}"))
             .at("GET", ev, c -> ok("{\"attendees\":[{\"emailAddress\":{\"address\":\"a@x.com\"},\"type\":\"required\"},{\"emailAddress\":{\"address\":\"b@x.com\"},\"type\":\"optional\"}]}"))
             .rx("POST", Pattern.quote(ev) + "/(cancel|accept|tentativelyAccept|decline)", c -> accepted())
             .at("DELETE", "/v1.0/me/events/E2", c -> none())
             .at("POST", ev + "/attachments", c -> new Object[]{201, "{\"id\":\"AT1\"}"})
             .at("POST", "/v1.0/me/findMeetingTimes", c -> ok("{\"emptySuggestionsReason\":\"\",\"meetingTimeSuggestions\":["
                    + "{\"confidence\":100,\"organizerAvailability\":\"free\",\"meetingTimeSlot\":{\"start\":{\"dateTime\":\"2026-11-02T09:00:00\"},\"end\":{\"dateTime\":\"2026-11-02T09:45:00\"}}},"
                    + "{\"confidence\":50,\"organizerAvailability\":\"free\",\"meetingTimeSlot\":{\"start\":{\"dateTime\":\"2026-11-02T11:00:00\"},\"end\":{\"dateTime\":\"2026-11-02T11:45:00\"}}}]}"));
            Path w = tmp();
            Files.writeString(w.resolve("agenda.txt"), "1. Budget");
            ActionContext ctx = ctx(w);
            LocalDateTime s = LocalDateTime.parse("2026-11-02T09:00"), e = LocalDateTime.parse("2026-11-02T10:00");

            assertEquals("https://outlook/E1", pkg.updateEvent(ctx, tok, "E1", "Budget review", s, e, "UTC", null, "Room 4", false, null, null, null, null, null, "me", b).get("web_link"));
            Map<String, Object> up = f.last("PATCH", ev).json();
            assertEquals("Budget review", up.get("subject"));
            assertEquals("2026-11-02T09:00", ((Map<?, ?>) up.get("start")).get("dateTime"));
            assertEquals(Map.of("displayName", "Room 4"), up.get("location"));
            assertFalse(up.containsKey("body"), "unchanged fields are not sent");
            assertEquals("M365.NOTHING_TO_DO", assertThrows(ActionException.class, () -> pkg.updateEvent(ctx, tok, "E1", null, null, null, "UTC", null, null, false, null, null, null, null, null, "me", b)).code());
            assertEquals("M365.BAD_TIMES", assertThrows(ActionException.class, () -> pkg.updateEvent(ctx, tok, "E1", null, e, s, "UTC", null, null, false, null, null, null, null, null, "me", b)).code());

            pkg.cancelEvent(ctx, tok, "E1", "Moved to next week", "me", b);
            assertEquals(Map.of("comment", "Moved to next week"), f.last("POST", ev + "/cancel").json());
            pkg.deleteEvent(ctx, tok, "E2", "me", b);
            assertNotNull(f.last("DELETE", "/v1.0/me/events/E2"));
            pkg.respondEvent(ctx, tok, "E1", "tentative", "May be late", false, "me", b);
            assertEquals(Map.of("comment", "May be late", "sendResponse", false), f.last("POST", ev + "/tentativelyAccept").json());
            pkg.respondEvent(ctx, tok, "E1", "decline", "", true, "me", b);
            assertNotNull(f.last("POST", ev + "/decline"));

            Map<String, Object> added = pkg.addAttendees(ctx, tok, "E1", "B@x.com; c@x.com", "optional", "me", b);
            assertEquals(List.of("a@x.com", "b@x.com", "c@x.com"), added.get("attendees"), "existing attendee not added twice");
            assertTrue(f.last("GET", ev).q().contains("$select=attendees"));
            List<?> patched = (List<?>) f.last("PATCH", ev).json().get("attendees");
            assertEquals(3, patched.size());
            assertEquals("optional", ((Map<?, ?>) patched.get(2)).get("type"));
            assertEquals(List.of("b@x.com"), pkg.removeAttendees(ctx, tok, "E1", List.of("A@x.com"), "me", b).get("attendees"));
            assertEquals(1, ((List<?>) f.last("PATCH", ev).json().get("attendees")).size());

            assertEquals("AT1", pkg.addEventAttachment(ctx, tok, "E1", "agenda.txt", "me", b).get("attachment_id"));
            Map<String, Object> att = f.last("POST", ev + "/attachments").json();
            assertEquals("agenda.txt", att.get("name"));
            assertEquals("1. Budget", new String(Base64.getDecoder().decode((String) att.get("contentBytes"))));

            Map<String, Object> slots = pkg.findMeetingTimes(ctx, tok, "a@x.com, b@x.com", s, LocalDateTime.parse("2026-11-02T17:00"), 45, "UTC", 5, "me", b);
            assertEquals(BigDecimal.valueOf(2), slots.get("count"));
            assertEquals(List.of("2026-11-02T09:00:00", "2026-11-02T09:45:00", new BigDecimal("100"), "free"),
                    rows(slots.get("table")).get(0).stream().map(v -> v instanceof Number n ? new BigDecimal(n.toString()) : v).toList());
            Map<String, Object> fmt = f.last("POST", "/v1.0/me/findMeetingTimes").json();
            assertEquals("PT45M", fmt.get("meetingDuration"));
            assertEquals(2, ((List<?>) fmt.get("attendees")).size());
            assertEquals("outlook.timezone=\"UTC\"", f.last("POST", "/v1.0/me/findMeetingTimes").header("Prefer"));
        }
    }

    // ------------------------------------------------------------------------------------ Teams

    @Test void teamsLookupDetailsUserAndChats() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("GET", "/v1.0/me/joinedTeams", c -> ok("{\"value\":[{\"id\":\"T1\",\"displayName\":\"Finance\",\"description\":\"Money\"},{\"id\":\"T2\",\"displayName\":\"Ops\"}]}"))
             .at("GET", "/v1.0/teams/T1/channels", c -> ok("{\"value\":[{\"id\":\"C1\",\"displayName\":\"General\",\"membershipType\":\"standard\"},{\"id\":\"C2\",\"displayName\":\"Month end\"}]}"))
             .at("GET", "/v1.0/teams/T1", c -> ok("{\"id\":\"T1\",\"displayName\":\"Finance\",\"visibility\":\"private\",\"isArchived\":false,\"webUrl\":\"https://teams/T1\"}"))
             .at("GET", "/v1.0/users/ann%40x.com", c -> ok("{\"id\":\"U1\",\"displayName\":\"Ann Lee\",\"mail\":\"ann@x.com\",\"jobTitle\":\"Controller\"}"))
             .at("GET", "/v1.0/me", c -> ok("{\"id\":\"ME1\"}"))
             .at("POST", "/v1.0/chats/CH1/messages", c -> new Object[]{201, "{\"id\":\"M9\"}"})
             .at("POST", "/v1.0/chats", c -> new Object[]{201, "{\"id\":\"CH2\"}"});
            ActionContext ctx = ctx(tmp());

            assertEquals(List.of("T1", "Finance", "Money"), rows(pkg.listTeams(ctx, tok, "me", b).get("table")).get(0));
            assertEquals("T1", pkg.teamByName(ctx, tok, " finance ", "me", b).get("team_id"));
            ActionException nf = assertThrows(ActionException.class, () -> pkg.teamByName(ctx, tok, "HR", "me", b));
            assertEquals("M365.NOT_FOUND", nf.code());
            assertTrue(nf.suggestedFix().contains("Finance, Ops"));
            assertEquals(2, rows(pkg.listChannels(ctx, tok, "T1", b).get("table")).size());
            assertEquals("C2", pkg.channelByName(ctx, tok, "T1", "MONTH END", b).get("channel_id"));
            @SuppressWarnings("unchecked") Map<String, Object> team = (Map<String, Object>) pkg.teamDetails(ctx, tok, "T1", b).get("team");
            assertEquals("private", team.get("visibility"));
            assertEquals(false, team.get("archived"));
            @SuppressWarnings("unchecked") Map<String, Object> user = (Map<String, Object>) pkg.getUser(ctx, tok, "ann@x.com", b).get("user");
            assertEquals("Controller", user.get("job_title"));
            assertEquals("ann@x.com", user.get("email"));

            assertEquals("M9", pkg.sendChatMessage(ctx, tok, "CH1", "<b>Done</b>", true, "normal", b).get("message_id"));
            assertEquals(Map.of("contentType", "html", "content", "<b>Done</b>"), f.last("POST", "/v1.0/chats/CH1/messages").json().get("body"));
            assertEquals("CH2", pkg.createChat(ctx, tok, "bob@x.com", b).get("chat_id"));
            Map<String, Object> chat = f.last("POST", "/v1.0/chats").json();
            assertEquals("oneOnOne", chat.get("chatType"));
            List<?> members = (List<?>) chat.get("members");
            assertEquals(b + "/users('ME1')", ((Map<?, ?>) members.get(0)).get("user@odata.bind"));
            assertEquals(b + "/users('bob@x.com')", ((Map<?, ?>) members.get(1)).get("user@odata.bind"));
        }
    }

    // ------------------------------------------------------------------------------------ SharePoint lists

    @Test void sharePointListsItemsPagingFilterAndCrud() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base(), site = "/v1.0/sites/contoso.sharepoint.com:/sites/Finance:", items = site + "/lists/Assets/items";
            f.at("GET", site + "/lists", c -> ok("{\"value\":[{\"id\":\"L1\",\"name\":\"Assets\",\"displayName\":\"Assets\",\"list\":{\"template\":\"genericList\"},\"webUrl\":\"https://sp/Assets\"}]}"))
             .at("GET", "/v1.0/sites/contoso.sharepoint.com,abc,def/lists", c -> ok("{\"value\":[]}"))
             .at("GET", items, c -> c.q().contains("skiptoken")
                    ? ok("{\"value\":[{\"id\":\"3\",\"fields\":{\"@odata.etag\":\"x\",\"Title\":\"Desk\",\"Owner\":\"Bob\"}}]}")
                    : ok("{\"value\":[{\"id\":\"1\",\"fields\":{\"@odata.etag\":\"x\",\"id\":\"1\",\"Title\":\"Laptop\",\"Status\":\"Open\"}},{\"id\":\"2\",\"fields\":{\"Title\":\"Phone\",\"Status\":\"Open\"}}],"
                       + "\"@odata.nextLink\":\"" + b + "/sites/contoso.sharepoint.com:/sites/Finance:/lists/Assets/items?$skiptoken=2\"}"))
             .at("POST", items, c -> new Object[]{201, "{\"id\":\"7\"}"})
             .at("PATCH", items + "/7/fields", c -> ok("{}"))
             .at("DELETE", items + "/7", c -> none());
            ActionContext ctx = ctx(tmp());
            String s = "contoso.sharepoint.com:/sites/Finance";

            assertEquals(List.of("L1", "Assets", "Assets", "genericList", "https://sp/Assets"), rows(pkg.listLists(ctx, tok, s, b).get("table")).get(0));
            assertTrue(rows(pkg.listLists(ctx, tok, "contoso.sharepoint.com,abc,def", b).get("table")).isEmpty(), "site id form");

            Map<String, Object> got = pkg.getListItems(ctx, tok, s, "Assets", "fields/Status eq 'Open'", null, 100, b);
            assertEquals(BigDecimal.valueOf(3), got.get("count"), "follows nextLink");
            assertEquals(List.of("id", "Title", "Status", "Owner"), tbl(got.get("table")).get("columns"), "union of fields, metadata dropped");
            assertEquals(Arrays.asList("3", "Desk", null, "Bob"), rows(got.get("table")).get(2));
            Call first = f.all("GET", Pattern.quote(items)).get(0);
            assertTrue(first.q().contains("$filter=fields/Status eq 'Open'") && first.q().contains("$expand=fields"));
            assertEquals("HonorNonIndexedQueriesWarningMayFailRandomly", first.header("Prefer"));

            Map<String, Object> picked = pkg.getListItems(ctx, tok, s, "Assets", null, List.of("Title"), 2, b);
            assertEquals(List.of("id", "Title"), tbl(picked.get("table")).get("columns"));
            assertEquals(BigDecimal.valueOf(2), picked.get("count"), "max_results stops paging");
            assertTrue(f.last("GET", items).q().contains("$expand=fields($select=Title)"));
            assertNull(f.last("GET", items).header("Prefer"));

            assertEquals("7", pkg.createListItem(ctx, tok, s, "Assets", Map.of("Title", "Monitor"), b).get("item_id"));
            assertEquals(Map.of("fields", Map.of("Title", "Monitor")), f.last("POST", items).json());
            pkg.updateListItem(ctx, tok, s, "Assets", "7", Map.of("Status", "Closed"), b);
            assertEquals(Map.of("Status", "Closed"), f.last("PATCH", items + "/7/fields").json());
            pkg.deleteListItem(ctx, tok, s, "Assets", "7", b);
            assertNotNull(f.last("DELETE", items + "/7"));
        }
    }

    // ------------------------------------------------------------------------------------ via the runner, errors, metadata

    @Test void runsInFlowsAndMapsGraphErrors() throws Exception {
        try (Fake f = new Fake()) {
            String b = f.base();
            f.at("PATCH", "/v1.0/me/messages/m1", c -> ok("{}"))
             .at("GET", "/v1.0/me/drive/root:/Fin/book.xlsx:/workbook/worksheets('Data')/range(address='A1')", c -> new Object[]{429, "{\"error\":{\"code\":\"TooManyRequests\",\"message\":\"Please retry after 10 seconds.\"}}"})
             .at("DELETE", "/v1.0/me/drive/root:/Gone.txt", c -> new Object[]{404, "{\"error\":{\"code\":\"itemNotFound\",\"message\":\"The resource could not be found.\"}}"})
             .at("GET", "/v1.0/sites/S1/lists/Assets/items", c -> new Object[]{503, "{\"error\":{\"message\":\"Service unavailable\"}}"});
            Map<String, Object> o = PackagesTest.run(tmp(), "[" + ConnectorsTest.TOKEN_VAR + ",{\"name\":\"n\",\"type\":\"Number\",\"direction\":\"output\"}]",
                    "[{\"id\":\"m\",\"action\":\"ooa.microsoft365/mark_mail\",\"args\":{\"access_token\":\"$tok\",\"base_url\":\"" + b + "\",\"id\":\"m1\",\"status\":\"unread\"}}]", ConnectorsTest.TOKEN);
            assertNotNull(o);
            assertEquals("Bearer tok-123", f.last("PATCH", "/v1.0/me/messages/m1").header("Authorization"), "vault credential used in a flow");

            RunResult r = ConnectorsTest.runRawWithToken(tmp(), "[" + ConnectorsTest.TOKEN_VAR + "]",
                    "[{\"id\":\"c\",\"action\":\"ooa.microsoft365/excel_get_cell\",\"args\":{\"access_token\":\"$tok\",\"base_url\":\"" + b + "\",\"remote_path\":\"Fin/book.xlsx\",\"sheet\":\"Data\",\"cell\":\"A1\"}}]");
            assertEquals("M365.RATE_LIMITED", r.error().get("code"));
            assertEquals(true, r.error().get("retryable"));
            assertTrue(String.valueOf(r.error().get("message")).contains("Please retry after 10 seconds"));

            ActionException nf = assertThrows(ActionException.class, () -> pkg.deleteItem(ctx(tmp()), tok, "Gone.txt", false, "me/drive", b));
            assertEquals("M365.NOT_FOUND", nf.code());
            assertFalse(nf.retryable());
            assertTrue(nf.getMessage().contains("could not be found"));
            ActionException down = assertThrows(ActionException.class, () -> pkg.getListItems(ctx(tmp()), tok, "S1", "Assets", null, null, 10, b));
            assertEquals("M365.SERVER_ERROR", down.code());
            assertTrue(down.retryable());
            assertEquals("M365.NO_TOKEN", assertThrows(ActionException.class, () -> pkg.deleteEvent(ctx(tmp()), "plain-text", "E1", "me", b)).code(), "tokens must come from the vault");
        }
    }

    static final Set<String> NEW_ACTIONS = Set.of("mark_mail", "delete_mail", "delete_all_mail", "move_all_mail", "forward_mail", "reply_mail", "reply_all_mail",
            "mail_folder_exists", "save_mail_as_eml", "copy_item", "move_item", "rename_item", "delete_item", "create_folder", "find_items", "get_item_info",
            "list_permissions", "check_permissions", "export_to_pdf", "restore_version", "excel_get_worksheets", "excel_add_worksheet", "excel_delete_worksheet",
            "excel_rename_worksheet", "excel_get_range", "excel_set_range", "excel_get_cell", "excel_set_cell", "excel_set_formula", "excel_read_formula",
            "excel_clear_range", "excel_delete_range", "excel_insert_range", "excel_autofit_columns", "excel_autofit_rows", "excel_format_range", "excel_used_range",
            "excel_read_row", "excel_read_column", "excel_list_tables", "excel_create_table", "excel_add_table_rows", "excel_get_table_rows", "excel_delete_table",
            "excel_write_data_table", "update_event", "cancel_event", "delete_event", "respond_to_event", "add_attendees", "remove_attendees", "add_event_attachment",
            "find_meeting_times", "list_teams", "get_team_by_name", "list_channels", "get_channel_by_name", "get_team_details", "get_user", "send_chat_message",
            "create_chat", "list_lists", "get_list_items", "create_list_item", "update_list_item", "delete_list_item");

    @Test void everyActionIsDescribedAndUsesVaultCredentials() {
        Set<String> ids = new HashSet<>();
        for (Method m : Microsoft365Package.class.getMethods()) {
            Action a = m.getAnnotation(Action.class);
            if (a == null) continue;
            assertTrue(ids.add(a.id()), "duplicate id " + a.id());
            assertFalse(a.description().isBlank(), a.id());
            assertFalse(a.description().matches("(?i).*\\bbots?\\b.*"), a.id() + " says bot");
            assertTrue(Arrays.asList(a.capabilities()).contains("network"), a.id());
            for (Parameter p : m.getParameters()) {
                Input in = p.getAnnotation(Input.class);
                if (in == null) continue;
                assertFalse(in.description().isBlank(), a.id() + "." + in.name());
                if (in.name().equals("access_token")) assertEquals(OoaType.CREDENTIAL, in.type(), a.id());
                if (!in.defaultValue().isEmpty()) assertDoesNotThrow(() -> JsonLite.parse(in.defaultValue()), a.id() + "." + in.name());
            }
            for (Output out : m.getAnnotationsByType(Output.class)) assertFalse(out.description().isBlank(), a.id() + " output " + out.name());
        }
        assertTrue(ids.containsAll(NEW_ACTIONS), "missing: " + NEW_ACTIONS.stream().filter(i -> !ids.contains(i)).toList());
        assertTrue(ids.containsAll(List.of("send_mail", "list_mail", "get_mail", "save_mail_attachments", "move_mail", "create_event", "list_events",
                "upload_file", "download_file", "list_files", "teams_message", "excel_read_range", "excel_write_range")), "existing actions kept");
    }
}
