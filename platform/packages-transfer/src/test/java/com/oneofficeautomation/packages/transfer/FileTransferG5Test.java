package com.oneofficeautomation.packages.transfer;

import com.oneofficeautomation.sdk.*;
import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.ftplet.Authority;
import org.apache.ftpserver.impl.DefaultFtpServer;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;
import org.junit.jupiter.api.Test;

import java.nio.file.*;
import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

import static org.junit.jupiter.api.Assertions.*;

/** G5 (AA parity, 22 Nov 2026), FTP on a real embedded server: anonymous sign-in, the parent folder, Get files with a pattern and dates, Put files, folders by date. */
class FileTransferG5Test {
    @SuppressWarnings("unchecked")
    @Test void anonymousParentGetAndPutFiles() throws Exception {
        Path root = Files.createTempDirectory("ooa-g5ftp-root");
        Files.createDirectories(root.resolve("out/old"));
        Files.writeString(root.resolve("out/a.csv"), "a");
        Files.writeString(root.resolve("out/b.csv"), "b");
        Files.writeString(root.resolve("out/c.txt"), "c");
        Path users = Files.createTempFile("ooa-g5ftp-users", ".properties");
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
        BaseUser anon = new BaseUser();
        anon.setName("anonymous");
        anon.setHomeDirectory(root.toString());
        anon.setAuthorities(List.<Authority>of(new WritePermission()));
        um.save(anon);
        sf.setUserManager(um);
        FtpServer server = sf.createServer();
        server.start();
        FileTransferPackage p = new FileTransferPackage();
        try {
            int port = ((DefaultFtpServer) server).getListener("default").getPort();
            Path w = Files.createTempDirectory("ooa-g5ftp");
            ActionContext ctx = new ActionContext("run", "s", "default", w, new HashMap<>(), new HashMap<>(), new AtomicBoolean(), List::of, (l, m) -> { });
            p.connect(ctx, "ftp", "127.0.0.1", port, null, null, null, null, null, "transfer", true, false, "binary", 10, 0, 5, "/out", true, null);
            assertEquals("TRANSFER.BAD_OPTION", assertThrows(ActionException.class,
                    () -> p.connect(ctx, "sftp", "127.0.0.1", port, null, null, null, null, null, "x", true, false, "binary", 10, 0, 5, null, true, null)).code());

            List<Object> got = (List<Object>) p.downloadFiles(ctx, ".", "*.csv", "in", false, null, null, "binary", "transfer").get("files");
            assertEquals(2, got.size());
            assertEquals("a", Files.readString(w.resolve("in/a.csv")));
            assertTrue(((List<Object>) p.downloadFiles(ctx, ".", "*.csv", "in", false, LocalDateTime.now().plusDays(1), null, null, "transfer").get("files")).isEmpty(), "nothing changed after tomorrow");

            Map<String, Object> folders = p.list(ctx, ".", "transfer", "*", "folders", LocalDateTime.now().minusDays(1), null);
            assertEquals(1, ((List<Object>) ((Map<String, Object>) folders.get("table")).get("rows")).size(), "the old folder, changed today");

            assertEquals("/", p.changeFolder(ctx, null, "transfer", true).get("folder"), "up to the parent");
            p.changeFolder(ctx, "out", "transfer", false);

            Files.createDirectories(w.resolve("up"));
            Files.writeString(w.resolve("up/x.dat"), "x");
            Files.writeString(w.resolve("up/y.dat"), "y");
            List<Object> put = (List<Object>) p.uploadFiles(ctx, null, "up", "*.dat", ".", false, null, "transfer").get("uploaded");
            assertEquals(List.of("x.dat", "y.dat"), put);
            assertEquals("x", Files.readString(root.resolve("out/x.dat")));
            assertTrue(((List<Object>) p.uploadFiles(ctx, List.of("up/x.dat"), null, null, ".", false, null, "transfer").get("uploaded")).isEmpty(), "kept, not overwritten");
        } finally { server.stop(); }
    }
}
