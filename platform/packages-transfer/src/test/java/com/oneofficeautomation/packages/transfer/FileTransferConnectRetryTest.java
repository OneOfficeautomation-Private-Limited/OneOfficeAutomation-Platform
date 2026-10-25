package com.oneofficeautomation.packages.transfer;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.*;

/** connect: retry_attempts, retry_delay_seconds and start_folder, against a small local FTP server. */
class FileTransferConnectRetryTest {
    private final FileTransferPackage p = new FileTransferPackage();
    private static final SecureString PASS = new SecureString("secret", "vault:ftp");

    /** Answers the FTP commands connect uses; the first refuseFirst connections get "421 busy" and are closed. */
    static final class FakeFtp implements AutoCloseable {
        final ServerSocket server = new ServerSocket(0, 50, InetAddress.getLoopbackAddress());
        final AtomicInteger connections = new AtomicInteger();
        final List<String> commands = Collections.synchronizedList(new ArrayList<>());
        final int refuseFirst;

        FakeFtp(int refuseFirst) throws IOException {
            this.refuseFirst = refuseFirst;
            Thread t = new Thread(this::serve, "fake-ftp");
            t.setDaemon(true);
            t.start();
        }

        int port() { return server.getLocalPort(); }

        private void serve() {
            while (!server.isClosed()) {
                try (Socket s = server.accept()) {
                    int n = connections.incrementAndGet();
                    Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.US_ASCII);
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.US_ASCII));
                    if (n <= refuseFirst) { out.write("421 Too busy, try later\r\n"); out.flush(); continue; }
                    out.write("220 Fake FTP ready\r\n"); out.flush();
                    String cwd = "/", line;
                    while ((line = in.readLine()) != null) {
                        commands.add(line);
                        int sp = line.indexOf(' ');
                        String cmd = (sp < 0 ? line : line.substring(0, sp)).toUpperCase(Locale.ROOT), arg = sp < 0 ? "" : line.substring(sp + 1);
                        String reply = switch (cmd) {
                            case "USER" -> "331 Password please";
                            case "PASS" -> arg.equals("secret") ? "230 Signed in" : "530 Login incorrect";
                            case "TYPE" -> "200 Type set";
                            case "CWD" -> arg.equals("/in") || arg.equals(".") ? "250 Folder changed" : "550 No such folder";
                            case "PWD" -> "257 \"" + cwd + "\" is the current folder";
                            case "QUIT" -> "221 Bye";
                            default -> "502 Not implemented";
                        };
                        if (cmd.equals("CWD") && arg.equals("/in")) cwd = "/in";
                        out.write(reply + "\r\n"); out.flush();
                        if (cmd.equals("QUIT")) break;
                    }
                } catch (IOException e) { if (server.isClosed()) return; }
            }
        }

        @Override public void close() throws IOException { server.close(); }
    }

    private static ActionContext ctx() throws IOException {
        return new ActionContext("run", "s", "default", Files.createTempDirectory("ooa-ftp-retry"), new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    private static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    @Test void retriesUntilTheServerAnswersThenStartsInTheFolder() throws Exception {
        try (FakeFtp ftp = new FakeFtp(2)) {
            ActionContext ctx = ctx();
            p.connect(ctx, "ftp", "127.0.0.1", ftp.port(), "bob", PASS, null, null, null, "transfer", true, false, "binary", 5, 3, 0.05, "/in");
            assertEquals(3, ftp.connections.get(), "two refused tries, then a good one");
            assertTrue(ctx.sessions().get("transfer") instanceof FileTransferPackage.Conn);
            assertEquals("/in", p.changeFolder(ctx, ".", "transfer").get("folder"));
            p.disconnect(ctx, "transfer");
            List<String> sent = new ArrayList<>(ftp.commands);
            assertEquals("USER bob", sent.get(0));
            assertEquals("PASS secret", sent.get(1));
            assertTrue(sent.contains("TYPE I"), sent.toString());
            assertTrue(sent.contains("CWD /in"), sent.toString());
            assertTrue(sent.indexOf("CWD /in") > sent.indexOf("PASS secret"), "folder changed after signing in");
        }
    }

    @Test void givesUpAfterTheLastTry() throws Exception {
        try (FakeFtp ftp = new FakeFtp(10)) {
            ActionContext ctx = ctx();
            ActionException e = assertThrows(ActionException.class,
                    () -> p.connect(ctx, "ftp", "127.0.0.1", ftp.port(), "bob", PASS, null, null, null, "transfer", true, false, "binary", 5, 1, 0, null));
            assertEquals("TRANSFER.FAILED", e.code());
            assertTrue(e.getMessage().contains("after 2 tries"), e.getMessage());
            assertEquals(2, ftp.connections.get());
            assertNull(ctx.sessions().get("transfer"));
        }
    }

    @Test void oldFormTriesOnce() throws Exception {
        try (FakeFtp ftp = new FakeFtp(1)) {
            ActionContext ctx = ctx();
            assertEquals("TRANSFER.FAILED", code(() -> p.connect(ctx, "ftp", "127.0.0.1", ftp.port(), "bob", PASS, null, null, null, "transfer", true, false, "binary", 5)));
            assertEquals(1, ftp.connections.get());
        }
    }

    @Test void wrongPasswordAndMissingStartFolderAreNotRetried() throws Exception {
        try (FakeFtp ftp = new FakeFtp(0)) {
            ActionContext ctx = ctx();
            assertEquals("TRANSFER.AUTH_FAILED", code(() -> p.connect(ctx, "ftp", "127.0.0.1", ftp.port(), "bob", new SecureString("nope", "vault:x"),
                    null, null, null, "transfer", true, false, "binary", 5, 4, 0, null)));
            assertEquals(1, ftp.connections.get());
            ActionException e = assertThrows(ActionException.class, () -> p.connect(ctx, "ftp", "127.0.0.1", ftp.port(), "bob", PASS,
                    null, null, null, "transfer", true, false, "binary", 5, 4, 0, "/missing"));
            assertTrue(e.getMessage().contains("/missing"), e.getMessage());
            assertEquals(2, ftp.connections.get());
            assertNull(ctx.sessions().get("transfer"));
            assertTrue(ftp.commands.contains("CWD /missing"));
        }
    }

    @Test void badRetryOptionsStopBeforeConnecting() throws Exception {
        try (FakeFtp ftp = new FakeFtp(0)) {
            ActionContext ctx = ctx();
            assertEquals("TRANSFER.BAD_OPTION", code(() -> p.connect(ctx, "ftp", "127.0.0.1", ftp.port(), "bob", PASS, null, null, null, "transfer", true, false, "binary", 5, -1, 0, null)));
            assertEquals("TRANSFER.BAD_OPTION", code(() -> p.connect(ctx, "ftp", "127.0.0.1", ftp.port(), "bob", PASS, null, null, null, "transfer", true, false, "binary", 5, 1, -2, null)));
            assertEquals("TRANSFER.BAD_OPTION", code(() -> p.connect(ctx, "ftp", "127.0.0.1", ftp.port(), "bob", PASS, null, null, null, "transfer", true, false, "binary", 5, 99, 0, null)));
            assertEquals(0, ftp.connections.get());
        }
    }
}
