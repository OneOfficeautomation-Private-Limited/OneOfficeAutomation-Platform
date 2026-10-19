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
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "SAP GUI automation needs Windows with SAP GUI installed", "Run this bot on a Windows device with SAP GUI.");
        Path script = null;
        try {
            script = Files.createTempFile("ooa-sap-", ".vbs");
            Files.writeString(script, LIB + body + "\r\n", StandardCharsets.ISO_8859_1);
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
    @Output(name = "connection", type = OoaType.NUMBER, description = "Connection number for later commands.")
    public Map<String, Object> logOn(ActionContext ctx,
            @Input(name = "system", type = OoaType.STRING, example = "PRD - Production", description = "System description exactly as in SAP Logon.") String system,
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
}
