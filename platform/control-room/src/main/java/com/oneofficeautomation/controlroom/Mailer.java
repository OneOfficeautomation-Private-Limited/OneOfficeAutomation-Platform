package com.oneofficeautomation.controlroom;

import com.oneofficeautomation.sdk.OoaException;

import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;
import java.io.*;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/**
 * E-mail sent by the Command Center itself (licence expiry, workflow steps), through the company's SMTP server. A small
 * SMTP client with no third-party library: EHLO, STARTTLS or implicit TLS, AUTH PLAIN/LOGIN, one plain-text message.
 * Settings: mail_smtp_host, mail_smtp_port (587), mail_smtp_security (starttls, ssl or none), mail_smtp_user,
 * mail_smtp_password_ref (a vault reference folder/credential/attribute, never the password itself) and mail_from.
 */
final class Mailer {
    static final int TIMEOUT_MS = 20_000;
    private final ControlRoom cr;
    /** Tests use a server without TLS on a random port. */
    SSLSocketFactory tls = (SSLSocketFactory) SSLSocketFactory.getDefault();

    Mailer(ControlRoom cr) { this.cr = cr; }

    boolean configured() { return !str("mail_smtp_host").isEmpty() && !str("mail_from").isEmpty(); }

    private String str(String name) { Object v = cr.platform.setting(name); return v == null ? "" : String.valueOf(v).trim(); }

    /** Sends one message to each address in to (at most 50). Throws OoaException with the server's answer on failure. */
    void send(List<String> to, String subject, String body) {
        if (!configured()) throw new OoaException("MAIL.NOT_SET_UP", "E-mail is not set up", "Fill in the outgoing mail server and sender in Administration > Settings > E-mail");
        List<String> rcpt = new ArrayList<>();
        for (String a : to) if (a != null && !a.isBlank()) rcpt.add(address(a.trim()));
        if (rcpt.isEmpty()) throw new OoaException("MAIL.NO_RECIPIENT", "No one to send the e-mail to");
        if (rcpt.size() > 50) throw new OoaException("MAIL.TOO_MANY", "At most 50 recipients");
        String host = str("mail_smtp_host"), security = str("mail_smtp_security").isEmpty() ? "starttls" : str("mail_smtp_security");
        int port = cr.platform.intSetting("mail_smtp_port");
        String user = str("mail_smtp_user"), ref = str("mail_smtp_password_ref"), from = address(str("mail_from"));
        String password = ref.isEmpty() ? null : cr.systemCredential(ref);
        NetGuard.check(cr, host, "The mail server");
        try (Socket plain = new Socket()) {
            plain.connect(new InetSocketAddress(host, port), TIMEOUT_MS);
            plain.setSoTimeout(TIMEOUT_MS);
            Socket s = security.equals("ssl") ? wrap(plain, host) : plain;
            Conv c = new Conv(s);
            c.expect(220);
            String ehlo = c.cmd("EHLO ooa-command-center", 250);
            if (security.equals("starttls")) {
                if (!ehlo.toUpperCase(Locale.ROOT).contains("STARTTLS")) throw new OoaException("MAIL.NO_TLS", "The mail server does not offer STARTTLS", "Choose SSL, or none only on a trusted network");
                c.cmd("STARTTLS", 220);
                c = new Conv(wrap(s, host));
                ehlo = c.cmd("EHLO ooa-command-center", 250);
            }
            if (!user.isEmpty()) {
                String caps = ehlo.toUpperCase(Locale.ROOT);
                if (caps.contains("PLAIN")) c.cmd("AUTH PLAIN " + b64("\0" + user + "\0" + (password == null ? "" : password)), 235);
                else { c.cmd("AUTH LOGIN", 334); c.cmd(b64(user), 334); c.cmd(b64(password == null ? "" : password), 235); }
            }
            c.cmd("MAIL FROM:<" + from + ">", 250);
            for (String r : rcpt) c.cmd("RCPT TO:<" + r + ">", 250, 251);
            c.cmd("DATA", 354);
            c.cmd(message(from, rcpt, subject, body) + "\r\n.", 250);
            try { c.cmd("QUIT", 221); } catch (RuntimeException ignored) { }
        } catch (IOException e) {
            throw new OoaException("MAIL.FAILED", "Could not reach the mail server " + host + ":" + port + ": " + e.getMessage(), "Check the server, port and security in Administration > Settings > E-mail");
        }
    }

    private Socket wrap(Socket s, String host) throws IOException {
        SSLSocket t = (SSLSocket) tls.createSocket(s, host, s.getPort(), true);
        javax.net.ssl.SSLParameters p = t.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");   // the certificate must name the server
        t.setSSLParameters(p);
        t.startHandshake();
        return t;
    }

    /** Settings > E-mail > Send a test e-mail (administrators): to the given address, or the person's own. */
    Map<String, Object> test(ControlRoom.User by, String to, String ip) {
        cr.require(by, "settings.manage", "send a test e-mail");
        String addr = to;
        if (addr == null || addr.isBlank()) {
            Map<String, Object> r = cr.db.one("SELECT email FROM users WHERE id = ?", by.id());
            addr = r == null || r.get("email") == null ? "" : String.valueOf(r.get("email"));
        }
        if (addr.isBlank()) throw new OoaException("MAIL.NO_RECIPIENT", "Give an address to send the test to", "Or add your own e-mail address to your user");
        try {
            send(List.of(addr), "ONEOFFICEAUTOMATION test e-mail", "This is a test e-mail from the ONEOFFICEAUTOMATION Command Center, sent by " + by.username() + ". E-mail is set up correctly.");
        } catch (OoaException e) {
            cr.audit.record(by.username(), "mail.test", "settings", null, Map.of("to", addr), "failed", ip);
            throw e;
        }
        cr.audit.record(by.username(), "mail.test", "settings", null, Map.of("to", addr), "ok", ip);
        return Map.of("sent", true, "to", addr);
    }

    /** A bare address, refusing anything that could add headers or SMTP commands. */
    static String address(String a) {
        String s = a.contains("<") && a.endsWith(">") ? a.substring(a.indexOf('<') + 1, a.length() - 1) : a;
        if (!s.matches("[^\\s<>@\",;:]+@[A-Za-z0-9.-]+\\.[A-Za-z]{2,}")) throw new OoaException("MAIL.BAD_ADDRESS", "Not an e-mail address: " + a);
        return s;
    }

    static String message(String from, List<String> to, String subject, String body) {
        String subj = subject == null ? "" : subject.replaceAll("[\\r\\n]+", " ").trim();
        StringBuilder m = new StringBuilder();
        m.append("From: ").append(from).append("\r\n");
        m.append("To: ").append(String.join(", ", to)).append("\r\n");
        m.append("Subject: ").append(subj.chars().allMatch(ch -> ch < 128) ? subj : "=?UTF-8?B?" + b64(subj) + "?=").append("\r\n");
        m.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now())).append("\r\n");
        m.append("Message-ID: <").append(UUID.randomUUID()).append("@ooa-command-center>\r\n");
        m.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
        String encoded = Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII)).encodeToString((body == null ? "" : body).getBytes(StandardCharsets.UTF_8));
        m.append(encoded);
        return m.toString();
    }

    private static String b64(String s) { return Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)); }

    /** One SMTP conversation: a command and the reply codes it accepts. */
    private static final class Conv {
        private final BufferedReader in;
        private final Writer out;

        Conv(Socket s) throws IOException {
            in = new BufferedReader(new InputStreamReader(s.getInputStream(), StandardCharsets.UTF_8));
            out = new OutputStreamWriter(s.getOutputStream(), StandardCharsets.UTF_8);
        }

        String cmd(String line, int... ok) throws IOException {
            out.write(line + "\r\n");
            out.flush();
            return expect(ok);
        }

        String expect(int... ok) throws IOException {
            StringBuilder all = new StringBuilder();
            String l;
            do {
                l = in.readLine();
                if (l == null || l.length() < 3) throw new IOException("the connection closed");
                all.append(l).append('\n');
            } while (l.length() > 3 && l.charAt(3) == '-');
            int code = Integer.parseInt(l.substring(0, 3));
            for (int o : ok) if (o == code) return all.toString();
            throw new OoaException("MAIL.REFUSED", "The mail server answered: " + l.trim(), "Check the sender, the account and the recipients");
        }
    }
}
