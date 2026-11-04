package com.oneofficeautomation.packages.transfer;

import com.oneofficeautomation.sdk.ActionContext;
import com.oneofficeautomation.sdk.ActionException;
import com.oneofficeautomation.sdk.SecureString;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.impl.DefaultFtpServer;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.apache.sshd.common.file.virtualfs.VirtualFileSystemFactory;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;
import org.apache.sshd.sftp.server.SftpSubsystemFactory;
import org.junit.jupiter.api.Test;

import java.math.BigDecimal;
import java.nio.file.*;
import java.nio.file.attribute.FileTime;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneOffset;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** send_command and sync_folder: logic over the in-memory remote, then end to end against local FTP and SFTP servers. */
class FileTransferSyncTest {
    private final FileTransferPackage p = new FileTransferPackage();
    private static final SecureString PASS = new SecureString("secret", "vault:ftp");

    private static String code(Runnable r) { return assertThrows(ActionException.class, r::run).code(); }

    private static ActionContext ctx(Path w) {
        return new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> {});
    }

    private Map<String, Object> sync(ActionContext ctx, String dir, String local, String remote, String pattern, boolean sub, String compare, boolean deleteExtra, boolean dryRun) {
        return p.syncFolder(ctx, dir, local, remote, "transfer", pattern, sub, compare, deleteExtra, dryRun);
    }

    /** "uploaded downloaded deleted skipped" as one string, for short assertions. */
    private static String counts(Map<String, Object> r) {
        return r.get("uploaded") + " " + r.get("downloaded") + " " + r.get("deleted") + " " + r.get("skipped");
    }

    private static Map<String, String> actions(Map<String, Object> r) {
        Map<String, String> m = new TreeMap<>();
        for (Object o : (List<?>) r.get("details")) { Map<?, ?> d = (Map<?, ?>) o; m.put(String.valueOf(d.get("path")), String.valueOf(d.get("action"))); }
        return m;
    }

    private static void write(Path f, String text, Instant modified) throws Exception {
        Files.createDirectories(f.getParent());
        Files.writeString(f, text);
        Files.setLastModifiedTime(f, FileTime.from(modified));
    }

    // ---------- send_command ----------

    @Test void sendCommandOnFtp() throws Exception {
        try (FileTransferConnectRetryTest.FakeFtp ftp = new FileTransferConnectRetryTest.FakeFtp(0)) {
            ActionContext ctx = ctx(Files.createTempDirectory("ooa-ftp-cmd"));
            p.connect(ctx, "ftp", "127.0.0.1", ftp.port(), "bob", PASS, null, null, null, "transfer", true, false, "binary", 5);

            Map<String, Object> feat = p.sendCommand(ctx, "FEAT", "transfer");
            assertEquals(new BigDecimal(211), feat.get("reply_code"));
            assertTrue(String.valueOf(feat.get("reply_text")).contains("MDTM"), "all reply lines: " + feat.get("reply_text"));
            assertEquals(true, feat.get("ok"));
            Map<String, Object> syst = p.sendCommand(ctx, "syst", "transfer");
            assertEquals(new BigDecimal(215), syst.get("reply_code"));
            assertEquals("215 UNIX Type: L8", syst.get("reply_text"));
            Map<String, Object> chmod = p.sendCommand(ctx, "SITE CHMOD 644 file.txt", "transfer");
            assertEquals(new BigDecimal(200), chmod.get("reply_code"));
            assertEquals(true, chmod.get("ok"));
            assertTrue(ftp.commands.contains("SITE CHMOD 644 file.txt"), ftp.commands.toString());
            Map<String, Object> bad = p.sendCommand(ctx, "SITE UTIME x", "transfer");
            assertEquals(new BigDecimal(504), bad.get("reply_code"));
            assertEquals(false, bad.get("ok"), "a refusal by the server is a reply, not an error");

            int sent = ftp.commands.size();
            for (String refused : List.of("USER eve", "pass x", "QUIT", "PORT 127,0,0,1,4,1", "PASV", "EPSV", "PROT C", "AUTH TLS", "REIN", "CCC", "LIST", "RETR a.txt", "STOR a.txt"))
                assertEquals("TRANSFER.BAD_COMMAND", code(() -> p.sendCommand(ctx, refused, "transfer")), refused);
            for (String malformed : List.of("", "  ", "FEAT\r\nDELE a.txt", "X1", "123"))
                assertEquals("TRANSFER.BAD_COMMAND", code(() -> p.sendCommand(ctx, malformed, "transfer")), malformed);
            assertEquals(sent, ftp.commands.size(), "refused commands never reach the server");
            p.disconnect(ctx, "transfer");
        }
    }

    // ---------- sync_folder logic, over the in-memory remote ----------

    @Test void syncOptionsOverMemoryRemote() throws Exception {
        Path w = Files.createTempDirectory("ooa-sync");
        Instant t0 = Instant.now().minusSeconds(3600);
        write(w.resolve("out/a.csv"), "a", t0);
        write(w.resolve("out/b.txt"), "bb", t0);
        write(w.resolve("out/sub/c.csv"), "ccc", t0);
        FileTransferFoldersTest.MemoryConn conn = new FileTransferFoldersTest.MemoryConn();
        ActionContext ctx = FileTransferFoldersTest.ctx(w, conn);

        // dry run to a folder that does not exist yet: reports, changes nothing
        Map<String, Object> dry = sync(ctx, "upload", "out", "/r", "*", true, "newer", false, true);
        assertEquals("3 0 0 0", counts(dry));
        assertEquals(Map.of("a.csv", "upload", "b.txt", "upload", "sub/c.csv", "upload"), actions(dry));
        assertTrue(conn.files.isEmpty());
        assertFalse(conn.dirs.contains("/r"));

        assertEquals("3 0 0 0", counts(sync(ctx, "upload", "out", "/r", "*", true, "newer", false, false)));
        assertArrayEquals("ccc".getBytes(), conn.files.get("/r/sub/c.csv"), "sub-folders created");
        assertEquals("0 0 0 3", counts(sync(ctx, "upload", "out", "/r", "*", true, "newer", false, false)), "nothing changed");

        // newer: only the changed local file goes up
        write(w.resolve("out/a.csv"), "a2", Instant.now().plusSeconds(3600));
        Map<String, Object> r = sync(ctx, "upload", "out", "/r", "*", true, "newer", false, false);
        assertEquals("1 0 0 2", counts(r));
        assertEquals("upload", actions(r).get("a.csv"));
        assertEquals("a2", new String(conn.files.get("/r/a.csv")));

        // size: a remote copy that is newer but a different size is replaced only when comparing by size
        conn.files.put("/r/b.txt", "xyz".getBytes());
        conn.times.put("/r/b.txt", LocalDateTime.now(ZoneOffset.UTC).plusHours(5));
        assertEquals("0 0 0 1", counts(sync(ctx, "upload", "out", "/r", "b.txt", true, "newer", false, false)));
        assertEquals("1 0 0 0", counts(sync(ctx, "upload", "out", "/r", "b.txt", true, "size", false, false)));
        assertEquals("bb", new String(conn.files.get("/r/b.txt")));
        conn.files.put("/r/b.txt", "xy".getBytes());   // same size, remote newer: both = size or time, neither says copy
        conn.times.put("/r/b.txt", LocalDateTime.now(ZoneOffset.UTC).plusHours(5));
        assertEquals("0 0 0 1", counts(sync(ctx, "upload", "out", "/r", "b.txt", true, "both", false, false)));
        conn.times.put("/r/b.txt", LocalDateTime.of(2000, 1, 1, 0, 0));
        assertEquals("1 0 0 0", counts(sync(ctx, "upload", "out", "/r", "b.txt", true, "both", false, false)), "same size but local newer");

        // pattern and sub-folders
        Map<String, Object> csvTop = sync(ctx, "upload", "out", "/r", "*.csv", false, "size", false, true);
        assertEquals(Set.of("a.csv"), actions(csvTop).keySet());

        // delete_extra on upload: only files matching the pattern; sub-folder files too
        conn.files.put("/r/extra.csv", "e".getBytes());
        conn.files.put("/r/sub/old.txt", "o".getBytes());
        Map<String, Object> del = sync(ctx, "upload", "out", "/r", "*.csv", true, "newer", true, false);
        assertEquals("0 0 1 2", counts(del));
        assertEquals("delete_remote", actions(del).get("extra.csv"));
        assertFalse(conn.files.containsKey("/r/extra.csv"));
        assertTrue(conn.files.containsKey("/r/sub/old.txt"), "outside the pattern");
        assertEquals("0 0 1 3", counts(sync(ctx, "upload", "out", "/r", "*", true, "newer", true, true)), "dry run");
        assertTrue(conn.files.containsKey("/r/sub/old.txt"));
        assertEquals("0 0 1 3", counts(sync(ctx, "upload", "out", "/r", "*", true, "newer", true, false)));
        assertFalse(conn.files.containsKey("/r/sub/old.txt"));
        assertTrue(conn.dirs.contains("/r/sub"), "folders are left in place");

        // download into a new folder; local times follow the server's, so a second run copies nothing
        Map<String, Object> down = sync(ctx, "download", "in", "/r", "*", true, "newer", false, false);
        assertEquals("0 3 0 0", counts(down));
        assertEquals("ccc", Files.readString(w.resolve("in/sub/c.csv")));
        assertEquals(conn.times.get("/r/sub/c.csv").toInstant(ZoneOffset.UTC).getEpochSecond(), Files.getLastModifiedTime(w.resolve("in/sub/c.csv")).toInstant().getEpochSecond());
        assertEquals("0 0 0 3", counts(sync(ctx, "download", "in", "/r", "*", true, "newer", false, false)));
        assertEquals("0 0 0 2", counts(sync(ctx, "download", "in", "/r", "*", false, "size", false, false)), "top folder only");

        // delete_extra on download, with a dry run first
        write(w.resolve("in/stray.txt"), "s", Instant.now());
        Map<String, Object> dd = sync(ctx, "download", "in", "/r", "*", true, "newer", true, true);
        assertEquals("0 0 1 3", counts(dd));
        assertEquals("delete_local", actions(dd).get("stray.txt"));
        assertTrue(Files.exists(w.resolve("in/stray.txt")));
        sync(ctx, "download", "in", "/r", "*", true, "newer", true, false);
        assertFalse(Files.exists(w.resolve("in/stray.txt")));
        // without delete_extra a target-only file is left alone and not reported
        write(w.resolve("in/keep.txt"), "k", Instant.now());
        assertFalse(actions(sync(ctx, "download", "in", "/r", "*", true, "newer", false, false)).containsKey("keep.txt"));

        // both ways: new files each side, the newer copy wins, equal times with different sizes are a conflict
        Path both = Files.createDirectories(w.resolve("both"));
        conn.dirs.add("/b");
        write(both.resolve("local-only.csv"), "l", t0);
        conn.files.put("/b/remote-only.csv", "r".getBytes()); conn.times.put("/b/remote-only.csv", LocalDateTime.ofInstant(t0, ZoneOffset.UTC));
        write(both.resolve("server-newer.csv"), "old", t0);
        conn.files.put("/b/server-newer.csv", "new".getBytes()); conn.times.put("/b/server-newer.csv", LocalDateTime.ofInstant(t0.plusSeconds(600), ZoneOffset.UTC));
        write(both.resolve("local-newer.csv"), "new", t0.plusSeconds(600));
        conn.files.put("/b/local-newer.csv", "old".getBytes()); conn.times.put("/b/local-newer.csv", LocalDateTime.ofInstant(t0, ZoneOffset.UTC));
        write(both.resolve("clash.csv"), "short", t0);
        conn.files.put("/b/clash.csv", "much longer".getBytes()); conn.times.put("/b/clash.csv", LocalDateTime.ofInstant(t0.plusSeconds(1), ZoneOffset.UTC));
        Map<String, Object> b = sync(ctx, "both", "both", "/b", "*", true, "both", false, false);
        assertEquals(Map.of("local-only.csv", "upload", "remote-only.csv", "download", "server-newer.csv", "download", "local-newer.csv", "upload", "clash.csv", "conflict"), actions(b));
        assertEquals("2 2 0 1", counts(b));
        assertEquals("new", Files.readString(both.resolve("server-newer.csv")));
        assertEquals("new", new String(conn.files.get("/b/local-newer.csv")));
        assertEquals("short", Files.readString(both.resolve("clash.csv")), "a conflict changes neither side");
        assertEquals("much longer", new String(conn.files.get("/b/clash.csv")));

        // options checked before anything is touched
        assertEquals("TRANSFER.BAD_OPTION", code(() -> sync(ctx, "both", "both", "/b", "*", true, "newer", true, false)));
        assertEquals("TRANSFER.BAD_OPTION", code(() -> sync(ctx, "sideways", "out", "/r", "*", true, "newer", false, false)));
        assertEquals("TRANSFER.BAD_OPTION", code(() -> sync(ctx, "upload", "out", "/r", "*", true, "hash", false, false)));
        assertEquals("FILE.NOT_FOUND", code(() -> sync(ctx, "upload", "missing", "/r", "*", true, "newer", false, false)));
        assertEquals("TRANSFER.FAILED", code(() -> sync(ctx, "download", "in", "/missing", "*", true, "newer", true, false)),
                "a missing remote folder is an error, never an empty folder whose absence deletes local files");
        assertTrue(Files.exists(w.resolve("in/a.csv")));
    }

    // ---------- end to end: SFTP ----------

    @Test void sftpSyncAndNoRawCommands() throws Exception {
        Path root = Files.createTempDirectory("ooa-sftp-root");
        SshServer sshd = SshServer.setUpDefaultServer();
        sshd.setHost("127.0.0.1");
        sshd.setPort(0);
        sshd.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
        sshd.setPasswordAuthenticator((u, pw, s) -> u.equals("bob") && pw.equals("secret"));
        sshd.setSubsystemFactories(List.of(new SftpSubsystemFactory()));
        sshd.setFileSystemFactory(new VirtualFileSystemFactory(root));
        sshd.start();
        try {
            Path w = Files.createTempDirectory("ooa-sftp-sync");
            ActionContext ctx = ctx(w);
            p.connect(ctx, "sftp", "127.0.0.1", sshd.getPort(), "bob", PASS, null, null, null, "transfer", true, false, "binary", 10);
            assertEquals("TRANSFER.NOT_SUPPORTED", code(() -> p.sendCommand(ctx, "FEAT", "transfer")));
            assertEquals("TRANSFER.BAD_COMMAND", code(() -> p.sendCommand(ctx, "QUIT", "transfer")), "the command is checked first");
            endToEnd(ctx, w, root);
            p.disconnect(ctx, "transfer");
        } finally { sshd.stop(true); }
    }

    // ---------- end to end: FTP ----------

    @Test void ftpSync() throws Exception {
        Path root = Files.createTempDirectory("ooa-ftp-root");
        Path users = Files.createTempFile("ooa-ftp-users", ".properties");
        FtpServerFactory sf = new FtpServerFactory();
        ListenerFactory lf = new ListenerFactory();
        lf.setServerAddress("127.0.0.1");
        lf.setPort(0);
        DataConnectionConfigurationFactory dc = new DataConnectionConfigurationFactory();
        dc.setPassiveAddress("127.0.0.1");
        lf.setDataConnectionConfiguration(dc.createDataConnectionConfiguration());
        sf.addListener("default", lf.createListener());
        PropertiesUserManagerFactory umf = new PropertiesUserManagerFactory();
        umf.setFile(users.toFile());
        umf.setPasswordEncryptor(new ClearTextPasswordEncryptor());
        var um = umf.createUserManager();
        BaseUser u = new BaseUser();
        u.setName("bob");
        u.setPassword("secret");
        u.setHomeDirectory(root.toString());
        u.setAuthorities(List.of(new WritePermission()));
        um.save(u);
        sf.setUserManager(um);
        FtpServer server = sf.createServer();
        server.start();
        try {
            int port = ((DefaultFtpServer) server).getListener("default").getPort();
            Path w = Files.createTempDirectory("ooa-ftp-sync");
            ActionContext ctx = ctx(w);
            p.connect(ctx, "ftp", "127.0.0.1", port, "bob", PASS, null, null, null, "transfer", true, false, "binary", 10);
            Map<String, Object> syst = p.sendCommand(ctx, "SYST", "transfer");
            assertEquals(true, syst.get("ok"), String.valueOf(syst.get("reply_text")));
            endToEnd(ctx, w, root);
            p.disconnect(ctx, "transfer");
        } finally { server.stop(); }
    }

    /** The same round trip on a real server: up, steady, change, down, steady, both, delete_extra. */
    private void endToEnd(ActionContext ctx, Path w, Path serverRoot) throws Exception {
        Instant t0 = Instant.now().minusSeconds(7200).truncatedTo(java.time.temporal.ChronoUnit.SECONDS);
        write(w.resolve("out/a.csv"), "a", t0);
        write(w.resolve("out/sub/b.csv"), "bb", t0);
        write(w.resolve("out/skip.tmp"), "x", t0);

        assertEquals("2 0 0 0", counts(sync(ctx, "upload", "out", "/data", "*.csv", true, "newer", false, true)), "dry run");
        assertFalse(Files.exists(serverRoot.resolve("data")));
        assertEquals("2 0 0 0", counts(sync(ctx, "upload", "out", "/data", "*.csv", true, "newer", false, false)));
        assertEquals("bb", Files.readString(serverRoot.resolve("data/sub/b.csv")));
        assertFalse(Files.exists(serverRoot.resolve("data/skip.tmp")));
        assertEquals("0 0 0 2", counts(sync(ctx, "upload", "out", "/data", "*.csv", true, "newer", false, false)), "steady after an upload");
        assertEquals("0 0 0 2", counts(sync(ctx, "upload", "out", "/data", "*.csv", true, "size", false, false)));

        write(w.resolve("out/a.csv"), "a changed", t0.plusSeconds(3600));
        assertEquals("1 0 0 1", counts(sync(ctx, "upload", "out", "/data", "*.csv", true, "both", false, false)));
        assertEquals("a changed", Files.readString(serverRoot.resolve("data/a.csv")));

        assertEquals("0 2 0 0", counts(sync(ctx, "download", "in", "/data", "*", true, "newer", false, false)));
        assertEquals("a changed", Files.readString(w.resolve("in/a.csv")));
        assertEquals("0 0 0 2", counts(sync(ctx, "download", "in", "/data", "*", true, "newer", false, false)), "steady after a download");
        assertEquals("0 0 0 1", counts(sync(ctx, "download", "in", "/data", "*", false, "newer", false, false)), "top folder only");

        // both ways: a new file on each side
        write(w.resolve("in/local.csv"), "l", Instant.now());
        Files.writeString(serverRoot.resolve("data/sub/server.csv"), "s");
        Map<String, Object> b = sync(ctx, "both", "in", "/data", "*", true, "newer", false, false);
        assertEquals("1 1 0 2", counts(b), String.valueOf(b.get("details")));
        assertEquals("l", Files.readString(serverRoot.resolve("data/local.csv")));
        assertEquals("s", Files.readString(w.resolve("in/sub/server.csv")));
        assertEquals("0 0 0 4", counts(sync(ctx, "both", "in", "/data", "*", true, "newer", false, false)), "steady both ways");

        // mirror the local folder back up: files gone locally are deleted on the server
        Files.delete(w.resolve("in/sub/server.csv"));
        Map<String, Object> m = sync(ctx, "upload", "in", "/data", "*", true, "newer", true, false);
        assertEquals("0 0 1 3", counts(m));
        assertEquals("delete_remote", actions(m).get("sub/server.csv"));
        assertFalse(Files.exists(serverRoot.resolve("data/sub/server.csv")));
    }
}
