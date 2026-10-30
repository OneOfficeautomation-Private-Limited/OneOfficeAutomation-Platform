package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionException;

import javax.net.ssl.SSLException;
import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.net.UnknownHostException;
import java.nio.ByteBuffer;
import java.nio.charset.Charset;
import java.nio.charset.CodingErrorAction;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * An IBM i (AS/400) host over TN5250 (RFC 1205, RFC 2877), written for this toolkit. Like {@link VtSshSession} it
 * answers the x3270 engine commands (Ascii, MoveCursor, String, Enter, PF(n), Wait...), so every Terminal action
 * works on it unchanged. The host sends whole screens with a table of input fields; the keyboard unlocks when the
 * host asks to read, and pressing Enter or a function key sends the cursor, the key and the changed fields back.
 */
final class Tn5250Session extends TerminalPackage.Session {
    // Telnet bytes and the options a 5250 client takes part in.
    private static final int IAC = 255, DONT = 254, DO = 253, WONT = 252, WILL = 251, SB = 250, SE = 240, EOR = 239;
    private static final int BINARY = 0, TTYPE = 24, OPT_EOR = 25, NEW_ENVIRON = 39;
    /** 5250 commands start with this byte. */
    private static final int ESC = 0x04;
    /** Attention keys: what each key sends to the host. */
    private static final int AID_ENTER = 0xF1, AID_HELP = 0xF3, AID_ROLL_DOWN = 0xF4, AID_ROLL_UP = 0xF5, AID_PRINT = 0xF6, AID_CLEAR = 0xBD;

    /** One entry of the host's field table: where the field starts (0-based screen position), its length and flags. */
    private static final class Field {
        final int start, length, ffw1, ffw2, attr;
        boolean mdt;
        Field(int start, int length, int ffw1, int ffw2, int attr) {
            this.start = start; this.length = length; this.ffw1 = ffw1; this.ffw2 = ffw2; this.attr = attr; mdt = (ffw1 & 0x08) != 0;
        }
        /** Output-only fields (no format word) and bypass fields take no typing. */
        boolean input() { return ffw1 != 0 && (ffw1 & 0x20) == 0; }
        int shift() { return ffw1 & 0x07; }
        boolean numeric() { return shift() == 3 || shift() == 5 || shift() == 7; }
        boolean hidden() { return (attr & 0x07) == 0x07; }
        boolean monocase() { return (ffw2 & 0x20) != 0; }
        Field copy() { Field f = new Field(start, length, ffw1, ffw2, attr); f.mdt = mdt; return f; }
    }

    /** A saved screen (Save Screen / Restore Screen). */
    private record Saved(int rows, int cols, byte[] buf, List<Field> fields, int cursor, int errorRow) { }

    private final Socket socket;
    private final InputStream fromHost;
    private final OutputStream toHost;
    private final String termType, deviceName;
    private final boolean wide;
    private final char[] toChar = new char[256];
    private final short[] fromChar = new short[65536];

    /** Guards the screen state below; waits release it. */
    private final Object lock = new Object();
    private int rows, cols, addr, cursor, home = -1, errorRow, keyMask, pendingRead, nextSave;
    /** One byte per screen position: an EBCDIC character, a display attribute (0x20-0x3F) or 0 (null). */
    private byte[] buf;
    private final List<Field> fields = new ArrayList<>();
    private boolean locked = true, inputError;
    private byte[] errorLine;
    private long records;
    private volatile boolean ended;
    private volatile String lastProblem;
    private final Map<Integer, Saved> saved = new HashMap<>();

    private Tn5250Session(Socket socket, String termType, String deviceName, Charset cs) throws IOException {
        this.socket = socket; this.termType = termType; this.deviceName = deviceName;
        this.fromHost = new BufferedInputStream(socket.getInputStream());
        this.toHost = new BufferedOutputStream(socket.getOutputStream());
        wide = termType.equals("IBM-3477-FC");
        Arrays.fill(fromChar, (short) -1);
        var dec = cs.newDecoder().onMalformedInput(CodingErrorAction.REPLACE).onUnmappableCharacter(CodingErrorAction.REPLACE).replaceWith(" ");
        for (int b = 0; b < 256; b++) {
            char c;
            try { c = dec.decode(ByteBuffer.wrap(new byte[]{(byte) b})).charAt(0); } catch (IOException e) { c = ' '; }
            toChar[b] = b < 0x40 ? ' ' : c;
            if (b >= 0x40 && fromChar[c] < 0) fromChar[c] = (short) b;
        }
        clearUnit(24, 80);
        Thread reader = new Thread(this::read, "ooa-terminal-5250");
        reader.setDaemon(true);
        reader.start();
    }

    /** Opens the connection (TLS when asked, with the host name checked) and starts reading. */
    static Tn5250Session open(String host, int port, boolean tls, String termType, String deviceName, Charset cs, int timeoutMs) {
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            if (tls) {
                SSLSocket ss = (SSLSocket) ((SSLSocketFactory) SSLSocketFactory.getDefault()).createSocket(s, host, port, true);
                SSLParameters p = ss.getSSLParameters();
                p.setEndpointIdentificationAlgorithm("HTTPS");
                ss.setSSLParameters(p);
                ss.setSoTimeout(timeoutMs);
                ss.startHandshake();
                ss.setSoTimeout(0);
                return new Tn5250Session(ss, termType, deviceName, cs);
            }
            return new Tn5250Session(s, termType, deviceName, cs);
        } catch (UnknownHostException e) {
            close(s);
            throw new ActionException("TERMINAL.CONNECT_FAILED", "Unknown host " + host, "Check the host name.");
        } catch (SSLException e) {
            close(s);
            throw new ActionException("TERMINAL.TLS_FAILED", "The TLS connection to " + host + ":" + port + " failed: " + e.getMessage(),
                    "Check the port (992 is usual for TLS) and that the host's certificate is trusted by this machine.");
        } catch (IOException e) {
            close(s);
            throw new ActionException("TERMINAL.CONNECT_FAILED", "Could not connect to " + host + ":" + port + ": " + e.getMessage(),
                    "Check the host, port (23, or 992 with TLS) and network.", true);
        }
    }

    private static void close(Socket s) { try { s.close(); } catch (IOException ignored) { } }

    /** The terminal type told to the host: 24 x 80 (3179-2) or 27 x 132 (3477-FC). 3270 model names count as 24 x 80, except model 5. */
    static String termType(String model) {
        String m = model == null ? "" : model.trim().toUpperCase(Locale.ROOT);
        if (m.isEmpty() || m.equals("3179-2") || m.matches("327[89]-[234]")) return "IBM-3179-2";
        if (m.equals("3477-FC") || m.matches("327[89]-5")) return "IBM-3477-FC";
        throw new ActionException("TERMINAL.BAD_MODEL", "Model '" + model + "' is not a 5250 display", "Use 3179-2 (24 x 80) or 3477-FC (27 x 132).");
    }

    /** The host code page as a single-byte character set; cp037 (US and Canada) when empty. */
    static Charset charset(String codePage) {
        String c = codePage == null || codePage.isBlank() ? "cp037" : codePage.trim();
        String digits = c.replaceAll("\\D", "");
        for (String name : List.of(c, "IBM" + digits, "Cp" + digits, "IBM0" + digits)) {
            try {
                Charset cs = Charset.forName(name);
                if (cs.newEncoder().maxBytesPerChar() == 1) return cs;
            } catch (RuntimeException ignored) {
                // try the next spelling
            }
        }
        throw new ActionException("TERMINAL.BAD_CODE_PAGE", "Code page '" + codePage + "' is not available", "Use a single-byte EBCDIC code page such as cp037, cp273, cp277, cp280, cp285, cp297 or cp500.");
    }

    // ---- Reading from the host ----

    private void read() {
        ByteArrayOutputStream record = new ByteArrayOutputStream();
        try {
            for (int b; (b = fromHost.read()) >= 0; ) {
                if (b != IAC) { record.write(b); continue; }
                int c = fromHost.read();
                if (c < 0) break;
                switch (c) {
                    case IAC -> record.write(IAC);
                    case EOR -> { record(record.toByteArray()); record.reset(); }
                    case DO, DONT, WILL, WONT -> { int opt = fromHost.read(); if (opt >= 0) negotiate(c, opt); }
                    case SB -> subnegotiation();
                    default -> { }
                }
            }
        } catch (IOException ignored) {
            // the connection closed; the last screen stays readable
        } finally {
            ended = true;
            synchronized (lock) { lock.notifyAll(); }
        }
    }

    private void negotiate(int verb, int opt) throws IOException {
        if (verb == DO) {
            boolean yes = opt == BINARY || opt == OPT_EOR || opt == TTYPE || (opt == NEW_ENVIRON && deviceName != null);
            sendRaw(IAC, yes ? WILL : WONT, opt);
        } else if (verb == WILL) {
            sendRaw(IAC, opt == BINARY || opt == OPT_EOR ? DO : DONT, opt);
        }
    }

    private void subnegotiation() throws IOException {
        ByteArrayOutputStream sb = new ByteArrayOutputStream();
        for (int b; (b = fromHost.read()) >= 0; ) {
            if (b == IAC) {
                int c = fromHost.read();
                if (c == SE || c < 0) break;
                sb.write(c);
            } else sb.write(b);
        }
        byte[] d = sb.toByteArray();
        if (d.length < 2 || d[1] != 1) return;   // only SEND requests need an answer
        ByteArrayOutputStream r = new ByteArrayOutputStream();
        r.write(IAC); r.write(SB);
        if (d[0] == TTYPE) {
            r.write(TTYPE); r.write(0);
            r.writeBytes(termType.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
        } else if (d[0] == NEW_ENVIRON) {
            r.write(NEW_ENVIRON); r.write(0);
            if (deviceName != null) {
                r.write(3); r.writeBytes("DEVNAME".getBytes(java.nio.charset.StandardCharsets.US_ASCII));
                r.write(1); r.writeBytes(deviceName.getBytes(java.nio.charset.StandardCharsets.US_ASCII));
            }
        } else return;
        r.write(IAC); r.write(SE);
        synchronized (toHost) { toHost.write(r.toByteArray()); toHost.flush(); }
    }

    private void sendRaw(int... bytes) throws IOException {
        synchronized (toHost) { for (int b : bytes) toHost.write(b); toHost.flush(); }
    }

    /** Reads a 5250 record: the header, then the commands in it. */
    void record(byte[] r) {
        synchronized (lock) {
            try {
                if (r.length >= 10 && (r[2] & 0xff) == 0x12 && (r[3] & 0xff) == 0xA0) {
                    int opcode = r[9] & 0xff;
                    Data d = new Data(r, Math.min(r.length, 6 + (r[6] & 0xff)));
                    if (opcode == 0x06) readImmediate();
                    else if (opcode == 0x08) sendScreen();
                    commands(d);
                }
            } catch (RuntimeException e) {
                lastProblem = e.getMessage();
            } finally {
                records++;
                lock.notifyAll();
            }
        }
    }

    /** Bytes of one record, read in order. */
    private static final class Data {
        final byte[] b; int p;
        Data(byte[] b, int p) { this.b = b; this.p = p; }
        boolean more() { return p < b.length; }
        int peek() { return p < b.length ? b[p] & 0xff : -1; }
        int next() {
            if (p >= b.length) throw new IllegalStateException("The host sent a short 5250 record");
            return b[p++] & 0xff;
        }
    }

    private void commands(Data d) {
        while (d.more()) {
            if (d.next() != ESC || !d.more()) continue;
            int c = d.next();
            switch (c) {
                case 0x40 -> clearUnit(24, 80);
                case 0x20 -> { int par = d.next(); if (par == 0x00 && wide) clearUnit(27, 132); else clearUnit(24, 80); }
                case 0x50 -> { fields.clear(); locked = true; pendingRead = 0; }
                case 0x11 -> writeToDisplay(d);
                case 0x21 -> writeError(d);
                case 0x22 -> { d.next(); d.next(); writeError(d); }
                case 0x42, 0x52, 0x82 -> {
                    int cc1 = d.next(), cc2 = d.next();
                    controlOne(cc1);
                    pendingRead = c;
                    locked = false;
                    controlTwo(cc2);
                }
                case 0x72 -> readImmediate();
                case 0x62 -> sendScreen();
                case 0x02 -> saveScreen();
                case 0x12 -> restoreScreen(d.next());
                case 0x23 -> roll(d.next(), d.next(), d.next());
                case 0xF3 -> structuredField(d);
                default -> throw new IllegalStateException("5250 command 0x" + Integer.toHexString(c) + " is not supported");
            }
        }
    }

    private void clearUnit(int r, int c) {
        rows = r; cols = c; buf = new byte[r * c];
        fields.clear();
        addr = 0; cursor = 0; home = -1; errorRow = r - 1; keyMask = 0;
        locked = true; inputError = false; errorLine = null; pendingRead = 0;
    }

    private int size() { return rows * cols; }

    /** A 1-based row and column from the host as a screen position. */
    private int at(int r, int c) {
        if (r < 1 || r > rows || c < 1 || c > cols) throw new IllegalStateException("The host addressed row " + r + ", column " + c + " outside the screen");
        return (r - 1) * cols + (c - 1);
    }

    private void put(int b) { buf[addr] = (byte) b; addr = (addr + 1) % size(); }

    /** CC1: lock the keyboard, and reset changed flags or empty input fields as the host asks. */
    private void controlOne(int cc1) {
        int c = cc1 & 0xE0;
        if (c != 0) { pendingRead = 0; locked = true; }
        for (Field f : fields) {
            if (((c == 0x40 || c == 0xA0 || c == 0xC0) && f.input()) || c == 0x60 || c == 0xE0) f.mdt = false;
            if (((c == 0x80 || c == 0xC0) && f.input() && f.mdt) || ((c == 0xA0 || c == 0xE0) && f.input())) blank(f);
        }
    }

    /** CC2: unlock the keyboard when the host says so (sound and blink flags do not matter here). */
    private void controlTwo(int cc2) { if ((cc2 & 0x08) != 0) locked = false; }

    private void blank(Field f) { for (int i = 0; i < f.length; i++) buf[(f.start + i) % size()] = 0; }

    private void writeToDisplay(Data d) {
        int cc1 = d.next(), cc2 = d.next();
        controlOne(cc1);
        boolean cursorSet = orders(d);
        if (locked && !cursorSet) {
            Field first = fields.stream().filter(Field::input).findFirst().orElse(null);
            cursor = first == null ? 0 : first.start;
        }
        controlTwo(cc2);
    }

    /** Write Error Code: a message on the error row, kept until Reset (or the next key) puts the row back. */
    private void writeError(Data d) {
        if (errorLine == null) errorLine = Arrays.copyOfRange(buf, errorRow * cols, (errorRow + 1) * cols);
        int keep = addr, keepCursor = cursor;
        addr = errorRow * cols;
        boolean cursorSet = orders(d);
        addr = keep;
        if (!cursorSet) cursor = keepCursor;
        inputError = true;
        locked = false;
    }

    private void resetError() {
        if (errorLine != null) System.arraycopy(errorLine, 0, buf, errorRow * cols, cols);
        errorLine = null;
        inputError = false;
    }

    /** The orders and text of a Write to Display, up to the next command. True when the host placed the cursor. */
    private boolean orders(Data d) {
        boolean cursorSet = false;
        while (d.more() && d.peek() != ESC) {
            int b = d.next();
            switch (b) {
                case 0x01 -> {   // start of header
                    int len = d.next();
                    if (len > 7) throw new IllegalStateException("Bad start-of-header length " + len);
                    int[] h = new int[len];
                    for (int i = 0; i < len; i++) h[i] = d.next();
                    fields.clear();
                    pendingRead = 0;
                    if (len >= 4 && h[3] >= 1 && h[3] <= rows) errorRow = h[3] - 1;
                    keyMask = len >= 7 ? h[4] << 16 | h[5] << 8 | h[6] : 0;
                }
                case 0x02 -> {   // repeat a character up to an address
                    int end = at(d.next(), d.next()), ch = d.next();
                    for (int n = Math.floorMod(end - addr, size()) + 1; n > 0; n--) put(ch);
                }
                case 0x03 -> {   // erase up to an address
                    int end = at(d.next(), d.next()), len = d.next();
                    for (int i = 1; i < len; i++) d.next();
                    for (int n = Math.floorMod(end - addr, size()) + 1; n > 0; n--) put(0);
                }
                case 0x10 -> {   // transparent data
                    int len = d.next() << 8 | d.next();
                    for (int i = 0; i < len; i++) put(d.next());
                }
                case 0x11 -> addr = at(d.next(), d.next());
                case 0x12 -> { d.next(); d.next(); }   // extended attributes (colour) are not kept
                case 0x13 -> { cursor = at(d.next(), d.next()); home = cursor; cursorSet = true; }
                case 0x14 -> { cursor = at(d.next(), d.next()); cursorSet = true; }
                case 0x15 -> { int len = d.next() << 8 | d.next(); for (int i = 2; i < len; i++) d.next(); }
                case 0x1D -> startField(d);
                default -> {
                    if (b < 0x20 && b != 0 && b != 0x1C) throw new IllegalStateException("5250 order 0x" + Integer.toHexString(b) + " is not supported");
                    put(b);
                }
            }
        }
        return cursorSet;
    }

    /** Start of field: optional format word and control words, the display attribute, then the length. */
    private void startField(Data d) {
        int ffw1 = 0, ffw2 = 0;
        if ((d.peek() & 0xC0) == 0x40) {
            ffw1 = d.next(); ffw2 = d.next();
            while ((d.peek() & 0xC0) == 0x80) { d.next(); d.next(); }
        }
        int attr = d.next(), len = d.next() << 8 | d.next();
        if (attr != 0) put(attr);
        int start = addr;
        fields.removeIf(f -> f.start == start);
        fields.add(new Field(start, Math.max(0, Math.min(len, size())), ffw1, ffw2, attr));
    }

    private void roll(int how, int top, int bottom) {
        boolean down = (how & 0x80) != 0;
        int n = how & 0x1F;
        if (top < 1 || bottom > rows || top > bottom || n == 0) return;
        int from = top - 1, to = bottom - 1;
        byte[] copy = buf.clone();
        for (int r = from; r <= to; r++) {
            int src = down ? r - n : r + n;
            for (int c = 0; c < cols; c++) buf[r * cols + c] = src >= from && src <= to ? copy[src * cols + c] : 0;
        }
    }

    private void structuredField(Data d) {
        int len = d.next() << 8 | d.next();
        int cls = d.next(), type = d.next();
        for (int i = 4; i < len; i++) d.next();
        if (cls == 0xD9 && type == 0x70) queryReply();
    }

    /** Answer to the host's 5250 query: a colour display, 24 x 80 and 27 x 132 capable, as other 5250 emulators describe themselves. */
    private void queryReply() {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.writeBytes(new byte[]{0, 0, (byte) 0x88, 0x00, 0x3A, (byte) 0xD9, 0x70, (byte) 0x80, 0x06, 0x00, 0x01, 0x01, 0x00});
        o.writeBytes(new byte[16]);
        o.write(0x01);
        for (char ch : "5251011".toCharArray()) o.write(fromChar[ch]);
        o.writeBytes(new byte[]{0x02, 0x00, 0x00, 0, 0, 0, 0, 0x01, 0x00, 0, 0, 0, 0x01, 0x43, 0, 0, 0, 0, 0, 0, 0, 0, 0, 0});
        packet(0x00, 0x00, o.toByteArray());
    }

    private void saveScreen() {
        int n = nextSave++ & 0xFF;
        List<Field> copy = new ArrayList<>();
        for (Field f : fields) copy.add(f.copy());
        saved.put(n, new Saved(rows, cols, buf.clone(), copy, cursor, errorRow));
        packet(0x00, 0x04, new byte[]{ESC, 0x12, (byte) n});
    }

    private void restoreScreen(int n) {
        Saved s = saved.get(n);
        if (s == null) return;
        rows = s.rows(); cols = s.cols(); buf = s.buf().clone(); cursor = s.cursor(); errorRow = s.errorRow();
        fields.clear();
        for (Field f : s.fields()) fields.add(f.copy());
    }

    private void sendScreen() {
        byte[] out = buf.clone();
        for (int i = 0; i < out.length; i++) if (out[i] == 0) out[i] = 0x40;
        packet(0x00, 0x08, out);
    }

    private void readImmediate() { sendInput(0x00, false); }

    // ---- Writing to the host ----

    /** One 5250 record: the header (length, record type, flags, opcode), the data with 0xFF doubled, and end-of-record. */
    private void packet(int flags, int opcode, byte[] data) {
        int len = 10 + data.length;
        ByteArrayOutputStream o = new ByteArrayOutputStream(len + 8);
        byte[] head = {(byte) (len >> 8), (byte) len, 0x12, (byte) 0xA0, 0, 0, 0x04, (byte) flags, 0, (byte) opcode};
        for (byte b : head) { o.write(b); if (b == (byte) IAC) o.write(IAC); }
        for (byte b : data) { o.write(b); if (b == (byte) IAC) o.write(IAC); }
        o.write(IAC); o.write(EOR);
        if (ended) throw new ActionException("TERMINAL.ENDED", "The host closed the connection", "Connect again; the host may have signed the session out.");
        try {
            synchronized (toHost) { toHost.write(o.toByteArray()); toHost.flush(); }
        } catch (IOException e) { throw new ActionException("TERMINAL.IO_ERROR", e.getMessage(), null, true); }
    }

    /** Sends the cursor, the attention key and the fields the pending read asks for (all input fields, or the changed ones with their address). */
    private void sendInput(int aid, boolean noFields) {
        ByteArrayOutputStream o = new ByteArrayOutputStream();
        o.write(cursor / cols + 1); o.write(cursor % cols + 1); o.write(aid);
        if (!noFields) {
            boolean all = pendingRead == 0x42 || pendingRead == 0;
            for (Field f : fields) {
                if (!f.input() || (!all && !f.mdt)) continue;
                byte[] data = fieldBytes(f);
                int n = data.length;
                if (!all) {
                    while (n > 0 && data[n - 1] == 0) n--;
                    o.write(0x11); o.write(f.start / cols + 1); o.write(f.start % cols + 1);
                }
                for (int i = 0; i < n; i++) o.write(data[i] == 0 ? 0x40 : data[i]);
            }
        }
        packet(0x00, 0x00, o.toByteArray());
    }

    /** A field's bytes; a signed-numeric field ending in '-' carries the sign in the zone of its last digit. */
    private byte[] fieldBytes(Field f) {
        byte[] d = new byte[f.length];
        for (int i = 0; i < f.length; i++) d[i] = buf[(f.start + i) % size()];
        if (f.shift() != 7) return d;
        int last = d.length - 1;
        while (last >= 0 && (d[last] == 0 || d[last] == 0x40)) last--;
        if (last < 0 || (d[last] & 0xff) != 0x60) return d;   // no '-'
        d[last] = 0;
        int digit = last - 1;
        while (digit >= 0 && ((d[digit] & 0xF0) != 0xF0)) digit--;
        if (digit >= 0) d[digit] = (byte) ((d[digit] & 0x0F) | 0xD0);
        return d;
    }

    // ---- Engine commands ----

    private static final Pattern ASCII = Pattern.compile("Ascii\\((\\d+),(\\d+),(\\d+)\\)");
    private static final Pattern MOVE = Pattern.compile("MoveCursor\\((\\d+),(\\d+)\\)");
    private static final Pattern WAIT = Pattern.compile("Wait\\((\\d+),(\\w+)\\)");
    private static final Pattern PF = Pattern.compile("PF\\((\\d+)\\)");

    @Override synchronized List<String> cmd(String action) {
        Matcher m;
        if (action.equals("Ascii()")) return lines();
        if ((m = ASCII.matcher(action)).matches()) return List.of(text(Integer.parseInt(m.group(1)), Integer.parseInt(m.group(2)), Integer.parseInt(m.group(3))));
        if (action.equals("Query(Cursor)")) { synchronized (lock) { return List.of(cursor / cols + " " + cursor % cols); } }
        if ((m = MOVE.matcher(action)).matches()) {
            int r = Integer.parseInt(m.group(1)), c = Integer.parseInt(m.group(2));
            synchronized (lock) {
                if (r >= rows || c >= cols) throw new ActionException("TERMINAL.BAD_POSITION", "Row " + (r + 1) + ", column " + (c + 1) + " is outside the " + rows + " x " + cols + " screen");
                cursor = r * cols + c;
            }
            return List.of();
        }
        if ((m = WAIT.matcher(action)).matches()) { waitFor(Integer.parseInt(m.group(1)), m.group(2)); return List.of(); }
        if (action.startsWith("String(\"") && action.endsWith("\")")) { type(unquote(action.substring(8, action.length() - 2))); return List.of(); }
        if (action.equals("Quit")) { close(); return List.of(); }
        if ((m = PF.matcher(action)).matches()) {
            int n = Integer.parseInt(m.group(1));
            aid(n <= 12 ? 0x30 + n : 0xB0 + n - 12, (keyMask & (1 << (n - 1))) != 0);
            return List.of();
        }
        switch (action) {
            case "Enter" -> aid(AID_ENTER, false);
            case "Clear" -> aid(AID_CLEAR, false);
            case "Help" -> aid(AID_HELP, true);
            case "Print" -> aid(AID_PRINT, false);
            case "PageUp" -> aid(AID_ROLL_DOWN, false);
            case "PageDown" -> aid(AID_ROLL_UP, false);
            case "Attn" -> signal(0x40);
            case "SysReq" -> signal(0x04);
            default -> local(action);
        }
        return List.of();
    }

    /** Sends an attention key with the fields the host is reading (none for Help, or for a key the host masked). */
    private void aid(int aid, boolean noFields) {
        synchronized (lock) {
            if (inputError) resetError();
            if (ended) throw new ActionException("TERMINAL.ENDED", "The host closed the connection", "Connect again; the host may have signed the session out.");
            if (locked || pendingRead == 0) throw new ActionException("TERMINAL.KEYBOARD_LOCKED", "The host is not waiting for a key yet",
                    "Wait for the screen (Wait for screen, or wait_for_host) before pressing the next key.");
            sendInput(aid, noFields);
            pendingRead = 0;
            locked = true;
        }
    }

    /** Attn and SysReq go to the host as flags on an empty record. */
    private void signal(int flag) {
        synchronized (lock) {
            if (inputError) resetError();
            packet(flag, 0x00, new byte[0]);
            locked = true;
        }
    }

    private Field inputAt(int pos) {
        for (Field f : fields) if (f.input() && Math.floorMod(pos - f.start, size()) < f.length) return f;
        return null;
    }

    /** The first input field starting after pos (wrapping), or null when there is none. */
    private Field nextInput(int pos) {
        Field best = null; int bestOff = Integer.MAX_VALUE;
        for (Field f : fields) {
            if (!f.input()) continue;
            int off = Math.floorMod(f.start - pos - 1, size());
            if (off < bestOff) { bestOff = off; best = f; }
        }
        return best;
    }

    private Field previousInput(int pos) {
        Field best = null; int bestOff = Integer.MAX_VALUE;
        for (Field f : fields) {
            if (!f.input()) continue;
            int off = Math.floorMod(pos - f.start - 1, size());
            if (off < bestOff) { bestOff = off; best = f; }
        }
        return best;
    }

    private Field firstInput() { return nextInput(size() - 1); }

    /** Types text into input fields at the cursor, moving to the next field when one is full. */
    private void type(String text) {
        synchronized (lock) {
            if (inputError) resetError();
            if (locked) throw new ActionException("TERMINAL.KEYBOARD_LOCKED", "The keyboard is locked while the host works", "Wait for the screen before typing.");
            for (int i = 0; i < text.length(); i++) {
                Field f = inputAt(cursor);
                if (f == null) throw new ActionException("TERMINAL.COMMAND_FAILED", "The cursor (row " + (cursor / cols + 1) + ", column " + (cursor % cols + 1) + ") is not in an input field",
                        "Check the connection and that the cursor is in an input field.");
                char ch = text.charAt(i);
                if (f.monocase()) ch = Character.toUpperCase(ch);
                short code = fromChar[ch];
                if (code < 0) throw new ActionException("TERMINAL.BAD_CHARACTER", "The character '" + ch + "' does not exist in the host code page", "Change the text, or choose the host's code page in Connect.");
                buf[cursor] = (byte) code;
                f.mdt = true;
                cursor = (cursor + 1) % size();
                if (Math.floorMod(cursor - f.start, size()) >= f.length) { Field n = nextInput(f.start); cursor = n == null ? cursor : n.start; }
            }
        }
    }

    /** Keys the terminal handles itself: cursor moves and field editing. */
    private void local(String action) {
        synchronized (lock) {
            Field f = inputAt(cursor);
            switch (action) {
                case "Reset" -> resetError();
                case "Tab" -> { Field n = nextInput(cursor); if (n != null) cursor = n.start; }
                case "BackTab" -> {
                    Field p = f != null && cursor != f.start ? f : previousInput(cursor);
                    if (p != null) cursor = p.start;
                }
                case "Home" -> { Field n = firstInput(); cursor = home >= 0 ? home : n == null ? 0 : n.start; }
                case "Newline" -> {
                    int nextRow = (cursor / cols + 1) % rows;
                    Field n = nextInput(nextRow * cols - 1);
                    cursor = n != null ? n.start : nextRow * cols;
                }
                case "Up" -> cursor = Math.floorMod(cursor - cols, size());
                case "Down" -> cursor = (cursor + cols) % size();
                case "Left", "BackSpace" -> { if (!action.equals("BackSpace") || (f != null && cursor != f.start)) cursor = Math.floorMod(cursor - 1, size()); }
                case "Right" -> cursor = (cursor + 1) % size();
                case "Insert" -> { }
                case "EraseEOF", "FieldExit", "Dup", "Delete", "FieldEnd" -> {
                    if (f == null) throw new ActionException("TERMINAL.COMMAND_FAILED", action + " needs the cursor in an input field", "Check the connection and that the cursor is in an input field.");
                    int off = Math.floorMod(cursor - f.start, size());
                    switch (action) {
                        case "Delete" -> {
                            for (int i = off; i < f.length - 1; i++) buf[(f.start + i) % size()] = buf[(f.start + i + 1) % size()];
                            buf[(f.start + f.length - 1) % size()] = 0;
                        }
                        case "FieldEnd" -> {
                            int end = f.length;
                            while (end > 0 && (buf[(f.start + end - 1) % size()] == 0 || buf[(f.start + end - 1) % size()] == 0x40)) end--;
                            cursor = (f.start + Math.min(end, f.length - 1)) % size();
                            return;
                        }
                        default -> { for (int i = off; i < f.length; i++) buf[(f.start + i) % size()] = (byte) (action.equals("Dup") ? 0x1C : 0); }
                    }
                    f.mdt = true;
                    if (!action.equals("EraseEOF") && !action.equals("Delete")) { Field n = nextInput(f.start); if (n != null) cursor = n.start; }
                }
                case "EraseInput" -> {
                    for (Field i : fields) if (i.input()) { blank(i); i.mdt = false; }
                    Field n = firstInput();
                    cursor = n == null ? 0 : n.start;
                }
                default -> throw new ActionException("TERMINAL.KEY_NOT_ON_5250", action + " is not a 5250 key", KEYS_HINT);
            }
        }
    }

    static final String KEYS_HINT = "On 5250 hosts use Enter, Tab, BackTab, Home, Newline, arrows, BackSpace, Delete, EraseEOF, EraseInput, FieldExit, FieldEnd, Dup, "
            + "Reset, Clear, Help, Print, PageUp, PageDown, Attn, SysReq or PF1-PF24.";

    /** Key names on a 5250 display; PA keys, FieldMark, Escape and Ctrl keys do not exist there. */
    static String keyAction(String key) {
        String k = key.trim().toUpperCase(Locale.ROOT).replace(" ", "");
        if (k.matches("PF([1-9]|1[0-9]|2[0-4])")) return "PF(" + k.substring(2) + ")";
        if (k.matches("F([1-9]|1[0-9]|2[0-4])")) return "PF(" + k.substring(1) + ")";
        return switch (k) {
            case "ENTER" -> "Enter"; case "TAB" -> "Tab"; case "BACKTAB" -> "BackTab"; case "CLEAR" -> "Clear";
            case "RESET" -> "Reset"; case "ERASEEOF" -> "EraseEOF"; case "HOME" -> "Home"; case "ERASEINPUT" -> "EraseInput";
            case "NEWLINE" -> "Newline"; case "UP" -> "Up"; case "DOWN" -> "Down"; case "LEFT" -> "Left"; case "RIGHT" -> "Right";
            case "BACKSPACE" -> "BackSpace"; case "DELETE" -> "Delete"; case "INSERT" -> "Insert"; case "FIELDEND" -> "FieldEnd";
            case "FIELDEXIT" -> "FieldExit"; case "DUP" -> "Dup"; case "ATTN" -> "Attn"; case "SYSREQ" -> "SysReq";
            case "HELP" -> "Help"; case "PRINT" -> "Print"; case "PAGEUP", "ROLLDOWN" -> "PageUp"; case "PAGEDOWN", "ROLLUP" -> "PageDown";
            default -> {
                if (k.matches("PA[1-3]|FIELDMARK|ESC(APE)?|END|CTRL[+-][A-Z]"))
                    throw new ActionException("TERMINAL.KEY_NOT_ON_5250", "The key '" + key + "' does not exist on 5250 displays", KEYS_HINT);
                throw new ActionException("TERMINAL.BAD_KEY", "Unknown key '" + key + "'", KEYS_HINT + " Or a name set with Set key mapping.");
            }
        };
    }

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
        synchronized (lock) {
            long seen = records;
            while (true) {
                boolean met = switch (what) {
                    case "Seconds" -> false;
                    case "Output" -> records > seen;
                    case "3270Mode" -> records > 0;
                    default -> !locked;   // InputField, Unlock
                };
                if (met) return;
                if (ended) {
                    if (what.equals("Seconds")) return;
                    throw new ActionException("TERMINAL.ENDED", "The host closed the connection" + (lastProblem == null ? "" : " (" + lastProblem + ")"), "Connect again; the host may have signed the session out.");
                }
                long left = end - System.nanoTime();
                if (left <= 0) {
                    if (what.equals("Seconds")) return;
                    throw new ActionException("TERMINAL.COMMAND_FAILED", "Wait timed out: the host did not " + (what.equals("Output") ? "send anything new" : "unlock the keyboard")
                            + " in " + seconds + " s" + (lastProblem == null ? "" : "; last problem: " + lastProblem), "Allow more time, or wait for specific text instead.");
                }
                try { lock.wait(Math.max(1, Math.min(50, left / 1_000_000))); }
                catch (InterruptedException e) { Thread.currentThread().interrupt(); throw new ActionException("TERMINAL.INTERRUPTED", "Stopped while waiting for the host"); }
            }
        }
    }

    // ---- Reading the screen ----

    /** The screen as text; attribute positions, nulls and characters under a non-display attribute show as blanks. */
    private char[] rendered() {
        char[] out = new char[size()];
        boolean[] hidden = new boolean[size()];
        for (Field f : fields) if (f.hidden()) for (int i = 0; i < f.length; i++) hidden[(f.start + i) % size()] = true;
        int attr = 0x20;
        for (int i = 0; i < out.length; i++) {
            int b = buf[i] & 0xff;
            if (b >= 0x20 && b <= 0x3F) { attr = b; out[i] = ' '; continue; }
            out[i] = hidden[i] || (attr & 0x07) == 0x07 ? ' ' : b == 0x1C ? '*' : toChar[b];
        }
        return out;
    }

    List<String> lines() {
        synchronized (lock) {
            char[] s = rendered();
            List<String> out = new ArrayList<>(rows);
            for (int r = 0; r < rows; r++) out.add(new String(s, r * cols, cols));
            return out;
        }
    }

    private String text(int row, int col, int len) {
        synchronized (lock) {
            char[] s = rendered();
            int from = row * cols + col;
            if (from >= s.length) throw new ActionException("TERMINAL.BAD_POSITION", "Row " + (row + 1) + ", column " + (col + 1) + " is outside the " + rows + " x " + cols + " screen");
            return new String(s, from, Math.min(len, s.length - from));
        }
    }

    int rows() { synchronized (lock) { return rows; } }
    int cols() { synchronized (lock) { return cols; } }

    private static final Pattern RUN = Pattern.compile("\\S+(?: \\S+)*");

    /**
     * Fields for the Fields actions: the host's field table (input and output-only fields) plus every run of other
     * text on the screen as a protected field, so a label such as "User" can be used to find the input field after it.
     */
    List<TerminalPackage.Field> fieldList() {
        synchronized (lock) {
            char[] s = rendered();
            boolean[] inField = new boolean[size()];
            record Item(int start, int length, boolean isProtected, boolean numeric, boolean hidden, String text) { }
            List<Item> items = new ArrayList<>();
            for (Field f : fields) {
                byte[] d = new byte[f.length];
                for (int i = 0; i < f.length; i++) { int p = (f.start + i) % size(); inField[p] = true; d[i] = buf[p]; }
                StringBuilder t = new StringBuilder();
                for (byte b : d) t.append(b == 0x1C ? '*' : toChar[b & 0xff]);
                items.add(new Item(f.start, f.length, !f.input(), f.numeric(), f.hidden(), t.toString()));
            }
            for (int r = 0; r < rows; r++) {
                int c = 0;
                while (c < cols) {
                    while (c < cols && inField[r * cols + c]) c++;
                    int from = c;
                    while (c < cols && !inField[r * cols + c]) c++;
                    String part = new String(s, r * cols + from, c - from);
                    for (Matcher m = RUN.matcher(part); m.find(); )
                        items.add(new Item(r * cols + from + m.start(), m.end() - m.start(), true, false, false, m.group()));
                }
            }
            items.sort(Comparator.comparingInt(Item::start));
            List<TerminalPackage.Field> out = new ArrayList<>();
            for (Item i : items)
                out.add(new TerminalPackage.Field(out.size() + 1, i.start() / cols + 1, i.start() % cols + 1, i.length(), i.isProtected(), i.numeric(), i.hidden(), i.text()));
            return out;
        }
    }

    @Override public void close() {
        ended = true;
        try { socket.close(); } catch (IOException ignored) { }
    }
}
