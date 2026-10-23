package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Catch entries: exact codes, PREFIX.*, kinds that cut across toolkits (NOT_FOUND, TIMEOUT ...) and older codes. */
class ErrorCodesTest {
    @Test void kindsCoverTheirCodesAcrossToolkits() {
        assertTrue(ErrorCodes.matches("NOT_FOUND", "FILE.NOT_FOUND"));
        assertTrue(ErrorCodes.matches("NOT_FOUND", "BROWSER.OBJECT_NOT_FOUND"));
        assertTrue(ErrorCodes.matches("not_found", "AD.USER_NOT_FOUND"), "kind names are not case sensitive");
        assertFalse(ErrorCodes.matches("NOT_FOUND", "FILE.IO_ERROR"));
        assertTrue(ErrorCodes.matches("FILE_NOT_FOUND", "FOLDER.NOT_FOUND"));
        assertTrue(ErrorCodes.matches("FILE_NOT_FOUND", "REST.FILE_NOT_FOUND"));
        assertFalse(ErrorCodes.matches("FILE_NOT_FOUND", "WINDOW.NOT_FOUND"));
        assertTrue(ErrorCodes.matches("WINDOW_NOT_FOUND", "BROWSER.WINDOW_NOT_FOUND"));
        assertTrue(ErrorCodes.matches("WINDOW_NOT_FOUND", "WINDOW.NOT_FOUND"));
        assertTrue(ErrorCodes.matches("OBJECT_NOT_FOUND", "UI.ELEMENT_NOT_FOUND"));
        assertTrue(ErrorCodes.matches("OBJECT_NOT_FOUND", "TERMINAL.FIELD_NOT_FOUND"));
        assertTrue(ErrorCodes.matches("OBJECT_NOT_FOUND", "IMAGE.NOT_FOUND"));
        assertTrue(ErrorCodes.matches("TIMEOUT", "BROWSER.PAGE_TIMEOUT"));
        assertTrue(ErrorCodes.matches("TIMEOUT", "RUN.TIMEOUT"));
        assertFalse(ErrorCodes.matches("TIMEOUT", "AD.BAD_TIMEOUT"), "a wrong time-out setting is not a time-out");
        assertTrue(ErrorCodes.matches("AUTH_FAILED", "AZURE_DEVOPS.AUTH_FAILED"));
        assertTrue(ErrorCodes.matches("AUTH_FAILED", "SAP.LOGON_FAILED"));
        assertTrue(ErrorCodes.matches("RATE_LIMITED", "GITHUB.RATE_LIMITED"));
    }

    @Test void exactCodesPrefixesAndOlderCodes() {
        assertTrue(ErrorCodes.matches("FILE.NOT_FOUND", "FILE.NOT_FOUND"));
        assertTrue(ErrorCodes.matches("BROWSER.*", "BROWSER.TIMEOUT"));
        assertFalse(ErrorCodes.matches("BROWSER.*", "FILE.NOT_FOUND"));
        assertTrue(ErrorCodes.matches("SAP.ERROR", "SAP.OBJECT_NOT_FOUND"), "agents that caught SAP.ERROR keep catching SAP scripting errors");
        assertTrue(ErrorCodes.matches("SAP.ERROR", "SAP.NOT_RUNNING"));
        assertFalse(ErrorCodes.matches("SAP.ERROR", "SAP.BAD_TCODE"), "input checks were never SAP.ERROR");
        assertTrue(ErrorCodes.anyMatches(List.of(), "ANY.CODE"), "an empty list catches everything");
        assertFalse(ErrorCodes.anyMatches(List.of("EXCEL.*", "TIMEOUT"), "FILE.NOT_FOUND"));
    }

    @Test @SuppressWarnings("unchecked") void aCatchOnAKindRunsForAnyToolkitsError() {
        Map<String, Object> flow = (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"k","name":"kinds","variables":[{"name":"seen","type":"String","direction":"output"}],
             "steps":[{"id":"t","action":"core/try","children":[{"id":"boom","action":"core/throw","args":{"code":"BROWSER.OBJECT_NOT_FOUND","message":"no button"}}],
                       "catch":[{"codes":["TIMEOUT"],"children":[{"id":"a","action":"core/set","args":{"variable":"seen","value":"timeout"}}]},
                                {"codes":["OBJECT_NOT_FOUND"],"children":[{"id":"b","action":"core/set","args":{"variable":"seen","value":"object"}}]}]}]}""");
        RunResult r = Runner.builder(RunnerTest.registry()).build().run(flow, Map.of());
        assertTrue(r.ok(), String.valueOf(r.error()));
        assertEquals("object", r.outputs().get("seen"), "the first Catch whose kind covers the code runs");
    }
}
