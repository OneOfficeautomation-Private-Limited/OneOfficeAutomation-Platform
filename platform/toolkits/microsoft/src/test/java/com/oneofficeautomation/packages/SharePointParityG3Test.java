package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.Microsoft365GapsTest.Fake;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.Microsoft365GapsTest.ok;
import static com.oneofficeautomation.packages.Microsoft365GapsTest.none;
import static com.oneofficeautomation.packages.PackagesTest.tmp;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G3 (AA parity, 22 Nov 2026), SharePoint against a fake server: a site signed in to once and reached through its own
 * REST API (items, lists and the four attachment steps), and on Microsoft Graph new lists from templates, column types
 * and items picked by field values.
 */
class SharePointParityG3Test {
    final Microsoft365Package pkg = new Microsoft365Package();
    final SecureString tok = new SecureString("tok-123", "t");

    @SuppressWarnings("unchecked") static List<List<Object>> rows(Object t) { return (List<List<Object>>) ((Map<String, Object>) t).get("rows"); }

    @Test void aSiteSessionReachesItemsListsAndAttachmentsThroughRest() throws Exception {
        try (Fake f = new Fake()) {
            String api = "/sites/Fin/_api/web";
            String tasks = api + "/lists/getbytitle\\('Tasks'\\)";
            f.at("GET", api, c -> ok("{\"Title\":\"Finance\"}"))
             .rx("GET", tasks + "/items", c -> ok("{\"value\":[{\"Id\":1,\"Title\":\"Laptop\",\"Status\":\"Open\"}]}"))
             .rx("GET", tasks, c -> ok("{\"ListItemEntityTypeFullName\":\"SP.Data.TasksListItem\"}"))
             .rx("POST", tasks + "/items", c -> ok("{\"Id\":7}"))
             .rx("POST", tasks + "/items\\(7\\)", c -> none())
             .rx("GET", tasks + "/items\\(7\\)/AttachmentFiles", c -> ok("{\"value\":[{\"FileName\":\"photo.png\",\"ServerRelativeUrl\":\"/sites/Fin/Lists/Tasks/Attachments/7/photo.png\"}]}"))
             .rx("POST", tasks + "/items\\(7\\)/AttachmentFiles/add.*", c -> ok("{}"))
             .rx("POST", tasks + "/items\\(7\\)/AttachmentFiles\\('photo.png'\\)", c -> none())
             .rx("GET", ".*/GetFileByServerRelativeUrl.*", c -> new Object[]{200, "PNGDATA".getBytes(StandardCharsets.UTF_8)})
             .rx("POST", tasks + "/items\\(7\\)/recycle\\(\\)", c -> ok("{}"))
             .rx("GET", api + "/lists", c -> ok("{\"value\":[{\"Id\":\"L1\",\"Title\":\"Tasks\",\"BaseTemplate\":107,\"RootFolder\":{\"Name\":\"Tasks\",\"ServerRelativeUrl\":\"/sites/Fin/Lists/Tasks\"}}]}"))
             .rx("POST", api + "/lists", c -> ok("{\"Id\":\"L9\"}"));
            Path w = tmp();
            ActionContext ctx = Microsoft365GapsTest.ctx(w);

            assertEquals("M365.BAD_SITE", assertThrows(ActionException.class, () -> pkg.sharepointConnect(ctx, "contoso", tok, null, null, null, null, false)).code());
            assertEquals("Finance", pkg.sharepointConnect(ctx, f.url() + "/sites/Fin/", tok, null, null, null, null, false).get("title"));
            assertEquals("Bearer tok-123", f.last("GET", api).header("Authorization"));

            Map<String, Object> got = pkg.getListItems(ctx, null, null, "Tasks", "Priority gt 1", null, 100, null, false, "sharepoint", new LinkedHashMap<>(Map.of("Status", "Open")));
            assertEquals(List.of(List.of("1", "Laptop", "Open")), rows(got.get("table")));
            String q = f.all("GET", tasks + "/items").get(0).q();
            assertTrue(q.contains("$filter=Status eq 'Open' and (Priority gt 1)"), q);

            assertEquals("7", pkg.createListItem(ctx, null, null, "Tasks", Map.of("Title", "Desk"), null, "sharepoint").get("item_id"));
            Map<String, Object> body = f.last("POST", "/sites/Fin/_api/web/lists/getbytitle('Tasks')/items").json();
            assertEquals("SP.Data.TasksListItem", ((Map<?, ?>) body.get("__metadata")).get("type"));
            assertEquals("Desk", body.get("Title"));
            pkg.updateListItem(ctx, null, null, "Tasks", "7", Map.of("Status", "Done"), null, "sharepoint");
            assertEquals("MERGE", f.last("POST", "/sites/Fin/_api/web/lists/getbytitle('Tasks')/items(7)").header("X-HTTP-Method"));

            // the four attachment steps
            assertEquals(List.of("photo.png"), pkg.listAttachments(ctx, "sharepoint", "Tasks", "7").get("names"));
            Files.writeString(w.resolve("receipt.pdf"), "PDF");
            pkg.addAttachment(ctx, "sharepoint", "Tasks", "7", "receipt.pdf", null);
            var add = f.all("POST", ".*/AttachmentFiles/add.*").get(0);
            assertTrue(add.path().endsWith("add(FileName='receipt.pdf')"), add.path());
            assertEquals("PDF", add.text());
            List<Object> files = (List<Object>) pkg.downloadAttachments(ctx, "sharepoint", "Tasks", "7", "att", null, false).get("files");
            assertEquals("PNGDATA", Files.readString(Path.of(files.get(0).toString())));
            assertEquals("M365.NO_ATTACHMENT", assertThrows(ActionException.class, () -> pkg.downloadAttachments(ctx, "sharepoint", "Tasks", "7", "att", "other.png", false)).code());
            pkg.deleteAttachment(ctx, "sharepoint", "Tasks", "7", "photo.png");
            assertEquals("DELETE", f.last("POST", "/sites/Fin/_api/web/lists/getbytitle('Tasks')/items(7)/AttachmentFiles('photo.png')").header("X-HTTP-Method"));
            pkg.deleteListItem(ctx, null, null, "Tasks", "7", null, "sharepoint");

            // lists
            assertEquals(List.of(List.of("L1", "Tasks", "Tasks", "107", f.url() + "/sites/Fin/Lists/Tasks")), rows(pkg.listLists(ctx, null, null, null, "sharepoint").get("table")));
            assertEquals("L9", pkg.createList(ctx, null, null, "Requests", "tasks", null, null, "sharepoint").get("list_id"));
            assertEquals(107, ((Number) f.last("POST", api + "/lists").json().get("BaseTemplate")).intValue());
            assertEquals("M365.NO_SESSION", assertThrows(ActionException.class, () -> pkg.listLists(ctx, null, null, null, "other")).code());
        }
    }

    @Test void graphListsColumnsAndFieldMatches() throws Exception {
        try (Fake f = new Fake()) {
            String site = "/v1.0/sites/site1";
            f.at("POST", site + "/lists", c -> ok("{\"id\":\"L2\",\"webUrl\":\"https://x/Lists/Requests\"}"))
             .at("PATCH", site + "/lists/L2", c -> ok("{}"))
             .at("DELETE", site + "/lists/L2", c -> none())
             .at("POST", site + "/lists/L2/columns", c -> ok("{\"id\":\"C1\"}"))
             .at("GET", site + "/lists/L2/items", c -> ok("{\"value\":[]}"));
            ActionContext ctx = Microsoft365GapsTest.ctx(tmp());
            assertEquals("L2", pkg.createList(ctx, tok, "site1", "Requests", "issueTracking", "Asks", f.base(), null).get("list_id"));
            assertEquals("issueTracking", ((Map<?, ?>) f.last("POST", site + "/lists").json().get("list")).get("template"));
            assertEquals("M365.BAD_TEMPLATE", assertThrows(ActionException.class, () -> pkg.createList(ctx, tok, "site1", "X", "nope", null, f.base(), null)).code());
            pkg.updateList(ctx, tok, "site1", "L2", "Requests 2026", null, f.base(), null);
            assertEquals(Map.of("displayName", "Requests 2026"), f.last("PATCH", site + "/lists/L2").json());
            assertEquals("C1", pkg.addListColumn(ctx, tok, "site1", "L2", "Status", "choice", List.of("Open", "Closed"), true, null, null, null, f.base()).get("column_id"));
            Map<String, Object> col = f.last("POST", site + "/lists/L2/columns").json();
            assertEquals(List.of("Open", "Closed"), ((Map<?, ?>) col.get("choice")).get("choices"));
            assertEquals(true, col.get("required"));
            assertEquals("M365.BAD_INPUT", assertThrows(ActionException.class, () -> pkg.addListColumn(ctx, tok, "site1", "L2", "S", "choice", null, false, null, null, null, f.base())).code());
            pkg.getListItems(ctx, tok, "site1", "L2", null, null, 10, f.base(), false, null, orderedMatch());
            assertTrue(f.last("GET", site + "/lists/L2/items").q().contains("$filter=fields/Status eq 'Open' and fields/Priority eq 2"), f.last("GET", site + "/lists/L2/items").q());
            pkg.deleteList(ctx, tok, "site1", "L2", f.base(), null);
            assertFalse(f.all("DELETE", site + "/lists/L2").isEmpty());
        }
    }

    @Test void matchesBecomeAFilter() {
        assertNull(Microsoft365Package.withMatch(null, null, ""));
        assertEquals("Title eq 'O''Neil' and Done eq true", Microsoft365Package.withMatch(" ", new LinkedHashMap<>(Map.of("Title", "O'Neil")) {{ put("Done", true); }}, ""));
        assertEquals("x eq 1", Microsoft365Package.withMatch("x eq 1", null, "fields/"));
    }
    /** Status first, then Priority: the filter keeps the order the step gives. */
    static Map<String, Object> orderedMatch() { Map<String, Object> m = new LinkedHashMap<>(); m.put("Status", "Open"); m.put("Priority", 2); return m; }
}
