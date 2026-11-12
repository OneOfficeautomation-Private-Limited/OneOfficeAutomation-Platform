package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static org.junit.jupiter.api.Assertions.*;

/** New options of the Salesforce toolkit: reading a record by external id and uploading a new version of a file. */
class SalesforceMoreOptionsTest {
    static final SecureString TOK = new SecureString("tok-123", "test");
    static final String API = "/services/data/v61.0";
    final SalesforcePackage sf = new SalesforcePackage();

    static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(Files.createTempDirectory("ooa-sf"), Map.of()); }
    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }

    /** K6 (batch 10): values go into SOQL quoted and escaped, so O'Brien works and a value cannot change the query. */
    @Test void parametersAndFindRecordsQuoteValues() throws Exception {
        assertEquals("'O\\'Brien'", SalesforcePackage.literal("O'Brien"));
        assertEquals("'a\\\\b\\nc'", SalesforcePackage.literal("a\\b\nc"));
        assertEquals("('x', 2, true)", SalesforcePackage.literal(List.of("x", 2, true)));
        assertEquals("SELECT Id FROM Contact WHERE LastName = 'O\\'Brien' AND CreatedDate > LAST_N_DAYS:30 AND Note = ':name'",
                SalesforcePackage.bind("SELECT Id FROM Contact WHERE LastName = :name AND CreatedDate > LAST_N_DAYS:30 AND Note = ':name'", Map.of("name", "O'Brien")));
        assertEquals("SELECT Id FROM Account WHERE Name = 'x\\' OR Name != \\'y'",
                SalesforcePackage.bind("SELECT Id FROM Account WHERE Name = :n", Map.of("n", "x' OR Name != 'y")), "the value stays one text");
        try (Fake f = new Fake()) {
            f.on("GET " + API + "/query", c -> ok("{\"done\":true,\"totalSize\":1,\"records\":[{\"attributes\":{},\"Id\":\"003A\",\"Name\":\"Ann O'Brien\"}]}"));
            ActionContext ctx = ctx();
            Map<String, Object> r = sf.findRecords(ctx, f.url(), TOK, "Contact", new LinkedHashMap<>(Map.of("LastName", "O'Brien")), List.of("Id", "Name"), "CreatedDate desc", 10, false, "v61.0");
            assertEquals("SELECT Id, Name FROM Contact WHERE LastName = 'O\\'Brien' ORDER BY CreatedDate DESC LIMIT 10", r.get("soql"));
            assertEquals(java.math.BigDecimal.ONE, r.get("count"));
            assertTrue(java.net.URLDecoder.decode(q(f.last("GET", API + "/query")), java.nio.charset.StandardCharsets.UTF_8).contains("O\\'Brien"));
            assertEquals("SALESFORCE.BAD_FIELD", code(() -> sf.findRecords(ctx, f.url(), TOK, "Contact", Map.of("Name) FROM User --", "x"), null, null, 10, false, "v61.0")));
            assertEquals("SALESFORCE.BAD_ORDER", code(() -> sf.findRecords(ctx, f.url(), TOK, "Contact", null, null, "Name; DELETE", 10, false, "v61.0")));
        }
    }

    /** K7 (batch 10): many records in calls of 200 with a result per row; what an object has. */
    @Test void saveManyRecordsAndDescribe() throws Exception {
        try (Fake f = new Fake()) {
            List<Integer> sizes = new ArrayList<>();
            f.on("POST " + API + "/composite/sobjects", c -> {
                List<?> recs = (List<?>) c.json().get("records");
                sizes.add(recs.size());
                StringJoiner out = new StringJoiner(",", "[", "]");
                for (int i = 0; i < recs.size(); i++) out.add(i == 1 && sizes.size() == 1
                        ? "{\"success\":false,\"errors\":[{\"statusCode\":\"REQUIRED_FIELD_MISSING\",\"message\":\"Required fields are missing: [LastName]\"}]}"
                        : "{\"id\":\"003" + sizes.size() + i + "\",\"success\":true}");
                return ok(out.toString());
            })
             .on("PATCH " + API + "/composite/sobjects/Contact/ERP_Number__c", c -> ok("[{\"id\":\"003X\",\"success\":true,\"created\":false}]"))
             .on("GET " + API + "/sobjects/Contact/describe", c -> ok("{\"name\":\"Contact\",\"label\":\"Contact\",\"keyPrefix\":\"003\",\"createable\":true,"
                    + "\"fields\":[{\"name\":\"LastName\",\"label\":\"Last Name\",\"type\":\"string\",\"length\":80,\"createable\":true,\"nillable\":false,\"defaultedOnCreate\":false,\"updateable\":true},"
                    + "{\"name\":\"LeadSource\",\"label\":\"Lead Source\",\"type\":\"picklist\",\"createable\":true,\"nillable\":true,\"picklistValues\":[{\"value\":\"Web\",\"active\":true},{\"value\":\"Old\",\"active\":false}]}]}"));
            ActionContext ctx = ctx();
            List<Object> rows = new ArrayList<>();
            for (int i = 0; i < 201; i++) rows.add(new ArrayList<>(Arrays.asList("Lee" + i, "")));
            Map<String, Object> r = sf.saveRecords(ctx, f.url(), TOK, "Contact", Map.of("columns", List.of("LastName", "Email"), "rows", rows), "create", null, false, "v61.0");
            assertEquals(List.of(200, 1), sizes, "200 a call");
            assertEquals(new java.math.BigDecimal(200), r.get("succeeded"));
            assertEquals(java.math.BigDecimal.ONE, r.get("failed"));
            List<?> second = (List<?>) ((List<?>) ((Map<?, ?>) r.get("results")).get("rows")).get(1);
            assertEquals(Arrays.asList(new java.math.BigDecimal(2), null, false, null, "REQUIRED_FIELD_MISSING: Required fields are missing: [LastName]"), second);
            Map<?, ?> first = (Map<?, ?>) ((List<?>) f.calls.stream().filter(c -> c.path().endsWith("/composite/sobjects")).findFirst().orElseThrow().json().get("records")).get(0);
            assertEquals(Map.of("attributes", Map.of("type", "Contact"), "LastName", "Lee0"), first, "an empty cell is left out");

            sf.saveRecords(ctx, f.url(), TOK, "Contact", Map.of("columns", List.of("ERP_Number__c", "LastName"), "rows", List.of(List.of("E1", "Lee"))), "upsert", "ERP_Number__c", true, "v61.0");
            assertEquals(true, f.last("PATCH", API + "/composite/sobjects/Contact/ERP_Number__c").json().get("allOrNone"));
            assertEquals("SALESFORCE.NO_ID_COLUMN", code(() -> sf.saveRecords(ctx, f.url(), TOK, "Contact", Map.of("columns", List.of("LastName"), "rows", List.of()), "update", null, false, "v61.0")));

            Map<String, Object> d = sf.describeObject(ctx, f.url(), TOK, "Contact", "v61.0");
            List<?> fields = (List<?>) ((Map<?, ?>) d.get("fields")).get("rows");
            assertEquals(true, ((List<?>) fields.get(0)).get(4), "LastName is required");
            assertEquals(List.of("Web"), ((List<?>) fields.get(1)).get(6), "only active picklist values");
            assertEquals("003", ((Map<?, ?>) d.get("object")).get("key_prefix"));
        }
    }

    @Test void getRecordByExternalId() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET " + API + "/sobjects/Account/ERP_Number__c/.*", c -> c.path().endsWith("/DUP")
                    ? new Object[]{300, "[\"" + API + "/sobjects/Account/001000000000001AAA\",\"" + API + "/sobjects/Account/001000000000002AAA\"]"}
                    : ok("{\"attributes\":{\"type\":\"Account\"},\"Id\":\"001000000000001AAA\",\"Name\":\"Acme\",\"ERP_Number__c\":\"A/1\"}"))
             .on("GET " + API + "/sobjects/Account/001000000000001AAA", c -> ok("{\"attributes\":{},\"Id\":\"001000000000001AAA\",\"Name\":\"Acme\"}"));
            ActionContext ctx = ctx();
            Map<?, ?> rec = (Map<?, ?>) sf.get(ctx, f.url(), TOK, "Account", "A/1", List.of("Id", "Name"), "v61.0", "ERP_Number__c").get("record");
            assertEquals("Acme", rec.get("Name"));
            assertEquals("001000000000001AAA", rec.get("Id"));
            Call c = f.last("GET", API + "/sobjects/Account/ERP_Number__c/.*");
            assertTrue(c.path().endsWith("/ERP_Number__c/A%2F1"), "the value is encoded as one path segment: " + c.path());
            assertEquals("fields=Id,Name", q(c));
            assertEquals("Bearer tok-123", c.auth());

            Map<?, ?> byId = (Map<?, ?>) sf.get(ctx, f.url(), TOK, "Account", "001000000000001AAA", null, "v61.0").get("record");
            assertEquals("Acme", byId.get("Name"), "the earlier call shape still reads by Salesforce id");

            assertEquals("SALESFORCE.AMBIGUOUS_EXTERNAL_ID", code(() -> sf.get(ctx, f.url(), TOK, "Account", "DUP", null, "v61.0", "ERP_Number__c")));
            int before = f.calls.size();
            assertEquals("SALESFORCE.BAD_OBJECT", code(() -> sf.get(ctx, f.url(), TOK, "Account", "1", null, "v61.0", "ERP Number")));
            assertEquals("SALESFORCE.NO_EXTERNAL_ID", code(() -> sf.get(ctx, f.url(), TOK, "Account", " ", null, "v61.0", "ERP_Number__c")));
            assertEquals("SALESFORCE.BAD_ID", code(() -> sf.get(ctx, f.url(), TOK, "Account", "A/1", null, "v61.0", null)), "without the field, id must be a Salesforce id");
            assertEquals(before, f.calls.size(), "bad input is refused before any request");
        }
    }

    @Test void uploadANewVersionOfAnExistingFile() throws Exception {
        try (Fake f = new Fake()) {
            String doc = "069000000000001AAA", rec = "001000000000001AAA";
            f.on("POST " + API + "/sobjects/ContentVersion", c -> new Object[]{201, "{\"id\":\"068000000000003AAA\",\"success\":true}"});
            ActionContext ctx = ctx();
            Files.writeString(ctx.resolvePath("report.csv"), "a,b,c");
            Map<String, Object> r = sf.upload(ctx, f.url(), TOK, null, "report.csv", "Monthly report", "v61.0", null, "March figures", doc);
            assertEquals("068000000000003AAA", r.get("content_version_id"));
            assertEquals(doc, r.get("content_document_id"));
            Map<String, Object> body = f.last("POST", API + "/sobjects/ContentVersion").json();
            assertEquals(doc, body.get("ContentDocumentId"));
            assertFalse(body.containsKey("FirstPublishLocationId"), "a new version keeps the file's existing links");
            assertEquals("Monthly report", body.get("Title"));
            assertEquals("report.csv", body.get("PathOnClient"));
            assertEquals("March figures", body.get("Description"));
            assertEquals(Base64.getEncoder().encodeToString("a,b,c".getBytes()), body.get("VersionData"));
            assertEquals(1, f.calls.size(), "no extra link request for a new version");

            sf.upload(ctx, f.url(), TOK, rec, "report.csv", null, "v61.0", null, null);
            Map<String, Object> first = f.last("POST", API + "/sobjects/ContentVersion").json();
            assertEquals(rec, first.get("FirstPublishLocationId"), "the earlier call shape still attaches to the record");
            assertFalse(first.containsKey("ContentDocumentId"));

            int before = f.calls.size();
            assertEquals("SALESFORCE.BAD_ID", code(() -> sf.upload(ctx, f.url(), TOK, null, "report.csv", null, "v61.0", null, null, rec)), "an id that is not a file is refused");
            assertEquals("SALESFORCE.VISIBILITY_WITH_VERSION", code(() -> sf.upload(ctx, f.url(), TOK, null, "report.csv", null, "v61.0", "AllUsers", null, doc)));
            assertEquals("SALESFORCE.NO_RECORD", code(() -> sf.upload(ctx, f.url(), TOK, " ", "report.csv", null, "v61.0", null, null, null)));
            assertEquals(before, f.calls.size(), "bad input is refused before any request");
        }
    }
}
