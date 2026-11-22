package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.ConnectionSession;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Item 9: the SaaS toolkits' Connect step keeps a saved connection for the later steps; Disconnect forgets it. */
class SavedConnectionStepsTest {
    @Test void connectKeepsTheConnectionAndDisconnectForgetsIt() throws Exception {
        Path w = Files.createTempDirectory("ooa-conn");
        Files.writeString(w.resolve("connections.json"), "{\"team-slack\":{\"package\":\"ooa.slack\"},\"jira-prod\":{\"package\":\"ooa.jira\"}}");
        ActionContext ctx = PackagesTestSupport.ctx(w, Map.of());
        SlackPackage slack = new SlackPackage();
        assertEquals(Map.of("connection", "team-slack"), slack.connectSaved(ctx, "team-slack", null, false));
        assertEquals(new ConnectionSession("ooa.slack", "team-slack").connection(), ((ConnectionSession) ctx.sessions().get("slack")).connection());
        assertEquals("CONNECTION.WRONG_TOOLKIT", assertThrows(ActionException.class, () -> slack.connectSaved(ctx, "jira-prod", null, false)).code());
        assertEquals("CONNECTION.NOT_FOUND", assertThrows(ActionException.class, () -> slack.connectSaved(ctx, "nope", null, false)).code());
        slack.disconnectSaved(ctx, null);
        assertNull(SavedConnections.current(ctx, "ooa.slack"));
        assertEquals(Map.of("connection", "jira-prod"), new JiraPackage().connectSaved(ctx, "jira-prod", "work", false));
        assertEquals("jira-prod", SavedConnections.current(ctx, "ooa.jira"));
    }
}
