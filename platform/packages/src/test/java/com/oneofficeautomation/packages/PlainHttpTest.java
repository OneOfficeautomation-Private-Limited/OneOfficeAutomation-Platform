package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** K6 (command review, batch 10): Jira, Asana, Azure DevOps and GitHub send a sign-in over plain http only when allowed. */
class PlainHttpTest {
    @Test void plainHttpOnlyToThisComputerOrAnAllowedHost() {
        assertEquals("https://acme.atlassian.net/rest", HttpJson.plainHttpCheck("JIRA", "https://acme.atlassian.net/rest"));
        assertEquals("http://127.0.0.1:8080/x", HttpJson.plainHttpCheck("GITHUB", "http://127.0.0.1:8080/x"));
        assertEquals("http://localhost/x", HttpJson.plainHttpCheck("ASANA", "http://localhost/x"));
        ActionException e = assertThrows(ActionException.class, () -> HttpJson.plainHttpCheck("AZURE_DEVOPS", "http://tfs.corp.local/DefaultCollection"));
        assertEquals("AZURE_DEVOPS.PLAIN_HTTP", e.code());
        assertTrue(e.suggestedFix().contains("OOA_ALLOW_PLAIN_HTTP"));
        assertEquals("http://anything.example/x", HttpJson.plainHttpCheck("REST", "http://anything.example/x"), "other toolkits decide for themselves");
        System.setProperty("ooa.allow_plain_http", "build.corp.local, tfs.corp.local");
        try { assertEquals("http://tfs.corp.local/x", HttpJson.plainHttpCheck("AZURE_DEVOPS", "http://tfs.corp.local/x")); }
        finally { System.clearProperty("ooa.allow_plain_http"); }
    }
}
