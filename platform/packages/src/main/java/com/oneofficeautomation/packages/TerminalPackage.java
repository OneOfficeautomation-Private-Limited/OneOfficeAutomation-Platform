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
        Session(Process p) { proc = p; in = new BufferedWriter(new OutputStreamWriter(p.getOutputStream(), StandardCharsets.UTF_8)); out = new BufferedReader(new InputStreamReader(p.getInputStream(), StandardCharsets.UTF_8)); }
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
        String k = key.trim().toUpperCase(Locale.ROOT);
        String action;
        if (k.matches("PF([1-9]|1[0-9]|2[0-4])")) action = "PF(" + k.substring(2) + ")";
        else if (k.matches("PA[1-3]")) action = "PA(" + k.substring(2) + ")";
        else action = switch (k) {
            case "ENTER" -> "Enter"; case "TAB" -> "Tab"; case "BACKTAB" -> "BackTab"; case "CLEAR" -> "Clear";
            case "RESET" -> "Reset"; case "ERASEEOF" -> "EraseEOF"; case "HOME" -> "Home";
            default -> throw new ActionException("TERMINAL.BAD_KEY", "Unknown key '" + key + "'", "Use Enter, Tab, Clear, PF1-PF24 or PA1-PA3.");
        };
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

}
