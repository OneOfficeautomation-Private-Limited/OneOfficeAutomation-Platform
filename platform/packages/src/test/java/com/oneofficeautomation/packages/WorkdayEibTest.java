package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.RestToolkitTestSupport.*;
import static org.junit.jupiter.api.Assertions.*;

/** Launch EIB with file: Launch_EIB with the file as Attachment_Data of the data source's field override. */
class WorkdayEibTest {
    @Test void launchesAnInboundEibWithItsFile() throws Exception {
        String wid = "abcdef0123456789abcdef0123456789";
        try (Fake f = new Fake()) {
            f.on("POST /ccx/service/acme/Integrations/v42.0", c -> ok("<env:Envelope xmlns:env=\"http://schemas.xmlsoap.org/soap/envelope/\"><env:Body><wd:Launch_EIB_Response xmlns:wd=\"urn:com.workday/bsvc\">"
                    + "<wd:Integration_Event><wd:Integration_Event_Reference><wd:ID wd:type=\"WID\">" + wid + "</wd:ID></wd:Integration_Event_Reference></wd:Integration_Event></wd:Launch_EIB_Response></env:Body></env:Envelope>"));
            Path dir = PackagesTest.tmp();
            Files.writeString(dir.resolve("workers & co.xml"), "<Workers/>");
            ActionContext ctx = ctx(dir);
            WorkdayPackage wd = new WorkdayPackage();
            Map<String, Object> r = wd.launchEib(ctx, f.url(), "acme", "INT_Worker_Load", "workers & co.xml", "Worker Data", null, "v42.0", null, null, TOKEN, null);
            assertEquals(wid, r.get("event_id"));
            Call c = f.last("POST", ".*");
            String x = c.text();
            assertTrue(x.contains("<bsvc:Launch_EIB_Request bsvc:version=\"v42.0\"><bsvc:Integration_System_Reference><bsvc:ID bsvc:type=\"Integration_System_ID\">INT_Worker_Load</bsvc:ID>"), x);
            assertTrue(x.contains("<bsvc:Service_Component_Data><bsvc:Data_Source>true</bsvc:Data_Source><bsvc:Field_Override_Data><bsvc:Field_Name>Worker Data</bsvc:Field_Name>"), x);
            assertTrue(x.contains("<bsvc:Attachment_Data bsvc:Filename=\"workers &amp; co.xml\" bsvc:Content_Type=\"text/xml\"><bsvc:File_Content>"
                    + Base64.getEncoder().encodeToString("<Workers/>".getBytes()) + "</bsvc:File_Content></bsvc:Attachment_Data>"), x);
            assertEquals("Bearer tok-123", c.auth());

            assertEquals("WORKDAY.NO_FILE_FIELD", assertThrows(ActionException.class, () -> wd.launchEib(ctx, f.url(), "acme", "INT_Worker_Load", "workers & co.xml", " ", null, "v42.0", null, null, TOKEN, null)).code());
            assertEquals("FILE.NOT_FOUND", assertThrows(ActionException.class, () -> wd.launchEib(ctx, f.url(), "acme", "INT_Worker_Load", "none.xlsx", "F", null, "v42.0", null, null, TOKEN, null)).code());
            assertEquals("WORKDAY.BAD_ID", assertThrows(ActionException.class, () -> wd.launchEib(ctx, f.url(), "acme", "INT_Load", "workers & co.xml", "F", null, "v42.0", null, null, TOKEN, "wid")).code());
        }
        assertEquals("application/vnd.openxmlformats-officedocument.spreadsheetml.sheet", WorkdayPackage.mimeType("Load.XLSX"));
    }
}
