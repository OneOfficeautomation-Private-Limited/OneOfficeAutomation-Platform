package com.oneofficeautomation.controlroom;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Optional modules add pages to the console by listing themselves as a ConsolePlugin (GET /api/plugins lists them). */
class ConsolePluginTest {
    @Test void installedModulesAreListedForTheConsole() {
        // Document Automation (its pages at /documents/, the DocFlow service behind them) fills AI > Document Automation
        List<Map<String, Object>> all = ConsolePlugin.installed();
        assertEquals(1, all.size(), String.valueOf(all));
        assertEquals("document-automation", all.get(0).get("id"));
        assertEquals("Document Automation", all.get(0).get("name"));
        assertEquals("/documents/", all.get(0).get("path"));
        assertFalse(String.valueOf(all.get(0).get("description")).isBlank());
    }
}
