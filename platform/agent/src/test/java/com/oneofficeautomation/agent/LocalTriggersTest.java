package com.oneofficeautomation.agent;

import com.oneofficeautomation.packages.ui.InputTriggers;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** Runner-side trigger evaluation with a fake operating system and fake hotkeys; folders use a temporary directory. */
class LocalTriggersTest {
    final List<String> fired = new ArrayList<>();
    final Map<String, String> status = new HashMap<>();
    final List<String> windows = new ArrayList<>(), processes = new ArrayList<>();
    final Map<String, String> services = new HashMap<>();
    final Map<String, Runnable> hotkeys = new HashMap<>();

    LocalTriggers triggers(boolean withHotkeys) {
        LocalTriggers.OsProbe os = new LocalTriggers.OsProbe() {
            public List<String> windowTitles() { return new ArrayList<>(windows); }
            public List<String> processNames() { return new ArrayList<>(processes); }
            public String serviceState(String name) { return services.get(name); }
        };
        LocalTriggers.Hotkeys hk = (combo, cb) -> {
            if (combo.equals("CTRL+ALT+DELETE")) throw new IllegalStateException("reserved by Windows");
            hotkeys.put(combo, cb);
            return () -> hotkeys.remove(combo);
        };
        return new LocalTriggers(new LocalTriggers.Reporter() {
            public void fired(String id, Map<String, Object> ev) { fired.add(id + " " + new TreeMap<>(ev)); }
            public void status(String id, String error) { status.put(id, error); }
        }, os, withHotkeys ? hk : null, s -> { });
    }

    static Map<String, Object> t(String id, String kind, Map<String, Object> config) { return Map.of("id", id, "kind", kind, "config", config); }
    static Map<String, Object> folder(String id, String kind, Path dir, String pattern) { return Map.of("id", id, "kind", kind, "folder", dir.toString(), "pattern", pattern, "config", Map.of()); }

    @Test void folderKinds() throws Exception {
        Path dir = Files.createTempDirectory("ooa-trg");
        Files.writeString(dir.resolve("old.csv"), "1");
        Files.createDirectory(dir.resolve("keep"));
        LocalTriggers lt = triggers(false);
        lt.update(List.of(folder("arrive", "file", dir, "*.csv"), folder("change", "file_changed", dir, "*.csv"), folder("delete", "file_deleted", dir, "*.csv"),
                folder("mk", "folder_created", dir, "*"), folder("rm", "folder_deleted", dir, "*")));
        lt.pollOnce();
        assertEquals(List.of(), fired, "the first look only records what is there");
        Files.writeString(dir.resolve("new.csv"), "a,b");
        Files.writeString(dir.resolve("ignored.txt"), "x");
        Files.createDirectory(dir.resolve("batch-1"));
        lt.pollOnce();
        assertEquals(List.of("mk {folder=" + dir + ", name=batch-1, path=" + dir.resolve("batch-1") + "}"), fired, "a new file waits one more look until it is complete");
        fired.clear();
        lt.pollOnce();
        assertEquals(1, fired.size());
        assertTrue(fired.get(0).startsWith("arrive ") && fired.get(0).contains("name=new.csv") && fired.get(0).contains("size=3"), fired.get(0));
        fired.clear();
        Files.writeString(dir.resolve("old.csv"), "1,2,3");
        Files.setLastModifiedTime(dir.resolve("old.csv"), java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() + 5000));
        Files.delete(dir.resolve("new.csv"));
        Files.delete(dir.resolve("keep"));
        lt.pollOnce();
        lt.pollOnce();
        Collections.sort(fired);
        assertEquals(3, fired.size(), String.valueOf(fired));
        assertTrue(fired.get(0).startsWith("change ") && fired.get(0).contains("old.csv"));
        assertTrue(fired.get(1).startsWith("delete ") && fired.get(1).contains("new.csv"));
        assertTrue(fired.get(2).startsWith("rm ") && fired.get(2).contains("keep"));
        assertNull(status.get("arrive"), "watched without problems");
    }

    @Test void missingFolderIsReportedThenRecovers() throws Exception {
        Path dir = Files.createTempDirectory("ooa-trg").resolve("later");
        LocalTriggers lt = triggers(false);
        lt.update(List.of(folder("f", "file", dir, "*")));
        lt.pollOnce();
        assertTrue(status.get("f").contains("Folder not found"), String.valueOf(status));
        Files.createDirectories(dir);
        lt.pollOnce();
        assertTrue(status.containsKey("f") && status.get("f") == null, "the error is cleared once the folder exists");
    }

    @Test void windowsProcessesAndServices() {
        windows.add("Inbox - Outlook");
        processes.addAll(List.of("explorer.exe", "EXCEL.EXE"));
        services.put("Spooler", "RUNNING");
        LocalTriggers lt = triggers(false);
        lt.update(List.of(t("wo", "window_opened", Map.of("title", "*Invoice*")), t("wc", "window_closed", Map.of("title", "re:^Inbox")),
                t("ps", "process_started", Map.of("process", "excel")), t("pe", "process_stopped", Map.of("process", "excel.exe")),
                t("ss", "service_started", Map.of("service", "Spooler")), t("st", "service_stopped", Map.of("service", "Spooler")),
                t("gone", "service_started", Map.of("service", "NoSuchSvc"))));
        lt.pollOnce();
        assertEquals(List.of(), fired);
        assertTrue(status.get("gone").contains("does not exist"));
        windows.add("Invoice 42 - Notepad");
        windows.remove("Inbox - Outlook");
        processes.add("excel.exe");                 // a second Excel starts
        services.put("Spooler", "STOPPED");
        lt.pollOnce();
        Collections.sort(fired);
        assertEquals(List.of("ps {process=excel.exe}", "st {previous_state=RUNNING, service=Spooler, state=STOPPED}", "wc {title=Inbox - Outlook}", "wo {title=Invoice 42 - Notepad}"), fired);
        fired.clear();
        processes.removeIf(p -> p.equalsIgnoreCase("excel.exe"));
        services.put("Spooler", "RUNNING");
        lt.pollOnce();
        Collections.sort(fired);
        assertEquals(List.of("pe {process=EXCEL.EXE}", "pe {process=excel.exe}", "ss {previous_state=STOPPED, service=Spooler, state=RUNNING}"), fired);
    }

    @Test void hotkeysRegisterOnceAndUnregisterWhenRemoved() {
        LocalTriggers lt = triggers(true);
        List<Object> list = List.of(t("hk", "hotkey", Map.of("hotkey", "CTRL+ALT+F9")), t("bad", "hotkey", Map.of("hotkey", "CTRL+ALT+DELETE")));
        lt.update(list);
        lt.update(list);
        assertEquals(Set.of("CTRL+ALT+F9"), hotkeys.keySet());
        assertTrue(status.get("bad").contains("reserved"), String.valueOf(status));
        hotkeys.get("CTRL+ALT+F9").run();
        assertEquals(List.of("hk {hotkey=CTRL+ALT+F9}"), fired);
        lt.update(List.of());
        assertTrue(hotkeys.isEmpty(), "removed triggers release their hotkey");
    }

    @Test void hotkeysWithoutSupportAreReportedAndUnknownKindsToo() {
        LocalTriggers lt = triggers(false);
        lt.update(List.of(t("hk", "hotkey", Map.of("hotkey", "F9")), t("x", "teleport", Map.of())));
        assertTrue(status.get("hk").contains("Windows runner"));
        assertTrue(status.get("x").contains("does not know"));
    }

    /** Fake keyboard and mouse hooks: the test plays the operating system. */
    final List<java.util.function.Consumer<InputTriggers.Window>> keyHooks = new ArrayList<>();
    final List<java.util.function.Consumer<InputTriggers.Click>> mouseHooks = new ArrayList<>();
    final InputTriggers.Native input = new InputTriggers.Native() {
        public AutoCloseable onKeyChord(InputTriggers.KeyChord chord, java.util.function.Consumer<InputTriggers.Window> onPress) { keyHooks.add(onPress); return () -> keyHooks.remove(onPress); }
        public AutoCloseable onMouseDown(java.util.function.Consumer<InputTriggers.Click> onClick) { mouseHooks.add(onClick); return () -> mouseHooks.remove(onClick); }
        public InputTriggers.ElementLookup openElementLookup() {
            return new InputTriggers.ElementLookup() {
                public Map<String, String> at(int x, int y) { return Map.of("Name", "Approve", "ControlType", "Button", "AutomationId", "ok"); }
                public void close() { }
            };
        }
    };

    @Test void keyPressAndClickTriggersHookOnceFireWithDebounceAndUnhook() {
        long[] now = {0};
        LocalTriggers lt = new LocalTriggers(new LocalTriggers.Reporter() {
            public void fired(String id, Map<String, Object> ev) { fired.add(id + " " + new TreeMap<>(ev)); }
            public void status(String id, String error) { status.put(id, error); }
        }, null, null, input, s -> { });
        lt.clock = () -> now[0];
        List<Object> list = List.of(t("kp", "key_pressed", Map.of("key", "CTRL+ENTER", "window", "*ERP*", "debounce_ms", 500)),
                t("mc", "mouse_clicked", Map.of("process", "erp.exe", "element_name", "Approve")),
                t("bad", "key_pressed", Map.of("key", "A")));
        lt.update(list);
        lt.update(list);
        assertEquals(1, keyHooks.size(), "one hook per trigger, kept across heartbeats");
        assertEquals(1, mouseHooks.size());
        assertTrue(status.get("bad").contains("typing key"), String.valueOf(status));
        keyHooks.get(0).accept(new InputTriggers.Window("Orders - ERP", "erp.exe"));
        keyHooks.get(0).accept(new InputTriggers.Window("Orders - ERP", "erp.exe"));   // within 500 ms
        keyHooks.get(0).accept(new InputTriggers.Window("Mail", "outlook.exe"));
        mouseHooks.get(0).accept(new InputTriggers.Click("left", 3, 4, new InputTriggers.Window("Orders - ERP", "erp.exe")));
        assertEquals(List.of("kp {key=CTRL+ENTER, process=erp.exe, window=Orders - ERP}",
                "mc {automation_id=ok, button=left, element_name=Approve, element_type=Button, process=erp.exe, window=Orders - ERP}"), fired);
        lt.update(List.of());
        assertTrue(keyHooks.isEmpty() && mouseHooks.isEmpty(), "removed triggers unhook");
    }

    @Test void keyPressAndClickTriggersSayWindowsOnlyWithoutHooks() {
        LocalTriggers lt = triggers(false);
        lt.update(List.of(t("kp", "key_pressed", Map.of("key", "F9")), t("mc", "mouse_clicked", Map.of("window", "ERP"))));
        assertTrue(status.get("kp").contains("Windows runners only"), String.valueOf(status));
        assertTrue(status.get("mc").contains("Windows runners only"), String.valueOf(status));
    }

    @Test void processNameMatching() {
        assertTrue(LocalTriggers.processMatcher("notepad").test("NOTEPAD.EXE"));
        assertTrue(LocalTriggers.processMatcher("notepad.exe").test("notepad"));
        assertFalse(LocalTriggers.processMatcher("note").test("notepad.exe"), "plain names match exactly");
        assertTrue(LocalTriggers.processMatcher("note*").test("notepad.exe"));
        assertTrue(LocalTriggers.SystemProbe.csv("\"a\",\"b,c\",\"d\"\"e\"").equals(List.of("a", "b,c", "d\"e")));
    }
}
