package com.oneofficeautomation.controlroom;

import org.junit.jupiter.api.Test;

import java.io.*;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;

/** A mailbox read over POP3 (O10, 25 Nov 2026): new messages once each, their attachments, never changed. */
class Pop3MailSourceTest {
    static final String MSG1 = "From: Supplier <ap@supplier.example>\r\nSubject: Invoice 1\r\nMIME-Version: 1.0\r\n"
            + "Content-Type: multipart/mixed; boundary=\"b\"\r\n\r\n--b\r\nContent-Type: text/plain\r\n\r\nSee attached\r\n"
            + "--b\r\nContent-Type: application/pdf; name=\"inv-1.pdf\"\r\nContent-Disposition: attachment; filename=\"inv-1.pdf\"\r\n"
            + "Content-Transfer-Encoding: base64\r\n\r\nJVBERi0xLjQ=\r\n--b--\r\n";
    static final String MSG2 = "From: other@example.com\r\nSubject: Hello\r\n\r\n.just a line starting with a dot\r\n";

    /** A POP3 server with two messages; it records the commands it got. */
    static ServerSocket server(List<String> commands) throws IOException {
        ServerSocket ss = new ServerSocket(0);
        Thread t = new Thread(() -> {
            while (!ss.isClosed()) {
                try (Socket s = ss.accept()) {
                    BufferedReader in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
                    Writer out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
                    out.write("+OK POP3 ready\r\n"); out.flush();
                    String l;
                    while ((l = in.readLine()) != null) {
                        commands.add(l);
                        String[] c = l.split(" ");
                        switch (c[0]) {
                            case "USER" -> out.write("+OK\r\n");
                            case "PASS" -> out.write(c[1].equals("secret") ? "+OK signed in\r\n" : "-ERR wrong password\r\n");
                            case "UIDL" -> out.write("+OK\r\n1 uid-a\r\n2 uid-b\r\n.\r\n");
                            case "TOP" -> { String m = c[1].equals("1") ? MSG1 : MSG2; out.write("+OK\r\n" + m.substring(0, m.indexOf("\r\n\r\n") + 2) + ".\r\n"); }
                            case "RETR" -> { String m = c[1].equals("1") ? MSG1 : MSG2; out.write("+OK\r\n" + m.replace("\r\n.", "\r\n..") + ".\r\n"); }
                            case "QUIT" -> { out.write("+OK bye\r\n"); out.flush(); s.close(); }
                            default -> out.write("-ERR unknown\r\n");
                        }
                        if (!s.isClosed()) out.flush();
                    }
                } catch (IOException ignored) { }
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    @Test void readsNewMessagesOnceWithTheirAttachments() throws Exception {
        List<String> commands = Collections.synchronizedList(new ArrayList<>());
        try (ServerSocket ss = server(commands)) {
            Pop3MailSource pop = new Pop3MailSource("127.0.0.1", ss.getLocalPort(), false, "ap", "secret", 5000);
            MailSource.Batch first = pop.fetch(null, false);
            assertEquals("uid-b", first.cursor());
            assertTrue(first.messages().isEmpty(), "messages already there when the inbox is set up are not read");
            MailSource.Batch all = pop.fetch(null, true);
            assertEquals(List.of("Invoice 1", "Hello"), all.messages().stream().map(m -> m.get("subject")).toList());
            assertEquals(true, all.messages().get(0).get("has_attachments"));
            List<MailSource.Attachment> files = pop.attachments(all.messages().get(0));
            assertEquals(1, files.size());
            assertEquals("inv-1.pdf", files.get(0).name());
            assertEquals("%PDF-1.4", new String(files.get(0).data(), StandardCharsets.US_ASCII));
            assertTrue(pop.fetch("uid-b", true).messages().isEmpty(), "nothing new after the newest");
            assertEquals(1, pop.fetch("uid-a", true).messages().size());
            assertFalse(commands.stream().anyMatch(c -> c.startsWith("DELE")), "messages are never deleted");
            IOException wrong = assertThrows(IOException.class, () -> new Pop3MailSource("127.0.0.1", ss.getLocalPort(), false, "ap", "nope", 5000).fetch(null, true));
            assertTrue(wrong.getMessage().contains("Sign-in"), wrong.getMessage());
        }
    }
}
