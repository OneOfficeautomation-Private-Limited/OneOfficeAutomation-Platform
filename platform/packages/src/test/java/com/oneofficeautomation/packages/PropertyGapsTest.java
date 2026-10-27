package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.awt.GraphicsEnvironment;
import java.awt.Rectangle;
import java.awt.event.KeyEvent;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.util.*;
import java.util.zip.ZipFile;

import static com.oneofficeautomation.packages.PackagesTest.*;
import static org.junit.jupiter.api.Assertions.*;

/** Options added to match the properties of the equivalent actions in other RPA products (system, files and desktop toolkits). */
class PropertyGapsTest {
    static ActionContext ctx(Path w) { return PackagesTestSupport.ctx(w, Map.of()); }
    static String code(org.junit.jupiter.api.function.Executable e) { return assertThrows(ActionException.class, e).code(); }
    static final boolean HEADLESS = GraphicsEnvironment.isHeadless();

    // ---------- Desktop: mouse, keyboard, screen ----------

    @Test void mouseAndKeyboardHelpers() {
        assertArrayEquals(new int[]{10, 20}, DesktopPackage.target(10, 20, "screen", null));
        assertArrayEquals(new int[]{110, 220}, DesktopPackage.target(10, 20, "window", new int[]{100, 200, 640, 480}));
        assertEquals("DESKTOP.NO_WINDOW", code(() -> DesktopPackage.target(1, 1, "window", null)));
        assertEquals("DESKTOP.NO_WINDOW", code(() -> DesktopPackage.focusWindow(" ", true)));
        assertNull(DesktopPackage.focusWindow(null, false), "no window asked for: nothing to do");
        assertEquals("down", DesktopPackage.choice("button_action", "DOWN", "click", "click", "down", "up"));
        assertEquals("click", DesktopPackage.choice("button_action", null, "click", "click", "down", "up"));
        assertEquals("DESKTOP.BAD_OPTION", code(() -> DesktopPackage.choice("button_action", "hold", "click", "click", "down", "up")));

        List<int[]> steps = DesktopPackage.glide(0, 0, 100, 50, 100);
        assertEquals(10, steps.size());
        assertArrayEquals(new int[]{100, 50}, Arrays.copyOf(steps.get(steps.size() - 1), 2));
        assertEquals(1, DesktopPackage.glide(5, 5, 6, 6, 1).size());

        assertEquals(3, DesktopPackage.wheel(3, null));
        assertEquals(-3, DesktopPackage.wheel(3, "up"));
        assertEquals(3, DesktopPackage.wheel(-3, "down"));
        assertEquals(-2, DesktopPackage.wheel(2, "left"));
        assertEquals(2, DesktopPackage.wheel(2, "right"));
    }

    @Test void specialKeysInTypedText() {
        List<DesktopPackage.KeyStep> s = DesktopPackage.parseSequence("Hello[TAB]World[ENTER]");
        assertEquals(4, s.size());
        assertEquals("Hello", s.get(0).text());
        assertEquals(List.of(KeyEvent.VK_TAB), s.get(1).keys());
        assertEquals("World", s.get(2).text());
        assertEquals(List.of(KeyEvent.VK_ENTER), s.get(3).keys());

        s = DesktopPackage.parseSequence("[CTRL+S][TAB 3][SHIFT DOWN]abc[SHIFT UP]");
        assertEquals(List.of(KeyEvent.VK_CONTROL, KeyEvent.VK_S), s.get(0).keys());
        assertEquals(3, s.get(1).times());
        assertEquals("down", s.get(2).action());
        assertEquals("abc", s.get(3).text());
        assertEquals("up", s.get(4).action());

        // brackets that are not key names, and [[ escapes, stay text
        s = DesktopPackage.parseSequence("[[ENTER] and [Note 1a] [x");
        assertEquals(1, s.size());
        assertEquals("[ENTER] and [Note 1a] [x", s.get(0).text());
        assertEquals(List.of(KeyEvent.VK_F13), DesktopPackage.parseKeys("F13"));
        assertEquals(List.of(KeyEvent.VK_NUMPAD5), DesktopPackage.parseKeys("numpad5"));
    }

    @Test void screenCaptureArea() {
        Rectangle screen = new Rectangle(0, 0, 1920, 1080);
        assertEquals(screen, DesktopPackage.area(null, null, null, null, null, screen));
        assertEquals(new Rectangle(5, 6, 7, 8), DesktopPackage.area(5, 6, 7, 8, null, screen));
        assertEquals(new Rectangle(100, 200, 640, 480), DesktopPackage.area(null, null, null, null, new int[]{100, 200, 640, 480}, screen));
        assertEquals(new Rectangle(110, 220, 50, 40), DesktopPackage.area(10, 20, 50, 40, new int[]{100, 200, 640, 480}, screen));
        assertEquals("DESKTOP.BAD_AREA", code(() -> DesktopPackage.area(0, 0, 0, 10, null, screen)));
    }

    @Test void dialogHelpers() {
        assertEquals("ok", DesktopPackage.buttonName("ok", 0));
        assertEquals("cancel", DesktopPackage.buttonName("ok_cancel", 2));
        assertEquals("yes", DesktopPackage.buttonName("yes_no", 0));
        assertEquals("no", DesktopPackage.buttonName("yes_no_cancel", 1));
        assertEquals("cancel", DesktopPackage.buttonName("yes_no_cancel", 2));
        assertEquals("closed", DesktopPackage.buttonName("ok", -1));
        assertEquals("timeout", DesktopPackage.buttonName("ok", null));
        assertEquals("timeout", DesktopPackage.buttonName("ok", javax.swing.JOptionPane.UNINITIALIZED_VALUE));
        assertEquals(javax.swing.JOptionPane.YES_NO_CANCEL_OPTION, DesktopPackage.optionType("yes_no_cancel"));
        assertEquals(javax.swing.JOptionPane.ERROR_MESSAGE, DesktopPackage.messageType("error"));
        assertTrue(DesktopPackage.scrolls("a\nb\nc", 2));
        assertFalse(DesktopPackage.scrolls("a\nb", 2));
        assertFalse(DesktopPackage.scrolls("a\nb\nc", 0));
        assertEquals(List.of("pdf", "xlsx", "csv"), DesktopPackage.extensions("*.pdf; xlsx, .csv pdf"));
        assertEquals(List.of(), DesktopPackage.extensions(" * "));
        assertEquals("DESKTOP.BAD_FILE_TYPE", code(() -> DesktopPackage.extensions("p/df")));
    }

    /** Without a desktop session, inputs are still checked first and the actions then stop with a clear message. */
    @Test void desktopActionsHeadless() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        DesktopPackage d = new DesktopPackage();
        assertEquals("DESKTOP.BAD_OPTION", code(() -> d.click(c, 1, 1, "left", false, "hold", "screen", null, null)));
        assertEquals("DESKTOP.BAD_KEY", code(() -> d.click(c, 1, 1, "left", false, "click", "screen", null, "CTRL+NOPE")));
        assertEquals("DESKTOP.BAD_OPTION", code(() -> d.messageBox(c, "hi", "t", 1, "maybe", "information", 0, true)));
        assertEquals("DESKTOP.BAD_FILE_TYPE", code(() -> d.promptFile(c, "t", null, "a/b", false, 1, true)));
        assertEquals("DESKTOP.BAD_OPTION", code(() -> d.pressKeys(c, "ENTER", 1, "tap", 40, 0, null)));
        Files.writeString(w.resolve("shot.png"), "x");
        assertEquals("FILE.EXISTS", code(() -> d.capture(c, "shot.png", null, null, null, null, "png", false, false, null)));
        assertEquals("DESKTOP.BAD_OPTION", code(() -> d.capture(c, "shot.gif", null, null, null, null, "gif", true, false, null)));
        if (!HEADLESS) { System.out.println("    (desktop session present; headless checks skipped)"); return; }
        assertEquals("DESKTOP.NO_SESSION", code(() -> d.click(c, 1, 1, "right", false, "down", "screen", null, "SHIFT")));
        assertEquals("DESKTOP.NO_SESSION", code(() -> d.scroll(c, 3, "left", 10, 10, "screen", null, 20)));
        assertEquals("DESKTOP.NO_SESSION", code(() -> d.typeText(c, "a[ENTER]", "keystrokes", 5, null, true, true, 0)));
        assertEquals("DESKTOP.NO_SESSION", code(() -> d.messageBox(c, "hi", "t", 1, "yes_no", "question", 5, false)));
        assertEquals("DESKTOP.NO_SESSION", code(() -> d.promptValue(c, "q", null, 1, "T", true, true)));
        assertEquals("DESKTOP.NO_SESSION", code(() -> d.promptYesNo(c, "q", 1, "T", true, true)));
        assertEquals("DESKTOP.NO_SESSION", code(() -> d.promptFile(c, "t", null, "pdf", true, 1, true)));
        assertEquals("DESKTOP.NO_SESSION", code(() -> d.clipboardGet(c, 0.2, true)));
        assertEquals("DESKTOP.NO_SESSION", code(() -> d.clipboardSet(c, "x", true)));
        // the beep is skipped, not failed, without a desktop
        assertEquals(Map.of(), new SoundPackage().beep(c, 3, 10));
    }

    @Test void soundVolume() {
        assertEquals(0f, SoundPackage.gainDb(100), 0.001);
        assertEquals(-6.02f, SoundPackage.gainDb(50), 0.01);
        assertEquals(-40f, SoundPackage.gainDb(1), 0.01);
        assertEquals(-40f, SoundPackage.gainDb(-5), 0.01);
    }

    // ---------- File and Folder ----------

    @Test @SuppressWarnings("unchecked") void fileOptions() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        FilePackage f = new FilePackage();
        f.writeText(c, "u16.txt", "héllo", false, "utf-16le", false);
        assertEquals("héllo", Files.readString(w.resolve("u16.txt"), StandardCharsets.UTF_16LE));
        assertEquals("FILE.EXISTS", code(() -> f.writeText(c, "u16.txt", "x", false, "utf-8", true)));
        assertEquals("FILE.BAD_ENCODING", code(() -> f.writeText(c, "y.txt", "x", false, "klingon", false)));

        Files.createDirectories(w.resolve("in/sub"));
        Files.writeString(w.resolve("in/a.pdf"), "a");
        Files.writeString(w.resolve("in/big.pdf"), "x".repeat(5000));
        Files.writeString(w.resolve("in/sub/c.pdf"), "c");
        Files.writeString(w.resolve("in/old.tmp"), "o");
        Files.setLastModifiedTime(w.resolve("in/old.tmp"), FileTime.fromMillis(System.currentTimeMillis() - 10 * 86_400_000L));
        Files.writeString(w.resolve("in/new.tmp"), "n");
        assertEquals(2, ((List<Object>) f.list(c, "in", "*.pdf", false, null, null, null, null).get("files")).size());
        assertEquals(3, ((List<Object>) f.list(c, "in", "*.pdf", true, null, null, null, null).get("files")).size());
        assertEquals(1, ((List<Object>) f.list(c, "in", "*.pdf", false, 1000L, null, null, null).get("files")).size());
        assertEquals(2, ((List<Object>) f.list(c, "in", "*", false, null, 10L, 5, null).get("files")).size(), "small and changed in the last 5 days");
        assertEquals(1, ((List<Object>) f.list(c, "in", "*.tmp", false, null, null, null, 7).get("files")).size());

        eqNum("1", f.delete(c, "in", "*.tmp", false, 7, null, null).get("deleted"));
        assertFalse(Files.exists(w.resolve("in/old.tmp")));
        assertTrue(Files.exists(w.resolve("in/new.tmp")));
        eqNum("0", f.delete(c, "in/missing.txt", null, false, null, null, null).get("deleted"));
        eqNum("1", f.delete(c, "in/new.tmp", "", false, null, null, null).get("deleted"));

        String copy = (String) f.copy(c, "in/a.pdf", "in/big.pdf", false, true).get("path");
        assertEquals(w.resolve("in/big (2).pdf").toString(), copy);
        assertEquals("a", Files.readString(Path.of(copy)));
        assertEquals("FILE.EXISTS", code(() -> f.copy(c, "in/a.pdf", "in/big.pdf", false, false)));
        assertEquals(w.resolve("in/moved.pdf").toString(), f.move(c, "in/big (2).pdf", "in/moved.pdf", false, false).get("path"));

        // zip: pattern, compression, overwrite and delete originals
        f.zip(c, "in", "out/pdfs.zip", "*.pdf", "store", true, false);
        try (ZipFile z = new ZipFile(w.resolve("out/pdfs.zip").toFile())) {
            assertTrue(z.stream().allMatch(e -> e.getName().endsWith(".pdf")));
            assertEquals(4, z.size());
            assertTrue(z.getEntry("big.pdf").getCompressedSize() >= z.getEntry("big.pdf").getSize(), "store does not compress");
        }
        assertEquals("FILE.EXISTS", code(() -> f.zip(c, "in", "out/pdfs.zip", null, "normal", false, false)));
        assertEquals("ZIP.BAD_COMPRESSION", code(() -> f.zip(c, "in", "out/x.zip", null, "ultra", true, false)));
        Files.createDirectories(w.resolve("gone"));
        Files.writeString(w.resolve("gone/g.txt"), "g");
        f.zip(c, "gone", "out/gone.zip", null, "fastest", true, true);
        assertFalse(Files.exists(w.resolve("gone/g.txt")), "originals deleted after zipping");

        // unzip without overwrite keeps existing files
        Files.createDirectories(w.resolve("un"));
        Files.writeString(w.resolve("un/a.pdf"), "mine");
        f.unzip(c, "out/pdfs.zip", "un", false);
        assertEquals("mine", Files.readString(w.resolve("un/a.pdf")));
        assertEquals("x".repeat(5000), Files.readString(w.resolve("un/big.pdf")));
        f.unzip(c, "out/pdfs.zip", "un", true);
        assertEquals("a", Files.readString(w.resolve("un/a.pdf")));

        assertEquals("FILE.NO_FILES", code(() -> f.printFiles(c, null, null, 1, "in", "*.docx", false, null)));
        assertEquals("FILE.NOT_FOUND", code(() -> f.printFiles(c, null, null, 1, "nowhere", "*", false, null)));
    }

    @Test void folderOptions() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        FolderPackage fp = new FolderPackage();
        fp.create(c, "box", false);
        fp.create(c, "box", false);
        assertEquals("FOLDER.EXISTS", code(() -> fp.create(c, "box", true)));

        Files.createDirectories(w.resolve("src/s"));
        Files.writeString(w.resolve("src/a.csv"), "new-a");
        Files.writeString(w.resolve("src/b.txt"), "b");
        Files.writeString(w.resolve("src/s/c.csv"), "c");
        Files.createDirectories(w.resolve("dst"));
        Files.writeString(w.resolve("dst/a.csv"), "old-a");
        eqNum("1", fp.copy(c, "src", "dst", false, "*.csv", false).get("copied"));
        assertEquals("old-a", Files.readString(w.resolve("dst/a.csv")), "kept when overwrite is false");
        assertFalse(Files.exists(w.resolve("dst/b.txt")), "pattern filters files");
        assertEquals("c", Files.readString(w.resolve("dst/s/c.csv")));

        Files.setLastModifiedTime(w.resolve("dst/a.csv"), FileTime.fromMillis(System.currentTimeMillis() + 60_000));
        eqNum("0", fp.copy(c, "src", "dst", true, "a.csv", true).get("copied"));  // target is newer
        Files.setLastModifiedTime(w.resolve("dst/a.csv"), FileTime.fromMillis(System.currentTimeMillis() - 60_000));
        eqNum("1", fp.copy(c, "src", "dst", true, "a.csv", true).get("copied"));
        assertEquals("new-a", Files.readString(w.resolve("dst/a.csv")));
        eqNum("3", fp.copy(c, "src", "all", true, null, false).get("copied"));
    }

    // ---------- System, application, scripts ----------

    @Test void systemPowerCommands() {
        assertEquals(List.of("shutdown.exe", "/r", "/t", "30"), SystemPackage.powerCommand("/r", 30, false, null, true));
        assertEquals(List.of("shutdown.exe", "/s", "/t", "600", "/f", "/c", "Patch night & reboot"),
                SystemPackage.powerCommand("/s", 9999, true, "Patch night\r\n& reboot", true), "the message is one argument; no shell sees it");
        assertEquals(List.of("shutdown", "-h", "+2", "maintenance"), SystemPackage.powerCommand("-h", 120, true, "maintenance", false));
    }

    @Test void applicationOptions() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        ApplicationPackage a = new ApplicationPackage();
        assertEquals("APPLICATION.BAD_VARIABLE_NAME", code(() -> ApplicationPackage.env(Map.of("A=B", "x"))));
        assertEquals("APPLICATION.BAD_WINDOW_STATE", code(() -> a.startProgram(c, "x", null, null, "tiny", null, 0)));
        assertEquals("Minimized", ApplicationPackage.windowStyle("minimized"));
        assertTrue(ApplicationPackage.START_SCRIPT.contains("$env:OOA_APP_PROGRAM"), "values travel in environment variables");
        List<Object> echo = ProcessRunner.WINDOWS ? List.of("/c", "echo %OOA_APP_TEST%") : List.of("-c", "echo $OOA_APP_TEST");
        Map<String, Object> r = a.runProgram(c, ProcessRunner.WINDOWS ? "cmd.exe" : "sh", echo, null, null, 30, true, Map.of("OOA_APP_TEST", "from-env"), "utf-8");
        assertEquals("from-env", String.valueOf(r.get("output")).strip());
        if (ProcessRunner.WINDOWS) {
            long pid = ((BigDecimal) a.startProgram(c, "ping.exe", List.of("-n", "3", "127.0.0.1"), null, "hidden", null, 0).get("process_id")).longValue();
            assertTrue(pid > 0);
            ProcessHandle.of(pid).ifPresent(ProcessHandle::destroy);
        }
    }

    @Test void scriptOptions() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        ScriptPackage s = new ScriptPackage();
        assertEquals(List.of("python", "py"), ScriptPackage.interpreters(" ", List.of("python", "py")));
        assertEquals(List.of("C:/venv/python.exe"), ScriptPackage.interpreters("C:/venv/python.exe", List.of("python")));
        assertEquals("SCRIPT.BAD_INTERPRETER", code(() -> ScriptPackage.interpreters("-c", List.of("python"))));
        assertEquals("SCRIPT.BAD_FUNCTION", code(() -> { Files.writeString(w.resolve("f.js"), ""); s.runJavaScriptFunction(c, "f.js", "a;b()", null, 30, true, null); }));

        if (OcrPgpTest.has(ProcessRunner.WINDOWS ? "python" : "python3")) {
            Files.createDirectories(w.resolve("work"));
            Map<String, Object> r = s.runPython(c, "import os; print(os.environ['OOA_S'] + ':' + os.path.basename(os.getcwd()))", null, null, null, 60, true, "work", Map.of("OOA_S", "v"), null);
            assertEquals("v:work", String.valueOf(r.get("output")).strip());
            Files.writeString(w.resolve("m.py"), "def boom():\n    raise KeyError('k')\n");
            Map<String, Object> e = s.runPythonFunction(c, "m.py", "boom", null, null, 60, false, null, null);
            assertTrue(String.valueOf(e.get("error")).startsWith("KeyError"), String.valueOf(e.get("error")));
        } else System.out.println("    (python not installed; skipped)");

        if (OcrPgpTest.has("node")) {
            Files.writeString(w.resolve("calc.js"), "function add(a, b) { console.log('adding'); return a + b; }\n"
                    + "module.exports = { tools: { shout: async t => t.toUpperCase() } };\nfunction bad() { throw new RangeError('nope'); }\n");
            Map<String, Object> r = s.runJavaScriptFunction(c, "calc.js", "add", List.of(new BigDecimal(2), new BigDecimal(3)), 60, true, null);
            eqNum("5", r.get("result"));
            assertTrue(String.valueOf(r.get("output")).contains("adding"));
            assertEquals("HI", s.runJavaScriptFunction(c, "calc.js", "tools.shout", List.of("hi"), 60, true, null).get("result"));
            assertEquals("SCRIPT.JAVASCRIPT_ERROR", code(() -> s.runJavaScriptFunction(c, "calc.js", "bad", null, 60, true, null)));
            assertTrue(String.valueOf(s.runJavaScriptFunction(c, "calc.js", "bad", null, 60, false, null).get("error")).contains("RangeError: nope"));
        } else System.out.println("    (node not installed; skipped)");

        if (ProcessRunner.WINDOWS) {
            Files.writeString(w.resolve("f.vbs"), "Function Greet(name, n)\r\n  Greet = \"Hello \" & name & \" x\" & (CInt(n) * 2)\r\nEnd Function\r\n"
                    + "Function Fail()\r\n  Err.Raise 5001, \"agent\", \"broken\"\r\nEnd Function\r\n", StandardCharsets.ISO_8859_1);
            Map<String, Object> r = s.runVbScriptFunction(c, "f.vbs", "Greet", List.of("Ann \"the\" Agent", new BigDecimal(21)), 60, true);
            assertEquals("Hello Ann \"the\" Agent x42", r.get("result"));
            assertEquals("SCRIPT.VBSCRIPT_ERROR", code(() -> s.runVbScriptFunction(c, "f.vbs", "Fail", null, 60, true)));
            assertTrue(String.valueOf(s.runVbScriptFunction(c, "f.vbs", "Fail", null, 60, false).get("error")).contains("broken"));
            assertEquals("SCRIPT.BAD_FUNCTION", code(() -> s.runVbScriptFunction(c, "f.vbs", "Greet(1)", null, 60, true)));
        }
    }

    // ---------- Database ----------

    @Test @SuppressWarnings("unchecked") void databaseOptions() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        DatabasePackage db = new DatabasePackage();
        String url = "jdbc:h2:mem:props" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        assertEquals("DB.BAD_DRIVER", code(() -> db.connect(c, url, null, null, null, null, null, null, null, "db", "not a class", 0, null, false, true)));
        assertEquals("DB.NO_DRIVER", code(() -> db.connect(c, url, null, null, null, null, null, null, null, "db", "com.example.NoSuchDriver", 0, null, false, true)));
        db.connect(c, url, null, null, null, null, null, "sa", null, "db", "org.h2.Driver", 5, Map.of("IGNORECASE", "TRUE"), false, false);
        try {
            db.execute(c, "CREATE TABLE t(id INT, name VARCHAR(20))", null, "db", 10);
            db.execute(c, "INSERT INTO t VALUES (1, 'Ann')", null, "db", 10);
            db.rollback(c, "db");  // auto_commit false: the insert is undone
            eqNum("0", ((List<List<Object>>) ((Map<String, Object>) db.query(c, "SELECT COUNT(*) FROM t", null, 10, "db", 5).get("table")).get("rows")).get(0).get(0));
            db.execute(c, "INSERT INTO t VALUES (2, 'Bob')", null, "db", 10);
            db.commit(c, "db");
            eqNum("1", db.query(c, "SELECT * FROM t WHERE name = 'BOB'", null, 10, "db", 5).get("row_count"));  // IGNORECASE property reached the driver
            Files.writeString(w.resolve("t.csv"), "keep");
            assertEquals("DB.FILE_EXISTS", code(() -> db.exportToFile(c, "SELECT * FROM t", "t.csv", null, ",", true, "utf-8", "db", 5, false)));
            assertEquals("keep", Files.readString(w.resolve("t.csv")));
            eqNum("1", db.exportToFile(c, "SELECT * FROM t", "t.csv", null, ",", true, "utf-8", "db", 5, true).get("row_count"));
        } finally { db.disconnect(c, "db"); }
        db.connect(c, url, null, null, null, null, null, "sa", null, "ro", null, 0, null, true, true);
        try { eqNum("1", db.query(c, "SELECT * FROM t", null, 10, "ro", 0).get("row_count")); }
        finally { db.disconnect(c, "ro"); }
    }

    // ---------- REST and SOAP ----------

    @Test void restAndSoapOptions() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        RestPackage rest = new RestPackage();
        assertEquals("proxy.example.com", HttpOptions.proxyAddress("http://proxy.example.com:8080/").getHostString());
        assertEquals(8080, HttpOptions.proxyAddress("proxy.example.com:8080").getPort());
        assertEquals("HTTP.BAD_PROXY", code(() -> HttpOptions.proxyAddress("proxy.example.com")));
        assertEquals("HTTP.BAD_PROXY", code(() -> HttpOptions.proxyAddress("p:70000")));
        assertTrue(HttpOptions.NONE.isDefault());
        try (ConnectorsTest.Fake f = new ConnectorsTest.Fake()
                .on("POST /xml", call -> new Object[]{200, "ok"})
                .on("GET /login", call -> new Object[]{200, "in", Map.of("Set-Cookie", "sid=abc123; Path=/")})
                .on("GET /me", call -> new Object[]{200, String.valueOf(call.header("Cookie"))})
                .on("GET /moved", call -> new Object[]{302, "", Map.of("Location", "/me")})
                .on("POST /soap", call -> new Object[]{200, "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><r:Quote xmlns:r=\"urn:q\">"
                        + "<r:Price>10.5</r:Price><r:Price>11</r:Price></r:Quote></s:Body></s:Envelope>"})
                .on("POST /fault", call -> new Object[]{500, "<s:Envelope xmlns:s=\"http://schemas.xmlsoap.org/soap/envelope/\"><s:Body><s:Fault><faultstring>bad</faultstring></s:Fault></s:Body></s:Envelope>"})) {
            rest.request(c, f.url() + "/xml", "POST", null, null, null, null, "<a/>", null, null, null, 30, true, "application/xml",
                    false, null, null, null, null, null, false, true);
            assertEquals("application/xml", f.last("POST", "/xml").contentType());

            rest.request(c, f.url() + "/login", "GET", null, null, null, null, null, null, null, null, 30, true, null, false, null, null, null, null, null, true, true);
            assertEquals("sid=abc123", rest.request(c, f.url() + "/me", "GET", null, null, null, null, null, null, null, null, 30, true, null, false, null, null, null, null, null, true, true).get("body"));
            assertEquals("null", rest.request(c, f.url() + "/me", "GET", null, null, null, null, null, null, null, null, 30, true, null, false, null, null, null, null, null, false, true).get("body"),
                    "cookies are only sent when asked for");
            eqNum("302", rest.request(c, f.url() + "/moved", "GET", null, null, null, null, null, null, null, null, 30, true, null, false, null, null, null, null, null, false, false).get("status"));
            eqNum("200", rest.request(c, f.url() + "/moved", "GET", null, null, null, null, null, null, null, null, 30, true, null, false, null, null, null, null, null, false, true).get("status"));
            assertEquals("HTTP.CERTIFICATE_NOT_FOUND", code(() -> rest.request(c, f.url() + "/me", "GET", null, null, null, null, null, null, null, null, 30, true, null,
                    false, null, null, null, "missing.p12", new SecureString("pw", "t"), false, true)));
            // accepting any certificate still works against plain http and is logged as a warning
            eqNum("200", rest.request(c, f.url() + "/me", "GET", null, null, null, null, null, null, null, null, 30, true, null, true, null, null, null, null, null, false, true).get("status"));

            SoapPackage soap = new SoapPackage();
            Map<String, Object> r = soap.send(c, f.url() + "/soap", "<q:Get xmlns:q=\"urn:q\"/>", "Get", "1.1", null, null, null, 30,
                    false, null, null, null, null, null, "//*[local-name()='Price']", "out/resp.xml", true);
            assertEquals(List.of("10.5", "11"), r.get("values"));
            assertTrue(Files.readString(w.resolve("out/resp.xml")).contains("Quote"));
            assertEquals("SOAP.FAULT", code(() -> soap.send(c, f.url() + "/fault", "<x/>", null, "1.1", null, null, null, 30, false, null, null, null, null, null, null, null, true)));
            eqNum("500", soap.send(c, f.url() + "/fault", "<x/>", null, "1.1", null, null, null, 30, false, null, null, null, null, null, null, null, false).get("status"));
            assertEquals("SOAP.BAD_XPATH", code(() -> soap.send(c, f.url() + "/soap", "<x/>", null, "1.1", null, null, null, 30, false, null, null, null, null, null, "//[", null, true)));
        }
    }

    // ---------- Network, log, crypto ----------

    @Test void pingCountAndSnmpVersion() throws Exception {
        ActionContext c = ctx(tmp());
        Map<String, Object> p = new NetworkPackage().ping(c, "127.0.0.1", 2000, 2, 10);
        eqNum("2", ((BigDecimal) p.get("replies")).add((BigDecimal) p.get("lost")));
        assertEquals("NETWORK.UNKNOWN_HOST", code(() -> new NetworkPackage().ping(c, "no-such-host.invalid", 500, 1, 0)));
        assertEquals(0, NetworkPackage.Snmp.version("v1"));
        assertEquals(1, NetworkPackage.Snmp.version(null));
        assertEquals("SNMP.BAD_VERSION", code(() -> NetworkPackage.Snmp.version("v4")));
        byte[] v1 = NetworkPackage.Snmp.encode(new NetworkPackage.Snmp.Message(0, "public", NetworkPackage.Snmp.GET, 1, 0, 0,
                List.of(new NetworkPackage.Snmp.VarBind("1.3.6.1.2.1.1.1.0", NetworkPackage.Snmp.NULL, null))));
        assertEquals(0, NetworkPackage.Snmp.decode(v1, v1.length).version());
        try (SystemGapsTest.FakeAgent agent = new SystemGapsTest.FakeAgent()) {
            NetworkPackage n = new NetworkPackage();
            @SuppressWarnings("unchecked") Map<String, Object> got = (Map<String, Object>) n.snmpGet(c, "127.0.0.1", "1.3.6.1.2.1.1.5.0", "public", agent.port(), 2000, 0, "v1").get("values");
            assertEquals("switch-01", got.get("1.3.6.1.2.1.1.5.0"));
            eqNum("3", n.snmpWalk(c, "127.0.0.1", "1.3.6.1.2.1.2.2", "public", agent.port(), 100, 2000, 0, "v1").get("row_count"));
            assertEquals("SNMP.BAD_TYPE", code(() -> n.snmpSet(c, "127.0.0.1", "1.3.6.1.2.1.1.6.0", "5", "counter64", "private", agent.port(), 500, 0, "v1")));
        }
    }

    @Test void logEncodingAndTimestamp() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        LogPackage log = new LogPackage();
        log.write(c, "l16.log", "first", "INFO", true, false, "utf-16le", "yyyy/MM/dd");
        log.write(c, "l16.log", "second", "WARN", false, false, "utf-16le", null);
        byte[] b = Files.readAllBytes(w.resolve("l16.log"));
        assertEquals((byte) 0xFF, b[0]);
        assertEquals((byte) 0xFE, b[1]);
        String text = new String(b, 2, b.length - 2, StandardCharsets.UTF_16LE);
        assertTrue(text.matches("\\d{4}/\\d{2}/\\d{2} INFO first\\R" + "WARN second\\R"), text);
        assertEquals(0, text.chars().filter(ch -> ch == 0xFEFF).count(), "only one byte-order mark, at the start");
        assertEquals("LOG.BAD_ENCODING", code(() -> log.write(c, "x.log", "m", "INFO", true, false, "ebcdic", null)));
        assertEquals("LOG.BAD_TIMESTAMP_FORMAT", code(() -> log.write(c, "x.log", "m", "INFO", true, false, "utf-8", "bad {pattern")));
        log.logVariables(c, "vars.log", Map.of("a", "1"), "t", false, true, "ansi", null);
        assertTrue(Files.readString(w.resolve("vars.log"), java.nio.charset.Charset.defaultCharset()).contains("a=1"));
    }

    @Test void cryptoOptions() throws Exception {
        Path w = tmp();
        ActionContext c = ctx(w);
        CryptoPackage cp = new CryptoPackage();
        assertEquals("ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad", cp.hashText(c, "abc", "SHA-256", "hex", "utf-8").get("hash"));
        assertEquals("BA7816BF8F01CFEA414140DE5DAE2223B00361A396177A9CB410FF61F20015AD", cp.hashText(c, "abc", "SHA-256", "hex_upper", "utf-8").get("hash"));
        assertEquals("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=", cp.hashText(c, "abc", "SHA-256", "base64", "utf-8").get("hash"));
        assertNotEquals(cp.hashText(c, "é", "SHA-256", "hex", "utf-8").get("hash"), cp.hashText(c, "é", "SHA-256", "hex", "windows-1252").get("hash"));
        assertEquals(96, String.valueOf(cp.hashText(c, "abc", "SHA-384", "hex", null).get("hash")).length());
        assertEquals("CRYPTO.BAD_FORMAT", code(() -> cp.hashText(c, "abc", "SHA-256", "octal", null)));
        Files.writeString(w.resolve("abc.txt"), "abc");
        assertEquals("ungWv48Bz+pBQUDeXa4iI7ADYaOWF3qctBD/YfIAFa0=", cp.hashFile(c, "abc.txt", "SHA-256", "base64").get("hash"));

        SecureString key = new SecureString("key", "test/hmac");
        String fox = "The quick brown fox jumps over the lazy dog";
        assertEquals("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8", cp.hmac(c, fox, key, "SHA-256", "hex", false).get("signature"));
        assertEquals(Base64.getEncoder().encodeToString(HexFormat.of().parseHex("f7bc83f430538424b13298e6aa6fb143ef4d59a14946175997479dbc2d1a3cd8")),
                cp.hmac(c, fox, key, "SHA-256", "base64", false).get("signature"));
        assertEquals(cp.hmac(c, fox, key, "SHA-512", "hex", false).get("signature"),
                cp.hmac(c, fox, new SecureString(Base64.getEncoder().encodeToString("key".getBytes()), "t"), "SHA-512", "hex", true).get("signature"));
        assertEquals(128, String.valueOf(cp.hmac(c, fox, key, "SHA-512", "hex", false).get("signature")).length());
        assertEquals("CRYPTO.BAD_ALGORITHM", code(() -> cp.hmac(c, fox, key, "MD5", "hex", false)));

        assertEquals("-_8", cp.encode(c, "\u00fb\u00ff", true, "windows-1252").get("result"));
        assertEquals("+/8=", cp.encode(c, "\u00fb\u00ff", false, "windows-1252").get("result"));
        assertEquals("\u00fb\u00ff", cp.decode(c, "-_8", "windows-1252").get("result"));
        assertEquals("hello", cp.decode(c, "aGVs\nbG8=", null).get("result"));

        Files.writeString(w.resolve("plain.txt"), "secret");
        cp.encryptFile(c, "plain.txt", "enc.bin", key, true);
        assertEquals("FILE.EXISTS", code(() -> cp.encryptFile(c, "plain.txt", "enc.bin", key, false)));
        cp.decryptFile(c, "enc.bin", "back.txt", key, false);
        assertEquals("secret", Files.readString(w.resolve("back.txt")));
    }

    @Test void pgpKeyFiles() throws Exception {
        if (!PgpPackage.usable() || !OcrPgpTest.has(PgpPackage.exe())) { System.out.println("    (gpg not installed; skipped)"); return; }
        Path w = tmp();
        ActionContext c = ctx(w);
        PgpPackage pgp = new PgpPackage();
        SecureString pass = new SecureString("agent-pass", "test/pgp/pass");
        Files.writeString(w.resolve("pay.csv"), "Ann,5000\n");
        try {
            pgp.createKeys(c, "Key File Agent", "keyfile-agent@ooa.test", pass, null, "ed25519", "1d", "k/public.asc", "k/private.asc", "maker");
            assertEquals("PGP.NO_RECIPIENT", code(() -> pgp.encrypt(c, "pay.csv", "x.pgp", null, false, null, null, true)));
            pgp.encrypt(c, "pay.csv", "out/pay.pgp", null, true, null, "k/public.asc", true);
            assertEquals("FILE.EXISTS", code(() -> pgp.encrypt(c, "pay.csv", "out/pay.pgp", null, true, null, "k/public.asc", false)));
            pgp.decrypt(c, "out/pay.pgp", "out/back.csv", pass, null, "k/private.asc", true);
            assertEquals("Ann,5000\n", Files.readString(w.resolve("out/back.csv")));
        } finally {
            try { new ProcessBuilder(PgpPackage.gpgconf(), "--homedir", w.resolve("maker").toString(), "--kill", "gpg-agent").start().waitFor(); } catch (Exception ignored) { }
        }
    }

    // ---------- Services ----------

    @Test void servicesDoNotWaitWhenAsked() throws Exception {
        ActionContext c = ctx(tmp());
        ServicesPackage s = new ServicesPackage();
        if (ProcessRunner.WINDOWS) {
            assertEquals("SERVICES.NOT_FOUND", code(() -> s.pause(c, "OoaNoSuchService123", 5, false)));
            assertEquals("SERVICES.BAD_NAME", code(() -> s.start(c, "bad|name", 5, false)));
        } else assertEquals("SYSTEM.WINDOWS_ONLY", code(() -> s.stop(c, "cron", 5, false)));
    }
}
