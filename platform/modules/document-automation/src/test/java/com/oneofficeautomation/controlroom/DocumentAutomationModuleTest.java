package com.oneofficeautomation.controlroom;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/**
 * The Document Automation module as One CommandCenter sees it: its console page (GET /api/plugins), the module running
 * with its version (GET /api/modules), and what it registered through the module contract.
 */
class DocumentAutomationModuleTest {
    @Test void installedModulesAreListedForTheConsole() {
        // Document Automation (its pages at /documents/, the DocFlow service behind them) fills AI > Document Automation
        List<Map<String, Object>> all = ConsolePlugin.installed();
        assertEquals(1, all.size(), String.valueOf(all));
        assertEquals("document-automation", all.get(0).get("id"));
        assertEquals("Document Automation", all.get(0).get("name"));
        assertEquals("/documents/", all.get(0).get("path"));
        assertFalse(String.valueOf(all.get(0).get("description")).isBlank());
    }

    @Test void theModuleRunsAndRegistersWhatItAdds() throws Exception {
        java.nio.file.Path data = java.nio.file.Files.createTempDirectory("ooa-docauto-module");
        try (Db db = Db.embedded(data)) {
            ControlRoom cr = new ControlRoom(db, data, com.oneofficeautomation.packages.FirstPartyPackages.registry(), null);
            assertEquals(List.of(Map.of("id", "document-automation", "name", "Document Automation",
                    "version", com.oneofficeautomation.modules.docauto.DocumentAutomationModule.VERSION, "schema", 0)), cr.modules.list());
            assertTrue(cr.modules.userRoutes.containsKey("docflow"));
            assertTrue(cr.modules.agentRoutes.containsKey("docflow/documents"));
            assertTrue(cr.modules.callbacks.keySet().containsAll(List.of("docflow-callback/start-bot", "docflow-callback/events")));
            assertEquals(2, cr.modules.jobs.size(), "review tasks and email inboxes");
            assertNotNull(cr.modules.taskKind("document_review"));
            assertNotNull(cr.modules.taskKind("document_review").completeElsewhere(), "a review closes on the review screen, not in the Task inbox");
            for (String kind : com.oneofficeautomation.modules.docauto.DocFlowProxy.DOCUMENT_EVENTS.values()) assertTrue(TriggerKinds.pushed(kind), kind);
            assertEquals("", cr.platform.setting("docflow_url"));
            assertEquals(1, ((Number) cr.platform.setting("mcp_documents")).intValue());
            assertEquals(1, cr.modules.mcpTools.size());
            assertEquals(1, cr.modules.templateParts.size());
        }
    }
}
