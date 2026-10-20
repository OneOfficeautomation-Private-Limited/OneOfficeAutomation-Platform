package com.oneofficeautomation.packages;

import com.oneofficeautomation.runner.Values;
import com.oneofficeautomation.sdk.*;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/**
 * SAP GUI for Windows through the SAP GUI Scripting API (scripting must be enabled on the server and client).
 * Each command attaches to the open SAP session, so bots can mix SAP with other applications. Element ids are the
 * scripting ids shown by SAP's script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART. Passwords go via standard input.
 */
@OoaPackage(id = "ooa.sap", name = "SAP GUI", version = "1.0.0",
        description = "Log on to SAP, run transactions, set and read fields, press buttons, read grids and the status bar through SAP GUI Scripting.")
public final class SapPackage {

    private static final String LIB = String.join("\r\n",
            "Option Explicit",
            "On Error Resume Next",
            "Dim app, conn, sess, a: Set a = WScript.Arguments",
            "Function Q(s): Q = Chr(34) & Replace(Replace(Replace(Replace(s, \"\\\", \"\\\\\"), Chr(34), \"\\\" & Chr(34)), vbCr, \"\\r\"), vbLf, \"\\n\") & Chr(34): End Function",
            "Sub Fail(msg): WScript.StdOut.WriteLine \"{\"\"error\"\":\" & Q(msg) & \"}\": WScript.Quit 2: End Sub",
            "Sub Check(what): If Err.Number <> 0 Then Fail what & \": \" & Err.Description: End If: End Sub",
            "Set app = GetObject(\"SAPGUI\").GetScriptingEngine: Check \"SAP GUI is not running or scripting is disabled\"",
            "Function Attach(ci, si)",
            "  Set conn = app.Children(CInt(ci)): Check \"No SAP connection \" & ci",
            "  Set sess = conn.Children(CInt(si)): Check \"No SAP session \" & si",
            "End Function",
            "");

    private static Map<String, Object> run(ActionContext ctx, String body, List<String> args, String stdin) {
        ScriptRunner hook = testRunner;
        if (hook != null) return hook.run(body, args, stdin);
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "SAP GUI automation needs Windows with SAP GUI installed", "Run this agent on a Windows runner with SAP GUI.");
        Path script = null;
        try {
            script = Files.createTempFile("ooa-sap-", ".vbs");
            Files.writeString(script, LIB + body.replace("\r\n", "\n").replace("\n", "\r\n") + "\r\n",StandardCharsets.ISO_8859_1);
            List<String> cmd = new ArrayList<>(List.of("cscript", "//NoLogo", "//E:VBScript", script.toString()));
            cmd.addAll(args);
            ProcessRunner.Result r = ProcessRunner.run(ctx, cmd, null, stdin, 300, null, StandardCharsets.ISO_8859_1);
            String out = r.stdout().strip();
            String last = out.isEmpty() ? "{}" : out.substring(out.lastIndexOf('\n') + 1);
            Object parsed;
            try { parsed = JsonLite.parse(last); } catch (IllegalArgumentException e) { throw new ActionException("SAP.FAILED", "Unexpected SAP script output: " + ApplicationPackage.tail(out + r.stderr()), null); }
            @SuppressWarnings("unchecked") Map<String, Object> m = parsed instanceof Map ? (Map<String, Object>) parsed : Map.of();
            if (m.get("error") != null) throw new ActionException("SAP.ERROR", String.valueOf(m.get("error")), "Check SAP GUI is open, scripting is enabled and the element id is correct.");
            return m;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } finally { if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { } }
    }

    private static final String D_CONN = "SAP connection number (0 = first open system).";
    private static final String D_SESS = "Session (window) number within the connection (0 = first).";
    private static final String D_ID = "Scripting id from the SAP script recorder, e.g. wnd[0]/usr/ctxtVBAK-AUART.";

    private static String id(String id) {
        if (!id.matches("^[A-Za-z0-9_/\\[\\]\\-.:%$]{1,300}$")) throw new ActionException("SAP.BAD_ID", "Invalid SAP element id '" + id + "'", D_ID);
        return id;
    }

    @Action(id = "log_on", name = "Open system and log on", category = "Session", timeoutSeconds = 180, capabilities = "desktop",
            description = "Open a system from SAP Logon by its description and log on with client, user, password and language.")
    @Output(name = "connection", type = OoaType.NUMBER, description = "Connection number for later actions.")
    public Map<String, Object> logOn(ActionContext ctx,
            @Input(name = "system", type = OoaType.STRING, example = "PRD - Live production", description = "System description exactly as in SAP Logon.") String system,
            @Input(name = "client", type = OoaType.STRING, example = "100", description = "Client.") String client,
            @Input(name = "user", type = OoaType.STRING, description = "User.") String user,
            @Input(name = "password", type = OoaType.CREDENTIAL, description = "Password from the vault.") SecureString password,
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"EN\"", description = "Logon language.") String lang) {
        String body = String.join("\r\n",
                "Dim pw: pw = WScript.StdIn.ReadLine",
                "Set conn = app.OpenConnection(a(0), True): Check \"Cannot open system \" & a(0)",
                "Set sess = conn.Children(0)",
                "sess.findById(\"wnd[0]/usr/txtRSYST-MANDT\").Text = a(1)",
                "sess.findById(\"wnd[0]/usr/txtRSYST-BNAME\").Text = a(2)",
                "sess.findById(\"wnd[0]/usr/pwdRSYST-BCODE\").Text = pw",
                "sess.findById(\"wnd[0]/usr/txtRSYST-LANGU\").Text = a(3)",
                "sess.findById(\"wnd[0]\").sendVKey 0: Check \"Logon screen not found\"",
                "Dim sb: sb = sess.findById(\"wnd[0]/sbar\").MessageType",
                "If sb = \"E\" Then Fail \"Logon failed: \" & sess.findById(\"wnd[0]/sbar\").Text",
                "WScript.StdOut.WriteLine \"{\"\"connection\"\":\" & (app.Children.Count - 1) & \"}\"");
        Map<String, Object> r = run(ctx, body, List.of(system, client, user, lang), password.reveal() + "\r\n");
        return Map.of("connection", Values.toNumber(r.getOrDefault("connection", BigDecimal.ZERO)));
    }

    @Action(id = "run_transaction", name = "Run transaction", category = "Session", timeoutSeconds = 120, capabilities = "desktop", description = "Start a transaction code, e.g. VA01 or /nME23N.")
    public Map<String, Object> runTransaction(ActionContext ctx,
            @Input(name = "tcode", type = OoaType.STRING, example = "VA01", description = "Transaction code.") String tcode,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        if (!tcode.matches("^/?[A-Za-z0-9_/]{1,30}$")) throw new ActionException("SAP.BAD_TCODE", "Invalid transaction code '" + tcode + "'");
        run(ctx, "Attach a(0), a(1)\r\nsess.StartTransaction Replace(Replace(a(2), \"/n\", \"\"), \"/N\", \"\"): Check \"Transaction \" & a(2)\r\nWScript.StdOut.WriteLine \"{}\"",
                List.of(String.valueOf(c), String.valueOf(s), tcode), null);
        return Map.of();
    }

    @Action(id = "set_field", name = "Set field", category = "Fields", capabilities = "desktop", description = "Type a value into a field (Credentials are passed without logging).")
    public Map<String, Object> setField(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "value", description = "Value, or a Credential variable.") Object value,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        String v = value instanceof SecureString ss ? ss.reveal() : Values.text(value);
        run(ctx, "Attach a(0), a(1)\r\nDim v: v = WScript.StdIn.ReadLine\r\nsess.findById(a(2)).Text = v: Check \"Field \" & a(2)\r\nWScript.StdOut.WriteLine \"{}\"",
                List.of(String.valueOf(c), String.valueOf(s), id(elementId)), v + "\r\n");
        return Map.of();
    }

    @Action(id = "get_field", name = "Get field", category = "Fields", idempotent = true, capabilities = "desktop", description = "Read the text of a field or label.")
    @Output(name = "text", type = OoaType.STRING, description = "Field text.")
    public Map<String, Object> getField(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        Map<String, Object> r = run(ctx, "Attach a(0), a(1)\r\nDim t: t = sess.findById(a(2)).Text: Check \"Field \" & a(2)\r\nWScript.StdOut.WriteLine \"{\"\"text\"\":\" & Q(t) & \"}\"",
                List.of(String.valueOf(c), String.valueOf(s), id(elementId)), null);
        return Map.of("text", String.valueOf(r.getOrDefault("text", "")));
    }

    @Action(id = "press", name = "Press button", category = "Fields", timeoutSeconds = 120, capabilities = "desktop", description = "Press a toolbar or screen button by its id.")
    public Map<String, Object> press(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/tbar[1]/btn[8]", description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        run(ctx, "Attach a(0), a(1)\r\nsess.findById(a(2)).Press: Check \"Button \" & a(2)\r\nWScript.StdOut.WriteLine \"{}\"", List.of(String.valueOf(c), String.valueOf(s), id(elementId)), null);
        return Map.of();
    }

    @Action(id = "send_key", name = "Send key", category = "Fields", timeoutSeconds = 120, capabilities = "desktop", description = "Send a virtual key: 0 Enter, 3 Back (F3), 8 Execute (F8), 11 Save (Ctrl+S).")
    public Map<String, Object> sendKey(ActionContext ctx,
            @Input(name = "vkey", type = OoaType.NUMBER, defaultValue = "0", required = false, description = "SAP virtual key number.") int vkey,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        if (vkey < 0 || vkey > 99) throw new ActionException("SAP.BAD_KEY", "Invalid virtual key " + vkey);
        run(ctx, "Attach a(0), a(1)\r\nsess.findById(\"wnd[0]\").sendVKey CInt(a(2)): Check \"Key\"\r\nWScript.StdOut.WriteLine \"{}\"", List.of(String.valueOf(c), String.valueOf(s), String.valueOf(vkey)), null);
        return Map.of();
    }

    @Action(id = "get_status_bar", name = "Get status bar", category = "Fields", idempotent = true, capabilities = "desktop",
            description = "Read the status bar message and type (S success, W warning, E error, I info).")
    @Output(name = "text", type = OoaType.STRING, description = "Message text.")
    @Output(name = "type", type = OoaType.STRING, description = "S, W, E, I, A or empty.")
    public Map<String, Object> statusBar(ActionContext ctx,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        Map<String, Object> r = run(ctx, "Attach a(0), a(1)\r\nDim b: Set b = sess.findById(\"wnd[0]/sbar\"): Check \"Status bar\"\r\nWScript.StdOut.WriteLine \"{\"\"text\"\":\" & Q(b.Text) & \",\"\"type\"\":\" & Q(b.MessageType) & \"}\"",
                List.of(String.valueOf(c), String.valueOf(s)), null);
        return Out.of("text", r.getOrDefault("text", ""), "type", r.getOrDefault("type", ""));
    }

    @Action(id = "read_grid", name = "Read grid", category = "Tables", idempotent = true, timeoutSeconds = 300, capabilities = "desktop",
            description = "Read an ALV grid (GuiGridView) into a table, using the column titles as names.")
    @Output(name = "table", type = OoaType.TABLE, description = "Grid content.")
    public Map<String, Object> readGrid(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/usr/cntlGRID1/shellcont/shell", description = D_ID) String elementId,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "5000", description = "Safety limit.") int max,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        String body = String.join("\r\n",
                "Attach a(0), a(1)",
                "Dim g, cols, r, i, n, line, out: Set g = sess.findById(a(2)): Check \"Grid \" & a(2)",
                "Set cols = g.ColumnOrder: n = g.RowCount: If n > CLng(a(3)) Then n = CLng(a(3))",
                "out = \"{\"\"columns\"\":[\"",
                "For i = 0 To cols.Count - 1: If i > 0 Then out = out & \",\"",
                "out = out & Q(g.GetDisplayedColumnTitle(cols(i))): Next",
                "out = out & \"],\"\"rows\"\":[\"",
                "For r = 0 To n - 1: If r > 0 Then out = out & \",\"",
                "  If r Mod 30 = 0 Then g.firstVisibleRow = r",
                "  line = \"[\": For i = 0 To cols.Count - 1: If i > 0 Then line = line & \",\"",
                "  line = line & Q(g.GetCellValue(r, cols(i))): Next: out = out & line & \"]\"",
                "Next: Check \"Reading grid\"",
                "WScript.StdOut.WriteLine out & \"]}\"");
        Map<String, Object> r = run(ctx, body, List.of(String.valueOf(c), String.valueOf(s), id(elementId), String.valueOf(max)), null);
        @SuppressWarnings("unchecked") List<String> cols = (List<String>) (List<?>) r.getOrDefault("columns", List.of());
        @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) r.getOrDefault("rows", List.of());
        return Map.of("table", Values.table(cols, rows));
    }

    @Action(id = "close", name = "Close connection", category = "Session", capabilities = "desktop", description = "Log off and close an SAP connection.")
    public Map<String, Object> close(ActionContext ctx, @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c) {
        run(ctx, "Set conn = app.Children(CInt(a(0))): Check \"No SAP connection\"\r\nconn.CloseConnection\r\nWScript.StdOut.WriteLine \"{}\"", List.of(String.valueOf(c)), null);
        return Map.of();
    }

    // ---- Clicks, menus, selections, trees and tables ----

    /** Test hook: when set, scripts are handed to it instead of cscript (SAP GUI is not installed on build machines). */
    interface ScriptRunner { Map<String, Object> run(String body, List<String> args, String stdin); }
    static volatile ScriptRunner testRunner;

    private static List<String> args(int c, int s, String... more) {
        List<String> l = new ArrayList<>(List.of(String.valueOf(c), String.valueOf(s)));
        l.addAll(List.of(more));
        return l;
    }
    private static String line(String v) { return (v == null ? "" : v.replace("\r", " ").replace("\n", " ")) + "\r\n"; }
    private static String secret(Object v) { return v instanceof SecureString ss ? ss.reveal() : v == null ? "" : Values.text(v); }
    private static String find(String what) { return "Dim e: Set e = sess.findById(a(2)): Check \"" + what + " \" & a(2)\n"; }

    /** Table-control helpers: column by index, title or field name, and cells by absolute row (scrolling as needed). */
    private static final String TABLE_LIB = """
            Function ColIndex(tbl, c)
              Dim i: ColIndex = -1
              If IsNumeric(c) Then ColIndex = CLng(c): Exit Function
              For i = 0 To tbl.Columns.Count - 1
                If LCase(tbl.Columns(CInt(i)).Title) = LCase(c) Then ColIndex = i: Exit Function
                If LCase(tbl.GetCell(0, CInt(i)).Name) = LCase(c) Then ColIndex = i: Exit Function
              Next
              Fail "No column " & c
            End Function
            Function TCell(id, r, c)
              Dim tbl, vr: Set tbl = sess.findById(id)
              vr = r - tbl.VerticalScrollbar.Position
              If vr < 0 Or vr >= tbl.VisibleRowCount Then
                tbl.VerticalScrollbar.Position = r: Set tbl = sess.findById(id): vr = r - tbl.VerticalScrollbar.Position
              End If
              Set TCell = tbl.GetCell(vr, ColIndex(tbl, c))
            End Function
            Function GCol(g, c)
              Dim i, n: Set n = g.ColumnOrder
              If IsNumeric(c) Then GCol = n(CLng(c)): Exit Function
              For i = 0 To n.Count - 1
                If LCase(n(i)) = LCase(c) Or LCase(g.GetDisplayedColumnTitle(n(i))) = LCase(c) Then GCol = n(i): Exit Function
              Next
              Fail "No column " & c
            End Function
            Function IsTable(e): IsTable = (e.Type = "GuiTableControl"): End Function
            """;

    static final String CLICK = "Attach a(0), a(1)\n" + find("Element") + """
            Select Case e.Type
              Case "GuiButton": e.Press
              Case "GuiRadioButton", "GuiTab", "GuiMenu": e.Select
              Case "GuiCheckBox": e.Selected = Not e.Selected
              Case Else: e.SetFocus
            End Select
            Check "Click " & a(2)
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "click", name = "Click", category = "Fields", timeoutSeconds = 120, capabilities = "desktop", description = "Click an element: buttons are pressed, tabs and radio buttons selected, check boxes toggled, other elements focused.")
    public Map<String, Object> click(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        run(ctx, CLICK, args(c, s, id(elementId)), null);
        return Map.of();
    }

    static final String DOUBLE_CLICK = "Attach a(0), a(1)\n" + find("Element") + """
            Dim it, col: it = WScript.StdIn.ReadLine: col = WScript.StdIn.ReadLine
            If e.Type = "GuiShell" Then
              If e.SubType = "GridView" Then
                If it <> "" Then e.setCurrentCell CLng(it), GCol(e, col)
                e.doubleClickCurrentCell
              ElseIf e.SubType = "Tree" Then
                e.doubleClickNode it
              Else
                e.doubleClick
              End If
            Else
              e.SetFocus: sess.findById("wnd[0]").sendVKey 2
            End If
            Check "Double-click " & a(2)
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "double_click", name = "Double-click", category = "Fields", timeoutSeconds = 120, capabilities = "desktop",
            description = "Double-click an element, a grid cell or a tree node (fields get F2, which SAP treats as a double-click).")
    public Map<String, Object> doubleClick(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "item", type = OoaType.STRING, required = false, description = "Grid row (0-based) or tree node key; empty for the current cell or a plain element.") String item,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Grid column (technical name, title or 0-based position).") String column,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        run(ctx, TABLE_LIB + DOUBLE_CLICK, args(c, s, id(elementId)), line(item) + line(column));
        return Map.of();
    }

    static final String RIGHT_CLICK = "Attach a(0), a(1)\n" + find("Element") + """
            Dim it, col, fc: it = WScript.StdIn.ReadLine: col = WScript.StdIn.ReadLine: fc = WScript.StdIn.ReadLine
            If e.Type = "GuiShell" Then
              If e.SubType = "GridView" Then
                If it <> "" Then e.setCurrentCell CLng(it), GCol(e, col)
                e.contextMenu
              ElseIf e.SubType = "Tree" Then
                e.nodeContextMenu it
              Else
                e.contextMenu
              End If
              Check "Context menu " & a(2)
              If fc <> "" Then e.selectContextMenuItem fc: Check "Menu item " & fc
            Else
              e.SetFocus: e.showContextMenu: Check "Context menu " & a(2)
              If fc <> "" Then sess.findById(fc).Select: Check "Menu item " & fc
            End If
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "right_click", name = "Right-click (context menu)", category = "Fields", timeoutSeconds = 120, capabilities = "desktop",
            description = "Open the context menu of an element, grid cell or tree node and optionally choose an entry.")
    public Map<String, Object> rightClick(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "item", type = OoaType.STRING, required = false, description = "Grid row (0-based) or tree node key.") String item,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Grid column (technical name, title or 0-based position).") String column,
            @Input(name = "menu_item", type = OoaType.STRING, required = false, description = "Entry to choose: its function code for grids and trees, or its element id for screen elements.") String menuItem,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        run(ctx, TABLE_LIB + RIGHT_CLICK, args(c, s, id(elementId)), line(item) + line(column) + line(menuItem));
        return Map.of();
    }

    static final String CLICK_MENU = """
            Attach a(0), a(1)
            Dim p, parts, m, i, j, found, t: p = Trim(WScript.StdIn.ReadLine)
            If Left(p, 4) = "wnd[" Then
              sess.findById(p).Select: Check "Menu " & p
            Else
              Set m = sess.findById("wnd[" & a(2) & "]/mbar"): Check "Menu bar"
              If InStr(p, ">") > 0 Then parts = Split(p, ">") Else parts = Split(p, "/")
              For i = 0 To UBound(parts)
                found = False
                For j = 0 To m.Children.Count - 1
                  t = Replace(m.Children(CInt(j)).Text, "&", "")
                  If LCase(Trim(t)) = LCase(Trim(parts(i))) Then Set m = m.Children(CInt(j)): found = True: Exit For
                Next
                If Not found Then Fail "Menu entry not found: " & Trim(parts(i))
              Next
              m.Select: Check "Menu " & p
            End If
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "click_menu", name = "Choose menu entry", category = "Menus", timeoutSeconds = 120, capabilities = "desktop",
            description = "Choose an entry from the window's menu bar by its path of texts, e.g. System > User Profile > Own Data, or by its element id.")
    public Map<String, Object> clickMenu(ActionContext ctx,
            @Input(name = "menu_path", type = OoaType.STRING, example = "System > User Profile > Own Data", description = "Menu texts separated by > (or /), or a menu element id such as wnd[0]/mbar/menu[3]/menu[2].") String path,
            @Input(name = "window", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Window number whose menu bar to use (0 = main window).") int window,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        if (path == null || path.isBlank()) throw new ActionException("SAP.BAD_MENU", "The menu path is empty", "Example: System > User Profile > Own Data.");
        if (path.trim().startsWith("wnd[")) id(path.trim());
        run(ctx, CLICK_MENU, args(c, s, String.valueOf(window)), line(path));
        return Map.of();
    }

    static final String CHECK_BOX = "Attach a(0), a(1)\n" + find("Check box") + "e.Selected = (a(3) = \"1\"): Check \"Check box \" & a(2)\nWScript.StdOut.WriteLine \"{}\"\n";

    @Action(id = "check_box", name = "Set check box", category = "Fields", capabilities = "desktop", description = "Tick or clear a check box.")
    public Map<String, Object> checkBox(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "checked", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "True to tick, false to clear.") boolean checked,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        run(ctx, CHECK_BOX, args(c, s, id(elementId), checked ? "1" : "0"), null);
        return Map.of();
    }

    static final String SELECT_RADIO = "Attach a(0), a(1)\n" + find("Radio button") + "e.Select: Check \"Radio button \" & a(2)\nWScript.StdOut.WriteLine \"{}\"\n";

    @Action(id = "select_radio", name = "Select radio button", category = "Fields", capabilities = "desktop", description = "Select a radio button option.")
    public Map<String, Object> selectRadio(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        run(ctx, SELECT_RADIO, args(c, s, id(elementId)), null);
        return Map.of();
    }

    static final String SELECT_ITEM = "Attach a(0), a(1)\n" + find("Drop-down list") + """
            Dim v, i, ok: v = WScript.StdIn.ReadLine
            If a(3) = "key" Then
              e.Key = v
            Else
              ok = False
              For i = 0 To e.Entries.Count - 1
                If LCase(Trim(e.Entries(CInt(i)).Value)) = LCase(Trim(v)) Then e.Key = e.Entries(CInt(i)).Key: ok = True: Exit For
              Next
              If Not ok Then Fail "No entry with text " & v
            End If
            Check "Select " & v
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "select_item", name = "Select list item", category = "Fields", capabilities = "desktop", description = "Choose an entry of a drop-down list by its key or by its displayed text.")
    public Map<String, Object> selectItem(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "value", type = OoaType.STRING, description = "Key or displayed text of the entry.") String value,
            @Input(name = "match_by", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "key"}, description = "Whether value is the displayed text or the key.") String by,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        run(ctx, SELECT_ITEM, args(c, s, id(elementId), "key".equals(by) ? "key" : "text"), line(value));
        return Map.of();
    }

    static final String EXPAND_NODE = "Attach a(0), a(1)\n" + find("Tree") + """
            Dim k: k = WScript.StdIn.ReadLine
            e.expandNode k: Check "Expand node " & k
            If a(3) = "1" Then e.selectNode k: Check "Select node " & k
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "expand_node", name = "Expand tree node", category = "Trees", timeoutSeconds = 120, capabilities = "desktop", description = "Expand a node of a tree control by its node key, optionally selecting it.")
    public Map<String, Object> expandNode(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/usr/cntlTREE/shellcont/shell", description = D_ID) String elementId,
            @Input(name = "node_key", type = OoaType.STRING, description = "Node key as shown by the script recorder (keep any leading spaces).") String key,
            @Input(name = "select", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also select the node.") boolean select,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        run(ctx, EXPAND_NODE, args(c, s, id(elementId), select ? "1" : "0"), line(key));
        return Map.of();
    }

    static final String GET_CHILDREN = "Attach a(0), a(1)\n" + find("Container") + """
            Dim ch, i, out: out = "{""rows"":["
            For i = 0 To e.Children.Count - 1
              Set ch = e.Children(CInt(i))
              If i > 0 Then out = out & ","
              out = out & "[" & Q(ch.Id) & "," & Q(ch.Type) & "," & Q(ch.Name) & "," & Q(ch.Text) & "]"
            Next
            Check "Children of " & a(2)
            WScript.StdOut.WriteLine out & "]}"
            """;

    @Action(id = "get_children", name = "Get children", category = "Fields", idempotent = true, capabilities = "desktop", description = "List the elements inside a container with their ids, types, names and texts.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: id, type, name, text.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of child elements.")
    public Map<String, Object> getChildren(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/usr", description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        Map<String, Object> r = run(ctx, GET_CHILDREN, args(c, s, id(elementId)), null);
        @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) r.getOrDefault("rows", new ArrayList<>());
        return Map.of("table", Values.table(List.of("id", "type", "name", "text"), rows), "count", BigDecimal.valueOf(rows.size()));
    }

    static final String ITEM_COUNT = "Attach a(0), a(1)\n" + find("Element") + """
            Dim n: n = -1
            If e.Type = "GuiComboBox" Then
              n = e.Entries.Count
            ElseIf e.Type = "GuiTableControl" Then
              n = e.RowCount
            ElseIf e.Type = "GuiShell" Then
              If e.SubType = "GridView" Then n = e.RowCount
              If e.SubType = "Tree" Then n = e.GetAllNodeKeys.Count
            End If
            If n = -1 Then n = e.Children.Count
            Check "Count of " & a(2)
            WScript.StdOut.WriteLine "{""count"":" & n & "}"
            """;

    @Action(id = "get_item_count", name = "Get item count", category = "Fields", idempotent = true, capabilities = "desktop",
            description = "Count the entries of a drop-down list, the rows of a grid or table, the nodes of a tree, or the elements in a container.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of items.")
    public Map<String, Object> itemCount(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        return Map.of("count", Values.toNumber(run(ctx, ITEM_COUNT, args(c, s, id(elementId)), null).getOrDefault("count", BigDecimal.ZERO)));
    }

    static final String TABLE_SIZE = "Attach a(0), a(1)\n" + find("Table") + """
            Dim n
            If IsTable(e) Then
              If a(3) = "rows" Then n = e.RowCount Else n = e.Columns.Count
            Else
              If a(3) = "rows" Then n = e.RowCount Else n = e.ColumnCount
            End If
            Check "Size of " & a(2)
            WScript.StdOut.WriteLine "{""count"":" & n & "}"
            """;

    @Action(id = "table_row_count", name = "Get table row count", category = "Tables", idempotent = true, capabilities = "desktop", description = "Count the rows of an ALV grid or a table control.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of rows.")
    public Map<String, Object> rowCount(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        return Map.of("count", Values.toNumber(run(ctx, TABLE_LIB + TABLE_SIZE, args(c, s, id(elementId), "rows"), null).getOrDefault("count", BigDecimal.ZERO)));
    }

    @Action(id = "table_column_count", name = "Get table column count", category = "Tables", idempotent = true, capabilities = "desktop", description = "Count the columns of an ALV grid or a table control.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of columns.")
    public Map<String, Object> columnCount(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        return Map.of("count", Values.toNumber(run(ctx, TABLE_LIB + TABLE_SIZE, args(c, s, id(elementId), "columns"), null).getOrDefault("count", BigDecimal.ZERO)));
    }

    static final String GET_CELL = "Attach a(0), a(1)\n" + find("Table") + """
            Dim col, t: col = WScript.StdIn.ReadLine
            If IsTable(e) Then t = TCell(a(2), CLng(a(3)), col).Text Else t = e.GetCellValue(CLng(a(3)), GCol(e, col))
            Check "Cell " & a(3) & "/" & col
            WScript.StdOut.WriteLine "{""text"":" & Q(t) & "}"
            """;

    @Action(id = "get_table_cell", name = "Get table cell", category = "Tables", idempotent = true, capabilities = "desktop", description = "Read one cell of an ALV grid or table control.")
    @Output(name = "text", type = OoaType.STRING, description = "Cell value.")
    public Map<String, Object> getCell(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "row", type = OoaType.NUMBER, description = "Row (0-based, counted from the top of the whole table).") int row,
            @Input(name = "column", type = OoaType.STRING, description = "Column: 0-based position, title, or technical field name.") String column,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        if (row < 0) throw new ActionException("SAP.BAD_ROW", "Rows start at 0");
        return Map.of("text", String.valueOf(run(ctx, TABLE_LIB + GET_CELL, args(c, s, id(elementId), String.valueOf(row)), line(column)).getOrDefault("text", "")));
    }

    static final String SET_CELL = "Attach a(0), a(1)\n" + find("Table") + """
            Dim col, v: col = WScript.StdIn.ReadLine: v = WScript.StdIn.ReadLine
            If IsTable(e) Then
              TCell(a(2), CLng(a(3)), col).Text = v
            Else
              e.modifyCell CLng(a(3)), GCol(e, col), v: e.triggerModified
            End If
            Check "Cell " & a(3) & "/" & col
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "set_table_cell", name = "Set table cell", category = "Tables", capabilities = "desktop", description = "Type a value into an editable cell of an ALV grid or table control.")
    public Map<String, Object> setCell(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "row", type = OoaType.NUMBER, description = "Row (0-based, counted from the top of the whole table).") int row,
            @Input(name = "column", type = OoaType.STRING, description = "Column: 0-based position, title, or technical field name.") String column,
            @Input(name = "value", description = "New value, or a Credential variable.") Object value,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        if (row < 0) throw new ActionException("SAP.BAD_ROW", "Rows start at 0");
        run(ctx, TABLE_LIB + SET_CELL, args(c, s, id(elementId), String.valueOf(row)), line(column) + line(secret(value)));
        return Map.of();
    }

    static final String FIND_CELL = "Attach a(0), a(1)\n" + find("Table") + """
            Dim col, v, r, i, n, cols, fr, fc: col = WScript.StdIn.ReadLine: v = LCase(Trim(WScript.StdIn.ReadLine)): fr = -1: fc = ""
            If IsTable(e) Then
              n = e.Columns.Count
              For r = 0 To e.RowCount - 1
                For i = 0 To n - 1
                  If col = "" Or CStr(ColIndex(e, col)) = CStr(i) Then
                    If LCase(Trim(TCell(a(2), r, i).Text)) = v Then fr = r: fc = e.Columns(CInt(i)).Title: Exit For
                  End If
                Next
                If fr >= 0 Then Exit For
                Set e = sess.findById(a(2))
              Next
            Else
              Set cols = e.ColumnOrder
              For r = 0 To e.RowCount - 1
                If r Mod 30 = 0 Then e.firstVisibleRow = r
                For i = 0 To cols.Count - 1
                  If col = "" Or LCase(cols(i)) = LCase(GCol(e, col)) Then
                    If LCase(Trim(e.GetCellValue(r, cols(i)))) = v Then fr = r: fc = cols(i): Exit For
                  End If
                Next
                If fr >= 0 Then Exit For
              Next
            End If
            Check "Search in " & a(2)
            WScript.StdOut.WriteLine "{""row"":" & fr & ",""column"":" & Q(fc) & "}"
            """;

    @Action(id = "get_table_cell_index", name = "Find value in table", category = "Tables", idempotent = true, timeoutSeconds = 300, capabilities = "desktop",
            description = "Find the first cell holding a value (ignoring case and surrounding spaces) and return its row and column.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the value was found.")
    @Output(name = "row", type = OoaType.NUMBER, description = "Row (0-based), or -1 when not found.")
    @Output(name = "column", type = OoaType.STRING, description = "Column name (grid) or title (table control) of the match.")
    public Map<String, Object> findCell(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "value", type = OoaType.STRING, description = "Value to look for.") String value,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Only search this column (position, title or field name); all columns when empty.") String column,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        Map<String, Object> r = run(ctx, TABLE_LIB + FIND_CELL, args(c, s, id(elementId)), line(column) + line(value));
        BigDecimal row = Values.toNumber(r.getOrDefault("row", BigDecimal.valueOf(-1)));
        return Out.of("found", row.signum() >= 0, "row", row, "column", row.signum() >= 0 ? r.get("column") : null);
    }

    static final String EXPORT = "Attach a(0), a(1)\n" + find("Table") + """
            Dim cols, r, i, n, nc, ln, out, tb, vr
            If IsTable(e) Then
              nc = e.Columns.Count: n = e.RowCount: If n > CLng(a(3)) Then n = CLng(a(3))
              out = "{""columns"":["
              For i = 0 To nc - 1: If i > 0 Then out = out & ","
              out = out & Q(e.Columns(CInt(i)).Title): Next
              out = out & "],""rows"":["
              For r = 0 To n - 1: If r > 0 Then out = out & ","
                Set tb = sess.findById(a(2)): vr = r - tb.VerticalScrollbar.Position
                If vr < 0 Or vr >= tb.VisibleRowCount Then tb.VerticalScrollbar.Position = r: Set tb = sess.findById(a(2)): vr = r - tb.VerticalScrollbar.Position
                ln = "[": For i = 0 To nc - 1: If i > 0 Then ln = ln & ","
                ln = ln & Q(tb.GetCell(vr, CInt(i)).Text): Next: out = out & ln & "]"
              Next
            Else
              Set cols = e.ColumnOrder: n = e.RowCount: If n > CLng(a(3)) Then n = CLng(a(3))
              out = "{""columns"":["
              For i = 0 To cols.Count - 1: If i > 0 Then out = out & ","
              out = out & Q(e.GetDisplayedColumnTitle(cols(i))): Next
              out = out & "],""rows"":["
              For r = 0 To n - 1: If r > 0 Then out = out & ","
                If r Mod 30 = 0 Then e.firstVisibleRow = r
                ln = "[": For i = 0 To cols.Count - 1: If i > 0 Then ln = ln & ","
                ln = ln & Q(e.GetCellValue(r, cols(i))): Next: out = out & ln & "]"
              Next
            End If
            Check "Reading " & a(2)
            WScript.StdOut.WriteLine out & "]}"
            """;

    @Action(id = "export_table", name = "Export table", category = "Tables", idempotent = true, timeoutSeconds = 600, capabilities = "desktop",
            description = "Read an ALV grid or a classic table control into a table, scrolling through all rows.")
    @Output(name = "table", type = OoaType.TABLE, description = "Table content with the column titles as names.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of rows read.")
    public Map<String, Object> exportTable(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/usr/tblSAPMV45ATCTRL_U_ERF_AUFTRAG", description = D_ID) String elementId,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "5000", description = "Safety limit.") int max,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        Map<String, Object> r = run(ctx, TABLE_LIB + EXPORT, args(c, s, id(elementId), String.valueOf(max)), null);
        @SuppressWarnings("unchecked") List<String> cols = (List<String>) (List<?>) r.getOrDefault("columns", List.of());
        @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) r.getOrDefault("rows", new ArrayList<>());
        return Map.of("table", Values.table(cols, rows), "count", BigDecimal.valueOf(rows.size()));
    }
}
