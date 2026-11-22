package com.oneofficeautomation.packages;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * G4 (AA parity, 22 Nov 2026): the Telnet side of a VT host reached without SSH. Reads the host's bytes, answers its
 * option questions (terminal type and window size yes, echo and go-ahead suppression from the host yes, anything else
 * no) and hands on only the screen data.
 */
final class TelnetIn extends FilterInputStream {
    static final int IAC = 255, DONT = 254, DO = 253, WONT = 252, WILL = 251, SB = 250, SE = 240;
    static final int ECHO = 1, SGA = 3, TTYPE = 24, NAWS = 31;

    private final OutputStream toHost;
    private final String term;
    private final int rows, cols;

    TelnetIn(InputStream in, OutputStream toHost, String term, int rows, int cols) {
        super(in);
        this.toHost = toHost; this.term = term; this.rows = rows; this.cols = cols;
    }

    private void reply(int... b) throws IOException {
        synchronized (toHost) { for (int x : b) toHost.write(x); toHost.flush(); }
    }

    @Override public int read() throws IOException {
        while (true) {
            int b = in.read();
            if (b != IAC) return b;
            int cmd = in.read();
            if (cmd < 0) return -1;
            if (cmd == IAC) return IAC;
            if (cmd == DO || cmd == DONT || cmd == WILL || cmd == WONT) {
                int opt = in.read();
                if (opt < 0) return -1;
                if (cmd == DO) {
                    if (opt == TTYPE) reply(IAC, WILL, TTYPE);
                    else if (opt == NAWS) { reply(IAC, WILL, NAWS); reply(IAC, SB, NAWS, cols >> 8, cols & 255, rows >> 8, rows & 255, IAC, SE); }
                    else reply(IAC, WONT, opt);
                } else if (cmd == WILL) reply(IAC, opt == ECHO || opt == SGA ? DO : DONT, opt);
                continue;
            }
            if (cmd == SB) {
                ByteArrayOutputStream sub = new ByteArrayOutputStream();
                int prev = -1;
                for (int x; (x = in.read()) >= 0; prev = x) {
                    if (prev == IAC && x == SE) break;
                    if (x != IAC || prev == IAC) sub.write(x);
                }
                byte[] s = sub.toByteArray();
                if (s.length >= 2 && (s[0] & 255) == TTYPE && s[1] == 1) {   // SEND: tell the terminal type
                    byte[] name = term.toUpperCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII);
                    synchronized (toHost) {
                        toHost.write(new byte[]{(byte) IAC, (byte) SB, (byte) TTYPE, 0});
                        toHost.write(name);
                        toHost.write(new byte[]{(byte) IAC, (byte) SE});
                        toHost.flush();
                    }
                }
                continue;
            }
            // other commands (NOP, GA, ...) carry no data
        }
    }

    @Override public int read(byte[] buf, int off, int len) throws IOException {
        if (len == 0) return 0;
        int b = read();
        if (b < 0) return -1;
        buf[off] = (byte) b;
        int n = 1;
        while (n < len && in.available() > 0) {
            int x = read();
            if (x < 0) break;
            buf[off + n++] = (byte) x;
        }
        return n;
    }
}
