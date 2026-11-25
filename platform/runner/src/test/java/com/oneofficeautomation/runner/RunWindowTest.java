package com.oneofficeautomation.runner;

import com.oneofficeautomation.sdk.JsonLite;
import org.junit.jupiter.api.Test;

import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Item 1 (21 Nov 2026): the run window shared by OneAgentStudio and runners. How it is asked for (true, a size, or
 * size/minimised/controls from One CommandCenter), and what it shows: the line being run, lines done, the last lines,
 * and how the run ended.
 */
class RunWindowTest {
    @Test void askedForAsTrueASizeOrTheJobsSettings() {
        assertNull(RunWindow.Options.from(null));
        assertNull(RunWindow.Options.from(false));
        assertNull(RunWindow.Options.from("hidden"));
        assertEquals(RunWindow.Options.DEFAULT, RunWindow.Options.from(true));
        assertEquals(RunWindow.Size.LARGE, RunWindow.Options.from("large").size());
        RunWindow.Options o = RunWindow.Options.from(Map.of("size", "small", "minimised", true, "controls", false));
        assertEquals(new RunWindow.Options(RunWindow.Size.SMALL, true, false), o);
        assertEquals(RunWindow.Size.MEDIUM, RunWindow.Options.from(Map.of("size", "odd")).size(), "an unknown size is Medium");
        assertNull(RunWindow.Options.from(Map.of("show", false)));
    }

    @Test void noWindowOnALinuxRunnerWithoutADisplay() {
        assertFalse(RunWindow.hasDisplay("Linux", Map.of()), "headless Linux (CI, a server runner): no window");
        assertFalse(RunWindow.hasDisplay("Linux", Map.of("DISPLAY", " ")));
        assertTrue(RunWindow.hasDisplay("Linux", Map.of("DISPLAY", ":0")));
        assertTrue(RunWindow.hasDisplay("Linux", Map.of("WAYLAND_DISPLAY", "wayland-0")));
        assertTrue(RunWindow.hasDisplay("Windows 11", Map.of()));
        assertTrue(RunWindow.hasDisplay("Mac OS X", Map.of()));
    }

    @Test void openNeverStopsTheRunWhenTheWindowCannotBeShown() {
        if (RunWindow.available()) return;   // a desktop: the window would really open
        assertNull(RunWindow.open("A", flow(), new EventSink.ListSink(), new java.util.concurrent.atomic.AtomicBoolean(),
                new java.util.concurrent.atomic.AtomicBoolean(), () -> true, a -> a, RunWindow.Options.DEFAULT));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> flow() {
        return (Map<String, Object>) JsonLite.parse("""
            {"kind":"TaskBot","id":"w","name":"W","steps":[
              {"id":"a","action":"ooa.log/write"},
              {"id":"loop","action":"core/loop","children":[{"id":"b","action":"ooa.excel/open"}]},
              {"id":"c","action":"ooa.excel/close"}]}""");
    }

    private static RunEvent ev(String type, String step, Map<String, Object> data) { return new RunEvent("r", 0, type, 0, step, data); }

    @Test void showsTheLineBeingRunAndTheLinesDone() {
        RunWindow.Progress p = new RunWindow.Progress(flow());
        assertEquals(4, p.total, "every line as on the canvas, nested ones too");
        List<RunEvent> evs = new ArrayList<>(List.of(
            ev("step.started", "a", Map.of("action", "ooa.log/write")),
            ev("log", "a", Map.of("message", "hello")),
            ev("step.finished", "a", Map.of("action", "ooa.log/write")),
            ev("step.started", "b", Map.of("action", "ooa.excel/open"))));
        p.read(evs, a -> a.equals("ooa.excel/open") ? "Open workbook" : a);
        assertEquals("Line 3: Open workbook", p.line);
        assertEquals(1, p.done());
        assertEquals(List.of("• hello", "✓ 1  ooa.log/write"), List.copyOf(p.last));
        evs.add(ev("step.failed", "b", Map.of("action", "ooa.excel/open", "code", "EXCEL.NOT_FOUND", "message", "No such file")));
        evs.add(ev("run.finished", null, Map.of("status", "failed")));
        p.read(evs, a -> a);
        assertEquals("Failed", p.outcome());
        assertEquals("EXCEL.NOT_FOUND: No such file", p.failed);
        assertTrue(p.last.getLast().startsWith("✗ 3"));
    }

    @Test void aLineRepeatedByALoopCountsOnce() {
        RunWindow.Progress p = new RunWindow.Progress(flow());
        List<RunEvent> evs = new ArrayList<>();
        for (int i = 0; i < 5; i++) evs.add(ev("step.finished", "b", Map.of("action", "ooa.excel/open")));
        p.read(evs, a -> a);
        assertEquals(1, p.done(), "five rounds of one line in a loop");
    }

    @Test void keepsOnlyTheLastLinesAndSaysStoppedOrFinished() {
        RunWindow.Progress p = new RunWindow.Progress(flow());
        List<RunEvent> evs = new ArrayList<>();
        for (int i = 0; i < 20; i++) evs.add(ev("log", null, Map.of("message", "line " + i)));
        evs.add(ev("run.finished", null, Map.of("status", "cancelled")));
        p.read(evs, a -> a);
        assertEquals(RunWindow.Progress.LAST_LINES, p.last.size());
        assertEquals("• line 19", p.last.getLast());
        assertEquals("Stopped", p.outcome());
        RunWindow.Progress q = new RunWindow.Progress(flow());
        q.read(List.of(ev("run.finished", null, Map.of("status", "ok"))), a -> a);
        assertEquals("Finished", q.outcome());
    }

    @Test void timeIsWrittenAsPeopleSayIt() {
        assertEquals("42 s", RunWindow.time(42));
        assertEquals("2 min 5 s", RunWindow.time(125));
        assertEquals("1 h 1 min", RunWindow.time(3660));
    }

    @Test void commandNamesComeFromTheToolkits() {
        var names = RunWindow.names(List.of(Map.of("id", "ooa.excel", "actions", List.of(Map.of("id", "open", "name", "Open workbook")))));
        assertEquals("Open workbook", names.apply("ooa.excel/open"));
        assertEquals("ooa.x/y", names.apply("ooa.x/y"));
    }
}
