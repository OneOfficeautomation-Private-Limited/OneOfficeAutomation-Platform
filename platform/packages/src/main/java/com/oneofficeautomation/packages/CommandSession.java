package com.oneofficeautomation.packages;

import java.io.IOException;
import java.io.InputStreamReader;
import java.io.OutputStreamWriter;
import java.io.Reader;
import java.io.Writer;
import java.nio.charset.Charset;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.TimeUnit;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A console program (cmd.exe, powershell.exe, ...) kept running for the steps of one run: Application > Open command
 * session starts it with standard input and output redirected, a background thread copies its output (standard error
 * merged in) into a buffer, and the later steps write lines to it and read what it printed. Closed with the whole
 * process tree when the run ends (the Runner closes every AutoCloseable session).
 */
final class CommandSession implements AutoCloseable {
    /** Characters kept; older output is dropped, newest kept, and the next read says so. */
    static final int MAX_BUFFER = 2_000_000;
    static final String TRIMMED_NOTE = "[... older output was dropped: a command session keeps the newest 2,000,000 characters ...]\n";

    final String program;
    final Process process;
    final Charset charset;
    private final Writer stdin;
    private final Thread reader;
    private final StringBuilder buf = new StringBuilder();
    /** Absolute position of buf[0], and of the next character a "new output" read returns. */
    private long base, readPos;
    private boolean trimmed, eof;
    private final List<String> secrets = new ArrayList<>();
    private int longestSecret;

    CommandSession(String program, Process process, Charset charset) {
        this.program = program;
        this.process = process;
        this.charset = charset;
        this.stdin = new OutputStreamWriter(process.getOutputStream(), charset);
        Reader in = new InputStreamReader(process.getInputStream(), charset);
        this.reader = new Thread(() -> {
            char[] b = new char[8192];
            try { for (int n; (n = in.read(b)) > 0; ) append(b, n); }
            catch (IOException ignored) { }
            finally { synchronized (this) { eof = true; notifyAll(); } }
        }, "ooa-command-session-io");
        reader.setDaemon(true);
        reader.start();
    }

    private synchronized void append(char[] b, int n) {
        int from = buf.length();
        buf.append(b, 0, n);
        maskFrom(Math.max(0, from - longestSecret));
        if (buf.length() > MAX_BUFFER) {
            int drop = buf.length() - MAX_BUFFER;
            buf.delete(0, drop);
            base += drop;
            trimmed = true;
        }
        notifyAll();
    }

    /** Replaces every known secret in the buffer from this index on; the buffer never keeps a secret the program echoed. */
    private void maskFrom(int from) {
        for (String s : secrets)
            for (int i = buf.indexOf(s, from); i >= 0; i = buf.indexOf(s, i + com.oneofficeautomation.sdk.SecureString.MASK.length()))
                buf.replace(i, i + s.length(), com.oneofficeautomation.sdk.SecureString.MASK);
    }

    /** Remembers a secret before it is written, so the program's echo of it is masked as it arrives. */
    synchronized void addSecret(String s) {
        if (s == null || s.isEmpty() || secrets.contains(s)) return;
        secrets.add(s);
        longestSecret = Math.max(longestSecret, s.length());
        maskFrom(0);
    }

    synchronized String mask(String text) {
        String out = text;
        for (String s : secrets) out = out.replace(s, com.oneofficeautomation.sdk.SecureString.MASK);
        return out;
    }

    boolean exited() { return !process.isAlive(); }

    /** True once the program has ended and its output is all in the buffer. */
    synchronized boolean finished() { return eof && !process.isAlive(); }

    void write(String text) throws IOException {
        synchronized (stdin) { stdin.write(text); stdin.flush(); }
    }

    /** What a read returns: the text, whether wait_for was seen, and whether the program has ended. */
    record Read(String output, boolean matched, boolean exited) {}

    /**
     * Waits for the pattern in the output not yet read (or in all of it), up to the deadline; with no pattern, until
     * the output has been quiet for {@code quietMillis}. A match moves the read position to the end of the match, so
     * text printed after it stays for the next read; without a match nothing is consumed (unless {@code consumeAnyway}).
     */
    Read read(Pattern pattern, boolean all, long deadlineNanos, long quietMillis, boolean consumeAnyway, Runnable checkCancelled) throws InterruptedException {
        long lastLen = -1, quietSince = System.nanoTime();
        while (true) {
            checkCancelled.run();
            synchronized (this) {
                long start = all ? base : Math.max(readPos, base);
                int from = (int) (start - base);
                if (pattern != null) {
                    Matcher m = pattern.matcher(buf).region(from, buf.length());
                    if (m.find()) return take(from, m.end(), all, true);
                } else {
                    long len = base + buf.length();
                    if (len != lastLen) { lastLen = len; quietSince = System.nanoTime(); }
                    else if (System.nanoTime() - quietSince >= quietMillis * 1_000_000L) return take(from, buf.length(), all, false);
                }
                boolean done = finished();
                if (done || System.nanoTime() >= deadlineNanos) {
                    if (pattern == null || consumeAnyway) return take(from, buf.length(), all, false);
                    return new Read(note(all) + buf.substring(from), false, exited());
                }
                long waitMs = Math.max(1, Math.min(100, (deadlineNanos - System.nanoTime()) / 1_000_000L));
                wait(waitMs);
            }
        }
    }

    private Read take(int from, int end, boolean all, boolean matched) {
        String text = note(all) + buf.substring(from, end);
        readPos = base + end;
        return new Read(text, matched, exited());
    }

    private String note(boolean all) {
        return (all && trimmed) || (!all && readPos < base && trimmed) ? TRIMMED_NOTE : "";
    }

    /** Waits up to this long for the program to end by itself; true when it has. */
    boolean waitForExit(long millis) throws InterruptedException {
        return process.waitFor(millis, TimeUnit.MILLISECONDS);
    }

    /** Stops the program and every process it started (child first), then closes the pipes. */
    @Override public void close() {
        if (process.isAlive()) {
            List<ProcessHandle> tree = process.descendants().toList();
            for (int i = tree.size() - 1; i >= 0; i--) tree.get(i).destroyForcibly();
            process.destroyForcibly();
            try { process.waitFor(5, TimeUnit.SECONDS); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        }
        try { stdin.close(); } catch (IOException ignored) { }
        try { reader.join(1000); } catch (InterruptedException e) { Thread.currentThread().interrupt(); }
        synchronized (this) { secrets.clear(); }
    }

    /** The console's code page on Windows (what cmd.exe writes to a pipe), read once with chcp; elsewhere the system's. */
    static Charset consoleCharset() {
        Charset c = console;
        if (c != null) return c;
        c = Charset.defaultCharset();
        if (ProcessRunner.WINDOWS) {
            try {
                Process p = new ProcessBuilder("cmd.exe", "/d", "/c", "chcp").redirectErrorStream(true).start();
                String out = new String(p.getInputStream().readAllBytes(), java.nio.charset.StandardCharsets.US_ASCII);
                p.waitFor(10, TimeUnit.SECONDS);
                Matcher m = Pattern.compile("(\\d+)\\D*$").matcher(out.strip());
                if (m.find()) {
                    String cp = m.group(1);
                    c = cp.equals("65001") ? java.nio.charset.StandardCharsets.UTF_8 : Charset.forName(cp.equals("1200") ? "UTF-16LE" : "cp" + cp);
                }
            } catch (Exception ignored) { }
        }
        console = c;
        return c;
    }
    private static volatile Charset console;

    static String baseName(String program) {
        String exe = program.strip().replace("\"", "");
        return exe.substring(Math.max(exe.lastIndexOf('/'), exe.lastIndexOf('\\')) + 1).toLowerCase(Locale.ROOT);
    }

    static boolean isCmd(String program) { String b = baseName(program); return b.equals("cmd") || b.equals("cmd.exe"); }

    static boolean isPowerShell(String program) {
        String b = baseName(program);
        return b.equals("powershell") || b.equals("powershell.exe") || b.equals("pwsh") || b.equals("pwsh.exe");
    }
}
