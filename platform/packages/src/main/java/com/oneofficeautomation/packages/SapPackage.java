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
            if (m.get("error") != null) throw scriptError(String.valueOf(m.get("error")));
            return m;
        } catch (IOException e) { throw new ActionException("FILE.IO_ERROR", e.getMessage(), null, true);
        } finally { if (script != null) try { Files.deleteIfExists(script); } catch (IOException ignored) { } }
    }

    /**
     * A specific error for what the SAP script reported, so an agent can catch, say, a missing field apart from SAP
     * not running. A Catch on the older SAP.ERROR still catches all of these (see ErrorCodes).
     */
    static ActionException scriptError(String msg) {
        String m = msg.toLowerCase(Locale.ROOT);
        if (m.contains("not running or scripting is disabled")) return new ActionException("SAP.NOT_RUNNING", msg, "Start SAP GUI and enable scripting (SAP GUI options > Accessibility & Scripting; on the server: RZ11 sapgui/user_scripting = TRUE).");
        if (m.startsWith("no sap connection")) return new ActionException("SAP.NO_CONNECTION", msg, "Open the system first (Connect / Log on), or check the connection number (0 = first open system).");
        if (m.startsWith("no sap session")) return new ActionException("SAP.NO_SESSION", msg, "Check the session number (0 = first window of the connection).");
        if (m.startsWith("cannot open system")) return new ActionException("SAP.CONNECT_FAILED", msg, "Check the system name exactly as it appears in SAP Logon, and that the server is reachable.");
        if (m.contains("already logged on")) return new ActionException("SAP.ALREADY_LOGGED_ON", msg, "Choose what to do with the other session in the logon step's multiple logon option.");
        if (m.startsWith("logon failed") || m.startsWith("logon screen not found")) return new ActionException("SAP.LOGON_FAILED", msg, "Check the client, user, password and language.");
        if (m.startsWith("sap reported:")) return new ActionException("SAP.STATUS_ERROR", msg, "SAP showed an error message in the status bar; read it with Get status bar or fix the input.");
        if (m.startsWith("transaction")) return new ActionException("SAP.TRANSACTION_FAILED", msg, "Check the transaction code and that the user may run it.");
        if (m.contains("could not be found") || m.contains("findbyid") || m.contains("not found"))
            return new ActionException("SAP.OBJECT_NOT_FOUND", msg, "Check the element id (record it again or read it with the SAP GUI scripting tracker) and that the right screen is open.");
        return new ActionException("SAP.ACTION_FAILED", msg, "Check SAP GUI is open on the expected screen and the element id is correct.");
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
            @Input(name = "language", type = OoaType.STRING, required = false, defaultValue = "\"EN\"", description = "Logon language.") String lang,
            @Input(name = "system_type", type = OoaType.STRING, required = false, defaultValue = "\"description\"", enumValues = {"description", "connection_string"},
                    description = "Whether system is the SAP Logon entry description or a connection string such as /H/host/S/3200.") String systemType,
            @Input(name = "multiple_logon", type = OoaType.STRING, required = false, defaultValue = "\"ignore\"", enumValues = {"ignore", "continue", "end_other_sessions", "fail"},
                    description = "What to do when SAP reports the user is already logged on: leave the question open, keep the other sessions, end them, or stop with an error.") String multiLogon,
            @Input(name = "logon_path", type = OoaType.FILE, required = false, example = "C:\\Program Files (x86)\\SAP\\FrontEnd\\SAPgui\\saplogon.exe",
                    description = "SAP Logon program to start first when it is not running; SAP Logon must already be open when empty.") String logonPath,
            @Input(name = "start_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "Seconds to wait for SAP Logon to start.") int startTimeout) {
        String mode = "connection_string".equals(systemType) ? "string" : "description";
        String ml = multiLogon == null || multiLogon.isBlank() ? "ignore" : multiLogon;
        if (!Set.of("ignore", "continue", "end_other_sessions", "fail").contains(ml)) throw new ActionException("SAP.BAD_OPTION", "Unknown multiple_logon value '" + multiLogon + "'");
        if (lang != null && !lang.isBlank() && !lang.matches("^[A-Za-z0-9]{1,2}$")) throw new ActionException("SAP.BAD_LANGUAGE", "Invalid logon language '" + lang + "'", "Use a two-letter code such as EN or DE.");
        if (logonPath != null && !logonPath.isBlank()) startLogon(ctx, logonPath, startTimeout);
        String body = String.join("\r\n",
                "Dim pw: pw = WScript.StdIn.ReadLine",
                "If a(4) = \"string\" Then",
                "  Set conn = app.OpenConnectionByConnectionString(a(0), True)",
                "Else",
                "  Set conn = app.OpenConnection(a(0), True)",
                "End If",
                "Check \"Cannot open system \" & a(0)",
                "Set sess = conn.Children(0)",
                "sess.findById(\"wnd[0]/usr/txtRSYST-MANDT\").Text = a(1)",
                "sess.findById(\"wnd[0]/usr/txtRSYST-BNAME\").Text = a(2)",
                "sess.findById(\"wnd[0]/usr/pwdRSYST-BCODE\").Text = pw",
                "sess.findById(\"wnd[0]/usr/txtRSYST-LANGU\").Text = a(3)",
                "sess.findById(\"wnd[0]\").sendVKey 0: Check \"Logon screen not found\"",
                "Dim ml: Set ml = Nothing: Set ml = sess.findById(\"wnd[1]/usr/radMULTI_LOGON_OPT2\", False): Err.Clear",
                "If Not ml Is Nothing And a(5) <> \"ignore\" Then",
                "  If a(5) = \"fail\" Then Fail \"The user is already logged on in another session\"",
                "  If a(5) = \"end_other_sessions\" Then sess.findById(\"wnd[1]/usr/radMULTI_LOGON_OPT1\").Select Else ml.Select",
                "  sess.findById(\"wnd[1]\").sendVKey 0: Check \"Multiple logon question\"",
                "End If",
                "Dim sb: sb = sess.findById(\"wnd[0]/sbar\").MessageType",
                "If sb = \"E\" Then Fail \"Logon failed: \" & sess.findById(\"wnd[0]/sbar\").Text",
                "WScript.StdOut.WriteLine \"{\"\"connection\"\":\" & (app.Children.Count - 1) & \"}\"");
        Map<String, Object> r = run(ctx, body, List.of(system, client, user, lang == null || lang.isBlank() ? "EN" : lang, mode, ml), password.reveal() + "\r\n");
        return Map.of("connection", Values.toNumber(r.getOrDefault("connection", BigDecimal.ZERO)));
    }

    /** Earlier signature, kept for callers written before the connect options. */
    public Map<String, Object> logOn(ActionContext ctx, String system, String client, String user, SecureString password, String lang) {
        return logOn(ctx, system, client, user, password, lang, "description", "ignore", null, 60);
    }

    /** Test hook: records the SAP Logon program instead of starting it. */
    static volatile java.util.function.Consumer<Path> testLogonStarter;

    /** Starts SAP Logon when it is not running and waits until its process is up. */
    static void startLogon(ActionContext ctx, String logonPath, int timeout) {
        Path exe = ctx.resolvePath(logonPath);
        if (!exe.getFileName().toString().toLowerCase(Locale.ROOT).endsWith(".exe")) throw new ActionException("SAP.BAD_LOGON_PATH", "The SAP Logon path must point to an .exe file", "Example: saplogon.exe in the SAP GUI folder.");
        java.util.function.Consumer<Path> hook = testLogonStarter;
        if (hook != null) { hook.accept(exe); return; }
        if (!ProcessRunner.WINDOWS) throw new ActionException("SYSTEM.WINDOWS_ONLY", "SAP GUI automation needs Windows with SAP GUI installed");
        String name = exe.getFileName().toString().toLowerCase(Locale.ROOT);
        java.util.function.BooleanSupplier running = () -> ProcessHandle.allProcesses().anyMatch(p -> p.info().command().map(cmd -> cmd.toLowerCase(Locale.ROOT).endsWith(name)).orElse(false));
        if (running.getAsBoolean()) return;
        if (!Files.isRegularFile(exe)) throw new ActionException("SAP.BAD_LOGON_PATH", "SAP Logon was not found at " + exe, "Check the SAP GUI installation folder.");
        try { new ProcessBuilder(exe.toString()).start(); } catch (IOException e) { throw new ActionException("SAP.START_FAILED", "Cannot start SAP Logon: " + e.getMessage()); }
        long end = System.nanoTime() + Math.max(1, timeout) * 1_000_000_000L;
        while (!running.getAsBoolean()) {
            if (System.nanoTime() > end) throw new ActionException("SAP.START_FAILED", "SAP Logon did not start within " + timeout + " s", null, true);
            HttpJson.pause(ctx, 500);
        }
        HttpJson.pause(ctx, 2000);  // the scripting engine registers a moment after the window appears
    }

    @Action(id = "open_connection", name = "Open system (single sign-on)", category = "Session", timeoutSeconds = 180, capabilities = "desktop",
            description = "Open a system from SAP Logon that signs on by itself (SSO or SNC), with no user or password.")
    @Output(name = "connection", type = OoaType.NUMBER, description = "Connection number for later actions.")
    public Map<String, Object> openConnection(ActionContext ctx,
            @Input(name = "system", type = OoaType.STRING, example = "PRD - Live production", description = "System description exactly as in SAP Logon, or a connection string.") String system,
            @Input(name = "system_type", type = OoaType.STRING, required = false, defaultValue = "\"description\"", enumValues = {"description", "connection_string"}, description = "Whether system is the SAP Logon description or a connection string.") String systemType,
            @Input(name = "logon_path", type = OoaType.FILE, required = false, description = "SAP Logon program to start first when it is not running.") String logonPath,
            @Input(name = "start_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "60", description = "Seconds to wait for SAP Logon to start.") int startTimeout) {
        if (system == null || system.isBlank()) throw new ActionException("SAP.BAD_SYSTEM", "The system is empty");
        if (logonPath != null && !logonPath.isBlank()) startLogon(ctx, logonPath, startTimeout);
        String body = String.join("\r\n",
                "If a(1) = \"string\" Then",
                "  Set conn = app.OpenConnectionByConnectionString(a(0), True)",
                "Else",
                "  Set conn = app.OpenConnection(a(0), True)",
                "End If",
                "Check \"Cannot open system \" & a(0)",
                "Dim sb: sb = conn.Children(0).findById(\"wnd[0]/sbar\").MessageType",
                "If sb = \"E\" Then Fail \"Logon failed: \" & conn.Children(0).findById(\"wnd[0]/sbar\").Text",
                "WScript.StdOut.WriteLine \"{\"\"connection\"\":\" & (app.Children.Count - 1) & \"}\"");
        Map<String, Object> r = run(ctx, body, List.of(system, "connection_string".equals(systemType) ? "string" : "description"), null);
        return Map.of("connection", Values.toNumber(r.getOrDefault("connection", BigDecimal.ZERO)));
    }

    /** Fails the action when the status bar shows an error or abort message (used by the fail_on_error options). */
    private static final String STATUS_CHECK = "\r\nIf a(a.Count - 1) = \"1\" Then\r\n  If sess.findById(\"wnd[0]/sbar\").MessageType = \"E\" Or sess.findById(\"wnd[0]/sbar\").MessageType = \"A\" Then Fail \"SAP reported: \" & sess.findById(\"wnd[0]/sbar\").Text\r\nEnd If";

    @Action(id = "run_transaction", name = "Run transaction", category = "Session", timeoutSeconds = 120, capabilities = "desktop", description = "Start a transaction code, e.g. VA01 or /nME23N.")
    public Map<String, Object> runTransaction(ActionContext ctx,
            @Input(name = "tcode", type = OoaType.STRING, example = "VA01", description = "Transaction code.") String tcode,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "new_window", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Open the transaction in a new SAP window (session) instead of the current one.") boolean newWindow,
            @Input(name = "fail_on_error_message", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Stop with an error when the status bar then shows an error message.") boolean failOnError) {
        if (!tcode.matches("^/?[A-Za-z0-9_/]{1,30}$")) throw new ActionException("SAP.BAD_TCODE", "Invalid transaction code '" + tcode + "'");
        String body = newWindow
                ? "Attach a(0), a(1)\r\nsess.findById(\"wnd[0]/tbar[0]/okcd\").Text = \"/o\" & Replace(Replace(a(2), \"/n\", \"\"), \"/N\", \"\")\r\nsess.findById(\"wnd[0]\").sendVKey 0: Check \"Transaction \" & a(2)"
                : "Attach a(0), a(1)\r\nsess.StartTransaction Replace(Replace(a(2), \"/n\", \"\"), \"/N\", \"\"): Check \"Transaction \" & a(2)";
        run(ctx, body + STATUS_CHECK + "\r\nWScript.StdOut.WriteLine \"{}\"", List.of(String.valueOf(c), String.valueOf(s), tcode, failOnError ? "1" : "0"), null);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the window and error options. */
    public Map<String, Object> runTransaction(ActionContext ctx, String tcode, int c, int s) { return runTransaction(ctx, tcode, c, s, false, false); }

    @Action(id = "set_field", name = "Set field", category = "Fields", capabilities = "desktop", description = "Type a value into a field (Credentials are passed without logging).")
    public Map<String, Object> setField(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "value", description = "Value, or a Credential variable.") Object value,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "append", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Add the value after the text already in the field instead of replacing it.") boolean append,
            @Input(name = "press_enter", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Press Enter in the window afterwards.") boolean enter) {
        String v = value instanceof SecureString ss ? ss.reveal() : Values.text(value);
        run(ctx, "Attach a(0), a(1)\r\nDim v, e: v = WScript.StdIn.ReadLine: Set e = sess.findById(a(2)): Check \"Field \" & a(2)\r\n"
                + "If a(3) = \"1\" Then e.Text = e.Text & v Else e.Text = v\r\nCheck \"Field \" & a(2)\r\n"
                + "If a(4) = \"1\" Then sess.findById(\"wnd[0]\").sendVKey 0: Check \"Enter\"\r\nWScript.StdOut.WriteLine \"{}\"",
                List.of(String.valueOf(c), String.valueOf(s), id(elementId), append ? "1" : "0", enter ? "1" : "0"), v + "\r\n");
        return Map.of();
    }

    /** Earlier signature, kept for callers written before append and press_enter. */
    public Map<String, Object> setField(ActionContext ctx, String elementId, Object value, int c, int s) { return setField(ctx, elementId, value, c, s, false, false); }

    @Action(id = "get_field", name = "Get field", category = "Fields", idempotent = true, capabilities = "desktop", description = "Read the text (or another property) of a field, label or the status bar.")
    @Output(name = "text", type = OoaType.STRING, description = "Field text, or the value of the chosen property.")
    public Map<String, Object> getField(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "property", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "tooltip", "name", "type", "changeable", "max_length", "icon_name"},
                    description = "Which property to read; text by default.") String property) {
        String prop = switch (property == null ? "text" : property) {
            case "text" -> "Text"; case "tooltip" -> "Tooltip"; case "name" -> "Name"; case "type" -> "Type"; case "changeable" -> "Changeable";
            case "max_length" -> "MaxLength"; case "icon_name" -> "IconName";
            default -> throw new ActionException("SAP.BAD_PROPERTY", "Unknown property '" + property + "'", "Use text, tooltip, name, type, changeable, max_length or icon_name.");
        };
        Map<String, Object> r = run(ctx, "Attach a(0), a(1)\r\nDim t: t = CStr(sess.findById(a(2))." + prop + "): Check \"Field \" & a(2)\r\nWScript.StdOut.WriteLine \"{\"\"text\"\":\" & Q(t) & \"}\"",
                List.of(String.valueOf(c), String.valueOf(s), id(elementId)), null);
        return Map.of("text", String.valueOf(r.getOrDefault("text", "")));
    }

    /** Earlier signature, kept for callers written before the property option. */
    public Map<String, Object> getField(ActionContext ctx, String elementId, int c, int s) { return getField(ctx, elementId, c, s, "text"); }

    @Action(id = "press", name = "Press button", category = "Fields", timeoutSeconds = 120, capabilities = "desktop", description = "Press a toolbar or screen button by its id.")
    public Map<String, Object> press(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/tbar[1]/btn[8]", description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "fail_on_error_message", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Stop with an error when the status bar then shows an error message.") boolean failOnError) {
        run(ctx, "Attach a(0), a(1)\r\nsess.findById(a(2)).Press: Check \"Button \" & a(2)" + STATUS_CHECK + "\r\nWScript.StdOut.WriteLine \"{}\"",
                List.of(String.valueOf(c), String.valueOf(s), id(elementId), failOnError ? "1" : "0"), null);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before fail_on_error_message. */
    public Map<String, Object> press(ActionContext ctx, String elementId, int c, int s) { return press(ctx, elementId, c, s, false); }

    @Action(id = "send_key", name = "Send key", category = "Fields", timeoutSeconds = 120, capabilities = "desktop", description = "Send a virtual key: 0 Enter, 3 Back (F3), 8 Execute (F8), 11 Save (Ctrl+S), 12 Cancel (F12).")
    public Map<String, Object> sendKey(ActionContext ctx,
            @Input(name = "vkey", type = OoaType.NUMBER, defaultValue = "0", required = false, description = "SAP virtual key number.") int vkey,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "window", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Window to send the key to (0 = main window, 1 = the first pop-up).") int window,
            @Input(name = "fail_on_error_message", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Stop with an error when the status bar then shows an error message.") boolean failOnError) {
        if (vkey < 0 || vkey > 99) throw new ActionException("SAP.BAD_KEY", "Invalid virtual key " + vkey);
        if (window < 0 || window > 9) throw new ActionException("SAP.BAD_WINDOW", "Invalid window number " + window);
        run(ctx, "Attach a(0), a(1)\r\nsess.findById(\"wnd[\" & a(3) & \"]\").sendVKey CInt(a(2)): Check \"Key\"" + STATUS_CHECK + "\r\nWScript.StdOut.WriteLine \"{}\"",
                List.of(String.valueOf(c), String.valueOf(s), String.valueOf(vkey), String.valueOf(window), failOnError ? "1" : "0"), null);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the window option. */
    public Map<String, Object> sendKey(ActionContext ctx, int vkey, int c, int s) { return sendKey(ctx, vkey, c, s, 0, false); }

    @Action(id = "get_status_bar", name = "Get status bar", category = "Fields", idempotent = true, capabilities = "desktop",
            description = "Read the status bar message and type (S success, W warning, E error, I info), with its message class and number.")
    @Output(name = "text", type = OoaType.STRING, description = "Message text.")
    @Output(name = "type", type = OoaType.STRING, description = "S, W, E, I, A or empty.")
    @Output(name = "message_id", type = OoaType.STRING, description = "Message class (work area), e.g. V1.")
    @Output(name = "message_number", type = OoaType.STRING, description = "Message number within the class, e.g. 311.")
    @Output(name = "parameters", type = OoaType.LIST, description = "The values filled into the message, e.g. the new document number.")
    public Map<String, Object> statusBar(ActionContext ctx,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "window", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Window whose status bar to read (0 = main window).") int window) {
        if (window < 0 || window > 9) throw new ActionException("SAP.BAD_WINDOW", "Invalid window number " + window);
        Map<String, Object> r = run(ctx, String.join("\r\n",
                "Attach a(0), a(1)",
                "Dim b: Set b = sess.findById(\"wnd[\" & a(2) & \"]/sbar\"): Check \"Status bar\"",
                "Dim p1, p2, p3: p1 = b.MessageParameter(0): p2 = b.MessageParameter(1): p3 = b.MessageParameter(2): Err.Clear",
                "WScript.StdOut.WriteLine \"{\"\"text\"\":\" & Q(b.Text) & \",\"\"type\"\":\" & Q(b.MessageType) & \",\"\"id\"\":\" & Q(b.MessageId) & \",\"\"number\"\":\" & Q(b.MessageNumber) & \",\"\"params\"\":[\" & Q(p1) & \",\" & Q(p2) & \",\" & Q(p3) & \"]}\""),
                List.of(String.valueOf(c), String.valueOf(s), String.valueOf(window)), null);
        List<Object> params = new ArrayList<>();
        if (r.get("params") instanceof List<?> l) for (Object o : l) if (o != null && !String.valueOf(o).isBlank()) params.add(String.valueOf(o).strip());
        return Out.of("text", r.getOrDefault("text", ""), "type", r.getOrDefault("type", ""), "message_id", String.valueOf(r.getOrDefault("id", "")).strip(),
                "message_number", String.valueOf(r.getOrDefault("number", "")).strip(), "parameters", params);
    }

    /** Earlier signature, kept for callers written before the window option. */
    public Map<String, Object> statusBar(ActionContext ctx, int c, int s) { return statusBar(ctx, c, s, 0); }

    @Action(id = "read_grid", name = "Read grid", category = "Tables", idempotent = true, timeoutSeconds = 300, capabilities = "desktop",
            description = "Read an ALV grid (GuiGridView) into a table, using the column titles as names.")
    @Output(name = "table", type = OoaType.TABLE, description = "Grid content.")
    public Map<String, Object> readGrid(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/usr/cntlGRID1/shellcont/shell", description = D_ID) String elementId,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "5000", description = "Safety limit.") int max,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "technical_names", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Name the columns by their technical field names (e.g. MATNR) instead of their titles.") boolean technical) {
        String body = String.join("\r\n",
                "Attach a(0), a(1)",
                "Dim g, cols, r, i, n, line, out: Set g = sess.findById(a(2)): Check \"Grid \" & a(2)",
                "Set cols = g.ColumnOrder: n = g.RowCount: If n > CLng(a(3)) Then n = CLng(a(3))",
                "out = \"{\"\"columns\"\":[\"",
                "For i = 0 To cols.Count - 1: If i > 0 Then out = out & \",\"",
                technical ? "out = out & Q(cols(i)): Next" : "out = out & Q(g.GetDisplayedColumnTitle(cols(i))): Next",
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

    /** Earlier signature, kept for callers written before technical_names. */
    public Map<String, Object> readGrid(ActionContext ctx, String elementId, int max, int c, int s) { return readGrid(ctx, elementId, max, c, s, false); }

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
                If a(a.Count - 1) = "index" Then
                  Set m = m.Children(CInt(Trim(parts(i)))): found = (Err.Number = 0): Err.Clear
                Else
                  For j = 0 To m.Children.Count - 1
                    t = Replace(m.Children(CInt(j)).Text, "&", "")
                    If LCase(Trim(t)) = LCase(Trim(parts(i))) Then Set m = m.Children(CInt(j)): found = True: Exit For
                  Next
                End If
                If Not found Then Fail "Menu entry not found: " & Trim(parts(i))
              Next
              m.Select: Check "Menu " & p
            End If
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "click_menu", name = "Choose menu entry", category = "Menus", timeoutSeconds = 120, capabilities = "desktop",
            description = "Choose an entry from the window's menu bar by its path of texts, e.g. System > User Profile > Own Data, by positions, or by its element id.")
    public Map<String, Object> clickMenu(ActionContext ctx,
            @Input(name = "menu_path", type = OoaType.STRING, example = "System > User Profile > Own Data", description = "Menu texts separated by > (or /), or a menu element id such as wnd[0]/mbar/menu[3]/menu[2].") String path,
            @Input(name = "window", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Window number whose menu bar to use (0 = main window).") int window,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "match_by", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "index"},
                    description = "text: the path holds menu texts; index: it holds 0-based positions, e.g. 5 > 1 > 0.") String matchBy) {
        if (path == null || path.isBlank()) throw new ActionException("SAP.BAD_MENU", "The menu path is empty", "Example: System > User Profile > Own Data.");
        if (path.trim().startsWith("wnd[")) id(path.trim());
        boolean byIndex = "index".equals(matchBy);
        if (byIndex && !path.trim().startsWith("wnd[") && !path.matches("^\\s*\\d{1,3}(\\s*[>/]\\s*\\d{1,3})*\\s*$"))
            throw new ActionException("SAP.BAD_MENU", "With match_by index, the menu path must be positions such as 5 > 1 > 0");
        run(ctx, CLICK_MENU, args(c, s, String.valueOf(window), byIndex ? "index" : "text"), line(path));
        return Map.of();
    }

    /** Earlier signature, kept for callers written before match_by. */
    public Map<String, Object> clickMenu(ActionContext ctx, String path, int window, int c, int s) { return clickMenu(ctx, path, window, c, s, "text"); }

    static final String CHECK_BOX = "Attach a(0), a(1)\n" + find("Check box")
            + "If a(3) = \"toggle\" Then e.Selected = Not e.Selected Else e.Selected = (a(3) = \"1\")\nCheck \"Check box \" & a(2)\nWScript.StdOut.WriteLine \"{}\"\n";

    @Action(id = "check_box", name = "Set check box", category = "Fields", capabilities = "desktop", description = "Tick, clear or toggle a check box.")
    public Map<String, Object> checkBox(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "checked", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "True to tick, false to clear.") boolean checked,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "toggle", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Switch the check box to the opposite state; checked is then ignored.") boolean toggle) {
        run(ctx, CHECK_BOX, args(c, s, id(elementId), toggle ? "toggle" : checked ? "1" : "0"), null);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before toggle. */
    public Map<String, Object> checkBox(ActionContext ctx, String elementId, boolean checked, int c, int s) { return checkBox(ctx, elementId, checked, c, s, false); }

    static final String GET_STATE = "Attach a(0), a(1)\n" + find("Element") + """
            Dim st: st = e.Selected
            Check "State of " & a(2)
            If st Then WScript.StdOut.WriteLine "{""selected"":true}" Else WScript.StdOut.WriteLine "{""selected"":false}"
            """;

    @Action(id = "get_status", name = "Get check box or radio state", category = "Fields", idempotent = true, capabilities = "desktop",
            description = "Read whether a check box is ticked or a radio button is selected.")
    @Output(name = "selected", type = OoaType.BOOLEAN, description = "True when ticked or selected.")
    public Map<String, Object> getStatus(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        return Map.of("selected", Boolean.TRUE.equals(run(ctx, GET_STATE, args(c, s, id(elementId)), null).get("selected")));
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

    /** Tree helper: the node key for a key, a node text or a 0-based position among all nodes. */
    private static final String TREE_LIB = """
            Function NodeKey(tr, v, mode)
              Dim ks, i: NodeKey = v
              If mode = "key" Then Exit Function
              Set ks = tr.GetAllNodeKeys
              If mode = "index" Then NodeKey = ks(CLng(v)): Exit Function
              For i = 0 To ks.Count - 1
                If LCase(Trim(tr.GetNodeTextByKey(ks(i)))) = LCase(Trim(v)) Then NodeKey = ks(i): Exit Function
              Next
              Fail "No node with text " & v
            End Function
            """;

    static final String SELECT_ITEM = "Attach a(0), a(1)\n" + find("Drop-down list") + """
            Dim v, i, ok: v = WScript.StdIn.ReadLine: ok = False
            If e.Type = "GuiTabStrip" Then
              For i = 0 To e.Children.Count - 1
                If (a(3) = "index" And CStr(i) = Trim(v)) Or (a(3) <> "index" And LCase(Trim(e.Children(CInt(i)).Text)) = LCase(Trim(v))) Then e.Children(CInt(i)).Select: ok = True: Exit For
              Next
              If Not ok Then Fail "No tab " & v
            ElseIf e.Type = "GuiShell" Then
              e.selectNode NodeKey(e, v, a(3))
            ElseIf a(3) = "key" Then
              e.Key = v
            ElseIf a(3) = "index" Then
              e.Key = e.Entries(CInt(v)).Key
            Else
              For i = 0 To e.Entries.Count - 1
                If LCase(Trim(e.Entries(CInt(i)).Value)) = LCase(Trim(v)) Then e.Key = e.Entries(CInt(i)).Key: ok = True: Exit For
              Next
              If Not ok Then Fail "No entry with text " & v
            End If
            Check "Select " & v
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "select_item", name = "Select list item", category = "Fields", capabilities = "desktop",
            description = "Choose an entry of a drop-down list, a tab of a tab strip, or a node of a tree, by key, displayed text or position.")
    public Map<String, Object> selectItem(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "value", type = OoaType.STRING, description = "Key, displayed text or 0-based position of the entry.") String value,
            @Input(name = "match_by", type = OoaType.STRING, required = false, defaultValue = "\"text\"", enumValues = {"text", "key", "index"}, description = "Whether value is the displayed text, the key, or the 0-based position.") String by,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        String mode = "key".equals(by) || "index".equals(by) ? by : "text";
        if (mode.equals("index") && (value == null || !value.trim().matches("^\\d{1,6}$"))) throw new ActionException("SAP.BAD_INDEX", "With match_by index, value must be a 0-based number");
        run(ctx, TREE_LIB + SELECT_ITEM, args(c, s, id(elementId), mode), line(value));
        return Map.of();
    }

    static final String EXPAND_NODE = "Attach a(0), a(1)\n" + find("Tree") + """
            Dim k: k = NodeKey(e, WScript.StdIn.ReadLine, a(4))
            e.expandNode k: Check "Expand node " & k
            If a(3) = "1" Then e.selectNode k: Check "Select node " & k
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "expand_node", name = "Expand tree node", category = "Trees", timeoutSeconds = 120, capabilities = "desktop", description = "Expand a node of a tree control by its node key, text or position, optionally selecting it.")
    public Map<String, Object> expandNode(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/usr/cntlTREE/shellcont/shell", description = D_ID) String elementId,
            @Input(name = "node_key", type = OoaType.STRING, description = "Node key as shown by the script recorder (keep any leading spaces), or the node text or position (see match_by).") String key,
            @Input(name = "select", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Also select the node.") boolean select,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "match_by", type = OoaType.STRING, required = false, defaultValue = "\"key\"", enumValues = {"key", "text", "index"}, description = "Whether node_key is the node key, its displayed text, or its 0-based position among all nodes.") String by) {
        String mode = "text".equals(by) || "index".equals(by) ? by : "key";
        if (mode.equals("index") && (key == null || !key.trim().matches("^\\d{1,6}$"))) throw new ActionException("SAP.BAD_INDEX", "With match_by index, node_key must be a 0-based number");
        run(ctx, TREE_LIB + EXPAND_NODE, args(c, s, id(elementId), select ? "1" : "0", mode), line(key));
        return Map.of();
    }

    /** Earlier signature, kept for callers written before match_by. */
    public Map<String, Object> expandNode(ActionContext ctx, String elementId, String key, boolean select, int c, int s) { return expandNode(ctx, elementId, key, select, c, s, "key"); }

    static final String GET_SELECTED = "Attach a(0), a(1)\n" + find("Element") + """
            Dim k, t, i, rows: k = "": t = "": i = -1
            If e.Type = "GuiComboBox" Then
              k = e.Key: t = e.Value
              For i = e.Entries.Count - 1 To 0 Step -1
                If e.Entries(CInt(i)).Key = k Then Exit For
              Next
            ElseIf e.Type = "GuiTabStrip" Then
              t = e.SelectedTab.Text: k = e.SelectedTab.Id
              For i = e.Children.Count - 1 To 0 Step -1
                If e.Children(CInt(i)).Id = k Then Exit For
              Next
            ElseIf e.Type = "GuiShell" Then
              If e.SubType = "GridView" Then
                k = e.SelectedRows: t = k
              Else
                k = e.SelectedNode: t = e.GetNodeTextByKey(k)
              End If
            Else
              k = e.Text: t = e.Text
            End If
            Check "Selection of " & a(2)
            WScript.StdOut.WriteLine "{""key"":" & Q(CStr(k)) & ",""text"":" & Q(CStr(t)) & ",""index"":" & i & "}"
            """;

    @Action(id = "get_selected_item", name = "Get selected item", category = "Fields", idempotent = true, capabilities = "desktop",
            description = "Read what is selected in a drop-down list, tab strip, tree or grid.")
    @Output(name = "key", type = OoaType.STRING, description = "Key of the entry, id of the tab, node key, or the selected grid rows (e.g. 0,3-5).")
    @Output(name = "text", type = OoaType.STRING, description = "Displayed text of the selection.")
    @Output(name = "index", type = OoaType.NUMBER, description = "0-based position of the entry or tab; -1 when it does not apply.")
    public Map<String, Object> getSelectedItem(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        Map<String, Object> r = run(ctx, GET_SELECTED, args(c, s, id(elementId)), null);
        return Out.of("key", String.valueOf(r.getOrDefault("key", "")), "text", String.valueOf(r.getOrDefault("text", "")), "index", Values.toNumber(r.getOrDefault("index", BigDecimal.valueOf(-1))));
    }

    static final String TOOLBAR = "Attach a(0), a(1)\n" + find("Toolbar") + """
            Dim b, m: b = WScript.StdIn.ReadLine: m = WScript.StdIn.ReadLine
            If m = "" Then
              e.pressToolbarButton b
            Else
              e.pressToolbarContextButton b: Check "Toolbar menu " & b
              e.selectContextMenuItem m
            End If
            Check "Toolbar button " & b
            WScript.StdOut.WriteLine "{}"
            """;

    @Action(id = "press_toolbar_button", name = "Press grid or tree toolbar button", category = "Tables", timeoutSeconds = 120, capabilities = "desktop",
            description = "Press a button in the toolbar of an ALV grid or tree (e.g. &MB_EXPORT), optionally choosing an entry of its drop-down menu.")
    public Map<String, Object> pressToolbarButton(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/usr/cntlGRID1/shellcont/shell", description = D_ID) String elementId,
            @Input(name = "button_id", type = OoaType.STRING, example = "&MB_EXPORT", description = "Button id as shown by the script recorder.") String button,
            @Input(name = "menu_item", type = OoaType.STRING, required = false, example = "&PC", description = "Function code of the drop-down entry to choose; none when empty.") String menuItem,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        if (button == null || !button.matches("^[A-Za-z0-9&%_.:-]{1,60}$")) throw new ActionException("SAP.BAD_BUTTON", "Invalid toolbar button id '" + button + "'");
        run(ctx, TOOLBAR, args(c, s, id(elementId)), line(button) + line(menuItem));
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
              If a(3) = "cells" Then n = e.RowCount * e.Columns.Count
            Else
              If a(3) = "rows" Then n = e.RowCount Else n = e.ColumnCount
              If a(3) = "cells" Then n = e.RowCount * e.ColumnCount
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
            Dim col, v, r, i, n, cols, fr, fc, all, hits: col = WScript.StdIn.ReadLine: v = Norm(WScript.StdIn.ReadLine): fr = -1: fc = "": hits = ""
            all = (a(a.Count - 1) = "1")
            If IsTable(e) Then
              n = e.Columns.Count
              For r = 0 To e.RowCount - 1
                For i = 0 To n - 1
                  If col = "" Or CStr(ColIndex(e, col)) = CStr(i) Then
                    If Norm(TCell(a(2), r, i).Text) = v Then
                      If fr < 0 Then fr = r: fc = e.Columns(CInt(i)).Title
                      If hits <> "" Then hits = hits & ","
                      hits = hits & "[" & r & "," & Q(e.Columns(CInt(i)).Title) & "]"
                      If Not all Then Exit For
                    End If
                  End If
                Next
                If fr >= 0 And Not all Then Exit For
                Set e = sess.findById(a(2))
              Next
            Else
              Set cols = e.ColumnOrder
              For r = 0 To e.RowCount - 1
                If r Mod 30 = 0 Then e.firstVisibleRow = r
                For i = 0 To cols.Count - 1
                  If col = "" Or LCase(cols(i)) = LCase(GCol(e, col)) Then
                    If Norm(e.GetCellValue(r, cols(i))) = v Then
                      If fr < 0 Then fr = r: fc = cols(i)
                      If hits <> "" Then hits = hits & ","
                      hits = hits & "[" & r & "," & Q(cols(i)) & "]"
                      If Not all Then Exit For
                    End If
                  End If
                Next
                If fr >= 0 And Not all Then Exit For
              Next
            End If
            Check "Search in " & a(2)
            WScript.StdOut.WriteLine "{""row"":" & fr & ",""column"":" & Q(fc) & ",""matches"":[" & hits & "]}"
            """;

    /** Comparison helper: trims, and ignores case unless a(3) is "1". */
    private static final String NORM = """
            Function Norm(x)
              If a(3) = "1" Then Norm = Trim(CStr(x)) Else Norm = LCase(Trim(CStr(x)))
            End Function
            """;

    @Action(id = "get_table_cell_index", name = "Find value in table", category = "Tables", idempotent = true, timeoutSeconds = 300, capabilities = "desktop",
            description = "Find the first cell (or every cell) holding a value, ignoring surrounding spaces, and return its row and column.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the value was found.")
    @Output(name = "row", type = OoaType.NUMBER, description = "Row (0-based), or -1 when not found.")
    @Output(name = "column", type = OoaType.STRING, description = "Column name (grid) or title (table control) of the match.")
    @Output(name = "matches", type = OoaType.TABLE, description = "Columns: row, column; every match when all_occurrences is on, else the first.")
    public Map<String, Object> findCell(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "value", type = OoaType.STRING, description = "Value to look for.") String value,
            @Input(name = "column", type = OoaType.STRING, required = false, description = "Only search this column (position, title or field name); all columns when empty.") String column,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "case_sensitive", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Match upper and lower case exactly.") boolean cs,
            @Input(name = "all_occurrences", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Keep searching and list every matching cell.") boolean all) {
        Map<String, Object> r = run(ctx, TABLE_LIB + NORM + FIND_CELL, args(c, s, id(elementId), cs ? "1" : "0", all ? "1" : "0"), line(column) + line(value));
        BigDecimal row = Values.toNumber(r.getOrDefault("row", BigDecimal.valueOf(-1)));
        List<Object> hits = new ArrayList<>();
        if (r.get("matches") instanceof List<?> l) for (Object o : l) if (o instanceof List<?> p && p.size() == 2) hits.add(new ArrayList<>(Arrays.asList(Values.toNumber(p.get(0)), String.valueOf(p.get(1)))));
        if (hits.isEmpty() && row.signum() >= 0) hits.add(new ArrayList<>(Arrays.asList(row, r.get("column"))));
        return Out.of("found", row.signum() >= 0, "row", row, "column", row.signum() >= 0 ? r.get("column") : null, "matches", Values.table(List.of("row", "column"), hits));
    }

    /** Earlier signature, kept for callers written before case_sensitive and all_occurrences. */
    public Map<String, Object> findCell(ActionContext ctx, String elementId, String value, String column, int c, int s) { return findCell(ctx, elementId, value, column, c, s, false, false); }

    @Action(id = "table_cell_count", name = "Get table cell count", category = "Tables", idempotent = true, capabilities = "desktop", description = "Count the cells (rows times columns) of an ALV grid or a table control.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of cells.")
    public Map<String, Object> cellCount(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, description = D_ID) String elementId,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s) {
        return Map.of("count", Values.toNumber(run(ctx, TABLE_LIB + TABLE_SIZE, args(c, s, id(elementId), "cells"), null).getOrDefault("count", BigDecimal.ZERO)));
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
    @Output(name = "path", type = OoaType.FILE, description = "The CSV file written, when csv_path was given.")
    public Map<String, Object> exportTable(ActionContext ctx,
            @Input(name = "id", type = OoaType.STRING, example = "wnd[0]/usr/tblSAPMV45ATCTRL_U_ERF_AUFTRAG", description = D_ID) String elementId,
            @Input(name = "max_rows", type = OoaType.NUMBER, required = false, defaultValue = "5000", description = "Safety limit.") int max,
            @Input(name = "connection", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_CONN) int c,
            @Input(name = "session", type = OoaType.NUMBER, required = false, defaultValue = "0", description = D_SESS) int s,
            @Input(name = "csv_path", type = OoaType.FILE, required = false, description = "Also save the rows to this CSV file; nothing is saved when empty.") String csvPath,
            @Input(name = "encoding", type = OoaType.STRING, required = false, defaultValue = "\"UTF-8\"", enumValues = {"UTF-8", "UTF-16", "ISO-8859-1", "windows-1252"}, description = "Character encoding of the CSV file.") String encoding,
            @Input(name = "include_header", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Write the column titles as the first CSV line.") boolean header,
            @Input(name = "append", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Add to the end of an existing CSV file instead of replacing it (no header is added to a non-empty file).") boolean append,
            @Input(name = "delimiter", type = OoaType.STRING, required = false, defaultValue = "\",\"", enumValues = {",", ";", "tab", "|"}, description = "Separator between CSV values.") String delimiter) {
        Map<String, Object> r = run(ctx, TABLE_LIB + EXPORT, args(c, s, id(elementId), String.valueOf(max)), null);
        @SuppressWarnings("unchecked") List<String> cols = (List<String>) (List<?>) r.getOrDefault("columns", List.of());
        @SuppressWarnings("unchecked") List<Object> rows = (List<Object>) r.getOrDefault("rows", new ArrayList<>());
        String saved = null;
        if (csvPath != null && !csvPath.isBlank()) saved = writeCsv(ctx.resolvePath(csvPath), cols, rows, encoding, header, append, delimiter).toString();
        return Out.of("table", Values.table(cols, rows), "count", BigDecimal.valueOf(rows.size()), "path", saved);
    }

    /** Earlier signature, kept for callers written before the CSV options. */
    public Map<String, Object> exportTable(ActionContext ctx, String elementId, int max, int c, int s) { return exportTable(ctx, elementId, max, c, s, null, "UTF-8", true, false, ","); }

    static Path writeCsv(Path p, List<String> cols, List<Object> rows, String encoding, boolean header, boolean append, String delimiter) {
        java.nio.charset.Charset cs;
        try { cs = java.nio.charset.Charset.forName(encoding == null || encoding.isBlank() ? "UTF-8" : encoding); }
        catch (RuntimeException e) { throw new ActionException("SAP.BAD_ENCODING", "Unknown encoding '" + encoding + "'"); }
        String d = delimiter == null || delimiter.isEmpty() ? "," : delimiter.equals("tab") ? "\t" : delimiter;
        StringBuilder sb = new StringBuilder();
        boolean existing = append && Files.isRegularFile(p) && p.toFile().length() > 0;
        if (header && !existing) sb.append(csvLine(new ArrayList<>(cols), d));
        for (Object row : rows) sb.append(csvLine(row instanceof List<?> l ? l : List.of(row), d));
        try {
            if (p.getParent() != null) Files.createDirectories(p.getParent());
            if (append) Files.writeString(p, sb, cs, StandardOpenOption.CREATE, StandardOpenOption.APPEND);
            else Files.writeString(p, sb, cs);
        } catch (IOException e) { throw new ActionException("FILE.WRITE_FAILED", "Cannot write " + p + ": " + e.getMessage(), "Check the folder exists and is writable."); }
        return p;
    }

    private static String csvLine(List<?> values, String d) {
        StringJoiner j = new StringJoiner(d, "", "\r\n");
        for (Object v : values) {
            String t = v == null ? "" : Values.text(v);
            j.add(t.contains(d) || t.contains("\"") || t.contains("\n") || t.contains("\r") ? "\"" + t.replace("\"", "\"\"") + "\"" : t);
        }
        return j.toString();
    }
}
