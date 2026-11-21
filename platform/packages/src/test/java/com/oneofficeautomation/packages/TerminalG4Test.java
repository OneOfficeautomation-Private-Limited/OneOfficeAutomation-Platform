package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.*;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * G4 (AA parity, 22 Nov 2026), terminals: a VT host over Telnet (options, login and password prompts, the terminal
 * type it asks for, a custom device-attributes answer, an ANSI encoding), the last line, a rectangle and the lines that
 * scrolled past, auto-wrap off, the 5250 display models, and the window Show terminal opens.
 */
class TerminalG4Test {
    static final Charset ANSI = Charset.forName("windows-1252");

    /** A Telnet host: asks for the terminal type and size, signs the user in, asks what terminal it is, prints 30 lines. */
    static final class FakeTelnet implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0);
        final BlockingQueue<String> seen = new LinkedBlockingQueue<>();
        FakeTelnet() throws IOException {
            Thread t = new Thread(() -> { try (Socket s = server.accept()) { serve(s); } catch (IOException ignored) { } }, "fake-telnet");
            t.setDaemon(true);
            t.start();
        }
        int port() { return server.getLocalPort(); }

        void serve(Socket s) throws IOException {
            InputStream in = s.getInputStream();
            OutputStream out = s.getOutputStream();
            out.write(new byte[]{(byte) 255, (byte) 253, 24, (byte) 255, (byte) 253, 31, (byte) 255, (byte) 251, 1, (byte) 255, (byte) 250, 24, 1, (byte) 255, (byte) 240});
            out.flush();
            // the client's answers: WILL TTYPE, WILL NAWS + size, DO ECHO, the terminal type
            ByteArrayOutputStream neg = new ByteArrayOutputStream();
            long end = System.currentTimeMillis() + 3000;
            while (System.currentTimeMillis() < end && !new String(neg.toByteArray(), ANSI).contains("VT220")) {
                if (in.available() > 0) neg.write(in.read()); else try { Thread.sleep(20); } catch (InterruptedException e) { return; }
            }
            byte[] n = neg.toByteArray();
            seen.add("type " + (new String(n, ANSI).contains("VT220") ? "VT220" : "?"));
            for (int i = 0; i + 8 < n.length; i++) if ((n[i] & 255) == 255 && (n[i + 1] & 255) == 250 && n[i + 2] == 31) seen.add("size " + ((n[i + 4] & 255)) + "x" + (n[i + 6] & 255));
            out.write("\r\nlogin: ".getBytes(ANSI)); out.flush();
            seen.add("user " + line(in));
            out.write("\r\nPassword: ".getBytes(ANSI)); out.flush();
            seen.add("password " + line(in));
            out.write("\r\n\033[c".getBytes(ANSI)); out.flush();
            StringBuilder da = new StringBuilder();
            for (int c; (c = in.read()) != 'c'; ) da.append((char) c);
            seen.add("da " + da.append('c').toString().replace("\033", "ESC"));
            StringBuilder lines = new StringBuilder();
            for (int i = 1; i <= 30; i++) lines.append("line ").append(i).append(i == 30 ? " café" : "").append("\r\n");
            lines.append("$ ");
            out.write(lines.toString().getBytes(ANSI)); out.flush();
            try { Thread.sleep(60_000); } catch (InterruptedException ignored) { }
        }

        static String line(InputStream in) throws IOException {
            StringBuilder b = new StringBuilder();
            for (int c; (c = in.read()) != '\r' && c >= 0; ) if (c != '\n' && c < 128) b.append((char) c);   // left-over option bytes are not typed text
            return b.toString();
        }
        @Override public void close() throws IOException { server.close(); }
    }

    @Test void aVtHostOverTelnet() throws Exception {
        try (FakeTelnet host = new FakeTelnet()) {
            ActionContext ctx = PackagesTestSupport.ctx(Files.createTempDirectory("ooa-g4tn"), Map.of());
            TerminalPackage t = new TerminalPackage();
            t.connect(ctx, "127.0.0.1", host.port(), false, null, "term", "vt-telnet", 10, null, null, false, "$",
                    "ada", new SecureString("s3cret", "t"), null, null, null, "vt220", 24, 80, "ansi", true, "?62;1;6c");
            assertEquals("type VT220", host.seen.poll(5, TimeUnit.SECONDS));
            assertEquals("size 80x24", host.seen.poll(5, TimeUnit.SECONDS));
            assertEquals("user ada", host.seen.poll(5, TimeUnit.SECONDS));
            assertEquals("password s3cret", host.seen.poll(5, TimeUnit.SECONDS));
            assertEquals("da ESC[?62;1;6c", host.seen.poll(5, TimeUnit.SECONDS));

            assertEquals("$", t.getText(ctx, 1, 1, 80, "term", true, "last_line", 1, 80).get("text"));
            String back = String.valueOf(t.getText(ctx, 1, 1, 80, "term", true, "scroll_back", 1, 80).get("text"));
            assertTrue(back.contains("line 1\n") && back.contains("line 30 café"), back);
            List<Object> screen = (List<Object>) t.getScreen(ctx, "term").get("lines");
            int at = 0;
            for (int i = 0; i < screen.size(); i++) if (String.valueOf(screen.get(i)).startsWith("line 29")) at = i + 1;
            assertEquals("line 29\nline 30", t.getText(ctx, at, 1, 0, "term", true, "rectangle", 2, 7).get("text"));
            assertTrue(back.indexOf("line 1\n") < back.indexOf("line 30"), "oldest first");

            Map<String, Object> shown = t.showTerminal(ctx, "term");
            if (Boolean.TRUE.equals(shown.get("shown"))) {
                Thread.sleep(800);
                t.hideTerminal(ctx, "term");
            }
            t.disconnect(ctx, "term");
        }
    }

    @Test void autoWrapOffKeepsTheLastColumnAndTheModels() {
        VtScreen on = new VtScreen(5, 20), off = new VtScreen(5, 20);
        off.autoWrap = false;
        String text = "abcdefghijklmnopqrstuvwxyz";
        on.feed(text); off.feed(text);
        assertEquals("abcdefghijklmnopqrst", on.lines().get(0));
        assertEquals("uvwxyz", on.lines().get(1).strip(), "wrapped onto the next line");
        assertEquals("abcdefghijklmnopqrsz", off.lines().get(0), "kept overwriting the last column");
        assertTrue(off.lines().get(1).isBlank());
        assertEquals("IBM-3477-FG", Tn5250Session.termType("3477-FG"));
        assertEquals("IBM-5291-1", Tn5250Session.termType("5291-1"));
        assertEquals("TERMINAL.BAD_ENCODING", assertThrows(ActionException.class, () -> TerminalPackage.vtCharset("klingon")).code());
        assertEquals(ANSI, TerminalPackage.vtCharset("ansi"));
    }
}
