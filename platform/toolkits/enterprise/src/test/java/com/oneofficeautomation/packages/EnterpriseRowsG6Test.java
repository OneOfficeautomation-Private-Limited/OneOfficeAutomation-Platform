package com.oneofficeautomation.packages;

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

/** G6 rows of the enterprise toolkits: Salesforce sign-in site and files, ServiceNow attachments and API version, Okta fields. */
class EnterpriseRowsG6Test {
    static final SecureString TOK = new SecureString("tok-123", "test");
    static final String API = "/services/data/v61.0";
    static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(Files.createTempDirectory("ooa-g6e"), Map.of()); }
    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }

    @Test void salesforceSignInSite() {
        assertEquals("https://login.salesforce.com", SalesforcePackage.loginRoot("production", "https://ignored.example.com"));
        assertEquals("https://test.salesforce.com", SalesforcePackage.loginRoot("sandbox", null));
        assertEquals("https://acme.my.salesforce.com", SalesforcePackage.loginRoot("custom", "https://acme.my.salesforce.com/"));
        assertEquals("https://acme.my.salesforce.com", SalesforcePackage.loginRoot(null, "https://acme.my.salesforce.com"), "older steps keep their login URL");
        assertEquals("https://login.salesforce.com", SalesforcePackage.loginRoot("", ""));
        assertEquals("SALESFORCE.NO_LOGIN_URL", code(() -> SalesforcePackage.loginRoot("custom", " ")));
        assertEquals("SALESFORCE.BAD_LOGIN_SITE", code(() -> SalesforcePackage.loginRoot("staging", null)));
    }

    @Test void salesforceSignInUsesTheCustomSite() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /services/oauth2/token", c -> ok("{\"access_token\":\"00D!s\",\"instance_url\":\"" + f.url() + "\"}"));
            new SalesforcePackage().authenticate(ctx(), f.url(), null, "3MVG9", new SecureString("cs", "t"), null, null, null, null, false, "custom");
            assertNotNull(f.last("POST", "/services/oauth2/token"));
        }
    }

    @Test void salesforceOneFileByIdAndExtensionFilter() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET " + API + "/query", c -> {
                String soql = q(c);
                if (soql.contains("FROM ContentDocument WHERE Id = '069000000000001AAA'"))
                    return ok("{\"done\":true,\"records\":[{\"Id\":\"069000000000001AAA\",\"Title\":\"Invoice\",\"FileExtension\":\"pdf\",\"ContentSize\":3,\"LatestPublishedVersionId\":\"068000000000001AAA\"}]}");
                if (soql.contains("FROM ContentDocument WHERE"))
                    return ok("{\"done\":true,\"records\":[]}");
                if (soql.contains("ContentDocumentLink"))
                    return ok("{\"done\":true,\"records\":[{\"ContentDocumentId\":\"069A\",\"ContentDocument\":{\"Title\":\"a\",\"FileExtension\":\"pdf\",\"ContentSize\":1,\"LatestPublishedVersionId\":\"068A\"}},"
                            + "{\"ContentDocumentId\":\"069B\",\"ContentDocument\":{\"Title\":\"b\",\"FileExtension\":\"DOCX\",\"ContentSize\":1,\"LatestPublishedVersionId\":\"068B\"}},"
                            + "{\"ContentDocumentId\":\"069C\",\"ContentDocument\":{\"Title\":\"c\",\"FileExtension\":\"txt\",\"ContentSize\":1,\"LatestPublishedVersionId\":\"068C\"}}]}");
                return ok("{\"done\":true,\"records\":[]}");
            }).on("GET " + API + "/sobjects/ContentVersion/068000000000001AAA/VersionData", c -> new Object[]{200, "PDF".getBytes()});
            SalesforcePackage sf = new SalesforcePackage();
            ActionContext ctx = ctx();
            Map<String, Object> r = sf.attachments(ctx, f.url(), TOK, null, "files", "v61.0", "all", null, true, "069000000000001AAA", null);
            assertEquals(java.math.BigDecimal.ONE, r.get("count"));
            Path saved = Path.of((String) r.get("path"));
            assertEquals("Invoice.pdf", saved.getFileName().toString());
            assertEquals("PDF", Files.readString(saved));

            Map<String, Object> l = sf.attachments(ctx, f.url(), TOK, "001000000000001AAA", null, "v61.0", "file", null, true, null, "pdf, .docx");
            assertEquals(new java.math.BigDecimal(2), l.get("count"), "pdf and docx, case ignored");
            assertNull(l.get("path"));
            assertEquals(Set.of("pdf", "docx"), SalesforcePackage.extensions(" .PDF; docx"));
            assertEquals("SALESFORCE.BAD_EXTENSION", code(() -> SalesforcePackage.extensions("p/df")));
            assertEquals("SALESFORCE.FILE_NOT_FOUND", code(() -> sf.attachments(ctx, f.url(), TOK, null, null, "v61.0", "all", null, true, "069000000000009AAA", null)));
            assertEquals("SALESFORCE.NO_RECORD", code(() -> sf.attachments(ctx, f.url(), TOK, null, null, "v61.0", "all", null, true, null, null)));
        }
    }

    @Test void salesforceNewVersionLeavesVisibilityOut() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST " + API + "/sobjects/ContentVersion", c -> new Object[]{201, "{\"id\":\"068000000000003AAA\",\"success\":true}"});
            ActionContext ctx = ctx();
            Files.writeString(ctx.resolvePath("r.csv"), "a");
            Map<String, Object> r = new SalesforcePackage().upload(ctx, f.url(), TOK, null, "r.csv", null, "v61.0", "AllUsers", null, "069000000000001AAA");
            assertEquals("069000000000001AAA", r.get("content_document_id"));
            assertEquals(1, f.calls.size(), "no link request: a new version keeps the file's sharing");
        }
    }

    @Test void serviceNowFindsAttachmentsThreeWaysAndSavesThem() throws Exception {
        String a1 = "a".repeat(32), a2 = "b".repeat(32), rec = "c".repeat(32);
        try (Fake f = new Fake()) {
            f.on("GET /api/now/v2/attachment", c -> ok("{\"result\":[{\"sys_id\":\"" + a1 + "\",\"file_name\":\"x.txt\"},{\"sys_id\":\"" + a2 + "\",\"file_name\":\"x.txt\"}]}"))
             .on("GET /api/now/attachment", c -> ok("{\"result\":[]}"))
             .on("GET /api/now/attachment/" + a1, c -> ok("{\"result\":{\"sys_id\":\"" + a1 + "\",\"file_name\":\"one.txt\"}}"))
             .on("GET /api/now/(v2/)?attachment/[ab]+/file", c -> new Object[]{200, ("data-" + c.path().charAt(c.path().length() - 6)).getBytes()})
             .on("GET /api/now/v1/table/incident", c -> ok("{\"result\":[]}"));
            ServiceNowPackage sn = new ServiceNowPackage();
            ActionContext ctx = ctx();
            Map<String, Object> r = sn.listAttachments(ctx, f.url(), null, null, "u", TOK, null, null, "table_name=incident", 5, true, "saved", "v2");
            assertEquals("table_name=incident", q(f.last("GET", "/api/now/v2/attachment")).replaceAll(".*sysparm_query=([^&]*).*", "$1"));
            assertTrue(q(f.last("GET", "/api/now/v2/attachment")).contains("sysparm_limit=5"));
            List<?> files = (List<?>) r.get("files");
            assertEquals(2, files.size());
            assertEquals(List.of("x.txt", "2_x.txt"), files.stream().map(p -> Path.of((String) p).getFileName().toString()).toList(), "same names are both kept");

            Map<String, Object> one = sn.listAttachments(ctx, f.url(), null, null, "u", TOK, null, a1, null, 1000, true, null, null);
            Path tmp = Path.of((String) ((List<?>) one.get("files")).get(0));
            assertEquals("one.txt", tmp.getFileName().toString());
            assertTrue(tmp.toString().contains(".ooa-temp"), "an empty folder means the run's temporary folder: " + tmp);

            Map<String, Object> byRecord = sn.listAttachments(ctx, f.url(), "incident", rec, "u", TOK, null);
            assertEquals(List.of(), byRecord.get("files"));
            assertTrue(q(f.last("GET", "/api/now/attachment")).contains("table_name=incident^table_sys_id=" + rec));
            assertEquals("SERVICENOW.NO_ATTACHMENT_FILTER", code(() -> sn.listAttachments(ctx, f.url(), null, null, "u", TOK, null)));

            sn.query(ctx, f.url(), "incident", null, null, 10, "u", TOK, null, "true", null, 0, true, null, "v1");
            assertNotNull(f.last("GET", "/api/now/v1/table/incident"));
            assertEquals("SERVICENOW.BAD_API_VERSION", code(() -> ServiceNowPackage.api("https://x", "v2/../x")));
            assertEquals("https://x/api/now", ServiceNowPackage.api("https://x", "latest"));
        }
    }

    @Test void oktaNamedFieldsAndGroupDone() throws Exception {
        String user = "{\"id\":\"00uABCDEFGHIJKLMNOPQ\",\"status\":\"ACTIVE\",\"profile\":{\"login\":\"ann@acme.com\"}}";
        try (Fake f = new Fake()) {
            f.on("POST /api/v1/users", c -> ok(user))
             .on("POST /api/v1/users/ann%40acme.com", c -> ok(user))
             .on("GET /api/v1/users/ann%40acme.com", c -> ok(user))
             .on("PUT /api/v1/groups/00gABCDEFGHIJKLMNOPQ/users/00uABCDEFGHIJKLMNOPQ", c -> new Object[]{204, ""})
             .on("DELETE /api/v1/groups/00gABCDEFGHIJKLMNOPQ/users/00uABCDEFGHIJKLMNOPQ", c -> new Object[]{204, ""});
            OktaPackage okta = new OktaPackage();
            ActionContext ctx = ctx();
            SecureString key = new SecureString("api", "t");
            okta.createUser(ctx, f.url(), "Ann", "Lee", "ann@acme.com", null, null, null, null, true, key, null, "+1 555 0100");
            assertEquals("+1 555 0100", ((Map<?, ?>) f.last("POST", "/api/v1/users").json().get("profile")).get("mobilePhone"));

            okta.updateUser(ctx, f.url(), "ann@acme.com", Map.of("title", "Lead"), key, null, null, "ann.lee@acme.com", null, "Lee-Smith", "+1 555 0101");
            assertEquals(Map.of("email", "ann.lee@acme.com", "lastName", "Lee-Smith", "mobilePhone", "+1 555 0101", "title", "Lead"),
                    f.last("POST", "/api/v1/users/ann%40acme.com").json().get("profile"));
            assertEquals("OKTA.NOTHING_TO_UPDATE", code(() -> okta.updateUser(ctx, f.url(), "ann@acme.com", null, key, null)));

            assertEquals(Map.of("done", true), okta.addToGroup(ctx, f.url(), "ann@acme.com", "00gABCDEFGHIJKLMNOPQ", key, null));
            assertEquals(Map.of("done", true), okta.removeFromGroup(ctx, f.url(), "ann@acme.com", "00gABCDEFGHIJKLMNOPQ", key, null));
        }
    }
}
