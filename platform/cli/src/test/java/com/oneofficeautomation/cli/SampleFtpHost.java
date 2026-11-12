package com.oneofficeautomation.cli;

import org.apache.ftpserver.DataConnectionConfigurationFactory;
import org.apache.ftpserver.FtpServer;
import org.apache.ftpserver.FtpServerFactory;
import org.apache.ftpserver.impl.DefaultFtpServer;
import org.apache.ftpserver.listener.ListenerFactory;
import org.apache.ftpserver.usermanager.ClearTextPasswordEncryptor;
import org.apache.ftpserver.usermanager.PropertiesUserManagerFactory;
import org.apache.ftpserver.usermanager.impl.BaseUser;
import org.apache.ftpserver.usermanager.impl.WritePermission;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

/**
 * The FTP server the file transfer samples talk to (S1, command review batch 8): in-process, user "agent" with the
 * samples' test password (test/mail/password), its own folder; started once, on first use. {ftp_port} in a sample is
 * its port; the samples use subfolders of their own.
 */
final class SampleFtpHost {
    private SampleFtpHost() {}
    private static FtpServer server;
    private static int port;

    static synchronized int port() throws Exception {
        if (server == null) {
            Path root = Files.createTempDirectory("ooa-sample-ftp");
            Path users = Files.createTempFile("ooa-sample-ftp-users", ".properties");
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
            u.setName("agent");
            u.setPassword(CommandSamplesTest.MAIL_PASSWORD);
            u.setHomeDirectory(root.toString());
            u.setAuthorities(List.of(new WritePermission()));
            um.save(u);
            sf.setUserManager(um);
            FtpServer s = sf.createServer();
            s.start();
            server = s;
            port = ((DefaultFtpServer) s).getListener("default").getPort();
        }
        return port;
    }
}
