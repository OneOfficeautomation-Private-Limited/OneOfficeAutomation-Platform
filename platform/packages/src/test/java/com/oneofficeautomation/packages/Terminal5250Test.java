package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import static com.oneofficeautomation.packages.EnterpriseGapsTest.rows;
import static org.junit.jupiter.api.Assertions.*;

/**
 * IBM i (TN5250) hosts, against an in-process host that speaks telnet and the 5250 data stream: it negotiates the
 * terminal type and device name, asks the 5250 query, then sends a sign-on screen, a menu, an error message and a
 * signed-off screen, and checks what the terminal sends back for each key.
 */
class Terminal5250Test {
    static final int IAC = 255, DO = 253, WILL = 251, SB = 250, SE = 240, EOR = 239;
    static final Charset EBCDIC = Charset.forName("IBM037");
    Host host;

    @AfterEach void stop() throws IOException { if (host != null) host.close(); }

    static ActionContext ctx() throws IOException { return PackagesTestSupport.ctx(Files.createTempDirectory("ooa-5250"), Map.of()); }

    /** A 5250 record body: commands, orders and EBCDIC text. */
    static final class Screen {
        final ByteArrayOutputStream b = new ByteArrayOutputStream();
        Screen bytes(int... v) { for (int x : v) b.write(x); return this; }
        Screen esc(int cmd) { return bytes(0x04, cmd); }
        Screen text(String s) { b.writeBytes(s.getBytes(EBCDIC)); return this; }
        Screen at(int r, int c) { return bytes(0x11, r, c); }
        Screen field(int ffw1, int ffw2, int attr, int len) { return bytes(0x1D, ffw1, ffw2, attr, len >> 8, len & 0xff); }
    }

    /** The host side: telnet negotiation, then 5250 records in both directions. */
    static final class Host implements Closeable {
        final ServerSocket server = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        final BlockingQueue<int[]> options = new LinkedBlockingQueue<>();
        final BlockingQueue<byte[]> subs = new LinkedBlockingQueue<>(), records = new LinkedBlockingQueue<>();
        Socket socket; OutputStream out;
        String termType, deviceName;
        byte[] queryReply;
        volatile Throwable failure;

        Host() throws IOException { }

        /** Accepts the terminal, negotiates, answers the query, and shows the first screen; runs while Connect waits. */
        Thread open(Screen first) {
            Thread t = new Thread(() -> {
                try {
                    socket = server.accept();
                    out = socket.getOutputStream();
                    Thread reader = new Thread(this::read, "fake-5250-reader");
                    reader.setDaemon(true);
                    reader.start();
                    raw(IAC, DO, 24);
                    assertArrayEquals(new int[]{WILL, 24}, options.poll(5, TimeUnit.SECONDS));
                    raw(IAC, SB, 24, 1, IAC, SE);
                    byte[] tt = subs.poll(5, TimeUnit.SECONDS);
                    termType = new String(tt, 2, tt.length - 2, StandardCharsets.US_ASCII);
                    raw(IAC, DO, 39);
                    assertArrayEquals(new int[]{WILL, 39}, options.poll(5, TimeUnit.SECONDS));
                    raw(IAC, SB, 39, 1, IAC, SE);
                    byte[] env = subs.poll(5, TimeUnit.SECONDS);
                    deviceName = new String(env, 2, env.length - 2, StandardCharsets.US_ASCII);
                    raw(IAC, DO, 25, IAC, WILL, 25, IAC, DO, 0, IAC, WILL, 0, IAC, DO, 1);
                    Set<String> answers = new HashSet<>();
                    for (int i = 0; i < 5; i++) { int[] o = options.poll(5, TimeUnit.SECONDS); answers.add(o[0] + ":" + o[1]); }
                    assertEquals(Set.of("251:25", "253:25", "251:0", "253:0", "252:1"), answers);   // WONT echo (1)
                    send(new Screen().esc(0xF3).bytes(0x00, 0x05, 0xD9, 0x70, 0x00));
                    queryReply = next();
                    send(first);
                } catch (Throwable e) { failure = e; }
            }, "fake-5250-host");
            t.setDaemon(true);
            t.start();
            return t;
        }

        private void read() {
            try {
                InputStream in = new BufferedInputStream(socket.getInputStream());
                ByteArrayOutputStream rec = new ByteArrayOutputStream();
                for (int b; (b = in.read()) >= 0; ) {
                    if (b != IAC) { rec.write(b); continue; }
                    int c = in.read();
                    if (c == IAC) rec.write(IAC);
                    else if (c == EOR) { records.add(rec.toByteArray()); rec.reset(); }
                    else if (c == SB) {
                        ByteArrayOutputStream s = new ByteArrayOutputStream();
                        for (int x; (x = in.read()) >= 0; ) {
                            if (x == IAC) { if (in.read() == SE) break; } else s.write(x);
                        }
                        subs.add(s.toByteArray());
                    } else options.add(new int[]{c, in.read()});
                }
            } catch (IOException ignored) { }
        }

        void raw(int... b) throws IOException { for (int x : b) out.write(x); out.flush(); }

        void send(Screen s) throws IOException {
            byte[] data = s.b.toByteArray();
            int len = 10 + data.length;
            ByteArrayOutputStream o = new ByteArrayOutputStream();
            o.writeBytes(new byte[]{(byte) (len >> 8), (byte) len, 0x12, (byte) 0xA0, 0, 0, 0x04, 0, 0, 0x03});
            for (byte x : data) { o.write(x); if (x == (byte) IAC) o.write(IAC); }
            o.write(IAC); o.write(EOR);
            out.write(o.toByteArray());
            out.flush();
        }

        byte[] next() throws InterruptedException {
            byte[] r = records.poll(5, TimeUnit.SECONDS);
            assertNotNull(r, "the terminal sent nothing");
            return r;
        }

        @Override public void close() throws IOException { server.close(); if (socket != null) socket.close(); }
    }

    /** Sign-on: User (monocase), Password (non-display) and a signed-numeric Amount; F5 is masked (no data returned). */
    static Screen signOn() {
        return new Screen().esc(0x40)
                .esc(0x11).bytes(0x00, 0x18)
                .bytes(0x01, 0x07, 0, 0, 0, 24, 0, 0, 0x10)
                .at(1, 35).text("Sign On")
                .at(6, 17).text("User  . . . . . .")
                .at(6, 52).field(0x40, 0x20, 0x24, 10).at(6, 63).bytes(0x20)
                .at(7, 17).text("Password  . . . .")
                .at(7, 52).field(0x40, 0x00, 0x27, 10).at(7, 63).bytes(0x20)
                .at(8, 17).text("Amount  . . . . .")
                .at(8, 52).field(0x47, 0x00, 0x24, 6).at(8, 59).bytes(0x20)
                .at(24, 1).text("F3=Exit")
                .bytes(0x13, 6, 53)
                .esc(0x52).bytes(0x00, 0x00);
    }

    static Screen menu() {
        return new Screen().esc(0x40)
                .esc(0x11).bytes(0x00, 0x18)
                .bytes(0x01, 0x07, 0, 0, 0, 24, 0, 0, 0x10)
                .at(1, 30).text("MAIN MENU")
                .at(3, 2).text("1. User tasks")
                .at(3, 30).text("Hidden:").bytes(0x27).text("SECRET").bytes(0x20)
                .at(20, 1).text("Selection or command")
                .at(21, 1).text("===>")
                .at(21, 6).field(0x40, 0x00, 0x24, 20).at(21, 27).bytes(0x20)
                .bytes(0x13, 21, 7)
                .esc(0x52).bytes(0x00, 0x00);
    }

    static String ebcdic(byte[] b, int from, int to) { return new String(Arrays.copyOfRange(b, from, to), EBCDIC); }

    static Map<String, Object> connect(TerminalPackage t, ActionContext ctx, Host h) {
        return t.connect(ctx, "127.0.0.1", h.server.getLocalPort(), false, "3179-2", "as400", "tn5250", 10, "OOADSP01", "cp037", true, "Sign On",
                null, null, null, null, null, "vt100", 24, 80);
    }

    @Test void signsOnAndWorksTheMenu() throws Exception {
        host = new Host();
        var t = new TerminalPackage();
        var ctx = ctx();
        Thread opening = host.open(signOn());
        connect(t, ctx, host);
        opening.join(5000);
        if (host.failure != null) throw new AssertionError(host.failure);
        assertEquals("IBM-3179-2", host.termType);
        assertEquals("\u0003DEVNAME\u0001OOADSP01", host.deviceName);
        byte[] q = host.queryReply;
        assertEquals(0x88, q[12] & 0xff, "query reply AID");
        assertEquals(0xD9, q[15] & 0xff);
        assertEquals(0x70, q[16] & 0xff);
        assertEquals(10 + 61, q.length, "header plus cursor, AID and the 58-byte reply");

        String screen = (String) t.getScreen(ctx, "as400").get("text");
        assertTrue(screen.contains("Sign On") && screen.contains("F3=Exit"), screen);
        assertEquals(List.of(6, 53), List.of(((Number) t.getCursor(ctx, "as400").get("row")).intValue(), ((Number) t.getCursor(ctx, "as400").get("column")).intValue()));

        // Fill the fields by their labels; the password is typed into a non-display field.
        t.setField(ctx, "qsecofr", null, null, "User", "as400", null, null, 10);
        t.setField(ctx, new SecureString("pw123", "t"), null, null, "Password", "as400", null, null, 10);
        t.setField(ctx, "42-", null, null, "Amount", "as400", null, null, 10);
        screen = (String) t.getScreen(ctx, "as400").get("text");
        assertTrue(screen.contains("QSECOFR"), "monocase field upper-cases: " + screen);
        assertFalse(screen.contains("pw123"), "non-display field shows blanks");
        List<List<Object>> inputs = rows(t.getAllFields(ctx, true, "as400").get("table"));   // index, row, column, length, protected, numeric, hidden, text
        assertEquals(3, inputs.size());
        assertEquals(List.of("QSECOFR", "", "42-"), inputs.stream().map(r -> r.get(7)).toList());
        assertEquals(true, inputs.get(1).get(6));
        assertEquals(true, inputs.get(2).get(5));

        // Enter sends the cursor, the key and each changed field with its address.
        Thread menuSender = new Thread(() -> {
            try {
                byte[] r = host.next();
                assertEquals(0x00, r[9] & 0xff);
                byte[] d = Arrays.copyOfRange(r, 10, r.length);
                assertEquals(0xF1, d[2] & 0xff, "Enter AID");
                assertArrayEquals(new byte[]{0x11, 6, 53}, Arrays.copyOfRange(d, 3, 6));
                assertEquals("QSECOFR", ebcdic(d, 6, 13));
                assertArrayEquals(new byte[]{0x11, 7, 53}, Arrays.copyOfRange(d, 13, 16));
                assertEquals("pw123", ebcdic(d, 16, 21));
                assertArrayEquals(new byte[]{0x11, 8, 53, (byte) 0xF4, (byte) 0xD2}, Arrays.copyOfRange(d, 21, 26), "42- carries the sign in the last digit");
                assertEquals(26, d.length);
                host.send(menu());
            } catch (Throwable e) { host.failure = e; }
        });
        menuSender.start();
        t.sendKey(ctx, "Enter", "as400", true, 10, 1, 0, "MAIN MENU");
        menuSender.join(5000);
        if (host.failure != null) throw new AssertionError(host.failure);
        screen = (String) t.getScreen(ctx, "as400").get("text");
        assertTrue(screen.contains("Hidden:") && !screen.contains("SECRET"), "text after a non-display attribute is hidden: " + screen);

        // A masked function key (F5) sends no field data; the host answers with an error message on row 24.
        t.setField(ctx, "call pgm", null, null, "===>", "as400", null, null, 10);
        Thread f5 = new Thread(() -> {
            try {
                byte[] r = host.next();
                byte[] d = Arrays.copyOfRange(r, 10, r.length);
                assertArrayEquals(new byte[]{21, 15, 0x35}, d, "cursor and F5 only");
                host.send(new Screen().esc(0x21).bytes(0x22).text("Option not allowed").bytes(0x20).esc(0x52).bytes(0x00, 0x00));
            } catch (Throwable e) { host.failure = e; }
        });
        f5.start();
        t.sendKey(ctx, "F5", "as400", true, 10, 1, 0, "Option not allowed");
        f5.join(5000);
        if (host.failure != null) throw new AssertionError(host.failure);

        // The next key puts row 24 back; PageDown is Roll Up and sends the typed command.
        Thread roll = new Thread(() -> {
            try {
                byte[] r = host.next();
                byte[] d = Arrays.copyOfRange(r, 10, r.length);
                assertEquals(0xF5, d[2] & 0xff, "Roll Up AID");
                assertArrayEquals(new byte[]{0x11, 21, 7}, Arrays.copyOfRange(d, 3, 6));
                assertEquals("call pgm", ebcdic(d, 6, d.length));
                host.send(new Screen().esc(0x02));
                byte[] saved = host.next();
                assertEquals(0x04, saved[9] & 0xff, "save screen answer");
                int n = saved[12];
                host.send(new Screen().esc(0x40).esc(0x11).bytes(0x00, 0x00).at(1, 1).text("Signed off").esc(0x12).bytes(n)
                        .esc(0x11).bytes(0x00, 0x08));
            } catch (Throwable e) { host.failure = e; }
        });
        roll.start();
        t.sendKey(ctx, "PageDown", "as400", false, 10, 1, 0, null);
        roll.join(5000);
        if (host.failure != null) throw new AssertionError(host.failure);
        t.waitForText(ctx, "MAIN MENU", 10, "as400");
        screen = (String) t.getScreen(ctx, "as400").get("text");
        assertFalse(screen.contains("Option not allowed"), "the error row was put back");

        // Unlocked by the host but no read pending: a key is refused rather than sent.
        assertEquals("TERMINAL.KEYBOARD_LOCKED", assertThrows(ActionException.class, () -> t.sendKey(ctx, "Enter", "as400", false, 2, 1, 0, null)).code());
        assertEquals("TERMINAL.KEY_NOT_ON_5250", assertThrows(ActionException.class, () -> t.sendKey(ctx, "PA1", "as400")).code());
        t.disconnect(ctx, "as400");
    }

    @Test void keysModelsAndCodePages() {
        assertEquals("PF(3)", Tn5250Session.keyAction("F3"));
        assertEquals("PF(24)", Tn5250Session.keyAction("pf24"));
        assertEquals("PageDown", Tn5250Session.keyAction("Roll Up"));
        assertEquals("FieldExit", Tn5250Session.keyAction("FieldExit"));
        assertEquals("TERMINAL.KEY_NOT_ON_5250", assertThrows(ActionException.class, () -> Tn5250Session.keyAction("Ctrl+C")).code());
        assertEquals("TERMINAL.BAD_KEY", assertThrows(ActionException.class, () -> Tn5250Session.keyAction("Banana")).code());
        assertEquals("IBM-3179-2", Tn5250Session.termType("3279-2"));
        assertEquals("IBM-3477-FC", Tn5250Session.termType("3477-fc"));
        assertEquals("TERMINAL.BAD_MODEL", assertThrows(ActionException.class, () -> Tn5250Session.termType("vt100")).code());
        assertEquals("IBM273", Tn5250Session.charset("cp273").name());
        assertEquals("IBM037", Tn5250Session.charset(null).name());
        assertEquals("TERMINAL.BAD_CODE_PAGE", assertThrows(ActionException.class, () -> Tn5250Session.charset("utf-8")).code());
    }

    @Test void refusedConnectionIsReported() throws Exception {
        int port;
        try (ServerSocket s = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) { port = s.getLocalPort(); }
        var t = new TerminalPackage();
        var ctx = ctx();
        ActionException e = assertThrows(ActionException.class, () -> t.connect(ctx, "127.0.0.1", port, false, null, "as400", "tn5250", 5,
                null, null, true, null));
        assertEquals("TERMINAL.CONNECT_FAILED", e.code());
        assertFalse(ctx.sessions().containsKey("as400"));
    }
}
