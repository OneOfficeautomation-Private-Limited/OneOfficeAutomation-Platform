package com.oneofficeautomation.packages;

import com.oneofficeautomation.packages.ConnectorsTest.Call;
import com.oneofficeautomation.packages.ConnectorsTest.Fake;
import com.oneofficeautomation.runner.AutomationRepository;
import com.oneofficeautomation.runner.BotInvoker;
import com.oneofficeautomation.runner.Runner;
import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.io.Reader;
import java.io.Writer;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.function.Function;

import static com.oneofficeautomation.packages.ConnectorsTest.ok;
import static com.oneofficeautomation.packages.ConnectorsTest.q;
import static com.oneofficeautomation.packages.EnterpriseGapsTest.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Options and actions added after comparing the enterprise application and AI toolkits with Automation Anywhere:
 * Terminal, SAP, Active Directory, Salesforce, ServiceNow, Jira, Workday, Generative AI, Prompt Skills, AI agents,
 * work queues and insights. Fake services, a scripted terminal engine, captured SAP scripts and an in-memory directory.
 */
class EnterpriseAiPropertiesTest {
    static final SecureString KEY = new SecureString("sk-test", "k");

    static ActionException fails(String code, org.junit.jupiter.api.function.Executable e) {
        ActionException ex = assertThrows(ActionException.class, e);
        assertEquals(code, ex.code(), ex.getMessage());
        return ex;
    }

    @SuppressWarnings("unchecked") static Map<String, Object> json(Call c) { return (Map<String, Object>) JsonLite.parse(c.text()); }

    // ---------------------------------------------------------------- catalog

    @Test void descriptionsAreCompleteAndSayAgent() {
        for (String id : List.of("ooa.terminal", "ooa.sap", "ooa.active_directory", "ooa.salesforce", "ooa.servicenow", "ooa.jira", "ooa.workday",
                "ooa.genai", "ooa.ai_skills", "ooa.ai_agent", "ooa.workload", "ooa.insights"))
            ToolkitTestSupport.checkCatalog(id, 1);
        PackageRegistry.LoadedPackage t = PackagesTest.REG.resolve("ooa.terminal", null);
        assertTrue(t.info().actions().keySet().containsAll(List.of("wait", "get_cursor")));
        assertTrue(PackagesTest.REG.resolve("ooa.sap", null).info().actions().keySet().containsAll(
                List.of("open_connection", "get_status", "get_selected_item", "press_toolbar_button", "table_cell_count")));
        assertTrue(PackagesTest.REG.resolve("ooa.genai", null).info().actions().keySet().containsAll(List.of("chat", "end_chat")));
        assertTrue(PackagesTest.REG.resolve("ooa.workload", null).info().actions().keySet().containsAll(List.of("update_item", "get_item")));
        assertTrue(PackagesTest.REG.resolve("ooa.insights", null).info().actions().keySet().containsAll(List.of("open_transaction", "close_transaction")));
        // every new input is optional, so existing agents keep working unchanged
        ActionSpec connect = t.info().actions().get("connect");
        for (ActionSpec.InputSpec in : connect.inputs()) if (!in.name().equals("host")) assertFalse(in.required(), in.name());
    }

    // ---------------------------------------------------------------- Terminal

    /** Scripted s3270: answers each command from a function (data lines) and fails the commands listed in failing. */
    static final class Engine {
        final List<String> commands = new ArrayList<>();
        final StringBuilder out = new StringBuilder(); int pos;
        final Set<String> failing = new HashSet<>();
        Function<String, List<String>> answer = c -> List.of();
        final Writer writer = new Writer() {
            final StringBuilder line = new StringBuilder();
            @Override public void write(char[] c, int off, int len) {
                for (int i = off; i < off + len; i++) { if (c[i] == '\n') { respond(line.toString().replace("\r", "")); line.setLength(0); } else line.append(c[i]); }
            }
            @Override public void flush() { }
            @Override public void close() { }
        };
        final Reader reader = new Reader() {
            @Override public int read(char[] c, int off, int len) {
                if (pos >= out.length()) return -1;
                int n = Math.min(len, out.length() - pos);
                out.getChars(pos, pos + n, c, off); pos += n; return n;
            }
            @Override public void close() { }
        };
        void respond(String cmd) {
            commands.add(cmd);
            for (String d : answer.apply(cmd)) out.append("data: ").append(d).append('\n');
            out.append("U F U C(host) I 4 3 20 0 0 0x0 0.000\n").append(failing.contains(cmd) ? "error\n" : "ok\n");
        }
        TerminalPackage.Session session() { return new TerminalPackage.Session(null, writer, reader); }
    }

    @Test void terminalConnectOptions() throws Exception {
        Engine e = new Engine();
        e.answer = c -> c.equals("Ascii()") ? List.of(" USERID ===>        ", "                    ") : List.of();
        List<List<String>> started = new ArrayList<>();
        TerminalPackage.testStarter = cmd -> { started.add(cmd); return e.session(); };
        try {
            var t = new TerminalPackage();
            var ctx = ctx();
            t.connect(ctx, "mvs.example.com", 992, true, "3278-4", "t1", "tn3270", 10, "LU0042", "cp273", true, "USERID");
            assertEquals(List.of("-model", "3278-4", "-codepage", "cp273"), started.get(0).subList(1, 5));
            assertEquals(List.of("Connect(L:N:LU0042@mvs.example.com:992)", "Wait(10,InputField)", "Ascii()"), e.commands);
            assertNotNull(ctx.sessions().get("t1"));

            e.commands.clear();
            t.connect(ctx, "mvs.example.com", 23, false, "3279-2", "t2", "tn3270e", 30, null, null, false, null);
            assertEquals(List.of("Connect(mvs.example.com:23)", "Wait(30,3270Mode)"), e.commands);
            assertEquals(List.of("-model", "3279-2"), started.get(1).subList(1, 3), "no code page option when empty");

            fails("TERMINAL.UNSUPPORTED_TYPE", () -> t.connect(ctx, "h", 23, false, "3279-2", "x", "tn5250", 30, null, null, true, null));
            fails("TERMINAL.BAD_LU", () -> t.connect(ctx, "h", 23, false, "3279-2", "x", "tn3270e", 30, "LU;rm", null, true, null));
            fails("TERMINAL.BAD_CODE_PAGE", () -> t.connect(ctx, "h", 23, false, "3279-2", "x", "tn3270e", 30, null, "cp 037", true, null));
            fails("TERMINAL.BAD_PORT", () -> t.connect(ctx, "h", 0, false, "3279-2", "x", "tn3270e", 30, null, null, true, null));
            int before = ctx.sessions().size();
            fails("TERMINAL.TEXT_NOT_FOUND", () -> t.connect(ctx, "h", 23, false, "3279-2", "t3", "tn3270e", 1, null, null, true, "LOGON APPLID"));
            assertEquals(before, ctx.sessions().size(), "a failed connect leaves no session behind");
        } finally { TerminalPackage.testStarter = null; }
    }

    @Test void terminalKeysTextScreenAndWaits() throws Exception {
        Engine e = new Engine();
        String[] screen = {"Ready   PLEASE WAIT ", "Name: Ann           ", "                    "};
        e.answer = c -> {
            if (c.equals("Ascii()")) return List.of(screen);
            if (c.startsWith("Ascii(0,8,")) return List.of(screen[0].substring(8, 8 + Integer.parseInt(c.substring(10, c.length() - 1))));
            if (c.equals("Ascii(1,0,10)")) return List.of(screen[1].substring(0, 10));
            if (c.equals("Query(Cursor)")) return List.of("1 2");
            return List.of();
        };
        var t = new TerminalPackage();
        var ctx = ctx();
        ctx.sessions().put("terminal", e.session());

        t.sendText(ctx, new SecureString("pw", "x"), 2, 7, true, "terminal", "Enter", 15, 0, null);
        assertEquals(List.of("MoveCursor(1,6)", "EraseEOF", "String(\"pw\")", "Enter", "Wait(15,InputField)"), e.commands);
        e.commands.clear();
        t.sendKey(ctx, "Tab", "terminal", true, 30, 3, 0, null);
        assertEquals(List.of("Tab", "Tab", "Tab"), e.commands, "local keys never wait for the host");
        e.commands.clear();
        t.sendKey(ctx, "attn", "terminal", true, 5, 1, 0, null);
        t.sendKey(ctx, "Enter", "terminal", false, 5, 1, 0, null);
        t.sendKey(ctx, "sysreq", "terminal", true, 5, 1, 1, "Name:");
        assertEquals(List.of("Attn", "Wait(5,InputField)", "Enter", "SysReq", "Wait(5,InputField)", "Ascii()"), e.commands);
        fails("TERMINAL.TEXT_NOT_FOUND", () -> t.sendKey(ctx, "Enter", "terminal", false, 1, 1, 0, "NOT THERE"));
        fails("TERMINAL.BAD_REPEAT", () -> t.sendKey(ctx, "Tab", "terminal", true, 30, 0, 0, null));
        fails("TERMINAL.BAD_DELAY", () -> t.sendKey(ctx, "Tab", "terminal", true, 30, 1, -5, null));
        for (String k : List.of("Up", "Down", "Left", "Right", "BackSpace", "Delete", "Insert", "FieldEnd", "Dup", "FieldMark", "Newline", "EraseInput"))
            assertTrue(TerminalPackage.LOCAL_KEYS.contains(TerminalPackage.keyAction(k)), k);

        Map<String, Object> s = t.getScreen(ctx, "terminal", true, 2, 2);
        assertEquals("Name: Ann", s.get("text"));
        assertEquals(List.of("Ready   PLEASE WAIT ", "Name: Ann           ", "                    "), t.getScreen(ctx, "terminal").get("lines"));
        assertEquals("Name: Ann ", t.getText(ctx, 2, 1, 10, "terminal", false).get("text"));
        assertEquals("Name: Ann", t.getText(ctx, 2, 1, 10, "terminal").get("text"));

        assertEquals(true, t.waitForText(ctx, "please wait", 1, "terminal", null, null, false, false, true, 50).get("found"));
        assertEquals(true, t.waitForText(ctx, "PLEASE", 1, "terminal", 1, 9, true, false, true, 50).get("found"), "text at a position");
        assertEquals(false, t.waitForText(ctx, "PLEASE WAIT", 1, "terminal", null, null, true, true, false, 50).get("found"), "still shown: not gone");
        fails("TERMINAL.TEXT_NOT_FOUND", () -> t.waitForText(ctx, "PLEASE WAIT", 1, "terminal", null, null, true, true, true, 50));
        fails("TERMINAL.BAD_POSITION", () -> t.waitForText(ctx, "x", 1, "terminal", 1, null, true, false, true, 50));

        e.commands.clear();
        assertEquals(true, t.waitFor(ctx, "unlock", 5, true, "terminal", null, null).get("met"));
        e.failing.add("Wait(2,Output)");
        assertEquals(false, t.waitFor(ctx, "screen_change", 2, false, "terminal", null, null).get("met"), "a timed-out wait is reported, not thrown");
        fails("TERMINAL.COMMAND_FAILED", () -> t.waitFor(ctx, "screen_change", 2, true, "terminal", null, null));
        assertEquals(true, t.waitFor(ctx, "cursor_at", 1, true, "terminal", 2, 3).get("met"), "Query(Cursor) 1 2 is row 2, column 3");
        assertEquals(false, t.waitFor(ctx, "cursor_leaves", 1, false, "terminal", 2, 3).get("met"));
        assertEquals(false, t.waitFor(ctx, "screen_blank", 1, false, "terminal", null, null).get("met"));
        fails("TERMINAL.NO_POSITION", () -> t.waitFor(ctx, "cursor_at", 1, true, "terminal", null, null));
        fails("TERMINAL.BAD_CONDITION", () -> t.waitFor(ctx, "rain", 1, true, "terminal", null, null));
        assertTrue(e.commands.containsAll(List.of("Wait(5,Unlock)", "Wait(2,Output)")));
        Map<String, Object> cur = t.getCursor(ctx, "terminal");
        assertEquals(new BigDecimal(2), cur.get("row"));
        assertEquals(new BigDecimal(3), cur.get("column"));
    }

    @Test void terminalFieldsByIndexAndWholeFieldSearch() throws Exception {
        List<String> buffer = List.of(
                "SF(c0=20)" + hex("User ID") + " SF(c0=00)" + hex("jdoe    ") + " SF(c0=20)" + hex("  "),
                hex("Password").substring(1) + " SF(c0=0c,41=f4)" + hex("secret12") + " SF(c0=20)" + hex("  "),
                hex("Ready").substring(1) + " SA(41=f2)" + hex("               "));
        List<String> screen = List.of(" User ID jdoe       ", "Password  secret12  ", "Ready               ");
        Engine e = new Engine();
        e.answer = c -> c.equals("ReadBuffer(Ascii)") ? buffer : c.equals("Ascii()") ? screen : List.of();
        var t = new TerminalPackage();
        var ctx = ctx();
        ctx.sessions().put("terminal", e.session());

        assertEquals("jdoe", t.getField(ctx, null, null, null, "terminal", 2).get("text"));
        fails("TERMINAL.FIELD_NOT_FOUND", () -> t.getField(ctx, null, null, null, "terminal", 9));
        e.commands.clear();
        t.setField(ctx, "ann", null, null, null, "terminal", 2, "Enter", 20);
        assertEquals(List.of("ReadBuffer(Ascii)", "MoveCursor(0,9)", "EraseEOF", "String(\"ann\")", "Enter", "Wait(20,InputField)"), e.commands);
        fails("TERMINAL.PROTECTED_FIELD", () -> t.setField(ctx, "x", null, null, null, "terminal", 1, null, 30));

        Map<String, Object> f = t.searchField(ctx, "jdoe", 1, true, "terminal", true);
        assertEquals(true, f.get("found"));
        assertEquals(new BigDecimal(2), f.get("field_index"));
        assertEquals(new BigDecimal(10), f.get("column"));
        assertEquals(false, t.searchField(ctx, "jdo", 1, true, "terminal", true).get("found"), "whole field must match");
        assertEquals(false, t.searchField(ctx, "secret12", 1, true, "terminal", true).get("found"), "hidden fields are never matched");
        Map<String, Object> any = t.searchField(ctx, "e", 2, true, "terminal", false);
        assertEquals(new BigDecimal(5), any.get("count"));
        assertEquals(new BigDecimal(1), any.get("row"));
        assertEquals(new BigDecimal(13), any.get("column"));
        assertEquals(new BigDecimal(2), any.get("field_index"), "the second e is in the jdoe input field");
    }

    // ---------------------------------------------------------------- SAP

    @Test void sapScriptErrorsGetSpecificCodes() {
        assertEquals("SAP.NOT_RUNNING", SapPackage.scriptError("SAP GUI is not running or scripting is disabled: x").code());
        assertEquals("SAP.NO_CONNECTION", SapPackage.scriptError("No SAP connection 2").code());
        assertEquals("SAP.NO_SESSION", SapPackage.scriptError("No SAP session 1").code());
        assertEquals("SAP.CONNECT_FAILED", SapPackage.scriptError("Cannot open system PRD: x").code());
        assertEquals("SAP.ALREADY_LOGGED_ON", SapPackage.scriptError("The user is already logged on in another session").code());
        assertEquals("SAP.LOGON_FAILED", SapPackage.scriptError("Logon failed: Name or password is incorrect").code());
        assertEquals("SAP.STATUS_ERROR", SapPackage.scriptError("SAP reported: Enter a valid value").code());
        assertEquals("SAP.TRANSACTION_FAILED", SapPackage.scriptError("Transaction ZZZ: not allowed").code());
        assertEquals("SAP.OBJECT_NOT_FOUND", SapPackage.scriptError("Field wnd[0]/usr/txtX: The control could not be found by id.").code());
        assertEquals("SAP.ACTION_FAILED", SapPackage.scriptError("Button btn[8]: Not changeable").code());
        assertNotNull(SapPackage.scriptError("No SAP session 1").suggestedFix());
    }

    @Test void sapConnectTransactionsFieldsAndStatusBar() throws Exception {
        List<Script> seen = new ArrayList<>();
        Map<String, Object> reply = new HashMap<>();
        List<Path> logonStarted = new ArrayList<>();
        SapPackage.testRunner = (b, a, in) -> { seen.add(new Script(b, a, in)); return new HashMap<>(reply); };
        SapPackage.testLogonStarter = logonStarted::add;
        try {
            var sap = new SapPackage();
            var ctx = ctx();
            reply.put("connection", new BigDecimal(1));
            Map<String, Object> c = sap.logOn(ctx, "/H/sap.example.com/S/3200", "100", "agent1", new SecureString("pw", "t"), "de", "connection_string", "continue",
                    "C:/SAP/saplogon.exe", 30);
            assertEquals(new BigDecimal(1), c.get("connection"));
            Script s = last(seen);
            assertEquals(List.of("/H/sap.example.com/S/3200", "100", "agent1", "de", "string", "continue"), s.args());
            assertTrue(s.body().contains("OpenConnectionByConnectionString(a(0), True)") && s.body().contains("radMULTI_LOGON_OPT1") && s.body().contains("radMULTI_LOGON_OPT2"));
            assertEquals("pw\r\n", s.stdin(), "the password goes through standard input");
            assertEquals(1, logonStarted.size());
            sap.logOn(ctx, "PRD", "100", "agent1", new SecureString("pw", "t"), "EN");
            assertEquals(List.of("PRD", "100", "agent1", "EN", "description", "ignore"), last(seen).args(), "earlier behaviour kept");
            fails("SAP.BAD_OPTION", () -> sap.logOn(ctx, "PRD", "100", "u", new SecureString("pw", "t"), "EN", "description", "maybe", null, 30));
            fails("SAP.BAD_LANGUAGE", () -> sap.logOn(ctx, "PRD", "100", "u", new SecureString("pw", "t"), "ENG", "description", "ignore", null, 30));
            fails("SAP.BAD_LOGON_PATH", () -> sap.logOn(ctx, "PRD", "100", "u", new SecureString("pw", "t"), "EN", "description", "ignore", "C:/SAP/saplogon.bat", 30));
            sap.openConnection(ctx, "PRD SSO", "description", null, 60);
            assertEquals(List.of("PRD SSO", "description"), last(seen).args());
            assertNull(last(seen).stdin(), "no credentials for single sign-on");
            reply.clear();

            sap.runTransaction(ctx, "/nVA01", 0, 1, true, true);
            s = last(seen);
            assertEquals(List.of("0", "1", "/nVA01", "1"), s.args());
            assertTrue(s.body().contains("\"/o\" & Replace(") && s.body().contains("If a(a.Count - 1) = \"1\" Then"));
            sap.runTransaction(ctx, "VA01", 0, 0);
            assertTrue(last(seen).body().contains("sess.StartTransaction") && last(seen).args().get(3).equals("0"));

            sap.setField(ctx, "wnd[0]/usr/txtNOTE", "more", 0, 0, true, true);
            assertEquals(List.of("0", "0", "wnd[0]/usr/txtNOTE", "1", "1"), last(seen).args());
            assertTrue(last(seen).body().contains("e.Text = e.Text & v"));
            sap.getField(ctx, "wnd[0]/usr/txtNOTE", 0, 0, "tooltip");
            assertTrue(last(seen).body().contains(".Tooltip)"));
            fails("SAP.BAD_PROPERTY", () -> sap.getField(ctx, "wnd[0]/usr/txtNOTE", 0, 0, "colour"));
            sap.press(ctx, "wnd[0]/tbar[1]/btn[8]", 0, 0, true);
            assertEquals("1", last(seen).args().get(3));
            sap.sendKey(ctx, 0, 0, 0, 1, false);
            assertEquals(List.of("0", "0", "0", "1", "0"), last(seen).args());
            assertTrue(last(seen).body().contains("sess.findById(\"wnd[\" & a(3) & \"]\").sendVKey"));
            fails("SAP.BAD_WINDOW", () -> sap.sendKey(ctx, 0, 0, 0, 12, false));

            reply.put("text", "Order 4711 saved"); reply.put("type", "S"); reply.put("id", "V1 "); reply.put("number", "311");
            reply.put("params", new ArrayList<>(List.of("Order", "4711", "")));
            Map<String, Object> sb = sap.statusBar(ctx, 0, 0, 1);
            assertEquals("V1", sb.get("message_id"));
            assertEquals("311", sb.get("message_number"));
            assertEquals(List.of("Order", "4711"), sb.get("parameters"));
            assertEquals("1", last(seen).args().get(2));
            reply.clear();

            sap.readGrid(ctx, "wnd[0]/usr/cntlGRID1/shellcont/shell", 10, 0, 0, true);
            assertTrue(last(seen).body().contains("out = out & Q(cols(i)): Next"));
            balanced(seen);
        } finally { SapPackage.testRunner = null; SapPackage.testLogonStarter = null; }
    }

    @Test void sapSelectionsTreesAndTables() throws Exception {
        List<Script> seen = new ArrayList<>();
        Map<String, Object> reply = new HashMap<>();
        SapPackage.testRunner = (b, a, in) -> { seen.add(new Script(b, a, in)); return new HashMap<>(reply); };
        try {
            var sap = new SapPackage();
            var ctx = ctx();
            String grid = "wnd[0]/usr/cntlGRID1/shellcont/shell", tree = "wnd[0]/usr/cntlTREE/shellcont/shell";

            sap.clickMenu(ctx, "5 > 1 > 0", 0, 0, 0, "index");
            assertEquals(List.of("0", "0", "0", "index"), last(seen).args());
            fails("SAP.BAD_MENU", () -> sap.clickMenu(ctx, "System > 2", 0, 0, 0, "index"));
            sap.checkBox(ctx, "wnd[0]/usr/chkP_TEST", true, 0, 0, true);
            assertEquals("toggle", last(seen).args().get(3));
            assertTrue(last(seen).body().contains("e.Selected = Not e.Selected"));
            reply.put("selected", true);
            assertEquals(true, sap.getStatus(ctx, "wnd[0]/usr/radP_ALL", 0, 0).get("selected"));
            reply.clear();

            sap.selectItem(ctx, "wnd[0]/usr/tabsTABSTRIP", "2", "index", 0, 0);
            assertEquals("index", last(seen).args().get(3));
            assertTrue(last(seen).body().contains("If e.Type = \"GuiTabStrip\" Then") && last(seen).body().contains("e.selectNode NodeKey(e, v, a(3))"));
            fails("SAP.BAD_INDEX", () -> sap.selectItem(ctx, "wnd[0]/usr/cmbLANG", "two", "index", 0, 0));
            sap.expandNode(ctx, tree, "Sales documents", false, 0, 0, "text");
            assertEquals(List.of("0", "0", tree, "0", "text"), last(seen).args());
            assertTrue(last(seen).body().contains("tr.GetNodeTextByKey(ks(i))"));
            sap.expandNode(ctx, tree, "          1", true, 0, 0);
            assertEquals("key", last(seen).args().get(4));

            reply.put("key", "EN"); reply.put("text", "English"); reply.put("index", new BigDecimal(3));
            Map<String, Object> sel = sap.getSelectedItem(ctx, "wnd[0]/usr/cmbLANG", 0, 0);
            assertEquals("EN", sel.get("key"));
            assertEquals(new BigDecimal(3), sel.get("index"));
            reply.clear();
            sap.pressToolbarButton(ctx, grid, "&MB_EXPORT", "&PC", 0, 0);
            assertEquals("&MB_EXPORT\r\n&PC\r\n", last(seen).stdin());
            fails("SAP.BAD_BUTTON", () -> sap.pressToolbarButton(ctx, grid, "x\" & y", null, 0, 0));

            reply.put("row", new BigDecimal(2)); reply.put("column", "MATNR");
            reply.put("matches", new ArrayList<>(List.of(new ArrayList<>(List.of(new BigDecimal(2), "MATNR")), new ArrayList<>(List.of(new BigDecimal(7), "MATNR")))));
            Map<String, Object> fc = sap.findCell(ctx, grid, "4711", null, 0, 0, true, true);
            assertEquals(List.of("0", "0", grid, "1", "1"), last(seen).args());
            assertEquals(2, rows(fc.get("matches")).size());
            assertEquals(new BigDecimal(7), rows(fc.get("matches")).get(1).get(0));
            assertTrue(last(seen).body().contains("Function Norm(x)"));
            reply.clear();
            reply.put("count", new BigDecimal(60));
            assertEquals(new BigDecimal(60), sap.cellCount(ctx, grid, 0, 0).get("count"));
            assertEquals("cells", last(seen).args().get(3));
            reply.clear();

            reply.put("columns", new ArrayList<>(List.of("Item", "Text")));
            reply.put("rows", new ArrayList<>(List.of(new ArrayList<>(List.of("10", "Bolt; M8")), new ArrayList<>(List.of("20", "Nut \"A\"")))));
            Map<String, Object> ex = sap.exportTable(ctx, grid, 100, 0, 0, "out/items.csv", "UTF-8", true, false, ";");
            Path csv = Path.of((String) ex.get("path"));
            assertEquals("Item;Text\r\n10;\"Bolt; M8\"\r\n20;\"Nut \"\"A\"\"\"\r\n", Files.readString(csv));
            sap.exportTable(ctx, grid, 100, 0, 0, "out/items.csv", "UTF-8", true, true, ";");
            assertEquals(5, Files.readAllLines(csv).size(), "appending adds rows but no second header");
            sap.exportTable(ctx, grid, 100, 0, 0, "out/tab.csv", "ISO-8859-1", false, false, "tab");
            assertEquals("10\tBolt; M8\r\n20\t\"Nut \"\"A\"\"\"\r\n", Files.readString(ctx.resolvePath("out/tab.csv"), StandardCharsets.ISO_8859_1));
            assertNull(sap.exportTable(ctx, grid, 100, 0, 0).get("path"));
            balanced(seen);
        } finally { SapPackage.testRunner = null; }
    }

    /** Every generated VBScript closes its block If statements and functions. */
    static void balanced(List<Script> seen) {
        for (Script x : seen) {
            String b = x.body();
            long opens = b.lines().map(String::strip).filter(l -> l.endsWith(" Then") && !l.startsWith("ElseIf")).count();
            long closes = b.lines().map(String::strip).filter(l -> l.equals("End If")).count();
            assertEquals(closes, opens, "block If statements are closed: " + b);
            assertEquals(b.lines().filter(l -> l.strip().startsWith("Function ")).count(), b.lines().filter(l -> l.strip().endsWith("End Function")).count());
        }
    }

    // ---------------------------------------------------------------- Active Directory

    @Test void activeDirectoryNewOptions() throws Exception {
        FakeDirectory d = directory();
        var ad = new ActiveDirectoryPackage();
        var ctx = ctx();
        ctx.sessions().put("ad", new ActiveDirectoryPackage.AdSession(d, BASE));
        String staff = "OU=Staff," + BASE;

        Hashtable<String, Object> env = ActiveDirectoryPackage.environment("ldaps://dc1:636", "svc", new SecureString("pw", "t"), 5, 90, false);
        assertEquals("5000", env.get("com.sun.jndi.ldap.connect.timeout"));
        assertEquals("90000", env.get("com.sun.jndi.ldap.read.timeout"));
        assertEquals("ignore", env.get(javax.naming.Context.REFERRAL));
        fails("AD.BAD_TIMEOUT", () -> ad.connect(ctx, "ldaps://dc1:636", "svc", new SecureString("pw", "t"), BASE, "x", 0, 30, true));

        String dn = (String) ad.createUser(ctx, staff, "kim", "Kim", "Lee", null, null, new SecureString("P@ss1!", "t"), true, true, null, "ad", "KL", "Contractor", true).get("dn");
        assertEquals("KL", d.val(dn, "initials"));
        assertEquals("Contractor", d.val(dn, "description"));
        assertEquals(String.valueOf(0x200 | 0x10000), d.val(dn, "userAccountControl"), "enabled with a password that never expires");
        assertNull(d.get(dn).get("pwdLastSet"), "must-change is not set for a never-expiring password");
        fails("AD.BAD_NAME", () -> ad.createUser(ctx, staff, "kim2", null, null, null, null, null, true, true, null, "ad", "TOOLONG", null, false));
        d.get(dn).put("objectCategory", "person");

        @SuppressWarnings("unchecked") Map<String, Object> u = (Map<String, Object>) ad.getUser(ctx, "kim", "ad", List.of("initials", "description")).get("user");
        assertEquals("KL", u.get("initials"));
        assertEquals("Contractor", u.get("description"));
        fails("AD.BAD_ATTRIBUTE", () -> ad.getUser(ctx, "kim", "ad", List.of("bad attr")));

        @SuppressWarnings("unchecked") List<Object> changed = (List<Object>) ad.updateUserDetails(ctx, "kim", null, "Lee-Smith", null, "Kim Lee-Smith", null, "B2.14", "+49 30 1234", "kim@example.com",
                "Analyst", "Finance", "Acme", "bob", Map.of("employeeID", "E77"), "ad").get("changed");
        assertEquals(List.of("sn", "displayName", "physicalDeliveryOfficeName", "telephoneNumber", "mail", "title", "department", "company", "manager", "employeeID"), changed);
        assertEquals("CN=Bob Ray," + staff, d.val(dn, "manager"), "the manager is looked up by user name");
        assertEquals("B2.14", d.val(dn, "physicalDeliveryOfficeName"));
        fails("AD.NO_CHANGES", () -> ad.updateUserDetails(ctx, "kim", null, null, null, null, null, null, null, null, null, null, null, null, null, "ad"));

        Map<String, Object> opts = ad.accountOptions(ctx, "kim", null, null, null, "ad", true, true, null, null, "2026-12-31");
        assertEquals(new BigDecimal(0x200 | 0x10000 | 0x40000 | 0x100000), opts.get("user_account_control"));
        assertEquals("134432352000000000", d.val(dn, "accountExpires"), "start of 2027-01-01 UTC in 100-ns steps since 1601");
        ad.accountOptions(ctx, "kim", null, null, null, "ad", false, null, null, null, "never");
        assertEquals("0", d.val(dn, "accountExpires"));
        assertEquals(String.valueOf(0x200 | 0x10000 | 0x100000), d.val(dn, "userAccountControl"));
        fails("AD.BAD_DATE", () -> ad.accountOptions(ctx, "kim", null, null, null, "ad", null, null, null, null, "31.12.2026"));

        d.log.clear();
        ad.resetPassword(ctx, "kim", new SecureString("N3w!", "t"), false, "ad", true);
        assertEquals(List.of(dn + " 2 unicodePwd", dn + " 2 lockoutTime"), d.log);

        ad.setAttribute(ctx, "kim", "otherTelephone", "111", "ad", "add");
        ad.setAttribute(ctx, "kim", "otherTelephone", "222", "ad", "add");
        ad.setAttribute(ctx, "kim", "otherTelephone", "111", "ad", "remove");
        assertEquals("222", d.val(dn, "otherTelephone"));
        fails("AD.NO_VALUE", () -> ad.setAttribute(ctx, "kim", "otherTelephone", "", "ad", "add"));
        fails("AD.BAD_MODE", () -> ad.setAttribute(ctx, "kim", "title", "x", "ad", "append"));

        String g = (String) ad.createGroup(ctx, "OU=Groups," + BASE, "Auditors", "global", "security", null, null, "ad").get("dn");
        assertEquals(new BigDecimal(2), ad.addToGroup(ctx, "kim", "Auditors", "ad", List.of("bob")).get("changed"), "group by name, several users");
        assertEquals(BigDecimal.ZERO, ad.addToGroup(ctx, "kim", g, "ad", null).get("changed"), "already a member");
        fails("AD.USER_NOT_FOUND", () -> ad.addToGroup(ctx, "AdminsA", g, "ad", null));
    }

    @Test void activeDirectoryMemberTypesAndMoveGroup() throws Exception {
        FakeDirectory d = directory();
        var ad = new ActiveDirectoryPackage();
        var ctx = ctx();
        ctx.sessions().put("ad", new ActiveDirectoryPackage.AdSession(d, BASE));
        String g = (String) ad.createGroup(ctx, "OU=Groups," + BASE, "Team", "global", "security", null, null, "ad").get("dn");
        ad.addToGroup(ctx, "bob", g, "ad", null);
        d.add("CN=PC1,OU=Computers," + BASE, "objectClass", "computer", "cn", "PC1", "sAMAccountName", "PC1$", "memberOf", g);
        assertEquals(new BigDecimal(2), ad.groupMembers(ctx, g, false, 100, "ad", "all").get("count"));
        assertEquals(BigDecimal.ONE, ad.groupMembers(ctx, g, false, 100, "ad", "computer").get("count"));
        assertEquals("bob", rows(ad.groupMembers(ctx, g, false, 100, "ad", "user").get("table")).get(0).get(2));
        fails("AD.BAD_TYPE", () -> ad.groupMembers(ctx, g, false, 100, "ad", "printer"));
        assertEquals(BigDecimal.ONE, ad.removeFromGroup(ctx, "bob", "Team", "ad", null).get("changed"));
        assertEquals("CN=Team,OU=Staff," + BASE, ad.moveGroup(ctx, "Team", "OU=Staff," + BASE, "ad").get("dn"));
        assertNotNull(d.get("CN=Team,OU=Staff," + BASE));
    }

    // ---------------------------------------------------------------- Salesforce

    @Test void salesforceAuthenticationQueryAndFiles() throws Exception {
        try (Fake f = new Fake()) {
            String rec = "001000000000001AAA";
            f.on("POST /services/oauth2/token", c -> ok("{\"access_token\":\"00D!session\",\"instance_url\":\"https://acme.my.salesforce.com\",\"token_type\":\"Bearer\"}"))
             .on("POST /services/oauth2/revoke", c -> new Object[]{200, ""})
             .on("GET /services/data/v61.0/queryAll", c -> ok("{\"done\":true,\"totalSize\":3,\"records\":[{\"Id\":\"a\"},{\"Id\":\"b\"},{\"Id\":\"c\"}]}"))
             .on("GET /services/data/v61.0/query", c -> q(c).contains("ContentDocumentLink")
                    ? ok("{\"done\":true,\"records\":[{\"ContentDocumentId\":\"069000000000001AAA\",\"ContentDocument\":{\"Title\":\"Invoice\",\"FileExtension\":\"pdf\",\"ContentSize\":4,\"LatestPublishedVersionId\":\"068000000000001AAA\"}},"
                       + "{\"ContentDocumentId\":\"069000000000002AAA\",\"ContentDocument\":{\"Title\":\"photo\",\"FileExtension\":\"png\",\"ContentSize\":1,\"LatestPublishedVersionId\":\"068000000000003AAA\"}}]}")
                    : ok("{\"done\":true,\"records\":[{\"Id\":\"00P000000000001AAA\",\"Name\":\"note.txt\"}]}"))
             .on("GET /services/data/v61.0/sobjects/ContentVersion/068000000000001AAA/VersionData", c -> new Object[]{200, "%PDF".getBytes()})
             .on("GET /services/data/v61.0/sobjects/ContentVersion/068000000000002AAA", c -> ok("{\"ContentDocumentId\":\"069000000000009AAA\"}"))
             .on("POST /services/data/v61.0/sobjects/ContentVersion", c -> new Object[]{201, "{\"id\":\"068000000000002AAA\"}"})
             .on("POST /services/data/v61.0/sobjects/ContentDocumentLink", c -> new Object[]{201, "{\"id\":\"06A\"}"});
            var sf = new SalesforcePackage();
            List<String> log = new ArrayList<>();
            var ctx = ToolkitTestSupport.ctx(Files.createTempDirectory("ooa-sf"), log);

            Map<String, Object> a = sf.authenticate(ctx, f.url(), "password", "3MVG9", new SecureString("cs-1", "t"), "agent@acme.com", new SecureString("pw", "t"), new SecureString("TOK", "t"));
            assertEquals("00D!session", ((SecureString) a.get("access_token")).reveal());
            assertEquals("https://acme.my.salesforce.com", a.get("instance_url"));
            String form = form(f.last("POST", "/services/oauth2/token"));
            assertTrue(form.contains("grant_type=password") && form.contains("password=pwTOK") && form.contains("client_secret=cs-1") && form.contains("username=agent@acme.com"), form);
            for (String l : log) assertFalse(l.contains("cs-1") || l.contains("pwTOK"), "secrets never logged: " + l);
            sf.authenticate(ctx, f.url(), null, "3MVG9", new SecureString("cs-1", "t"), null, null, null);
            assertTrue(form(f.last("POST", "/services/oauth2/token")).contains("grant_type=client_credentials"));
            fails("SALESFORCE.NO_USER", () -> sf.authenticate(ctx, f.url(), "password", "3MVG9", new SecureString("cs", "t"), null, null, null));
            fails("SALESFORCE.NO_SECRET", () -> sf.authenticate(ctx, f.url(), null, "3MVG9", "plain", null, null, null));
            sf.revoke(ctx, f.url(), TOK);
            assertEquals("token=tok-123", f.last("POST", "/services/oauth2/revoke").text());

            Map<String, Object> qa = sf.query(ctx, f.url(), TOK, "SELECT Id FROM Account", 2, "v61.0", true, 500);
            assertEquals(new BigDecimal(2), qa.get("count"), "max_records is honoured within a page");
            assertEquals(new BigDecimal(3), qa.get("total_size"));
            assertEquals("batchSize=500", f.last("GET", "/services/data/v61.0/queryAll").header("Sforce-Query-Options"));
            fails("SALESFORCE.BAD_BATCH", () -> sf.query(ctx, f.url(), TOK, "SELECT Id FROM Account", 2, "v61.0", false, 50));

            Map<String, Object> files = sf.attachments(ctx, f.url(), TOK, rec, "dl", "v61.0", "file", "*.PDF", false);
            assertEquals(BigDecimal.ONE, files.get("count"));
            assertEquals("Invoice.pdf", rows(files.get("table")).get(0).get(1));
            Files.writeString(ctx.resolvePath("dl/Invoice.pdf"), "old");
            Map<String, Object> again = sf.attachments(ctx, f.url(), TOK, rec, "dl", "v61.0", "file", "*.pdf", false);
            assertTrue(((String) ((List<?>) again.get("files")).get(0)).endsWith("2_Invoice.pdf"), "existing file kept when overwrite is off");
            assertEquals("old", Files.readString(ctx.resolvePath("dl/Invoice.pdf")));
            assertEquals(BigDecimal.ONE, sf.attachments(ctx, f.url(), TOK, rec, null, "v61.0", "attachment", null, true).get("count"));

            Files.writeString(ctx.resolvePath("r.csv"), "a,b");
            Map<String, Object> up = sf.upload(ctx, f.url(), TOK, rec, "r.csv", "Report", "v61.0", "AllUsers", "Monthly");
            assertEquals("069000000000009AAA", up.get("content_document_id"));
            String version = f.last("POST", "/services/data/v61.0/sobjects/ContentVersion").text();
            assertFalse(version.contains("FirstPublishLocationId"), version);
            assertTrue(version.contains("\"Description\":\"Monthly\""));
            Map<String, Object> link = json(f.last("POST", "/services/data/v61.0/sobjects/ContentDocumentLink"));
            assertEquals(Map.of("ContentDocumentId", "069000000000009AAA", "LinkedEntityId", rec, "ShareType", "V", "Visibility", "AllUsers"), link);
            fails("SALESFORCE.BAD_VISIBILITY", () -> sf.upload(ctx, f.url(), TOK, rec, "r.csv", null, "v61.0", "Everyone", null));
        }
    }

    // ---------------------------------------------------------------- ServiceNow

    @Test void serviceNowAuthenticationQueryOptionsAndAttachments() throws Exception {
        String sys = "0123456789abcdef0123456789abcdef", att = "fedcba9876543210fedcba9876543210";
        try (Fake f = new Fake()) {
            f.on("POST /oauth_token.do", c -> ok("{\"access_token\":\"sn-at\",\"refresh_token\":\"sn-rt\",\"expires_in\":1799}"))
             .on("POST /oauth_revoke_token.do", c -> ok("{}"))
             .on("GET /api/now/table/incident/" + sys, c -> ok("{\"result\":{\"number\":\"INC1\",\"caller_id\":\"5137\"}}"))
             .on("GET /api/now/table/incident", c -> ok("{\"result\":[{\"number\":\"INC1\",\"caller_id\":{\"display_value\":\"Ann Lee\",\"value\":\"5137\"}}]}"))
             .on("POST /api/now/table/incident", c -> new Object[]{201, "{\"result\":{\"sys_id\":\"" + sys + "\",\"number\":\"INC9\",\"state\":\"1\"}}"})
             .on("PATCH /api/now/table/incident/" + sys, c -> ok("{\"result\":{\"sys_id\":\"" + sys + "\",\"state\":\"2\"}}"))
             .on("POST /api/now/attachment/file", c -> new Object[]{201, "{\"result\":{\"sys_id\":\"" + att + "\"}}"})
             .on("DELETE /api/now/attachment/" + att, c -> new Object[]{204, ""})
             .on("GET /api/now/attachment/" + att + "/file", c -> new Object[]{200, "DATA".getBytes()})
             .on("GET /api/now/attachment/" + att, c -> ok("{\"result\":{\"file_name\":\"trace.log\"}}"));
            var sn = new ServiceNowPackage();
            var ctx = ctx();
            Map<String, Object> a = sn.authenticate(ctx, f.url(), "client1", new SecureString("cs", "t"), null, "agent", new SecureString("pw", "t"));
            assertEquals("sn-at", ((SecureString) a.get("access_token")).reveal());
            assertEquals("sn-rt", ((SecureString) a.get("refresh_token")).reveal());
            assertEquals(new BigDecimal(1799), a.get("expires_in"));
            String form = form(f.last("POST", "/oauth_token.do"));
            assertTrue(form.contains("grant_type=password") && form.contains("username=agent") && form.contains("password=pw") && form.contains("client_id=client1"), form);
            sn.authenticate(ctx, f.url(), "client1", new SecureString("cs", "t"), "client_credentials", null, null);
            assertFalse(form(f.last("POST", "/oauth_token.do")).contains("username"));
            fails("SERVICENOW.NO_USER", () -> sn.authenticate(ctx, f.url(), "client1", new SecureString("cs", "t"), "password", null, null));
            sn.revoke(ctx, f.url(), "client1", new SecureString("cs", "t"), new SecureString("sn-rt", "t"));
            assertTrue(form(f.last("POST", "/oauth_revoke_token.do")).contains("token=sn-rt"));

            Map<String, Object> all = sn.query(ctx, f.url(), "incident", "active=true", List.of("number", "caller_id"), 10, null, null, TOK, "all", "-sys_created_on", 20, false);
            assertEquals(List.of("number", "caller_id", "dv_caller_id"), cols(all.get("table")));
            assertEquals(List.of("INC1", "5137", "Ann Lee"), rows(all.get("table")).get(0));
            String qs = q(f.last("GET", "/api/now/table/incident"));
            assertTrue(qs.contains("sysparm_query=active=true^ORDERBYDESCsys_created_on") && qs.contains("sysparm_offset=20")
                    && qs.contains("sysparm_display_value=all") && qs.contains("sysparm_exclude_reference_link=false"), qs);
            fails("SERVICENOW.BAD_OPTION", () -> sn.query(ctx, f.url(), "incident", null, null, 10, null, null, TOK, "maybe", null, 0, true));
            fails("SERVICENOW.BAD_FIELD", () -> sn.query(ctx, f.url(), "incident", null, List.of("a b"), 10, null, null, TOK, "true", null, 0, true));

            sn.get(ctx, f.url(), "incident", sys, null, null, TOK, List.of("number", "caller_id"), "false");
            String gq = q(f.last("GET", "/api/now/table/incident/" + sys));
            assertTrue(gq.contains("sysparm_display_value=false") && gq.contains("sysparm_fields=number,caller_id"), gq);

            Map<String, Object> cr = sn.create(ctx, f.url(), "incident", Map.of("caller_id", "Ann Lee"), null, null, TOK, true);
            assertEquals("sysparm_input_display_value=true", f.last("POST", "/api/now/table/incident").query());
            assertEquals("1", ((Map<?, ?>) cr.get("record")).get("state"));
            assertEquals("2", ((Map<?, ?>) sn.update(ctx, f.url(), "incident", sys, Map.of("state", "2"), null, null, TOK, false).get("record")).get("state"));

            Files.writeString(ctx.resolvePath("log.bin"), "x");
            sn.attach(ctx, f.url(), "incident", sys, "log.bin", null, null, TOK, "../trace.txt", "text/plain");
            Call up = f.last("POST", "/api/now/attachment/file");
            assertTrue(q(up).contains("file_name=trace.txt"), "the given name is reduced to a safe file name");
            assertEquals("text/plain", up.contentType());
            fails("SERVICENOW.BAD_CONTENT_TYPE", () -> sn.attach(ctx, f.url(), "incident", sys, "log.bin", null, null, TOK, null, "text/plain\r\nX: y"));
            sn.deleteAttachment(ctx, f.url(), att, null, null, TOK);
            assertNotNull(f.last("DELETE", "/api/now/attachment/" + att));
            String saved = (String) sn.downloadAttachment(ctx, f.url(), att, "in", null, null, TOK, "copy.log", true).get("path");
            assertTrue(saved.endsWith("copy.log"));
            fails("SERVICENOW.FILE_EXISTS", () -> sn.downloadAttachment(ctx, f.url(), att, "in", null, null, TOK, "copy.log", false));
        }
    }

    // ---------------------------------------------------------------- Jira

    @Test void jiraIssueFieldsCommentsTransitionsAndProjects() throws Exception {
        try (Fake f = new Fake()) {
            f.on("GET /rest/api/2/user/search", c -> q(c).contains("ann@example.com") ? ok("[{\"accountId\":\"acc-ann\",\"name\":\"ann\"}]") : ok("[]"))
             .on("POST /rest/api/2/issue", c -> new Object[]{201, "{\"id\":\"10001\",\"key\":\"OPS-8\"}"})
             .on("GET /rest/api/2/issue/OPS-8", c -> ok("{\"key\":\"OPS-8\",\"fields\":{\"summary\":\"Disk\",\"labels\":[\"infra\"],\"customfield_1\":{\"value\":\"Gold\"},\"duedate\":\"2026-12-01\"}}"))
             .on("PUT /rest/api/2/issue/OPS-8", c -> new Object[]{204, ""})
             .on("POST /rest/api/2/search", c -> ok("{\"total\":1,\"issues\":[{\"key\":\"OPS-8\",\"fields\":{\"summary\":\"Disk\",\"labels\":[\"a\",\"b\"],\"reporter\":{\"displayName\":\"Bob\"}}}]}"))
             .on("POST /rest/api/2/issue/OPS-8/comment", c -> new Object[]{201, "{\"id\":\"777\",\"author\":{\"displayName\":\"Agent\"},\"created\":\"2026-10-22T10:00:00.000+0000\"}"})
             .on("GET /rest/api/2/issue/OPS-8/transitions", c -> ok("{\"transitions\":[{\"id\":\"31\",\"name\":\"Done\",\"to\":{\"name\":\"Done\"}}]}"))
             .on("POST /rest/api/2/issue/OPS-8/transitions", c -> new Object[]{204, ""})
             .on("GET /rest/api/2/project", c -> ok("[{\"id\":\"1\",\"key\":\"OPS\",\"name\":\"Operations\",\"projectTypeKey\":\"software\"},"
                    + "{\"id\":\"2\",\"key\":\"HR\",\"name\":\"People\",\"projectTypeKey\":\"business\"},{\"id\":\"3\",\"key\":\"OPS2\",\"name\":\"Ops two\",\"projectTypeKey\":\"software\"}]"))
             .on("PUT /rest/api/2/project/OPS", c -> ok("{}"));
            var j = new JiraPackage();
            var ctx = ctx();
            String u = f.url();
            Map<String, Object> cr = j.create(ctx, u, "OPS", "Sub-task", "Disk", null, Map.of("customfield_1", Map.of("value", "Gold")), "a@x.com", TOK, null,
                    "High", "ann@example.com", List.of("infra", "db"), List.of("Storage"), "2026-12-01", "OPS-7");
            assertEquals(u + "/browse/OPS-8", cr.get("url"));
            @SuppressWarnings("unchecked") Map<String, Object> fields = (Map<String, Object>) json(f.last("POST", "/rest/api/2/issue")).get("fields");
            assertEquals(Map.of("name", "High"), fields.get("priority"));
            assertEquals(Map.of("accountId", "acc-ann"), fields.get("assignee"), "email looked up to a Cloud account id");
            assertEquals(List.of("infra", "db"), fields.get("labels"));
            assertEquals(List.of(Map.of("name", "Storage")), fields.get("components"));
            assertEquals("2026-12-01", fields.get("duedate"));
            assertEquals(Map.of("key", "OPS-7"), fields.get("parent"));
            assertEquals(Map.of("value", "Gold"), fields.get("customfield_1"));
            fails("JIRA.BAD_LABEL", () -> j.create(ctx, u, "OPS", "Task", "x", null, null, null, TOK, null, null, null, List.of("two words"), null, null, null));
            fails("JIRA.BAD_DATE", () -> j.create(ctx, u, "OPS", "Task", "x", null, null, null, TOK, null, null, null, null, null, "1.12.2026", null));
            fails("JIRA.USER_NOT_FOUND", () -> j.create(ctx, u, "OPS", "Task", "x", null, null, null, TOK, null, null, "nobody@example.com", null, null, null, null));
            j.create(ctx, u, "OPS", "Task", "x", null, null, null, null, TOK, null, "jdoe", null, null, null, null);
            assertEquals(Map.of("name", "jdoe"), ((Map<?, ?>) json(f.last("POST", "/rest/api/2/issue")).get("fields")).get("assignee"), "Data Center uses user names");

            Map<String, Object> gi = j.get(ctx, u, "OPS-8", null, null, TOK, List.of("labels", "customfield_1", "duedate"));
            @SuppressWarnings("unchecked") Map<String, Object> issue = (Map<String, Object>) gi.get("issue");
            assertEquals(List.of("infra"), issue.get("labels"));
            assertEquals("Gold", issue.get("customfield_1"));
            assertEquals("OPS-8", ((Map<?, ?>) gi.get("raw")).get("key"));

            Map<String, Object> s = j.search(ctx, u, "project = OPS", 10, null, null, TOK, List.of("labels", "reporter"));
            assertEquals(List.of("key", "summary", "status", "assignee", "priority", "updated", "labels", "reporter"), cols(s.get("table")));
            assertEquals(List.of("a", "b"), rows(s.get("table")).get(0).get(6));
            assertEquals("Bob", rows(s.get("table")).get(0).get(7));
            assertTrue(((List<?>) json(f.last("POST", "/rest/api/2/search")).get("fields")).containsAll(List.of("labels", "reporter")));

            Map<String, Object> cm = j.comment(ctx, u, "OPS-8", "Fixed", null, null, TOK, "Administrators", null);
            assertEquals("777", cm.get("comment_id"));
            assertEquals("Agent", cm.get("author"));
            assertEquals(Map.of("type", "role", "value", "Administrators"), json(f.last("POST", "/rest/api/2/issue/OPS-8/comment")).get("visibility"));
            fails("JIRA.BAD_VISIBILITY", () -> j.comment(ctx, u, "OPS-8", "x", null, null, TOK, "A", "B"));

            j.transition(ctx, u, "OPS-8", "Done", null, null, TOK, "Closing", Map.of("resolution", Map.of("name", "Fixed")));
            Map<String, Object> tb = json(f.last("POST", "/rest/api/2/issue/OPS-8/transitions"));
            assertEquals(Map.of("resolution", Map.of("name", "Fixed")), tb.get("fields"));
            assertEquals(Map.of("comment", List.of(Map.of("add", Map.of("body", "Closing")))), tb.get("update"));

            j.updateIssue(ctx, u, "OPS-8", null, true, null, null, TOK, "New title", null, "Low", null, List.of("x"), null);
            assertEquals(Map.of("summary", "New title", "priority", Map.of("name", "Low"), "labels", List.of("x")), json(f.last("PUT", "/rest/api/2/issue/OPS-8")).get("fields"));
            fails("JIRA.NO_FIELDS", () -> j.updateIssue(ctx, u, "OPS-8", null, true, null, null, TOK, null, null, null, null, null, null));

            Map<String, Object> ps = j.getProjects(ctx, u, null, null, TOK, "ops", "software", 1, 1);
            assertEquals(BigDecimal.ONE, ps.get("count"));
            assertEquals("OPS2", rows(ps.get("table")).get(0).get(1), "filtered, then paged");
            j.updateProject(ctx, u, "OPS", null, "a@x.com", TOK, null, "Operations EU", null, "ann@example.com", "UNASSIGNED", "10000");
            Map<String, Object> pb = json(f.last("PUT", "/rest/api/2/project/OPS"));
            assertEquals("Operations EU", pb.get("name"));
            assertEquals("acc-ann", pb.get("leadAccountId"));
            assertEquals("UNASSIGNED", pb.get("assigneeType"));
            assertEquals(new BigDecimal(10000), pb.get("categoryId"));
            fails("JIRA.BAD_ID", () -> j.updateProject(ctx, u, "OPS", null, null, null, TOK, null, null, null, null, "x"));
        }
    }

    // ---------------------------------------------------------------- Workday

    @Test void workdayAuthenticationXmlReportsAndWidLaunch() throws Exception {
        String wid = "abcdef0123456789abcdef0123456789";
        try (Fake f = new Fake()) {
            f.on("POST /ccx/oauth2/acme/token", c -> ok("{\"access_token\":\"wd-at\",\"expires_in\":3600}"))
             .on("GET /ccx/service/customreport2/acme/isu/Workers", c -> ok("<wd:Report_Data xmlns:wd=\"urn:com.workday.report/Workers\">"
                    + "<wd:Report_Entry><wd:Worker wd:Descriptor=\"Ann Lee\"><wd:ID wd:type=\"WID\">w1</wd:ID><wd:ID wd:type=\"Employee_ID\">21001</wd:ID></wd:Worker>"
                    + "<wd:Email>a@x</wd:Email><wd:Email>b@x</wd:Email></wd:Report_Entry>"
                    + "<wd:Report_Entry><wd:Worker wd:Descriptor=\"Bob\"><wd:ID wd:type=\"WID\">w2</wd:ID></wd:Worker><wd:Email>c@x</wd:Email></wd:Report_Entry></wd:Report_Data>"))
             .on("POST /ccx/service/acme/Integrations/v42.0", c -> ok("<env:Envelope xmlns:env=\"http://schemas.xmlsoap.org/soap/envelope/\"><env:Body><wd:Launch_Integration_Response xmlns:wd=\"urn:com.workday/bsvc\">"
                    + "<wd:Integration_Event><wd:Integration_Event_Reference><wd:ID wd:type=\"WID\">" + wid + "</wd:ID></wd:Integration_Event_Reference></wd:Integration_Event></wd:Launch_Integration_Response></env:Body></env:Envelope>"));
            var wd = new WorkdayPackage();
            var ctx = ctx();
            Map<String, Object> a = wd.authenticate(ctx, f.url(), "acme", "client-9", new SecureString("cs", "t"), new SecureString("rt-1", "t"));
            assertEquals("wd-at", ((SecureString) a.get("access_token")).reveal());
            Call tc = f.last("POST", "/ccx/oauth2/acme/token");
            assertEquals("Basic " + Base64.getEncoder().encodeToString("client-9:cs".getBytes()), tc.auth());
            assertEquals("grant_type=refresh_token&refresh_token=rt-1", tc.text());
            fails("WORKDAY.BAD_TENANT", () -> wd.authenticate(ctx, f.url(), "ac me", "c", new SecureString("cs", "t"), new SecureString("r", "t")));

            Map<String, Object> r = wd.getReport(ctx, f.url(), "/ccx/service/customreport2/acme/isu/Workers", null, "workday_xml", null, null, TOK, "reports/workers.xml");
            assertEquals(List.of("Worker.ID_WID", "Worker.ID_Employee_ID", "Email"), cols(r.get("table")));
            assertEquals(Arrays.asList("w1", "21001", "a@x; b@x"), rows(r.get("table")).get(0));
            assertTrue(Files.readString(Path.of((String) r.get("path"))).contains("Report_Entry"), "answer saved unchanged");
            assertTrue(q(f.last("GET", ".*")).contains("format=workday_xml"));
            fails("WORKDAY.BAD_FORMAT", () -> wd.getReport(ctx, f.url(), "/ccx/service/customreport2/acme/isu/Workers", null, "xlsx", null, null, TOK, null));

            assertEquals(wid, wd.launchIntegration(ctx, f.url(), "acme", "0123456789abcdef0123456789abcdef", "v42.0", null, null, TOK, "wid").get("event_id"));
            assertTrue(f.last("POST", "/ccx/service/acme/Integrations/v42.0").text().contains("<bsvc:ID bsvc:type=\"WID\">0123456789abcdef0123456789abcdef</bsvc:ID>"));
            fails("WORKDAY.BAD_ID", () -> wd.launchIntegration(ctx, f.url(), "acme", "INT_Load", "v42.0", null, null, TOK, "wid"));
        }
    }

    @Test void workdaySimpleXmlUsesDescriptors() {
        List<Map<String, Object>> rows = WorkdayPackage.xmlEntries("<Report_Data><Report_Entry><Worker Descriptor=\"Ann\"/><Dept>Sales</Dept><Org><Name>EMEA</Name></Org></Report_Entry></Report_Data>");
        assertEquals(Map.of("Worker", "Ann", "Dept", "Sales", "Org.Name", "EMEA"), rows.get(0));
    }

    // ---------------------------------------------------------------- Generative AI

    @SuppressWarnings("unchecked")
    @Test void promptSamplingOptionsSeveralAnswersAndJson() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> ok(c.text().contains("json_object")
                    ? "{\"choices\":[{\"message\":{\"content\":\"{\\\"total\\\": 120}\"}}],\"usage\":{\"prompt_tokens\":7,\"completion_tokens\":3}}"
                    : "{\"choices\":[{\"message\":{\"content\":\"One\"}},{\"message\":{\"content\":\"Two\"}}],\"usage\":{\"prompt_tokens\":5,\"completion_tokens\":2}}"))
             .on("POST /v1/messages", c -> ok("{\"content\":[{\"type\":\"text\",\"text\":\"Claude\"}],\"usage\":{\"input_tokens\":1,\"output_tokens\":1}}"));
            var g = new GenAiPackage();
            var ctx = PackagesTestSupport.ctx(Files.createTempDirectory("ooa-ai"), Map.of());
            Map<String, Object> r = g.prompt(ctx, "Name it", null, "openai", null, "gpt-test", KEY, f.url() + "/v1", 0.7, 50, false, null, null,
                    0.9, null, List.of("END"), 0.5, -0.5, 42, 2, "text", "user-7", 30);
            Map<String, Object> body = json(f.last("POST", "/v1/chat/completions"));
            assertEquals(new BigDecimal("0.9"), body.get("top_p"));
            assertEquals(List.of("END"), body.get("stop"));
            assertEquals(new BigDecimal("0.5"), body.get("presence_penalty"));
            assertEquals(new BigDecimal("-0.5"), body.get("frequency_penalty"));
            assertEquals(new BigDecimal(42), body.get("seed"));
            assertEquals(new BigDecimal(2), body.get("n"));
            assertEquals("user-7", body.get("user"));
            assertEquals("One", r.get("text"));
            assertEquals(List.of("One", "Two"), r.get("responses"));
            assertEquals(new BigDecimal(5), r.get("input_tokens"));

            Map<String, Object> j = g.prompt(ctx, "Total?", null, "openai", null, "gpt-test", KEY, f.url() + "/v1", 0, 50, false, null, null,
                    null, null, null, null, null, null, 1, "json", null, 0);
            assertEquals(new BigDecimal(120), ((Map<String, Object>) j.get("json")).get("total"));
            Map<String, Object> plain = json(f.last("POST", "/v1/chat/completions"));
            assertFalse(plain.containsKey("top_p") || plain.containsKey("n") || plain.containsKey("stop"), "unset options are not sent: " + plain);

            g.prompt(ctx, "Hi", null, "anthropic", null, "claude-test", KEY, f.url(), 0, 50, false, null, null, 0.8, 40, List.of("STOP"), null, null, null, 1, "text", null, 0);
            Map<String, Object> ab = json(f.last("POST", "/v1/messages"));
            assertEquals(new BigDecimal(40), ab.get("top_k"));
            assertEquals(List.of("STOP"), ab.get("stop_sequences"));
            assertEquals(new BigDecimal("0.8"), ab.get("top_p"));

            fails("AI.BAD_OPTION", () -> g.prompt(ctx, "x", null, "openai", null, "m", KEY, f.url() + "/v1", 0, 50, false, null, null, 1.5, null, null, null, null, null, 1, "text", null, 0));
            fails("AI.BAD_OPTION", () -> g.prompt(ctx, "x", null, "openai", null, "m", KEY, f.url() + "/v1", 0, 50, false, null, null, null, null, List.of("a", "b", "c", "d", "e"), null, null, null, 1, "text", null, 0));
            fails("AI.BAD_OPTION", () -> g.prompt(ctx, "x", null, "openai", null, "m", KEY, f.url() + "/v1", 0, 50, false, null, null, null, null, null, null, null, null, 11, "text", null, 0));
        }
    }

    @SuppressWarnings("unchecked")
    @Test void chatKeepsHistoryPerSessionUntilEnded() throws Exception {
        try (Fake f = new Fake()) {
            int[] n = {0};
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":\"Reply " + (++n[0]) + "\"}}]}"));
            var g = new GenAiPackage();
            var ctx = PackagesTestSupport.ctx(Files.createTempDirectory("ooa-chat"), Map.of());
            String base = f.url() + "/v1";
            assertEquals("Reply 1", g.chat(ctx, "Hello, I am ann@example.com", null, "Be brief", "openai", null, "m", KEY, base, 0, 100, true, 40, null, null, null, null, 0, null, null).get("text"));
            Map<String, Object> second = g.chat(ctx, "What did I say?", null, null, "openai", null, "m", KEY, base, 0, 100, true, 40, null, null, null, null, 0, null, null);
            assertEquals(new BigDecimal(4), second.get("turns"));
            List<Map<String, Object>> msgs = (List<Map<String, Object>>) json(f.last("POST", "/v1/chat/completions")).get("messages");
            assertEquals(List.of("system", "user", "assistant", "user"), msgs.stream().map(m -> m.get("role")).toList());
            assertEquals("Be brief", msgs.get(0).get("content"), "the system text stays with the session");
            assertFalse(String.valueOf(msgs).contains("ann@example.com"), "history is masked on every call");

            g.chat(ctx, "Other chat", "Billing", null, "openai", null, "m", KEY, base, 0, 100, false, 40, null, null, null, null, 0, null, null);
            assertEquals(1, ((List<?>) json(f.last("POST", "/v1/chat/completions")).get("messages")).size(), "separate sessions do not share history");

            g.endChat(ctx, null);
            g.chat(ctx, "Fresh start", null, null, "openai", null, "m", KEY, base, 0, 100, false, 2, null, null, null, null, 0, null, null);
            assertEquals(1, ((List<?>) json(f.last("POST", "/v1/chat/completions")).get("messages")).size(), "End chat forgets the conversation");
            Map<String, Object> trimmed = g.chat(ctx, "Again", null, null, "openai", null, "m", KEY, base, 0, 100, false, 2, null, null, null, null, 0, null, null);
            assertEquals(new BigDecimal(2), trimmed.get("turns"), "history_limit drops the oldest messages");
            fails("AI.BAD_SESSION", () -> g.chat(ctx, "x", "../s", null, "openai", null, "m", KEY, base, 0, 100, false, 40, null, null, null, null, 0, null, null));
            fails("AI.EMPTY_MESSAGE", () -> g.chat(ctx, " ", null, null, "openai", null, "m", KEY, base, 0, 100, false, 40, null, null, null, null, 0, null, null));
        }
    }

    @Test void skillOverridesAndSoftFailure() throws Exception {
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":\"done\"}}],\"usage\":{\"prompt_tokens\":3,\"completion_tokens\":1}}"));
            Path w = Files.createTempDirectory("ooa-skill");
            Files.createDirectories(w.resolve("ai-skills"));
            Files.writeString(w.resolve("ai-skills/greet.json"), "{\"prompt\":\"Greet ${name}\",\"provider\":\"openai\",\"model\":\"m\",\"temperature\":0.2,\"max_tokens\":300,\"top_p\":0.5}");
            var ctx = PackagesTestSupport.ctx(w, Map.of());
            var s = new AiSkillsPackage();
            Map<String, Object> r = s.run(ctx, "greet", Map.of("name", "Ann"), null, KEY, null, f.url() + "/v1", null, null, null, null, true, 0);
            Map<String, Object> body = json(f.last("POST", "/v1/chat/completions"));
            assertEquals(new BigDecimal("0.2"), body.get("temperature"));
            assertEquals(new BigDecimal(300), body.get("max_tokens"), "the skill's own max_tokens");
            assertEquals(new BigDecimal("0.5"), body.get("top_p"));
            assertEquals(true, r.get("success"));
            assertEquals(new BigDecimal(3), r.get("input_tokens"));
            s.run(ctx, "greet", Map.of("name", "Ann"), null, KEY, null, f.url() + "/v1", null, null, 0.9, 50, true, 0);
            body = json(f.last("POST", "/v1/chat/completions"));
            assertEquals(new BigDecimal("0.9"), body.get("temperature"));
            assertEquals(new BigDecimal(50), body.get("max_tokens"));
            Map<String, Object> soft = s.run(ctx, "missing", Map.of(), null, KEY, null, f.url() + "/v1", null, null, null, null, false, 0);
            assertEquals(false, soft.get("success"));
            assertTrue(String.valueOf(soft.get("error")).startsWith("AI.SKILL_NOT_FOUND"));
            fails("AI.SKILL_NOT_FOUND", () -> s.run(ctx, "missing", Map.of(), null, KEY, null, f.url() + "/v1", null, null, null, null, true, 0));
        }
    }

    @SuppressWarnings("unchecked")
    @Test void agentRolePlanOutputFieldsAndEndStates() throws Exception {
        Map<String, Object> lookup = (Map<String, Object>) JsonLite.parse("""
            {"kind":"ApiTask","id":"lookup_order","name":"Look up order","description":"Find an order by id.",
             "variables":[{"name":"order_id","type":"String","direction":"input","required":true},{"name":"total","type":"Number","direction":"output"}],
             "steps":[{"id":"s","action":"core/set","args":{"variable":"total","value":129.99}}]}""");
        AutomationRepository repo = (id, v) -> { if (id.equals("lookup_order")) return lookup; throw new OoaException("REPO.NOT_FOUND", id); };
        BotInvoker inv = (id, inputs) -> Runner.builder(PackagesTest.REG).build().run(repo.get(id, "production"), inputs);
        String[] finalAnswer = {"{\"status\":\"completed\",\"answer\":\"Found it\",\"outputs\":{\"total\":129.99,\"currency\":null,\"extra\":1}}"};
        try (Fake f = new Fake()) {
            f.on("POST /v1/chat/completions", c -> ok("{\"choices\":[{\"message\":{\"content\":" + JsonLite.write(finalAnswer[0]) + "}}],\"usage\":{\"prompt_tokens\":10,\"completion_tokens\":5}}"));
            var ctx = PackagesTestSupport.ctx(Files.createTempDirectory("ooa-agent"), Map.of("repository", repo, "bot_invoker", inv));
            var a = new AiAgentPackage();
            Map<String, Object> fields = new LinkedHashMap<>(Map.of("total", "order total"));
            fields.put("currency", "ISO currency code");
            Map<String, Object> r = a.run(ctx, "Total of ORD-1?", "Be accurate.", List.of("lookup_order"), null, "openai", null, "m", KEY, f.url() + "/v1", 4, 60000, false, null, null,
                    "Order desk clerk", "Look the order up first.", fields, false, 0, 0.3);
            assertEquals("completed", r.get("status"));
            assertEquals("Found it", r.get("answer"));
            Map<String, Object> outs = (Map<String, Object>) r.get("outputs");
            assertEquals(new BigDecimal("129.99"), outs.get("total"));
            assertTrue(outs.containsKey("currency") && !outs.containsKey("extra"), "exactly the requested output fields");
            Map<String, Object> body = json(f.last("POST", "/v1/chat/completions"));
            String system = String.valueOf(((Map<?, ?>) ((List<?>) body.get("messages")).get(0)).get("content"));
            assertTrue(system.contains("Your role: Order desk clerk") && system.contains("Action plan: Look the order up first.") && system.contains("completed, failed)"), system);
            assertEquals(new BigDecimal("0.3"), body.get("temperature"));

            finalAnswer[0] = "{\"status\":\"cancelled\",\"answer\":\"Not ours\",\"outputs\":{}}";
            assertEquals("failed", a.run(ctx, "g", "i", List.of("lookup_order"), null, "openai", null, "m", KEY, f.url() + "/v1", 4, 60000, false, null, null,
                    null, null, fields, false, 0, 0).get("status"), "cancelled is only allowed when switched on");
            assertEquals("cancelled", a.run(ctx, "g", "i", List.of("lookup_order"), null, "openai", null, "m", KEY, f.url() + "/v1", 4, 60000, false, null, null,
                    null, null, fields, true, 0, 0).get("status"));
            finalAnswer[0] = "I could not format that.";
            assertEquals("failed", a.run(ctx, "g", "i", List.of("lookup_order"), null, "openai", null, "m", KEY, f.url() + "/v1", 4, 60000, false, null, null,
                    null, null, fields, false, 0, 0).get("status"));
            finalAnswer[0] = "Plain answer";
            Map<String, Object> plain = a.run(ctx, "g", "i", List.of("lookup_order"), null, "openai", null, "m", KEY, f.url() + "/v1", 4, 60000, false, null, null);
            assertEquals("completed", plain.get("status"));
            assertEquals(Map.of(), plain.get("outputs"));
            fails("AGENT.BAD_OPTION", () -> a.run(ctx, "g", "i", List.of("lookup_order"), null, "openai", null, "m", KEY, f.url() + "/v1", 4, 60000, false, null, null,
                    null, null, null, false, -1, 0));
        }
    }

    // ---------------------------------------------------------------- Work queues and insights

    @Test void workItemUpdateHoldAndReprocess() throws Exception {
        var q = new WorkloadPackage();
        var ctx = ctx();
        String id = (String) q.addItem(ctx, "inv", Map.of("invoice", "INV-1", "amount", 10)).get("item_id");
        q.getNext(ctx, "inv");
        q.fail(ctx, "inv", id, "portal down", 1);
        assertEquals("failed", q.updateItem(ctx, "inv", id, Map.of("amount", 12), false, "unchanged", null, null).get("status"));
        @SuppressWarnings("unchecked") Map<String, Object> item = (Map<String, Object>) q.getItem(ctx, "inv", id).get("item");
        assertEquals(Map.of("invoice", "INV-1", "amount", new BigDecimal(12)), item.get("data"), "fields are merged");
        assertEquals("new", q.updateItem(ctx, "inv", id, null, false, "reprocess", null, Map.of("note", "retry")).get("status"));
        Map<String, Object> next = q.getNext(ctx, "inv");
        assertEquals(id, next.get("item_id"));
        assertEquals(BigDecimal.ONE, next.get("attempt"), "attempts start again after reprocess");

        assertEquals("deferred", q.updateItem(ctx, "inv", id, Map.of("x", 1), true, "on_hold", null, null).get("status"));
        assertEquals(false, q.getNext(ctx, "inv").get("found"), "held indefinitely");
        @SuppressWarnings("unchecked") Map<String, Object> held = (Map<String, Object>) q.getItem(ctx, "inv", id).get("item");
        assertEquals(WorkloadPackage.HOLD_FOREVER, held.get("defer_until"));
        assertEquals(Map.of("x", BigDecimal.ONE), held.get("data"), "replace_data drops the old fields");
        q.updateItem(ctx, "inv", id, null, false, "on_hold", LocalDateTime.now(ZoneOffset.UTC).plusDays(1), null);
        assertFalse(((String) ((Map<?, ?>) q.getItem(ctx, "inv", id).get("item")).get("defer_until")).startsWith("9999"));
        fails("QUEUE.BAD_TIME", () -> q.updateItem(ctx, "inv", id, null, false, "on_hold", LocalDateTime.now(ZoneOffset.UTC).minusDays(1), null));
        fails("QUEUE.BAD_STATUS", () -> q.updateItem(ctx, "inv", id, null, false, "done", null, null));
        fails("QUEUE.BAD_STATUS", () -> q.updateItem(ctx, "inv", id, null, false, "reprocess", LocalDateTime.now(ZoneOffset.UTC).plusDays(1), null));
        fails("QUEUE.ITEM_NOT_FOUND", () -> q.getItem(ctx, "inv", "wi_none"));
    }

    @Test void insightTransactionsAndFilters() throws Exception {
        var ins = new InsightsPackage();
        var ctx = ctx();
        ins.openTransaction(ctx, "Invoice", "invoices");
        Map<String, Object> c = ins.closeTransaction(ctx, "Invoice", Map.of("amount", 120, "supplier", "Acme"), "success");
        assertTrue(((BigDecimal) c.get("duration_seconds")).signum() >= 0);
        ins.openTransaction(ctx, "Credit note", "invoices");
        ins.closeTransaction(ctx, "Credit note", null, "business_exception");
        ins.tag(ctx, "invoices", Map.of("amount", 5));
        Map<String, Object> inv = ins.read(ctx, "invoices", null, null, "Invoice", 0);
        assertEquals(BigDecimal.ONE, inv.get("count"));
        List<Object> row = rows(inv.get("table")).get(0);
        List<String> cs = cols(inv.get("table"));
        assertEquals("success", row.get(cs.indexOf("outcome")));
        assertEquals("Acme", row.get(cs.indexOf("supplier")));
        assertEquals(new BigDecimal(3), ins.read(ctx, "invoices", null).get("count"));
        assertEquals(new BigDecimal(2), ins.read(ctx, "invoices", null, null, null, 2).get("count"), "newest records only");
        assertEquals(BigDecimal.ZERO, ins.read(ctx, "invoices", null, LocalDateTime.now(ZoneOffset.UTC).minusDays(1), null, 0).get("count"));
        fails("INSIGHTS.NOT_OPEN", () -> ins.closeTransaction(ctx, "Invoice", null, "success"));
        ins.openTransaction(ctx, "X", "invoices");
        fails("INSIGHTS.RESERVED_NAME", () -> ins.closeTransaction(ctx, "X", Map.of("outcome", "a"), "success"));
        fails("INSIGHTS.BAD_TRANSACTION", () -> ins.openTransaction(ctx, "<b>", "invoices"));
        fails("INSIGHTS.BAD_CATEGORY", () -> ins.openTransaction(ctx, "Y", "../x"));
        ins.openTransaction(ctx, "Z", "invoices");
        fails("INSIGHTS.SECRET", () -> ins.closeTransaction(ctx, "Z", Map.of("pw", new SecureString("s", "t")), "success"));
    }
}
