package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ui.InterruptRules;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.function.Function;

import static com.oneofficeautomation.packages.PackagesTest.*;
import static com.oneofficeautomation.packages.TriggerLoopTest.drain;
import static com.oneofficeautomation.packages.TriggerLoopTest.later;
import static org.junit.jupiter.api.Assertions.*;

/**
 * G1 (AA parity, 21 Nov 2026): the trigger loop's new events (files changed, gone or renamed; windows opening and
 * closing; services changing state; new or changed items of any loop type or list command) and interrupt rules that
 * fire on any condition. Desktop and service states are fakes; files are real.
 */
class TriggerLoopG1Test {
    final TriggerLoopPackage tl = new TriggerLoopPackage();
    final java.util.function.Supplier<List<String>> realTitles = TriggerLoopPackage.windowTitles;
    final java.util.function.BiFunction<ActionContext, String, String> realService = TriggerLoopPackage.serviceState;

    @AfterEach void putBack() { TriggerLoopPackage.windowTitles = realTitles; TriggerLoopPackage.serviceState = realService; }

    @SuppressWarnings("unchecked")
    private static List<String> field(List<Object> events, String f) { return events.stream().map(e -> String.valueOf(((Map<String, Object>) e).get(f))).toList(); }

    @Test void aChangedFileIsReportedOnce() throws Exception {
        Path w = tmp();
        Path f = Files.writeString(w.resolve("rates.csv"), "1");
        Files.setLastModifiedTime(f, java.nio.file.attribute.FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        later(200, () -> Files.writeString(f, "1,2,3"));
        List<Object> ev = drain(tl.fileArrives(PackagesTestSupport.ctx(w, Map.of()), ".", "*.csv", false, 0, 0.05, 1, 5, "modified", "files", false));
        assertEquals(List.of("modified"), field(ev, "change"));
        assertEquals(List.of("rates.csv"), field(ev, "name"));
    }

    @Test void aDeletedFileAndARenamedFile() throws Exception {
        Path w = tmp();
        Path a = Files.writeString(w.resolve("a.txt"), "a"), b = Files.writeString(w.resolve("b.txt"), "bb");
        later(200, () -> Files.delete(a));
        List<Object> gone = drain(tl.fileArrives(PackagesTestSupport.ctx(w, Map.of()), ".", "*.txt", false, 0, 0.05, 1, 5, "deleted", "files", false));
        assertEquals(List.of("deleted"), field(gone, "change"));
        assertEquals(List.of("a.txt"), field(gone, "name"));

        later(200, () -> Files.move(b, w.resolve("c.txt")));
        List<Object> moved = drain(tl.fileArrives(PackagesTestSupport.ctx(w, Map.of()), ".", "*.txt", false, 0, 0.05, 1, 5, "renamed", "files", false));
        assertEquals(List.of("renamed"), field(moved, "change"));
        assertEquals(List.of("c.txt"), field(moved, "name"));
        assertEquals(List.of("b.txt"), field(moved, "old_name"));
    }

    @Test void foldersInSubfoldersAreWatchedWhenAsked() throws Exception {
        Path w = tmp();
        Files.createDirectories(w.resolve("in"));
        later(200, () -> Files.createDirectories(w.resolve("in/2026")));
        List<Object> ev = drain(tl.fileArrives(PackagesTestSupport.ctx(w, Map.of()), ".", "*", false, 0, 0.05, 1, 5, "created", "folders", true));
        assertEquals(List.of("2026"), field(ev, "name"));
        assertEquals(List.of("folder"), field(ev, "event"));
    }

    @Test void anUnknownChangeIsRefused() throws Exception {
        var e = assertThrows(ActionException.class, () -> tl.fileArrives(PackagesTestSupport.ctx(tmp(), Map.of()), ".", "*", false, 0, 0.05, 1, 1, "moved", "files", false));
        assertEquals("TRIGGER.BAD_EVENT", e.code());
    }

    @Test void windowsOpeningAndClosing() throws Exception {
        List<String> titles = new ArrayList<>(List.of("Inbox - Outlook"));
        TriggerLoopPackage.windowTitles = () -> new ArrayList<>(titles);
        later(150, () -> titles.add("Invoice 42 - SAP"));
        later(400, () -> titles.remove("Invoice 42 - SAP"));
        List<Object> ev = drain(tl.windowChanges(PackagesTestSupport.ctx(tmp(), Map.of()), "*Invoice*", "any", false, 0.05, 2, 5));
        assertEquals(List.of("opened", "closed"), field(ev, "change"));
        assertEquals(List.of("Invoice 42 - SAP", "Invoice 42 - SAP"), field(ev, "title"));
    }

    @Test void servicesChangingState() throws Exception {
        String[] state = {"RUNNING"};
        TriggerLoopPackage.serviceState = (c, n) -> state[0];
        later(150, () -> state[0] = "STOP_PENDING");    // a change still going on is not reported
        later(300, () -> state[0] = "STOPPED");
        later(500, () -> state[0] = "RUNNING");
        List<Object> ev = drain(tl.serviceChanges(PackagesTestSupport.ctx(tmp(), Map.of()), "Spooler", "any", true, 0.05, 3, 5));
        assertEquals(List.of("is_running", "stops", "starts"), field(ev, "change"));
        assertEquals("STOPPED", field(ev, "state").get(1));
    }

    @Test void newAndChangedItemsOfAnySource() throws Exception {
        List<Map<String, Object>> records = new ArrayList<>(List.of(Map.of("sys_id", "1", "state", "new"), Map.of("sys_id", "2", "state", "new")));
        List<Map<String, Object>> asked = new ArrayList<>();
        Function<Map<String, Object>, Object> source = req -> { asked.add(req); return new ArrayList<>(records); };
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of(Runner.ITEM_SOURCE_SERVICE, source));
        later(150, () -> { records.add(Map.of("sys_id", "3", "state", "new")); records.set(0, Map.of("sys_id", "1", "state", "closed")); });
        List<Object> ev = drain(tl.newItems(ctx, "ooa.servicenow/query", Map.of("table", "incident"), "snow", null, "sys_id", "state", false, 0.1, 2, 5));
        assertEquals(Set.of("1:changed", "3:new"), new HashSet<>(ev.stream().map(e -> ((Map<?, ?>) e).get("sys_id") + ":" + ((Map<?, ?>) e).get("trigger_event")).toList()));
        assertEquals("ooa.servicenow/query", asked.get(0).get("source"));
        assertEquals("snow", asked.get(0).get("connection"));
    }

    @SuppressWarnings("unchecked")
    @Test void newItemsReadsACommandsListThroughTheRunner() throws Exception {
        Path w = tmp();
        Files.createDirectories(w.resolve("in/old"));
        later(300, () -> Files.createDirectories(w.resolve("in/2026-11")));
        Map<String, Object> o = run(w, "[{\"name\":\"ev\",\"type\":\"Dictionary\"},{\"name\":\"names\",\"type\":\"List\",\"direction\":\"output\",\"default\":[]}]",
                "[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"ooa.triggerloop/new_items\","
                + "\"iterator_args\":{\"source\":\"ooa.folder/list_subfolders\",\"source_inputs\":{\"path\":\"in\"},\"poll_seconds\":1,\"max_events\":1,\"max_seconds\":8},\"as\":\"ev\"},\"children\":["
                + "{\"id\":\"a\",\"action\":\"ooa.list/add_item\",\"args\":{\"list\":\"$names\",\"item\":\"$ev.item\"},\"out\":{\"list\":\"names\"}}]}]", Map.of());
        List<Object> names = (List<Object>) o.get("names");
        assertEquals(1, names.size(), String.valueOf(names));
        assertTrue(String.valueOf(names.get(0)).endsWith("2026-11"), "only the folder that is new: " + names);
    }

    @Test void interruptRulesMayWatchAnyCondition() {
        InterruptRules rules = UiResilienceTest.rules();
        rules.add(new InterruptRules.Rule("low disk", null, null, null, "run_agent", "free_space", Map.of(), 0, Map.of("condition", "ooa.file/exists")));
        boolean[] holds = {false};
        UiResilienceTest.FakeScreen s = new UiResilienceTest.FakeScreen() {
            @Override public Boolean conditionTrue(Object condition) { return holds[0]; }
        };
        List<String> log = new ArrayList<>();
        assertEquals(List.of(), rules.check(s, s, log::add));
        holds[0] = true;
        assertEquals(List.of("low disk"), rules.check(s, s, log::add));
        assertTrue(s.actions.get(0).startsWith("agent free_space"));
        assertTrue(log.stream().anyMatch(l -> l.contains("its condition")), String.valueOf(log));
        assertTrue(((Map<?, ?>) rules.describe().get(0)).containsKey("when_condition"));
    }

    @Test void aRuleNamesExactlyOneThingToWatch() {
        var e = assertThrows(ActionException.class, () -> new InterruptRules.Rule("both", "*Error*", null, null, "ignore", null, null, 0, Map.of("condition", "x")));
        assertEquals("INTERRUPT.BAD_RULE", e.code());
    }
}
