package com.oneofficeautomation.cli;

import org.apache.sshd.server.Environment;
import org.apache.sshd.server.ExitCallback;
import org.apache.sshd.server.SshServer;
import org.apache.sshd.server.channel.ChannelSession;
import org.apache.sshd.server.command.Command;
import org.apache.sshd.server.keyprovider.SimpleGeneratorHostKeyProvider;

import java.io.*;
import java.nio.charset.StandardCharsets;

/**
 * The host the terminal command samples talk to (T3, command review batch 6): an in-process SSH server, user
 * "agent" with the samples' test password (test/mail/password), whose shell draws a sign-in prompt, a small form
 * and a command line, as a VT100 host would. Started once, on first use; {ssh_port} in a sample is its port.
 */
final class SampleSshHost {
    private SampleSshHost() {}
    private static SshServer server;

    static synchronized int port() throws IOException {
        if (server == null) {
            SshServer s = SshServer.setUpDefaultServer();
            s.setHost("127.0.0.1");
            s.setPort(0);
            s.setKeyPairProvider(new SimpleGeneratorHostKeyProvider());
            s.setPasswordAuthenticator((user, pw, session) -> user.equals("agent") && pw.equals(CommandSamplesTest.MAIL_PASSWORD));
            s.setShellFactory(ch -> new Shell());
            s.start();
            server = s;
        }
        return server.getPort();
    }

    /**
     * "SAMPLE HOST" and a "Name:" prompt; then MAIN MENU with the name on row 3 and the cursor on the Code row (5);
     * then "Result: <code>" on row 7 and a "> " command line that answers "You typed: <command>" until "exit".
     */
    static final class Shell implements Command, Runnable {
        InputStream in; OutputStream out; ExitCallback exit; Thread thread;

        @Override public void setInputStream(InputStream in) { this.in = in; }
        @Override public void setOutputStream(OutputStream out) { this.out = out; }
        @Override public void setErrorStream(OutputStream err) { }
        @Override public void setExitCallback(ExitCallback callback) { exit = callback; }
        @Override public void start(ChannelSession channel, Environment env) {
            thread = new Thread(this, "sample-ssh-host");
            thread.setDaemon(true);
            thread.start();
        }
        @Override public void destroy(ChannelSession channel) { thread.interrupt(); }

        void write(String s) throws IOException { out.write(s.getBytes(StandardCharsets.UTF_8)); out.flush(); }

        /** Reads keys until Enter, echoing letters as a host does; arrow keys move the cursor. */
        String line() throws IOException {
            StringBuilder b = new StringBuilder();
            for (int c; (c = in.read()) != '\r'; ) {
                if (c < 0) throw new EOFException();
                if (c == 27 && in.read() == '[') {
                    int k = in.read();
                    if (k == 'C') write("\033[C"); else if (k == 'D') write("\033[D");
                } else if (c == 3) write("^C");
                else if (c >= 32 && c != 127) { b.append((char) c); write(String.valueOf((char) c)); }
            }
            return b.toString();
        }

        @Override public void run() {
            try {
                write("\033[2J\033[HSAMPLE HOST\r\nName: ");
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
    }
}
