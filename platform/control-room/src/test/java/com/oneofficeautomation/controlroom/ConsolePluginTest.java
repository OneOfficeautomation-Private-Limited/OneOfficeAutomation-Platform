package com.oneofficeautomation.controlroom;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** Optional modules add pages to the console by listing themselves as a ConsolePlugin (GET /api/plugins lists them). */
class ConsolePluginTest {
    /** A module as Document Automation would be: registered in this test's META-INF/services only. */
    public static class SampleDocumentAutomation implements ConsolePlugin {
        @Override public String id() { return "document-automation"; }
        @Override public String name() { return "Document Automation"; }
        @Override public String description() { return "Learning instances, validation and evaluation."; }
        @Override public String path() { return "/plugins/document-automation/"; }
    }

    @Test void installedModulesAreListedForTheConsole() {
        List<Map<String, Object>> all = ConsolePlugin.installed();
        assertEquals(List.of(Map.of("id", "document-automation", "name", "Document Automation", "description", "Learning instances, validation and evaluation.",
                "path", "/plugins/document-automation/")), all);
    }
}
