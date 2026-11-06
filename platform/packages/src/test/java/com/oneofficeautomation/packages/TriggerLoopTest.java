package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ui.InputTriggersTest.FakeNative;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Trigger Loop iterators: directly and inside a core/loop step run by the Runner. */
class TriggerLoopTest {
    final TriggerLoopPackage tl = new TriggerLoopPackage();

    static List<Object> drain(Iterable<Object> it) {
        List<Object> out = new ArrayList<>();
        for (Object o : it) out.add(o);
        return out;
    }

    static Thread later(long ms, ThrowingRunnable r) {
        Thread t = new Thread(() -> { try { Thread.sleep(ms); r.run(); } catch (Exception e) { throw new RuntimeException(e); } });
        t.setDaemon(true);
        t.start();
        return t;
    }

    interface ThrowingRunnable { void run() throws Exception; }

    @Test void timerTicksInsideALoopStep() throws Exception {
        Map<String, Object> o = run(tmp(), "[{\"name\":\"ev\",\"type\":\"Dictionary\"},{\"name\":\"sum\",\"type\":\"Number\",\"direction\":\"output\",\"default\":0}]",
                "[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"ooa.triggerloop/timer_tick\","
                + "\"iterator_args\":{\"interval_seconds\":0.05,\"run_immediately\":true,\"max_events\":3},\"as\":\"ev\"},\"children\":["
                + "{\"id\":\"a\",\"action\":\"core/set\",\"args\":{\"variable\":\"sum\",\"value\":{\"expr\":\"$sum + $ev.tick\"}}}]}]", Map.of());
        eqNum("6", o.get("sum"));
    }

    @Test void exitLoopStopsAnEndlessTimer() throws Exception {
        Map<String, Object> o = run(tmp(), "[{\"name\":\"ev\",\"type\":\"Dictionary\"},{\"name\":\"n\",\"type\":\"Number\",\"direction\":\"output\",\"default\":0}]",
                "[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"ooa.triggerloop/timer_tick\","
                + "\"iterator_args\":{\"interval_seconds\":0.02},\"as\":\"ev\"},\"children\":["
                + "{\"id\":\"a\",\"action\":\"core/set\",\"args\":{\"variable\":\"n\",\"value\":{\"expr\":\"$n + 1\"}}},"
                + "{\"id\":\"b\",\"action\":\"core/if\",\"args\":{\"condition\":{\"op\":\"ge\",\"left\":\"$n\",\"right\":2}},\"children\":[{\"id\":\"x\",\"action\":\"core/break\"}]}]}]", Map.of());
        eqNum("2", o.get("n"));
    }

    @SuppressWarnings("unchecked")
    @Test void fileArrivesRunsOncePerNewFile() throws Exception {
        Path w = tmp();
        Path in = Files.createDirectories(w.resolve("inbox"));
        Files.writeString(in.resolve("old.csv"), "already here");
        Files.writeString(in.resolve("note.txt"), "ignored by pattern");
        var ctx = PackagesTestSupport.ctx(w, Map.of());
        later(200, () -> Files.writeString(in.resolve("b.csv"), "1"));
        later(500, () -> Files.writeString(in.resolve("c.csv"), "22"));
        long t0 = System.nanoTime();
        List<Object> ev = drain(tl.fileArrives(ctx, "inbox", "*.csv", false, 0, 0.05, 2, 10));
        assertEquals(List.of("b.csv", "c.csv"), ev.stream().map(e -> ((Map<String, Object>) e).get("name")).toList());
        eqNum("2", ((Map<String, Object>) ev.get(1)).get("size"));
        assertEquals("file", ((Map<String, Object>) ev.get(0)).get("event"));
        assertTrue(System.nanoTime() - t0 < 5_000_000_000L, "stopped after max_events, not at max_seconds");

        // include_existing, and the loop ends at max_seconds when nothing else arrives
        Map<String, Object> o = run(w, "[{\"name\":\"ev\",\"type\":\"Dictionary\"},{\"name\":\"names\",\"type\":\"List\",\"direction\":\"output\",\"default\":[]}]",
                "[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"ooa.triggerloop/file_arrives\","
                + "\"iterator_args\":{\"folder\":\"inbox\",\"pattern\":\"*.csv\",\"include_existing\":true,\"settle_seconds\":0,\"poll_seconds\":0.05,\"max_seconds\":0.5},\"as\":\"ev\"},\"children\":["
                + "{\"id\":\"a\",\"action\":\"ooa.list/add_item\",\"args\":{\"list\":\"$names\",\"item\":\"$ev.name\"},\"out\":{\"list\":\"names\"}}]}]", Map.of());
        assertEquals(List.of("b.csv", "c.csv", "old.csv"), new ArrayList<>((List<Object>) o.get("names")).stream().sorted().toList());
    }

    @SuppressWarnings("unchecked")
    @Test void fileIsHandedOverOnlyAfterItSettles() throws Exception {
        Path w = tmp();
        var ctx = PackagesTestSupport.ctx(w, Map.of());
        Iterator<Object> it = tl.fileArrives(ctx, ".", "*.dat", false, 0.4, 0.05, 1, 5).iterator();
        Files.writeString(w.resolve("x.dat"), "data");
        long written = System.currentTimeMillis();
        assertTrue(it.hasNext());
        assertEquals("x.dat", ((Map<String, Object>) it.next()).get("name"));
        assertTrue(System.currentTimeMillis() - written >= 350, "waited for the file to stop changing");
        assertFalse(it.hasNext(), "max_events reached");
    }

    @Test void maxSecondsAndStopRequestEndTheWait() throws Exception {
        Path w = tmp();
        long t0 = System.nanoTime();
        assertEquals(List.of(), drain(tl.fileArrives(PackagesTestSupport.ctx(w, Map.of()), ".", "*", false, 0, 0.05, 0, 0.3)));
        assertTrue(System.nanoTime() - t0 >= 250_000_000L);

        AtomicBoolean cancel = new AtomicBoolean();
        ActionContext ctx = new ActionContext("r", "s", "default", w, new HashMap<>(), new HashMap<>(), cancel, List::of, (l, m) -> {});
        later(250, () -> cancel.set(true));
        long t1 = System.nanoTime();
        var e = assertThrows(OoaException.class, () -> drain(tl.timerTick(ctx, 30, false, 0, 0)));
        assertEquals("RUN.CANCELLED", e.code());
        assertTrue(System.nanoTime() - t1 < 2_000_000_000L, "a stop request ends the wait promptly");
    }

    @SuppressWarnings("unchecked")
    @Test void processStartsDetectsANewProcess() throws Exception {
        boolean win = System.getProperty("os.name", "").toLowerCase(Locale.ROOT).contains("win");
        String name = win ? "ping" : "sleep";
        List<String> cmd = win ? List.of("ping", "-n", "4", "127.0.0.1") : List.of("sleep", "3");
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        Iterable<Object> it = tl.processStarts(ctx, name, 0.1, 1, 10);
        Process[] p = new Process[1];
        later(300, () -> p[0] = new ProcessBuilder(cmd).redirectErrorStream(true).redirectOutput(ProcessBuilder.Redirect.DISCARD).start());
        try {
            List<Object> ev = drain(it);
            assertEquals(1, ev.size());
            Map<String, Object> e = (Map<String, Object>) ev.get(0);
            assertEquals("process", e.get("event"));
            assertTrue(TriggerLoopPackage.nameMatches(String.valueOf(e.get("name")), name), String.valueOf(e));
            assertTrue(tl.processRunning(ctx, name));
        } finally {
            for (int i = 0; i < 20 && p[0] == null; i++) Thread.sleep(50);
            if (p[0] != null) p[0].destroyForcibly();
        }
        assertFalse(tl.processRunning(ctx, "ooa-no-such-program-" + UUID.randomUUID()));
    }

    @Test void inputErrors() throws Exception {
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        assertEquals("TRIGGER.NO_FOLDER", assertThrows(ActionException.class, () -> tl.fileArrives(ctx, "missing", "*", false, 0, 1, 0, 0)).code());
        assertEquals("TRIGGER.BAD_INTERVAL", assertThrows(ActionException.class, () -> tl.fileArrives(ctx, ".", "*", false, 0, 0, 0, 0)).code());
        assertEquals("TRIGGER.BAD_LIMIT", assertThrows(ActionException.class, () -> tl.fileArrives(ctx, ".", "*", false, -1, 1, 0, 0)).code());
        assertEquals("TRIGGER.BAD_LIMIT", assertThrows(ActionException.class, () -> tl.timerTick(ctx, 1, false, -2, 0)).code());
        assertEquals("TRIGGER.BAD_INTERVAL", assertThrows(ActionException.class, () -> tl.timerTick(ctx, 0, false, 0, 0)).code());
        assertEquals("TRIGGER.BAD_PATTERN", assertThrows(ActionException.class, () -> tl.fileArrives(ctx, ".", "[", false, 0, 1, 0, 0)).code());
        assertEquals("TRIGGER.NO_PROCESS", assertThrows(ActionException.class, () -> tl.processStarts(ctx, " ", 1, 0, 0)).code());
        assertEquals("TRIGGER.NO_PROCESS", assertThrows(ActionException.class, () -> tl.processRunning(ctx, "")).code());
        assertFalse(TriggerLoopPackage.nameMatches("notepad.exe", "note"));
        assertTrue(TriggerLoopPackage.nameMatches("NOTEPAD.EXE", "notepad"));
        assertTrue(TriggerLoopPackage.nameMatches("notepad", "notepad.exe"));
    }

    // ---------- key presses and clicks (fake hooks) ----------

    @AfterEach void realHooks() {
        TriggerLoopPackage.inputHooks = () -> com.oneofficeautomation.packages.ui.DesktopEvents.installed().flatMap(com.oneofficeautomation.packages.ui.DesktopEvents::input);
        TriggerLoopPackage.clockMs = System::currentTimeMillis;
    }

    static void await(java.util.function.BooleanSupplier ok) throws InterruptedException {
        for (int i = 0; i < 200 && !ok.getAsBoolean(); i++) Thread.sleep(10);
        assertTrue(ok.getAsBoolean());
    }

    @Test void keyPressedInsideALoopStepUnhooksWhenTheLoopEnds() throws Exception {
        FakeNative n = new FakeNative();
        long[] now = {0};
        TriggerLoopPackage.inputHooks = () -> Optional.of(n);
        TriggerLoopPackage.clockMs = () -> now[0] += 1000;   // every press is a second after the last: no debouncing here
        Thread presser = later(0, () -> {
            await(() -> n.key != null);
            n.press("Notepad", "notepad.exe");                 // not the named window
            n.press("Invoice 7 - ERP", "erp.exe");
            n.press("Invoice 8 - ERP", "erp.exe");
        });
        Map<String, Object> o = run(tmp(), "[{\"name\":\"ev\",\"type\":\"Dictionary\"},{\"name\":\"seen\",\"type\":\"String\",\"direction\":\"output\",\"default\":\"\"}]",
                "[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"ooa.triggerloop/key_pressed\","
                + "\"iterator_args\":{\"key\":\"ctrl+enter\",\"window\":\"*ERP\",\"max_events\":2,\"max_seconds\":10},\"as\":\"ev\"},\"children\":["
                + "{\"id\":\"a\",\"action\":\"core/set\",\"args\":{\"variable\":\"seen\",\"value\":{\"expr\":\"$seen + $ev.key + '@' + $ev.window + ';'\"}}}]}]", Map.of());
        presser.join(5000);
        assertEquals("CTRL+ENTER@Invoice 7 - ERP;CTRL+ENTER@Invoice 8 - ERP;", o.get("seen"));
        assertNull(n.key, "the hook is removed when the loop ends");
        assertEquals(1, n.hooksClosed);
    }

    @Test void exitLoopUnhooksTheClickWatch() throws Exception {
        FakeNative n = new FakeNative();
        n.elements.put("5,5", Map.of("Name", "Approve", "ControlType", "Button", "AutomationId", "ok"));
        TriggerLoopPackage.inputHooks = () -> Optional.of(n);
        Thread clicker = later(0, () -> { await(() -> n.mouse != null); n.click("left", 5, 5, "ERP", "erp.exe"); });
        Map<String, Object> o = run(tmp(), "[{\"name\":\"ev\",\"type\":\"Dictionary\"},{\"name\":\"what\",\"type\":\"String\",\"direction\":\"output\",\"default\":\"\"}]",
                "[{\"id\":\"l\",\"action\":\"core/loop\",\"args\":{\"iterator\":\"ooa.triggerloop/mouse_clicked\","
                + "\"iterator_args\":{\"process\":\"erp\",\"element_type\":\"Button\",\"max_seconds\":10},\"as\":\"ev\"},\"children\":["
                + "{\"id\":\"a\",\"action\":\"core/set\",\"args\":{\"variable\":\"what\",\"value\":{\"expr\":\"$ev.element_type + ' ' + $ev.element_name + ' ' + $ev.event\"}}},"
                + "{\"id\":\"x\",\"action\":\"core/break\"}]}]", Map.of());
        clicker.join(5000);
        assertEquals("Button Approve click", o.get("what"));
        assertNull(n.mouse, "Exit loop also removes the hook");
        assertEquals(1, n.lookupsClosed);
    }

    @Test void keyAndClickLoopsNeedWindows() throws Exception {
        TriggerLoopPackage.inputHooks = Optional::empty;
        var ctx = PackagesTestSupport.ctx(tmp(), Map.of());
        ActionException e = assertThrows(ActionException.class, () -> tl.keyPressed(ctx, "F9", null, null, 500, 0, 0));
        assertEquals("TRIGGER.WINDOWS_ONLY", e.code());
        assertTrue(e.getMessage().contains("Windows runners only"), e.getMessage());
        assertEquals("TRIGGER.WINDOWS_ONLY", assertThrows(ActionException.class, () -> tl.mouseClicked(ctx, "ERP", null, "left", null, null, null, 500, 0, 0)).code());
        TriggerLoopPackage.inputHooks = () -> Optional.of(new FakeNative());
        assertEquals("TRIGGER.BAD_KEY", assertThrows(ActionException.class, () -> tl.keyPressed(ctx, "A", null, null, 500, 0, 0)).code(), "typing keys are refused");
        assertEquals("TRIGGER.BAD_CLICK", assertThrows(ActionException.class, () -> tl.mouseClicked(ctx, null, null, "left", "OK", null, null, 500, 0, 0)).code());
    }
}
