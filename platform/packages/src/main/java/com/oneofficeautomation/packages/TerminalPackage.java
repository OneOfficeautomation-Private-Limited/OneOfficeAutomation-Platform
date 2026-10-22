package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.TimeUnit;

/**
 * IBM mainframe (TN3270) terminal automation through the open-source x3270 scripting engine
 * (s3270 on Linux/macOS, ws3270 from the wc3270 installer on Windows). Rows and columns are 1-based, as on screen.
 */
@OoaPackage(id = "ooa.terminal", name = "Terminal emulator (TN3270)", version = "1.0.0",
        description = "Connect to IBM mainframe 3270 screens, type into fields, press Enter and PF keys, and read screen text.")
public final class TerminalPackage {

    static String exe() {
        String env = System.getenv("OOA_S3270");
        if (env != null && !env.isBlank()) return env;
        if (ProcessRunner.WINDOWS) for (String p : List.of("C:\\Program Files\\wc3270\\ws3270.exe", "C:\\Program Files (x86)\\wc3270\\ws3270.exe"))
            if (Files.isRegularFile(Path.of(p))) return p;
        return "s3270";
    }

    /** One s3270 process: send an action line, read data lines until "ok" or "error". */
    public static final class Session implements AutoCloseable {
        final Process proc; final BufferedWriter in; final BufferedReader out;
        /** Friendly key names set with Set key mapping (upper-case name to key). */
        final Map<String, String> keyAliases = new java.util.concurrent.ConcurrentHashMap<>();
        Session(Process p) { this(p, new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8), new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)); }
        /** Also used by tests with an in-memory engine (no process). */
        Session(Process p, Writer w, Reader r) { proc = p; in = new BufferedWriter(w); out = new BufferedReader(r); }
        synchronized List<String> cmd(String action) {
            try {
                in.write(action); in.newLine(); in.flush();
                List<String> data = new ArrayList<>();
                String prev = null;
                for (String line; (line = out.readLine()) != null; ) {
                    if (line.equals("ok")) return data;
                    if (line.equals("error")) throw new ActionException("TERMINAL.COMMAND_FAILED", action.replaceAll("String\\(.*\\)", "String(****)") + " failed: " + String.join(" ", data).trim(),
                            "Check the connection and that the cursor is in an input field.");
                    if (line.startsWith("data: ")) data.add(line.substring(6)); else if (line.equals("data:")) data.add("");
                    prev = line;
                }
                throw new ActionException("TERMINAL.ENDED", "The terminal engine stopped unexpectedly", "Check the host address and network.");
            } catch (IOException e) { throw new ActionException("TERMINAL.IO_ERROR", e.getMessage(), null, true); }
        }
        @Override public void close() {
            try { cmd("Quit"); } catch (RuntimeException ignored) { }
            if (proc == null) return;
            try { if (!proc.waitFor(3, TimeUnit.SECONDS)) proc.destroyForcibly(); } catch (InterruptedException e) { proc.destroyForcibly(); Thread.currentThread().interrupt(); }
        }
    }

    static String quote(String s) {
        StringBuilder b = new StringBuilder("\"");
        for (char c : s.toCharArray()) { if (c == '"' || c == '\\') b.append('\\'); if (c >= 32) b.append(c); }
        return b.append('"').toString();
    }

    private static Session s(ActionContext ctx, String name) { return ctx.session(name, Session.class); }

    /** Test hook: when set, Connect uses it (with the engine command line) instead of starting s3270. */
    interface EngineStarter { Session start(List<String> command) throws IOException; }
    static volatile EngineStarter testStarter;

    private static final String D_SESSION = "Session name.";
    private static final String D_WAIT = "Seconds to wait for the host to unlock the keyboard after the key.";

    @Action(id = "connect", name = "Connect", category = "Terminal", timeoutSeconds = 120, capabilities = "network", description = "Connect to a TN3270 host and wait for the first screen.")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Mainframe host name or address.") String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "23", description = "Port (992 is common for TLS).") int port,
            @Input(name = "tls", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Use a TLS-encrypted connection.") boolean tls,
            @Input(name = "model", type = OoaType.STRING, required = false, defaultValue = "\"3279-2\"", enumValues = {"3279-2", "3279-3", "3279-4", "3279-5", "3278-2", "3278-3", "3278-4", "3278-5"}, description = "Screen model (2 = 24x80, 3 = 32x80, 4 = 43x80, 5 = 27x132; 3278 is monochrome).") String model,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "terminal_type", type = OoaType.STRING, required = false, defaultValue = "\"tn3270e\"", enumValues = {"tn3270e", "tn3270"}, description = "Protocol: tn3270e (extended, the usual choice) or plain tn3270 for hosts that refuse the extensions.") String terminalType,
            @Input(name = "connect_timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Seconds to wait for the connection and the first screen.") int connectTimeout,
            @Input(name = "lu_name", type = OoaType.STRING, required = false, description = "Logical unit (device) name to request from the host; any free one when empty.") String luName,
            @Input(name = "code_page", type = OoaType.STRING, required = false, example = "cp037", description = "Host code page, e.g. cp037 (US), cp273 (German), cp500; the engine default when empty.") String codePage,
            @Input(name = "wait_for_input_field", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Wait until the first screen has a field to type into; turn off for hosts that show a read-only banner first.") boolean waitForInput,
            @Input(name = "wait_for_text", type = OoaType.STRING, required = false, example = "USERID", description = "Text the first screen must show (e.g. a logon prompt) before the agent continues; no check when empty.") String waitText) {
        if (host == null || !host.matches("^[A-Za-z0-9.-]{1,253}$")) throw new ActionException("TERMINAL.BAD_HOST", "Invalid host '" + host + "'");
        if (port < 1 || port > 65535) throw new ActionException("TERMINAL.BAD_PORT", "Invalid port " + port, "Use 23 for telnet or 992 for TLS.");
        String type = terminalType == null || terminalType.isBlank() ? "tn3270e" : terminalType.trim().toLowerCase(Locale.ROOT);
        if (!type.equals("tn3270e") && !type.equals("tn3270"))
            throw new ActionException("TERMINAL.UNSUPPORTED_TYPE", "Terminal type '" + terminalType + "' is not supported", "This toolkit drives IBM 3270 hosts (tn3270e or tn3270).");
        if (luName != null && !luName.isBlank() && !luName.matches("^[A-Za-z0-9#@$._-]{1,16}$")) throw new ActionException("TERMINAL.BAD_LU", "Invalid LU name '" + luName + "'");
        if (codePage != null && !codePage.isBlank() && !codePage.matches("^[A-Za-z0-9_-]{1,32}$")) throw new ActionException("TERMINAL.BAD_CODE_PAGE", "Invalid code page '" + codePage + "'", "Example: cp037.");
        int wait = Math.max(1, connectTimeout <= 0 ? 30 : connectTimeout);
        List<String> command = new ArrayList<>(List.of(exe(), "-model", model == null || model.isBlank() ? "3279-2" : model));
        if (codePage != null && !codePage.isBlank()) { command.add("-codepage"); command.add(codePage.trim()); }
        Session t;
        try {
            EngineStarter hook = testStarter;
            t = hook != null ? hook.start(command) : new Session(new ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start());
        } catch (IOException e) { throw new ActionException("TERMINAL.NOT_INSTALLED", "The x3270 terminal engine is not installed", "Install wc3270 (Windows) or s3270, or set OOA_S3270 to its path."); }
        String target = (tls ? "L:" : "") + (type.equals("tn3270") ? "N:" : "") + (luName == null || luName.isBlank() ? "" : luName.trim() + "@") + host + ":" + port;
        try {
            t.cmd("Connect(" + target + ")");
            t.cmd("Wait(" + wait + "," + (waitForInput ? "InputField" : "3270Mode") + ")");
        } catch (RuntimeException e) { t.close(); throw e; }
        ctx.sessions().put(session, t);
        if (waitText != null && !waitText.isEmpty()) {
            try { waitForText(ctx, waitText, wait, session, null, null, true, false, true, 500); }
            catch (RuntimeException e) { ctx.sessions().remove(session); t.close(); throw e; }
        }
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the extra connect options. */
    public Map<String, Object> connect(ActionContext ctx, String host, int port, boolean tls, String model, String session) {
        return connect(ctx, host, port, tls, model, session, "tn3270e", 30, null, null, true, null);
    }

    /** Presses a key (alias, Enter, PF3...) and, for keys that go to the host, waits for the keyboard to unlock. */
    private static void press(Session t, String key, boolean waitForHost, int timeout) {
        String action = keyAction(t.keyAliases.getOrDefault(key.trim().toUpperCase(Locale.ROOT), key));
        t.cmd(action);
        if (waitForHost && !LOCAL_KEYS.contains(action)) t.cmd("Wait(" + Math.max(1, timeout) + ",InputField)");
    }

    @Action(id = "send_text", name = "Type text", category = "Terminal", timeoutSeconds = 120, description = "Type text at a row and column, or at the cursor; Credentials are typed without being logged.")
    public Map<String, Object> sendText(ActionContext ctx,
            @Input(name = "text", description = "Text, or a Credential variable for passwords.") Object text,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Row (1-based); the cursor position when empty.") Integer row,
            @Input(name = "column", type = OoaType.NUMBER, required = false, description = "Column (1-based).") Integer col,
            @Input(name = "clear_field", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Erase the rest of the field first.") boolean clear,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "press_key", type = OoaType.STRING, required = false, example = "Enter", description = "Key to press after typing, e.g. Enter or PF8; none when empty.") String pressKey,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = D_WAIT) int timeout,
            @Input(name = "delay_after_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Extra pause after typing (and the key), in milliseconds.") int delay,
            @Input(name = "wait_for_text", type = OoaType.STRING, required = false, description = "Text that must appear on the screen afterwards (waits up to timeout_seconds); no check when empty.") String waitText) {
        Session t = s(ctx, session);
        if (row != null && col != null) {
            if (row < 1 || col < 1) throw new ActionException("TERMINAL.BAD_POSITION", "Row and column start at 1");
            t.cmd("MoveCursor(" + (row - 1) + "," + (col - 1) + ")");
        }
        if (clear) t.cmd("EraseEOF");
        String v = text instanceof SecureString ss ? ss.reveal() : com.oneofficeautomation.runner.Values.text(text);
        t.cmd("String(" + quote(v) + ")");
        if (pressKey != null && !pressKey.isBlank()) press(t, pressKey, true, timeout);
        after(ctx, session, delay, waitText, timeout);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before press_key and timeout_seconds. */
    public Map<String, Object> sendText(ActionContext ctx, Object text, Integer row, Integer col, boolean clear, String session) {
        return sendText(ctx, text, row, col, clear, session, null, 30, 0, null);
    }

    @Action(id = "send_key", name = "Press key", category = "Terminal", timeoutSeconds = 120, description = "Press Enter, Tab, Clear, a PF or PA key, and wait for the host to answer.")
    public Map<String, Object> sendKey(ActionContext ctx,
            @Input(name = "key", type = OoaType.STRING, example = "Enter", description = "Enter, Tab, BackTab, Clear, Reset, EraseEOF, EraseInput, Home, Newline, Up, Down, Left, Right, BackSpace, Delete, Insert, FieldEnd, Dup, FieldMark, Attn, SysReq, PF1 to PF24 or PA1 to PA3.") String key,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "wait_for_host", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "After keys that go to the host (Enter, PF, PA, Clear...), wait until it unlocks the keyboard.") boolean waitForHost,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = D_WAIT) int timeout,
            @Input(name = "repeat", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "How many times to press the key, e.g. 3 x Tab.") int repeat,
            @Input(name = "delay_after_ms", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Extra pause after the key, in milliseconds.") int delay,
            @Input(name = "wait_for_text", type = OoaType.STRING, required = false, description = "Text that must appear on the screen after the key (waits up to timeout_seconds); no check when empty.") String waitText) {
        Session t = s(ctx, session);
        if (repeat < 1 || repeat > 200) throw new ActionException("TERMINAL.BAD_REPEAT", "Repeat must be between 1 and 200");
        for (int i = 0; i < repeat; i++) press(t, key, waitForHost, timeout);
        after(ctx, session, delay, waitText, timeout);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the wait options. */
    public Map<String, Object> sendKey(ActionContext ctx, String key, String session) { return sendKey(ctx, key, session, true, 30, 1, 0, null); }

    private void after(ActionContext ctx, String session, int delayMs, String waitText, int timeout) {
        if (delayMs < 0 || delayMs > 600_000) throw new ActionException("TERMINAL.BAD_DELAY", "The delay must be between 0 and 600000 ms");
        if (delayMs > 0) HttpJson.pause(ctx, delayMs);
        if (waitText != null && !waitText.isEmpty()) waitForText(ctx, waitText, Math.max(1, timeout), session, null, null, true, false, true, 500);
    }

    @Action(id = "get_screen", name = "Get screen text", category = "Terminal", idempotent = true, description = "Read the whole screen as text, one line per row.")
    @Output(name = "text", type = OoaType.STRING, description = "Screen text.")
    @Output(name = "lines", type = OoaType.LIST, description = "One item per row.")
    public Map<String, Object> getScreen(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "trim_lines", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Remove trailing spaces from each row.") boolean trim,
            @Input(name = "from_row", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "First row to return (1-based).") int fromRow,
            @Input(name = "to_row", type = OoaType.NUMBER, required = false, defaultValue = "0", description = "Last row to return; 0 for the bottom of the screen.") int toRow) {
        List<String> all = s(ctx, session).cmd("Ascii()");
        int from = Math.max(1, fromRow), to = toRow <= 0 ? all.size() : Math.min(toRow, all.size());
        List<String> rows = new ArrayList<>();
        for (int i = from - 1; i < to; i++) rows.add(trim ? all.get(i).stripTrailing() : all.get(i));
        return Map.of("text", String.join("\n", rows), "lines", new ArrayList<Object>(rows));
    }

    /** Earlier signature, kept for callers written before the row range options. */
    public Map<String, Object> getScreen(ActionContext ctx, String session) { return getScreen(ctx, session, false, 1, 0); }

    @Action(id = "get_text", name = "Get text at position", category = "Terminal", idempotent = true, description = "Read text from a row and column for a given length.")
    @Output(name = "text", type = OoaType.STRING, description = "Text (trimmed unless trim is off).")
    public Map<String, Object> getText(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, description = "Row (1-based).") int row,
            @Input(name = "column", type = OoaType.NUMBER, description = "Column (1-based).") int col,
            @Input(name = "length", type = OoaType.NUMBER, description = "Number of characters.") int len,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "trim", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Remove leading and trailing spaces.") boolean trim) {
        if (row < 1 || col < 1 || len < 1) throw new ActionException("TERMINAL.BAD_POSITION", "Row, column and length start at 1");
        List<String> d = s(ctx, session).cmd("Ascii(" + (row - 1) + "," + (col - 1) + "," + len + ")");
        String v = String.join("", d);
        return Map.of("text", trim ? v.strip() : v);
    }

    /** Earlier signature, kept for callers written before the trim option. */
    public Map<String, Object> getText(ActionContext ctx, int row, int col, int len, String session) { return getText(ctx, row, col, len, session, true); }

    @Action(id = "wait_for_text", name = "Wait for text", category = "Terminal", timeoutSeconds = 600, description = "Wait until text appears (or disappears) on the screen or at a position.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the condition was met in time (false only when fail_on_timeout is off).")
    public Map<String, Object> waitForText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to wait for.") String text,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait.") double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Only look at text starting at this row (1-based); anywhere when empty.") Integer row,
            @Input(name = "column", type = OoaType.NUMBER, required = false, description = "Column (1-based) of that position.") Integer col,
            @Input(name = "case_sensitive", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Match upper and lower case exactly.") boolean cs,
            @Input(name = "wait_until_gone", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Wait until the text is no longer shown, e.g. a PLEASE WAIT message.") boolean gone,
            @Input(name = "fail_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Stop with an error on timeout; when off, found is false instead.") boolean failOnTimeout,
            @Input(name = "poll_interval_ms", type = OoaType.NUMBER, required = false, defaultValue = "500", description = "How often to look at the screen.") int poll) {
        Session t = s(ctx, session);
        if (row != null && (row < 1 || col == null || col < 1)) throw new ActionException("TERMINAL.BAD_POSITION", "Give both row and column, starting at 1");
        String want = cs ? text : text.toLowerCase(Locale.ROOT);
        long end = System.nanoTime() + (long) (timeout * 1e9);
        while (true) {
            String shown = row != null ? String.join("", t.cmd("Ascii(" + (row - 1) + "," + (col - 1) + "," + Math.max(1, text.length()) + ")")) : String.join("\n", t.cmd("Ascii()"));
            if (!cs) shown = shown.toLowerCase(Locale.ROOT);
            boolean present = row != null ? shown.startsWith(want) : shown.contains(want);
            if (present != gone) return Map.of("found", true);
            if (System.nanoTime() > end) {
                if (!failOnTimeout) return Map.of("found", false);
                throw new ActionException("TERMINAL.TEXT_NOT_FOUND", "'" + text + "' did not " + (gone ? "disappear" : "appear") + " within " + timeout + " s", "Check the previous step reached the expected screen.", true);
            }
            HttpJson.pause(ctx, Math.max(50, Math.min(poll, 10_000)));
        }
    }

    /** Earlier signature, kept for callers written before the position and timeout options. */
    public Map<String, Object> waitForText(ActionContext ctx, String text, double timeout, String session) {
        return waitForText(ctx, text, timeout, session, null, null, true, false, true, 500);
    }

    @Action(id = "wait", name = "Wait for screen", category = "Terminal", timeoutSeconds = 600,
            description = "Wait until the host unlocks the keyboard, shows an input field, changes the screen, or a number of seconds passes.")
    @Output(name = "met", type = OoaType.BOOLEAN, description = "True when the condition was met in time (false only when fail_on_timeout is off).")
    public Map<String, Object> waitFor(ActionContext ctx,
            @Input(name = "condition", type = OoaType.STRING, required = false, defaultValue = "\"input_field\"",
                    enumValues = {"input_field", "unlock", "screen_change", "screen_blank", "cursor_at", "cursor_leaves", "seconds"},
                    description = "input_field: a typeable field is shown; unlock: keyboard unlocked (terminal ready); screen_change: the host sends new output; "
                            + "screen_blank: the screen is empty; cursor_at / cursor_leaves: the cursor reaches or leaves row and column; seconds: just pause.") String condition,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait (the pause length for seconds).") int timeout,
            @Input(name = "fail_on_timeout", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Stop with an error on timeout; when off, met is false instead.") boolean failOnTimeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Row (1-based) for cursor_at and cursor_leaves.") Integer row,
            @Input(name = "column", type = OoaType.NUMBER, required = false, description = "Column (1-based) for cursor_at and cursor_leaves.") Integer col) {
        Session t = s(ctx, session);
        String c = condition == null ? "input_field" : condition;
        if (timeout < 1 || timeout > 3600) throw new ActionException("TERMINAL.BAD_TIMEOUT", "The timeout must be between 1 and 3600 seconds");
        if (Set.of("screen_blank", "cursor_at", "cursor_leaves").contains(c)) {
            if (!c.equals("screen_blank") && (row == null || col == null || row < 1 || col < 1))
                throw new ActionException("TERMINAL.NO_POSITION", "Give the row and column for " + c, "Rows and columns start at 1.");
            long end = System.nanoTime() + timeout * 1_000_000_000L;
            while (true) {
                boolean met;
                if (c.equals("screen_blank")) met = String.join("", t.cmd("Ascii()")).isBlank();
                else {
                    int[] cur = cursor(t);
                    boolean at = cur[0] == row && cur[1] == col;
                    met = c.equals("cursor_at") == at;
                }
                if (met) return Map.of("met", true);
                if (System.nanoTime() > end) {
                    if (!failOnTimeout) return Map.of("met", false);
                    throw new ActionException("TERMINAL.WAIT_TIMEOUT", "The condition " + c + " was not met within " + timeout + " s", "Check the previous step reached the expected screen.", true);
                }
                HttpJson.pause(ctx, 250);
            }
        }
        String what = switch (c) {
            case "input_field" -> "InputField"; case "unlock" -> "Unlock"; case "screen_change" -> "Output"; case "seconds" -> "Seconds";
            default -> throw new ActionException("TERMINAL.BAD_CONDITION", "Unknown condition '" + condition + "'", "Use input_field, unlock, screen_change, screen_blank, cursor_at, cursor_leaves or seconds.");
        };
        try { t.cmd("Wait(" + timeout + "," + what + ")"); }
        catch (ActionException e) {
            if (failOnTimeout || !"TERMINAL.COMMAND_FAILED".equals(e.code())) throw e;
            return Map.of("met", false);
        }
        return Map.of("met", true);
    }

    /** Cursor position, 1-based {row, column}, from the engine's Query(Cursor). */
    static int[] cursor(Session t) {
        List<String> d = t.cmd("Query(Cursor)");
        String[] p = String.join(" ", d).trim().split("\\s+");
        try { return new int[]{Integer.parseInt(p[0]) + 1, Integer.parseInt(p[1]) + 1}; }
        catch (RuntimeException e) { throw new ActionException("TERMINAL.NO_CURSOR", "The engine did not report a cursor position: " + String.join(" ", d), "Check the session is connected."); }
    }

    @Action(id = "disconnect", name = "Disconnect", category = "Terminal", description = "Disconnect and close the terminal.")
    public Map<String, Object> disconnect(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        Object o = ctx.sessions().remove(session);
        if (o instanceof Session t) t.close();
        return Map.of();
    }


    // ---- Fields, cursor, screen and key mappings ----

    /** Turns a key name (Enter, PF3, PA1, ...) into the engine action. */
    static String keyAction(String key) {
        String k = key.trim().toUpperCase(Locale.ROOT);
        if (k.matches("PF([1-9]|1[0-9]|2[0-4])")) return "PF(" + k.substring(2) + ")";
        if (k.matches("PA[1-3]")) return "PA(" + k.substring(2) + ")";
        return switch (k) {
            case "ENTER" -> "Enter"; case "TAB" -> "Tab"; case "BACKTAB" -> "BackTab"; case "CLEAR" -> "Clear";
            case "RESET" -> "Reset"; case "ERASEEOF" -> "EraseEOF"; case "HOME" -> "Home";
            case "ERASEINPUT" -> "EraseInput"; case "NEWLINE" -> "Newline"; case "UP" -> "Up"; case "DOWN" -> "Down";
            case "LEFT" -> "Left"; case "RIGHT" -> "Right"; case "BACKSPACE" -> "BackSpace"; case "DELETE" -> "Delete";
            case "INSERT" -> "Insert"; case "FIELDEND" -> "FieldEnd"; case "DUP" -> "Dup"; case "FIELDMARK" -> "FieldMark";
            case "ATTN" -> "Attn"; case "SYSREQ" -> "SysReq";
            default -> throw new ActionException("TERMINAL.BAD_KEY", "Unknown key '" + key + "'", "Use Enter, Tab, Clear, PF1-PF24 or PA1-PA3, or a name set with Set key mapping.");
        };
    }

    /** Keys handled by the terminal itself; nothing is sent to the host, so there is nothing to wait for. */
    static final Set<String> LOCAL_KEYS = Set.of("Tab", "BackTab", "Home", "EraseEOF", "EraseInput", "Newline", "Up", "Down", "Left", "Right",
            "BackSpace", "Delete", "Insert", "FieldEnd", "Dup", "FieldMark");

    /** One 3270 field: position of its first character (1-based), length and attribute flags. */
    record Field(int index, int row, int column, int length, boolean isProtected, boolean numeric, boolean hidden, String text) {
        Map<String, Object> asMap(boolean reveal) {
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("index", java.math.BigDecimal.valueOf(index)); m.put("row", java.math.BigDecimal.valueOf(row)); m.put("column", java.math.BigDecimal.valueOf(column));
            m.put("length", java.math.BigDecimal.valueOf(length)); m.put("protected", isProtected); m.put("numeric", numeric); m.put("hidden", hidden);
            m.put("text", hidden && !reveal ? "" : text.strip());
            return m;
        }
    }

    /**
     * Parses ReadBuffer(Ascii) output. Each row is a list of tokens: "SF(c0=xx,...)" marks a field attribute position,
     * "SA(...)" changes character attributes without using a position, anything else is the hex code of one character.
     */
    static List<Field> parseFields(List<String> rows) {
        List<Integer> codes = new ArrayList<>();          // -1 - attr for field attribute positions, else the character
        int cols = 0;
        for (String row : rows) {
            int n = 0;
            for (String tok : row.trim().split("\\s+")) {
                if (tok.isEmpty() || tok.startsWith("SA(")) continue;
                if (tok.startsWith("SF(")) {
                    int attr = 0;
                    for (String kv : tok.substring(3, tok.length() - (tok.endsWith(")") ? 1 : 0)).split(","))
                        if (kv.toLowerCase(Locale.ROOT).startsWith("c0=")) attr = Integer.parseInt(kv.substring(3), 16);
                    codes.add(-1 - attr);
                } else {
                    int c;
                    try { c = Integer.parseInt(tok, 16); } catch (NumberFormatException e) { c = '?'; }
                    codes.add(c < 32 ? ' ' : c);
                }
                n++;
            }
            if (n > 0) cols = Math.max(cols, n);
        }
        List<Integer> fa = new ArrayList<>();
        for (int i = 0; i < codes.size(); i++) if (codes.get(i) < 0) fa.add(i);
        List<Field> out = new ArrayList<>();
        int total = codes.size();
        if (cols == 0) return out;
        for (int f = 0; f < fa.size(); f++) {
            int at = fa.get(f), next = fa.get((f + 1) % fa.size());
            int len = fa.size() == 1 ? total - 1 : Math.floorMod(next - at - 1, total);
            int attr = -1 - codes.get(at);
            StringBuilder sb = new StringBuilder();
            for (int i = 1; i <= len; i++) sb.appendCodePoint(codes.get((at + i) % total));
            int start = (at + 1) % total;
            out.add(new Field(out.size() + 1, start / cols + 1, start % cols + 1, len, (attr & 0x20) != 0, (attr & 0x10) != 0, (attr & 0x0c) == 0x0c, sb.toString()));
        }
        return out;
    }

    static int columns(List<String> rows) {
        for (String row : rows) {
            int n = 0;
            for (String tok : row.trim().split("\\s+")) if (!tok.isEmpty() && !tok.startsWith("SA(")) n++;
            if (n > 0) return n;
        }
        return 80;
    }

    /** The field holding a screen position, or the field after a label (the next unprotected one when forInput). */
    static Field locate(List<Field> fields, int cols, int totalRows, Integer row, Integer col, String label, boolean forInput) {
        if (fields.isEmpty()) throw new ActionException("TERMINAL.NO_FIELDS", "The screen has no fields", "Wait for a formatted screen before reading fields.");
        if (label != null && !label.isBlank()) {
            String want = label.strip().toLowerCase(Locale.ROOT);
            for (int i = 0; i < fields.size(); i++) {
                if (!fields.get(i).text().toLowerCase(Locale.ROOT).contains(want)) continue;
                for (int j = 1; j < fields.size(); j++) {
                    Field c = fields.get((i + j) % fields.size());
                    if (!forInput || !c.isProtected()) return c;
                }
            }
            throw new ActionException("TERMINAL.FIELD_NOT_FOUND", "No field follows the label '" + label + "'", "Check the label text exactly as shown on the screen.");
        }
        if (row == null || col == null) throw new ActionException("TERMINAL.NO_POSITION", "Give a row and column, or a label", "Rows and columns start at 1.");
        int total = cols * totalRows, pos = (row - 1) * cols + (col - 1);
        for (Field f : fields) {
            int start = (f.row() - 1) * cols + (f.column() - 1);
            int off = Math.floorMod(pos - start, total);
            if (off < f.length() || Math.floorMod(start - 1, total) == pos) return f;
        }
        throw new ActionException("TERMINAL.FIELD_NOT_FOUND", "No field at row " + row + ", column " + col, "Use Get all fields to see the field positions.");
    }

    private static List<String> buffer(Session t) { return t.cmd("ReadBuffer(Ascii)"); }

    @Action(id = "get_all_fields", name = "Get all fields", category = "Fields", idempotent = true, description = "List every field on the screen with its position, length, protection and text.")
    @Output(name = "table", type = OoaType.TABLE, description = "Columns: index, row, column, length, protected, numeric, hidden, text.")
    @Output(name = "count", type = OoaType.NUMBER, description = "Number of fields.")
    public Map<String, Object> getAllFields(ActionContext ctx,
            @Input(name = "only_input_fields", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Return only unprotected (typeable) fields.") boolean onlyInput,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        List<Object> rows = new ArrayList<>();
        for (Field f : parseFields(buffer(s(ctx, session)))) {
            if (onlyInput && f.isProtected()) continue;
            rows.add(new ArrayList<>(f.asMap(false).values()));
        }
        return Map.of("table", com.oneofficeautomation.runner.Values.table(List.of("index", "row", "column", "length", "protected", "numeric", "hidden", "text"), rows),
                "count", java.math.BigDecimal.valueOf(rows.size()));
    }

    @Action(id = "get_field", name = "Get field", category = "Fields", idempotent = true, description = "Read one field, found by a screen position inside it or by the label just before it.")
    @Output(name = "text", type = OoaType.STRING, description = "Field text (trimmed; empty for hidden fields).")
    @Output(name = "field", type = OoaType.DICTIONARY, description = "index, row, column, length, protected, numeric, hidden, text.")
    public Map<String, Object> getField(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Row of any position in the field (1-based).") Integer row,
            @Input(name = "column", type = OoaType.NUMBER, required = false, description = "Column of that position (1-based).") Integer col,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Label text shown before the field; used instead of row and column.") String label,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "index", type = OoaType.NUMBER, required = false, description = "Field number as listed by Get all fields (1-based); used instead of position or label.") Integer index) {
        List<String> buf = buffer(s(ctx, session));
        Field f = index != null ? byIndex(parseFields(buf), index) : locate(parseFields(buf), columns(buf), buf.size(), row, col, label, false);
        Map<String, Object> m = f.asMap(false);
        return Map.of("text", m.get("text"), "field", m);
    }

    /** Earlier signature, kept for callers written before the index option. */
    public Map<String, Object> getField(ActionContext ctx, Integer row, Integer col, String label, String session) { return getField(ctx, row, col, label, session, null); }

    static Field byIndex(List<Field> fields, int index) {
        if (index < 1 || index > fields.size()) throw new ActionException("TERMINAL.FIELD_NOT_FOUND", "There is no field number " + index + " (the screen has " + fields.size() + ")", "Use Get all fields to see the field numbers.");
        return fields.get(index - 1);
    }

    @Action(id = "set_field", name = "Set field", category = "Fields", timeoutSeconds = 120, description = "Replace the contents of an input field, found by position, label or field number; Credentials are typed without being logged.")
    public Map<String, Object> setField(ActionContext ctx,
            @Input(name = "text", description = "Text, or a Credential variable for passwords.") Object text,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Row of any position in the field (1-based).") Integer row,
            @Input(name = "column", type = OoaType.NUMBER, required = false, description = "Column of that position (1-based).") Integer col,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Label text shown before the field; the next input field after it is used.") String label,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "index", type = OoaType.NUMBER, required = false, description = "Field number as listed by Get all fields (1-based); used instead of position or label.") Integer index,
            @Input(name = "press_key", type = OoaType.STRING, required = false, example = "Enter", description = "Key to press after filling the field; none when empty.") String pressKey,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = D_WAIT) int timeout) {
        Session t = s(ctx, session);
        List<String> buf = buffer(t);
        Field f = index != null ? byIndex(parseFields(buf), index) : locate(parseFields(buf), columns(buf), buf.size(), row, col, label, true);
        if (f.isProtected()) throw new ActionException("TERMINAL.PROTECTED_FIELD", "The field at row " + f.row() + ", column " + f.column() + " cannot be typed into", "Pick an input field; Get all fields shows which are protected.");
        String v = text instanceof SecureString ss ? ss.reveal() : com.oneofficeautomation.runner.Values.text(text);
        if (v.length() > f.length()) throw new ActionException("TERMINAL.TOO_LONG", "The value has " + v.length() + " characters but the field holds " + f.length(), "Shorten the value.");
        t.cmd("MoveCursor(" + (f.row() - 1) + "," + (f.column() - 1) + ")");
        t.cmd("EraseEOF");
        if (!v.isEmpty()) t.cmd("String(" + quote(v) + ")");
        if (pressKey != null && !pressKey.isBlank()) press(t, pressKey, true, timeout);
        return Map.of();
    }

    /** Earlier signature, kept for callers written before the index and press_key options. */
    public Map<String, Object> setField(ActionContext ctx, Object text, Integer row, Integer col, String label, String session) {
        return setField(ctx, text, row, col, label, session, null, null, 30);
    }

    @Action(id = "search_field", name = "Find text on screen", category = "Fields", idempotent = true, description = "Find where a piece of text appears on the screen.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the text was found.")
    @Output(name = "row", type = OoaType.NUMBER, description = "Row of the first character (0 when not found).")
    @Output(name = "column", type = OoaType.NUMBER, description = "Column of the first character (0 when not found).")
    @Output(name = "field_index", type = OoaType.NUMBER, description = "Number of the field holding the match, as in Get all fields (0 when not found or the screen has no fields).")
    @Output(name = "count", type = OoaType.NUMBER, description = "How many times the text appears on the screen.")
    public Map<String, Object> searchField(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to look for.") String text,
            @Input(name = "occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Which match to return when the text appears more than once.") int occurrence,
            @Input(name = "case_sensitive", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Match upper and lower case exactly.") boolean cs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session,
            @Input(name = "whole_field", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Only match a field whose whole (trimmed) text equals the search text.") boolean wholeField) {
        Session t = s(ctx, session);
        java.math.BigDecimal zero = java.math.BigDecimal.ZERO;
        if (wholeField) {
            List<String> buf = buffer(t);
            int seen = 0, total = 0; Field hit = null;
            for (Field f : parseFields(buf)) {
                String v = f.hidden() ? "" : f.text().strip();
                if (cs ? v.equals(text) : v.equalsIgnoreCase(text)) { total++; if (++seen == Math.max(1, occurrence)) hit = f; }
            }
            if (hit == null) return Map.of("found", false, "row", zero, "column", zero, "field_index", zero, "count", java.math.BigDecimal.valueOf(total));
            int lead = hit.text().length() - hit.text().stripLeading().length();
            return Map.of("found", true, "row", java.math.BigDecimal.valueOf(hit.row()), "column", java.math.BigDecimal.valueOf(hit.column() + lead),
                    "field_index", java.math.BigDecimal.valueOf(hit.index()), "count", java.math.BigDecimal.valueOf(total));
        }
        List<String> lines = t.cmd("Ascii()");
        String want = cs ? text : text.toLowerCase(Locale.ROOT);
        int seen = 0, hr = 0, hc = 0;
        for (int r = 0; r < lines.size(); r++) {
            String line = cs ? lines.get(r) : lines.get(r).toLowerCase(Locale.ROOT);
            for (int i = line.indexOf(want); i >= 0 && !want.isEmpty(); i = line.indexOf(want, i + 1))
                if (++seen == Math.max(1, occurrence)) { hr = r + 1; hc = i + 1; }
        }
        if (hr == 0) return Map.of("found", false, "row", zero, "column", zero, "field_index", zero, "count", java.math.BigDecimal.valueOf(seen));
        int idx = 0;
        try { List<String> buf = buffer(t); idx = locate(parseFields(buf), columns(buf), buf.size(), hr, hc, null, false).index(); }
        catch (ActionException ignored) { /* unformatted screen: no field number */ }
        return Map.of("found", true, "row", java.math.BigDecimal.valueOf(hr), "column", java.math.BigDecimal.valueOf(hc), "field_index", java.math.BigDecimal.valueOf(idx), "count", java.math.BigDecimal.valueOf(seen));
    }

    /** Earlier signature, kept for callers written before the whole_field option. */
    public Map<String, Object> searchField(ActionContext ctx, String text, int occurrence, boolean cs, String session) { return searchField(ctx, text, occurrence, cs, session, false); }

    @Action(id = "set_cursor", name = "Set cursor", category = "Terminal", description = "Move the cursor to a row and column.")
    public Map<String, Object> setCursor(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, description = "Row (1-based).") int row,
            @Input(name = "column", type = OoaType.NUMBER, description = "Column (1-based).") int col,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        if (row < 1 || col < 1) throw new ActionException("TERMINAL.BAD_POSITION", "Row and column start at 1");
        s(ctx, session).cmd("MoveCursor(" + (row - 1) + "," + (col - 1) + ")");
        return Map.of();
    }

    @Action(id = "get_cursor", name = "Get cursor position", category = "Terminal", idempotent = true, description = "Read the row and column where the cursor is.")
    @Output(name = "row", type = OoaType.NUMBER, description = "Cursor row (1-based).")
    @Output(name = "column", type = OoaType.NUMBER, description = "Cursor column (1-based).")
    public Map<String, Object> getCursor(ActionContext ctx,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = D_SESSION) String session) {
        int[] c = cursor(s(ctx, session));
        return Map.of("row", java.math.BigDecimal.valueOf(c[0]), "column", java.math.BigDecimal.valueOf(c[1]));
    }

    @Action(id = "clear_screen", name = "Clear screen", category = "Terminal", timeoutSeconds = 120, description = "Erase all input fields locally, or send the Clear key to the host.")
    public Map<String, Object> clearScreen(ActionContext ctx,
            @Input(name = "send_to_host", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Send the Clear key to the host (blanks the whole screen) instead of only erasing input fields.") boolean host,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        Session t = s(ctx, session);
        if (host) { t.cmd("Clear"); t.cmd("Wait(30,InputField)"); }
        else t.cmd("EraseInput");
        return Map.of();
    }

    @Action(id = "show_terminal", name = "Show terminal", category = "Terminal", description = "Ask for a visible terminal window; the scripting engine has no window, so this only records a note in the log.")
    public Map<String, Object> showTerminal(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        s(ctx, session);
        ctx.info("The terminal runs without a window; use Get screen text to see what the host shows.");
        return Map.of();
    }

    @Action(id = "hide_terminal", name = "Hide terminal", category = "Terminal", description = "Hide the terminal window; the scripting engine is always hidden, so nothing changes.")
    public Map<String, Object> hideTerminal(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        s(ctx, session);
        ctx.info("The terminal already runs without a window.");
        return Map.of();
    }

    @Action(id = "set_key_mapping", name = "Set key mapping", category = "Terminal", description = "Give keys friendly names for this session, e.g. Submit for Enter or Exit for PF3, usable in Press key.")
    @Output(name = "mappings", type = OoaType.DICTIONARY, description = "All names set in this session.")
    public Map<String, Object> setKeyMapping(ActionContext ctx,
            @Input(name = "mappings", type = OoaType.DICTIONARY, example = "{\"Submit\": \"Enter\", \"Exit\": \"PF3\"}", description = "Name to key, e.g. {\"Submit\": \"Enter\"}; an empty key removes the name.") Map<String, Object> mappings,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        Session t = s(ctx, session);
        if (mappings != null) for (var e : mappings.entrySet()) {
            String alias = e.getKey().trim().toUpperCase(Locale.ROOT);
            String target = e.getValue() == null ? "" : com.oneofficeautomation.runner.Values.text(e.getValue()).trim();
            if (target.isEmpty()) { t.keyAliases.remove(alias); continue; }
            keyAction(target);
            t.keyAliases.put(alias, target);
        }
        return Map.of("mappings", new LinkedHashMap<String, Object>(new TreeMap<>(t.keyAliases)));
    }
}
