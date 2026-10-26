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
