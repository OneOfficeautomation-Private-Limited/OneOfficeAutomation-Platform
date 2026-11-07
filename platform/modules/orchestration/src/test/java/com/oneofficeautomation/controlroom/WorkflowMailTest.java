package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.modules.orchestration.Workflows;
import com.oneofficeautomation.packages.FirstPartyPackages;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Workflow notifications: inbox recipients, and e-mail addresses through One CommandCenter's mail server. */
class WorkflowMailTest {
    @Test @SuppressWarnings("unchecked")
    void workflowNotificationsEmailAddressesThroughTheMailServer() throws Exception {
        Path data = Files.createTempDirectory("ooa-wfmail");
        Db db = Db.embedded(data);
        try (LicenseMailAndUsageTest.FakeSmtp smtp = new LicenseMailAndUsageTest.FakeSmtp()) {
            ControlRoom cr = new ControlRoom(db, data, FirstPartyPackages.registry(), null);
            ControlRoom.User admin = RepositoryLifecycleTest.boot(cr);
            cr.checkin(admin, "Proc/Tell.process.json", (Map<String, Object>) com.oneofficeautomation.sdk.JsonLite.parse("{\"name\":\"Tell\",\"stages\":["
                + "{\"id\":\"n\",\"type\":\"notify\",\"name\":\"Tell\",\"to\":\"user:admin, buyer@example.com\",\"subject\":\"Order ${order} approved\",\"message\":\"Order ${order} is approved\"}]}"), "v1", true, null);

            // without e-mail set up, the inbox gets it and the address is reported as not delivered
            Map<String, Object> r1 = cr.modules.service(Workflows.class).start(admin, "Proc/Tell.process.json", Map.of("order", "PO-7"), null);
            String h1 = String.valueOf(cr.modules.service(Workflows.class).run(admin, (String) r1.get("id"), null).get("history"));
            assertTrue(h1.contains("not_delivered") && h1.contains("not set up"), h1);
            assertTrue(smtp.messages.isEmpty());

            cr.platform.updateSettings(admin, Map.of("mail_smtp_host", "127.0.0.1", "mail_smtp_port", smtp.port(), "mail_smtp_security", "none", "mail_from", "ooa@example.com"), null);
            Map<String, Object> r2 = cr.modules.service(Workflows.class).start(admin, "Proc/Tell.process.json", Map.of("order", "PO-8"), null);
            String h2 = String.valueOf(cr.modules.service(Workflows.class).run(admin, (String) r2.get("id"), null).get("history"));
            assertTrue(h2.contains("emailed"), h2);
            assertEquals(1, smtp.messages.size());
            assertEquals(List.of("buyer@example.com"), smtp.rcpts);
            assertEquals("Order PO-8 approved", LicenseMailAndUsageTest.subject(smtp.messages.get(0)));
            assertEquals("Order PO-8 is approved", LicenseMailAndUsageTest.body(smtp.messages.get(0)));
        } finally { db.close(); }
    }
}
