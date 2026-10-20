package com.oneofficeautomation.controlroom;

import javax.net.SocketFactory;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.Charset;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * A minimal IMAP4rev1 client for email triggers (no third-party library): LOGIN, SELECT, UID SEARCH and UID FETCH of
 * a few headers and the start of the text. The cursor is "uidvalidity:last uid"; a new UIDVALIDITY starts over.
 */
final class ImapMailSource implements MailSource {
    static final int MAX_PER_POLL = 25, PREVIEW_BYTES = 8192;
    private final String host, user, password, folder;
    private final int port;
    private final boolean ssl;
    private final int timeoutMs;

    ImapMailSource(String host, int port, boolean ssl, String user, String password, String folder, int timeoutMs) {
        this.host = host; this.port = port; this.ssl = ssl; this.user = user; this.password = password; this.folder = folder; this.timeoutMs = timeoutMs;
    }

    @Override public Batch fetch(String cursor, boolean fireExisting) throws IOException {
        SocketFactory f = ssl ? SSLSocketFactory.getDefault() : SocketFactory.getDefault();
        try (Socket s = f.createSocket()) {
            s.connect(new InetSocketAddress(host, port), timeoutMs);
            s.setSoTimeout(timeoutMs);
            Conn c = new Conn(new BufferedInputStream(s.getInputStream()), s.getOutputStream());
            String greet = c.line();
            if (greet == null || !greet.startsWith("* OK")) throw new IOException("Not an IMAP server: " + greet);
            c.ok("LOGIN " + quote(user) + " " + quote(password), "Sign-in to the mailbox failed");
            Resp sel = c.ok("SELECT " + quote(folder), "Mailbox folder '" + folder + "' cannot be opened");
            long validity = num(sel, "UIDVALIDITY"), next = num(sel, "UIDNEXT");
            long last;
            String[] cur = cursor == null ? null : cursor.split(":");
            if (cur != null && cur.length == 2 && cur[0].equals(String.valueOf(validity))) last = Long.parseLong(cur[1]);
            else {
                long newest = next > 0 ? next - 1 : maxUid(c.ok("UID SEARCH ALL", "Search failed"));
                if (!fireExisting) { c.logout(); return new Batch(validity + ":" + newest, List.of()); }
                last = Math.max(0, newest - 5);
            }
            List<Long> uids = new ArrayList<>();
            for (Untagged u : c.ok("UID SEARCH UID " + (last + 1) + ":*", "Search failed").untagged)
                if (u.text.startsWith("* SEARCH")) for (String t : u.text.substring(8).trim().split("\\s+")) if (!t.isBlank()) { long n = Long.parseLong(t); if (n > last) uids.add(n); }
            Collections.sort(uids);
            if (uids.size() > MAX_PER_POLL) uids = uids.subList(0, MAX_PER_POLL);
            List<Map<String, Object>> out = new ArrayList<>();
            long newest = last;
            for (long uid : uids) {
                Resp r = c.ok("UID FETCH " + uid + " (UID BODY.PEEK[HEADER.FIELDS (FROM TO SUBJECT DATE MESSAGE-ID CONTENT-TYPE CONTENT-TRANSFER-ENCODING)] BODY.PEEK[TEXT]<0." + PREVIEW_BYTES + ">)", "Fetch failed");
                for (Untagged u : r.untagged) if (u.text.contains("FETCH") && !u.literals.isEmpty()) out.add(message(uid, u));
                newest = Math.max(newest, uid);
            }
            c.logout();
            return new Batch(validity + ":" + newest, out);
        }
    }

    Map<String, Object> message(long uid, Untagged u) {
        byte[] header = null, text = null;
        for (int i = 0; i < u.literals.size(); i++) {
            String before = u.before.get(i).toUpperCase(Locale.ROOT);
            if (before.contains("HEADER")) header = u.literals.get(i); else if (before.contains("TEXT")) text = u.literals.get(i);
        }
        Map<String, String> h = headers(header == null ? "" : new String(header, StandardCharsets.UTF_8));
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("id", String.valueOf(uid));
        m.put("uid", uid);
        m.put("from", h.getOrDefault("from", ""));
        m.put("to", h.getOrDefault("to", ""));
        m.put("subject", h.getOrDefault("subject", ""));
        m.put("date", h.getOrDefault("date", ""));
        m.put("message_id", h.getOrDefault("message-id", ""));
        m.put("body_preview", preview(h, text == null ? new byte[0] : text));
        m.put("folder", folder);
        return m;
    }

    // ---------- MIME helpers ----------
    static Map<String, String> headers(String raw) {
        Map<String, String> h = new LinkedHashMap<>();
        String unfolded = raw.replaceAll("\r?\n[ \t]+", " ");
        for (String line : unfolded.split("\r?\n")) {
            int i = line.indexOf(':');
            if (i > 0) h.putIfAbsent(line.substring(0, i).trim().toLowerCase(Locale.ROOT), decodeWords(line.substring(i + 1).trim()));
        }
        return h;
    }

    private static final Pattern WORD = Pattern.compile("=\\?([^?]+)\\?([BbQq])\\?([^?]*)\\?=");

    /** Decodes RFC 2047 encoded words such as =?UTF-8?B?...?= in headers. */
    static String decodeWords(String v) {
        Matcher m = WORD.matcher(v.replaceAll("\\?=\\s+=\\?", "?==?"));
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String text;
            try {
                Charset cs = Charset.forName(m.group(1));
                byte[] b = m.group(2).equalsIgnoreCase("B") ? Base64.getMimeDecoder().decode(m.group(3)) : qp(m.group(3).replace('_', ' '));
                text = new String(b, cs);
            } catch (RuntimeException e) { text = m.group(); }
            m.appendReplacement(sb, Matcher.quoteReplacement(text));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    static byte[] qp(String s) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (c == '=' && i + 1 < s.length()) {
                if (s.charAt(i + 1) == '\r' || s.charAt(i + 1) == '\n') { i += s.charAt(i + 1) == '\r' && i + 2 < s.length() && s.charAt(i + 2) == '\n' ? 2 : 1; continue; }   // soft line break
                if (i + 2 < s.length()) {
                    try { out.write(Integer.parseInt(s.substring(i + 1, i + 3), 16)); i += 2; continue; } catch (NumberFormatException ignored) { }
                }
            }
            out.write(c > 255 ? '?' : c);
        }
        return out.toByteArray();
    }

    /** Plain text of the message start: the first text part of a multipart message, decoded, at most 1000 characters. */
    static String preview(Map<String, String> h, byte[] body) {
        String ct = h.getOrDefault("content-type", "text/plain"), cte = h.getOrDefault("content-transfer-encoding", "");
        String text = new String(body, StandardCharsets.ISO_8859_1);
        Matcher b = Pattern.compile("boundary=\"?([^\";]+)\"?", Pattern.CASE_INSENSITIVE).matcher(ct);
        if (ct.toLowerCase(Locale.ROOT).startsWith("multipart") && b.find()) {
            for (String part : text.split("--" + Pattern.quote(b.group(1)))) {
                int split = part.indexOf("\r\n\r\n");
                if (split < 0) split = part.indexOf("\n\n");
                if (split < 0) continue;
                Map<String, String> ph = headers(part.substring(0, split));
                if (ph.getOrDefault("content-type", "text/plain").toLowerCase(Locale.ROOT).startsWith("text/plain")) {
                    ct = ph.getOrDefault("content-type", "text/plain"); cte = ph.getOrDefault("content-transfer-encoding", "");
                    text = part.substring(split).trim();
                    break;
                }
            }
        }
        byte[] raw = text.getBytes(StandardCharsets.ISO_8859_1);
        try {
            if (cte.equalsIgnoreCase("base64")) { String s = text.replaceAll("[^A-Za-z0-9+/=]", ""); raw = Base64.getDecoder().decode(s.substring(0, s.length() - s.length() % 4)); }
            else if (cte.equalsIgnoreCase("quoted-printable")) raw = qp(text);
        } catch (RuntimeException ignored) { }
        Matcher cs = Pattern.compile("charset=\"?([A-Za-z0-9_.:-]+)", Pattern.CASE_INSENSITIVE).matcher(ct);
        Charset charset = StandardCharsets.UTF_8;
        try { if (cs.find()) charset = Charset.forName(cs.group(1)); } catch (RuntimeException ignored) { }
        String out = new String(raw, charset).replaceAll("\\s+", " ").trim();
        return out.length() > 1000 ? out.substring(0, 1000) : out;
    }

    // ---------- protocol ----------
    static String quote(String s) { return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\""; }

    static long num(Resp r, String key) {
        Pattern p = Pattern.compile("\\[" + key + " (\\d+)\\]");
        for (Untagged u : r.untagged) { Matcher m = p.matcher(u.text); if (m.find()) return Long.parseLong(m.group(1)); }
        return -1;
    }

    static long maxUid(Resp r) {
        long max = 0;
        for (Untagged u : r.untagged) if (u.text.startsWith("* SEARCH")) for (String t : u.text.substring(8).trim().split("\\s+")) if (!t.isBlank()) max = Math.max(max, Long.parseLong(t));
        return max;
    }

    /** An untagged response; literals are kept apart, each with the text that came before it. */
    static final class Untagged { String text = ""; final List<byte[]> literals = new ArrayList<>(); final List<String> before = new ArrayList<>(); }
    record Resp(String status, List<Untagged> untagged) {}

    static final class Conn {
        private final InputStream in; private final OutputStream out; private int tag;
        Conn(InputStream in, OutputStream out) { this.in = in; this.out = out; }

        String line() throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) { if (c == '\n') break; if (c != '\r') b.write(c); }
            if (c == -1 && b.size() == 0) return null;
            return b.toString(StandardCharsets.UTF_8);
        }

        Resp command(String cmd) throws IOException {
            String t = "A" + (++tag);
            out.write((t + " " + cmd + "\r\n").getBytes(StandardCharsets.UTF_8));
            out.flush();
            List<Untagged> untagged = new ArrayList<>();
            Untagged cur = null;
            while (true) {
                String l = line();
                if (l == null) throw new IOException("The IMAP server closed the connection");
                if (cur == null) {
                    if (l.startsWith(t + " ")) return new Resp(l.substring(t.length() + 1), untagged);
                    cur = new Untagged();
                    untagged.add(cur);
                }
                cur.text += l;
                Matcher m = Pattern.compile("\\{(\\d+)\\}$").matcher(l);
                if (m.find()) {
                    byte[] lit = in.readNBytes(Integer.parseInt(m.group(1)));
                    cur.before.add(l);   // the line that announced this literal names its item (HEADER, TEXT)
                    cur.literals.add(lit);
                } else cur = null;
            }
        }

        Resp ok(String cmd, String failure) throws IOException {
            Resp r = command(cmd);
            if (!r.status().toUpperCase(Locale.ROOT).startsWith("OK")) throw new IOException(failure + ": " + r.status());
            return r;
        }

        void logout() { try { command("LOGOUT"); } catch (IOException ignored) { } }
    }
}
