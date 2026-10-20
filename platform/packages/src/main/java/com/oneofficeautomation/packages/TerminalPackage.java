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

    @Action(id = "connect", name = "Connect", category = "Terminal", timeoutSeconds = 120, capabilities = "network", description = "Connect to a TN3270 host and wait for the first screen.")
    public Map<String, Object> connect(ActionContext ctx,
            @Input(name = "host", type = OoaType.STRING, description = "Mainframe host name or address.") String host,
            @Input(name = "port", type = OoaType.NUMBER, required = false, defaultValue = "23", description = "Port (992 is common for TLS).") int port,
            @Input(name = "tls", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Use a TLS-encrypted connection.") boolean tls,
            @Input(name = "model", type = OoaType.STRING, required = false, defaultValue = "\"3279-2\"", enumValues = {"3279-2", "3279-3", "3279-4", "3279-5"}, description = "Screen model (2 = 24x80).") String model,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        if (!host.matches("^[A-Za-z0-9.-]{1,253}$")) throw new ActionException("TERMINAL.BAD_HOST", "Invalid host '" + host + "'");
        Process p;
        try { p = new ProcessBuilder(exe(), "-model", model).redirectError(ProcessBuilder.Redirect.DISCARD).start(); }
        catch (IOException e) { throw new ActionException("TERMINAL.NOT_INSTALLED", "The x3270 terminal engine is not installed", "Install wc3270 (Windows) or s3270, or set OOA_S3270 to its path."); }
        Session t = new Session(p);
        try {
            t.cmd("Connect(" + (tls ? "L:" : "") + host + ":" + port + ")");
            t.cmd("Wait(30,InputField)");
        } catch (RuntimeException e) { t.close(); throw e; }
        ctx.sessions().put(session, t);
        return Map.of();
    }

    @Action(id = "send_text", name = "Type text", category = "Terminal", description = "Type text at a row and column, or at the cursor; Credentials are typed without being logged.")
    public Map<String, Object> sendText(ActionContext ctx,
            @Input(name = "text", description = "Text, or a Credential variable for passwords.") Object text,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Row (1-based); the cursor position when empty.") Integer row,
            @Input(name = "column", type = OoaType.NUMBER, required = false, description = "Column (1-based).") Integer col,
            @Input(name = "clear_field", type = OoaType.BOOLEAN, required = false, defaultValue = "false", description = "Erase the rest of the field first.") boolean clear,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        Session t = s(ctx, session);
        if (row != null && col != null) t.cmd("MoveCursor(" + (row - 1) + "," + (col - 1) + ")");
        if (clear) t.cmd("EraseEOF");
        String v = text instanceof SecureString ss ? ss.reveal() : com.oneofficeautomation.runner.Values.text(text);
        t.cmd("String(" + quote(v) + ")");
        return Map.of();
    }

    @Action(id = "send_key", name = "Press key", category = "Terminal", timeoutSeconds = 120, description = "Press Enter, Tab, Clear, a PF or PA key, and wait for the host to answer.")
    public Map<String, Object> sendKey(ActionContext ctx,
            @Input(name = "key", type = OoaType.STRING, example = "Enter", description = "Enter, Tab, BackTab, Clear, Reset, EraseEOF, Home, PF1 to PF24 or PA1 to PA3.") String key,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        Session t = s(ctx, session);
        String action = keyAction(t.keyAliases.getOrDefault(key.trim().toUpperCase(Locale.ROOT), key));
        t.cmd(action);
        if (!Set.of("Tab", "BackTab", "Home", "EraseEOF").contains(action)) t.cmd("Wait(30,InputField)");
        return Map.of();
    }

    @Action(id = "get_screen", name = "Get screen text", category = "Terminal", idempotent = true, description = "Read the whole screen as text, one line per row.")
    @Output(name = "text", type = OoaType.STRING, description = "Screen text.")
    @Output(name = "lines", type = OoaType.LIST, description = "One item per row.")
    public Map<String, Object> getScreen(ActionContext ctx, @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        List<String> rows = s(ctx, session).cmd("Ascii()");
        return Map.of("text", String.join("\n", rows), "lines", new ArrayList<Object>(rows));
    }

    @Action(id = "get_text", name = "Get text at position", category = "Terminal", idempotent = true, description = "Read text from a row and column for a given length.")
    @Output(name = "text", type = OoaType.STRING, description = "Text (trimmed).")
    public Map<String, Object> getText(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, description = "Row (1-based).") int row,
            @Input(name = "column", type = OoaType.NUMBER, description = "Column (1-based).") int col,
            @Input(name = "length", type = OoaType.NUMBER, description = "Number of characters.") int len,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        List<String> d = s(ctx, session).cmd("Ascii(" + (row - 1) + "," + (col - 1) + "," + len + ")");
        return Map.of("text", String.join("", d).strip());
    }

    @Action(id = "wait_for_text", name = "Wait for text", category = "Terminal", timeoutSeconds = 600, description = "Wait until text appears anywhere on the screen.")
    public Map<String, Object> waitForText(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to wait for.") String text,
            @Input(name = "timeout_seconds", type = OoaType.NUMBER, required = false, defaultValue = "30", description = "Maximum wait.") double timeout,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        Session t = s(ctx, session);
        long end = System.nanoTime() + (long) (timeout * 1e9);
        while (!String.join("\n", t.cmd("Ascii()")).contains(text)) {
            if (System.nanoTime() > end) throw new ActionException("TERMINAL.TEXT_NOT_FOUND", "'" + text + "' did not appear within " + timeout + " s", "Check the previous step reached the expected screen.", true);
            ctx.checkCancelled();
            try { Thread.sleep(500); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new OoaException("RUN.CANCELLED", "Interrupted"); }
        }
        return Map.of();
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
            default -> throw new ActionException("TERMINAL.BAD_KEY", "Unknown key '" + key + "'", "Use Enter, Tab, Clear, PF1-PF24 or PA1-PA3, or a name set with Set key mapping.");
        };
    }

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
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        List<String> buf = buffer(s(ctx, session));
        Field f = locate(parseFields(buf), columns(buf), buf.size(), row, col, label, false);
        Map<String, Object> m = f.asMap(false);
        return Map.of("text", m.get("text"), "field", m);
    }

    @Action(id = "set_field", name = "Set field", category = "Fields", description = "Replace the contents of an input field, found by position or by its label; Credentials are typed without being logged.")
    public Map<String, Object> setField(ActionContext ctx,
            @Input(name = "text", description = "Text, or a Credential variable for passwords.") Object text,
            @Input(name = "row", type = OoaType.NUMBER, required = false, description = "Row of any position in the field (1-based).") Integer row,
            @Input(name = "column", type = OoaType.NUMBER, required = false, description = "Column of that position (1-based).") Integer col,
            @Input(name = "label", type = OoaType.STRING, required = false, description = "Label text shown before the field; the next input field after it is used.") String label,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        Session t = s(ctx, session);
        List<String> buf = buffer(t);
        Field f = locate(parseFields(buf), columns(buf), buf.size(), row, col, label, true);
        if (f.isProtected()) throw new ActionException("TERMINAL.PROTECTED_FIELD", "The field at row " + f.row() + ", column " + f.column() + " cannot be typed into", "Pick an input field; Get all fields shows which are protected.");
        String v = text instanceof SecureString ss ? ss.reveal() : com.oneofficeautomation.runner.Values.text(text);
        if (v.length() > f.length()) throw new ActionException("TERMINAL.TOO_LONG", "The value has " + v.length() + " characters but the field holds " + f.length(), "Shorten the value.");
        t.cmd("MoveCursor(" + (f.row() - 1) + "," + (f.column() - 1) + ")");
        t.cmd("EraseEOF");
        if (!v.isEmpty()) t.cmd("String(" + quote(v) + ")");
        return Map.of();
    }

    @Action(id = "search_field", name = "Find text on screen", category = "Fields", idempotent = true, description = "Find where a piece of text appears on the screen.")
    @Output(name = "found", type = OoaType.BOOLEAN, description = "True when the text was found.")
    @Output(name = "row", type = OoaType.NUMBER, description = "Row of the first character (0 when not found).")
    @Output(name = "column", type = OoaType.NUMBER, description = "Column of the first character (0 when not found).")
    public Map<String, Object> searchField(ActionContext ctx,
            @Input(name = "text", type = OoaType.STRING, description = "Text to look for.") String text,
            @Input(name = "occurrence", type = OoaType.NUMBER, required = false, defaultValue = "1", description = "Which match to return when the text appears more than once.") int occurrence,
            @Input(name = "case_sensitive", type = OoaType.BOOLEAN, required = false, defaultValue = "true", description = "Match upper and lower case exactly.") boolean cs,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        List<String> lines = s(ctx, session).cmd("Ascii()");
        String want = cs ? text : text.toLowerCase(Locale.ROOT);
        int seen = 0;
        for (int r = 0; r < lines.size(); r++) {
            String line = cs ? lines.get(r) : lines.get(r).toLowerCase(Locale.ROOT);
            for (int i = line.indexOf(want); i >= 0 && !want.isEmpty(); i = line.indexOf(want, i + 1))
                if (++seen == Math.max(1, occurrence)) return Map.of("found", true, "row", java.math.BigDecimal.valueOf(r + 1), "column", java.math.BigDecimal.valueOf(i + 1));
        }
        return Map.of("found", false, "row", java.math.BigDecimal.ZERO, "column", java.math.BigDecimal.ZERO);
    }

    @Action(id = "set_cursor", name = "Set cursor", category = "Terminal", description = "Move the cursor to a row and column.")
    public Map<String, Object> setCursor(ActionContext ctx,
            @Input(name = "row", type = OoaType.NUMBER, description = "Row (1-based).") int row,
            @Input(name = "column", type = OoaType.NUMBER, description = "Column (1-based).") int col,
            @Input(name = "session", type = OoaType.STRING, required = false, defaultValue = "\"terminal\"", description = "Session name.") String session) {
        if (row < 1 || col < 1) throw new ActionException("TERMINAL.BAD_POSITION", "Row and column start at 1");
        s(ctx, session).cmd("MoveCursor(" + (row - 1) + "," + (col - 1) + ")");
        return Map.of();
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
