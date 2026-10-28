package com.oneofficeautomation.packages;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.apache.sshd.common.config.keys.KeyUtils;
import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.util.*;

import static com.oneofficeautomation.packages.EnterpriseGapsTest.rows;
import static org.junit.jupiter.api.Assertions.*;

/** VT100 hosts over SSH, against an in-process SSH server whose shell draws a small sign-in prompt and form. */
class TerminalVtTest {
    static SshServer sshd;
    static KeyPair clientKey;
    static final List<FakeHost> hosts = Collections.synchronizedList(new ArrayList<>());
    static final SecureString PW = new SecureString("s3cret", "test");

    @BeforeAll static void start() throws Exception {
        KeyPairGenerator g = KeyPairGenerator.getInstance("RSA");
        g.initialize(2048);
        clientKey = g.generateKeyPair();
        sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        sshd.setPasswordAuthenticator((user, pw, s) -> user.equals("ada") && pw.equals("s3cret"));
        sshd.setPublickeyAuthenticator((user, key, s) -> user.equals("ada") && KeyUtils.compareKeys(key, clientKey.getPublic()));
        sshd.setShellFactory(ch -> { FakeHost h = new FakeHost(); hosts.add(h); return h; });
        sshd.start();
    }

    @AfterAll static void stop() throws IOException { sshd.stop(true); }

    static ActionContext ctx() throws IOException { return PackagesTestSupport.ctx(Files.createTempDirectory("ooa-vt"), Map.of()); }

    static Map<String, Object> connect(TerminalPackage t, ActionContext ctx, Object password, String keyFile) {
        return t.connect(ctx, "127.0.0.1", sshd.getPort(), false, null, "terminal", "vt-ssh", 10, null, null, true, "Name:",
                "ada", password, keyFile, null, null, "vt100", 24, 80);
    }

    /**
     * The host's shell: a "Name:" prompt, then a form (MAIN MENU, User, Code) whose cursor starts at the left of the
     * Code row and is moved with arrow keys, then a "> " command line. It echoes what it is sent, like a real host.
     */
    static final class FakeHost implements Command, Runnable {
        InputStream in; OutputStream out; ExitCallback exit; Thread thread;
        final ByteArrayOutputStream received = new ByteArrayOutputStream();

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { }
        @Override public void setExitCallback(ExitCallback callback) { exit = callback; }
        @Override public void start(ChannelSession channel, Environment env) {
            thread = new Thread(this, "fake-vt-host");
            thread.setDaemon(true);
            thread.start();
        }
        @Override public void destroy(ChannelSession channel) { thread.interrupt(); }

        void write(String s) throws IOException { out.write(s.getBytes(StandardCharsets.UTF_8)); out.flush(); }

        int read() throws IOException {
            int c = in.read();
            if (c < 0) throw new EOFException();
            synchronized (received) { received.write(c); }
            return c;
        }

        /** Reads keys until Enter, echoing letters and applying arrow keys and Delete to the screen. */
        String line() throws IOException {
            StringBuilder b = new StringBuilder();
            for (int c; (c = read()) != '\r'; ) {
                if (c == 27 && read() == '[') {
                    int k = read();
                    if (k == 'C') write("\033[C");
                    else if (k == 'D') write("\033[D");
                    else if (k == '3' && read() == '~') { write("\033[P"); if (b.length() > 0) b.setLength(b.length() - 1); }
                } else if (c == 3) write("^C");
                else if (c >= 32 && c != 127) { b.append((char) c); write(String.valueOf((char) c)); }
            }
            return b.toString();
        }

        @Override public void run() {
            try {
                write("\033[2J\033[HOOA TEST HOST\r\nName: ");
                String name = line();
                write("\r\n\033[2J\033[1;1HMAIN MENU\033[3;1HUser: " + name + "\033[5;1HCode:\033[5;1H");
                String code = line();
                write("\033[7;1HResult: " + code + "\033[9;1H> ");
                for (String cmd; !(cmd = line()).equals("exit"); ) write("\r\nYou typed: " + cmd + "\r\n> ");
                write("\r\nbye\r\n");
                exit.onExit(0);
            } catch (IOException e) {
                exit.onExit(1);
            }
        }

        String received() { synchronized (received) { return received.toString(StandardCharsets.UTF_8); } }
    }

    @Test void signsInReadsAndTypes() throws Exception {
        var t = new TerminalPackage();
        var ctx = ctx();
        hosts.clear();
        connect(t, ctx, PW, null);
        FakeHost host = hosts.get(0);

        assertTrue(((String) t.getScreen(ctx, "terminal").get("text")).startsWith("OOA TEST HOST"));
        assertEquals("OOA TEST HOST", t.getText(ctx, 1, 1, 13, "terminal").get("text"));
        Map<String, Object> cur = t.getCursor(ctx, "terminal");
        assertEquals(List.of(new BigDecimal(2), new BigDecimal(7)), List.of(cur.get("row"), cur.get("column")));

        // The prompt's input field starts where the cursor already is, so nothing but the name is sent.
        t.setField(ctx, "Ada", null, null, "Name", "terminal", null, "Enter", 10);
        t.waitForText(ctx, "MAIN MENU", 10, "terminal");
        assertEquals("Ada", t.getField(ctx, null, null, "User", "terminal").get("text"));

        // Code: the cursor waits at column 1, so filling the field moves it 6 places right with arrow keys.
        t.setField(ctx, new SecureString("X42", "t"), null, null, "Code", "terminal", null, "Enter", 10);
        t.waitForText(ctx, "Result: X42", 10, "terminal");
        assertTrue(host.received().contains("Ada\r" + "\033[C".repeat(6) + "X42\r"), host.received());
        assertEquals("X42", t.getText(ctx, 7, 9, 3, "terminal").get("text"));

        Map<String, Object> all = t.getAllFields(ctx, true, "terminal");
        assertEquals(new BigDecimal(3), all.get("count"), "User, Code and Result take input");
        assertEquals(List.of(new BigDecimal(3), new BigDecimal(3), new BigDecimal(7)), rows(all.get("table")).get(0).subList(0, 3));

        t.sendText(ctx, "hello", null, null, false, "terminal", "Enter", 10, 0, "You typed: hello");
        Map<String, Object> hit = t.searchField(ctx, "You typed", 1, true, "terminal");
        assertEquals(true, hit.get("found"));
        assertEquals(new BigDecimal(10), hit.get("row"));

        t.sendKey(ctx, "Ctrl+C", "terminal", true, 10, 1, 0, "^C");
        assertEquals("TERMINAL.KEY_NOT_ON_VT", assertThrows(ActionException.class, () -> t.sendKey(ctx, "PA1", "terminal")).code());
        assertEquals("TERMINAL.NOT_ON_VT", assertThrows(ActionException.class, () -> t.clearScreen(ctx, false, "terminal")).code());
        t.setKeyMapping(ctx, Map.of("Quit", "Escape"), "terminal");

        t.sendText(ctx, "exit", null, null, false, "terminal", "Enter", 10, 0, "bye");
        t.disconnect(ctx, "terminal");
    }

    @Test void wrongPasswordIsRefused() throws Exception {
        var t = new TerminalPackage();
        var ctx = ctx();
        ActionException e = assertThrows(ActionException.class, () -> connect(t, ctx, new SecureString("wrong", "t"), null));
        assertEquals("TERMINAL.AUTH_FAILED", e.code());
        assertFalse(ctx.sessions().containsKey("terminal"));
        assertEquals("TERMINAL.NO_SIGN_IN", assertThrows(ActionException.class, () -> connect(t, ctx, null, null)).code());
        assertEquals("TERMINAL.NO_USER", assertThrows(ActionException.class, () -> t.connect(ctx, "127.0.0.1", sshd.getPort(), false, null, "terminal", "vt-ssh", 10,
                null, null, true, null, null, PW, null, null, null, "vt100", 24, 80)).code());
        assertEquals("TERMINAL.UNSUPPORTED_TYPE", assertThrows(ActionException.class, () -> t.connect(ctx, "127.0.0.1", 23, false, null, "terminal", "vt-telnet", 10,
                null, null, true, null)).code());
    }

    @Test void signsInWithKeyFile() throws Exception {
        var t = new TerminalPackage();
        var ctx = ctx();
        Path key = Files.createTempFile("ooa-vt", ".pem");
        String pem = "-----BEGIN PRIVATE KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(clientKey.getPrivate().getEncoded()) + "\n-----END PRIVATE KEY-----\n";
        Files.writeString(key, pem);
        connect(t, ctx, null, key.toString());
        assertEquals("Name:", t.getText(ctx, 2, 1, 5, "terminal").get("text"));
        t.disconnect(ctx, "terminal");
        assertEquals("TERMINAL.KEY_FILE_NOT_FOUND", assertThrows(ActionException.class, () -> connect(t, ctx, null, key + ".missing")).code());
    }

    @Test void screenFollowsControlSequences() {
        VtScreen s = new VtScreen(4, 10);
        s.feed("abcdefghijKL");                                  // wraps onto row 2
        assertEquals(List.of("abcdefghij", "KL        ", "          ", "          "), s.lines());
        s.feed("\033[1;3H\033[2P");                               // delete two characters
        assertEquals("abefghij  ", s.lines().get(0));
        s.feed("\033[2@XY");                                      // insert two blanks, then type over them
        assertEquals("abXYefghij", s.lines().get(0));
        s.feed("\033[2;1H\033[K\033[4;1Hbottom\r\n");              // erase a row; a line feed on the last row scrolls
        assertEquals(List.of("          ", "          ", "bottom    ", "          "), s.lines());
        s.feed("\033[?1049h\033[Hfull screen\033[?1049l");         // the alternate screen leaves the main one untouched
        assertEquals("bottom    ", s.lines().get(2));
        s.feed("\033[3;5H\033[6n");
        assertEquals("\033[3;5R", s.takeReplies());
        s.feed("\033]0;window title\007\033[1;31mred\033[0m");      // titles and colours do not reach the screen
        assertEquals("bottred   ", s.lines().get(2));
        s.feed("\033[2J");
        assertTrue(String.join("", s.lines()).isBlank());
    }

    @Test void fieldsComeFromLabels() {
        List<TerminalPackage.Field> f = TerminalPackage.vtFields(List.of(
                "Name: John Smith    Dept: Sales     ",
                "Last login: Sun 12:30:01            ",
                "MAIN MENU   F3=Exit                 "));
        assertEquals(List.of("Name:", "John Smith   ", "Dept:", "Sales     ", "Last login:", "Sun 12:30:01            ", "MAIN MENU", "F3=Exit"),
                f.stream().map(TerminalPackage.Field::text).toList());
        assertFalse(f.get(1).isProtected());
        assertEquals(7, f.get(1).column());
        assertTrue(f.get(6).isProtected());
    }
}
