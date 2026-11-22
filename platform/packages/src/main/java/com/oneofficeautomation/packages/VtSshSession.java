package com.oneofficeautomation.packages;

import com.jcraft.jsch.ChannelShell;
import com.jcraft.jsch.JSch;
import com.jcraft.jsch.JSchException;
import com.oneofficeautomation.sdk.ActionException;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A VT100/ANSI host reached over SSH. It answers the same engine commands as the x3270 process
 * (Ascii, MoveCursor, String, Enter, PF(n), Wait...), so every Terminal action works on it unchanged.
 * VT hosts have no keyboard lock: "wait for the host" means wait until it stops sending output.
 * Moving the cursor sends arrow keys, which only suits full-screen programs, not a shell prompt.
 */
final class VtSshSession extends TerminalPackage.Session {
    /** Output must pause this long before the host counts as done answering. */
    static final long QUIET_MS = 250;
    /** When the host sends nothing back after a key, stop waiting after this long. */
    static final long NO_ANSWER_MS = 1000;

    final VtScreen screen;
    private final com.jcraft.jsch.Session ssh;
    private final ChannelShell shell;
    private final OutputStream toHost;
    /** G4: a host reached over Telnet instead of SSH (then ssh and shell are null). */
    private final java.net.Socket telnet;
    private final java.nio.charset.Charset charset;
    private final Object signal = new Object();
    private volatile long lastOutput = System.nanoTime(), lastSent = System.nanoTime(), outputs, sentMark;
    private volatile boolean ended;
    /** Where MoveCursor put the cursor, until the host has caught up; null to use the screen cursor. */
    private int[] intended;

    private VtSshSession(com.jcraft.jsch.Session ssh, ChannelShell shell, InputStream fromHost, OutputStream toHost, VtScreen screen) {
        this(ssh, shell, null, StandardCharsets.UTF_8, fromHost, toHost, screen);
    }

    private VtSshSession(com.jcraft.jsch.Session ssh, ChannelShell shell, java.net.Socket telnet, java.nio.charset.Charset charset, InputStream fromHost, OutputStream toHost, VtScreen screen) {
        this.ssh = ssh; this.shell = shell; this.telnet = telnet; this.charset = charset; this.toHost = toHost; this.screen = screen;
        Thread reader = new Thread(() -> read(fromHost), "ooa-terminal-vt");
        reader.setDaemon(true);
        reader.start();
    }

    /** Signs in, opens a shell with a pseudo-terminal of the given size, and starts reading the screen. */
    /** G4: how new screens are set up (auto-wrap, the device-attributes answer) before the host sends anything. */
    static VtScreen screen(int rows, int cols, boolean autoWrap, String deviceAttributes) {
        VtScreen sc = new VtScreen(rows, cols);
        sc.autoWrap = autoWrap;
        if (deviceAttributes != null) sc.deviceAttributes = deviceAttributes;
        return sc;
    }

    static VtSshSession open(String host, int port, String user, String password, Path keyFile, String keyPassphrase, String fingerprint,
                             String term, int rows, int cols, int timeoutMs, Consumer<String> warn) {
        return open(host, port, user, password, keyFile, keyPassphrase, fingerprint, term, rows, cols, timeoutMs, warn, StandardCharsets.UTF_8, true, null);
    }

    static VtSshSession open(String host, int port, String user, String password, Path keyFile, String keyPassphrase, String fingerprint,
                             String term, int rows, int cols, int timeoutMs, Consumer<String> warn, java.nio.charset.Charset charset, boolean autoWrap, String deviceAttributes) {
        com.jcraft.jsch.Session s = null;
        try {
            JSch j = new JSch();
            if (keyFile != null) {
                if (!Files.isRegularFile(keyFile)) throw new ActionException("TERMINAL.KEY_FILE_NOT_FOUND", "Key file not found: " + keyFile, "Check the key_file path.");
                try {
                    if (keyPassphrase != null) j.addIdentity(keyFile.toString(), keyPassphrase); else j.addIdentity(keyFile.toString());
                } catch (JSchException e) {
                    throw new ActionException("TERMINAL.BAD_KEY_FILE", "The key file could not be read: " + e.getMessage(), "Use an OpenSSH or PEM private key, and give key_passphrase when the key is protected.");
                }
            }
            s = j.getSession(user, host, port);
            if (password != null) s.setPassword(password);
            s.setConfig("StrictHostKeyChecking", "no");
            s.setTimeout(timeoutMs);
            s.connect(timeoutMs);
            String actual = s.getHostKey().getFingerPrint(j);
            if (fingerprint != null && !fingerprint.isBlank()) {
                if (!fingerprint.trim().equalsIgnoreCase(actual)) throw new ActionException("TERMINAL.HOST_KEY_MISMATCH",
                        "Host fingerprint " + actual + " does not match the expected one", "This may be a different machine; confirm the fingerprint with the host's owner.");
            } else warn.accept("SSH host key not pinned (host fingerprint " + actual + "); set host_key_fingerprint to verify the host.");
            s.setTimeout(0);
            ChannelShell ch = (ChannelShell) s.openChannel("shell");
            ch.setPtyType(term, cols, rows, 0, 0);
            InputStream in = ch.getInputStream();
            OutputStream out = ch.getOutputStream();
            ch.connect(timeoutMs);
            return new VtSshSession(s, ch, null, charset, in, out, screen(rows, cols, autoWrap, deviceAttributes));
        } catch (JSchException e) {
            if (s != null) s.disconnect();
            String m = String.valueOf(e.getMessage());
            if (m.contains("Auth")) throw new ActionException("TERMINAL.AUTH_FAILED", "Sign-in to " + host + " failed", "Check the user, password or key file.");
            throw new ActionException("TERMINAL.CONNECT_FAILED", "Could not connect to " + host + ":" + port + ": " + m, "Check the host, port (22 for SSH) and network.", true);
        } catch (IOException e) {
            if (s != null) s.disconnect();
            throw new ActionException("TERMINAL.CONNECT_FAILED", "Could not open a terminal on " + host + ": " + e.getMessage(), "Check that the account may open an interactive shell.", true);
        } catch (RuntimeException e) {
            if (s != null) s.disconnect();
            throw e;
        }
    }

    /**
     * G4: a VT host over Telnet (no SSH): connects, answers the Telnet option questions with the terminal type and screen
     * size, and signs in when a user is given (the user at the login prompt, then the password at its prompt).
     */
    static VtSshSession openTelnet(String host, int port, String user, String password, String term, int rows, int cols, int timeoutMs,
                                   java.nio.charset.Charset charset, Consumer<String> warn, boolean autoWrap, String deviceAttributes) {
        java.net.Socket so = new java.net.Socket();
        try {
            so.connect(new java.net.InetSocketAddress(host, port), timeoutMs);
            OutputStream out = so.getOutputStream();
            VtSshSession s = new VtSshSession(null, null, so, charset, new TelnetIn(so.getInputStream(), out, term, rows, cols), out, screen(rows, cols, autoWrap, deviceAttributes));
            warn.accept("Telnet sends everything unencrypted, sign-in included; use SSH where the host offers it");
            if (user != null && !user.isBlank()) {
                s.waitForScreen("(?is).*(login|user ?name|user ?id)\\s*:?\\s*$", timeoutMs);
                s.send(user + "\r");
                if (password != null) { s.waitForScreen("(?is).*password\\s*:?\\s*$", timeoutMs); s.send(password + "\r"); }
            }
            return s;
        } catch (IOException e) {
            try { so.close(); } catch (IOException ignored) { }
            throw new ActionException("TERMINAL.CONNECT_FAILED", "Could not connect to " + host + ":" + port + " over Telnet: " + e.getMessage(), "Check the host, port (23 for Telnet) and network.", true);
        }
    }

    /** Waits until the screen text, up to the cursor, matches (the sign-in prompts). */
    private void waitForScreen(String regex, int timeoutMs) {
        long end = System.currentTimeMillis() + timeoutMs;
        java.util.regex.Pattern p = java.util.regex.Pattern.compile(regex);
        while (System.currentTimeMillis() < end) {
            int[] c = screen.cursor();
            List<String> l = screen.lines();
            StringBuilder upTo = new StringBuilder();
            for (int r = 0; r < c[0] && r < l.size(); r++) upTo.append(l.get(r)).append('\n');
            if (c[0] < l.size()) upTo.append(l.get(c[0]), 0, Math.min(c[1], l.get(c[0]).length()));
            if (p.matcher(upTo.toString().stripTrailing()).matches()) return;
            if (ended) break;
            try { Thread.sleep(100); } catch (InterruptedException e) { Thread.currentThread().interrupt(); break; }
        }
        throw new ActionException("TERMINAL.NO_PROMPT", "The host did not ask for the " + (regex.contains("password") ? "password" : "user name") + " in time",
                "Leave user empty and sign in with Type text steps, or check the host.");
    }

    private void read(InputStream fromHost) {
        char[] buf = new char[8192];
        try (Reader r = new InputStreamReader(fromHost, charset)) {
            for (int n; (n = r.read(buf)) >= 0; ) {
                screen.feed(new String(buf, 0, n));
                String reply = screen.takeReplies();
                if (!reply.isEmpty()) synchronized (toHost) { toHost.write(reply.getBytes(StandardCharsets.US_ASCII)); toHost.flush(); }
                synchronized (signal) { lastOutput = System.nanoTime(); outputs++; signal.notifyAll(); }
            }
        } catch (IOException ignored) {
            // the connection closed; the last screen stays readable
        } finally {
            synchronized (signal) { ended = true; signal.notifyAll(); }
        }
    }

    private void send(String s) {
        if (ended || (shell != null && !shell.isConnected())) throw new ActionException("TERMINAL.ENDED", "The host closed the connection", "Connect again; the host may have signed the session out.");
        try {
            synchronized (toHost) { toHost.write(s.getBytes(charset)); toHost.flush(); }
        } catch (IOException e) { throw new ActionException("TERMINAL.IO_ERROR", e.getMessage(), "Check that the file or folder exists, is not open in another program, and that the runner's account may read and write it.", true); }
        synchronized (signal) { lastSent = System.nanoTime(); sentMark = outputs; }
    }

    private static final Pattern ASCII = Pattern.compile("Ascii\\((\\d+),(\\d+),(\\d+)\\)");
    private static final Pattern MOVE = Pattern.compile("MoveCursor\\((\\d+),(\\d+)\\)");
    private static final Pattern WAIT = Pattern.compile("Wait\\((\\d+),(\\w+)\\)");
    private static final Pattern PF = Pattern.compile("PF\\((\\d+)\\)");
    private static final Pattern CTRL = Pattern.compile("Ctrl\\(([A-Z@\\[\\\\\\]^_])\\)");
    private static final Pattern SEQ = Pattern.compile("Seq\\(([0-9a-f]+)\\)");
    /** Escape sequences for PF5 to PF20 (the VT220 function keys F5 to F20). */
    private static final String[] FKEYS = {"15", "17", "18", "19", "20", "21", "23", "24", "25", "26", "28", "29", "31", "32", "33", "34"};

    @Override synchronized List<String> cmd(String action) {
        Matcher m;
        if (action.equals("Ascii()")) return screen.lines();
        if (action.equals("History()")) return screen.withHistory();
        if ((m = ASCII.matcher(action)).matches()) return List.of(screen.text(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        if (action.equals("Query(Cursor)")) { int[] c = screen.cursor(); return List.of(c[0] + " " + c[1]); }
        if ((m = MOVE.matcher(action)).matches()) { moveTo(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2))); return List.of(); }
        if ((m = WAIT.matcher(action)).matches()) { waitFor(Integer.parseInt(m.group(1)), m.group(2)); return List.of(); }
        if (action.startsWith("String(\"") && action.endsWith("\")")) {
            send(unquote(action.substring(8, action.length() - 2)));
            intended = null;
            return List.of();
        }
        if (action.equals("EraseEOF")) {
            int[] at = position();
            erase(screen.text(at[0], at[1], screen.cols - at[1]).stripTrailing().length());
            return List.of();
        }
        if (action.equals("Quit")) { disconnect(); return List.of(); }
        if (action.equals("Reset")) return List.of();
        String keys = keys(action);
        if (keys == null) throw new ActionException("TERMINAL.NOT_ON_VT", action + " is a 3270 command; VT hosts have no such key",
                "Use keys a VT host understands: Enter, Tab, BackTab, arrows, Home, End, PageUp, PageDown, BackSpace, Delete, Insert, Escape, Ctrl+C, PF1-PF20.");
        send(keys);
        intended = null;
        return List.of();
    }

    static String keys(String action) {
        Matcher m;
        if ((m = SEQ.matcher(action)).matches()) return new String(java.util.HexFormat.of().parseHex(m.group(1)), StandardCharsets.UTF_8);
        if ((m = PF.matcher(action)).matches()) {
            int n = Integer.parseInt(m.group(1));
            if (n >= 1 && n <= 4) return "\033O" + (char) ('P' + n - 1);
            return n <= 20 ? "\033[" + FKEYS[n - 5] + "~" : null;
        }
        if ((m = CTRL.matcher(action)).matches()) return String.valueOf((char) (m.group(1).charAt(0) - '@'));
        return switch (action) {
            case "Enter" -> "\r"; case "Newline" -> "\n"; case "Tab" -> "\t"; case "BackTab" -> "\033[Z";
            case "Up" -> "\033[A"; case "Down" -> "\033[B"; case "Right" -> "\033[C"; case "Left" -> "\033[D";
            case "Home" -> "\033[H"; case "End", "FieldEnd" -> "\033[F"; case "PageUp" -> "\033[5~"; case "PageDown" -> "\033[6~";
            case "BackSpace" -> "\177"; case "Delete" -> "\033[3~"; case "Insert" -> "\033[2~"; case "Escape" -> "\033";
            default -> null;
        };
    }

    private int[] position() { return intended != null ? intended : screen.cursor(); }

    /** Moves the host's cursor with arrow keys, from where it is (or was last sent) to row r, column c (0-based). */
    private void moveTo(int r, int c) {
        int[] from = position();
        StringBuilder b = new StringBuilder();
        b.append((r < from[0] ? "\033[A" : "\033[B").repeat(Math.abs(r - from[0])));
        b.append((c < from[1] ? "\033[D" : "\033[C").repeat(Math.abs(c - from[1])));
        if (b.length() > 0) send(b.toString());
        intended = new int[]{r, c};
    }

    /** Deletes n characters at the cursor with the Delete key (nothing is sent when n is 0, so an empty prompt is never closed). */
    void erase(int n) { if (n > 0) send("\033[3~".repeat(n)); }

    private static String unquote(String q) {
        StringBuilder b = new StringBuilder();
        for (int i = 0; i < q.length(); i++) {
            char c = q.charAt(i);
            if (c == '\\' && i + 1 < q.length()) c = q.charAt(++i);
            b.append(c);
        }
        return b.toString();
    }

    private void waitFor(int seconds, String what) {
        long end = System.nanoTime() + seconds * 1_000_000_000L;
        switch (what) {
            case "Seconds" -> { sleepUntil(end); return; }
            case "Output" -> {
                long seen = outputs;
                synchronized (signal) {
                    while (outputs == seen && !ended) if (!pause(end)) throw timedOut(seconds, "sent nothing new");
                }
            }
            default -> { }
        }
        // InputField, Unlock, 3270Mode and after Output: wait until the host has answered (or plainly will not) and gone quiet.
        synchronized (signal) {
            while (!ended) {
                long now = System.nanoTime();
                boolean answered = outputs > sentMark || now - lastSent > NO_ANSWER_MS * 1_000_000;
                if (answered && now - lastOutput >= QUIET_MS * 1_000_000) return;
                if (!pause(end)) throw timedOut(seconds, "kept sending output");
            }
        }
    }

    /** Waits on the signal for up to 50 ms; false once the deadline has passed. */
    private boolean pause(long end) {
        if (System.nanoTime() > end) return false;
        try { signal.wait(50); } catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("TERMINAL.INTERRUPTED", "Stopped while waiting for the host", "The run was stopped before this step finished. Run the agent again when it should go on."); }
        return true;
    }

    private void sleepUntil(long end) {
        synchronized (signal) { while (pause(end)) { /* just wait */ } }
    }

    private static ActionException timedOut(int seconds, String what) {
        return new ActionException("TERMINAL.COMMAND_FAILED", "Wait timed out: the host " + what + " for " + seconds + " s", "Wait for specific text instead, or allow more time.");
    }

    private void disconnect() {
        if (shell != null) shell.disconnect();
        if (ssh != null) ssh.disconnect();
        if (telnet != null) try { telnet.close(); } catch (IOException ignored) { }
    }

    @Override public void close() { disconnect(); }

    /** The terminal names a VT host may be told it is talking to. */
    static String termName(String emulation) {
        String e = emulation == null || emulation.isBlank() ? "vt100" : emulation.trim().toLowerCase(Locale.ROOT);
        if (!List.of("vt100", "vt220", "xterm", "ansi", "linux", "scoansi").contains(e))
            throw new ActionException("TERMINAL.BAD_EMULATION", "Unknown emulation '" + emulation + "'", "Use vt100, vt220, xterm or ansi.");
        return e;
    }
}
