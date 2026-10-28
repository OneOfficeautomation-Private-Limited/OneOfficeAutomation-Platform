package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import javax.naming.*;
import javax.naming.directory.*;
import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Consumer;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static org.junit.jupiter.api.Assertions.*;

/** New actions of the enterprise application toolkits, against fake services, a fake terminal engine, captured SAP scripts and an in-memory directory. */
class EnterpriseGapsTest {
    static final SecureString TOK = new SecureString("tok-123", "test");

    static ActionContext ctx() throws Exception { return PackagesTestSupport.ctx(Files.createTempDirectory("ooa-gaps"), Map.of()); }
    static String form(Call c) { return java.net.URLDecoder.decode(c.text(), StandardCharsets.UTF_8); }
    @SuppressWarnings("unchecked") static List<List<Object>> rows(Object table) { return (List<List<Object>>) ((Map<String, Object>) table).get("rows"); }
    @SuppressWarnings("unchecked") static List<String> cols(Object table) { return (List<String>) ((Map<String, Object>) table).get("columns"); }

    // ---------------------------------------------------------------- Jira

    @Test void jiraCommentsIssuesAndProjects() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /rest/api/2/issue/OPS-7/comment", c -> q(c).contains("startAt=0")
                    ? ok("{\"total\":3,\"comments\":[{\"id\":\"10\",\"author\":{\"displayName\":\"Ann\"},\"body\":\"first\",\"created\":\"2026-10-01\"},{\"id\":\"11\",\"author\":{\"displayName\":\"Bob\"},\"body\":\"second\"}]}")
                    : ok("{\"total\":3,\"comments\":[{\"id\":\"12\",\"body\":\"third\"}]}"))
             .on("PUT /rest/api/2/issue/OPS-7/comment/10", c -> ok("{\"id\":\"10\"}"))
             .on("DELETE /rest/api/2/issue/OPS-7/comment/11", c -> new Object[]{204, ""})
             .on("PUT /rest/api/2/issue/OPS-7", c -> new Object[]{204, ""})
             .on("DELETE /rest/api/2/issue/OPS-7", c -> new Object[]{204, ""})
             .on("POST /rest/api/2/project", c -> new Object[]{201, "{\"id\":10000,\"key\":\"NEW\"}"})
             .on("GET /rest/api/2/project", c -> ok("[{\"id\":\"1\",\"key\":\"OPS\",\"name\":\"Operations\",\"projectTypeKey\":\"software\",\"lead\":{\"displayName\":\"Ann\"}}]"))
             .on("PUT /rest/api/2/project/NEW", c -> ok("{}"))
             .on("DELETE /rest/api/2/project/NEW", c -> new Object[]{204, ""});
            var j = new JiraPackage();
            var ctx = ctx();
            String u = f.url();
            Map<String, Object> cm = j.getComments(ctx, u, "OPS-7", 500, "bot@x.com", TOK, null);
            assertEquals(new BigDecimal(3), cm.get("count"));
            assertEquals(Arrays.asList("10", "Ann", "2026-10-01", null, "first"), rows(cm.get("table")).get(0));
            assertTrue(f.last("GET", "/rest/api/2/issue/OPS-7/comment").auth().startsWith("Basic "));
            j.updateComment(ctx, u, "OPS-7", "10", "edited", "bot@x.com", TOK, null);
            assertEquals("{\"body\":\"edited\"}", f.last("PUT", "/rest/api/2/issue/OPS-7/comment/10").text());
            j.deleteComment(ctx, u, "OPS-7", "11", null, null, TOK);
            assertEquals("Bearer tok-123", f.last("DELETE", "/rest/api/2/issue/OPS-7/comment/11").auth());
            assertEquals("JIRA.BAD_COMMENT_ID", assertThrows(ActionException.class, () -> j.deleteComment(ctx, u, "OPS-7", "1 OR 1", null, null, TOK)).code());
            j.updateIssue(ctx, u, "OPS-7", Map.of("summary", "Disk full on db01"), false, null, null, TOK);
            Call up = f.last("PUT", "/rest/api/2/issue/OPS-7");
            assertEquals("{\"fields\":{\"summary\":\"Disk full on db01\"}}", up.text());
            assertEquals("notifyUsers=false", up.query());
            assertEquals("JIRA.NO_FIELDS", assertThrows(ActionException.class, () -> j.updateIssue(ctx, u, "OPS-7", Map.of(), true, null, null, TOK)).code());
            j.deleteIssue(ctx, u, "OPS-7", true, null, null, TOK);
            assertEquals("deleteSubtasks=true", f.last("DELETE", "/rest/api/2/issue/OPS-7").query());
            Map<String, Object> p = j.createProject(ctx, u, "NEW", "New team", "software", "5b10a2844c20165700ede21g", null, null, "Team space", null, null, TOK);
            assertEquals("10000", p.get("id"));
            String body = f.last("POST", "/rest/api/2/project").text();
            assertTrue(body.contains("\"leadAccountId\":\"5b10a2844c20165700ede21g\"") && body.contains("\"projectTypeKey\":\"software\""), body);
            assertEquals("JIRA.NO_LEAD", assertThrows(ActionException.class, () -> j.createProject(ctx, u, "NEW", "x", "software", null, null, null, null, null, null, TOK)).code());
            assertEquals("JIRA.BAD_PROJECT_KEY", assertThrows(ActionException.class, () -> j.deleteProject(ctx, u, "../x", null, null, TOK)).code());
            Map<String, Object> ps = j.getProjects(ctx, u, null, null, TOK);
            assertEquals(List.of("1", "OPS", "Operations", "software", "Ann"), rows(ps.get("table")).get(0));
            j.updateProject(ctx, u, "NEW", Map.of("name", "Renamed"), null, null, TOK);
            assertEquals("{\"name\":\"Renamed\"}", f.last("PUT", "/rest/api/2/project/NEW").text());
            j.deleteProject(ctx, u, "NEW", null, null, TOK);
            assertNotNull(f.last("DELETE", "/rest/api/2/project/NEW"));
        }
    }

    // ---------------------------------------------------------------- Slack

    @Test void slackDirectMessagesAndLookups() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /api/users.lookupByEmail", c -> form(c).contains("email=ann@example.com")
                    ? ok("{\"ok\":true,\"user\":{\"id\":\"U111\",\"name\":\"ann\",\"real_name\":\"Ann Lee\",\"tz\":\"Europe/Berlin\",\"profile\":{\"email\":\"ann@example.com\",\"display_name\":\"ann\",\"title\":\"Ops\"}}}")
                    : ok("{\"ok\":false,\"error\":\"users_not_found\"}"))
             .on("POST /api/conversations.open", c -> ok("{\"ok\":true,\"channel\":{\"id\":\"D999\"}}"))
             .on("POST /api/chat.postMessage", c -> ok("{\"ok\":true,\"ts\":\"1700.5\"}"))
             .on("POST /api/conversations.list", c -> form(c).contains("cursor=c2")
                    ? ok("{\"ok\":true,\"channels\":[{\"id\":\"C42\",\"name\":\"ops-alerts\"}]}")
                    : ok("{\"ok\":true,\"channels\":[{\"id\":\"C1\",\"name\":\"general\"}],\"response_metadata\":{\"next_cursor\":\"c2\"}}"))
             .on("POST /api/conversations.info", c -> ok("{\"ok\":true,\"channel\":{\"id\":\"C42\",\"name\":\"ops-alerts\",\"is_private\":false,\"num_members\":7,\"topic\":{\"value\":\"Pager\"},\"purpose\":{\"value\":\"Alerts\"}}}"))
             .on("POST /api/conversations.history", c -> form(c).contains("cursor=h2")
                    ? ok("{\"ok\":true,\"messages\":[{\"ts\":\"3.0\",\"user\":\"U2\",\"text\":\"three\"}],\"has_more\":false}")
                    : ok("{\"ok\":true,\"messages\":[{\"ts\":\"5.0\",\"user\":\"U1\",\"text\":\"five\",\"reply_count\":2},{\"ts\":\"4.0\",\"user\":\"U1\",\"text\":\"four\"}],\"has_more\":true,\"response_metadata\":{\"next_cursor\":\"h2\"}}"))
             .on("POST /api/users.info", c -> ok("{\"ok\":true,\"user\":{\"id\":\"U222\",\"name\":\"bob\",\"is_bot\":false,\"profile\":{\"real_name\":\"Bob Ray\",\"email\":\"bob@example.com\"}}}"))
             .on("POST /api/team.info", c -> ok("{\"ok\":true,\"team\":{\"id\":\"T1\",\"name\":\"Acme\",\"domain\":\"acme\",\"email_domain\":\"acme.com\"}}"));
            var sl = new SlackPackage();
            var ctx = ctx();
            String base = f.url() + "/api";
            Map<String, Object> dm = sl.sendDirect(ctx, TOK, "ann@example.com", "Your report is ready", null, null, null, null, true, null, base);
            assertEquals("1700.5", dm.get("ts"));
            assertEquals("D999", dm.get("channel"));
            assertEquals("{\"users\":\"U111\"}", f.last("POST", "/api/conversations.open").text());
            assertTrue(f.last("POST", "/api/chat.postMessage").text().contains("\"channel\":\"D999\""));
            assertTrue(f.last("POST", "/api/users.lookupByEmail").contentType().startsWith("application/x-www-form-urlencoded"));
            assertEquals("SLACK.USERS_NOT_FOUND", assertThrows(ActionException.class, () -> sl.sendDirect(ctx, TOK, "nobody@example.com", "x", null, null, null, null, true, null, base)).code());
            assertEquals("SLACK.BAD_USER", assertThrows(ActionException.class, () -> sl.sendDirect(ctx, TOK, "bob smith", "x", null, null, null, null, true, null, base)).code());
            assertEquals("C42", sl.channelId(ctx, TOK, "#ops-alerts", true, false, base).get("channel_id"));
            assertTrue(form(f.last("POST", "/api/conversations.list")).contains("types=public_channel,private_channel"));
            assertEquals("SLACK.CHANNEL_NOT_FOUND", assertThrows(ActionException.class, () -> sl.channelId(ctx, TOK, "missing", true, false, base)).code());
            @SuppressWarnings("unchecked") Map<String, Object> ch = (Map<String, Object>) sl.channelDetails(ctx, TOK, "C42", base).get("channel");
            assertEquals("Pager", ch.get("topic"));
            assertEquals(new BigDecimal(7), ch.get("num_members"));
            Map<String, Object> h = sl.history(ctx, TOK, "C42", "1.0", null, 10, null, false, base);
            assertEquals(new BigDecimal(3), h.get("count"));
            assertEquals(List.of("ts", "user", "text", "thread_ts", "reply_count", "subtype"), cols(h.get("table")));
            assertEquals("five", rows(h.get("table")).get(0).get(2));
            assertTrue(form(f.last("POST", "/api/conversations.history")).contains("oldest=1.0"));
            @SuppressWarnings("unchecked") Map<String, Object> user = (Map<String, Object>) sl.userDetails(ctx, TOK, "U222", base).get("user");
            assertEquals("Bob Ray", user.get("real_name"));
            assertEquals("bob@example.com", user.get("email"));
            assertTrue(form(f.last("POST", "/api/users.info")).contains("user=U222"));
            @SuppressWarnings("unchecked") Map<String, Object> byMail = (Map<String, Object>) sl.userDetails(ctx, TOK, "ann@example.com", base).get("user");
            assertEquals("Ops", byMail.get("title"));
            @SuppressWarnings("unchecked") Map<String, Object> ws = (Map<String, Object>) sl.workspace(ctx, TOK, null, base).get("workspace");
            assertEquals("acme.com", ws.get("email_domain"));
        }
    }

    // ---------------------------------------------------------------- Salesforce

    @Test void salesforceUpsertAndFiles() throws Exception {
        try (Fake f = new Fake()) {
            String rec = "001000000000001AAA";
            f.on("PATCH /services/data/v61.0/sobjects/Account/ERP_Number__c/.*", c -> new Object[]{201, "{\"id\":\"" + rec + "\",\"created\":true,\"success\":true}"})
             .on("GET /services/data/v61.0/query", c -> q(c).contains("ContentDocumentLink")
                    ? ok("{\"done\":true,\"records\":[{\"attributes\":{},\"ContentDocumentId\":\"069000000000001AAA\",\"ContentDocument\":{\"attributes\":{},\"Title\":\"Invoice\",\"FileExtension\":\"pdf\",\"ContentSize\":4,\"LatestPublishedVersionId\":\"068000000000001AAA\"}}]}")
                    : ok("{\"done\":true,\"records\":[{\"attributes\":{},\"Id\":\"00P000000000001AAA\",\"Name\":\"note.txt\",\"BodyLength\":2,\"ContentType\":\"text/plain\"}]}"))
             .on("GET /services/data/v61.0/sobjects/ContentVersion/068000000000001AAA/VersionData", c -> new Object[]{200, "%PDF".getBytes()})
             .on("GET /services/data/v61.0/sobjects/Attachment/00P000000000001AAA/Body", c -> new Object[]{200, "hi".getBytes()})
             .on("POST /services/data/v61.0/sobjects/ContentVersion", c -> new Object[]{201, "{\"id\":\"068000000000002AAA\",\"success\":true}"});
            var sf = new SalesforcePackage();
            var ctx = ctx();
            Map<String, Object> up = sf.upsert(ctx, f.url(), TOK, "Account", "ERP_Number__c", "A/1", Map.of("Name", "Acme"), "v61.0");
            assertEquals(rec, up.get("id"));
            assertEquals(true, up.get("created"));
            assertTrue(f.last("PATCH", ".*").path().endsWith("/ERP_Number__c/A%2F1"));
            assertEquals("SALESFORCE.BAD_OBJECT", assertThrows(ActionException.class, () -> sf.upsert(ctx, f.url(), TOK, "Account", "Bad Field", "1", Map.of(), "v61.0")).code());
            Map<String, Object> list = sf.attachments(ctx, f.url(), TOK, rec, null, "v61.0");
            assertEquals(new BigDecimal(2), list.get("count"));
            assertEquals(List.of(), list.get("files"));
            Map<String, Object> got = sf.attachments(ctx, f.url(), TOK, rec, "files", "v61.0");
            @SuppressWarnings("unchecked") List<Object> files = (List<Object>) got.get("files");
            assertEquals(2, files.size());
            assertEquals("%PDF", Files.readString(Path.of((String) files.get(0))));
            assertTrue(((String) files.get(0)).endsWith("Invoice.pdf"));
            assertEquals("hi", Files.readString(Path.of((String) files.get(1))));
            assertEquals("SALESFORCE.BAD_ID", assertThrows(ActionException.class, () -> sf.attachments(ctx, f.url(), TOK, "x' OR Id != '", null, "v61.0")).code());
            Files.writeString(ctx.resolvePath("report.csv"), "a,b");
            assertEquals("068000000000002AAA", sf.upload(ctx, f.url(), TOK, rec, "report.csv", null, "v61.0").get("content_version_id"));
            String body = f.last("POST", "/services/data/v61.0/sobjects/ContentVersion").text();
            assertTrue(body.contains("\"FirstPublishLocationId\":\"" + rec + "\"") && body.contains("\"Title\":\"report\"")
                    && body.contains(Base64.getEncoder().encodeToString("a,b".getBytes())), body);
        }
    }

    // ---------------------------------------------------------------- Workday

    @Test void workdayReportsAndIntegrations() throws Exception {
        String wid = "abcdef0123456789abcdef0123456789";
        try (Fake f = new Fake()) {
            f.on("GET /ccx/service/customreport2/acme/isu/Active_Workers", c -> q(c).contains("format=csv")
                    ? ok("Worker,Dept\nAnn,Sales\nBob,\"Ops, East\"\n")
                    : ok("{\"Report_Entry\":[{\"Worker\":\"Ann\",\"Org\":{\"Name\":\"Sales\"},\"Emails\":[{\"Email\":\"a@x\"},{\"Email\":\"b@x\"}]}]}"))
             .on("POST /ccx/service/acme/Integrations/v42.0", c -> {
                 String x = c.text();
                 if (x.contains("Launch_Integration_Event_Request") && x.contains("INT_Bad")) return new Object[]{500, "<e:Envelope xmlns:e=\"http://schemas.xmlsoap.org/soap/envelope/\"><e:Body><e:Fault><faultcode>e:Client</faultcode><faultstring>Invalid ID value</faultstring></e:Fault></e:Body></e:Envelope>"};
                 if (x.contains("Launch_Integration_Event_Request")) return ok("<env:Envelope xmlns:env=\"http://schemas.xmlsoap.org/soap/envelope/\"><env:Body><wd:Launch_Integration_Response xmlns:wd=\"urn:com.workday/bsvc\"><wd:Integration_Event><wd:Integration_Event_Reference><wd:ID wd:type=\"WID\">" + wid + "</wd:ID></wd:Integration_Event_Reference></wd:Integration_Event></wd:Launch_Integration_Response></env:Body></env:Envelope>");
                 return ok("<env:Envelope xmlns:env=\"http://schemas.xmlsoap.org/soap/envelope/\"><env:Body><wd:Get_Integration_Events_Response xmlns:wd=\"urn:com.workday/bsvc\"><wd:Response_Data><wd:Integration_Event><wd:Integration_Event_Data>"
                         + "<wd:Background_Process_Instance_Data><wd:Background_Process_Instance_Status_Reference><wd:ID wd:type=\"WID\">ff</wd:ID><wd:ID wd:type=\"Background_Process_Instance_Status_ID\">Completed</wd:ID></wd:Background_Process_Instance_Status_Reference>"
                         + "<wd:Percent_Complete>100</wd:Percent_Complete></wd:Background_Process_Instance_Data><wd:Initiated_DateTime>2026-10-19T08:00:00</wd:Initiated_DateTime><wd:Completed_DateTime>2026-10-19T08:05:00</wd:Completed_DateTime>"
                         + "</wd:Integration_Event_Data></wd:Integration_Event></wd:Response_Data></wd:Get_Integration_Events_Response></env:Body></env:Envelope>");
             });
            var wd = new WorkdayPackage();
            var ctx = ctx();
            SecureString pw = new SecureString("s3cret", "t");
            Map<String, Object> js = wd.getReport(ctx, f.url(), "/ccx/service/customreport2/acme/isu/Active_Workers", Map.of("Effective_as_of_Date", "2026-10-01"), "json", "isu@acme", pw, null);
            assertEquals(List.of("Worker", "Org.Name", "Emails"), cols(js.get("table")));
            assertEquals(List.of("Ann", "Sales", "a@x; b@x"), rows(js.get("table")).get(0));
            assertTrue(q(f.last("GET", ".*")).contains("Effective_as_of_Date=2026-10-01"));
            assertTrue(f.last("GET", ".*").auth().startsWith("Basic "));
            Map<String, Object> csv = wd.getReport(ctx, f.url(), "/ccx/service/customreport2/acme/isu/Active_Workers", null, "csv", null, null, TOK);
            assertEquals(new BigDecimal(2), csv.get("count"));
            assertEquals(List.of("Bob", "Ops, East"), rows(csv.get("table")).get(1));
            assertEquals("WORKDAY.BAD_PATH", assertThrows(ActionException.class, () -> wd.getReport(ctx, f.url(), "/ccx/api/v1/acme/workers", null, "json", null, null, TOK)).code());

            assertEquals(wid, wd.launchIntegration(ctx, f.url(), "acme", "INT_Worker_Load", "v42.0", null, null, TOK).get("event_id"));
            Call launch = f.last("POST", ".*");
            assertEquals("Bearer tok-123", launch.auth());
            assertTrue(launch.text().contains("<bsvc:ID bsvc:type=\"Integration_System_ID\">INT_Worker_Load</bsvc:ID>"), launch.text());
            wd.launchIntegration(ctx, f.url(), "acme", "INT_Worker_Load", "v42.0", "isu@acme", pw, null);
            assertTrue(f.last("POST", ".*").text().contains("<wsse:Username>isu@acme</wsse:Username>"));
            assertEquals("WORKDAY.SOAP_FAULT", assertThrows(ActionException.class, () -> wd.launchIntegration(ctx, f.url(), "acme", "INT_Bad", "v42.0", null, null, TOK)).code());
            assertEquals("WORKDAY.BAD_ID", assertThrows(ActionException.class, () -> wd.launchIntegration(ctx, f.url(), "acme", "<x/>", "v42.0", null, null, TOK)).code());
            // launch parameters go in Integration_Launch_Parameter_Data, each value with its Workday type
            Map<String, Object> params = new LinkedHashMap<>();
            params.put("Effective_Date", "2026-11-01"); params.put("Include_Terminated", false); params.put("Batch_Size", new BigDecimal("500")); params.put("Note", "A & B");
            wd.launchIntegration(ctx, f.url(), "acme", "INT_Worker_Load", "v42.0", null, null, TOK, "integration_system_id", params);
            String sent = f.last("POST", ".*").text();
            assertTrue(sent.contains("<bsvc:Launch_Integration_Event_Request"), sent);
            assertTrue(sent.contains("<bsvc:ID bsvc:type=\"Launch_Parameter_Name\">Effective_Date</bsvc:ID></bsvc:Launch_Parameter_Reference><bsvc:Launch_Parameter_Value_Data><bsvc:Date>2026-11-01</bsvc:Date>"), sent);
            assertTrue(sent.contains("<bsvc:Boolean>false</bsvc:Boolean>") && sent.contains("<bsvc:Numeric>500</bsvc:Numeric>") && sent.contains("<bsvc:Text>A &amp; B</bsvc:Text>"), sent);
            assertEquals("WORKDAY.BAD_ID", assertThrows(ActionException.class, () -> wd.launchIntegration(ctx, f.url(), "acme", "INT_Worker_Load", "v42.0", null, null, TOK,
                    "integration_system_id", Map.of("bad name", 1))).code());
            Map<String, Object> ev = wd.getIntegrationEvent(ctx, f.url(), "acme", wid, "v42.0", null, null, TOK);
            assertEquals("Completed", ev.get("status"));
            assertEquals(new BigDecimal(100), ev.get("percent_complete"));
            assertEquals(true, ev.get("done"));
            assertEquals("2026-10-19T08:00:00", ev.get("started"));
            assertTrue(f.last("POST", ".*").text().contains("<bsvc:ID bsvc:type=\"WID\">" + wid + "</bsvc:ID>"));
        }
    }

    @Test void workdayIntegrationEventByAnotherIdType() throws Exception {
        String wid = "abcdef0123456789abcdef0123456789";
        try (Fake f = new Fake()) {
            f.on("POST /ccx/service/acme/Integrations/v42.0", c -> ok("<env:Envelope xmlns:env=\"http://schemas.xmlsoap.org/soap/envelope/\"><env:Body>"
                    + "<wd:Get_Integration_Events_Response xmlns:wd=\"urn:com.workday/bsvc\"><wd:Response_Data><wd:Integration_Event><wd:Integration_Event_Data>"
                    + "<wd:Background_Process_Instance_Data><wd:Background_Process_Instance_Status_Reference><wd:ID wd:type=\"Background_Process_Instance_Status_ID\">Processing</wd:ID>"
                    + "</wd:Background_Process_Instance_Status_Reference></wd:Background_Process_Instance_Data></wd:Integration_Event_Data></wd:Integration_Event></wd:Response_Data>"
                    + "</wd:Get_Integration_Events_Response></env:Body></env:Envelope>"));
            var wd = new WorkdayPackage();
            var ctx = ctx();

            Map<String, Object> ev = wd.getIntegrationEvent(ctx, f.url(), "acme", "INT_EVENT_0042", "v42.0", null, null, TOK, "Integration_Event_ID");
            assertEquals("Processing", ev.get("status"));
            assertEquals(false, ev.get("done"));
            String sent = f.last("POST", ".*").text();
            assertTrue(sent.contains("<bsvc:Integration_Event_Reference><bsvc:ID bsvc:type=\"Integration_Event_ID\">INT_EVENT_0042</bsvc:ID></bsvc:Integration_Event_Reference>"), sent);

            wd.getIntegrationEvent(ctx, f.url(), "acme", wid, "v42.0", null, null, TOK, "wid");
            assertTrue(f.last("POST", ".*").text().contains("<bsvc:ID bsvc:type=\"WID\">" + wid + "</bsvc:ID>"), "wid in any case is sent as WID");
            wd.getIntegrationEvent(ctx, f.url(), "acme", wid, "v42.0", null, null, TOK, " ");
            assertTrue(f.last("POST", ".*").text().contains("bsvc:type=\"WID\""), "WID when empty");

            int calls = f.calls.size();
            assertEquals("WORKDAY.BAD_ID", assertThrows(ActionException.class, () -> wd.getIntegrationEvent(ctx, f.url(), "acme", "INT_EVENT_0042", "v42.0", null, null, TOK, "WID")).code());
            assertEquals("WORKDAY.BAD_ID_TYPE", assertThrows(ActionException.class, () -> wd.getIntegrationEvent(ctx, f.url(), "acme", wid, "v42.0", null, null, TOK, "a\" b")).code());
            assertEquals("WORKDAY.BAD_ID", assertThrows(ActionException.class, () -> wd.getIntegrationEvent(ctx, f.url(), "acme", "<x/>", "v42.0", null, null, TOK, "Integration_Event_ID")).code());
            assertEquals(calls, f.calls.size(), "invalid input is rejected before any request");
        }
    }

    // ---------------------------------------------------------------- ServiceNow

    @Test void serviceNowDeleteAttachmentsAndChangedRecords() throws Exception {
        String sys = "0123456789abcdef0123456789abcdef", att = "fedcba9876543210fedcba9876543210";
        try (Fake f = new Fake()) {
            f.on("DELETE /api/now/table/incident/" + sys, c -> new Object[]{204, ""})
             .on("GET /api/now/attachment/" + att + "/file", c -> new Object[]{200, "LOGDATA".getBytes()})
             .on("GET /api/now/attachment/" + att, c -> ok("{\"result\":{\"sys_id\":\"" + att + "\",\"file_name\":\"../trace.log\"}}"))
             .on("GET /api/now/attachment", c -> ok("{\"result\":[{\"sys_id\":\"" + att + "\",\"file_name\":\"trace.log\",\"content_type\":\"text/plain\",\"size_bytes\":\"7\",\"sys_created_on\":\"2026-10-19 08:00:00\"}]}"))
             .on("GET /api/now/table/incident", c -> ok("{\"result\":[{\"sys_id\":\"a\",\"number\":\"INC1\",\"sys_updated_on\":\"2026-10-19 08:30:00\"},{\"sys_id\":\"b\",\"number\":\"INC2\",\"sys_updated_on\":\"2026-10-19 09:15:00\"}]}"));
            var sn = new ServiceNowPackage();
            var ctx = ctx();
            SecureString pw = new SecureString("pw", "t");
            sn.delete(ctx, f.url(), "incident", sys, "agent", pw, null);
            assertTrue(f.last("DELETE", "/api/now/table/incident/" + sys).auth().startsWith("Basic "));
            assertEquals("SERVICENOW.BAD_SYS_ID", assertThrows(ActionException.class, () -> sn.delete(ctx, f.url(), "incident", "1", "agent", pw, null)).code());
            Map<String, Object> la = sn.listAttachments(ctx, f.url(), "incident", sys, null, null, TOK);
            assertEquals(List.of(att, "trace.log", "text/plain", "7", "2026-10-19 08:00:00"), rows(la.get("table")).get(0));
            assertTrue(q(f.last("GET", "/api/now/attachment")).contains("sysparm_query=table_name=incident^table_sys_id=" + sys));
            String saved = (String) sn.downloadAttachment(ctx, f.url(), att, "out", null, null, TOK).get("path");
            assertTrue(saved.endsWith("trace.log") && saved.contains("out"), saved);
            assertEquals("LOGDATA", Files.readString(Path.of(saved)));
            Map<String, Object> ch = sn.changed(ctx, f.url(), "incident", "2026-10-19T08:00", "active=true", List.of("number"), 100, null, null, TOK);
            assertEquals(new BigDecimal(2), ch.get("count"));
            assertEquals("2026-10-19 09:15:00", ch.get("latest"));
            String qs = q(f.last("GET", "/api/now/table/incident"));
            assertTrue(qs.contains("sysparm_query=sys_updated_on>javascript:gs.dateGenerate('2026-10-19','08:00:00')^active=true^ORDERBYsys_updated_on"), qs);
            assertTrue(qs.contains("sysparm_fields=sys_id,sys_updated_on,number"), qs);
            assertEquals("SERVICENOW.BAD_TIME", assertThrows(ActionException.class, () -> sn.changed(ctx, f.url(), "incident", "yesterday') or (", null, null, 10, null, null, TOK)).code());
        }
    }

    // ---------------------------------------------------------------- Terminal

    /** In-memory s3270: answers ReadBuffer(Ascii) and Ascii() from a fixed screen and records every command. */
    static final class FakeEngine {
        final List<String> commands = new ArrayList<>();
        final StringBuilder out = new StringBuilder(); int pos;
        final List<String> buffer, screen;
        FakeEngine(List<String> buffer, List<String> screen) { this.buffer = buffer; this.screen = screen; }
        final Writer writer = new Writer() {
            final StringBuilder line = new StringBuilder();
            @Override public void write(char[] c, int off, int len) {
                for (int i = off; i < off + len; i++) {
                    if (c[i] == '\n') { respond(line.toString().replace("\r", "")); line.setLength(0); } else line.append(c[i]);
                }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        final Reader reader = new Reader() {
            @Override public int read(char[] c, int off, int len) {
                if (pos >= out.length()) return -1;
                int n = Math.min(len, out.length() - pos);
                out.getChars(pos, pos + n, c, off); pos += n; return n;
            }
            @Override public void close() { }
        };
        void respond(String cmd) {
            commands.add(cmd);
            List<String> data = cmd.equals("ReadBuffer(Ascii)") ? buffer : cmd.equals("Ascii()") ? screen : List.of();
            for (String d : data) out.append("data: ").append(d).append('\n');
            out.append("U F U C(host) I 4 3 20 0 0 0x0 0.000\nok\n");
        }
    }

    static String hex(String s) { StringBuilder b = new StringBuilder(); for (char c : s.toCharArray()) b.append(' ').append(String.format("%02x", (int) c)); return b.toString(); }

    @Test void terminalFieldsCursorAndKeyMappings() throws Exception {
        // 3 rows x 20 columns: "User ID" label, an 8-character input field, "Password" label, a hidden input field, then a status line.
        List<String> buffer = List.of(
                "SF(c0=20)" + hex("User ID") + " SF(c0=00)" + hex("jdoe    ") + " SF(c0=20)" + hex("  "),
                hex("Password").substring(1) + " SF(c0=0c,41=f4)" + hex("secret12") + " SF(c0=20)" + hex("  "),
                hex("Ready").substring(1) + " SA(41=f2)" + hex("               "));
        List<String> screen = List.of(" User ID jdoe       ", "Password  secret12  ", "Ready               ");
        FakeEngine eng = new FakeEngine(buffer, screen);
        var t = new TerminalPackage();
        var ctx = ctx();
        ctx.sessions().put("terminal", new TerminalPackage.Session(null, eng.writer, eng.reader));

        Map<String, Object> all = t.getAllFields(ctx, false, "terminal");
        assertEquals(new BigDecimal(5), all.get("count"));
        List<List<Object>> r = rows(all.get("table"));
        assertEquals(List.of(BigDecimal.ONE, BigDecimal.ONE, new BigDecimal(2), new BigDecimal(7), true, false, false, "User ID"), r.get(0));
        assertEquals(List.of(new BigDecimal(2), BigDecimal.ONE, new BigDecimal(10), new BigDecimal(8), false, false, false, "jdoe"), r.get(1));
        assertEquals(List.of(new BigDecimal(4), new BigDecimal(2), new BigDecimal(10), new BigDecimal(8), false, false, true, ""), r.get(3), "hidden field text is not revealed");
        assertEquals(new BigDecimal(2), t.getAllFields(ctx, true, "terminal").get("count"));

        assertEquals("jdoe", t.getField(ctx, null, null, "User ID", "terminal").get("text"));
        assertEquals("jdoe", t.getField(ctx, 1, 15, null, "terminal").get("text"));
        assertEquals("Ready", t.getField(ctx, 3, 1, null, "terminal").get("text"));

        eng.commands.clear();
        t.setField(ctx, new SecureString("pw42", "t"), null, null, "password", "terminal");
        assertEquals(List.of("ReadBuffer(Ascii)", "MoveCursor(1,9)", "EraseEOF", "String(\"pw42\")"), eng.commands);
        assertEquals("TERMINAL.PROTECTED_FIELD", assertThrows(ActionException.class, () -> t.setField(ctx, "x", 1, 1, null, "terminal")).code());
        assertEquals("TERMINAL.TOO_LONG", assertThrows(ActionException.class, () -> t.setField(ctx, "123456789", 1, 10, null, "terminal")).code());
        assertEquals("TERMINAL.FIELD_NOT_FOUND", assertThrows(ActionException.class, () -> t.setField(ctx, "x", null, null, "Account", "terminal")).code());

        Map<String, Object> found = t.searchField(ctx, "ready", 1, false, "terminal");
        assertEquals(true, found.get("found"));
        assertEquals(new BigDecimal(3), found.get("row"));
        assertEquals(BigDecimal.ONE, found.get("column"));
        assertEquals(false, t.searchField(ctx, "ready", 1, true, "terminal").get("found"));

        eng.commands.clear();
        t.setCursor(ctx, 2, 4, "terminal");
        t.clearScreen(ctx, false, "terminal");
        t.clearScreen(ctx, true, "terminal");
        t.showTerminal(ctx, "terminal");
        t.hideTerminal(ctx, "terminal");
        assertEquals(List.of("MoveCursor(1,3)", "EraseInput", "Clear", "Wait(30,InputField)"), eng.commands);

        Map<String, Object> maps = t.setKeyMapping(ctx, Map.of("Submit", "Enter", "Exit", "pf3"), "terminal");
        assertEquals(Map.of("EXIT", "pf3", "SUBMIT", "Enter"), maps.get("mappings"));
        eng.commands.clear();
        t.sendKey(ctx, "submit", "terminal");
        t.sendKey(ctx, "Exit", "terminal");
        t.sendKey(ctx, "PF12", "terminal");
        assertEquals(List.of("Enter", "Wait(30,InputField)", "PF(3)", "Wait(30,InputField)", "PF(12)", "Wait(30,InputField)"), eng.commands);
        assertEquals("TERMINAL.BAD_KEY", assertThrows(ActionException.class, () -> t.setKeyMapping(ctx, Map.of("Go", "F99"), "terminal")).code());
        t.setKeyMapping(ctx, Collections.singletonMap("Submit", ""), "terminal");
        assertEquals("TERMINAL.BAD_KEY", assertThrows(ActionException.class, () -> t.sendKey(ctx, "Submit", "terminal")).code());
    }

    // ---------------------------------------------------------------- SAP

    record Script(String body, List<String> args, String stdin) {}

    @Test void sapGeneratesScriptsForNewActions() throws Exception {
        List<Script> seen = new ArrayList<>();
        Map<String, Object> reply = new HashMap<>();
        SapPackage.testRunner = (b, a, in) -> { seen.add(new Script(b, a, in)); return new HashMap<>(reply); };
        try {
            var sap = new SapPackage();
            var ctx = ctx();
            String grid = "wnd[0]/usr/cntlGRID1/shellcont/shell";

            sap.click(ctx, "wnd[0]/tbar[1]/btn[8]", 0, 1);
            Script s = seen.get(0);
            assertEquals(List.of("0", "1", "wnd[0]/tbar[1]/btn[8]"), s.args());
            assertTrue(s.body().contains("Case \"GuiButton\": e.Press") && s.body().contains("e.Selected = Not e.Selected"), s.body());
            assertEquals("SAP.BAD_ID", assertThrows(ActionException.class, () -> sap.click(ctx, "wnd[0]\" & CreateObject(\"x\")", 0, 0)).code());

            sap.doubleClick(ctx, grid, "4", "MATNR", 0, 0);
            s = last(seen);
            assertTrue(s.body().contains("e.setCurrentCell CLng(it), GCol(e, col)") && s.body().contains("e.doubleClickCurrentCell") && s.body().contains("e.doubleClickNode it"));
            assertEquals("4\r\nMATNR\r\n", s.stdin());

            sap.rightClick(ctx, "wnd[0]/usr/cntlTREE/shellcont/shell", "          1", null, "DELETE", 0, 0);
            s = last(seen);
            assertTrue(s.body().contains("e.nodeContextMenu it") && s.body().contains("e.selectContextMenuItem fc"));
            assertEquals("          1\r\n\r\nDELETE\r\n", s.stdin(), "node keys keep their spaces and travel on standard input");

            sap.clickMenu(ctx, "System > User Profile > Own Data", 0, 0, 0);
            s = last(seen);
            assertEquals(List.of("0", "0", "0", "text"), s.args());
            assertEquals("System > User Profile > Own Data\r\n", s.stdin());
            assertTrue(s.body().contains("Split(p, \">\")") && s.body().contains("Fail \"Menu entry not found: \""));
            assertEquals("SAP.BAD_MENU", assertThrows(ActionException.class, () -> sap.clickMenu(ctx, " ", 0, 0, 0)).code());

            sap.checkBox(ctx, "wnd[0]/usr/chkP_TEST", false, 0, 0);
            assertEquals(List.of("0", "0", "wnd[0]/usr/chkP_TEST", "0"), last(seen).args());
            assertTrue(last(seen).body().contains("e.Selected = (a(3) = \"1\")"));
            sap.selectRadio(ctx, "wnd[0]/usr/radP_ALL", 0, 0);
            assertTrue(last(seen).body().contains("e.Select: Check \"Radio button \""));
            sap.selectItem(ctx, "wnd[0]/usr/cmbLANG", "English", "text", 0, 0);
            assertEquals("text", last(seen).args().get(3));
            assertEquals("English\r\n", last(seen).stdin());
            assertTrue(last(seen).body().contains("e.Key = e.Entries(CInt(i)).Key"));
            sap.expandNode(ctx, "wnd[0]/usr/cntlTREE/shellcont/shell", "F00001", true, 0, 0);
            assertTrue(last(seen).body().contains("e.expandNode k") && last(seen).body().contains("e.selectNode k"));
            assertEquals("1", last(seen).args().get(3));

            reply.put("rows", new ArrayList<>(List.of(new ArrayList<>(List.of("/app/con[0]/ses[0]/wnd[0]/usr/txtA", "GuiTextField", "txtA", "x")))));
            Map<String, Object> kids = sap.getChildren(ctx, "wnd[0]/usr", 0, 0);
            assertEquals(BigDecimal.ONE, kids.get("count"));
            assertEquals(List.of("id", "type", "name", "text"), cols(kids.get("table")));
            reply.clear();

            reply.put("count", new BigDecimal(12));
            assertEquals(new BigDecimal(12), sap.itemCount(ctx, "wnd[0]/usr/cmbLANG", 0, 0).get("count"));
            assertTrue(last(seen).body().contains("n = e.Entries.Count") && last(seen).body().contains("n = e.GetAllNodeKeys.Count"));
            assertEquals(new BigDecimal(12), sap.rowCount(ctx, grid, 0, 0).get("count"));
            assertEquals("rows", last(seen).args().get(3));
            sap.columnCount(ctx, grid, 0, 0);
            assertEquals("columns", last(seen).args().get(3));
            assertTrue(last(seen).body().contains("Function ColIndex(tbl, c)"), "table helpers are included");
            reply.clear();

            reply.put("text", "4711");
            assertEquals("4711", sap.getCell(ctx, grid, 3, "Material", 0, 0).get("text"));
            assertEquals(List.of("0", "0", grid, "3"), last(seen).args());
            assertEquals("Material\r\n", last(seen).stdin());
            assertTrue(last(seen).body().contains("t = TCell(a(2), CLng(a(3)), col).Text Else t = e.GetCellValue(CLng(a(3)), GCol(e, col))"));
            assertEquals("SAP.BAD_ROW", assertThrows(ActionException.class, () -> sap.getCell(ctx, grid, -1, "A", 0, 0)).code());
            reply.clear();

            sap.setCell(ctx, grid, 0, "2", new SecureString("hidden", "t"), 0, 0);
            assertEquals("2\r\nhidden\r\n", last(seen).stdin());
            assertTrue(last(seen).body().contains("e.modifyCell CLng(a(3)), GCol(e, col), v"));

            reply.put("row", new BigDecimal(-1)); reply.put("column", "");
            Map<String, Object> nf = sap.findCell(ctx, grid, "4711", null, 0, 0);
            assertEquals(false, nf.get("found"));
            assertEquals("\r\n4711\r\n", last(seen).stdin());
            reply.put("row", new BigDecimal(5)); reply.put("column", "MATNR");
            Map<String, Object> fc = sap.findCell(ctx, grid, "4711", "MATNR", 0, 0);
            assertEquals(true, fc.get("found"));
            assertEquals(new BigDecimal(5), fc.get("row"));
            assertEquals("MATNR", fc.get("column"));
            reply.clear();

            reply.put("columns", new ArrayList<>(List.of("Item", "Material")));
            reply.put("rows", new ArrayList<>(List.of(new ArrayList<>(List.of("10", "4711")), new ArrayList<>(List.of("20", "4712")))));
            Map<String, Object> ex = sap.exportTable(ctx, "wnd[0]/usr/tblSAPMV45ATCTRL_U_ERF_AUFTRAG", 100, 0, 0);
            assertEquals(new BigDecimal(2), ex.get("count"));
            assertEquals(List.of("Item", "Material"), cols(ex.get("table")));
            assertEquals("100", last(seen).args().get(3));
            assertTrue(last(seen).body().contains("tb.VerticalScrollbar.Position = r") && last(seen).body().contains("e.GetDisplayedColumnTitle(cols(i))"));

            for (Script x : seen) {
                String b = x.body();
                assertFalse(b.contains("\"\"\""), "no stray triple quotes");
                long opens = b.lines().map(String::strip).filter(l -> l.endsWith(" Then") && !l.startsWith("ElseIf")).count();
                long closes = b.lines().map(String::strip).filter(l -> l.equals("End If")).count();
                assertEquals(closes, opens, "block If statements are closed: " + b);
                assertEquals(b.lines().filter(l -> l.strip().startsWith("Function ")).count(), b.lines().filter(l -> l.strip().endsWith("End Function")).count());
            }
        } finally { SapPackage.testRunner = null; }
    }

    static Script last(List<Script> l) { return l.get(l.size() - 1); }

    // ---------------------------------------------------------------- Active Directory

    /** In-memory directory with a small LDAP filter evaluator (and, or, not, equality, presence, * wildcards). */
    static final class FakeDirectory implements ActiveDirectoryPackage.Directory {
        final Map<String, String> names = new LinkedHashMap<>();       // lower-case dn -> dn as created
        final Map<String, Attributes> entries = new LinkedHashMap<>(); // lower-case dn -> attributes
        final List<String> log = new ArrayList<>();

        void add(String dn, String... kv) {
            BasicAttributes a = new BasicAttributes(true);
            for (int i = 0; i < kv.length; i += 2) {
                Attribute at = a.get(kv[i]);
                if (at == null) a.put(kv[i], kv[i + 1]); else at.add(kv[i + 1]);
            }
            names.put(dn.toLowerCase(Locale.ROOT), dn); entries.put(dn.toLowerCase(Locale.ROOT), a);
        }
        Attributes get(String dn) { return entries.get(dn.toLowerCase(Locale.ROOT)); }
        String val(String dn, String attr) throws NamingException { Attribute a = get(dn).get(attr); return a == null ? null : String.valueOf(a.get()); }

        static String parent(String dn) {
            for (int i = 0; i < dn.length(); i++) {
                if (dn.charAt(i) == '\\') i++;
                else if (dn.charAt(i) == ',') return dn.substring(i + 1);
            }
            return "";
        }

        @Override public void search(String base, String filter, SearchControls sc, Consumer<ActiveDirectoryPackage.Entry> each) throws NamingException {
            String b = base.toLowerCase(Locale.ROOT);
            long limit = sc.getCountLimit(), n = 0;
            for (var e : new ArrayList<>(entries.entrySet())) {
                String dn = e.getKey();
                boolean inScope = switch (sc.getSearchScope()) {
                    case SearchControls.OBJECT_SCOPE -> dn.equals(b);
                    case SearchControls.ONELEVEL_SCOPE -> parent(dn).equals(b);
                    default -> dn.equals(b) || dn.endsWith("," + b);
                };
                if (!inScope || !eval(filter, e.getValue())) continue;
                if (limit > 0 && n >= limit) throw new SizeLimitExceededException();
                n++;
                each.accept(new ActiveDirectoryPackage.Entry(names.get(dn), (Attributes) e.getValue().clone()));
            }
        }

        static boolean eval(String f, Attributes a) throws NamingException {
            f = f.strip();
            if (!f.startsWith("(") || !f.endsWith(")")) throw new InvalidSearchFilterException(f);
            String in = f.substring(1, f.length() - 1);
            if (in.startsWith("&") || in.startsWith("|")) {
                boolean and = in.startsWith("&");
                for (String sub : split(in.substring(1))) { boolean v = eval(sub, a); if (and && !v) return false; if (!and && v) return true; }
                return and;
            }
            if (in.startsWith("!")) return !eval(in.substring(1), a);
            int eq = in.indexOf('=');
            if (eq < 0) throw new InvalidSearchFilterException(f);
            String attr = in.substring(0, eq), value = in.substring(eq + 1);
            if (attr.contains(":")) attr = attr.substring(0, attr.indexOf(':'));
            Attribute at = a.get(attr);
            if (at == null) return false;
            if (value.equals("*")) return true;
            StringBuilder re = new StringBuilder("(?i)");
            for (String part : value.split("\\*", -1)) { if (re.length() > 4) re.append(".*"); re.append(java.util.regex.Pattern.quote(unescape(part))); }
            for (int i = 0; i < at.size(); i++) if (String.valueOf(at.get(i)).matches(re.toString())) return true;
            return false;
        }
        static String unescape(String v) {
            StringBuilder sb = new StringBuilder();
            for (int i = 0; i < v.length(); i++) {
                if (v.charAt(i) == '\\' && i + 2 < v.length()) { sb.append((char) Integer.parseInt(v.substring(i + 1, i + 3), 16)); i += 2; }
                else sb.append(v.charAt(i));
            }
            return sb.toString();
        }
        static List<String> split(String s) {
            List<String> out = new ArrayList<>();
            int depth = 0, start = 0;
            for (int i = 0; i < s.length(); i++) {
                if (s.charAt(i) == '(') { if (depth++ == 0) start = i; }
                else if (s.charAt(i) == ')' && --depth == 0) out.add(s.substring(start, i + 1));
            }
            return out;
        }

        @Override public Attributes read(String dn, String[] attrs) throws NamingException {
            Attributes a = get(dn);
            if (a == null) throw new NameNotFoundException(dn);
            if (attrs == null) return (Attributes) a.clone();
            BasicAttributes out = new BasicAttributes(true);
            for (String n : attrs) if (a.get(n) != null) out.put((Attribute) a.get(n).clone());
            return out;
        }

        @Override public void modify(String dn, ModificationItem[] items) throws NamingException {
            Attributes a = get(dn);
            if (a == null) throw new NameNotFoundException(dn);
            for (ModificationItem m : items) {
                Attribute at = m.getAttribute();
                String id = at.getID();
                log.add(dn + " " + m.getModificationOp() + " " + id);
                switch (m.getModificationOp()) {
                    case DirContext.REPLACE_ATTRIBUTE -> a.put((Attribute) at.clone());
                    case DirContext.ADD_ATTRIBUTE -> {
                        Attribute cur = a.get(id);
                        if (cur == null) { cur = new BasicAttribute(id); a.put(cur); }
                        for (int i = 0; i < at.size(); i++) { if (cur.contains(at.get(i))) throw new AttributeInUseException(id); cur.add(at.get(i)); backLink(id, at.get(i), dn, true); }
                    }
                    case DirContext.REMOVE_ATTRIBUTE -> {
                        Attribute cur = a.get(id);
                        if (cur == null) throw new NoSuchAttributeException(id);
                        if (at.size() == 0) a.remove(id);
                        else for (int i = 0; i < at.size(); i++) { if (!cur.remove(at.get(i))) throw new NoSuchAttributeException(id); backLink(id, at.get(i), dn, false); }
                    }
                    default -> throw new OperationNotSupportedException();
                }
            }
        }
        void backLink(String id, Object member, String groupDn, boolean add) {
            if (!id.equalsIgnoreCase("member")) return;
            Attributes m = get(String.valueOf(member));
            if (m == null) return;
            Attribute mo = m.get("memberOf");
            if (add) { if (mo == null) m.put("memberOf", groupDn); else mo.add(groupDn); } else if (mo != null) mo.remove(groupDn);
        }

        @Override public void create(String dn, Attributes attrs) throws NamingException {
            if (get(dn) != null) throw new NameAlreadyBoundException(dn);
            if (get(parent(dn)) == null) throw new NameNotFoundException(parent(dn));
            names.put(dn.toLowerCase(Locale.ROOT), dn); entries.put(dn.toLowerCase(Locale.ROOT), (Attributes) attrs.clone());
            log.add("create " + dn);
        }
        @Override public void delete(String dn) throws NamingException {
            String k = dn.toLowerCase(Locale.ROOT);
            if (!entries.containsKey(k)) throw new NameNotFoundException(dn);
            for (String other : entries.keySet()) if (other.endsWith("," + k)) throw new ContextNotEmptyException(dn);
            entries.remove(k); names.remove(k);
            log.add("delete " + dn);
        }
        @Override public void rename(String dn, String newDn) throws NamingException {
            String k = dn.toLowerCase(Locale.ROOT), nk = newDn.toLowerCase(Locale.ROOT);
            if (!entries.containsKey(k)) throw new NameNotFoundException(dn);
            if (entries.containsKey(nk)) throw new NameAlreadyBoundException(newDn);
            for (String old : new ArrayList<>(entries.keySet())) {
                if (!old.equals(k) && !old.endsWith("," + k)) continue;
                String moved = names.get(old).substring(0, names.get(old).length() - dn.length()) + newDn;
                Attributes a = entries.remove(old); names.remove(old);
                entries.put(moved.toLowerCase(Locale.ROOT), a); names.put(moved.toLowerCase(Locale.ROOT), moved);
            }
            javax.naming.ldap.LdapName ln = new javax.naming.ldap.LdapName(newDn);   // like a real directory, the naming attribute follows the new name
            javax.naming.ldap.Rdn rdn = ln.getRdn(ln.size() - 1);
            get(newDn).put(rdn.getType(), String.valueOf(rdn.getValue()));
            log.add("rename " + dn + " -> " + newDn);
        }
        @Override public void close() { }
    }

    static final String BASE = "DC=corp,DC=example,DC=com";

    static FakeDirectory directory() {
        FakeDirectory d = new FakeDirectory();
        d.add(BASE, "objectClass", "domain");
        d.add("OU=Staff," + BASE, "objectClass", "organizationalUnit", "ou", "Staff");
        d.add("OU=Leavers," + BASE, "objectClass", "organizationalUnit", "ou", "Leavers");
        d.add("OU=Groups," + BASE, "objectClass", "organizationalUnit", "ou", "Groups");
        d.add("OU=Computers," + BASE, "objectClass", "organizationalUnit", "ou", "Computers");
        d.add("CN=Bob Ray,OU=Staff," + BASE, "objectClass", "user", "objectCategory", "person", "cn", "Bob Ray", "sAMAccountName", "bob", "userAccountControl", "512", "mail", "bob@example.com");
        d.add("CN=Admins A,OU=Groups," + BASE, "objectClass", "group", "cn", "Admins", "sAMAccountName", "AdminsA");
        d.add("CN=Admins B,OU=Staff," + BASE, "objectClass", "group", "cn", "Admins", "sAMAccountName", "AdminsB");
        return d;
    }

    @Test void activeDirectoryUsersGroupsComputersOusAndQueries() throws Exception {
        FakeDirectory d = directory();
        var ad = new ActiveDirectoryPackage();
        var ctx = ctx();
        ctx.sessions().put("ad", new ActiveDirectoryPackage.AdSession(d, BASE));
        String staff = "OU=Staff," + BASE;

        // users
        String dn = (String) ad.createUser(ctx, staff, "jdoe", "Jane", "Doe", null, "jdoe@corp.example.com", new SecureString("P@ssw0rd!", "t"), true, true,
                Map.of("department", "Finance"), "ad").get("dn");
        assertEquals("CN=Jane Doe," + staff, dn);
        assertEquals(List.of("create " + dn, dn + " 2 unicodePwd", dn + " 2 pwdLastSet", dn + " 2 userAccountControl"), d.log, "created disabled, then password, then enabled");
        assertEquals("512", d.val(dn, "userAccountControl"));
        assertEquals("0", d.val(dn, "pwdLastSet"));
        assertEquals("Finance", d.val(dn, "department"));
        assertEquals("person", String.valueOf(d.get(dn).get("objectClass").get(1)));
        assertArrayEquals("\"P@ssw0rd!\"".getBytes(StandardCharsets.UTF_16LE), (byte[]) d.get(dn).get("unicodePwd").get());
        d.get(dn).put("objectCategory", "person");
        assertEquals("AD.ALREADY_EXISTS", assertThrows(ActionException.class, () -> ad.createUser(ctx, staff, "jdoe2", "Jane", "Doe", null, null, null, true, true, null, "ad")).code());
        assertEquals("AD.BAD_NAME", assertThrows(ActionException.class, () -> ad.createUser(ctx, staff, "a*b", null, null, null, null, null, true, true, null, "ad")).code());
        assertEquals("AD.BAD_DN", assertThrows(ActionException.class, () -> ad.createUser(ctx, "Staff", "x1", null, null, null, null, null, true, true, null, "ad")).code());
        String noPw = (String) ad.createUser(ctx, staff, "temp1", null, null, "Temp, User", null, null, true, false, null, "ad").get("dn");
        assertEquals("CN=Temp\\, User," + staff, noPw, "commas in names are escaped");
        assertEquals("514", d.val(noPw, "userAccountControl"), "no password, so it stays disabled");

        Map<String, Object> opts = ad.accountOptions(ctx, "jdoe", false, true, false, "ad");
        assertEquals(new BigDecimal(0x200 | 0x2 | 0x10000), opts.get("user_account_control"));
        assertEquals("-1", d.val(dn, "pwdLastSet"));
        ad.accountOptions(ctx, "jdoe", true, null, null, "ad");
        assertEquals(String.valueOf(0x200 | 0x10000), d.val(dn, "userAccountControl"));

        d.log.clear();
        ad.setPassword(ctx, "jdoe", new SecureString("N3w!pass", "t"), false, true, "ad");
        assertEquals(List.of(dn + " 2 unicodePwd", dn + " 2 lockoutTime"), d.log);

        String renamed = (String) ad.renameUser(ctx, "jdoe", "Janet Doe", true, "ad").get("dn");
        assertEquals("CN=Janet Doe," + staff, renamed);
        assertEquals("Janet Doe", d.val(renamed, "displayName"));
        assertEquals("jdoe", d.val(renamed, "sAMAccountName"));
        String moved = (String) ad.moveUser(ctx, "jdoe", "OU=Leavers," + BASE, "ad").get("dn");
        assertEquals("CN=Janet Doe,OU=Leavers," + BASE, moved);

        // groups
        String g = (String) ad.createGroup(ctx, "OU=Groups," + BASE, "Finance", "global", "security", "Finance team", null, "ad").get("dn");
        assertEquals("CN=Finance,OU=Groups," + BASE, g);
        assertEquals(String.valueOf(0x80000002), d.val(g, "groupType"));
        ad.createGroup(ctx, "OU=Groups," + BASE, "News", "universal", "distribution", null, "news-list", "ad");
        assertEquals("8", d.val("CN=News,OU=Groups," + BASE, "groupType"));
        ad.addToGroup(ctx, "jdoe", g, "ad");
        ad.addToGroup(ctx, "bob", g, "ad");
        ad.addToGroup(ctx, "bob", g, "ad");  // already a member: no error
        Map<String, Object> members = ad.groupMembers(ctx, "Finance", false, 100, "ad");
        assertEquals(new BigDecimal(2), members.get("count"));
        assertEquals(Arrays.asList(moved, "Janet Doe", "jdoe", "user", null), rows(members.get("table")).get(1));
        assertEquals(new BigDecimal(2), ad.groupMembers(ctx, g, true, 100, "ad").get("count"));
        assertEquals("AD.AMBIGUOUS", assertThrows(ActionException.class, () -> ad.groupMembers(ctx, "Admins", false, 100, "ad")).code());
        assertEquals("AD.GROUP_NOT_FOUND", assertThrows(ActionException.class, () -> ad.deleteGroup(ctx, "Fin*", "ad")).code(), "wildcards are escaped");
        ad.setGroupProperty(ctx, "Finance", "description", "Money people", "ad");
        ad.setGroupProperty(ctx, "Finance", "info", List.of("a", "b"), "ad");
        @SuppressWarnings("unchecked") Map<String, Object> gp = (Map<String, Object>) ad.getGroupProperty(ctx, "Finance", List.of("description", "info", "missing"), "ad").get("properties");
        assertEquals("Money people", gp.get("description"));
        assertEquals(List.of("a", "b"), gp.get("info"));
        assertTrue(gp.containsKey("missing") && gp.get("missing") == null);
        assertEquals("AD.USE_RESET_PASSWORD", assertThrows(ActionException.class, () -> ad.setGroupProperty(ctx, "Finance", "unicodePwd", "x", "ad")).code());
        assertEquals("AD.BAD_ATTRIBUTE", assertThrows(ActionException.class, () -> ad.setGroupProperty(ctx, "Finance", "bad attr", "x", "ad")).code());
        ad.setGroupProperty(ctx, "Finance", "info", null, "ad");
        assertNull(d.get(g).get("info"));
        String g2 = (String) ad.renameGroup(ctx, "Finance", "Finance EU", true, "ad").get("dn");
        assertEquals("CN=Finance EU,OU=Groups," + BASE, g2);
        assertEquals("Finance EU", d.val(g2, "sAMAccountName"));
        ad.deleteGroup(ctx, g2, "ad");
        assertNull(d.get(g2));

        // computers
        String pc = (String) ad.createComputer(ctx, "OU=Computers," + BASE, "pc-0042", "Front desk", true, "ad").get("dn");
        assertEquals("CN=pc-0042,OU=Computers," + BASE, pc);
        assertEquals("PC-0042$", d.val(pc, "sAMAccountName"));
        assertEquals("4096", d.val(pc, "userAccountControl"));
        assertEquals("AD.BAD_NAME", assertThrows(ActionException.class, () -> ad.createComputer(ctx, "OU=Computers," + BASE, "name-that-is-far-too-long", null, true, "ad")).code());
        @SuppressWarnings("unchecked") Map<String, Object> cp = (Map<String, Object>) ad.getComputerProperty(ctx, "PC-0042$", null, "ad").get("properties");
        assertEquals("Front desk", cp.get("description"));
        assertEquals(pc, cp.get("dn"));
        ad.setComputerProperty(ctx, "PC-0042", "location", "Lobby", "ad");
        assertEquals("Lobby", d.val(pc, "location"));
        String pc2 = (String) ad.renameComputer(ctx, "PC-0042", "PC-0043", "ad").get("dn");
        assertEquals("PC-0043$", d.val(pc2, "sAMAccountName"));
        String pc3 = (String) ad.moveComputer(ctx, "PC-0043", staff, "ad").get("dn");
        assertEquals("CN=PC-0043," + staff, pc3);
        ad.deleteComputer(ctx, "PC-0043", "ad");
        assertNull(d.get(pc3));

        // organizational units
        String ou = (String) ad.createOu(ctx, BASE, "Projects", "Project teams", "ad").get("dn");
        assertEquals("OU=Projects," + BASE, ou);
        ad.createOu(ctx, ou, "Alpha", null, "ad");
        ad.createUser(ctx, "OU=Alpha," + ou, "alpha1", "Al", "Pha", null, null, null, false, false, null, "ad");
        ad.setOuProperty(ctx, "Projects", "description", "All projects", "ad");
        @SuppressWarnings("unchecked") Map<String, Object> op = (Map<String, Object>) ad.getOuProperty(ctx, ou, List.of("description"), "ad").get("properties");
        assertEquals("All projects", op.get("description"));
        Map<String, Object> q1 = ad.runQuery(ctx, "(objectClass=*)", List.of("ou", "cn"), ou, "one_level", 100, "ad");
        assertEquals(List.of(Arrays.asList("OU=Alpha," + ou, "Alpha", null)), rows(q1.get("table")));
        assertEquals(new BigDecimal(3), ad.runQuery(ctx, "(objectClass=*)", null, ou, "subtree", 100, "ad").get("count"));
        assertEquals(BigDecimal.ONE, ad.runQuery(ctx, "(objectClass=*)", null, ou, "base", 100, "ad").get("count"));
        assertEquals(BigDecimal.ONE, ad.runQuery(ctx, "(&(objectClass=user)(cn=*Doe*))", null, null, "subtree", 100, "ad").get("count"));
        assertEquals("AD.BAD_FILTER", assertThrows(ActionException.class, () -> ad.runQuery(ctx, "objectClass=user", null, null, "subtree", 10, "ad")).code());
        String ou2 = (String) ad.renameOu(ctx, "Projects", "Programs", "ad").get("dn");
        assertEquals("OU=Programs," + BASE, ou2);
        assertNotNull(d.get("CN=Al Pha,OU=Alpha," + ou2), "children follow a rename");
        String ou3 = (String) ad.moveOu(ctx, ou2, staff, "ad").get("dn");
        assertEquals("OU=Programs," + staff, ou3);
        assertEquals("AD.NOT_EMPTY", assertThrows(ActionException.class, () -> ad.deleteOu(ctx, ou3, false, "ad")).code());
        ad.deleteOu(ctx, ou3, true, "ad");
        assertNull(d.get(ou3));
        assertNull(d.get("CN=Al Pha,OU=Alpha," + ou3));

        // existing actions still work through the directory layer
        ad.disable(ctx, "bob", "ad");
        assertEquals("514", d.val("CN=Bob Ray," + staff, "userAccountControl"));
        ad.deleteUser(ctx, "bob", "ad");
        assertEquals("AD.USER_NOT_FOUND", assertThrows(ActionException.class, () -> ad.getUser(ctx, "bob", "ad")).code());
    }
}
