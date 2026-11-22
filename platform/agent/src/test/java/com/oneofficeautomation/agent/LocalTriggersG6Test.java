package com.oneofficeautomation.agent;

import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** G6 runner triggers: renamed files and folders, "also start if it already exists", paused services, both process events, case-sensitive titles. */
class LocalTriggersG6Test {
    final LocalTriggersTest base = new LocalTriggersTest();

    static Map<String, Object> folder(String id, String kind, Path dir, String pattern, Map<String, Object> cfg) {
        return Map.of("id", id, "kind", kind, "folder", dir.toString(), "pattern", pattern, "config", cfg);
    }

    @Test void renamesAreOneToOnePairs() throws Exception {
        Path dir = Files.createTempDirectory("ooa-g6");
        Files.writeString(dir.resolve("a.csv"), "12345");
        Files.createDirectory(dir.resolve("box"));
        LocalTriggers lt = base.triggers(false);
        lt.update(List.of(folder("ren", "file_renamed", dir, "*.csv", Map.of()), folder("dren", "folder_renamed", dir, "*", Map.of())));
        Files.move(dir.resolve("a.csv"), dir.resolve("b.csv"));
        Files.move(dir.resolve("box"), dir.resolve("crate"));
        lt.pollOnce();
        Collections.sort(base.fired);
        assertEquals(2, base.fired.size(), String.valueOf(base.fired));
        assertTrue(base.fired.get(0).startsWith("dren ") && base.fired.get(0).contains("name=crate") && base.fired.get(0).contains("old_name=box"), base.fired.get(0));
        assertTrue(base.fired.get(1).startsWith("ren ") && base.fired.get(1).contains("name=b.csv") && base.fired.get(1).contains("old_name=a.csv")
                && base.fired.get(1).contains("old_path=" + dir.resolve("a.csv")), base.fired.get(1));
        base.fired.clear();
        Files.delete(dir.resolve("b.csv"));
        Files.writeString(dir.resolve("other.csv"), "xy");   // a delete and an unrelated new file are no rename
        lt.pollOnce();
        assertEquals(List.of(), base.fired);
    }

    @Test void alsoStartForWhatIsAlreadyThere() throws Exception {
        Path dir = Files.createTempDirectory("ooa-g6");
        Files.writeString(dir.resolve("waiting.csv"), "1");
        Files.createDirectory(dir.resolve("old"));
        LocalTriggers lt = base.triggers(false);
        lt.update(List.of(folder("f", "file", dir, "*.csv", Map.of("fire_existing", true)), folder("d", "folder_created", dir, "*", Map.of("fire_existing", true)),
                folder("plain", "folder_created", dir, "*", Map.of())));
        assertEquals(1, base.fired.size(), "a folder already there fires at the first look: " + base.fired);
        assertTrue(base.fired.get(0).startsWith("d ") && base.fired.get(0).contains("name=old"));
        base.fired.clear();
        lt.pollOnce();
        assertEquals(1, base.fired.size(), String.valueOf(base.fired));
        assertTrue(base.fired.get(0).startsWith("f ") && base.fired.get(0).contains("waiting.csv"), "a file already there fires once it is settled");
        base.fired.clear();
        lt.pollOnce();
        assertEquals(List.of(), base.fired, "each fires once");
    }

    @Test void pausedServicesBothProcessEventsAndCaseSensitiveTitles() {
        LocalTriggers lt = base.triggers(false);
        base.services.put("Spooler", "RUNNING");
        base.windows.add("Invoice - Editor");
        lt.update(List.of(LocalTriggersTest.t("sp", "service_paused", Map.of("service", "Spooler")),
                LocalTriggersTest.t("pr", "process_started", Map.of("process", "notepad.exe", "events", "both")),
                LocalTriggersTest.t("cs", "window_opened", Map.of("title", "INVOICE", "case_sensitive", true)),
                LocalTriggersTest.t("ci", "window_opened", Map.of("title", "INVOICE"))));
        base.services.put("Spooler", "PAUSED");
        base.processes.add("notepad.exe");
        base.windows.add("Invoice 2");
        base.windows.add("INVOICE 3");
        lt.pollOnce();
        Collections.sort(base.fired);
        assertEquals(List.of("ci {title=INVOICE 3}", "ci {title=Invoice 2}", "cs {title=INVOICE 3}", "pr {event=started, process=notepad.exe}",
                "sp {previous_state=RUNNING, service=Spooler, state=PAUSED}"), base.fired);
        base.fired.clear();
        base.processes.clear();
        lt.pollOnce();
        assertEquals(List.of("pr {event=stopped, process=notepad.exe}"), base.fired);
        assertTrue(LocalTriggers.caseSensitive("re:^Inv").test("Invoice"));
        assertFalse(LocalTriggers.caseSensitive("re:^inv").test("Invoice"));
        assertTrue(LocalTriggers.caseSensitive("Inv*").test("Invoice"));
        assertFalse(LocalTriggers.caseSensitive("inv*").test("Invoice"));
        assertEquals(-1, LocalTriggers.processEvents("process_stopped", null));
        assertEquals(1, LocalTriggers.processEvents("process_stopped", "started"));
    }
}
