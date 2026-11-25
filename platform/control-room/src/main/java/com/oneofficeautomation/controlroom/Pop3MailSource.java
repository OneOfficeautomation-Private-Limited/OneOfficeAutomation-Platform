package com.oneofficeautomation.controlroom;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.*;

/**
 * A mailbox read over POP3 (RFC 1939, with UIDL): for mail servers that offer POP3 only. Messages are never deleted or
 * changed; the cursor is the newest message's unique id, so each message is read once. Headers come from TOP n 0, the
 * attachments from RETR, read with the IMAP reader's MIME parsing.
 */
public final class Pop3MailSource implements MailSource {
    private final String host, user, password;
    private final int port, timeoutMs;
    private final boolean ssl;

    public Pop3MailSource(String host, int port, boolean ssl, String user, String password, int timeoutMs) {
        this.host = host; this.port = port; this.ssl = ssl; this.user = user; this.password = password; this.timeoutMs = timeoutMs;
    }

    /** One POP3 session: commands and their answers (+OK / -ERR), multi-line answers until a line with a single dot. */
    static final class Session implements Closeable {
        final Socket socket; final BufferedInputStream in; final OutputStream out;

        Session(Socket socket) throws IOException { this.socket = socket; this.in = new BufferedInputStream(socket.getInputStream()); this.out = socket.getOutputStream(); }

        String line() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') b.write(c); }
            if (c == -1 && b.size() == 0) throw new EOFException("The mail server closed the connection");
            return b.toString(StandardCharsets.UTF_8);
        }

        String ok(String command, String what) throws IOException {
            if (command != null) { out.write((command + "\r\n").getBytes(StandardCharsets.UTF_8)); out.flush(); }
            String r = line();
            if (!r.startsWith("+OK")) throw new IOException(what + ": " + r);
            return r;
        }

        /** The lines of a multi-line answer, dot-unstuffed, as raw bytes joined with CRLF. */
        byte[] multi() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            while (true) {
                String l = line();
                if (l.equals(".")) break;
                if (l.startsWith("..")) l = l.substring(1);
                b.write(l.getBytes(StandardCharsets.UTF_8));
                b.write('\r'); b.write('\n');
            }
            return b.toByteArray();
        }

        @Override public void close() throws IOException {
            try { out.write("QUIT\r\n".getBytes(StandardCharsets.UTF_8)); out.flush(); } catch (IOException ignored) { }
            socket.close();
        }
    }

    Session open() throws IOException {
        SocketFactory f = ssl ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        Socket s = f.createSocket();
        s.connect(new InetSocketAddress(host, port), timeoutMs);
        s.setSoTimeout(timeoutMs);
        Session p = new Session(s);
        try {
            p.ok(null, "Not a POP3 server");
            p.ok("USER " + user, "Sign-in to the mailbox failed");
            p.ok("PASS " + password, "Sign-in to the mailbox failed");
            return p;
        } catch (IOException e) { s.close(); throw e; }
    }

    /** message number -> unique id, in the mailbox's order (oldest first). */
    static LinkedHashMap<Integer, String> uidl(Session p) throws IOException {
        p.ok("UIDL", "The mail server does not list message ids (UIDL)");
        LinkedHashMap<Integer, String> out = new LinkedHashMap<>();
        for (String l : new String(p.multi(), StandardCharsets.UTF_8).split("\r\n")) {
            String[] t = l.trim().split("\\s+");
            if (t.length == 2) out.put(Integer.parseInt(t[0]), t[1]);
        }
        return out;
    }

    @Override public Batch fetch(String cursor, boolean fireExisting) throws IOException {
        try (Session p = open()) {
            LinkedHashMap<Integer, String> ids = uidl(p);
            List<Map.Entry<Integer, String>> all = new ArrayList<>(ids.entrySet());
            String newest = all.isEmpty() ? (cursor == null ? "" : cursor) : all.get(all.size() - 1).getValue();
            int from;
            int at = -1;
            for (int i = 0; i < all.size(); i++) if (all.get(i).getValue().equals(cursor)) at = i;
            if (at >= 0) from = at + 1;
            else if (cursor == null || cursor.isEmpty()) {
                if (!fireExisting) return new Batch(newest, List.of());
                from = Math.max(0, all.size() - 5);
            } else from = Math.max(0, all.size() - ImapMailSource.MAX_PER_POLL);   // the newest seen is gone: the latest ones
            List<Map<String, Object>> out = new ArrayList<>();
            String last = at >= 0 ? cursor : (from > 0 ? all.get(from - 1).getValue() : newest);
            for (int i = from; i < all.size() && out.size() < ImapMailSource.MAX_PER_POLL; i++) {
                Map.Entry<Integer, String> e = all.get(i);
                p.ok("TOP " + e.getKey() + " 0", "Fetch failed");
                Map<String, String> h = ImapMailSource.headers(new String(p.multi(), StandardCharsets.UTF_8));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("id", e.getValue());
                m.put("uid", e.getValue());
                m.put("from", h.getOrDefault("from", ""));
                m.put("to", h.getOrDefault("to", ""));
                m.put("subject", h.getOrDefault("subject", ""));
                m.put("date", h.getOrDefault("date", ""));
                m.put("message_id", h.getOrDefault("message-id", ""));
                m.put("body_preview", "");
                m.put("folder", "INBOX");
                m.put("has_attachments", h.getOrDefault("content-type", "").toLowerCase(Locale.ROOT).startsWith("multipart/mixed"));
                out.add(m);
                last = e.getValue();
            }
            return new Batch(last == null ? newest : last, out);
        }
    }

    @Override public List<Attachment> attachments(Map<String, Object> message) throws IOException {
        try (Session p = open()) {
            String want = String.valueOf(message.get("uid"));
            for (Map.Entry<Integer, String> e : uidl(p).entrySet()) {
                if (!e.getValue().equals(want)) continue;
                p.ok("RETR " + e.getKey(), "Fetch failed");
                return ImapMailSource.attachmentsOf(p.multi());
            }
            return List.of();
        }
    }
}
